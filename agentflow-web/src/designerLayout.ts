import type { GraphEdge } from './api'
import type { DesignerNode } from './designerGraph'

export const MIN_ZOOM = 0.5
export const MAX_ZOOM = 1.6
export const ZOOM_STEP = 0.1
export const CANVAS_PADDING = 36
const GRID = 9
const ROW_SPACING = 120
const COLUMN_GAP = 64
const ROUTE_CLEARANCE = 24
const MAX_LABEL_LENGTH = 28

/** 画布逻辑坐标，不是缩放后的屏幕坐标。@author owlzhangfq@gmail.com */
export interface Point { x: number; y: number }
/** 渲染节点的确定尺寸，须与画布节点样式保持一致。@author owlzhangfq@gmail.com */
export interface NodeRectangle extends Point { width: number; height: number }
/** 由节点和连线推导的视图边界。@author owlzhangfq@gmail.com */
export interface GraphBounds { left: number; top: number; right: number; bottom: number }
/** 连线显示结果，始终保留原连线对象及业务条件。@author owlzhangfq@gmail.com */
export interface RoutedEdge { edge: GraphEdge; points: Point[]; path: string; label: Point; text: string; fullText: string; obstructed: boolean }
/** 滚动视口的几何信息。@author owlzhangfq@gmail.com */
export interface Viewport { width: number; height: number; left: number; top: number }

/** 所有坐标运算共用同一份节点几何。 */
export function nodeRectangle(node: DesignerNode): NodeRectangle {
  const compact = node.type === 'START' || node.type === 'END'
  return { x: node.x, y: node.y, width: compact ? 67 : 126, height: compact ? 42 : 64 }
}

/** 缩放不进入定义快照，限制在产品约定的 50% 至 160%。 */
export function clampZoom(value: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(value * 100) / 100))
}

/** 改变缩放时保持视口中心的逻辑位置。 */
export function zoomedScroll(view: Viewport, previous: number, next: number): Point {
  return { x: Math.max(0, (view.left + view.width / 2) * next / previous - view.width / 2),
    y: Math.max(0, (view.top + view.height / 2) * next / previous - view.height / 2) }
}

/** 拖动同时考虑缩放和拖动期间的滚动，再做网格吸附。 */
export function draggedPosition(start: Point, screenDelta: Point, scrollDelta: Point, zoom: number): Point {
  return { x: Math.max(12, Math.round((start.x + (screenDelta.x + scrollDelta.x) / zoom) / GRID) * GRID),
    y: Math.max(20, Math.round((start.y + (screenDelta.y + scrollDelta.y) / zoom) / GRID) * GRID) }
}

/** 按最长路径分列，汇合节点始终排在所有前驱之后；回边仅在分层时忽略。 */
export function arrangeNodes(nodes: DesignerNode[], edges: GraphEdge[], labelFor: (edge: GraphEdge) => string = edgeLabel): { nodes: DesignerNode[]; hasCycle: boolean } {
  if (!nodes.length) return { nodes: [], hasCycle: false }
  const byId = new Map(nodes.map(node => [node.id, node]))
  if (byId.size !== nodes.length) throw new Error('存在重复节点标识，请修复后再自动布局。')
  const outgoing = new Map(nodes.map(node => [node.id, [] as GraphEdge[]]))
  const incoming = new Map(nodes.map(node => [node.id, [] as GraphEdge[]]))
  const validEdges = edges.filter(edge => byId.has(edge.source) && byId.has(edge.target))
  validEdges.forEach(edge => outgoing.get(edge.source)!.push(edge))
  const visited = new Map<string, number>()
  const order: string[] = []
  const backEdges = new Set<GraphEdge>()
  // 显式栈避免长流程递归溢出，遍历保留原出线顺序，不改写条件优先级。
  for (const root of [...nodes.filter(node => node.type === 'START'), ...nodes]) {
    if (visited.has(root.id)) continue
    visited.set(root.id, 1); order.push(root.id)
    const stack = [{ id: root.id, next: 0 }]
    while (stack.length) {
      const frame = stack[stack.length - 1]!
      const next = outgoing.get(frame.id)![frame.next++]
      if (!next) { visited.set(frame.id, 2); stack.pop(); continue }
      if (visited.get(next.target) === 1) { backEdges.add(next); continue }
      if (visited.has(next.target)) continue
      visited.set(next.target, 1); order.push(next.target); stack.push({ id: next.target, next: 0 })
    }
  }
  const forward = validEdges.filter(edge => !backEdges.has(edge))
  const degree = new Map(nodes.map(node => [node.id, 0]))
  const layer = new Map(nodes.map(node => [node.id, 0]))
  forward.forEach(edge => { degree.set(edge.target, degree.get(edge.target)! + 1); incoming.get(edge.target)!.push(edge) })
  const queue = order.filter(id => degree.get(id) === 0)
  for (let index = 0; index < queue.length; index++) {
    const id = queue[index]!
    for (const edge of outgoing.get(id)!.filter(edge => !backEdges.has(edge))) {
      layer.set(edge.target, Math.max(layer.get(edge.target)!, layer.get(id)! + 1))
      degree.set(edge.target, degree.get(edge.target)! - 1)
      if (degree.get(edge.target) === 0) queue.push(edge.target)
    }
  }
  const columns: string[][] = []
  order.forEach(id => (columns[layer.get(id)!] ??= []).push(id))
  const row = new Map<string, number>()
  const positions = new Map<string, Point>()
  let left = 40
  columns.forEach(ids => {
    const wanted = new Map(ids.map((id, index) => {
      const predecessors = incoming.get(id)!.map(edge => row.get(edge.source)!)
      return [id, predecessors.length ? Math.min(...predecessors) : index]
    }))
    ids.sort((a, b) => wanted.get(a)! - wanted.get(b)!)
    let previous = -1
    const width = Math.max(...ids.map(id => nodeRectangle(byId.get(id)!).width))
    for (const id of ids) {
      const nextRow = Math.max(wanted.get(id)!, previous + 1)
      row.set(id, nextRow); previous = nextRow
      const rectangle = nodeRectangle(byId.get(id)!)
      positions.set(id, { x: Math.round(left + (width - rectangle.width) / 2), y: 84 + ROW_SPACING * nextRow - rectangle.height / 2 })
    }
    const labels = ids.flatMap(id => outgoing.get(id)!.map(edge => shortLabel(labelFor(edge)).length * 10 + 32))
    left += width + Math.max(COLUMN_GAP, ...labels)
  })
  return { nodes: nodes.map(node => ({ ...node, ...positions.get(node.id)! })), hasCycle: backEdges.size > 0 }
}

function edgeLabel(edge: GraphEdge): string { return edge.defaultBranch ? '默认分支' : edge.condition }
function shortLabel(text: string): string { return text.length > MAX_LABEL_LENGTH ? text.slice(0, MAX_LABEL_LENGTH - 1) + '…' : text }

/** 正交线段是否穿过节点内部；端口恰好落在边界上不算穿越。 */
export function crossesNode(points: Point[], rectangle: NodeRectangle): boolean {
  return points.slice(1).some((point, index) => {
    const previous = points[index]!
    return point.x === previous.x
      ? point.x > rectangle.x && point.x < rectangle.x + rectangle.width
        && Math.max(point.y, previous.y) > rectangle.y && Math.min(point.y, previous.y) < rectangle.y + rectangle.height
      : point.y > rectangle.y && point.y < rectangle.y + rectangle.height
        && Math.max(point.x, previous.x) > rectangle.x && Math.min(point.x, previous.x) < rectangle.x + rectangle.width
  })
}

function compactPoints(points: Point[]): Point[] {
  const result: Point[] = []
  for (const point of points) {
    const previous = result[result.length - 1]
    if (previous?.x === point.x && previous.y === point.y) continue
    const before = result[result.length - 2]
    if (before && previous && ((before.x === previous.x && previous.x === point.x) || (before.y === previous.y && previous.y === point.y))) result.pop()
    result.push(point)
  }
  return result
}
function pathLength(points: Point[]): number {
  return points.slice(1).reduce((sum, point, index) => sum + Math.abs(point.x - points[index]!.x) + Math.abs(point.y - points[index]!.y), 0)
}

/** 从当前坐标重算连线；重新加载或手动移动后无需持久化另一份连线语义。 */
export function routeEdges(nodes: DesignerNode[], edges: GraphEdge[], labelFor: (edge: GraphEdge) => string = edgeLabel): RoutedEdge[] {
  const rectangles = new Map(nodes.map(node => [node.id, nodeRectangle(node)]))
  const all = [...rectangles.values()]
  return edges.flatMap(edge => {
    const source = rectangles.get(edge.source), target = rectangles.get(edge.target)
    if (!source || !target) return []
    const start = { x: source.x + source.width, y: source.y + source.height / 2 }
    const end = { x: target.x, y: target.y + target.height / 2 }
    const middle = (start.x + end.x) / 2
    const direct = [start, { x: middle, y: start.y }, { x: middle, y: end.y }, end]
    const startX = start.x + ROUTE_CLEARANCE, endX = Math.max(4, end.x - ROUTE_CLEARANCE)
    const lanes = new Set([start.y, end.y, ...all.flatMap(rectangle => [Math.max(4, rectangle.y - ROUTE_CLEARANCE), rectangle.y + rectangle.height + ROUTE_CLEARANCE])])
    const directClear = !all.some(rectangle => crossesNode(direct, rectangle))
    const candidates = directClear ? [direct] : [...lanes].map(y => [start, { x: startX, y: start.y }, { x: startX, y }, { x: endX, y }, { x: endX, y: end.y }, end])
    const clear = candidates.filter(points => !all.some(rectangle => crossesNode(points, rectangle)))
    clear.sort((a, b) => pathLength(a) - pathLength(b))
    const points = compactPoints(clear[0] ?? direct)
    const horizontal = points.slice(1).map((point, index) => [points[index]!, point] as const)
      .filter(([a, b]) => a.y === b.y).sort((a, b) => Math.abs(b[0].x - b[1].x) - Math.abs(a[0].x - a[1].x))[0]
    const text = shortLabel(labelFor(edge))
    const label = horizontal ? { x: Math.max(text.length * 5 + 6, (horizontal[0].x + horizontal[1].x) / 2), y: Math.max(14, horizontal[0].y - 9) } : { x: middle, y: Math.max(14, start.y - 9) }
    return [{ edge, points, path: points.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '), label, text,
      fullText: labelFor(edge), obstructed: !clear.length }]
  })
}

/** 画布随节点、回边和条件标签扩展；不再截断固定尺寸之外的流程。 */
export function graphBounds(nodes: DesignerNode[], routes: RoutedEdge[]): GraphBounds {
  const points = nodes.flatMap(node => { const rectangle = nodeRectangle(node); return [{ x: rectangle.x, y: rectangle.y }, { x: rectangle.x + rectangle.width, y: rectangle.y + rectangle.height }] })
  routes.forEach(route => {
    points.push(...route.points)
    if (route.text) points.push({ x: route.label.x - route.text.length * 5, y: route.label.y - 12 }, { x: route.label.x + route.text.length * 5, y: route.label.y + 3 })
  })
  if (!points.length) return { left: 0, top: 0, right: 0, bottom: 0 }
  return { left: Math.min(...points.map(point => point.x)), top: Math.min(...points.map(point => point.y)),
    right: Math.max(...points.map(point => point.x)), bottom: Math.max(...points.map(point => point.y)) }
}

/** 适应只计算视图；达到最小比例时保留滚动，不隐藏超大流程。 */
export function fittedViewport(bounds: GraphBounds, width: number, height: number): { zoom: number; left: number; top: number; clipped: boolean } {
  const contentWidth = Math.max(1, bounds.right - bounds.left), contentHeight = Math.max(1, bounds.bottom - bounds.top)
  const candidate = Math.min(1.2, (width - 2 * CANVAS_PADDING) / contentWidth, (height - 2 * CANVAS_PADDING) / contentHeight)
  const zoom = clampZoom(Math.floor(candidate * 100) / 100)
  return { zoom, left: Math.max(0, bounds.left * zoom - CANVAS_PADDING), top: Math.max(0, bounds.top * zoom - CANVAS_PADDING), clipped: candidate < MIN_ZOOM }
}
