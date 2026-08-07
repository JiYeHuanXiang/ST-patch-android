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
# Node.js 二进制需手动放置到：
#   Android/app/src/main/assets/sillytavern/node-bin/node
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
for f in server.js package.json package-lock.json webpack.config.js \
         plugins.js post-install.js jsconfig.json index.d.ts config.yaml LICENSE; do
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

# 3. 复制 node_modules（仅 production 依赖）
echo ""
echo "[3/4] 复制 node_modules..."
if [ -d "$PROJECT_ROOT/node_modules" ]; then
    rm -rf "$ASSETS_DIR/app/node_modules"
    cp -r "$PROJECT_ROOT/node_modules" "$ASSETS_DIR/app/"
    
    # 清理不需要的文件以减小体积
    echo "  清理 node_modules 中的冗余文件..."
    find "$ASSETS_DIR/app/node_modules" -type f -name "*.d.ts" -delete 2>/dev/null || true
    find "$ASSETS_DIR/app/node_modules" -type f -name "*.map" -delete 2>/dev/null || true
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
    
    # 删除 @types 包（运行时不需要）
    rm -rf "$ASSETS_DIR/app/node_modules/@types" 2>/dev/null || true
    
    echo "  ✓ node_modules/ (已清理冗余文件)"
else
    echo "  ✗ node_modules/ 不存在！请先运行 npm install"
    exit 1
fi

# 4. 检查 Node.js 二进制
echo ""
echo "[4/4] 检查 Node.js 二进制..."
NODE_BIN="$ASSETS_DIR/node-bin/node"
if [ -f "$NODE_BIN" ]; then
    echo "  ✓ node 二进制已存在"
else
    echo "  ✗ node 二进制缺失！"
    echo ""
    echo "  请从 Termux 获取 arm64 node 二进制并放置到："
    echo "    $NODE_BIN"
    echo ""
    echo "  获取方法："
    echo "    1. 在已安装 Termux 的安卓设备上："
    echo "       pkg install nodejs"
    echo "       cp /data/data/com.termux/files/usr/bin/node /sdcard/Download/node"
    echo "    2. 下载 Termux 的 nodejs 包："
    echo "       https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs/"
    echo "       解压 dpkg -X nodejs_*.deb . 提取 usr/bin/node"
    echo "    3. 或使用 nodejs-mobile 编译的 node："
    echo "       https://github.com/niclas/nodejs-mobile"
    echo ""
    echo "  放置后设置可执行权限："
    echo "    chmod +x $NODE_BIN"
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
    [ -f "$NODE_BIN" ] && du -sh "$ASSETS_DIR/node-bin/" 2>/dev/null || true
fi

echo ""
echo "目录结构："
echo "  assets/sillytavern/"
echo "  ├── node-bin/"
echo "  │   └── node          ← arm64 Node.js 二进制"
echo "  └── app.zip           ← 归档后的 SillyTavern（server.js/src/public/default/node_modules）"
echo ""
echo "现在可以用 Android Studio 或 Gradle 构建 APK："
echo "  cd Android && ./gradlew assembleRelease"
