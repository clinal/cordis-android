export const name = 'android-test'

interface ButtonRegistration {
  patch(patch: Record<string, unknown>): void
}

interface CordisContext {
  android: {
    button(def: Record<string, unknown>, handler: () => void | Promise<void>): ButtonRegistration
    deviceInfo(): Promise<{ width?: number; height?: number; density?: number; currentPackage?: string }>
    screenshot(): Promise<{ mimeType: string; base64: string }>
    execute(command: string): Promise<{ stdout: string; stderr: string; exitCode: number }>
    tap(x: number, y: number): Promise<unknown>
    swipe(x1: number, y1: number, x2: number, y2: number, duration?: number): Promise<unknown>
    key(keyCode: number): Promise<unknown>
  }
  logger: {
    info(format: unknown, ...params: unknown[]): void
    warn(format: unknown, ...params: unknown[]): void
  }
}

export function apply(ctx: CordisContext): void {
  let info!: ButtonRegistration
  info = ctx.android.button({
    id: 'android-test.info.run',
    label: 'Device info',
    description: 'Reads screen dimensions, density, and foreground package.',
  }, async () => {
    await report(info, 'Device info', async () => JSON.stringify(await ctx.android.deviceInfo()))
  })

  let screenshot!: ButtonRegistration
  screenshot = ctx.android.button({
    id: 'android-test.screenshot.run',
    label: 'Screenshot',
    description: 'Captures a chunked base64 PNG and reports its encoded size.',
  }, async () => {
    await report(screenshot, 'Screenshot', async () => {
      const result = await ctx.android.screenshot()
      return `${result.mimeType}, ${result.base64.length} base64 chars`
    })
  })

  let execute!: ButtonRegistration
  execute = ctx.android.button({
    id: 'android-test.execute.run',
    label: 'Execute',
    description: 'Runs dumpsys battery through the Shizuku shell.',
  }, async () => {
    await report(execute, 'Execute', async () => {
      const result = await ctx.android.execute('dumpsys battery')
      if (result.exitCode !== 0) throw new Error(result.stderr || `exit code ${result.exitCode}`)
      return result.stdout.trim().replace(/\s+/g, ' ').slice(0, 160)
    })
  })

  ctx.android.button({
    id: 'android-test.tap',
    label: 'Tap screen center',
    description: 'Reads the display size and taps its center.',
  }, async () => {
    const device = await ctx.android.deviceInfo()
    if (!device.width || !device.height) throw new Error('screen size is unavailable')
    await ctx.android.tap(Math.floor(device.width / 2), Math.floor(device.height / 2))
  })

  ctx.android.button({
    id: 'android-test.swipe',
    label: 'Swipe up',
    description: 'Performs a vertical swipe through the center of the screen.',
  }, async () => {
    const device = await ctx.android.deviceInfo()
    if (!device.width || !device.height) throw new Error('screen size is unavailable')
    const x = Math.floor(device.width / 2)
    await ctx.android.swipe(x, Math.floor(device.height * 0.75), x, Math.floor(device.height * 0.25), 400)
  })

  ctx.android.button({
    id: 'android-test.back',
    label: 'Back key',
    description: 'Sends Android KEYCODE_BACK.',
  }, () => ctx.android.key(4).then(() => undefined))
}

async function report(button: ButtonRegistration, label: string, action: () => Promise<string>): Promise<void> {
  button.patch({ label: `${label}…`, description: 'Running…' })
  try {
    const message = await action()
    button.patch({ label: `${label} ✓`, description: message })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    button.patch({ label: `${label} failed`, description: message })
    throw error
  }
}

apply.inject = ['android']

export default apply
