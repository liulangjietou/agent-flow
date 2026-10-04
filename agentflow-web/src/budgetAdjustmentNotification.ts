import type { BudgetAdjustmentNotificationTarget, InboxMessage } from './api'
import { budgetOperationLabels, budgetReviewLabels, budgetResultLabels } from './budgetFinance.js'

export const budgetAdjustmentNoticeLabels = {
  REVIEW_UNAVAILABLE: '本次预算台账复核暂不可用，尚未形成新的调整授权。',
  REVIEW_BLOCKED: '本次预算台账条件不满足，财务需要核对原依据；尚未执行预算调整。',
  REVIEW_SOURCE_CHANGED: '原来源或办理条件已变化，本次台账复核已停止。',
  UNKNOWN: '原预算调整指令结果暂不明确，需继续按原编号查询。',
  NOT_FOUND: '本次查询未找到原预算指令；查无不能证明预算从未调整。',
  REJECTED: '原系统已明确拒绝整条预算调整指令，请在原申请核对处理。',
  APPLIED: '原预算调整指令已收到完整生效回执，其他预算操作仍需分别核对。',
  RECONCILING: '原预算调整回执存在矛盾，原结果与冲突依据分别保留，需要继续核对。',
  EXPIRED: '原预算调整授权窗口已到期，原指令不能继续发送；不据此推断其他操作结果。',
  VOIDED: '原预算调整指令发送已停止，请核对原记录和当前办理资格。',
  RETIRED: '财务已依据原指令的无副作用证明安全结束本次操作，后续调整须另行授权。'
}
export const isBudgetAdjustmentNotification = (value: InboxMessage) => ['BUDGET_ADJUSTMENT_RESULT', 'BUDGET_ADJUSTMENT_ATTENTION'].includes(value.kind)
const resultFacts = ['APPLIED', 'REJECTED', 'RETIRED']
const issues = ['NOT_CONFIGURED', 'TARGET_CHANGED', 'TIMEOUT', 'CONNECTION', 'AUTHENTICATION', 'REMOTE_FAILURE', 'INVALID_RESPONSE', 'RESPONSE_TOO_LARGE', 'INTERNAL_ERROR', 'SOURCE_CHANGED', 'LEDGER_CHANGED', 'LEDGER_REJECTED']
const failures = ['NOT_CONFIGURED', 'TARGET_CHANGED', 'TIMEOUT', 'CONNECTION', 'AUTHENTICATION', 'REMOTE_FAILURE', 'INVALID_RESPONSE', 'RESPONSE_TOO_LARGE', 'LEASE_EXPIRED', 'INTERNAL_ERROR', 'AUTHORIZATION_EXPIRED', 'SOURCE_CHANGED', 'INCONSISTENT_OBSERVATION', 'RECHECK_REQUESTED']
const rejections = ['AUTHORIZATION_EXPIRED', 'BUDGET_PERIOD_CLOSED', 'BUDGET_INSUFFICIENT', 'LEDGER_VERSION_CONFLICT', 'BUDGET_POLICY_UNAVAILABLE', 'BUDGET_POSITION_UNAVAILABLE', 'LEGAL_ENTITY_UNAVAILABLE', 'EMPLOYEE_UNAVAILABLE']

/** 只接受本人消息的原来源和最小只读事实，不接收台账、金额或办理许可。 */
export function readBudgetAdjustmentNotificationTarget(value: BudgetAdjustmentNotificationTarget, message: InboxMessage): BudgetAdjustmentNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原预算调整记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && Object.keys(v).every(k => keys.includes(k))
  if (!closed(value, ['messageId', 'requestId', 'applicationId', 'roundNo', 'sourceType', 'sourceId', 'fact', 'review', 'operation', 'retirement'])
      || !isBudgetAdjustmentNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.requestId, value.applicationId, value.sourceId].every(uuid) || !positive(value.roundNo)
      || !known(budgetAdjustmentNoticeLabels, value.fact) || (message.kind === 'BUDGET_ADJUSTMENT_RESULT') !== resultFacts.includes(value.fact)) invalid()
  if (value.sourceType === 'REVIEW') {
    const review = value.review
    const expected = { REVIEW_UNAVAILABLE: 'UNAVAILABLE', REVIEW_BLOCKED: 'BLOCKED', REVIEW_SOURCE_CHANGED: 'VOIDED' }
    if (!review || value.operation !== null || value.retirement !== null || !known(expected, value.fact)
        || !closed(review, ['id', 'version', 'status', 'requestedAt', 'updatedAt', 'issue']) || review.id !== value.sourceId || !positive(review.version)
        || !known(budgetReviewLabels, review.status) || review.status !== expected[value.fact as keyof typeof expected]
        || !issues.includes(review.issue) || !time(review.requestedAt) || !time(review.updatedAt) || Date.parse(review.updatedAt) < Date.parse(review.requestedAt)
        || review.status === 'VOIDED' && review.issue !== 'SOURCE_CHANGED'
        || review.status === 'BLOCKED' && !['LEDGER_CHANGED', 'LEDGER_REJECTED'].includes(review.issue)) invalid()
    return value
  }
  const operation = value.operation
  if (value.sourceType !== 'OPERATION' || value.review !== null || !operation || value.fact.startsWith('REVIEW_')) invalid()
  if (!operation) return invalid()
  if (!closed(operation, ['id', 'version', 'status', 'createdAt', 'updatedAt', 'failure', 'observation', 'conflictingObservation']) || operation.id !== value.sourceId
      || !positive(operation.version) || !known(budgetOperationLabels, operation.status) || !time(operation.createdAt) || !time(operation.updatedAt)
      || Date.parse(operation.updatedAt) < Date.parse(operation.createdAt) || operation.failure !== null && !failures.includes(operation.failure)) invalid()
  for (const observed of [operation.observation, operation.conflictingObservation]) {
    if (observed === null) continue
    if (!closed(observed, ['outcome', 'revision', 'observedAt', 'appliedAt', 'rejection']) || !known(budgetResultLabels, observed.outcome)
        || !Number.isSafeInteger(observed.revision) || (observed.outcome === 'NOT_FOUND' ? observed.revision !== 0 : !positive(observed.revision))
        || !time(observed.observedAt) || Date.parse(observed.observedAt) > Date.parse(operation.updatedAt)
        || (observed.outcome === 'APPLIED' ? !time(observed.appliedAt) || Date.parse(observed.appliedAt!) > Date.parse(observed.observedAt) : observed.appliedAt !== null)
        || (observed.outcome === 'REJECTED' ? !rejections.includes(observed.rejection!) : observed.rejection !== null)) invalid()
  }
  const observed = operation.observation, conflict = operation.conflictingObservation
  if (conflict && !observed || ['APPLIED', 'REJECTED', 'NOT_FOUND'].includes(operation.status) && (!observed || observed.outcome !== operation.status || conflict !== null || operation.failure !== null)
      || operation.status === 'RECONCILING' && (!observed || !conflict || operation.failure !== 'INCONSISTENT_OBSERVATION')
      || operation.status === 'VOIDED' && operation.failure !== 'SOURCE_CHANGED' || operation.status === 'EXPIRED' && operation.failure !== 'AUTHORIZATION_EXPIRED') invalid()
  const retirement = value.retirement
  if (retirement !== null && (!closed(retirement, ['operationId', 'operationVersion', 'basis', 'retiredAt']) || retirement.operationId !== operation.id
      || retirement.operationVersion !== operation.version || !time(retirement.retiredAt) || Date.parse(retirement.retiredAt) < Date.parse(operation.updatedAt)
      || (retirement.basis === 'REJECTED' ? operation.status !== 'REJECTED' : retirement.basis !== 'NEVER_SENT' || !['VOIDED', 'EXPIRED'].includes(operation.status)))) invalid()
  if (value.fact === 'RETIRED' && !retirement || value.fact === 'RECONCILING' && !conflict
      || ['APPLIED', 'REJECTED'].includes(value.fact) && ![observed?.outcome, conflict?.outcome].includes(value.fact as 'APPLIED' | 'REJECTED')
      || ['EXPIRED', 'VOIDED'].includes(value.fact) && operation.status !== value.fact) invalid()
  return value
}
