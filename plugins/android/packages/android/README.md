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

For non-Android development, open this plugin in Cordis WebUI and click the
**调试** button. The dialog lists and triggers registered buttons without running
inside `cordis-android`.
