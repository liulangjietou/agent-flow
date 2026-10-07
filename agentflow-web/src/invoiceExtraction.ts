import type { InvoiceFormat, InvoiceItem } from './invoiceWallet.js'

export type ExtractionMethod = 'STRUCTURED_XML' | 'MODEL'
export type ExtractionStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CONFIRMED' | 'DISMISSED'
export type ExtractionField = keyof typeof extractionFields
export interface ExtractionSource { invoiceId: string; originalId: string; originalDigest: string; format: InvoiceFormat; originalBytes: number; pageCount: number }
export interface ExtractionOptions {
  input: ExtractionSource; method: ExtractionMethod; transmission: 'NONE' | 'XML_TEXT' | 'ORIGINAL_BYTES' | 'RENDERED_PAGES'; enabled: boolean
  unavailableCode: string | null; providerId: string | null; model: string | null; destination: string | null; targetDigest: string | null; supportedFormats: InvoiceFormat[]
}
export interface ExtractionSelection { field: ExtractionField; value: string }
export interface ExtractionEvidence { originalId: string; originalDigest: string; page: number; quote: string; xmlPath?: string | null }
export interface ExtractionProposal extends ExtractionSelection { confidence: 'LOW' | 'MEDIUM' | 'HIGH'; evidence: ExtractionEvidence[] }
export interface ExtractionReceipt { id: string; status: ExtractionStatus; version: number }
export interface ExtractionSummary extends ExtractionReceipt { method: ExtractionMethod; createdAt: string }
export interface ExtractionPage { items: ExtractionSummary[]; total: number; page: number; pageSize: number }
export interface ExtractionDetail extends ExtractionSummary {
  input: ExtractionSource; startedAt: string | null; completedAt: string | null; canConfirm: boolean
  suggestion: { method: ExtractionMethod; providerId: string; processorVersion: string; contractVersion: string; proposals: ExtractionProposal[] } | null
  failure: 'MODEL_UNAVAILABLE' | 'EXECUTION_TIMEOUT' | 'INVALID_RESULT' | 'INPUT_UNAVAILABLE' | null
  review: { actor: string; at: string; selected?: ExtractionSelection[] | null; comment?: string | null } | null
}
export interface ExtractionGenerate { expectedOriginalId: string; expectedOriginalDigest: string; method: ExtractionMethod; targetDigest: string | null; externalSendConfirmed: boolean }
export interface ExtractionReview { expectedRunVersion: number; action: 'CONFIRM' | 'DISMISS'; selected?: ExtractionSelection[]; comment: string }
export interface ExtractionEdits { runId: string; version: number; selected: ExtractionField[]; values: Partial<Record<ExtractionField, string>>; comment: string }

export const extractionFields = { INVOICE_CODE: '发票代码', INVOICE_NUMBER: '发票号码', ISSUE_DATE: '开票日期', BUYER_NAME: '购买方名称', BUYER_TAX_ID: '购买方税号', SELLER_NAME: '销售方名称', SELLER_TAX_ID: '销售方税号', CURRENCY: '币种', NET_AMOUNT: '不含税金额', TAX_AMOUNT: '税额', GROSS_AMOUNT: '含税金额' }
export const extractionStatuses: Record<ExtractionStatus, string> = { QUEUED: '等待提取', RUNNING: '正在提取', COMPLETED: '待本人核对', FAILED: '提取未完成', CONFIRMED: '已保存本人确认值', DISMISSED: '已放弃本次结果' }
export const extractionMethods: Record<ExtractionMethod, string> = { STRUCTURED_XML: '本地 XML 提取', MODEL: '模型识别' }
export const extractionPath = (id: string) => '/invoices/' + encodeURIComponent(id) + '/extraction-runs'
const versions: Record<ExtractionStatus, number> = { QUEUED: 1, RUNNING: 2, COMPLETED: 3, FAILED: 3, CONFIRMED: 4, DISMISSED: 4 }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const has = (v: object, key: string) => Object.prototype.hasOwnProperty.call(v, key)
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim()
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
const digest = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const time = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const method = (v: unknown): v is ExtractionMethod => v === 'MODEL' || v === 'STRUCTURED_XML'
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '提取响应不完整，请刷新原记录；写入结果未知时先恢复原操作。' })
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

function source(value: unknown, invoiceId: string): value is ExtractionSource {
  if (!object(value) || value.invoiceId !== invoiceId || !uuid(value.invoiceId) || !uuid(value.originalId) || !digest(value.originalDigest)
      || !['XML', 'PNG', 'JPEG', 'PDF', 'OFD'].includes(String(value.format)) || !Number.isSafeInteger(value.originalBytes)
      || (value.originalBytes as number) < 1 || (value.originalBytes as number) > 20 * 1024 * 1024
      || !Number.isInteger(value.pageCount) || (value.pageCount as number) < 1 || (value.pageCount as number) > 10) return false
  return !['XML', 'PNG', 'JPEG'].includes(String(value.format)) || value.pageCount === 1
}
/** 原件身份独立于发票查验版本；查验更新不能把提取值变成查验事实。 */
export function extractionMatches(input: ExtractionSource, item: InvoiceItem) {
  return item.original.status === 'READY' && input.invoiceId === item.id && input.originalId === item.original.id
    && input.originalDigest === item.original.sha256 && input.format === item.original.format && input.originalBytes === item.original.size
}
/** 实际处理方式决定确认范围；损坏或互相矛盾的目录不能开启外发。 */
export function readExtractionOptions(value: unknown, invoiceId: string): ExtractionOptions {
  if (!object(value) || !source(value.input, invoiceId) || !method(value.method) || typeof value.enabled !== 'boolean'
      || !Array.isArray(value.supportedFormats) || !value.supportedFormats.length || value.supportedFormats.length > 5
      || !value.supportedFormats.every(f => ['XML', 'PNG', 'JPEG', 'PDF', 'OFD'].includes(String(f)))
      || new Set(value.supportedFormats).size !== value.supportedFormats.length || !value.supportedFormats.includes(value.input.format)) throw unreadable()
  if (value.method === 'STRUCTURED_XML') {
    if (value.input.format !== 'XML' || value.transmission !== 'NONE' || !value.enabled || value.unavailableCode !== null
        || [value.providerId, value.model, value.destination, value.targetDigest].some(v => v !== null)) throw unreadable()
  } else {
    if (value.transmission !== (value.input.format === 'XML' ? 'XML_TEXT' : value.input.format === 'OFD' ? 'RENDERED_PAGES' : 'ORIGINAL_BYTES')) throw unreadable()
    if (value.enabled ? !text(value.providerId) || !text(value.model) || !text(value.destination) || !digest(value.targetDigest) || value.unavailableCode !== null
      : !text(value.unavailableCode) || [value.providerId, value.model, value.destination, value.targetDigest].some(v => v !== null)) throw unreadable()
  }
  return value as unknown as ExtractionOptions
}
function summary(value: unknown): value is ExtractionSummary {
  return object(value) && uuid(value.id) && method(value.method) && typeof value.status === 'string' && has(versions, value.status)
    && versions[value.status as ExtractionStatus] === value.version && time(value.createdAt)
}
/** 有界历史只作选择索引，页号必须对应原读取。 */
export function readExtractionPage(value: unknown, page: number): ExtractionPage {
  if (!object(value) || !Array.isArray(value.items) || value.items.length > 20 || !value.items.every(summary)
      || new Set(value.items.map(v => v.id)).size !== value.items.length || value.page !== page || value.pageSize !== 20
      || !Number.isSafeInteger(value.total) || (value.total as number) < value.items.length) throw unreadable()
  return value as unknown as ExtractionPage
}
/** 字符串保留票号前导零与金额精度；不自动纠正字段间的算术或真实性。 */
export function validExtractionValue(field: unknown, value: unknown): value is string {
  if (typeof field !== 'string' || !has(extractionFields, field) || !text(value) || value !== value.trim() || /[\u0000-\u001f\u007f-\u009f]/.test(value)) return false
  if (field === 'INVOICE_CODE' || field === 'INVOICE_NUMBER') return /^[0-9]{1,32}$/.test(value)
  if (field === 'ISSUE_DATE') return /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value + 'T00:00:00Z')) && new Date(value + 'T00:00:00Z').toISOString().slice(0, 10) === value
  if (field === 'CURRENCY') return /^[A-Z]{3}$/.test(value)
  if (field.endsWith('_AMOUNT')) return /^-?(0|[1-9][0-9]{0,15})(\.[0-9]{1,2})?$/.test(value)
  return value.length <= (field.endsWith('_TAX_ID') ? 64 : 256)
}
/** 详情绑定本人原票和原运行，模型值、来源与人工值分别校验。 */
export function readExtractionDetail(value: unknown, invoiceId: string, runId: string): ExtractionDetail {
  if (!summary(value) || value.id !== runId) throw unreadable()
  const d = value as ExtractionDetail
  if (!source(d.input, invoiceId) || typeof d.canConfirm !== 'boolean' || d.canConfirm && d.status !== 'COMPLETED'
      || d.method === 'STRUCTURED_XML' && d.input.format !== 'XML') throw unreadable()
  if (d.status === 'QUEUED' ? d.startedAt !== null : !time(d.startedAt) || Date.parse(d.startedAt) < Date.parse(d.createdAt)) throw unreadable()
  if (['QUEUED', 'RUNNING'].includes(d.status) ? d.completedAt !== null : !time(d.completedAt) || Date.parse(d.completedAt) < Date.parse(d.startedAt!)) throw unreadable()
  if (['COMPLETED', 'CONFIRMED', 'DISMISSED'].includes(d.status)) {
    const s = d.suggestion
    if (!s || s.method !== d.method || !text(s.providerId) || !text(s.processorVersion) || s.contractVersion !== 'invoice-extraction-v1'
        || !Array.isArray(s.proposals) || s.proposals.length > 11 || new Set(s.proposals.map(p => p?.field)).size !== s.proposals.length) throw unreadable()
    for (const p of s.proposals) {
      if (!p || !validExtractionValue(p.field, p.value) || !['LOW', 'MEDIUM', 'HIGH'].includes(p.confidence)
          || !Array.isArray(p.evidence) || !p.evidence.length || p.evidence.length > 4) throw unreadable()
      for (const e of p.evidence) {
        if (!e || e.originalId !== d.input.originalId || e.originalDigest !== d.input.originalDigest || !Number.isInteger(e.page) || e.page < 1 || e.page > d.input.pageCount
            || !text(e.quote) || e.quote.length > 512 || (d.method === 'MODEL' ? e.xmlPath != null : typeof e.xmlPath !== 'string' || e.xmlPath.length > 512 || !/^(\/[A-Za-z_][A-Za-z0-9_.-]*\[1\]){1,8}$/.test(e.xmlPath))) throw unreadable()
      }
    }
  } else if (d.suggestion !== null) throw unreadable()
  if (d.status === 'FAILED' ? !['MODEL_UNAVAILABLE', 'EXECUTION_TIMEOUT', 'INVALID_RESULT', 'INPUT_UNAVAILABLE'].includes(String(d.failure)) : d.failure !== null) throw unreadable()
  if (['CONFIRMED', 'DISMISSED'].includes(d.status)) {
    const r = d.review
    if (!r || !text(r.actor) || !time(r.at) || Date.parse(r.at) < Date.parse(d.completedAt!) || r.comment != null && (typeof r.comment !== 'string' || r.comment.length > 2000)) throw unreadable()
    if (d.status === 'CONFIRMED') {
      if (!Array.isArray(r.selected) || !r.selected.length || r.selected.length > 11 || new Set(r.selected.map(v => v?.field)).size !== r.selected.length
          || !r.selected.every(v => v && validExtractionValue(v.field, v.value) && d.suggestion!.proposals.some(p => p.field === v.field))) throw unreadable()
    } else if (r.selected != null) throw unreadable()
  } else if (d.review !== null) throw unreadable()
  return d
}
/** 清除原请求前校验安全回执；错误运行编号或版本仍属于结果未知。 */
export function validateExtractionReceipt(value: unknown, path: string, body: string): ExtractionReceipt {
  const review = /^\/invoices\/[^/?]+\/extraction-runs\/([^/?]+)\/review$/.exec(path), input = JSON.parse(body) as ExtractionReview
  if (!object(value) || !uuid(value.id) || review && value.id !== decodeURIComponent(review[1]!)
      || value.status !== (review ? input.action === 'CONFIRM' ? 'CONFIRMED' : 'DISMISSED' : 'QUEUED')
      || value.version !== (review ? input.expectedRunVersion + 1 : 1)) throw unreadable()
  return value as unknown as ExtractionReceipt
}
/** 只生成本人明确勾选的字段，不修改原候选。 */
export function extractionSelections(detail: ExtractionDetail, selected: ExtractionField[], values: Partial<Record<ExtractionField, string>>): ExtractionSelection[] {
  if (!selected.length || selected.length > 11 || new Set(selected).size !== selected.length) throw new Error('请逐项勾选要保存的字段。')
  return selected.map(field => {
    if (!detail.suggestion?.proposals.some(p => p.field === field)) throw new Error('所选字段不在本次结果中，请刷新核对。')
    const value = has(values, field) ? values[field] : undefined
    if (!validExtractionValue(field, value)) throw new Error(`请核对“${extractionFields[field]}”的格式；票号和金额不可留空或改为科学计数法。`)
    return { field, value }
  })
}

const focus = new Map<string, string>(), drafts = new Map<string, ExtractionEdits>()
const key = (scope: string, invoiceId: string) => JSON.stringify([scope, invoiceId])
/** 未发送复核仅存在按账号隔离的内存中，切换页面可接续，不持久存储票面。 */
export const extractionDrafts = {
  get: (scope: string, invoiceId: string) => { const value = drafts.get(key(scope, invoiceId)); return value ? copy(value) : null },
  set: (scope: string, invoiceId: string, value: ExtractionEdits) => { drafts.set(key(scope, invoiceId), copy(value)) },
  clear: (scope: string, invoiceId: string) => { drafts.delete(key(scope, invoiceId)) },
  hasDrafts: () => drafts.size > 0
}
export function rememberExtraction(scope: string, invoiceId: string, runId: string) { focus.set(key(scope, invoiceId), runId) }
export function focusedExtraction(scope: string, invoiceId: string) { return drafts.get(key(scope, invoiceId))?.runId ?? focus.get(key(scope, invoiceId)) ?? '' }
/** 已确认原写入时只清除对应版本且仍等于原请求的修订，不丢弃之后的新编辑。 */
export function acknowledgeExtraction(scope: string, path: string, body: string, receipt: ExtractionReceipt) {
  const invoiceId = decodeURIComponent(path.split('/')[2]!)
  rememberExtraction(scope, invoiceId, receipt.id)
  if (!path.endsWith('/review')) return
  const input = JSON.parse(body) as ExtractionReview, draft = extractionDrafts.get(scope, invoiceId)
  if (!draft || draft.runId !== receipt.id || draft.version !== input.expectedRunVersion || draft.comment !== input.comment) return
  if (input.action === 'DISMISS' || input.selected?.length === draft.selected.length && input.selected.every(v => draft.selected.includes(v.field) && draft.values[v.field] === v.value)) extractionDrafts.clear(scope, invoiceId)
}
const messages: Record<string, string> = {
  AGENT_MODEL_DISABLED: '模型服务尚未启用，请联系管理员配置后刷新。', AGENT_MODEL_UNCONFIGURED: '模型配置不可用，请联系管理员检查。',
  AGENT_TARGET_CHANGED: '模型目的地已变化，请重新读取来源并核对外发范围。', AGENT_INPUT_CHANGED: '原件或处理方式已变化，请刷新票据后重新核对。',
  AGENT_RUN_ACTIVE: '本票据已有正在执行的提取，请刷新历史记录。', CONCURRENCY_CONFLICT: '这条记录已更新，请刷新后重新核对。',
  AGENT_RUN_STATE_CONFLICT: '这条记录已经处理，请刷新历史。', INVALID_AGENT_REVIEW: '请核对所选字段和人工修订。',
  INVALID_AGENT_OUTPUT: '字段格式或来源不符合要求，请核对后保存。', INVALID_AGENT_CONSENT: '请重新核对处理方式和本次外发确认。',
  INVOICE_EXTRACTION_FORMAT_UNSUPPORTED: '当前不支持此格式的提取，请核对原件格式。',
  INVOICE_EXTRACTION_SOURCE_UNAVAILABLE: '原件无法完整读取或超过提取限制，请下载原件核对。最多 20 MiB、10 页。',
  INVOICE_EXTRACTION_SOURCE_BUSY: '原件解析正在处理其他请求，请稍后刷新。',
  FORBIDDEN: '当前账号不可读取或处理这份本人提取记录。', NOT_FOUND: '记录不存在或当前账号不可读取。',
  IDEMPOTENCY_KEY_EXPIRED: '原请求恢复期限已过，请核对历史原记录；结果未明确前不要重复发起。',
  RESPONSE_UNREADABLE: unreadable().message,
  MODEL_UNAVAILABLE: '模型服务不可用，请核对配置后明确发起新的提取。', EXECUTION_TIMEOUT: '执行已超时，本次任务不会自动重新发送。',
  INVALID_RESULT: '结果未通过字段或来源校验，没有保存票面候选。', INPUT_UNAVAILABLE: '执行时本人资格或原件不可用，请核对后重新发起。'
}
export function extractionError(cause: unknown): string {
  const code = (cause as { code?: string } | null)?.code
  return code && has(messages, code) ? messages[code]! : cause instanceof Error && /[\u4e00-\u9fff]/.test(cause.message)
    ? cause.message : '请求未完成，请刷新核对；结果未知的写入请先使用“恢复上次操作”。'
}
