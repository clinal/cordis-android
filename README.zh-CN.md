# Cordis for Android

[English](README.md) | [简体中文](README.zh-CN.md)

在 Android 设备上本地运行 [Cordis](https://github.com/cordiverse/cordis) 项目。原生 Kotlin 应用负责管理 Cordis 实例，Node.js 则运行在应用私有目录中的 Nix proot 环境内。

## 功能

- 创建和管理多个 Cordis 实例。
- 通过 Jetpack Compose 界面启动和停止各个运行时。
- 打开全局 Shell 或实例专属终端。
- 在应用内查看 Cordis Web 控制台。
- 配置端口、DNS、启动命令和可选的 Android 控制集成。
- 通过 Android Bridge 暴露插件操作，并将其固定为首页快捷操作。
- 安装内置的 Cordis 模板，或导入自定义 ZIP 包。

## 运行要求

- Android 9（API 28）或更高版本。
- 默认引导环境和模板资源需要 `arm64-v8a` 设备。
- 如需进行可复现的本地构建，需要启用 Flakes 的 [Nix](https://nixos.org/)。

应用有意将目标版本设为 Android API 28，以便从应用私有目录执行解压后的 proot 运行时、Node.js 和相关脚本。

## 构建

首先构建 ARM64 运行时资源：

```shell
nix build ./bootstrap
```

然后在项目提供的开发环境中构建 APK：

```shell
nix develop --command gradle \
  -PcordisBootstrapAssetsDir=result \
  assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

`cordisBootstrapAssetsDir` 属性为可选项。省略该属性会生成不包含 proot 运行时的轻量开发版本；除非应用数据目录中已经安装了兼容的运行时文件，否则该版本无法启动 Cordis。

## 测试

运行 JVM 单元测试：

```shell
nix develop --command gradle testDebugUnitTest
```

在 Nix 管理的 x86_64 模拟器中运行 Compose 端到端测试：

```shell
nix build ./bootstrap#x86_64 -o result
nix run .#emulated-connected-android-test -- \
  -PcordisBootstrapAssetsDir=result
```

模拟器测试需要 Linux 环境及 KVM 访问权限。

## 运行时打包

`bootstrap/` 用于构建应用内置的最小 Linux 用户空间，其中包含 proot、BusyBox、Node.js、CA 证书和 Shell 兼容脚本。构建结果会在 `result/assets` 中提供以下 Android 资源：

- `bootstrap/bootstrap.zip`：根文件系统。
- `bootstrap/env.txt`：用于安装及升级检测的运行时环境元数据。
- `bootstrap/boilerplate.zip`：默认 Cordis 项目模板（当前为使用 Node.js 24 的 boilerplate v0.6.1）。

首次启动时，应用会将这些资源解压到私有目录。每个实例使用独立的 Home 目录，并由 Android 服务负责监控。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 应用、Compose 界面、运行时管理器、Bridge 和终端 |
| `bootstrap/` | 用于生成 proot 运行时资源的 Nix 定义 |
| `boilerplate/` | Cordis 工作区和 `cordis-plugin-android` 源码 |
| `flake.nix` | Android 开发环境和模拟器测试运行器 |
| `.github/workflows/` | APK 构建、端到端测试和发布自动化 |
