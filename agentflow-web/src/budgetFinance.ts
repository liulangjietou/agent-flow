import { amountMinor, type Money } from './expenses.js'
import type { BudgetAdjustmentContent, BudgetAdjustmentPosition } from './budgetAdjustment'

export interface BudgetFinanceBinding { requestId: string; applicationId: string; roundNo: number; applicationVersion: number; requestVersion: number }
export type BudgetFinanceAction = 'REVIEW' | 'AUTHORIZE' | 'QUERY' | 'RETRY' | 'RETIRE'
export interface BudgetFinanceReview { id: string; version: number; status: keyof typeof budgetReviewLabels; requestedAt: string; checkedAt: string | null; validUntil: string | null; positions: BudgetAdjustmentPosition[]; issue: string | null }
export interface BudgetFinanceResult { status: 'APPLIED' | 'REJECTED' | 'PENDING' | 'NOT_FOUND'; revision: number; observedAt: string; reference: string | null; appliedAt: string | null; rejection: string | null }
export interface BudgetFinanceOperation {
  id: string; version: number; status: keyof typeof budgetOperationLabels; authorizedBy: string; reason: string; authorizedAt: string; expiresAt: string; updatedAt: string
  positions: BudgetAdjustmentPosition[]; observation: BudgetFinanceResult | null; conflictingObservation: BudgetFinanceResult | null; failure: string | null
  retirement: { operationId: string; operationVersion: number; basis: 'NEVER_SENT' | 'REJECTED'; retiredBy: string; retiredAt: string } | null
}
export interface BudgetFinanceView extends BudgetFinanceBinding { destinationReady: boolean; review: BudgetFinanceReview | null; operation: BudgetFinanceOperation | null; actions: { review: boolean; authorize: boolean; query: boolean; retry: boolean; retire: boolean } }
export interface BudgetFinancePage { items: BudgetFinanceOperation[]; nextBefore: string | null }
export interface BudgetFinanceReviewInput { roundNo: number; applicationVersion: number; requestVersion: number; comment: string }
export interface BudgetFinanceAuthorizeInput extends BudgetFinanceReviewInput { reviewId: string; reviewVersion: number }
export interface BudgetFinanceActionInput { action: 'QUERY' | 'RETRY' | 'RETIRE'; operationVersion: number; comment: string }
export interface BudgetFinanceReceipt { requestId: string; applicationId: string; roundNo: number; action: BudgetFinanceAction; reviewId: string | null; reviewVersion: number | null; operationId: string | null; operationVersion: number | null; auditEventId: string }
export const budgetActionLabels = { REVIEW: '读取最新预算台账', AUTHORIZE: '确认预算调整授权', QUERY: '查询原调整结果', RETRY: '重发原调整指令', RETIRE: '安全结束原指令' }
export const budgetActionKeys = { REVIEW: 'review', AUTHORIZE: 'authorize', QUERY: 'query', RETRY: 'retry', RETIRE: 'retire' } as const
export const budgetReviewLabels = { QUEUED: '等待读取', RUNNING: '正在读取', READY: '等待财务确认', CONSUMED: '已用于授权', BLOCKED: '台账条件不满足', UNAVAILABLE: '本次读取不可用', VOIDED: '本次读取已停止' }
export const budgetOperationLabels = { QUEUED: '等待执行', EXECUTING: '正在执行原指令', QUERYING: '正在查询原指令', UNKNOWN: '结果待确认', APPLIED: '预算调整已生效', REJECTED: '原系统明确拒绝', NOT_FOUND: '原指令暂未查到', EXPIRED: '原授权窗口已到期', VOIDED: '原指令发送已停止', RECONCILING: '原系统结果存在矛盾' }
export const budgetResultLabels = { APPLIED: '已生效', REJECTED: '明确拒绝', PENDING: '仍在处理', NOT_FOUND: '暂未查到' }
export const budgetIssueLabels: Record<string, string> = {
  NOT_CONFIGURED: '原财务系统尚未就绪。', TARGET_CHANGED: '原财务目标已变化，请先核对原系统。', TIMEOUT: '财务系统响应超时，请核对本次读取或原指令结果。', CONNECTION: '财务系统暂时无法连接。', AUTHENTICATION: '财务系统认证不可用。', REMOTE_FAILURE: '财务系统暂未完成请求。', INVALID_RESPONSE: '财务响应未通过完整核对。', RESPONSE_TOO_LARGE: '财务响应超出读取范围。', INTERNAL_ERROR: '本次处理未完成，请刷新后核对。', LEASE_EXPIRED: '处理超时，正在查询原指令。', SOURCE_CHANGED: '原批准或财务任职已变化，请核对后办理。', LEDGER_CHANGED: '预算期间、余额或已占用额度不满足本次调整，请核对原台账。', LEDGER_REJECTED: '原系统拒绝提供本次调整所需台账。', AUTHORIZATION_EXPIRED: '原授权有效期已结束，不能继续发送。', INCONSISTENT_OBSERVATION: '原系统返回互相矛盾的结果，请保持原号核对。', RECHECK_REQUESTED: '已登记原指令查询。', BUDGET_PERIOD_CLOSED: '预算期间已关闭。', BUDGET_INSUFFICIENT: '预算余额不足。', LEDGER_VERSION_CONFLICT: '原预算台账版本已变化。', BUDGET_POLICY_UNAVAILABLE: '本次预算政策不可用。', BUDGET_POSITION_UNAVAILABLE: '原预算项不可用。', LEGAL_ENTITY_UNAVAILABLE: '原法人不可用。', EMPLOYEE_UNAVAILABLE: '原申请人不可用。'
}
const positive = (value: number) => Number.isSafeInteger(value) && value > 0
const time = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const known = (values: object, value: string) => Object.prototype.hasOwnProperty.call(values, value)
const money = (value: Money | null): value is Money => !!value && typeof value.value === 'string' && /^(0|[1-9][0-9]{0,14})\.[0-9]{2}$/.test(value.value) && /^[A-Z]{3}$/.test(value.currency)
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const bindingKeys = ['requestId', 'applicationId', 'roundNo', 'applicationVersion', 'requestVersion'] as const

/** 两端额度按原申请方向逐分核对，不用浮点加减，也不将预览金额当作已生效事实。 */
function validatePositions(positions: BudgetAdjustmentPosition[], content: BudgetAdjustmentContent) {
  const expected = [content.sourceBudgetReference, content.targetBudgetReference].filter((value): value is string => !!value)
  if (!money(content.amount) || amountMinor(content.amount.value) <= 0n || !Array.isArray(positions) || positions.length !== expected.length
      || !positions.length || positions.length > 2 || new Set(positions.map(value => value.budgetReference)).size !== positions.length) throw new Error('原预算明细不完整，请重新读取。')
  for (const position of positions) {
    if (!expected.includes(position.budgetReference) || !text(position.name) || !text(position.version) || !text(position.periodReference) || position.periodStatus !== 'OPEN'
        || ![position.periodStart, position.periodEnd].every(value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)))
        || content.accountingDate < position.periodStart || content.accountingDate > position.periodEnd
        || ![position.beforeLimit, position.committed, position.consumed, position.available, position.proposedLimit].every(value => money(value) && value.currency === content.amount.currency)) throw new Error('预算期间或原额度未通过核对，请刷新。')
    const occupied = amountMinor(position.committed.value) + amountMinor(position.consumed.value)
    const before = amountMinor(position.beforeLimit.value), proposed = amountMinor(position.proposedLimit.value)
    const change = amountMinor(content.amount.value) * (position.budgetReference === content.sourceBudgetReference ? -1n : 1n)
    // 原台账已经超预算时可用额为零，仍允许按批准追加；只有调减须守住已占用额度。
    const available = before > occupied ? before - occupied : 0n
    if (amountMinor(position.available.value) !== available || proposed !== before + change || change < 0n && proposed < occupied) throw new Error('两端预算额度与原批准不一致，请重新核对。')
  }
  if (positions.length === 2 && ['periodReference', 'periodStart', 'periodEnd'].some(key => positions[0][key as keyof BudgetAdjustmentPosition] !== positions[1][key as keyof BudgetAdjustmentPosition])) throw new Error('调拨两端预算期间不一致，请重新核对。')
}
function validateResult(value: BudgetFinanceResult, operation: BudgetFinanceOperation) {
  if (!value || !known(budgetResultLabels, value.status) || !Number.isSafeInteger(value.revision) || value.revision < (value.status === 'NOT_FOUND' ? 0 : 1)
      || value.status === 'NOT_FOUND' && value.revision !== 0 || !time(value.observedAt) || Date.parse(value.observedAt) < Date.parse(operation.authorizedAt)
      || value.reference !== null && !text(value.reference) || value.rejection !== null && !known(budgetIssueLabels, value.rejection)) throw new Error('原预算执行结果不完整，请查询原号。')
  if (value.status === 'APPLIED' ? !text(value.reference) || !time(value.appliedAt) || Date.parse(value.appliedAt) < Date.parse(operation.authorizedAt) || Date.parse(value.appliedAt) > Date.parse(value.observedAt) || value.rejection !== null
      : value.appliedAt !== null || (value.status === 'REJECTED' ? !value.rejection : value.rejection !== null)) throw new Error('预算生效依据不完整，请查询原号。')
}
function validateOperation(value: BudgetFinanceOperation, content: BudgetAdjustmentContent) {
  if (!value || !text(value.id) || !positive(value.version) || !known(budgetOperationLabels, value.status) || !text(value.authorizedBy) || !text(value.reason)
      || !time(value.authorizedAt) || !time(value.expiresAt) || !time(value.updatedAt) || Date.parse(value.expiresAt) <= Date.parse(value.authorizedAt)
      || Date.parse(value.updatedAt) < Date.parse(value.authorizedAt) || value.failure !== null && !known(budgetIssueLabels, value.failure)) throw new Error('原预算授权或执行状态不完整，请刷新。')
  validatePositions(value.positions, content)
  if (value.observation !== null) validateResult(value.observation, value)
  if (value.conflictingObservation !== null) validateResult(value.conflictingObservation, value)
  if (['APPLIED', 'REJECTED', 'NOT_FOUND'].includes(value.status) && (value.observation?.status !== value.status || value.conflictingObservation !== null)
      || value.status === 'RECONCILING' && (!value.observation || !value.conflictingObservation)) throw new Error('预算执行结论与原系统事实不一致，请刷新。')
  if (value.retirement !== null) {
    const retired = value.retirement
    if (!retired || retired.operationId !== value.id || retired.operationVersion !== value.version || !text(retired.retiredBy) || !time(retired.retiredAt)
        || Date.parse(retired.retiredAt) < Date.parse(value.updatedAt) || value.conflictingObservation !== null
        || (retired.basis === 'NEVER_SENT' ? !['VOIDED', 'EXPIRED'].includes(value.status) || value.observation !== null : retired.basis !== 'REJECTED' || value.status !== 'REJECTED')) throw new Error('原指令安全结束依据不完整，请刷新。')
  }
}

/** 申请、轮次、双版本与原指令一致后才允许显示金额和办理按钮。 */
export function validateBudgetFinance(value: BudgetFinanceView, expected: BudgetFinanceBinding, content: BudgetAdjustmentContent, operationId?: string): BudgetFinanceView {
  if (!value || !bindingKeys.every(key => value[key] === expected[key]) || !text(value.requestId) || !text(value.applicationId)
      || ![value.roundNo, value.applicationVersion, value.requestVersion].every(positive) || typeof value.destinationReady !== 'boolean' || !value.actions
      || Object.values(budgetActionKeys).some(key => typeof value.actions[key] !== 'boolean')) throw new Error('预算批准轮次或版本已变化，请刷新申请。')
  const review = value.review, operation = value.operation
  if (review !== null) {
    if (!review || !text(review.id) || !positive(review.version) || !known(budgetReviewLabels, review.status) || !time(review.requestedAt)
        || review.issue !== null && !known(budgetIssueLabels, review.issue)) throw new Error('预算台账复核状态不完整，请刷新。')
    if (['READY', 'CONSUMED'].includes(review.status)) {
      if (!time(review.checkedAt) || !time(review.validUntil) || Date.parse(review.checkedAt) < Date.parse(review.requestedAt)
          || Date.parse(review.validUntil) <= Date.parse(review.checkedAt) || review.issue !== null) throw new Error('预算台账复核依据不完整，请重新读取。')
      validatePositions(review.positions, content)
    } else if (review.checkedAt !== null || review.validUntil !== null || !Array.isArray(review.positions) || review.positions.length) throw new Error('未完成的预算读取不能携带可授权额度。')
  }
  if (operation !== null) validateOperation(operation, content)
  if (operationId && operation?.id !== operationId) throw new Error('返回的执行记录与所选原指令不一致。')
  if (value.actions.authorize && (!value.destinationReady || review?.status !== 'READY') || value.actions.review && !value.destinationReady
      || (value.actions.review || value.actions.authorize) && operation && operation.retirement === null
      || (value.actions.query || value.actions.retry || value.actions.retire) && (!operation || operation.retirement !== null)
      || value.actions.query && ['QUEUED', 'EXECUTING', 'QUERYING'].includes(operation?.status ?? '')
      || value.actions.retry && (!value.destinationReady || operation?.status !== 'NOT_FOUND' || operation.conflictingObservation !== null)
      || value.actions.retire && (!['QUEUED', 'VOIDED', 'EXPIRED', 'REJECTED'].includes(operation?.status ?? '') || operation?.conflictingObservation !== null)) throw new Error('办理权限与原预算状态不一致，请刷新。')
  return value
}
/** 历史分页仍校验原批准金额，未知状态不能在页面被当作成功。 */
export function validateBudgetFinancePage(page: BudgetFinancePage, content: BudgetAdjustmentContent): BudgetFinancePage {
  if (!page || !Array.isArray(page.items) || page.items.length > 25 || page.nextBefore !== null && !text(page.nextBefore)
      || new Set(page.items.map(value => value.id)).size !== page.items.length || page.nextBefore !== null && page.items[page.items.length - 1]?.id !== page.nextBefore) throw new Error('预算执行历史分页不完整，请重新读取。')
  page.items.forEach(value => validateOperation(value, content)); return page
}
export function budgetActionAllowed(view: BudgetFinanceView | null, action: BudgetFinanceAction, now = Date.now()): boolean {
  return !!view && !!view.actions[budgetActionKeys[action]]
    && (action !== 'AUTHORIZE' || view.review?.status === 'READY' && Date.parse(view.review.validUntil ?? '') > now)
    && (action !== 'RETRY' || view.operation?.status === 'NOT_FOUND' && Date.parse(view.operation.expiresAt) > now)
}
/** 确认时重新核对期限，提交只包含持久证据身份与显示版本。 */
export function budgetFinanceInput(view: BudgetFinanceView, content: BudgetAdjustmentContent, action: BudgetFinanceAction, comment: string, now = Date.now()): BudgetFinanceReviewInput | BudgetFinanceAuthorizeInput | BudgetFinanceActionInput {
  validateBudgetFinance(view, view, content)
  if (!budgetActionAllowed(view, action, now)) throw new Error('当前状态或证据期限不允许此操作，请刷新核对。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的操作说明。')
  if (action === 'REVIEW' || action === 'AUTHORIZE') {
    const input = { roundNo: view.roundNo, applicationVersion: view.applicationVersion, requestVersion: view.requestVersion, comment: comment.trim() }
    return action === 'REVIEW' ? input : { ...input, reviewId: view.review!.id, reviewVersion: view.review!.version }
  }
  return { action, operationVersion: view.operation!.version, comment: comment.trim() }
}
/** 202 只确认人工决定保存成功，生效状态必须重新查询。 */
export function validateBudgetFinanceReceipt(receipt: BudgetFinanceReceipt, view: BudgetFinanceView, action: BudgetFinanceAction) {
  if (!receipt || receipt.requestId !== view.requestId || receipt.applicationId !== view.applicationId || receipt.roundNo !== view.roundNo || receipt.action !== action || !text(receipt.auditEventId)) throw new Error('办理回执未能对应原申请，请恢复原请求后核对。')
  if (action === 'REVIEW' ? !text(receipt.reviewId) || receipt.reviewVersion !== 1 || receipt.operationId !== null || receipt.operationVersion !== null
      : action === 'AUTHORIZE' ? receipt.reviewId !== view.review?.id || receipt.reviewVersion !== view.review!.version + 1 || !text(receipt.operationId) || receipt.operationVersion !== 1
      : receipt.reviewId !== null || receipt.reviewVersion !== null || receipt.operationId !== view.operation?.id
        || receipt.operationVersion !== view.operation!.version + (action !== 'RETIRE' || view.operation!.status === 'QUEUED' ? 1 : 0)) throw new Error('办理回执版本不完整，请恢复原请求后核对。')
}
export function budgetFinanceError(cause: unknown): string {
  if (cause instanceof Error) return cause.message
  const code = (cause as { code?: string } | null)?.code
  const labels: Record<string, string> = { FORBIDDEN: '当前角色、法人任职或字段权限不允许办理。', NOT_FOUND: '当前范围内无法读取原申请或指令。', CONCURRENCY_CONFLICT: '展示版本或最新复核已变化，请刷新后核对。', BUDGET_ADJUSTMENT_REVIEW_ACTIVE: '已有本人台账读取正在进行，请刷新结果。', BUDGET_ADJUSTMENT_REVIEW_UNAVAILABLE: '本次台账已失效或被消费，请重新读取。', BUDGET_ADJUSTMENT_ALREADY_AUTHORIZED: '本轮已有活动指令，请核对其结果。', BUDGET_ADJUSTMENT_RETIREMENT_UNSAFE: '无法证明原指令未生效，请保持原号查询。', BUDGET_ADJUSTMENT_OPERATION_CONFLICT: '原指令状态不允许此操作，请刷新核对。', BUDGET_ADJUSTMENT_DESTINATION_UNAVAILABLE: '原批准的财务目标已不可用，请联系管理员核对。', BUDGET_ADJUSTMENT_SOURCE_CHANGED: '原预算批准已变化，请刷新原申请。', BUDGET_ADJUSTMENT_AUTHORIZATION_EXPIRED: '原授权已到期，不能继续发送。', REQUEST_TIMEOUT: '操作结果尚未确认，请恢复原请求后刷新。', PAYMENT_ACTOR_UNAVAILABLE: '原法人任职已失效，当前不能办理。' }
  return code && labels[code] || '本次办理未完成，请核对原请求与当前状态。'
}
