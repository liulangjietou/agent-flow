import type { InboxMessage, InboxPage, InboxQuery, PaymentNotificationTarget, SupplierPaymentNotificationTarget, VoucherNotificationTarget, BudgetNotificationTarget, ReversalNotificationTarget } from './api'
import { validatePayment } from './payments.js'
import { validateSupplierCashier } from './supplierCashier.js'
import { preparationLabels, operationLabels } from './vouchers.js'
import { reversalPreparationLabels, reversalExecutionLabels } from './voucherReversalExecution.js'

export const notificationLabels: Record<InboxMessage['kind'], string> = {
  REVERSAL_RESULT: '独立冲销结果更新', REVERSAL_ATTENTION: '独立冲销需核对',
  BUDGET_RESULT: '预算操作结果更新', BUDGET_ATTENTION: '预算操作需核对',
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

export const isReversalNotification = (item: InboxMessage) => ['REVERSAL_RESULT', 'REVERSAL_ATTENTION'].includes(item.kind)
/** 原冲销准备、命令和安全结束不能被新的尝试或办理权限替换。 */
export function readReversalNotificationTarget(value: ReversalNotificationTarget, message: InboxMessage): ReversalNotificationTarget {
  const invalid = () => { throw new Error('消息对应的冲销记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const natural = (v: number) => Number.isSafeInteger(v) && v >= 0
  const instant = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const issue = (v: unknown) => v === null || typeof v === 'string' && /^[A-Z_]{1,64}$/.test(v)
  const closed = (v: object, keys: string[]) => Object.keys(v).every(key => keys.includes(key))
  if (!value || !isReversalNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.reversalId, value.operationId, value.applicationId, value.businessId].every(uuid) || !positive(value.roundNo)
      || value.reversalId === value.operationId || !['EMPLOYEE_ADVANCE', 'EXPENSE_ACCRUAL', 'PAYMENT'].includes(value.kind)
      || !Object.prototype.hasOwnProperty.call(operationLabels, value.originalStatus) || typeof value.originalHeld !== 'boolean'
      || !closed(value, ['messageId', 'reversalId', 'operationId', 'applicationId', 'businessId', 'roundNo', 'kind', 'originalStatus', 'originalHeld', 'preparation', 'operation', 'retirement'])) invalid()
  const preparation = value.preparation, operation = value.operation, retirement = value.retirement
  if (!preparation || typeof preparation !== 'object' || Array.isArray(preparation)
      || operation !== null && (typeof operation !== 'object' || Array.isArray(operation))
      || retirement !== null && (typeof retirement !== 'object' || Array.isArray(retirement))
      || preparation.id !== value.reversalId || !positive(preparation.version)
      || !Object.prototype.hasOwnProperty.call(reversalPreparationLabels, preparation.status) || !instant(preparation.requestedAt) || !instant(preparation.updatedAt)
      || !/^\d{4}-\d{2}-\d{2}$/.test(preparation.accountingDate) || !issue(preparation.issue)
      || ['UNAVAILABLE', 'VOIDED'].includes(preparation.status) !== (preparation.issue !== null) || (preparation.status === 'AUTHORIZED') !== !!operation
      || !closed(preparation, ['id', 'version', 'status', 'requestedAt', 'updatedAt', 'accountingDate', 'issue'])) invalid()
  if (operation && (operation.id !== value.reversalId || !positive(operation.version) || !natural(operation.attempts) || !natural(operation.highestRevision)
      || !Object.prototype.hasOwnProperty.call(reversalExecutionLabels, operation.status) || !instant(operation.updatedAt) || !instant(operation.expiresAt)
      || !issue(operation.issue) || typeof operation.disputed !== 'boolean' || operation.status === 'RECONCILING' && !operation.disputed
      || operation.observedStatus !== null && !['PENDING', 'POSTED', 'FAILED', 'NOT_FOUND'].includes(operation.observedStatus)
      || (operation.observedStatus === 'POSTED' ? typeof operation.voucherReference !== 'string' || !operation.voucherReference.trim() || operation.voucherReference.length > 128 || !instant(operation.postedAt)
        : operation.voucherReference !== null || operation.postedAt !== null)
      || operation.status === 'POSTED' && operation.observedStatus !== 'POSTED' || operation.status === 'FAILED' && operation.observedStatus !== 'FAILED'
      || operation.status === 'NOT_FOUND' && operation.observedStatus !== 'NOT_FOUND'
      || !closed(operation, ['id', 'version', 'status', 'attempts', 'highestRevision', 'updatedAt', 'expiresAt', 'observedStatus', 'issue', 'disputed', 'voucherReference', 'postedAt']))) invalid()
  if (retirement && (!operation || !uuid(retirement.id) || !instant(retirement.retiredAt)
      || !['NEVER_DISPATCHED', 'CONFIRMED_FAILED'].includes(retirement.basis)
      || (retirement.basis === 'NEVER_DISPATCHED' ? operation.attempts !== 0 || !['VOIDED', 'EXPIRED'].includes(operation.status) : operation.status !== 'FAILED')
      || !closed(retirement, ['id', 'retiredAt', 'basis']))) invalid()
  return value
}

export const isBudgetNotification = (item: InboxMessage) => ['BUDGET_RESULT', 'BUDGET_ATTENTION'].includes(item.kind)
export const budgetActionLabels = { FREEZE: '冻结', ADJUST: '调整冻结', RELEASE: '释放', CONSUME: '消费' }
export const budgetStatusLabels = { QUEUED: '原命令待执行', EXECUTING: '正在发送原命令', UNKNOWN: '原操作结果暂不明确', QUERYING: '正在查询原操作', APPLIED: '原操作已确认', REJECTED: '原操作已明确拒绝' }
const budgetFailureLabels = { NOT_CONFIGURED: '预算连接尚未配置', TARGET_CHANGED: '预算连接配置已变化', TIMEOUT: '预算系统响应超时', CONNECTION: '预算系统暂时无法连接', AUTHENTICATION: '预算系统认证未通过', REMOTE_FAILURE: '预算系统暂时不可用', INVALID_RESPONSE: '预算回执未通过校验', RESPONSE_TOO_LARGE: '预算回执超出接收范围', LEASE_EXPIRED: '原执行未在期限内确认', INTERNAL_ERROR: '预算处理暂时异常' }
const budgetRejectionLabels = { BUDGET_INSUFFICIENT: '预算余额不足', BUDGET_POLICY_UNAVAILABLE: '预算控制规则不可用', ACCOUNTING_PERIOD_CLOSED: '会计期间已关闭', COST_OBJECT_UNAVAILABLE: '成本对象不可用', LEGAL_ENTITY_UNAVAILABLE: '法人不可用', EMPLOYEE_UNAVAILABLE: '员工不可用', LEDGER_VERSION_CONFLICT: '预算台账版本存在冲突', RESERVATION_FINALIZED: '原预算占用已经结束' }
const budgetFailures = Object.keys(budgetFailureLabels), budgetRejections = Object.keys(budgetRejectionLabels)
/** 只显示约定的稳定原因，不将远端原始错误正文带入页面。 */
export const budgetIssueLabel = (issue: string) => ({ ...budgetFailureLabels, ...budgetRejectionLabels } as Record<string, string>)[issue] ?? '原因暂不可用'
/** 原消息只定位固定命令；严格区分处理中、查询查无、业务拒绝和实际应用。 */
export function readBudgetNotificationTarget(value: BudgetNotificationTarget, message: InboxMessage): BudgetNotificationTarget {
  const invalid = () => { throw new Error('消息对应的预算记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v > 0
  const instant = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  if (!value || !isBudgetNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.operationId, value.applicationId, value.reportId].every(uuid) || ![value.roundNo, value.financialVersion, value.version].every(positive)
      || !Number.isSafeInteger(value.attempts) || value.attempts < 0 || !instant(value.updatedAt)
      || !Object.prototype.hasOwnProperty.call(budgetActionLabels, value.action) || !Object.prototype.hasOwnProperty.call(budgetStatusLabels, value.status)
      || Object.keys(value).some(key => !['messageId', 'operationId', 'applicationId', 'reportId', 'roundNo', 'financialVersion', 'action', 'status', 'version', 'attempts', 'updatedAt', 'observedStatus', 'issue', 'ledgerRevision', 'reference', 'appliedAt'].includes(key))) invalid()
  const applied = value.status === 'APPLIED'
  if (applied ? value.observedStatus !== 'APPLIED' || value.issue !== null || !positive(value.ledgerRevision) || !instant(value.appliedAt)
      || typeof value.reference !== 'string' || !value.reference.trim() || value.reference.length > 128
    : value.ledgerRevision !== null || value.appliedAt !== null || value.reference !== null) invalid()
  if (value.status === 'REJECTED' && (value.observedStatus !== 'REJECTED' || !budgetRejections.includes(value.issue ?? ''))) invalid()
  if (value.status === 'UNKNOWN' && !(value.observedStatus === 'PENDING' && value.issue === null || value.observedStatus === null && budgetFailures.includes(value.issue ?? ''))) invalid()
  if (value.status === 'QUEUED' && (value.issue !== null || value.observedStatus !== null && value.observedStatus !== 'NOT_FOUND')) invalid()
  if (['EXECUTING', 'QUERYING'].includes(value.status) && (value.issue !== null || value.observedStatus !== null)) invalid()
  return value
}

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
