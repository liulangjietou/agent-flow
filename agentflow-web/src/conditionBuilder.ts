import { validDecimal } from './formSchema.js'
import type { FormField } from './formSchema'
/** 可视化条件只表达同一连接符的比较列表，复杂表达式原文保留。@author owlzhangfq@gmail.com */
export interface ConditionRows { join: 'AND' | 'OR'; rows: ConditionRow[] }
/** 值以字符串保存，避免金额阈值经过 JS 浮点数损失精度。@author owlzhangfq@gmail.com */
export interface ConditionRow { field: string; operator: string; value: string }
export const conditionOperators = [
  { value: '==', label: '等于' }, { value: '!=', label: '不等于' }, { value: '>', label: '大于' },
  { value: '>=', label: '大于等于' }, { value: '<', label: '小于' }, { value: '<=', label: '小于等于' },
  { value: 'EXISTS', label: '已填写' }, { value: 'NOT_EXISTS', label: '未填写' }
]
/** 文本、选项和布尔仅开放其真实支持的运算。 */
export function operatorsFor(field?: FormField) {
  return conditionOperators.filter(operator => ['NUMBER', 'DATE'].includes(field?.type ?? '') || ['==', '!=', 'EXISTS', 'NOT_EXISTS'].includes(operator.value))
}
/** 仅识别可无歧义回显的平面语法；不能识别时绝不重写原条件。 */
export function parseConditionRows(source: string, fields: FormField[]): ConditionRows | null {
  if (!source.trim()) return { join: 'AND', rows: [{ field: fields[0]?.key ?? '', operator: '==', value: '' }] }
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
/** 对齐服务端字面量限制；同时含两种引号无法安全生成，须提示而不是拼接注入条件。 */
export function safeConditionLiteral(value: string): boolean {
  return value.length <= 256 && !/[;()]/.test(value) && !value.includes('${') && !value.includes('#{') && !(value.includes("'") && value.includes('"'))
}
/** 用户明确编辑才生成新表达式，所有文本始终引用，数字保持原字符串。 */
export function serializeConditionRows(value: ConditionRows, fields: FormField[]): string {
  return value.rows.map(row => {
    if (!safeConditionLiteral(row.value)) throw new Error('此值包含条件语法不支持的字符，已保留原条件。')
    if (['EXISTS', 'NOT_EXISTS'].includes(row.operator)) return `${row.field} ${row.operator}`
    const field = fields.find(field => field.key === row.field)
    const literal = (field?.type === 'NUMBER' && /^-?\d+(\.\d+)?$/.test(row.value)) || (field?.type === 'BOOLEAN' && ['true', 'false'].includes(row.value))
      ? row.value : `${row.value.includes("'") ? '"' : "'"}${row.value}${row.value.includes("'") ? '"' : "'"}`
    return `${row.field} ${row.operator} ${literal}`
  }).join(` ${value.join} `)
}
