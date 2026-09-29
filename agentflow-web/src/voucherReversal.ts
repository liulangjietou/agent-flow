import { amountMinor, expenseError, type Money } from './expenses.js'
import { operationLabels, type VoucherKind } from './vouchers.js'

/** 独立冲销沿原凭证版本登记，逐行反向核验由服务端完成。 */
export interface VoucherReversalBinding { applicationId: string; operationId: string; roundNo: number; applicationVersion: number; businessVersion: number; operationVersion: number; kind: VoucherKind }
export interface ReversalLine { entryReference: string; originalLineNo: number; accountCode: string; side: 'DEBIT' | 'CREDIT'; amount: Money; sourceLineNo: number; costCenter?: string | null; projectCode?: string | null; advanceId?: string | null }
export interface ReversePosting { postingReference: string; voucherReference: string; periodReference: string; accountingDate: string; postedAt: string; lines: ReversalLine[] }
export interface ReversalOriginal { postingReference: string; voucherReference: string; periodReference: string; accountingDate: string; total: Money; postedAt: string }
export interface VoucherReversalView extends VoucherReversalBinding {
  originalStatus: keyof typeof operationLabels; original: ReversalOriginal; canQuery: boolean
  latestCheck: null | { id: string; version: number; status: keyof typeof reversalCheckLabels; requestedAt: string; updatedAt: string; issue: string | null; canRecord: boolean; confirmationIssue: string | null
    evidence: null | { status: 'UNRESOLVED' | 'VERIFIED'; revision: number; observedAt: string; validUntil: string; originalRevision: number | null; originalStatus: string | null; reversal: ReversePosting | null } }
  record: null | { id: string; operationVersion: number; reversal: ReversePosting; recordedBy: string; recordedAt: string; evidenceReference: string; comment: string }
}
export interface ReversalQueryInput { roundNo: number; applicationVersion: number; businessVersion: number; operationVersion: number; comment: string }
export interface ReversalRecordInput extends ReversalQueryInput { checkId: string; checkVersion: number; evidenceReference: string }
export interface ReversalActionReceipt { applicationId: string; operationId: string; roundNo: number; checkId: string; checkVersion: number; recordId: string | null; operationVersion: number; auditEventId: string }
export const reversalCheckLabels = { QUEUED: '等待核验冲销凭证', RUNNING: '正在核验反向分录', CHECKED: '冲销核验已返回', RECORDED: '独立冲销已登记', UNAVAILABLE: '冲销核验暂不可用', VOIDED: '原凭证或财务资格已变化' }
const issues: Record<string, string> = {
  VOUCHER_REVERSAL_ALREADY_RECORDED: '原凭证或这张反向凭证已经登记，不能重复办理或恢复原过账。', VOUCHER_REVERSAL_PENDING: '冲销核验正在处理，请刷新查看。',
  VOUCHER_REVERSAL_ORIGINAL_UNRESOLVED: '请先核清原凭证，并明确确认原凭证已冲销。', VOUCHER_REVERSAL_EVIDENCE_UNAVAILABLE: '完整反向凭证尚未核实或依据已过期，请重新查询。',
  VOUCHER_REVERSAL_EVIDENCE_CHANGED: '原凭证或反向分录与已知事实不一致，请核对 ERP 原件。', VOUCHER_REVERSAL_SOURCE_CHANGED: '原凭证来源已变化，请刷新核对。',
  TIMEOUT: '冲销核验超时，请重新查询。', TARGET_CHANGED: '原 ERP 配置已变化，请联系财务管理员。', SOURCE_CHANGED: '原凭证状态或当前财务资格已变化。'
}
export const reversalIssue = (code: string) => issues[code] ?? '当前冲销核验依据不可用，请核对 ERP 原件。'
export const reversalError = (cause: unknown) => cause instanceof Error ? cause.message : issues[(cause as { code?: string })?.code ?? ''] ?? expenseError(cause)
function requireValue(condition: unknown): asserts condition { if (!condition) throw new Error('独立冲销资料未通过校验，请刷新核对。') }
function identifier(value: unknown): asserts value is string { requireValue(typeof value === 'string' && !!value.trim() && value.length <= 128) }
function version(value: number) { requireValue(Number.isSafeInteger(value) && value > 0) }
function instant(value: string) { requireValue(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function date(value: string) { requireValue(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value) }
function note(value: string) { if (!value.trim() || value.length > 2000) throw new Error('请填写 2000 字以内的核对说明。'); return value.trim() }
function posting(value: ReversePosting, original: ReversalOriginal, observedAt: number) {
  requireValue(value); identifier(value.postingReference); identifier(value.voucherReference); identifier(value.periodReference); date(value.accountingDate)
  requireValue(value.postingReference !== original.postingReference && value.voucherReference !== original.voucherReference && value.accountingDate >= original.accountingDate
    && instant(value.postedAt) >= instant(original.postedAt) && instant(value.postedAt) <= observedAt && Array.isArray(value.lines) && value.lines.length >= 2)
  let debit = 0n, credit = 0n; const seen = new Set<string>()
  value.lines.forEach((line, index) => {
    requireValue(line && line.originalLineNo === index + 1); identifier(line.entryReference); identifier(line.accountCode)
    requireValue(!seen.has(line.entryReference)); seen.add(line.entryReference)
    requireValue(['DEBIT', 'CREDIT'].includes(line.side) && Number.isSafeInteger(line.sourceLineNo) && line.sourceLineNo >= 0)
    for (const dimension of [line.costCenter, line.projectCode, line.advanceId]) if (dimension != null) identifier(dimension)
    requireValue(line.amount && line.amount.currency === original.total.currency); const amount = amountMinor(line.amount.value); requireValue(amount > 0n)
    if (line.side === 'DEBIT') debit += amount; else credit += amount
  })
  requireValue(debit === credit && debit === amountMinor(original.total.value))
}
/** 身份、三个页面版本、借贷合计和证据边界同时校验，不能采用错轮或错凭证结果。 */
export function validateVoucherReversal(value: VoucherReversalView, expected: VoucherReversalBinding) {
  requireValue(value && value.original && ['applicationId', 'operationId', 'roundNo', 'applicationVersion', 'businessVersion', 'operationVersion', 'kind'].every(key => value[key as keyof VoucherReversalBinding] === expected[key as keyof VoucherReversalBinding]))
  identifier(value.applicationId); identifier(value.operationId); [value.roundNo, value.applicationVersion, value.businessVersion, value.operationVersion].forEach(version)
  requireValue(Object.prototype.hasOwnProperty.call(operationLabels, value.originalStatus) && ['EMPLOYEE_ADVANCE', 'EXPENSE_ACCRUAL', 'PAYMENT'].includes(value.kind) && typeof value.canQuery === 'boolean')
  const original = value.original; identifier(original.postingReference); identifier(original.voucherReference); identifier(original.periodReference); date(original.accountingDate); instant(original.postedAt)
  requireValue(original.total && /^[A-Z]{3}$/.test(original.total.currency) && amountMinor(original.total.value) > 0n)
  const check = value.latestCheck
  if (check !== null) {
    requireValue(check && Object.prototype.hasOwnProperty.call(reversalCheckLabels, check.status)); identifier(check.id); version(check.version)
    requireValue(instant(check.requestedAt) <= instant(check.updatedAt) && typeof check.canRecord === 'boolean'
      && (check.issue === null || typeof check.issue === 'string') && (check.confirmationIssue === null || typeof check.confirmationIssue === 'string'))
    const observed = check.status === 'CHECKED' || check.status === 'RECORDED'
    requireValue(observed ? check.evidence !== null && check.issue === null : check.evidence === null && !check.canRecord)
    if (check.evidence !== null) {
      const evidence = check.evidence; version(evidence.revision); const at = instant(evidence.observedAt), until = instant(evidence.validUntil)
      requireValue(at <= instant(check.updatedAt) && until > at && until - at <= 300_000 && ['UNRESOLVED', 'VERIFIED'].includes(evidence.status))
      if (evidence.originalRevision !== null) requireValue(Number.isSafeInteger(evidence.originalRevision) && evidence.originalRevision >= 0)
      requireValue(evidence.originalStatus === null || ['NOT_FOUND', 'PENDING', 'POSTED', 'FAILED', 'REVERSED'].includes(evidence.originalStatus))
      if (evidence.status === 'VERIFIED') { requireValue(evidence.reversal && evidence.originalStatus === 'REVERSED' && evidence.originalRevision !== null); version(evidence.originalRevision); posting(evidence.reversal, original, at) }
      else requireValue(evidence.reversal === null)
      if (check.canRecord) requireValue(check.status === 'CHECKED' && evidence.status === 'VERIFIED' && value.originalStatus === 'REVERSED' && check.confirmationIssue === null && value.record === null)
    }
  }
  if (value.record !== null) {
    requireValue(value.record); identifier(value.record.id); version(value.record.operationVersion); identifier(value.record.recordedBy); identifier(value.record.evidenceReference); note(value.record.comment)
    requireValue(value.record.operationVersion <= value.operationVersion && !value.canQuery && !check?.canRecord)
    posting(value.record.reversal, original, instant(value.record.recordedAt))
    if (check?.status === 'RECORDED') requireValue(check.evidence?.status === 'VERIFIED' && JSON.stringify(check.evidence.reversal) === JSON.stringify(value.record.reversal))
  } else requireValue(check?.status !== 'RECORDED')
  if (value.canQuery) requireValue(value.originalStatus === 'REVERSED' && value.record === null && (!check || !['QUEUED', 'RUNNING'].includes(check.status)))
  return value
}
/** 查询只传固定页面版本和说明，原命令及反向分录从服务器取得。 */
export function reversalQueryInput(value: VoucherReversalView, comment: string): ReversalQueryInput {
  validateVoucherReversal(value, value); requireValue(value.canQuery)
  return versions(value, note(comment))
}
/** 点击确认时再检查有效期，原查询只供一次独立登记。 */
export function reversalRecordInput(value: VoucherReversalView, reference: string, comment: string, now = Date.now()): ReversalRecordInput {
  validateVoucherReversal(value, value); const check = value.latestCheck, evidence = check?.evidence
  requireValue(check?.canRecord && evidence?.status === 'VERIFIED' && instant(evidence.observedAt) <= now && instant(evidence.validUntil) > now)
  if (!reference.trim() || reference.length > 128 || /[\u0000-\u001f\u007f-\u009f]/u.test(reference)) throw new Error('请填写 128 字以内、不含控制字符的核验材料编号。')
  return { ...versions(value, note(comment)), checkId: check.id, checkVersion: check.version, evidenceReference: reference.trim() }
}
function versions(value: VoucherReversalView, comment: string) { return { roundNo: value.roundNo, applicationVersion: value.applicationVersion, businessVersion: value.businessVersion, operationVersion: value.operationVersion, comment } }
/** 登记不会改变原凭证版本；回执必须精确匹配被消费查询。 */
export function validateReversalReceipt(receipt: ReversalActionReceipt, value: VoucherReversalView, input: ReversalQueryInput | ReversalRecordInput) {
  requireValue(receipt && receipt.applicationId === value.applicationId && receipt.operationId === value.operationId && receipt.roundNo === input.roundNo && receipt.operationVersion === input.operationVersion)
  identifier(receipt.checkId); identifier(receipt.auditEventId)
  if ('checkId' in input) { requireValue(receipt.checkId === input.checkId && receipt.checkVersion === input.checkVersion + 1); identifier(receipt.recordId) }
  else requireValue(receipt.checkVersion === 1 && receipt.recordId === null)
}
