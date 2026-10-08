import type { PriorControlSource } from './expensePriorControl'
import type { ApprovalProxyOption, ApprovalProxyUse } from './api'
import type { TaskReturnDraft } from './taskActions'

/** 所有金额以十进制字符串传输；币种来自服务端财务事实。 */
export interface Money { value: string; currency: string }
export interface ExpensePolicySelection { policyId: string; policyVersion: number; categoryRevision: number; activeRevision: number; definitionDigest: string }
export interface ExpenseAllowanceRule { dailyRate: Money; dayCountBasis: 'CALENDAR_DAYS_INCLUSIVE' }
export interface ExpenseAllowanceBasis {
  policy: { selection: ExpensePolicySelection; ruleKey: string; factSourceReference: string }
  calculation: { startsOn: string; endsOn: string; days: number; rule: ExpenseAllowanceRule; gross: Money }
}
export type ExpensePolicyException = 'AMOUNT' | 'SERVICE_LEVEL' | 'INVOICE_AGE'
export interface CostAllocation { costCenter: string; projectCode: string | null; amount: Money }
export interface AdvanceOffset { advanceId: string; amount: Money }
export interface ExpenseLine {
  lineNo: number; categoryCode: string; incurredOn: string; endedOn: string | null; cityCode: string
  quantity: string | number; unit: 'ITEM' | 'DAY' | 'NIGHT' | 'KILOMETER' | 'PERSON'
  claimedGross: Money; claimedTax: Money; invoiceIds: string[]
  priorRequest: { requestId: string; lineNo: number } | null; allocations: CostAllocation[]
  description: string; exceptionReason: string | null
  allowance?: ExpenseAllowanceBasis | null
}
export interface ExpenseContent { legalEntityId: string; type: 'TRAVEL' | 'DAILY' | 'ENTERTAINMENT' | 'TRAINING' | 'OTHER'; title: string; lines: ExpenseLine[]; advanceOffsets: AdvanceOffset[] }
export interface ApprovedLine { lineNo: number; gross: Money; tax: Money; allocations: CostAllocation[] }
export interface FrozenExpenseLine {
  original: ExpenseLine; claimedBase: Money; deductibleTaxBase: Money
  assessment: { exchangeRate: { fromCurrency: string; toCurrency: string; rate: string | number; source: string; rateDate: string }; policy: {
    policyId: string; version: number; assessedGross: Money; allowedGross: Money; decision: 'WITHIN_LIMIT' | 'REQUIRES_EXCEPTION' | 'DENIED'
    evidenceReference?: string; taxRuleReference?: string
    exceptionReasons?: ExpensePolicyException[]; managedPolicy?: { selection: ExpensePolicySelection; ruleKey: string; factSourceReference: string } | null
  } }
}
export type ReductionReason = 'INELIGIBLE_COST' | 'OVER_STANDARD_NOT_ACCEPTED' | 'INVALID_INVOICE' | 'TAX_CORRECTION' | 'OTHER'
export interface ExpenseAdjustment {
  id: string; adjustedBy: string; adjustedAt: string; reasonCode: ReductionReason; comment: string
  lineChanges: Array<{ lineNo: number; previousGross: Money; approvedGross: Money; previousTax: Money; approvedTax: Money; reasonCode?: ReductionReason }>
  offsetChanges: Array<{ advanceId: string; previousAmount: Money; amount: Money }>
}
export interface FinancialRound {
  roundNo: number; submittedFinancialVersion: number; submittedBy: string; submittedAt: string; baseCurrency: string; maskedAccount: string
  originalLines: FrozenExpenseLine[]; approvedLines: ApprovedLine[]; advanceOffsets: AdvanceOffset[]; adjustments: ExpenseAdjustment[]
  approvedGross: Money; approvedTax: Money; offsetTotal: Money; payable: Money
}
export interface ExpenseBudgetRetentionView {
  roundNo: number; stoppedStatus: 'RETURNED' | 'WITHDRAWN'; retainedAt: string; retentionDays: number; expiresAt: string
  status: 'RETAINED' | 'RECONCILING' | 'RELEASE_QUEUED' | 'RELEASED' | 'SUPERSEDED' | 'NO_FROZEN_BUDGET' | 'RELEASE_REJECTED'
  releaseOperationId: string | null; issue: string | null; updatedAt: string
}
export interface ExpenseDetail { id: string; applicationId: string; businessNo: string; applicationStatus: string; applicationVersion: number; financialVersion: number; roundNo: number; editable: boolean; content: ExpenseContent; financialRound: FinancialRound | null; budgetRetention?: ExpenseBudgetRetentionView | null }
export interface ExpenseVersions { applicationVersion: number; financialVersion: number }
/** 费用操作锁绑定当前待办身份，旧组件卸载不能解除另一个任务的锁。 */
export interface ExpenseTaskActivity { scopeKey: string; applicationId: string; taskId: string | undefined; applicationVersion: number | undefined; busy: boolean }
export interface ExpenseReturnRequest { applicationId: string; draft: TaskReturnDraft }
export interface ExpenseCommand extends ExpenseVersions { comment: string }
export interface ReductionLine { lineNo: number; approvedGross: string; approvedTax: string; reasonCode?: ReductionReason | '' }
/** 财务任务操作可明确选用直接代理，申请人撤回和作废不接受该依据。 */
export interface ExpenseTaskCommand extends ExpenseCommand { proxyId?: string }
export interface ExpenseReduction extends ExpenseTaskCommand { lines: ReductionLine[]; reasonCode: ReductionReason }
export interface ExpenseReceipt extends ExpenseVersions { reportId: string; applicationId: string }
export interface ExpenseRevocationReceipt extends ExpenseReceipt { status: 'REVOKED' }
export interface ExpenseWorkflow extends ExpenseReceipt {
  roundNo: number; canWithdraw: boolean; canCancel: boolean
  revocation?: { allowed: boolean; unavailable: string | null } | null
  paper: null | { roundNo: number; required: boolean; received: boolean; receivedBy: string | null; receivedAt: string | null; proxyUse?: ApprovalProxyUse | null }
  budget: { ledgerStatus: string | null; confirmedCurrent: boolean; operationId: string | null; operationStatus: string | null; issue: string | null }
  task: null | { taskId: string; stage: 'BUSINESS' | 'PROJECT_REVIEW' | 'PRIOR_REQUEST_REVIEW' | 'BUDGET_REVIEW' | 'RECEIPT' | 'FINANCE_REVIEW' | 'FINANCE_RECHECK'; canApprove?: boolean; approvalUnavailable?: string | null; canReceive: boolean; canReduce: boolean; reductionUnavailable: string | null; canActDirectly?: boolean; proxyOptions?: ApprovalProxyOption[] }
}
export interface ExpenseItem extends ExpenseVersions { id: string; applicationId: string; businessNo: string; title: string; status: string; roundNo: number; createdAt: string }
export interface ExpensePage<T> { items: T[]; nextBeforeId: string | null }
export interface ExpenseFilter { beforeId?: string; limit?: number; status?: string }
export interface PriorRequestItem { id: string; applicationId: string; legalEntityId: string; version: number; closed: boolean; lines: Array<{ lineNo: number; approved: Money; limit: Money; available: Money; reserved: Money; consumed: Money; control?: PriorControlSource; hardLimit?: boolean; exceeded?: Money }> }
export interface AdvanceItem { id: string; legalEntityId: string; version: number; status: string; paidOn: string; dueOn: string; paid: Money; available: Money; reserved: Money; settled: Money; repaid: Money; outstanding: Money; receivedRepayments: Money; returnedRepayments: Money; returnedDisbursements?: Money }

export const reductionReasons: Record<ReductionReason, string> = { INELIGIBLE_COST: '不符合报销范围', OVER_STANDARD_NOT_ACCEPTED: '超标部分不予报销', INVALID_INVOICE: '票据不符合要求', TAX_CORRECTION: '调整可抵扣税额', OTHER: '其他原因' }
export const expenseStatuses: Record<string, string> = { DRAFT: '草稿', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已驳回', APPROVED: '审批通过', CANCELLED: '已作废', REVOKED: '已撤销' }
export const expenseTypes: Record<ExpenseContent['type'], string> = { TRAVEL: '差旅', DAILY: '日常费用', ENTERTAINMENT: '业务招待', TRAINING: '培训', OTHER: '其他费用' }
export const policyExceptionLabels: Record<ExpensePolicyException, string> = { AMOUNT: '金额超出标准', SERVICE_LEVEL: '出行等级不符合标准', INVOICE_AGE: '票据超过报销时限' }
export const budgetIssues: Record<string, string> = {
  NOT_CONFIGURED: '尚未配置预算服务', TARGET_CHANGED: '预算服务配置已变化，需要核对原操作', TIMEOUT: '预算服务响应超时', CONNECTION: '暂时无法连接预算服务',
  AUTHENTICATION: '预算服务连接凭据不可用', REMOTE_FAILURE: '预算服务暂时不可用', INVALID_RESPONSE: '预算结果未通过校验', RESPONSE_TOO_LARGE: '预算结果未通过校验',
  BUDGET_EXCEPTION_REQUIRED: '原预算操作需要独立的预算负责人审批', BUDGET_INSUFFICIENT: '可用预算不足', BUDGET_POLICY_UNAVAILABLE: '预算制度不可用', ACCOUNTING_PERIOD_CLOSED: '会计期间已关闭', COST_OBJECT_UNAVAILABLE: '成本归属不可用',
  LEGAL_ENTITY_UNAVAILABLE: '费用法人不可用', EMPLOYEE_UNAVAILABLE: '员工主数据不可用', LEDGER_VERSION_CONFLICT: '预算占用已变化，需要核对', RESERVATION_FINALIZED: '预算占用已结算或释放'
}
const EXPENSE_QUERY_TIMEOUT_MS = 12_000
const EXPENSE_PAGE_LIMIT = 25

/** 输入的分以整数比较，不用浮点数判断核减或余额。 */
export function amountMinor(value: string): bigint {
  if (typeof value !== 'string' || !/^(?:0|[1-9][0-9]{0,14})(?:\.[0-9]{1,2})?$/.test(value)) throw new Error('金额请填写普通十进制，最多两位小数。')
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole!) * 100n + BigInt(fraction.padEnd(2, '0'))
}
export function moneyLabel(money: Money): string {
  const cents = amountMinor(money.value)
  return `${money.currency} ${(cents / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${(cents % 100n).toString().padStart(2, '0')}`
}
/** 只发送实际减少的已有行；保留输入字符串，不改变币种或其他财务对象。 */
export function changedReductions(before: ApprovedLine[], inputs: ReductionLine[]): ReductionLine[] {
  const result = reductionChanges(before, inputs)
  if (!result.length) throw new Error('请至少减少一行的含税额或可抵扣税额。')
  return result
}
function reductionChanges(before: ApprovedLine[], inputs: ReductionLine[]): ReductionLine[] {
  const seen = new Set<number>(), result: ReductionLine[] = []
  for (const input of inputs) {
    const original = before.find(line => line.lineNo === input.lineNo)
    if (!original || seen.has(input.lineNo)) throw new Error('费用行已变化，请刷新后重新核对。')
    seen.add(input.lineNo)
    let gross: bigint, tax: bigint
    try { gross = amountMinor(input.approvedGross); tax = amountMinor(input.approvedTax) }
    catch { throw new Error(`第 ${input.lineNo} 行金额请填写普通十进制，最多两位小数。`) }
    if (gross > amountMinor(original.gross.value) || tax > amountMinor(original.tax.value) || tax > gross) throw new Error(`第 ${input.lineNo} 行只能减少金额，税额不能超过含税额。`)
    if (gross !== amountMinor(original.gross.value) || tax !== amountMinor(original.tax.value)) result.push({ ...input })
  }
  return result
}
/** 仅预览未保存核减；原申报固定，冲销总额按核定额封顶，不产生预算或付款事实。 */
export function previewReduction(round: FinancialRound, inputs: ReductionLine[]) {
  const lines = reductionChanges(round.approvedLines, inputs)
  const changes = new Map(lines.map(line => [line.lineNo, line]))
  const gross = round.approvedLines.reduce((sum, line) => sum + amountMinor(changes.get(line.lineNo)?.approvedGross ?? line.gross.value), 0n)
  const tax = round.approvedLines.reduce((sum, line) => sum + amountMinor(changes.get(line.lineNo)?.approvedTax ?? line.tax.value), 0n)
  const original = round.originalLines.reduce((sum, line) => sum + amountMinor(line.claimedBase.value), 0n)
  const existingOffset = amountMinor(round.offsetTotal.value), offset = existingOffset < gross ? existingOffset : gross
  const money = (minor: bigint): Money => ({ currency: round.baseCurrency, value: `${minor / 100n}.${(minor % 100n).toString().padStart(2, '0')}` })
  return { lines, original: money(original), gross: money(gross), tax: money(tax), offset: money(offset), payable: money(gross - offset) }
}
export type ExpenseReductionPreview = ReturnType<typeof previewReduction>
export function expenseError(cause: unknown): string {
  const failure = cause as { status?: number; code?: string }
  const messages: Record<string, string> = {
    EXPENSE_REVOCATION_VOUCHER_STARTED: '凭证已开始外发，请先由财务核对原凭证并办理冲回，不能直接撤销。',
    EXPENSE_REVOCATION_SETTLEMENT_STARTED: '已登记付款或核销，请通过原付款及资源调整入口办理，不能直接撤销。',
    ALLOWANCE_RECALCULATION_REQUIRED: '补贴制度或行程已变化，请刷新费用标准、重新计算后保存。',
    ALLOWANCE_CALCULATION_MISMATCH: '补贴金额、天数和税额必须使用系统计算值，且不能关联发票。',
    ALLOWANCE_ITINERARY_REQUIRED: '补贴必须填写完整的行程起止日期。',
    ALLOWANCE_ITINERARY_OVERLAP: '同一补贴类别的行程日期不能重复，请合并或调整费用行。',
    ALLOWANCE_POLICY_PERIOD_MISMATCH: '行程跨越制度有效日期，请按制度有效期拆分行程。',
    CONCURRENCY_CONFLICT: '单据已更新，请刷新并核对最新金额后重新操作。', EXPENSE_BUDGET_NOT_CONFIRMED: '当前金额的预算尚未确认，请稍后刷新。',
    EXPENSE_PAPER_RECEIPT_REQUIRED: '请先由收单节点确认纸质原件。', TASK_DELEGATION_PENDING: '请先完成委派回交，再办理财务操作。',
    EXPENSE_WITHDRAWAL_NOT_ALLOWED: '本轮已进入财务审核，不能撤回。', PENDING_REQUEST_CHANGED: '上次操作结果尚未确认，请使用页面上的恢复入口。',
    REQUEST_TIMEOUT: '操作结果尚未确认，请使用页面上的恢复入口，勿重复提交。', RESPONSE_UNREADABLE: '操作响应未完整接收，请使用页面上的恢复入口确认结果。'
  }
  if (failure.code && messages[failure.code]) return messages[failure.code]!
  if ([401, 403, 404].includes(failure.status ?? 0)) return '当前无法查看或办理这份费用单，请刷新申请或恢复原账号。'
  return '费用请求未完成，请刷新核对；若页面提示待恢复操作，请先恢复原操作。'
}

/** 当前详情和按钮必须属于同一财务/审批版本；历史轮次只读，不读取当前动作。 */
export class ExpenseDetailQuery {
  detail: ExpenseDetail | null = null
  workflow: ExpenseWorkflow | null = null
  restricted = false
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetchDetail: (id: string, round: number | undefined, signal: AbortSignal) => Promise<ExpenseDetail>,
    private fetchWorkflow: (id: string, taskId: string | undefined, signal: AbortSignal) => Promise<ExpenseWorkflow>) {}
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null; this.detail = null; this.workflow = null; this.restricted = false; this.loading = false; this.error = ''
  }
  async load(scope: string, id: string, applicationId: string, taskId?: string, roundNo?: number) {
    this.clear()
    if (!scope || !id || !applicationId) return
    const generation = this.generation, controller = new AbortController(); this.controller = controller; this.loading = true
    const timer = setTimeout(() => {
      if (generation !== this.generation) return
      this.clear(); this.error = '费用详情查询超时，请重试。'
    }, EXPENSE_QUERY_TIMEOUT_MS)
    try {
      const [detail, workflow] = await Promise.all([this.fetchDetail(id, roundNo, controller.signal), roundNo === undefined ? this.fetchWorkflow(id, taskId, controller.signal) : Promise.resolve(null)])
      if (generation !== this.generation) return
      if (detail.id !== id || detail.applicationId !== applicationId || roundNo !== undefined && detail.roundNo !== roundNo
          || workflow && (workflow.reportId !== id || workflow.applicationId !== applicationId || workflow.applicationVersion !== detail.applicationVersion || workflow.financialVersion !== detail.financialVersion || workflow.roundNo !== detail.roundNo || taskId !== undefined && workflow.task?.taskId !== taskId)) {
        this.error = '费用内容或办理状态已变化，请刷新后重新核对。'; return
      }
      this.detail = detail; this.workflow = workflow
    } catch (cause) {
      if (generation === this.generation) {
        this.restricted = (cause as { status?: number }).status === 403
        this.error = this.restricted ? '当前身份无法查看完整费用明细。' : expenseError(cause)
      }
    }
    finally { clearTimeout(timer); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }
}

/** 本人报销和资金余额使用相同的有界分页，身份变化后不保留旧余额。 */
export class ExpensePageQuery<T> {
  items: T[] = []; nextBeforeId: string | null = null; loading = false; error = ''
  private generation = 0
  private controller: AbortController | null = null
  private context = ''
  constructor(private fetchPage: (filter: ExpenseFilter, signal: AbortSignal) => Promise<ExpensePage<T>>) {}
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.items = []; this.nextBeforeId = null; this.loading = false; this.error = ''; this.context = '' }
  async load(scope: string, status?: string, more = false) {
    const context = JSON.stringify([scope, status]), beforeId = more ? this.nextBeforeId : null
    if (more && (this.loading || !beforeId || context !== this.context)) return
    if (!more) this.clear()
    if (!scope) return
    this.context = context; this.error = ''; this.loading = true
    const generation = ++this.generation, controller = new AbortController(); this.controller = controller
    const timer = setTimeout(() => {
      if (generation !== this.generation) return
      this.generation++; controller.abort(); this.controller = null; this.loading = false; this.error = '费用列表查询超时，请重试。'
    }, EXPENSE_QUERY_TIMEOUT_MS)
    try {
      const page = await this.fetchPage({ limit: EXPENSE_PAGE_LIMIT, ...(beforeId ? { beforeId } : {}), ...(status ? { status } : {}) }, controller.signal)
      if (generation !== this.generation) return
      this.items = more ? [...this.items, ...page.items] : page.items; this.nextBeforeId = page.nextBeforeId
    } catch (cause) {
      if (generation !== this.generation) return
      if ([401, 403, 404].includes((cause as { status?: number }).status ?? 0)) this.clear()
      this.error = expenseError(cause)
    } finally { clearTimeout(timer); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }
}
