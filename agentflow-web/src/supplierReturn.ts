import { amountMinor, expenseError, type Money } from './expenses.js'
import { paymentOperationLabels } from './payments.js'
import type { SupplierDisputeBinding } from './supplierDispute.js'

export type SupplierReturnOutcome = 'UNRESOLVED' | 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'
export interface SupplierReturnFunding { transactionReference: string; amount: Money; receivedAt: string }
export interface SupplierReturnOriginal { paymentReference: string; receiptReference: string; amount: Money; paidAt: string }
export interface SupplierReturnEvidence { outcome: SupplierReturnOutcome; revision: number; observedAt: string; validUntil: string; bankRevision: number | null; bankStatus: string | null; returns: SupplierReturnFunding[]; totalReturned: Money; newReturned: Money }
export interface SupplierReturnCheck { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RESOLVED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null; evidence: SupplierReturnEvidence | null; canRegister: boolean; registrationIssue: string | null }
export interface SupplierReturnRegistration { id: string; returnVersion: number; outcome: Exclude<SupplierReturnOutcome, 'UNRESOLVED'>; totalReturned: Money; registeredBy: string; registeredAt: string; evidenceReference: string; reason: string }
export interface SupplierReturnView extends SupplierDisputeBinding {
  operationVersion: number | null; bankStatus: keyof typeof paymentOperationLabels | null; returnVersion: number; reviewRequired: boolean
  original: SupplierReturnOriginal | null; totalReturned: Money | null; netPaid: Money | null; returns: { registrationId: string; funding: SupplierReturnFunding }[]
  canQuery: boolean; latestCheck: SupplierReturnCheck | null; registrations: SupplierReturnRegistration[]; nextBeforeVersion: number | null
}
export interface SupplierReturnQueryInput { operationVersion: number; returnVersion: number; comment: string }
export interface SupplierReturnRegisterInput extends SupplierReturnQueryInput { checkId: string; checkVersion: number; outcome: Exclude<SupplierReturnOutcome, 'UNRESOLVED'>; evidenceReference: string }
export interface SupplierReturnReceipt { paymentId: string; operationVersion: number; checkId: string; checkVersion: number; registrationId: string | null; returnVersion: number; auditEventId: string }
export const supplierReturnLabels: Record<SupplierReturnOutcome, string> = { UNRESOLVED: '原付款回款尚未核清', CONFIRMED: '原付款有效，未发生回款', PARTIALLY_RETURNED: '已部分退回公司账户', RETURNED: '已全额退回公司账户' }
export const supplierReturnCheckLabels: Record<SupplierReturnCheck['status'], string> = { QUEUED: '等待查询', RUNNING: '正在读取实际回款', CHECKED: '已读取，待财务核对', RESOLVED: '本次依据已登记', UNAVAILABLE: '本次查询不可用', VOIDED: '查询来源已失效' }
const issues: Record<string, string> = {
  SUPPLIER_PAYMENT_RETURN_EVIDENCE_UNAVAILABLE: '尚无完整有效的银行入款依据，请重新查询。', SUPPLIER_PAYMENT_RETURN_NOT_REQUIRED: '当前没有需要确认的回款疑点。',
  SUPPLIER_PAYMENT_RETURN_PAYMENT_UNRESOLVED: '原付款结果尚未核清，请先完成原银行对账或争议处理。', SUPPLIER_PAYMENT_RETURN_EVIDENCE_CHANGED: '本次依据与已知银行事实或累计入款不一致，请重新核对。',
  SUPPLIER_PAYMENT_RETURN_SOURCE_CHANGED: '原付款依据或当前财务身份发生变化，请重新核对。', SUPPLIER_PAYMENT_RETURN_ALREADY_RECORDED: '本笔入款已用于其他财务登记，不能重复计入。',
  SUPPLIER_PAYMENT_RETURN_OUTCOME_CHANGED: '登记结论与展示依据不一致，请刷新。', SUPPLIER_PAYMENT_RETURN_PENDING: '回款查询正在处理，请刷新查看。',
  SUPPLIER_PAYMENT_RETURN_REVIEW_REQUIRED: '实际回款仍需独立账务调整，原应付暂不能再次付款。',
  TIMEOUT: '查询超时，请明确发起新的查询。', TARGET_CHANGED: '原财务服务配置已变化，请联系管理员。', SOURCE_CHANGED: '原付款来源或当前财务身份已变化。',
  SOURCE_UNAVAILABLE: '原法人或供应商资料不可用。', INVALID_RESPONSE: '财务服务返回的回款依据不完整或与原交易不一致。', NOT_CONFIGURED: '财务服务尚未配置。',
  INTERNAL_ERROR: '本次查询未完成，请刷新核对。', CONNECTION: '财务服务连接失败。', AUTHENTICATION: '财务服务认证失败。', REMOTE_FAILURE: '财务服务暂未返回可用依据。', RESPONSE_TOO_LARGE: '财务服务响应超出允许范围。',
}
export function supplierReturnIssue(code: string) { return issues[code] ?? '本次依据暂不可采用，请核对原财务记录。' }
export function supplierReturnError(cause: unknown) {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string })?.code; return code && issues[code] ? issues[code]! : expenseError(cause)
}
function requireValue(value: unknown): asserts value { if (!value) throw new Error('供应商回款数据与原付款或累计资金不一致，请刷新核对。') }
function reference(value: unknown): asserts value is string { requireValue(typeof value === 'string' && value.trim() === value && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f-\u009f]/.test(value)) }
function uuid(value: unknown) { reference(value); requireValue(/^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value)) }
function version(value: number, minimum = 1) { requireValue(Number.isSafeInteger(value) && value >= minimum) }
function instant(value: string) { requireValue(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function money(value: Money | null, currency: string) { requireValue(value && value.currency === currency && typeof value.value === 'string'); return amountMinor(value.value) }
function note(value: string) { requireValue(typeof value === 'string' && value.trim().length > 0 && value.length <= 2000); return value.trim() }
function known(values: object, value: unknown) { return typeof value === 'string' && Object.prototype.hasOwnProperty.call(values, value) }
function sameFunding(a: SupplierReturnFunding, b: SupplierReturnFunding) { return a.transactionReference === b.transactionReference && a.amount.currency === b.amount.currency && a.amount.value === b.amount.value && a.receivedAt === b.receivedAt }
function funding(values: SupplierReturnFunding[], original: SupplierReturnOriginal, until?: number) {
  requireValue(Array.isArray(values) && values.length <= 100); const references = new Set<string>(); let total = 0n
  for (const item of values) {
    requireValue(item); reference(item.transactionReference)
    requireValue(!('creditAccountReference' in item) && !references.has(item.transactionReference) && ![original.paymentReference, original.receiptReference].includes(item.transactionReference))
    const amount = money(item.amount, original.amount.currency), received = instant(item.receivedAt)
    requireValue(amount > 0n && received >= instant(original.paidAt) && (until === undefined || received <= until))
    total += amount; references.add(item.transactionReference)
  }
  requireValue(total <= amountMinor(original.amount.value)); return total
}
function outcome(value: SupplierReturnOutcome, total: bigint, paid: bigint) {
  requireValue(known(supplierReturnLabels, value) && (value === 'RETURNED' ? total === paid : value === 'PARTIALLY_RETURNED' ? total > 0n && total < paid : total === 0n))
}
/** 当前金额独立于历史分页，所有办理均绑定原付款及原审批轮次。 */
export function validateSupplierReturn(value: SupplierReturnView, binding: SupplierDisputeBinding, beforeVersion?: number): SupplierReturnView {
  requireValue(value && value.paymentId === binding.paymentId && value.requestId === binding.requestId && value.applicationId === binding.applicationId && value.roundNo === binding.roundNo)
  uuid(value.paymentId); uuid(value.requestId); uuid(value.applicationId); version(value.roundNo); version(value.returnVersion, 0)
  requireValue(typeof value.reviewRequired === 'boolean' && typeof value.canQuery === 'boolean' && Array.isArray(value.returns) && Array.isArray(value.registrations) && value.registrations.length <= 50)
  requireValue(value.bankStatus === null ? value.operationVersion === null : known(paymentOperationLabels, value.bankStatus) && value.operationVersion !== null)
  if (value.operationVersion !== null) version(value.operationVersion)
  if (value.original === null) {
    requireValue(value.totalReturned === null && value.netPaid === null && !value.reviewRequired && !value.canQuery && value.returnVersion === 0 && !value.returns.length && !value.registrations.length && value.latestCheck === null && value.nextBeforeVersion === null)
    return value
  }
  const original = value.original; reference(original.paymentReference); reference(original.receiptReference); instant(original.paidAt)
  requireValue(value.operationVersion !== null && /^[A-Z]{3}$/.test(original.amount?.currency)); const currency = original.amount.currency, paid = money(original.amount, currency); requireValue(paid > 0n)
  value.returns.forEach(item => { requireValue(item); uuid(item.registrationId) })
  const accepted = funding(value.returns.map(item => item.funding), original)
  requireValue(accepted === money(value.totalReturned, currency) && paid - accepted === money(value.netPaid, currency) && (accepted === 0n || value.reviewRequired))
  requireValue(value.returnVersion > 0 || !value.returns.length && !value.registrations.length && value.latestCheck === null && !value.reviewRequired)
  let priorVersion = beforeVersion ?? value.returnVersion + 1, priorTotal = accepted, priorAt = Infinity
  const ids = new Set<string>()
  for (const item of value.registrations) {
    requireValue(item); uuid(item.id); version(item.returnVersion); reference(item.registeredBy); reference(item.evidenceReference); note(item.reason)
    const total = money(item.totalReturned, currency), at = instant(item.registeredAt)
    requireValue(!ids.has(item.id) && item.returnVersion < priorVersion && item.returnVersion <= value.returnVersion && total <= priorTotal && at <= priorAt && at >= instant(original.paidAt) && item.outcome !== ('UNRESOLVED' as string))
    outcome(item.outcome, total, paid); ids.add(item.id); priorVersion = item.returnVersion; priorTotal = total; priorAt = at
    funding(value.returns.filter(entry => entry.registrationId === item.id).map(entry => entry.funding), original, at)
  }
  if (value.nextBeforeVersion !== null) { version(value.nextBeforeVersion); requireValue(value.registrations.length > 0 && value.nextBeforeVersion === value.registrations[value.registrations.length - 1]!.returnVersion) }
  const check = value.latestCheck
  if (check !== null) {
    requireValue(check && known(supplierReturnCheckLabels, check.status)); uuid(check.id); version(check.version)
    requireValue(typeof check.canRegister === 'boolean' && instant(check.requestedAt) >= instant(original.paidAt) && instant(check.updatedAt) >= instant(check.requestedAt))
    requireValue(check.issue === null || typeof check.issue === 'string'); requireValue(check.registrationIssue === null || typeof check.registrationIssue === 'string')
    requireValue(!['QUEUED', 'RUNNING'].includes(check.status) || !value.canQuery && check.evidence === null)
    requireValue(check.evidence !== null || !['CHECKED', 'RESOLVED'].includes(check.status))
    if (check.evidence !== null) {
      const proof = check.evidence; version(proof.revision)
      const observed = instant(proof.observedAt), until = instant(proof.validUntil)
      requireValue(observed >= instant(original.paidAt) && until > observed && until - observed <= 300_000)
      requireValue(proof.bankStatus === null ? proof.bankRevision === null : ['PENDING', 'SUCCEEDED', 'FAILED', 'REVERSED', 'NOT_FOUND'].includes(proof.bankStatus) && proof.bankRevision !== null)
      if (proof.bankRevision !== null) { version(proof.bankRevision, proof.bankStatus === 'NOT_FOUND' ? 0 : 1); requireValue(proof.bankStatus !== 'NOT_FOUND' || proof.bankRevision === 0) }
      const total = funding(proof.returns, original, observed); outcome(proof.outcome, total, paid); requireValue(total === money(proof.totalReturned, currency))
      requireValue(proof.outcome === 'UNRESOLVED' || proof.bankStatus === (proof.outcome === 'RETURNED' ? 'REVERSED' : 'SUCCEEDED'))
      const fresh = proof.returns.filter(item => !value.returns.some(entry => sameFunding(entry.funding, item))).reduce((sum, item) => sum + amountMinor(item.amount.value), 0n)
      requireValue(fresh === money(proof.newReturned, currency))
      if (check.canRegister) requireValue(proof.outcome !== 'UNRESOLVED' && value.returns.every(entry => proof.returns.some(item => sameFunding(item, entry.funding))))
    }
    if (check.canRegister) requireValue(check.status === 'CHECKED' && check.issue === null && check.registrationIssue === null && check.evidence !== null && value.reviewRequired && ['SUCCEEDED', 'REVERSED'].includes(value.bankStatus!))
  }
  return value
}
export function supplierReturnAllowed(value: SupplierReturnView | null, action: 'QUERY' | 'REGISTER', now = Date.now()) {
  return !!value?.original && (action === 'QUERY' ? value.canQuery : !!value.latestCheck?.canRegister && !!value.latestCheck.evidence && now < Date.parse(value.latestCheck.evidence.validUntil))
}
/** 人工决定只提交展示版本，实际回款金额和账号必须由服务端原件确定。 */
export function supplierReturnInput(value: SupplierReturnView, action: 'QUERY' | 'REGISTER', comment: string, evidenceReference = '', now = Date.now()): SupplierReturnQueryInput | SupplierReturnRegisterInput {
  validateSupplierReturn(value, value)
  if (!supplierReturnAllowed(value, action, now)) throw new Error('当前状态或核对期限不允许办理，请刷新或重新查询。')
  const input = { operationVersion: value.operationVersion!, returnVersion: value.returnVersion, comment: note(comment) }
  if (action === 'QUERY') return input
  const external = evidenceReference.trim(); reference(external)
  return { ...input, checkId: value.latestCheck!.id, checkVersion: value.latestCheck!.version, outcome: value.latestCheck!.evidence!.outcome as SupplierReturnRegisterInput['outcome'], evidenceReference: external }
}
/** 受理查询不增加资金；登记只递增本次账本和查询版本，原银行版本保持。 */
export function validateSupplierReturnReceipt(receipt: SupplierReturnReceipt, value: SupplierReturnView, input: SupplierReturnQueryInput | SupplierReturnRegisterInput) {
  requireValue(receipt && receipt.paymentId === value.paymentId && receipt.operationVersion === input.operationVersion); uuid(receipt.checkId); uuid(receipt.auditEventId)
  if ('checkId' in input) {
    uuid(receipt.registrationId); requireValue(receipt.checkId === input.checkId && receipt.checkVersion === input.checkVersion + 1 && receipt.returnVersion === input.returnVersion + 1)
  } else requireValue(receipt.registrationId === null && receipt.checkVersion === 1 && receipt.returnVersion === Math.max(1, input.returnVersion))
}
