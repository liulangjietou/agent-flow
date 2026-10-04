export type BudgetReviewStatus = 'WAITING_BUDGET' | 'REVIEW_REQUIRED' | 'AUTHORIZED' | 'CONFIRMED' | 'REJECTED' | 'CLOSED'
export type BudgetOperationStatus = 'QUEUED' | 'EXECUTING' | 'UNKNOWN' | 'QUERYING' | 'APPLIED' | 'REJECTED'
export interface BudgetReviewDecision { actorId: string; taskId: string; auditEventId: string; approvedAt: string }
export interface BudgetReviewDetails {
  version: number; financialVersion: number; budgetNodeId: string | null; policyReference: string | null
  originalOperationId: string; originalOperationStatus: BudgetOperationStatus
  authorizedOperationId: string | null; authorizedOperationStatus: BudgetOperationStatus | null
  status: BudgetReviewStatus; decision: BudgetReviewDecision | null
  automaticPass: { taskId: string; auditEventId: string; passedAt: string } | null
  closure: 'WITHDRAWN' | 'RETURNED' | 'REJECTED' | 'CANCELLED' | null; submittedAt: string; updatedAt: string
}
export interface BudgetReviewView { reportId: string; applicationId: string; roundNo: number; status: 'NOT_RECORDED' | 'RECORDED'; details: BudgetReviewDetails | null }

const statuses = new Set<unknown>(['WAITING_BUDGET', 'REVIEW_REQUIRED', 'AUTHORIZED', 'CONFIRMED', 'REJECTED', 'CLOSED'])
const operations = new Set<unknown>(['QUEUED', 'EXECUTING', 'UNKNOWN', 'QUERYING', 'APPLIED', 'REJECTED'])
const closures = new Set<unknown>(['WITHDRAWN', 'RETURNED', 'REJECTED', 'CANCELLED'])
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) > 0
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value.length <= 128
const time = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T.*Z$/.test(value) && Number.isFinite(Date.parse(value))
function keys(value: unknown, names: string[]): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === names.length
    && names.every(name => Object.prototype.hasOwnProperty.call(value, name))
}
function requireValue(valid: unknown): asserts valid {
  if (!valid) throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '预算审批依据不完整或与原轮次不符，请重新读取。' }
}
/** 只接收原轮次、原预算操作和真实审批依据；人工授权不能被解释为预算成功。 */
export function readBudgetReviewView(value: unknown, reportId: string, applicationId: string, roundNo: number): BudgetReviewView {
  requireValue(keys(value, ['reportId', 'applicationId', 'roundNo', 'status', 'details']) && uuid(value.reportId) && value.reportId === reportId
    && uuid(value.applicationId) && value.applicationId === applicationId && positive(value.roundNo) && value.roundNo === roundNo)
  if (value.status === 'NOT_RECORDED') requireValue(value.details === null)
  else {
    const details = value.details
    requireValue(value.status === 'RECORDED' && keys(details, ['version', 'financialVersion', 'budgetNodeId', 'policyReference',
      'originalOperationId', 'originalOperationStatus', 'authorizedOperationId', 'authorizedOperationStatus', 'status', 'decision', 'automaticPass', 'closure', 'submittedAt', 'updatedAt'])
      && positive(details.version) && positive(details.financialVersion) && details.financialVersion >= 2 && statuses.has(details.status)
      && (details.budgetNodeId === null || text(details.budgetNodeId)) && (details.policyReference === null || text(details.policyReference) && text(details.budgetNodeId))
      && uuid(details.originalOperationId) && operations.has(details.originalOperationStatus)
      && time(details.submittedAt) && time(details.updatedAt) && Date.parse(details.updatedAt) >= Date.parse(details.submittedAt))
    const within = (at: unknown) => time(at) && Date.parse(at) >= Date.parse(details.submittedAt as string) && Date.parse(at) <= Date.parse(details.updatedAt as string)
    if (details.decision === null) requireValue(details.authorizedOperationId === null && details.authorizedOperationStatus === null)
    else {
      const decision = details.decision
      requireValue(keys(decision, ['actorId', 'taskId', 'auditEventId', 'approvedAt']) && text(decision.actorId) && text(decision.taskId)
        && uuid(decision.auditEventId) && within(decision.approvedAt) && uuid(details.authorizedOperationId) && details.authorizedOperationId !== details.originalOperationId
        && operations.has(details.authorizedOperationStatus) && text(details.policyReference) && text(details.budgetNodeId) && details.originalOperationStatus === 'REJECTED')
    }
    if (details.automaticPass !== null) {
      const passed = details.automaticPass
      requireValue(keys(passed, ['taskId', 'auditEventId', 'passedAt']) && text(passed.taskId) && uuid(passed.auditEventId) && within(passed.passedAt)
        && details.decision === null && text(details.budgetNodeId) && details.originalOperationStatus === 'APPLIED' && ['CONFIRMED', 'CLOSED'].includes(details.status as string))
    }
    requireValue(details.status === 'CLOSED' ? closures.has(details.closure) : details.closure === null)
    requireValue(details.status === 'WAITING_BUDGET' ? details.version === 1 && details.updatedAt === details.submittedAt : (details.version as number) >= 2)
    if (details.status === 'WAITING_BUDGET' || details.status === 'REVIEW_REQUIRED') requireValue(details.decision === null && details.automaticPass === null)
    if (details.status === 'REVIEW_REQUIRED') requireValue(text(details.policyReference) && text(details.budgetNodeId) && details.originalOperationStatus === 'REJECTED')
    if (details.status === 'AUTHORIZED') requireValue(details.decision !== null && details.automaticPass === null)
    const active = details.decision === null ? details.originalOperationStatus : details.authorizedOperationStatus
    if (details.status === 'CONFIRMED') requireValue(active === 'APPLIED')
    if (details.status === 'REJECTED') requireValue(active === 'REJECTED')
  }
  return value as unknown as BudgetReviewView
}

export const budgetReviewLabels: Record<BudgetReviewStatus, string> = {
  WAITING_BUDGET: '等待本轮预算结果', REVIEW_REQUIRED: '需要预算负责人审批', AUTHORIZED: '例外已批准，等待预算确认',
  CONFIRMED: '本轮预算操作已确认', REJECTED: '本轮预算操作被拒绝', CLOSED: '本轮预算审批已关闭',
}
export const budgetOperationLabels: Record<BudgetOperationStatus, string> = {
  QUEUED: '等待处理', EXECUTING: '请求处理中', UNKNOWN: '结果未知，等待核对原操作', QUERYING: '正在核对原操作', APPLIED: '已确认生效', REJECTED: '已明确拒绝',
}
export const budgetApprovalIssues: Record<string, string> = {
  EXPENSE_BUDGET_RESULT_PENDING: '本轮预算结果尚未确认，暂不能同意。', EXPENSE_BUDGET_AUTOMATIC_PENDING: '预算已确认，等待系统推进本节点。',
  EXPENSE_BUDGET_REVIEW_UNAVAILABLE: '预算审批与原轮次不一致，请刷新核对。', EXPENSE_BUDGET_REVIEW_NOT_ALLOWED: '本轮预算审批已办理或关闭。',
  EXPENSE_BUDGET_REJECTED: '本轮预算操作被拒绝，请按退回意见补正。', EXPENSE_BUDGET_NOT_CONFIRMED: '当前核定金额尚未实际冻结预算，暂不能同意。',
  EXPENSE_PAPER_RECEIPT_REQUIRED: '本轮纸质原件尚未签收，暂不能同意。', TASK_DELEGATION_PENDING: '任务正在委派办理，回交后再核对可办动作。',
  FORBIDDEN: '当前身份没有此任务的审批权限。',
}
