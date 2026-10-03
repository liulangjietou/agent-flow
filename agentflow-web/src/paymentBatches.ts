import { amountMinor } from './expenses.js'
import { cashierPaymentInput, validateCashierPayment, validatePaymentAccounts, type CashierPaymentView, type DebitAccount, type PaymentAccounts } from './payments.js'

export const MAX_BATCH_ITEMS = 25
export interface PaymentBatchInput { items: { authorizationId: string; authorizationVersion: number }[]; debitAccountReference: string; debitAccountVersion: string; comment: string }
export interface PaymentBatchReceipt { batchId: string; createdAt: string; itemCount: number }
export interface PaymentBatchSummary { id: string; legalEntityId: string; currency: string; total: string; itemCount: number; cashier: string; createdAt: string }
export interface PaymentBatchPage { items: PaymentBatchSummary[]; nextBeforeId: string | null }
export interface PaymentBatchDetail { batch: PaymentBatchSummary; comment: string; items: { authorizationVersion: number; requestId: string; current: CashierPaymentView }[] }
const time = (value: string) => typeof value === 'string' && Number.isFinite(Date.parse(value))
const count = (value: number) => Number.isSafeInteger(value) && value >= 1 && value <= MAX_BATCH_ITEMS
const decimal = (minor: bigint) => `${minor / 100n}.${(minor % 100n).toString().padStart(2, '0')}`

/** 汇总始终使用整数分，不能因跨越单笔金额上限而丢失精度。 */
export function batchTotal(items: CashierPaymentView[]): string { return decimal(items.reduce((total, item) => total + amountMinor(item.payment.amount.value), 0n)) }

/** 勾选只接受同法人、同币种的有效原授权，最终登记仍由服务端逐笔复核。 */
export function validateBatchSelection(items: CashierPaymentView[], now = Date.now()) {
  if (!count(items.length) || new Set(items.map(item => item.payment.id)).size !== items.length) throw new Error('请选择 1 至 25 笔不同的付款授权。')
  const first = items[0]!.payment
  for (const item of items) {
    const payment = validateCashierPayment(item).payment
    if (!item.actions.execute || payment.status !== 'AUTHORIZED' || payment.version !== 1 || payment.request || payment.operation || Date.parse(payment.expiresAt) <= now) throw new Error('部分授权已变化或到期，请刷新目录后重新选择。')
    if (payment.legalEntityId !== first.legalEntityId || payment.amount.currency !== first.amount.currency) throw new Error('一个批次只接受同一法人、同一币种的付款。')
  }
}

/** 逐笔读取真实账户目录，只展示每笔都允许且版本、脱敏展示一致的交集。 */
export function commonBatchAccounts(items: CashierPaymentView[], directories: PaymentAccounts[], now = Date.now()): DebitAccount[] {
  validateBatchSelection(items, now)
  if (directories.length !== items.length) throw new Error('部分付款账户目录尚未就绪，请重新查询。')
  directories.forEach((directory, index) => validatePaymentAccounts(directory, items[index]!.payment, now))
  return directories[0]!.items.filter(account => directories.every(directory => directory.items.some(candidate => candidate.reference === account.reference
    && candidate.sourceVersion === account.sourceVersion && candidate.maskedAccount === account.maskedAccount && candidate.displayName === account.displayName && candidate.currency === account.currency)))
}

/** 金额、收款人和目标不由客户端提交；明确选择的同一账户应用于所有原授权。 */
export function paymentBatchInput(items: CashierPaymentView[], directories: PaymentAccounts[], selected: string, comment: string, now = Date.now()): PaymentBatchInput {
  if (!commonBatchAccounts(items, directories, now).some(account => account.reference === selected)) throw new Error('请明确选择所有付款共同可用的出款账户。')
  const inputs = items.map((item, index) => cashierPaymentInput(item, 'EXECUTE', comment, directories[index], selected, now))
  return { items: items.map(item => ({ authorizationId: item.payment.id, authorizationVersion: item.payment.version })), debitAccountReference: inputs[0]!.debitAccountReference!, debitAccountVersion: inputs[0]!.debitAccountVersion!, comment: inputs[0]!.comment }
}

/** 批次回执只确认登记数量，后续每笔结果从原交易读取。 */
export function validateBatchReceipt(value: PaymentBatchReceipt, input: PaymentBatchInput) {
  if (!value || !value.batchId || !time(value.createdAt) || value.itemCount !== input.items.length) throw new Error('批次回执未通过校验，请恢复原请求并核对记录。')
}
/** 目录摘要限定数量和十进制格式，不接收外部构造的批次成功状态。 */
export function validateBatchSummary(value: PaymentBatchSummary): PaymentBatchSummary {
  if (!value || !value.id || !value.legalEntityId || !/^[A-Z]{3}$/.test(value.currency) || !count(value.itemCount) || !value.cashier || !time(value.createdAt)
    || typeof value.total !== 'string' || !/^[1-9][0-9]{0,16}\.[0-9]{2}$|^0\.[0-9]{2}$/.test(value.total) || value.total === '0.00') throw new Error('批次摘要未通过校验，请刷新核对。')
  return value
}
/** 详情逐笔绑定原请求，并重算合计；历史登记不推导统一到账状态。 */
export function validateBatchDetail(value: PaymentBatchDetail, id: string): PaymentBatchDetail {
  const batch = validateBatchSummary(value?.batch)
  if (batch.id !== id || typeof value.comment !== 'string' || !value.comment.trim() || !Array.isArray(value.items) || value.items.length !== batch.itemCount) throw new Error('批次详情不属于当前记录。')
  const payments = value.items.map(item => {
    const current = validateCashierPayment(item.current), payment = current.payment
    if (item.authorizationVersion !== 1 || payment.version < item.authorizationVersion || !item.requestId || item.requestId !== payment.request?.id
      || payment.request.cashier !== batch.cashier || payment.legalEntityId !== batch.legalEntityId || payment.amount.currency !== batch.currency) throw new Error('批次成员与原付款请求不一致。')
    return current
  })
  if (new Set(payments.map(item => item.payment.id)).size !== payments.length || new Set(value.items.map(item => item.requestId)).size !== payments.length || batchTotal(payments) !== batch.total) throw new Error('批次成员或合计金额未通过核对。')
  return value
}
