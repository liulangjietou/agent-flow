import type { InboxMessage, InboxPage, InboxQuery, PaymentNotificationTarget, SupplierPaymentNotificationTarget, VoucherNotificationTarget } from './api'
import { validatePayment } from './payments.js'
import { validateSupplierCashier } from './supplierCashier.js'
import { preparationLabels, operationLabels } from './vouchers.js'

export const notificationLabels: Record<InboxMessage['kind'], string> = {
  VOUCHER_RESULT: '凭证结果更新', VOUCHER_ATTENTION: '凭证处理需核对',
  SUPPLIER_PAYMENT_RESULT: '供应商付款结果更新', SUPPLIER_PAYMENT_ATTENTION: '供应商付款需核对',
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
export const isSupplierPaymentNotification = (item: InboxMessage) => ['SUPPLIER_PAYMENT_RESULT', 'SUPPLIER_PAYMENT_ATTENTION'].includes(item.kind)
export const isPaymentNotification = (item: InboxMessage) => ['PAYMENT_RESULT', 'PAYMENT_ATTENTION'].includes(item.kind) || isSupplierPaymentNotification(item)
export const isVoucherNotification = (item: InboxMessage) => ['VOUCHER_RESULT', 'VOUCHER_ATTENTION'].includes(item.kind)

/** 原准备和过账必须同号；消息详情没有写入许可，也不接受同轮最新准备替换旧编号。 */
export function readVoucherNotificationTarget(value: VoucherNotificationTarget, message: InboxMessage): VoucherNotificationTarget {
  const invalid = () => { throw new Error('消息对应的凭证记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const instant = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const issue = (v: unknown) => v === null || typeof v === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/.test(v)
  if (!value || !isVoucherNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.voucherId, value.applicationId, value.businessId].every(uuid) || !positive(value.roundNo)
      || !['EMPLOYEE_ADVANCE', 'EXPENSE_ACCRUAL', 'PAYMENT'].includes(value.kind) || typeof value.reversalBound !== 'boolean'
      || Object.keys(value).some(key => !['messageId', 'voucherId', 'applicationId', 'businessId', 'roundNo', 'kind', 'preparation', 'operation', 'reversalBound'].includes(key))) invalid()
  const preparation = value.preparation, operation = value.operation
  if (preparation === undefined || operation === undefined || !preparation && !operation || value.reversalBound && !operation) invalid()
  if (preparation && (preparation.id !== value.voucherId || !Object.prototype.hasOwnProperty.call(preparationLabels, preparation.status) || !positive(preparation.attempt)
      || !instant(preparation.createdAt) || !issue(preparation.issue)
      || (['QUEUED', 'RUNNING'].includes(preparation.status) ? preparation.completedAt !== null : !instant(preparation.completedAt))
      || (preparation.status === 'READY') !== !!operation)) invalid()
  if (operation && (operation.id !== value.voucherId || operation.kind !== value.kind || !Object.prototype.hasOwnProperty.call(operationLabels, operation.status)
      || !positive(operation.version) || !Number.isSafeInteger(operation.attempts) || operation.attempts < 0 || typeof operation.disputed !== 'boolean'
      || !instant(operation.updatedAt) || !instant(operation.sendExpiresAt) || !/^\d{4}-\d{2}-\d{2}$/.test(operation.accountingDate)
      || !issue(operation.issue) || operation.observedStatus !== null && !['PENDING', 'POSTED', 'FAILED', 'REVERSED', 'NOT_FOUND'].includes(operation.observedStatus)
      || operation.voucherReference !== null && (typeof operation.voucherReference !== 'string' || !operation.voucherReference.trim() || operation.voucherReference.length > 256)
      || operation.postedAt !== null && !instant(operation.postedAt))) invalid()
  return value
}
/** 原消息、轮次和付款三个标识必须同时匹配，损坏响应不能成为财务入口。 */
export function readPaymentNotificationTarget(value: PaymentNotificationTarget, message: InboxMessage): PaymentNotificationTarget {
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
  if (!value || !isPaymentNotification(message) || isSupplierPaymentNotification(message) || value.messageId !== message.id || !uuid(value.messageId) || !uuid(value.paymentId)
      || !uuid(value.applicationId) || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || !Number.isSafeInteger(value.roundNo) || value.roundNo < 1 || !['APPLICATION_ROUND', 'CASHIER_PAYMENT'].includes(value.view)) throw new Error('消息对应的付款记录不一致，请刷新消息后重新读取。')
  const payment = validatePayment(value.payment, value.paymentId)
  if (payment.applicationId !== value.applicationId || payment.roundNo !== value.roundNo) throw new Error('付款不属于该消息记录的申请轮次。')
  return value
}

/** 原授权与原出纳请求同时匹配，消息只读投影不能携带资金操作许可。 */
export function readSupplierPaymentNotificationTarget(value: SupplierPaymentNotificationTarget, message: InboxMessage): SupplierPaymentNotificationTarget {
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
  if (!value || !isSupplierPaymentNotification(message) || value.messageId !== message.id || !uuid(value.messageId) || !uuid(value.paymentId)
      || !uuid(value.executionRequestId) || !uuid(value.applicationId) || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || !Number.isSafeInteger(value.roundNo) || value.roundNo < 1 || !['APPLICATION_ROUND', 'CASHIER_PAYMENT'].includes(value.view)
      || typeof value.canOpenCashier !== 'boolean') throw new Error('消息对应的供应商付款记录不一致，请重新读取。')
  const payment = validateSupplierCashier(value.payment, value.paymentId)
  if (payment.applicationId !== value.applicationId || payment.roundNo !== value.roundNo || payment.preparation?.id !== value.executionRequestId
      || payment.actions.execute || payment.actions.query || payment.actions.resendOriginal
      || value.canOpenCashier && (value.view !== 'CASHIER_PAYMENT' || !['QUEUED', 'RUNNING', 'READY'].includes(payment.preparation.status))) throw new Error('供应商原选择或只读权限不一致，请重新读取。')
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
