import type { InboxMessage, DisbursementReturnNotificationTarget } from './api'
import { disbursementReturnCheckLabels } from './disbursementReturn.js'

export const disbursementReturnNoticeLabels = {
  UNAVAILABLE: '原借款放款退回查询暂不可用，尚未形成可登记结论。', SOURCE_CHANGED: '原来源或核对资格已变化，本次退回查询已停止。',
  UNRESOLVED: '本次查询尚未核清原付款及实际入款，需要继续核对。', RETURN_REVIEW: '本次原件显示银行资金退回，等待财务明确核对和登记。',
  REVIEW_REQUIRED: '本次原件与既有放款或退回依据不一致，已要求重新核对；历史资金记录保持不变。',
  RESOLVED: '本次原放款核对已由财务明确裁决，借款余额按本次决定记录；其他还款复核和占用仍分别保留。'
}
export const disbursementReturnObservationLabels = { UNRESOLVED: '原件尚未核清', CONFIRMED: '原件未见资金退回', PARTIALLY_RETURNED: '原件显示部分退回', RETURNED: '原件显示全额退回' }
export const isDisbursementReturnNotification = (item: InboxMessage) => ['DISBURSEMENT_RETURN_RESULT', 'DISBURSEMENT_RETURN_ATTENTION'].includes(item.kind)
/** 查询回执和实际登记分别验证，拒绝换用其他查询、最新决定或办理许可。 */
export function readDisbursementReturnNotificationTarget(value: DisbursementReturnNotificationTarget, message: InboxMessage): DisbursementReturnNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原借款放款退回记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).every(key => keys.includes(key))
  if (!closed(value, ['messageId', 'checkId', 'paymentId', 'advanceId', 'applicationId', 'roundNo', 'fact', 'version', 'status', 'requestedAt', 'updatedAt', 'issue', 'observation', 'resolution'])
      || !isDisbursementReturnNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.checkId, value.paymentId, value.advanceId, value.applicationId].every(uuid) || !positive(value.roundNo) || !positive(value.version)
      || !known(disbursementReturnNoticeLabels, value.fact) || !known(disbursementReturnCheckLabels, value.status)
      || !time(value.requestedAt) || !time(value.updatedAt) || Date.parse(value.updatedAt) < Date.parse(value.requestedAt)
      || value.issue !== null && (typeof value.issue !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.issue))
      || (message.kind === 'DISBURSEMENT_RETURN_RESULT') !== (value.fact === 'RESOLVED')) invalid()
  const observed = value.observation, resolved = value.resolution
  if ((['CHECKED', 'RESOLVED'].includes(value.status)) !== (observed !== null) || (value.status === 'RESOLVED') !== (resolved !== null)
      || ['UNAVAILABLE', 'VOIDED'].includes(value.status) !== (value.issue !== null)) invalid()
  if (observed !== null && (!closed(observed, ['outcome', 'revision', 'observedAt', 'validUntil']) || !known(disbursementReturnObservationLabels, observed.outcome)
      || !positive(observed.revision) || !time(observed.observedAt) || !time(observed.validUntil) || Date.parse(observed.validUntil) <= Date.parse(observed.observedAt))) invalid()
  // 浏览器仅保留毫秒精度，相等时依赖后端已保存的登记证明，不据此授予登记许可。
  if (resolved !== null && (!closed(resolved, ['id', 'advanceVersion', 'outcome', 'resolvedAt']) || !uuid(resolved.id) || !positive(resolved.advanceVersion)
      || !time(resolved.resolvedAt) || Date.parse(resolved.resolvedAt) !== Date.parse(value.updatedAt)
      || !observed || observed.outcome === 'UNRESOLVED' || resolved.outcome !== observed.outcome
      || Date.parse(resolved.resolvedAt) < Date.parse(observed.observedAt) || Date.parse(resolved.resolvedAt) > Date.parse(observed.validUntil))) invalid()
  if (value.fact === 'RESOLVED' ? resolved === null
      : value.fact === 'REVIEW_REQUIRED' ? !observed || observed.outcome !== 'CONFIRMED' || !['CHECKED', 'RESOLVED'].includes(value.status)
      : value.fact === 'RETURN_REVIEW' ? !observed || !['PARTIALLY_RETURNED', 'RETURNED'].includes(observed.outcome)
      : value.fact === 'UNRESOLVED' ? value.status !== 'CHECKED' || observed?.outcome !== 'UNRESOLVED'
      : value.fact === 'SOURCE_CHANGED' ? value.status !== 'VOIDED' || value.issue !== 'SOURCE_CHANGED'
      : value.status !== 'UNAVAILABLE') invalid()
  return value
}
