import type { Context as BaseContext } from 'cordis'
import type { Entry, WebUI } from '@cordisjs/plugin-webui'
import { Socket, createConnection } from 'node:net'

declare module 'cordis' {
  interface Context {
    android: AndroidBridge
  }
}

export const name = 'cordis-plugin-android'

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
}

declare module '@cordisjs/plugin-webui' {
  interface Events {
    'android.debug.buttons'(): ButtonDef[]
    'android.debug.trigger'(buttonId: string): Promise<void>
  }
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
    if (!this.socket || this.socket.destroyed) return Promise.reject(new Error('Android bridge is not connected'))
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
      this.debugEntry = ctx.webui.addEntry({
        path: 'cordis-plugin-android/dist',
        base: import.meta.url,
        dev: '../client/index.ts',
        prod: '../dist/manifest.json',
      }, () => ({ buttons: this.listButtons() }))
      ctx.webui.addListener('android.debug.buttons', () => this.listButtons())
      ctx.webui.addListener('android.debug.trigger', (buttonId: string) => this.trigger(buttonId))
      return () => {
        this.debugEntry = undefined
      }
    })
  }

  private refreshDebugUI(): void {
    this.debugEntry?.patch({ buttons: this.listButtons() })
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

export function apply(ctx: CordisContext, config: Config = {}): void {
  const bridge = new AndroidBridge(ctx, config)
  ctx.provide('android', bridge)
  ctx.effect(() => () => bridge.dispose(), 'android bridge')
}

export default apply
