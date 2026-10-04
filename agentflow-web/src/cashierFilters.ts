import { isPaymentDueDate, validateCashierPayment, type CashierAccountOption, type CashierFilterOptions, type CashierPaymentFilter, type CashierPaymentPage, type CashierPaymentView } from './payments.js'

const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const accountKey = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const name = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value.length <= 128
function fields(value: unknown, keys: string[]): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.prototype.hasOwnProperty.call(value, key))
}
function account(value: unknown): value is CashierAccountOption {
  if (!fields(value, ['key', 'legalEntityId', 'currency', 'displayName', 'maskedAccount'])) return false
  const mask = value.maskedAccount
  return accountKey(value.key) && uuid(value.legalEntityId) && typeof value.currency === 'string' && /^[A-Z]{3}$/.test(value.currency) && name(value.displayName)
    && typeof mask === 'string' && mask.length <= 128 && /^[0-9*•xX -]+$/.test(mask) && /\*{2,}|•{2,}|[xX]{2,}/.test(mask) && !/[0-9]{5}/.test(mask) && (mask.match(/[0-9]/g) ?? []).length <= 8
}

/** 法人、账户只接受当前页面选项中的规范标识，不拼接调用者提供的查询字符串。 */
export function cashierFilter(legalEntityId: string, debitAccount: string, dueFrom = '', dueTo = '', undated = false, sort = 'AUTHORIZED_AT_DESC'): CashierPaymentFilter {
  if (legalEntityId && !uuid(legalEntityId) || debitAccount && debitAccount !== 'UNASSIGNED' && !accountKey(debitAccount)
      || dueFrom && !isPaymentDueDate(dueFrom) || dueTo && !isPaymentDueDate(dueTo) || dueFrom && dueTo && dueFrom > dueTo
      || undated && (dueFrom || dueTo) || !['AUTHORIZED_AT_DESC', 'DUE_DATE_ASC'].includes(sort)) throw new Error('付款筛选条件无效，请核对日期范围和选项。')
  return { ...(legalEntityId ? { legalEntityId } : {}), ...(debitAccount ? { debitAccount } : {}),
    ...(dueFrom ? { dueFrom } : {}), ...(dueTo ? { dueTo } : {}), ...(undated ? { undated: true } : {}), ...(sort === 'DUE_DATE_ASC' ? { sort } : {}) }
}

/** 账户选项可继续分页；切换法人后，旧范围的账户和游标不能混入新目录。 */
export function validateCashierFilterOptions(value: CashierFilterOptions, legalEntityId = '', after?: string): CashierFilterOptions {
  if (!fields(value, ['legalEntities', 'accounts', 'nextAfterAccountKey']) || !Array.isArray(value.legalEntities) || !Array.isArray(value.accounts)
      || value.accounts.length > 25 || value.legalEntities.some(item => !fields(item, ['id', 'name']) || !uuid(item.id) || !name(item.name))
      || new Set(value.legalEntities.map(item => item.id)).size !== value.legalEntities.length
      || value.accounts.some(item => !account(item) || legalEntityId && item.legalEntityId !== legalEntityId || !value.legalEntities.some(entity => entity.id === item.legalEntityId))
      || new Set(value.accounts.map(item => item.key)).size !== value.accounts.length
      || value.accounts.some((item, index) => after && item.key <= after || index > 0 && item.key <= value.accounts[index - 1]!.key)
      || value.nextAfterAccountKey !== null && (!accountKey(value.nextAfterAccountKey) || !value.accounts.length || value.nextAfterAccountKey !== value.accounts[value.accounts.length - 1]!.key || value.nextAfterAccountKey === after)) {
    throw new Error('付款筛选选项未通过校验，请重新读取。')
  }
  return value
}

/** 同条件总数和已固定账户共同校验；未复查选择不伪装成已确认出款账户。 */
export function validateCashierPaymentPage(value: CashierPaymentPage, filter: CashierPaymentFilter, before?: string): CashierPaymentPage {
  if (!fields(value, ['items', 'nextBeforeId', 'totalCount']) || !Array.isArray(value.items) || value.items.length > 25
      || !Number.isSafeInteger(value.totalCount) || value.totalCount < value.items.length || value.totalCount < 0
      || value.nextBeforeId !== null && (typeof value.nextBeforeId !== 'string' || !value.items.length || value.nextBeforeId !== value.items[value.items.length - 1]?.payment.id || value.nextBeforeId === before)) {
    throw new Error('付款分页结果未通过校验。')
  }
  for (const item of value.items) {
    if (!fields(item, ['payment', 'actions', 'debitAccount'])) throw new Error('出纳付款条目未通过校验。')
    validateCashierPayment(item as CashierPaymentView)
    if (item.debitAccount !== null && (!account(item.debitAccount) || item.debitAccount.legalEntityId !== item.payment.legalEntityId || item.debitAccount.currency !== item.payment.amount.currency)
        || (item.payment.executedBy !== null) !== (item.debitAccount !== null)
        || filter.legalEntityId && item.payment.legalEntityId !== filter.legalEntityId
        || filter.debitAccount === 'UNASSIGNED' && item.debitAccount !== null
        || filter.debitAccount && filter.debitAccount !== 'UNASSIGNED' && item.debitAccount?.key !== filter.debitAccount
        || filter.undated && item.payment.dueDate !== null
        || filter.dueFrom && (item.payment.dueDate === null || item.payment.dueDate < filter.dueFrom)
        || filter.dueTo && (item.payment.dueDate === null || item.payment.dueDate > filter.dueTo)) throw new Error('付款记录与当前筛选条件不一致，请刷新。')
  }
  return value
}
