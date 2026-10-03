import type { Graph, GraphNode } from './api'

export const MAX_RESPONSIBILITY_REFERENCES = 20
export const MAX_RESPONSIBILITY_REFERENCE_TEXT = MAX_RESPONSIBILITY_REFERENCES * 128 + MAX_RESPONSIBILITY_REFERENCES - 1

/** 两项规则独立保存，旧配置和非法原文在明确编辑前保持不变。@author owlzhangfq@gmail.com */
export interface ApprovalResponsibilities { excludeApplicant?: string; differentApproverFrom?: string }

/** 读取既有字面量，不把未设置改写为显式关闭。 */
export function readResponsibilities(properties: Record<string, string>): ApprovalResponsibilities {
  return { excludeApplicant: properties.excludeApplicant, differentApproverFrom: properties.differentApproverFrom }
}

/** 只更新职责属性，不改写审批人、期限、财务职责或其他节点属性。 */
export function writeResponsibilities(properties: Record<string, string>, value: ApprovalResponsibilities): Record<string, string> {
  const result = { ...properties }
  for (const key of ['excludeApplicant', 'differentApproverFrom'] as const) {
    if (value[key] === undefined) delete result[key]
    else result[key] = value[key]
  }
  return result
}

/** 与 Java String.strip 的空白边界一致，不隐式修剪或去重引用。 */
function validReference(id: string): boolean {
  const stripped = id.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g, '')
  return id.length > 0 && id.length <= 128 && id === stripped && !id.includes(',')
}

/** 空项、重复项与超限原文要由用户明确清除，不能自动改变规则。 */
export function responsibilityReferenceIssue(raw: string | undefined): string {
  if (raw === undefined) return ''
  const values = raw.split(',')
  return values.length > MAX_RESPONSIBILITY_REFERENCES || new Set(values).size !== values.length || values.some(id => !validReference(id))
    ? '已有前序引用格式无效：最多选择 20 个不同步骤，不能包含空项或首尾空白。请明确清除后重新选择。' : ''
}

/** 只列出沿顺序流位于当前步骤之前的人工节点；发布时仍由服务器验证完整结构。 */
export function priorApprovalNodes(graph: Graph, nodeId: string): GraphNode[] {
  const outgoing = new Map<string, string[]>()
  for (const edge of graph.edges) outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target])
  const reaches = (source: string, target: string) => {
    const pending = [source], seen = new Set<string>()
    while (pending.length) {
      const current = pending.pop()!
      if (current === target) return true
      if (seen.has(current)) continue
      seen.add(current); pending.push(...(outgoing.get(current) ?? []))
    }
    return false
  }
  if (graph.nodes.filter(node => node.id === nodeId && node.type === 'USER_TASK').length !== 1) return []
  return graph.nodes.filter(node => node.type === 'USER_TASK' && node.id !== nodeId && validReference(node.id)
    && graph.nodes.filter(other => other.id === node.id).length === 1 && reaches(node.id, nodeId) && !reaches(nodeId, node.id))
}

/** 模板核对使用步骤名称及标识，不将引用按当前人员目录替换。 */
export function responsibilitySummary(properties: Record<string, string>, graph: Graph): string[] {
  const value = readResponsibilities(properties), result: string[] = []
  if (value.excludeApplicant !== undefined) result.push(value.excludeApplicant === 'true' ? '禁止申请人办理'
    : value.excludeApplicant === 'false' ? '不额外排除申请人' : '申请人限制待修正：' + value.excludeApplicant)
  if (value.differentApproverFrom !== undefined) {
    result.push('排除这些步骤的实际批准人：' + value.differentApproverFrom.split(',').map(id => {
      const node = graph.nodes.find(item => item.id === id)
      return node ? node.name + '（' + id + '）' : (id || '空引用') + '（步骤不存在）'
    }).join('、'))
  }
  return result
}
