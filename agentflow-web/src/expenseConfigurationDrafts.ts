import { policyFingerprint, type CategoryInput, type ExpenseCategories, type ExpenseCategory, type ExpensePolicyDefinition,
  type ExpensePolicyDraft, type ExpensePolicyRule, type PolicyDraftInput } from './expenseConfiguration.js'

export interface CategoryEdit { baseline: ExpenseCategories; categories: ExpenseCategory[]; comment: string }
export interface PolicyEdit { key: string; baseline: ExpensePolicyDraft | null; definition: ExpensePolicyDefinition; comment: string }
export const copyConfiguration = <T>(value: T): T => JSON.parse(JSON.stringify(value))
/** 空规则不预置任何企业金额、等级、时限或适用对象。 */
export function emptyPolicyRule(): ExpensePolicyRule {
  return { key: '', name: '', match: { legalEntityIds: [], categoryCodes: [], cityTiers: [], employeeGrades: [], fromDate: null, throughDate: null, currency: null },
    constraints: { effect: 'ALLOW', unitPriceLimit: null, limitUnit: null, invoiceMaxAgeDays: null, invoiceAgeAction: null, allowedServiceLevels: [], priorRequestRequired: false } }
}
export function categoryInput(edit: CategoryEdit): CategoryInput {
  return { expectedVersion: edit.baseline.version, categories: copyConfiguration(edit.categories), comment: edit.comment.trim() }
}
export function policyInput(edit: PolicyEdit): PolicyDraftInput {
  return { expectedRevision: edit.baseline?.revision ?? 0, definition: copyConfiguration(edit.definition), comment: edit.comment.trim() }
}
export function categoriesChanged(edit: CategoryEdit): boolean { return JSON.stringify(edit.categories) !== JSON.stringify(edit.baseline.categories) || !!edit.comment }
export function policyChanged(edit: PolicyEdit): boolean {
  return !edit.baseline || JSON.stringify(edit.definition) !== JSON.stringify(edit.baseline.definition) || !!edit.comment
}
/** 草稿只在本页内存驻留，身份和目标双重隔离，不持久化企业规则正文。 */
export class ConfigurationDrafts {
  private categories = new Map<string, CategoryEdit>()
  private policies = new Map<string, Map<string, PolicyEdit>>()
  category(scope: string) { const value = this.categories.get(scope); return value ? copyConfiguration(value) : null }
  policy(scope: string, key: string) { const value = this.policies.get(scope)?.get(key); return value ? copyConfiguration(value) : null }
  putCategories(scope: string, value: CategoryEdit) {
    if (!scope) return
    if (categoriesChanged(value)) this.categories.set(scope, copyConfiguration(value)); else this.categories.delete(scope)
  }
  putPolicy(scope: string, value: PolicyEdit) {
    if (!scope) return
    if (!this.policies.has(scope)) this.policies.set(scope, new Map())
    const slot = value.baseline ? value.key : ''
    if (policyChanged(value)) this.policies.get(scope)!.set(slot, copyConfiguration(value)); else this.discardPolicy(scope, slot)
  }
  discardCategories(scope: string) { this.categories.delete(scope) }
  discardPolicy(scope: string, key: string) { this.policies.get(scope)?.delete(key) }
  hasDrafts() { return this.categories.size > 0 || [...this.policies.values()].some(values => values.size > 0) }
  /** 只清理与原保存正文相同的草稿，未发送的新修改必须继续保留。 */
  acknowledge(scope: string, path: string, body: string) {
    if (path === '/admin/expense-categories') {
      const value = this.categories.get(scope)
      if (value && JSON.stringify(categoryInput(value)) === body) this.categories.delete(scope)
      return
    }
    const match = /^\/admin\/expense-policies\/([^/?]+)\/draft$/.exec(path)
    if (match) {
      const key = decodeURIComponent(match[1]!), values = this.policies.get(scope)
      const slot = values?.has(key) ? key : '', value = values?.get(slot)
      if (value?.key === key && JSON.stringify(policyInput(value)) === body) this.discardPolicy(scope, slot)
    }
  }
}
export const configurationDrafts = new ConfigurationDrafts()
/** 发布必须基于已保存且未变化的草稿，不能把未保存文本当成将发布的正文。 */
export function samePolicyDraft(edit: PolicyEdit, latest: ExpensePolicyDraft): boolean {
  if (edit.key !== latest.key || edit.baseline?.id !== latest.id || edit.baseline.revision !== latest.revision || edit.comment) return false
  try { return policyFingerprint(edit.definition) === policyFingerprint(latest.definition) } catch { return false }
}
