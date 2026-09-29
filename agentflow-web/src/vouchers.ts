/** 凭证摘要仅包含授权轮次的状态，不包含会计命令或账户。 */
export type VoucherKind = 'EMPLOYEE_ADVANCE' | 'EXPENSE_ACCRUAL' | 'PAYMENT'
export interface VoucherBinding { applicationId: string; businessId: string; businessType: 'EXPENSE' | 'ADVANCE_REQUEST'; roundNo: number; applicationVersion: number; businessVersion: number; kind?: VoucherKind }
export type VoucherAction = 'PREPARE' | 'QUERY' | 'RESEND_ORIGINAL'
export interface VoucherView extends VoucherBinding {
  kind: VoucherKind
  preparation: null | { id: string; status: keyof typeof preparationLabels; attempt: number; createdAt: string; completedAt: string | null; issue: string | null }
  operation: null | { id: string; version: number; kind: VoucherKind; status: keyof typeof operationLabels; attempts: number; accountingDate: string; updatedAt: string; sendExpiresAt: string; observedStatus: 'PENDING' | 'POSTED' | 'FAILED' | 'REVERSED' | 'NOT_FOUND' | null; voucherReference: string | null; postedAt: string | null; disputed: boolean; issue: string | null }
  actions: { prepare: boolean; query: boolean; resendOriginal: boolean }
  dispute?: null | { candidate: null | { outcome: 'PENDING' | 'POSTED' | 'FAILED' | 'REVERSED' | 'NOT_FOUND'; revision: number; observedAt: string; validUntil: string; postingReference: string | null; voucherReference: string | null; postedAt: string | null; failure: string | null }; issue: string | null; canResolve: boolean; latest: null | { id: string; operationVersion: number; outcome: VoucherDisputeInput['outcome']; resolvedBy: string; resolvedAt: string; evidenceReference: string } }
}
export interface VoucherDisputeInput { roundNo: number; applicationVersion: number; businessVersion: number; operationVersion: number; outcome: 'POSTED' | 'FAILED' | 'REVERSED'; evidenceReference: string; comment: string }
export interface VoucherDisputeReceipt { applicationId: string; operationId: string; roundNo: number; kind: VoucherKind; resolutionId: string; operationVersion: number; outcome: VoucherDisputeInput['outcome']; auditEventId: string }
export interface VoucherActionInput { action: VoucherAction; roundNo: number; applicationVersion: number; businessVersion: number; operationId?: string; operationVersion?: number; comment: string }
export interface VoucherReceipt { applicationId: string; businessId: string; roundNo: number; action: VoucherAction; preparationId: string | null; operationId: string | null; operationVersion: number | null; auditEventId: string; kind: VoucherKind }

export const preparationLabels = { QUEUED: '等待准备凭证', RUNNING: '正在核对会计依据', READY: '凭证已准备', BLOCKED: '准备条件未满足', UNAVAILABLE: '准备服务暂不可用', VOIDED: '本次准备已停止', NOT_REQUIRED: '本轮无需金额凭证' }
export const operationLabels = { QUEUED: '等待发送 ERP', POSTING: '正在请求过账', QUERYING: '正在核对 ERP 结果', UNKNOWN: '过账结果待确认', POSTED: '已过账', FAILED: '过账未通过', NOT_FOUND: 'ERP 确认原操作不存在', EXPIRED: '原发送期限已过', VOIDED: '原凭证命令已停止', RECONCILING: '凭证结果存在冲突', REVERSED: '凭证已冲销' }
export const voucherOutcomeLabels = { PENDING: 'ERP 处理中', POSTED: '已过账', FAILED: '过账未通过', REVERSED: '凭证已冲销', NOT_FOUND: 'ERP 确认原操作不存在' }
export const voucherActionLabels: Record<VoucherAction, string> = { PREPARE: '重新准备凭证', QUERY: '查询 ERP 结果', RESEND_ORIGINAL: '按原编号重发' }
const actionKeys = { PREPARE: 'prepare', QUERY: 'query', RESEND_ORIGINAL: 'resendOriginal' } as const
const positive = (value: number) => Number.isSafeInteger(value) && value > 0

/** 跨申请、轮次或版本的响应不能用于展示和后续写入。 */
export function validateVoucherView(view: VoucherView, expected: VoucherBinding): VoucherView {
  if (!view || !['applicationId', 'businessId', 'businessType', 'roundNo', 'applicationVersion', 'businessVersion'].every(key => view[key as keyof VoucherBinding] === expected[key as keyof VoucherBinding])
      || view.kind !== (expected.kind ?? (expected.businessType === 'EXPENSE' ? 'EXPENSE_ACCRUAL' : 'EMPLOYEE_ADVANCE'))
      || !['PAYMENT', view.businessType === 'EXPENSE' ? 'EXPENSE_ACCRUAL' : 'EMPLOYEE_ADVANCE'].includes(view.kind)
      || ![view.roundNo, view.applicationVersion, view.businessVersion].every(positive)
      || !view.actions || Object.values(actionKeys).some(key => typeof view.actions[key] !== 'boolean')) throw new Error('凭证轮次或版本已变化，请刷新业务明细。')
  const preparation = view.preparation, operation = view.operation
  if (preparation && (!preparation.id || !Object.prototype.hasOwnProperty.call(preparationLabels, preparation.status) || !positive(preparation.attempt))
      || operation && (!operation.id || !positive(operation.version) || !Object.prototype.hasOwnProperty.call(operationLabels, operation.status)
        || operation.kind !== view.kind
        || !Number.isFinite(Date.parse(operation.sendExpiresAt)) || typeof operation.disputed !== 'boolean'
        || operation.status === 'POSTED' && (operation.disputed || operation.observedStatus !== 'POSTED' || !operation.voucherReference || !operation.postedAt))
      || preparation?.status === 'READY' && preparation.id !== operation?.id) throw new Error('凭证状态未通过校验，请刷新核对。')
  const dispute = view.dispute, candidate = dispute?.candidate
  if (dispute && (!operation || typeof dispute.canResolve !== 'boolean'
      || candidate && (!Object.prototype.hasOwnProperty.call(voucherOutcomeLabels, candidate.outcome) || !Number.isSafeInteger(candidate.revision) || candidate.revision < 0
        || !Number.isFinite(Date.parse(candidate.observedAt)) || !Number.isFinite(Date.parse(candidate.validUntil)))
      || dispute.canResolve && (!candidate || dispute.issue !== null || operation.status !== 'RECONCILING' || !operation.disputed)
      || dispute.latest && (!dispute.latest.id || !positive(dispute.latest.operationVersion) || dispute.latest.operationVersion > operation.version
        || !['POSTED', 'FAILED', 'REVERSED'].includes(dispute.latest.outcome)))) throw new Error('凭证裁决状态未通过核对，请刷新。')
  return view
}

/** 裁决只采用页面已展示的近期原查询终态，不接收可编辑的 ERP 事实。 */
export function voucherDisputeInput(view: VoucherView, evidenceReference: string, comment: string, now = Date.now()): VoucherDisputeInput {
  validateVoucherView(view, view)
  const dispute = view.dispute, candidate = dispute?.candidate
  if (!view.operation || view.operation.status !== 'RECONCILING' || !view.operation.disputed || !dispute?.canResolve || dispute.issue || !candidate
      || !['POSTED', 'FAILED', 'REVERSED'].includes(candidate.outcome) || !positive(candidate.revision) || !candidate.postingReference
      || !Number.isFinite(Date.parse(candidate.observedAt)) || !Number.isFinite(Date.parse(candidate.validUntil))
      || Date.parse(candidate.observedAt) > now || Date.parse(candidate.validUntil) <= now
      || Date.parse(candidate.validUntil) - Date.parse(candidate.observedAt) !== 300_000
      || candidate.outcome !== 'FAILED' && (!candidate.voucherReference || !candidate.postedAt)) throw new Error('凭证裁决依据已变化或过期，请先重新查询 ERP 结果。')
  if (!evidenceReference.trim() || evidenceReference.length > 128 || /[\u0000-\u001f\u007f-\u009f]/u.test(evidenceReference)) throw new Error('请填写 128 字以内、不含控制字符的核对凭据编号。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的裁决说明。')
  return { roundNo: view.roundNo, applicationVersion: view.applicationVersion, businessVersion: view.businessVersion, operationVersion: view.operation.version,
    outcome: candidate.outcome as VoucherDisputeInput['outcome'], evidenceReference: evidenceReference.trim(), comment: comment.trim() }
}

/** 幂等结果必须精确匹配当前凭证、终态及连续修订。 */
export function validateVoucherDisputeReceipt(receipt: VoucherDisputeReceipt, view: VoucherView, input: VoucherDisputeInput) {
  if (!receipt || receipt.applicationId !== view.applicationId || receipt.operationId !== view.operation?.id || receipt.roundNo !== input.roundNo
      || receipt.kind !== view.kind || receipt.outcome !== input.outcome || receipt.operationVersion !== input.operationVersion + 1 || !receipt.resolutionId || !receipt.auditEventId) {
    throw new Error('凭证裁决回执未通过核对，请刷新状态后确认。')
  }
}
const disputeIssues: Record<string, string> = { NOT_DISPUTED: '原凭证正在查询或已经发生变化', NON_TERMINAL: 'ERP 尚未提供可采用的终态', STALE_OBSERVATION: '查询结果早于已知会计版本', EVIDENCE_EXPIRED: '核对依据已过期，请重新查询 ERP', DIFFERENT_POSTING: '查询结果与原凭证身份不一致', POSTING_ALREADY_OBSERVED: '历史存在过账记录，不能采用失败结论删除该事实', REVERSAL_IN_PROGRESS: '已授权独立冲销，原凭证继续停用，请核对原冲销命令' }
export function voucherDisputeIssue(code: string | null) { return code ? disputeIssues[code] ?? '原凭证裁决条件尚未满足' : '' }

/** 确认时再核对发送期限，永远只传原编号和已展示版本。 */
export function voucherActionInput(view: VoucherView, action: VoucherAction, comment: string, now = Date.now()): VoucherActionInput {
  validateVoucherView(view, view)
  if (!view.actions[actionKeys[action]]) throw new Error('当前凭证不允许此操作，请刷新状态。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的操作说明。')
  const operation = view.operation
  if (action !== 'PREPARE' && (!operation || ['QUEUED', 'POSTING', 'QUERYING'].includes(operation.status))) throw new Error('原凭证仍在处理，请刷新状态。')
  if (action === 'RESEND_ORIGINAL' && (operation!.status !== 'NOT_FOUND' || operation!.disputed || Date.parse(operation!.sendExpiresAt) <= now)) throw new Error('原凭证当前不能重发，请刷新核对结果与期限。')
  if (action === 'PREPARE' && (operation || view.preparation && ['QUEUED', 'RUNNING', 'READY', 'NOT_REQUIRED'].includes(view.preparation.status))) throw new Error('本轮已有凭证处理记录，请刷新核对。')
  return { action, roundNo: view.roundNo, applicationVersion: view.applicationVersion, businessVersion: view.businessVersion,
    ...(action === 'PREPARE' ? {} : { operationId: operation!.id, operationVersion: operation!.version }), comment: comment.trim() }
}

/** 幂等受理回执也要匹配本次意图，不能把别轮或别动作的结果显示为已受理。 */
export function validateVoucherReceipt(receipt: VoucherReceipt, view: VoucherView, input: VoucherActionInput) {
  if (!receipt || receipt.applicationId !== view.applicationId || receipt.businessId !== view.businessId || receipt.roundNo !== input.roundNo
      || receipt.kind !== view.kind
      || receipt.action !== input.action || !receipt.auditEventId
      || (input.action === 'PREPARE' ? !receipt.preparationId || receipt.operationId !== null || receipt.operationVersion !== null
        : receipt.preparationId !== null || receipt.operationId !== input.operationId || !positive(receipt.operationVersion!) || receipt.operationVersion! <= input.operationVersion!)) {
    throw new Error('操作回执未通过核对，请刷新凭证状态后再处理。')
  }
}

const issues: Record<string, string> = {
  NOT_CONFIGURED: '尚未配置会计服务', TARGET_CHANGED: '会计服务配置已变化，请核对原操作', TIMEOUT: '会计服务响应超时', CONNECTION: '暂时无法连接会计服务',
  AUTHENTICATION: '会计服务连接凭据不可用', REMOTE_FAILURE: '会计服务暂时不可用', INVALID_RESPONSE: '会计结果未通过校验', RESPONSE_TOO_LARGE: '会计结果未通过校验',
  LEASE_EXPIRED: '本次处理超时，需要重新核对', VOUCHER_BUDGET_NOT_FROZEN: '本轮预算尚未确认，暂不能准备凭证', VOUCHER_SOURCE_CHANGED: '原批准内容已变化，停止发送',
  ACCOUNTING_PERIOD_CLOSED: '原会计日期的期间已关闭', ACCOUNT_MAPPING_CHANGED: '原科目映射已变化', APPROVAL_CHANGED: '原批准依据已变化', COST_OBJECT_UNAVAILABLE: '成本归属当前不可用', VOUCHER_REJECTED: 'ERP 未接受本次凭证',
  ZERO_AMOUNT: '本轮核定金额为零，结算状态仍须另行确认',
  PAYMENT_VOUCHER_SOURCE_CHANGED: '原付款会计依据需要核对', PAYMENT_VOUCHER_PAYMENT_UNCONFIRMED: '原付款尚未确认成功或存在退票、争议，请先核对付款状态',
  PAYMENT_VOUCHER_ACCRUAL_UNCONFIRMED: '原挂账凭证尚未确认或已冲销，请先核对挂账结果'
}
export function voucherIssue(code: string | null) { return code ? issues[code] ?? '凭证条件或外部结果需要核对' : '' }
export function voucherError(cause: unknown) {
  const failure = cause as { status?: number; code?: string; message?: string }
  if (failure.status === 403 || failure.status === 404) return '当前身份无权查看或操作这轮凭证。'
  if (failure.code === 'CONCURRENCY_CONFLICT') return '审批、财务或凭证版本已变化，请刷新业务明细后重新核对。'
  if (failure.code === 'VOUCHER_DISPUTE_UNRESOLVABLE') return '凭证裁决依据已过期或不满足条件，请重新查询 ERP 后核对。'
  if (failure.code && issues[failure.code]) return issues[failure.code]!
  if (failure.code === 'VOUCHER_OPERATION_EXISTS') return '本轮已有原凭证，请核对原操作状态。'
  if (failure.code === 'PENDING_REQUEST_CHANGED' || failure.status === 0) return '操作结果尚未确认，请在未确认操作中恢复原请求后刷新状态。'
  return cause instanceof Error ? cause.message : '凭证操作未完成，请刷新状态后核对。'
}
