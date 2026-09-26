/** 流程目录只读摘要。@author owlzhangfq@gmail.com */
export interface DefinitionCatalogItem {
  id: string; key: string; name: string; status: 'DRAFT' | 'PUBLISHED'; version: number; revision: number; startEnabled: boolean
  createdAt: string; updatedAt: string
}
/** 目录已提交筛选。@author owlzhangfq@gmail.com */
export interface DefinitionCatalogFilters { q?: string; status?: string; processKey?: string; version?: number; startEnabled?: boolean; limit?: number; cursor?: string }
/** 不包含全库总数的有界分页。@author owlzhangfq@gmail.com */
export interface DefinitionCatalogPage { items: DefinitionCatalogItem[]; nextCursor?: string | null }

/**
 * 查询绑定当前账号和已提交筛选，旧响应不能覆盖新目录。
 * @author owlzhangfq@gmail.com
 */
export class DefinitionCatalogQuery {
  items: DefinitionCatalogItem[] = []
  nextCursor: string | null = null
  loading = false
  loaded = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  private scope = ''
  private filters: DefinitionCatalogFilters = {}

  constructor(private fetchPage: (filters: DefinitionCatalogFilters, signal: AbortSignal) => Promise<DefinitionCatalogPage>) {}

  /** 关闭目录或切换账号时取消等待并清空结果。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.items = []; this.nextCursor = null; this.loading = false; this.loaded = false; this.error = ''; this.scope = ''
  }

  /** 冻结已提交的筛选，未提交的输入不影响后续分页。 */
  async load(scope: string, filters: DefinitionCatalogFilters) {
    this.clear()
    if (!scope) return
    this.scope = scope; this.filters = { ...filters, limit: 30 }; delete this.filters.cursor
    await this.fetch(false)
  }

  /** 翻页失败保留当前结果和原游标，允许重试。 */
  async more() { if (this.scope && !this.loading && this.nextCursor) await this.fetch(true) }

  private async fetch(append: boolean) {
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true; this.error = ''
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const page = await Promise.race([
        this.fetchPage({ ...this.filters, ...(append ? { cursor: this.nextCursor! } : {}) }, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '流程目录查询超时，请重试。' }) }, 12_000) })
      ])
      if (generation !== this.generation) return
      const existing = new Set(this.items.map(item => item.id))
      this.items = append ? [...this.items, ...page.items.filter(item => !existing.has(item.id))] : page.items
      this.nextCursor = page.nextCursor ?? null; this.loaded = true
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { code?: string })?.code === 'INVALID_DEFINITION_QUERY'
        ? '筛选条件或分页位置已失效，请重新查询。' : (cause as { message?: string })?.message ?? '流程目录读取失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
