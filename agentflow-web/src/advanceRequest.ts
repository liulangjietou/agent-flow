import type { Definition } from './api'
import type { FinanceCatalog } from './expenseDraft'
import type { InitiatorContext } from './initiatorContext'
import { amountMinor, type Money } from './expenses.js'

export interface AdvanceContent { legalEntityId: string; title: string; purpose: string; amount: Money; dueOn: string }
export interface AdvanceVersions { applicationVersion: number; requestVersion: number }
export interface AdvanceRound {
  roundNo: number; submittedRequestVersion: number; submittedBy: string; submittedAt: string; content: AdvanceContent
  legalEntity: FinanceCatalog['legalEntities'][number]; catalogVersion: string; maskedAccount: string
}
export interface AdvanceApproval { roundNo: number; applicationVersion: number; approvedBy: string; approvedAt: string }
export interface AdvanceDetail extends AdvanceVersions { id: string; applicationId: string; businessNo: string; status: string; roundNo: number; editable: boolean; content: AdvanceContent; financialRound?: AdvanceRound | null; approval?: AdvanceApproval | null }
export interface AdvanceReceipt extends AdvanceVersions { id: string; applicationId: string; roundNo: number; status: string }
export interface AdvanceRequestItem extends AdvanceVersions { id: string; applicationId: string; businessNo: string; title: string; status: string; roundNo: number; createdAt: string }
export interface AdvanceCreate { businessNo: string; processKey: string; definitionVersion: number; content: AdvanceContent }
export interface AdvanceRevise extends AdvanceVersions { content: AdvanceContent }
export interface AdvanceCheckInput extends AdvanceVersions { initiatorAppointmentId: string; targetDigest: string }
export interface AdvanceCheckOptions extends AdvanceVersions { enabled: boolean; unavailableCode?: string | null; destination?: string | null; targetDigest?: string | null; latestPrecheckId?: string | null; overduePolicy: 'UNCONFIGURED' | 'ALLOW' | 'BLOCK' }
/** 未配置必须明确显示，不能将缺失配置解释为已经实施了逾期控制。 */
export function advanceOverduePolicyText(policy: AdvanceCheckOptions['overduePolicy'] | undefined): string {
  if (policy === 'BLOCK') return '本租户已启用逾期控制：本人在本次法人下存在逾期借款时，不能提交新借款。正式提交时会再次核对。'
  if (policy === 'ALLOW') return '本租户已明确允许有逾期借款时提交新借款，逾期提醒仍独立执行。'
  if (policy === 'UNCONFIGURED') return '本租户尚未配置是否禁止逾期员工提交新借款，当前不因逾期自动阻断。'
  return '未能读取逾期控制配置，请刷新后核对。'
}
export interface AdvanceCheckView {
  job: AdvanceVersions & { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'BLOCKED' | 'UNAVAILABLE'; attempt: number; createdAt: string; startedAt?: string | null; completedAt?: string | null }
  usable: boolean; unavailableCode?: string | null; initiator: InitiatorContext; validUntil?: string | null; preview?: AdvanceRound | null; failureCode?: string | null
}

/** 借款明细使用独立敏感组，不能用报销或普通表单代替。 */
export function advanceDefinition(definition: Definition | null): boolean {
  const fields = definition?.formSchema?.fields
  const types: Record<string, string> = { advanceRequestDetails: 'TEXT', amount: 'NUMBER', currency: 'TEXT' }
  return !!fields && fields.length === 3 && new Set(fields.map(field => field.key)).size === 3
    && fields.every(field => field.required && types[field.key] === field.type && (field.key !== 'advanceRequestDetails' || field.sensitive === true))
}
export function emptyAdvance(): AdvanceContent { return { legalEntityId: '', title: '', purpose: '', amount: { value: '', currency: '' }, dueOn: '' } }

/** 只提交允许编辑的约定，浏览器不能附带账户引用、摘要或批准结果。 */
export function advanceContent(input: AdvanceContent, catalog: FinanceCatalog, now = Date.now()): AdvanceContent {
  if (!(Date.parse(catalog.validUntil) > now)) throw new Error('财务目录已过期，请刷新后保存。')
  const entity = catalog.legalEntities.find(value => value.id === input.legalEntityId)
  if (!entity) throw new Error('请选择本人可用的借款法人。')
  if (!input.title.trim() || input.title.length > 256) throw new Error('请填写 256 字以内的借款标题。')
  if (!input.purpose.trim() || input.purpose.length > 2000) throw new Error('请填写 2000 字以内的借款用途。')
  if (amountMinor(input.amount.value) <= 0n || input.amount.currency !== entity.baseCurrency) throw new Error('请按法人本位币填写大于零的借款金额。')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.dueOn) || !Number.isFinite(Date.parse(input.dueOn)) || new Date(input.dueOn).toISOString().slice(0, 10) !== input.dueOn) throw new Error('请填写有效的归还日期。')
  return { legalEntityId: input.legalEntityId, title: input.title.trim(), purpose: input.purpose.trim(),
    amount: { value: input.amount.value, currency: entity.baseCurrency }, dueOn: input.dueOn }
}
export function nextAdvanceRound(detail: AdvanceDetail): number { return detail.status === 'DRAFT' ? detail.roundNo : detail.roundNo + 1 }
/** 点击确认时重验时效、双版本与任职，不能只沿用先前显示的 READY。 */
export function usableAdvanceCheck(view: AdvanceCheckView | null, detail: AdvanceDetail, appointment: string, now = Date.now()): boolean {
  return !!view && view.usable && view.job.status === 'READY' && !!view.preview && view.preview.roundNo === nextAdvanceRound(detail)
    && view.job.applicationVersion === detail.applicationVersion && view.job.requestVersion === detail.requestVersion
    && view.preview.submittedRequestVersion === detail.requestVersion && view.initiator.appointmentId === appointment
    && view.initiator.legalEntityId === detail.content.legalEntityId && !!view.validUntil && Date.parse(view.validUntil) > now
}
export const advanceIssues: Record<string, string> = {
  ADVANCE_OVERDUE: '本人在本次法人下仍有逾期借款，请处理未还余额后重新预检。',
  ADVANCE_REQUEST_CHECK_ACTIVE: '已有借款预检正在执行，请刷新状态', ADVANCE_REQUEST_FORM_REQUIRED: '请选择支持员工借款的流程版本',
  ADVANCE_REQUEST_REVIEW_REQUIRED: '所选流程有绕过人工审核的路径，请联系流程管理员',
  ADVANCE_REQUEST_REVIEW_FIELDS_REQUIRED: '审批节点需要完整读取借款明细，请联系流程管理员',
  ADVANCE_CATALOG_CHANGED: '本人财务目录已变化，请重新预检', ADVANCE_ACCOUNT_EXPIRED: '本人收款账户依据已过期，请重新预检',
  ADVANCE_BASE_CURRENCY_REQUIRED: '借款金额必须使用所选法人的本位币', ADVANCE_REPAYMENT_DATE_PASSED: '归还日已早于法人当地提交日，请补正后重新预检',
  ADVANCE_INITIATOR_MISMATCH: '所选任职须属于本人与本次借款法人', SUBMISSION_DATE_CHANGED: '法人当地日期已变化，请重新预检',
  ACCOUNT_UNAVAILABLE: '本人收款账户不可用，请在财务主数据系统维护后重新预检'
}

export interface AdvanceDraftState {
  detail: AdvanceDetail | null; receipt: AdvanceReceipt | null; content: AdvanceContent; businessNo: string; definition: Definition | null
  baseline: string; pending: { path: string; body: string } | null; requiresRefresh: boolean
}
function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
/** 按身份隔离内存输入；最小回执只确认保存，必须重新查询后才能提交。 */
export class AdvanceDrafts {
  private drafts = new Map<string, AdvanceDraftState>()
  private listeners = new Set<(scope: string, key: string) => void>()
  get(scope: string, key: string) { const value = this.drafts.get(JSON.stringify([scope, key])); return value ? copy(value) : null }
  put(scope: string, key: string, value: AdvanceDraftState) { if (scope) this.drafts.set(JSON.stringify([scope, key]), copy(value)) }
  clear(scope: string, key: string) { this.drafts.delete(JSON.stringify([scope, key])) }
  hasDrafts() { return [...this.drafts.values()].some(value => JSON.stringify(value.content) !== value.baseline || !value.detail && !!value.businessNo.trim() || !!value.pending) }
  subscribe(listener: (scope: string, key: string) => void) { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  acknowledge(scope: string, path: string, body: string, result: AdvanceReceipt): boolean {
    if (path !== '/advance-requests' && !/^\/advance-requests\/[^/]+\/revise$/.test(path)) return false
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
export const advanceDrafts = new AdvanceDrafts()
