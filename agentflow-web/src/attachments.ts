/** 附件使用申请和可选轮次的授权上下文，账号身份用于清理迟到响应。@author owlzhangfq@gmail.com */
export interface AttachmentContext { applicationId: string; expectedVersion?: number; roundNo?: number; scopeKey: string }
export interface AttachmentMetadata { id: string; fieldPath: string; filename: string; size: number; sha256: string; status: 'UPLOADING' | 'READY' | 'FAILED' }
export interface AttachmentOptions { enabled: boolean; maxFileBytes: number; maxApplicationBytes: number; maxApplicationUploads: number; maxAttachmentsPerField: number; contentScanAvailable: boolean }
export interface AttachmentInput { expectedVersion: number; fieldPath: string; filename: string; size: number; sha256: string }
/** 表单中只使用规范 UUID 列表，不接受路径或 URL。 */
export function attachmentIds(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) : []
}
/** 字节数来自文件和服务端元数据，不把未知大小显示为零。 */
export function fileSize(bytes: number): string { return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB` }
/** 相同文件的重试继续使用原登记身份，内容摘要保留完整 SHA-256。 */
export async function fileDigest(file: Blob): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())), byte => byte.toString(16).padStart(2, '0')).join('')
}
