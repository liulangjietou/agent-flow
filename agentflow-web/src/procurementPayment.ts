import type { Definition } from './api'
import type { FinanceCatalog } from './expenseDraft'
import type { InitiatorContext } from './initiatorContext'
import { amountMinor, type Money } from './expenses.js'

export interface ProcurementContent { legalEntityId: string; title: string; purpose: string; supplierReference: string; payableReference: string; amount: Money }
export interface ProcurementVersions { applicationVersion: number; requestVersion: number }
export interface ProcurementMatchedLine {
  lineNo: number; orderLineNo: number; acceptanceReference: string; invoice: { type: 'DIGITAL' | 'TRADITIONAL'; code: string | null; number: string }; invoiceLineNo: number; unit: string
  orderedQuantity: string; acceptedQuantity: string; invoicedQuantity: string
  orderedGross: Money; acceptedGross: Money; invoicedGross: Money; tax: Money
}
export interface ProcurementPayable {
  sourceVersion: string; observedAt: string; supplierName: string; maskedAccount: string
  contractReference: string; orderReference: string; matchingReference: string; accrualVoucherReference: string; budgetRecognitionReference: string
  dueOn: string; gross: Money; settled: Money; outstanding: Money; lines: ProcurementMatchedLine[]
}
export interface ProcurementRound {
  roundNo: number; submittedRequestVersion: number; submittedBy: string; submittedAt: string; content: ProcurementContent
  legalEntity: FinanceCatalog['legalEntities'][number]; catalogVersion: string; payable: ProcurementPayable
}
export interface ProcurementApproval { roundNo: number; applicationVersion: number; approvedBy: string; approvedAt: string }
export interface ProcurementDetail extends ProcurementVersions { id: string; applicationId: string; businessNo: string; status: string; roundNo: number; editable: boolean; content: ProcurementContent; financialRound?: ProcurementRound | null; approval?: ProcurementApproval | null }
export interface ProcurementReceipt extends ProcurementVersions { id: string; applicationId: string; roundNo: number; status: string }
export interface ProcurementPaymentItem extends ProcurementVersions { id: string; applicationId: string; businessNo: string; title: string; status: string; roundNo: number; createdAt: string }
export interface ProcurementCreate { businessNo: string; processKey: string; definitionVersion: number; content: ProcurementContent }
export interface ProcurementRevise extends ProcurementVersions { content: ProcurementContent }
export interface ProcurementCheckInput extends ProcurementVersions { initiatorAppointmentId: string; targetDigest: string }
export interface ProcurementCheckOptions extends ProcurementVersions { enabled: boolean; unavailableCode?: string | null; destination?: string | null; targetDigest?: string | null; latestPrecheckId?: string | null }
export interface ProcurementCheckView {
  job: ProcurementVersions & { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'BLOCKED' | 'UNAVAILABLE'; attempt: number; createdAt: string; startedAt?: string | null; completedAt?: string | null }
  usable: boolean; unavailableCode?: string | null; initiator: InitiatorContext; validUntil?: string | null; preview?: ProcurementRound | null; failureCode?: string | null
}

/** 采购付款明细使用独立敏感组，不能用报销或普通表单代替。 */
export function procurementDefinition(definition: Definition | null): boolean {
  const fields = definition?.formSchema?.fields
  const types: Record<string, string> = { procurementPaymentDetails: 'TEXT', amount: 'NUMBER', currency: 'TEXT' }
  return !!fields && fields.length === 3 && new Set(fields.map(field => field.key)).size === 3
    && fields.every(field => field.required && types[field.key] === field.type && (field.key !== 'procurementPaymentDetails' || field.sensitive === true))
}
export function emptyProcurement(): ProcurementContent { return { legalEntityId: '', title: '', purpose: '', supplierReference: '', payableReference: '', amount: { value: '', currency: '' } } }

/** 只提交允许编辑的约定，浏览器不能附带账户引用、摘要或批准结果。 */
export function procurementContent(input: ProcurementContent, catalog: FinanceCatalog, now = Date.now()): ProcurementContent {
  if (!(Date.parse(catalog.validUntil) > now)) throw new Error('财务目录已过期，请刷新后保存。')
  const entity = catalog.legalEntities.find(value => value.id === input.legalEntityId)
  if (!entity) throw new Error('请选择本人可用的采购付款法人。')
  if (!input.title.trim() || input.title.length > 256) throw new Error('请填写 256 字以内的采购付款标题。')
  if (!input.purpose.trim() || input.purpose.length > 2000) throw new Error('请填写 2000 字以内的采购付款用途。')
  if ([input.title, input.purpose].some(value => /[\u0000-\u001f\u007f-\u009f]/u.test(value))) throw new Error('标题和用途请填写单行文字，不包含控制字符。')
  if (![input.supplierReference, input.payableReference].every(value => value.trim() && value.trim().length <= 128 && !/[\u0000-\u001f\u007f-\u009f]/u.test(value))) throw new Error('请填写 128 字以内的供应商编号与原应付编号，不包含控制字符。')
  if (amountMinor(input.amount.value) <= 0n || input.amount.currency !== entity.baseCurrency) throw new Error('请按法人本位币填写大于零的采购付款金额。')
  return { legalEntityId: input.legalEntityId, title: input.title.trim(), purpose: input.purpose.trim(),
    supplierReference: input.supplierReference.trim(), payableReference: input.payableReference.trim(), amount: { value: input.amount.value, currency: entity.baseCurrency } }
}
export function nextProcurementRound(detail: ProcurementDetail): number { return detail.status === 'DRAFT' ? detail.roundNo : detail.roundNo + 1 }
/** 点击确认时重验时效、双版本与任职，不能只沿用先前显示的 READY。 */
export function usableProcurementCheck(view: ProcurementCheckView | null, detail: ProcurementDetail, appointment: string, now = Date.now()): boolean {
  return !!view && view.usable && view.job.status === 'READY' && !!view.preview && view.preview.roundNo === nextProcurementRound(detail)
    && view.job.applicationVersion === detail.applicationVersion && view.job.requestVersion === detail.requestVersion
    && view.preview.submittedRequestVersion === detail.requestVersion && view.initiator.appointmentId === appointment
    && view.initiator.legalEntityId === detail.content.legalEntityId && !!view.validUntil && Date.parse(view.validUntil) > now
    && sameContent(view.preview.content, detail.content)
}
function sameContent(left: ProcurementContent, right: ProcurementContent): boolean {
  return left.legalEntityId === right.legalEntityId && left.title === right.title && left.purpose === right.purpose
    && left.supplierReference === right.supplierReference && left.payableReference === right.payableReference
    && left.amount.currency === right.amount.currency && left.amount.value === right.amount.value
}
export const procurementIssues: Record<string, string> = {
  PROCUREMENT_CHECK_ACTIVE: '已有采购付款预检正在执行，请刷新状态', PROCUREMENT_FORM_REQUIRED: '请选择支持采购付款的流程版本',
  PROCUREMENT_REVIEW_REQUIRED: '所选流程有绕过人工审核的路径，请联系流程管理员',
  PROCUREMENT_REVIEW_FIELDS_REQUIRED: '审批节点需要完整读取采购付款明细，请联系流程管理员',
  INVOICE_OCCUPIED: '发票已被报销占用或归属其他采购应付，请核对原业务后重新预检',
  PROCUREMENT_CATALOG_CHANGED: '本人财务目录已变化，请重新预检', PROCUREMENT_PAYABLE_OCCUPIED: '该原应付已有有效付款申请，请核对现有申请后处理',
  PROCUREMENT_BASE_CURRENCY_REQUIRED: '采购付款金额必须使用所选法人的本位币', PROCUREMENT_AMOUNT_EXCEEDS_PAYABLE: '本次付款额超过原应付未结余额，请补正后重新预检',
  PROCUREMENT_INITIATOR_MISMATCH: '所选任职须属于本人与本次采购付款法人', PROCUREMENT_PAYABLE_UNAVAILABLE: '原应付不可用，请核对法人、供应商与原应付编号',
  SUPPLIER_UNAVAILABLE: '供应商不可用，请在财务系统核对后重新预检', ACCOUNT_UNAVAILABLE: '供应商收款账户不可用，请在财务主数据系统维护后重新预检',
  PROCUREMENT_MATCH_REQUIRED: '订单、验收与发票匹配依据不完整，请在财务系统核对',
  PROCUREMENT_RESERVATION_CONTEXT_CHANGED: '原应付占用依据已变化，请刷新并联系财务核对', PROCUREMENT_ALREADY_APPROVED: '本轮采购付款已批准，请刷新查看结果'
}

export interface ProcurementDraftState {
  detail: ProcurementDetail | null; receipt: ProcurementReceipt | null; content: ProcurementContent; businessNo: string; definition: Definition | null
  baseline: string; pending: { path: string; body: string } | null; requiresRefresh: boolean
}
function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
/** 按身份隔离内存输入；最小回执只确认保存，必须重新查询后才能提交。 */
export class ProcurementDrafts {
  private drafts = new Map<string, ProcurementDraftState>()
  private listeners = new Set<(scope: string, key: string) => void>()
  get(scope: string, key: string) { const value = this.drafts.get(JSON.stringify([scope, key])); return value ? copy(value) : null }
  put(scope: string, key: string, value: ProcurementDraftState) { if (scope) this.drafts.set(JSON.stringify([scope, key]), copy(value)) }
  clear(scope: string, key: string) { this.drafts.delete(JSON.stringify([scope, key])) }
  hasDrafts() { return [...this.drafts.values()].some(value => JSON.stringify(value.content) !== value.baseline || !value.detail && !!value.businessNo.trim() || !!value.pending) }
  subscribe(listener: (scope: string, key: string) => void) { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  acknowledge(scope: string, path: string, body: string, result: ProcurementReceipt): boolean {
    if (path !== '/procurement-payments' && !/^\/procurement-payments\/[^/]+\/revise$/.test(path)) return false
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
export const procurementDrafts = new ProcurementDrafts()
