import { operatorsFor, serializeConditionRows, validConditionRow, type ConditionRow } from './conditionBuilder.js'
import { parseConditionExpression, type ConditionExpression } from './conditionSyntax.js'
import type { FormField } from './formSchema'

/** 可视化分组只承载编辑状态，不执行条件；标识不写入流程定义。@author owlzhangfq@gmail.com */
export interface ConditionGroup { kind: 'group'; id: string; join: 'AND' | 'OR'; negated: boolean; children: ConditionTerm[] }
/** 比较取值始终保留字符串，取反作用于完整比较结果。@author owlzhangfq@gmail.com */
export interface ConditionRule { kind: 'rule'; id: string; negated: boolean; row: ConditionRow }
/** 递归编辑项，共用语法版本二现有比较运算。@author owlzhangfq@gmail.com */
export type ConditionTerm = ConditionGroup | ConditionRule
/** 一次显式 UI 操作对应一次不可变编辑，不保存第二套业务规则。@author owlzhangfq@gmail.com */
export type ConditionGroupEdit = { kind: 'row'; id: string; row: ConditionRow }
  | { kind: 'join'; id: string; join: 'AND' | 'OR' }
  | { kind: 'negate'; id: string; negated: boolean }
  | { kind: 'addRule' | 'addGroup' | 'remove'; id: string }
const MAX_COMPARISONS = 100, MAX_DEPTH = 16, MAX_LENGTH = 4000

/** 无效字段、旧语法或未完整识别的条件只能继续编辑原表达式，不猜测分组。 */
export function parseConditionGroup(source: string, fields: FormField[], version: number): ConditionGroup | null {
  if (version !== 2 || !fields.length) return null
  if (!source.trim()) return newGroup(fields)
  const parsed = parseConditionExpression(source)
  if (!parsed) return null
  function convert(node: ConditionExpression): ConditionTerm {
    if (node.kind === 'group') {
      const child = convert(node.term)
      return child.kind === 'group' ? child : { kind: 'group', id: crypto.randomUUID(), join: 'AND', negated: false, children: [child] }
    }
    if (node.kind === 'not') { const term = convert(node.term); return { ...term, negated: !term.negated } }
    if (node.kind === 'comparison') {
      if (!validConditionRow(node.row, fields, 2)) throw new Error('Invalid field comparison')
      return { kind: 'rule', id: crypto.randomUUID(), negated: false, row: { ...node.row, ...(node.row.values ? { values: [...node.row.values] } : {}) } }
    }
    return { kind: 'group', id: crypto.randomUUID(), join: node.kind, negated: false, children: node.terms.map(convert) }
  }
  try {
    const root = convert(parsed)
    return root.kind === 'group' ? root : { kind: 'group', id: crypto.randomUUID(), join: 'AND', negated: false, children: [root] }
  } catch { return null }
}

/** 只在显式修改后序列化；子组保留括号，保存重载后仍能识别用户设置的分组。 */
export function serializeConditionGroup(root: ConditionGroup, fields: FormField[]): string {
  let comparisons = 0
  function render(node: ConditionTerm, isRoot = false): { text: string; depth: number } {
    let text: string, depth: number
    if (node.kind === 'rule') {
      if (++comparisons > MAX_COMPARISONS) throw new Error('最多配置 100 个判断条件，原条件已保留。')
      text = serializeConditionRows({ join: 'AND', rows: [node.row] }, fields, 2)
      if (node.negated) text = '!' + text
      depth = node.negated ? 1 : 0
    } else {
      if (!node.children.length) throw new Error('条件组至少保留一个条件。')
      const children = node.children.map(child => render(child))
      text = children.map(child => child.text).join(' ' + node.join + ' ')
      depth = Math.max(...children.map(child => child.depth))
      if (node.negated) { text = '!(' + text + ')'; depth += 2 }
      else if (!isRoot) { text = '(' + text + ')'; depth++ }
    }
    if (depth > MAX_DEPTH) throw new Error('括号与取反合计最多嵌套 16 层，原条件已保留。')
    return { text, depth }
  }
  const source = render(root, true).text
  if (source.length > MAX_LENGTH) throw new Error('条件表达式最多 4000 个字符，原条件已保留。')
  return source
}

/** 只替换命中的项，删除组会同时删除其子项，但不能留下空组。 */
export function editConditionGroup(root: ConditionGroup, edit: ConditionGroupEdit, fields: FormField[]): ConditionGroup {
  let found = false
  function visit(node: ConditionTerm): ConditionTerm {
    if (node.id === edit.id) {
      found = true
      if (edit.kind === 'negate') return { ...node, negated: edit.negated }
      if (edit.kind === 'row' && node.kind === 'rule') return { ...node, row: { ...edit.row, ...(edit.row.values ? { values: [...edit.row.values] } : {}) } }
      if (node.kind === 'group') {
        if (edit.kind === 'join') return { ...node, join: edit.join }
        if (edit.kind === 'addRule' || edit.kind === 'addGroup') return { ...node, children: [...node.children, edit.kind === 'addGroup' ? newGroup(fields) : newRule(fields)] }
      }
      throw new Error('此条件不支持当前操作，原条件已保留。')
    }
    if (node.kind === 'rule') return node
    if (edit.kind === 'remove' && node.children.some(child => child.id === edit.id)) {
      found = true
      if (node.children.length < 2) throw new Error('条件组至少保留一个条件。')
      return { ...node, children: node.children.filter(child => child.id !== edit.id) }
    }
    return { ...node, children: node.children.map(visit) }
  }
  const result = visit(root) as ConditionGroup
  if (!found) throw new Error('当前条件已变化，请重新选择要编辑的条件。')
  // 先验证完整编辑结果的预算，失败不能把半次修改发送给父设计器。
  serializeConditionGroup(result, fields)
  return result
}

function newRule(fields: FormField[]): ConditionRule {
  const field = fields[0]
  if (!field) throw new Error('请先配置表单字段。')
  return { kind: 'rule', id: crypto.randomUUID(), negated: false, row: { field: field.key, operator: operatorsFor(field, 2)[0]!.value, value: '' } }
}
function newGroup(fields: FormField[]): ConditionGroup {
  return { kind: 'group', id: crypto.randomUUID(), join: 'AND', negated: false, children: [newRule(fields)] }
}
