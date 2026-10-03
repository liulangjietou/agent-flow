import type { InboxMessage, InboxPage, InboxQuery, PaymentNotificationTarget } from './api'
import { validatePayment } from './payments.js'

export const notificationLabels: Record<InboxMessage['kind'], string> = {
  PAYMENT_RESULT: '付款结果更新', PAYMENT_ATTENTION: '付款执行需核对',
  ADVANCE_OVERDUE: '借款逾期提醒',
  TASK_ESCALATED: '审批超时升级提醒',
  COMMENT_MENTIONED: '有人在评论中提及你',
  APPLICATION_PAUSED: '审批已暂停', APPLICATION_RESUMED: '审批已恢复', APPLICATION_SUBMITTED: '申请已提交', TASK_PENDING: '有新的待办', APPLICATION_RETURNED: '申请已退回',
  APPLICATION_REJECTED: '申请已驳回', APPLICATION_APPROVED: '申请已批准', APPLICATION_WITHDRAWN: '申请已撤回', APPLICATION_CANCELLED: '审批已取消',
  TASK_TRANSFERRED: '收到转交任务', TASK_DELEGATED: '收到委派任务', TASK_RESOLVED: '受托意见已回交', TASK_OVERDUE: '审批任务已超时', APPLICATION_COPIED: '收到审批抄送', EXPENSE_ADJUSTED: '报销核定金额已调整', TASK_COUNTERSIGN_REMOVED: '会签任务已取消', TASK_COUNTERSIGN_COMPLETED: '会签已达通过条件，你的待办已结束'
}
/** 只有办理提醒尝试打开实时任务；已结束的会签直接进入仍受权限约束的申请详情。 */
export const isTaskNotification = (item: InboxMessage) => ['TASK_PENDING', 'TASK_TRANSFERRED', 'TASK_DELEGATED', 'TASK_RESOLVED', 'TASK_OVERDUE'].includes(item.kind)
/** 付款消息先读取受当前权限保护的原付款，不把历史消息当作本轮最新授权。 */
export const isPaymentNotification = (item: InboxMessage) => ['PAYMENT_RESULT', 'PAYMENT_ATTENTION'].includes(item.kind)
/** 原消息、轮次和付款三个标识必须同时匹配，损坏响应不能成为财务入口。 */
export function readPaymentNotificationTarget(value: PaymentNotificationTarget, message: InboxMessage): PaymentNotificationTarget {
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
  if (!value || !isPaymentNotification(message) || value.messageId !== message.id || !uuid(value.messageId) || !uuid(value.paymentId)
      || !uuid(value.applicationId) || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || !Number.isSafeInteger(value.roundNo) || value.roundNo < 1 || !['APPLICATION_ROUND', 'CASHIER_PAYMENT'].includes(value.view)) throw new Error('消息对应的付款记录不一致，请刷新消息后重新读取。')
  const payment = validatePayment(value.payment, value.paymentId)
  if (payment.applicationId !== value.applicationId || payment.roundNo !== value.roundNo) throw new Error('付款不属于该消息记录的申请轮次。')
  return value
}

/**
 * 消息查询绑定当前账号与未读筛选，取消、失败与分页均不混入其他上下文的记录。
 * @author owlzhangfq@gmail.com
 */
export class NotificationInboxQuery {
  items: InboxMessage[] = []
  nextCursor: string | null = null
  unreadCount = 0
  loaded = false
  loading = false
  error = ''
  private generation = 0
  private scope = ''
  private read: 'all' | 'unread' = 'all'
  private controller: AbortController | null = null

  constructor(private fetchPage: (query: InboxQuery, signal: AbortSignal) => Promise<InboxPage>) {}

  /** 切换身份或筛选后立即清空旧消息及计数。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null; this.scope = ''
    this.items = []; this.nextCursor = null; this.unreadCount = 0
    this.loaded = false; this.loading = false; this.error = ''
  }

  /** 刷新首页，不复用已读筛选变化前的游标。 */
  async load(scope: string, read: 'all' | 'unread') {
    this.clear()
    if (!scope) return
    this.scope = scope; this.read = read
    await this.fetch(false)
  }

  /** 追加失败保留原游标；重复点击不启动第二次查询。 */
  async more() {
    if (!this.scope || this.loading || !this.nextCursor) return
    await this.fetch(true)
  }

  private async fetch(append: boolean) {
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true; this.error = ''
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const result = await Promise.race([
        this.fetchPage({ read: this.read, limit: 30, ...(append ? { cursor: this.nextCursor! } : {}) }, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '消息查询超时，请重试。' }) }, 12_000) })
      ])
      if (generation !== this.generation) return
      const ids = new Set(this.items.map(item => item.id))
      this.items = append ? [...this.items, ...result.items.filter(item => !ids.has(item.id))] : result.items
      this.nextCursor = result.nextCursor ?? null; this.unreadCount = result.unreadCount; this.loaded = true
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { message?: string })?.message ?? '无法读取消息，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
