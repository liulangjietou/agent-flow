import { paymentOperationLabels } from './payments.js'

export { paymentOperationLabels }
export const paymentFailureLabels = { ACCOUNT_UNAVAILABLE: '供应商收款账户不可用', DEBIT_ACCOUNT_UNAVAILABLE: '出款账户不可用', INSUFFICIENT_FUNDS: '出款账户余额不足', AUTHORIZATION_EXPIRED: '原授权已到期', APPROVAL_CHANGED: '原批准已变化', ACCOUNT_CHANGED: '原账户已变化', VOUCHER_UNAVAILABLE: '原凭证不可用', PAYMENT_REJECTED: '银行明确拒绝付款' }
export const supplierBankOutcomes = { PENDING: '银行仍在处理', SUCCEEDED: '已到账', FAILED: '明确失败', REVERSED: '已退回', NOT_FOUND: '暂未查到' }
export const supplierDisputeIssues = {
  NOT_DISPUTED: '当前未处于可裁决的争议状态。', NON_TERMINAL: '尚无原交易的明确终态，请继续查询。', STALE_EVIDENCE: '本次回执早于已知事实，请查询原交易。',
  EXPIRED_EVIDENCE: '本次核对依据已到期，请重新查询。', HISTORY_CHANGED: '原付款历史不完整，暂不能裁决。', DIFFERENT_PAYMENT: '回执中的银行交易号发生变化，请核对原交易。',
  FUNDING_ALREADY_OBSERVED: '历史中已出现到账或退回，不能裁决为从未付款。', DIFFERENT_SETTLEMENT: '回执未保持原到账金额、时刻或回单。', RETURN_ALREADY_OBSERVED: '原交易已经确认退回，不能恢复成已付款或替换退回回单。'
}
export const supplierDisputeActions = { QUERY: '查询原银行交易', RESOLVE: '确认原付款裁决' }
export type SupplierDisputeAction = keyof typeof supplierDisputeActions
export type SupplierDisputeOutcome = 'SUCCEEDED' | 'FAILED' | 'REVERSED'
export interface SupplierDisputeBinding { paymentId: string; requestId: string; applicationId: string; roundNo: number }
export interface SupplierBankFact {
  outcome: keyof typeof supplierBankOutcomes; revision: number; observedAt: string; validUntil: string
  paymentReference: string | null; receiptReference: string | null; completedAt: string | null; failure: keyof typeof paymentFailureLabels | null
}
export interface SupplierDisputeView extends SupplierDisputeBinding {
  operationVersion: number | null; status: keyof typeof paymentOperationLabels | null
  observed: SupplierBankFact | null; candidate: SupplierBankFact | null; issue: keyof typeof supplierDisputeIssues | null
  canQuery: boolean; canResolve: boolean
  latest: { id: string; operationVersion: number; outcome: SupplierDisputeOutcome; resolvedBy: string; resolvedAt: string; evidenceReference: string } | null
}
export interface SupplierDisputeQueryInput { operationVersion: number; comment: string }
export interface SupplierDisputeResolveInput extends SupplierDisputeQueryInput { outcome: SupplierDisputeOutcome; evidenceReference: string }
export interface SupplierDisputeReceipt extends SupplierDisputeBinding {
  action: SupplierDisputeAction; operationVersion: number; status: keyof typeof paymentOperationLabels; resolutionId: string | null; auditEventId: string
}
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && !/[\u0000-\u001f\u007f]/.test(value)
const time = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const known = (labels: object, value: string) => Object.prototype.hasOwnProperty.call(labels, value)
const terminal = (value: string): value is SupplierDisputeOutcome => ['SUCCEEDED', 'FAILED', 'REVERSED'].includes(value)
const bindings = ['paymentId', 'requestId', 'applicationId', 'roundNo'] as const

function validateFact(value: SupplierBankFact) {
  if (!value || !known(supplierBankOutcomes, value.outcome) || !Number.isSafeInteger(value.revision)
      || (value.outcome === 'NOT_FOUND' ? value.revision !== 0 || value.paymentReference !== null : value.revision < 1 || !text(value.paymentReference))
      || !time(value.observedAt) || !time(value.validUntil) || Date.parse(value.validUntil) - Date.parse(value.observedAt) !== 300_000
      || (['SUCCEEDED', 'REVERSED'].includes(value.outcome) ? !text(value.receiptReference) || !time(value.completedAt) || Date.parse(value.completedAt) > Date.parse(value.observedAt) || value.failure !== null
        : value.receiptReference !== null || value.completedAt !== null || (value.outcome === 'FAILED' ? !value.failure || !known(paymentFailureLabels, value.failure) : value.failure !== null))) throw new Error('原银行回执不完整，请重新查询。')
}

/** 原付款、原轮次与双方回执一致后才显示候选；历史裁决不等于当前争议已经解除。 */
export function validateSupplierDispute(value: SupplierDisputeView, expected: SupplierDisputeBinding): SupplierDisputeView {
  if (!value || !bindings.every(key => value[key] === expected[key]) || ![value.paymentId, value.requestId, value.applicationId].every(text) || !positive(value.roundNo)
      || typeof value.canQuery !== 'boolean' || typeof value.canResolve !== 'boolean' || value.issue !== null && !known(supplierDisputeIssues, value.issue)) throw new Error('原付款裁决的申请或轮次不一致，请刷新。')
  if (value.status === null ? value.operationVersion !== null || value.observed !== null || value.candidate !== null || value.latest !== null || value.issue !== null || value.canQuery || value.canResolve
      : !known(paymentOperationLabels, value.status) || !positive(value.operationVersion)) throw new Error('原付款状态或版本不完整，请刷新。')
  if (value.observed !== null) validateFact(value.observed)
  if (value.candidate !== null) validateFact(value.candidate)
  if (value.status === 'RECONCILING' && value.candidate === null
      || value.canResolve && (value.status !== 'RECONCILING' || !value.candidate || !terminal(value.candidate.outcome) || value.issue !== null)
      || value.canQuery && (value.status === null || ['QUEUED', 'CHECKING', 'SENDING', 'QUERYING'].includes(value.status))) throw new Error('裁决能力与原付款状态不一致，请刷新。')
  if (value.latest !== null && (!value.latest || !text(value.latest.id) || !positive(value.latest.operationVersion) || value.operationVersion === null || value.latest.operationVersion > value.operationVersion
      || !terminal(value.latest.outcome) || !text(value.latest.resolvedBy) || !time(value.latest.resolvedAt) || !text(value.latest.evidenceReference))) throw new Error('原付款历史裁决不完整，请刷新。')
  return value
}

export function supplierDisputeAllowed(value: SupplierDisputeView | null, action: SupplierDisputeAction, now = Date.now()) {
  return !!value && (action === 'QUERY' ? value.canQuery : value.canResolve && !!value.candidate && Date.parse(value.candidate.validUntil) > now)
}

/** 客户端只提交展示版本、实际候选终态和人工说明，不携带任何金额或银行回执。 */
export function supplierDisputeInput(value: SupplierDisputeView, action: SupplierDisputeAction, comment: string, evidenceReference = '', now = Date.now()): SupplierDisputeQueryInput | SupplierDisputeResolveInput {
  validateSupplierDispute(value, value)
  if (!supplierDisputeAllowed(value, action, now) || value.operationVersion === null) throw new Error('当前状态或核对期限不允许办理，请刷新。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的操作说明。')
  const common = { operationVersion: value.operationVersion, comment: comment.trim() }
  if (action === 'QUERY') return common
  const reference = evidenceReference.trim()
  if (!text(reference) || reference.length > 128) throw new Error('请填写 128 字以内的外部对账凭据编号。')
  return { ...common, outcome: value.candidate!.outcome as SupplierDisputeOutcome, evidenceReference: reference }
}

/** 幂等回执只代表决定已保存，原资金和核销状态须重新读取。 */
export function validateSupplierDisputeReceipt(receipt: SupplierDisputeReceipt, value: SupplierDisputeView, action: SupplierDisputeAction) {
  if (!receipt || !bindings.every(key => receipt[key] === value[key]) || receipt.action !== action || value.operationVersion === null
      || receipt.operationVersion !== value.operationVersion + 1 || !text(receipt.auditEventId)
      || (action === 'QUERY' ? receipt.status !== 'UNKNOWN' || receipt.resolutionId !== null : receipt.status !== value.candidate?.outcome || !text(receipt.resolutionId))) throw new Error('裁决回执未能对应原付款，请恢复原请求后核对。')
}

export function supplierDisputeError(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string } | null)?.code
  const labels: Record<string, string> = { FORBIDDEN: '当前角色、法人任职或原轮次字段权限不允许办理。', NOT_FOUND: '当前范围内不能读取这笔原付款。', CONCURRENCY_CONFLICT: '展示的原付款版本已变化，请刷新核对。', SUPPLIER_PAYMENT_DISPUTE_UNRESOLVABLE: '当前原银行证据不能用于裁决，请查询原交易。', INVALID_SUPPLIER_PAYMENT_DISPUTE_RESOLUTION: '本次裁决未保持原付款依据或独立财务身份。', REQUEST_TIMEOUT: '操作结果尚未确认，请恢复原请求后刷新。' }
  return code && labels[code] || '原付款裁决未完成，请核对原请求与当前状态。'
}
