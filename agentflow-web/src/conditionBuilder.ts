import { parseConditionExpression } from './conditionSyntax.js'
import { validDecimal } from './formSchema.js'
import type { FormField } from './formSchema'
/** 可视化条件只表达同一连接符的比较列表，复杂表达式原文保留。@author owlzhangfq@gmail.com */
export interface ConditionRows { join: 'AND' | 'OR'; rows: ConditionRow[] }
/** 值以字符串保存，避免金额阈值经过 JS 浮点数损失精度。@author owlzhangfq@gmail.com */
export interface ConditionRow { field: string; operator: string; value: string; values?: string[] }
export const conditionOperators = [
  { value: '==', label: '等于' }, { value: '!=', label: '不等于' }, { value: '>', label: '大于' },
  { value: '>=', label: '大于等于' }, { value: '<', label: '小于' }, { value: '<=', label: '小于等于' },
  { value: 'EXISTS', label: '已填写' }, { value: 'NOT_EXISTS', label: '未填写' }
]
/** 文本、选项和布尔仅开放其真实支持的运算。 */
export function operatorsFor(field?: FormField, version = 1) {
  const result = conditionOperators.filter(operator => field?.type === 'TABLE' ? ['EXISTS', 'NOT_EXISTS'].includes(operator.value) : ['NUMBER', 'DATE'].includes(field?.type ?? '') || ['==', '!=', 'EXISTS', 'NOT_EXISTS'].includes(operator.value))
  return version === 2 && field?.type === 'SELECT' ? [...result, { value: 'IN', label: '属于（多选）' }] : result
}
/** 仅识别可无歧义回显的平面语法；不能识别时绝不重写原条件。 */
export function parseConditionRows(source: string, fields: FormField[], version = 1): ConditionRows | null {
  if (version === 2 && source.trim()) return parseVersionTwo(source, fields)
  if (!source.trim()) return { join: 'AND', rows: [{ field: fields[0]?.key ?? '', operator: operatorsFor(fields[0])[0]!.value, value: '' }] }
  const rows: ConditionRow[] = [], joins: string[] = []
  let rest = source.trim()
  const comparison = /^([a-zA-Z][a-zA-Z0-9_.]{0,63})\s+(NOT_EXISTS|EXISTS|==|!=|>=|<=|>|<)(?:\s+|$)/i
  while (rest) {
    const match = comparison.exec(rest)
    if (!match) return null
    const field = fields.find(field => field.key === match[1]), operator = match[2]!.toUpperCase()
    if (!field || !operatorsFor(field).some(item => item.value === operator)) return null
    rest = rest.slice(match[0].length)
    let value = ''
    if (!['EXISTS', 'NOT_EXISTS'].includes(operator)) {
      if (!rest) return null
      if (rest[0] === "'" || rest[0] === '"') {
        const end = rest.indexOf(rest[0], 1)
        if (end < 0) return null
        value = rest.slice(1, end); rest = rest.slice(end + 1).trimStart()
      } else {
        const connector = /\s+(AND|OR)\s+/i.exec(rest)
        value = (connector ? rest.slice(0, connector.index) : rest).trimEnd()
        rest = connector ? rest.slice(connector.index).trimStart() : ''
      }
      if (!safeConditionLiteral(value)) return null
      if (field.type === 'NUMBER' && !validDecimal(value)) return null
      if (field.type === 'BOOLEAN' && !['true', 'false'].includes(value)) return null
      if (field.type === 'SELECT' && !field.options?.some(option => option.value === value)) return null
      if (field.type === 'DATE' && (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0, 10) !== value)) return null
    }
    rows.push({ field: field.key, operator, value })
    if (!rest) break
    const connector = /^(AND|OR)\s+/i.exec(rest)
    if (!connector) return null
    joins.push(connector[1]!.toUpperCase()); rest = rest.slice(connector[0].length)
    if (!rest) return null
  }
  if (new Set(joins).size > 1) return null
  return { join: (joins[0] ?? 'AND') as 'AND' | 'OR', rows }
}
/** 对齐各语法版本的字面量限制；旧版不能同时包含两种引号，新版使用转义保留原值。 */
export function safeConditionLiteral(value: string, version = 1): boolean {
  return value.length <= 256 && !value.includes(';') && !value.includes('$' + '{') && !value.includes('#' + '{')
    && (version === 2 || !/[()]/.test(value) && !(value.includes("'") && value.includes('"')))
}
/** 用户明确编辑才生成新表达式，金额保持字符串精度，文本始终引用。 */
export function serializeConditionRows(value: ConditionRows, fields: FormField[], version = 1): string {
  return value.rows.map(row => {
    if (!safeConditionLiteral(row.value, version)) throw new Error('此值包含条件语法不支持的字符，已保留原条件。')
    const field = fields.find(field => field.key === row.field)
    if (field?.type === 'TABLE' && !['EXISTS', 'NOT_EXISTS'].includes(row.operator)) throw new Error('明细仅支持已填写或未填写判断。')
    const prefix = row.field + ' ' + row.operator
    if (['EXISTS', 'NOT_EXISTS'].includes(row.operator)) return prefix
    if (row.operator === 'IN') {
      if (version !== 2 || field?.type !== 'SELECT') throw new Error('属于判断只支持新版条件中的单选字段。')
      const selected = row.values ?? []
      if (selected.length > 50 || selected.some(item => !safeConditionLiteral(item, 2) || !field.options?.some(option => option.value === item))) throw new Error('请选择字段中的有效选项。')
      return prefix + ' [' + selected.map(item => JSON.stringify(item)).join(', ') + ']'
    }
    if (version === 2) return prefix + ' ' + JSON.stringify(row.value)
    const quote = row.value.includes("'") ? '"' : "'"
    const literal = (field?.type === 'NUMBER' && /^-?\d+(\.\d+)?$/.test(row.value)) || (field?.type === 'BOOLEAN' && ['true', 'false'].includes(row.value))
      ? row.value : quote + row.value + quote
    return prefix + ' ' + literal
  }).join(' ' + value.join + ' ')
}

/** 新语法只回显平面且/或列表，显式括号和取反仍保留为表达式。 */
function parseVersionTwo(source: string, fields: FormField[]): ConditionRows | null {
  const parsed = parseConditionExpression(source)
  if (!parsed) return null
  const terms = parsed.kind === 'comparison' ? [parsed] : parsed.kind === 'AND' || parsed.kind === 'OR' ? parsed.terms : []
  if (!terms.length || terms.some(term => term.kind !== 'comparison' || !validConditionRow(term.row, fields, 2))) return null
  return { join: parsed.kind === 'OR' ? 'OR' : 'AND', rows: terms.map(term => (term as { kind: 'comparison'; row: ConditionRow }).row) }
}

/** 回显与中文说明共用字段类型约束，服务端仍负责最终合法性。 */
export function validConditionRow(row: ConditionRow, fields: FormField[], version: number): boolean {
  const field = fields.find(field => field.key === row.field)
  if (!field || !operatorsFor(field, version).some(operator => operator.value === row.operator)) return false
  if (['EXISTS', 'NOT_EXISTS'].includes(row.operator)) return true
  if (row.operator === 'IN') return !!row.values?.length && row.values.length <= 50
    && row.values.every(value => safeConditionLiteral(value, version) && field.options?.some(option => option.value === value))
  if (!safeConditionLiteral(row.value, version)) return false
  if (field.type === 'NUMBER') return validDecimal(row.value)
  if (field.type === 'BOOLEAN') return ['true', 'false'].includes(row.value)
  if (field.type === 'SELECT') return !!field.options?.some(option => option.value === row.value)
  if (field.type === 'DATE') return /^\d{4}-\d{2}-\d{2}$/.test(row.value) && Number.isFinite(Date.parse(row.value)) && new Date(row.value).toISOString().slice(0, 10) === row.value
  return true
}
