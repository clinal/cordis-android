# cordis-plugin-android

Cordis plugin for talking to `cordis-android` through the Android socket.

The Android app injects these variables when it starts a Cordis instance:

- `CORDIS_ANDROID_SOCKET`
- `CORDIS_ANDROID_SOCKET_NAMESPACE`
- `CORDIS_ANDROID_INSTANCE_ID`
- `CORDIS_ANDROID_PROTOCOL`
- `CORDIS_ANDROID_TOKEN`

The plugin exposes `ctx.android`:

```ts
const button = ctx.android.button({
  id: 'hello',
  label: 'Hello',
}, async () => {
  ctx.logger.info('clicked from Android')
})

button.patch({ enabled: false, disabledReason: 'Busy' })
button.dispose()
```

Device control is disabled by default. Enable **Android control** in the
instance settings, restart that instance, and grant cordis-android access in
Shizuku before using it:

```ts
await ctx.android.tap(500, 800)
await ctx.android.swipe(500, 1200, 500, 300, 400)
await ctx.android.key(4) // Android KEYCODE_BACK
const screenshot = await ctx.android.screenshot()
const device = await ctx.android.deviceInfo()
const result = await ctx.android.execute('dumpsys window displays')
```

`screenshot()` returns a chunked base64 PNG so large captures stay below the
Android Binder transaction limit. `deviceInfo()` returns the screen dimensions,
density, and current foreground package when available. `execute()` remains
available for other read-only device queries such as `dumpsys` and `pm list`.

When the instance setting is off, control calls reject with an explicit error.
`execute()` intentionally exposes the Shizuku shell and should only be enabled
for trusted Cordis instances and plugins.

For non-Android development, open this plugin in Cordis WebUI and click the
**调试** button. The dialog lists and triggers registered buttons without running
inside `cordis-android`.
