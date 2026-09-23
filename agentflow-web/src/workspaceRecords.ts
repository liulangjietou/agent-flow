import type { WorkspaceApplication, WorkspaceHandled, WorkspacePage, WorkspaceQuery } from './api'

export type WorkspaceMode = 'started' | 'drafts' | 'handled'
export type WorkspaceItem = WorkspaceApplication | WorkspaceHandled

/**
 * 筛选、账号与页面切换立即清空旧记录；分页失败可沿原游标重试。
 * @author owlzhangfq@gmail.com
 */
export class WorkspaceRecordsQuery {
  items: WorkspaceItem[] = []
  nextCursor: string | null = null
  loading = false
  error = ''
  loaded = false
  private generation = 0
  private controller: AbortController | null = null
  private mode: WorkspaceMode = 'started'
  private filters: WorkspaceQuery = {}
  private scope = ''

  constructor(private fetchPage: (mode: WorkspaceMode, query: WorkspaceQuery, signal: AbortSignal) => Promise<WorkspacePage<WorkspaceItem>>) {}

  /** 撤销旧会话；迟到的成功、失败都不能回填。 */
  clear() {
    this.generation++
    this.controller?.abort()
    this.controller = null
    this.items = []; this.nextCursor = null; this.loading = false; this.error = ''; this.loaded = false
    this.scope = ''
  }

  /** 只有当前账号的完整新查询可以替换首页。 */
  async load(scope: string, mode: WorkspaceMode, text = '', filter = '') {
    this.clear()
    if (!scope) return
    this.scope = scope; this.mode = mode
    this.filters = { q: text.trim(), limit: 30, ...(mode === 'handled' ? { action: filter } : { view: mode, status: mode === 'drafts' ? '' : filter }) }
    await this.fetch(false)
  }

  /** 防止重复点击；追加失败保留已经读到的记录与原游标。 */
  async more() {
    if (!this.scope || this.loading || !this.nextCursor) return
    await this.fetch(true)
  }

  private async fetch(append: boolean) {
    const generation = this.generation
    const controller = new AbortController()
    this.controller = controller; this.loading = true; this.error = ''
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const page = await Promise.race([
        this.fetchPage(this.mode, { ...this.filters, ...(append ? { cursor: this.nextCursor! } : {}) }, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '查询超时，请重试。' }) }, 12_000) })
      ])
      if (generation !== this.generation) return
      const existing = new Set(this.items.map(item => item.id))
      this.items = append ? [...this.items, ...page.items.filter(item => !existing.has(item.id))] : page.items
      this.nextCursor = page.nextCursor ?? null; this.loaded = true
    } catch (error) {
      if (generation === this.generation) this.error = (error as { message?: string })?.message ?? '查询失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
