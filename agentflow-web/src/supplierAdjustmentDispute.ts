import { amountMinor, type Money } from './expenses.js'
import { adjustmentLabels, adjustmentRejectionLabels, type SupplierAdjustmentOperation } from './supplierAdjustment.js'
import type { SupplierSettlementBinding } from './supplierSettlement.js'

export { adjustmentLabels, adjustmentRejectionLabels }
export const adjustmentDisputeOutcomes = { PENDING: 'ERP 仍在处理', ADJUSTED: 'ERP 已确认调整', REJECTED: 'ERP 明确拒绝调整', NOT_FOUND: '暂未查到原调整' }
export const adjustmentDisputeIssues = {
  NOT_DISPUTED: '当前未处于可裁决的争议状态。', NON_TERMINAL: '尚无原调整的明确终态，请查询原调整。', STALE_EVIDENCE: '本次回执早于已知事实，请查询原调整。',
  EXPIRED_EVIDENCE: '本次核对依据已到期，请重新查询。', HISTORY_CHANGED: '原调整历史不完整，暂不能裁决。',
  ADJUSTMENT_ALREADY_OBSERVED: '历史中已有调整证据，不能裁决为未调整。真实冲销需要独立调整。', DIFFERENT_POSTING: '本次回执未保留原调整凭证或前后余额，请核对原账务。'
}
export interface AdjustmentDisputeBinding extends SupplierSettlementBinding { adjustmentId: string; amount: Money; returnedAmount: Money; totalReturned: Money; netPaid: Money; recognizesOriginalPayment: boolean }
export interface AdjustmentDisputeFact {
  outcome: keyof typeof adjustmentDisputeOutcomes; revision: number; observedAt: string; validUntil: string; rejection: keyof typeof adjustmentRejectionLabels | null
  posting: (NonNullable<SupplierAdjustmentOperation['posting']> & { periodReference: string; accountingDate: string }) | null
}
export interface AdjustmentDisputeView extends AdjustmentDisputeBinding {
  adjustmentVersion: number; status: keyof typeof adjustmentLabels; observed: AdjustmentDisputeFact | null; candidate: AdjustmentDisputeFact | null
  issue: keyof typeof adjustmentDisputeIssues | null; canResolve: boolean
  latest: { id: string; adjustmentVersion: number; outcome: 'ADJUSTED' | 'REJECTED'; resolvedBy: string; resolvedAt: string; evidenceReference: string } | null
}
export interface AdjustmentDisputeInput { adjustmentVersion: number; outcome: 'ADJUSTED' | 'REJECTED'; evidenceReference: string; comment: string }
export interface AdjustmentDisputeReceipt extends SupplierSettlementBinding { adjustmentId: string; adjustmentVersion: number; status: 'ADJUSTED' | 'REJECTED'; resolutionId: string; auditEventId: string }
const amountKeys = ['returnedAmount', 'totalReturned', 'netPaid'] as const
const keys = ['adjustmentId', 'paymentId', 'requestId', 'applicationId', 'roundNo'] as const
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) > 0
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim() && v === v.trim() && !/[\u0000-\u001f\u007f]/.test(v)
const time = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
const terminal = (v: string): v is 'ADJUSTED' | 'REJECTED' => ['ADJUSTED', 'REJECTED'].includes(v)
const money = (v: Money | null): v is Money => !!v && typeof v.value === 'string' && /^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(v.value) && /^[A-Z]{3}$/.test(v.currency)
const calendarDate = (v: unknown): v is string => typeof v === 'string' && /^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$/.test(v) && time(v) && new Date(v).toISOString().slice(0, 10) === v

function validateFact(v: AdjustmentDisputeFact, scope: AdjustmentDisputeBinding) {
  if (!v || !known(adjustmentDisputeOutcomes, v.outcome) || !Number.isSafeInteger(v.revision) || (v.outcome === 'NOT_FOUND' ? v.revision !== 0 : v.revision < 1)
      || !time(v.observedAt) || !time(v.validUntil) || Date.parse(v.validUntil) - Date.parse(v.observedAt) !== 300_000
      || (v.outcome === 'REJECTED' ? !v.rejection || !known(adjustmentRejectionLabels, v.rejection) : v.rejection !== null)) throw new Error('原 ERP 回执不完整，请重新查询。')
  const p = v.posting
  if (v.outcome !== 'ADJUSTED') { if (p !== null) throw new Error('尚未调整的回执不能包含已记账凭证。'); return }
  if (!p || !text(p.adjustmentReference) || !text(p.recognitionVoucherReference) || !text(p.periodReference) || !calendarDate(p.accountingDate)
      || !time(p.adjustedAt) || Date.parse(p.adjustedAt) > Date.parse(v.observedAt)
      || ![p.returnedAmount, p.totalReturned, p.netPaid, p.payableSettledBefore, p.payableSettledAfter].every(m => money(m) && m.currency === scope.amount.currency)
      || !amountKeys.every(key => p[key].value === scope[key].value)
      || amountMinor(p.payableSettledBefore.value) + (scope.recognizesOriginalPayment ? amountMinor(scope.amount.value) : 0n) !== amountMinor(p.payableSettledAfter.value) + amountMinor(p.returnedAmount.value)
      || !Array.isArray(p.entries) || !p.entries.length || p.entries.length > 100) throw new Error('原调整凭证、资金范围或前后余额不一致，请核对。')
  const transactions = new Set<string>(), entries = new Set<string>(); let total = 0n
  for (const entry of p.entries) {
    const key = JSON.stringify([entry?.voucherReference, entry?.entryReference])
    if (!entry || !text(entry.transactionReference) || !text(entry.voucherReference) || !text(entry.entryReference)
        || !money(entry.amount) || entry.amount.currency !== scope.amount.currency || amountMinor(entry.amount.value) <= 0n
        || entry.voucherReference === p.recognitionVoucherReference || transactions.has(entry.transactionReference) || entries.has(key)) throw new Error('原调整入款与会计分录不完整或重复，请核对。')
    total += amountMinor(entry.amount.value); transactions.add(entry.transactionReference); entries.add(key)
  }
  if (total !== amountMinor(scope.returnedAmount.value)) throw new Error('入款分录合计与本次调整金额不一致，请核对。')
}

/** 调整号、原采购轮次与金额同时绑定，历史决定不代表后续争议已经解除。 */
export function validateAdjustmentDispute(v: AdjustmentDisputeView, expected: AdjustmentDisputeBinding) {
  if (!v || !keys.every(key => v[key] === expected[key]) || ![v.adjustmentId, v.paymentId, v.requestId, v.applicationId].every(text) || !positive(v.roundNo)
      || !positive(v.adjustmentVersion) || !known(adjustmentLabels, v.status) || !money(v.amount) || amountMinor(v.amount.value) <= 0n
      || v.amount.value !== expected.amount.value || v.amount.currency !== expected.amount.currency || typeof v.canResolve !== 'boolean'
      || v.issue !== null && !known(adjustmentDisputeIssues, v.issue)) throw new Error('原调整、申请轮次或金额不一致，请刷新。')
  if (!amountKeys.every(key => money(v[key]) && v[key].currency === v.amount.currency && v[key].value === expected[key].value && v[key].currency === expected[key].currency)
      || amountMinor(v.returnedAmount.value) <= 0n || amountMinor(v.totalReturned.value) < amountMinor(v.returnedAmount.value)
      || amountMinor(v.totalReturned.value) + amountMinor(v.netPaid.value) !== amountMinor(v.amount.value)
      || typeof v.recognizesOriginalPayment !== 'boolean' || v.recognizesOriginalPayment !== expected.recognizesOriginalPayment) throw new Error('本次回款调整范围不一致，请刷新。')
  if (v.observed !== null) validateFact(v.observed, v)
  if (v.candidate !== null) validateFact(v.candidate, v)
  if (v.status === 'RECONCILING' && v.candidate === null || v.canResolve && (v.status !== 'RECONCILING' || !v.candidate || !terminal(v.candidate.outcome) || v.issue !== null)
      || ['ADJUSTED', 'REJECTED', 'NOT_FOUND'].includes(v.status) && (v.observed?.outcome !== v.status || v.candidate !== null)) throw new Error('裁决能力与原调整状态不一致，请刷新。')
  if (v.latest !== null && (!v.latest || !text(v.latest.id) || !positive(v.latest.adjustmentVersion) || v.latest.adjustmentVersion > v.adjustmentVersion
      || !terminal(v.latest.outcome) || !text(v.latest.resolvedBy) || !time(v.latest.resolvedAt) || !text(v.latest.evidenceReference))) throw new Error('历史调整裁决不完整，请刷新。')
  return v
}
export function adjustmentDisputeAllowed(v: AdjustmentDisputeView | null, now = Date.now()) {
  return !!v?.canResolve && !!v.candidate && Date.parse(v.candidate.validUntil) > now
}
/** 请求只采用已展示候选，客户端不能定义账务事实。 */
export function adjustmentDisputeInput(v: AdjustmentDisputeView, comment: string, evidenceReference: string, now = Date.now()): AdjustmentDisputeInput {
  validateAdjustmentDispute(v, v)
  if (!adjustmentDisputeAllowed(v, now)) throw new Error('当前状态或核对期限不允许裁决，请查询原调整。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的操作说明。')
  const reference = evidenceReference.trim()
  if (!text(reference) || reference.length > 128) throw new Error('请填写 128 字以内的外部对账凭据编号。')
  return { adjustmentVersion: v.adjustmentVersion, outcome: v.candidate!.outcome as 'ADJUSTED' | 'REJECTED', comment: comment.trim(), evidenceReference: reference }
}
/** 精确相邻修订及原轮次核对后，仍重新读取本地完成与银行状态。 */
export function validateAdjustmentDisputeReceipt(r: AdjustmentDisputeReceipt, v: AdjustmentDisputeView) {
  if (!r || !keys.every(key => r[key] === v[key]) || r.adjustmentVersion !== v.adjustmentVersion + 1 || r.status !== v.candidate?.outcome
      || !text(r.resolutionId) || !text(r.auditEventId)) throw new Error('裁决回执未对应原调整，请恢复原请求后核对。')
}
export function adjustmentDisputeError(cause: unknown) {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string } | null)?.code
  const labels: Record<string, string> = { FORBIDDEN: '当前角色、法人任职或原轮次字段权限不允许裁决。', NOT_FOUND: '当前范围内无法读取原调整。', CONCURRENCY_CONFLICT: '原调整版本已变化，请刷新。', SUPPLIER_ADJUSTMENT_DISPUTE_UNRESOLVABLE: '当前证据不能支持裁决，请查询原调整并核对原凭证。', INVALID_SUPPLIER_ADJUSTMENT_DISPUTE_RESOLUTION: '原调整依据或独立财务身份不符合裁决要求。', REQUEST_TIMEOUT: '结果尚未确认，请恢复原请求后刷新。' }
  return code && labels[code] || '原调整裁决未完成，请核对原请求与当前状态。'
}
