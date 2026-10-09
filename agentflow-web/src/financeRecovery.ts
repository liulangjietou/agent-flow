import type { FinancePaymentView } from './payments.js'
import type { VoucherView } from './vouchers.js'

export interface FinanceRecoveryAdvice { reason: string; next: string; originalId: string | null }
/** 资金恢复只解释已授权的原操作，未知结果不能推导成失败或新的付款授权。 */
export function paymentRecovery(view: FinancePaymentView): FinanceRecoveryAdvice | null {
  const payment = view.payment, operation = payment?.operation
  if (!payment || !operation) return null
  const originalId = payment.id
  if (operation.disputed || operation.status === 'RECONCILING') return { originalId, reason: '原资金回执存在冲突，历史到账或退回证据仍然保留。', next: view.dispute?.canResolve ? '核对本页权威查询候选和凭据后，使用“确认原付款对账结果”。' : view.actions.query ? '使用“查询原交易”取得新的权威回执，再由财务核对。' : '当前身份没有可执行的恢复动作，请由原付款财务负责人核对。' }
  if (['UNKNOWN', 'QUERYING', 'SENDING'].includes(operation.status)) return { originalId, reason: '尚未确认原付款的最终结果，不能据此认为付款失败。', next: view.actions.query ? '使用“查询原交易”，沿用本页原授权编号。' : '等待原交易查询完成后刷新本页；不要新增一笔付款。' }
  if (['FAILED', 'NOT_FOUND', 'EXPIRED'].includes(operation.status)) return { originalId, reason: '当前原付款未取得有效到账结果，后续动作取决于服务端核对的状态。', next: view.actions.retire ? '核对本页记录后，可使用“安全结束原付款”；新授权仍需独立确认。' : '核对原交易及账户资料，由有权限的财务或出纳按本页允许动作处理。' }
  return null
}
/** ERP 已确认查无原操作且服务端允许时，才提示按原号重发。 */
export function voucherRecovery(view: VoucherView): FinanceRecoveryAdvice | null {
  const operation = view.operation
  if (!operation) return null
  const originalId = operation.id
  if (operation.disputed || operation.status === 'RECONCILING') return { originalId, reason: 'ERP 回执与已接受的凭证依据不一致。', next: view.dispute?.canResolve ? '核对 ERP 候选回执和原过账编号后，使用本页对账确认。' : view.actions.query ? '先查询原 ERP 操作，取得可用于财务核对的权威终态。' : '当前身份没有可执行的恢复动作，请由原凭证财务负责人核对。' }
  if (['UNKNOWN', 'QUERYING', 'POSTING'].includes(operation.status)) return { originalId, reason: '原过账最终结果待确认，不能新建替代凭证。', next: view.actions.query ? '使用“查询 ERP 结果”，沿用原过账操作。' : '等待原操作查询完成后刷新，保留原凭证编号与金额。' }
  if (operation.status === 'NOT_FOUND') return { originalId, reason: 'ERP 已确认原操作不存在。', next: view.actions.resendOriginal ? '核对发送期限、会计日期和金额后，可使用“按原编号重发”。' : '当前不具备重发条件，请核对本页期限与权限。' }
  return null
}
