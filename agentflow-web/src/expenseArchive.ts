import type { SettlementBinding } from './expenseSettlement'

export interface ExpenseArchiveView extends SettlementBinding {
  status: 'WAITING' | 'BLOCKED' | 'ARCHIVED'; manifestSha256: string | null; archivedAt: string | null
  originalCount: number; voucherCount: number; issue: string | null; canDownload: boolean
}
export const archiveLabels = { WAITING: '等待归档', BLOCKED: '归档条件尚未满足', ARCHIVED: '原档案已归档' }
/** 已归档允许同时出现后续争议，但未封存的状态绝不能展示下载按钮。 */
export function validateArchive(value: ExpenseArchiveView, binding: SettlementBinding): ExpenseArchiveView {
  if (!value || !(['reportId', 'applicationId', 'roundNo', 'applicationVersion', 'financialVersion'] as const).every(key => value[key] === binding[key])
      || !value.reportId || !value.applicationId || ![value.roundNo, value.applicationVersion, value.financialVersion].every(item => Number.isSafeInteger(item) && item > 0)
      || !Object.prototype.hasOwnProperty.call(archiveLabels, value.status) || typeof value.canDownload !== 'boolean'
      || ![value.originalCount, value.voucherCount].every(item => Number.isSafeInteger(item) && item >= 0)
      || value.issue !== null && (typeof value.issue !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(value.issue))) throw new Error('归档身份或状态不一致，请刷新报销明细。')
  if (value.status === 'ARCHIVED'
      ? !value.canDownload || typeof value.manifestSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.manifestSha256) || !value.archivedAt || !Number.isFinite(Date.parse(value.archivedAt))
      : value.canDownload || value.manifestSha256 !== null || value.archivedAt !== null || value.originalCount !== 0 || value.voucherCount !== 0 || !value.issue) throw new Error('归档清单尚未完整确认，请刷新核对。')
  return value
}
const issues: Record<string, string> = {
  ARCHIVE_SETTLEMENT_REQUIRED: '等待本轮发票、额度、借款及预算结算全部确认。', ARCHIVE_CHECK_PENDING: '结算已完成，正在等待后台逐项检查归档资料。',
  ARCHIVE_PAYMENT_VOUCHER_REQUIRED: '付款凭证尚未确认过账，请核对上方付款凭证状态。', ARCHIVE_ACCRUAL_REQUIRED: '原挂账凭证尚未确认，请核对上方挂账状态。',
  ARCHIVE_PAYMENT_REQUIRED: '原银行付款仍需核对，暂不作为有效归档依据。', ARCHIVE_BUDGET_REQUIRED: '预算实际占用尚未确认。',
  ARCHIVE_APPROVAL_REQUIRED: '本轮批准依据已变化，请由财务核对。', ARCHIVE_PAPER_REQUIRED: '本轮纸质单据尚未签收。',
  ARCHIVE_SOURCE_CHANGED: '核验期间原依据发生变化，后台将重新核对。', ARCHIVE_ORIGINAL_REQUIRED: '缺少本轮原始文件或提交时的查验凭据。',
  FILE_INTEGRITY_FAILED: '原始文件完整性校验未通过，请核对文件存储及备份。', FILE_STORAGE_UNAVAILABLE: '原始文件暂时无法读取，请核对文件存储。'
}
export function archiveIssue(code: string | null) { return code ? issues[code] ?? '归档依据需要进一步核对，请联系财务处理。' : '' }
export function archiveError(cause: unknown) {
  const error = cause as { status?: number; code?: string }
  if (error.status === 403 || error.status === 404) return '当前身份无权读取这份报销的完整档案。'
  if (error.code && issues[error.code]) return issues[error.code]
  if (error.code === 'ARCHIVE_NOT_READY') return '本轮资料尚未完成归档，请稍后刷新。'
  return cause instanceof Error ? cause.message : '归档读取或下载未完成，请重新核对。'
}
