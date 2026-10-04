import { amountMinor, type Money } from './expenses.js'

export type SplitStatus = 'NOT_RECORDED' | 'UNCONFIGURED' | 'DISABLED' | 'CLEAR' | 'SPLIT_SUSPECTED' | 'RESTRICTED'
export interface SplitRule { windowDays: number; threshold: Money }
export interface SplitConfiguration { mode: 'UNCONFIGURED' | 'DISABLED' | 'ENABLED'; rule?: SplitRule; gatewayIds: string[] }
export interface SplitLine { lineNo: number; categoryCode: string; approvedGross: Money }
export interface SplitDocument {
  reportId: string; applicationId: string; applicationVersion: number; financialVersion: number; roundNo: number
  scope: { tenantId: string; employeeId: string; legalEntityId: string; currency: string }
  submittedAt: string; status: string; lines: SplitLine[]
}
export interface SplitCategory { categoryCode: string; total: Money; reportCount: number; triggered: boolean }
export interface SplitAssessment { windowFrom: string; assessedAt: string; ownAmount: Money; routingAmount: Money; categories: SplitCategory[]; sources: SplitDocument[] }
export interface SplitSnapshot { ruleVersion: number; definitionId: string; processKey: string; definitionVersion: number; configuration: SplitConfiguration; primary: SplitDocument; assessment?: SplitAssessment }
export interface SplitRoutingView { reportId: string; applicationId: string; roundNo: number; status: SplitStatus; sourcesReadable: boolean; details: SplitSnapshot | null }

export const splitStatuses: Record<SplitStatus, string> = {
  NOT_RECORDED: '本轮未记录跨单检查', UNCONFIGURED: '本轮未配置跨单规则', DISABLED: '本轮已关闭跨单规则',
  CLEAR: '本轮未命中拆单风险', SPLIT_SUSPECTED: '本轮命中拆单风险', RESTRICTED: '跨单依据受限'
}
const MAX_DOCUMENTS = 1000, MAX_LINES = 200, MAX_WINDOW_DAYS = 365, READ_TIMEOUT_MS = 12_000
const MAX_ROUND_NO = 2147483647, RULE_VERSION = 1
const DAY_NANOS = 86_400_000_000_000n
const own = (value: object, key: string) => Object.prototype.hasOwnProperty.call(value, key)
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) > 0
const validRound = (value: unknown): value is number => positive(value) && value <= MAX_ROUND_NO
const text = (value: unknown, max: number): value is string => typeof value === 'string' && !!value.trim() && value.length <= max
const unique = (values: unknown[]) => new Set(values).size === values.length
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '跨单路由依据不完整或与本轮不一致，请刷新。' })
function keys(value: unknown, required: string[], optional: string[] = []): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value) && required.every(key => own(value, key))
    && Object.keys(value).every(key => required.includes(key) || optional.includes(key))
}
function monetary(value: unknown, currency?: string): value is Money {
  if (!keys(value, ['value', 'currency']) || typeof value.value !== 'string' || typeof value.currency !== 'string'
      || !/^[A-Z]{3}$/.test(value.currency) || currency !== undefined && value.currency !== currency) return false
  try { amountMinor(value.value); return true } catch { return false }
}
/** Java 原依据保留微秒或纳秒，不能用 Date 的毫秒截断比较窗口边界。 */
function instant(value: unknown): bigint | null {
  if (typeof value !== 'string') return null
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value)
  if (!match) return null
  const seconds = Date.parse(match[1] + 'Z')
  if (!Number.isFinite(seconds) || new Date(seconds).toISOString().slice(0, 19) !== match[1]) return null
  return BigInt(seconds / 1000) * 1_000_000_000n + BigInt((match[2] ?? '').padEnd(9, '0'))
}
function readDocument(value: unknown, primary: boolean): SplitDocument {
  if (!keys(value, ['reportId', 'applicationId', 'applicationVersion', 'financialVersion', 'roundNo', 'scope', 'submittedAt', 'status', 'lines'])
      || !uuid(value.reportId) || !uuid(value.applicationId) || !positive(value.applicationVersion) || !positive(value.financialVersion)
      || !validRound(value.roundNo) || instant(value.submittedAt) === null
      || !(primary ? ['DRAFT', 'RETURNED', 'WITHDRAWN'] : ['IN_APPROVAL', 'APPROVED']).includes(String(value.status))
      || !keys(value.scope, ['tenantId', 'employeeId', 'legalEntityId', 'currency']) || !text(value.scope.tenantId, 128)
      || !text(value.scope.employeeId, 128) || !uuid(value.scope.legalEntityId) || !text(value.scope.currency, 3)
      || !Array.isArray(value.lines) || !value.lines.length || value.lines.length > MAX_LINES) throw unreadable()
  const currency = value.scope.currency
  if (!value.lines.every(line => keys(line, ['lineNo', 'categoryCode', 'approvedGross']) && positive(line.lineNo)
      && line.lineNo <= MAX_LINES && text(line.categoryCode, 64) && monetary(line.approvedGross, currency))
      || !unique(value.lines.map(line => line.lineNo))) throw unreadable()
  return value as unknown as SplitDocument
}
const scopeKey = (doc: SplitDocument) => JSON.stringify([doc.scope.tenantId, doc.scope.employeeId, doc.scope.legalEntityId, doc.scope.currency])
const documentKey = (doc: SplitDocument) => JSON.stringify([doc.reportId, doc.applicationId, doc.applicationVersion, doc.financialVersion,
  doc.roundNo, scopeKey(doc), doc.submittedAt, doc.status, doc.lines.map(line => [line.lineNo, line.categoryCode, line.approvedGross.value, line.approvedGross.currency])])
function readConfiguration(value: unknown, currency: string): SplitConfiguration {
  if (!keys(value, ['mode', 'gatewayIds'], ['rule']) || !['UNCONFIGURED', 'DISABLED', 'ENABLED'].includes(String(value.mode))
      || !Array.isArray(value.gatewayIds) || !value.gatewayIds.every(id => text(id, 128)) || !unique(value.gatewayIds)) throw unreadable()
  if (own(value, 'rule') && (!keys(value.rule, ['windowDays', 'threshold']) || !positive(value.rule.windowDays)
      || value.rule.windowDays > MAX_WINDOW_DAYS || !monetary(value.rule.threshold, value.mode === 'ENABLED' ? currency : undefined)
      || amountMinor(value.rule.threshold.value) === 0n)) throw unreadable()
  if (value.mode === 'ENABLED' && (!own(value, 'rule') || !value.gatewayIds.length)
      || value.mode === 'UNCONFIGURED' && (own(value, 'rule') || value.gatewayIds.length)) throw unreadable()
  return value as unknown as SplitConfiguration
}
/** 校验完整来源与显示合计的关系；这些检查只拒绝损坏响应，不生成或写入审批依据。 */
function readAssessment(value: unknown, primary: SplitDocument, rule: SplitRule, status: SplitStatus): void {
  if (!keys(value, ['windowFrom', 'assessedAt', 'ownAmount', 'routingAmount', 'categories', 'sources'])) throw unreadable()
  const at = instant(value.assessedAt), from = instant(value.windowFrom), submitted = instant(primary.submittedAt)
  if (at === null || from === null || at !== submitted || at - from !== BigInt(rule.windowDays) * DAY_NANOS
      || !monetary(value.ownAmount, primary.scope.currency) || !monetary(value.routingAmount, primary.scope.currency)
      || !Array.isArray(value.sources) || !value.sources.length || value.sources.length > MAX_DOCUMENTS
      || !Array.isArray(value.categories) || value.categories.length > MAX_LINES) throw unreadable()
  const documents = value.sources.map((source, index) => readDocument(source, index === 0))
  if (documentKey(documents[0]!) !== documentKey(primary) || !unique(documents.map(doc => doc.reportId)) || !unique(documents.map(doc => doc.applicationId))
      || documents.some(doc => scopeKey(doc) !== scopeKey(primary) || instant(doc.submittedAt)! < from || instant(doc.submittedAt)! > at)) throw unreadable()
  const categories = new Map(primary.lines.filter(line => amountMinor(line.approvedGross.value) > 0n).map(line => [line.categoryCode, { amount: 0n, count: 0 }]))
  for (const [index, doc] of documents.entries()) {
    const amounts = new Map<string, bigint>()
    for (const line of doc.lines) if (categories.has(line.categoryCode) && amountMinor(line.approvedGross.value) > 0n) {
      amounts.set(line.categoryCode, (amounts.get(line.categoryCode) ?? 0n) + amountMinor(line.approvedGross.value))
    }
    if (index > 0 && !amounts.size) throw unreadable()
    for (const [category, amount] of amounts) { const total = categories.get(category)!; total.amount += amount; total.count++ }
  }
  const ownAmount = primary.lines.reduce((total, line) => total + amountMinor(line.approvedGross.value), 0n)
  let routingAmount = ownAmount, suspected = false
  if (amountMinor(value.ownAmount.value) !== ownAmount || value.categories.length !== categories.size
      || !unique(value.categories.map(category => category?.categoryCode))) throw unreadable()
  for (const category of value.categories) {
    if (!keys(category, ['categoryCode', 'total', 'reportCount', 'triggered']) || !text(category.categoryCode, 64)
        || !monetary(category.total, primary.scope.currency) || !positive(category.reportCount) || typeof category.triggered !== 'boolean') throw unreadable()
    const calculated = categories.get(category.categoryCode)
    if (!calculated || calculated.amount !== amountMinor(category.total.value) || calculated.count !== category.reportCount
        || category.triggered !== (calculated.count > 1 && calculated.amount > amountMinor(rule.threshold.value))) throw unreadable()
    if (category.triggered) { suspected = true; if (calculated.amount > routingAmount) routingAmount = calculated.amount }
  }
  if (amountMinor(value.routingAmount.value) !== routingAmount || suspected !== (status === 'SPLIT_SUSPECTED')) throw unreadable()
}

/** 原轮次响应只在一个入口完整校验，展示组件不再按字段补默认值或猜测权限状态。 */
export function readSplitRouting(value: unknown, reportId: string, roundNo: number, applicationId?: string): SplitRoutingView {
  if (!keys(value, ['reportId', 'applicationId', 'roundNo', 'status', 'sourcesReadable', 'details']) || !uuid(value.reportId)
      || value.reportId !== reportId || !uuid(value.applicationId) || applicationId !== undefined && value.applicationId !== applicationId
      || !validRound(value.roundNo) || value.roundNo !== roundNo || typeof value.status !== 'string' || !own(splitStatuses, value.status)
      || typeof value.sourcesReadable !== 'boolean') throw unreadable()
  const status = value.status as SplitStatus
  if (status === 'NOT_RECORDED' || status === 'RESTRICTED') {
    if (value.sourcesReadable || value.details !== null) throw unreadable()
  } else {
    const detail = value.details
    if (!value.sourcesReadable || !keys(detail, ['ruleVersion', 'definitionId', 'processKey', 'definitionVersion', 'configuration', 'primary'], ['assessment'])
        || detail.ruleVersion !== RULE_VERSION || !uuid(detail.definitionId) || !text(detail.processKey, 128) || !positive(detail.definitionVersion)) throw unreadable()
    const primary = readDocument(detail.primary, true), configuration = readConfiguration(detail.configuration, primary.scope.currency)
    if (primary.reportId !== reportId || primary.applicationId !== value.applicationId || primary.roundNo !== roundNo
        || configuration.mode !== (status === 'CLEAR' || status === 'SPLIT_SUSPECTED' ? 'ENABLED' : status)) throw unreadable()
    if (configuration.mode === 'ENABLED') readAssessment(detail.assessment, primary, configuration.rule!, status)
    else if (own(detail, 'assessment')) throw unreadable()
  }
  return value as unknown as SplitRoutingView
}

/** 每次读取绑定当前身份及原轮次，切换、刷新和卸载立即清除跨单正文。 */
export class SplitRoutingQuery {
  view: SplitRoutingView | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetch: (id: string, roundNo: number, signal: AbortSignal) => Promise<unknown>) {}
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.view = null; this.loading = false; this.error = ''
  }
  async load(scope: string, reportId: string, applicationId: string, roundNo: number) {
    this.clear()
    if (!scope || !uuid(reportId) || !uuid(applicationId) || !validRound(roundNo)) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timedOut = false
    const timeout = setTimeout(() => { timedOut = true; controller.abort() }, READ_TIMEOUT_MS)
    try {
      const value = await this.fetch(reportId, roundNo, controller.signal)
      if (generation !== this.generation) return
      if (timedOut) this.error = '读取跨单依据超时，请刷新。'
      else this.view = readSplitRouting(value, reportId, roundNo, applicationId)
    } catch (error) {
      if (generation === this.generation) {
        const failure = error as { code?: string; status?: number; message?: string }
        this.error = timedOut ? '读取跨单依据超时，请刷新。' : failure.code === 'RESPONSE_UNREADABLE' ? failure.message!
          : failure.status === 403 || failure.status === 404 ? '当前无法读取这份原轮次依据。' : '读取跨单依据失败，请刷新。'
      }
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
