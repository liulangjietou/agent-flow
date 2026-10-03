/** 科目配置仅供财务配置管理员管理，发布表示固定意图，ERP 仍校验科目事实。 */
export const mappingRoles = { EXPENSE: '费用科目', DEDUCTIBLE_TAX: '进项税', EMPLOYEE_RECEIVABLE: '员工应收', EMPLOYEE_PAYABLE: '员工应付', BANK: '付款银行' } as const
export type MappingRole = keyof typeof mappingRoles
export interface MappingScope { legalEntityId: string; currency: string }
export interface MappingEntry { key: { role: MappingRole; selector: string }; accountCode: string }
export interface MappingDefinition extends MappingScope { name: string; entries: MappingEntry[] }
export interface MappingDraft { id: string; tenantId: string; key: string; revision: number; definition: MappingDefinition; publishedVersion: number; publishedDraftRevision: number }
export interface PublishedMapping { mappingId: string; tenantId: string; key: string; version: number; draftRevision: number; categoryRevision: number; definition: MappingDefinition; targetDigest: string; publishedBy: string; publishedAt: string; comment: string }
export interface MappingCurrent extends MappingScope { categoryRevision: number; activeRevision: number; activeMapping: PublishedMapping | null }
export interface MappingSummary extends MappingScope { id: string; key: string; name: string; revision: number; publishedVersion: number; publishedDraftRevision: number; updatedBy: string; updatedAt: string }
export interface MappingDirectory { items: MappingSummary[]; nextAfterKey: string | null }
export interface MappingVersion { version: number; draftRevision: number; categoryRevision: number; name: string; targetDigest: string; publishedBy: string; publishedAt: string; comment: string }
export interface MappingActivation { revision: number; key: string; mappingId: string; mappingVersion: number; activatedBy: string; activatedAt: string; comment: string }
export interface MappingHistory<T> { items: T[]; nextBeforeVersion: number | null }
export interface MappingDraftRevision { mappingId: string; revision: number; definition: MappingDefinition; updatedBy: string; updatedAt: string; comment: string }
export interface MappingDraftInput { expectedRevision: number; definition: MappingDefinition; comment: string }
export interface MappingPublishInput { expectedDraftRevision: number; expectedCategoryRevision: number; expectedActiveRevision: number; comment: string }
type Identity = { tenantId: string; userId: string } | null
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const integer = (v: unknown, min = 0): v is number => Number.isSafeInteger(v) && Number(v) >= min
const text = (v: unknown, max = 128): v is string => typeof v === 'string' && !!v.trim() && v === v.trim() && v.length <= max && !/[\u0000-\u001f\u007f-\u009f]/u.test(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v)
const key = (v: unknown): v is string => typeof v === 'string' && /^[a-z][a-z0-9-]{0,63}$/.test(v)
const digest = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const time = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const reason = (v: unknown): v is string => typeof v === 'string' && !!v.trim() && v.length <= 2000
const order = (a: string, b: string) => a < b ? -1 : a > b ? 1 : 0
function requireValue(valid: unknown): asserts valid { if (!valid) throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '科目配置响应不完整或范围、版本不符；保存结果未知时请恢复原操作。' } }
function scope(value: MappingScope) { requireValue(object(value) && uuid(value.legalEntityId) && typeof value.currency === 'string' && /^[A-Z]{3}$/.test(value.currency)) }
function sameScope(value: MappingScope, expected: MappingScope) { requireValue(value.legalEntityId === expected.legalEntityId && value.currency === expected.currency) }
function cursors(value: { revision: number; publishedVersion: number; publishedDraftRevision: number }) {
  requireValue(integer(value.revision, 1) && integer(value.publishedVersion) && integer(value.publishedDraftRevision)
    && value.publishedVersion <= value.publishedDraftRevision && value.publishedDraftRevision <= value.revision && (value.publishedVersion === 0) === (value.publishedDraftRevision === 0))
}
/** 顺序与服务端相同；同用途及选择器只能配置一个明确科目。 */
export function readMappingDefinition(value: unknown): MappingDefinition {
  const v = value as MappingDefinition; scope(v)
  requireValue(text(v.name) && Array.isArray(v.entries) && v.entries.length <= 4096)
  const seen = new Set<string>(), entries = v.entries.map(entry => {
    requireValue(object(entry) && object(entry.key) && typeof entry.key.role === 'string' && Object.prototype.hasOwnProperty.call(mappingRoles, entry.key.role) && text(entry.accountCode))
    const needsSelector = entry.key.role === 'EXPENSE' || entry.key.role === 'BANK'
    requireValue(needsSelector ? text(entry.key.selector) : entry.key.selector === '')
    const identity = JSON.stringify([entry.key.role, entry.key.selector]); requireValue(!seen.has(identity)); seen.add(identity)
    return { key: { role: entry.key.role, selector: entry.key.selector }, accountCode: entry.accountCode }
  }).sort((a, b) => order(a.key.role, b.key.role) || order(a.key.selector, b.key.selector))
  return { name: v.name, legalEntityId: v.legalEntityId, currency: v.currency, entries }
}
export function readMappingDraft(value: unknown, actor: Identity, expectedKey: string): MappingDraft {
  const v = value as MappingDraft
  requireValue(object(v) && actor && v.tenantId === actor.tenantId && uuid(v.id) && key(v.key) && v.key === expectedKey); cursors(v)
  return { ...v, definition: readMappingDefinition(v.definition) }
}
export function readPublishedMapping(value: unknown, actor: Identity, expectedKey?: string, version?: number): PublishedMapping {
  const v = value as PublishedMapping
  requireValue(object(v) && actor && v.tenantId === actor.tenantId && uuid(v.mappingId) && key(v.key) && (expectedKey === undefined || v.key === expectedKey)
    && integer(v.version, 1) && (version === undefined || v.version === version) && integer(v.draftRevision, v.version) && integer(v.categoryRevision)
    && digest(v.targetDigest) && text(v.publishedBy) && time(v.publishedAt) && reason(v.comment))
  const definition = readMappingDefinition(v.definition)
  requireValue(definition.entries.length > 0 && (v.categoryRevision > 0 || definition.entries.every(entry => entry.key.role !== 'EXPENSE')))
  return { ...v, definition }
}
export function readMappingCurrent(value: unknown, actor: Identity, expected?: MappingScope): MappingCurrent {
  const v = value as MappingCurrent; scope(v); if (expected) sameScope(v, expected)
  requireValue(integer(v.categoryRevision) && integer(v.activeRevision) && (v.activeRevision === 0 ? v.activeMapping === null : object(v.activeMapping)))
  const activeMapping = v.activeMapping === null ? null : readPublishedMapping(v.activeMapping, actor)
  if (activeMapping) { sameScope(activeMapping.definition, v); requireValue(activeMapping.categoryRevision <= v.categoryRevision) }
  return { ...v, activeMapping }
}
export function readMappingDirectory(value: unknown, filters: Partial<MappingScope>, after?: string): MappingDirectory {
  const v = value as MappingDirectory
  requireValue(object(v) && Array.isArray(v.items) && v.items.length <= 25)
  let last = after ?? ''; const ids = new Set<string>()
  for (const item of v.items) {
    scope(item); cursors(item)
    requireValue(uuid(item.id) && !ids.has(item.id) && key(item.key) && item.key > last && text(item.name) && text(item.updatedBy) && time(item.updatedAt)
      && (!filters.legalEntityId || item.legalEntityId === filters.legalEntityId) && (!filters.currency || item.currency === filters.currency))
    ids.add(item.id); last = item.key
  }
  requireValue(v.nextAfterKey === null || v.items.length === 25 && v.nextAfterKey === last)
  return v
}
function history<T>(value: unknown, before: number | undefined, check: (item: T) => number): MappingHistory<T> {
  const v = value as MappingHistory<T>; requireValue(object(v) && Array.isArray(v.items) && v.items.length <= 25)
  let last = before ?? Number.MAX_SAFE_INTEGER
  for (const item of v.items) { const version = check(item); requireValue(integer(version, 1) && version < last); last = version }
  requireValue(v.nextBeforeVersion === null || v.items.length === 25 && v.nextBeforeVersion === last); return v
}
export function readMappingVersions(value: unknown, before?: number): MappingHistory<MappingVersion> {
  return history(value, before, (v: MappingVersion) => {
    requireValue(object(v) && integer(v.draftRevision, v.version) && integer(v.categoryRevision) && text(v.name) && digest(v.targetDigest) && text(v.publishedBy) && time(v.publishedAt) && reason(v.comment)); return v.version
  })
}
export function readMappingActivations(value: unknown, before?: number): MappingHistory<MappingActivation> {
  return history(value, before, (v: MappingActivation) => {
    requireValue(object(v) && key(v.key) && uuid(v.mappingId) && integer(v.mappingVersion, 1) && text(v.activatedBy) && time(v.activatedAt) && reason(v.comment)); return v.revision
  })
}
export function readMappingDraftRevision(value: unknown, mappingId: string, revision: number): MappingDraftRevision {
  const v = value as MappingDraftRevision
  requireValue(object(v) && v.mappingId === mappingId && v.revision === revision && text(v.updatedBy) && time(v.updatedAt) && reason(v.comment))
  return { ...v, definition: readMappingDefinition(v.definition) }
}
export function mappingFingerprint(value: MappingDefinition) { return JSON.stringify(readMappingDefinition(value)) }
/** 先验证回执再释放原幂等槽，恢复不能接受别租户、别范围或改变正文的成功响应。 */
export function validateMappingReceipt(value: unknown, path: string, body: string, actor: Identity) {
  const match = /^\/admin\/account-mappings\/([^/?]+)\/(draft|publish)$/.exec(path); requireValue(match)
  const expectedKey = decodeURIComponent(match[1]!)
  if (match[2] === 'draft') {
    const input = JSON.parse(body) as MappingDraftInput, result = readMappingDraft(value, actor, expectedKey)
    requireValue(result.revision === input.expectedRevision + 1 && mappingFingerprint(result.definition) === mappingFingerprint(input.definition)); return result
  }
  const input = JSON.parse(body) as MappingPublishInput, result = readMappingCurrent(value, actor), published = result.activeMapping
  requireValue(published && published.key === expectedKey && published.publishedBy === actor?.userId && published.comment === input.comment.trim()
    && published.draftRevision === input.expectedDraftRevision && published.categoryRevision === input.expectedCategoryRevision
    && result.categoryRevision === input.expectedCategoryRevision && result.activeRevision === input.expectedActiveRevision + 1)
  return result
}
/** 发布正文还须符合原草稿修订；只确认键和版本不足以确认原法人、币种及全部科目。 */
export function validateMappingPublicationHistory(current: MappingCurrent, history: unknown, revision: number) {
  requireValue(current.activeMapping)
  const original = readMappingDraftRevision(history, current.activeMapping.mappingId, revision)
  requireValue(mappingFingerprint(original.definition) === mappingFingerprint(current.activeMapping.definition)); return current
}
export function mappingError(cause: unknown): string {
  const v = cause as { status?: number; code?: string; message?: string }, errors: Record<string, string> = {
    CONCURRENCY_CONFLICT: '配置版本已变化，请刷新并核对。本地修改保留原基线，不会覆盖新版本。',
    ACCOUNT_MAPPING_UNCHANGED: '此草稿没有新的修改，或当前修订已经发布。',
    ACCOUNT_MAPPING_SCOPE_IMMUTABLE: '已有配置的法人和币种不能改变，请为新范围创建配置。',
    ACCOUNT_MAPPING_NOT_PUBLISHABLE: '请配置至少一个科目，费用用途只能使用启用的费用类别。',
    INVALID_ACCOUNT_MAPPING_DEFINITION: '请核对名称、法人、币种、科目代码以及重复的用途和选择器。',
    ACCOUNT_MAPPING_CONFIGURATION_INCONSISTENT: '保存的版本证据不一致，请先核对配置记录。',
    FINANCE_GATEWAY_UNAVAILABLE: '尚未配置可用的企业会计服务，草稿可保留，配置就绪后再发布。'
  }
  if (v?.status === 403) return '当前账号没有科目配置权限，请核对登录身份。'
  return v?.code && Object.prototype.hasOwnProperty.call(errors, v.code) ? errors[v.code]! : v?.message ?? '科目配置读取或保存失败，请重试。'
}
