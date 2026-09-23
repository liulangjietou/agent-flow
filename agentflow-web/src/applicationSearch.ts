/** 可访问的申请摘要，不包含业务正文。 */
export interface ApplicationSearchItem {
  id: string; businessNo: string; title: string; processKey: string; definitionVersion: number
  createdBy: string; status: string; roundNo: number; createdAt: string; updatedAt: string
}
export interface ApplicationSearchFilters { q?: string; status?: string; processKey?: string; definitionVersion?: number; applicant?: string; from?: string; to?: string; limit?: number; cursor?: string }
export interface ApplicationSearchPage { items: ApplicationSearchItem[]; nextCursor?: string | null }

/**
 * 申请检索只保留当前账号和已提交条件的结果，分页失败可重试。
 * @author owlzhangfq@gmail.com
 */
export class ApplicationSearchQuery {
  items: ApplicationSearchItem[] = []
  nextCursor: string | null = null
  loading = false
  loaded = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  private scope = ''
  private filters: ApplicationSearchFilters = {}

  constructor(private fetchPage: (filters: ApplicationSearchFilters, signal: AbortSignal) => Promise<ApplicationSearchPage>) {}

  /** 账号和视图变化时清空结果，迟到响应不能重新填入。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.items = []; this.nextCursor = null; this.loading = false; this.loaded = false; this.error = ''; this.scope = ''
  }

  /** 拷贝条件，编辑尚未查询的筛选不会影响下一页。 */
  async load(scope: string, filters: ApplicationSearchFilters) {
    this.clear()
    if (!scope) return
    this.scope = scope; this.filters = { ...filters, limit: 30 }; delete this.filters.cursor
    await this.fetch(false)
  }

  /** 同一游标一次请求；失败保留已加载页面。 */
  async more() { if (this.scope && !this.loading && this.nextCursor) await this.fetch(true) }

  private async fetch(append: boolean) {
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true; this.error = ''
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const page = await Promise.race([
        this.fetchPage({ ...this.filters, ...(append ? { cursor: this.nextCursor! } : {}) }, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '申请查询超时，请重试。' }) }, 12_000) })
      ])
      if (generation !== this.generation) return
      const existing = new Set(this.items.map(item => item.id))
      this.items = append ? [...this.items, ...page.items.filter(item => !existing.has(item.id))] : page.items
      this.nextCursor = page.nextCursor ?? null; this.loaded = true
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { code?: string })?.code === 'INVALID_APPLICATION_QUERY'
        ? '筛选条件或分页位置已失效，请重新查询。' : (cause as { message?: string })?.message ?? '申请查询失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
