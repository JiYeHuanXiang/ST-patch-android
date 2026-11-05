#!/usr/bin/env bash
# ============================================================
# prepare-assets.sh — 准备 Android assets 目录
#
# 将 SillyTavern 源码、node_modules 复制到
# Android/app/src/main/assets/sillytavern/
#
# 使用方式：
#   cd <项目根目录>
#   bash Android/prepare-assets.sh
#
# Node.js 运行组件（按 ABI）需手动放置：
#   Android/app/src/main/jniLibs/<abi>/libnode.so     ← Termux node 二进制重命名
#   Android/app/src/main/assets/sillytavern/node-bin/<abi>/*.so  ← Termux 共享库
#   （abi = arm64-v8a / x86_64；来源与重命名规则见 README「运行组件来源」）
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# 支持自定义 SillyTavern 源码根：SOURCE_ROOT=/path/to/sillytavern bash prepare-assets.sh
if [ -n "${SOURCE_ROOT:-}" ]; then
    PROJECT_ROOT="$(cd "$SOURCE_ROOT" && pwd)"
else
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets/sillytavern"

echo "============================================================"
echo " 准备 Android assets 目录"
echo " 项目根目录: $PROJECT_ROOT"
echo " Assets 目录: $ASSETS_DIR"
echo "============================================================"

# 1. 创建目录结构
echo ""
echo "[1/4] 创建目录结构..."
mkdir -p "$ASSETS_DIR/node-bin"
mkdir -p "$ASSETS_DIR/app"

# 2. 复制 SillyTavern 核心文件
echo ""
echo "[2/4] 复制 SillyTavern 源码..."

# 核心启动文件
# 注意：config.yaml 不打包——它是开发者本地配置，端口可能与 app 硬编码的 8000 不一致
# （如本仓库为 8100）。运行时由 Node 从 default/config.yaml 生成，再由 NodeService.sanitizeConfig 修正。
for f in server.js package.json package-lock.json webpack.config.js \
         plugins.js post-install.js jsconfig.json index.d.ts LICENSE; do
    if [ -f "$PROJECT_ROOT/$f" ]; then
        cp "$PROJECT_ROOT/$f" "$ASSETS_DIR/app/"
        echo "  ✓ $f"
    fi
done

# 核心目录
for d in src public default; do
    if [ -d "$PROJECT_ROOT/$d" ]; then
        # 删除旧副本
        rm -rf "$ASSETS_DIR/app/$d"
        cp -r "$PROJECT_ROOT/$d" "$ASSETS_DIR/app/"
        echo "  ✓ $d/"
    fi
done

# 补丁版自描述标记：原版与补丁版酒馆的特征文件完全相同（server.js + package.json），
# Termux 侧脚本（apply.sh 等）靠 .st-patch-build 区分：无标记=原版；有标记=补丁版，
# 版本与当前补丁包不一致=旧补丁（可覆盖升级），一致则跳过。
VERSION_NAME="$(grep -m1 'versionName' "$SCRIPT_DIR/app/build.gradle" 2>/dev/null | sed 's/.*"\([^"]*\)".*/\1/' || true)"
printf '%s\n' "${VERSION_NAME:-unknown}" > "$ASSETS_DIR/app/.st-patch-build"
echo "  ✓ .st-patch-build（${VERSION_NAME:-unknown}）"

# 3. 复制 node_modules（仅 production 依赖）
echo ""
echo "[3/4] 复制 node_modules..."
if [ -d "$PROJECT_ROOT/node_modules" ]; then
    rm -rf "$ASSETS_DIR/app/node_modules"
    cp -r "$PROJECT_ROOT/node_modules" "$ASSETS_DIR/app/"
    
    # 清理不需要的文件以减小体积
    echo "  清理 node_modules 中的冗余文件..."
    # 注意：*.d.ts 清理须跳过 @types/（其内容全部是 .d.ts）。
    # @types/ 本身必须保留：webpack filesystem 缓存的依赖快照需要解析它，
    # 删除会导致每次启动全量重编译（实测 ~20s；保留后命中缓存约 2s）。
    find "$ASSETS_DIR/app/node_modules" -type f -name "*.d.ts" -not -path "*/@types/*" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name "*.map" -not -path "*/@types/*" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type d -name ".github" -exec rm -rf {} + 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type d -name "test" -exec rm -rf {} + 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type d -name "tests" -exec rm -rf {} + 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type d -name "__tests__" -exec rm -rf {} + 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type d -name "docs" -exec rm -rf {} + 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name "CHANGELOG*" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name "HISTORY*" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name "README*" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name "LICENSE*" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name ".npmignore" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name "Makefile" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name "tsconfig.json" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name ".eslintrc*" -delete 2>/dev/null || true
    
    # @types/ 保留（原因见上方 *.d.ts 清理处的注释）
    
    echo "  ✓ node_modules/ (已清理冗余文件)"
else
    echo "  ✗ node_modules/ 不存在！请先运行 npm install"
    exit 1
fi

# 4. 检查 Node.js 运行组件（按 ABI 分目录：arm64-v8a / x86_64）
echo ""
echo "[4/4] 检查 Node.js 运行组件（按 ABI）..."
REQUIRED_LIBS="libc++_shared.so libcares.so libcrypto.so.3 libffi.so libicudata.so.78 libicui18n.so.78 libicuuc.so.78 libsqlite3.so libssl.so.3 libz.so.1"
MISSING=0
for abi in arm64-v8a x86_64; do
    NODE_SO="$SCRIPT_DIR/app/src/main/jniLibs/$abi/libnode.so"
    if [ -f "$NODE_SO" ]; then
        echo "  ✓ jniLibs/$abi/libnode.so"
    else
        echo "  ✗ jniLibs/$abi/libnode.so 缺失"
        MISSING=1
    fi
    for lib in $REQUIRED_LIBS; do
        if [ -f "$ASSETS_DIR/node-bin/$abi/$lib" ]; then
            echo "  ✓ node-bin/$abi/$lib"
        else
            echo "  ✗ node-bin/$abi/$lib 缺失"
            MISSING=1
        fi
    done
done
if [ "$MISSING" = "1" ]; then
    echo ""
    echo "  获取方法：从 Termux 仓库下载对应架构的 deb 并解包："
    echo "    https://packages.termux.dev/apt/termux-main/pool/main/"
    echo "  nodejs 的 usr/bin/node 重命名为 libnode.so 放入 jniLibs/<abi>/；"
    echo "  各依赖库放入 assets/sillytavern/node-bin/<abi>/"
    echo "  （icu/sqlite/zlib 文件名需按 DT_NEEDED 重命名，规则见 README「运行组件来源」）"
fi

# 5. 把 app/ 打成 app.zip（运行时单流解压，避免 14000+ 次 AssetManager 查找）
echo ""
echo "[5/5] 把 app/ 归档为 app.zip（加速运行时解压）..."

APP_DIR="$ASSETS_DIR/app"
APP_ZIP="$ASSETS_DIR/app.zip"

if [ ! -d "$APP_DIR" ]; then
    echo "  错误：$APP_DIR 不存在，无法归档。" >&2
    exit 1
fi

rm -f "$APP_ZIP"
# 用 Python 生成标准 zip（macOS/Linux 一般自带 python3）；回退到 zip 命令
if command -v python3 &> /dev/null; then
    (cd "$APP_DIR" && python3 - "$APP_ZIP" <<'PYEOF'
import sys, zipfile, os
out = sys.argv[1]
# 保留 app/ 前缀，使条目为 app/server.js 等；解压到 sillytavern/ 即得 sillytavern/app/server.js
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk('.'):
        dirs.sort(); files.sort()
        for name in dirs:
            full = os.path.join(root, name)
            # 相对 app/ 的路径（不加 app/ 前缀）；解压器解到 .app_tmp/ 后 rename 为 app/
            arc = os.path.relpath(full, '.') + '/'
            zf.write(full, arc)
        for name in files:
            full = os.path.join(root, name)
            arc = os.path.relpath(full, '.')
            zf.write(full, arc)
PYEOF
)
elif command -v zip &> /dev/null; then
    (cd "$APP_DIR" && zip -r -q "$APP_ZIP" .)
else
    echo "  错误：需要 python3 或 zip 命令来生成 app.zip" >&2
    exit 1
fi

ZIP_SIZE=$(du -h "$APP_ZIP" | cut -f1)
ZIP_ENTRIES=$(python3 -c "import zipfile; print(len(zipfile.ZipFile('$APP_ZIP').namelist()))" 2>/dev/null || echo "?")
echo "  + app.zip ($ZIP_SIZE, $ZIP_ENTRIES 条目)"

# 归档完成后删除散件 app/（node-bin/ 保持散件）
rm -rf "$APP_DIR"
echo "  已删除散件 app/ 目录"

# 汇总
echo ""
echo "============================================================"
echo " Assets 准备完成！"
echo "============================================================"

# 显示目录大小
if command -v du &> /dev/null; then
    echo ""
    echo "目录大小："
    du -sh "$ASSETS_DIR" 2>/dev/null || true
    du -sh "$ASSETS_DIR/app/" 2>/dev/null || true
    du -sh "$ASSETS_DIR/app/node_modules/" 2>/dev/null || true
    du -sh "$ASSETS_DIR/node-bin/" 2>/dev/null || true
fi

echo ""
echo "目录结构："
echo "  app/src/main/"
echo "  ├── jniLibs/arm64-v8a/libnode.so   ← arm64 Node.js（libnode.so = node 重命名）"
echo "  ├── jniLibs/x86_64/libnode.so      ← x86_64 Node.js（模拟器调试用）"
echo "  └── assets/sillytavern/"
echo "      ├── node-bin/arm64-v8a/*.so    ← arm64 共享库依赖"
echo "      ├── node-bin/x86_64/*.so       ← x86_64 共享库依赖"
echo "      └── app.zip                    ← 归档后的 SillyTavern（与架构无关）"
echo ""
echo "现在可以用 Android Studio 或 Gradle 构建 APK："
echo "  cd Android && ./gradlew assembleRelease"
