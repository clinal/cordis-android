# Cordis for Android

[English](README.md) | [简体中文](README.zh-CN.md)

Run [Cordis](https://github.com/cordiverse/cordis) projects locally on Android. The native Kotlin app manages Cordis instances while Node.js runs inside an app-private, proot-based Nix environment.

## Features

- Create and manage multiple Cordis instances.
- Start and stop each runtime from a Jetpack Compose interface.
- Open a global shell or an instance-specific terminal.
- View a Cordis web console inside the app.
- Configure ports, DNS, startup commands, and optional Android control integration.
- Expose plugin actions through the Android bridge and pin them as home shortcuts.
- Install the bundled Cordis boilerplate or import a custom ZIP package.

## Requirements

- Android 9 (API 28) or newer.
- An `arm64-v8a` device for the default bootstrap and boilerplate assets.
- [Nix](https://nixos.org/) with flakes enabled for reproducible local builds.

The app intentionally targets Android API 28 so the unpacked proot runtime, Node.js, and scripts can execute from app-private storage.

## Build

Build the ARM64 runtime assets first:

```shell
nix build ./bootstrap
```

Then build an APK in the provided development environment:

```shell
nix develop --command gradle \
  -PcordisBootstrapAssetsDir=result \
  assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The `cordisBootstrapAssetsDir` property is optional. Omitting it produces a smaller developer build without a bundled proot runtime; that build cannot start Cordis unless compatible runtime files are already installed in the app data directory.

## Test

Run JVM unit tests:

```shell
nix develop --command gradle testDebugUnitTest
```

Run the Compose end-to-end tests in the Nix-managed x86_64 emulator:

```shell
nix build ./bootstrap#x86_64 -o result
nix run .#emulated-connected-android-test -- \
  -PcordisBootstrapAssetsDir=result
```

The emulator test requires Linux with KVM access.

## Runtime packaging

`bootstrap/` builds the minimal Linux userspace shipped with the app, including proot, BusyBox, Node.js, CA certificates, and shell shims. Its output exposes Android assets under `result/assets`:

- `bootstrap/bootstrap.zip` — the root filesystem.
- `bootstrap/env.txt` — runtime environment metadata used for install and upgrade detection.
- `bootstrap/boilerplate.zip` — the default Cordis project template (currently boilerplate v0.6.1 with Node.js 24).

At first launch, the app extracts these assets into private storage. Instances live in separate home directories and are supervised by an Android service.

## Project layout

| Path | Purpose |
| --- | --- |
| `app/` | Android application, Compose UI, runtime supervisor, bridge, and terminal |
| `bootstrap/` | Nix definitions for the proot runtime assets |
| `boilerplate/` | Cordis workspace and `cordis-plugin-android` source |
| `flake.nix` | Android development shell and emulator test runner |
| `.github/workflows/` | APK build, end-to-end test, and release automation |
