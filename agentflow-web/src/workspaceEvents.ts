export type WorkspaceEventType = 'CONNECTED' | 'INBOX_UPDATED' | 'TASK_UPDATED' | 'AGENT_UPDATED'

export interface WorkspaceEvent {
  type: WorkspaceEventType
  at: string
}

type Listener = (event: WorkspaceEvent) => void

/**
 * 基于 fetch 流式读取的轻量 SSE 订阅器：同时兼容演示 Bearer 令牌与企业 OIDC HttpOnly Cookie，
 * 避免将访问令牌放入 URL 查询参数。只分发无敏感正文的刷新信号，实际数据由各组件走原授权接口拉取。
 * @author owlzhangfq@gmail.com
 */
class WorkspaceEventStream {
  private readonly listeners = new Set<Listener>()
  private controller: AbortController | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private scope = ''
  private backoffMs = 2000

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  connect(scopeKey: string, baseUrl: string, tokenProvider: () => string | null): void {
    if (!scopeKey) { this.disconnect(); return }
    if (this.scope === scopeKey && this.controller) return
    this.disconnect()
    this.scope = scopeKey
    this.backoffMs = 2000
    void this.open(scopeKey, baseUrl, tokenProvider)
  }

  disconnect(): void {
    this.scope = ''
    if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null }
    if (this.controller) { this.controller.abort(); this.controller = null }
  }

  private async open(scopeKey: string, baseUrl: string, tokenProvider: () => string | null): Promise<void> {
    if (this.scope !== scopeKey || typeof fetch !== 'function') return
    const controller = new AbortController()
    this.controller = controller
    const headers: Record<string, string> = { Accept: 'text/event-stream' }
    const token = tokenProvider()
    if (token) headers.Authorization = `Bearer ${token}`
    try {
      const response = await fetch(`${baseUrl}/events/stream`, { headers, credentials: 'same-origin', signal: controller.signal })
      if (!response.ok || !response.body) throw new Error(`SSE status ${response.status}`)
      this.backoffMs = 2000
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (this.scope === scopeKey && !controller.signal.aborted) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() ?? ''
        for (const line of lines) this.handleLine(line)
      }
    } catch {
      /* 断线或代理重置后进入有界退避重连，不影响主页面可用性。 */
    } finally {
      if (this.controller === controller) this.controller = null
      if (this.scope === scopeKey) {
        const delay = this.backoffMs
        this.backoffMs = Math.min(30_000, this.backoffMs * 2)
        this.reconnectTimer = setTimeout(() => {
          this.reconnectTimer = null
          void this.open(scopeKey, baseUrl, tokenProvider)
        }, delay)
      }
    }
  }

  private handleLine(line: string): void {
    if (!line.startsWith('data:')) return
    const raw = line.slice(5).trim()
    if (!raw) return
    try {
      const parsed = JSON.parse(raw) as Partial<WorkspaceEvent>
      if (parsed && typeof parsed.type === 'string' && typeof parsed.at === 'string') {
        const event = parsed as WorkspaceEvent
        for (const listener of this.listeners) listener(event)
      }
    } catch { /* 忽略非结构化心跳行 */ }
  }
}

export const workspaceEvents = new WorkspaceEventStream()
