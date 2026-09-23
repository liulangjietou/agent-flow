import type { GraphNode } from './api'

/**
 * 画布节点保留来源属性和加载时展示位置，以区分真实修改与展示默认值。
 * @author owlzhangfq@gmail.com
 */
export interface DesignerNode {
  id: string; name: string; type: string; x: number; y: number; assigneeRule: string
  approvalMode?: string
  originalProperties?: Record<string, string>
  loadedPosition?: { x: number; y: number }
}

const DEFAULT_NODE_X = 40
const DEFAULT_NODE_Y = 180
const DEFAULT_NODE_SPACING = 180

/** 保留完整来源配置；没有持久化坐标时只在画布里补充展示位置。 */
export function loadDesignerNodes(nodes: GraphNode[]): DesignerNode[] {
  return nodes.map((node, index) => {
    const rawX = Number(node.properties.x ?? DEFAULT_NODE_X + index * DEFAULT_NODE_SPACING)
    const rawY = Number(node.properties.y ?? DEFAULT_NODE_Y)
    const x = Number.isFinite(rawX) && rawX >= 0 ? rawX : DEFAULT_NODE_X + index * DEFAULT_NODE_SPACING
    const y = Number.isFinite(rawY) && rawY >= 0 ? rawY : DEFAULT_NODE_Y
    return { id: node.id, name: node.name, type: node.type, x, y, assigneeRule: node.properties.assigneeRule ?? '',
      approvalMode: node.properties.approvalMode ?? 'SINGLE', originalProperties: { ...node.properties }, loadedPosition: { x, y } }
  })
}

/** 将画布节点转换为发布、保存、校验、模拟和比较共用的配置快照。 */
export function serializeDesignerNodes(nodes: DesignerNode[]): GraphNode[] {
  return nodes.map(node => {
    const properties = { ...node.originalProperties }
    if (node.type === 'USER_TASK') {
      if (node.assigneeRule) properties.assigneeRule = node.assigneeRule
      else delete properties.assigneeRule
      // 未修改的旧节点保留原属性，不能因展示默认值而制造版本差异。
      if (node.approvalMode && (node.approvalMode !== 'SINGLE' || properties.approvalMode !== undefined)) {
        properties.approvalMode = node.approvalMode
      }
    }
    for (const axis of ['x', 'y'] as const) {
      if (!node.loadedPosition || node[axis] !== node.loadedPosition[axis]) properties[axis] = String(node[axis])
    }
    return { id: node.id, name: node.name, type: node.type, properties }
  })
}
