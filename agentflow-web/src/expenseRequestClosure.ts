export interface ExpenseRequestCloseInput { expectedVersion: number; comment: string }
export interface ExpenseRequestCloseReceipt { requestId: string; applicationId: string; version: number; closed: true; eventId: string }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const keys = ['requestId', 'applicationId', 'version', 'closed', 'eventId']

/** 清除原请求前核对目标、相邻版本与最小回执，不能把损坏的 200 当作已确认。 */
export function readExpenseRequestCloseReceipt(value: unknown, requestId: string, input: ExpenseRequestCloseInput): ExpenseRequestCloseReceipt {
  const receipt = value as Partial<ExpenseRequestCloseReceipt> | null
  if (!receipt || Array.isArray(receipt) || typeof receipt !== 'object'
    || Object.keys(receipt).length !== keys.length || !keys.every(key => Object.prototype.hasOwnProperty.call(receipt, key))
    || receipt.requestId !== requestId || !uuid.test(receipt.requestId)
    || typeof receipt.applicationId !== 'string' || !uuid.test(receipt.applicationId)
    || typeof receipt.eventId !== 'string' || !uuid.test(receipt.eventId)
    || !Number.isSafeInteger(receipt.version) || receipt.version !== input.expectedVersion + 1 || receipt.closed !== true) {
    throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '关闭结果尚未确认，请恢复上次操作。' }
  }
  return receipt as ExpenseRequestCloseReceipt
}

/** 不在页面显示原始异常或财务数据；明确失败后也须刷新所见额度版本。 */
export function expenseRequestCloseError(error: unknown): string {
  const code = (error as { code?: string } | null)?.code
  if (code === 'CONCURRENCY_CONFLICT') return '额度已被其他操作更新，请刷新后核对余额，再决定是否关闭。'
  if (code === 'EXPENSE_REQUEST_CLOSED') return '此额度已经关闭，请刷新记录。'
  if (code === 'NOT_FOUND') return '额度不存在或当前账号无权操作，请刷新本人记录。'
  if (['INVALID_REQUEST', 'VALIDATION_ERROR'].includes(code ?? '')) return '关闭原因或版本无效，请刷新记录并重新填写。'
  if (isDefinitiveWriteFailure(error)) return '当前请求已结束，请刷新本人记录，核对额度状态后再操作。'
  return '关闭结果尚未确认，请通过页面上方的“恢复上次操作”核对原请求。'
}
import { isDefinitiveWriteFailure } from './pendingWrites.js'
