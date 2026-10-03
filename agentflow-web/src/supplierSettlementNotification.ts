import type { InboxMessage, SupplierSettlementNotificationTarget } from './api'
import { supplierSettlementLabels, settlementPreparationLabels } from './supplierSettlement.js'

export const supplierSettlementNoticeLabels = {
  PREPARATION_RETRY: '结算依据读取暂不可用，原登记等待重读。', PREPARATION_BLOCKED: '结算依据未通过，本次尚未登记 ERP 核销。',
  PREPARATION_VOIDED: '原准备依据已变化，本次准备已停止。', EXECUTION_RETRY: '发送前复核暂不可用，原指令等待重读。',
  UNKNOWN: 'ERP 结果暂不明确，继续核对原编号。', NOT_FOUND: 'ERP 暂未查到原核销，不代表可以另建核销。',
  RECONCILING: '原 ERP 核销事实存在矛盾，需要人工核对。', REJECTED: 'ERP 明确拒绝本次核销，安全结束仍需独立处理。',
  VOIDED: '本次核销发送已停止，尚未证明安全结束。', ERP_SETTLED: 'ERP 已确认核销，当时本地占用尚未完成。',
  COMPLETED: '原 ERP 核销及本地占用均已记录完成。', RETIRED: '本次结算已安全结束，原银行已付事实保持。'
}
export const isSupplierSettlementNotification = (item: InboxMessage) => ['SUPPLIER_SETTLEMENT_RESULT', 'SUPPLIER_SETTLEMENT_ATTENTION'].includes(item.kind)
/** 拒绝不同原编号、伪造完成和夹带办理许可；当前状态可在消息发生后继续变化。 */
export function readSupplierSettlementNotificationTarget(value: SupplierSettlementNotificationTarget, message: InboxMessage): SupplierSettlementNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原供应商结算不一致，请刷新消息后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).every(key => keys.includes(key))
  const state = (v: SupplierSettlementNotificationTarget['preparation'] | NonNullable<SupplierSettlementNotificationTarget['operation']>, labels: object) => {
    if (!closed(v, ['version', 'status', 'issue', 'updatedAt']) || !positive(v.version) || !known(labels, v.status) || !time(v.updatedAt)
        || v.issue !== null && (typeof v.issue !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(v.issue))) invalid()
  }
  if (!closed(value, ['messageId', 'settlementId', 'paymentId', 'requestId', 'applicationId', 'roundNo', 'accountingDate', 'fact', 'preparation', 'operation', 'retirement', 'completion'])
      || !isSupplierSettlementNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.settlementId, value.paymentId, value.requestId, value.applicationId].every(uuid) || !positive(value.roundNo)
      || !known(supplierSettlementNoticeLabels, value.fact) || !/^\d{4}-\d{2}-\d{2}$/.test(value.accountingDate) || !time(value.accountingDate)
      || new Date(value.accountingDate).toISOString().slice(0, 10) !== value.accountingDate
      || (message.kind === 'SUPPLIER_SETTLEMENT_RESULT') !== ['ERP_SETTLED', 'COMPLETED', 'RETIRED'].includes(value.fact)) invalid()
  state(value.preparation, settlementPreparationLabels)
  if (value.operation !== null) state(value.operation, supplierSettlementLabels)
  if ((value.preparation.status === 'READY') !== (value.operation !== null)) invalid()
  if (value.retirement !== null && (!closed(value.retirement, ['basis', 'retiredAt']) || !time(value.retirement.retiredAt)
      || !['NEVER_DISPATCHED', 'CONFIRMED_REJECTED'].includes(value.retirement.basis) || !value.operation
      || !['REJECTED', 'VOIDED'].includes(value.operation.status))) invalid()
  const completion = value.completion
  if (completion !== null && (!closed(completion, ['operationId', 'operationVersion', 'paymentId', 'completedAt'])
      || completion.operationId !== value.settlementId || completion.paymentId !== value.paymentId || !positive(completion.operationVersion)
      || !time(completion.completedAt) || !value.operation || completion.operationVersion > value.operation.version || value.retirement !== null
      || completion.operationVersion === value.operation.version && value.operation.status !== 'SETTLED')) invalid()
  if (value.fact === 'COMPLETED' && completion === null || value.fact === 'RETIRED' && value.retirement === null
      || !value.fact.startsWith('PREPARATION_') && value.operation === null) invalid()
  return value
}
