import { readPriorControl, type PriorControl } from './expensePriorControl.js'
import { amountMinor, type Money, type ExpenseAllowanceRule } from './expenses.js'

export type ExpenseUnit = 'ITEM' | 'DAY' | 'NIGHT' | 'KILOMETER' | 'PERSON'
export const expenseUnits: Record<ExpenseUnit, string> = { ITEM: '项', DAY: '天', NIGHT: '晚', KILOMETER: '公里', PERSON: '人' }
export interface ExpenseCategory { code: string; name: string; units: ExpenseUnit[]; active: boolean; priorControl?: PriorControl | null }
export interface ExpenseCategories { tenantId: string; version: number; categories: ExpenseCategory[] }
export interface PolicyMatch {
  legalEntityIds: string[]; categoryCodes: string[]; cityTiers: string[]; employeeGrades: string[]
  fromDate: string | null; throughDate: string | null; currency: string | null
}
export interface PolicyConstraints {
  effect: 'ALLOW' | 'DENY'; unitPriceLimit: Money | null; limitUnit: ExpenseUnit | null
  invoiceMaxAgeDays: number | null; invoiceAgeAction: 'REJECT' | 'REQUIRE_REASON' | null
  allowedServiceLevels: string[]; priorRequestRequired: boolean
  fixedAllowance?: ExpenseAllowanceRule | null
}
export interface ExpensePolicyRule { key: string; name: string; match: PolicyMatch; constraints: PolicyConstraints }
export interface ExpensePolicyDefinition { name: string; rules: ExpensePolicyRule[] }
export interface ExpensePolicyDraft {
  id: string; tenantId: string; key: string; revision: number; definition: ExpensePolicyDefinition
  publishedVersion: number; publishedDraftRevision: number
}
export interface PublishedExpensePolicy {
  policyId: string; tenantId: string; key: string; version: number; draftRevision: number; categoryRevision: number
  definition: ExpensePolicyDefinition; publishedBy: string; publishedAt: string; comment: string
}
export interface ExpenseConfigurationCurrent { categories: ExpenseCategories; activeRevision: number; activePolicy: PublishedExpensePolicy | null }
export interface PolicySummary { id: string; key: string; name: string; revision: number; publishedVersion: number; publishedDraftRevision: number; updatedBy: string; updatedAt: string }
export interface PolicyDirectory { items: PolicySummary[]; nextAfterKey: string | null }
export interface PolicyVersionSummary { version: number; draftRevision: number; categoryRevision: number; name: string; publishedBy: string; publishedAt: string; comment: string }
export interface CategoryVersionSummary { version: number; categoryCount: number; updatedBy: string; updatedAt: string; comment: string }
export interface PolicyActivation { revision: number; key: string; policyId: string; policyVersion: number; activatedBy: string; activatedAt: string; comment: string }
export interface ConfigurationHistory<T> { items: T[]; nextBeforeVersion: number | null }
export interface CategoryRevision { catalog: ExpenseCategories; updatedBy: string; updatedAt: string; comment: string }
export interface PolicyDraftRevision { policyId: string; revision: number; definition: ExpensePolicyDefinition; updatedBy: string; updatedAt: string; comment: string }
export interface CategoryInput { expectedVersion: number; categories: ExpenseCategory[]; comment: string }
export interface PolicyDraftInput { expectedRevision: number; definition: ExpensePolicyDefinition; comment: string }
export interface PolicyPublishInput { expectedDraftRevision: number; expectedCategoryRevision: number; expectedActiveRevision: number; comment: string }
type Identity = { tenantId: string; userId: string } | null
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const text = (value: unknown, max = 2000): value is string => typeof value === 'string' && !!value.trim() && value === value.trim() && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value)
const integer = (value: unknown, min = 0): value is number => Number.isSafeInteger(value) && Number(value) >= min
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value)
const time = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const key = (value: unknown): value is string => typeof value === 'string' && /^[a-z][a-z0-9-]{0,63}$/.test(value)
const unit = (value: unknown): value is ExpenseUnit => typeof value === 'string' && Object.prototype.hasOwnProperty.call(expenseUnits, value)
const currency = (value: unknown): value is string => typeof value === 'string' && /^[A-Z]{3}$/.test(value)
const day = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && time(value) && new Date(value).toISOString().slice(0, 10) === value
const reason = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value.length <= 2000
function requireValue(valid: unknown): asserts valid {
  if (!valid) throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '费用配置响应不完整或版本不符；保存结果未知时请恢复原操作。' }
}
function selectors(value: unknown, identities = false): string[] {
  requireValue(Array.isArray(value) && value.length <= 200 && value.every(item => identities ? uuid(item) : text(item, 64)) && new Set(value).size === value.length)
  return [...value].sort()
}
function readCategory(value: ExpenseCategory): ExpenseCategory {
  requireValue(object(value) && text(value.code, 64) && text(value.name, 128) && typeof value.active === 'boolean'
    && Array.isArray(value.units) && value.units.length > 0 && value.units.every(unit) && new Set(value.units).size === value.units.length)
  return { code: value.code, name: value.name, units: [...value.units], active: value.active,
    ...(value.priorControl == null ? {} : { priorControl: readPriorControl(value.priorControl) }) }
}

/** 同时核对租户和精确历史版本，畸形响应不能成为可编辑的零版。 */
export function readExpenseCategories(value: unknown, actor: Identity, version?: number): ExpenseCategories {
  const result = value as ExpenseCategories
  requireValue(actor && object(result) && result.tenantId === actor.tenantId && integer(result.version)
    && (version === undefined || result.version === version) && Array.isArray(result.categories) && result.categories.length <= 2000)
  const categories = result.categories.map(readCategory)
  requireValue(new Set(categories.map(item => item.code)).size === categories.length && (result.version !== 0 || !categories.length))
  return { tenantId: result.tenantId, version: result.version, categories }
}

/** 管理编辑与本人填报提示共用规则约束校验，防止两处接受不同的金额或单位。 */
export function readPolicyConstraints(value: unknown, expectedCurrency: string | null): PolicyConstraints {
  requireValue(object(value))
  const c = value as unknown as PolicyConstraints
  const constraints: PolicyConstraints = { effect: c.effect, unitPriceLimit: c.unitPriceLimit ?? null, limitUnit: c.limitUnit ?? null,
    invoiceMaxAgeDays: c.invoiceMaxAgeDays ?? null, invoiceAgeAction: c.invoiceAgeAction ?? null,
    allowedServiceLevels: selectors(c.allowedServiceLevels), priorRequestRequired: c.priorRequestRequired }
  requireValue(['ALLOW', 'DENY'].includes(c.effect) && typeof c.priorRequestRequired === 'boolean'
    && (constraints.unitPriceLimit === null) === (constraints.limitUnit === null)
    && (constraints.invoiceMaxAgeDays === null) === (constraints.invoiceAgeAction === null))
  if (constraints.unitPriceLimit !== null) {
    const amount = constraints.unitPriceLimit
    requireValue(object(amount) && typeof amount.value === 'string' && /^\d+(?:\.\d+)?$/.test(amount.value)
      && currency(amount.currency) && amount.currency === expectedCurrency && unit(constraints.limitUnit))
    constraints.unitPriceLimit = { value: amount.value, currency: amount.currency }
  }
  if (constraints.invoiceMaxAgeDays !== null) requireValue(integer(constraints.invoiceMaxAgeDays) && constraints.invoiceMaxAgeDays <= 36600
    && ['REJECT', 'REQUIRE_REASON'].includes(constraints.invoiceAgeAction!))
  if (constraints.effect === 'DENY') requireValue(!constraints.unitPriceLimit && constraints.invoiceMaxAgeDays === null
    && !constraints.allowedServiceLevels.length && !constraints.priorRequestRequired)
  if (c.fixedAllowance != null) {
    const fixed = c.fixedAllowance
    requireValue(object(fixed) && object(fixed.dailyRate) && fixed.dayCountBasis === 'CALENDAR_DAYS_INCLUSIVE'
      && fixed.dailyRate.currency === expectedCurrency && currency(fixed.dailyRate.currency)
      && amountMinor(fixed.dailyRate.value) > 0n && constraints.effect === 'ALLOW' && !constraints.unitPriceLimit
      && constraints.invoiceMaxAgeDays === null && !constraints.allowedServiceLevels.length)
    constraints.fixedAllowance = { dailyRate: { ...fixed.dailyRate }, dayCountBasis: fixed.dayCountBasis }
  }
  return constraints
}

/** 集合条件按领域规则排序，金额始终保留十进制字符串。 */
export function readPolicyDefinition(value: unknown): ExpensePolicyDefinition {
  const definition = value as ExpensePolicyDefinition
  requireValue(object(definition) && text(definition.name, 128) && Array.isArray(definition.rules) && definition.rules.length <= 200)
  const rules = definition.rules.map(rule => {
    requireValue(object(rule) && text(rule.key, 64) && text(rule.name, 128) && object(rule.match) && object(rule.constraints))
    const match: PolicyMatch = { legalEntityIds: selectors(rule.match.legalEntityIds, true), categoryCodes: selectors(rule.match.categoryCodes),
      cityTiers: selectors(rule.match.cityTiers), employeeGrades: selectors(rule.match.employeeGrades), fromDate: rule.match.fromDate ?? null,
      throughDate: rule.match.throughDate ?? null, currency: rule.match.currency ?? null }
    requireValue((match.fromDate === null || day(match.fromDate)) && (match.throughDate === null || day(match.throughDate))
      && (!match.fromDate || !match.throughDate || match.fromDate <= match.throughDate) && (match.currency === null || currency(match.currency)))
    const constraints = readPolicyConstraints(rule.constraints, match.currency)
    if (constraints.fixedAllowance) requireValue(match.categoryCodes.length > 0)
    return { key: rule.key, name: rule.name, match, constraints }
  })
  requireValue(new Set(rules.map(rule => rule.key)).size === rules.length && new Set(rules.map(rule => JSON.stringify(rule.match))).size === rules.length)
  return { name: definition.name, rules }
}
function checkDraftCursor(value: { revision: number; publishedVersion: number; publishedDraftRevision: number }) {
  requireValue(integer(value.revision, 1) && integer(value.publishedVersion) && integer(value.publishedDraftRevision)
    && value.publishedVersion <= value.publishedDraftRevision && value.publishedDraftRevision <= value.revision
    && (value.publishedVersion === 0) === (value.publishedDraftRevision === 0))
}
/** 草稿读取绑定业务键；当前发布游标与内容修订分别保留。 */
export function readPolicyDraft(value: unknown, actor: Identity, policyKey: string): ExpensePolicyDraft {
  const result = value as ExpensePolicyDraft
  requireValue(actor && object(result) && result.tenantId === actor.tenantId && uuid(result.id) && key(result.key) && result.key === policyKey)
  checkDraftCursor(result)
  return { ...result, definition: readPolicyDefinition(result.definition) }
}
/** 已发布正文保留发布人和三种版本，不能以当前草稿补写。 */
export function readPublishedPolicy(value: unknown, actor: Identity, policyKey?: string, version?: number): PublishedExpensePolicy {
  const result = value as PublishedExpensePolicy
  requireValue(actor && object(result) && result.tenantId === actor.tenantId && uuid(result.policyId) && key(result.key)
    && (policyKey === undefined || result.key === policyKey) && integer(result.version, 1) && (version === undefined || result.version === version)
    && integer(result.draftRevision, result.version) && integer(result.categoryRevision, 1) && text(result.publishedBy) && time(result.publishedAt) && reason(result.comment))
  const definition = readPolicyDefinition(result.definition); requireValue(definition.rules.length)
  return { ...result, definition }
}
/** 未配置时明确返回空制度；类别修订可新于已发布正文的原类别版本。 */
export function readExpenseConfiguration(value: unknown, actor: Identity): ExpenseConfigurationCurrent {
  const result = value as ExpenseConfigurationCurrent
  requireValue(object(result) && integer(result.activeRevision) && (result.activePolicy === null || object(result.activePolicy)))
  const categories = readExpenseCategories(result.categories, actor)
  const activePolicy = result.activePolicy === null ? null : readPublishedPolicy(result.activePolicy, actor)
  requireValue((result.activeRevision === 0) === (activePolicy === null)
    && (!activePolicy || categories.version >= activePolicy.categoryRevision && result.activeRevision >= activePolicy.version))
  return { categories, activeRevision: result.activeRevision, activePolicy }
}
/** 目录游标只接受本页最后一个有序业务键。 */
export function readPolicyDirectory(value: unknown, afterKey?: string): PolicyDirectory {
  const page = value as PolicyDirectory
  requireValue(object(page) && Array.isArray(page.items) && page.items.length <= 25 && (page.nextAfterKey === null || key(page.nextAfterKey)))
  let previous = afterKey ?? ''
  for (const item of page.items) {
    requireValue(object(item) && uuid(item.id) && key(item.key) && item.key > previous && text(item.name, 128) && text(item.updatedBy) && time(item.updatedAt))
    checkDraftCursor(item); previous = item.key
  }
  requireValue(page.nextAfterKey === null || page.items.length > 0 && page.nextAfterKey === previous)
  return page
}
function history<T>(value: unknown, before: number | undefined, sequence: (item: T) => number): ConfigurationHistory<T> {
  const page = value as ConfigurationHistory<T>
  requireValue(object(page) && Array.isArray(page.items) && page.items.length <= 25 && (page.nextBeforeVersion === null || integer(page.nextBeforeVersion, 1)))
  let previous = before ?? Number.MAX_SAFE_INTEGER
  for (const item of page.items) { const version = sequence(item); requireValue(integer(version, 1) && version < previous); previous = version }
  requireValue(page.nextBeforeVersion === null || page.items.length > 0 && page.nextBeforeVersion === previous)
  return page
}
function audit(value: { updatedBy: string; updatedAt: string; comment: string }) {
  requireValue(object(value) && text(value.updatedBy) && time(value.updatedAt) && reason(value.comment))
}
/** 版本列表拒绝倒序破坏和错误游标，翻页失败时不前移边界。 */
export function readCategoryHistory(value: unknown, before?: number): ConfigurationHistory<CategoryVersionSummary> {
  return history(value, before, (item: CategoryVersionSummary) => { audit(item); requireValue(integer(item.categoryCount) && item.categoryCount <= 2000); return item.version })
}
export function readPolicyHistory(value: unknown, before?: number): ConfigurationHistory<PolicyVersionSummary> {
  return history(value, before, (item: PolicyVersionSummary) => {
    requireValue(object(item) && integer(item.draftRevision, item.version) && integer(item.categoryRevision, 1) && text(item.name, 128)
      && text(item.publishedBy) && time(item.publishedAt) && reason(item.comment)); return item.version
  })
}
export function readActivationHistory(value: unknown, before?: number): ConfigurationHistory<PolicyActivation> {
  return history(value, before, (item: PolicyActivation) => {
    requireValue(object(item) && key(item.key) && uuid(item.policyId) && integer(item.policyVersion, 1)
      && text(item.activatedBy) && time(item.activatedAt) && reason(item.comment)); return item.revision
  })
}
export function readCategoryRevision(value: unknown, actor: Identity, version: number): CategoryRevision {
  const result = value as CategoryRevision; audit(result)
  return { ...result, catalog: readExpenseCategories(result.catalog, actor, version) }
}
export function readPolicyDraftRevision(value: unknown, policyId: string, version: number): PolicyDraftRevision {
  const result = value as PolicyDraftRevision; audit(result)
  requireValue(result.policyId === policyId && result.revision === version)
  return { ...result, definition: readPolicyDefinition(result.definition) }
}
function decimal(value: string) { return value.replace(/^0+(?=\d)/, '').replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '') }
/** 用规范化定义比较保存回执，不把服务端金额补零或集合排序误判为内容更改。 */
export function policyFingerprint(value: ExpensePolicyDefinition): string {
  const definition = readPolicyDefinition(value)
  for (const rule of definition.rules) {
    if (rule.constraints.unitPriceLimit) rule.constraints.unitPriceLimit.value = decimal(rule.constraints.unitPriceLimit.value)
    if (rule.constraints.fixedAllowance) rule.constraints.fixedAllowance.dailyRate.value = decimal(rule.constraints.fixedAllowance.dailyRate.value)
  }
  return JSON.stringify(definition)
}
/** 原请求恢复也在清除幂等槽之前验证成功回执。 */
export function validateConfigurationReceipt(value: unknown, path: string, body: string, actor: Identity) {
  if (path === '/admin/expense-categories') {
    const input = JSON.parse(body) as CategoryInput, result = readExpenseCategories(value, actor, input.expectedVersion + 1)
    requireValue(JSON.stringify(result.categories) === JSON.stringify(input.categories.map(readCategory))); return result
  }
  const match = /^\/admin\/expense-policies\/([^/?]+)\/(draft|publish)$/.exec(path)
  requireValue(match); const policyKey = decodeURIComponent(match[1]!)
  if (match[2] === 'draft') {
    const input = JSON.parse(body) as PolicyDraftInput, result = readPolicyDraft(value, actor, policyKey)
    requireValue(result.revision === input.expectedRevision + 1 && policyFingerprint(result.definition) === policyFingerprint(input.definition)); return result
  }
  const input = JSON.parse(body) as PolicyPublishInput, result = readExpenseConfiguration(value, actor), policy = result.activePolicy
  requireValue(policy && policy.key === policyKey && policy.publishedBy === actor?.userId && policy.comment === input.comment.trim()
    && policy.draftRevision === input.expectedDraftRevision && policy.categoryRevision === input.expectedCategoryRevision
    && result.categories.version === input.expectedCategoryRevision && result.activeRevision === input.expectedActiveRevision + 1)
  return result
}

/** 将预期业务冲突定位到管理员可以处理的编辑步骤。 */
export function configurationError(error: unknown): string {
  const value = error as { code?: string; status?: number; message?: string }
  const labels: Record<string, string> = {
    CONCURRENCY_CONFLICT: '版本已变化，请重新读取并核对；本地修改已保留，不会自动覆盖新版本。',
    EXPENSE_CONFIGURATION_UNCHANGED: '没有可保存的新内容；同一草稿修订不能重复发布。',
    EXPENSE_CATEGORY_REMOVAL_FORBIDDEN: '已有类别只能停用，不能删除或更改代码。',
    EXPENSE_POLICY_INCOMPLETE: '请先保存类别目录，并为制度添加至少一条完整规则。',
    EXPENSE_POLICY_CATEGORY_UNAVAILABLE: '制度引用了未启用的类别，请核对类别和规则后重新发布。',
    ALLOWANCE_CATEGORY_UNIT_REQUIRED: '定额补贴引用的类别只能使用“天”单位，请调整类别后重新发布。',
    INVALID_ALLOWANCE_RULE: '请填写大于零的每日补贴金额，并明确按自然日含起止日计算。',
    INVALID_EXPENSE_POLICY_DEFINITION: '规则条件或约束不完整，请核对币种、单位、日期和重复条件。',
    INVALID_EXPENSE_CATEGORIES: '请核对类别代码、名称和至少一个允许单位。',
    INVALID_EXPENSE_CONFIGURATION_REQUEST: '输入不完整或格式不正确，请核对后重试。'
  }
  if (value?.status === 403) return '当前账号没有财务配置权限，请重新核对身份。'
  if (value?.code && Object.prototype.hasOwnProperty.call(labels, value.code)) return labels[value.code]!
  return value?.message ?? '费用配置读取或保存失败，请重试。'
}
