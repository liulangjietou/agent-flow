import type { AssistReference, AssistSource, AssistStatus } from './assistRuns.js'
import { ownValue, validatePayload, validateFormSchema, fieldErrorMessage, type FormSchema, type FormField } from './formSchema.js'

/** 草稿建议独立于审批摘要，输出只在本人确认后保存。@author owlzhangfq@gmail.com */
export interface DraftAssistInput {
  applicationVersion: number; enabled: boolean; unavailableCode: string | null; providerId: string | null
  model: string | null; destination: string | null; targetDigest: string | null; targetSchema: FormSchema; sources: AssistSource[]
}
export interface DraftAssistSummary { id: string; applicationVersion: number; status: AssistStatus; version: number; createdAt: string }
export interface DraftAssistPage { items: DraftAssistSummary[]; total: number; page: number; pageSize: number }
export interface DraftSelection { targetId: string; value: unknown }
export interface DraftProposal extends DraftSelection { evidence: AssistReference[] }
export interface DraftAssistDetail extends DraftAssistSummary {
  startedAt: string | null; completedAt: string | null; targetSchema: FormSchema; sources: AssistSource[]; canAdopt: boolean
  suggestion: { providerId: string; modelVersion: string; promptVersion: string; proposals: DraftProposal[] } | null
  failure: 'MODEL_UNAVAILABLE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'INPUT_UNAVAILABLE' | null
  review: { actor: string; at: string; selected?: DraftSelection[] | null; appliedApplicationVersion?: number | null; comment?: string | null } | null
}
export interface DraftAssistReceipt { id: string; status: AssistStatus; version: number; savedApplicationVersion: number | null }
export interface GenerateDraftInput { expectedVersion: number; targetDigest: string; brief: string; sourceIds: string[] }
export interface ReviewDraftInput { expectedRunVersion: number; expectedApplicationVersion?: number; action: 'ADOPT' | 'DISMISS'; selected?: DraftSelection[]; comment?: string }
export const draftAssistPath = (applicationId: string) => '/applications/' + encodeURIComponent(applicationId) + '/draft-assist-runs'
const focusByApplication = new Map<string, string>()
/** 仅在内存保留原运行编号，成功后详情读取失败或恢复请求时可回到同一条记录。 */
export function rememberDraftRun(scope: string, applicationId: string, runId: string) { focusByApplication.set(JSON.stringify([scope, applicationId]), runId) }
export function focusedDraftRun(scope: string, applicationId: string) { return focusByApplication.get(JSON.stringify([scope, applicationId])) ?? '' }
export const draftStatuses: Record<AssistStatus, string> = { QUEUED: '等待生成', RUNNING: '正在生成', COMPLETED: '待人工核对', FAILED: '生成未完成', ADOPTED: '已保存到草稿', DISMISSED: '未采纳' }
const versions: Record<AssistStatus, number> = { QUEUED: 1, RUNNING: 2, COMPLETED: 3, FAILED: 3, ADOPTED: 4, DISMISSED: 4 }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) > 0
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim()
const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
const digest = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '草稿建议响应不完整，请刷新记录；写入结果未知时先恢复原操作。' })
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
function summary(value: unknown): value is DraftAssistSummary {
  return object(value) && uuid(value.id) && positive(value.applicationVersion) && positive(value.version) && typeof value.status === 'string'
    && ownValue(versions, value.status) === value.version && time(value.createdAt)
}
function safeField(field: FormField): boolean {
  return !field.sensitive && field.type !== 'ATTACHMENT' && (field.type !== 'TABLE' || !!field.columns?.every(safeField))
}
function schema(value: unknown): value is FormSchema {
  if (!object(value) || !Array.isArray(value.fields)) return false
  try {
    const result = validateFormSchema(value as unknown as FormSchema)
    return !result.schema.length && result.fields.every(errors => !Object.keys(errors).length) && value.fields.every(safeField)
  } catch { return false }
}
function sources(value: unknown): value is AssistSource[] {
  return Array.isArray(value) && value.length <= 64 && value.every(source => object(source) && text(source.label) && typeof source.content === 'string'
    && object(source.reference) && text(source.reference.sourceId) && digest(source.reference.contentDigest))
    && new Set(value.map(source => source.reference.sourceId)).size === value.length
}
/** 只显示受控字段与明确目的地；损坏输入目录不能开启生成按钮。 */
export function readDraftInput(value: unknown): DraftAssistInput {
  if (!object(value) || !positive(value.applicationVersion) || typeof value.enabled !== 'boolean' || !schema(value.targetSchema) || !sources(value.sources)
      || value.enabled && (!text(value.providerId) || !text(value.model) || !text(value.destination) || !digest(value.targetDigest))) throw unreadable()
  return value as unknown as DraftAssistInput
}
/** 页号与页大小绑定原请求，索引不作为保存授权。 */
export function readDraftPage(value: unknown, page: number): DraftAssistPage {
  if (!object(value) || !Array.isArray(value.items) || value.items.length > 20 || !value.items.every(summary)
      || new Set(value.items.map(item => item.id)).size !== value.items.length || value.page !== page || value.pageSize !== 20
      || !Number.isSafeInteger(value.total) || (value.total as number) < value.items.length) throw unreadable()
  return value as unknown as DraftAssistPage
}
/** 原建议只接受本次实际来源，不能将一个运行的响应填入另一个运行。 */
export function readDraftDetail(value: unknown, id: string): DraftAssistDetail {
  if (!summary(value) || value.id !== id) throw unreadable()
  const detail = value as DraftAssistDetail
  if (!schema(detail.targetSchema) || !sources(detail.sources) || !detail.sources.some(source => source.reference.sourceId === 'application:brief')
      || typeof detail.canAdopt !== 'boolean' || detail.canAdopt && detail.status !== 'COMPLETED') throw unreadable()
  if (['COMPLETED', 'ADOPTED', 'DISMISSED'].includes(detail.status)) {
    const suggestion = detail.suggestion
    if (!suggestion || !text(suggestion.providerId) || !text(suggestion.modelVersion) || suggestion.promptVersion !== 'application-draft-v1'
        || !Array.isArray(suggestion.proposals) || !suggestion.proposals.length || suggestion.proposals.length > 51
        || new Set(suggestion.proposals.map(p => p?.targetId)).size !== suggestion.proposals.length) throw unreadable()
    for (const proposal of suggestion.proposals) {
      if (!proposal || !text(proposal.targetId) || proposal.value == null || !Array.isArray(proposal.evidence) || !proposal.evidence.length
          || !proposal.evidence.every(ref => ref && detail.sources.some(source => source.reference.sourceId === ref.sourceId && source.reference.contentDigest === ref.contentDigest))) throw unreadable()
      try { validateSelection(detail.targetSchema, proposal) } catch { throw unreadable() }
    }
  } else if (detail.suggestion != null) throw unreadable()
  if (detail.status === 'FAILED' && !['MODEL_UNAVAILABLE', 'MODEL_TIMEOUT', 'INVALID_MODEL_OUTPUT', 'INPUT_UNAVAILABLE'].includes(String(detail.failure))) throw unreadable()
  if (['ADOPTED', 'DISMISSED'].includes(detail.status)) {
    if (!detail.review || !text(detail.review.actor) || !time(detail.review.at)) throw unreadable()
    if (detail.status === 'ADOPTED' && (!Array.isArray(detail.review.selected) || !detail.review.selected.length
        || detail.review.appliedApplicationVersion !== detail.applicationVersion + 1)) throw unreadable()
  }
  return detail
}
/** 成功回执在清除原请求之前核对，错误编号或保存版本仍属于结果未知。 */
export function validateDraftReceipt(value: unknown, path: string, body: string): DraftAssistReceipt {
  const review = /\/draft-assist-runs\/([^/?]+)\/review$/.exec(path), input = JSON.parse(body) as ReviewDraftInput
  if (!object(value) || !uuid(value.id) || review && value.id !== decodeURIComponent(review[1]!)
      || value.status !== (review ? input.action === 'ADOPT' ? 'ADOPTED' : 'DISMISSED' : 'QUEUED')
      || value.version !== (review ? input.expectedRunVersion + 1 : 1)
      || value.savedApplicationVersion !== (review && input.action === 'ADOPT' ? input.expectedApplicationVersion! + 1 : null)) throw unreadable()
  return value as unknown as DraftAssistReceipt
}
export const proposalField = (schema: FormSchema, id: string) => schema.fields.find(field => id === 'form:' + field.key)
export const proposalLabel = (schema: FormSchema, id: string) => id === 'application:title' ? '申请标题' : proposalField(schema, id)?.label ?? '未知字段'
function validateSelection(schema: FormSchema, selection: DraftSelection) {
  const label = proposalLabel(schema, selection.targetId)
  if (selection.targetId === 'application:title') {
    if (typeof selection.value !== 'string' || !selection.value.trim() || selection.value.length > 256) throw new Error('申请标题须为 1 至 256 个字符。')
    return
  }
  const field = proposalField(schema, selection.targetId)
  if (!field || !safeField(field) || selection.value == null) throw new Error(`请填写勾选的“${label}”，或取消勾选。`)
  const errors = validatePayload({ schemaVersion: schema.schemaVersion, fields: [field] }, { [field.key]: selection.value }, false)
  if (Object.keys(errors).length) throw new Error(`${label}：${fieldErrorMessage(Object.values(errors)[0]!)}`)
}
/** 人工值深复制，不修改模型原文；仅保留明确勾选且真实存在的目标。 */
export function draftSelections(detail: DraftAssistDetail, selected: string[], values: Record<string, unknown>): DraftSelection[] {
  if (!selected.length || new Set(selected).size !== selected.length) throw new Error('请明确勾选要保存的字段。')
  return selected.map(targetId => {
    if (!detail.suggestion?.proposals.some(proposal => proposal.targetId === targetId)) throw new Error('所选字段不在本次建议中，请刷新记录。')
    const selection = { targetId, value: ownValue(values, targetId) }
    validateSelection(detail.targetSchema, selection)
    return copy(selection)
  })
}
const messages: Record<string, string> = {
  AGENT_MODEL_DISABLED: '模型服务尚未启用，请联系管理员配置后刷新。', AGENT_MODEL_UNCONFIGURED: '模型配置不可用，请联系管理员检查。',
  AGENT_TARGET_CHANGED: '模型目的地已变化，请刷新发送目录并重新选择。', AGENT_INPUT_CHANGED: '申请版本已变化，请重新读取申请并生成新的建议。',
  AGENT_DRAFT_UNSUPPORTED: '这份申请暂不支持草稿建议，请按原业务表单填写。', AGENT_RUN_ACTIVE: '这份申请已有正在生成的建议，请刷新记录查看。',
  CONCURRENCY_CONFLICT: '申请或建议已变化，请重新读取后核对。', AGENT_RUN_STATE_CONFLICT: '这条建议已经处理，请刷新记录。',
  FORBIDDEN: '只有原申请人可读取和处理这份草稿建议。', NOT_FOUND: '这条记录不存在或当前账号不可读取。',
  INVALID_AGENT_INPUT: '生成要求最长 8,000 字符，最多勾选 63 项已有内容，合计不超过 64 KiB。',
  INVALID_AGENT_REVIEW: '请核对勾选字段、申请版本和人工修订值。', INVALID_AGENT_OUTPUT: '所选字段值未通过表单校验，请核对后保存。',
  DOMAIN_RULE_VIOLATION: '当前申请不可编辑，请刷新申请状态。',
  SUBPROCESS_PARENT_CONTROL_REQUIRED: '这是子流程申请，请从主申请填写和修改内容。',
  RESPONSE_UNREADABLE: '草稿建议响应不完整，请刷新记录；写入结果未知时先恢复原操作。'
}
export function draftError(cause: unknown) {
  const failure = cause as { code?: string; message?: string } | null
  return ownValue(messages, failure?.code ?? '') ?? (cause instanceof Error && /[\u4e00-\u9fff]/.test(cause.message)
    ? cause.message : '操作未完成，请刷新后重试；结果未知的写入请先恢复原操作。')
}
