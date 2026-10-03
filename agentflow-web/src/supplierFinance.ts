import { amountMinor, type Money } from './expenses.js'

export interface SupplierFinanceBinding { requestId: string; applicationId: string; roundNo: number; applicationVersion: number; requestVersion: number }
export type SupplierFinanceAction = 'REVIEW' | 'AUTHORIZE' | 'QUERY' | 'RETRY' | 'RETIRE'
export interface SupplierReview { id: string; version: number; status: keyof typeof reviewLabels; requestedAt: string; checkedAt: string | null; validUntil: string | null; settled: Money | null; outstanding: Money | null; maskedAccount: string | null; issue: string | null }
export interface SupplierAuthorization { id: string; authorizedBy: string; authorizedAt: string; expiresAt: string; maskedAccount: string; retiredAt: string | null; retirementBasis: 'NEVER_DISPATCHED' | 'CONFIRMED_REJECTED' | null }
export interface SupplierHold { version: number; status: keyof typeof holdLabels; updatedAt: string; observedAt: string | null; failure: string | null }
export interface SupplierFinanceView extends SupplierFinanceBinding { approvedAmount: Money | null; review: SupplierReview | null; authorization: SupplierAuthorization | null; hold: SupplierHold | null; actions: { review: boolean; authorize: boolean; query: boolean; retry: boolean; retire: boolean } }
export interface SupplierReviewInput { roundNo: number; applicationVersion: number; requestVersion: number; comment: string }
export interface SupplierAuthorizeInput extends SupplierReviewInput { reviewId: string; reviewVersion: number }
export interface SupplierHoldInput { action: 'QUERY' | 'RETRY' | 'RETIRE'; holdVersion: number; comment: string }
export interface SupplierFinanceReceipt { requestId: string; applicationId: string; roundNo: number; action: SupplierFinanceAction; reviewId: string | null; reviewVersion: number | null; authorizationId: string | null; holdVersion: number | null; auditEventId: string }
export const supplierActionLabels = { REVIEW: '复核当前应付', AUTHORIZE: '确认财务授权', QUERY: '查询原预留', RETRY: '重试原预留', RETIRE: '结束原授权' }
export const supplierActionKeys = { REVIEW: 'review', AUTHORIZE: 'authorize', QUERY: 'query', RETRY: 'retry', RETIRE: 'retire' } as const
export const reviewLabels = { QUEUED: '等待读取', RUNNING: '正在读取', READY: '等待财务确认', CONSUMED: '已用于授权', BLOCKED: '需要核对原应付', UNAVAILABLE: '本次读取不可用', VOIDED: '本次读取已停止' }
export const holdLabels = { QUEUED: '等待预留', RESERVING: '正在登记预留', UNKNOWN: '预留结果待确认', QUERYING: '正在查询原预留', HELD: '原应付已预留', REJECTED: '原预留明确被拒绝', NOT_FOUND: '原预留暂未查到', RECONCILING: '原预留存在争议', EXPIRED: '预留窗口已过期', VOIDED: '原预留发送已停止' }
export const supplierIssueLabels: Record<string, string> = {
  NOT_CONFIGURED: '财务系统尚未配置，请联系管理员。', TARGET_CHANGED: '原财务目标已变化，请先核对原系统。', TIMEOUT: '财务系统响应超时，请查询原结果。', CONNECTION: '财务系统暂时无法连接。', AUTHENTICATION: '财务系统认证不可用。', REMOTE_FAILURE: '财务系统暂未完成请求。', INVALID_RESPONSE: '财务响应未通过核对。', RESPONSE_TOO_LARGE: '财务响应超出读取范围。', INTERNAL_ERROR: '本次处理未完成，请刷新后核对。', LEASE_EXPIRED: '处理超时，正在恢复原请求。', SOURCE_CHANGED: '原批准或财务任职已变化，请重新核对。', PAYABLE_REJECTED: '原应付无法用于本次付款，请在财务系统核对。', PAYABLE_CHANGED: '余额、账户或原采购匹配依据已变化，请核对原应付。', SEND_WINDOW_EXPIRED: '原应付证据已到期，未继续发送预留。', FINANCE_RETIRED: '财务已明确结束原授权。', STALE_OBSERVATION: '返回了更早的预留事实，请核对原系统。', INCONSISTENT_OBSERVATION: '原预留事实存在矛盾，请保持原号核对。', RECHECK_REQUESTED: '已登记原预留查询。'
}
const positive = (value: number) => Number.isSafeInteger(value) && value > 0
const time = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const known = (values: object, value: string) => Object.prototype.hasOwnProperty.call(values, value)
const money = (value: Money | null): value is Money => !!value && typeof value.value === 'string' && /^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(value.value) && /^[A-Z]{3}$/.test(value.currency)
const mask = (value: string | null) => typeof value === 'string' && /(?:\*{2,}|•{2,}|[xX]{2,})/.test(value) && /^[0-9*•xX -]+$/.test(value) && !/[0-9]{5,}/.test(value) && value.replace(/[^0-9]/g, '').length <= 8
const bindingKeys = ['requestId', 'applicationId', 'roundNo', 'applicationVersion', 'requestVersion'] as const

/** 跨轮次、错误版本、完整账号和未知状态一律不生成办理按钮。 */
export function validateSupplierFinance(value: SupplierFinanceView, expected: SupplierFinanceBinding): SupplierFinanceView {
  if (!value || !bindingKeys.every(key => value[key] === expected[key]) || !value.requestId || !value.applicationId
      || ![value.roundNo, value.applicationVersion, value.requestVersion].every(positive) || !value.actions
      || Object.values(supplierActionKeys).some(key => typeof value.actions[key] !== 'boolean')
      || value.approvedAmount !== null && (!money(value.approvedAmount) || amountMinor(value.approvedAmount.value) <= 0n)) throw new Error('采购批准轮次或版本已变化，请刷新申请。')
  const review = value.review, authorization = value.authorization, hold = value.hold
  if (review !== null) {
    if (!review || !review.id || !positive(review.version) || !known(reviewLabels, review.status) || !time(review.requestedAt)
        || review.issue !== null && !known(supplierIssueLabels, review.issue)) throw new Error('应付复核状态不完整，请刷新。')
    const evidence = ['READY', 'CONSUMED'].includes(review.status)
    if (evidence ? !time(review.checkedAt) || !time(review.validUntil) || Date.parse(review.checkedAt) < Date.parse(review.requestedAt)
        || Date.parse(review.validUntil) <= Date.parse(review.checkedAt) || !money(review.settled) || !money(review.outstanding) || !mask(review.maskedAccount) || review.issue !== null
        || !value.approvedAmount || review.outstanding.currency !== value.approvedAmount.currency || review.settled.currency !== value.approvedAmount.currency || amountMinor(review.outstanding.value) < amountMinor(value.approvedAmount.value)
        : review.checkedAt !== null || review.validUntil !== null || review.settled !== null || review.outstanding !== null || review.maskedAccount !== null) throw new Error('应付复核依据不完整，请重新读取。')
  }
  if ((authorization === null) !== (hold === null)) throw new Error('授权与原预留绑定不完整，请刷新。')
  if (authorization !== null) {
    if (!authorization || !value.approvedAmount || !authorization.id || !authorization.authorizedBy || !mask(authorization.maskedAccount)
        || !time(authorization.authorizedAt) || !time(authorization.expiresAt) || Date.parse(authorization.expiresAt) <= Date.parse(authorization.authorizedAt)
        || !hold || !positive(hold.version) || !known(holdLabels, hold.status) || !time(hold.updatedAt)
        || hold.observedAt !== null && !time(hold.observedAt) || hold.failure !== null && !known(supplierIssueLabels, hold.failure)) throw new Error('原授权或预留状态未通过核对，请刷新。')
    if (authorization.retiredAt !== null ? !time(authorization.retiredAt) || Date.parse(authorization.retiredAt) < Date.parse(hold.updatedAt)
        || (authorization.retirementBasis === 'NEVER_DISPATCHED' ? !['VOIDED', 'EXPIRED'].includes(hold.status) : authorization.retirementBasis !== 'CONFIRMED_REJECTED' || hold.status !== 'REJECTED')
        : authorization.retirementBasis !== null) throw new Error('原授权结束依据不完整，请刷新。')
  }
  if (value.actions.authorize && (!review || review.status !== 'READY' || authorization && authorization.retiredAt === null)
      || (value.actions.query || value.actions.retry || value.actions.retire) && (!hold || !authorization || authorization.retiredAt !== null)
      || value.actions.retry && hold?.status !== 'NOT_FOUND'
      || value.actions.retire && !['QUEUED', 'VOIDED', 'EXPIRED', 'REJECTED'].includes(hold?.status ?? '')) throw new Error('办理能力与原预留状态不一致，请刷新。')
  return value
}

/** 确认时再检查证据到期，不能因为页面先前显示可授权就沿用旧余额。 */
export function supplierActionAllowed(view: SupplierFinanceView | null, action: SupplierFinanceAction, now = Date.now()): boolean {
  return !!view && !!view.actions[supplierActionKeys[action]] && (action !== 'AUTHORIZE' || view.review?.status === 'READY' && Date.parse(view.review.validUntil ?? '') > now)
}
export function supplierFinanceInput(view: SupplierFinanceView, action: SupplierFinanceAction, comment: string, now = Date.now()): SupplierReviewInput | SupplierAuthorizeInput | SupplierHoldInput {
  validateSupplierFinance(view, view)
  if (!supplierActionAllowed(view, action, now)) throw new Error('当前状态或证据期限不允许此操作，请刷新核对。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的操作说明。')
  if (action === 'REVIEW' || action === 'AUTHORIZE') {
    const input = { roundNo: view.roundNo, applicationVersion: view.applicationVersion, requestVersion: view.requestVersion, comment: comment.trim() }
    return action === 'REVIEW' ? input : { ...input, reviewId: view.review!.id, reviewVersion: view.review!.version }
  }
  return { action, holdVersion: view.hold!.version, comment: comment.trim() }
}

/** 最小回执必须对应原意图，只确认保存，不将 202 解释为 ERP 预留或银行到账。 */
export function validateSupplierFinanceReceipt(receipt: SupplierFinanceReceipt, view: SupplierFinanceView, action: SupplierFinanceAction) {
  if (!receipt || receipt.requestId !== view.requestId || receipt.applicationId !== view.applicationId || receipt.roundNo !== view.roundNo || receipt.action !== action || !receipt.auditEventId) throw new Error('办理回执未能对应原申请，请恢复原请求后核对。')
  if (action === 'REVIEW' ? !receipt.reviewId || receipt.reviewVersion !== 1 || receipt.authorizationId !== null || receipt.holdVersion !== null
      : action === 'AUTHORIZE' ? receipt.reviewId !== view.review?.id || receipt.reviewVersion !== view.review!.version + 1 || !receipt.authorizationId || receipt.holdVersion !== 1
      : receipt.reviewId !== null || receipt.reviewVersion !== null || receipt.authorizationId !== view.authorization?.id
        || receipt.holdVersion !== view.hold!.version + (action !== 'RETIRE' || view.hold!.status === 'QUEUED' ? 1 : 0)) throw new Error('办理回执版本不完整，请恢复原请求后核对。')
}
export function supplierFinanceError(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string } | null)?.code
  const labels: Record<string, string> = { FORBIDDEN: '当前岗位、法人任职或字段权限不允许办理。', NOT_FOUND: '当前范围内无法读取原申请或授权。', CONCURRENCY_CONFLICT: '展示版本已变化，请刷新申请和办理状态。', SUPPLIER_PAYABLE_REVIEW_PENDING: '已有本人复核正在读取，请刷新结果。', SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE: '本次复核已失效或已被消费，请重新核对。', SUPPLIER_PAYMENT_ALREADY_AUTHORIZED: '已有活动授权，请先核对原预留。', SUPPLIER_AUTHORIZATION_RETIREMENT_UNSAFE: '原预留尚未确认安全结束，请保持原号查询。', SUPPLIER_PAYABLE_HOLD_STATE_CONFLICT: '原预留状态已变化，请刷新核对。', REQUEST_TIMEOUT: '操作结果尚未确认，请恢复原请求后刷新。', PAYMENT_ACTOR_UNAVAILABLE: '原法人任职已失效，当前不能办理。', PROCUREMENT_PAYMENT_SOURCE_CHANGED: '实际采购批准或原占用已变化，请刷新原申请。' }
  return code && labels[code] || '本次办理未完成，请核对原请求与当前状态。'
}
