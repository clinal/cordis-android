import type { Context as BaseContext } from 'cordis'
import type WebUI from '@cordisjs/plugin-webui/base'
import type { Entry } from '@cordisjs/plugin-webui/base'
import { Socket, createConnection } from 'node:net'

declare module 'cordis' {
  interface Context {
    android: AndroidBridge
  }
}

export const name = 'android'

const PROTOCOL = 'cordis.android.bridge.v1'
const DEFAULT_RECONNECT_INTERVAL = 3000

export interface Config {
  reconnectInterval?: number
}

export interface InstanceInfo {
  id: string
}

export interface RuntimeInfo {
  connected: boolean
  protocol: string
}

export interface CommandResult {
  stdout: string
  stderr: string
  exitCode: number
}

export interface Screenshot {
  mimeType: 'image/png'
  base64: string
}

export interface DeviceInfo {
  width?: number
  height?: number
  density?: number
  currentPackage?: string
}

export interface ButtonDef {
  id: string
  label: string
  icon?: string
  description?: string
  enabled?: boolean
  disabledReason?: string
}

export type ButtonPatch = Partial<Omit<ButtonDef, 'id'>>
export type ButtonHandler = () => void | Promise<void>

export interface ButtonRegistration {
  patch(patch: ButtonPatch): void
  dispose(): void
}

export type OpenTarget =
  | { type: 'url'; url: string }
  | { type: 'route'; route: string; params?: Record<string, unknown> }
  | { type: 'activity'; action: string; extras?: Record<string, unknown> }

export interface NotificationOptions {
  title: string
  content?: string
}

export interface NotificationResult {
  id?: string
}

type JsonRpcId = string | number

type CordisContext = BaseContext & {
  logger: {
    info(format: unknown, ...params: unknown[]): void
    warn(format: unknown, ...params: unknown[]): void
  }
  effect(effect: () => () => void, label?: string): () => unknown
  inject(deps: string[], callback: (ctx: BaseContext & { webui: WebUI }) => void | (() => void)): unknown
  provide(name: string, value: unknown): () => unknown
}

interface DebugData {
  buttons: ButtonDef[]
  trigger(buttonId: string): Promise<void>
}

interface JsonRpcRequest {
  jsonrpc: '2.0'
  id?: JsonRpcId
  method: string
  params?: unknown
}

interface JsonRpcResponse {
  jsonrpc: '2.0'
  id: JsonRpcId
  result?: unknown
  error?: { code: number; message: string }
}

interface PendingRequest {
  resolve(value: unknown): void
  reject(error: Error): void
}

export class AndroidBridge {
  private readonly buttons = new Map<string, RegisteredButton>()
  private readonly pending = new Map<JsonRpcId, PendingRequest>()
  private readonly reconnectInterval: number
  private socket: Socket | undefined
  private buffer = ''
  private requestId = 0
  private reconnectTimer: NodeJS.Timeout | undefined
  private debugEntry: Entry<DebugData> | undefined
  private connected = false

  constructor(private readonly ctx: CordisContext, private config: Config = {}) {
    this.reconnectInterval = config.reconnectInterval ?? DEFAULT_RECONNECT_INTERVAL
    this.connect()
    this.setupDebugUI()
  }

  instance(): Promise<InstanceInfo> {
    return Promise.resolve({
      id: process.env.CORDIS_ANDROID_INSTANCE_ID || 'unknown',
    })
  }

  runtime(): Promise<RuntimeInfo> {
    return Promise.resolve({
      connected: this.connected,
      protocol: PROTOCOL,
    })
  }

  execute(command: string): Promise<CommandResult> {
    if (process.env.CORDIS_ANDROID_CONTROL_ENABLED !== 'true') {
      return Promise.reject(new Error('Android control is not enabled for this Cordis instance'))
    }
    if (!command.trim()) return Promise.reject(new Error('Android control command must not be empty'))
    return this.request('control.execute', { command }).then(result => result as CommandResult)
  }

  tap(x: number, y: number): Promise<CommandResult> {
    return this.execute(`input tap ${integer(x, 'x')} ${integer(y, 'y')}`)
  }

  swipe(x1: number, y1: number, x2: number, y2: number, duration = 300): Promise<CommandResult> {
    return this.execute(
      `input swipe ${integer(x1, 'x1')} ${integer(y1, 'y1')} ${integer(x2, 'x2')} ${integer(y2, 'y2')} ${integer(duration, 'duration')}`,
    )
  }

  key(keyCode: number): Promise<CommandResult> {
    return this.execute(`input keyevent ${integer(keyCode, 'keyCode')}`)
  }

  async screenshot(): Promise<Screenshot> {
    const suffix = `${Date.now()}-${Math.random().toString(16).slice(2)}`
    const path = `/data/local/tmp/cordis-android-${suffix}.png.b64`
    try {
      const capture = await this.execute(`screencap -p | base64 > ${path} && wc -c < ${path}`)
      ensureSuccess(capture, 'capture Android screenshot')
      const size = Number(capture.stdout.trim().split(/\s+/).at(-1))
      if (!Number.isSafeInteger(size) || size <= 0) throw new Error('Android screenshot is empty')

      const chunks: string[] = []
      for (let offset = 0; offset < size; offset += SCREENSHOT_CHUNK_SIZE) {
        const length = Math.min(SCREENSHOT_CHUNK_SIZE, size - offset)
        const chunk = await this.execute(
          `dd if=${path} bs=1 skip=${offset} count=${length} 2>/dev/null`,
        )
        ensureSuccess(chunk, 'read Android screenshot')
        chunks.push(chunk.stdout.replace(/\s/g, ''))
      }
      return { mimeType: 'image/png', base64: chunks.join('') }
    } finally {
      await this.execute(`rm -f ${path}`).catch(() => {})
    }
  }

  async deviceInfo(): Promise<DeviceInfo> {
    const result = await this.execute(
      "wm size; wm density; dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1",
    )
    ensureSuccess(result, 'read Android device information')
    const sizes = [...result.stdout.matchAll(/(?:Physical|Override) size:\s*(\d+)x(\d+)/g)]
    const size = sizes.at(-1)
    const densities = [...result.stdout.matchAll(/(?:Physical|Override) density:\s*(\d+)/g)]
    const density = densities.at(-1)
    const currentPackage = result.stdout.match(/([\w]+(?:\.[\w]+)+)\/[^\s}]+/)?.[1]
    return {
      width: size ? Number(size[1]) : undefined,
      height: size ? Number(size[2]) : undefined,
      density: density ? Number(density[1]) : undefined,
      currentPackage,
    }
  }

  listButtons(): ButtonDef[] {
    return [...this.buttons.values()].map(button => ({ ...button.def }))
  }

  button(def: ButtonDef, handler: ButtonHandler): ButtonRegistration {
    const normalized = normalizeButton(def)
    const current = this.buttons.get(normalized.id)
    current?.dispose(false)

    const registered = new RegisteredButton(this, normalized, handler)
    this.buttons.set(normalized.id, registered)
    this.sendNotification('button.register', { button: normalized })
    this.refreshDebugUI()

    this.ctx.effect(() => () => registered.dispose(), `android.button(${JSON.stringify(normalized.id)})`)
    return registered
  }

  open(target: OpenTarget): Promise<void> {
    return this.request('open', target).then(() => undefined)
  }

  notify(options: NotificationOptions): Promise<NotificationResult> {
    return this.request('notify', options).then(result => (result ?? {}) as NotificationResult)
  }

  async trigger(buttonId: string): Promise<void> {
    const button = this.buttons.get(buttonId)
    if (!button) throw new Error(`Android button is not registered: ${buttonId}`)
    if (button.def.enabled === false) throw new Error(button.def.disabledReason || `Android button is disabled: ${buttonId}`)
    await button.handler()
  }

  updateButton(def: ButtonDef): void {
    this.buttons.set(def.id, new RegisteredButton(this, def, this.buttons.get(def.id)?.handler ?? (() => {})))
    this.refreshDebugUI()
  }

  patchButton(id: string, patch: ButtonPatch): void {
    const button = this.buttons.get(id)
    if (!button) return
    button.def = normalizeButton({ ...button.def, ...patch, id })
    this.sendNotification('button.patch', { id, patch })
    this.refreshDebugUI()
  }

  unregisterButton(id: string, notifyHost = true): void {
    const button = this.buttons.get(id)
    if (!button) return
    this.buttons.delete(id)
    if (notifyHost) this.sendNotification('button.unregister', { id })
    this.refreshDebugUI()
  }

  private connect(): void {
    const socketName = process.env.CORDIS_ANDROID_SOCKET
    if (!socketName) return

    const namespace = process.env.CORDIS_ANDROID_SOCKET_NAMESPACE
    const path = namespace === 'abstract' ? `\0${socketName}` : socketName
    const socket = createConnection({ path })
    this.socket = socket

    socket.setEncoding('utf8')
    socket.on('connect', () => {
      this.hello().catch(error => this.ctx.logger.warn(error))
    })
    socket.on('data', chunk => this.read(String(chunk)))
    socket.on('error', error => this.ctx.logger.warn(error))
    socket.on('close', () => {
      this.connected = false
      this.socket = undefined
      for (const pending of this.pending.values()) pending.reject(new Error('Android bridge socket closed'))
      this.pending.clear()
      this.scheduleReconnect()
    })
  }

  private async hello(): Promise<void> {
    const result = await this.request('hello', {
      protocol: process.env.CORDIS_ANDROID_PROTOCOL || PROTOCOL,
      token: process.env.CORDIS_ANDROID_TOKEN,
      plugin: name,
      version: '0.1.0',
      instanceId: process.env.CORDIS_ANDROID_INSTANCE_ID,
    })
    const response = result as { protocol?: string } | undefined
    if (response?.protocol !== PROTOCOL) throw new Error('Android bridge protocol mismatch')
    this.connected = true
    for (const button of this.buttons.values()) {
      this.sendNotification('button.register', { button: button.def })
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer || !process.env.CORDIS_ANDROID_SOCKET) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined
      this.connect()
    }, this.reconnectInterval)
  }

  private read(chunk: string): void {
    this.buffer += chunk
    while (true) {
      const index = this.buffer.indexOf('\n')
      if (index < 0) return
      const line = this.buffer.slice(0, index).trim()
      this.buffer = this.buffer.slice(index + 1)
      if (!line) continue
      this.handleMessage(JSON.parse(line) as JsonRpcRequest | JsonRpcResponse)
    }
  }

  private handleMessage(message: JsonRpcRequest | JsonRpcResponse): void {
    if ('method' in message) {
      this.handleRequest(message)
      return
    }
    const pending = this.pending.get(message.id)
    if (!pending) return
    this.pending.delete(message.id)
    if (message.error) {
      pending.reject(new Error(message.error.message))
    } else {
      pending.resolve(message.result)
    }
  }

  private handleRequest(request: JsonRpcRequest): void {
    Promise.resolve()
      .then(async () => {
        if (request.method === 'buttons') return this.listButtons()
        if (request.method === 'button.click') {
          const params = request.params as { id?: string } | undefined
          if (!params?.id) throw new JsonRpcError(-32602, 'missing button id')
          await this.trigger(params.id)
          return {}
        }
        throw new JsonRpcError(-32601, 'method not found')
      })
      .then(result => this.respond(request.id, result))
      .catch(error => {
        const code = error instanceof JsonRpcError ? error.code : -32603
        this.respondError(request.id, code, error instanceof Error ? error.message : String(error))
      })
  }

  private request(method: string, params: unknown): Promise<unknown> {
    if (!this.socket) {
      const configured = Boolean(process.env.CORDIS_ANDROID_SOCKET)
      return Promise.reject(new Error(
        `Android bridge socket is unavailable (configured=${configured}, handshake=${this.connected}). ` +
        'Check the Cordis instance log and restart the instance if the plugin has not reconnected.',
      ))
    }
    if (this.socket.destroyed) {
      return Promise.reject(new Error(
        `Android bridge socket was closed (handshake=${this.connected}). Waiting for the plugin to reconnect.`,
      ))
    }
    const id = ++this.requestId
    this.send({ jsonrpc: '2.0', id, method, params })
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }))
  }

  private sendNotification(method: string, params: unknown): void {
    if (!this.socket || this.socket.destroyed) return
    this.send({ jsonrpc: '2.0', method, params })
  }

  private respond(id: JsonRpcId | undefined, result: unknown): void {
    if (id === undefined) return
    this.send({ jsonrpc: '2.0', id, result })
  }

  private respondError(id: JsonRpcId | undefined, code: number, message: string): void {
    if (id === undefined) return
    this.send({ jsonrpc: '2.0', id, error: { code, message } })
  }

  private send(message: JsonRpcRequest | JsonRpcResponse): void {
    this.socket?.write(`${JSON.stringify(message)}\n`)
  }

  private setupDebugUI(): void {
    this.ctx.inject(['webui'], (ctx) => {
      this.debugEntry = ctx.webui.addEntry<DebugData>({
        baseUrl: import.meta.url,
        source: '../client/index.ts',
        manifest: '../dist/manifest.json',
      }, {
        buttons: this.listButtons(),
        trigger: (buttonId: string) => this.trigger(buttonId),
      })
      return () => {
        this.debugEntry = undefined
      }
    })
  }

  private refreshDebugUI(): void {
    this.debugEntry?.mutate((data: DebugData) => {
      data.buttons = this.listButtons()
    })
  }

  dispose(): void {
    clearTimeout(this.reconnectTimer)
    this.socket?.destroy()
    this.buttons.clear()
    this.pending.clear()
  }
}

class RegisteredButton implements ButtonRegistration {
  constructor(
    private bridge: AndroidBridge,
    public def: ButtonDef,
    public handler: ButtonHandler,
  ) {}

  patch(patch: ButtonPatch): void {
    this.bridge.patchButton(this.def.id, patch)
  }

  dispose(notifyHost = true): void {
    this.bridge.unregisterButton(this.def.id, notifyHost)
  }
}

class JsonRpcError extends Error {
  constructor(public code: number, message: string) {
    super(message)
  }
}

function normalizeButton(def: ButtonDef): ButtonDef {
  return {
    ...def,
    label: def.label || def.id,
    enabled: def.enabled ?? true,
  }
}

function integer(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${name} must be a non-negative integer`)
  return value
}

function ensureSuccess(result: CommandResult, action: string): void {
  if (result.exitCode !== 0) {
    throw new Error(`Failed to ${action}: ${result.stderr.trim() || `exit code ${result.exitCode}`}`)
  }
}

const SCREENSHOT_CHUNK_SIZE = 250_000

export function apply(ctx: CordisContext, config: Config = {}): void {
  const bridge = new AndroidBridge(ctx, config)
  ctx.provide('android', bridge)
  ctx.effect(() => () => bridge.dispose(), 'android bridge')
}

export default apply
