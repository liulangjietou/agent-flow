import type { AssistReference, AssistSource, AssistStatus } from './assistRuns'
import { ownValue } from './formSchema.js'

/** 风险范围只携带现有单据位置，费用正文和身份由服务端重新读取。@author owlzhangfq@gmail.com */
export interface RiskDocument { reportId: string; roundNo: number; lineNos: number[] }
export interface RiskScope { documents: RiskDocument[]; calendarId?: string | null }
export interface RiskRequest { taskId: string; scope: RiskScope }
export type RiskKind = 'SAME_DAY' | 'CROSS_DOCUMENT' | 'NON_WORKING_DAY' | 'CONSECUTIVE_INVOICES'
export interface RiskConcern { sourceId: string; kind: RiskKind; documents: number[] }
export interface RiskInput {
  enabled: boolean; unavailableCode: string | null; inputDigest: string | null; targetDigest: string | null
  providerId: string | null; model: string | null; destination: string | null; concerns: RiskConcern[]; sources: AssistSource[]
}
export interface RiskGenerate extends RiskRequest { inputDigest: string; targetDigest: string; sourceIds: string[] }
export interface RiskReceipt { id: string; status: AssistStatus; version: number }
export interface RiskSummary extends RiskReceipt { createdAt: string }
export interface RiskPage { items: RiskSummary[]; total: number; page: number; pageSize: number }
export interface RiskItem { concernSourceId: string; kind: RiskKind; explanation: string; limitations: string; checks: string[]; evidence: AssistReference[] }
export interface RiskDetail extends RiskSummary {
  taskId: string; roundNo: number; startedAt: string | null; completedAt: string | null
  concerns: RiskConcern[]; sources: AssistSource[]; reviewable: boolean; adoptable: boolean; unavailableCode: string | null
  suggestion: { providerId: string; modelVersion: string; promptVersion: string; items: RiskItem[] } | null
  failure: 'MODEL_UNAVAILABLE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'INPUT_UNAVAILABLE' | null
  review: { actor: string; at: string; selectedConcernIds: string[]; comment?: string | null } | null
}
export interface RiskReview { expectedRunVersion: number; action: 'ADOPT' | 'DISMISS'; selectedConcernIds?: string[]; comment?: string }
export interface RiskCalendar { id: string; key: string; name: string; zoneId: string; revision: number }
export interface RiskCalendars { items: RiskCalendar[]; nextAfterKey: string | null }
export const riskPath = (id: string) => '/expense-reports/' + encodeURIComponent(id) + '/risk-explanations'
export const riskKinds: Record<RiskKind, string> = { SAME_DAY: '同日同类费用', CROSS_DOCUMENT: '所选对照单中的同类费用', NON_WORKING_DAY: '所选日历中的非工作日', CONSECUTIVE_INVOICES: '查验票号数值相邻' }
export const riskStatuses: Record<AssistStatus, string> = { QUEUED: '等待生成', RUNNING: '正在生成', COMPLETED: '待人工核对', FAILED: '生成未完成', ADOPTED: '已记录采纳', DISMISSED: '已放弃' }
export const riskFailures = { MODEL_UNAVAILABLE: '模型服务不可用。', MODEL_TIMEOUT: '生成已超时，系统没有自动重新发送。', INVALID_MODEL_OUTPUT: '模型输出未通过内容或来源校验。', INPUT_UNAVAILABLE: '原登录、任务或费用依据已失效。' }
const versions: Record<AssistStatus, number> = { QUEUED: 1, RUNNING: 2, COMPLETED: 3, FAILED: 3, ADOPTED: 4, DISMISSED: 4 }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) > 0
const text = (v: unknown, max = Number.MAX_SAFE_INTEGER): v is string => typeof v === 'string' && !!v.trim() && v.length <= max
const time = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const digest = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const unique = (v: unknown[]) => new Set(v).size === v.length
const concernId = (v: string) => /^expense:risk\[[1-9][0-9]{0,3}\]$/.test(v)
const documentId = (n: number) => `expense:document[${n}]`
const coverageId = 'expense:coverage'
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '风险解释响应不完整，请刷新记录；结果未知的写入先恢复原操作。' })
function summary(v: unknown): v is RiskSummary {
  return object(v) && uuid(v.id) && text(v.status) && positive(v.version) && ownValue(versions, v.status) === v.version && time(v.createdAt)
}
function sources(v: unknown, maximum: number): v is AssistSource[] {
  return Array.isArray(v) && v.length <= maximum && v.every(s => object(s) && text(s.label) && typeof s.content === 'string'
    && object(s.reference) && text(s.reference.sourceId) && /^(?:expense:coverage|expense:document\[(?:[1-9]|1[0-9]|20)\]|expense:risk\[[1-9][0-9]{0,3}\])$/.test(s.reference.sourceId)
    && digest(s.reference.contentDigest)) && unique(v.map(s => s.reference.sourceId))
}
function concerns(v: unknown, maximum: number): v is RiskConcern[] {
  return Array.isArray(v) && v.length <= maximum && v.every(c => object(c) && text(c.sourceId) && concernId(c.sourceId)
    && text(c.kind) && !!ownValue(riskKinds, c.kind) && Array.isArray(c.documents) && c.documents.length > 0 && c.documents.length <= 20
    && c.documents.every(n => positive(n) && n <= 20) && unique(c.documents) && (c.kind !== 'CROSS_DOCUMENT' || c.documents.length >= 2))
    && unique(v.map(c => c.sourceId))
}
function requireSources(values: AssistSource[], observations: RiskConcern[], documentCount?: number) {
  const ids = new Set(values.map(s => s.reference.sourceId))
  const documents = values.filter(s => s.reference.sourceId.startsWith('expense:document['))
  if (!ids.has(coverageId) || !documents.length || documentCount !== undefined && documents.length !== documentCount
      || documents.some((_, i) => !ids.has(documentId(i + 1)))
      || observations.some(c => !ids.has(c.sourceId) || c.documents.some(n => !ids.has(documentId(n))))
      || values.filter(s => concernId(s.reference.sourceId)).length !== observations.length) throw unreadable()
}
/** 保留明确选择的行；总行数上限跨单共用，第一份固定为当前主单。 */
export function riskScope(reportId: string, roundNo: number, documents: RiskDocument[], calendarId?: string | null): RiskScope {
  if (!uuid(reportId) || !positive(roundNo) || !documents.length || documents.length > 20 || !unique(documents.map(d => d.reportId))
      || documents[0]?.reportId !== reportId || documents[0]?.roundNo !== roundNo
      || documents.some(d => !uuid(d.reportId) || !positive(d.roundNo) || d.roundNo > 2_147_483_647 || !d.lineNos.length || !unique(d.lineNos) || d.lineNos.some(n => !positive(n) || n > 200))
      || documents.reduce((n, d) => n + d.lineNos.length, 0) > 200 || calendarId != null && !uuid(calendarId)) throw new Error('请选择当前轮次的费用行；最多 20 份单据、合计 200 行，每份单据至少 1 行。')
  return { documents: documents.map(d => ({ reportId: d.reportId, roundNo: d.roundNo, lineNos: [...d.lineNos].sort((a, b) => a - b) })), ...(calendarId ? { calendarId } : {}) }
}
/** 目录属于本次显式范围；不可发送时不允许夹带费用来源或模型目的地。 */
export function readRiskInput(v: unknown, scope: RiskScope): RiskInput {
  if (!object(v) || typeof v.enabled !== 'boolean' || !sources(v.sources, 10020) || !concerns(v.concerns, 9999)) throw unreadable()
  if (v.enabled) {
    if (v.unavailableCode !== null || !digest(v.inputDigest) || !digest(v.targetDigest) || !text(v.providerId, 128)
        || !text(v.model, 128) || !text(v.destination) || !v.concerns.length) throw unreadable()
    requireSources(v.sources, v.concerns, scope.documents.length)
  } else if (!text(v.unavailableCode) || v.sources.length || v.concerns.length
      || [v.inputDigest, v.targetDigest, v.providerId, v.model, v.destination].some(x => x !== null)) throw unreadable()
  return v as unknown as RiskInput
}
/** 来源必须逐项勾选；范围和覆盖事实不可隐式加入发送内容。 */
export function riskSelection(input: RiskInput, ids: string[]): string[] {
  if (!input.enabled || !ids.length || ids.length > 64 || !unique(ids)) throw new Error('请明确勾选 1 至 64 项发送来源。')
  const selected = ids.map(id => input.sources.find(s => s.reference.sourceId === id))
  const mandatory = input.sources.filter(s => !concernId(s.reference.sourceId)).map(s => s.reference.sourceId)
  const count = ids.filter(concernId).length
  if (selected.some(s => !s) || mandatory.some(id => !ids.includes(id)) || count < 1 || count > 20) throw new Error('请勾选每份单据的费用事实、范围及查验覆盖，并选择 1 至 20 条观察。')
  if (new TextEncoder().encode(JSON.stringify(selected)).length > 65_536) throw new Error('本次发送内容超过 64 KiB，请减少所选范围后重新预览。')
  return [...ids]
}
/** 分页响应只代表原轮次的摘要，详情仍单独检查全部来源权限。 */
export function readRiskPage(v: unknown, page: number): RiskPage {
  if (!object(v) || !Array.isArray(v.items) || v.items.length > 20 || !v.items.every(summary) || !unique(v.items.map(s => s.id))
      || v.page !== page || v.pageSize !== 20 || !Number.isSafeInteger(v.total) || (v.total as number) < v.items.length) throw unreadable()
  return v as unknown as RiskPage
}
/** 日历仅作本次计算依据，列表响应不包含管理员维护权限。 */
export function readRiskCalendars(v: unknown): RiskCalendars {
  if (!object(v) || !Array.isArray(v.items) || v.items.length > 30 || !v.items.every(c => object(c) && uuid(c.id)
      && text(c.key, 64) && /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(c.key) && text(c.name) && text(c.zoneId) && positive(c.revision))
      || !unique(v.items.map(c => c.id)) || !unique(v.items.map(c => c.key))
      || v.nextAfterKey !== null && (!v.items.length || v.nextAfterKey !== v.items[v.items.length - 1]?.key)) throw unreadable()
  return v as unknown as RiskCalendars
}
/** 核对原轮次、状态时间、全部观察引用和人工复核，损坏响应不能启用采纳。 */
export function readRiskDetail(v: unknown, id: string, roundNo: number): RiskDetail {
  if (!summary(v) || v.id !== id) throw unreadable()
  const d = v as RiskDetail
  if (!text(d.taskId, 64) || d.roundNo !== roundNo || !positive(d.roundNo) || !sources(d.sources, 64) || !concerns(d.concerns, 20) || !d.concerns.length
      || typeof d.reviewable !== 'boolean' || typeof d.adoptable !== 'boolean' || d.adoptable && !d.reviewable
      || d.reviewable && d.status !== 'COMPLETED' || d.unavailableCode !== null && !text(d.unavailableCode)
      || d.adoptable && d.unavailableCode !== null
      || (d.status === 'QUEUED' ? d.startedAt !== null : !time(d.startedAt))
      || (['QUEUED', 'RUNNING'].includes(d.status) ? d.completedAt !== null : !time(d.completedAt))
      || d.startedAt && Date.parse(d.startedAt) < Date.parse(d.createdAt)
      || d.completedAt && Date.parse(d.completedAt) < Date.parse(d.startedAt!)) throw unreadable()
  requireSources(d.sources, d.concerns)
  if (['COMPLETED', 'ADOPTED', 'DISMISSED'].includes(d.status)) {
    const s = d.suggestion
    if (!s || !text(s.providerId, 128) || !text(s.modelVersion, 128) || s.promptVersion !== 'expense-risk-explanation-v1'
        || !Array.isArray(s.items) || s.items.length !== d.concerns.length || !unique(s.items.map(i => i?.concernSourceId))) throw unreadable()
    for (const item of s.items) {
      const c = d.concerns.find(c => c.sourceId === item?.concernSourceId)
      if (!c || c.kind !== item.kind || !text(item.explanation, 1000) || !text(item.limitations, 1000) || !Array.isArray(item.checks)
          || !item.checks.length || item.checks.length > 5 || !item.checks.every(c => text(c, 500)) || !Array.isArray(item.evidence)
          || item.evidence.length > 64 || !unique(item.evidence.map(r => r?.sourceId))
          || ![c.sourceId, coverageId, ...c.documents.map(documentId)].every(id => item.evidence.some(r => r?.sourceId === id))
          || !item.evidence.every(r => r && d.sources.some(s => s.reference.sourceId === r.sourceId && s.reference.contentDigest === r.contentDigest))) throw unreadable()
    }
  } else if (d.suggestion !== null) throw unreadable()
  if (d.status === 'FAILED' ? !ownValue(riskFailures, String(d.failure)) : d.failure !== null) throw unreadable()
  if (['ADOPTED', 'DISMISSED'].includes(d.status)) {
    const r = d.review
    if (!r || !text(r.actor) || !time(r.at) || Date.parse(r.at) < Date.parse(d.completedAt!) || !Array.isArray(r.selectedConcernIds)
        || !unique(r.selectedConcernIds) || !r.selectedConcernIds.every(id => d.concerns.some(c => c.sourceId === id))
        || r.comment != null && (typeof r.comment !== 'string' || r.comment.length > 2000)
        || (d.status === 'ADOPTED' ? !r.selectedConcernIds.length : r.selectedConcernIds.length > 0)) throw unreadable()
  } else if (d.review !== null) throw unreadable()
  return d
}
/** 成功回执与原请求严格对应后，才能结束幂等恢复。 */
export function validateRiskReceipt(v: unknown, path: string, body: string): RiskReceipt {
  const review = /\/risk-explanations\/([^/?]+)\/review$/.exec(path), input = JSON.parse(body) as RiskReview
  if (!object(v) || !uuid(v.id) || review && v.id !== decodeURIComponent(review[1]!)
      || v.status !== (review ? input.action === 'ADOPT' ? 'ADOPTED' : 'DISMISSED' : 'QUEUED')
      || v.version !== (review ? input.expectedRunVersion + 1 : 1)) throw unreadable()
  return v as unknown as RiskReceipt
}
const focus = new Map<string, string>()
type Recovery = (scope: string, reportId: string, roundNo: number | null, receipt: RiskReceipt) => void
const listeners = new Set<Recovery>()
const focusKey = (scope: string, reportId: string, roundNo: number) => JSON.stringify([scope, reportId, roundNo])
export function rememberRisk(scope: string, reportId: string, roundNo: number, id: string) { focus.set(focusKey(scope, reportId, roundNo), id) }
export function focusedRisk(scope: string, reportId: string, roundNo: number) { return focus.get(focusKey(scope, reportId, roundNo)) ?? '' }
export function subscribeRiskRecovery(listener: Recovery) { listeners.add(listener); return () => { listeners.delete(listener) } }
/** 原请求恢复按账号、主单和原轮次定位，复核回放不切换到其他轮次。 */
export function acknowledgeRisk(scope: string, path: string, body: string, receipt: RiskReceipt) {
  const reportId = decodeURIComponent(path.split('/')[2]!), input = JSON.parse(body) as RiskGenerate
  const roundNo = path.endsWith('/review') ? null : input.scope.documents[0]!.roundNo
  if (roundNo !== null) rememberRisk(scope, reportId, roundNo, receipt.id)
  for (const listener of listeners) listener(scope, reportId, roundNo, receipt)
}
const messages: Record<string, string> = {
  AGENT_MODEL_DISABLED: '模型服务尚未启用。', AGENT_MODEL_UNCONFIGURED: '模型配置不可用。', DEFERRED_AUTHENTICATION_UNAVAILABLE: '当前登录方式无法安全保留发送授权。',
  AGENT_TARGET_CHANGED: '模型目的地已变化，请重新预览并确认发送内容。', AGENT_INPUT_CHANGED: '费用或日历依据已变化，请重新选择范围并预览。',
  AGENT_RUN_ACTIVE: '本单已有解释正在生成，请刷新记录。', AGENT_RUN_STATE_CONFLICT: '解释已经处理，请刷新记录。',
  INVALID_AGENT_INPUT: '所选来源无效，请核对单据、原轮次及费用行。', NO_RISK_OBSERVATIONS: '所选范围没有可解释的观察，不代表整单没有风险。',
  FORBIDDEN: '当前账号没有这次审批的决定权，或不能完整读取所选费用。', NOT_FOUND: '记录不存在或当前账号不可读取。',
  CONCURRENCY_CONFLICT: '费用或运行版本已变化，请刷新后核对。', RESPONSE_UNREADABLE: unreadable().message
}
export function riskError(cause: unknown): string {
  return ownValue(messages, (cause as { code?: string } | null)?.code ?? '')
    ?? (cause instanceof Error && /[\u4e00-\u9fff]/.test(cause.message) ? cause.message : '操作未完成，请刷新后核对；结果未知的写入先恢复原操作。')
}
