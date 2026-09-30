import { amountMinor, type Money } from './expenses.js'
import { paymentOperationLabels } from './payments.js'
import { supplierIssueLabels } from './supplierFinance.js'
import type { SupplierSettlementBinding } from './supplierSettlement.js'

export { paymentOperationLabels }
export const adjustmentLabels = { QUEUED: '等待账务调整', CHECKING: '正在复核调整依据', ADJUSTING: '正在请求 ERP 调整', UNKNOWN: 'ERP 结果待确认', QUERYING: '正在查询原调整', ADJUSTED: 'ERP 已确认调整', REJECTED: 'ERP 明确拒绝调整', NOT_FOUND: '原调整暂未查到', RECONCILING: 'ERP 调整存在争议', VOIDED: '本次调整发送已停止' }
export const adjustmentPreparationLabels = { QUEUED: '等待读取调整依据', RUNNING: '正在读取调整依据', READY: '已登记独立调整指令', BLOCKED: '调整依据需要处理', VOIDED: '本次调整准备已停止' }
export const adjustmentActionLabels = { PREPARE: '登记独立调整', QUERY: '查询原调整', RETRY: '按原编号重试调整', RETIRE: '安全结束本次调整' }
export const adjustmentRejectionLabels = { ACCOUNTING_PERIOD_CLOSED: '所选会计期间已关闭', ORIGINAL_CHANGED: '原付款或核销事实已变化', RETURNS_CHANGED: '实际回款事实已变化', PREVIOUS_CHANGED: '前次调整已变化', PAYABLE_CHANGED: '原应付余额已变化', ALREADY_ADJUSTED: 'ERP 提示资金已被调整，请核对原账务' }
export const adjustmentIssueLabels: Record<string, string> = { ...supplierIssueLabels, ACCOUNTING_PERIOD_REJECTED: '所选会计日期不可用，请核对后重新登记。', EVIDENCE_CHANGED: '资金、原核销或前次调整依据已变化，发送已停止。', FINANCE_RETIRED: '财务已安全结束本次调整。', STALE_OBSERVATION: 'ERP 返回了更早的调整事实，请保持原编号查询。', INCONSISTENT_OBSERVATION: 'ERP 调整事实存在矛盾，需要核对原账务。', RECHECK_REQUESTED: '原调整查询已登记。' }
export type SupplierAdjustmentAction = 'QUERY' | 'RETRY' | 'RETIRE'
export interface AdjustmentCompletion { adjustmentId: string; adjustmentVersion: number; returnVersion: number; accountedEntries: number; completedAt: string }
export interface SupplierAdjustmentOperation {
  id: string; version: number; status: keyof typeof adjustmentLabels; financeActor: string; accountingDate: string; periodReference: string
  returnVersion: number; returnedAmount: Money; totalReturned: Money; netPaid: Money; recognizesOriginalPayment: boolean
  createdAt: string; updatedAt: string; attempts: number; dispatches: number; observedStatus: 'PENDING' | 'ADJUSTED' | 'REJECTED' | 'NOT_FOUND' | null; disputed: boolean; issue: string | null
  rejection: keyof typeof adjustmentRejectionLabels | null
  posting: { adjustmentReference: string; recognitionVoucherReference: string; returnedAmount: Money; totalReturned: Money; netPaid: Money; payableSettledBefore: Money; payableSettledAfter: Money; entries: { transactionReference: string; amount: Money; voucherReference: string; entryReference: string }[]; adjustedAt: string } | null
  retirement: { retiredBy: string; retiredAt: string; basis: 'NEVER_DISPATCHED' | 'CONFIRMED_REJECTED' } | null
  completion: AdjustmentCompletion | null; actions: { query: boolean; retryOriginal: boolean; retire: boolean }
}
export interface SupplierAdjustmentView extends SupplierSettlementBinding {
  legalEntityId: string; supplierName: string; amount: Money; maskedPayeeAccount: string
  bank: { version: number; status: keyof typeof paymentOperationLabels; cashier: string; updatedAt: string; disputed: boolean } | null
  returnVersion: number; reviewRequired: boolean; totalReturned: Money; accountedReturned: Money; pendingReturned: Money; netPaid: Money; minimumAccountingDate: string | null
  preparation: { id: string; version: number; status: keyof typeof adjustmentPreparationLabels; financeActor: string; accountingDate: string; updatedAt: string; issue: string | null } | null
  activeAdjustmentId: string | null; items: SupplierAdjustmentOperation[]; nextBeforeId: string | null; completion: AdjustmentCompletion | null; canPrepare: boolean
}
export interface SupplierAdjustmentPrepareInput { paymentVersion: number; returnVersion: number; accountingDate: string; comment: string }
export interface SupplierAdjustmentActionInput { action: SupplierAdjustmentAction; adjustmentVersion: number; comment: string }
export interface SupplierAdjustmentReceipt extends SupplierSettlementBinding {
  action: 'PREPARE' | SupplierAdjustmentAction; preparationId: string | null; preparationVersion: number | null; adjustmentId: string | null; adjustmentVersion: number | null; auditEventId: string
}
const bindingKeys = ['paymentId', 'requestId', 'applicationId', 'roundNo'] as const
const positive = (value: number) => Number.isSafeInteger(value) && value > 0
const count = (value: number) => Number.isSafeInteger(value) && value >= 0
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value === value.trim() && !/[\u0000-\u001f\u007f]/.test(value)
const known = (labels: object, value: string) => Object.prototype.hasOwnProperty.call(labels, value)
const time = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const calendarDate = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$/.test(value) && time(value) && new Date(value).toISOString().slice(0, 10) === value
const money = (value: Money | null): value is Money => !!value && typeof value.value === 'string' && /^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(value.value) && /^[A-Z]{3}$/.test(value.currency)
const minor = (value: Money) => amountMinor(value.value)
const same = (left: Money, right: Money) => left.currency === right.currency && minor(left) === minor(right)
const mask = (value: string) => text(value) && /(?:\*{2,}|•{2,}|[xX]{2,})/.test(value) && /^[0-9*•xX -]+$/.test(value) && !/[0-9]{5,}/.test(value) && value.replace(/[^0-9]/g, '').length <= 8
const issue = (value: string | null) => value === null || known(adjustmentIssueLabels, value)
const safeRetirement = (operation: SupplierAdjustmentOperation) => operation.dispatches === 0 && ['QUEUED', 'CHECKING', 'VOIDED'].includes(operation.status)
  || operation.status === 'REJECTED' && !operation.disputed && operation.observedStatus === 'REJECTED' && operation.rejection !== null && operation.rejection !== 'ALREADY_ADJUSTED'
function completionValid(value: AdjustmentCompletion | null, view: SupplierAdjustmentView): boolean {
  if (value === null) return true
  if (!value || !text(value.adjustmentId) || !positive(value.adjustmentVersion) || !positive(value.returnVersion) || value.returnVersion > view.returnVersion
      || !positive(value.accountedEntries) || value.accountedEntries > 100 || !time(value.completedAt)) return false
  const operation = view.items.find(item => item.id === value.adjustmentId)
  return !operation || value.adjustmentVersion <= operation.version && value.returnVersion > operation.returnVersion && operation.retirement === null
    && Date.parse(value.completedAt) >= Date.parse(operation.createdAt) && (value.adjustmentVersion < operation.version || operation.status === 'ADJUSTED')
}

/** 资金使用分为单位精确核对，ERP 成功和历史本地完成不能覆盖当前争议或新增回款。 */
export function validateSupplierAdjustment(value: SupplierAdjustmentView, expected: SupplierSettlementBinding): SupplierAdjustmentView {
  if (!value || !bindingKeys.every(key => value[key] === expected[key]) || ![value.paymentId, value.requestId, value.applicationId, value.legalEntityId, value.supplierName].every(text)
      || !positive(value.roundNo) || !money(value.amount) || minor(value.amount) <= 0n || !mask(value.maskedPayeeAccount) || !count(value.returnVersion)
      || typeof value.canPrepare !== 'boolean' || typeof value.reviewRequired !== 'boolean' || value.minimumAccountingDate !== null && !calendarDate(value.minimumAccountingDate)
      || value.activeAdjustmentId !== null && !text(value.activeAdjustmentId) || !Array.isArray(value.items) || value.items.length > 100
      || value.nextBeforeId !== null && (!text(value.nextBeforeId) || value.nextBeforeId !== value.items[value.items.length - 1]?.id)) throw new Error('调整身份、原金额或历史范围不完整，请刷新原申请。')
  if (![value.totalReturned, value.accountedReturned, value.pendingReturned, value.netPaid].every(item => money(item) && item.currency === value.amount.currency)
      || minor(value.totalReturned) + minor(value.netPaid) !== minor(value.amount) || minor(value.accountedReturned) + minor(value.pendingReturned) !== minor(value.totalReturned)
      || minor(value.pendingReturned) > 0n && !value.reviewRequired || value.returnVersion === 0 && minor(value.totalReturned) !== 0n) throw new Error('已登记、已入账及待入账金额不一致，请刷新核对。')
  const bank = value.bank, preparation = value.preparation
  if (bank !== null && (!bank || !positive(bank.version) || !known(paymentOperationLabels, bank.status) || !text(bank.cashier) || !time(bank.updatedAt) || typeof bank.disputed !== 'boolean'
      || ['SUCCEEDED', 'REVERSED'].includes(bank.status) && bank.disputed)) throw new Error('原银行状态不完整，请刷新核对。')
  if (preparation !== null && (!preparation || !text(preparation.id) || !positive(preparation.version) || !known(adjustmentPreparationLabels, preparation.status) || !text(preparation.financeActor)
      || !calendarDate(preparation.accountingDate) || !time(preparation.updatedAt) || !issue(preparation.issue))) throw new Error('调整准备依据不完整，请刷新。')
  const ids = new Set<string>()
  for (const operation of value.items) {
    if (!operation || !text(operation.id) || ids.has(operation.id) || !positive(operation.version) || !known(adjustmentLabels, operation.status) || !text(operation.financeActor)
        || !calendarDate(operation.accountingDate) || !text(operation.periodReference) || !time(operation.createdAt) || !time(operation.updatedAt) || Date.parse(operation.updatedAt) < Date.parse(operation.createdAt)
        || !positive(operation.returnVersion) || operation.returnVersion > value.returnVersion || ![operation.returnedAmount, operation.totalReturned, operation.netPaid].every(item => money(item) && item.currency === value.amount.currency)
        || minor(operation.returnedAmount) <= 0n || minor(operation.totalReturned) < minor(operation.returnedAmount) || minor(operation.totalReturned) > minor(value.totalReturned)
        || minor(operation.netPaid) + minor(operation.totalReturned) !== minor(value.amount) || typeof operation.recognizesOriginalPayment !== 'boolean'
        || !count(operation.attempts) || !count(operation.dispatches) || operation.dispatches > operation.attempts || typeof operation.disputed !== 'boolean' || !issue(operation.issue)
        || operation.observedStatus !== null && !['PENDING', 'ADJUSTED', 'REJECTED', 'NOT_FOUND'].includes(operation.observedStatus)
        || operation.rejection !== null && !known(adjustmentRejectionLabels, operation.rejection) || !operation.actions
        || ['query', 'retryOriginal', 'retire'].some(key => typeof operation.actions[key as keyof typeof operation.actions] !== 'boolean')) throw new Error('原调整范围或历史状态不完整，请刷新。')
    ids.add(operation.id)
    const posting = operation.posting, retirement = operation.retirement
    if ((operation.observedStatus === 'ADJUSTED') !== (posting !== null) || (operation.observedStatus === 'REJECTED') !== (operation.rejection !== null)
        || ['ADJUSTED', 'REJECTED', 'NOT_FOUND'].includes(operation.status) && (operation.disputed || operation.observedStatus !== operation.status)
        || operation.status === 'ADJUSTED' && operation.dispatches === 0) throw new Error('ERP 调整凭据与当前状态不一致，请刷新。')
    if (posting !== null) {
      if (!posting || !text(posting.adjustmentReference) || !text(posting.recognitionVoucherReference)
          || ![posting.returnedAmount, posting.totalReturned, posting.netPaid, posting.payableSettledBefore, posting.payableSettledAfter].every(item => money(item) && item.currency === value.amount.currency)
          || !same(posting.returnedAmount, operation.returnedAmount) || !same(posting.totalReturned, operation.totalReturned) || !same(posting.netPaid, operation.netPaid)
          || minor(posting.payableSettledBefore) + (operation.recognizesOriginalPayment ? minor(value.amount) : 0n) !== minor(posting.payableSettledAfter) + minor(posting.returnedAmount)
          || !time(posting.adjustedAt) || Date.parse(posting.adjustedAt) < Date.parse(operation.createdAt) || Date.parse(posting.adjustedAt) > Date.parse(operation.updatedAt)
          || !Array.isArray(posting.entries) || !posting.entries.length || posting.entries.length > 100) throw new Error('ERP 调整金额或原应付余额不一致，请刷新。')
      const transactions = new Set<string>(), lines = new Set<string>(); let total = 0n
      for (const entry of posting.entries) {
        const line = JSON.stringify([entry?.voucherReference, entry?.entryReference])
        if (!entry || !text(entry.transactionReference) || !text(entry.voucherReference) || !text(entry.entryReference) || !money(entry.amount) || entry.amount.currency !== value.amount.currency
            || minor(entry.amount) <= 0n || entry.voucherReference === posting.recognitionVoucherReference || transactions.has(entry.transactionReference) || lines.has(line)) throw new Error('回款与 ERP 分录对应不完整或重复，请刷新。')
        transactions.add(entry.transactionReference); lines.add(line); total += minor(entry.amount)
      }
      if (total !== minor(posting.returnedAmount)) throw new Error('本次回款分录合计不一致，请刷新。')
    }
    if (retirement !== null && (!retirement || !text(retirement.retiredBy) || !time(retirement.retiredAt) || Date.parse(retirement.retiredAt) < Date.parse(operation.updatedAt)
        || !safeRetirement(operation) || (retirement.basis === 'NEVER_DISPATCHED' ? operation.status !== 'VOIDED' || operation.dispatches !== 0 : retirement.basis !== 'CONFIRMED_REJECTED' || operation.status !== 'REJECTED')
        || Object.values(operation.actions).some(Boolean) || value.activeAdjustmentId === operation.id)) throw new Error('调整安全结束依据不完整，请刷新。')
    if (!completionValid(operation.completion, value) || operation.completion && operation.completion.adjustmentId !== operation.id
        || Object.values(operation.actions).some(Boolean) && (retirement !== null || !operation.completion && value.activeAdjustmentId !== operation.id)
        || operation.actions.query && (operation.dispatches === 0 || ['QUEUED', 'CHECKING', 'ADJUSTING', 'QUERYING'].includes(operation.status))
        || operation.actions.retryOriginal && (operation.status !== 'NOT_FOUND' || operation.disputed || operation.completion !== null)
        || operation.actions.retire && (!safeRetirement(operation) || operation.completion !== null)) throw new Error('调整办理能力或本地完成版本不一致，请刷新。')
  }
  if (!completionValid(value.completion, value) || (minor(value.accountedReturned) > 0n) !== (value.completion !== null)) throw new Error('本地入账金额与完成依据不一致，请刷新。')
  const completedItem = value.items.find(item => item.id === value.completion?.adjustmentId)
  if (value.completion && completedItem && (!completedItem.completion || (['adjustmentId', 'adjustmentVersion', 'returnVersion', 'accountedEntries', 'completedAt'] as const).some(key => value.completion![key] !== completedItem.completion![key]) || !same(value.accountedReturned, completedItem.totalReturned))) throw new Error('累计入账金额与原调整完成记录不一致，请刷新。')
  if (value.canPrepare && (!bank || !['SUCCEEDED', 'REVERSED'].includes(bank.status) || bank.disputed || !value.minimumAccountingDate || !positive(value.returnVersion)
      || minor(value.pendingReturned) <= 0n || value.activeAdjustmentId !== null || preparation && ['QUEUED', 'RUNNING'].includes(preparation.status))) throw new Error('当前资金或办理状态不允许新建调整。')
  return value
}

/** 明确选择会计日期，只提交页面核对过的银行与回款版本。 */
export function supplierAdjustmentPreparationInput(view: SupplierAdjustmentView, accountingDate: string, comment: string): SupplierAdjustmentPrepareInput {
  validateSupplierAdjustment(view, view)
  if (!view.canPrepare) throw new Error('当前身份或状态不允许登记调整，请刷新。')
  if (!calendarDate(accountingDate) || accountingDate < view.minimumAccountingDate!) throw new Error('请选择有效会计日期，不能早于原资金和前次记账日期。')
  return { paymentVersion: view.bank!.version, returnVersion: view.returnVersion, accountingDate, comment: explanation(comment) }
}
export function supplierAdjustmentAllowed(view: SupplierAdjustmentView | null, id: string, action: SupplierAdjustmentAction): boolean {
  const operation = view?.items.find(item => item.id === id)
  return !!operation?.actions[{ QUERY: 'query', RETRY: 'retryOriginal', RETIRE: 'retire' }[action] as keyof typeof operation.actions]
}
/** 后续动作保持原调整号、金额与会计日期，只提交本次展示版本和说明。 */
export function supplierAdjustmentActionInput(view: SupplierAdjustmentView, id: string, action: SupplierAdjustmentAction, comment: string): SupplierAdjustmentActionInput {
  validateSupplierAdjustment(view, view)
  if (!supplierAdjustmentAllowed(view, id, action)) throw new Error('当前身份或状态不允许办理，请刷新原调整。')
  return { action, adjustmentVersion: view.items.find(item => item.id === id)!.version, comment: explanation(comment) }
}
function explanation(comment: string) { if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的操作说明。'); return comment.trim() }
/** 202 只确认原人工决定已保存，回执不得宣称资金或 ERP 已经完成。 */
export function validateSupplierAdjustmentReceipt(receipt: SupplierAdjustmentReceipt, view: SupplierAdjustmentView, action: SupplierAdjustmentReceipt['action'], id?: string) {
  if (!receipt || !bindingKeys.every(key => receipt[key] === view[key]) || receipt.action !== action || !text(receipt.auditEventId)) throw new Error('调整回执未对应原申请，请恢复原请求后核对。')
  const operation = view.items.find(item => item.id === id)
  if (action === 'PREPARE' ? !text(receipt.preparationId) || receipt.preparationVersion !== 1 || receipt.adjustmentId !== null || receipt.adjustmentVersion !== null
      : !operation || receipt.preparationId !== null || receipt.preparationVersion !== null || receipt.adjustmentId !== id
        || receipt.adjustmentVersion !== operation.version + (action !== 'RETIRE' || ['QUEUED', 'CHECKING'].includes(operation.status) ? 1 : 0)) throw new Error('调整回执版本不完整，请恢复原请求后核对。')
}
export function supplierAdjustmentError(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string } | null)?.code
  const labels: Record<string, string> = { FORBIDDEN: '当前岗位、法人任职、字段权限或三方分离规则不允许办理。', NOT_FOUND: '当前范围内无法读取原付款或调整。', CONCURRENCY_CONFLICT: '原付款或调整版本已变化，请刷新。', SUPPLIER_ADJUSTMENT_PENDING: '已有准备或原调整，请刷新其结果。', SUPPLIER_ADJUSTMENT_SOURCE_CHANGED: '原银行、登记回款或既有账务已变化，请刷新核对。', SUPPLIER_ADJUSTMENT_PREPARATION_CONFLICT: '调整准备已变化，请刷新。', SUPPLIER_PAYABLE_ADJUSTMENT_STATE_CONFLICT: '原调整状态已变化，请刷新。', SUPPLIER_ADJUSTMENT_RETIREMENT_UNSAFE: '原调整尚未确认安全结束，请保持原编号查询。', REQUEST_TIMEOUT: '操作结果未确认，请恢复原请求后刷新。', PENDING_REQUEST_CHANGED: '原请求尚未确认，请先恢复原请求。', PAYMENT_ACTOR_UNAVAILABLE: '原法人财务任职已失效。', INVALID_SUPPLIER_ADJUSTMENT_QUERY: '历史游标已失效，请返回最新状态。', INVALID_SUPPLIER_PAYABLE_ADJUSTMENT_COMMAND: '本次会计日期或原资金依据不符，请刷新核对。' }
  return code && labels[code] || '本次调整办理未完成，请核对原请求与当前状态。'
}
