# cordis-android

Android host application for running Cordis inside a proot-managed Nix runtime.

## Direction

- Native Android app built with Kotlin and Jetpack Compose.
- Cordis runs as a Node.js process inside proot, supervised by an Android service.
- Runtime assets are produced by Nix and unpacked into app-private storage.
- Keep `targetSdkVersion 28` initially so the app can execute the unpacked proot,
  Node.js, and runtime scripts from private storage.

## Initial Milestones

1. Create the Android Compose project skeleton. Done: app module, Compose
   console, runtime service, and default instance model are present.
2. Port the proot installer and process supervisor from Koishi Android. Started:
   directory preparation, default Cordis config seeding, and command construction
   are scaffolded; bootstrap extraction and real process packaging are next.
3. Build a minimal Nix bootstrap containing proot-static, busybox, Node.js,
   certificates, and required shell shims.
4. Package a default Cordis instance template with `cordis.yml`.
5. Add Compose screens for instances, runtime logs, settings, and import/export.
6. Validate on Android 10, 11, 14, and 15 devices or emulators.

## References

- https://github.com/koishijs/koishi-android
- https://github.com/cordiverse/cordis
