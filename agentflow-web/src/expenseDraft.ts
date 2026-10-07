import type { ExpenseProjectOwners } from './expenseProjectApproval'
import type { PriorAssessment } from './expensePriorControl'
import type { Definition } from './api'
import type { InitiatorContext } from './initiatorContext'
import type { InvoiceItem } from './invoiceWallet'
export type { InvoiceItem } from './invoiceWallet'
import { amountMinor, type ExpenseContent, type ExpenseDetail, type ExpenseLine, type ExpenseVersions, type FinancialRound, type Money } from './expenses.js'
import { extractionMatches, type ExtractionDetail } from './invoiceExtraction.js'

export interface FinanceCatalog {
  employeeId: string; sourceVersion: string; validUntil: string
  legalEntities: Array<{ id: string; name: string; baseCurrency: string; paperReceiptRequired: boolean; sourceVersion: string; timeZone: string }>
  categories: Array<{ code: string; name: string; units: ExpenseLine['unit'][] }>
  costCenters: Array<{ legalEntityId: string; code: string; name: string }>
  projects: Array<{ legalEntityId: string; code: string; name: string; ownerSubject?: string | null }>
  cities: Array<{ code: string; name: string }>
}
export interface ExpenseCreate { businessNo: string; processKey: string; definitionVersion: number; content: ExpenseContent }
export interface ExpenseRevise extends ExpenseVersions { content: ExpenseContent }
export interface PrecheckOptions extends ExpenseVersions { enabled: boolean; unavailableCode: string | null; destination: string | null; targetDigest: string | null; latestPrecheckId?: string | null }
export interface PrecheckInput extends ExpenseVersions { initiatorAppointmentId: string; accountingDate: string; targetDigest: string }
export interface PrecheckSummary extends ExpenseVersions { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'BLOCKED' | 'UNAVAILABLE'; attempt: number; createdAt: string; startedAt: string | null; completedAt: string | null }
export interface PrecheckView {
  job: PrecheckSummary; usable: boolean; unavailableCode: string | null; initiator: InitiatorContext; accountingDate: string
  rateDate: string | null; validUntil: string | null; preview: FinancialRound | null
  budgetExceptionPolicy?: { reference: string } | null
  priorControls?: PriorAssessment[] | null
  projectOwners?: ExpenseProjectOwners | null
  findings: Array<{ stage: string; lineNo: number | null; nature: 'REJECTED' | 'UNAVAILABLE'; code: string }>
}

export const expenseUnits: Record<ExpenseLine['unit'], string> = { ITEM: '项', DAY: '天', NIGHT: '晚', KILOMETER: '公里', PERSON: '人' }
export const precheckStatuses: Record<PrecheckSummary['status'], string> = { QUEUED: '等待检查', RUNNING: '正在检查', READY: '检查完成', BLOCKED: '费用检查未通过', UNAVAILABLE: '检查未完成' }
export const precheckStages: Record<string, string> = { INPUT: '填报内容', CATALOG: '财务主数据', ACCOUNT: '收款账户', INVOICE: '发票', RATE: '汇率', POLICY: '费用标准', RESOURCES: '额度与借款', BUDGET: '预算', CONTEXT: '单据与任职', SYSTEM: '预检服务' }
export const precheckIssues: Record<string, string> = {
  EXPENSE_PROJECT_OWNER_UNAVAILABLE: '项目缺少有效负责人，请联系主数据管理员补全后重新检查',
  EXPENSE_PROJECT_APPROVAL_REQUIRED: '当前流程缺少固定的项目负责人会签，请选择支持项目审批的发布版本',
  EXPENSE_PROJECT_PRECHECK_REQUIRED: '请重新预检，取得本次项目负责人依据后再提交',
  EXPENSE_PROJECT_SNAPSHOT_MISSING: '原轮次项目依据不完整，请联系管理员核对，不能跳过项目审批',
  PRIOR_REQUEST_CATEGORY_MISMATCH: '事前批准行与费用类别不一致，请重新选择',
  PRIOR_REQUEST_EXCEPTION_REASON_REQUIRED: '累计超过事前容差，请为每个相关费用行填写说明后重新检查',
  EXPENSE_BUDGET_APPROVAL_REQUIRED: '当前流程缺少安全的预算审批节点，请联系流程管理员选择支持预算例外的发布版本',
  EXPENSE_PRIOR_APPROVAL_REQUIRED: '当前流程缺少独立额度例外审批，请联系流程管理员使用支持该控制的版本',
  INSUFFICIENT_FINANCIAL_BALANCE: '累计使用超过事前硬上限或借款可用余额',
  APPLICATION_NOT_EDITABLE: '当前单据不能编辑或提交', EXPENSE_LINES_REQUIRED: '请先保存至少一行费用', FINANCE_GATEWAY_UNAVAILABLE: '财务服务尚未配置',
  PRECHECK_NOT_READY: '检查尚未通过', PRECHECK_SUPERSEDED: '已有更新的检查，请刷新结果', FACTS_EXPIRED: '财务事实已过期，请重新检查',
  POLICY_CONFIGURATION_CHANGED: '费用制度或类别版本已变化，请重新检查后提交',
  CONTEXT_CHANGED: '单据版本已变化，请重新打开并检查', INITIATOR_CHANGED: '任职已变化，请重新选择并检查', RESOURCES_CHANGED: '原件或资金占用已变化，请重新检查',
  TARGET_CHANGED: '财务服务配置已变化，请刷新后检查', NOT_CONFIGURED: '财务服务尚未配置', TIMEOUT: '服务响应超时，请明确重试检查',
  EXPENSE_LEGAL_ENTITY_MISMATCH: '所选任职与费用法人不一致', EXPENSE_PRECHECK_ACTIVE: '已有检查正在执行，请刷新当前结果',
  INVOICE_NOT_VERIFIED: '发票尚未通过查验', INVOICE_EXPIRED: '发票查验已过期', INVOICE_ORIGINAL_NOT_READY: '发票原件尚未上传完成',
  ALLOCATION_UNBALANCED: '成本分摊之和须等于含税金额', INSUFFICIENT_AVAILABLE_AMOUNT: '可用额度或借款余额不足',
  BUDGET_INSUFFICIENT: '预算余额不足', EXPENSE_POLICY_DENIED: '制度禁止报销此项费用，请核对并移除此行',
  EXPENSE_EXCEPTION_REASON_REQUIRED: '超标费用需要填写说明', EXPENSE_EXCEPTION_REQUIRED: '超标费用需要填写说明'
}

/** 完整费用字段契约与后端一致，选错通用流程时不创建不适用的草稿。 */
export function expenseDefinition(definition: Pick<Definition, 'formSchema'> | null): boolean {
  const fields = definition?.formSchema?.fields
  const types: Record<string, string> = { expenseDetails: 'TEXT', amount: 'NUMBER', currency: 'TEXT', overPolicy: 'BOOLEAN', priorRequestOverTolerance: 'BOOLEAN', hasProjectAllocation: 'BOOLEAN' }
  return !!fields && [4, 5, 6].includes(fields.length) && new Set(fields.map(field => field.key)).size === fields.length
    && ['expenseDetails', 'amount', 'currency', 'overPolicy'].every(key => fields.some(field => field.key === key))
    && fields.every(field => field.required && types[field.key] === field.type && (field.key !== 'expenseDetails' || field.sensitive === true))
}

export function emptyExpense(): ExpenseContent { return { legalEntityId: '', type: 'DAILY', title: '', lines: [], advanceOffsets: [] } }
/** 行号保持稳定；移除一行不改变其他行的发票和事前批准归属。 */
export function newExpenseLine(content: ExpenseContent, currency: string): ExpenseLine {
  const lineNo = Array.from({ length: 200 }, (_, index) => index + 1).find(number => !content.lines.some(line => line.lineNo === number))
  if (!lineNo) throw new Error('一份报销单最多填写 200 行费用。')
  return { lineNo, categoryCode: '', cityCode: '', incurredOn: '', endedOn: null, quantity: '1', unit: 'ITEM',
    claimedGross: { value: '', currency }, claimedTax: { value: '0.00', currency }, allocations: [{ costCenter: '', projectCode: null, amount: { value: '', currency } }],
    description: '', exceptionReason: null, invoiceIds: [], priorRequest: null }
}

/** 输入提示集中在保存边界；只发送申请人可编辑字段，金额始终保留十进制字符串。 */
export function expenseContent(input: ExpenseContent, catalog: FinanceCatalog, now = Date.now()): ExpenseContent {
  if (!(Date.parse(catalog.validUntil) > now)) throw new Error('财务目录已过期，请刷新目录后保存。')
  if (!catalog.legalEntities.some(entity => entity.id === input.legalEntityId)) throw new Error('请选择本人可用的费用法人。')
  if (!input.title.trim()) throw new Error('请填写报销标题。')
  const invoices = new Set<string>()
  const lines = input.lines.map(line => {
    const fail = (message: string): never => { throw new Error(`第 ${line.lineNo} 行：${message}`) }
    const category = catalog.categories.find(value => value.code === line.categoryCode)
    if (!category || !category.units.includes(line.unit)) fail('请选择可用的费用类别和计量单位。')
    if (!catalog.cities.some(value => value.code === line.cityCode)) fail('请选择费用发生城市。')
    if (!/^\d{4}-\d{2}-\d{2}$/.test(line.incurredOn) || line.endedOn && line.endedOn < line.incurredOn) fail('请核对起止日期。')
    if (!line.description.trim()) fail('请填写费用说明。')
    const quantity = String(line.quantity)
    if (!/^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,3})?$/.test(quantity) || Number(quantity) <= 0 || Number(quantity) > 1_000_000) fail('数量须大于零、不超过一百万，最多三位小数。')
    const currency = line.claimedGross.currency
    if (!/^[A-Z]{3}$/.test(currency)) fail('请填写三位大写币种代码。')
    const gross = amountMinor(line.claimedGross.value), tax = amountMinor(line.claimedTax.value)
    if (line.allowance && (Number(quantity) !== line.allowance.calculation.days || line.incurredOn !== line.allowance.calculation.startsOn
      || line.endedOn !== line.allowance.calculation.endsOn || line.unit !== 'DAY' || currency !== line.allowance.calculation.gross.currency
      || gross !== amountMinor(line.allowance.calculation.gross.value) || tax !== 0n || line.invoiceIds.length)) fail('请重新计算补贴，保留系统金额并移除补贴行的发票。')
    if (gross <= 0n || tax > gross) fail('含税金额须大于零，税额不能超过含税额。')
    const targets = new Set<string>()
    let allocated = 0n
    const allocations = line.allocations.map(allocation => {
      if (!catalog.costCenters.some(value => value.legalEntityId === input.legalEntityId && value.code === allocation.costCenter)) fail('请选择本法人可用的成本中心。')
      const projectCode = allocation.projectCode || null, target = JSON.stringify([allocation.costCenter, projectCode])
      if (projectCode && !catalog.projects.some(value => value.legalEntityId === input.legalEntityId && value.code === projectCode)) fail('请选择本法人可用的项目。')
      if (targets.has(target)) fail('同一成本中心和项目请合并为一条分摊。')
      targets.add(target)
      const amount = amountMinor(allocation.amount.value)
      if (amount <= 0n) fail('分摊金额须大于零。')
      allocated += amount
      return { costCenter: allocation.costCenter, projectCode, amount: { value: allocation.amount.value, currency } }
    })
    if (allocated !== gross) fail('成本分摊之和须等于含税金额。')
    for (const id of line.invoiceIds) { if (invoices.has(id)) fail('同一发票只能用于一行费用。'); invoices.add(id) }
    return { lineNo: line.lineNo, categoryCode: line.categoryCode, cityCode: line.cityCode, incurredOn: line.incurredOn, endedOn: line.endedOn || null,
      quantity: Number(quantity), unit: line.unit, claimedGross: { value: line.claimedGross.value, currency }, claimedTax: { value: line.claimedTax.value, currency },
      allocations, invoiceIds: [...line.invoiceIds], priorRequest: line.priorRequest ? { ...line.priorRequest } : null, description: line.description.trim(), exceptionReason: line.exceptionReason?.trim() || null }
  })
  const selected = new Set<string>()
  const advanceOffsets = input.advanceOffsets.map(offset => {
    if (!offset.advanceId || selected.has(offset.advanceId) || amountMinor(offset.amount.value) <= 0n) throw new Error('借款须逐笔选择且抵扣金额大于零。')
    selected.add(offset.advanceId)
    return { advanceId: offset.advanceId, amount: { ...offset.amount } }
  })
  return { legalEntityId: input.legalEntityId, title: input.title.trim(), type: input.type, lines, advanceOffsets }
}

/** 历史 READY 不能自动变为可提交；必须仍匹配当前保存版本、任职、会计日与有效期。 */
export function usablePrecheck(view: PrecheckView | null, detail: ExpenseDetail, appointmentId: string, accountingDate: string, now = Date.now()): boolean {
  return !!view && view.job.status === 'READY' && view.usable && !!view.preview && view.preview.roundNo === nextExpenseRound(detail)
    && view.job.applicationVersion === detail.applicationVersion && view.job.financialVersion === detail.financialVersion
    && view.initiator.appointmentId === appointmentId && view.initiator.legalEntityId === detail.content.legalEntityId
    && view.accountingDate === accountingDate && !!view.validUntil && Date.parse(view.validUntil) > now
}

/** 与申请聚合一致：草稿已预编号，退回或撤回后重提才增加轮次。 */
export function nextExpenseRound(detail: ExpenseDetail): number { return detail.applicationStatus === 'DRAFT' ? detail.roundNo : detail.roundNo + 1 }

export function invoiceSelectable(item: InvoiceItem, legalEntityId: string, reportId?: string, now = Date.now()): boolean {
  return item.original.status === 'READY' && item.verification === 'VERIFIED' && !!item.facts && item.facts.legalEntityId === legalEntityId
    && Date.parse(item.facts.validUntil) > now && (item.occupation === 'AVAILABLE' || item.occupation === 'OCCUPIED' && !!reportId && item.use?.reportId === reportId)
}

export type InvoiceFillField = 'GROSS_AMOUNT' | 'CURRENCY'
export interface InvoiceFillChoice { field: InvoiceFillField; label: string; current: string; value: string; unavailable: string | null }
const invoiceFillLabels: Record<InvoiceFillField, string> = { GROSS_AMOUNT: '本行含税金额', CURRENCY: '本行原币币种（含税额和分摊）' }

/** 只映射同义字段；开票日期和票面税额不能推导发生日期及可抵扣税额。 */
export function invoiceFillChoices(run: ExtractionDetail, line: ExpenseLine): InvoiceFillChoice[] {
  if (run.status !== 'CONFIRMED') return []
  return (Object.keys(invoiceFillLabels) as InvoiceFillField[]).flatMap(field => {
    const selected = run.review?.selected?.find(item => item.field === field)
    if (!selected) return []
    let unavailable = null
    if (field === 'GROSS_AMOUNT') {
      try { if (amountMinor(selected.value) <= 0n) unavailable = '红字或零金额不能作为正数报销金额带入。' }
      catch { unavailable = '金额超出费用填报范围，不能直接带入。' }
    }
    return [{ field, label: invoiceFillLabels[field], current: field === 'GROSS_AMOUNT' ? line.claimedGross.value : line.claimedGross.currency, value: selected.value, unavailable }]
  })
}

/** 没有已确认币种或需要改变整行币种时，必须由本人核对金额的单位。 */
export function invoiceFillCurrencyConfirmation(line: ExpenseLine, run: ExtractionDetail, selected: InvoiceFillField[]): boolean {
  const currency = run.review?.selected?.find(item => item.field === 'CURRENCY')?.value
  return selected.includes('GROSS_AMOUNT') && !currency || selected.includes('CURRENCY') && currency !== line.claimedGross.currency
}

/** 仅更新本地费用输入，既不引用发票，也不保存、查验或提交；分摊金额由本人核对。 */
export function fillExpenseLineFromInvoice(line: ExpenseLine, invoice: InvoiceItem, run: ExtractionDetail,
    selected: InvoiceFillField[], currencyConfirmed: boolean): ExpenseLine {
  if (line.allowance) throw new Error('补贴金额按行程自动计算，不能从票面带入金额或币种。')
  if (run.status !== 'CONFIRMED' || !extractionMatches(run.input, invoice)) throw new Error('本人确认记录或原件已变化，请重新选择来源。')
  const choices = invoiceFillChoices(run, line)
  if (!selected.length || new Set(selected).size !== selected.length || selected.some(field => !choices.some(choice => choice.field === field))) throw new Error('请逐项勾选已确认的金额或币种。')
  const unavailable = choices.find(choice => selected.includes(choice.field) && choice.unavailable)
  if (unavailable) throw new Error(unavailable.unavailable!)
  const sourceCurrency = run.review?.selected?.find(item => item.field === 'CURRENCY')?.value
  const currency = selected.includes('CURRENCY') ? sourceCurrency! : line.claimedGross.currency
  if (!/^[A-Z]{3}$/.test(currency)) throw new Error('请先核对本行三位大写币种代码。')
  if (selected.includes('GROSS_AMOUNT') && sourceCurrency && currency !== sourceCurrency) throw new Error('已确认票面币种与本行不同，请同时选择币种并核对整行金额；这里不会换算汇率。')
  if (invoiceFillCurrencyConfirmation(line, run, selected) && !currencyConfirmed) throw new Error('请明确核对本行币种及金额单位；这里不会换算汇率。')
  const result = copy(line)
  if (selected.includes('GROSS_AMOUNT')) result.claimedGross.value = choices.find(choice => choice.field === 'GROSS_AMOUNT')!.value
  if (selected.includes('CURRENCY')) {
    result.claimedGross.currency = currency; result.claimedTax.currency = currency
    result.allocations.forEach(allocation => { allocation.amount.currency = currency })
  }
  return result
}

export interface ExpenseDraftState {
  detail: ExpenseDetail | null; content: ExpenseContent; businessNo: string; definition: Definition | null; baseline: string
  pending: { path: string; body: string } | null; requiresRefresh: boolean
}
function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
/** 页面内的费用输入按身份和编辑入口隔离；仅原保存回执可以推进原版本。 */
export class ExpenseDrafts {
  private drafts = new Map<string, ExpenseDraftState>()
  private listeners = new Set<(scope: string, key: string) => void>()
  get(scope: string, key: string) { const value = this.drafts.get(JSON.stringify([scope, key])); return value ? copy(value) : null }
  put(scope: string, key: string, value: ExpenseDraftState) { if (scope) this.drafts.set(JSON.stringify([scope, key]), copy(value)) }
  clear(scope: string, key: string) { this.drafts.delete(JSON.stringify([scope, key])) }
  hasDrafts() { return [...this.drafts.values()].some(value => JSON.stringify(value.content) !== value.baseline || !value.detail && !!value.businessNo.trim() || !!value.pending) }
  subscribe(listener: (scope: string, key: string) => void) { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  acknowledge(scope: string, path: string, body: string, result: ExpenseDetail): boolean {
    if (path !== '/expense-reports' && !/^\/expense-reports\/[^/]+\/revise$/.test(path)) return false
    for (const [storedKey, state] of this.drafts) {
      const [owner, key] = JSON.parse(storedKey) as [string, string]
      if (owner !== scope || state.pending?.path !== path || state.pending.body !== body || !result.id || !result.applicationId
        || state.detail && (state.detail.id !== result.id || state.detail.applicationId !== result.applicationId)) continue
      const next = { ...state, detail: result, content: result.content, businessNo: result.businessNo, baseline: JSON.stringify(result.content), pending: null, requiresRefresh: false }
      this.put(scope, key, next)
      this.listeners.forEach(listener => listener(scope, key))
      return true
    }
    return false
  }
}
export const expenseDrafts = new ExpenseDrafts()
