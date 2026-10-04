import { organizationLabels, type OrganizationPage, type OrganizationRecord, type OrganizationSection } from './organization.js'
import { syncKey, syncPath, syncStatuses, syncFailures, type SyncDelta, type SyncDetail, type SyncKey, type SyncOverview, type SyncPage,
  type SyncPlanReceipt, type SyncPlanSummary, type SyncReceipt, type SyncSavedPlan, type SyncStatus, type SyncSummary, type SyncTransition } from './organizationSync.js'

/** 同步读取与写回执的信任边界；响应不完整时不能确认原写入成功。@author owlzhangfq@gmail.com */
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const own = (v: object, key: string) => Object.prototype.hasOwnProperty.call(v, key)
const exact = (v: unknown, required: string[], optional: string[] = []): v is Record<string, unknown> => object(v)
  && required.every(k => own(v, k)) && Object.keys(v).every(k => required.includes(k) || optional.includes(k))
const integer = (v: unknown, minimum = 0): v is number => Number.isSafeInteger(v) && (v as number) >= minimum
const text = (v: unknown, max = 128): v is string => typeof v === 'string' && !!v.trim() && v.length <= max && !/[\u0000-\u001f\u007f]/.test(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v)
const digest = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const source = (v: unknown): v is string => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/.test(v)
const bool = (v: unknown): v is boolean => typeof v === 'boolean'
const time = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v))
const nullable = (v: unknown, check: (value: unknown) => boolean) => v === null || check(v)
const optional = (v: Record<string, unknown>, field: string, check: (value: unknown) => boolean) => !own(v, field) || check(v[field])
const list = (v: unknown, check: (value: unknown) => boolean, maximum = 5000): v is unknown[] => Array.isArray(v) && v.length <= maximum && v.every(check)
const kind = (v: unknown): v is OrganizationSection => typeof v === 'string' && own(organizationLabels, v)
const key = (v: unknown): v is SyncKey => exact(v, ['kind', 'externalId']) && kind(v.kind) && text(v.externalId)
const reference = (v: unknown, expected: OrganizationSection) => key(v) && v.kind === expected
const status = (v: unknown): v is SyncStatus => typeof v === 'string' && own(syncStatuses, v)
const failure = (v: unknown) => typeof v === 'string' && own(syncFailures, v)
const comment = (v: unknown) => typeof v === 'string' && v.length <= 2000 && !/[\u0000-\u0008\u000b-\u001f\u007f]/.test(v)
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '组织同步响应不完整或与当前记录不符，请刷新核对；写入结果未知时先恢复原操作。' })
function versions(value: unknown, version: unknown) {
  return status(value) && integer(version, 1) && (value === 'CANCELLED' ? version >= 2 && version <= 4
    : version === ({ QUEUED: 1, FETCHING: 2, RECEIVED: 3, FAILED: 3, APPLIED: 4 } as const)[value])
}
function unitFact(v: unknown) {
  return exact(v, ['key', 'name', 'active'], ['legalEntity', 'parentDepartment', 'headAppointment']) && key(v.key)
    && ['LEGAL_ENTITY', 'DEPARTMENT', 'POSITION'].includes(v.key.kind) && text(v.name) && bool(v.active)
    && optional(v, 'legalEntity', x => reference(x, 'LEGAL_ENTITY')) && optional(v, 'parentDepartment', x => reference(x, 'DEPARTMENT'))
    && optional(v, 'headAppointment', x => reference(x, 'APPOINTMENT'))
}
function personFact(v: unknown) {
  return exact(v, ['key', 'subject', 'displayName', 'active', 'approvalEligible']) && reference(v.key, 'PERSON')
    && text(v.subject) && text(v.displayName) && bool(v.active) && bool(v.approvalEligible)
}
function appointmentFact(v: unknown) {
  return exact(v, ['key', 'person', 'department', 'position', 'active'], ['supervisorAppointment']) && reference(v.key, 'APPOINTMENT')
    && reference(v.person, 'PERSON') && reference(v.department, 'DEPARTMENT') && reference(v.position, 'POSITION') && bool(v.active)
    && optional(v, 'supervisorAppointment', x => reference(x, 'APPOINTMENT'))
}
function delta(v: unknown): v is SyncDelta {
  if (!exact(v, ['sourceKey', 'afterRevision', 'revision', 'units', 'people', 'appointments']) || !source(v.sourceKey)
    || !integer(v.afterRevision) || !integer(v.revision) || v.revision < v.afterRevision
    || !list(v.units, unitFact) || !list(v.people, personFact) || !list(v.appointments, appointmentFact)) return false
  const facts = [...v.units, ...v.people, ...v.appointments] as Array<{ key: SyncKey }>
  return facts.length <= 5000 && (facts.length === 0 || v.revision > v.afterRevision) && new Set(facts.map(f => syncKey(f.key))).size === facts.length
}
function decision(v: unknown) {
  if (!exact(v, ['actor', 'at'], ['comment', 'applied']) || !text(v.actor) || !time(v.at) || !optional(v, 'comment', comment)) return false
  if (!own(v, 'applied')) return true
  const a = v.applied
  return exact(a, ['planDigest', 'directoryRevisionBefore', 'directoryRevisionAfter']) && digest(a.planDigest)
    && integer(a.directoryRevisionBefore, 1) && integer(a.directoryRevisionAfter, a.directoryRevisionBefore)
}
/** 状态字段必须与原请求及阶段一致，不能从残缺正文推断可应用。 */
export function readSyncDetail(value: unknown, id: string): SyncDetail {
  if (!exact(value, ['request', 'state', 'appliedPlanId', 'reviewable', 'unavailableReason']) || !bool(value.reviewable)
    || !nullable(value.appliedPlanId, uuid) || !nullable(value.unavailableReason, v => text(v, 80))) throw unreadable()
  const request = value.request, state = value.state
  if (!exact(request, ['id', 'sourceKey', 'afterRevision', 'requestedBy', 'createdAt', 'retryOf']) || request.id !== id || !uuid(request.id)
    || !source(request.sourceKey) || !integer(request.afterRevision) || !text(request.requestedBy) || !time(request.createdAt)
    || !nullable(request.retryOf, uuid) || request.retryOf === request.id
    || !exact(state, ['status', 'version'], ['startedAt', 'leaseUntil', 'receivedAt', 'delta', 'failure', 'finishedAt', 'decision'])
    || !versions(state.status, state.version)) throw unreadable()
  const fetching = state.status !== 'QUEUED' && !(state.status === 'CANCELLED' && state.version === 2)
  const received = state.status === 'RECEIVED' || state.status === 'APPLIED' || state.status === 'CANCELLED' && state.version === 4
  const terminal = ['FAILED', 'APPLIED', 'CANCELLED'].includes(state.status as string)
  const decided = state.status === 'APPLIED' || state.status === 'CANCELLED'
  if (fetching !== own(state, 'startedAt') || fetching !== own(state, 'leaseUntil') || received !== own(state, 'receivedAt')
    || received !== own(state, 'delta') || terminal !== own(state, 'finishedAt') || decided !== own(state, 'decision')
    || (state.status === 'FAILED') !== own(state, 'failure') || (state.status === 'APPLIED') !== (value.appliedPlanId !== null)) throw unreadable()
  for (const field of ['startedAt', 'leaseUntil', 'receivedAt', 'finishedAt']) if (!optional(state, field, time)) throw unreadable()
  if (!optional(state, 'delta', delta) || !optional(state, 'failure', failure) || !optional(state, 'decision', decision)) throw unreadable()
  const result = value as unknown as SyncDetail
  if (received && (result.state.delta!.sourceKey !== request.sourceKey || result.state.delta!.afterRevision !== request.afterRevision)
    || decided && (state.status === 'APPLIED') !== !!result.state.decision!.applied
    || value.reviewable && state.status !== 'RECEIVED' || value.reviewable !== (value.unavailableReason === null)) throw unreadable()
  return result
}
export function readSyncOverview(value: unknown): SyncOverview {
  if (!exact(value, ['initialized', 'configured', 'workerEnabled', 'sourceKey', 'registeredSourceKey', 'sourceVersion', 'appliedRevision', 'targetDigest', 'activeBatchId'])
    || !bool(value.initialized) || !bool(value.configured) || !bool(value.workerEnabled) || !nullable(value.sourceKey, source)
    || !nullable(value.registeredSourceKey, source) || !integer(value.sourceVersion) || !integer(value.appliedRevision)
    || !nullable(value.targetDigest, digest) || !nullable(value.activeBatchId, uuid)
    || value.configured !== (value.sourceKey !== null) || value.configured !== (value.targetDigest !== null)
    || (value.sourceVersion > 0) !== (value.registeredSourceKey !== null) || value.sourceVersion === 0 && value.appliedRevision !== 0) throw unreadable()
  return value as unknown as SyncOverview
}
function receipt(value: unknown): value is SyncReceipt {
  return exact(value, ['id', 'status', 'version']) && uuid(value.id) && versions(value.status, value.version)
}
function summary(v: unknown) {
  return exact(v, ['id', 'sourceKey', 'afterRevision', 'status', 'version', 'requestedBy', 'createdAt'], ['receivedRevision'])
    && uuid(v.id) && source(v.sourceKey) && integer(v.afterRevision) && versions(v.status, v.version) && text(v.requestedBy) && time(v.createdAt)
    && optional(v, 'receivedRevision', r => integer(r, v.afterRevision as number))
}
function page<T>(v: unknown, index: number, size: number, check: (value: unknown) => boolean): SyncPage<T> {
  if (!exact(v, ['items', 'total', 'page', 'pageSize']) || !integer(v.total) || v.page !== index || v.pageSize !== size
    || !list(v.items, check, size) || v.items.length > v.total) throw unreadable()
  const ids = v.items.map(item => (item as { id: string }).id)
  if (new Set(ids).size !== ids.length) throw unreadable()
  return v as unknown as SyncPage<T>
}
export const readSyncBatches = (value: unknown, index: number, size = 20) => page<SyncSummary>(value, index, size, summary)
export const readSyncPlans = (value: unknown, index: number, size = 20) => page<SyncPlanSummary>(value, index, size, v =>
  exact(v, ['id', 'directoryRevision', 'preparedBy', 'preparedAt', 'ready']) && uuid(v.id) && integer(v.directoryRevision, 1)
  && text(v.preparedBy) && time(v.preparedAt) && bool(v.ready))
export function readSyncTransitions(value: unknown): SyncTransition[] {
  if (!list(value, v => exact(v, ['version', 'status', 'occurredAt', 'failure', 'decision']) && versions(v.status, v.version)
    && time(v.occurredAt) && nullable(v.failure, failure) && nullable(v.decision, decision), 4) || !value.length
    || !value.every((v, i) => (v as SyncTransition).version === i + 1)) throw unreadable()
  return value as SyncTransition[]
}
function localRecord(v: unknown, section: OrganizationSection): v is OrganizationRecord {
  if (!object(v) || !uuid(v.id) || !integer(v.revision, 1) || !bool(v.active)) return false
  if (section === 'PERSON') return exact(v, ['id', 'subject', 'displayName', 'active', 'approvalEligible', 'revision']) && text(v.subject) && text(v.displayName) && bool(v.approvalEligible)
  if (section === 'APPOINTMENT') return exact(v, ['id', 'personId', 'departmentId', 'positionId', 'active', 'revision'], ['supervisorAppointmentId'])
    && uuid(v.personId) && uuid(v.departmentId) && uuid(v.positionId) && optional(v, 'supervisorAppointmentId', x => nullable(x, uuid))
  return exact(v, ['id', 'kind', 'name', 'active', 'revision'], ['legalEntityId', 'parentDepartmentId', 'headAppointmentId']) && v.kind === section && text(v.name)
    && ['legalEntityId', 'parentDepartmentId', 'headAppointmentId'].every(field => optional(v, field, x => nullable(x, uuid)))
}
function change(v: unknown, section: OrganizationSection) {
  return exact(v, ['key', 'bindingVersion', 'after', 'explicitlySelected'], ['before']) && reference(v.key, section)
    && integer(v.bindingVersion) && bool(v.explicitlySelected) && localRecord(v.after, section)
    && optional(v, 'before', b => localRecord(b, section) && b.id === (v.after as OrganizationRecord).id && b.revision <= (v.after as OrganizationRecord).revision)
    && (v.bindingVersion === 0 || own(v, 'before'))
}
export function readSyncPlan(value: unknown, id: string, batchId: string, tenantId: string): SyncSavedPlan {
  if (!exact(value, ['plan', 'digest']) || !digest(value.digest)) throw unreadable()
  const p = value.plan
  if (!exact(p, ['id', 'tenantId', 'batchId', 'batchVersion', 'sourceVersion', 'directoryRevision', 'preparedBy', 'preparedAt', 'selections', 'units', 'people', 'appointments', 'conflicts'])
    || p.id !== id || !uuid(id) || p.batchId !== batchId || !uuid(batchId) || p.tenantId !== tenantId || !text(tenantId, 64)
    || p.batchVersion !== 3 || !integer(p.sourceVersion, 1) || !integer(p.directoryRevision, 1) || !text(p.preparedBy) || !time(p.preparedAt)
    || !list(p.selections, v => exact(v, ['key', 'localId', 'expectedRevision']) && key(v.key) && uuid(v.localId) && integer(v.expectedRevision, 1))
    || !list(p.units, v => object(v) && key(v.key) && ['LEGAL_ENTITY', 'DEPARTMENT', 'POSITION'].includes(v.key.kind) && change(v, v.key.kind))
    || !list(p.people, v => change(v, 'PERSON')) || !list(p.appointments, v => change(v, 'APPOINTMENT'))
    || !list(p.conflicts, v => exact(v, ['key', 'code'], ['localId']) && key(v.key) && text(v.code, 80) && optional(v, 'localId', uuid))) throw unreadable()
  const changes = [...p.units, ...p.people, ...p.appointments] as Array<{ key: SyncKey }>
  if (changes.length > 5000 || [changes, p.selections, p.conflicts].some(items => new Set((items as Array<{ key: SyncKey }>).map(v => syncKey(v.key))).size !== items.length)) throw unreadable()
  return value as unknown as SyncSavedPlan
}
/** 采用选项必须来自当前读取的完整本地身份与修订，不能用输入框猜测修订。 */
export function readSyncLocalPage(value: unknown, section: OrganizationSection): OrganizationPage<OrganizationRecord> {
  if (!exact(value, ['items'], ['nextAfterId']) || !list(value.items, item => localRecord(item, section), 30)
    || !optional(value, 'nextAfterId', v => nullable(v, uuid))) throw unreadable()
  return value as unknown as OrganizationPage<OrganizationRecord>
}
/** 回执必须属于原命令与原批次；错误响应保持为未知结果，继续使用原幂等键恢复。 */
export function validateSyncReceipt(value: unknown, path: string, body: string): SyncReceipt | SyncPlanReceipt {
  const match = new RegExp('^' + syncPath + '/batches/([a-f0-9-]{36})/(retry|cancel|preflight|apply)$').exec(path)
  const request = JSON.parse(body) as { expectedVersion?: number }
  if (match?.[2] === 'preflight') {
    if (!exact(value, ['id', 'ready']) || !uuid(value.id) || !bool(value.ready)) throw unreadable()
    return value as unknown as SyncPlanReceipt
  }
  if (!receipt(value)) throw unreadable()
  if (path === syncPath + '/batches' || match?.[2] === 'retry') {
    if (value.status !== 'QUEUED' || value.version !== 1 || value.id === match?.[1]) throw unreadable()
  } else if (match && ['cancel', 'apply'].includes(match[2]!)) {
    if (value.id !== match[1] || !integer(request.expectedVersion, 1) || value.version !== request.expectedVersion + 1
      || value.status !== (match[2] === 'apply' ? 'APPLIED' : 'CANCELLED')) throw unreadable()
  } else throw unreadable()
  return value
}
