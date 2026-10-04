import type { OrganizationAppointment, OrganizationPerson, OrganizationRecord, OrganizationSection, OrganizationUnit } from './organization.js'

/** 组织同步只承载组织事实；账号与系统角色仍归认证源。@author owlzhangfq@gmail.com */
export type SyncStatus = 'QUEUED' | 'FETCHING' | 'RECEIVED' | 'FAILED' | 'APPLIED' | 'CANCELLED'
export type SyncFailure = 'SOURCE_UNAVAILABLE' | 'SOURCE_TIMEOUT' | 'INVALID_SOURCE_DATA' | 'SOURCE_CHANGED'
export interface SyncKey { kind: OrganizationSection; externalId: string }
export interface SyncOverview {
  initialized: boolean; configured: boolean; workerEnabled: boolean; sourceKey: string | null; registeredSourceKey: string | null
  sourceVersion: number; appliedRevision: number; targetDigest: string | null; activeBatchId: string | null
}
export interface SyncReceipt { id: string; status: SyncStatus; version: number }
export interface SyncPlanReceipt { id: string; ready: boolean }
export interface SyncRequest { id: string; sourceKey: string; afterRevision: number; requestedBy: string; createdAt: string; retryOf: string | null }
export interface SyncUnitFact { key: SyncKey; name: string; legalEntity?: SyncKey; parentDepartment?: SyncKey; active: boolean; headAppointment?: SyncKey }
export interface SyncPersonFact { key: SyncKey; subject: string; displayName: string; active: boolean; approvalEligible: boolean }
export interface SyncAppointmentFact { key: SyncKey; person: SyncKey; department: SyncKey; position: SyncKey; active: boolean; supervisorAppointment?: SyncKey }
export type SyncFact = SyncUnitFact | SyncPersonFact | SyncAppointmentFact
export interface SyncDelta { sourceKey: string; afterRevision: number; revision: number; units: SyncUnitFact[]; people: SyncPersonFact[]; appointments: SyncAppointmentFact[] }
export interface SyncApplied { planDigest: string; directoryRevisionBefore: number; directoryRevisionAfter: number }
export interface SyncDecision { actor: string; comment?: string; at: string; applied?: SyncApplied }
export interface SyncState {
  status: SyncStatus; version: number; startedAt?: string; leaseUntil?: string; receivedAt?: string
  delta?: SyncDelta; failure?: SyncFailure; finishedAt?: string; decision?: SyncDecision
}
export interface SyncDetail { request: SyncRequest; state: SyncState; appliedPlanId: string | null; reviewable: boolean; unavailableReason: string | null }
export interface SyncSummary extends SyncReceipt { sourceKey: string; afterRevision: number; receivedRevision?: number; requestedBy: string; createdAt: string }
export interface SyncPage<T> { items: T[]; total: number; page: number; pageSize: number }
export interface SyncTransition { version: number; status: SyncStatus; occurredAt: string; failure: SyncFailure | null; decision: SyncDecision | null }
export interface SyncSelection extends SyncKey { localId: string; expectedRevision: number }
export interface SyncChange<T extends OrganizationRecord = OrganizationRecord> { key: SyncKey; bindingVersion: number; before?: T; after: T; explicitlySelected: boolean }
export interface SyncConflict { key: SyncKey; code: string; localId?: string }
export interface SyncPlan {
  id: string; tenantId: string; batchId: string; batchVersion: number; sourceVersion: number; directoryRevision: number; preparedBy: string; preparedAt: string
  selections: Array<{ key: SyncKey; localId: string; expectedRevision: number }>
  units: SyncChange<OrganizationUnit>[]; people: SyncChange<OrganizationPerson>[]; appointments: SyncChange<OrganizationAppointment>[]; conflicts: SyncConflict[]
}
export interface SyncSavedPlan { plan: SyncPlan; digest: string }
export interface SyncPlanSummary { id: string; directoryRevision: number; preparedBy: string; preparedAt: string; ready: boolean }
export interface SyncDraft { batchId: string; planId: string; selections: SyncSelection[]; plannedSelections: SyncSelection[]; comment: string }

export const syncPath = '/organization/synchronization'
export const syncStatuses: Record<SyncStatus, string> = { QUEUED: '等待读取', FETCHING: '正在读取', RECEIVED: '待核对应用', FAILED: '读取失败', APPLIED: '已应用', CANCELLED: '已取消' }
export const syncFailures: Record<SyncFailure, string> = {
  SOURCE_UNAVAILABLE: '来源暂时不可用，请核对服务后明确重试。', SOURCE_TIMEOUT: '本次读取已超时，系统未自动重发。',
  INVALID_SOURCE_DATA: '来源响应未通过组织事实校验，请核对来源内容。', SOURCE_CHANGED: '本次来源配置已变化，旧结果不能继续采用。'
}
const conflictMessages: Record<string, string> = {
  ORGANIZATION_SYNC_ADOPTION_REQUIRED: '已存在同一身份的本地记录，需要明确选择后才能采用来源值。',
  ORGANIZATION_SYNC_LOCAL_CHANGED: '本地记录已修改，请核对当前修订后明确采用来源值。',
  ORGANIZATION_SYNC_SELECTION_STALE: '选择的本地修订已过期，请重新读取并选择。',
  ORGANIZATION_SYNC_ALREADY_BOUND: '该本地记录已被另一来源标识绑定，不能重复采用。',
  ORGANIZATION_SYNC_DUPLICATE_LOCAL: '多条来源事实选择了同一条本地记录，请分别核对身份。',
  ORGANIZATION_SYNC_IDENTITY_IMMUTABLE: '来源试图替换稳定身份或归属；调岗应新增任职并停用旧任职。',
  ORGANIZATION_SYNC_DUPLICATE_IDENTITY: '多条来源事实使用了相同身份，请在来源侧修正。',
  ORGANIZATION_SYNC_REFERENCE_MISSING: '引用的组织记录尚未提供或绑定，请补齐来源事实。',
  ORGANIZATION_RELATION_INACTIVE: '新关系引用了停用或无资格的对象，请核对任命资格。',
  ORGANIZATION_SYNC_PLAN_STALE: '核对计划已过期，请重新读取批次并生成计划。',
  ORGANIZATION_SYNC_SOURCE_CHANGED: '来源配置已变化，请取消原批次后重新读取。',
  ORGANIZATION_SYNC_UNAVAILABLE: '当前租户未启用或未配置可信来源。',
  ORGANIZATION_SYNC_BATCH_ACTIVE: '当前仍有待处理批次，请先核对或取消。',
  ORGANIZATION_SYNC_STATE_CONFLICT: '批次状态已变化，请刷新后核对可用操作。',
  CONCURRENCY_CONFLICT: '记录版本已变化，请刷新后重新核对。'
}
export const syncKey = (key: SyncKey) => JSON.stringify([key.kind, key.externalId])
export const syncFacts = (delta: SyncDelta | undefined): SyncFact[] => delta ? [...delta.units, ...delta.people, ...delta.appointments] : []
export const syncChanges = (plan: SyncPlan): SyncChange[] => [...plan.units, ...plan.people, ...plan.appointments]
export const syncPlanSelections = (plan: SyncPlan): SyncSelection[] => plan.selections.map(value => ({ ...value.key, localId: value.localId, expectedRevision: value.expectedRevision }))
/** 明确选择与已保存计划一致时才允许应用；排序不影响选择含义。 */
export function sameSyncSelections(a: SyncSelection[], b: SyncSelection[]) {
  const stable = (items: SyncSelection[]) => JSON.stringify(items.map(v => [v.kind, v.externalId, v.localId, v.expectedRevision]).sort((x, y) => JSON.stringify(x).localeCompare(JSON.stringify(y))))
  return stable(a) === stable(b)
}
export function syncConflictMessage(code: string) { return conflictMessages[code] ?? '此项未通过组织规则检查，请核对来源身份、归属及上下级关系。' }
export function syncError(cause: unknown) {
  const value = cause as { code?: string; message?: string } | null
  return value?.code && conflictMessages[value.code] || value?.message || '组织同步操作未完成，请刷新后重试；结果未知时先恢复原操作。'
}
export function emptySyncDraft(): SyncDraft { return { batchId: '', planId: '', selections: [], plannedSelections: [], comment: '' } }

/** 仅在页面内存按登录身份保留核对选择；不保存来源正文或凭据。@author owlzhangfq@gmail.com */
export class OrganizationSyncDrafts {
  private values = new Map<string, SyncDraft>()
  get(scope: string): SyncDraft { return structuredClone(this.values.get(scope) ?? emptySyncDraft()) }
  put(scope: string, draft: SyncDraft) { if (scope) this.values.set(scope, JSON.parse(JSON.stringify(draft)) as SyncDraft) }
  clear(scope: string) { this.values.delete(scope) }
  hasDrafts() { return [...this.values.values()].some(value => !sameSyncSelections(value.selections, value.plannedSelections) || !!value.comment.trim()) }
  /** 原请求恢复只定位原批次和计划；不能顺带执行下一步应用。 */
  acknowledge(scope: string, path: string, body: string, result: SyncReceipt | SyncPlanReceipt) {
    const match = new RegExp('^' + syncPath + '/batches/([a-f0-9-]{36})/(retry|cancel|preflight|apply)$').exec(path)
    if (path === syncPath + '/batches' || match?.[2] === 'retry') {
      this.put(scope, { ...emptySyncDraft(), batchId: result.id }); return
    }
    if (!match) return
    const batchId = match[1]!, current = this.get(scope), draft = current.batchId === batchId ? current : { ...emptySyncDraft(), batchId }
    if (match[2] === 'preflight') {
      draft.planId = result.id; draft.selections = (JSON.parse(body) as { selections: SyncSelection[] }).selections; draft.plannedSelections = draft.selections
    } else { draft.selections = []; draft.plannedSelections = []; draft.comment = '' }
    this.put(scope, draft)
  }
}
export const organizationSyncDrafts = new OrganizationSyncDrafts()
