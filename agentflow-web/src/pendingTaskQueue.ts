import type { PendingTaskItem, PendingTaskPage, PendingTaskQuery } from './api'

/**
 * 待办列表查询独立于办理快照，筛选/会话变化撤销旧请求，分页失败保留当前结果。
 * @author owlzhangfq@gmail.com
 */
export class PendingTaskQueueQuery {
  items: PendingTaskItem[] = []
  nextCursor: string | null = null
  total = 0
  loading = false
  loaded = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  private scope = ''
  private filters: PendingTaskQuery = {}

  constructor(private fetchPage: (query: PendingTaskQuery, signal: AbortSignal) => Promise<PendingTaskPage>) {}

  /** 清空旧账号和旧条件下的结果，迟到响应不可恢复它们。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.items = []; this.nextCursor = null; this.total = 0; this.loading = false; this.loaded = false; this.error = ''; this.scope = ''
  }

  /** 保存已提交筛选的副本，编辑中的表单不能影响后续分页。 */
  async load(scope: string, filters: PendingTaskQuery) {
    this.clear()
    if (!scope) return
    this.scope = scope; this.filters = { ...filters, limit: 30 }; delete this.filters.cursor
    await this.fetch(false)
  }

  /** 重复点击不会重复查询，失败时沿用原游标。 */
  async more() { if (this.scope && !this.loading && this.nextCursor) await this.fetch(true) }

  private async fetch(append: boolean) {
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true; this.error = ''
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const page = await Promise.race([
        this.fetchPage({ ...this.filters, ...(append ? { cursor: this.nextCursor! } : {}) }, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '待办查询超时，请重试。' }) }, 12_000) })
      ])
      if (generation !== this.generation) return
      const existing = new Set(this.items.map(item => item.taskId))
      this.items = append ? [...this.items, ...page.items.filter(item => !existing.has(item.taskId))] : page.items
      this.total = page.total; this.nextCursor = page.nextCursor ?? null; this.loaded = true
    } catch (error) {
      if (generation === this.generation) this.error = (error as { message?: string })?.message ?? '待办查询失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
