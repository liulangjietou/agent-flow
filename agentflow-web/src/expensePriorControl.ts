import { amountMinor, moneyLabel, type Money, type ExpensePage, type PriorRequestItem } from './expenses.js'

export type PriorControlMode = 'STRICT' | 'TOLERANCE' | 'NONE'
export interface PriorControl { mode: PriorControlMode; toleranceFraction?: number | null }
export interface PriorControlSource { categoryCode: string; categoryRevision: number; control: PriorControl }
export interface PriorApprovedLine { lineNo: number; approvedAmount: Money; toleranceFraction: number; policyReference: string; control?: PriorControlSource }
export interface PriorAssessment {
  lineNo: number; requestId: string; requestVersion: number; source: PriorApprovedLine
  threshold: Money; consumed: Money; otherReserved: Money; roundReserved: Money; lineAmount: Money; totalExposure: Money; exceeded: Money
}
export interface PriorSubmissionSnapshot {
  tenantId: string; reportId: string; applicationId: string; applicationVersion: number; roundNo: number; financialVersion: number
  definitionId: string; definitionVersion: number; submittedAt: string; assessments: PriorAssessment[]
}
export interface PriorControlView { reportId: string; applicationId: string; roundNo: number; status: 'NOT_RECORDED' | 'RECORDED'; requiresApproval: boolean | null; details: PriorSubmissionSnapshot | null }
export const priorControlModes: Record<PriorControlMode, string> = { STRICT: '严格控制', TOLERANCE: '容差控制', NONE: '不限制额度' }
const SCALE = 1_000_000n
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) > 0
const lineNo = (value: unknown): value is number => positive(value) && value <= 200
const text = (value: unknown, limit: number): value is string => typeof value === 'string' && !!value.trim() && value.length <= limit
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const own = (value: object, name: string) => Object.prototype.hasOwnProperty.call(value, name)
function keys(value: unknown, required: string[], optional: string[] = []): value is Record<string, unknown> {
  return object(value) && required.every(name => own(value, name)) && Object.keys(value).every(name => required.includes(name) || optional.includes(name))
}
function requireValue(valid: unknown): asserts valid { if (!valid) throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '事前额度依据不完整或与本轮不符，请重新读取。' } }
/** 以百万分之一转换比例，金额计算全程使用整数分。 */
function fraction(value: unknown): bigint {
  requireValue(typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1 && Number(value.toFixed(6)) === value)
  return BigInt(value.toFixed(6).replace('.', ''))
}
function monetary(value: unknown, currency?: string): value is Money {
  if (!keys(value, ['value', 'currency']) || typeof value.value !== 'string' || typeof value.currency !== 'string' || !/^[A-Z]{3}$/.test(value.currency)
    || currency !== undefined && value.currency !== currency) return false
  try { amountMinor(value.value); return true } catch { return false }
}
/** 类别配置只接受明确模式；容差不得用缺省值代替企业选择。 */
export function readPriorControl(value: unknown): PriorControl {
  requireValue(keys(value, ['mode'], ['toleranceFraction']) && typeof value.mode === 'string' && own(priorControlModes, value.mode))
  if (value.mode === 'TOLERANCE') fraction(value.toleranceFraction)
  else requireValue(value.toleranceFraction == null)
  return { mode: value.mode as PriorControlMode, ...(own(value, 'toleranceFraction') ? { toleranceFraction: value.toleranceFraction as number | null } : {}) }
}
/** 类别身份、修订和模式必须一起出现，不能按比例猜测控制来源。 */
export function readPriorControlSource(value: unknown): PriorControlSource {
  requireValue(keys(value, ['categoryCode', 'categoryRevision', 'control']) && text(value.categoryCode, 64) && positive(value.categoryRevision))
  return { categoryCode: value.categoryCode, categoryRevision: value.categoryRevision, control: readPriorControl(value.control) }
}
export function priorControlLabel(control?: PriorControl | null): string {
  if (!control) return '历史硬上限'
  const checked = readPriorControl(control)
  return checked.mode === 'TOLERANCE' ? `容差 ${Number((Number(checked.toleranceFraction) * 100).toFixed(4))}% · 超过阈值需说明和独立审批` : priorControlModes[checked.mode]
}
/** NONE 的余额只供参考，不能显示成允许报销的剩余上限。 */
export function priorBalanceLabel(line: PriorRequestItem['lines'][number]): string {
  const mode = line.control?.control.mode
  return mode === 'NONE' ? `不按额度阻断 · 参考余额 ${moneyLabel(line.available)}`
    : mode === 'TOLERANCE' ? `阈值内参考余额 ${moneyLabel(line.available)}` : `可用额度 ${moneyLabel(line.available)}`
}
/** 一个来源行的各条本轮用量共享同一份累计依据，逐项核对十进制总额。 */
export function readPriorAssessments(value: unknown): PriorAssessment[] {
  requireValue(Array.isArray(value) && value.length <= 200)
  const numbers = new Set<number>(), sources = new Map<string, { fingerprint: string; own: bigint; expected: bigint }>()
  for (const item of value) {
    requireValue(keys(item, ['lineNo', 'requestId', 'requestVersion', 'source', 'threshold', 'consumed', 'otherReserved', 'roundReserved', 'lineAmount', 'totalExposure', 'exceeded'])
      && lineNo(item.lineNo) && !numbers.has(item.lineNo) && uuid(item.requestId) && positive(item.requestVersion))
    numbers.add(item.lineNo)
    const source = item.source
    requireValue(keys(source, ['lineNo', 'approvedAmount', 'toleranceFraction', 'policyReference'], ['control'])
      && lineNo(source.lineNo) && monetary(source.approvedAmount) && amountMinor(source.approvedAmount.value) > 0n && text(source.policyReference, 128))
    const tolerance = fraction(source.toleranceFraction), control = own(source, 'control') ? readPriorControlSource(source.control) : null
    if (control) requireValue(tolerance === (control.control.mode === 'TOLERANCE' ? fraction(control.control.toleranceFraction) : 0n))
    const currency = source.approvedAmount.currency
    for (const key of ['threshold', 'consumed', 'otherReserved', 'roundReserved', 'lineAmount', 'totalExposure', 'exceeded']) requireValue(monetary(item[key], currency))
    const row = item as unknown as PriorAssessment
    const threshold = amountMinor(row.threshold.value), total = amountMinor(row.totalExposure.value), round = amountMinor(row.roundReserved.value), ownAmount = amountMinor(row.lineAmount.value)
    requireValue(threshold === amountMinor(source.approvedAmount.value) * (SCALE + tolerance) / SCALE && ownAmount > 0n && ownAmount <= round
      && amountMinor(row.consumed.value) + amountMinor(row.otherReserved.value) + round === total
      && amountMinor(row.exceeded.value) === (total > threshold ? total - threshold : 0n)
      && (control && control.control.mode !== 'STRICT' || total <= threshold))
    const identity = `${row.requestId}:${source.lineNo}`, previous = sources.get(identity)
    const fingerprint = JSON.stringify({ ...row, lineNo: undefined, lineAmount: undefined })
    if (previous) { requireValue(previous.fingerprint === fingerprint); previous.own += ownAmount }
    else sources.set(identity, { fingerprint, own: ownAmount, expected: round })
  }
  requireValue([...sources.values()].every(source => source.own === source.expected))
  return value as PriorAssessment[]
}
export function priorReviewRequired(value: PriorAssessment): boolean { return value.source.control?.control.mode === 'TOLERANCE' && amountMinor(value.exceeded.value) > 0n }
/** 原轮次金额不以当前核减金额替换，未知历史必须明确显示为未记录。 */
export function readPriorControlView(value: unknown, reportId: string, applicationId: string, roundNo: number): PriorControlView {
  requireValue(keys(value, ['reportId', 'applicationId', 'roundNo', 'status', 'requiresApproval', 'details']) && uuid(value.reportId) && value.reportId === reportId
    && uuid(value.applicationId) && value.applicationId === applicationId && positive(value.roundNo) && value.roundNo === roundNo)
  if (value.status === 'NOT_RECORDED') requireValue(value.details === null && value.requiresApproval === null)
  else {
    const details = value.details
    requireValue(value.status === 'RECORDED' && typeof value.requiresApproval === 'boolean' && keys(details,
      ['tenantId', 'reportId', 'applicationId', 'applicationVersion', 'roundNo', 'financialVersion', 'definitionId', 'definitionVersion', 'submittedAt', 'assessments'])
      && text(details.tenantId, 64) && details.reportId === reportId && details.applicationId === applicationId && details.roundNo === roundNo
      && positive(details.applicationVersion) && positive(details.financialVersion) && details.financialVersion >= 2
      && uuid(details.definitionId) && positive(details.definitionVersion) && typeof details.submittedAt === 'string' && Number.isFinite(Date.parse(details.submittedAt)))
    const rows = readPriorAssessments(details.assessments)
    requireValue(value.requiresApproval === rows.some(priorReviewRequired))
  }
  return value as unknown as PriorControlView
}
/** 选择器保留旧页兼容，并校验新控制与上限语义一致；未知控制不能被当作无限额度。 */
export function readPriorRequestPage(value: unknown): ExpensePage<PriorRequestItem> {
  requireValue(keys(value, ['items'], ['nextBeforeId']) && Array.isArray(value.items) && (value.nextBeforeId == null || uuid(value.nextBeforeId)))
  for (const item of value.items) {
    requireValue(object(item) && uuid(item.id) && uuid(item.applicationId) && uuid(item.legalEntityId) && positive(item.version) && typeof item.closed === 'boolean' && Array.isArray(item.lines))
    for (const line of item.lines) {
      requireValue(object(line) && lineNo(line.lineNo) && monetary(line.approved))
      for (const name of ['limit', 'available', 'reserved', 'consumed']) requireValue(monetary(line[name], line.approved.currency))
      const control = line.control == null ? null : readPriorControlSource(line.control)
      if (control || own(line, 'hardLimit')) requireValue(line.hardLimit === (!control || control.control.mode === 'STRICT'))
      if (control || own(line, 'exceeded')) requireValue(monetary(line.exceeded, line.approved.currency))
    }
  }
  return value as unknown as ExpensePage<PriorRequestItem>
}
