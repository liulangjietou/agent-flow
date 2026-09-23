/** 服务端追加的纯文本沟通记录，不是审批结论。@author owlzhangfq@gmail.com */
export interface ApplicationComment {
  id: string; applicationId: string; author: string; content: string; roundNo: number
  applicationVersion: number; applicationStatus: string; createdAt: string
}
/** 单申请评论游标页。@author owlzhangfq@gmail.com */
export interface CommentPage { items: ApplicationComment[]; nextCursor?: string | null }
/** 评论筛选只作用于读取，不改变新评论关联的当前申请上下文。@author owlzhangfq@gmail.com */
export interface CommentQuery { roundNo?: number; limit?: number; cursor?: string }
/** 尚未发送的本页草稿，固定开始编辑时的申请版本。@author owlzhangfq@gmail.com */
export interface CommentDraft { content: string; expectedVersion: number }

/**
 * 待办和申请弹窗共享本页草稿，切换账号后不可见；不写入浏览器持久存储。
 * @author owlzhangfq@gmail.com
 */
export class CommentDrafts {
  private entries = new Map<string, CommentDraft>()
  private key(scope: string, id: string) { return JSON.stringify([scope, id]) }
  get(scope: string, id: string): CommentDraft | null {
    const value = this.entries.get(this.key(scope, id))
    return value ? { ...value } : null
  }
  /** 后续刷新不能自动把旧评论草稿绑定到新版本。 */
  put(scope: string, id: string, content: string, version: number) {
    if (!scope || !id) return
    const key = this.key(scope, id), previous = this.entries.get(key)
    if (!content.trim()) this.entries.delete(key)
    else this.entries.set(key, { content, expectedVersion: previous?.expectedVersion ?? version })
  }
  /** 用户核对最新申请后显式更新上下文，保留正文。 */
  adopt(scope: string, id: string, version: number) {
    const value = this.entries.get(this.key(scope, id))
    if (value) value.expectedVersion = version
  }
  discard(scope: string, id: string) { this.entries.delete(this.key(scope, id)) }
  /** 只清除已经确认的原请求，不能误删发送后的另一份内容。 */
  acknowledge(scope: string, id: string, sent: CommentDraft) {
    const current = this.get(scope, id)
    if (current?.expectedVersion === sent.expectedVersion && current.content.trim() === sent.content.trim()) this.discard(scope, id)
  }
  hasDrafts() { return this.entries.size > 0 }
}
export const commentDrafts = new CommentDrafts()

/**
 * 评论分页按账号和申请隔离，失权或切换资源时清除正文，迟到响应不能覆盖当前页。
 * @author owlzhangfq@gmail.com
 */
export class CommentsQuery {
  items: ApplicationComment[] = []
  nextCursor: string | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  private context = ''
  constructor(private fetchPage: (id: string, query: CommentQuery, signal: AbortSignal) => Promise<CommentPage>) {}

  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.items = []; this.nextCursor = null; this.loading = false; this.error = ''; this.context = ''
  }

  /** 新查询清除旧正文；追加失败保留已读页，但权限错误立即清空。 */
  async load(scope: string, id: string, roundNo?: number, more = false) {
    const context = JSON.stringify([scope, id, roundNo])
    if (more && (this.loading || !this.nextCursor || this.context !== context)) return
    const cursor = more ? this.nextCursor : null
    if (!more) this.clear()
    if (!scope || !id) return
    this.context = context
    const generation = ++this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true; this.error = ''
    let timedOut = false
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, 12_000)
    try {
      const page = await this.fetchPage(id, { roundNo, limit: 30, ...(cursor ? { cursor } : {}) }, controller.signal)
      if (generation !== this.generation) return
      if (timedOut) { this.error = '评论查询超时，请重试。'; return }
      this.items = more ? [...this.items, ...page.items] : page.items
      this.nextCursor = page.nextCursor ?? null
    } catch (cause) {
      if (generation !== this.generation) return
      const failure = cause as { status?: number; message?: string }
      if ([401, 403, 404].includes(failure.status ?? 0)) { this.items = []; this.nextCursor = null }
      this.error = timedOut ? '评论查询超时，请重试。' : failure.message ?? '评论暂时无法加载，请重试。'
    } finally {
      clearTimeout(timer)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
