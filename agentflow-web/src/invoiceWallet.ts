import type { Money } from './expenses.js'

export type InvoiceFormat = 'PDF' | 'OFD' | 'PNG' | 'JPEG' | 'XML'
export interface InvoiceOriginal { id: string; filename: string; size: number; sha256: string; format: InvoiceFormat; status: 'UPLOADING' | 'READY' | 'FAILED'; createdAt: string }
export interface InvoiceOccupationView {
  status: 'OCCUPIED' | 'CONSUMED'
  expense?: { reportId: string; applicationId: string; businessNo: string; roundNo: number; lineNo: number } | null
}
export interface InvoiceConflict { lineNo: number; invoiceId: string; occupation: InvoiceOccupationView }
export interface InvoiceItem {
  id: string; version: number; original: InvoiceOriginal
  verification: 'PENDING' | 'VERIFIED' | 'FAILED'; occupation: 'AVAILABLE' | 'OCCUPIED' | 'CONSUMED'
  facts: null | { key: { type: string; code: string | null; number: string }; legalEntityId: string; gross: Money; tax: Money; issueDate: string; originalDigest: string; reference: string; verifiedAt: string; validUntil: string }
  use: null | { reportId: string; roundNo: number; lineNo: number }; failureCode: string | null; checkedAt: string | null
  activeClaim?: InvoiceOccupationView | null
}
/** 来源只使用服务端当前授权结果，缺少单号时不以内部标识推测归属。 */
export function invoiceOccupationLabel(claim: InvoiceOccupationView): string {
  const status = claim.status === 'CONSUMED' ? '已核销' : '已占用', expense = claim.expense
  return expense ? `${status} · 报销单 ${expense.businessNo} · 第 ${expense.roundNo} 轮 · 第 ${expense.lineNo} 行`
    : `${status} · 占用来源无权查看或暂不可用，请联系财务核对`
}
export interface InvoiceUploadInput { filename: string; size: number; sha256: string; format: InvoiceFormat }
export interface InvoiceWalletOptions { enabled: boolean; maxFileBytes: number; maxWalletBytes: number; maxWalletUploads: number; formats: InvoiceFormat[] }
export interface InvoiceVerificationOptions { invoiceVersion: number; enabled: boolean; unavailableCode: string | null; destination: string | null; targetDigest: string | null; confirmedLegalEntityId: string | null; activeVerificationId?: string | null }
export interface InvoiceVerificationInput { expectedInvoiceVersion: number; legalEntityId: string; targetDigest: string }
export interface InvoiceVerificationJob {
  id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'REJECTED' | 'UNAVAILABLE'
  invoiceVersion: number; resultingInvoiceVersion: number | null; legalEntityId: string
  createdAt: string; startedAt: string | null; completedAt: string | null; rejection: string | null; failure: string | null
}
export const verificationStatuses = { PENDING: '尚未查验', VERIFIED: '查验通过', FAILED: '查验不通过' }
export const occupationStatuses = { AVAILABLE: '未占用', OCCUPIED: '报销占用中', CONSUMED: '已核销' }
export const verificationJobStatuses = { QUEUED: '等待查验', RUNNING: '正在查验', SUCCEEDED: '本次查验通过', REJECTED: '本次业务拒绝', UNAVAILABLE: '本次未取得可信结论' }
const issues: Record<string, string> = {
  INVOICE_INVALID: '票据无效', INVOICE_CANCELLED: '票据已作废', INVOICE_BUYER_MISMATCH: '票面买方与所选法人不一致', LEGAL_ENTITY_UNAVAILABLE: '所选法人不可用',
  NOT_CONFIGURED: '尚未配置查验服务', FINANCE_GATEWAY_UNAVAILABLE: '尚未配置财务服务', INVOICE_ORIGINAL_NOT_READY: '原件尚未上传完成',
  TIMEOUT: '查验超时，请核对原任务后明确重试', CONNECTION: '暂时无法连接查验服务', AUTHENTICATION: '查验服务连接凭据不可用', REMOTE_FAILURE: '查验服务暂时不可用',
  INVALID_RESPONSE: '查验结果未通过协议校验', RESPONSE_TOO_LARGE: '查验结果未通过协议校验', TARGET_CHANGED: '查验目标已变化，请刷新并核对', FINANCE_TARGET_CHANGED: '查验目标已变化，请刷新并核对',
  ORIGINAL_UNAVAILABLE: '原件不可用，请核对上传状态', INVOICE_CHANGED: '发票已变化，本次结果未写入', INTERNAL_ERROR: '查验未完成，请联系管理员核对',
  INVOICE_VERIFICATION_ACTIVE: '已有查验正在执行，请刷新当前任务', INVOICE_TITLE_MISMATCH: '已确认的买方法人不能更换', CONCURRENCY_CONFLICT: '发票已更新，请刷新后再操作',
  FILE_TOO_LARGE: '原件超过当前大小限制', FILE_SIZE_MISMATCH: '文件大小与原登记不一致', FILE_DIGEST_MISMATCH: '文件摘要与原登记不一致',
  INVOICE_ORIGINAL_FORMAT_MISMATCH: '文件内容与登记格式不一致', INVOICE_WALLET_QUOTA_EXCEEDED: '票夹累计容量或上传次数已达上限', FILE_STORAGE_UNAVAILABLE: '原件存储尚未就绪',
  IDEMPOTENCY_KEY_EXPIRED: '原登记确认期限已过，请核对票夹中的原记录并联系管理员，勿另建重复原件',
  REQUEST_TIMEOUT: '请求结果未确认，请恢复原操作', RESPONSE_UNREADABLE: '响应未完整接收，请恢复原操作', PENDING_REQUEST_CHANGED: '上次结果尚未确认，请使用页面的恢复入口'
}
export function invoiceIssue(code: string | null | undefined): string { return code ? issues[code] ?? '请核对本次查验结果或联系财务' : '' }
export function invoiceError(cause: unknown): string {
  const value = cause as { status?: number; code?: string }
  if (value.code && issues[value.code]) return issues[value.code]!
  if ([401, 403, 404].includes(value.status ?? 0)) return '当前无法访问这份本人票据，请恢复原账号后刷新。'
  return '票夹请求未完成，请刷新核对；上传结果未知时请继续原上传，勿另建重复原件。'
}
/** 文件扩展名只供登记，实际字节、格式与 SHA-256 仍由服务端核验。 */
export function invoiceFileFormat(file: File, options: InvoiceWalletOptions): InvoiceFormat {
  if (!options.enabled) throw new Error('原件存储尚未就绪。')
  if (!file.size || file.size > options.maxFileBytes) throw new Error('文件不能为空，也不能超过页面显示的单份上限。')
  if (!file.name || file.name.length > 255 || /[\u0000-\u001f\u007f/\\]/.test(file.name)) throw new Error('文件名不能含路径或控制字符，且最多 255 个字符。')
  const formats: Readonly<Record<string, InvoiceFormat | undefined>> = { pdf: 'PDF', ofd: 'OFD', png: 'PNG', jpg: 'JPEG', jpeg: 'JPEG', xml: 'XML' }
  const format = formats[file.name.split('.').pop()!.toLowerCase()]
  if (!format || !options.formats.includes(format)) throw new Error('请选择页面支持的 PDF、OFD、PNG、JPEG 或 XML 原件。')
  return format
}
export interface InvoiceUploadAttempt { file: File; key: string; invoiceId?: string; input?: InvoiceUploadInput; registrationSent: boolean }
/** 原文件和原请求身份仅保留在按账号隔离的内存中，导航返回可继续，不落浏览器持久存储。 */
export class InvoiceUploads {
  private readonly attempts = new Map<string, InvoiceUploadAttempt>()
  get(scope: string) { return this.attempts.get(scope) ?? null }
  set(scope: string, attempt: InvoiceUploadAttempt) { this.attempts.set(scope, attempt) }
  clear(scope: string) { this.attempts.delete(scope) }
  hasPending() { return this.attempts.size > 0 }
}
export const invoiceUploads = new InvoiceUploads()
