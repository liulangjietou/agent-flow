import type { ApplicationComment, CommentDraft } from './applicationComments'

export const MAX_COMMENT_MENTIONS = 20
const QUERY_TIMEOUT_MS = 12_000
export interface CommentMentionPage { applicationId: string; roundNo: number; applicationVersion: number; items: string[]; nextAfter?: string | null }
export interface CommentMentionFilter { q?: string; afterUser?: string; limit?: number }

/** 评论是追加事实；回执必须对应原正文、上下文和名单，才能清除原请求恢复槽。 */
export function validateCommentReceipt(value: unknown, id: string, input: CommentDraft) {
  const comment = value as ApplicationComment | null
  if (!comment || typeof comment.id !== 'string' || !comment.id || comment.applicationId !== id
      || comment.content !== input.content.trim() || comment.applicationVersion !== input.expectedVersion
      || comment.applicationStatus !== 'IN_APPROVAL' || !Number.isSafeInteger(comment.roundNo) || comment.roundNo < 1
      || typeof comment.author !== 'string' || !comment.author || !Number.isFinite(Date.parse(comment.createdAt))
      || JSON.stringify(comment.mentions ?? []) !== JSON.stringify(input.mentions ?? [])) {
    throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '评论回执未能核实，请恢复上次操作确认评论及提醒结果。' }
  }
}

/** 目录响应不得串申请、重复账号或返回无法继续的分页位置。 */
export function readCommentMentionPage(value: CommentMentionPage, id: string, query: CommentMentionFilter) {
  const users = value?.items
  if (value?.applicationId !== id || !Number.isSafeInteger(value.applicationVersion) || value.applicationVersion < 1
      || !Number.isSafeInteger(value.roundNo) || value.roundNo < 0 || !Array.isArray(users) || users.length > (query.limit ?? 30)
      || users.some((user, index) => typeof user !== 'string' || !user.trim() || user.length > 128
        || user <= (index ? users[index - 1]! : query.afterUser ?? ''))
      || value.nextAfter != null && (!users.length || value.nextAfter !== users[users.length - 1])) {
    throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '提醒名单无法核实，请重新读取。' }
  }
  return value
}

/** 名单绑定账号、申请和版本；刷新、失败或退出清除旧资格，迟到读取不能回填。 @author owlzhangfq@gmail.com */
export class CommentMentionQuery {
  items: string[] = []
  nextAfter: string | null = null
  applicationVersion: number | null = null
  verified = new Set<string>()
  loading = false
  error = ''
  private generation = 0
  private context = ''
  private binding = ''
  private controller: AbortController | null = null
  constructor(private read: (id: string, query: CommentMentionFilter, signal: AbortSignal) => Promise<CommentMentionPage>) { }

  /** 撤销旧账号、旧版本及旧搜索结果。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null; this.context = ''; this.binding = ''; this.verified = new Set()
    this.items = []; this.nextAfter = null; this.applicationVersion = null; this.loading = false; this.error = ''
  }

  /** 核对和翻页都是只读请求，不能触发任何提醒发送。 */
  async load(scope: string, id: string, version: number, q = '', more = false) {
    const binding = JSON.stringify([scope, id, version]), context = JSON.stringify([binding, q])
    if (more && (this.loading || !this.nextAfter || this.context !== context)) return
    const afterUser = more ? this.nextAfter! : undefined
    if (!more) {
      // 换搜索词时保留跨页选择；明确刷新相同搜索必须撤销上次读取的资格。
      const verified = this.binding === binding && this.context !== context ? this.verified : new Set<string>()
      this.clear(); this.verified = verified
    }
    if (!scope || !id) return
    this.context = context; this.binding = binding; this.loading = true; this.error = ''
    const generation = ++this.generation, controller = new AbortController(); this.controller = controller
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const page = await Promise.race([
        this.read(id, { q, afterUser, limit: 30 }, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject(new Error('提醒名单读取超时，请重试。')) }, QUERY_TIMEOUT_MS) })
      ])
      if (generation !== this.generation) return
      if (page.applicationId !== id || page.applicationVersion !== version) throw new Error('申请已更新，请重新加载申请后核对提醒名单。')
      this.items = more ? [...new Set([...this.items, ...page.items])] : page.items
      page.items.forEach(user => this.verified.add(user))
      this.nextAfter = page.nextAfter ?? null; this.applicationVersion = page.applicationVersion
    } catch (cause) {
      if (generation !== this.generation) return
      this.items = []; this.nextAfter = null; this.applicationVersion = null; this.verified = new Set()
      this.error = (cause as Error)?.message ?? '提醒名单暂时无法读取。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
