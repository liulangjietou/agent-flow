import type { Definition } from './api'
import type { FinanceCatalog } from './expenseDraft'
import type { InitiatorContext } from './initiatorContext'
import { amountMinor, type Money } from './expenses.js'

export type BudgetAdjustmentType = 'INCREASE' | 'DECREASE' | 'TRANSFER'
export interface BudgetAdjustmentContent {
  legalEntityId: string; title: string; purpose: string; type: BudgetAdjustmentType; accountingDate: string
  sourceBudgetReference: string | null; targetBudgetReference: string | null; amount: Money
}
export interface BudgetAdjustmentVersions { applicationVersion: number; requestVersion: number }
export interface BudgetAdjustmentPosition {
  budgetReference: string; name: string; version: string; periodReference: string; periodStart: string; periodEnd: string; periodStatus: 'OPEN' | 'CLOSED'
  beforeLimit: Money; committed: Money; consumed: Money; available: Money; proposedLimit: Money
}
export interface BudgetAdjustmentRound {
  roundNo: number; submittedRequestVersion: number; submittedBy: string; submittedAt: string; content: BudgetAdjustmentContent
  legalEntity: FinanceCatalog['legalEntities'][number]; catalogVersion: string; ledgerVersion: string; observedAt: string; positions: BudgetAdjustmentPosition[]
}
export interface BudgetAdjustmentApproval { roundNo: number; applicationVersion: number; approvedBy: string; approvedAt: string }
export interface BudgetAdjustmentDetail extends BudgetAdjustmentVersions { id: string; applicationId: string; businessNo: string; status: string; roundNo: number; editable: boolean; content: BudgetAdjustmentContent; financialRound?: BudgetAdjustmentRound | null; approval?: BudgetAdjustmentApproval | null }
export interface BudgetAdjustmentReceipt extends BudgetAdjustmentVersions { id: string; applicationId: string; roundNo: number; status: string }
export interface BudgetAdjustmentItem extends BudgetAdjustmentVersions { id: string; applicationId: string; businessNo: string; title: string; status: string; roundNo: number; createdAt: string }
export interface BudgetAdjustmentCreate { businessNo: string; processKey: string; definitionVersion: number; content: BudgetAdjustmentContent }
export interface BudgetAdjustmentRevise extends BudgetAdjustmentVersions { content: BudgetAdjustmentContent }
export interface BudgetAdjustmentCheckInput extends BudgetAdjustmentVersions { initiatorAppointmentId: string; targetDigest: string }
export interface BudgetAdjustmentCheckOptions extends BudgetAdjustmentVersions { enabled: boolean; unavailableCode?: string | null; destination?: string | null; targetDigest?: string | null; latestPrecheckId?: string | null }
export interface BudgetAdjustmentCheckView {
  job: BudgetAdjustmentVersions & { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'BLOCKED' | 'UNAVAILABLE'; attempt: number; createdAt: string; startedAt?: string | null; completedAt?: string | null }
  usable: boolean; unavailableCode?: string | null; initiator: InitiatorContext; validUntil?: string | null; preview?: BudgetAdjustmentRound | null; failureCode?: string | null
}
export const budgetAdjustmentTypes: Record<BudgetAdjustmentType, string> = { INCREASE: '预算追加', DECREASE: '预算调减', TRANSFER: '预算调拨' }

/** 预算明细使用独立敏感组，不能用报销或普通表单代替。 */
export function budgetAdjustmentDefinition(definition: Definition | null): boolean {
  const fields = definition?.formSchema?.fields
  const types: Record<string, string> = { budgetAdjustmentDetails: 'TEXT', amount: 'NUMBER', currency: 'TEXT' }
  return !!fields && fields.length === 3 && new Set(fields.map(field => field.key)).size === 3
    && fields.every(field => field.required && types[field.key] === field.type && (field.key !== 'budgetAdjustmentDetails' || field.sensitive === true))
}
export function emptyBudgetAdjustment(): BudgetAdjustmentContent {
  return { legalEntityId: '', title: '', purpose: '', type: 'INCREASE', accountingDate: '', sourceBudgetReference: null, targetBudgetReference: '', amount: { value: '', currency: '' } }
}

/** 只提交申请意图；原额度、占用、期间及拟调整值均由财务台账核对。 */
export function budgetAdjustmentContent(input: BudgetAdjustmentContent, catalog: FinanceCatalog, now = Date.now()): BudgetAdjustmentContent {
  if (!(Date.parse(catalog.validUntil) > now)) throw new Error('财务目录已过期，请刷新后保存。')
  const entity = catalog.legalEntities.find(value => value.id === input.legalEntityId)
  if (!entity) throw new Error('请选择本人可用的预算法人。')
  if (!input.title.trim() || input.title.length > 256) throw new Error('请填写 256 字以内的预算调整标题。')
  if (!input.purpose.trim() || input.purpose.length > 2000) throw new Error('请填写 2000 字以内的预算调整原因。')
  if ([input.title, input.purpose].some(value => /[\u0000-\u001f\u007f-\u009f]/u.test(value))) throw new Error('标题和原因请填写单行文字，不包含控制字符。')
  if (!Object.prototype.hasOwnProperty.call(budgetAdjustmentTypes, input.type)) throw new Error('请选择预算追加、调减或调拨。')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.accountingDate) || !Number.isFinite(Date.parse(input.accountingDate))
    || new Date(input.accountingDate).toISOString().slice(0, 10) !== input.accountingDate) throw new Error('请选择有效的预算调整日期。')
  const source = input.sourceBudgetReference?.trim() || null, target = input.targetBudgetReference?.trim() || null
  const referenceValid = (value: string | null) => !!value && value.length <= 128 && !/[\u0000-\u001f\u007f-\u009f]/u.test(value)
  if (input.type !== 'INCREASE' && !referenceValid(source) || input.type !== 'DECREASE' && !referenceValid(target)) throw new Error('请填写 128 字以内的有效预算编号，不包含控制字符。')
  if (input.type === 'TRANSFER' && source === target) throw new Error('调出与调入预算不能相同。')
  if (amountMinor(input.amount.value) <= 0n || input.amount.currency !== entity.baseCurrency) throw new Error('请按法人本位币填写大于零的调整金额。')
  return { legalEntityId: entity.id, title: input.title.trim(), purpose: input.purpose.trim(), type: input.type, accountingDate: input.accountingDate,
    sourceBudgetReference: input.type === 'INCREASE' ? null : source, targetBudgetReference: input.type === 'DECREASE' ? null : target,
    amount: { value: input.amount.value, currency: entity.baseCurrency } }
}
export function nextBudgetAdjustmentRound(detail: BudgetAdjustmentDetail): number { return detail.status === 'DRAFT' ? detail.roundNo : detail.roundNo + 1 }
/** 点击确认时重验期限、双版本、任职和完整调整意图，不能只沿用先前显示的 READY。 */
export function usableBudgetAdjustmentCheck(view: BudgetAdjustmentCheckView | null, detail: BudgetAdjustmentDetail, appointment: string, now = Date.now()): boolean {
  return !!view && view.usable && view.job.status === 'READY' && !!view.preview && view.preview.roundNo === nextBudgetAdjustmentRound(detail)
    && view.job.applicationVersion === detail.applicationVersion && view.job.requestVersion === detail.requestVersion
    && view.preview.submittedRequestVersion === detail.requestVersion && view.initiator.appointmentId === appointment
    && view.initiator.legalEntityId === detail.content.legalEntityId && !!view.validUntil && Date.parse(view.validUntil) > now
    && sameContent(view.preview.content, detail.content)
}
function sameContent(left: BudgetAdjustmentContent, right: BudgetAdjustmentContent): boolean {
  return left.legalEntityId === right.legalEntityId && left.title === right.title && left.purpose === right.purpose
    && left.type === right.type && left.accountingDate === right.accountingDate
    && (left.sourceBudgetReference ?? null) === (right.sourceBudgetReference ?? null) && (left.targetBudgetReference ?? null) === (right.targetBudgetReference ?? null)
    && left.amount.currency === right.amount.currency && left.amount.value === right.amount.value
}
export const budgetAdjustmentIssues: Record<string, string> = {
  BUDGET_ADJUSTMENT_CHECK_ACTIVE: '已有预算预检正在执行，请刷新状态', BUDGET_ADJUSTMENT_FORM_REQUIRED: '请选择支持预算调整的流程版本',
  BUDGET_ADJUSTMENT_REVIEW_REQUIRED: '所选流程有绕过人工审核的路径，请联系流程管理员',
  BUDGET_ADJUSTMENT_REVIEW_FIELDS_REQUIRED: '审批节点需要完整读取预算调整明细，请联系流程管理员',
  BUDGET_CATALOG_CHANGED: '本人财务目录已变化，请重新预检', BUDGET_BASE_CURRENCY_REQUIRED: '调整金额必须使用所选法人的本位币',
  BUDGET_INITIATOR_MISMATCH: '所选任职须属于本人与本次预算法人', BUDGET_ADJUSTMENT_INITIATOR_MISMATCH: '所选任职须属于本人与本次预算法人',
  BUDGET_POSITION_UNAVAILABLE: '预算台账不可用，请核对法人、日期与预算编号',
  BUDGET_ADJUSTMENT_INSUFFICIENT: '调出金额超过未占用且未使用的余额，请补正后重新预检',
  BUDGET_PERIOD_CLOSED: '预算期间已关闭，请在财务系统核对后重新预检',
  BUDGET_TRANSFER_PERIOD_MISMATCH: '调出与调入预算必须属于同一预算期间',
  BUDGET_ADJUSTMENT_ALREADY_APPROVED: '本轮预算调整已批准，请刷新查看结果',
  INVALID_BUDGET_ADJUSTMENT: '预算方向、日期、编号或金额不一致，请补正后重试'
}

export interface BudgetAdjustmentDraftState {
  detail: BudgetAdjustmentDetail | null; receipt: BudgetAdjustmentReceipt | null; content: BudgetAdjustmentContent; businessNo: string; definition: Definition | null
  baseline: string; pending: { path: string; body: string } | null; requiresRefresh: boolean
}
function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
/** 按身份隔离内存输入；最小回执只确认保存，重新查询后才能预检和提交。 */
export class BudgetAdjustmentDrafts {
  private drafts = new Map<string, BudgetAdjustmentDraftState>()
  private listeners = new Set<(scope: string, key: string) => void>()
  get(scope: string, key: string) { const value = this.drafts.get(JSON.stringify([scope, key])); return value ? copy(value) : null }
  put(scope: string, key: string, value: BudgetAdjustmentDraftState) { if (scope) this.drafts.set(JSON.stringify([scope, key]), copy(value)) }
  clear(scope: string, key: string) { this.drafts.delete(JSON.stringify([scope, key])) }
  hasDrafts() { return [...this.drafts.values()].some(value => JSON.stringify(value.content) !== value.baseline || !value.detail && !!value.businessNo.trim() || !!value.pending) }
  subscribe(listener: (scope: string, key: string) => void) { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  acknowledge(scope: string, path: string, body: string, result: BudgetAdjustmentReceipt): boolean {
    if (path !== '/budget-adjustments' && !/^\/budget-adjustments\/[^/]+\/revise$/.test(path)) return false
    for (const [storedKey, state] of this.drafts) {
      const [owner, key] = JSON.parse(storedKey) as [string, string]
      if (owner !== scope || state.pending?.path !== path || state.pending.body !== body || !result.id || !result.applicationId
        || !Number.isSafeInteger(result.requestVersion) || result.requestVersion < 1 || !Number.isSafeInteger(result.applicationVersion) || result.applicationVersion < 1
        || state.detail && (state.detail.id !== result.id || state.detail.applicationId !== result.applicationId)) continue
      this.put(scope, key, { ...state, receipt: result, pending: null, requiresRefresh: true })
      this.listeners.forEach(listener => listener(scope, key)); return true
    }
    return false
  }
}
export const budgetAdjustmentDrafts = new BudgetAdjustmentDrafts()
