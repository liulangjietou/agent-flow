import type { SubmissionRound } from './api'
import type { FormField } from './formSchema'

/** 保存值与缺失分别表示，不把空值、false 或零折叠为同一个占位符。@author owlzhangfq@gmail.com */
export interface ComparisonValue { present: boolean; text: string; type: string }
/** 同一字段在两轮中可能拥有不同名称、类型或选项。@author owlzhangfq@gmail.com */
export interface RoundDifference {
  key: string; beforeLabel: string; afterLabel: string; category: 'title' | 'version' | 'field'
  before: ComparisonValue; after: ComparisonValue; changed: boolean; definitionChanged: boolean
}
/** 比较只描述提交快照，不推导当前草稿或审批结论。@author owlzhangfq@gmail.com */
export interface RoundComparison { rows: RoundDifference[]; valueChanges: number; definitionChanges: number; schemaChanged: boolean }

/** JSON 对象键顺序不构成修改；数组顺序、原始类型和字符串写法均保留。 */
export function sameSubmittedValue(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (left == null || right == null || typeof left !== 'object' || typeof right !== 'object') return false
  if (Array.isArray(left) || Array.isArray(right)) return Array.isArray(left) && Array.isArray(right)
    && left.length === right.length && left.every((value, index) => sameSubmittedValue(value, right[index]))
  const a = left as Record<string, unknown>, b = right as Record<string, unknown>
  const keys = Object.keys(a)
  return keys.length === Object.keys(b).length && keys.every(key => Object.prototype.hasOwnProperty.call(b, key) && sameSubmittedValue(a[key], b[key]))
}

/** 仅按各自轮次的表单解释显示值，所有输出作为纯文本渲染。 */
function displayValue(present: boolean, value: unknown, field?: FormField): ComparisonValue {
  if (!present) return { present, text: '未提交该字段', type: '缺失' }
  if (value === null) return { present, text: '空值（null）', type: '空值' }
  if (value === '') return { present, text: '空文本', type: '文本' }
  if (typeof value === 'boolean') return { present, text: value ? '是（true）' : '否（false）', type: '布尔' }
  if (typeof value === 'object') return { present, text: JSON.stringify(value, null, 2), type: Array.isArray(value) ? '数组' : '对象' }
  if (field?.type === 'SELECT') {
    const option = field.options?.find(option => option.value === value)
    return { present, text: option ? `${option.label}（${String(value)}）` : String(value), type: '选项原值' }
  }
  return { present, text: String(value), type: field?.type === 'NUMBER' ? '数字原值' : typeof value === 'number' ? '数字' : '文本' }
}

/** 按原始快照对比标题、定义版本、所有字段值及字段配置，不修改输入。 */
export function compareSubmissionRounds(before: SubmissionRound, after: SubmissionRound): RoundComparison {
  const fieldsBefore = new Map((before.formSchema?.fields ?? []).map(field => [field.key, field]))
  const fieldsAfter = new Map((after.formSchema?.fields ?? []).map(field => [field.key, field]))
  const keys = new Set([...fieldsAfter.keys(), ...fieldsBefore.keys(), ...Object.keys(after.payload), ...Object.keys(before.payload)])
  const special = (key: string, label: string, category: 'title' | 'version', a: unknown, b: unknown): RoundDifference => ({
    key, beforeLabel: label, afterLabel: label, category, before: displayValue(true, a), after: displayValue(true, b),
    changed: !sameSubmittedValue(a, b), definitionChanged: false
  })
  const rows: RoundDifference[] = [special('title', '申请标题', 'title', before.title, after.title),
    special('definitionVersion', '流程版本', 'version', `v${before.definitionVersion}`, `v${after.definitionVersion}`)]
  for (const key of keys) {
    const a = fieldsBefore.get(key), b = fieldsAfter.get(key)
    const hasBefore = Object.prototype.hasOwnProperty.call(before.payload, key), hasAfter = Object.prototype.hasOwnProperty.call(after.payload, key)
    const legacyLabel = key === 'amount' ? '申请金额' : key === 'description' ? '申请说明' : key
    rows.push({ key, category: 'field', beforeLabel: a?.label ?? legacyLabel, afterLabel: b?.label ?? legacyLabel,
      before: displayValue(hasBefore, before.payload[key], a), after: displayValue(hasAfter, after.payload[key], b),
      changed: hasBefore !== hasAfter || (hasBefore && hasAfter && !sameSubmittedValue(before.payload[key], after.payload[key])),
      definitionChanged: !sameSubmittedValue(a, b) })
  }
  return { rows, valueChanges: rows.filter(row => row.changed).length,
    definitionChanges: rows.filter(row => row.definitionChanged).length,
    schemaChanged: !sameSubmittedValue(before.formSchema, after.formSchema) }
}

/** 保留仍然有效的选择；缺失轮次不伪造，默认比较最近两个真实快照。 */
export function selectRoundPair(rounds: SubmissionRound[], before = 0, after = 0): { before: number; after: number } {
  const numbers = rounds.map(round => round.roundNo).sort((a, b) => a - b)
  if (numbers.length < 2) return { before: 0, after: 0 }
  const chosenAfter = numbers.includes(after) && after > numbers[0]! ? after : numbers[numbers.length - 1]!
  const earlier = numbers.filter(round => round < chosenAfter)
  return { before: earlier.includes(before) ? before : earlier[earlier.length - 1]!, after: chosenAfter }
}

/** 不缓存申请正文，隔离账号/申请切换和失效请求。@author owlzhangfq@gmail.com */
export class RoundComparisonQuery {
  rounds: SubmissionRound[] | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private readonly fetchRounds: (id: string, signal?: AbortSignal) => Promise<SubmissionRound[]>) {}
  /** 离开或刷新时立即清除旧快照。 */
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.rounds = null; this.loading = false; this.error = '' }
  /** 复用轮次授权接口，失败时不继续展示旧申请内容。 */
  async load(scope: string, id: string) {
    this.clear()
    if (!scope || !id) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const rounds = await Promise.race([this.fetchRounds(id, controller.signal), new Promise<never>((_, reject) => {
        timeout = setTimeout(() => { controller.abort(); reject({ message: '提交记录加载超时，请重试。' }) }, 12_000)
      })])
      if (generation === this.generation) this.rounds = [...rounds].sort((a, b) => b.roundNo - a.roundNo)
    } catch (cause) {
      if (generation !== this.generation) return
      const failure = cause as { status?: number; message?: string }
      this.error = failure.status === 401 ? '登录已失效，请重新登录。'
        : failure.status === 404 || failure.status === 403 ? '提交记录不可用或无权查看，请重新核对申请。'
        : failure.message ?? '提交记录加载失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
