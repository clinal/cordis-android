# cordis-android

Android host application for running Cordis inside a proot-managed Nix runtime.

## Direction

- Native Android app built with Kotlin and Jetpack Compose.
- Cordis runs as a Node.js process inside proot, supervised by an Android service.
- Runtime assets are produced by Nix and unpacked into app-private storage.
  Default instances can be seeded from the Cordis boilerplate release, matching
  the Koishi Android approach of shipping a prepared runtime template.
- Keep `targetSdkVersion 28` initially so the app can execute the unpacked proot,
  Node.js, and runtime scripts from private storage.

## Initial Milestones

1. Create the Android Compose project skeleton. Done: app module, Compose
   console, runtime service, and default instance model are present.
2. Port the proot installer and process supervisor from Koishi Android. Started:
   directory preparation, Cordis boilerplate seeding, and command construction
   are scaffolded; bootstrap extraction and real process packaging are next.
3. Build a minimal Nix bootstrap containing proot-static, busybox, Node.js,
   certificates, and required shell shims. Done: the bootstrap flake follows
   Koishi Android's pinned nix-on-droid/proot packaging path and emits
   `bootstrap.zip` plus `env.txt`.
4. Package a default Cordis instance template from
   `cordiverse/boilerplate@v0.6.0`:
   `boilerplate-v0.6.0-linux-arm64-node24.zip`
   (`sha256:e415d4fa689e1792be1ccf59e6c6ce32f88b9e7f05ab2b3c4db2638de98c8b74`).
5. Add Compose screens for instances, runtime logs, settings, and import/export.
6. Validate on Android 10, 11, 14, and 15 devices or emulators.

## References

- https://github.com/koishijs/koishi-android
- https://github.com/cordiverse/cordis
- https://github.com/cordiverse/boilerplate/releases/tag/v0.6.0

## Bootstrap Assets

Build the bootstrap package with:

```bash
nix build ./bootstrap
```

Then package those generated assets into the Android app with:

```bash
nix develop --command gradle \
  -PcordisBootstrapAssetsDir="$(readlink -f result)" \
  assembleDebug
```

Without `cordisBootstrapAssetsDir`, the app skips bundled runtime assets so
development builds stay small.
