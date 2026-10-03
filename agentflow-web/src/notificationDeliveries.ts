/** 本人外发元数据；邮箱、凭据与业务正文不进入页面模型。 */
export const deliveryStatuses = {
  PENDING: '待发送', IN_FLIGHT: '正在发送', RETRY_WAIT: '等待自动重试', ACCEPTED: '服务器已受理',
  FAILED: '发送失败', UNKNOWN: '结果未知', SUPPRESSED: '已停止提醒'
} as const
export type DeliveryStatus = keyof typeof deliveryStatuses
export const deliveryChannels = { EMAIL: '邮件', ENTERPRISE_IM: '企业 IM' } as const
export type DeliveryChannel = keyof typeof deliveryChannels
export const deliveryErrors = {
  CONSENT_REVOKED: '原通知偏好已关闭或重新设置，旧消息不会恢复发送。', RECIPIENT_INACTIVE: '当前收件账号已停用。',
  MESSAGE_UNAVAILABLE: '原消息归属或代理资格已失效，请查看当前业务状态。', BINDING_NOT_CAPTURED: '原消息没有固定收件绑定，不能补绑后重发。',
  BINDING_UNAVAILABLE: '原收件绑定或发送服务已停用，请联系部署管理员核对。', BINDING_CHANGED: '原收件目标已变更，不能转发旧消息到新地址。',
  CHANNEL_UNAVAILABLE: '当前渠道尚未接通。', SMTP_CONNECT_FAILED: '连接发送服务失败，本次尚未提交邮件。',
  SMTP_AUTH_FAILED: '发送服务认证失败，请联系部署管理员核对。', SMTP_TEMPORARY_REJECTION: '发送服务明确临时拒绝了本次邮件。',
  SMTP_PERMANENT_REJECTION: '发送服务明确拒绝了本次邮件，请先核实原因。', SMTP_RESULT_UNKNOWN: '发送中断或确认超时，接收方可能已经收到。',
  IM_TOKEN_UNAVAILABLE: '暂时无法获取企业 IM 凭据，本次尚未提交消息。', IM_AUTH_FAILED: '企业 IM 认证失败，请联系部署管理员核对应用配置。',
  IM_RECIPIENT_REJECTED: '企业 IM 收件账号无效、无应用权限或缺少许可，请先核实绑定。',
  IM_TEMPORARY_REJECTION: '企业 IM 服务繁忙或调用受限，本次已被明确拒绝。', IM_PERMANENT_REJECTION: '企业 IM 明确拒绝了本次消息，请先核实原因。',
  IM_RESULT_UNKNOWN: '企业 IM 发送中断或回执不完整，接收方可能已经收到。',
  LEASE_EXPIRED: '发送确认未及时保存，接收方可能已经收到。', WORKER_RESULT_UNKNOWN: '后台未能确认结果，接收方可能已经收到。'
} as const
export type DeliveryError = keyof typeof deliveryErrors
export interface NotificationDelivery {
  id: string; inboxId: string; channel: DeliveryChannel; status: DeliveryStatus; version: number
  attempts: number; cycleAttempts: number; errorCode: DeliveryError | null; createdAt: string; updatedAt: string
  nextAttemptAt: string | null; leaseUntil: string | null
}
export interface NotificationDeliveryEvent {
  version: number; status: DeliveryStatus; attempts: number; cycleAttempts: number; errorCode: DeliveryError | null
  actor: string | null; reason: string | null; occurredAt: string
}
export interface DeliveryPage<T> { items: T[]; nextCursor: string | null }
export interface NotificationDeliveryDetail {
  delivery: NotificationDelivery
  retry: { allowed: boolean; requiresDuplicateAcknowledgement: boolean; blockedCode: DeliveryError | 'STATE_NOT_RETRYABLE' | null }
  history: DeliveryPage<NotificationDeliveryEvent>
}
export interface NotificationDeliveryFilters { channel?: string; status?: string; limit?: number; cursor?: string }
export interface NotificationDeliveryRetryInput { expectedVersion: number; acknowledgePossibleDuplicate: boolean; reason: string }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const safeInteger = (value: unknown, min = 0): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min
const timestamp = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const owns = (value: object, key: PropertyKey) => Object.prototype.hasOwnProperty.call(value, key)
const keys = (value: Record<string, unknown>, names: string) => Object.keys(value).sort().join(',') === names.split(',').sort().join(',')
const failure = (value: unknown): value is DeliveryError | null => value === null || typeof value === 'string' && owns(deliveryErrors, value)
const status = (value: unknown): value is DeliveryStatus => typeof value === 'string' && owns(deliveryStatuses, value)
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '投递响应不完整，请重新读取；写入结果未知时请恢复原操作。' })

/** 对完整字段和状态关系做校验，空对象不能解除待确认写入。 */
export function readNotificationDelivery(value: unknown): NotificationDelivery {
  if (!object(value) || !keys(value, 'id,inboxId,channel,status,version,attempts,cycleAttempts,errorCode,createdAt,updatedAt,nextAttemptAt,leaseUntil')
      || typeof value.id !== 'string' || !uuid.test(value.id) || typeof value.inboxId !== 'string' || !uuid.test(value.inboxId)
      || typeof value.channel !== 'string' || !owns(deliveryChannels, value.channel) || !status(value.status)
      || !safeInteger(value.version, 1) || !safeInteger(value.attempts) || !safeInteger(value.cycleAttempts) || value.cycleAttempts > 3
      || value.cycleAttempts > value.attempts || !timestamp(value.createdAt) || !timestamp(value.updatedAt) || !failure(value.errorCode)) throw unreadable()
  const queued = value.status === 'PENDING' || value.status === 'RETRY_WAIT'
  if ((queued ? !timestamp(value.nextAttemptAt) : value.nextAttemptAt !== null)
      || (value.status === 'IN_FLIGHT' ? !timestamp(value.leaseUntil) : value.leaseUntil !== null)
      || (['PENDING', 'IN_FLIGHT', 'ACCEPTED'].includes(value.status) ? value.errorCode !== null : value.errorCode === null)
      || (['IN_FLIGHT', 'ACCEPTED', 'UNKNOWN', 'RETRY_WAIT'].includes(value.status) && (value.attempts < 1 || value.cycleAttempts < 1))) throw unreadable()
  return value as unknown as NotificationDelivery
}

function readEvent(value: unknown): NotificationDeliveryEvent {
  if (!object(value) || !keys(value, 'version,status,attempts,cycleAttempts,errorCode,actor,reason,occurredAt')
      || !safeInteger(value.version, 1) || !status(value.status) || !safeInteger(value.attempts)
      || !safeInteger(value.cycleAttempts) || value.cycleAttempts > 3 || value.cycleAttempts > value.attempts
      || !failure(value.errorCode) || !timestamp(value.occurredAt)
      || (value.actor === null ? value.reason !== null : typeof value.actor !== 'string' || !value.actor.length || value.actor.length > 128
        || typeof value.reason !== 'string' || !value.reason.trim() || value.reason.length > 1000)) throw unreadable()
  return value as unknown as NotificationDeliveryEvent
}
function readPage<T>(value: unknown, read: (value: unknown) => T, key: (value: T) => string | number): DeliveryPage<T> {
  if (!object(value) || !keys(value, 'items,nextCursor') || !Array.isArray(value.items) || value.items.length > 100
      || (value.nextCursor !== null && (typeof value.nextCursor !== 'string' || !value.nextCursor.length || value.nextCursor.length > 512 || !value.items.length))) throw unreadable()
  const items = value.items.map(read)
  if (new Set(items.map(key)).size !== items.length) throw unreadable()
  return { items, nextCursor: value.nextCursor as string | null }
}
/** 分页与详情都使用同一完整投递读取契约。 */
export const readDeliveryPage = (value: unknown) => readPage(value, readNotificationDelivery, item => item.id)
/** 历史按版本严格降序，不能让损坏数据冒充新操作。 */
export function readDeliveryHistory(value: unknown) {
  const page = readPage(value, readEvent, item => item.version)
  if (page.items.some((item, index) => index > 0 && item.version >= page.items[index - 1]!.version)) throw unreadable()
  return page
}
/** 查询目标、详情版本及当前恢复提示必须相互一致。 */
export function readDeliveryDetail(value: unknown, id: string): NotificationDeliveryDetail {
  if (!object(value) || !keys(value, 'delivery,retry,history') || !object(value.retry)
      || !keys(value.retry, 'allowed,requiresDuplicateAcknowledgement,blockedCode')) throw unreadable()
  const delivery = readNotificationDelivery(value.delivery), history = readDeliveryHistory(value.history), retry = value.retry
  if (delivery.id !== id || typeof retry.allowed !== 'boolean' || typeof retry.requiresDuplicateAcknowledgement !== 'boolean'
      || retry.requiresDuplicateAcknowledgement !== (delivery.status === 'UNKNOWN')
      || (retry.allowed ? retry.blockedCode !== null || !['FAILED', 'UNKNOWN'].includes(delivery.status)
        : typeof retry.blockedCode !== 'string' || !(retry.blockedCode === 'STATE_NOT_RETRYABLE' || owns(deliveryErrors, retry.blockedCode)))
      || !history.items.length || history.items[0]!.version !== delivery.version || history.items[0]!.status !== delivery.status
      || history.items.some(item => item.version > delivery.version)) throw unreadable()
  return { delivery, history, retry } as NotificationDeliveryDetail
}
/** 原键回放只确认原次排队，当前状态必须另行读取。 */
export function validateDeliveryRetryReceipt(value: unknown, id: string, input: NotificationDeliveryRetryInput) {
  const result = readNotificationDelivery(value)
  if (result.id !== id || result.version !== input.expectedVersion + 1 || result.status !== 'PENDING' || result.cycleAttempts !== 0) throw unreadable()
  return result
}

/** 两个真实分页场景共用取消与迟到隔离，列表和历史分别持有实例与上下文。 */
export class DeliveryPageQuery<T> {
  items: T[] = []
  nextCursor: string | null = null
  loaded = false
  loading = false
  error = ''
  private generation = 0
  private scope = ''
  private controller: AbortController | null = null
  constructor(private fetchPage: (cursor: string | null, signal: AbortSignal) => Promise<DeliveryPage<T>>, private key: (item: T) => string | number) {}
  /** 身份、筛选或选中投递变化时立即移除旧记录。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null; this.scope = ''
    this.items = []; this.nextCursor = null; this.loaded = false; this.loading = false; this.error = ''
  }
  /** 详情已经读取的首段历史无需再次请求。 */
  seed(scope: string, page: DeliveryPage<T>) { this.clear(); this.scope = scope; this.items = [...page.items]; this.nextCursor = page.nextCursor; this.loaded = true }
  /** 新查询从首页开始，不使用旧身份或旧筛选的游标。 */
  async load(scope: string) { this.clear(); if (!scope) return; this.scope = scope; await this.fetch(false) }
  /** 下一页失败保留原数据和游标；重复点击不并发翻页。 */
  async more() { if (!this.scope || this.loading || !this.nextCursor) return; await this.fetch(true) }
  private async fetch(append: boolean) {
    const generation = this.generation, controller = new AbortController(), cursor = append ? this.nextCursor : null
    this.controller = controller; this.loading = true; this.error = ''
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const page = await Promise.race([this.fetchPage(cursor, controller.signal), new Promise<never>((_, reject) => {
        timeout = setTimeout(() => { controller.abort(); reject({ message: '投递查询超时，请重新读取。' }) }, 12_000)
      })])
      if (generation !== this.generation) return
      const prior = new Set(this.items.map(this.key))
      if (page.nextCursor !== null && page.nextCursor === cursor || append && page.items.some(item => prior.has(this.key(item)))) throw unreadable()
      this.items = append ? [...this.items, ...page.items] : page.items; this.nextCursor = page.nextCursor; this.loaded = true
    } catch (cause) { if (generation === this.generation) this.error = (cause as { message?: string })?.message ?? '无法读取投递记录，请重试。' }
    finally { clearTimeout(timeout); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }
}
