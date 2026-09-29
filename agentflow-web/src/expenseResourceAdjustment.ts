import { amountMinor, expenseError, type Money } from './expenses.js'

/** 原报销和独立调整分开保存，页面只提交已展示版本和明确办理意图。@author owlzhangfq@gmail.com */
export interface AdjustmentBinding { reportId: string; applicationId: string; roundNo: number; applicationVersion: number; businessVersion: number }
export type AdjustmentStatus = 'WAITING_BUDGET' | 'READY' | 'APPLIED' | 'REVIEW_REQUIRED' | 'RETIRED'
export type AdjustmentAction = 'QUERY' | 'RESEND_ORIGINAL' | 'RETRY_RESOURCES' | 'CONFIRM_COMPLETED'
export type AdjustmentIntent = 'PREPARE' | 'AUTHORIZE' | 'RETIRE' | AdjustmentAction
export interface AdjustmentBudget { version: number; status: 'QUEUED' | 'EXECUTING' | 'QUERYING' | 'UNKNOWN' | 'APPLIED' | 'REJECTED' | 'NOT_FOUND' | 'EXPIRED' | 'VOIDED' | 'RECONCILING'; attempts: number; expiresAt: string; updatedAt: string; issue: string | null; acceptedReference: string | null; acceptedAt: string | null }
export interface AdjustmentPreparation { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'AUTHORIZED' | 'UNAVAILABLE' | 'VOIDED'; accountingDate: string; evidenceReference: string; reason: string; requestedAt: string; updatedAt: string; issue: string | null; periodReference: string | null; expiresAt: string | null; canAuthorize: boolean; authorizationIssue: string | null }
export interface AdjustmentEntry { id: string; version: number; status: AdjustmentStatus; resourcesReversed: boolean; issue: string | null; authorizedBy: string; authorizedAt: string; updatedAt: string; accountingDate: string; evidenceReference: string; reason: string; budget: AdjustmentBudget; availableActions: AdjustmentAction[]; canRetire: boolean; retirement: { retiredBy: string; retiredAt: string; evidenceReference: string; reason: string } | null }
export interface AdjustmentView extends AdjustmentBinding { settlementVersion: number; original: { gross: Money; offsets: Money; payable: Money; budgetOperationId: string | null }; finance: boolean; canPrepare: boolean; preparationIssue: string | null; latestPreparation: AdjustmentPreparation | null; adjustments: AdjustmentEntry[] }
interface Versions { roundNo: number; applicationVersion: number; businessVersion: number }
export interface AdjustmentPrepareInput extends Versions { settlementVersion: number; accountingDate: string; evidenceReference: string; reason: string }
export interface AdjustmentAuthorizeInput extends Versions { settlementVersion: number; preparationId: string; preparationVersion: number; comment: string }
export interface AdjustmentOperationInput extends Versions { adjustmentId: string; adjustmentVersion: number; budgetVersion: number; action: AdjustmentAction; comment: string }
export interface AdjustmentRetireInput extends Versions { adjustmentId: string; adjustmentVersion: number; budgetVersion: number; evidenceReference: string; reason: string }
export interface AdjustmentPreparationReceipt { reportId: string; roundNo: number; preparationId: string; preparationVersion: number; adjustmentId: string | null; adjustmentVersion: number | null; budgetVersion: number | null; auditEventId: string }
export interface AdjustmentActionReceipt { reportId: string; roundNo: number; adjustmentId: string; adjustmentVersion: number; budgetVersion: number; status: AdjustmentStatus; budgetStatus: AdjustmentBudget['status']; resourcesReversed: boolean; auditEventId: string }
export const adjustmentLabels: Record<AdjustmentStatus, string> = { WAITING_BUDGET: '等待预算冲正确认', READY: '预算已确认，等待资源冲回', APPLIED: '独立调整已完成', REVIEW_REQUIRED: '本次调整需复核', RETIRED: '本次调整已安全结束' }
export const adjustmentBudgetLabels: Record<AdjustmentBudget['status'], string> = { QUEUED: '已授权，等待发送', EXECUTING: '正在发送原预算命令', QUERYING: '正在查询原预算命令', UNKNOWN: '预算结果待确认', APPLIED: '预算冲正已确认', REJECTED: '预算冲正已明确拒绝', NOT_FOUND: '原命令查无，等待明确处理', EXPIRED: '原发送授权已过期', VOIDED: '原命令已停止发送', RECONCILING: '预算回执存在争议' }
export const adjustmentPreparationLabels: Record<AdjustmentPreparation['status'], string> = { QUEUED: '等待读取会计期间', RUNNING: '正在读取会计期间', READY: '期间已准备，等待明确授权', AUTHORIZED: '本次准备已授权', UNAVAILABLE: '期间准备不可用', VOIDED: '原准备依据已失效' }
export const adjustmentIntentLabels: Record<AdjustmentIntent, string> = { PREPARE: '准备全额取消依据', AUTHORIZE: '授权预算冲正与资源冲回', QUERY: '查询原预算结果', RESEND_ORIGINAL: '明确重发原预算命令', RETRY_RESOURCES: '恢复未完成资源冲回', CONFIRM_COMPLETED: '确认原调整仍然有效', RETIRE: '安全结束本次调整' }
const issues: Record<string, string> = {
  INDEPENDENT_FINANCE_REQUIRED: '本次办理需要同法人独立财务人员。', EXPENSE_ADJUSTMENT_SOURCE_CHANGED: '请先核清原挂账冲销登记、全额银行退回及原预算消费依据。',
  INVALID_EXPENSE_ADJUSTMENT_BASIS: '完整取消所需的财务原件尚不齐全，请核对挂账冲销、实际退回及原应付科目。',
  EXPENSE_ADJUSTMENT_EXISTS: '原报销已有独立调整，请继续办理或核对该调整。', EXPENSE_ADJUSTMENT_PREPARATION_PENDING: '本人的期间准备正在处理，请刷新查看。',
  EXPENSE_ADJUSTMENT_NOT_READY: '本次期间准备尚不可授权。', EXPENSE_ADJUSTMENT_PREPARATION_EXPIRED: '期间依据已过期，请重新准备后授权。',
  BUDGET_AUTHORIZATION_EXPIRED: '发送授权已过期，请核对原预算结果后再处理。', BUDGET_RECHECK_REQUIRED: '正在复核原预算结果，已完成资源记录保持。',
  SOURCE_CHANGED: '原财务依据或授权人资格已变化。', PAYMENT_ACTOR_UNAVAILABLE: '当前财务任职已变化。', FINANCE_TARGET_CHANGED: '原财务服务配置已变化。', TARGET_CHANGED: '原财务服务配置已变化。',
  NOT_CONFIGURED: '财务服务尚未配置。', INVALID_RESPONSE: '财务回执不完整或不属于原预算命令，请查询原结果。', AUTHORIZATION_EXPIRED: '原发送授权已过期。', RECHECK_REQUESTED: '等待查询原预算结果。',
  BUDGET_RECONCILING: '预算回执存在冲突，需要核查外部原账。', INCONSISTENT_OBSERVATION: '预算回执存在冲突，需要核查外部原账。',
  TIMEOUT: '本次财务读取超时，请刷新核对。', CONNECTION: '财务服务暂时无法连接。', AUTHENTICATION: '财务服务认证失败，请联系管理员。',
  REMOTE_FAILURE: '财务服务暂未返回可用结果。', INTERNAL_ERROR: '本次办理尚未完成，请刷新核对原状态。', RESPONSE_TOO_LARGE: '财务回执超出允许范围。', LEASE_EXPIRED: '上次处理结果未知，继续查询原命令。',
}
export function adjustmentIssue(code: string) { return issues[code] ?? '当前调整条件尚不满足，请刷新并核对原财务依据。' }
export function adjustmentError(cause: unknown) { const code = (cause as { code?: string })?.code; return code && issues[code] ? issues[code]! : expenseError(cause) }
function requireValue(value: unknown): asserts value { if (!value) throw new Error('独立调整数据与当前报销不一致，请刷新核对。') }
function text(value: unknown, max = 128): asserts value is string { requireValue(typeof value === 'string' && value.length > 0 && value.length <= max && value === value.trim() && !/[\u0000-\u001f\u007f-\u009f]/.test(value)) }
function uuid(value: unknown) { text(value); requireValue(/^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value)) }
function version(value: number, min = 1) { requireValue(Number.isSafeInteger(value) && value >= min) }
function instant(value: unknown): number { requireValue(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function date(value: unknown) { requireValue(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value) }
function code(value: unknown) { requireValue(value === null || typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value)) }
function known(values: object, key: unknown) { return typeof key === 'string' && Object.prototype.hasOwnProperty.call(values, key) }
function note(value: string, max = 2000) { const result = value.trim(); text(result, max); return result }
/** 核对实际金额、单据版本和分步结果，错误响应不能留下可执行按钮。 */
export function validateAdjustment(value: AdjustmentView, binding: AdjustmentBinding) {
  requireValue(value && Object.entries(binding).every(([key, expected]) => value[key as keyof AdjustmentBinding] === expected))
  uuid(value.reportId); uuid(value.applicationId); version(value.roundNo); version(value.applicationVersion); version(value.businessVersion); version(value.settlementVersion)
  requireValue(value.original && typeof value.finance === 'boolean' && typeof value.canPrepare === 'boolean' && Array.isArray(value.adjustments)); code(value.preparationIssue)
  const original = value.original; requireValue(/^[A-Z]{3}$/.test(original.gross?.currency))
  requireValue(original.gross.currency === original.offsets?.currency && original.gross.currency === original.payable?.currency)
  requireValue(amountMinor(original.gross.value) === amountMinor(original.offsets.value) + amountMinor(original.payable.value))
  if (original.budgetOperationId !== null) uuid(original.budgetOperationId)
  const preparation = value.latestPreparation
  if (preparation !== null) {
    requireValue(value.finance && preparation && known(adjustmentPreparationLabels, preparation.status)); uuid(preparation.id); version(preparation.version); date(preparation.accountingDate)
    text(preparation.evidenceReference); text(preparation.reason, 2000); requireValue(instant(preparation.updatedAt) >= instant(preparation.requestedAt)); code(preparation.issue); code(preparation.authorizationIssue)
    requireValue(typeof preparation.canAuthorize === 'boolean')
    const prepared = ['READY', 'AUTHORIZED'].includes(preparation.status)
    requireValue(prepared ? preparation.periodReference !== null && preparation.expiresAt !== null : preparation.periodReference === null && preparation.expiresAt === null)
    if (prepared) { text(preparation.periodReference); requireValue(instant(preparation.expiresAt) > instant(preparation.updatedAt)) }
    requireValue(!preparation.canAuthorize || preparation.status === 'READY' && preparation.authorizationIssue === null)
  }
  const seen = new Set<string>(); let active = 0
  for (const entry of value.adjustments) {
    requireValue(entry && known(adjustmentLabels, entry.status) && typeof entry.resourcesReversed === 'boolean' && typeof entry.canRetire === 'boolean')
    uuid(entry.id); version(entry.version); requireValue(!seen.has(entry.id)); seen.add(entry.id); if (entry.status !== 'RETIRED') active++
    text(entry.authorizedBy); text(entry.evidenceReference); text(entry.reason, 2000); date(entry.accountingDate); code(entry.issue)
    const authorized = instant(entry.authorizedAt); requireValue(instant(entry.updatedAt) >= authorized); const budget = entry.budget
    requireValue(budget && known(adjustmentBudgetLabels, budget.status)); version(budget.version); version(budget.attempts, 0); code(budget.issue)
    requireValue(instant(budget.updatedAt) >= authorized && instant(budget.expiresAt) > authorized && instant(budget.expiresAt) - authorized <= 300000)
    requireValue((budget.acceptedReference === null) === (budget.acceptedAt === null))
    if (budget.acceptedReference !== null) { text(budget.acceptedReference); requireValue(instant(budget.acceptedAt) >= authorized) }
    requireValue(!['READY', 'APPLIED'].includes(entry.status) || budget.acceptedReference !== null)
    requireValue(entry.status !== 'APPLIED' || entry.resourcesReversed)
    requireValue(!entry.resourcesReversed || budget.acceptedReference !== null && ['APPLIED', 'REVIEW_REQUIRED'].includes(entry.status))
    requireValue((entry.status === 'REVIEW_REQUIRED') === (entry.issue !== null))
    requireValue(Array.isArray(entry.availableActions) && new Set(entry.availableActions).size === entry.availableActions.length)
    for (const action of entry.availableActions) {
      requireValue(value.finance && ['QUERY', 'RESEND_ORIGINAL', 'RETRY_RESOURCES', 'CONFIRM_COMPLETED'].includes(action) && entry.status !== 'RETIRED')
      if (action === 'QUERY') requireValue(budget.attempts > 0 && !['QUEUED', 'EXECUTING', 'QUERYING'].includes(budget.status))
      if (action === 'RESEND_ORIGINAL') requireValue(budget.status === 'NOT_FOUND' && !entry.resourcesReversed && budget.acceptedReference === null)
      if (action === 'RETRY_RESOURCES' || action === 'CONFIRM_COMPLETED') requireValue(entry.status === 'REVIEW_REQUIRED' && budget.status === 'APPLIED' && entry.resourcesReversed === (action === 'CONFIRM_COMPLETED'))
    }
    if (entry.canRetire) requireValue(value.finance && entry.status !== 'RETIRED' && !entry.resourcesReversed && budget.acceptedReference === null && (budget.status === 'REJECTED' || budget.attempts === 0 && ['QUEUED', 'EXPIRED', 'VOIDED'].includes(budget.status)))
    if (entry.status === 'RETIRED') {
      requireValue(entry.retirement && !entry.resourcesReversed && budget.acceptedReference === null && !entry.canRetire && entry.availableActions.length === 0)
      text(entry.retirement.retiredBy); text(entry.retirement.evidenceReference); text(entry.retirement.reason, 2000); requireValue(instant(entry.retirement.retiredAt) === instant(entry.updatedAt))
    } else requireValue(entry.retirement === null)
  }
  requireValue(active <= 1 && (!value.canPrepare || value.finance && active === 0 && value.preparationIssue === null && !['QUEUED', 'RUNNING'].includes(preparation?.status ?? '')))
  requireValue(!preparation?.canAuthorize || active === 0)
  return value
}
function versions(view: AdjustmentView): Versions { return { roundNo: view.roundNo, applicationVersion: view.applicationVersion, businessVersion: view.businessVersion } }
export function adjustmentPrepareInput(view: AdjustmentView, accountingDate: string, evidence: string, reason: string): AdjustmentPrepareInput {
  requireValue(view.finance && view.canPrepare); date(accountingDate)
  return { ...versions(view), settlementVersion: view.settlementVersion, accountingDate, evidenceReference: note(evidence, 128), reason: note(reason) }
}
export function adjustmentAuthorizeInput(view: AdjustmentView, comment: string, now = Date.now()): AdjustmentAuthorizeInput {
  const prepared = view.latestPreparation; requireValue(view.finance && prepared?.canAuthorize && prepared.expiresAt)
  if (now < instant(prepared.updatedAt) || now >= instant(prepared.expiresAt)) throw new Error('期间依据已过期，请重新准备后授权。')
  return { ...versions(view), settlementVersion: view.settlementVersion, preparationId: prepared.id, preparationVersion: prepared.version, comment: note(comment) }
}
export function adjustmentOperationInput(view: AdjustmentView, id: string, action: AdjustmentAction, comment: string, now = Date.now()): AdjustmentOperationInput {
  const entry = view.adjustments.find(item => item.id === id); requireValue(view.finance && entry && entry.availableActions.includes(action))
  if (action === 'RESEND_ORIGINAL' && now >= instant(entry.budget.expiresAt)) throw new Error('原发送授权已过期，不能重新发送。')
  return { ...versions(view), adjustmentId: entry.id, adjustmentVersion: entry.version, budgetVersion: entry.budget.version, action, comment: note(comment) }
}
export function adjustmentRetireInput(view: AdjustmentView, id: string, evidence: string, reason: string): AdjustmentRetireInput {
  const entry = view.adjustments.find(item => item.id === id); requireValue(view.finance && entry?.canRetire)
  return { ...versions(view), adjustmentId: entry.id, adjustmentVersion: entry.version, budgetVersion: entry.budget.version, evidenceReference: note(evidence, 128), reason: note(reason) }
}
/** 接受回执只表明本次意图已保存，不能推断后台已经预算冲正或资源完成。 */
export function validateAdjustmentPreparationReceipt(receipt: AdjustmentPreparationReceipt, view: AdjustmentView, input: AdjustmentPrepareInput | AdjustmentAuthorizeInput) {
  requireValue(receipt && receipt.reportId === view.reportId && receipt.roundNo === input.roundNo); uuid(receipt.preparationId); uuid(receipt.auditEventId)
  if ('preparationId' in input) requireValue(receipt.preparationId === input.preparationId && receipt.preparationVersion === input.preparationVersion + 1 && receipt.adjustmentId === input.preparationId && receipt.adjustmentVersion === 1 && receipt.budgetVersion === 1)
  else requireValue(receipt.preparationVersion === 1 && receipt.adjustmentId === null && receipt.adjustmentVersion === null && receipt.budgetVersion === null)
}
/** 原查询、重发及恢复各有确定版本变化，错单或伪造完成回执必须重新读取。 */
export function validateAdjustmentActionReceipt(receipt: AdjustmentActionReceipt, view: AdjustmentView, input: AdjustmentOperationInput | AdjustmentRetireInput) {
  const entry = view.adjustments.find(item => item.id === input.adjustmentId); requireValue(entry && receipt && receipt.reportId === view.reportId && receipt.roundNo === input.roundNo && receipt.adjustmentId === entry.id); uuid(receipt.auditEventId)
  const action = 'action' in input ? input.action : 'RETIRE'
  const adjustmentDelta = action === 'QUERY' ? (['READY', 'APPLIED'].includes(entry.status) ? 1 : 0) : action === 'RESEND_ORIGINAL' ? 0 : 1
  const budgetDelta = ['QUERY', 'RESEND_ORIGINAL'].includes(action) || action === 'RETIRE' && entry.budget.status === 'QUEUED' ? 1 : 0
  const status = action === 'QUERY' ? (adjustmentDelta ? 'REVIEW_REQUIRED' : entry.status) : action === 'RESEND_ORIGINAL' ? entry.status : action === 'RETRY_RESOURCES' ? 'READY' : action === 'CONFIRM_COMPLETED' ? 'APPLIED' : 'RETIRED'
  const budgetStatus = action === 'QUERY' ? 'UNKNOWN' : action === 'RESEND_ORIGINAL' ? 'QUEUED' : action === 'RETIRE' && budgetDelta ? 'VOIDED' : entry.budget.status
  requireValue(receipt.adjustmentVersion === input.adjustmentVersion + adjustmentDelta && receipt.budgetVersion === input.budgetVersion + budgetDelta && receipt.status === status && receipt.budgetStatus === budgetStatus && receipt.resourcesReversed === entry.resourcesReversed)
}
