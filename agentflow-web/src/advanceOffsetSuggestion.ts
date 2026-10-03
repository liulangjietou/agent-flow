import { amountMinor, type ExpenseDetail, type Money } from './expenses.js'
import type { PrecheckView } from './expenseDraft'

export interface AdvanceOffsetSuggestion {
  reportId: string; applicationId: string; applicationVersion: number; financialVersion: number; precheckId: string; validUntil: string
  approvedGross: Money; offsetTotal: Money; payable: Money; selectionLimitReached: boolean
  items: Array<{ advanceId: string; version: number; paidOn: string; capacity: Money; amount: Money }>
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** 建议只可作用于原预检和双版本；金额以分校验，不从页面手填费用重新估算。 */
export function requireAdvanceOffsetSuggestion(value: AdvanceOffsetSuggestion, detail: ExpenseDetail, precheck: PrecheckView, now = Date.now()): AdvanceOffsetSuggestion {
  const invalid = () => { throw new Error('借款建议与当前预检不一致，请重新读取。') }
  if (!value || !detail.editable || !precheck.usable || precheck.job.status !== 'READY' || !precheck.preview
    || precheck.job.applicationVersion !== detail.applicationVersion || precheck.job.financialVersion !== detail.financialVersion
    || value.reportId !== detail.id || value.applicationId !== detail.applicationId || value.applicationVersion !== detail.applicationVersion
    || value.financialVersion !== detail.financialVersion || value.precheckId !== precheck.job.id
    || value.validUntil !== precheck.validUntil || !(Date.parse(value.validUntil) > now)
    || !Array.isArray(value.items) || value.items.length > 50 || typeof value.selectionLimitReached !== 'boolean') invalid()
  const gross = precheck.preview!.approvedGross, ids = new Set<string>()
  const cents = (money: Money) => {
    if (!money || money.currency !== gross.currency) invalid()
    return amountMinor(money.value)
  }
  let total = 0n, previous = ''
  for (const item of value.items) {
    if (!item || !uuid.test(item.advanceId) || ids.has(item.advanceId) || !Number.isSafeInteger(item.version) || item.version < 1
      || !/^\d{4}-\d{2}-\d{2}$/.test(item.paidOn) || !Number.isFinite(Date.parse(item.paidOn))
      || new Date(item.paidOn).toISOString().slice(0, 10) !== item.paidOn) invalid()
    const order = `${item.paidOn}/${item.advanceId}`
    if (previous && order <= previous) invalid()
    previous = order; ids.add(item.advanceId)
    const amount = cents(item.amount)
    if (amount <= 0n || amount > cents(item.capacity)) invalid()
    total += amount
  }
  if (cents(value.approvedGross) !== amountMinor(gross.value) || total !== cents(value.offsetTotal)
    || total + cents(value.payable) !== cents(value.approvedGross)
    || value.selectionLimitReached !== (value.items.length === 50 && cents(value.payable) > 0n)) invalid()
  return value
}
