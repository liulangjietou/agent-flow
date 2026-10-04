import type { FormSchema } from './formSchema.js'
import { attachmentIds, type AttachmentMetadata } from './attachments.js'

/** 页面只持有公开签署事实；服务账号、凭据与原始回执不进入浏览器。@author owlzhangfq@gmail.com */
export const signatureStatuses = { QUEUED: '等待发送', SENDING: '正在发送', UNKNOWN: '结果待确认', QUERYING: '正在查询原操作', PENDING: '等待服务方签署', COLLECTING: '正在收集签署结果', FETCHING_FILES: '正在保存签署结果', SIGNED: '签署结果已完整保存', DECLINED: '服务方已拒签', CANCELLED: '签署已取消', EXPIRED: '发送授权已到期' } as const
export type SignatureStatus = keyof typeof signatureStatuses
export interface SignatureReceipt { id: string; version: string; status: SignatureStatus }
export interface SignaturePage { items: SignatureReceipt[]; nextAfterId?: string | null }
export interface SignatureProfile { key: string; version: string; name: string }
export interface SignatureOptions { enabled: boolean; profiles: SignatureProfile[]; maxDocuments: number; maxDocumentBytes: number; maxTotalBytes: number; maxAuthorizationSeconds: number }
export interface SignatureDocument { id: string; filename: string; originalBytes: number; signedBytes?: number | null; downloadable: boolean }
export interface SignatureView { operation: SignatureReceipt; roundNo: number; profileKey: string; profileVersion: string; authorizedBy: string; purpose: string; authorizedAt: string; validUntil: string; updatedAt: string; nextAttemptAt?: string | null; failure?: string | null; canCancel: boolean; documents: SignatureDocument[] }
export interface SignatureInput { roundNo: number; expectedVersion: string; profileKey: string; profileVersion: string; documentIds: string[]; purpose: string; validUntil: string }
export interface SignatureSource { roundNo: number; status: string; formSchema: FormSchema | null; payload: Record<string, unknown> }
export interface SignatureChoice extends AttachmentMetadata { label: string }
const failureCodes = ['NOT_CONFIGURED', 'TARGET_CHANGED', 'OPERATION_DISABLED', 'TIMEOUT', 'CONNECTION', 'AUTHENTICATION', 'REMOTE_FAILURE', 'INVALID_RESPONSE', 'RESPONSE_TOO_LARGE', 'STALE_RESPONSE', 'CONFLICTING_RECEIPT', 'NOT_FOUND', 'ARTIFACT_MISMATCH', 'LEASE_EXPIRED', 'AUTHORIZATION_EXPIRED', 'SOURCE_UNAVAILABLE', 'STORAGE_UNAVAILABLE', 'INTERNAL_ERROR']
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const own = (v: object, k: string) => Object.prototype.hasOwnProperty.call(v, k)
const exact = (v: unknown, required: string[], optional: string[] = []): v is Record<string, unknown> => object(v) && required.every(k => own(v, k)) && Object.keys(v).every(k => required.includes(k) || optional.includes(k))
const text = (v: unknown, max = 128): v is string => typeof v === 'string' && !!v.trim() && v.length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v)
const integer = (v: unknown, maximum = Number.MAX_SAFE_INTEGER): v is number => Number.isSafeInteger(v) && (v as number) > 0 && (v as number) <= maximum
const key = (v: unknown): v is string => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/.test(v)
const time = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v))
const filename = (v: unknown): v is string => text(v, 255) && !/[\\/]/.test(v)
const optional = (v: Record<string, unknown>, k: string, check: (value: unknown) => boolean) => !own(v, k) || v[k] === null || check(v[k])
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '签署响应不完整或与当前操作不符。请刷新核对；写入结果未知时先恢复原操作。' })
/** 长整数版本保留文本，不能经过 Number 后再猜回原版本。 */
export const signatureVersion = (v: unknown): v is string => typeof v === 'string' && /^[1-9][0-9]{0,18}$/.test(v) && BigInt(v) <= 9223372036854775807n
export const signatureSourceVersion = (v: number): string | null => integer(v) ? String(v) : null
export const signaturePath = (application: string) => `/applications/${encodeURIComponent(application)}/signatures`
export const signatureWritePath = /^\/applications\/[a-f0-9-]{36}\/signatures(?:\/([a-f0-9-]{36})\/cancel)?$/
let lastOperation: { scope: string; application: string; id: string; roundNo: number } | null = null
/** 页面内仅记住一个原操作位置，不保存正文、凭据或文件资料。 */
export function rememberSignatureOperation(scope: string, application: string, id: string, roundNo?: number) {
  const original = lastOperation?.scope === scope && lastOperation.application === application && lastOperation.id === id ? lastOperation.roundNo : undefined
  if (roundNo ?? original) lastOperation = { scope, application, id, roundNo: (roundNo ?? original)! }
}
export function recalledSignatureOperation(scope: string, application: string) { return lastOperation?.scope === scope && lastOperation.application === application ? { ...lastOperation } : null }
function receipt(v: unknown): v is SignatureReceipt {
  return exact(v, ['id', 'version', 'status']) && uuid(v.id) && signatureVersion(v.version) && typeof v.status === 'string' && own(signatureStatuses, v.status)
    && (v.status === 'QUEUED' ? v.version === '1' : BigInt(v.version) >= 2n)
}
/** 原成功回执在清除恢复槽之前校验，不能从空正文或错误操作号推断成功。 */
export function validateSignatureReceipt(v: unknown, path: string, body: string): SignatureReceipt {
  const match = signatureWritePath.exec(path), input: unknown = JSON.parse(body)
  if (!match || !receipt(v) || !object(input)) throw unreadable()
  if (match[1] ? v.id !== match[1] || v.status !== 'CANCELLED' || !signatureVersion(input.expectedVersion) || BigInt(v.version) !== BigInt(input.expectedVersion) + 1n
    : v.status !== 'QUEUED' || v.version !== '1') throw unreadable()
  return v
}
export function readSignatureOptions(v: unknown): SignatureOptions {
  if (!exact(v, ['enabled', 'profiles', 'maxDocuments', 'maxDocumentBytes', 'maxTotalBytes', 'maxAuthorizationSeconds']) || typeof v.enabled !== 'boolean'
    || !Array.isArray(v.profiles) || !v.profiles.every(p => exact(p, ['key', 'version', 'name']) && key(p.key) && signatureVersion(p.version) && text(p.name, 128))
    || v.maxDocuments !== 10 || v.maxDocumentBytes !== 16777216 || v.maxTotalBytes !== 33554432 || v.maxAuthorizationSeconds !== 86400
    || new Set(v.profiles.map(p => JSON.stringify([p.key, p.version]))).size !== v.profiles.length || v.enabled && !v.profiles.length) throw unreadable()
  return v as unknown as SignatureOptions
}
/** 过滤后的空页允许有游标，但重复游标或重复操作不能造成翻页循环。 */
export function readSignaturePage(v: unknown, afterId?: string): SignaturePage {
  if (!exact(v, ['items'], ['nextAfterId']) || !Array.isArray(v.items) || v.items.length > 25 || !v.items.every(receipt)
    || new Set(v.items.map(item => item.id)).size !== v.items.length || v.items.some(item => item.id === afterId)
    || !optional(v, 'nextAfterId', uuid) || afterId && v.nextAfterId === afterId) throw unreadable()
  return v as unknown as SignaturePage
}
export function readSignatureView(v: unknown, id: string, roundNo: number, userId: string): SignatureView {
  if (!exact(v, ['operation', 'roundNo', 'profileKey', 'profileVersion', 'authorizedBy', 'purpose', 'authorizedAt', 'validUntil', 'updatedAt', 'canCancel', 'documents'], ['nextAttemptAt', 'failure'])
    || !receipt(v.operation) || v.operation.id !== id || v.roundNo !== roundNo || !integer(v.roundNo) || !key(v.profileKey) || !signatureVersion(v.profileVersion)
    || !text(v.authorizedBy) || !text(v.purpose, 1000) || ![v.authorizedAt, v.validUntil, v.updatedAt].every(time)
    || !optional(v, 'nextAttemptAt', time) || !optional(v, 'failure', x => typeof x === 'string' && failureCodes.includes(x))
    || v.canCancel !== (v.operation.status === 'QUEUED' && v.authorizedBy === userId) || !Array.isArray(v.documents) || !v.documents.length || v.documents.length > 10) throw unreadable()
  const signed = v.operation.status === 'SIGNED'
  if (!v.documents.every(d => exact(d, ['id', 'filename', 'originalBytes', 'downloadable'], ['signedBytes']) && uuid(d.id) && filename(d.filename)
    && integer(d.originalBytes, 16777216) && d.downloadable === signed && (signed ? integer(d.signedBytes, 33554432) : d.signedBytes == null))
    || new Set(v.documents.map(d => d.id)).size !== v.documents.length || v.documents.reduce((sum, d) => sum + d.originalBytes, 0) > 33554432
    || v.documents.reduce((sum, d) => sum + (d.signedBytes ?? 0), 0) > 67108864) throw unreadable()
  return v as unknown as SignatureView
}
/** 只从冻结且已投影的附件字段取得引用，不扫描自由文本或额外隐藏字段。 */
export function signatureReferences(source: SignatureSource): { id: string; fieldPath: string; label: string }[] {
  const found = new Map<string, { id: string; fieldPath: string; label: string }>()
  const add = (value: unknown, fieldPath: string, label: string) => {
    for (const id of attachmentIds(value)) {
      if (found.has(id) && found.get(id)!.fieldPath !== fieldPath) throw unreadable()
      found.set(id, { id, fieldPath, label })
    }
  }
  for (const field of source.formSchema?.fields ?? []) {
    if (field.type === 'ATTACHMENT') add(source.payload[field.key], field.key, field.label)
    if (field.type === 'TABLE' && Array.isArray(source.payload[field.key])) for (const row of source.payload[field.key] as unknown[]) {
      if (object(row)) for (const column of field.columns ?? []) if (column.type === 'ATTACHMENT') add(row[column.key], `${field.key}.${column.key}`, `${field.label} / ${column.label}`)
    }
  }
  return [...found.values()]
}
/** 有界并发复核当前权限，隐藏文件不展示；存储等故障不伪装成空附件列表。 */
export async function signatureChoices(source: SignatureSource, fetch: (id: string) => Promise<AttachmentMetadata>, signal: AbortSignal): Promise<SignatureChoice[]> {
  const refs = signatureReferences(source), files: SignatureChoice[] = []
  for (let offset = 0; offset < refs.length; offset += 4) {
    if (signal.aborted) throw new Error('aborted')
    const part = await Promise.all(refs.slice(offset, offset + 4).map(async ref => {
      let file: AttachmentMetadata
      try { file = await fetch(ref.id) } catch (cause) { if ([403, 404].includes((cause as { status?: number })?.status ?? 0)) return null; throw cause }
      if (!exact(file, ['id', 'fieldPath', 'filename', 'size', 'sha256', 'status']) || file.id !== ref.id || file.fieldPath !== ref.fieldPath
        || !filename(file.filename) || !integer(file.size, 16777216) || !/^[a-f0-9]{64}$/.test(file.sha256) || file.status !== 'READY') throw unreadable()
      return { ...file, label: ref.label }
    }))
    files.push(...part.filter((file): file is SignatureChoice => file !== null))
  }
  return files
}
export function signatureMessage(cause: unknown): string {
  const code = (cause as { code?: string })?.code, status = (cause as { status?: number })?.status
  if (status === 401 || code === 'SESSION_CHANGED') return '登录状态已变化，请重新登录原账号后恢复未确认操作。'
  if (status === 403 || status === 404) return '当前记录或原件已不可访问，请刷新权限后查看。'
  if (code === 'SIGNATURE_SOURCE_CHANGED') return '批准来源或资料已变化，请重新加载申请并核对后授权。'
  if (code === 'SIGNATURE_OPERATION_ACTIVE') return '本轮已有未结束的签署，请刷新原记录并等待结果。'
  if (code === 'SIGNATURE_RESULT_NOT_READY') return '结果文件尚未完整保存，请稍后刷新。'
  if (status === 409) return '记录已变化，请刷新核对；结果未知时保留原操作。'
  if (code === 'READ_TIMEOUT') return '读取超时，请重新加载。'
  return '操作未确认，请刷新读取；存在待恢复请求时，先恢复原操作。'
}
/** 每个读取槽隔离代次，关闭或换身份后既取消请求也拒绝迟到正文。@author owlzhangfq@gmail.com */
export class SignatureRead<T> {
  value: T | null = null; loading = false; error = ''
  private generation = 0; private controller: AbortController | null = null
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = '' }
  async load(scope: string, fetch: (signal: AbortSignal) => Promise<T>, timeoutMs = 15_000): Promise<T | null> {
    this.clear(); if (!scope) return null
    const generation = this.generation, controller = new AbortController(); this.controller = controller; this.loading = true
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([fetch(controller.signal), new Promise<never>((_, reject) => {
        timer = setTimeout(() => { controller.abort(); reject({ code: 'READ_TIMEOUT' }) }, timeoutMs)
      })])
      if (generation === this.generation) { this.value = value; return value }
    } catch (cause) { if (generation === this.generation) this.error = signatureMessage(cause) }
    finally { clearTimeout(timer); if (generation === this.generation) { this.loading = false; this.controller = null } }
    return null
  }
}
