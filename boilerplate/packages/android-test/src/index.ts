export const name = 'android-test'

interface CordisContext {
  android: {
    button(def: Record<string, unknown>, handler: () => void | Promise<void>): unknown
    toast(content: string, duration?: 'short' | 'long'): Promise<void>
  }
}

export function apply(ctx: CordisContext): void {
  ctx.android.button({
    id: 'android-test.toast.short',
    label: 'Short toast',
    description: 'Shows a short native Android toast.',
  }, () => ctx.android.toast('Hello from Cordis'))

  ctx.android.button({
    id: 'android-test.toast.long',
    label: 'Long toast',
    description: 'Shows a long native Android toast.',
  }, () => ctx.android.toast('This is a long toast from Cordis', 'long'))
}

apply.inject = ['android']

export default apply
