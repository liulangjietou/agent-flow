import type { Graph } from './api'
import { parseConditionExpression, type ConditionExpression } from './conditionSyntax.js'
import { amountMinor } from './expenses.js'
export interface SplitRuleFields { mode: string; windowDays: string; threshold: string; currency: string }
export const SPLIT_GATEWAY = 'AGGREGATE_AMOUNT'
const ruleProperties = { mode: 'expenseSplitRisk', windowDays: 'expenseSplitWindowDays', threshold: 'expenseSplitThreshold', currency: 'expenseSplitCurrency' } as const
/** 两种设计器共用属性读写，只改动明确选择的跨单字段。 */
export function readSplitRule(properties: Record<string, string>): SplitRuleFields {
  return { mode: properties.expenseSplitRisk ?? '', windowDays: properties.expenseSplitWindowDays ?? '', threshold: properties.expenseSplitThreshold ?? '', currency: properties.expenseSplitCurrency ?? '' }
}
/** 清除开始规则只影响四项规则属性，不连带改写其他网关。 */
export function writeSplitRule(properties: Record<string, string>, fields: SplitRuleFields): Record<string, string> {
  const result = { ...properties }
  for (const key of Object.keys(ruleProperties) as Array<keyof SplitRuleFields>) {
    if (!fields.mode || !fields[key]) delete result[ruleProperties[key]]
    else result[ruleProperties[key]] = fields[key]
  }
  return result
}
/** 每次只修改明确选择的网关，保留原属性和未知规则。 */
export function writeSplitGateway(properties: Record<string, string>, value: string | undefined): Record<string, string> {
  const result = { ...properties }
  if (value === undefined) delete result.expenseSplitRouting
  else result.expenseSplitRouting = value
  return result
}
function referencesAmount(expression: ConditionExpression): boolean {
  if (expression.kind === 'comparison') return expression.row.field === 'amount'
  if (expression.kind === 'AND' || expression.kind === 'OR') return expression.terms.some(referencesAmount)
  return 'term' in expression && referencesAmount(expression.term)
}
/** 检查全部财务可达路径；说明用途的解析器只识别字段，不执行条件。 */
export function splitGatewayIssue(graph: Graph, nodeId: string): string | undefined {
  if (graph.nodes.find(node => node.id === nodeId)?.type !== 'EXCLUSIVE_GATEWAY') return '只有条件网关可以使用冻结合计金额。'
  const outgoing = new Map<string, string[]>()
  for (const edge of graph.edges) outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target])
  const pending = graph.nodes.filter(node => ['FINANCE_REVIEW', 'FINANCE_RECHECK'].includes(node.properties.expenseStage ?? '')).map(node => node.id)
  const visited = new Set<string>()
  for (let index = 0; index < pending.length; index++) {
    const id = pending[index]!
    if (visited.has(id)) continue
    if (id === nodeId) return '财务审核或复核之后的网关必须使用本单金额。'
    visited.add(id); pending.push(...(outgoing.get(id) ?? []))
  }
  const usesAmount = graph.edges.filter(edge => edge.source === nodeId).some(edge => {
    const expression = parseConditionExpression(edge.condition)
    return expression !== null && referencesAmount(expression)
  })
  if (!usesAmount) return '至少一条完整有效的出线条件必须引用金额字段 amount。'
}
/** 编辑时允许保留未填写或未知内容，列出问题供明确修正；发布校验由服务端负责。 */
export function splitRuleIssues(graph: Graph, fields: SplitRuleFields): string[] {
  const issues: string[] = [], marked = graph.nodes.filter(node => node.properties.expenseSplitRouting !== undefined)
  const supplied = [fields.windowDays, fields.threshold, fields.currency].filter(value => value !== '').length
  if (!['', 'ENABLED', 'DISABLED'].includes(fields.mode)) issues.push('已有启用状态未知，原值已保留，请明确选择有效状态。')
  if (!fields.mode && (supplied || marked.length)) issues.push('开始节点尚未设置规则状态，请明确配置或清除剩余规则和网关标记。')
  if (fields.mode === 'ENABLED' || supplied) {
    if (!/^[1-9][0-9]{0,2}$/.test(fields.windowDays) || Number(fields.windowDays) > 365) issues.push('滚动窗口请填写 1 至 365 的整数天数。')
    try { if (amountMinor(fields.threshold) <= 0n) issues.push('风险阈值必须大于零。') }
    catch { issues.push('风险阈值请填写大于零的普通十进制金额，最多两位小数。') }
    if (!/^[A-Z]{3}$/.test(fields.currency)) issues.push('请填写明确的三位大写本位币代码，发布时校验系统支持的两位小数币种。')
  }
  for (const node of marked) {
    const issue = node.properties.expenseSplitRouting === SPLIT_GATEWAY ? splitGatewayIssue(graph, node.id) : '已有合计路由标记未知，请明确修正或清除。'
    if (issue) issues.push(`${node.name}：${issue}`)
  }
  if (fields.mode === 'ENABLED' && !marked.some(node => node.properties.expenseSplitRouting === SPLIT_GATEWAY && !splitGatewayIssue(graph, node.id))) {
    issues.push('启用规则至少需要一个财务审核之前、引用 amount 的业务条件网关。')
  }
  return issues
}
