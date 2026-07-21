# SillyTavern Patch (Android)

> 将 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 完整移植到 Android 平台，**无需 Termux**，点击即启动。

本项目是 [JiYeHuanXiang/sillytavern-patch](https://github.com/JiYeHuanXiang/sillytavern-patch) 的衍生作品，旨在尽可能降低移动端游玩门槛。

---

## 概述

我们将完整的 SillyTavern（Node.js 后端 + Web 前端）打包进一个 Android APK 中。安装后直接打开应用，即可在手机上体验完整的 SillyTavern，无需安装 Termux 或任何额外环境。

### 核心特性

- **一键启动** — 安装后打开即用，自动完成服务部署
- **自包含运行** — 内嵌 Node.js 运行时（ARM64）及全部 npm 依赖
- **原生 WebView** — 全屏沉浸式体验，与浏览器端完全一致的界面
- **完整功能保留** — 支持角色管理、对话、角色卡上传、聊天导出等所有 SillyTavern 特性
- **常驻服务** — 前台服务保证 Node.js 进程在后台稳定运行

---

### 核心机制

| 组件 | 作用 |
|------|------|
| `libnode.so` | 预编译的 ARM64 Node.js 可执行文件，存放在 `jniLibs/` |
| `assets/sillytavern/app/` | 完整的 SillyTavern 源码（含 `node_modules`） |
| `ExtractAssets` | 迭代式解压，避免 14000+ 文件导致栈溢出 |
| 双保险就绪检测 | 广播 + HTTP 轮询双重机制确保 WebView 正确加载 |

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

> **注意**：`prepare-assets` 脚本会从同级目录的 SillyTavern 源码中复制 `server.js`、`src/`、`public/`、`default/` 及 `node_modules/`（自动清理 `.d.ts`、`.map`、测试文件等冗余内容）到 `assets/sillytavern/app/`。

### 产物

编译完成后 APK 位于：`app/build/outputs/apk/release/`

---

## 支持设备

- **CPU 架构**：仅 ARM64（`arm64-v8a`）
- **最低系统**：Android 8.0 (API 26)
- **推荐系统**：Android 11+ (API 30+)

> 非 arm64-v8a 设备（如 x86 模拟器）无法运行，因 `libnode.so` 仅预编译了 ARM64 版本。

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
│       ├── AndroidManifest.xml  # 四大组件声明
│       ├── jniLibs/arm64-v8a/
│       │   └── libnode.so       # 预编译 Node.js 运行时 (~50MB)
│       ├── assets/sillytavern/
│       │   ├── node-bin/        # Termux 共享库 (.so)
│       │   └── app/             # SillyTavern 完整源码 (由 prepare-assets 生成)
│       ├── java/com/st/patch/
│       │   ├── SplashActivity.kt    # 启动页
│       │   ├── NodeService.kt       # Node.js 进程管理器 (前台服务)
│       │   ├── WebViewActivity.kt   # SillyTavern WebView 壳
│       │   ├── MainActivity.kt      # 废弃入口 (重定向至 SplashActivity)
│       │   └── common/
│       │       ├── ExtractAssets.java   # 迭代式资产解压器
│       │       └── FileWrite.kt         # 文件读写工具
│       └── res/                 # 资源文件 (布局、主题、图标)
```

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
