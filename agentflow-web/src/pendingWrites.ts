export interface WriteRequest { method: 'POST' | 'PUT'; path: string; body?: string; label: string }
export interface PendingWrite { id: string; method: string; path: string; label: string; sending: boolean }
export interface RequestFailure { status: number; code: string; message: string }
interface Entry { id: string; scope: string; request: WriteRequest; key: string; inflight?: Promise<unknown> }

/**
 * 页面内保留尚未确认的原请求；不自动重试，不把正文或令牌写入持久存储。
 * @author owlzhangfq@gmail.com
 */
export class PendingWrites {
  private scope: string | null = null
  private epoch = 0
  private entries = new Map<string, Entry>()
  private listeners = new Set<() => void>()

  constructor(private send: (request: WriteRequest, key: string) => Promise<unknown>) { }

  /** 使用认证主体隔离恢复槽；退出不删除未确认请求，重新登录原账号后仍可恢复。 */
  setActor(actor: { tenantId: string; userId: string } | null) {
    this.epoch++
    this.scope = actor ? JSON.stringify([actor.tenantId, actor.userId]) : null
    this.notify()
  }

  /** 仅返回当前账号的操作说明，不向界面暴露正文或请求键。 */
  pending(): PendingWrite[] {
    return [...this.entries.values()].filter(entry => entry.scope === this.scope).map(entry => ({
      id: entry.id, method: entry.request.method, path: entry.request.path, label: entry.request.label, sending: !!entry.inflight
    }))
  }

  hasUnconfirmed() { return this.entries.size > 0 }
  subscribe(listener: () => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }

  /** 同目标原请求共享执行；未确认时改变内容必须先由用户恢复原操作。 */
  run<T>(request: WriteRequest): Promise<T> {
    if (!this.scope) return Promise.reject(this.failure(401, 'UNAUTHENTICATED', '请先登录后再操作。'))
    const id = JSON.stringify([this.scope, request.method, request.path.split('?')[0]])
    let entry = this.entries.get(id)
    if (entry && (entry.request.path !== request.path || entry.request.body !== request.body)) {
      return Promise.reject(this.failure(409, 'PENDING_REQUEST_CHANGED', '上次操作的结果尚未确认，请先恢复上次操作，再修改内容。'))
    }
    if (!entry) {
      entry = { id, scope: this.scope, request: { ...request }, key: crypto.randomUUID() }
      this.entries.set(id, entry)
    }
    return this.execute(entry) as Promise<T>
  }

  /** 用户主动恢复时只发送保存的原请求，不触发后续提交或审批。 */
  async recover(id: string): Promise<{ request: WriteRequest; result: unknown }> {
    const entry = this.entries.get(id)
    if (!entry || entry.scope !== this.scope) throw this.failure(409, 'PENDING_REQUEST_GONE', '此操作已确认或账号已切换，请刷新业务数据。')
    return { request: { ...entry.request }, result: await this.execute(entry) }
  }

  private execute(entry: Entry): Promise<unknown> {
    if (entry.inflight) return entry.inflight
    const epoch = this.epoch
    const sessionChanged = () => this.failure(401, 'SESSION_CHANGED', '账号已切换，旧操作结果未应用到当前页面。')
    const remove = () => { if (this.entries.get(entry.id) === entry) this.entries.delete(entry.id) }
    // 微任务中发起网络请求，确保并发调用已经能看到同一个执行对象。
    entry.inflight = Promise.resolve().then(() => {
      if (epoch !== this.epoch) throw sessionChanged()
      return this.send(entry.request, entry.key)
    }).then(result => {
      remove()
      if (epoch !== this.epoch) throw sessionChanged()
      return result
    }).catch((error: unknown) => {
      const failure = error as Partial<RequestFailure>
      // 认证或 CSRF 失败不能证明前次请求未成功；恢复会话后仍须使用原键。
      if (failure.code !== 'SESSION_CHANGED' && failure.code !== 'CSRF_INVALID' && typeof failure.status === 'number'
          && failure.status >= 400 && failure.status < 500 && failure.status !== 401) remove()
      if (epoch !== this.epoch) throw sessionChanged()
      throw error
    }).finally(() => {
      entry.inflight = undefined
      this.notify()
    })
    this.notify()
    return entry.inflight
  }

  private notify() { this.listeners.forEach(listener => listener()) }
  private failure(status: number, code: string, message: string): RequestFailure { return { status, code, message } }
}
