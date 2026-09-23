/** 审计摘要不包含表单正文或审批意见，关联标题是申请当前值。 */
export interface AuditSearchItem {
  id: string; eventId: string; source: string; aggregateId: string; aggregateVersion: number
  action?: string | null; actor?: string | null; occurredAt: string
  applicationId?: string | null; businessNo?: string | null; currentTitle?: string | null
}
export interface AuditSearchFilters { q?: string; actor?: string; action?: string; source?: string; applicationId?: string; from?: string; to?: string; limit?: number; cursor?: string }
export interface AuditSearchPage { items: AuditSearchItem[]; nextCursor?: string | null }

/**
 * 管理员审计检索只保留当前账号和已提交条件的结果，分页失败可重试。
 * @author owlzhangfq@gmail.com
 */
export class AuditSearchQuery {
  items: AuditSearchItem[] = []
  nextCursor: string | null = null
  loading = false
  loaded = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  private scope = ''
  private filters: AuditSearchFilters = {}

  constructor(private fetchPage: (filters: AuditSearchFilters, signal: AbortSignal) => Promise<AuditSearchPage>) {}

  /** 账号和视图变化时清空结果，迟到响应不能重新填入。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.items = []; this.nextCursor = null; this.loading = false; this.loaded = false; this.error = ''; this.scope = ''
  }

  /** 拷贝条件，编辑尚未查询的筛选不会影响下一页。 */
  async load(scope: string, filters: AuditSearchFilters) {
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
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '审计查询超时，请重试。' }) }, 12_000) })
      ])
      if (generation !== this.generation) return
      const existing = new Set(this.items.map(item => item.id))
      this.items = append ? [...this.items, ...page.items.filter(item => !existing.has(item.id))] : page.items
      this.nextCursor = page.nextCursor ?? null; this.loaded = true
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { code?: string })?.code === 'INVALID_AUDIT_QUERY'
        ? '筛选条件或分页位置已失效，请重新查询。' : (cause as { message?: string })?.message ?? '审计查询失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
