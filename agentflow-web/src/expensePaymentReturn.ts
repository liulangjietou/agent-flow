import { amountMinor, expenseError, type Money } from './expenses.js'

/** 原报销银行退回与应付贷方分别核验；页面不接收自填资金。@author owlzhangfq@gmail.com */
export type ExpenseReturnOutcome = 'UNRESOLVED' | 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'
export interface ExpenseReturnBinding { applicationId: string; reportId: string; roundNo: number }
export interface ExpenseReturnProof { funding: { transactionReference: string; amount: Money; receivedAt: string }; posting: { voucherReference: string; entryReference: string; accountCode: string; amount: Money; accountingDate: string; postedAt: string } }
export interface ExpenseReturnEntry { registrationId: string; proof: ExpenseReturnProof }
export interface ExpenseReturnOriginal { paymentId: string; paymentReference: string; amount: Money; paidAt: string }
export interface ExpenseReturnEvidence { status: ExpenseReturnOutcome; revision: number; observedAt: string; validUntil: string; originalRevision: number | null; originalStatus: string | null; returns: ExpenseReturnProof[]; totalReturned: Money; newReturned: Money }
export interface ExpenseReturnCheck { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RESOLVED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null; evidence: ExpenseReturnEvidence | null; canRegister: boolean; registrationIssue: string | null }
export interface ExpenseReturnRegistration { id: string; outcome: Exclude<ExpenseReturnOutcome, 'UNRESOLVED'>; totalReturned: Money; registeredBy: string; registeredAt: string; evidenceReference: string; reason: string }
export interface ExpenseReturnView extends ExpenseReturnBinding { settlementVersion: number; settlementStatus: string; returnVersion: number; reviewRequired: boolean; original: ExpenseReturnOriginal; totalReturned: Money; netPaid: Money; returns: ExpenseReturnEntry[]; canQuery: boolean; latestCheck: ExpenseReturnCheck | null; registrations: ExpenseReturnRegistration[] }
export interface ExpenseReturnQueryInput { settlementVersion: number; returnVersion: number; comment: string }
export interface ExpenseReturnRegisterInput extends ExpenseReturnQueryInput { checkId: string; checkVersion: number; outcome: Exclude<ExpenseReturnOutcome, 'UNRESOLVED'>; evidenceReference: string }
export interface ExpenseReturnActionReceipt { reportId: string; checkId: string; checkVersion: number; registrationId: string | null; returnVersion: number; settlementVersion: number; auditEventId: string }
export const expenseReturnLabels: Record<ExpenseReturnOutcome, string> = { UNRESOLVED: '原报销付款尚未核清', CONFIRMED: '原付款有效，未发生退回', PARTIALLY_RETURNED: '已部分退回公司并入账', RETURNED: '已全额退回公司并入账' }
export const expenseReturnCheckLabels: Record<ExpenseReturnCheck['status'], string> = { QUEUED: '等待查询', RUNNING: '正在读取银行和会计原件', CHECKED: '原件已读取，待核对', RESOLVED: '本次依据已登记', UNAVAILABLE: '本次查询不可用', VOIDED: '查询来源已失效' }
const issues: Record<string, string> = {
  EXPENSE_PAYMENT_RETURN_EVIDENCE_UNAVAILABLE: '尚无完整有效的银行入款和员工应付贷方依据，请重新查询。', EXPENSE_PAYMENT_RETURN_NOT_REQUIRED: '当前没有需要确认的退回复核疑点。',
  EXPENSE_PAYMENT_RETURN_PAYMENT_UNRESOLVED: '原付款存在未核清的结果，请先完成原交易对账或争议处理。', EXPENSE_PAYMENT_RETURN_EVIDENCE_CHANGED: '原件已变化、版本落后或遗漏此前入款，请核对完整累计依据后重新查询。',
  EXPENSE_PAYMENT_RETURN_SOURCE_CHANGED: '原报销、付款或挂账依据不一致，请核对原件。', EXPENSE_PAYMENT_RETURN_ALREADY_RECORDED: '银行流水或贷方分录已用于其他报销退回、借款还款或退票，不能重复登记。',
  EXPENSE_PAYMENT_RETURN_OUTCOME_CHANGED: '登记结论与当前展示依据不一致，请刷新。', EXPENSE_PAYMENT_RETURN_PENDING: '银行退回查询正在处理，请刷新查看。',
  EXPENSE_PAYMENT_RETURN_REVIEW_REQUIRED: '原付款退回需要独立复核或后续调整，原核销记录保留。', EXPENSE_PAYMENT_RETURNED: '已登记银行退回，等待独立后续办理，原发票、预算和借款冲销保留。',
  PAYMENT_ACTOR_UNAVAILABLE: '当前财务任职已变化，暂时不能登记。', TIMEOUT: '查询超时，请明确发起新的查询。', TARGET_CHANGED: '原财务服务配置已变化，请联系财务管理员。',
  SOURCE_CHANGED: '原付款来源或当前财务身份已变化，请重新核对。', SOURCE_UNAVAILABLE: '原法人或员工在财务系统中不可用。', INVALID_RESPONSE: '外部原件不完整或与原付款不一致，请核对财务服务。',
  NOT_CONFIGURED: '原财务服务尚未配置。', INTERNAL_ERROR: '本次查询未完成，请刷新后重新查询。', CONNECTION: '财务服务连接失败，请重新查询。',
  AUTHENTICATION: '财务服务认证失败，请联系管理员。', REMOTE_FAILURE: '财务服务暂未返回可用依据。', RESPONSE_TOO_LARGE: '财务服务响应超出允许范围。',
}
export function expenseReturnIssue(code: string) { return issues[code] ?? '本次依据暂不可采用，请刷新并核对原财务记录。' }
export function expenseReturnError(cause: unknown) { const code = (cause as { code?: string })?.code; return code && issues[code] ? issues[code]! : expenseError(cause) }
function requireValue(value: unknown): asserts value { if (!value) throw new Error('报销退回数据不完整或与当前单据不一致，请刷新核对。') }
function reference(value: unknown): asserts value is string { requireValue(typeof value === 'string' && value.trim() === value && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f-\u009f]/.test(value)) }
function uuid(value: unknown) { reference(value); requireValue(/^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value)) }
function version(value: number, minimum = 1) { requireValue(Number.isSafeInteger(value) && value >= minimum) }
function instant(value: string) { requireValue(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function amount(value: Money, currency: string) { requireValue(value && value.currency === currency); return amountMinor(value.value) }
function note(value: string) { const result = value.trim(); requireValue(result.length > 0 && result.length <= 2000); return result }
function known(values: object, key: unknown) { return typeof key === 'string' && Object.prototype.hasOwnProperty.call(values, key) }
function proofs(values: ExpenseReturnProof[], original: ExpenseReturnOriginal, until?: number) {
  requireValue(Array.isArray(values) && values.length <= 100); let total = 0n; const funds = new Set(), entries = new Set(), accounts = new Set()
  for (const value of values) {
    requireValue(value && value.funding && value.posting); const { funding, posting } = value
    reference(funding.transactionReference); reference(posting.voucherReference); reference(posting.entryReference); reference(posting.accountCode)
    requireValue(!('channel' in funding) && funding.transactionReference !== original.paymentReference)
    const paid = amount(funding.amount, original.amount.currency); requireValue(paid > 0n && amount(posting.amount, original.amount.currency) === paid); total += paid
    requireValue(instant(funding.receivedAt) >= instant(original.paidAt) && instant(funding.receivedAt) <= instant(posting.postedAt))
    if (until !== undefined) requireValue(instant(posting.postedAt) <= until)
    requireValue(typeof posting.accountingDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(posting.accountingDate) && Number.isFinite(Date.parse(posting.accountingDate)) && new Date(posting.accountingDate).toISOString().slice(0, 10) === posting.accountingDate)
    const entry = JSON.stringify([posting.voucherReference, posting.entryReference]); requireValue(!funds.has(funding.transactionReference) && !entries.has(entry))
    funds.add(funding.transactionReference); entries.add(entry); accounts.add(posting.accountCode)
  }
  requireValue(accounts.size <= 1 && total <= amountMinor(original.amount.value)); return total
}
function sameProof(a: ExpenseReturnProof, b: ExpenseReturnProof) {
  return a.funding.transactionReference === b.funding.transactionReference && a.funding.amount.currency === b.funding.amount.currency
    && amountMinor(a.funding.amount.value) === amountMinor(b.funding.amount.value) && instant(a.funding.receivedAt) === instant(b.funding.receivedAt)
    && a.posting.voucherReference === b.posting.voucherReference && a.posting.entryReference === b.posting.entryReference && a.posting.accountCode === b.posting.accountCode
    && a.posting.amount.currency === b.posting.amount.currency && amountMinor(a.posting.amount.value) === amountMinor(b.posting.amount.value)
    && a.posting.accountingDate === b.posting.accountingDate && instant(a.posting.postedAt) === instant(b.posting.postedAt)
}
function outcome(status: ExpenseReturnOutcome, total: bigint, paid: bigint) {
  requireValue(known(expenseReturnLabels, status)); requireValue(status === 'RETURNED' ? total === paid : status === 'PARTIALLY_RETURNED' ? total > 0n && total < paid : total === 0n)
}
/** 原单绑定、累计金额、原件保留与登记历史共同核验，失真响应不保留财务按钮。 */
export function validateExpenseReturn(value: ExpenseReturnView, binding: ExpenseReturnBinding) {
  requireValue(value && value.reportId === binding.reportId && value.applicationId === binding.applicationId && value.roundNo === binding.roundNo)
  uuid(value.reportId); uuid(value.applicationId); version(value.roundNo); version(value.settlementVersion); version(value.returnVersion, 0)
  requireValue(['QUEUED', 'BLOCKED', 'BUDGET_PENDING', 'BUDGET_REJECTED', 'SETTLED', 'REVIEW_REQUIRED'].includes(value.settlementStatus))
  requireValue(typeof value.reviewRequired === 'boolean' && typeof value.canQuery === 'boolean' && value.original)
  const original = value.original; uuid(original.paymentId); reference(original.paymentReference); instant(original.paidAt)
  requireValue(/^[A-Z]{3}$/.test(original.amount?.currency)); const currency = original.amount.currency, paid = amount(original.amount, currency); requireValue(paid > 0n)
  requireValue(Array.isArray(value.returns) && Array.isArray(value.registrations)); value.returns.forEach(entry => { requireValue(entry); uuid(entry.registrationId) })
  const accepted = proofs(value.returns.map(entry => entry.proof), original)
  requireValue(accepted === amount(value.totalReturned, currency) && paid - accepted === amount(value.netPaid, currency))
  requireValue(!value.reviewRequired || value.settlementStatus === 'REVIEW_REQUIRED'); requireValue(accepted === 0n || value.reviewRequired)
  requireValue(value.returnVersion > 0 || value.returns.length === 0 && value.registrations.length === 0 && value.latestCheck === null && !value.reviewRequired)
  const history = new Map<string, ExpenseReturnRegistration>(); let priorTotal = 0n, priorAt = instant(original.paidAt)
  for (const registration of value.registrations) {
    uuid(registration.id); reference(registration.registeredBy); reference(registration.evidenceReference); note(registration.reason)
    const total = amount(registration.totalReturned, currency), at = instant(registration.registeredAt)
    requireValue(registration.outcome !== ('UNRESOLVED' as string) && !history.has(registration.id) && total >= priorTotal && total <= accepted && at >= priorAt)
    outcome(registration.outcome, total, paid); history.set(registration.id, registration); priorTotal = total; priorAt = at
  }
  requireValue(priorTotal === accepted)
  for (const entry of value.returns) { const registration = history.get(entry.registrationId); requireValue(registration); proofs([entry.proof], original, instant(registration.registeredAt)) }
  const check = value.latestCheck
  if (check !== null) {
    requireValue(check && known(expenseReturnCheckLabels, check.status)); uuid(check.id); version(check.version)
    requireValue(typeof check.canRegister === 'boolean' && instant(check.requestedAt) <= instant(check.updatedAt))
    requireValue(check.issue === null || typeof check.issue === 'string'); requireValue(check.registrationIssue === null || typeof check.registrationIssue === 'string')
    const observed = check.status === 'CHECKED' || check.status === 'RESOLVED'
    requireValue(observed ? check.evidence !== null && check.issue === null : check.evidence === null && !check.canRegister)
    requireValue(!value.canQuery || !['QUEUED', 'RUNNING'].includes(check.status))
    if (check.evidence !== null) {
      const evidence = check.evidence; version(evidence.revision); const at = instant(evidence.observedAt), until = instant(evidence.validUntil)
      requireValue(at <= instant(check.updatedAt) && until > at && until - at <= 300000)
      if (evidence.originalRevision !== null) version(evidence.originalRevision, 0)
      requireValue(evidence.originalStatus === null || ['NOT_FOUND', 'PENDING', 'SUCCEEDED', 'FAILED', 'REVERSED'].includes(evidence.originalStatus))
      const total = proofs(evidence.returns, original, at); outcome(evidence.status, total, paid)
      requireValue(total === amount(evidence.totalReturned, currency))
      const added = evidence.returns.filter(proof => !value.returns.some(entry => sameProof(entry.proof, proof))).reduce((sum, proof) => sum + amountMinor(proof.funding.amount.value), 0n)
      requireValue(added === amount(evidence.newReturned, currency))
      if (evidence.status !== 'UNRESOLVED') requireValue(evidence.originalRevision !== null && evidence.originalRevision > 0 && evidence.originalStatus === (evidence.status === 'RETURNED' ? 'REVERSED' : 'SUCCEEDED'))
      if (check.canRegister) {
        requireValue(check.status === 'CHECKED' && check.registrationIssue === null && evidence.status !== 'UNRESOLVED' && value.reviewRequired)
        requireValue(value.returns.every(entry => evidence.returns.some(proof => sameProof(entry.proof, proof))))
      }
      if (check.status === 'RESOLVED') requireValue(evidence.status !== 'UNRESOLVED' && !check.canRegister)
    }
  }
  return value
}
/** 查询只携带原结算及独立账本版本，资金来源由服务端派生。 */
export function expenseReturnQueryInput(view: ExpenseReturnView, comment: string): ExpenseReturnQueryInput {
  requireValue(view.canQuery); return { settlementVersion: view.settlementVersion, returnVersion: view.returnVersion, comment: note(comment) }
}
/** 人工登记只消费当前证据，过期不续期，也不发送金额或分录。 */
export function expenseReturnRegisterInput(view: ExpenseReturnView, material: string, comment: string, now = Date.now()): ExpenseReturnRegisterInput {
  const check = view.latestCheck; requireValue(check?.canRegister && check.evidence && check.evidence.status !== 'UNRESOLVED')
  if (now < instant(check.evidence.observedAt) || now >= instant(check.evidence.validUntil)) throw new Error('报销退回依据已过期，请重新查询后登记。')
  const evidenceReference = material.trim(); reference(evidenceReference)
  return { settlementVersion: view.settlementVersion, returnVersion: view.returnVersion, checkId: check.id, checkVersion: check.version, outcome: check.evidence.status, evidenceReference, comment: note(comment) }
}
/** 回执必须对应原请求版本；无退回确认最多恢复一次结算，实际退回保持冻结。 */
export function validateExpenseReturnReceipt(receipt: ExpenseReturnActionReceipt, view: ExpenseReturnView, input: ExpenseReturnQueryInput | ExpenseReturnRegisterInput) {
  requireValue(receipt && receipt.reportId === view.reportId); uuid(receipt.checkId); uuid(receipt.auditEventId)
  if ('checkId' in input) {
    uuid(receipt.registrationId); requireValue(receipt.checkId === input.checkId && receipt.checkVersion === input.checkVersion + 1 && receipt.returnVersion === input.returnVersion + 1)
    requireValue(receipt.settlementVersion === input.settlementVersion || input.outcome === 'CONFIRMED' && receipt.settlementVersion === input.settlementVersion + 1)
  } else requireValue(receipt.registrationId === null && receipt.checkVersion === 1 && receipt.returnVersion === Math.max(1, input.returnVersion) && receipt.settlementVersion === input.settlementVersion)
}
