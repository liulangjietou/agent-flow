import { amountMinor, type Money } from './expenses.js'
import { supplierSettlementLabels, settlementRejectionLabels, type SupplierSettlementBinding } from './supplierSettlement.js'

export { supplierSettlementLabels, settlementRejectionLabels }
export const settlementDisputeOutcomes = { PENDING: 'ERP 仍在处理', SETTLED: 'ERP 已确认核销', REJECTED: 'ERP 明确拒绝核销', NOT_FOUND: '暂未查到原核销' }
export const settlementDisputeIssues = {
  NOT_DISPUTED: '当前未处于可裁决的争议状态。', NON_TERMINAL: '尚无原核销的明确终态，请查询原核销。', STALE_EVIDENCE: '本次回执早于已知事实，请查询原核销。',
  EXPIRED_EVIDENCE: '本次核对依据已到期，请重新查询。', HISTORY_CHANGED: '原核销历史不完整，暂不能裁决。',
  SETTLEMENT_ALREADY_OBSERVED: '历史中已有核销证据，不能裁决为未核销。真实冲销需要独立调整。', DIFFERENT_POSTING: '本次回执未保留原核销凭证或前后余额，请核对原账务。'
}
export interface SettlementDisputeBinding extends SupplierSettlementBinding { settlementId: string; amount: Money }
export interface SettlementDisputeFact {
  outcome: keyof typeof settlementDisputeOutcomes; revision: number; observedAt: string; validUntil: string; rejection: keyof typeof settlementRejectionLabels | null
  posting: { settlementReference: string; voucherReference: string; amount: Money; settledBefore: Money; settledAfter: Money; periodReference: string; accountingDate: string; settledAt: string } | null
}
export interface SettlementDisputeView extends SettlementDisputeBinding {
  settlementVersion: number; status: keyof typeof supplierSettlementLabels; observed: SettlementDisputeFact | null; candidate: SettlementDisputeFact | null
  issue: keyof typeof settlementDisputeIssues | null; canResolve: boolean
  latest: { id: string; settlementVersion: number; outcome: 'SETTLED' | 'REJECTED'; resolvedBy: string; resolvedAt: string; evidenceReference: string } | null
}
export interface SettlementDisputeInput { settlementVersion: number; outcome: 'SETTLED' | 'REJECTED'; evidenceReference: string; comment: string }
export interface SettlementDisputeReceipt extends SupplierSettlementBinding { settlementId: string; settlementVersion: number; status: 'SETTLED' | 'REJECTED'; resolutionId: string; auditEventId: string }
const keys = ['settlementId', 'paymentId', 'requestId', 'applicationId', 'roundNo'] as const
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) > 0
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim() && v === v.trim() && !/[\u0000-\u001f\u007f]/.test(v)
const time = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
const terminal = (v: string): v is 'SETTLED' | 'REJECTED' => ['SETTLED', 'REJECTED'].includes(v)
const money = (v: Money | null): v is Money => !!v && typeof v.value === 'string' && /^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(v.value) && /^[A-Z]{3}$/.test(v.currency)
const calendarDate = (v: unknown): v is string => typeof v === 'string' && /^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$/.test(v) && time(v) && new Date(v).toISOString().slice(0, 10) === v

function validateFact(v: SettlementDisputeFact, amount: Money) {
  if (!v || !known(settlementDisputeOutcomes, v.outcome) || !Number.isSafeInteger(v.revision) || (v.outcome === 'NOT_FOUND' ? v.revision !== 0 : v.revision < 1)
      || !time(v.observedAt) || !time(v.validUntil) || Date.parse(v.validUntil) - Date.parse(v.observedAt) !== 300_000
      || (v.outcome === 'REJECTED' ? !v.rejection || !known(settlementRejectionLabels, v.rejection) : v.rejection !== null)) throw new Error('原 ERP 回执不完整，请重新查询。')
  const p = v.posting
  if (v.outcome !== 'SETTLED') { if (p !== null) throw new Error('尚未核销的回执不能包含已核销凭证。'); return }
  if (!p || !text(p.settlementReference) || !text(p.voucherReference) || !text(p.periodReference) || !calendarDate(p.accountingDate)
      || !time(p.settledAt) || Date.parse(p.settledAt) > Date.parse(v.observedAt) || !money(p.amount) || !money(p.settledBefore) || !money(p.settledAfter)
      || p.amount.value !== amount.value || p.amount.currency !== amount.currency || p.settledBefore.currency !== amount.currency || p.settledAfter.currency !== amount.currency
      || amountMinor(p.settledBefore.value) + amountMinor(p.amount.value) !== amountMinor(p.settledAfter.value)) throw new Error('原核销凭证、金额或累计前后余额不一致，请核对。')
}

/** 核销号、原采购轮次与金额同时绑定，历史决定不代表后续争议已经解除。 */
export function validateSettlementDispute(v: SettlementDisputeView, expected: SettlementDisputeBinding) {
  if (!v || !keys.every(key => v[key] === expected[key]) || ![v.settlementId, v.paymentId, v.requestId, v.applicationId].every(text) || !positive(v.roundNo)
      || !positive(v.settlementVersion) || !known(supplierSettlementLabels, v.status) || !money(v.amount) || amountMinor(v.amount.value) <= 0n
      || v.amount.value !== expected.amount.value || v.amount.currency !== expected.amount.currency || typeof v.canResolve !== 'boolean'
      || v.issue !== null && !known(settlementDisputeIssues, v.issue)) throw new Error('原核销、申请轮次或金额不一致，请刷新。')
  if (v.observed !== null) validateFact(v.observed, v.amount)
  if (v.candidate !== null) validateFact(v.candidate, v.amount)
  if (v.status === 'RECONCILING' && v.candidate === null || v.canResolve && (v.status !== 'RECONCILING' || !v.candidate || !terminal(v.candidate.outcome) || v.issue !== null)
      || ['SETTLED', 'REJECTED', 'NOT_FOUND'].includes(v.status) && (v.observed?.outcome !== v.status || v.candidate !== null)) throw new Error('裁决能力与原核销状态不一致，请刷新。')
  if (v.latest !== null && (!v.latest || !text(v.latest.id) || !positive(v.latest.settlementVersion) || v.latest.settlementVersion > v.settlementVersion
      || !terminal(v.latest.outcome) || !text(v.latest.resolvedBy) || !time(v.latest.resolvedAt) || !text(v.latest.evidenceReference))) throw new Error('历史核销裁决不完整，请刷新。')
  return v
}
export function settlementDisputeAllowed(v: SettlementDisputeView | null, now = Date.now()) {
  return !!v?.canResolve && !!v.candidate && Date.parse(v.candidate.validUntil) > now
}
/** 请求只采用已展示候选，客户端不能定义账务事实。 */
export function settlementDisputeInput(v: SettlementDisputeView, comment: string, evidenceReference: string, now = Date.now()): SettlementDisputeInput {
  validateSettlementDispute(v, v)
  if (!settlementDisputeAllowed(v, now)) throw new Error('当前状态或核对期限不允许裁决，请查询原核销。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的操作说明。')
  const reference = evidenceReference.trim()
  if (!text(reference) || reference.length > 128) throw new Error('请填写 128 字以内的外部对账凭据编号。')
  return { settlementVersion: v.settlementVersion, outcome: v.candidate!.outcome as 'SETTLED' | 'REJECTED', comment: comment.trim(), evidenceReference: reference }
}
/** 精确相邻修订及原轮次核对后，仍重新读取本地完成与银行状态。 */
export function validateSettlementDisputeReceipt(r: SettlementDisputeReceipt, v: SettlementDisputeView) {
  if (!r || !keys.every(key => r[key] === v[key]) || r.settlementVersion !== v.settlementVersion + 1 || r.status !== v.candidate?.outcome
      || !text(r.resolutionId) || !text(r.auditEventId)) throw new Error('裁决回执未对应原核销，请恢复原请求后核对。')
}
export function settlementDisputeError(cause: unknown) {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string } | null)?.code
  const labels: Record<string, string> = { FORBIDDEN: '当前角色、法人任职或原轮次字段权限不允许裁决。', NOT_FOUND: '当前范围内无法读取原核销。', CONCURRENCY_CONFLICT: '原核销版本已变化，请刷新。', SUPPLIER_SETTLEMENT_DISPUTE_UNRESOLVABLE: '当前证据不能支持裁决，请查询原核销并核对原凭证。', INVALID_SUPPLIER_SETTLEMENT_DISPUTE_RESOLUTION: '原核销依据或独立财务身份不符合裁决要求。', REQUEST_TIMEOUT: '结果尚未确认，请恢复原请求后刷新。' }
  return code && labels[code] || '原核销裁决未完成，请核对原请求与当前状态。'
}
