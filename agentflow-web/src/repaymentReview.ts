import { amountMinor, expenseError } from './expenses.js'
import { validateRepaymentView, validateRepaymentReturns, recordedRepaymentReturns, type AdvanceBalance, type RepaymentBinding, type RepaymentRecord, type RepaymentReturnedFunds, type RepaymentPosting, type RepaymentReturnProof } from './advanceRepayment.js'

/** 原还款不覆盖，真实退回的资金与借方分录单独展示。@author owlzhangfq@gmail.com */
export type RepaymentReviewOutcome = 'UNRESOLVED' | 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'
export interface RepaymentReviewBinding extends RepaymentBinding { repaymentId: string }
export interface RepaymentReviewEvidence { status: RepaymentReviewOutcome; revision: number; observedAt: string; validUntil: string; originalRevision: number | null; fundsReturn: RepaymentReturnedFunds | null; posting: RepaymentPosting | null; additionalReturns?: RepaymentReturnProof[] }
export interface RepaymentReviewCheck { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RESOLVED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null; evidence: RepaymentReviewEvidence | null; canResolve: boolean; confirmationIssue: string | null }
export interface RepaymentReviewDecision { id: string; outcome: Exclude<RepaymentReviewOutcome, 'UNRESOLVED'>; resolvedBy: string; resolvedAt: string; evidenceReference: string }
export interface RepaymentReviewView extends RepaymentBinding { balance: AdvanceBalance; original: RepaymentRecord; canQuery: boolean; latestCheck: RepaymentReviewCheck | null; latestDecision: RepaymentReviewDecision | null }
export interface RepaymentReviewQueryInput { advanceVersion: number; comment: string }
export interface RepaymentResolutionInput extends RepaymentReviewQueryInput { checkId: string; checkVersion: number; outcome: Exclude<RepaymentReviewOutcome, 'UNRESOLVED'>; evidenceReference: string }
export interface RepaymentReviewActionReceipt { advanceId: string; repaymentId: string; checkId: string; checkVersion: number; resolutionId: string | null; advanceVersion: number; auditEventId: string }
export const repaymentReviewLabels: Record<RepaymentReviewOutcome, string> = { UNRESOLVED: '原还款尚未核清', CONFIRMED: '原还款仍然有效', PARTIALLY_RETURNED: '原还款已部分退回并入账', RETURNED: '原还款已全额退回并入账' }
export const repaymentReviewCheckLabels: Record<RepaymentReviewCheck['status'], string> = { QUEUED: '等待复核查询', RUNNING: '正在读取原件', CHECKED: '已取得复核依据', RESOLVED: '本次复核已确认', UNAVAILABLE: '本次查询不可用', VOIDED: '复核来源已变化' }
const issues: Record<string, string> = {
  REPAYMENT_REVIEW_EVIDENCE_UNAVAILABLE: '尚无完整、有效的复核依据，请核对原收款及退回记录后重新查询。', REPAYMENT_REVIEW_NOT_REQUIRED: '这笔还款当前没有待解除的冻结。',
  REPAYMENT_REVIEW_EVIDENCE_CHANGED: '原件出现更新或矛盾，请重新核对后查询，保留当前记录。', REPAYMENT_REVIEW_RETURN_CHANGED: '已确认退回与本次材料不一致，请核对原资金及记账依据。',
  REPAYMENT_REVIEW_PENDING: '复核查询正在处理，请刷新查看。', REPAYMENT_REVIEW_OUTCOME_CHANGED: '本次结论与展示材料不一致，请刷新核对。',
  REPAYMENT_RETURN_ALREADY_RECORDED: '退款流水或分录已用于其他还款，请核对原件。', PAYMENT_ACTOR_UNAVAILABLE: '当前财务任职已变化，暂时不能确认。',
  TIMEOUT: '复核查询超时，请重新发起查询。', TARGET_CHANGED: '原资金服务配置已变化，请联系财务管理员。', SOURCE_CHANGED: '原还款来源或当前财务身份已变化。'
}
export function repaymentReviewIssue(code: string) { return issues[code] ?? '复核依据暂不可用，请刷新并核对原件。' }
export function repaymentReviewError(cause: unknown) { return cause instanceof Error ? cause.message : issues[(cause as { code?: string })?.code ?? ''] ?? expenseError(cause) }
function requireValue(condition: unknown): asserts condition { if (!condition) throw new Error('还款复核资料未通过校验，请刷新核对。') }
function identifier(value: unknown): asserts value is string { requireValue(typeof value === 'string' && value === value.trim() && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f]/.test(value)) }
function version(value: number) { requireValue(Number.isSafeInteger(value) && value > 0) }
function instant(value: string) { requireValue(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function known(labels: object, value: string) { return Object.prototype.hasOwnProperty.call(labels, value) }
function note(value: string) { const trimmed = value.trim(); if (!trimmed || value.length > 2000) throw new Error('请填写 2000 字以内的核对说明。'); return trimmed }

/** 原借款、轮次及原还款同时绑定，退回金额不能超过或替换原收款。 */
export function validateRepaymentReview(value: RepaymentReviewView, binding: RepaymentReviewBinding) {
  requireValue(value && value.original?.id === binding.repaymentId && value.balance)
  validateRepaymentView({ applicationId: value.applicationId, advanceId: value.advanceId, roundNo: value.roundNo, balance: value.balance, records: [value.original], latestCheck: null, canQuery: false, nextBeforeId: null }, binding)
  requireValue(typeof value.canQuery === 'boolean')
  if (value.original.reviewRequired) requireValue(['PAYMENT_REVIEW', 'REPAYMENT_REVIEW'].includes(value.balance.status))
  const check = value.latestCheck
  if (check !== null) {
    requireValue(check && known(repaymentReviewCheckLabels, check.status)); identifier(check.id); version(check.version)
    requireValue(typeof check.canResolve === 'boolean' && instant(check.requestedAt) <= instant(check.updatedAt))
    requireValue(check.issue === null || typeof check.issue === 'string'); requireValue(check.confirmationIssue === null || typeof check.confirmationIssue === 'string')
    const observed = check.status === 'CHECKED' || check.status === 'RESOLVED'
    requireValue(observed ? check.evidence !== null && check.issue === null : check.evidence === null && !check.canResolve)
    requireValue(!value.canQuery || !['QUEUED', 'RUNNING'].includes(check.status))
    if (check.evidence !== null) {
      const evidence = check.evidence; requireValue(known(repaymentReviewLabels, evidence.status)); version(evidence.revision)
      const at = instant(evidence.observedAt), until = instant(evidence.validUntil)
      requireValue(at <= instant(check.updatedAt) && until > at && until - at <= 300000)
      if (evidence.originalRevision !== null) requireValue(Number.isSafeInteger(evidence.originalRevision) && evidence.originalRevision >= 0)
      if (evidence.status !== 'UNRESOLVED') requireValue(evidence.originalRevision !== null && evidence.originalRevision > 0)
      const returns = repaymentReviewReturns(evidence), total = validateRepaymentReturns(returns, value.original)
      if (evidence.status === 'RETURNED' || evidence.status === 'PARTIALLY_RETURNED') {
        requireValue(returns.length > 0 && (evidence.status === 'RETURNED' ? total === amountMinor(value.original.amount.value) : total < amountMinor(value.original.amount.value)))
        for (const entry of returns) requireValue(instant(entry.posting.postedAt) <= at)
      } else requireValue(returns.length === 0)
      if (check.canResolve) requireValue(check.status === 'CHECKED' && evidence.status !== 'UNRESOLVED' && check.confirmationIssue === null && value.original.reviewRequired)
      if (check.status === 'RESOLVED') requireValue(evidence.status !== 'UNRESOLVED' && !check.canResolve)
    }
  }
  if (value.latestDecision !== null) {
    const decision = value.latestDecision; requireValue(decision && ['CONFIRMED', 'PARTIALLY_RETURNED', 'RETURNED'].includes(decision.outcome))
    identifier(decision.id); identifier(decision.resolvedBy); identifier(decision.evidenceReference); instant(decision.resolvedAt)
    const total = validateRepaymentReturns(recordedRepaymentReturns(value.original), value.original)
    requireValue(decision.outcome === 'CONFIRMED' ? total === 0n : decision.outcome === 'RETURNED' ? total === amountMinor(value.original.amount.value) : total > 0n && total < amountMinor(value.original.amount.value))
  }
  return value
}
/** 查询保留原首笔字段并追加独立退回，缺失配对或缺失首笔不能被当成零退款。 */
export function repaymentReviewReturns(evidence: RepaymentReviewEvidence): RepaymentReturnProof[] {
  const additional = evidence.additionalReturns === undefined ? [] : evidence.additionalReturns
  requireValue(Array.isArray(additional) && additional.length < 100)
  if (evidence.fundsReturn === null && evidence.posting === null) { requireValue(additional.length === 0); return [] }
  requireValue(evidence.fundsReturn && evidence.posting)
  return [{ fundsReturn: evidence.fundsReturn, posting: evidence.posting }, ...additional]
}
/** 查询只引用当前借款版本，全部外部身份由服务端取得。 */
export function repaymentReviewQueryInput(view: RepaymentReviewView, comment: string): RepaymentReviewQueryInput {
  requireValue(view.canQuery); return { advanceVersion: view.balance.version, comment: note(comment) }
}
/** 决定来自已展示证据，不允许页面填写退款金额或改变资金方向。 */
export function repaymentResolutionInput(view: RepaymentReviewView, reference: string, comment: string, now = Date.now()): RepaymentResolutionInput {
  const check = view.latestCheck; requireValue(check?.canResolve && check.evidence && check.evidence.status !== 'UNRESOLVED')
  if (now < instant(check.evidence.observedAt) || now >= instant(check.evidence.validUntil)) throw new Error('复核证据已过期，请重新查询后确认。')
  const evidenceReference = reference.trim(); identifier(evidenceReference)
  return { advanceVersion: view.balance.version, checkId: check.id, checkVersion: check.version, outcome: check.evidence.status, evidenceReference, comment: note(comment) }
}
/** 成功回执必须属于本笔原还款及精确连续版本。 */
export function validateRepaymentReviewReceipt(receipt: RepaymentReviewActionReceipt, view: RepaymentReviewView, input: RepaymentReviewQueryInput | RepaymentResolutionInput) {
  requireValue(receipt && receipt.advanceId === view.advanceId && receipt.repaymentId === view.original.id); identifier(receipt.checkId); identifier(receipt.auditEventId)
  if ('checkId' in input) { identifier(receipt.resolutionId); requireValue(receipt.checkId === input.checkId && receipt.checkVersion === input.checkVersion + 1 && receipt.advanceVersion === input.advanceVersion + 1) }
  else requireValue(receipt.resolutionId === null && receipt.checkVersion === 1 && receipt.advanceVersion === input.advanceVersion)
}
