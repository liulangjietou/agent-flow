export const callbackStatuses = { RECEIVED: '已接收', WAITING: '等待处理', QUERY_QUEUED: '已登记原号查询', REVIEW_REQUIRED: '需人工处理' } as const
export const callbackReasons = {
  QUERY_REQUESTED: '原交易查询已排队，银行结果请在财务办理页核对。',
  ALREADY_OBSERVED: '收到已观察版本的信号，已重新查询原交易。',
  BUSY: '原银行请求仍在执行，等待结束后处理。',
  NEVER_DISPATCHED: '原指令从未外发，请先核对发送方的事件来源。',
  TARGET_CHANGED: '原资金网关与当前配置不一致，请核对部署配置。',
  SOURCE_MISSING: '原付款依据不一致，请核对原交易。',
  PROCESSING_FAILED: '本地处理暂未完成，请检查服务状态后恢复。'
} as const
export interface PaymentCallbackView {
  id: string; eventId: string; kind: 'EMPLOYEE' | 'SUPPLIER'; authorizationId: string; sourceRevision: number
  version: number; status: keyof typeof callbackStatuses; receivedAt: string; updatedAt: string; nextAttemptAt: string | null
  failures: number; queryVersion: number | null; reason: keyof typeof callbackReasons | null; requestedBy: string | null; requestReason: string | null
}
export interface PaymentCallbackPage { items: PaymentCallbackView[]; nextBeforeId: string | null }
export interface PaymentCallbackDetail { callback: PaymentCallbackView; history: PaymentCallbackView[] }
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const number = (value: unknown, minimum = 1): value is number => Number.isSafeInteger(value) && Number(value) >= minimum
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const own = (values: object, key: PropertyKey) => Object.prototype.hasOwnProperty.call(values, key)
const invalid = () => new Error('回调处理记录不完整或已变化，请刷新核对。')

/** 只接受运行状态之间一致的投影，查询排队不能被显示为付款成功。 */
export function validatePaymentCallback(raw: unknown): PaymentCallbackView {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw invalid()
  const input = raw as PaymentCallbackView
  // 服务端省略空字段；在读取边界统一为空值，后续状态校验仍要求必要的时间、原因及查询版本。
  const value: PaymentCallbackView = { ...input, nextAttemptAt: input.nextAttemptAt ?? null, queryVersion: input.queryVersion ?? null,
    reason: input.reason ?? null, requestedBy: input.requestedBy ?? null, requestReason: input.requestReason ?? null }
  if (!uuid(value.id) || !uuid(value.authorizationId) || !text(value.eventId) || value.eventId.length > 128
      || !['EMPLOYEE', 'SUPPLIER'].includes(value.kind) || !number(value.sourceRevision) || !number(value.version)
      || !own(callbackStatuses, value.status) || !date(value.receivedAt) || !date(value.updatedAt) || Date.parse(value.updatedAt) < Date.parse(value.receivedAt)
      || !number(value.failures, 0) || value.failures > 10 || value.reason !== null && !own(callbackReasons, value.reason)
      || (value.requestedBy === null) !== (value.requestReason === null)
      || value.requestedBy !== null && (!text(value.requestedBy) || !text(value.requestReason) || value.requestReason.length > 500)) throw invalid()
  const pending = ['RECEIVED', 'WAITING'].includes(value.status)
  if (pending ? !date(value.nextAttemptAt) || Date.parse(value.nextAttemptAt) < Date.parse(value.updatedAt) : value.nextAttemptAt !== null) throw invalid()
  if (value.status === 'QUERY_QUEUED' ? !number(value.queryVersion) : value.queryVersion !== null) throw invalid()
  const reasons = { RECEIVED: [null], WAITING: ['BUSY', 'PROCESSING_FAILED'], QUERY_QUEUED: ['QUERY_REQUESTED', 'ALREADY_OBSERVED'], REVIEW_REQUIRED: ['NEVER_DISPATCHED', 'TARGET_CHANGED', 'SOURCE_MISSING', 'PROCESSING_FAILED'] }
  if (!(reasons[value.status] as (string | null)[]).includes(value.reason)) throw invalid()
  return value
}
/** 游标必须为真实末行；翻页整页替换，禁止重复或未前进的历史页。 */
export function validateCallbackPage(raw: unknown, beforeId?: string): PaymentCallbackPage {
  const input = raw as PaymentCallbackPage
  if (!input || !Array.isArray(input.items) || input.items.length > 25) throw invalid()
  const page = { items: input.items.map(validatePaymentCallback), nextBeforeId: input.nextBeforeId ?? null }
  if (page.nextBeforeId !== null && !uuid(page.nextBeforeId)) throw invalid()
  if (new Set(page.items.map(item => item.id)).size !== page.items.length || page.items.some(item => item.id === beforeId)
      || page.nextBeforeId !== null && (page.nextBeforeId === beforeId || page.nextBeforeId !== page.items[page.items.length - 1]?.id)) throw invalid()
  return page
}
const sameEvent = (first: PaymentCallbackView, second: PaymentCallbackView) => first.id === second.id && first.eventId === second.eventId
  && first.kind === second.kind && first.authorizationId === second.authorizationId && first.sourceRevision === second.sourceRevision && first.receivedAt === second.receivedAt
/** 详情与每次修订都固定同一事件，最多展示最新五十次处理。 */
export function validateCallbackDetail(raw: unknown, id: string): PaymentCallbackDetail {
  const input = raw as PaymentCallbackDetail
  if (!input || !Array.isArray(input.history) || input.history.length < 1 || input.history.length > 50) throw invalid()
  const detail = { callback: validatePaymentCallback(input.callback), history: input.history.map(validatePaymentCallback) }
  if (detail.callback.id !== id) throw invalid()
  detail.history.forEach((item, index) => {
    if (!sameEvent(item, detail.callback) || item.version !== detail.callback.version - index || Date.parse(item.updatedAt) > Date.parse(detail.callback.updatedAt)) throw invalid()
  })
  if ((Object.keys(detail.callback) as (keyof PaymentCallbackView)[]).some(key => detail.history[0][key] !== detail.callback[key])) throw invalid()
  return detail
}
/** 恢复只携带原记录版本和人工原因，不能添加资金事实。 */
export function callbackRetryInput(value: PaymentCallbackView, reason: string) {
  validatePaymentCallback(value)
  if (value.status !== 'REVIEW_REQUIRED') throw invalid()
  const comment = reason.trim()
  if (!comment || comment.length > 500) throw new Error('请填写 1–500 字的核对与恢复原因。')
  return { expectedVersion: value.version, reason: comment }
}
/** 回执必须精确对应本次原事件恢复；之后仍以服务端刷新结果为准。 */
export function validateCallbackRetry(raw: unknown, previous: PaymentCallbackView, reason: string): PaymentCallbackView {
  const value = validatePaymentCallback(raw)
  if (!sameEvent(value, previous) || value.version !== previous.version + 1 || value.status !== 'RECEIVED'
      || value.failures !== 0 || value.requestReason !== reason.trim() || !text(value.requestedBy)) throw invalid()
  return value
}
export function callbackError(error: unknown): string {
  const code = (error as { code?: string })?.code
  const labels: Record<string, string> = { FORBIDDEN: '当前账号没有回调管理权限。', UNAUTHENTICATED: '登录已失效，请恢复原账号后继续。', NOT_FOUND: '该回调记录已不可访问。', CONCURRENCY_CONFLICT: '处理状态已变化，请刷新后核对。', PAYMENT_CALLBACK_INVALID: '请求参数无效，请重新核对。' }
  return code && labels[code] || (error instanceof Error ? error.message : '回调状态暂时不可用，请稍后刷新。')
}
