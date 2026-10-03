import type { InboxMessage, RepaymentReviewNotificationTarget } from './api'
import { repaymentReviewCheckLabels } from './repaymentReview.js'

export const repaymentReviewNoticeLabels = {
  UNAVAILABLE: '原还款复核查询暂不可用，尚未形成可裁决结论。', SOURCE_CHANGED: '原来源或核对资格已变化，本次复核查询已停止。',
  UNRESOLVED: '本次原还款及实际退回尚未核清，需要继续核对。', RETURN_REVIEW: '本次原件显示还款资金退回，等待财务明确复核和裁决。',
  REVIEW_REQUIRED: '本次原件与已确认的还款或退回依据不一致，已要求重新复核；历史登记保持不变。',
  RESOLVED: '本次原还款已由财务明确裁决，借款余额按原决定记录；原放款和其他占用仍分别保留。'
}
export const repaymentReviewObservationLabels = { UNRESOLVED: '原还款尚未核清', CONFIRMED: '原件显示原还款有效', PARTIALLY_RETURNED: '原件显示部分还款退回', RETURNED: '原件显示全额还款退回' }
export const isRepaymentReviewNotification = (item: InboxMessage) => ['REPAYMENT_REVIEW_RESULT', 'REPAYMENT_REVIEW_ATTENTION'].includes(item.kind)
/** 查询回执和实际登记分别验证，拒绝换用其他查询、最新决定或办理许可。 */
export function readRepaymentReviewNotificationTarget(value: RepaymentReviewNotificationTarget, message: InboxMessage): RepaymentReviewNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原还款复核记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).every(key => keys.includes(key))
  if (!closed(value, ['messageId', 'checkId', 'paymentId', 'advanceId', 'repaymentId', 'applicationId', 'roundNo', 'fact', 'version', 'status', 'requestedAt', 'updatedAt', 'issue', 'observation', 'resolution'])
      || !isRepaymentReviewNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.checkId, value.paymentId, value.advanceId, value.repaymentId, value.applicationId].every(uuid) || !positive(value.roundNo) || !positive(value.version)
      || !known(repaymentReviewNoticeLabels, value.fact) || !known(repaymentReviewCheckLabels, value.status)
      || !time(value.requestedAt) || !time(value.updatedAt) || Date.parse(value.updatedAt) < Date.parse(value.requestedAt)
      || value.issue !== null && (typeof value.issue !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.issue))
      || (message.kind === 'REPAYMENT_REVIEW_RESULT') !== (value.fact === 'RESOLVED')) invalid()
  const observed = value.observation, resolved = value.resolution
  if ((['CHECKED', 'RESOLVED'].includes(value.status)) !== (observed !== null) || (value.status === 'RESOLVED') !== (resolved !== null)
      || ['UNAVAILABLE', 'VOIDED'].includes(value.status) !== (value.issue !== null)) invalid()
  if (observed !== null && (!closed(observed, ['outcome', 'revision', 'observedAt', 'validUntil']) || !known(repaymentReviewObservationLabels, observed.outcome)
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
