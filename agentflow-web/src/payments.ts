import type { Money } from './expenses'

export const authorizationLabels = { AUTHORIZED: '财务已授权', EXECUTION_REGISTERED: '出纳执行已登记', VOIDED: '授权已作废', EXPIRED: '授权已到期', RETIRED: '原付款已安全结束' }
export const paymentRequestLabels = { QUEUED: '等待复核账户', RUNNING: '正在复核账户', READY: '已进入付款队列', BLOCKED: '账户复核未通过', VOIDED: '本次选择已停止', EXPIRED: '授权期限已过' }
export const paymentOperationLabels = { QUEUED: '等待执行付款', CHECKING: '发送前复核账户', SENDING: '正在请求付款', QUERYING: '正在查询原交易', UNKNOWN: '付款结果待确认', SUCCEEDED: '银行已确认到账', FAILED: '银行未通过付款', NOT_FOUND: '资金系统确认原交易不存在', EXPIRED: '发送期限已过', VOIDED: '付款发送已停止', RECONCILING: '资金结果存在冲突', REVERSED: '银行已退回款项' }
export type FinancePaymentAction = 'AUTHORIZE' | 'VOID' | 'QUERY' | 'RETIRE'
export type CashierPaymentAction = 'EXECUTE' | 'QUERY' | 'RESEND_ORIGINAL'
export const financePaymentLabels: Record<FinancePaymentAction, string> = { AUTHORIZE: '授权付款', VOID: '作废未执行授权', QUERY: '查询原交易', RETIRE: '安全结束原付款' }
export const retirementBasisLabels = { NEVER_DISPATCHED: '原命令从未进入发送阶段', CONFIRMED_FAILED: '资金系统已确认终态失败' }
export const cashierPaymentLabels: Record<CashierPaymentAction, string> = { EXECUTE: '登记付款执行', QUERY: '查询原交易', RESEND_ORIGINAL: '按原编号重发' }
export interface PaymentBinding { applicationId: string; businessId: string; roundNo: number; applicationVersion: number; businessVersion: number }
export interface PaymentView extends PaymentBinding {
  id: string; version: number; status: keyof typeof authorizationLabels; purpose: 'EMPLOYEE_ADVANCE' | 'EXPENSE_REIMBURSEMENT'
  legalEntityId: string; employeeId: string; amount: Money; maskedPayeeAccount: string; authorizedBy: string; authorizedAt: string; expiresAt: string; executedBy: string | null
  request: null | { id: string; version: number; status: keyof typeof paymentRequestLabels; cashier: string; createdAt: string; updatedAt: string; issue: string | null }
  operation: null | { version: number; status: keyof typeof paymentOperationLabels; updatedAt: string; observedStatus: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'REVERSED' | 'NOT_FOUND' | null; paymentReference: string | null; receiptReference: string | null; completedAt: string | null; disputed: boolean; issue: string | null }
  retirement: null | { retiredBy: string; retiredAt: string; operationVersion: number; basis: keyof typeof retirementBasisLabels }
}
export interface FinancePaymentView extends PaymentBinding { voucherOperationId: string | null; voucherVersion: number | null; payable: Money | null; payment: PaymentView | null; actions: { authorize: boolean; voidAuthorization: boolean; query: boolean; retire: boolean } }
export interface CashierPaymentView { payment: PaymentView; actions: { execute: boolean; query: boolean; resendOriginal: boolean } }
export interface CashierPaymentPage { items: CashierPaymentView[]; nextBeforeId: string | null }
export interface DebitAccount { reference: string; displayName: string; maskedAccount: string; currency: string; sourceVersion: string }
export interface PaymentAccounts { authorizationId: string; authorizationVersion: number; validUntil: string; items: DebitAccount[] }
export interface PaymentAuthorizationInput { roundNo: number; applicationVersion: number; businessVersion: number; voucherOperationId: string; voucherVersion: number; validitySeconds: number; comment: string }
export interface FinancePaymentActionInput { action: 'VOID' | 'QUERY' | 'RETIRE'; authorizationVersion: number; operationVersion?: number; comment: string }
export interface CashierPaymentActionInput { action: CashierPaymentAction; authorizationVersion: number; operationVersion?: number; debitAccountReference?: string; debitAccountVersion?: string; comment: string }
export interface FinancePaymentReceipt { applicationId: string; businessId: string; roundNo: number; authorizationId: string; authorizationVersion: number; action: FinancePaymentAction; operationVersion: number | null; expiresAt: string; auditEventId: string }
export interface CashierPaymentReceipt { authorizationId: string; authorizationVersion: number; action: CashierPaymentAction; requestId: string | null; operationVersion: number | null; auditEventId: string }
const positive = (value: number) => Number.isSafeInteger(value) && value > 0
const time = (value: string) => typeof value === 'string' && Number.isFinite(Date.parse(value))
const known = (labels: object, value: string) => Object.prototype.hasOwnProperty.call(labels, value)
const money = (value: Money | null) => !!value && typeof value.value === 'string' && /^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(value.value) && /^[A-Z]{3}$/.test(value.currency)
const mask = (value: string) => typeof value === 'string' && /(?:\*{2,}|•{2,}|[xX]{2,})/.test(value) && /^[0-9*•xX -]+$/.test(value) && !/[0-9]{5,}/.test(value) && value.replace(/[^0-9]/g, '').length <= 8
const bindingKeys = ['applicationId', 'businessId', 'roundNo', 'applicationVersion', 'businessVersion'] as const

/** 只有绑定完整且带回单的真实状态才能显示到账，未知枚举和完整账号均拒绝展示。 */
export function validatePayment(value: PaymentView, id = value?.id): PaymentView {
  if (!value || !value.id || value.id !== id || !value.applicationId || !value.businessId || !value.legalEntityId || !value.employeeId || !value.authorizedBy
      || ![value.version, value.roundNo, value.applicationVersion, value.businessVersion].every(positive) || !known(authorizationLabels, value.status)
      || !['EMPLOYEE_ADVANCE', 'EXPENSE_REIMBURSEMENT'].includes(value.purpose) || !money(value.amount) || value.amount.value === '0.00'
      || !mask(value.maskedPayeeAccount) || !time(value.authorizedAt) || !time(value.expiresAt) || Date.parse(value.expiresAt) <= Date.parse(value.authorizedAt)) throw new Error('付款绑定或金额未通过校验，请刷新核对。')
  const request = value.request, operation = value.operation
  if (request && (!request.id || !positive(request.version) || !request.cashier || !known(paymentRequestLabels, request.status))
      || operation && (!positive(operation.version) || !known(paymentOperationLabels, operation.status) || typeof operation.disputed !== 'boolean'
        || ['SUCCEEDED', 'REVERSED'].includes(operation.status) && (operation.disputed || operation.observedStatus !== operation.status || !operation.paymentReference || !operation.receiptReference || !time(operation.completedAt!)))
      || ['EXECUTION_REGISTERED', 'RETIRED'].includes(value.status) && (!value.executedBy || !operation)
      || !['EXECUTION_REGISTERED', 'RETIRED'].includes(value.status) && (value.executedBy !== null || operation !== null)
      || request?.status === 'READY' && !operation) throw new Error('付款状态或回单未通过核对，请刷新。')
  const retirement = value.retirement
  if (value.status === 'RETIRED' ? value.version !== 3 || !retirement || !retirement.retiredBy || !time(retirement.retiredAt)
      || !known(retirementBasisLabels, retirement.basis) || retirement.operationVersion !== operation?.version || operation?.disputed
      || retirement.basis === 'CONFIRMED_FAILED' && (operation?.status !== 'FAILED' || operation.observedStatus !== 'FAILED')
      || retirement.basis === 'NEVER_DISPATCHED' && (!['VOIDED', 'EXPIRED'].includes(operation?.status ?? '') || operation?.observedStatus !== null)
      || Date.parse(retirement.retiredAt) < Date.parse(operation!.updatedAt) : retirement !== null) throw new Error('原付款结束证据不完整，请刷新核对。')
  return value
}
/** 原付款保留授权时版本；外层明细必须与当前展示版本一致，撤销后仍可查询旧交易。 */
export function validateFinancePayment(view: FinancePaymentView, expected: PaymentBinding): FinancePaymentView {
  if (!view || !bindingKeys.every(key => view[key] === expected[key]) || ![view.roundNo, view.applicationVersion, view.businessVersion].every(positive)
      || !view.actions || ['authorize', 'voidAuthorization', 'query', 'retire'].some(key => typeof view.actions[key as keyof typeof view.actions] !== 'boolean')
      || view.payable !== null && !money(view.payable) || view.voucherOperationId !== null && (!view.voucherOperationId || !positive(view.voucherVersion!))) throw new Error('财务轮次或版本已变化，请刷新业务明细。')
  if (view.payment) {
    validatePayment(view.payment)
    if (view.payment.applicationId !== view.applicationId || view.payment.businessId !== view.businessId || view.payment.roundNo !== view.roundNo) throw new Error('付款不属于当前业务轮次。')
  }
  return view
}
/** 列表与详情使用相同的严格投影，选择另一笔付款后不能显示旧详情。 */
export function validateCashierPayment(view: CashierPaymentView, id = view?.payment?.id): CashierPaymentView {
  validatePayment(view?.payment, id)
  if (!view.actions || ['execute', 'query', 'resendOriginal'].some(key => typeof view.actions[key as keyof typeof view.actions] !== 'boolean')) throw new Error('出纳权限提示未通过校验。')
  return view
}
/** 账户目录必须属于当前授权，不能自动选中第一项或复用另一笔付款的目录。 */
export function validatePaymentAccounts(options: PaymentAccounts, payment: PaymentView, now = Date.now()): PaymentAccounts {
  if (!options || options.authorizationId !== payment.id || options.authorizationVersion !== payment.version || !time(options.validUntil) || Date.parse(options.validUntil) <= now
      || !Array.isArray(options.items) || options.items.length > 200 || new Set(options.items.map(item => item.reference)).size !== options.items.length
      || options.items.some(item => !item.reference || !item.sourceVersion || !item.displayName || item.currency !== payment.amount.currency || !mask(item.maskedAccount))) throw new Error('出款账户目录已变化或过期，请重新查询。')
  return options
}
function reason(value: string) { if (!value.trim() || value.length > 2000) throw new Error('请填写 2000 字以内的办理说明。'); return value.trim() }
function requireWindow(payment: PaymentView, now: number) { if (Date.parse(payment.expiresAt) <= now) throw new Error('付款授权已到期，请刷新后由财务处理。') }
function requireQuery(payment: PaymentView) { if (payment.status !== 'EXECUTION_REGISTERED' || !payment.operation || ['QUEUED', 'CHECKING', 'SENDING', 'QUERYING'].includes(payment.operation.status)) throw new Error('原交易尚不允许此操作，请刷新状态。') }

/** 金额和账户由服务端派生，财务仅提交已核对版本、期限和说明。 */
export function financePaymentInput(view: FinancePaymentView, action: FinancePaymentAction, comment: string, validitySeconds = 900, now = Date.now()): PaymentAuthorizationInput | FinancePaymentActionInput {
  validateFinancePayment(view, view); const explanation = reason(comment)
  if (!view.actions[({ AUTHORIZE: 'authorize', VOID: 'voidAuthorization', QUERY: 'query', RETIRE: 'retire' } as const)[action]]) throw new Error('当前不允许此财务操作，请刷新。')
  if (action === 'AUTHORIZE') {
    if (!view.voucherOperationId || !positive(view.voucherVersion!) || !money(view.payable) || view.payable!.value === '0.00'
        || !Number.isSafeInteger(validitySeconds) || validitySeconds < 60 || validitySeconds > 86400) throw new Error('请核对应付金额与 1 分钟至 24 小时的授权期限。')
    return { roundNo: view.roundNo, applicationVersion: view.applicationVersion, businessVersion: view.businessVersion, voucherOperationId: view.voucherOperationId, voucherVersion: view.voucherVersion!, validitySeconds, comment: explanation }
  }
  const payment = view.payment; if (!payment) throw new Error('当前没有付款授权。')
  if (action === 'VOID') { requireWindow(payment, now); if (payment.status !== 'AUTHORIZED') throw new Error('已经登记执行的授权不能作废。') }
  else if (action === 'RETIRE') {
    if (payment.status !== 'EXECUTION_REGISTERED' || !payment.operation || payment.operation.disputed || !['QUEUED', 'CHECKING', 'VOIDED', 'EXPIRED', 'FAILED'].includes(payment.operation.status)) throw new Error('原交易尚不具备安全结束依据，请核对资金结果。')
  }
  else requireQuery(payment)
  return { action, authorizationVersion: payment.version, ...(action !== 'VOID' ? { operationVersion: payment.operation!.version } : {}), comment: explanation }
}
/** 出纳确认只固化目录中的账户引用和版本；未知结果不能改账户或换号付款。 */
export function cashierPaymentInput(view: CashierPaymentView, action: CashierPaymentAction, comment: string, accounts: PaymentAccounts | null = null, selected = '', now = Date.now()): CashierPaymentActionInput {
  validateCashierPayment(view); const payment = view.payment, explanation = reason(comment)
  if (!view.actions[({ EXECUTE: 'execute', QUERY: 'query', RESEND_ORIGINAL: 'resendOriginal' } as const)[action]]) throw new Error('当前不允许此出纳操作，请刷新。')
  if (action === 'EXECUTE') {
    requireWindow(payment, now); if (payment.status !== 'AUTHORIZED' || payment.request || payment.operation || !accounts) throw new Error('当前不能登记新的付款选择。')
    validatePaymentAccounts(accounts, payment, now); const account = accounts.items.find(item => item.reference === selected)
    if (!account) throw new Error('请明确选择本次出款账户。')
    return { action, authorizationVersion: payment.version, debitAccountReference: account.reference, debitAccountVersion: account.sourceVersion, comment: explanation }
  }
  requireQuery(payment)
  if (action === 'RESEND_ORIGINAL') { requireWindow(payment, now); if (payment.operation!.status !== 'NOT_FOUND' || payment.operation!.disputed) throw new Error('只有明确查无的原交易可以按原编号重发。') }
  return { action, authorizationVersion: payment.version, operationVersion: payment.operation!.version, comment: explanation }
}
/** 成功回执必须与原意图绑定，回放后也重新读取实时资金状态。 */
export function validateFinancePaymentReceipt(receipt: FinancePaymentReceipt, view: FinancePaymentView, input: PaymentAuthorizationInput | FinancePaymentActionInput) {
  const authorize = 'roundNo' in input
  if (!receipt || receipt.applicationId !== view.applicationId || receipt.businessId !== view.businessId || receipt.roundNo !== view.roundNo || !receipt.auditEventId || !receipt.authorizationId || !time(receipt.expiresAt)
      || (authorize ? receipt.action !== 'AUTHORIZE' || receipt.authorizationVersion !== 1 || receipt.operationVersion !== null
        : receipt.action !== input.action || receipt.authorizationId !== view.payment?.id || receipt.authorizationVersion !== input.authorizationVersion + (input.action === 'QUERY' ? 0 : 1)
          || (input.action === 'VOID' ? receipt.operationVersion !== null : !positive(receipt.operationVersion!)
            || (input.action === 'RETIRE' ? receipt.operationVersion! < input.operationVersion! || receipt.operationVersion! > input.operationVersion! + 1 : receipt.operationVersion! <= input.operationVersion!)))) throw new Error('财务回执不匹配，请刷新核对原授权。')
}
export function validateCashierPaymentReceipt(receipt: CashierPaymentReceipt, id: string, input: CashierPaymentActionInput) {
  if (!receipt || receipt.authorizationId !== id || receipt.authorizationVersion !== input.authorizationVersion || receipt.action !== input.action || !receipt.auditEventId
      || (input.action === 'EXECUTE' ? !receipt.requestId || receipt.operationVersion !== null : receipt.requestId !== null || !positive(receipt.operationVersion!) || receipt.operationVersion! <= input.operationVersion!)) throw new Error('出纳回执不匹配，请刷新核对原交易。')
}
const issues: Record<string, string> = { FINANCE_RETIRED: '财务已安全停止未发送原命令', PAYMENT_REJECTED: '资金系统已明确拒绝本次付款', NOT_CONFIGURED: '资金服务尚未配置', TARGET_CHANGED: '原资金服务配置已变化', TIMEOUT: '资金服务响应超时', CONNECTION: '暂时无法连接资金服务', AUTHENTICATION: '资金服务认证失败', REMOTE_FAILURE: '资金服务暂不可用', INVALID_RESPONSE: '资金响应未通过校验', RESPONSE_TOO_LARGE: '资金响应未通过校验', SOURCE_CHANGED: '原批准、凭证或人员依据已变化', ACCOUNT_CHANGED: '账户已变化，已停止本次新发送', AUTHORIZATION_EXPIRED: '原授权期限已过', LEASE_EXPIRED: '处理超时，正在恢复原操作', INSUFFICIENT_FUNDS: '出款账户资金不足', ACCOUNT_UNAVAILABLE: '收款账户不可用', DEBIT_ACCOUNT_UNAVAILABLE: '出款账户不可用', INCONSISTENT_OBSERVATION: '资金结果相互矛盾，需要对账', STALE_OBSERVATION: '资金系统返回了旧版本结果', RECHECK_REQUESTED: '已登记原交易查询' }
export function paymentIssue(code: string | null) { return code ? issues[code] ?? '付款依据或外部结果需要核对' : '' }
export function paymentError(cause: unknown) {
  const error = cause as { status?: number; code?: string }
  if (error.status === 403 || error.status === 404) return '当前身份无权查看或办理这笔付款。'
  if (error.status === 409) return '付款或业务版本已变化，请刷新后核对原操作。'
  if (error.status === 0 || error.code === 'PENDING_REQUEST_CHANGED') return '结果尚未确认，请在未确认操作中恢复原请求，再刷新状态。'
  if (error.code === 'FINANCE_GATEWAY_UNAVAILABLE') return '资金目录暂不可用，请稍后重新查询。'
  if (error.code === 'PAYMENT_AUTHORIZATION_EXPIRED') return '付款授权已到期，请刷新后由财务处理。'
  if (error.code?.startsWith('PAYMENT_')) return '原付款条件已变化，请刷新核对授权、账户与原交易。'
  return cause instanceof Error ? cause.message : '付款操作未完成，请刷新核对。'
}
