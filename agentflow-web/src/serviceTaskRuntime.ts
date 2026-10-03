import { serviceTaskVersion } from './serviceTasks.js'

export type ServiceOperationStatus = 'QUEUED' | 'EXECUTING' | 'UNKNOWN' | 'QUERYING' | 'APPLIED' | 'REJECTED' | 'CANCELLED'
export type ServiceOperationProgress = 'PENDING' | 'ADVANCED' | 'STALE'
export type ServiceOperationFailure = 'NOT_CONFIGURED' | 'TARGET_CHANGED' | 'OPERATION_DISABLED' | 'TIMEOUT' | 'CONNECTION' | 'AUTHENTICATION' | 'REMOTE_FAILURE' | 'INVALID_RESPONSE' | 'RESPONSE_TOO_LARGE' | 'LEASE_EXPIRED' | 'INTERNAL_ERROR'
export interface ServiceTaskRuntimeEntry {
  id: string; nodeId: string; nodeName: string; operationKey: string; operationVersion: string; operationName: string; version: string
  status: ServiceOperationStatus; progress: ServiceOperationProgress; attempts: number; createdAt: string; updatedAt: string
  completedAt?: string | null; progressedAt?: string | null; failure?: ServiceOperationFailure | null; configurationAvailable: boolean
}
export interface ServiceTaskRuntimeView {
  applicationId: string; applicationVersion: number; roundNo: number; roundStatus: string
  items: ServiceTaskRuntimeEntry[]; nextAfterId?: string | null
}
const statuses = ['QUEUED', 'EXECUTING', 'UNKNOWN', 'QUERYING', 'APPLIED', 'REJECTED', 'CANCELLED']
const roundStatuses = ['IN_APPROVAL', 'RETURNED', 'REJECTED', 'APPROVED', 'WITHDRAWN', 'CANCELLED']
const failures = ['NOT_CONFIGURED', 'TARGET_CHANGED', 'OPERATION_DISABLED', 'TIMEOUT', 'CONNECTION', 'AUTHENTICATION', 'REMOTE_FAILURE', 'INVALID_RESPONSE', 'RESPONSE_TOO_LARGE', 'LEASE_EXPIRED', 'INTERNAL_ERROR']
const uuid = (value: unknown) => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const text = (value: unknown, max: number) => typeof value === 'string' && value.length > 0 && value.length <= max
const instant = (value: unknown) => typeof value === 'string' && value.length <= 35 && Number.isFinite(Date.parse(value))
const only = (value: object, names: string[]) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => names.includes(key))
function check(value: unknown): asserts value { if (!value) throw new Error('服务运行记录与当前申请或轮次不一致，请刷新后重试。') }

/** 身份、完整版本、有界状态与分页均匹配后才显示；不接受命令或任意回执字段。 */
export function readServiceTaskRuntime(value: ServiceTaskRuntimeView, applicationId: string, round: number, afterId?: string, limit = 25) {
  check(only(value, ['applicationId', 'applicationVersion', 'roundNo', 'roundStatus', 'items', 'nextAfterId']))
  check(value.applicationId === applicationId && value.roundNo === round && Number.isSafeInteger(value.applicationVersion) && value.applicationVersion >= 0)
  check(roundStatuses.includes(value.roundStatus) && Array.isArray(value.items) && value.items.length <= limit)
  const seen = new Set<string>()
  for (const item of value.items) {
    check(only(item, ['id', 'nodeId', 'nodeName', 'operationKey', 'operationVersion', 'operationName', 'version', 'status', 'progress', 'attempts', 'createdAt', 'updatedAt', 'completedAt', 'progressedAt', 'failure', 'configurationAvailable']))
    check(uuid(item.id) && item.id !== afterId && !seen.has(item.id)); seen.add(item.id)
    check(text(item.nodeId, 128) && typeof item.nodeName === 'string' && item.nodeName.length > 0 && text(item.operationName, 200))
    check(typeof item.operationKey === 'string' && /^[a-z][a-z0-9._-]{0,63}$/.test(item.operationKey))
    check(serviceTaskVersion(item.operationVersion) && serviceTaskVersion(item.version) && statuses.includes(item.status))
    check(['PENDING', 'ADVANCED', 'STALE'].includes(item.progress) && Number.isSafeInteger(item.attempts) && item.attempts >= 0 && item.attempts <= 2147483647)
    check(instant(item.createdAt) && instant(item.updatedAt) && Date.parse(item.updatedAt) >= Date.parse(item.createdAt))
    check(typeof item.configurationAvailable === 'boolean' && (item.failure == null || item.status === 'UNKNOWN' && failures.includes(item.failure)))
    check(['APPLIED', 'REJECTED'].includes(item.status) ? instant(item.completedAt) : item.completedAt == null)
    check(item.progress === 'PENDING' ? item.progressedAt == null : instant(item.progressedAt))
    check(item.progress !== 'ADVANCED' || item.status === 'APPLIED')
    check(item.progress !== 'STALE' || ['APPLIED', 'REJECTED', 'CANCELLED'].includes(item.status))
  }
  check(value.nextAfterId == null || value.items.length === limit && uuid(value.nextAfterId) && value.nextAfterId === value.items[value.items.length - 1]?.id)
  return value
}

/** 已完成的外部操作与审批流程的推进分开展示，未知结果不会显示成失败或成功。 */
export function serviceTaskState(item: ServiceTaskRuntimeEntry, roundStatus: string) {
  if (item.status === 'APPLIED') {
    if (item.progress === 'ADVANCED') return '已完成并继续流程'
    if (item.progress === 'STALE' || roundStatus !== 'IN_APPROVAL') return '已完成 · 原轮次已结束'
    return '已完成 · 等待流程继续'
  }
  return ({ QUEUED: '等待执行', EXECUTING: '正在执行', UNKNOWN: '结果待确认', QUERYING: '正在核对结果', REJECTED: '服务已拒绝', CANCELLED: '已取消未执行操作' } as const)[item.status]
}

/** 提示来自已保存状态，不根据一次连接失败推断远端是否产生效果。 */
export function serviceTaskExplanation(item: ServiceTaskRuntimeEntry, roundStatus: string) {
  if (item.status === 'UNKNOWN' || item.status === 'QUERYING') return '系统会按原操作编号核对结果。结果确认前，流程不会自动通过此节点。'
  if (item.status === 'EXECUTING') return roundStatus === 'IN_APPROVAL' ? '正在等待服务回执；暂时未收到回执时会核对原操作。' : '原审批轮次已结束，系统仍会核对已经发出的操作结果。'
  if (item.status === 'QUEUED') return roundStatus !== 'IN_APPROVAL' ? '原审批轮次已结束，后台将取消尚未执行的操作。' : !item.configurationAvailable ? '原服务配置当前不可用，请联系流程管理员恢复。' : '等待后台处理；审批暂停时会保留这项等待。'
  if (item.status === 'APPLIED' && item.progress === 'PENDING') {
    if (roundStatus !== 'IN_APPROVAL') return '原审批轮次已结束，已保存的服务结果不会推进新轮次。'
    return !item.configurationAvailable ? '服务结果已保存，恢复原服务配置后继续流程。' : '服务结果已保存，后台正在衔接审批流程；审批暂停时会等待恢复。'
  }
  if (item.status === 'APPLIED' && item.progress === 'STALE') return '服务结果已保留，不会推进已经结束的审批轮次。'
  if (item.status === 'REJECTED') return '服务明确拒绝了本次操作，请联系流程管理员核对；此结果不会替代人工批准。'
  if (item.status === 'CANCELLED') return '本次操作未发送，或查询原号已确认不存在，原等待已结束。'
  return '已保存服务结果并继续原审批流程，后续审批仍由审批人办理。'
}
