import type { InboxMessage } from './api'
import { partialLabels, partialOperationLabels } from './expensePartialAdjustment.js'

export const partialNoticeLabels = {
  PREPARATION_UNAVAILABLE: '本侧调整依据读取失败，尚未授权本侧命令。', PREPARATION_VOIDED: '本次准备的来源或办理资格变化，已停止。',
  BUDGET_UNKNOWN: '原预算调减结果尚不明确。', BUDGET_NOT_FOUND: '原预算命令暂未查到。', BUDGET_REJECTED: '原预算调减已明确拒绝。',
  BUDGET_EXPIRED: '原预算发送授权已到期。', BUDGET_VOIDED: '原预算命令已停止。', BUDGET_RECONCILING: '原预算回执存在争议。', BUDGET_APPLIED: '原预算调减已确认，资源完成需单独核对。',
  ACCRUAL_UNKNOWN: '原挂账调整结果尚不明确。', ACCRUAL_NOT_FOUND: '原挂账命令暂未查到。', ACCRUAL_FAILED: '原挂账调整已明确失败。',
  ACCRUAL_EXPIRED: '原挂账发送授权已到期。', ACCRUAL_VOIDED: '原挂账命令已停止。', ACCRUAL_RECONCILING: '原挂账回执存在争议。', ACCRUAL_POSTED: '原挂账调整已过账，资源完成需单独核对。',
  RESOURCES_BLOCKED: '本地资源调整受阻，两侧财务事实保留。', COMPLETED: '本次两侧财务结果和资源调整均已完成。', RETIRED: '本次调整已依据无副作用证明安全结束。', DISPUTE_RESOLVED: '本次原操作争议已登记明确裁决。'
}
type SourceType = 'PREPARATION' | 'BUDGET' | 'ACCRUAL' | 'ADJUSTMENT' | 'DISPUTE'
interface Operation { id: string; version: number; status: keyof typeof partialOperationLabels; updatedAt: string; failure: string | null; outcome: string | null; conflictingOutcome: string | null }
/** 只读投影固定消息中的原操作，重新授权的替代操作不覆盖旧消息。 */
export interface ExpensePartialAdjustmentNotificationTarget {
  messageId: string; applicationId: string; reportId: string; adjustmentId: string; roundNo: number; sourceType: SourceType; sourceId: string; fact: keyof typeof partialNoticeLabels
  preparation: { side: 'BUDGET' | 'ACCRUAL'; version: number; status: 'UNAVAILABLE' | 'VOIDED'; updatedAt: string; issue: string } | null
  budget: Operation | null; accrual: Operation | null
  adjustment: { version: number; status: keyof typeof partialLabels; updatedAt: string; resourcesCompleted: boolean; issue: string | null } | null
  completion: { budgetVersion: number; accrualVersion: number; completedAt: string } | null
  retirement: { retiredAt: string } | null
  resolution: { id: string; side: 'BUDGET' | 'ACCRUAL'; operationId: string; beforeVersion: number; afterVersion: number; outcome: string; resolvedAt: string } | null
}
export const isExpensePartialAdjustmentNotification = (message: InboxMessage) => ['EXPENSE_PARTIAL_ADJUSTMENT_RESULT', 'EXPENSE_PARTIAL_ADJUSTMENT_ATTENTION'].includes(message.kind)
/** 在展示前校验封闭结构、消息归属、来源侧和原操作关联。 */
export function readExpensePartialAdjustmentNotificationTarget(v: ExpensePartialAdjustmentNotificationTarget, m: InboxMessage) {
  const invalid = (): never => { throw new Error('消息对应的原报销部分调整记录不一致，请刷新后重新读取。') }
  const uuid = (x: unknown) => typeof x === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(x)
  const positive = (x: number) => Number.isSafeInteger(x) && x > 0
  const time = (x: unknown) => typeof x === 'string' && Number.isFinite(Date.parse(x))
  const code = (x: unknown) => x === null || typeof x === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(x)
  const closed = (x: unknown, keys: string[]) => !!x && typeof x === 'object' && !Array.isArray(x) && Object.keys(x).length === keys.length && Object.keys(x).every(k => keys.includes(k))
  const known = (labels: object, x: string) => Object.prototype.hasOwnProperty.call(labels, x)
  if (!closed(v, ['messageId','applicationId','reportId','adjustmentId','roundNo','sourceType','sourceId','fact','preparation','budget','accrual','adjustment','completion','retirement','resolution'])
      || !isExpensePartialAdjustmentNotification(m) || v.messageId !== m.id || v.applicationId !== m.applicationId || v.roundNo !== m.roundNo || !positive(v.roundNo)
      || ![v.messageId,v.applicationId,v.reportId,v.adjustmentId,v.sourceId].every(uuid) || !known(partialNoticeLabels, v.fact)
      || (m.kind === 'EXPENSE_PARTIAL_ADJUSTMENT_RESULT') !== ['BUDGET_APPLIED','ACCRUAL_POSTED','COMPLETED','RETIRED','DISPUTE_RESOLVED'].includes(v.fact)) invalid()
  const source = v.fact.startsWith('PREPARATION_') ? 'PREPARATION' : v.fact.startsWith('BUDGET_') ? 'BUDGET' : v.fact.startsWith('ACCRUAL_') ? 'ACCRUAL' : v.fact === 'DISPUTE_RESOLVED' ? 'DISPUTE' : 'ADJUSTMENT'
  if (v.sourceType !== source || source === 'ADJUSTMENT' && v.sourceId !== v.adjustmentId) invalid()
  if (source === 'PREPARATION') {
    const p = v.preparation
    if (!p || !closed(p, ['side','version','status','updatedAt','issue']) || !['BUDGET','ACCRUAL'].includes(p.side) || !positive(p.version) || !time(p.updatedAt)
        || !code(p.issue) || p.issue === null || p.status !== v.fact.slice('PREPARATION_'.length)
        || [v.budget,v.accrual,v.adjustment,v.completion,v.retirement,v.resolution].some(x => x !== null)) invalid()
    return v
  }
  if (v.preparation !== null || !v.adjustment) return invalid()
  for (const side of ['budget','accrual'] as const) {
    const o = v[side]; if (o === null) continue
    const outcomes = side === 'budget' ? ['APPLIED','REJECTED','PENDING','NOT_FOUND'] : ['POSTED','FAILED','PENDING','NOT_FOUND']
    const forbidden = side === 'budget' ? ['POSTING','POSTED','FAILED'] : ['EXECUTING','APPLIED','REJECTED']
    if (!closed(o, ['id','version','status','updatedAt','failure','outcome','conflictingOutcome']) || !uuid(o.id) || !positive(o.version) || !time(o.updatedAt)
        || !known(partialOperationLabels,o.status) || forbidden.includes(o.status) || !code(o.failure)
        || o.outcome !== null && !outcomes.includes(o.outcome) || o.conflictingOutcome !== null && (!outcomes.includes(o.conflictingOutcome) || o.outcome === null)
        || ['APPLIED','REJECTED','POSTED','FAILED','NOT_FOUND'].includes(o.status) && (o.status !== o.outcome || o.failure !== null || o.conflictingOutcome !== null)
        || o.status === 'RECONCILING' && (!o.conflictingOutcome || o.failure !== 'INCONSISTENT_OBSERVATION')) invalid()
  }
  if (source === 'BUDGET' && v.budget?.id !== v.sourceId || source === 'ACCRUAL' && v.accrual?.id !== v.sourceId) invalid()
  const a = v.adjustment, c = v.completion, r = v.retirement, d = v.resolution
  if (!closed(a,['version','status','updatedAt','resourcesCompleted','issue']) || !positive(a.version) || !known(partialLabels,a.status) || !time(a.updatedAt)
      || !code(a.issue) || typeof a.resourcesCompleted !== 'boolean' || a.resourcesCompleted !== (c !== null)
      || a.status === 'APPLIED' && !c || a.status === 'READY' && (v.budget?.status !== 'APPLIED' || v.accrual?.status !== 'POSTED')
      || v.budget && Date.parse(v.budget.updatedAt) > Date.parse(a.updatedAt) || v.accrual && Date.parse(v.accrual.updatedAt) > Date.parse(a.updatedAt)) invalid()
  if (c && (!closed(c,['budgetVersion','accrualVersion','completedAt']) || !v.budget || !v.accrual || !positive(c.budgetVersion) || !positive(c.accrualVersion)
      || c.budgetVersion > v.budget.version || c.accrualVersion > v.accrual.version || !time(c.completedAt) || Date.parse(c.completedAt) > Date.parse(a.updatedAt))) invalid()
  if ((a.status === 'RETIRED') !== (r !== null) || r && (!closed(r,['retiredAt']) || !time(r.retiredAt) || r.retiredAt !== a.updatedAt || c !== null)) invalid()
  if ((source === 'DISPUTE') !== (d !== null) || d && (!closed(d,['id','side','operationId','beforeVersion','afterVersion','outcome','resolvedAt'])
      || d.id !== v.sourceId || !['BUDGET','ACCRUAL'].includes(d.side) || !uuid(d.operationId) || !positive(d.beforeVersion) || d.afterVersion !== d.beforeVersion + 1
      || d.afterVersion > a.version || !time(d.resolvedAt) || Date.parse(d.resolvedAt) > Date.parse(a.updatedAt)
      || (d.side === 'BUDGET' ? v.budget?.id : v.accrual?.id) !== d.operationId || !(d.side === 'BUDGET' ? ['APPLIED','REJECTED'] : ['POSTED','FAILED']).includes(d.outcome))) invalid()
  if (v.fact === 'COMPLETED' && !c || v.fact === 'RETIRED' && !r) invalid()
  return v
}
