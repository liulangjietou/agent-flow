import { amountMinor, type Money } from './expenses.js'
import { paymentOperationLabels, type DebitAccount, type CashierPaymentAction } from './payments.js'
import { holdLabels, supplierIssueLabels } from './supplierFinance.js'

export { paymentOperationLabels, holdLabels }
export type SupplierCashierAction = CashierPaymentAction
export const supplierPreparationLabels = { QUEUED: '等待复核原应付', RUNNING: '正在复核付款依据', READY: '已登记银行指令', BLOCKED: '付款依据需要重新核对', VOIDED: '本次选择已停止', EXPIRED: '财务授权已到期' }
export const supplierCashierLabels = { EXECUTE: '选择账户并登记付款', QUERY: '查询原银行交易', RESEND_ORIGINAL: '按原编号重试付款' }
export const supplierCashierKeys = { EXECUTE: 'execute', QUERY: 'query', RESEND_ORIGINAL: 'resendOriginal' } as const
export const supplierBankIssues: Record<string, string> = { ...supplierIssueLabels,
  EVIDENCE_CHANGED: '原预留、应付或所选账户已变化，发送已停止。', AUTHORIZATION_EXPIRED: '财务授权已到期，不能继续新的发送。',
  ACCOUNT_UNAVAILABLE: '供应商收款账户不可用。', DEBIT_ACCOUNT_UNAVAILABLE: '所选出款账户不可用。', INSUFFICIENT_FUNDS: '出款账户余额不足。', APPROVAL_CHANGED: '原采购批准已变化。', ACCOUNT_CHANGED: '原账户信息已变化。', VOUCHER_UNAVAILABLE: '原财务凭证不可用。', PAYMENT_REJECTED: '资金系统明确拒绝本次付款。',
  STALE_OBSERVATION: '银行返回了更早的结果，需要核对原交易。', INCONSISTENT_OBSERVATION: '银行结果存在矛盾，需要核对原交易。', RECHECK_REQUESTED: '原银行交易查询已登记。' }
export interface SupplierCashierView {
  authorizationId: string; requestId: string; applicationId: string; roundNo: number; legalEntityId: string; employeeId: string; supplierName: string
  amount: Money; maskedPayeeAccount: string; authorizedBy: string; authorizedAt: string; expiresAt: string; retiredAt: string | null
  hold: { version: number; status: keyof typeof holdLabels; updatedAt: string }
  preparation: null | { id: string; version: number; status: keyof typeof supplierPreparationLabels; cashier: string; updatedAt: string; issue: string | null }
  operation: null | { version: number; status: keyof typeof paymentOperationLabels; cashier: string; updatedAt: string; observedStatus: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'REVERSED' | 'NOT_FOUND' | null; paymentReference: string | null; receiptReference: string | null; completedAt: string | null; disputed: boolean; issue: string | null }
  actions: { execute: boolean; query: boolean; resendOriginal: boolean }
}
export interface SupplierCashierPage { items: SupplierCashierView[]; nextBeforeId: string | null }
export interface SupplierCashierAccounts { authorizationId: string; holdVersion: number; validUntil: string; items: DebitAccount[] }
export interface SupplierCashierInput { action: SupplierCashierAction; holdVersion?: number; operationVersion?: number; debitAccountReference?: string; debitAccountVersion?: string; comment: string }
export interface SupplierCashierReceipt { authorizationId: string; action: SupplierCashierAction; preparationId: string | null; preparationVersion: number | null; operationVersion: number | null; auditEventId: string }
const positive = (value: number) => Number.isSafeInteger(value) && value > 0
const time = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value === value.trim() && !/[\u0000-\u001f\u007f]/.test(value)
const known = (labels: object, value: string) => Object.prototype.hasOwnProperty.call(labels, value)
const mask = (value: string) => text(value) && /(?:\*{2,}|•{2,}|[xX]{2,})/.test(value) && /^[0-9*•xX -]+$/.test(value) && !/[0-9]{5,}/.test(value) && value.replace(/[^0-9]/g, '').length <= 8
const issue = (value: string | null) => value === null || known(supplierBankIssues, value)

/** 金额保留十进制字符串，银行回执与原授权、准备状态必须同时成立。 */
export function validateSupplierCashier(value: SupplierCashierView, id = value?.authorizationId): SupplierCashierView {
  if (!value || value.authorizationId !== id || ![value.authorizationId, value.requestId, value.applicationId, value.legalEntityId, value.employeeId, value.supplierName, value.authorizedBy].every(text)
      || !positive(value.roundNo) || !value.amount || typeof value.amount.value !== 'string' || !/^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(value.amount.value)
      || !/^[A-Z]{3}$/.test(value.amount.currency) || amountMinor(value.amount.value) <= 0n || !mask(value.maskedPayeeAccount)
      || !time(value.authorizedAt) || !time(value.expiresAt) || Date.parse(value.expiresAt) <= Date.parse(value.authorizedAt)
      || value.retiredAt !== null && !time(value.retiredAt) || !value.hold || !positive(value.hold.version) || !known(holdLabels, value.hold.status) || !time(value.hold.updatedAt)
      || !value.actions || Object.values(supplierCashierKeys).some(key => typeof value.actions[key] !== 'boolean')) throw new Error('供应商付款身份、金额或原预留未通过核对，请刷新。')
  const preparation = value.preparation, operation = value.operation
  if (preparation !== null && (!preparation || !text(preparation.id) || !positive(preparation.version) || !known(supplierPreparationLabels, preparation.status) || !text(preparation.cashier) || !time(preparation.updatedAt) || !issue(preparation.issue))
      || operation !== null && (!operation || !positive(operation.version) || !known(paymentOperationLabels, operation.status) || !text(operation.cashier) || !time(operation.updatedAt)
        || typeof operation.disputed !== 'boolean' || !issue(operation.issue) || operation.observedStatus !== null && !['PENDING', 'SUCCEEDED', 'FAILED', 'REVERSED', 'NOT_FOUND'].includes(operation.observedStatus)
        || !preparation || preparation.status !== 'READY' || preparation.cashier !== operation.cashier
        || ['SUCCEEDED', 'REVERSED'].includes(operation.status) && (operation.disputed || operation.observedStatus !== operation.status || !text(operation.paymentReference) || !text(operation.receiptReference) || !time(operation.completedAt)
          || Date.parse(operation.completedAt) < Date.parse(value.authorizedAt) || Date.parse(operation.completedAt) > Date.parse(operation.updatedAt)))
      || preparation?.status === 'READY' && !operation) throw new Error('出纳准备或银行结果不完整，请刷新核对。')
  if (value.actions.execute && (value.retiredAt !== null || value.hold.status !== 'HELD' || operation !== null || preparation && ['QUEUED', 'RUNNING', 'READY'].includes(preparation.status))
      || value.actions.query && (!operation || ['QUEUED', 'CHECKING', 'SENDING', 'QUERYING'].includes(operation.status))
      || value.actions.resendOriginal && (value.retiredAt !== null || value.hold.status !== 'HELD' || operation?.status !== 'NOT_FOUND' || operation.observedStatus !== 'NOT_FOUND' || operation.disputed)) throw new Error('当前办理能力与原付款状态不一致，请刷新。')
  return value
}

/** 展示后的授权到期也会禁止新的执行，查询原交易不受该窗口限制。 */
export function supplierCashierAllowed(view: SupplierCashierView | null, action: SupplierCashierAction, now = Date.now()) {
  return !!view && view.actions[supplierCashierKeys[action]] && (action === 'QUERY' || Date.parse(view.expiresAt) > now)
}
/** 账户目录必须属于本授权所见预留；完整账号、重复引用、错币种和过期选项均不展示。 */
export function validateSupplierCashierAccounts(options: SupplierCashierAccounts, view: SupplierCashierView, now = Date.now()) {
  if (!options || options.authorizationId !== view.authorizationId || options.holdVersion !== view.hold.version || !time(options.validUntil)
      || Date.parse(options.validUntil) <= now || Date.parse(options.validUntil) > now + 300_000 || !Array.isArray(options.items) || options.items.length > 200
      || options.items.some(account => !account || ![account.reference, account.sourceVersion, account.displayName].every(text) || !mask(account.maskedAccount) || account.currency !== view.amount.currency)
      || new Set(options.items.map(account => account.reference)).size !== options.items.length) throw new Error('出款账户范围或有效期已变化，请重新查询。')
  return options
}
/** 确认时再核对授权、目录时效与实际选择，不发送客户端金额或收款账户。 */
export function supplierCashierInput(view: SupplierCashierView, action: SupplierCashierAction, comment: string, options: SupplierCashierAccounts | null, selection: string, now = Date.now()): SupplierCashierInput {
  validateSupplierCashier(view)
  if (!supplierCashierAllowed(view, action, now)) throw new Error('当前状态或授权期限不允许此操作，请刷新。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的办理说明。')
  if (action !== 'EXECUTE') return { action, operationVersion: view.operation!.version, comment: comment.trim() }
  if (!options) throw new Error('请先读取并选择本次出款账户。')
  validateSupplierCashierAccounts(options, view, now)
  const account = options.items.find(item => item.reference === selection)
  if (!account) throw new Error('请选择当前目录中的出款账户。')
  return { action, holdVersion: view.hold.version, debitAccountReference: account.reference, debitAccountVersion: account.sourceVersion, comment: comment.trim() }
}
/** 最小回执只证明办理已保存，必须与当前原意图和精确下一版本匹配。 */
export function validateSupplierCashierReceipt(receipt: SupplierCashierReceipt, id: string, input: SupplierCashierInput) {
  if (!receipt || receipt.authorizationId !== id || receipt.action !== input.action || !text(receipt.auditEventId)
      || (input.action === 'EXECUTE' ? !text(receipt.preparationId) || receipt.preparationVersion !== 1 || receipt.operationVersion !== null
        : receipt.preparationId !== null || receipt.preparationVersion !== null || receipt.operationVersion !== input.operationVersion! + 1)) throw new Error('办理回执未对应原请求，请恢复原请求后刷新核对。')
}
export function supplierCashierError(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string } | null)?.code
  const errors: Record<string, string> = { FORBIDDEN: '当前角色或三方分离规则不允许办理。', NOT_FOUND: '当前法人任职范围内无法读取该付款。', CONCURRENCY_CONFLICT: '原预留或银行状态已变化，请刷新后核对。', SUPPLIER_PAYMENT_EXECUTION_PENDING: '原授权已有出纳选择，请刷新处理结果。', SUPPLIER_PAYMENT_ALREADY_REGISTERED: '原授权已有银行指令，请继续核对原交易。', SUPPLIER_PAYMENT_STATE_CONFLICT: '原银行交易状态已变化，请刷新核对。', SUPPLIER_PAYMENT_AUTHORIZATION_EXPIRED: '财务授权已到期，不能登记新的发送。', PAYMENT_ACTOR_UNAVAILABLE: '原财务或出纳的法人任职已失效。', PAYMENT_ACCOUNT_EVIDENCE_EXPIRED: '出款账户目录已到期，请重新读取。', SUPPLIER_PAYMENT_EVIDENCE_CHANGED: '原应付预留或所选账户已变化，请先核对。', PROCUREMENT_PAYMENT_SOURCE_CHANGED: '原采购批准或应付占用已变化。', REQUEST_TIMEOUT: '本次操作结果尚未确认，请恢复原请求后刷新。', FINANCE_GATEWAY_UNAVAILABLE: '暂时无法读取资金系统，请稍后重新查询。' }
  return code && errors[code] || '本次办理未完成，请核对原请求与当前付款状态。'
}
