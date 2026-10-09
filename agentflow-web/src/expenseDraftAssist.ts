import type { AssistReference, AssistSource } from './assistRuns.js'
import { expenseTypes, type ExpenseContent, type ExpenseDetail, type ExpenseLine } from './expenses.js'
import { expenseUnits, newExpenseLine, type FinanceCatalog } from './expenseDraft.js'

export type ExpenseAssistStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CONFIRMED' | 'DISMISSED'
export type ExpenseAssistPart = 'ITINERARY' | 'CATEGORY' | 'ALLOCATION'
export interface ExpenseAssistLeg { id: number; startsOn: string; endsOn: string; cityCode: string; purpose: string }
export interface ExpenseAssistRequest {
  applicationVersion: number; financialVersion: number; brief: string; itinerary: ExpenseAssistLeg[]
  catalog: { categoryCodes: string[]; costCenterCodes: string[]; projectCodes: string[] }
}
export interface ExpenseAssistInput {
  reportId: string; applicationId: string; applicationVersion: number; financialVersion: number; legalEntityId: string
  reportType: ExpenseContent['type']; catalogVersion: string; validUntil: string; financeTargetDigest: string
  itinerary: ExpenseAssistLeg[]; sources: AssistSource[]
  options: { categories: FinanceCatalog['categories']; costCenters: Choice[]; projects: Choice[]; cities: Choice[] }
}
interface Choice { code: string; name: string }
export interface ExpenseAssistPreview { input: ExpenseAssistInput; providerId: string; model: string; destination: string; targetDigest: string; consentDigest: string }
export interface ExpenseAssistGenerate { input: ExpenseAssistRequest; validUntil: string; targetDigest: string; consentDigest: string; handlingTaskId?: string }
export interface ExpenseAssistSelection { proposalId: string; parts: ExpenseAssistPart[] }
export interface ExpenseAssistConfirm { expectedRunVersion: number; applicationVersion: number; financialVersion: number; selected: ExpenseAssistSelection[]; comment?: string }
export interface ExpenseAssistDismiss { expectedRunVersion: number; comment?: string }
export interface ExpenseAssistReceipt { id: string; status: ExpenseAssistStatus; version: number }
export interface ExpenseAssistSummary extends ExpenseAssistReceipt { applicationVersion: number; financialVersion: number; createdAt: string }
export interface ExpenseAssistPage { items: ExpenseAssistSummary[]; total: number; page: number; pageSize: number }
export interface ExpenseAssistLine {
  id: string; itineraryId: number; categoryCode: string; unit: ExpenseLine['unit']; description: string
  allocations: Array<{ costCenter: string; projectCode?: string | null; percent: number }>; evidence: AssistReference[]
}
export interface ExpenseAssistDetail extends ExpenseAssistReceipt {
  input: ExpenseAssistInput; createdAt: string; startedAt: string | null; completedAt: string | null
  suggestion: { providerId: string; modelVersion: string; promptVersion: string; lines: ExpenseAssistLine[] } | null
  failure: keyof typeof expenseAssistFailures | null
  review: { actor: string; at: string; selected: ExpenseAssistSelection[]; comment?: string | null } | null
  canConfirm: boolean; unavailableCode: string | null
}
export const expenseAssistPath = (reportId: string) => '/expense-reports/' + encodeURIComponent(reportId) + '/draft-assists'
export const expenseAssistStatuses: Record<ExpenseAssistStatus, string> = { QUEUED: '等待生成', RUNNING: '正在生成', COMPLETED: '待逐项确认', FAILED: '生成未完成', CONFIRMED: '已确认，尚需填入草稿', DISMISSED: '已放弃' }
export const expenseAssistFailures = { MODEL_UNAVAILABLE: '模型服务不可用，请检查配置后明确发起新请求。', MODEL_TIMEOUT: '生成已超时，系统没有自动重新发送。', INVALID_MODEL_OUTPUT: '建议未通过内容与来源校验。', INPUT_UNAVAILABLE: '发送依据已变化，本次生成未完成。' }
const versions: Record<ExpenseAssistStatus, number> = { QUEUED: 1, RUNNING: 2, COMPLETED: 3, FAILED: 3, CONFIRMED: 4, DISMISSED: 4 }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const own = (v: object, key: string) => Object.prototype.hasOwnProperty.call(v, key)
const exact = (v: unknown, required: string[], optional: string[] = []): v is Record<string, unknown> => object(v)
  && required.every(k => own(v, k)) && Object.keys(v).every(k => required.includes(k) || optional.includes(k))
const text = (v: unknown, max = 128): v is string => typeof v === 'string' && !!v.trim() && v.length <= max
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) > 0
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v)
const digest = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const date = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v)
  && Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 10) === v
const time = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v))
// 服务端有效期可能精确到微秒；历史确认不能因浏览器毫秒截断而被误判为到期。
const instant = (v: string) => BigInt(Date.parse(v.replace(/\.\d+Z$/, 'Z'))) * 1_000_000n + BigInt((/\.(\d+)Z$/.exec(v)?.[1] ?? '').padEnd(9, '0'))
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '填报建议响应不完整，请刷新原记录；写入结果未知时先恢复原操作。' })
function same(a: unknown, b: unknown): boolean {
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => same(v, b[i]))
  if (object(a) && object(b)) return same(Object.keys(a).sort(), Object.keys(b).sort()) && Object.keys(a).every(k => same(a[k], b[k]))
  return a === b
}
function choices(v: unknown, max: number, minimum = 0): v is Choice[] {
  return Array.isArray(v) && v.length >= minimum && v.length <= max && v.every(c => exact(c, ['code', 'name']) && text(c.code) && text(c.name))
    && new Set(v.map(c => c.code)).size === v.length
}
function leg(v: unknown): v is ExpenseAssistLeg {
  return exact(v, ['id', 'startsOn', 'endsOn', 'cityCode', 'purpose']) && positive(v.id) && v.id <= 20
    && date(v.startsOn) && date(v.endsOn) && v.endsOn >= v.startsOn && text(v.cityCode) && text(v.purpose, 2000)
}
/** 来源正文、引用摘要和目录投影一起核对，损坏响应不能成为发送清单。 */
async function input(value: unknown, reportId: string): Promise<ExpenseAssistInput> {
  if (!exact(value, ['reportId', 'applicationId', 'applicationVersion', 'financialVersion', 'legalEntityId', 'reportType', 'catalogVersion', 'validUntil', 'financeTargetDigest', 'itinerary', 'options', 'sources'])
      || value.reportId !== reportId || !uuid(value.reportId) || !uuid(value.applicationId) || !positive(value.applicationVersion)
      || !positive(value.financialVersion) || !uuid(value.legalEntityId) || !own(expenseTypes, String(value.reportType))
      || !text(value.catalogVersion) || !time(value.validUntil) || !digest(value.financeTargetDigest)
      || !Array.isArray(value.itinerary) || !value.itinerary.length || value.itinerary.length > 20 || !value.itinerary.every(leg)
      || new Set(value.itinerary.map(v => v.id)).size !== value.itinerary.length
      || !exact(value.options, ['categories', 'costCenters', 'projects', 'cities'])) throw unreadable()
  const o = value.options
  if (!Array.isArray(o.categories) || !o.categories.length || o.categories.length > 50
      || !o.categories.every(c => exact(c, ['code', 'name', 'units']) && text(c.code) && text(c.name) && Array.isArray(c.units)
        && !!c.units.length && c.units.length <= 5 && c.units.every(u => typeof u === 'string' && own(expenseUnits, u)) && new Set(c.units).size === c.units.length)
      || new Set(o.categories.map(c => c.code)).size !== o.categories.length || !choices(o.costCenters, 50, 1)
      || !choices(o.projects, 50) || !choices(o.cities, 20, 1)
      || value.itinerary.some(l => !(o.cities as Choice[]).some(c => c.code === l.cityCode))) throw unreadable()
  const expected = new Map<string, unknown>([['expense:catalog', { reportType: value.reportType, catalogVersion: value.catalogVersion, options: o }]])
  value.itinerary.forEach(l => expected.set(`expense:itinerary[${l.id}]`, l))
  if (!Array.isArray(value.sources) || value.sources.length !== expected.size + 1) throw unreadable()
  const seen = new Set<string>(); let bytes = 0
  for (const source of value.sources) {
    if (!exact(source, ['reference', 'label', 'content']) || !text(source.label, 256) || typeof source.content !== 'string'
        || !exact(source.reference, ['sourceId', 'contentDigest']) || typeof source.reference.sourceId !== 'string'
        || !digest(source.reference.contentDigest) || seen.has(source.reference.sourceId)) throw unreadable()
    const id = source.reference.sourceId, encoded = new TextEncoder().encode(source.content); bytes += encoded.length; seen.add(id)
    if (bytes > 65_536) throw unreadable()
    let parsed: unknown
    try { parsed = JSON.parse(source.content) } catch { throw unreadable() }
    if (id === 'expense:brief' ? !text(parsed, 8000) : !expected.has(id) || !same(expected.get(id), parsed)) throw unreadable()
    const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', encoded)), byte => byte.toString(16).padStart(2, '0')).join('')
    if (hash !== source.reference.contentDigest) throw unreadable()
  }
  if (!seen.has('expense:brief')) throw unreadable()
  return value as unknown as ExpenseAssistInput
}
/** 预览必须对应本次填写的行程、双版本和目录选择，不能接受另一请求的晚到内容。 */
export async function readExpenseAssistPreview(value: unknown, reportId: string, request: ExpenseAssistRequest): Promise<ExpenseAssistPreview> {
  if (!exact(value, ['input', 'providerId', 'model', 'destination', 'targetDigest', 'consentDigest']) || !text(value.providerId)
      || !text(value.model) || !text(value.destination, 512) || !digest(value.targetDigest) || !digest(value.consentDigest)) throw unreadable()
  const i = await input(value.input, reportId)
  if (i.applicationVersion !== request.applicationVersion || i.financialVersion !== request.financialVersion || !same(i.itinerary, request.itinerary)
      || JSON.parse(i.sources.find(s => s.reference.sourceId === 'expense:brief')!.content) !== request.brief
      || !same(i.options.categories.map(c => c.code), request.catalog.categoryCodes)
      || !same(i.options.costCenters.map(c => c.code), request.catalog.costCenterCodes)
      || !same(i.options.projects.map(c => c.code), request.catalog.projectCodes)) throw unreadable()
  return value as unknown as ExpenseAssistPreview
}
function receipt(v: unknown): v is ExpenseAssistReceipt {
  return object(v) && uuid(v.id) && typeof v.status === 'string' && own(versions, v.status) && versions[v.status as ExpenseAssistStatus] === v.version
}
/** 页码绑定原读取，只提供轻量历史；实际填入仍需读取完整本人记录。 */
export function readExpenseAssistPage(value: unknown, page: number): ExpenseAssistPage {
  if (!exact(value, ['items', 'total', 'page', 'pageSize']) || value.page !== page || value.pageSize !== 20
      || !Array.isArray(value.items) || value.items.length > 20 || !value.items.every(v => exact(v, ['id', 'applicationVersion', 'financialVersion', 'status', 'version', 'createdAt'])
        && receipt(v) && positive(v.applicationVersion) && positive(v.financialVersion) && time(v.createdAt))
      || new Set(value.items.map(v => v.id)).size !== value.items.length || !Number.isSafeInteger(value.total)
      || (value.total as number) < page * 20 + value.items.length && value.items.length > 0 || (value.total as number) < 0) throw unreadable()
  return value as unknown as ExpenseAssistPage
}
function suggestion(value: unknown, source: ExpenseAssistInput): value is NonNullable<ExpenseAssistDetail['suggestion']> {
  if (!exact(value, ['providerId', 'modelVersion', 'promptVersion', 'lines']) || !text(value.providerId) || !text(value.modelVersion)
      || value.promptVersion !== 'expense-draft-assist-v1' || !Array.isArray(value.lines) || value.lines.length > 50) return false
  const ids = new Set<string>()
  for (const l of value.lines) {
    if (!exact(l, ['id', 'itineraryId', 'categoryCode', 'unit', 'description', 'allocations', 'evidence']) || typeof l.id !== 'string'
        || !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(l.id) || ids.has(l.id) || !text(l.categoryCode, 64) || !text(l.description, 2000)
        || !source.itinerary.some(i => i.id === l.itineraryId) || !source.options.categories.some(c => c.code === l.categoryCode && c.units.includes(l.unit as ExpenseLine['unit']))
        || !Array.isArray(l.allocations) || !l.allocations.length || l.allocations.length > 50 || !Array.isArray(l.evidence) || !l.evidence.length || l.evidence.length > 64) return false
    ids.add(l.id); const allocations = new Set<string>(); let hundredths = 0
    for (const a of l.allocations) {
      if (!exact(a, ['costCenter', 'percent'], ['projectCode']) || !source.options.costCenters.some(c => c.code === a.costCenter)
          || a.projectCode != null && !source.options.projects.some(p => p.code === a.projectCode) || typeof a.percent !== 'number'
          || !/^(?:[0-9]+)(?:\.[0-9]{1,2})?$/.test(String(a.percent)) || a.percent <= 0 || a.percent > 100) return false
      const key = JSON.stringify([a.costCenter, a.projectCode ?? null]); if (allocations.has(key)) return false
      allocations.add(key); hundredths += Math.round(a.percent * 100)
    }
    if (hundredths !== 10000 || new Set(l.evidence.map(r => object(r) ? r.sourceId : null)).size !== l.evidence.length
        || !l.evidence.every(r => exact(r, ['sourceId', 'contentDigest']) && source.sources.some(s => same(s.reference, r)))
        || !l.evidence.some(r => r.sourceId === 'expense:catalog') || !l.evidence.some(r => r.sourceId === `expense:itinerary[${l.itineraryId}]`)) return false
  }
  return true
}
function selections(value: unknown, lines: ExpenseAssistLine[]): value is ExpenseAssistSelection[] {
  return Array.isArray(value) && value.length <= 50 && new Set(value.map(s => object(s) ? s.proposalId : null)).size === value.length
    && value.every(s => exact(s, ['proposalId', 'parts']) && lines.some(l => l.id === s.proposalId) && Array.isArray(s.parts)
      && s.parts.length <= 3 && s.parts.includes('ITINERARY') && new Set(s.parts).size === s.parts.length
      && s.parts.every(p => ['ITINERARY', 'CATEGORY', 'ALLOCATION'].includes(p)))
}
/** 完整响应必须保持状态、来源和人工选择一致；生成记录不代表财务已保存。 */
export async function readExpenseAssistDetail(value: unknown, reportId: string, id: string): Promise<ExpenseAssistDetail> {
  if (!exact(value, ['id', 'input', 'status', 'version', 'createdAt', 'startedAt', 'completedAt', 'suggestion', 'failure', 'review', 'canConfirm', 'unavailableCode'])
      || !receipt(value) || value.id !== id || !time(value.createdAt) || typeof value.canConfirm !== 'boolean'
      || !(value.unavailableCode === null || text(value.unavailableCode)) || !(value.startedAt === null || time(value.startedAt))
      || !(value.completedAt === null || time(value.completedAt))) throw unreadable()
  const i = await input(value.input, reportId), d = value as unknown as ExpenseAssistDetail
  if (instant(d.createdAt) >= instant(i.validUntil) || (d.status === 'QUEUED' ? d.startedAt !== null : d.startedAt === null)
      || (['QUEUED', 'RUNNING'].includes(d.status) ? d.completedAt !== null : d.completedAt === null)
      || d.startedAt && instant(d.startedAt) < instant(d.createdAt) || d.completedAt && instant(d.completedAt) < instant(d.startedAt!)
      || (['COMPLETED', 'CONFIRMED', 'DISMISSED'].includes(d.status) ? !suggestion(d.suggestion, i) : d.suggestion !== null)
      || (d.status === 'FAILED' ? !own(expenseAssistFailures, String(d.failure)) : d.failure !== null)) throw unreadable()
  if (d.canConfirm ? d.status !== 'COMPLETED' || !d.suggestion?.lines.length || d.unavailableCode !== null : d.unavailableCode === null) throw unreadable()
  if (['CONFIRMED', 'DISMISSED'].includes(d.status)) {
    const r = d.review
    if (!exact(r, ['actor', 'at', 'selected'], ['comment']) || !text(r.actor) || !time(r.at) || instant(r.at) < instant(d.completedAt!)
        || !selections(r.selected, d.suggestion!.lines) || r.comment != null && (typeof r.comment !== 'string' || r.comment.length > 2000)
        || (d.status === 'CONFIRMED' ? !r.selected.length || instant(r.at) >= instant(i.validUntil) : !!r.selected.length)) throw unreadable()
  } else if (d.review !== null) throw unreadable()
  return d
}
/** 成功回执绑定原运行及动作后，才能清除待恢复的原幂等请求。 */
export function validateExpenseAssistReceipt(value: unknown, path: string, body: string): ExpenseAssistReceipt {
  const match = /^\/expense-reports\/[^/?]+\/draft-assists(?:\/([^/?]+)\/(confirm|dismiss))?$/.exec(path)
  if (!match || !exact(value, ['id', 'status', 'version']) || !receipt(value)) throw unreadable()
  const request = JSON.parse(body) as ExpenseAssistConfirm | ExpenseAssistDismiss
  if (match[1] ? value.id !== decodeURIComponent(match[1]) || value.status !== (match[2] === 'confirm' ? 'CONFIRMED' : 'DISMISSED')
    || value.version !== request.expectedRunVersion + 1 : value.status !== 'QUEUED') throw unreadable()
  return value
}
/** 本人当前草稿、目录及原双版本必须一致；是否可重新确认由服务端决定。 */
export function expenseAssistMatches(source: ExpenseAssistInput, detail: ExpenseDetail, catalog: FinanceCatalog, now = Date.now()): boolean {
  return detail.editable && source.reportId === detail.id && source.applicationId === detail.applicationId
    && source.applicationVersion === detail.applicationVersion && source.financialVersion === detail.financialVersion
    && source.legalEntityId === detail.content.legalEntityId && source.reportType === detail.content.type
    && Date.parse(source.validUntil) > now && Date.parse(catalog.validUntil) > now && source.catalogVersion === catalog.sourceVersion
    && catalog.legalEntities.some(e => e.id === source.legalEntityId)
    && source.options.categories.every(c => catalog.categories.some(a => same(a, c)))
    && source.options.cities.every(c => catalog.cities.some(a => same(a, c)))
    && source.options.costCenters.every(c => catalog.costCenters.some(a => a.legalEntityId === source.legalEntityId && a.code === c.code && a.name === c.name))
    && source.options.projects.every(c => catalog.projects.some(a => a.legalEntityId === source.legalEntityId && a.code === c.code && a.name === c.name))
}
/** 仅追加已经逐项确认的新行；金额及分摊金额留空，继续走原费用保存和补贴计算。 */
export function fillExpenseFromAssist(content: ExpenseContent, detail: ExpenseDetail, catalog: FinanceCatalog, run: ExpenseAssistDetail, now = Date.now()): ExpenseContent {
  if (run.status !== 'CONFIRMED' || !run.review?.selected.length || !expenseAssistMatches(run.input, detail, catalog, now)
      || !same(content, detail.content)) throw new Error('请先保存或放弃当前修改，并重新核对原单据、目录和确认记录。')
  if (content.lines.length + run.review.selected.length > 200) throw new Error('填入后超过 200 行，请减少本次选择。')
  const currency = catalog.legalEntities.find(e => e.id === content.legalEntityId)!.baseCurrency
  const result = JSON.parse(JSON.stringify(content)) as ExpenseContent
  for (const selection of run.review.selected) {
    const proposal = run.suggestion!.lines.find(l => l.id === selection.proposalId)!, leg = run.input.itinerary.find(l => l.id === proposal.itineraryId)!
    const line = newExpenseLine(result, currency)
    line.incurredOn = leg.startsOn; line.endedOn = leg.endsOn; line.cityCode = leg.cityCode; line.description = proposal.description
    if (selection.parts.includes('CATEGORY')) { line.categoryCode = proposal.categoryCode; line.unit = proposal.unit }
    if (selection.parts.includes('ALLOCATION')) line.allocations = proposal.allocations.map(a => ({ costCenter: a.costCenter, projectCode: a.projectCode ?? null, amount: { value: '', currency } }))
    result.lines.push(line)
  }
  return result
}
const focus = new Map<string, string>()
type Recovery = (scope: string, reportId: string, receipt: ExpenseAssistReceipt) => void
const listeners = new Set<Recovery>(), key = (scope: string, id: string) => JSON.stringify([scope, id])
export function rememberExpenseAssist(scope: string, reportId: string, id: string) { focus.set(key(scope, reportId), id) }
export function focusedExpenseAssist(scope: string, reportId: string) { return focus.get(key(scope, reportId)) ?? '' }
export function subscribeExpenseAssistRecovery(listener: Recovery) { listeners.add(listener); return () => { listeners.delete(listener) } }
/** 恢复只定位既有运行，明确查看后再填入，不在后台自动修改草稿。 */
export function acknowledgeExpenseAssist(scope: string, path: string, receipt: ExpenseAssistReceipt) {
  const id = decodeURIComponent(path.split('/')[2]!); rememberExpenseAssist(scope, id, receipt.id)
  listeners.forEach(listener => listener(scope, id, receipt))
}
export function expenseAssistError(cause: unknown): string {
  const messages: Record<string, string> = { AGENT_MODEL_DISABLED: '模型服务尚未启用，请联系管理员配置。', AGENT_MODEL_UNCONFIGURED: '模型配置不可用，请联系管理员检查。',
    AGENT_TARGET_CHANGED: '模型目的地已变化，请重新预览后确认发送。', AGENT_INPUT_CHANGED: '目录或单据依据已变化，请刷新单据和目录后重新预览。',
    AGENT_RUN_ACTIVE: '本单已有建议正在生成，请刷新原记录。', AGENT_RUN_STATE_CONFLICT: '建议已被处理，请刷新原记录。',
    CONCURRENCY_CONFLICT: '单据或运行版本已变化，请刷新后核对。', FORBIDDEN: '当前账号无权读取这些来源，请重新打开单据。',
    NOT_FOUND: '记录不存在或当前账号不可读取。', RESPONSE_UNREADABLE: unreadable().message }
  const code = (cause as { code?: string } | null)?.code ?? ''
  return own(messages, code) ? messages[code] : cause instanceof Error && /[\u4e00-\u9fff]/.test(cause.message) ? cause.message : '操作未完成，请刷新后核对；结果未知时先恢复原操作。'
}
