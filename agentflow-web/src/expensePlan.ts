import type { Definition } from './api'
import type { FinanceCatalog } from './expenseDraft'
import type { InitiatorContext } from './initiatorContext'
import { amountMinor, type CostAllocation, type ExpenseContent, type FrozenExpenseLine, type Money } from './expenses.js'

export interface PlanLine { lineNo: number; categoryCode: string; plannedOn: string; endedOn: string | null; cityCode: string; amount: Money; allocations: CostAllocation[]; description: string }
export interface PlanContent { legalEntityId: string; type: ExpenseContent['type']; title: string; lines: PlanLine[] }
export interface PlanVersions { applicationVersion: number; planVersion: number }
export interface PlanRound {
  roundNo: number; submittedPlanVersion: number; submittedBy: string; submittedAt: string; content: PlanContent
  legalEntity: FinanceCatalog['legalEntities'][number]; catalogVersion: string
  lines: Array<{ original: PlanLine; rate: FrozenExpenseLine['assessment']['exchangeRate']; amount: Money; allocations: CostAllocation[] }>
}
export interface PlanDetail extends PlanVersions { id: string; applicationId: string; businessNo: string; status: string; roundNo: number; editable: boolean; content: PlanContent; financialRound?: PlanRound | null }
export interface PlanReceipt extends PlanVersions { id: string; applicationId: string; roundNo: number; status: string }
export interface PlanItem extends PlanVersions { id: string; applicationId: string; businessNo: string; title: string; status: string; roundNo: number; createdAt: string }
export interface PlanCreate { businessNo: string; processKey: string; definitionVersion: number; content: PlanContent }
export interface PlanRevise extends PlanVersions { content: PlanContent }
export interface PlanCheckInput extends PlanVersions { initiatorAppointmentId: string; targetDigest: string }
export interface PlanCheckOptions extends PlanVersions { enabled: boolean; unavailableCode?: string | null; destination?: string | null; targetDigest?: string | null; latestPrecheckId?: string | null }
export interface PlanCheckView {
  job: PlanVersions & { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'BLOCKED' | 'UNAVAILABLE'; attempt: number; createdAt: string; startedAt?: string | null; completedAt?: string | null }
  usable: boolean; unavailableCode?: string | null; initiator: InitiatorContext; validUntil?: string | null; preview?: PlanRound | null; failureCode?: string | null
}

/** 独立计划契约不复用报销制度、税额或发票输入。 */
export function planDefinition(definition: Definition | null): boolean {
  const fields = definition?.formSchema?.fields
  const types: Record<string, string> = { expensePlanDetails: 'TEXT', amount: 'NUMBER', currency: 'TEXT' }
  return !!fields && fields.length === 3 && new Set(fields.map(field => field.key)).size === 3
    && fields.every(field => field.required && types[field.key] === field.type && (field.key !== 'expensePlanDetails' || field.sensitive === true))
}
export function emptyPlan(): PlanContent { return { legalEntityId: '', type: 'TRAVEL', title: '', lines: [] } }
export function newPlanLine(content: PlanContent, currency: string): PlanLine {
  const lineNo = Array.from({ length: 200 }, (_, index) => index + 1).find(number => !content.lines.some(line => line.lineNo === number))
  if (!lineNo) throw new Error('一份计划最多填写 200 行。')
  return { lineNo, categoryCode: '', plannedOn: '', endedOn: null, cityCode: '', amount: { value: '', currency },
    allocations: [{ costCenter: '', projectCode: null, amount: { value: '', currency } }], description: '' }
}

/** 保存时校验计划目录与精确分摊，只返回允许编辑的字段。 */
export function planContent(input: PlanContent, catalog: FinanceCatalog, now = Date.now()): PlanContent {
  if (!(Date.parse(catalog.validUntil) > now)) throw new Error('财务目录已过期，请刷新后保存。')
  if (!catalog.legalEntities.some(entity => entity.id === input.legalEntityId)) throw new Error('请选择本人可用的计划法人。')
  if (!input.title.trim() || input.title.length > 256) throw new Error('请填写 256 字以内的计划标题。')
  if (input.lines.length > 200) throw new Error('一份计划最多填写 200 行。')
  const numbers = new Set<number>()
  const lines = input.lines.map(line => {
    const fail = (message: string): never => { throw new Error(`第 ${line.lineNo} 行：${message}`) }
    if (!Number.isInteger(line.lineNo) || line.lineNo < 1 || line.lineNo > 200 || numbers.has(line.lineNo)) fail('计划行号必须唯一且在 1 至 200 之间。')
    numbers.add(line.lineNo)
    if (!catalog.categories.some(item => item.code === line.categoryCode)) fail('请选择可用的费用类别。')
    if (!catalog.cities.some(item => item.code === line.cityCode)) fail('请选择计划城市。')
    if (!validDate(line.plannedOn) || line.endedOn && (!validDate(line.endedOn) || line.endedOn < line.plannedOn)) fail('请核对计划起止日期。')
    if (!line.description.trim() || line.description.length > 2000) fail('请填写 2000 字以内的计划依据。')
    const currency = line.amount.currency, total = amountMinor(line.amount.value)
    if (!/^[A-Z]{3}$/.test(currency) || total <= 0n) fail('请填写三位大写币种和大于零的计划金额。')
    if (!line.allocations.length || line.allocations.length > 50) fail('每行须有 1 至 50 笔成本分摊。')
    const targets = new Set<string>(); let allocated = 0n
    const allocations = line.allocations.map(allocation => {
      if (!catalog.costCenters.some(item => item.legalEntityId === input.legalEntityId && item.code === allocation.costCenter)) fail('请选择本法人可用的成本中心。')
      const projectCode = allocation.projectCode || null, key = JSON.stringify([allocation.costCenter, projectCode])
      if (projectCode && !catalog.projects.some(item => item.legalEntityId === input.legalEntityId && item.code === projectCode)) fail('请选择本法人可用的项目。')
      if (targets.has(key)) fail('同一成本中心和项目请合并为一笔分摊。')
      targets.add(key)
      const amount = amountMinor(allocation.amount.value)
      if (amount <= 0n) fail('分摊金额必须大于零。')
      allocated += amount
      return { costCenter: allocation.costCenter, projectCode, amount: { value: allocation.amount.value, currency } }
    })
    if (allocated !== total) fail('分摊合计须等于计划金额。')
    return { lineNo: line.lineNo, categoryCode: line.categoryCode, plannedOn: line.plannedOn, endedOn: line.endedOn || null,
      cityCode: line.cityCode, amount: { value: line.amount.value, currency }, allocations, description: line.description.trim() }
  })
  return { legalEntityId: input.legalEntityId, type: input.type, title: input.title.trim(), lines }
}
function validDate(value: string): boolean { return /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value }
export function nextPlanRound(detail: PlanDetail): number { return detail.status === 'DRAFT' ? detail.roundNo : detail.roundNo + 1 }
/** 确认动作执行时重算时效，不能只依赖页面先前缓存的 READY。 */
export function usablePlanCheck(view: PlanCheckView | null, detail: PlanDetail, appointment: string, now = Date.now()): boolean {
  return !!view && view.usable && view.job.status === 'READY' && !!view.preview && view.preview.roundNo === nextPlanRound(detail)
    && view.job.applicationVersion === detail.applicationVersion && view.job.planVersion === detail.planVersion
    && view.preview.submittedPlanVersion === detail.planVersion && view.initiator.appointmentId === appointment
    && view.initiator.legalEntityId === detail.content.legalEntityId && !!view.validUntil && Date.parse(view.validUntil) > now
}
/** 只合计服务端已换算的整分金额，浏览器不自行换汇。 */
export function planTotal(round: PlanRound): Money {
  const total = round.lines.reduce((sum, line) => sum + amountMinor(line.amount.value), 0n)
  return { value: `${total / 100n}.${String(total % 100n).padStart(2, '0')}`, currency: round.legalEntity.baseCurrency }
}
export const planIssues: Record<string, string> = {
  EXPENSE_PLAN_LINES_REQUIRED: '请先保存至少一行计划', EXPENSE_PLAN_CHECK_ACTIVE: '已有检查正在执行，请刷新状态',
  EXPENSE_PLAN_FORM_REQUIRED: '请选择支持事前申请的流程版本', EXPENSE_PLAN_REVIEW_REQUIRED: '所选流程有绕过人工审核的路径，请联系流程管理员',
  EXPENSE_PLAN_REVIEW_FIELDS_REQUIRED: '审批节点需要完整读取计划明细，请联系流程管理员', EXPENSE_PLAN_CATALOG_CHANGED: '本人财务目录已变化，请重新预检',
  RATE_DATE_CHANGED: '法人当地日期已变化，请重新取得汇率', RATE_UNAVAILABLE: '当前汇率不可用，请联系财务后重新检查',
  EXPENSE_PLAN_CATEGORY_UNAVAILABLE: '计划类别或城市已不可用，请刷新目录后补正', COST_OBJECT_UNAVAILABLE: '计划成本归属已不可用，请刷新目录后补正'
}

export interface PlanDraftState { detail: PlanDetail | null; content: PlanContent; businessNo: string; definition: Definition | null; baseline: string; pending: { path: string; body: string } | null; requiresRefresh: boolean }
function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
/** 输入只留在按身份隔离的页面内存中，原请求回执不能确认别的计划。 */
export class PlanDrafts {
  private drafts = new Map<string, PlanDraftState>()
  private listeners = new Set<(scope: string, key: string) => void>()
  get(scope: string, key: string) { const value = this.drafts.get(JSON.stringify([scope, key])); return value ? copy(value) : null }
  put(scope: string, key: string, value: PlanDraftState) { if (scope) this.drafts.set(JSON.stringify([scope, key]), copy(value)) }
  clear(scope: string, key: string) { this.drafts.delete(JSON.stringify([scope, key])) }
  hasDrafts() { return [...this.drafts.values()].some(value => JSON.stringify(value.content) !== value.baseline || !value.detail && !!value.businessNo.trim() || !!value.pending) }
  subscribe(listener: (scope: string, key: string) => void) { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  acknowledge(scope: string, path: string, body: string, result: PlanDetail): boolean {
    if (path !== '/expense-plans' && !/^\/expense-plans\/[^/]+\/revise$/.test(path)) return false
    for (const [storedKey, state] of this.drafts) {
      const [owner, key] = JSON.parse(storedKey) as [string, string]
      if (owner !== scope || state.pending?.path !== path || state.pending.body !== body || !result.id || !result.applicationId
        || state.detail && (state.detail.id !== result.id || state.detail.applicationId !== result.applicationId)) continue
      this.put(scope, key, { ...state, detail: result, content: result.content, businessNo: result.businessNo, baseline: JSON.stringify(result.content), pending: null, requiresRefresh: false })
      this.listeners.forEach(listener => listener(scope, key)); return true
    }
    return false
  }
}
export const planDrafts = new PlanDrafts()
