import { amountMinor, expenseError, type Money } from './expenses.js'

/** 还款独立保留资金和记账依据，所有金额来自服务端。@author owlzhangfq@gmail.com */
export interface RepaymentBinding { applicationId: string; advanceId: string; roundNo: number }
export type RepaymentChannel = 'BANK_TRANSFER' | 'CASH' | 'PAYROLL'
export type AdvanceBalanceStatus = 'PAID_OUT' | 'PARTIALLY_SETTLED' | 'SETTLED' | 'RETURNED' | 'PAYMENT_REVIEW' | 'REPAYMENT_REVIEW'
export interface AdvanceBalance { version: number; status: AdvanceBalanceStatus; paid: Money; available: Money; reserved: Money; offset: Money; repaid: Money; outstanding: Money; receivedRepayments: Money; returnedRepayments: Money; returnedDisbursements?: Money }
export interface RepaymentFunding { channel: RepaymentChannel; transactionReference: string; amount: Money; receivedAt: string }
export interface RepaymentPosting { voucherReference: string; entryReference: string; amount: Money; accountingDate: string; postedAt: string }
export interface RepaymentEvidence { status: 'NOT_FOUND' | 'PENDING' | 'CONFIRMED' | 'REVERSED'; revision: number; observedAt: string; validUntil: string; funding: RepaymentFunding | null; posting: RepaymentPosting | null }
export interface RepaymentCheck { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RECORDED' | 'UNAVAILABLE' | 'VOIDED'; receiptReference: string; requestedAt: string; updatedAt: string; evidence: RepaymentEvidence | null; issue: string | null; canRecord: boolean; confirmationIssue: string | null }
export interface RepaymentReturnedFunds { channel: RepaymentChannel; transactionReference: string; amount: Money; returnedAt: string }
export interface RepaymentReturnProof { fundsReturn: RepaymentReturnedFunds; posting: RepaymentPosting }
export interface RepaymentReturn extends RepaymentReturnProof { resolutionId: string; repaymentId: string }
export interface RepaymentRecord { id: string; receiptReference: string; channel: RepaymentChannel; amount: Money; receivedAt: string; voucherReference: string; entryReference: string; accountingDate: string; postedAt: string; recordedBy: string; recordedAt: string; reviewRequired: boolean; returned: RepaymentReturn | null; additionalReturns?: RepaymentReturn[] }
export interface RepaymentView extends RepaymentBinding { balance: AdvanceBalance | null; canQuery: boolean; latestCheck: RepaymentCheck | null; records: RepaymentRecord[]; nextBeforeId: string | null }
export interface RepaymentQueryInput { advanceVersion: number; receiptReference: string; comment: string }
export interface RepaymentRecordInput { advanceVersion: number; checkId: string; checkVersion: number; comment: string }
export interface RepaymentActionReceipt { advanceId: string; checkId: string; checkVersion: number; repaymentId: string | null; advanceVersion: number; auditEventId: string }
export const advanceBalanceLabels: Record<AdvanceBalanceStatus, string> = { PAID_OUT: '已放款', PARTIALLY_SETTLED: '部分归还 / 冲销', SETTLED: '已结清', RETURNED: '原放款已全额退回', PAYMENT_REVIEW: '付款待核对，暂停使用', REPAYMENT_REVIEW: '还款待核对，暂停使用' }
export const repaymentChannelLabels: Record<RepaymentChannel, string> = { BANK_TRANSFER: '银行转账', CASH: '现金收款', PAYROLL: '工资抵扣' }
export const repaymentCheckLabels: Record<RepaymentCheck['status'], string> = { QUEUED: '等待查询', RUNNING: '正在查询', CHECKED: '已取得查询结果', RECORDED: '已确认还款', UNAVAILABLE: '本次查询不可用', VOIDED: '本次查询已失效' }
export const repaymentEvidenceLabels: Record<RepaymentEvidence['status'], string> = { NOT_FOUND: '未查到原收款', PENDING: '尚未完成收款及入账', CONFIRMED: '已收款并已入账', REVERSED: '原收款已撤销' }
const issueLabels: Record<string, string> = {
  ADVANCE_PAYMENT_REVIEW_REQUIRED: '原放款正在核对，暂时不能确认还款。', ADVANCE_REPAYMENT_REVIEW_REQUIRED: '已确认还款出现变化，请先完成财务核对。',
  ADVANCE_REPAYMENT_EVIDENCE_UNAVAILABLE: '尚无可确认的有效收款，请核对原收款编号后重新查询。', ADVANCE_REPAYMENT_EVIDENCE_CHANGED: '原收款依据已变化，请重新核对资金及记账记录。',
  ADVANCE_REPAYMENT_ALREADY_RECORDED: '这笔收款或记账分录已经确认，不能重复冲减借款。', ADVANCE_REPAYMENT_BEFORE_DISBURSEMENT: '收款早于本笔放款，请核对原借款归属。',
  INSUFFICIENT_FINANCIAL_BALANCE: '本次收款超过未预留余额，请先核对报销预留与借款余额。', ADVANCE_REPAYMENT_CHECK_PENDING: '已有查询正在处理，请刷新查看。',
  PAYMENT_ACTOR_UNAVAILABLE: '当前财务任职已变化，暂时不能办理。', ADVANCE_REPAYMENT_CHECK_CONFLICT: '还款查询状态已变化，请刷新后重新核对。',
  TIMEOUT: '收款查询超时，可重新发起查询。', SOURCE_CHANGED: '原付款或当前财务身份已变化，请重新核对。', TARGET_CHANGED: '原资金服务配置已变化，请联系财务管理员。'
}
export function repaymentIssue(code: string) { return issueLabels[code] ?? '当前还款依据不可用，请核对原收款并刷新。' }
export function repaymentError(cause: unknown) { return cause instanceof Error ? cause.message : issueLabels[(cause as { code?: string })?.code ?? ''] ?? expenseError(cause) }
function requireValue(condition: unknown): asserts condition { if (!condition) throw new Error('还款资料未通过校验，请刷新核对。') }
function identifier(value: unknown): asserts value is string { requireValue(typeof value === 'string' && value.trim() === value && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f]/.test(value)) }
function version(value: number) { requireValue(Number.isSafeInteger(value) && value > 0) }
function instant(value: string) { requireValue(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function date(value: string) { requireValue(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value) }
function money(value: Money, currency: string, positive = false) { requireValue(value && value.currency === currency && /^[A-Z]{3}$/.test(currency)); const amount = amountMinor(value.value); requireValue(!positive || amount > 0n); return amount }
function owns(labels: object, value: string) { return Object.prototype.hasOwnProperty.call(labels, value) }
function channel(value: RepaymentChannel) { requireValue(owns(repaymentChannelLabels, value)) }
function note(value: string) { const trimmed = value.trim(); if (!trimmed || value.length > 2000) throw new Error('请填写 2000 字以内的核对说明。'); return trimmed }

/** 原申请、原借款和原轮次必须同时一致，冻结余额仍保留全部历史金额。 */
export function validateRepaymentView(value: RepaymentView, binding: RepaymentBinding): RepaymentView {
  requireValue(value && value.applicationId === binding.applicationId && value.advanceId === binding.advanceId && value.roundNo === binding.roundNo)
  identifier(value.applicationId); identifier(value.advanceId); version(value.roundNo)
  requireValue(typeof value.canQuery === 'boolean' && Array.isArray(value.records) && value.records.length <= 25)
  const balance = value.balance
  if (balance === null) requireValue(!value.canQuery && value.latestCheck === null && value.records.length === 0 && value.nextBeforeId === null)
  else {
    requireValue(balance && owns(advanceBalanceLabels, balance.status)); version(balance.version)
    const currency = balance.paid.currency, paid = money(balance.paid, currency, true), offset = money(balance.offset, currency), repaid = money(balance.repaid, currency)
    const outstanding = money(balance.outstanding, currency), reserved = money(balance.reserved, currency), available = money(balance.available, currency)
    const returned = balance.returnedDisbursements === undefined ? 0n : money(balance.returnedDisbursements, currency)
    requireValue(money(balance.receivedRepayments, currency) - money(balance.returnedRepayments, currency) === repaid)
    const held = balance.status === 'PAYMENT_REVIEW' || balance.status === 'REPAYMENT_REVIEW'
    requireValue(paid === offset + repaid + returned + outstanding && reserved <= outstanding && available === (held ? 0n : outstanding - reserved))
    if (!held) requireValue(balance.status === (returned === paid ? 'RETURNED' : outstanding === 0n ? 'SETTLED' : offset + repaid + returned > 0n ? 'PARTIALLY_SETTLED' : 'PAID_OUT'))
    for (const row of value.records) {
      for (const field of [row.id, row.receiptReference, row.voucherReference, row.entryReference, row.recordedBy]) identifier(field)
      channel(row.channel); money(row.amount, currency, true); date(row.accountingDate)
      requireValue(instant(row.receivedAt) <= instant(row.postedAt) && instant(row.postedAt) <= instant(row.recordedAt))
      requireValue(typeof row.reviewRequired === 'boolean')
      const returns = recordedRepaymentReturns(row)
      for (const entry of returns) { requireValue(entry && entry.repaymentId === row.id); identifier(entry.resolutionId) }
      validateRepaymentReturns(returns, row)
    }
    requireValue(new Set(value.records.map(row => row.id)).size === value.records.length)
    if (value.nextBeforeId !== null) requireValue(value.records.length === 25 && value.nextBeforeId === value.records[value.records.length - 1]?.id)
  }
  const check = value.latestCheck
  if (check !== null) {
    requireValue(balance && check && owns(repaymentCheckLabels, check.status)); identifier(check.id); identifier(check.receiptReference); version(check.version)
    requireValue(instant(check.requestedAt) <= instant(check.updatedAt) && typeof check.canRecord === 'boolean')
    requireValue(check.issue === null || typeof check.issue === 'string'); requireValue(check.confirmationIssue === null || typeof check.confirmationIssue === 'string')
    const observed = check.status === 'CHECKED' || check.status === 'RECORDED'
    requireValue(observed ? check.evidence !== null && check.issue === null : check.evidence === null && !check.canRecord)
    requireValue(!value.canQuery || !['QUEUED', 'RUNNING'].includes(check.status))
    if (check.evidence !== null) {
      const evidence = check.evidence; requireValue(owns(repaymentEvidenceLabels, evidence.status))
      requireValue(evidence.status === 'NOT_FOUND' ? evidence.revision === 0 : Number.isSafeInteger(evidence.revision) && evidence.revision > 0)
      const observedAt = instant(evidence.observedAt), until = instant(evidence.validUntil)
      requireValue(until > observedAt && until - observedAt <= 300000 && observedAt <= instant(check.updatedAt))
      if (evidence.status === 'CONFIRMED' || evidence.status === 'REVERSED') {
        const funding = evidence.funding, posting = evidence.posting; requireValue(funding && posting)
        channel(funding.channel); identifier(funding.transactionReference); identifier(posting.voucherReference); identifier(posting.entryReference); date(posting.accountingDate)
        requireValue(money(funding.amount, balance.paid.currency, true) === money(posting.amount, balance.paid.currency, true))
        requireValue(instant(funding.receivedAt) <= instant(posting.postedAt) && instant(posting.postedAt) <= observedAt)
      } else requireValue(evidence.funding === null && evidence.posting === null)
      if (check.canRecord) requireValue(check.status === 'CHECKED' && evidence.status === 'CONFIRMED' && check.confirmationIssue === null && !['PAYMENT_REVIEW', 'REPAYMENT_REVIEW'].includes(balance.status) && amountMinor(evidence.funding!.amount.value) <= amountMinor(balance.available.value))
      if (check.status === 'RECORDED') requireValue(evidence.status === 'CONFIRMED' && !check.canRecord)
    }
  }
  return value
}

/** 原收款和退回分开显示，退款与借方分录的金额、方向及时间不能互相矛盾。 */
export function validateRepaymentReturnProof(funds: RepaymentReturnedFunds, posting: RepaymentPosting, original: RepaymentRecord) {
  requireValue(funds && posting); channel(funds.channel); identifier(funds.transactionReference); identifier(posting.voucherReference); identifier(posting.entryReference); date(posting.accountingDate)
  const currency = original.amount.currency, amount = money(original.amount, currency, true)
  const returned = money(funds.amount, currency, true)
  requireValue(returned <= amount && money(posting.amount, currency, true) === returned)
  requireValue(instant(funds.returnedAt) >= instant(original.receivedAt) && instant(posting.postedAt) >= instant(funds.returnedAt) && instant(posting.postedAt) >= instant(original.postedAt))
  requireValue(posting.voucherReference !== original.voucherReference || posting.entryReference !== original.entryReference)
}

/** 兼容原单笔字段，同时完整列出每笔后续退回。 */
export function recordedRepaymentReturns(row: RepaymentRecord): RepaymentReturn[] {
  const additional = row.additionalReturns === undefined ? [] : row.additionalReturns
  requireValue(Array.isArray(additional) && additional.length < 100 && (row.returned !== null || additional.length === 0))
  return row.returned === null ? [] : [row.returned, ...additional]
}
/** 分别核对资金及分录身份，不能靠重复条目或累计超额增加欠款。 */
export function validateRepaymentReturns(entries: RepaymentReturnProof[], original: RepaymentRecord) {
  requireValue(Array.isArray(entries) && entries.length <= 100)
  for (const entry of entries) validateRepaymentReturnProof(entry?.fundsReturn, entry?.posting, original)
  const identities = entries.map(entry => JSON.stringify([entry.fundsReturn.channel, entry.fundsReturn.transactionReference]))
  const postings = entries.map(entry => JSON.stringify([entry.posting.voucherReference, entry.posting.entryReference]))
  requireValue(new Set(identities).size === entries.length && new Set(postings).size === entries.length)
  const total = entries.reduce((sum, entry) => sum + amountMinor(entry.fundsReturn.amount.value), 0n)
  requireValue(total <= amountMinor(original.amount.value)); return total
}
/** 展示按分精确累计的退回总额，不改变任何原记录金额。 */
export function repaymentReturnTotal(entries: RepaymentReturnProof[], currency: string): Money {
  const total = entries.reduce((sum, entry) => sum + amountMinor(entry.fundsReturn.amount.value), 0n)
  return { currency, value: `${total / 100n}.${String(total % 100n).padStart(2, '0')}` }
}

/** 页面只提交收款引用和已展示余额版本，不提供改写金额、员工或账号的入口。 */
export function repaymentQueryInput(view: RepaymentView, reference: string, comment: string): RepaymentQueryInput {
  requireValue(view.canQuery && view.balance); const receiptReference = reference.trim(); identifier(receiptReference)
  return { advanceVersion: view.balance.version, receiptReference, comment: note(comment) }
}
/** 确认时再次校验证据时效，已展示的陈旧材料不能继续使用。 */
export function repaymentRecordInput(view: RepaymentView, comment: string, now = Date.now()): RepaymentRecordInput {
  const check = view.latestCheck; requireValue(view.balance && check?.canRecord && check.evidence?.status === 'CONFIRMED')
  if (now < instant(check.evidence.observedAt) || now >= instant(check.evidence.validUntil)) throw new Error('收款查询结果已过期，请重新查询后确认。')
  return { advanceVersion: view.balance.version, checkId: check.id, checkVersion: check.version, comment: note(comment) }
}
/** 回执精确绑定本次动作；成功提示不能来自其他借款或旧确认。 */
export function validateRepaymentReceipt(receipt: RepaymentActionReceipt, view: RepaymentView, input: RepaymentQueryInput | RepaymentRecordInput) {
  requireValue(receipt && receipt.advanceId === view.advanceId); identifier(receipt.checkId); identifier(receipt.auditEventId)
  if ('checkId' in input) { identifier(receipt.repaymentId); requireValue(receipt.checkId === input.checkId && receipt.checkVersion === input.checkVersion + 1 && receipt.advanceVersion === input.advanceVersion + 1) }
  else requireValue(receipt.repaymentId === null && receipt.checkVersion === 1 && receipt.advanceVersion === input.advanceVersion)
}
