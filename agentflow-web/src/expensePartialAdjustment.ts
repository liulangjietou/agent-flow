import { amountMinor as expenseAmountMinor, expenseError, type Money } from './expenses.js'
import { adjustmentPreparationLabels, type AdjustmentPreparation } from './expenseResourceAdjustment.js'

/** 页面只使用已展示版本、整笔回款和持久候选；财务与资源事实各自保留。@author owlzhangfq@gmail.com */
export interface PartialBinding { reportId: string; applicationId: string; roundNo: number; applicationVersion: number; businessVersion: number }
export type PartialSide = 'BUDGET' | 'ACCRUAL'
export const partialSides: PartialSide[] = ['BUDGET', 'ACCRUAL']
export const partialSideLabels: Record<PartialSide, string> = { BUDGET: '预算调减', ACCRUAL: 'ERP 挂账调整' }
export const partialLabels = { WAITING_FINANCE: '等待两侧财务处理', READY: '财务已确认，等待本地完成', APPLIED: '本次调整已完成', REVIEW_REQUIRED: '当前依据需复核', RETIRED: '本次调整已安全结束' }
export const partialOperationLabels = { QUEUED: '已授权，等待发送', EXECUTING: '正在发送预算命令', POSTING: '正在发送 ERP 命令', QUERYING: '正在查询原命令', UNKNOWN: '结果尚未确认', APPLIED: '预算调减已确认', POSTED: 'ERP 调整已过账', REJECTED: '预算已明确拒绝', FAILED: 'ERP 已明确失败', NOT_FOUND: '原命令查无', EXPIRED: '发送授权已过期', VOIDED: '原命令已停止', RECONCILING: '回执存在争议' }
export const partialIntentLabels = { CREATE: '登记本次剩余额', ORIGINAL_QUERY: '查询原报销财务', PREPARE: '准备本侧原件与期间', AUTHORIZE: '授权本侧调整', QUERY_BUDGET: '查询原预算结果', QUERY_ACCRUAL: '查询原 ERP 结果', RESEND_BUDGET: '明确重发原预算命令', RESEND_ACCRUAL: '明确重发原 ERP 命令', SOURCE_QUERY: '重查原报销财务', CONFIRM_CURRENT: '确认当前来源', RETIRE: '安全结束本次调整', DISPUTE: '明确裁决本侧争议' }
export type PartialIntent = keyof typeof partialIntentLabels
export type PartialAction = 'QUERY_BUDGET' | 'QUERY_ACCRUAL' | 'RESEND_BUDGET' | 'RESEND_ACCRUAL' | 'CONFIRM_CURRENT'
export interface PartialAmounts { gross: Money; tax: Money; offsets: Money; payable: Money; lines: { lineNo: number; gross: Money; tax: Money }[] }
export interface PartialSource { id: string; version: number; status: string; updatedAt: string; issue: string | null }
export interface PartialBudgetFact { status: 'APPLIED' | 'REJECTED' | 'PENDING' | 'NOT_FOUND'; observedAt: string; rejection: string | null; reference: string | null; reducedAmount: Money | null; appliedAt: string | null }
export interface PartialAccrualFact { status: 'POSTED' | 'FAILED' | 'PENDING' | 'NOT_FOUND'; revision: number; observedAt: string; rejection: string | null; acceptanceReference: string | null; postingReference: string | null; voucherReference: string | null; postedAt: string | null }
export interface PartialResolution { id: string; operationId: string; beforeVersion: number; afterVersion: number; outcome: 'APPLIED' | 'REJECTED' | 'POSTED' | 'FAILED'; observedAt: string; resolvedBy: string; resolvedAt: string; evidenceReference: string; reason: string }
export interface PartialOperation<F = PartialBudgetFact | PartialAccrualFact> { id: string; version: number; status: keyof typeof partialOperationLabels; attempts: number; authorizedBy: string; authorizedAt: string; accountingDate: string; expiresAt: string; updatedAt: string; issue: string | null; accepted: F | null; conflicting: F | null; canResolve: boolean; resolutionIssue: string | null; candidateValidUntil: string | null; latestResolution: PartialResolution | null }
export interface PartialEntry { id: string; version: number; status: keyof typeof partialLabels; issue: string | null; requestedBy: string; evidenceReference: string; reason: string; createdAt: string; updatedAt: string; before: PartialAmounts; after: PartialAmounts; returnIds: string[]; budget: PartialOperation<PartialBudgetFact> | null; accrual: PartialOperation<PartialAccrualFact> | null; completion: { budgetVersion: number; accrualVersion: number; budgetReference: string; postingReference: string; voucherReference: string; at: string } | null; retirement: { actor: string; evidenceReference: string; reason: string; at: string } | null; budgetPreparation: AdjustmentPreparation | null; accrualPreparation: AdjustmentPreparation | null; availableActions: PartialAction[]; canRetire: boolean; canQueryOriginals: boolean }
export interface PartialView extends PartialBinding { settlementVersion: number; settlementStatus: string; original: { amounts: PartialAmounts; budget: PartialSource; accrual: PartialSource; payment: PartialSource | null; paymentVoucher: PartialSource | null }; remaining: PartialAmounts; previousId: string | null; previousVersion: number; returnsVersion: number; returns: { registrationId: string; fundsIdentity: string; amount: Money; receivedAt: string; available: boolean }[]; finance: boolean; adjustments: PartialEntry[] }
interface Versions { roundNo: number; applicationVersion: number; businessVersion: number; settlementVersion: number }
interface Target extends Versions { adjustmentId: string; adjustmentVersion: number }
export interface PartialLineInput { lineNo: number; remainingGross: string; remainingTax: string }
export interface PartialCreateInput extends Versions { previousId: string | null; previousVersion: number; returnsVersion: number; lines: PartialLineInput[]; returnIds: string[]; evidenceReference: string; reason: string }
export interface PartialOriginalInput extends Versions { accrualVersion: number; paymentVersion: number; paymentVoucherVersion: number; comment: string }
export interface PartialSourceInput extends PartialOriginalInput, Target {}
export interface PartialPrepareInput extends Target { side: PartialSide; accountingDate: string; evidenceReference: string; reason: string }
export interface PartialAuthorizeInput extends Target { preparationId: string; preparationVersion: number; comment: string }
export interface PartialActionInput extends Target { action: PartialAction; comment: string }
export interface PartialRetireInput extends Target { evidenceReference: string; reason: string }
export interface PartialDisputeInput extends Target { side: PartialSide; outcome: PartialResolution['outcome']; evidenceReference: string; comment: string }
export type PartialInput = PartialCreateInput | PartialOriginalInput | PartialSourceInput | PartialPrepareInput | PartialAuthorizeInput | PartialActionInput | PartialRetireInput | PartialDisputeInput
export interface PartialReceipt { reportId: string; roundNo: number; adjustmentId: string | null; adjustmentVersion: number | null; preparationId: string | null; preparationVersion: number | null; auditEventId: string }
/** 只展示页面自产的校验说明，远端错误正文继续使用稳定分类。@author owlzhangfq@gmail.com */
class PartialValidationError extends Error {}
function amountMinor(value: string) { try { return expenseAmountMinor(value) } catch { throw new PartialValidationError('金额必须为非负数，最多两位小数，请按原本币填写。') } }
const actions: PartialAction[] = ['QUERY_BUDGET', 'QUERY_ACCRUAL', 'RESEND_BUDGET', 'RESEND_ACCRUAL', 'CONFIRM_CURRENT']
function ensure(value: unknown): asserts value { if (!value) throw new PartialValidationError('部分报销调整数据或操作条件不一致，请刷新核对。') }
function text(value: unknown, max = 128): asserts value is string { ensure(typeof value === 'string' && value.length > 0 && value.length <= max && value === value.trim() && !/[\u0000-\u001f\u007f-\u009f]/.test(value)) }
function uuid(value: unknown) { text(value); ensure(/^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value)) }
function version(value: unknown, min = 1): asserts value is number { ensure(Number.isSafeInteger(value) && (value as number) >= min) }
function instant(value: unknown) { ensure(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function date(value: string) { ensure(/^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value) }
function code(value: unknown) { ensure(value === null || typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value)) }
function known(values: object, value: unknown) { return typeof value === 'string' && Object.prototype.hasOwnProperty.call(values, value) }
function note(value: string, max = 2000) { const result = value.trim(); text(result, max); return result }
function money(value: Money, currency: string) { ensure(value && value.currency === currency); return amountMinor(value.value) }
function moneyFrom(minor: bigint, currency: string): Money { return { currency, value: `${minor / 100n}.${String(minor % 100n).padStart(2, '0')}` } }
function amounts(value: PartialAmounts, currency: string) {
  ensure(value && Array.isArray(value.lines) && value.lines.length > 0 && value.lines.length <= 200)
  const gross = money(value.gross, currency), tax = money(value.tax, currency)
  ensure(gross === money(value.offsets, currency) + money(value.payable, currency) && tax <= gross)
  let total = 0n, taxes = 0n; const seen = new Set<number>()
  for (const line of value.lines) { ensure(line); version(line.lineNo); ensure(line.lineNo <= 200 && !seen.has(line.lineNo)); seen.add(line.lineNo); const g = money(line.gross, currency), t = money(line.tax, currency); ensure(t <= g); total += g; taxes += t }
  ensure(total === gross && taxes === tax)
}
function decreased(before: PartialAmounts, after: PartialAmounts, strict = false) {
  const gross = amountMinor(after.gross.value), original = amountMinor(before.gross.value)
  ensure(strict ? gross < original : gross <= original)
  const offsets = amountMinor(before.offsets.value)
  ensure(amountMinor(after.offsets.value) === (offsets < gross ? offsets : gross) && before.lines.length === after.lines.length)
  before.lines.forEach((line, index) => { const next = after.lines[index]; ensure(next && line.lineNo === next.lineNo); const dg = amountMinor(line.gross.value) - amountMinor(next.gross.value), dt = amountMinor(line.tax.value) - amountMinor(next.tax.value); ensure(dg >= 0n && dt >= 0n && dt <= dg) })
}
function equalAmounts(before: PartialAmounts, after: PartialAmounts) { decreased(before, after); decreased(after, before) }
function source(value: PartialSource) { ensure(value); uuid(value.id); version(value.version); instant(value.updatedAt); code(value.issue); ensure(typeof value.status === 'string' && /^[A-Z_]{1,32}$/.test(value.status)) }
function preparation(value: AdjustmentPreparation | null, finance: boolean) {
  if (value === null) return
  ensure(finance && value && known(adjustmentPreparationLabels, value.status)); uuid(value.id); version(value.version); date(value.accountingDate); text(value.evidenceReference); text(value.reason, 2000)
  ensure(instant(value.updatedAt) >= instant(value.requestedAt)); code(value.issue); code(value.authorizationIssue); ensure(typeof value.canAuthorize === 'boolean')
  const ready = ['READY', 'AUTHORIZED'].includes(value.status)
  ensure(ready ? value.expiresAt !== null && value.periodReference !== null : value.expiresAt === null && value.periodReference === null)
  if (ready) { text(value.periodReference); ensure(instant(value.expiresAt) > instant(value.updatedAt)) }
  ensure(!value.canAuthorize || value.status === 'READY' && value.authorizationIssue === null)
}
function fact(value: PartialBudgetFact | PartialAccrualFact | null, side: PartialSide, delta: Money) {
  if (value === null) return
  ensure(value); instant(value.observedAt); code(value.rejection)
  if (side === 'BUDGET') {
    const item = value as PartialBudgetFact; ensure(['APPLIED', 'REJECTED', 'PENDING', 'NOT_FOUND'].includes(item.status)); ensure((item.status === 'REJECTED') === (item.rejection !== null))
    if (item.status === 'APPLIED') { text(item.reference); ensure(money(item.reducedAmount!, delta.currency) === amountMinor(delta.value)); ensure(instant(item.appliedAt) <= instant(item.observedAt)) }
    else ensure(item.reference === null && item.reducedAmount === null && item.appliedAt === null)
  } else {
    const item = value as PartialAccrualFact; ensure(['POSTED', 'FAILED', 'PENDING', 'NOT_FOUND'].includes(item.status)); version(item.revision, item.status === 'NOT_FOUND' ? 0 : 1); ensure((item.status === 'FAILED') === (item.rejection !== null))
    if (item.acceptanceReference !== null) text(item.acceptanceReference)
    if (item.status === 'POSTED') { text(item.acceptanceReference); text(item.postingReference); text(item.voucherReference); ensure(instant(item.postedAt) <= instant(item.observedAt)) }
    else ensure(item.postingReference === null && item.voucherReference === null && item.postedAt === null)
  }
}
function operation(value: PartialOperation | null, side: PartialSide, entry: PartialEntry, finance: boolean) {
  if (value === null) return
  ensure(value && known(partialOperationLabels, value.status)); ensure(!(side === 'BUDGET' ? ['POSTING', 'POSTED', 'FAILED'] : ['EXECUTING', 'APPLIED', 'REJECTED']).includes(value.status))
  uuid(value.id); version(value.version); version(value.attempts, 0); text(value.authorizedBy); date(value.accountingDate); code(value.issue); code(value.resolutionIssue)
  const start = instant(value.authorizedAt), end = instant(value.expiresAt)
  ensure(start >= instant(entry.createdAt) && instant(value.updatedAt) >= start && instant(value.updatedAt) <= instant(entry.updatedAt) && end > start && end - start <= 300000)
  const delta = moneyFrom(amountMinor(entry.before.gross.value) - amountMinor(entry.after.gross.value), entry.before.gross.currency)
  fact(value.accepted, side, delta); fact(value.conflicting, side, delta)
  if (['APPLIED', 'POSTED', 'REJECTED', 'FAILED', 'NOT_FOUND'].includes(value.status)) ensure(value.accepted?.status === value.status)
  ensure(typeof value.canResolve === 'boolean' && (value.candidateValidUntil === null) === (value.conflicting === null))
  if (value.conflicting) ensure(instant(value.candidateValidUntil) === instant(value.conflicting.observedAt) + 300000)
  ensure(!value.canResolve || finance && !entry.retirement && value.status === 'RECONCILING' && value.resolutionIssue === null && value.conflicting && (side === 'BUDGET' ? ['APPLIED', 'REJECTED'] : ['POSTED', 'FAILED']).includes(value.conflicting.status))
  const decision = value.latestResolution
  if (decision !== null) {
    ensure(decision); uuid(decision.id); uuid(decision.operationId); version(decision.beforeVersion); ensure(decision.afterVersion === decision.beforeVersion + 1 && decision.afterVersion <= entry.version)
    ensure((side === 'BUDGET' ? ['APPLIED', 'REJECTED'] : ['POSTED', 'FAILED']).includes(decision.outcome)); text(decision.resolvedBy); text(decision.evidenceReference); text(decision.reason, 2000)
    ensure(instant(decision.resolvedAt) >= instant(decision.observedAt) && instant(decision.resolvedAt) < instant(decision.observedAt) + 300000 && instant(decision.resolvedAt) <= instant(entry.updatedAt))
  }
}
/** 响应校验只认可真实完成投影；排队意图、争议候选及旧裁决均不能改写剩余额。 */
export function validatePartial(value: PartialView, binding: PartialBinding): PartialView {
  ensure(value && Object.entries(binding).every(([key, expected]) => value[key as keyof PartialBinding] === expected))
  uuid(value.reportId); uuid(value.applicationId); version(value.roundNo); version(value.applicationVersion); version(value.businessVersion); version(value.settlementVersion); version(value.returnsVersion, 0); version(value.previousVersion, 0)
  ensure(typeof value.finance === 'boolean' && value.original && Array.isArray(value.returns) && value.returns.length <= 100 && Array.isArray(value.adjustments))
  ensure(['BUDGET_PENDING', 'BUDGET_REJECTED', 'SETTLED', 'REVIEW_REQUIRED'].includes(value.settlementStatus))
  const currency = value.original.amounts?.gross?.currency; ensure(typeof currency === 'string' && /^[A-Z]{3}$/.test(currency))
  amounts(value.original.amounts, currency); amounts(value.remaining, currency); decreased(value.original.amounts, value.remaining); ensure(amountMinor(value.original.amounts.gross.value) > 0n)
  source(value.original.budget); source(value.original.accrual)
  if (value.original.payment !== null) source(value.original.payment)
  if (value.original.paymentVoucher !== null) { ensure(value.original.payment); source(value.original.paymentVoucher) }
  ensure((amountMinor(value.original.amounts.payable.value) === 0n) === (value.original.payment === null))
  const returned = new Set<string>()
  for (const entry of value.returns) { ensure(entry); uuid(entry.registrationId); text(entry.fundsIdentity); ensure(!returned.has(entry.fundsIdentity) && money(entry.amount, currency) > 0n && typeof entry.available === 'boolean'); returned.add(entry.fundsIdentity); instant(entry.receivedAt) }
  ensure(value.original.payment !== null || value.returns.length === 0 && value.returnsVersion === 0)
  const seen = new Set<string>(), claimed = new Set<string>(); let unfinished = 0
  for (const entry of value.adjustments) {
    ensure(entry && known(partialLabels, entry.status)); uuid(entry.id); version(entry.version); ensure(!seen.has(entry.id)); seen.add(entry.id)
    text(entry.requestedBy); text(entry.evidenceReference); text(entry.reason, 2000); code(entry.issue); ensure(instant(entry.updatedAt) >= instant(entry.createdAt))
    amounts(entry.before, currency); amounts(entry.after, currency); decreased(value.original.amounts, entry.before); decreased(entry.before, entry.after, true)
    ensure(Array.isArray(entry.returnIds) && new Set(entry.returnIds).size === entry.returnIds.length)
    let funding = 0n
    for (const id of entry.returnIds) { const found = value.returns.find(item => item.fundsIdentity === id); ensure(found); funding += amountMinor(found.amount.value); if (!entry.retirement) { ensure(!claimed.has(id) && !found.available); claimed.add(id) } }
    ensure(funding === amountMinor(entry.before.payable.value) - amountMinor(entry.after.payable.value))
    operation(entry.budget, 'BUDGET', entry, value.finance); operation(entry.accrual, 'ACCRUAL', entry, value.finance)
    preparation(entry.budgetPreparation, value.finance); preparation(entry.accrualPreparation, value.finance)
    for (const side of partialSides) if (partialPreparation(entry, side)?.canAuthorize) ensure(partialCanPrepare(value, entry, side))
    ensure(Array.isArray(entry.availableActions) && new Set(entry.availableActions).size === entry.availableActions.length && typeof entry.canRetire === 'boolean' && typeof entry.canQueryOriginals === 'boolean')
    for (const action of entry.availableActions) { ensure(value.finance && !entry.retirement && actions.includes(action)); if (action !== 'CONFIRM_CURRENT') { const item = partialOperation(entry, action.endsWith('BUDGET') ? 'BUDGET' : 'ACCRUAL'); ensure(item && item.attempts > 0); ensure(action.startsWith('RESEND') ? item.status === 'NOT_FOUND' && item.conflicting === null : !['QUEUED', 'EXECUTING', 'POSTING', 'QUERYING'].includes(item.status)) } else ensure(entry.issue !== null && entry.budget?.status === 'APPLIED' && entry.accrual?.status === 'POSTED') }
    ensure(!entry.canQueryOriginals || value.finance && !entry.retirement)
    ensure(!entry.canRetire || value.finance && !entry.retirement && !entry.completion && partialSafe(entry.budget, 'BUDGET') && partialSafe(entry.accrual, 'ACCRUAL'))
    if (entry.completion !== null) {
      const completed = entry.completion; ensure(completed && entry.budget && entry.accrual && !entry.retirement && ['APPLIED', 'REVIEW_REQUIRED'].includes(entry.status)); version(completed.budgetVersion); version(completed.accrualVersion)
      ensure(completed.budgetVersion <= entry.budget.version && completed.accrualVersion <= entry.accrual.version); text(completed.budgetReference); text(completed.postingReference); text(completed.voucherReference); ensure(instant(completed.at) >= instant(entry.createdAt) && instant(completed.at) <= instant(entry.updatedAt))
    }
    if (entry.retirement !== null) { const retired = entry.retirement; ensure(retired && entry.status === 'RETIRED' && entry.issue === null && !entry.canRetire && !entry.canQueryOriginals && entry.availableActions.length === 0); text(retired.actor); text(retired.evidenceReference); text(retired.reason, 2000); ensure(instant(retired.at) === instant(entry.updatedAt)) }
    else { ensure(entry.status !== 'RETIRED'); if (!entry.completion) unfinished++ }
    if (entry.status === 'APPLIED' || entry.status === 'READY') ensure(entry.budget?.status === 'APPLIED' && entry.accrual?.status === 'POSTED' && entry.issue === null && (entry.status === 'APPLIED') === (entry.completion !== null))
    if (entry.status === 'APPLIED') ensure(entry.completion!.budgetReference === entry.budget!.accepted!.reference && entry.completion!.postingReference === entry.accrual!.accepted!.postingReference && entry.completion!.voucherReference === entry.accrual!.accepted!.voucherReference)
    ensure(entry.issue === null || entry.status === 'REVIEW_REQUIRED')
  }
  ensure(unfinished <= 1)
  if (value.previousId === null) { ensure(value.previousVersion === 0 && !value.adjustments.some(entry => entry.completion)); equalAmounts(value.original.amounts, value.remaining) }
  else { uuid(value.previousId); const previous = value.adjustments.find(entry => entry.id === value.previousId); ensure(previous?.completion && previous.version === value.previousVersion); equalAmounts(previous.after, value.remaining) }
  return value
}
export function partialOperation(entry: PartialEntry, side: PartialSide): PartialOperation | null { return side === 'BUDGET' ? entry.budget : entry.accrual }
export function partialPreparation(entry: PartialEntry, side: PartialSide) { return side === 'BUDGET' ? entry.budgetPreparation : entry.accrualPreparation }
function partialSafe(value: PartialOperation | null, side: PartialSide) {
  if (!value) return true
  if (value.conflicting) return false
  if (value.attempts === 0 && value.accepted === null && ['QUEUED', 'EXPIRED', 'VOIDED'].includes(value.status)) return true
  return side === 'BUDGET' ? value.status === 'REJECTED' : value.status === 'FAILED' && ['ACCOUNTING_PERIOD_CLOSED', 'LEGAL_ENTITY_UNAVAILABLE', 'ACCOUNT_UNAVAILABLE', 'AUTHORIZATION_REJECTED'].includes(value.accepted?.rejection ?? '')
}
/** 无活动意图时允许登记新调整，原来源及跨路径互斥仍由服务端锁内核对。 */
export function partialCanCreate(value: PartialView) { return value.finance && amountMinor(value.remaining.gross.value) > 0n && value.adjustments.every(entry => ['APPLIED', 'RETIRED'].includes(entry.status)) }
export function partialCanPrepare(value: PartialView, entry: PartialEntry, side: PartialSide) { const operation = partialOperation(entry, side), preparation = partialPreparation(entry, side); return value.finance && !entry.completion && !entry.retirement && entry.issue === null && !['QUEUED', 'RUNNING'].includes(preparation?.status ?? '') && (!operation || operation.status !== 'QUEUED' && partialSafe(operation, side)) }
export function partialNeedsAcknowledgement(intent: PartialIntent) { return !['PREPARE', 'ORIGINAL_QUERY', 'SOURCE_QUERY', 'QUERY_BUDGET', 'QUERY_ACCRUAL'].includes(intent) }
function versions(value: PartialView): Versions { return { roundNo: value.roundNo, applicationVersion: value.applicationVersion, businessVersion: value.businessVersion, settlementVersion: value.settlementVersion } }
function target(value: PartialView, id: string): Target { const entry = value.adjustments.find(item => item.id === id); ensure(value.finance && entry && !entry.retirement); return { ...versions(value), adjustmentId: entry.id, adjustmentVersion: entry.version } }
/** 整分核对逐行税额与不含税额同时减少，借款只在剩余额低于原抵扣时恢复。 */
export function partialPreview(value: PartialView, lines: PartialLineInput[], returnIds: string[]) {
  ensure(Array.isArray(lines) && lines.length > 0 && lines.length <= 200 && new Set(lines.map(line => line.lineNo)).size === lines.length)
  const requested = new Map(lines.map(line => [line.lineNo, line])), changed: PartialLineInput[] = []
  let gross = 0n, tax = 0n
  for (const line of value.remaining.lines) {
    const input = requested.get(line.lineNo); requested.delete(line.lineNo)
    const g = input ? amountMinor(input.remainingGross) : amountMinor(line.gross.value), t = input ? amountMinor(input.remainingTax) : amountMinor(line.tax.value)
    const dg = amountMinor(line.gross.value) - g, dt = amountMinor(line.tax.value) - t
    if (t > g || dg < 0n || dt < 0n || dt > dg) throw new PartialValidationError(`第 ${line.lineNo} 行的含税额、税额和不含税额只能减少。`)
    if (dg > 0n) changed.push({ lineNo: line.lineNo, remainingGross: moneyFrom(g, line.gross.currency).value, remainingTax: moneyFrom(t, line.tax.currency).value })
    gross += g; tax += t
  }
  if (requested.size || !changed.length) throw new PartialValidationError('请至少减少一行报销金额，保留原行号。')
  ensure(Array.isArray(returnIds) && returnIds.length <= 100 && new Set(returnIds).size === returnIds.length)
  let returned = 0n
  const selected = value.returns.filter(item => returnIds.includes(item.fundsIdentity)); ensure(selected.length === returnIds.length)
  for (const entry of selected) { ensure(entry.available); returned += amountMinor(entry.amount.value) }
  const offsets = amountMinor(value.remaining.offsets.value) < gross ? amountMinor(value.remaining.offsets.value) : gross, payable = gross - offsets
  const bank = amountMinor(value.remaining.payable.value) - payable, currency = value.remaining.gross.currency
  return { lines: changed, returnIds: selected.map(entry => entry.fundsIdentity), remainingGross: moneyFrom(gross, currency), remainingTax: moneyFrom(tax, currency), reduction: moneyFrom(amountMinor(value.remaining.gross.value) - gross, currency), bankReturn: moneyFrom(bank, currency), advanceRestored: moneyFrom(amountMinor(value.remaining.offsets.value) - offsets, currency), selectedReturn: moneyFrom(returned, currency), matched: bank === returned }
}
export function partialCreateInput(value: PartialView, lines: PartialLineInput[], returnIds: string[], reference: string, reason: string): PartialCreateInput { ensure(partialCanCreate(value)); const preview = partialPreview(value, lines, returnIds); if (!preview.matched) throw new PartialValidationError('请选择完整且未占用的回款，合计须等于本次所需银行退回金额。'); return { ...versions(value), previousId: value.previousId, previousVersion: value.previousVersion, returnsVersion: value.returnsVersion, lines: preview.lines, returnIds: preview.returnIds, evidenceReference: note(reference, 128), reason: note(reason) } }
export function partialOriginalInput(value: PartialView, comment: string): PartialOriginalInput { ensure(partialCanCreate(value)); return { ...versions(value), ...sourceVersions(value), comment: note(comment) } }
function sourceVersions(value: PartialView) { return { accrualVersion: value.original.accrual.version, paymentVersion: value.original.payment?.version ?? 0, paymentVoucherVersion: value.original.paymentVoucher?.version ?? 0 } }
export function partialSourceInput(value: PartialView, id: string, comment: string): PartialSourceInput { ensure(value.adjustments.find(item => item.id === id)?.canQueryOriginals); return { ...target(value, id), ...sourceVersions(value), comment: note(comment) } }
export function partialPrepareInput(value: PartialView, id: string, side: PartialSide, accountingDate: string, reference: string, reason: string): PartialPrepareInput { const entry = value.adjustments.find(item => item.id === id); ensure(entry && partialSides.includes(side) && partialCanPrepare(value, entry, side)); date(accountingDate); return { ...target(value, id), side, accountingDate, evidenceReference: note(reference, 128), reason: note(reason) } }
export function partialAuthorizeInput(value: PartialView, id: string, side: PartialSide, comment: string, now = Date.now()): PartialAuthorizeInput { const entry = value.adjustments.find(item => item.id === id); ensure(entry && partialSides.includes(side)); const prepared = partialPreparation(entry, side); ensure(prepared?.canAuthorize && !entry.completion && entry.issue === null); if (now < instant(prepared.updatedAt) || now >= instant(prepared.expiresAt)) throw new PartialValidationError('本侧准备依据已过期，请重新准备后授权。'); return { ...target(value, id), preparationId: prepared.id, preparationVersion: prepared.version, comment: note(comment) } }
export function partialActionInput(value: PartialView, id: string, action: PartialAction, comment: string, now = Date.now()): PartialActionInput { const entry = value.adjustments.find(item => item.id === id); ensure(entry && entry.availableActions.includes(action)); if (action.startsWith('RESEND')) { const operation = partialOperation(entry, action.endsWith('BUDGET') ? 'BUDGET' : 'ACCRUAL'); ensure(operation); if (now < instant(operation.authorizedAt) || now >= instant(operation.expiresAt)) throw new PartialValidationError('原发送授权已过期，请核对原命令结果。') } return { ...target(value, id), action, comment: note(comment) } }
export function partialRetireInput(value: PartialView, id: string, reference: string, reason: string): PartialRetireInput { ensure(value.adjustments.find(item => item.id === id)?.canRetire); return { ...target(value, id), evidenceReference: note(reference, 128), reason: note(reason) } }
/** 采用已显示的实际候选状态，禁止客户端填写或替换外部凭证。 */
export function partialDisputeInput(value: PartialView, id: string, side: PartialSide, reference: string, comment: string, now = Date.now()): PartialDisputeInput { const entry = value.adjustments.find(item => item.id === id); ensure(entry && partialSides.includes(side)); const operation = partialOperation(entry, side); ensure(operation?.canResolve && operation.conflicting); if (now < instant(operation.conflicting.observedAt) || now >= instant(operation.candidateValidUntil)) throw new PartialValidationError('争议候选已过期，请重新查询原命令后核对。'); ensure((side === 'BUDGET' ? ['APPLIED', 'REJECTED'] : ['POSTED', 'FAILED']).includes(operation.conflicting.status)); return { ...target(value, id), side, outcome: operation.conflicting.status as PartialResolution['outcome'], evidenceReference: note(reference, 128), comment: note(comment) } }
/** 接受回执只证明登记成功，完成状态必须重新读取；准备和来源查询不推进根修订。 */
export function validatePartialReceipt(receipt: PartialReceipt, value: PartialView, intent: PartialIntent, input: PartialInput) {
  ensure(receipt && receipt.reportId === value.reportId && receipt.roundNo === input.roundNo); uuid(receipt.auditEventId)
  if (intent === 'ORIGINAL_QUERY') ensure(receipt.adjustmentId === null && receipt.adjustmentVersion === null)
  else if (intent === 'CREATE') { uuid(receipt.adjustmentId); ensure(receipt.adjustmentVersion === 1 && !value.adjustments.some(entry => entry.id === receipt.adjustmentId)) }
  else { ensure('adjustmentId' in input && receipt.adjustmentId === input.adjustmentId && receipt.adjustmentVersion === input.adjustmentVersion + (['PREPARE', 'SOURCE_QUERY'].includes(intent) ? 0 : 1)) }
  if (intent === 'PREPARE') { uuid(receipt.preparationId); ensure(receipt.preparationVersion === 1) }
  else if (intent === 'AUTHORIZE') ensure('preparationId' in input && receipt.preparationId === input.preparationId && receipt.preparationVersion === input.preparationVersion + 1)
  else ensure(receipt.preparationId === null && receipt.preparationVersion === null)
}
const issues: Record<string, string> = {
  NOT_DISPUTED: '当前没有待裁决的争议。', NON_TERMINAL: '候选尚未终结，请继续查询原命令。', EXPIRED_EVIDENCE: '候选依据已过期，请重新查询原命令。', HISTORY_CHANGED: '候选与已保存历史不符，需核对外部原账。', EFFECT_ALREADY_OBSERVED: '历史成功事实必须保留，不能采用失败候选撤销。', STALE_EVIDENCE: '候选比已知事实更旧，请重新查询。', DIFFERENT_ACCEPTANCE: '候选受理号与原记录不一致，请核对外部原账。', DIFFERENT_POSTING: '候选凭证与原过账事实不一致，请核对外部原账。',
  EXPENSE_ADJUSTMENT_PENDING: '原报销正在办理整笔取消，请先完成或安全结束该调整。', EXPENSE_PARTIAL_ADJUSTMENT_PENDING: '已有待办调整，请继续办理原记录。', EXPENSE_ADJUSTMENT_FUNDING_CHANGED: '原付款、整笔回款或付款凭证需重新核对。',
  PARTIAL_ADJUSTMENT_PREPARATION_PENDING: '本侧原件准备正在处理，请刷新查看。', PARTIAL_ADJUSTMENT_PREPARATION_CONFLICT: '本侧准备已失效或尚未就绪，请刷新后重新准备。', CONCURRENCY_CONFLICT: '当前版本已变化，请刷新核对后再办理。',
  SOURCE_CHANGED: '原财务依据已变化，请重查原件。', FINANCE_TARGET_CHANGED: '财务服务配置已变化，请联系管理员核对。', NOT_CONFIGURED: '财务服务尚未配置。', TIMEOUT: '本次读取超时，请刷新核对原状态。', BUDGET_RESULT_UNCONFIRMED: '原预算结果待确认，已完成资源保持。', ACCRUAL_RESULT_UNCONFIRMED: '原 ERP 结果待确认，已完成资源保持。',
  ACCOUNTING_PERIOD_CLOSED: '所选会计期间已关闭。', LEGAL_ENTITY_UNAVAILABLE: '原法人当前不可记账。', ACCOUNT_UNAVAILABLE: '原会计科目当前不可用。', AUTHORIZATION_REJECTED: '外部系统拒绝本次授权。', ORIGINAL_NOT_POSTED: '外部原挂账尚未确认过账。', ORIGINAL_CHANGED: '外部原凭证已发生变化。', ADJUSTMENT_VERSION_CONFLICT: '外部累计调整版本已变化。', BUDGET_POLICY_UNAVAILABLE: '原预算规则当前不可用。', COST_OBJECT_UNAVAILABLE: '原成本对象当前不可用。'
}
const factLabels: Record<string, string> = { APPLIED: '预算调减成功', REJECTED: '预算明确拒绝', POSTED: 'ERP 已过账', FAILED: 'ERP 明确失败', PENDING: '仍在处理', NOT_FOUND: '原号查无' }
export function partialFactLabel(status: string) { return factLabels[status] ?? '结果需核对' }
export function partialSourceLabel(status: string) { const labels: Record<string, string> = { ...partialOperationLabels, APPLIED: '预算消费已确认', POSTED: '原凭证已过账', SUCCEEDED: '原付款已确认', REVERSED: '原记录已反向处理', SENDING: '原付款正在发送', CHECKING: '原付款正在复核' }; return labels[status] ?? '原件需核对' }
export function partialIssue(code: string) { return issues[code] ?? '当前财务依据需核对，请刷新查看原件和处理结果。' }
export function partialError(cause: unknown) { if (cause instanceof PartialValidationError) return cause.message; const code = (cause as { code?: string })?.code; return code && issues[code] ? issues[code]! : expenseError(cause) }
