import type { InboxMessage, RepaymentNotificationTarget } from './api'
import { repaymentCheckLabels } from './advanceRepayment.js'

export const repaymentNoticeLabels = {
  UNAVAILABLE: '原还款查询暂不可用，尚未取得可登记的收款依据。', SOURCE_CHANGED: '原放款来源或核对资格已变化，本次还款查询已停止。',
  NOT_FOUND: '本次查询未找到匹配的收款原件，不能据此认定已经还款。', PENDING: '本次原件尚未完成收款核对，尚不能登记还款。',
  REVERSED: '本次原件显示收款已撤销，借款登记及后续处理需要分别核对。',
  REVIEW_REQUIRED: '本次查询与原还款依据不一致，已要求复核；原还款记录保留，查询不会自行改变欠款。',
  RECORDED: '本次收款原件已由财务明确登记还款；后续复核与既有占用分别保留。'
}
export const repaymentObservationLabels = { NOT_FOUND: '本次未找到收款原件', PENDING: '原件尚待确认', CONFIRMED: '原件显示已收款并入账', REVERSED: '原件显示收款已撤销' }
export const isRepaymentNotification = (item: InboxMessage) => ['REPAYMENT_RESULT', 'REPAYMENT_ATTENTION'].includes(item.kind)
/** 查询、登记和当时触发的复核分别验证，旧消息不借用最新余额或后续裁决。 */
export function readRepaymentNotificationTarget(value: RepaymentNotificationTarget, message: InboxMessage): RepaymentNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原借款还款记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).every(key => keys.includes(key))
  if (!closed(value, ['messageId', 'checkId', 'paymentId', 'advanceId', 'applicationId', 'roundNo', 'fact', 'version', 'status', 'requestedAt', 'updatedAt', 'issue', 'reviewRepaymentId', 'observation', 'record'])
      || !isRepaymentNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.checkId, value.paymentId, value.advanceId, value.applicationId].every(uuid) || !positive(value.roundNo) || !positive(value.version)
      || !known(repaymentNoticeLabels, value.fact) || !known(repaymentCheckLabels, value.status)
      || !time(value.requestedAt) || !time(value.updatedAt) || Date.parse(value.updatedAt) < Date.parse(value.requestedAt)
      || value.issue !== null && (typeof value.issue !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.issue))
      || (message.kind === 'REPAYMENT_RESULT') !== (value.fact === 'RECORDED')
      || value.reviewRepaymentId !== null && !uuid(value.reviewRepaymentId)
      || (value.fact === 'REVIEW_REQUIRED') !== (value.reviewRepaymentId !== null)) invalid()
  const observed = value.observation, recorded = value.record
  if (['CHECKED', 'RECORDED'].includes(value.status) !== (observed !== null) || (value.status === 'RECORDED') !== (recorded !== null)
      || ['UNAVAILABLE', 'VOIDED'].includes(value.status) !== (value.issue !== null)) invalid()
  if (observed !== null && (!closed(observed, ['outcome', 'revision', 'observedAt', 'validUntil']) || !known(repaymentObservationLabels, observed.outcome)
      || (observed.outcome === 'NOT_FOUND' ? observed.revision !== 0 : !positive(observed.revision))
      || !time(observed.observedAt) || !time(observed.validUntil) || Date.parse(observed.validUntil) <= Date.parse(observed.observedAt))) invalid()
  // 历史登记允许浏览器截断为同一毫秒，不据此提供新鲜性或再次登记许可。
  if (recorded !== null && (!closed(recorded, ['id', 'advanceVersion', 'recordedAt']) || !uuid(recorded.id) || !positive(recorded.advanceVersion)
      || !time(recorded.recordedAt) || Date.parse(recorded.recordedAt) !== Date.parse(value.updatedAt)
      || !observed || observed.outcome !== 'CONFIRMED' || Date.parse(recorded.recordedAt) < Date.parse(observed.observedAt)
      || Date.parse(recorded.recordedAt) > Date.parse(observed.validUntil))) invalid()
  if (value.fact === 'RECORDED' ? recorded === null
      : value.fact === 'REVIEW_REQUIRED' ? value.status !== 'CHECKED'
      : value.fact === 'SOURCE_CHANGED' ? value.status !== 'VOIDED' || value.issue !== 'SOURCE_CHANGED'
      : value.fact === 'UNAVAILABLE' ? value.status !== 'UNAVAILABLE'
      : value.status !== 'CHECKED' || observed?.outcome !== value.fact) invalid()
  return value
}
