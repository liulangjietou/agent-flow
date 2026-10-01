/** 投递摘要，不含事件正文、目的地 URL 或签名密钥。@author owlzhangfq@gmail.com */
export interface WebhookItem {
  id: string; targetId: string; eventId: string; eventType: string; applicationId: string; aggregateVersion: number
  occurredAt: string; updatedAt: string; status: string; version: number; attempts: number; cycleAttempts: number
  nextAttemptAt?: string; leaseUntil?: string; httpStatus?: number; errorCode?: string
}
export interface WebhookFilters { target?: string; status?: string; applicationId?: string; limit?: number; cursor?: string }
/** 概况只按目的地与申请收窄范围。@author owlzhangfq@gmail.com */
export type WebhookOverviewFilters = Pick<WebhookFilters, 'target' | 'applicationId'>
/** 完整当前状态数量；2xx 确认接收不表示外部业务已完成。@author owlzhangfq@gmail.com */
export interface WebhookOverview { queriedAt: string; total: number; pending: number; inFlight: number; retryWait: number; delivered: number; failed: number }
/** 从已提交筛选取范围，状态卡片切换与后续分页不改变概况口径。 */
export function webhookOverviewFilters(filters: WebhookFilters): WebhookOverviewFilters {
  return { ...(filters.target ? { target: filters.target } : {}), ...(filters.applicationId ? { applicationId: filters.applicationId } : {}) }
}
export interface WebhookPage { items: WebhookItem[]; nextCursor?: string | null }
export interface WebhookTarget { id: string; label: string; enabled: boolean }
export interface WebhookAttempt { attemptNo: number; startedAt: string; finishedAt?: string; result: string; httpStatus?: number; errorCode?: string }
export interface WebhookRetryRequest { requestedBy: string; requestedAt: string; previousStatus: string; previousVersion: number }
export interface WebhookDetail { delivery: WebhookItem; attempts: WebhookAttempt[]; retryRequests: WebhookRetryRequest[] }
export const webhookStatuses: Record<string, string> = { PENDING: '待投递', IN_FLIGHT: '投递中', RETRY_WAIT: '等待重试', DELIVERED: '已送达', FAILED: '停止投递' }
export const webhookEvents: Record<string, string> = { ApplicationSubmitted: '提交审批', ApplicationWithdrawn: '撤回申请', ApplicationCancelled: '作废申请', TaskActionAccepted: '任务操作', ApplicationApproved: '申请批准', ApplicationReturned: '申请退回', ApplicationRejected: '申请驳回', TimerWaitElapsed: '等待到期', TimerWaitFailed: '等待推进失败', TimerWaitRetried: '原等待重试' }
export const webhookErrors: Record<string, string> = { TARGET_UNAVAILABLE: '目的地已移除或停用', TARGET_CHANGED: '目的地地址已变更', OUTCOME_UNKNOWN: '上次尝试结果未知，接收方可能已收到', CONNECTION_FAILED: '连接异常，接收方可能已收到', TIMEOUT: '响应超时，接收方可能已收到', INTERRUPTED: '发送被中断，接收方可能已收到' }
export function webhookRetryable(item: WebhookItem) { return ['FAILED', 'DELIVERED', 'RETRY_WAIT'].includes(item.status) }
/**
 * 管理员投递检索只保留当前账号和已提交条件的结果，分页失败可重试。
 * @author owlzhangfq@gmail.com
 */
export class WebhookQuery {
  items: WebhookItem[] = []
  nextCursor: string | null = null
  loading = false
  loaded = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  private scope = ''
  private filters: WebhookFilters = {}

  constructor(private fetchPage: (filters: WebhookFilters, signal: AbortSignal) => Promise<WebhookPage>) {}

  /** 账号和视图变化时清空结果，迟到响应不能重新填入。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.items = []; this.nextCursor = null; this.loading = false; this.loaded = false; this.error = ''; this.scope = ''
  }

  /** 拷贝条件，编辑尚未查询的筛选不会影响下一页。 */
  async load(scope: string, filters: WebhookFilters) {
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
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '投递查询超时，请重试。' }) }, 12_000) })
      ])
      if (generation !== this.generation) return
      const existing = new Set(this.items.map(item => item.id))
      this.items = append ? [...this.items, ...page.items.filter(item => !existing.has(item.id))] : page.items
      this.nextCursor = page.nextCursor ?? null; this.loaded = true
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { code?: string })?.code === 'INVALID_WEBHOOK_QUERY'
        ? '筛选条件或分页位置已失效，请重新查询。' : (cause as { message?: string })?.message ?? '投递查询失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}

/** 有界详情读取，切换投递或账号后忽略迟到响应。@author owlzhangfq@gmail.com */
export class WebhookRead<T> {
  value: T | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = '' }
  async load(fetchValue: (signal: AbortSignal) => Promise<T>) {
    this.clear()
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([fetchValue(controller.signal), new Promise<never>((_, reject) => {
        timer = setTimeout(() => { controller.abort(); reject({ message: '投递查询超时，请重试。' }) }, 12_000)
      })])
      if (generation === this.generation) this.value = value
    } catch (cause) { if (generation === this.generation) this.error = (cause as { message?: string })?.message ?? '投递查询失败，请重试。' }
    finally { clearTimeout(timer); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }
}
