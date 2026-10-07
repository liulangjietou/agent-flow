import type { GraphEdge } from './api'
import type { DesignerNode } from './designerGraph'

export const CANVAS_KEY_STEP = 9
export const CANVAS_LARGE_KEY_STEP = CANVAS_KEY_STEP * 5
const INSERT_SPACING = 180
const branches = (node: DesignerNode) => ['EXCLUSIVE_GATEWAY', 'PARALLEL_GATEWAY'].includes(node.type)

/** 属性连线和指针连线共用同一规则；返回 null 表示原草稿应保持。 */
export function connectCanvasGraph(nodes: DesignerNode[], edges: GraphEdge[], sourceId: string, targetId: string, id: string): GraphEdge[] | null {
  const source = nodes.find(node => node.id === sourceId), target = nodes.find(node => node.id === targetId)
  if (!source || !target || source.type === 'END' || target.type === 'START' || sourceId === targetId
    || edges.some(edge => edge.source === sourceId && edge.target === targetId)) return null
  const result = branches(source) ? [...edges] : edges.filter(edge => edge.source !== sourceId)
  const edge = { id, source: sourceId, target: targetId, condition: '', defaultBranch: false }
  const fallback = result.findIndex(item => item.source === sourceId && item.defaultBranch)
  result.splice(fallback < 0 ? result.length : fallback, 0, edge)
  return result
}

/** 点击插入为后继留出空间；拖放只按指定位置新增，不改写原路径。 */
export function insertCanvasGraph(nodes: DesignerNode[], edges: GraphEdge[], baseId: string, added: DesignerNode, edgeId: string, placed: boolean) {
  const result = { nodes: [...nodes, added], edges: [...edges] }
  const base = nodes.find(node => node.id === baseId)
  if (placed || !base || base.type === 'END') return result
  const outgoing = edges.filter(edge => edge.source === baseId)
  if (branches(base)) {
    result.edges = connectCanvasGraph(result.nodes, edges, baseId, added.id, edgeId) ?? result.edges
    return result
  }
  // 损坏草稿的多出线或结束节点的已有后继必须由用户明确处理，不能猜测删除路径。
  if (outgoing.length > 1 || (added.type === 'END' && outgoing.length)) return result
  const old = outgoing[0]
  if (old) {
    result.edges = result.edges.map(edge => edge.id === old.id ? { ...edge, source: added.id } : edge)
    const reached = new Set<string>([baseId, added.id]), queue = [old.target]
    for (let i = 0; i < queue.length; i++) {
      const id = queue[i]!
      if (reached.has(id)) continue
      reached.add(id)
      queue.push(...edges.filter(edge => edge.source === id).map(edge => edge.target))
    }
    const target = nodes.find(node => node.id === old.target)
    const shift = target ? Math.max(0, added.x + INSERT_SPACING - target.x) : 0
    result.nodes = result.nodes.map(node => reached.has(node.id) && node.id !== baseId && node.id !== added.id ? { ...node, x: node.x + shift } : node)
  }
  result.edges.push({ id: edgeId, source: baseId, target: added.id, condition: '', defaultBranch: false })
  return result
}

/** 一进一出仅在后继无条件且不产生自环/重复时接续，保留进入节点前的原分支依据。 */
export function deleteCanvasNode(nodes: DesignerNode[], edges: GraphEdge[], id: string) {
  const incoming = edges.filter(edge => edge.target === id), outgoing = edges.filter(edge => edge.source === id)
  const result = { nodes: nodes.filter(node => node.id !== id), edges: edges.filter(edge => edge.source !== id && edge.target !== id) }
  const before = incoming[0], after = outgoing[0]
  if (incoming.length === 1 && outgoing.length === 1 && before && after && !after.condition.trim() && !after.defaultBranch
    && before.source !== after.target && before.source !== id && after.target !== id
    && nodes.some(node => node.id === before.source && node.type !== 'END')
    && nodes.some(node => node.id === after.target && node.type !== 'START')
    && !result.edges.some(edge => edge.source === before.source && edge.target === after.target)) {
    // 使用原数组位置，避免改变同一个条件网关其他分支的判断先后。
    result.edges = edges.flatMap(edge => edge.id === before.id ? [{ ...before, target: after.target }]
      : edge.source === id || edge.target === id ? [] : [edge])
  }
  return result
}

/** 只改指定网关的出线顺序，其他边仍留在原数组位置。 */
export function orderCanvasBranches(edges: GraphEdge[], source: string, ordered: GraphEdge[]): GraphEdge[] {
  let index = 0
  return edges.map(edge => edge.source === source ? ordered[index++]! : edge)
}
