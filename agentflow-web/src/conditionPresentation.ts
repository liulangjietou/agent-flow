import type { GraphEdge } from './api'
import type { FormField } from './formSchema'
import { conditionOperators, parseConditionRows, validConditionRow, type ConditionRow } from './conditionBuilder.js'
import { parseConditionExpression, type ConditionExpression } from './conditionSyntax.js'

/** 面向流程设计者的只读说明；不能完整解释时返回原文，不修改或执行条件。 */
export function describeCondition(source: string, fields: FormField[], version = 1): string {
  if (!source.trim()) return source
  const ast = version === 2 ? parseConditionExpression(source) : version === 1 ? legacyExpression(source, fields) : null
  if (!ast) return source
  const description = describe(ast, fields, version)
  return description ?? source
}

function describe(ast: ConditionExpression, fields: FormField[], version: number): string | null {
  if (ast.kind === 'comparison') return describeRow(ast.row, fields, version)
  if (ast.kind === 'group' || ast.kind === 'not') {
    const text = describe(ast.term, fields, version)
    if (text === null) return null
    return ast.kind === 'not' ? `不满足：${ast.term.kind === 'group' ? text : bracket(text)}` : bracket(text)
  }
  const parts = ast.terms.map(term => {
    const text = describe(term, fields, version)
    return text === null ? null : term.kind === 'AND' || term.kind === 'OR' ? bracket(text) : text
  })
  return parts.some(part => part === null) ? null : parts.join(ast.kind === 'AND' ? ' 且 ' : ' 或 ')
}

function bracket(text: string) { return `（${text}）` }
function describeRow(row: ConditionRow, fields: FormField[], version: number): string | null {
  if (!validConditionRow(row, fields, version)) return null
  const field = fields.find(field => field.key === row.field)!
  const prefix = field.label + ' ' + (row.operator === 'IN' ? '属于' : conditionOperators.find(operator => operator.value === row.operator)!.label)
  if (['EXISTS', 'NOT_EXISTS'].includes(row.operator)) return prefix
  const valueLabel = (value: string) => field.type === 'BOOLEAN' ? value === 'true' ? '是' : '否'
    : field.type === 'SELECT' ? JSON.stringify(field.options!.find(option => option.value === value)!.label)
    : field.type === 'NUMBER' || field.type === 'DATE' ? value : JSON.stringify(value)
  return prefix + (row.operator === 'IN' ? `【${row.values!.map(valueLabel).join('、')}】` : ' ' + valueLabel(row.value))
}

/** v1 的引号无转义，且无引号文本可以包含撇号与 &&，不能按 v2 重新解释。 */
function legacyExpression(source: string, fields: FormField[]): ConditionExpression | null {
  const flat = parseConditionRows(source, fields, 1)
  if (flat) return flat.rows.length === 1 ? { kind: 'comparison', row: flat.rows[0]! }
    : { kind: flat.join, terms: flat.rows.map(row => ({ kind: 'comparison', row })) }
  const parts: string[] = []
  let start = 0, words = 0, inWord = false, quote = ''
  for (let index = 0; index < source.length; index++) {
    const char = source[index]!
    if (quote) { if (char === quote) quote = ''; continue }
    if (/[ \t\n\r\f\v]/.test(char)) { inWord = false; continue }
    const connector = index > 0 && /[ \t\n\r\f\v]/.test(source[index - 1]!) ? /^(AND|OR)(?=[ \t\n\r\f\v])/i.exec(source.slice(index)) : null
    if (connector) {
      if (connector[1]!.toUpperCase() === 'OR') { parts.push(source.slice(start, index)); start = index + 2 }
      words = 0; inWord = false; index += connector[1]!.length - 1
    } else if (!inWord) {
      words++; inWord = true
      if (words === 3 && (char === '"' || char === "'")) quote = char
    }
  }
  if (quote || !parts.length) return null
  parts.push(source.slice(start))
  const terms = parts.map(part => {
    const parsed = parseConditionRows(part, fields, 1)
    if (!parsed) return null
    const rows: ConditionExpression[] = parsed.rows.map(row => ({ kind: 'comparison', row }))
    return rows.length === 1 ? rows[0]! : { kind: parsed.join, terms: rows } as ConditionExpression
  })
  return terms.some(term => term === null) ? null : { kind: 'OR', terms: terms as ConditionExpression[] }
}

/** 默认分支独立说明，不解释其未生效的条件文本。 */
export function describeBranch(edge: GraphEdge, fields: FormField[], version = 1): string {
  return edge.defaultBranch ? '其他条件均不满足' : describeCondition(edge.condition, fields, version)
}

/** 悬停同时显示业务说明和精确原文，方便核对字段标识、选项值与优先级。 */
export function branchTooltip(edge: GraphEdge, fields: FormField[], version = 1): string {
  const text = describeBranch(edge, fields, version)
  return edge.defaultBranch || text === edge.condition ? text : `${text}\n原始表达式：${edge.condition}`
}

/** 插入结果包含 UTF-16 光标位置，与 textarea 的选区 API 一致。@author owlzhangfq@gmail.com */
export interface ConditionInsertion { value: string; caret: number }

/** 仅替换明确选区，保留周边原文；引号内拒绝插入字段，避免把字段误当作字面量。 */
export function insertConditionField(source: string, field: string, start: number, end: number, version = 1): ConditionInsertion {
  if (!/^[a-zA-Z][a-zA-Z0-9_.]{0,63}$/.test(field)) throw new Error('此字段标识不可用于条件。')
  const from = Math.max(0, Math.min(source.length, start)), to = Math.max(from, Math.min(source.length, end))
  if (insideQuotedLiteral(source, from, version) || insideQuotedLiteral(source, to, version)) {
    throw new Error('请将光标或选区移到引号外，再插入字段。')
  }
  const before = source.slice(0, from), after = source.slice(to)
  const left = before && !/[\s(!]$/.test(before) ? ' ' : ''
  const right = after && !/^[\s)]/.test(after) ? ' ' : ''
  const inserted = left + field + right
  const value = before + inserted + after
  if (version === 2 && value.length > 4000) throw new Error('插入后超过 4000 字符，请先精简条件。')
  return { value, caret: before.length + inserted.length }
}

/** v1 仅在比较式第三个词的开头识别引用，正文里的 O'Reilly 不是引号起点。 */
function insideQuotedLiteral(source: string, position: number, version: number): boolean {
  let quote = '', words = 0, inWord = false
  for (let index = 0; index < position; index++) {
    const char = source[index]!
    if (quote) {
      if (version === 2 && char === '\\') index++
      else if (char === quote) quote = ''
    } else if (version === 2) {
      if (char === '\"' || char === "'") quote = char
    } else if (/[ \t\n\r\f\v]/.test(char)) inWord = false
    else {
      const connector = index > 0 && /[ \t\n\r\f\v]/.test(source[index - 1]!) ? /^(AND|OR)(?=[ \t\n\r\f\v])/i.exec(source.slice(index)) : null
      if (connector) { words = 0; inWord = false; index += connector[1]!.length - 1 }
      else if (!inWord) {
        words++; inWord = true
        if (words === 3 && (char === '\"' || char === "'")) quote = char
      }
    }
  }
  return !!quote
}
