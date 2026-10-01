/** 事件目录、可信收件和实际等待只暴露各自的业务事实。@author owlzhangfq@gmail.com */
export interface EventOption { key: string; version: number; name: string; sourceKey: string; eventType: string; envelopeVersion: number; enabled: boolean; availabilityRevision: number }
/** @author owlzhangfq@gmail.com */
export interface EventAvailability { revision: number; enabled: boolean; changedBy: string; changedAt: string; reason: string }
/** @author owlzhangfq@gmail.com */
export interface EventContract extends Omit<EventOption, 'enabled' | 'availabilityRevision'> { publishedBy: string; publishedAt: string; publicationReason: string; availability: EventAvailability }
/** @author owlzhangfq@gmail.com */
export interface EventDirectory { items: EventOption[]; nextAfterKey: string | null }
/** @author owlzhangfq@gmail.com */
export interface EventVersions { items: EventOption[]; nextBeforeVersion: number | null }
/** @author owlzhangfq@gmail.com */
export interface EventContractHistory { items: EventAvailability[]; nextBeforeRevision: number | null }
/** @author owlzhangfq@gmail.com */
export interface EventPublication { expectedVersion: number; name: string; sourceKey: string; eventType: string; reason: string }
/** @author owlzhangfq@gmail.com */
export interface EventAvailabilityInput { expectedRevision: number; enabled: boolean; reason: string }
/** 设计器保留原始引用，不能自动升级成最新版本。@author owlzhangfq@gmail.com */
export interface EventBinding { key?: string; version?: string }

export const eventStatuses = { RECEIVED: '已接收', WAITING: '等待处理', CONSUMED: '已推进等待', IGNORED: '已忽略', REVIEW_REQUIRED: '待检查' } as const
export const eventReasons = { MATCHED: '已推进原等待，后续仍按流程办理。', PAUSED: '本轮审批已暂停，恢复后继续处理。', CONTRACT_DISABLED: '引用的事件版本已停用，等待恢复原版本。', SOURCE_DISABLED: '事件来源暂时停用，原收件已保留。', SOURCE_CHANGED: '来源信任已变化，请核对原来源配置。', TARGET_STALE: '原轮次或本次等待已结束，不再推进。', CONTRACT_MISMATCH: '事件与本次等待引用的契约不匹配，不再推进。', PROCESSING_FAILED: '后续处理未完成，原事件与等待已保留。' } as const
/** @author owlzhangfq@gmail.com */
export interface EventInboxItem { id: string; eventId: string; sourceKey: string; trustRevision: number; eventType: string; applicationId: string; roundNo: number; waitId: string; contractKey: string; contractVersion: number; version: number; status: keyof typeof eventStatuses; receivedAt: string; updatedAt: string; nextAttemptAt?: string | null; failures: number; reason?: keyof typeof eventReasons | null; errorCode?: string | null; requestedBy?: string | null; requestReason?: string | null }
/** @author owlzhangfq@gmail.com */
export interface EventInboxPage { items: EventInboxItem[]; nextBeforeId?: string | null }
/** @author owlzhangfq@gmail.com */
export interface EventInboxHistory { items: EventInboxItem[]; nextBeforeVersion?: number | null }
/** @author owlzhangfq@gmail.com */
export interface EventRetryInput { expectedVersion: number; reason: string }
/** @author owlzhangfq@gmail.com */
export interface EventWait { waitId: string; nodeId: string; nodeName: string; contractKey: string; contractVersion: number; createdAt: string; suspended: boolean; contractEnabled: boolean }
/** @author owlzhangfq@gmail.com */
export interface EventWaitView { applicationId: string; roundNo: number; applicationVersion: number; items: EventWait[] }
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const date = (value: unknown) => text(value) && Number.isFinite(Date.parse(value))
export const eventKey = (value: unknown): value is string => typeof value === 'string' && /^[a-z][a-z0-9._-]{0,63}$/.test(value)
const eventType = (value: unknown) => typeof value === 'string' && /^[A-Za-z][A-Za-z0-9._-]{0,127}$/.test(value)
function check(valid: unknown, message = '事件记录未能核实，请刷新后重试。'): asserts valid { if (!valid) throw new Error(message) }
function reason(value: string, max: number) { const result = value.trim(); check(result && result.length <= max, `请填写 1 至 ${max} 字的操作原因。`); return result }
function common(value: EventOption | EventContract) {
  check(value && eventKey(value.key) && positive(value.version) && text(value.name) && eventKey(value.sourceKey) && eventType(value.eventType) && value.envelopeVersion === 1)
}
export function readEventOption(value: EventOption, key?: string, version?: number) {
  common(value); check(typeof value.enabled === 'boolean' && positive(value.availabilityRevision) && (!key || value.key === key) && (version === undefined || value.version === version)); return value
}
export function readEventAvailability(value: EventAvailability) {
  check(value && positive(value.revision) && typeof value.enabled === 'boolean' && text(value.changedBy) && date(value.changedAt) && text(value.reason)); return value
}
export function readEventContract(value: EventContract, key: string, version: number) {
  common(value); check(value.key === key && value.version === version && text(value.publishedBy) && date(value.publishedAt) && text(value.publicationReason)); readEventAvailability(value.availability); return value
}
export function readEventDirectory(value: EventDirectory, after?: string) {
  check(value && Array.isArray(value.items) && value.items.length <= 100)
  value.items.forEach((item, index) => { readEventOption(item); check(item.key > (index ? value.items[index - 1]!.key : after ?? '')) })
  check(value.nextAfterKey == null || value.items.length && value.nextAfterKey === value.items[value.items.length - 1]!.key); return value
}
export function readEventVersions(value: EventVersions, key: string, before?: number) {
  check(value && Array.isArray(value.items) && value.items.length <= 100)
  value.items.forEach((item, index) => { readEventOption(item, key); check(item.version < (index ? value.items[index - 1]!.version : before ?? Infinity)) })
  check(value.nextBeforeVersion == null || value.items.length && value.nextBeforeVersion === value.items[value.items.length - 1]!.version); return value
}
export function readEventContractHistory(value: EventContractHistory, before?: number) {
  check(value && Array.isArray(value.items) && value.items.length <= 100)
  value.items.forEach((item, index) => { readEventAvailability(item); check(item.revision < (index ? value.items[index - 1]!.revision : before ?? Infinity)) })
  check(value.nextBeforeRevision == null || value.items.length && value.nextBeforeRevision === value.items[value.items.length - 1]!.revision); return value
}
/** 发布、启停和恢复各自使用对应版本，不混用发布版本与处理修订。 */
export function eventPublication(key: string, input: EventPublication): EventPublication {
  check(eventKey(key), '事件标识须以小写字母开头，最多 64 位。')
  check(Number.isSafeInteger(input.expectedVersion) && input.expectedVersion >= 0 && input.expectedVersion < Number.MAX_SAFE_INTEGER)
  const name = input.name.trim(), sourceKey = input.sourceKey.trim(), type = input.eventType.trim()
  check(text(name) && name.length <= 200 && !/[\u0000-\u001f\u007f-\u009f]/.test(name), '请填写 1 至 200 字、不含控制字符的事件名称。')
  check(eventKey(sourceKey), '来源标识须以小写字母开头，最多 64 位。'); check(eventType(type), '事件类型须是最多 128 位的字母、数字、点、下划线或连字符。')
  return { expectedVersion: input.expectedVersion, name, sourceKey, eventType: type, reason: reason(input.reason, 2000) }
}
export function eventAvailabilityInput(value: EventContract, enabled: boolean, explanation: string): EventAvailabilityInput {
  check(value && value.availability.enabled !== enabled && positive(value.availability.revision), '当前启停状态已变化，请刷新。')
  return { expectedRevision: value.availability.revision, enabled, reason: reason(explanation, 2000) }
}
export function readEventInboxItem(value: EventInboxItem, id?: string) {
  check(value && text(value.id) && (!id || value.id === id) && text(value.eventId) && eventKey(value.sourceKey) && eventType(value.eventType)
    && text(value.applicationId) && positive(value.roundNo) && text(value.waitId) && eventKey(value.contractKey) && positive(value.contractVersion)
    && positive(value.version) && positive(value.trustRevision) && Object.keys(eventStatuses).includes(value.status)
    && date(value.receivedAt) && date(value.updatedAt) && Date.parse(value.updatedAt) >= Date.parse(value.receivedAt)
    && Number.isInteger(value.failures) && value.failures >= 0 && value.failures <= 10)
  check(value.reason == null || Object.keys(eventReasons).includes(value.reason))
  const pending = value.status === 'RECEIVED' || value.status === 'WAITING'
  check(pending ? date(value.nextAttemptAt) : value.nextAttemptAt == null)
  check(value.status === 'RECEIVED' ? value.reason == null : value.status === 'CONSUMED' ? value.reason === 'MATCHED'
    : value.status === 'IGNORED' ? ['TARGET_STALE', 'CONTRACT_MISMATCH'].includes(value.reason ?? '')
    : value.status === 'REVIEW_REQUIRED' ? ['SOURCE_CHANGED', 'PROCESSING_FAILED'].includes(value.reason ?? '')
    : ['PAUSED', 'CONTRACT_DISABLED', 'SOURCE_DISABLED', 'PROCESSING_FAILED'].includes(value.reason ?? ''))
  check(value.reason === 'PROCESSING_FAILED' ? value.errorCode === 'EVENT_PROCESSING_FAILED' : value.errorCode == null)
  check(value.requestedBy == null ? value.requestReason == null : text(value.requestedBy) && text(value.requestReason)); return value
}
export function readEventInboxPage(value: EventInboxPage, before?: string) {
  check(value && Array.isArray(value.items) && value.items.length <= 100 && new Set(value.items.map(item => item.id)).size === value.items.length)
  value.items.forEach(item => { readEventInboxItem(item); check(item.id !== before) })
  check(value.nextBeforeId == null || value.items.length && value.nextBeforeId === value.items[value.items.length - 1]!.id); return value
}
export function readEventInboxHistory(value: EventInboxHistory, id: string, before?: number) {
  check(value && Array.isArray(value.items) && value.items.length <= 100)
  value.items.forEach((item, index) => { readEventInboxItem(item, id); check(item.version < (index ? value.items[index - 1]!.version : before ?? Infinity)) })
  check(value.nextBeforeVersion == null || value.items.length && value.nextBeforeVersion === value.items[value.items.length - 1]!.version); return value
}
export function eventRetryInput(value: EventInboxItem, explanation: string): EventRetryInput {
  readEventInboxItem(value); check(value.status === 'REVIEW_REQUIRED', '仅待检查的原事件可以恢复，请刷新处理状态。')
  return { expectedVersion: value.version, reason: reason(explanation, 500) }
}
/** 在原幂等请求被清除前核对真实写回执；损坏回执保持为结果未知。 */
export function validateEventMutation(value: unknown, path: string, raw: string) {
  const publication = /^\/event-contracts\/([^/]+)\/versions$/.exec(path)
  const availability = /^\/event-contracts\/([^/]+)\/versions\/([1-9][0-9]*)\/availability$/.exec(path)
  const retry = /^\/integrations\/events\/([^/]+)\/retry$/.exec(path)
  if (!publication && !availability && !retry) return
  try {
    const input = JSON.parse(raw)
    if (publication) {
      const result = readEventContract(value as EventContract, decodeURIComponent(publication[1]!), input.expectedVersion + 1)
      check(result.name === input.name && result.sourceKey === input.sourceKey && result.eventType === input.eventType && result.publicationReason === input.reason
        && result.availability.enabled && result.availability.revision === 1 && result.availability.reason === input.reason)
    } else if (availability) {
      const result = readEventContract(value as EventContract, decodeURIComponent(availability[1]!), Number(availability[2]))
      check(result.availability.revision === input.expectedRevision + 1 && result.availability.enabled === input.enabled && result.availability.reason === input.reason)
    } else {
      const result = readEventInboxItem(value as EventInboxItem, decodeURIComponent(retry![1]!))
      check(result.version === input.expectedVersion + 1 && result.status === 'RECEIVED' && result.failures === 0 && result.requestReason === input.reason && text(result.requestedBy))
    }
  } catch { throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '事件操作回执未能核实，请恢复上次操作确认结果。' } }
}
export function readEventWaits(value: EventWaitView, id: string, round: number) {
  check(value && value.applicationId === id && value.roundNo === round && positive(value.applicationVersion) && Array.isArray(value.items)
    && new Set(value.items.map(item => item.waitId)).size === value.items.length)
  value.items.forEach(item => check(text(item.waitId) && text(item.nodeId) && text(item.nodeName) && eventKey(item.contractKey) && positive(item.contractVersion)
    && date(item.createdAt) && typeof item.suspended === 'boolean' && typeof item.contractEnabled === 'boolean')); return value
}
export function eventError(cause: unknown) {
  const value = cause as { code?: string; message?: string }
  const labels: Record<string, string> = { FORBIDDEN: '当前账号无权查看或办理此事件。', AUTH_UNAUTHORIZED: '登录已失效，请重新登录。', AUTHENTICATION_IDENTITY_CHANGED: '账号已变化，请刷新后继续。', NOT_FOUND: '原事件记录已不可访问，请刷新。', CONCURRENCY_CONFLICT: '记录版本已变化，请刷新后核对。', EVENT_CONTRACT_AVAILABILITY_UNCHANGED: '启停状态没有变化，请刷新后核对。' }
  return labels[value?.code ?? ''] ?? value?.message ?? '事件记录暂时不可用，请刷新后重试。'
}

/** 目录、详情、选择器和等待面板共用读取取消与超时边界，不保存写入授权。@author owlzhangfq@gmail.com */
export class EventRead<T> {
  value: T | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = '' }
  async load(scope: string, fetch: (signal: AbortSignal) => Promise<T>) {
    this.clear(); if (!scope) return
    const current = this.generation, controller = new AbortController(); this.controller = controller; this.loading = true
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const result = await Promise.race([fetch(controller.signal), new Promise<never>((_, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('事件记录读取超时，请重试。')) }, 12_000) })])
      if (current === this.generation) { this.value = result; return result }
    } catch (cause) { if (current === this.generation) this.error = eventError(cause) }
    finally { clearTimeout(timer); if (current === this.generation) { this.loading = false; this.controller = null } }
  }
}
