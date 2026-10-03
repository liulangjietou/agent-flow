import type { AssistReference, AssistSource, AssistStatus } from './assistRuns.js'
import { ownValue } from './formSchema.js'
import { precheckIssues, precheckStages, precheckStatuses } from './expenseDraft.js'

/** 解释只引用已保存的检查事实，采纳仅记录人工复核。@author owlzhangfq@gmail.com */
export interface ExplanationInput {
  precheckId: string; applicationVersion: number; financialVersion: number; attempt: number
  result: 'QUEUED' | 'RUNNING' | 'READY' | 'BLOCKED' | 'UNAVAILABLE'; checkedAt: string | null; validUntil: string | null
  enabled: boolean; unavailableCode: string | null; providerId: string | null; model: string | null
  destination: string | null; targetDigest: string | null; sources: AssistSource[]
}
export interface ExplanationSummary {
  id: string; precheckId: string; applicationVersion: number; financialVersion: number; attempt: number
  status: AssistStatus; version: number; createdAt: string
}
export interface ExplanationPage { items: ExplanationSummary[]; total: number; page: number; pageSize: number }
export interface ExplanationItem { issueSourceId: string; explanation: string; corrections: string[]; evidence: AssistReference[] }
export interface ExplanationDetail extends ExplanationSummary {
  result: 'READY' | 'BLOCKED' | 'UNAVAILABLE'; checkedAt: string; validUntil: string
  startedAt: string | null; completedAt: string | null; sources: AssistSource[]
  suggestion: { providerId: string; modelVersion: string; promptVersion: string; items: ExplanationItem[] } | null
  failure: 'MODEL_UNAVAILABLE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'INPUT_UNAVAILABLE' | null
  review: { actor: string; at: string; selectedIssueIds: string[]; comment?: string | null } | null
  canAdopt: boolean; unavailableCode: string | null
}
export interface ExplanationGenerate { precheckId: string; applicationVersion: number; financialVersion: number; targetDigest: string; sourceIds: string[] }
export interface ExplanationReview { expectedRunVersion: number; action: 'ADOPT' | 'DISMISS'; selectedIssueIds?: string[]; comment?: string }
export interface ExplanationReceipt { id: string; status: AssistStatus; version: number }
export const explanationPath = (reportId: string) => '/expense-reports/' + encodeURIComponent(reportId) + '/precheck-explanations'
export const explanationStatuses: Record<AssistStatus, string> = { QUEUED: '等待生成', RUNNING: '正在生成', COMPLETED: '待人工核对', FAILED: '生成未完成', ADOPTED: '已记录采纳', DISMISSED: '已放弃' }
export const explanationFailures = { MODEL_UNAVAILABLE: '模型服务不可用，请检查配置后明确发起新请求。', MODEL_TIMEOUT: '生成已超时，系统没有自动重新发送。', INVALID_MODEL_OUTPUT: '模型输出未通过内容或来源校验。', INPUT_UNAVAILABLE: '发送前检查依据已失效，本次内容未发送。' }
const versions: Record<AssistStatus, number> = { QUEUED: 1, RUNNING: 2, COMPLETED: 3, FAILED: 3, ADOPTED: 4, DISMISSED: 4 }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) > 0
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim()
const time = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const nullableText = (v: unknown) => v === null || text(v)
const nullableTime = (v: unknown) => v === null || time(v)
const digest = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const terminal = (v: unknown) => ['READY', 'BLOCKED', 'UNAVAILABLE'].includes(String(v))
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '预检解释响应不完整，请刷新记录；写入结果未知时先恢复原操作。' })
const finding = (id: string) => /^precheck:finding\[(?:0|[1-9][0-9]*)\]$/.test(id)
function summary(v: unknown): v is ExplanationSummary {
  return object(v) && uuid(v.id) && uuid(v.precheckId) && positive(v.applicationVersion) && positive(v.financialVersion)
    && positive(v.attempt) && typeof v.status === 'string' && ownValue(versions, v.status) === v.version && time(v.createdAt)
}
function sources(v: unknown, maximum: number): v is AssistSource[] {
  return Array.isArray(v) && v.length <= maximum && v.every(s => object(s) && text(s.label) && typeof s.content === 'string'
    && object(s.reference) && typeof s.reference.sourceId === 'string'
    && /^(?:precheck:result|precheck:finding\[(?:0|[1-9][0-9]*)\]|expense:line\[[1-9][0-9]*\])$/.test(s.reference.sourceId)
    && digest(s.reference.contentDigest)) && new Set(v.map(s => s.reference.sourceId)).size === v.length
}
function issueIds(result: string, values: AssistSource[]) {
  if (!values.some(s => s.reference.sourceId === 'precheck:result')) throw unreadable()
  const issues = values.filter(s => finding(s.reference.sourceId)).map(s => s.reference.sourceId)
  if (result === 'READY' ? issues.length > 0 : !issues.length) throw unreadable()
  return result === 'READY' ? ['precheck:result'] : issues
}
/** 目录绑定明确的检查；不可发送的目录不能夹带来源或目的地。 */
export function readExplanationInput(value: unknown, precheckId: string): ExplanationInput {
  if (!object(value) || !uuid(value.precheckId) || value.precheckId !== precheckId || !positive(value.applicationVersion)
      || !positive(value.financialVersion) || !positive(value.attempt) || !ownValue(precheckStatuses, String(value.result))
      || !nullableTime(value.checkedAt) || !nullableTime(value.validUntil) || typeof value.enabled !== 'boolean'
      || !nullableText(value.unavailableCode) || !sources(value.sources, 411)) throw unreadable()
  if (value.enabled) {
    if (!terminal(value.result) || !time(value.checkedAt) || !time(value.validUntil) || Date.parse(value.validUntil) <= Date.parse(value.checkedAt)
        || value.unavailableCode !== null || !text(value.providerId) || !text(value.model) || !text(value.destination) || !digest(value.targetDigest)) throw unreadable()
    issueIds(String(value.result), value.sources)
  } else if (!text(value.unavailableCode) || value.sources.length || [value.providerId, value.model, value.destination, value.targetDigest].some(v => v !== null)) throw unreadable()
  return value as unknown as ExplanationInput
}
/** 页号与大小绑定原查询；列表不代替原记录的权限和时效。 */
export function readExplanationPage(value: unknown, page: number): ExplanationPage {
  if (!object(value) || !Array.isArray(value.items) || value.items.length > 20 || !value.items.every(summary)
      || new Set(value.items.map(v => v.id)).size !== value.items.length || value.page !== page || value.pageSize !== 20
      || !Number.isSafeInteger(value.total) || (value.total as number) < value.items.length) throw unreadable()
  return value as unknown as ExplanationPage
}
/** 核对运行、问题集合、来源摘要与复核状态，损坏响应不能开启采纳。 */
export function readExplanationDetail(value: unknown, id: string): ExplanationDetail {
  if (!summary(value) || value.id !== id) throw unreadable()
  const d = value as ExplanationDetail
  if (!terminal(d.result) || !time(d.checkedAt) || !time(d.validUntil) || Date.parse(d.validUntil) <= Date.parse(d.checkedAt)
      || !sources(d.sources, 64) || typeof d.canAdopt !== 'boolean' || !nullableText(d.unavailableCode)
      || d.canAdopt && (d.status !== 'COMPLETED' || d.unavailableCode !== null)
      || !nullableTime(d.startedAt) || !nullableTime(d.completedAt)) throw unreadable()
  const issues = issueIds(d.result, d.sources)
  if (issues.length > 20 || Date.parse(d.createdAt) < Date.parse(d.checkedAt) || Date.parse(d.createdAt) >= Date.parse(d.validUntil)
      || (d.status === 'QUEUED' ? d.startedAt !== null : !time(d.startedAt))
      || (['QUEUED', 'RUNNING'].includes(d.status) ? d.completedAt !== null : !time(d.completedAt))
      || d.startedAt && Date.parse(d.startedAt) < Date.parse(d.createdAt)
      || d.completedAt && Date.parse(d.completedAt) < Date.parse(d.startedAt!)) throw unreadable()
  if (['COMPLETED', 'ADOPTED', 'DISMISSED'].includes(d.status)) {
    const s = d.suggestion
    if (!s || !text(s.providerId) || !text(s.modelVersion) || s.promptVersion !== 'expense-precheck-explanation-v1'
        || !Array.isArray(s.items) || s.items.length !== issues.length || new Set(s.items.map(i => i?.issueSourceId)).size !== issues.length) throw unreadable()
    for (const item of s.items) {
      if (!item || !issues.includes(item.issueSourceId) || !text(item.explanation) || item.explanation.length > 1000
          || !Array.isArray(item.corrections) || item.corrections.length > 5 || d.result !== 'READY' && !item.corrections.length
          || !item.corrections.every(c => text(c) && c.length <= 500) || !Array.isArray(item.evidence) || !item.evidence.length
          || !item.evidence.some(r => r?.sourceId === item.issueSourceId)
          || !item.evidence.every(r => r && d.sources.some(s => s.reference.sourceId === r.sourceId && s.reference.contentDigest === r.contentDigest))) throw unreadable()
    }
  } else if (d.suggestion !== null) throw unreadable()
  if (d.status === 'FAILED' ? !ownValue(explanationFailures, String(d.failure)) : d.failure !== null) throw unreadable()
  if (['ADOPTED', 'DISMISSED'].includes(d.status)) {
    const r = d.review
    if (!r || !text(r.actor) || !time(r.at) || Date.parse(r.at) < Date.parse(d.completedAt!) || !Array.isArray(r.selectedIssueIds)
        || new Set(r.selectedIssueIds).size !== r.selectedIssueIds.length || !r.selectedIssueIds.every(i => issues.includes(i))
        || r.comment != null && (typeof r.comment !== 'string' || r.comment.length > 2000)
        || (d.status === 'ADOPTED' ? !r.selectedIssueIds.length || Date.parse(r.at) >= Date.parse(d.validUntil) : r.selectedIssueIds.length > 0)) throw unreadable()
  } else if (d.review !== null) throw unreadable()
  return d
}
/** 核对成功回执后才能清除原幂等请求；错误编号或状态仍属于结果未知。 */
export function validateExplanationReceipt(value: unknown, path: string, body: string): ExplanationReceipt {
  const review = /\/precheck-explanations\/([^/?]+)\/review$/.exec(path), input = JSON.parse(body) as ExplanationReview
  if (!object(value) || !uuid(value.id) || review && value.id !== decodeURIComponent(review[1]!)
      || value.status !== (review ? input.action === 'ADOPT' ? 'ADOPTED' : 'DISMISSED' : 'QUEUED')
      || value.version !== (review ? input.expectedRunVersion + 1 : 1)) throw unreadable()
  return value as unknown as ExplanationReceipt
}
/** 只发送明确勾选的目录项，保持原文，编码大小与服务端约束一致。 */
export function explanationSelection(input: ExplanationInput, ids: string[]): string[] {
  if (!ids.length || ids.length > 64 || new Set(ids).size !== ids.length) throw new Error('请勾选 1 至 64 项来源。')
  const selected = ids.map(id => input.sources.find(s => s.reference.sourceId === id))
  if (selected.some(s => !s) || !ids.includes('precheck:result') || input.result !== 'READY' && !ids.some(finding)) throw new Error('请明确勾选本次预检结论及要解释的检查问题。')
  if (ids.filter(finding).length > 20 || new TextEncoder().encode(JSON.stringify(selected)).length > 65_536) throw new Error('一次最多选择 20 个问题，所选来源合计不超过 64 KiB。')
  return [...ids]
}
const focus = new Map<string, string>()
type Recovery = (scope: string, reportId: string, receipt: ExplanationReceipt) => void
const listeners = new Set<Recovery>()
const focusKey = (scope: string, reportId: string) => JSON.stringify([scope, reportId])
export function rememberExplanation(scope: string, reportId: string, runId: string) { focus.set(focusKey(scope, reportId), runId) }
export function focusedExplanation(scope: string, reportId: string) { return focus.get(focusKey(scope, reportId)) ?? '' }
export function subscribeExplanationRecovery(listener: Recovery) { listeners.add(listener); return () => { listeners.delete(listener) } }
/** 原请求恢复只定位既有运行，不重新生成，也不改写费用草稿。 */
export function acknowledgeExplanation(scope: string, path: string, receipt: ExplanationReceipt) {
  const reportId = decodeURIComponent(path.split('/')[2]!)
  rememberExplanation(scope, reportId, receipt.id)
  for (const listener of listeners) listener(scope, reportId, receipt)
}
/** 业务标签用于阅读；原始发送文本另行原样展示，金额不经浮点重算。 */
export function explanationSourceLabel(source: AssistSource): string {
  if (finding(source.reference.sourceId)) {
    try {
      const f = JSON.parse(source.content)
      return `${f.lineNo == null ? '整单' : `第 ${f.lineNo} 行`} · ${ownValue(precheckStages, f.stage) ?? '检查问题'} · ${ownValue(precheckIssues, f.code) ?? '请核对原检查问题'}`
    } catch { return source.label }
  }
  return source.label
}
const messages: Record<string, string> = {
  AGENT_MODEL_DISABLED: '模型服务尚未启用，请联系管理员配置。', AGENT_MODEL_UNCONFIGURED: '模型配置不可用，请联系管理员检查。',
  AGENT_TARGET_CHANGED: '模型目的地已变化，请刷新发送目录后重新选择。', AGENT_INPUT_CHANGED: '检查依据已变化，请重新预检。',
  PRECHECK_EXPLANATION_REFRESH_REQUIRED: '这次检查没有可用的解释依据，请重新执行费用预检。',
  AGENT_RUN_ACTIVE: '本单已有解释正在生成，请刷新记录。', AGENT_RUN_STATE_CONFLICT: '解释已被处理，请刷新记录。',
  CONCURRENCY_CONFLICT: '单据或解释版本已变化，请刷新后核对。', VERSION_CHANGED: '单据版本已变化，请重新预检。',
  FORBIDDEN: '只有原申请人可查看和复核这些解释。', NOT_FOUND: '记录不存在或当前账号不可读取。',
  RESPONSE_UNREADABLE: unreadable().message
}
export function explanationError(cause: unknown): string {
  const code = (cause as { code?: string } | null)?.code ?? ''
  return ownValue(messages, code) ?? ownValue(precheckIssues, code) ?? (cause instanceof Error && /[\u4e00-\u9fff]/.test(cause.message)
    ? cause.message : '操作未完成，请刷新后核对；结果未知的写入请先恢复原操作。')
}
