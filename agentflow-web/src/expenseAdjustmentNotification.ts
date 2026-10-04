import type { InboxMessage } from './api'
import { adjustmentLabels, adjustmentBudgetLabels, adjustmentPreparationLabels } from './expenseResourceAdjustment.js'

export const expenseAdjustmentNoticeLabels = {
  PREPARATION_UNAVAILABLE: '本次调整依据读取失败，尚未授权预算冲正。',
  PREPARATION_VOIDED: '原依据或办理资格已变化，本次准备已停止。',
  BUDGET_UNKNOWN: '原预算冲正结果暂不明确，需要继续核对原编号。',
  BUDGET_NOT_FOUND: '原预算冲正暂未查到，不能据此重新建立调整。',
  BUDGET_REJECTED: '原预算系统明确拒绝冲正，后续处理需核对原记录。',
  BUDGET_EXPIRED: '原预算发送授权已到期，本次尚未完成资源冲回。',
  BUDGET_VOIDED: '原预算发送已停止，安全结束仍需独立确认。',
  BUDGET_RECONCILING: '原预算回执存在矛盾，原结果与冲突结果分别保留。',
  BUDGET_APPLIED: '预算冲正已确认；本地资源是否完成需单独核对。',
  RESOURCES_BLOCKED: '本地资源冲回遇到问题，已发生的预算事实保持。',
  COMPLETED: '原预算冲正和本地资源恢复已记录完成。',
  RETIRED: '本次调整已依据无副作用证明安全结束。'
}

/** 原消息只读摘要，不含财务金额、账户、外部引用或办理许可。 */
export interface ExpenseAdjustmentNotificationTarget {
  messageId: string; adjustmentId: string; reportId: string; applicationId: string; roundNo: number
  fact: keyof typeof expenseAdjustmentNoticeLabels
  preparation: { version: number; status: keyof typeof adjustmentPreparationLabels; updatedAt: string; issue: string | null }
  budget: { version: number; status: keyof typeof adjustmentBudgetLabels; updatedAt: string; failure: string | null; outcome: string | null; conflictingOutcome: string | null } | null
  adjustment: { version: number; status: keyof typeof adjustmentLabels; updatedAt: string; resourcesReversed: boolean; issue: string | null } | null
  completion: { adjustmentVersion: number; budgetVersion: number; completedAt: string } | null
  retirement: { adjustmentVersion: number; budgetVersion: number; retiredAt: string } | null
}

export const isExpenseAdjustmentNotification = (value: InboxMessage) => ['EXPENSE_ADJUSTMENT_RESULT', 'EXPENSE_ADJUSTMENT_ATTENTION'].includes(value.kind)

/** 消息事实和原操作当前状态分别验证，历史未知结果允许在同一原编号下恢复。 */
export function readExpenseAdjustmentNotificationTarget(value: ExpenseAdjustmentNotificationTarget, message: InboxMessage): ExpenseAdjustmentNotificationTarget {
  const invalid = () => { throw new Error('消息对应的原报销资源调整记录不一致，请刷新后重新读取。') }
  const uuid = (v: unknown) => typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const positive = (v: number) => Number.isSafeInteger(v) && v > 0
  const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const known = (labels: object, v: string) => Object.prototype.hasOwnProperty.call(labels, v)
  const code = (v: unknown) => v === null || typeof v === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(v)
  const closed = (v: object, keys: string[]) => !!v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && Object.keys(v).every(k => keys.includes(k))
  if (!closed(value, ['messageId', 'adjustmentId', 'reportId', 'applicationId', 'roundNo', 'fact', 'preparation', 'budget', 'adjustment', 'completion', 'retirement'])
      || !isExpenseAdjustmentNotification(message) || value.messageId !== message.id || value.applicationId !== message.applicationId || value.roundNo !== message.roundNo
      || ![value.messageId, value.adjustmentId, value.reportId, value.applicationId].every(uuid) || !positive(value.roundNo)
      || !known(expenseAdjustmentNoticeLabels, value.fact)
      || (message.kind === 'EXPENSE_ADJUSTMENT_RESULT') !== ['BUDGET_APPLIED', 'COMPLETED', 'RETIRED'].includes(value.fact)) invalid()
  const p = value.preparation, b = value.budget, a = value.adjustment
  if (!closed(p, ['version', 'status', 'updatedAt', 'issue']) || !positive(p.version) || !known(adjustmentPreparationLabels, p.status) || !time(p.updatedAt) || !code(p.issue)
      || (['UNAVAILABLE', 'VOIDED'].includes(p.status)) !== (p.issue !== null)) invalid()
  if (value.fact.startsWith('PREPARATION_')) {
    if (b !== null || a !== null || value.completion !== null || value.retirement !== null
        || p.status !== (value.fact === 'PREPARATION_UNAVAILABLE' ? 'UNAVAILABLE' : 'VOIDED')) invalid()
    return value
  }
  if (p.status !== 'AUTHORIZED' || !b || !a) return invalid()
  const outcomes = ['APPLIED', 'REJECTED', 'PENDING', 'NOT_FOUND']
  if (!closed(b, ['version', 'status', 'updatedAt', 'failure', 'outcome', 'conflictingOutcome']) || !positive(b.version) || !known(adjustmentBudgetLabels, b.status)
      || !time(b.updatedAt) || Date.parse(b.updatedAt) < Date.parse(p.updatedAt) || !code(b.failure)
      || b.outcome !== null && !outcomes.includes(b.outcome) || b.conflictingOutcome !== null && (!outcomes.includes(b.conflictingOutcome) || b.outcome === null)
      || ['APPLIED', 'REJECTED', 'NOT_FOUND'].includes(b.status) && (b.outcome !== b.status || b.failure !== null || b.conflictingOutcome !== null)
      || b.status === 'RECONCILING' && (!b.conflictingOutcome || b.failure !== 'INCONSISTENT_OBSERVATION')) invalid()
  if (!closed(a, ['version', 'status', 'updatedAt', 'resourcesReversed', 'issue']) || !positive(a.version) || !known(adjustmentLabels, a.status)
      || !time(a.updatedAt) || Date.parse(a.updatedAt) < Date.parse(p.updatedAt) || typeof a.resourcesReversed !== 'boolean' || !code(a.issue)
      || (a.status === 'REVIEW_REQUIRED') !== (a.issue !== null) || a.status === 'APPLIED' && !a.resourcesReversed
      || a.resourcesReversed && !['APPLIED', 'REVIEW_REQUIRED'].includes(a.status)) invalid()
  const c = value.completion, r = value.retirement
  if (a.resourcesReversed !== (c !== null) || c && (!closed(c, ['adjustmentVersion', 'budgetVersion', 'completedAt'])
      || !positive(c.adjustmentVersion) || c.adjustmentVersion > a.version || !positive(c.budgetVersion) || c.budgetVersion > b.version
      || !time(c.completedAt) || Date.parse(c.completedAt) > Date.parse(a.updatedAt) || Date.parse(c.completedAt) < Date.parse(p.updatedAt))) invalid()
  if ((a.status === 'RETIRED') !== (r !== null) || r && (!closed(r, ['adjustmentVersion', 'budgetVersion', 'retiredAt'])
      || r.adjustmentVersion !== a.version || r.budgetVersion !== b.version || a.resourcesReversed || !['VOIDED', 'EXPIRED', 'REJECTED'].includes(b.status)
      || !time(r.retiredAt) || r.retiredAt !== a.updatedAt || Date.parse(r.retiredAt) < Date.parse(b.updatedAt))) invalid()
  if (value.fact === 'COMPLETED' && !c || value.fact === 'RETIRED' && !r
      || value.fact === 'BUDGET_APPLIED' && ![b.outcome, b.conflictingOutcome].includes('APPLIED')) invalid()
  return value
}
