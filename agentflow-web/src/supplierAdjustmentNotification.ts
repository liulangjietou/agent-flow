import type { InboxMessage, SupplierAdjustmentNotificationTarget } from './api'
import { adjustmentLabels, adjustmentPreparationLabels } from './supplierAdjustment.js'

export const supplierAdjustmentNoticeLabels = {
  PREPARATION_RETRY: '应付调整依据读取暂不可用，原登记等待重读。', PREPARATION_BLOCKED: '应付调整依据未通过，本次尚未登记 ERP 调整。',
  PREPARATION_VOIDED: '原准备依据已变化，本次准备已停止。', EXECUTION_RETRY: '发送前复核暂不可用，原指令等待重读。',
  UNKNOWN: 'ERP 结果暂不明确，继续核对原编号。', NOT_FOUND: 'ERP 暂未查到原调整，不代表可以另建调整。',
  RECONCILING: '原 ERP 调整事实存在矛盾，需要人工核对。', REJECTED: 'ERP 明确拒绝本次调整，安全结束仍需独立处理。',
  VOIDED: '本次调整发送已停止，尚未证明安全结束。', ERP_ADJUSTED: 'ERP 已确认调整，当时本地账务尚未完成。',
  COMPLETED: '原 ERP 调整及本地账务均已记录完成。', RETIRED: '本次应付调整已安全结束，原银行已付事实保持。'
}
export const isSupplierAdjustmentNotification = (item: InboxMessage) => ['SUPPLIER_ADJUSTMENT_RESULT', 'SUPPLIER_ADJUSTMENT_ATTENTION'].includes(item.kind)
/** 拒绝不同原编号、伪造完成和夹带办理许可；当前状态可在消息发生后继续变化。 */
export function readSupplierAdjustmentNotificationTarget(value: SupplierAdjustmentNotificationTarget, message: InboxMessage): SupplierAdjustmentNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原供应商应付调整不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).every(key => keys.includes(key))
  const state = (v: SupplierAdjustmentNotificationTarget['preparation'] | NonNullable<SupplierAdjustmentNotificationTarget['operation']>, labels: object) => {
    if (!closed(v, ['version', 'status', 'issue', 'updatedAt']) || !positive(v.version) || !known(labels, v.status) || !time(v.updatedAt)
        || v.issue !== null && (typeof v.issue !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(v.issue))) invalid()
  }
  if (!closed(value, ['messageId', 'adjustmentId', 'paymentId', 'requestId', 'applicationId', 'roundNo', 'accountingDate', 'fact', 'preparation', 'operation', 'retirement', 'completion'])
      || !isSupplierAdjustmentNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.adjustmentId, value.paymentId, value.requestId, value.applicationId].every(uuid) || !positive(value.roundNo)
      || !known(supplierAdjustmentNoticeLabels, value.fact) || !/^\d{4}-\d{2}-\d{2}$/.test(value.accountingDate) || !time(value.accountingDate)
      || new Date(value.accountingDate).toISOString().slice(0, 10) !== value.accountingDate
      || (message.kind === 'SUPPLIER_ADJUSTMENT_RESULT') !== ['ERP_ADJUSTED', 'COMPLETED', 'RETIRED'].includes(value.fact)) invalid()
  state(value.preparation, adjustmentPreparationLabels)
  if (value.operation !== null) state(value.operation, adjustmentLabels)
  if ((value.preparation.status === 'READY') !== (value.operation !== null)) invalid()
  if (value.retirement !== null && (!closed(value.retirement, ['basis', 'retiredAt']) || !time(value.retirement.retiredAt)
      || !['NEVER_DISPATCHED', 'CONFIRMED_REJECTED'].includes(value.retirement.basis) || !value.operation
      || !['REJECTED', 'VOIDED'].includes(value.operation.status))) invalid()
  const completion = value.completion
  if (completion !== null && (!closed(completion, ['adjustmentId', 'adjustmentVersion', 'paymentId', 'paymentVersion', 'returnVersion', 'completedAt'])
      || completion.adjustmentId !== value.adjustmentId || completion.paymentId !== value.paymentId || !positive(completion.adjustmentVersion)
      || !positive(completion.paymentVersion) || !positive(completion.returnVersion) || !time(completion.completedAt) || !value.operation || completion.adjustmentVersion > value.operation.version || value.retirement !== null
      || completion.adjustmentVersion === value.operation.version && value.operation.status !== 'ADJUSTED')) invalid()
  if (value.fact === 'COMPLETED' && completion === null || value.fact === 'RETIRED' && value.retirement === null
      || !value.fact.startsWith('PREPARATION_') && value.operation === null) invalid()
  return value
}
