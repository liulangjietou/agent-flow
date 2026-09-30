import { amountMinor, type Money } from './expenses.js'
import { paymentOperationLabels } from './payments.js'
import { supplierIssueLabels } from './supplierFinance.js'

export { paymentOperationLabels }
export const supplierSettlementLabels = { QUEUED: '等待核销', CHECKING: '正在复核结算依据', SETTLING: '正在请求 ERP 核销', UNKNOWN: 'ERP 结果待确认', QUERYING: '正在查询原核销', SETTLED: 'ERP 已确认核销', REJECTED: 'ERP 明确拒绝核销', NOT_FOUND: '原核销暂未查到', RECONCILING: 'ERP 核销存在争议', VOIDED: '本次核销发送已停止' }
export const settlementPreparationLabels = { QUEUED: '等待读取结算依据', RUNNING: '正在读取结算依据', READY: '已登记原结算指令', BLOCKED: '结算依据需要处理', VOIDED: '本次结算准备已停止' }
export const settlementActionLabels = { PREPARE: '登记应付结算', QUERY: '查询原核销', RETRY: '按原编号重试核销', RETIRE: '安全结束本次核销' }
export const settlementRejectionLabels = { ACCOUNTING_PERIOD_CLOSED: '所选会计期间已关闭', HOLD_UNAVAILABLE: '原应付预留不可用', HOLD_CHANGED: '原应付预留已变化', PAYMENT_UNAVAILABLE: '原银行付款不可用', PAYMENT_CHANGED: '原银行付款已变化', PAYABLE_CHANGED: '原应付已变化', ALREADY_SETTLED: 'ERP 提示已有结算，请核对原账务' }
export const settlementIssueLabels: Record<string, string> = { ...supplierIssueLabels, ACCOUNTING_PERIOD_REJECTED: '所选会计日期不可用，请核对会计期间后重新登记。', EVIDENCE_CHANGED: '原付款、应付预留或会计期间已变化，发送已停止。', FINANCE_RETIRED: '财务已安全结束本次核销。', STALE_OBSERVATION: 'ERP 返回了更早的核销事实，请保持原编号查询。', INCONSISTENT_OBSERVATION: 'ERP 核销事实存在矛盾，需要核对原账务。', RECHECK_REQUESTED: '原核销查询已登记。' }
export type SupplierSettlementAction = 'QUERY' | 'RETRY' | 'RETIRE'
export interface SupplierSettlementBinding { paymentId: string; requestId: string; applicationId: string; roundNo: number }
export interface SupplierSettlementOperation {
  id: string; version: number; status: keyof typeof supplierSettlementLabels; financeActor: string; accountingDate: string; periodReference: string
  createdAt: string; updatedAt: string; attempts: number; dispatches: number; observedStatus: 'PENDING' | 'SETTLED' | 'REJECTED' | 'NOT_FOUND' | null; disputed: boolean; issue: string | null
  rejection: keyof typeof settlementRejectionLabels | null
  posting: { settlementReference: string; voucherReference: string; amount: Money; settledAt: string } | null
  retirement: { retiredBy: string; retiredAt: string; basis: 'NEVER_DISPATCHED' | 'CONFIRMED_REJECTED' } | null
  actions: { query: boolean; retryOriginal: boolean; retire: boolean }
}
export interface SupplierSettlementView extends SupplierSettlementBinding {
  legalEntityId: string; supplierName: string; amount: Money; maskedPayeeAccount: string; minimumAccountingDate: string | null
  bank: { version: number; status: keyof typeof paymentOperationLabels; cashier: string; updatedAt: string; observedStatus: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'REVERSED' | 'NOT_FOUND' | null; paymentReference: string | null; receiptReference: string | null; completedAt: string | null; disputed: boolean } | null
  preparation: { id: string; version: number; status: keyof typeof settlementPreparationLabels; financeActor: string; accountingDate: string; updatedAt: string; issue: string | null } | null
  activeSettlementId: string | null; items: SupplierSettlementOperation[]; nextBeforeId: string | null
  completion: { settlementId: string; settlementVersion: number; completedAt: string } | null; canPrepare: boolean
}
export interface SupplierSettlementPrepareInput { paymentVersion: number; accountingDate: string; comment: string }
export interface SupplierSettlementActionInput { action: SupplierSettlementAction; settlementVersion: number; comment: string }
export interface SupplierSettlementReceipt extends SupplierSettlementBinding {
  action: 'PREPARE' | SupplierSettlementAction; preparationId: string | null; preparationVersion: number | null; settlementId: string | null; settlementVersion: number | null; auditEventId: string
}
const bindingKeys = ['paymentId', 'requestId', 'applicationId', 'roundNo'] as const
const positive = (value: number) => Number.isSafeInteger(value) && value > 0
const count = (value: number) => Number.isSafeInteger(value) && value >= 0
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value === value.trim() && !/[\u0000-\u001f\u007f]/.test(value)
const known = (labels: object, value: string) => Object.prototype.hasOwnProperty.call(labels, value)
const time = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const calendarDate = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$/.test(value) && time(value) && new Date(value).toISOString().slice(0, 10) === value
const money = (value: Money | null): value is Money => !!value && typeof value.value === 'string' && /^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(value.value) && /^[A-Z]{3}$/.test(value.currency) && amountMinor(value.value) > 0n
const mask = (value: string) => text(value) && /(?:\*{2,}|•{2,}|[xX]{2,})/.test(value) && /^[0-9*•xX -]+$/.test(value) && !/[0-9]{5,}/.test(value) && value.replace(/[^0-9]/g, '').length <= 8
const issue = (value: string | null) => value === null || known(settlementIssueLabels, value)
const activePreparation = (view: SupplierSettlementView) => !!view.preparation && ['QUEUED', 'RUNNING'].includes(view.preparation.status)
const safeRetirement = (operation: SupplierSettlementOperation) => operation.dispatches === 0 && ['QUEUED', 'CHECKING', 'VOIDED'].includes(operation.status)
  || operation.status === 'REJECTED' && !operation.disputed && operation.observedStatus === 'REJECTED' && operation.rejection !== null && operation.rejection !== 'ALREADY_SETTLED'

/** 三项事实分别校验，历史完成记录不能抹掉银行或 ERP 后续争议。 */
export function validateSupplierSettlement(value: SupplierSettlementView, expected: SupplierSettlementBinding): SupplierSettlementView {
  if (!value || !bindingKeys.every(key => value[key] === expected[key]) || ![value.paymentId, value.requestId, value.applicationId, value.legalEntityId, value.supplierName].every(text)
      || !positive(value.roundNo) || !money(value.amount) || !mask(value.maskedPayeeAccount) || typeof value.canPrepare !== 'boolean'
      || value.minimumAccountingDate !== null && !calendarDate(value.minimumAccountingDate) || value.activeSettlementId !== null && !text(value.activeSettlementId)
      || !Array.isArray(value.items) || value.items.length > 100 || value.nextBeforeId !== null && (!text(value.nextBeforeId) || value.nextBeforeId !== value.items[value.items.length - 1]?.id)) throw new Error('结算身份、金额或历史范围不完整，请刷新原申请。')
  const bank = value.bank, preparation = value.preparation, completion = value.completion
  if (bank !== null && (!bank || !positive(bank.version) || !known(paymentOperationLabels, bank.status) || !text(bank.cashier) || !time(bank.updatedAt) || typeof bank.disputed !== 'boolean'
      || bank.observedStatus !== null && !['PENDING', 'SUCCEEDED', 'FAILED', 'REVERSED', 'NOT_FOUND'].includes(bank.observedStatus)
      || bank.paymentReference !== null && !text(bank.paymentReference) || bank.receiptReference !== null && !text(bank.receiptReference)
      || bank.completedAt !== null && (!time(bank.completedAt) || Date.parse(bank.completedAt) > Date.parse(bank.updatedAt))
      || ['SUCCEEDED', 'REVERSED'].includes(bank.observedStatus ?? '') && (!bank.paymentReference || !bank.receiptReference || !bank.completedAt)
      || ['SUCCEEDED', 'REVERSED'].includes(bank.status) && (bank.disputed || bank.observedStatus !== bank.status))) throw new Error('原银行回单不完整，请刷新核对。')
  if (preparation !== null && (!preparation || !text(preparation.id) || !positive(preparation.version) || !known(settlementPreparationLabels, preparation.status) || !text(preparation.financeActor)
      || !calendarDate(preparation.accountingDate) || !time(preparation.updatedAt) || !issue(preparation.issue))) throw new Error('结算准备依据不完整，请刷新。')
  const ids = new Set<string>()
  for (const operation of value.items) {
    if (!operation || !text(operation.id) || ids.has(operation.id) || !positive(operation.version) || !known(supplierSettlementLabels, operation.status) || !text(operation.financeActor)
        || !calendarDate(operation.accountingDate) || !text(operation.periodReference) || !time(operation.createdAt) || !time(operation.updatedAt) || Date.parse(operation.updatedAt) < Date.parse(operation.createdAt)
        || !count(operation.attempts) || !count(operation.dispatches) || operation.dispatches > operation.attempts || typeof operation.disputed !== 'boolean' || !issue(operation.issue)
        || operation.observedStatus !== null && !['PENDING', 'SETTLED', 'REJECTED', 'NOT_FOUND'].includes(operation.observedStatus)
        || operation.rejection !== null && !known(settlementRejectionLabels, operation.rejection) || !operation.actions
        || ['query', 'retryOriginal', 'retire'].some(key => typeof operation.actions[key as keyof typeof operation.actions] !== 'boolean')) throw new Error('原核销状态或历史记录不完整，请刷新。')
    ids.add(operation.id)
    const posting = operation.posting, retirement = operation.retirement
    if (posting !== null && (!posting || !text(posting.settlementReference) || !text(posting.voucherReference) || !money(posting.amount) || posting.amount.value !== value.amount.value || posting.amount.currency !== value.amount.currency
        || !time(posting.settledAt) || Date.parse(posting.settledAt) < Date.parse(operation.createdAt) || Date.parse(posting.settledAt) > Date.parse(operation.updatedAt))
        || (operation.observedStatus === 'SETTLED') !== (posting !== null) || (operation.observedStatus === 'REJECTED') !== (operation.rejection !== null)
        || ['SETTLED', 'REJECTED', 'NOT_FOUND'].includes(operation.status) && (operation.disputed || operation.observedStatus !== operation.status)
        || operation.status === 'SETTLED' && operation.dispatches === 0) throw new Error('ERP 核销凭据与原金额或状态不一致，请刷新核对。')
    if (retirement !== null && (!retirement || !text(retirement.retiredBy) || !time(retirement.retiredAt) || Date.parse(retirement.retiredAt) < Date.parse(operation.updatedAt)
        || !safeRetirement(operation) || (retirement.basis === 'NEVER_DISPATCHED' ? operation.status !== 'VOIDED' || operation.dispatches !== 0 : retirement.basis !== 'CONFIRMED_REJECTED' || operation.status !== 'REJECTED')
        || Object.values(operation.actions).some(Boolean) || value.activeSettlementId === operation.id)) throw new Error('原核销安全结束依据不完整，请刷新。')
    if (Object.values(operation.actions).some(Boolean) && (retirement !== null || value.activeSettlementId !== operation.id)
        || operation.actions.query && (operation.dispatches === 0 || ['QUEUED', 'CHECKING', 'SETTLING', 'QUERYING'].includes(operation.status))
        || operation.actions.retryOriginal && (operation.status !== 'NOT_FOUND' || operation.disputed)
        || operation.actions.retire && !safeRetirement(operation)) throw new Error('核销办理能力与原状态不一致，请刷新。')
  }
  if (completion !== null && (!completion || !text(completion.settlementId) || !positive(completion.settlementVersion) || !time(completion.completedAt))) throw new Error('本地应付完成凭据不完整，请刷新。')
  if (completion) {
    const original = value.items.find(item => item.id === completion.settlementId)
    if (original && (completion.settlementVersion > original.version || original.retirement !== null || Date.parse(completion.completedAt) < Date.parse(original.createdAt)
        || completion.settlementVersion === original.version && original.status !== 'SETTLED')) throw new Error('本地应付完成版本与原核销不一致，请刷新。')
  }
  if (value.canPrepare && (!bank || bank.status !== 'SUCCEEDED' || bank.disputed || !value.minimumAccountingDate || activePreparation(value) || value.activeSettlementId !== null || completion !== null)) throw new Error('当前付款或结算状态不允许另行登记。')
  return value
}

/** 会计日期由财务明确选择，客户端只提交原付款版本、日期和说明。 */
export function supplierSettlementPreparationInput(view: SupplierSettlementView, accountingDate: string, comment: string): SupplierSettlementPrepareInput {
  validateSupplierSettlement(view, view)
  if (!view.canPrepare) throw new Error('当前身份或状态不允许登记结算，请刷新。')
  if (!calendarDate(accountingDate) || accountingDate < view.minimumAccountingDate!) throw new Error('请选择有效会计日期，不能早于原法人当地到账日期。')
  return { paymentVersion: view.bank!.version, accountingDate, comment: explanation(comment) }
}
export function supplierSettlementAllowed(view: SupplierSettlementView | null, id: string, action: SupplierSettlementAction): boolean {
  const operation = view?.items.find(item => item.id === id)
  return !!operation?.actions[{ QUERY: 'query', RETRY: 'retryOriginal', RETIRE: 'retire' }[action] as keyof typeof operation.actions]
}
/** 查询、重试和安全结束始终携带同一原核销编号及展示版本。 */
export function supplierSettlementActionInput(view: SupplierSettlementView, id: string, action: SupplierSettlementAction, comment: string): SupplierSettlementActionInput {
  validateSupplierSettlement(view, view)
  if (!supplierSettlementAllowed(view, id, action)) throw new Error('当前身份或状态不允许办理，请刷新原核销。')
  return { action, settlementVersion: view.items.find(item => item.id === id)!.version, comment: explanation(comment) }
}
function explanation(comment: string) {
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的操作说明。')
  return comment.trim()
}
/** 保存回执不代表 ERP 成功，身份、动作和精确版本必须对应本次确认。 */
export function validateSupplierSettlementReceipt(receipt: SupplierSettlementReceipt, view: SupplierSettlementView, action: SupplierSettlementReceipt['action'], id?: string) {
  if (!receipt || !bindingKeys.every(key => receipt[key] === view[key]) || receipt.action !== action || !text(receipt.auditEventId)) throw new Error('结算回执未对应原申请，请恢复原请求后核对。')
  const operation = view.items.find(item => item.id === id)
  if (action === 'PREPARE' ? !text(receipt.preparationId) || receipt.preparationVersion !== 1 || receipt.settlementId !== null || receipt.settlementVersion !== null
      : !operation || receipt.preparationId !== null || receipt.preparationVersion !== null || receipt.settlementId !== id
        || receipt.settlementVersion !== operation.version + (action !== 'RETIRE' || ['QUEUED', 'CHECKING'].includes(operation.status) ? 1 : 0)) throw new Error('结算回执版本不完整，请恢复原请求后核对。')
}
export function supplierSettlementError(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string } | null)?.code
  const labels: Record<string, string> = { FORBIDDEN: '当前岗位、法人任职、字段权限或三方分离规则不允许办理。', NOT_FOUND: '当前范围内无法读取原付款或核销。', CONCURRENCY_CONFLICT: '原付款或核销版本已变化，请刷新。', SUPPLIER_SETTLEMENT_PENDING: '已有结算准备或原核销，请刷新其结果。', SUPPLIER_SETTLEMENT_PREPARATION_CONFLICT: '结算准备已变化，请刷新。', SUPPLIER_PAYABLE_SETTLEMENT_STATE_CONFLICT: '原核销状态已变化，请刷新。', SUPPLIER_SETTLEMENT_RETIREMENT_UNSAFE: '原核销尚未确认安全结束，请保持原编号查询。', REQUEST_TIMEOUT: '操作结果未确认，请恢复原请求后刷新。', PENDING_REQUEST_CHANGED: '原请求尚未确认，请先恢复原请求。', PROCUREMENT_PAYMENT_SOURCE_CHANGED: '原采购批准或应付占用已变化。', PAYMENT_ACTOR_UNAVAILABLE: '原法人财务任职已失效。', INVALID_SUPPLIER_SETTLEMENT_QUERY: '历史游标已失效，请返回最新状态。' }
  return code && labels[code] || '本次结算办理未完成，请核对原请求与当前状态。'
}
