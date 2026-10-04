export const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
export const at = '2026-10-04T04:00:00Z'
export const started = '2026-10-04T04:00:01Z'
export const received = '2026-10-04T04:00:02Z'
export const sourceKey = () => ({ kind: 'PERSON', externalId: 'employee-7' })
export const local = () => ({ id: uuid(5), subject: 'stable-subject', displayName: '本地人员', active: true, approvalEligible: true, revision: 2 })
export const overview = () => ({ initialized: true, configured: true, workerEnabled: true, sourceKey: 'hr', registeredSourceKey: 'hr', sourceVersion: 1, appliedRevision: 0, targetDigest: 'a'.repeat(64), activeBatchId: uuid(1) })
export function detail(status = 'RECEIVED', id = uuid(1)) {
  const state = { status, version: { QUEUED: 1, FETCHING: 2, RECEIVED: 3, FAILED: 3, APPLIED: 4, CANCELLED: 4 }[status] }
  if (status !== 'QUEUED') Object.assign(state, { startedAt: started, leaseUntil: '2026-10-04T04:00:31Z' })
  if (['RECEIVED', 'APPLIED', 'CANCELLED'].includes(status)) Object.assign(state, { receivedAt: received, delta: { sourceKey: 'hr', afterRevision: 0, revision: 1, units: [],
    people: [{ key: sourceKey(), subject: 'stable-subject', displayName: '来源人员', active: true, approvalEligible: true }], appointments: [] } })
  if (['FAILED', 'APPLIED', 'CANCELLED'].includes(status)) state.finishedAt = '2026-10-04T04:00:03Z'
  if (status === 'FAILED') state.failure = 'SOURCE_TIMEOUT'
  if (['APPLIED', 'CANCELLED'].includes(status)) state.decision = { actor: 'admin', at: state.finishedAt, comment: '已核对' }
  if (status === 'APPLIED') state.decision.applied = { planDigest: 'b'.repeat(64), directoryRevisionBefore: 1, directoryRevisionAfter: 2 }
  return { request: { id, sourceKey: 'hr', afterRevision: 0, requestedBy: 'admin', createdAt: at, retryOf: null }, state,
    appliedPlanId: status === 'APPLIED' ? uuid(2) : null, reviewable: status === 'RECEIVED', unavailableReason: status === 'RECEIVED' ? null : 'ORGANIZATION_SYNC_NOT_RECEIVED' }
}
export const summary = (value = detail()) => ({ id: value.request.id, sourceKey: value.request.sourceKey, afterRevision: value.request.afterRevision,
  ...(value.state.delta ? { receivedRevision: value.state.delta.revision } : {}), status: value.state.status, version: value.state.version, requestedBy: 'admin', createdAt: at })
export const page = (items, index = 0) => ({ items, total: items.length, page: index, pageSize: 20 })
export function savedPlan(batchId = uuid(1), id = uuid(2), tenantId = 'tenant') {
  return { plan: { id, tenantId, batchId, batchVersion: 3, sourceVersion: 1, directoryRevision: 1, preparedBy: 'admin', preparedAt: received,
    selections: [], units: [], people: [{ key: sourceKey(), bindingVersion: 1, before: local(), after: { ...local(), displayName: '来源人员', revision: 3 }, explicitlySelected: false }], appointments: [], conflicts: [] }, digest: 'b'.repeat(64) }
}
export const planSummary = (value = savedPlan()) => ({ id: value.plan.id, directoryRevision: value.plan.directoryRevision, preparedBy: value.plan.preparedBy, preparedAt: value.plan.preparedAt, ready: value.plan.conflicts.length === 0 })
export function history(value = detail()) {
  const records = [{ version: 1, status: 'QUEUED', occurredAt: at, failure: null, decision: null }]
  if (value.state.version > 1) records.push({ version: 2, status: 'FETCHING', occurredAt: started, failure: null, decision: null })
  if (value.state.version > 2) records.push({ version: 3, status: 'RECEIVED', occurredAt: received, failure: null, decision: null })
  if (value.state.version === 4) records.push({ version: 4, status: value.state.status, occurredAt: value.state.finishedAt, failure: value.state.failure ?? null, decision: value.state.decision ?? null })
  const last = records[records.length - 1]; last.status = value.state.status; last.failure = value.state.failure ?? null; last.decision = value.state.decision ?? null
  return records
}
