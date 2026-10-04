/** 财务报告按原轮次筛选；空值在响应中明确保留。@author owlzhangfq@gmail.com */
export interface FinancialFilter { from: string; to: string; legalEntityId?: string; departmentId?: string; categoryCode?: string }
/** 四种时长样本互斥，没有有效样本时分位数为空。@author owlzhangfq@gmail.com */
export interface FinancialCycle { samples: number; unknown: number; pending: number; notApplicable: number; p50Seconds: number | null; p90Seconds: number | null }
/** 金额以精确十进制字符串传递，不转换成浮点数。@author owlzhangfq@gmail.com */
export interface FinancialAmounts { currency: string; claimed: string; approved: string; reduced: string }
/** 重提独立计轮次，退回率只使用已有审批结论。@author owlzhangfq@gmail.com */
export interface FinancialMetrics {
  submitted: number; approved: number; returned: number; rejected: number; withdrawn: number; cancelled: number; inApproval: number
  overLimit: number; reduced: number; overLimitRate: number | null; reductionRate: number | null; returnRate: number | null
  approval: FinancialCycle; payment: FinancialCycle; endToEnd: FinancialCycle; amounts: FinancialAmounts[]; returnReasons: { reason: string | null; count: number }[]
}
/** 分组名称取原提交快照，未知历史不回填当前目录。@author owlzhangfq@gmail.com */
export interface FinancialGroup { dimension: 'LEGAL_ENTITY' | 'DEPARTMENT' | 'CATEGORY'; code: string | null; name: string | null; metrics: FinancialMetrics }
export const ageBands = ['NOT_DUE', 'DAYS_1_30', 'DAYS_31_60', 'DAYS_61_90', 'OVER_90', 'UNKNOWN'] as const
export const voucherStates = ['QUEUED', 'POSTING', 'QUERYING', 'UNKNOWN', 'FAILED', 'NOT_FOUND', 'EXPIRED', 'VOIDED', 'RECONCILING', 'REVERSED'] as const
export const paymentStates = ['QUEUED', 'CHECKING', 'SENDING', 'QUERYING', 'UNKNOWN', 'FAILED', 'NOT_FOUND', 'EXPIRED', 'VOIDED', 'RECONCILING', 'REVERSED'] as const
/** 余额与积压属于生成时点，独立于提交日期窗口。@author owlzhangfq@gmail.com */
export interface FinancialReport {
  generatedAt: string; timeZone: 'UTC'; scope: 'CURRENT_ACTOR_READABLE'
  filters: { from: string; to: string; legalEntityId: string | null; departmentId: string | null; categoryCode: string | null }
  totals: FinancialMetrics; groups: FinancialGroup[]
  activity: { verification: { succeeded: number; rejected: number; unavailable: number; pending: number; failureRate: number | null }
    duplicatePrechecks: number; duplicateSubmissions: { recorded: number; recordingStartedAt: string; containsUnrecordedHistory: boolean } }
  resources: { asOf: string; advanceCategoryApplicable: boolean
    advances: { currency: string; accounts: number; outstanding: string; underReview: string; ages: { band: typeof ageBands[number]; accounts: number; outstanding: string }[] }[]
    priorRequests: { currency: string; lines: number; approved: string; consumed: string; reserved: string; executionRate: number | null }[] }
  backlog: { asOf: string; vouchers: Record<typeof voucherStates[number], number>; payments: Record<typeof paymentStates[number], number> }
}

const DAY = 86_400_000
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const date = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= '0001-01-01'
  && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value

/** 包含当前 UTC 日期的三十天。 */
export function defaultFinancialFilter(now = new Date()): FinancialFilter {
  const to = now.toISOString().slice(0, 10)
  return { from: new Date(Date.parse(to) - 29 * DAY).toISOString().slice(0, 10), to }
}
/** 表单入口解释可修正的日期错误；类别必须保持原始编码。 */
export function financialFilter(value: FinancialFilter, now = new Date()): FinancialFilter {
  if (!date(value.from) || !date(value.to) || value.from > value.to || value.to > now.toISOString().slice(0, 10)
      || (Date.parse(value.to) - Date.parse(value.from)) / DAY >= 366) throw new Error('请选择有效的 UTC 日期，结束日期不晚于今天，范围最多 366 天。')
  if (value.legalEntityId != null && !uuid(value.legalEntityId) || value.departmentId != null && !uuid(value.departmentId)) throw new Error('请从原轮次分组选择法人或部门。')
  if (value.categoryCode != null && (!value.categoryCode.trim() || value.categoryCode.length > 64)) throw new Error('原费用类别不能为空，最多 64 字。')
  return { ...value }
}

type Row = Record<string, unknown>
function requireValue(valid: unknown): asserts valid { if (!valid) throw new Error('财务报告格式或查询范围不一致，请刷新后重试。') }
function row(value: unknown, keys: string): Row {
  requireValue(value && typeof value === 'object' && !Array.isArray(value))
  const expected = keys.split(' '), result = value as Row
  requireValue(Object.keys(result).length === expected.length && expected.every(key => Object.prototype.hasOwnProperty.call(result, key)))
  return result
}
function array(value: unknown): unknown[] { requireValue(Array.isArray(value)); return value }
function count(value: unknown) { requireValue(typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) }
function text(value: unknown) { requireValue(typeof value === 'string' && value.trim().length > 0) }
function instant(value: unknown) { requireValue(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))) }
function amount(value: unknown) { requireValue(typeof value === 'string' && /^(0|[1-9]\d*)\.\d{2}$/.test(value)) }
function rate(value: unknown, unbounded = false) { requireValue(value === null || typeof value === 'number' && Number.isFinite(value) && value >= 0 && (unbounded || value <= 1)) }
function currency(value: unknown) { requireValue(typeof value === 'string' && /^[A-Z]{3}$/.test(value)) }
function unique(values: unknown[]) { requireValue(new Set(values).size === values.length) }
function cycle(value: unknown) {
  const item = row(value, 'samples unknown pending notApplicable p50Seconds p90Seconds')
  for (const key of ['samples', 'unknown', 'pending', 'notApplicable']) count(item[key])
  if (item.samples === 0) requireValue(item.p50Seconds === null && item.p90Seconds === null)
  else { count(item.p50Seconds); count(item.p90Seconds); requireValue((item.p50Seconds as number) <= (item.p90Seconds as number)) }
}
function metrics(value: unknown) {
  const item = row(value, 'submitted approved returned rejected withdrawn cancelled inApproval overLimit reduced overLimitRate reductionRate returnRate approval payment endToEnd amounts returnReasons')
  for (const key of ['submitted', 'approved', 'returned', 'rejected', 'withdrawn', 'cancelled', 'inApproval', 'overLimit', 'reduced']) count(item[key])
  for (const key of ['overLimitRate', 'reductionRate', 'returnRate']) rate(item[key])
  for (const key of ['approval', 'payment', 'endToEnd']) cycle(item[key])
  const amounts = array(item.amounts).map(value => {
    const valueRow = row(value, 'currency claimed approved reduced'); currency(valueRow.currency)
    for (const key of ['claimed', 'approved', 'reduced']) amount(valueRow[key])
    return valueRow.currency
  }); unique(amounts)
  unique(array(item.returnReasons).map(value => { const reason = row(value, 'reason count'); if (reason.reason !== null) text(reason.reason); count(reason.count); return reason.reason }))
}

/** 只接纳完整报告并核对本次筛选，未知字段和错误正文不能保留为旧结果。 */
export function readFinancialReport(value: unknown, expected?: FinancialFilter): FinancialReport {
  const result = row(value, 'generatedAt timeZone scope filters totals groups activity resources backlog')
  instant(result.generatedAt); requireValue(result.timeZone === 'UTC' && result.scope === 'CURRENT_ACTOR_READABLE')
  const filters = row(result.filters, 'from to legalEntityId departmentId categoryCode')
  requireValue(date(filters.from) && date(filters.to) && filters.from <= filters.to && (Date.parse(filters.to) - Date.parse(filters.from)) / DAY < 366)
  for (const key of ['legalEntityId', 'departmentId']) requireValue(filters[key] === null || uuid(filters[key]))
  if (filters.categoryCode !== null) { text(filters.categoryCode); requireValue((filters.categoryCode as string).length <= 64) }
  if (expected) for (const key of ['from', 'to', 'legalEntityId', 'departmentId', 'categoryCode'] as const) {
    const actual = filters[key], wanted = expected[key] ?? null
    requireValue(key.endsWith('Id') && typeof actual === 'string' && typeof wanted === 'string' ? actual.toLowerCase() === wanted.toLowerCase() : actual === wanted)
  }
  metrics(result.totals)
  unique(array(result.groups).map(value => {
    const group = row(value, 'dimension code name metrics'); requireValue(['LEGAL_ENTITY', 'DEPARTMENT', 'CATEGORY'].includes(group.dimension as string))
    if (group.dimension === 'CATEGORY') { text(group.code); requireValue((group.code as string).length <= 64 && group.name === group.code) }
    else { requireValue(group.code === null || uuid(group.code)); if (group.name !== null) text(group.name); requireValue((group.code === null) === (group.name === null)) }
    metrics(group.metrics); return JSON.stringify([group.dimension, group.code, group.name])
  }))
  const activity = row(result.activity, 'verification duplicatePrechecks duplicateSubmissions')
  const verification = row(activity.verification, 'succeeded rejected unavailable pending failureRate')
  for (const key of ['succeeded', 'rejected', 'unavailable', 'pending']) count(verification[key])
  rate(verification.failureRate); count(activity.duplicatePrechecks)
  const duplicates = row(activity.duplicateSubmissions, 'recorded recordingStartedAt containsUnrecordedHistory')
  count(duplicates.recorded); instant(duplicates.recordingStartedAt); requireValue(typeof duplicates.containsUnrecordedHistory === 'boolean')
  const resources = row(result.resources, 'asOf advanceCategoryApplicable advances priorRequests')
  requireValue(resources.asOf === result.generatedAt && resources.advanceCategoryApplicable === (filters.categoryCode === null))
  if (!resources.advanceCategoryApplicable) requireValue(array(resources.advances).length === 0)
  unique(array(resources.advances).map(value => {
    const loan = row(value, 'currency accounts outstanding underReview ages'); currency(loan.currency); count(loan.accounts); amount(loan.outstanding); amount(loan.underReview)
    const bands = array(loan.ages).map(value => { const age = row(value, 'band accounts outstanding'); count(age.accounts); amount(age.outstanding); return age.band })
    requireValue(bands.length === ageBands.length && ageBands.every((band, index) => bands[index] === band)); return loan.currency
  }))
  unique(array(resources.priorRequests).map(value => {
    const plan = row(value, 'currency lines approved consumed reserved executionRate'); currency(plan.currency); count(plan.lines)
    for (const key of ['approved', 'consumed', 'reserved']) amount(plan[key]); rate(plan.executionRate, true); return plan.currency
  }))
  const backlog = row(result.backlog, 'asOf vouchers payments'); requireValue(backlog.asOf === result.generatedAt)
  for (const [key, states] of [['vouchers', voucherStates], ['payments', paymentStates]] as const) {
    const counts = row(backlog[key], states.join(' ')); for (const state of states) count(counts[state])
  }
  return value as FinancialReport
}

/** 显示仍采用原精确字符串，避免大金额被浮点舍入。 */
export function financialAmount(value: string) { const [integer, fraction] = value.split('.'); return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + fraction }
/** 零分母显示未知，执行率允许超过百分之百。 */
export function financialPercent(value: number | null) { return value === null ? '—' : new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 2 }).format(value) }

/** 身份、筛选、卸载和超时统一失效旧响应，不保留旧报告。@author owlzhangfq@gmail.com */
export class ExpenseFinancialQuery {
  report: FinancialReport | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetchReport: (filter: FinancialFilter, signal: AbortSignal) => Promise<unknown>) {}
  /** 主动取消时也立即清空已经渲染的金额。 */
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.report = null; this.loading = false; this.error = '' }
  /** 即使下游忽略 AbortSignal，也以本次序号和超时竞争屏蔽迟到正文。 */
  async load(scope: string, filter: FinancialFilter) {
    this.clear(); if (!scope) return
    const generation = this.generation, controller = new AbortController(), snapshot = { ...filter }
    this.controller = controller; this.loading = true
    let timedOut = false, abort: () => void = () => {}
    const cancelled = new Promise<never>((_, reject) => { abort = () => reject(new Error('查询已取消')); controller.signal.addEventListener('abort', abort, { once: true }) })
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, 15_000)
    try {
      const result = await Promise.race([this.fetchReport(snapshot, controller.signal), cancelled])
      if (generation === this.generation) this.report = readFinancialReport(result, snapshot)
    } catch (cause) {
      if (generation === this.generation) this.error = timedOut ? '统计查询超时，请缩小范围后重试。' : (cause instanceof Error ? cause.message : '财务报告查询失败，请重试。')
    } finally {
      clearTimeout(timer); controller.signal.removeEventListener('abort', abort)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
