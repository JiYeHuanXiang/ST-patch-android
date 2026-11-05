# SillyTavern Patch (Android)

> 将 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 移植到 Android 平台。自带内核模式**无需 Termux**，点击即启动；也可经 Termux 接管/迁移/修补/影子化已有的酒馆安装。

本项目是 [JiYeHuanXiang/sillytavern-patch](https://github.com/JiYeHuanXiang/sillytavern-patch) 的衍生作品，旨在尽可能降低移动端游玩门槛。

---

## 概述

我们将完整的 SillyTavern（Node.js 后端 + Web 前端）打包进一个 Android APK 中，并内嵌 Node.js 运行时（ARM64）。打开应用后进入**模式选择页**，按需选择运行方式：既可零依赖地用 app 自带内核启动，也可与 Termux 中已有的酒馆安装互操作（接管、迁移、修补、影子）。

### 五种模式

| 模式 | 入口 | 说明 |
|------|------|------|
| **启动** | `SplashActivity` | app 自带内核（libnode + 内置酒馆源码），免 Termux，等价最早的自动启动流程 |
| **接管** | `TakeoverActivity` | 经 Termux RUN_COMMAND 原地启动其目录下的酒馆，app WebView 打开 `127.0.0.1:8000`，不复制文件，退出即停 |
| **数据迁移** | `PatchActivity` | 把 Termux 原版酒馆的用户数据迁入 app 自带内核 |
| **修补** | `PatchUpgradeActivity` | 用 app 补丁版酒馆覆盖 Termux 原版酒馆的代码，保留已有数据 |
| **影子** | `ShadowActivity` | 把 Termux 酒馆整目录复制进 app（一次性复制），由 app 自带内核运行，与 Termux 解耦 |

> 除「启动」外，其余四种均需 Termux，并要求在 `~/.termux/termux.properties` 中设置 `allow-external-apps = true` 后执行 `termux-reload-settings`。酒馆目录名不限，使用时自动扫描识别。

### 核心特性

- **多模式架构** — 自带内核零依赖启动，或与 Termux 酒馆互操作，同一入口按需选择
- **自包含运行** — 内嵌 Node.js 运行时（ARM64）及全部 npm 依赖
- **原生 WebView** — 全屏沉浸式体验，与浏览器端完全一致的界面
- **完整功能保留** — 支持角色管理、对话、角色卡上传、聊天导出等所有 SillyTavern 特性
- **常驻服务** — 前台服务保证 Node.js 进程在后台稳定运行
- **公共角色卡目录** — 可将角色卡映射到 `Download/SillyTavern/` 下的公有目录，用系统文件管理器直接增删（按版本隔离，详见下文）
- **配置编辑器** — 应用内直接编辑自带内核私有目录的 `config.yaml`
- **无 root 运行** — 全部互操作经 Termux RUN_COMMAND，不依赖 root

---

### 核心机制

| 组件 | 作用 |
|------|------|
| `libnode.so` | 预编译的 Node.js 可执行文件（arm64-v8a 与 x86_64 各一份），存放在 `jniLibs/<abi>/` |
| `assets/sillytavern/app/` | 完整的 SillyTavern 源码（含 `node_modules`） |
| `ExtractAssets` | 迭代式解压，避免 14000+ 文件导致栈溢出 |
| 双保险就绪检测 | 广播 + HTTP 轮询双重机制确保 WebView 正确加载 |
| `TermuxBridge` / `TermuxScripts` | 经 RUN_COMMAND 与 Termux 交互、注入启动脚本 |
| `TakeoverBeacon` / `TakeoverGuardService` | 接管模式信标端口 + 生命周期守卫，app 退出时酒馆跟随停止 |
| `PublicCharactersLinker` | 角色卡公有目录映射，补丁版与原版（影子）严格隔离 |

### 角色卡公共目录

启用后，角色卡被映射到设备公共存储，可用系统文件管理器直接增删（PNG/JSON），递归支持子目录。两个版本各自独立，互不干扰：

| 版本 | 公共目录 |
|------|----------|
| 补丁版（启动模式） | `Download/SillyTavern/patch/characters` |
| 原版（影子模式） | `Download/SillyTavern/shadow/characters` |

> 该功能需 Android 11 以上及「所有文件访问权限」。原版（影子）选项仅在影子副本已创建时出现。关闭时角色卡迁回应用私有目录。目录内放置 `.nomedia` 防止角色卡图片进入相册。

---

## 构建指南

### 前置条件

- Android Studio (推荐 Hedgehog 2023.1.1+)
- JDK 17
- Android SDK (API 34)
- Gradle 8.4 (wrapper 已包含)
- 从上游获取完整的 SillyTavern 源码

### 构建步骤

```bash
# 1. 准备 SillyTavern 资产
#    将 SillyTavern 源码目录放置在项目同级目录下，然后运行：
.\prepare-assets.ps1          # Windows
# bash prepare-assets.sh       # Linux/macOS

# 2. 配置 SDK 路径
cp local.properties.example local.properties
# 编辑 local.properties，填入你的 SDK 路径

# 3. 编译 APK
./gradlew assembleRelease     # Windows: gradlew.bat assembleRelease
```

> **注意**：`prepare-assets` 脚本会从同级目录的 SillyTavern 源码中复制 `server.js`、`src/`、`public/`、`default/` 及 `node_modules/`（清理 `.d.ts`、`.map`、测试目录等冗余内容，但**保留 `@types/`** 以维持 webpack 文件系统缓存，避免每次启动全量重编译）到 `assets/sillytavern/app/`。

### 产物

编译完成后 APK 位于：`app/build/outputs/apk/release/`

---

## 支持设备

- **CPU 架构**：ARM64（`arm64-v8a`）与 x86_64（模拟器调试）
- **最低系统**：Android 8.0 (API 26)
- **推荐系统**：Android 11+ (API 30+)

> x86_64 支持用于在 Android 模拟器中调试；内置 Node.js 运行组件按 ABI 分目录（`jniLibs/<abi>/libnode.so` + `assets/sillytavern/node-bin/<abi>/*.so`），安装时自动选择匹配架构。

---

## 项目结构

```
Android/
├── build.gradle                 # 顶层 Gradle 配置 (AGP 8.1.4, Kotlin 1.9.22)
├── settings.gradle              # 模块声明
├── gradle.properties            # JVM 参数与 AndroidX 配置
├── local.properties.example     # SDK 路径模板
├── prepare-assets.ps1           # Windows 资产准备脚本
├── prepare-assets.sh            # Linux/macOS 资产准备脚本
│
├── app/
│   ├── build.gradle             # 应用模块配置
│   └── src/main/
│       ├── AndroidManifest.xml  # 组件声明（入口 + 五模式 + 服务）
│       ├── jniLibs/
│       │   ├── arm64-v8a/libnode.so   # 预编译 Node.js 运行时 (~50MB)
│       │   └── x86_64/libnode.so      # 同上，x86_64 版（模拟器调试）
│       ├── assets/sillytavern/
│       │   ├── node-bin/<abi>/  # Termux 共享库 (.so，按 ABI 分目录)
│       │   └── app/             # SillyTavern 完整源码 (由 prepare-assets 生成)
│       ├── java/com/st/patch/
│       │   ├── ModeSelectActivity.kt      # 应用入口：模式选择页
│       │   ├── SplashActivity.kt          # 启动模式：自带内核流程
│       │   ├── TakeoverActivity.kt        # 接管模式：经 RUN_COMMAND 接管 Termux 酒馆
│       │   ├── PatchActivity.kt           # 数据迁移：Termux 数据迁入自带内核
│       │   ├── PatchUpgradeActivity.kt    # 修补模式：补丁版代码覆盖 Termux 原版
│       │   ├── ShadowActivity.kt          # 影子模式：复制 Termux 酒馆目录后自带内核运行
│       │   ├── ConfigEditorActivity.kt    # 配置编辑器：编辑自带内核 config.yaml
│       │   ├── WebViewActivity.kt         # SillyTavern WebView 全屏容器
│       │   ├── NodeService.kt             # Node.js 前台服务（进程管理）
│       │   ├── TakeoverGuardService.kt    # 接管守卫前台服务（信标 + 生命周期）
│       │   ├── MainActivity.kt            # 旧入口（保留，不再是启动入口）
│       │   └── common/
│       │       ├── ExtractAssets.java       # 迭代式资产解压器
│       │       ├── FileWrite.kt             # 文件读写工具
│       │       ├── TermuxBridge.kt          # Termux RUN_COMMAND 桥接
│       │       ├── TermuxScripts.kt         # 注入 Termux 的启动脚本
│       │       ├── ConfigPatcher.kt        # config.yaml 修补（端口/路径注入）
│       │       ├── PatchExporter.kt        # 补丁包导出
│       │       ├── TakeoverBeacon.kt       # 接管信标端口探测
│       │       └── PublicCharactersLinker.kt # 角色卡公有目录映射
│       └── res/                 # 资源文件 (布局、主题、图标)
```

---

## 运行组件来源

内置 Node.js 运行组件取自 [Termux](https://packages.termux.dev/apt/termux-main/) 仓库（当前为 Node.js 26.4.0），按 ABI 分目录存放：

| 位置 | 内容 |
|------|------|
| `app/src/main/jniLibs/<abi>/libnode.so` | Termux `nodejs` 包的 `usr/bin/node`，重命名而来（`<abi>` = `arm64-v8a` / `x86_64`） |
| `app/src/main/assets/sillytavern/node-bin/<abi>/*.so` | `libc++`、`c-ares`、`libffi`、`openssl`、`libicu`、`libsqlite`、`zlib` 各包的共享库 |

更新运行组件时，从 Termux 池下载与内置 Node 版本同版、对应架构的 deb 解包提取。注意 bionic 按 `DT_NEEDED` 的字面文件名搜索共享库，因此部分文件需重命名：`libicudata.so.78.3 → libicudata.so.78`、`libsqlite3.so.3.x → libsqlite3.so`、`libz.so.1.x → libz.so.1`（icu 大版本、openssl 3.x 必须与 node 构建时一致）。

---

## 许可

本项目包含两个许可层：

| 组件 | 许可 |
|------|------|
| SillyTavern 核心代码（`server.js`、`src/`、`public/` 等） | **AGPL-3.0** |
| Android 壳代码（`com.st.patch` 包下所有 Kotlin/Java 代码） | **MIT** |

请遵守各自许可条款使用。

---

## 鸣谢

- [SillyTavern](https://github.com/SillyTavern/SillyTavern) — 强大的 AI 聊天前端
- [Termux](https://termux.dev/) — 提供了 Android 上的 Linux 环境生态（`node-bin/` 共享库来源于此）
