import type { SupplierPayableNotificationTarget, InboxMessage } from './api'
import { holdLabels, reviewLabels } from './supplierFinance.js'

export const supplierPayableNoticeLabels = {
  "REVIEW_UNAVAILABLE": "本次应付复核暂不可用，尚未形成新的财务授权。",
  "REVIEW_BLOCKED": "本次应付依据未通过复核，请核对原采购与账户条件；尚未登记预留。",
  "REVIEW_SOURCE_CHANGED": "原批准或办理条件已变化，本次应付复核已停止。",
  "REVIEW_INTERRUPTED": "本次应付读取已中断，正在恢复原请求；不表示财务授权或预留完成。",
  "UNKNOWN": "原 ERP 应付预留结果暂不明确，需继续按原授权查询；不能据此另起预留。",
  "NOT_FOUND": "本次查询未找到原应付预留；查无不能证明预留从未发生。",
  "HELD": "原 ERP 应付已收到预留回执，银行付款与应付结算仍需分别核对。",
  "REJECTED": "原 ERP 已明确拒绝本次预留，请核对原授权；这不是银行付款结果。",
  "RECONCILING": "原应付预留回执存在矛盾，原观察与冲突依据分别保留，需要继续核对。",
  "EXPIRED": "原应付预留发送窗口已到期，未继续发送；不据此推断其他操作结果。",
  "VOIDED": "原批准或财务资格已变化，尚未发送的原应付预留已停止。",
  "RETIRED": "财务已依据原预留的安全证明结束本次授权，后续办理须重新复核并授权。"
}
export const payableObservationLabels = { HELD: '原应付已预留', REJECTED: '原预留明确拒绝', PENDING: '原系统处理中', NOT_FOUND: '暂未查到原预留' }
export const payableRejectionLabels = { PAYABLE_VERSION_CONFLICT: '原应付版本已变化', PAYABLE_INSUFFICIENT: '原应付可用余额不足', PAYABLE_UNAVAILABLE: '原应付当前不可用', SUPPLIER_UNAVAILABLE: '原供应商不可用', ACCOUNT_CHANGED: '原收款账户已变化', MATCHING_CHANGED: '原采购匹配已变化', AUTHORIZATION_EXPIRED: '原授权已到期', APPROVAL_CHANGED: '原批准依据已变化' }
export const isSupplierPayableNotification = (value: InboxMessage) => ['SUPPLIER_PAYABLE_RESULT', 'SUPPLIER_PAYABLE_ATTENTION'].includes(value.kind)
const resultFacts = ['HELD', 'REJECTED', 'RETIRED']
const issues = ['NOT_CONFIGURED', 'TARGET_CHANGED', 'TIMEOUT', 'CONNECTION', 'AUTHENTICATION', 'REMOTE_FAILURE', 'INVALID_RESPONSE', 'RESPONSE_TOO_LARGE', 'INTERNAL_ERROR', 'LEASE_EXPIRED', 'SOURCE_CHANGED', 'PAYABLE_CHANGED', 'PAYABLE_REJECTED']
const failures = ['NOT_CONFIGURED', 'TARGET_CHANGED', 'TIMEOUT', 'CONNECTION', 'AUTHENTICATION', 'REMOTE_FAILURE', 'INVALID_RESPONSE', 'RESPONSE_TOO_LARGE', 'LEASE_EXPIRED', 'INTERNAL_ERROR', 'SEND_WINDOW_EXPIRED', 'SOURCE_CHANGED', 'FINANCE_RETIRED', 'STALE_OBSERVATION', 'INCONSISTENT_OBSERVATION', 'RECHECK_REQUESTED']

/** 闭集投影只接受当前消息的原编号、轮次和事实，财务命令、账户及办理许可一律拒绝。 */
export function readSupplierPayableNotificationTarget(value: SupplierPayableNotificationTarget, message: InboxMessage): SupplierPayableNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原应付记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && Object.keys(v).every(k => keys.includes(k))
  if (!closed(value, ['messageId', 'requestId', 'applicationId', 'roundNo', 'sourceType', 'sourceId', 'fact', 'review', 'operation', 'retirement'])
      || !isSupplierPayableNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.requestId, value.applicationId, value.sourceId].every(uuid) || !positive(value.roundNo)
      || !known(supplierPayableNoticeLabels, value.fact) || (message.kind === 'SUPPLIER_PAYABLE_RESULT') !== resultFacts.includes(value.fact)) invalid()
  if (value.sourceType === 'REVIEW') {
    const review = value.review
    const expected = { REVIEW_UNAVAILABLE: 'UNAVAILABLE', REVIEW_BLOCKED: 'BLOCKED', REVIEW_SOURCE_CHANGED: 'VOIDED' }
    if (!review || value.operation !== null || value.retirement !== null || !value.fact.startsWith('REVIEW_')) return invalid()
    if (!closed(review, ['id', 'version', 'status', 'requestedAt', 'updatedAt', 'issue']) || review.id !== value.sourceId || !positive(review.version)
        || !known(reviewLabels, review.status) || !time(review.requestedAt) || !time(review.updatedAt) || Date.parse(review.updatedAt) < Date.parse(review.requestedAt)
        || review.issue !== null && !issues.includes(review.issue)
        || value.fact !== 'REVIEW_INTERRUPTED' && review.status !== expected[value.fact as keyof typeof expected]
        || ['RUNNING', 'READY', 'CONSUMED'].includes(review.status) && review.issue !== null
        || review.status === 'UNAVAILABLE' && review.issue === null || review.status === 'QUEUED' && review.issue !== 'LEASE_EXPIRED'
        || review.status === 'VOIDED' && review.issue !== 'SOURCE_CHANGED'
        || review.status === 'BLOCKED' && !['PAYABLE_CHANGED', 'PAYABLE_REJECTED'].includes(review.issue!)) invalid()
    return value
  }
  const operation = value.operation
  if (value.sourceType !== 'OPERATION' || value.review !== null || !operation || value.fact.startsWith('REVIEW_')) return invalid()
  if (!closed(operation, ['id', 'version', 'status', 'createdAt', 'updatedAt', 'failure', 'observation', 'conflictingObservation']) || operation.id !== value.sourceId
      || !positive(operation.version) || !known(holdLabels, operation.status) || !time(operation.createdAt) || !time(operation.updatedAt)
      || Date.parse(operation.updatedAt) < Date.parse(operation.createdAt) || operation.failure !== null && !failures.includes(operation.failure)) invalid()
  for (const observed of [operation.observation, operation.conflictingObservation]) {
    if (observed === null) continue
    if (!closed(observed, ['outcome', 'revision', 'observedAt', 'heldAt', 'rejection']) || !known(payableObservationLabels, observed.outcome)
        || !Number.isSafeInteger(observed.revision) || (observed.outcome === 'NOT_FOUND' ? observed.revision !== 0 : !positive(observed.revision))
        || !time(observed.observedAt) || Date.parse(observed.observedAt) > Date.parse(operation.updatedAt)
        || (observed.outcome === 'HELD' ? !time(observed.heldAt) || Date.parse(observed.heldAt!) > Date.parse(observed.observedAt) : observed.heldAt !== null)
        || (observed.outcome === 'REJECTED' ? !known(payableRejectionLabels, observed.rejection!) : observed.rejection !== null)) invalid()
  }
  const observed = operation.observation, conflict = operation.conflictingObservation
  if (['HELD', 'REJECTED', 'NOT_FOUND'].includes(operation.status) && (!observed || observed.outcome !== operation.status || conflict !== null || operation.failure !== null)
      || operation.status === 'RECONCILING' && (!conflict || !['INCONSISTENT_OBSERVATION', 'STALE_OBSERVATION'].includes(operation.failure!))
      || operation.status === 'VOIDED' && !['SOURCE_CHANGED', 'FINANCE_RETIRED'].includes(operation.failure!)
      || operation.status === 'EXPIRED' && operation.failure !== 'SEND_WINDOW_EXPIRED'
      || operation.status === 'UNKNOWN' && operation.failure === null && observed?.outcome !== 'PENDING'
      || ['QUEUED', 'RESERVING', 'QUERYING'].includes(operation.status) && operation.failure !== null) invalid()
  const retirement = value.retirement
  if (retirement !== null && (!closed(retirement, ['operationId', 'operationVersion', 'basis', 'retiredAt']) || retirement.operationId !== operation.id
      || retirement.operationVersion !== operation.version || !time(retirement.retiredAt) || Date.parse(retirement.retiredAt) < Date.parse(operation.updatedAt)
      || (retirement.basis === 'CONFIRMED_REJECTED' ? operation.status !== 'REJECTED' : retirement.basis !== 'NEVER_DISPATCHED' || !['VOIDED', 'EXPIRED'].includes(operation.status)))) invalid()
  if (value.fact === 'RETIRED' && !retirement || value.fact === 'RECONCILING' && !conflict
      || ['HELD', 'REJECTED'].includes(value.fact) && ![observed?.outcome, conflict?.outcome].includes(value.fact as 'HELD' | 'REJECTED')
      || ['EXPIRED', 'VOIDED'].includes(value.fact) && operation.status !== value.fact) invalid()
  return value
}
