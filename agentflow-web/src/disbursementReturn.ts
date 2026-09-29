import { amountMinor, expenseError, type Money } from './expenses.js'
import { validateRepaymentView, type AdvanceBalance, type RepaymentBinding, type RepaymentFunding, type RepaymentPosting } from './advanceRepayment.js'
import { repaymentReviewCheckLabels, type RepaymentReviewCheck, type RepaymentReviewOutcome, type RepaymentReviewDecision } from './repaymentReview.js'

/** 银行退回原放款独立减少借款欠款，不能混入员工主动还款。@author owlzhangfq@gmail.com */
export interface DisbursementReturnProof { funding: RepaymentFunding; posting: RepaymentPosting }
export interface DisbursementReturnEntry { resolutionId: string; proof: DisbursementReturnProof }
export interface DisbursementReturnOriginal { paymentId: string; paymentReference: string; amount: Money; paidAt: string }
export interface DisbursementReturnEvidence { status: RepaymentReviewOutcome; revision: number; observedAt: string; validUntil: string; originalRevision: number | null; originalStatus: string | null; returns: DisbursementReturnProof[] }
export interface DisbursementReturnCheck extends Omit<RepaymentReviewCheck, 'evidence'> { evidence: DisbursementReturnEvidence | null }
export interface DisbursementReturnView extends RepaymentBinding { balance: AdvanceBalance; original: DisbursementReturnOriginal; returns: DisbursementReturnEntry[]; canQuery: boolean; latestCheck: DisbursementReturnCheck | null; latestDecision: RepaymentReviewDecision | null }
export interface DisbursementReturnQueryInput { advanceVersion: number; comment: string }
export interface DisbursementResolutionInput extends DisbursementReturnQueryInput { checkId: string; checkVersion: number; outcome: Exclude<RepaymentReviewOutcome, 'UNRESOLVED'>; evidenceReference: string }
export interface DisbursementReturnActionReceipt { advanceId: string; checkId: string; checkVersion: number; resolutionId: string | null; advanceVersion: number; auditEventId: string }
export const disbursementReturnLabels: Record<RepaymentReviewOutcome, string> = { UNRESOLVED: '原放款尚未核清', CONFIRMED: '原放款仍然有效', PARTIALLY_RETURNED: '原放款已部分退回并入账', RETURNED: '原放款已全额退回并入账' }
export const disbursementReturnCheckLabels = repaymentReviewCheckLabels
const issues: Record<string, string> = {
  DISBURSEMENT_RETURN_EVIDENCE_UNAVAILABLE: '尚无完整有效的原放款、银行回款及入账依据，请重新查询。', DISBURSEMENT_RETURN_NOT_REQUIRED: '原放款当前没有待解除的冻结。',
  DISBURSEMENT_RETURN_PAYMENT_UNRESOLVED: '原付款结果尚未核清，请先在付款明细完成原交易核对。', DISBURSEMENT_RETURN_EVIDENCE_CHANGED: '原件已变化或遗漏此前回款，请核对累计完整依据后重新查询。',
  DISBURSEMENT_RETURN_SOURCE_CHANGED: '原放款或已确认退回与当前材料不一致，请核对原件。', DISBURSEMENT_RETURN_CONFLICTS_WITH_USAGE: '退回金额与已有报销冲销、还款或预留冲突，请先核清相关占用。',
  DISBURSEMENT_RETURN_ALREADY_RECORDED: '回款流水或贷方分录已用于还款或其他放款退回，不能重复减少欠款。', DISBURSEMENT_RETURN_OUTCOME_CHANGED: '结论与当前展示依据不一致，请刷新核对。',
  DISBURSEMENT_RETURN_PENDING: '复核查询正在处理，请刷新查看。', PAYMENT_ACTOR_UNAVAILABLE: '当前财务任职已变化，暂时不能确认。',
  TIMEOUT: '复核查询超时，请重新查询。', TARGET_CHANGED: '原资金服务配置已变化，请联系财务管理员。', SOURCE_CHANGED: '原放款来源或当前财务身份已变化。'
}
export function disbursementReturnIssue(code: string) { return issues[code] ?? '当前原放款复核依据不可用，请刷新并核对原件。' }
export function disbursementReturnError(cause: unknown) { return cause instanceof Error ? cause.message : issues[(cause as { code?: string })?.code ?? ''] ?? expenseError(cause) }
function requireValue(condition: unknown): asserts condition { if (!condition) throw new Error('原放款退回资料未通过校验，请刷新核对。') }
function identifier(value: unknown): asserts value is string { requireValue(typeof value === 'string' && value === value.trim() && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f]/.test(value)) }
function version(value: number) { requireValue(Number.isSafeInteger(value) && value > 0) }
function instant(value: string) { requireValue(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function known(labels: object, value: string) { return Object.prototype.hasOwnProperty.call(labels, value) }
function note(value: string) { const trimmed = value.trim(); if (!trimmed || value.length > 2000) throw new Error('请填写 2000 字以内的核对说明。'); return trimmed }
function amount(value: Money, currency: string) { requireValue(value && value.currency === currency); return amountMinor(value.value) }
function proofs(entries: DisbursementReturnProof[], original: DisbursementReturnOriginal, until?: number) {
  requireValue(Array.isArray(entries) && entries.length <= 100)
  const funds = new Set<string>(), postings = new Set<string>(); let total = 0n
  for (const entry of entries) {
    requireValue(entry?.funding && entry.posting)
    const { funding, posting } = entry; requireValue(funding.channel === 'BANK_TRANSFER')
    identifier(funding.transactionReference); identifier(posting.voucherReference); identifier(posting.entryReference)
    requireValue(funding.transactionReference !== original.paymentReference)
    const value = amount(funding.amount, original.amount.currency); requireValue(value > 0n && value === amount(posting.amount, original.amount.currency)); total += value
    requireValue(instant(original.paidAt) <= instant(funding.receivedAt) && instant(funding.receivedAt) <= instant(posting.postedAt))
    if (until !== undefined) requireValue(instant(posting.postedAt) <= until)
    requireValue(typeof posting.accountingDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(posting.accountingDate) && Number.isFinite(Date.parse(posting.accountingDate)) && new Date(posting.accountingDate).toISOString().slice(0, 10) === posting.accountingDate)
    const fundKey = funding.transactionReference, postingKey = JSON.stringify([posting.voucherReference, posting.entryReference])
    requireValue(!funds.has(fundKey) && !postings.has(postingKey)); funds.add(fundKey); postings.add(postingKey)
  }
  requireValue(total <= amountMinor(original.amount.value)); return total
}
function sameProof(left: DisbursementReturnProof, right: DisbursementReturnProof) {
  return left.funding.channel === right.funding.channel && left.funding.transactionReference === right.funding.transactionReference
    && left.funding.amount.currency === right.funding.amount.currency && amountMinor(left.funding.amount.value) === amountMinor(right.funding.amount.value)
    && instant(left.funding.receivedAt) === instant(right.funding.receivedAt) && left.posting.voucherReference === right.posting.voucherReference
    && left.posting.entryReference === right.posting.entryReference && left.posting.accountingDate === right.posting.accountingDate
    && instant(left.posting.postedAt) === instant(right.posting.postedAt)
}
function outcome(status: RepaymentReviewOutcome, total: bigint, paid: bigint) {
  requireValue(known(disbursementReturnLabels, status))
  requireValue(status === 'RETURNED' ? total === paid : status === 'PARTIALLY_RETURNED' ? total > 0n && total < paid : total === 0n)
}
/** 余额守恒、原付款身份与每笔银行回款共同校验，累计金额不能被误作新增金额。 */
export function validateDisbursementReturn(value: DisbursementReturnView, binding: RepaymentBinding) {
  requireValue(value?.balance && value.original)
  validateRepaymentView({ applicationId: value.applicationId, advanceId: value.advanceId, roundNo: value.roundNo, balance: value.balance, records: [], latestCheck: null, canQuery: false, nextBeforeId: null }, binding)
  const original = value.original, paid = amountMinor(value.balance.paid.value)
  identifier(original.paymentId); identifier(original.paymentReference); instant(original.paidAt)
  requireValue(amount(original.amount, value.balance.paid.currency) === paid && Array.isArray(value.returns) && typeof value.canQuery === 'boolean')
  value.returns.forEach(entry => { requireValue(entry); identifier(entry.resolutionId) })
  const accepted = proofs(value.returns.map(entry => entry.proof), original)
  requireValue(value.balance.returnedDisbursements && accepted === amount(value.balance.returnedDisbursements, original.amount.currency))
  const check = value.latestCheck
  if (check !== null) {
    requireValue(check && known(disbursementReturnCheckLabels, check.status)); identifier(check.id); version(check.version)
    requireValue(typeof check.canResolve === 'boolean' && instant(check.requestedAt) <= instant(check.updatedAt))
    requireValue(check.issue === null || typeof check.issue === 'string'); requireValue(check.confirmationIssue === null || typeof check.confirmationIssue === 'string')
    const observed = check.status === 'CHECKED' || check.status === 'RESOLVED'
    requireValue(observed ? check.evidence !== null && check.issue === null : check.evidence === null && !check.canResolve)
    requireValue(!value.canQuery || !['QUEUED', 'RUNNING'].includes(check.status))
    if (check.evidence !== null) {
      const evidence = check.evidence; version(evidence.revision)
      const at = instant(evidence.observedAt), until = instant(evidence.validUntil)
      requireValue(at <= instant(check.updatedAt) && until > at && until - at <= 300000)
      if (evidence.originalRevision !== null) requireValue(Number.isSafeInteger(evidence.originalRevision) && evidence.originalRevision >= 0)
      requireValue(evidence.originalStatus === null || ['NOT_FOUND', 'PENDING', 'SUCCEEDED', 'FAILED', 'REVERSED'].includes(evidence.originalStatus))
      const total = proofs(evidence.returns, original, at); outcome(evidence.status, total, paid)
      if (evidence.status !== 'UNRESOLVED') requireValue(evidence.originalRevision !== null && evidence.originalRevision > 0 && evidence.originalStatus === (evidence.status === 'RETURNED' ? 'REVERSED' : 'SUCCEEDED'))
      if (check.canResolve) {
        requireValue(check.status === 'CHECKED' && check.confirmationIssue === null && evidence.status !== 'UNRESOLVED' && value.balance.status === 'PAYMENT_REVIEW')
        requireValue(value.returns.every(entry => evidence.returns.some(proof => sameProof(entry.proof, proof))))
      }
      if (check.status === 'RESOLVED') requireValue(evidence.status !== 'UNRESOLVED' && !check.canResolve)
    }
  }
  if (value.latestDecision !== null) {
    const decision = value.latestDecision; requireValue(decision && decision.outcome !== ('UNRESOLVED' as string))
    identifier(decision.id); identifier(decision.resolvedBy); identifier(decision.evidenceReference); const at = instant(decision.resolvedAt)
    outcome(decision.outcome, accepted, paid); proofs(value.returns.map(entry => entry.proof), original, at)
  } else requireValue(value.returns.length === 0)
  return value
}
/** 合计用于只读展示，资金变化仍由服务端原件决定。 */
export function disbursementReturnTotal(entries: DisbursementReturnProof[], currency: string): Money {
  const total = entries.reduce((sum, entry) => sum + amountMinor(entry.funding.amount.value), 0n)
  return { currency, value: `${total / 100n}.${String(total % 100n).padStart(2, '0')}` }
}
/** 查询只提交当前借款版本和说明，固定原放款由服务端读取。 */
export function disbursementReturnQueryInput(view: DisbursementReturnView, comment: string): DisbursementReturnQueryInput {
  requireValue(view.canQuery); return { advanceVersion: view.balance.version, comment: note(comment) }
}
/** 五分钟内明确采纳完整依据，不接受页面传入资金或会计分录。 */
export function disbursementResolutionInput(view: DisbursementReturnView, reference: string, comment: string, now = Date.now()): DisbursementResolutionInput {
  const check = view.latestCheck; requireValue(check?.canResolve && check.evidence && check.evidence.status !== 'UNRESOLVED')
  if (now < instant(check.evidence.observedAt) || now >= instant(check.evidence.validUntil)) throw new Error('原放款退回依据已过期，请重新查询后确认。')
  const evidenceReference = reference.trim(); identifier(evidenceReference)
  return { advanceVersion: view.balance.version, checkId: check.id, checkVersion: check.version, outcome: check.evidence.status, evidenceReference, comment: note(comment) }
}
/** 幂等回执必须属于当前借款和精确连续版本。 */
export function validateDisbursementReturnReceipt(receipt: DisbursementReturnActionReceipt, view: DisbursementReturnView, input: DisbursementReturnQueryInput | DisbursementResolutionInput) {
  requireValue(receipt && receipt.advanceId === view.advanceId); identifier(receipt.checkId); identifier(receipt.auditEventId)
  if ('checkId' in input) { identifier(receipt.resolutionId); requireValue(receipt.checkId === input.checkId && receipt.checkVersion === input.checkVersion + 1 && receipt.advanceVersion === input.advanceVersion + 1) }
  else requireValue(receipt.resolutionId === null && receipt.checkVersion === 1 && receipt.advanceVersion === input.advanceVersion)
}
