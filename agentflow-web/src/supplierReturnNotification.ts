import type { InboxMessage, SupplierReturnNotificationTarget } from './api'
import { supplierReturnCheckLabels } from './supplierReturn.js'

export const supplierReturnNoticeLabels = {
  UNAVAILABLE: '原供应商回款查询暂不可用，尚未形成可登记结论。', SOURCE_CHANGED: '原来源或核对资格已变化，本次回款查询已停止。',
  UNRESOLVED: '本次查询尚未核清原付款及实际入款，需要继续核对。', RETURN_REVIEW: '本次原件显示银行资金退回，等待财务明确核对和登记。',
  RECORDED: '本次回款核对已由财务明确登记，ERP 调整与本地账务完成仍需分别核对。'
}
export const supplierReturnObservationLabels = { UNRESOLVED: '原件尚未核清', CONFIRMED: '原件未见资金退回', PARTIALLY_RETURNED: '原件显示部分退回', RETURNED: '原件显示全额退回' }
export const isSupplierReturnNotification = (item: InboxMessage) => ['SUPPLIER_RETURN_RESULT', 'SUPPLIER_RETURN_ATTENTION'].includes(item.kind)
/** 查询回执和实际登记分别验证，拒绝换用其他查询、最新决定或办理许可。 */
export function readSupplierReturnNotificationTarget(value: SupplierReturnNotificationTarget, message: InboxMessage): SupplierReturnNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原供应商回款记录不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).every(key => keys.includes(key))
  if (!closed(value, ['messageId', 'checkId', 'paymentId', 'requestId', 'applicationId', 'roundNo', 'fact', 'version', 'status', 'requestedAt', 'updatedAt', 'issue', 'observation', 'registration'])
      || !isSupplierReturnNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.checkId, value.paymentId, value.requestId, value.applicationId].every(uuid) || !positive(value.roundNo) || !positive(value.version)
      || !known(supplierReturnNoticeLabels, value.fact) || !known(supplierReturnCheckLabels, value.status)
      || !time(value.requestedAt) || !time(value.updatedAt) || Date.parse(value.updatedAt) < Date.parse(value.requestedAt)
      || value.issue !== null && (typeof value.issue !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.issue))
      || (message.kind === 'SUPPLIER_RETURN_RESULT') !== (value.fact === 'RECORDED')) invalid()
  const observed = value.observation, registered = value.registration
  if ((['CHECKED', 'RESOLVED'].includes(value.status)) !== (observed !== null) || (value.status === 'RESOLVED') !== (registered !== null)
      || ['UNAVAILABLE', 'VOIDED'].includes(value.status) !== (value.issue !== null)) invalid()
  if (observed !== null && (!closed(observed, ['outcome', 'revision', 'observedAt', 'validUntil']) || !known(supplierReturnObservationLabels, observed.outcome)
      || !positive(observed.revision) || !time(observed.observedAt) || !time(observed.validUntil) || Date.parse(observed.validUntil) <= Date.parse(observed.observedAt))) invalid()
  // 浏览器仅保留毫秒精度，相等时依赖后端已保存的登记证明，不据此授予登记许可。
  if (registered !== null && (!closed(registered, ['id', 'returnVersion', 'outcome', 'registeredAt']) || !uuid(registered.id) || !positive(registered.returnVersion)
      || !time(registered.registeredAt) || Date.parse(registered.registeredAt) !== Date.parse(value.updatedAt)
      || !observed || observed.outcome === 'UNRESOLVED' || registered.outcome !== observed.outcome
      || Date.parse(registered.registeredAt) < Date.parse(observed.observedAt) || Date.parse(registered.registeredAt) > Date.parse(observed.validUntil))) invalid()
  if (value.fact === 'RECORDED' ? registered === null
      : value.fact === 'RETURN_REVIEW' ? !observed || !['PARTIALLY_RETURNED', 'RETURNED'].includes(observed.outcome)
      : value.fact === 'UNRESOLVED' ? value.status !== 'CHECKED' || observed?.outcome !== 'UNRESOLVED'
      : value.fact === 'SOURCE_CHANGED' ? value.status !== 'VOIDED' || value.issue !== 'SOURCE_CHANGED'
      : value.status !== 'UNAVAILABLE') invalid()
  return value
}
