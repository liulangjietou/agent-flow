import test from 'node:test'
import assert from 'node:assert/strict'
const { arrangeNodes, nodeRectangle, routeEdges, graphBounds, fittedViewport, zoomedScroll, draggedPosition, clampZoom } = await import(process.env.AGENTFLOW_TEST_LAYOUT)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const node = (id, type = 'USER_TASK') => ({ id, name: id, type, x: 40, y: 60, assigneeRule: 'role:MANAGER', originalProperties: { businessTag: '保留', assigneeRule: 'role:MANAGER' }, loadedPosition: { x: 40, y: 60 } })
const edge = (source, target, condition = '', defaultBranch = false) => ({ id: source + '-' + target, source, target, condition, defaultBranch })
const split = () => ({ nodes: [node('start', 'START'), node('gate', 'EXCLUSIVE_GATEWAY'), node('a'), node('b'), node('b2'), node('end', 'END')],
  edges: [edge('start', 'gate'), edge('gate', 'a', 'amount > 100'), edge('gate', 'b', '', true), edge('a', 'end'), edge('b', 'b2'), edge('b2', 'end')] })

test('按最长路径排列不等长分支，汇合在所有来路之后且主路径水平', () => {
  const graph = split(), before = structuredClone(graph)
  const arranged = arrangeNodes(graph.nodes, graph.edges)
  const byId = new Map(arranged.nodes.map(item => [item.id, nodeRectangle(item)]))
  assert.equal(arranged.hasCycle, false)
  for (const item of graph.edges) assert.ok(byId.get(item.target).x > byId.get(item.source).x + byId.get(item.source).width)
  assert.notEqual(byId.get('a').y, byId.get('b').y)
  assert.equal(byId.get('gate').y, byId.get('a').y)
  assert.deepEqual(graph, before, '布局不能修改原数组、原属性和出线优先级')
  assert.deepEqual(arrangeNodes(arranged.nodes, graph.edges).nodes, arranged.nodes, '重复布局稳定')
})

test('长连线和汇合走空通道，不穿过节点；重新加载仍生成相同路线', () => {
  const graph = split(), arranged = arrangeNodes(graph.nodes, graph.edges).nodes
  const routes = routeEdges(arranged, graph.edges)
  assert.equal(routes.length, graph.edges.length)
  for (const route of routes) {
    assert.equal(route.obstructed, false, route.edge.id)
    for (const item of arranged) assert.equal(crosses(route.points, nodeRectangle(item)), false, route.edge.id + ' / ' + item.id)
    for (let i = 1; i < route.points.length; i++) assert.ok(route.points[i].x === route.points[i-1].x || route.points[i].y === route.points[i-1].y)
  }
  const reloaded = loadDesignerNodes(serializeDesignerNodes(arranged))
  assert.deepEqual(routeEdges(reloaded, graph.edges), routes)
})

// 测试独立判定线段与矩形内部相交，避免仅调用被测辅助函数验证自身。
function crosses(points, rectangle) {
  for (let i = 1; i < points.length; i++) {
    const a = points[i-1], b = points[i]
    if (a.x === b.x && a.x > rectangle.x && a.x < rectangle.x + rectangle.width && Math.max(a.y,b.y) > rectangle.y && Math.min(a.y,b.y) < rectangle.y + rectangle.height) return true
    if (a.y === b.y && a.y > rectangle.y && a.y < rectangle.y + rectangle.height && Math.max(a.x,b.x) > rectangle.x && Math.min(a.x,b.x) < rectangle.x + rectangle.width) return true
  }
  return false
}

test('草稿回环和断开的节点也能展示，布局不能删除回边或冒充校验通过', () => {
  const nodes = [node('start','START'),node('a'),node('b'),node('end','END'),node('isolated')]
  const edges = [edge('start','a'),edge('a','b'),edge('b','a'),edge('b','end'),edge('missing','end')]
  const before = structuredClone(edges)
  const layout = arrangeNodes(nodes, edges)
  assert.equal(layout.hasCycle, true)
  assert.equal(layout.nodes.length, nodes.length)
  assert.equal(new Set(layout.nodes.map(item => item.x + ':' + item.y)).size, nodes.length)
  assert.equal(routeEdges(layout.nodes, edges).length, edges.length - 1)
  assert.deepEqual(edges, before)
  assert.deepEqual(arrangeNodes([], []), { nodes: [], hasCycle: false })
  assert.throws(() => arrangeNodes([node('a'),node('a')],[]), /重复节点/)
})

test('自动布局仅改变坐标，节点属性、分支条件和顺序均保留', () => {
  const graph = split(), original = serializeDesignerNodes(graph.nodes)
  const arranged = serializeDesignerNodes(arrangeNodes(graph.nodes, graph.edges).nodes)
  const semantic = list => list.map(item => ({...item, properties: Object.fromEntries(Object.entries(item.properties).filter(([key]) => !['x','y'].includes(key))) }))
  assert.deepEqual(semantic(arranged), semantic(original))
  assert.equal(graph.edges[2].defaultBranch, true)
  assert.equal(graph.edges[1].condition, 'amount > 100')
})

test('长链无递归溢出，动态画布扩展到固定1600像素之外', () => {
  const nodes = Array.from({length:250}, (_, i) => node('n' + i))
  const edges = nodes.slice(1).map((item,i) => edge(nodes[i].id,item.id))
  const layout = arrangeNodes(nodes, edges)
  const bounds = graphBounds(layout.nodes, routeEdges(layout.nodes,edges))
  assert.ok(bounds.right > 1600)
  assert.equal(layout.nodes.length, 250)
  assert.equal(layout.hasCycle, false)
})

test('缩放和适应仅计算视图，偏移很远的图仍居于可见区域', () => {
  const bounds = {left:4000,top:2000,right:4400,bottom:2200}
  const fit = fittedViewport(bounds,600,400)
  assert.equal(fit.clipped,false)
  assert.ok(bounds.left*fit.zoom >= fit.left)
  assert.ok(bounds.right*fit.zoom <= fit.left+600)
  assert.ok(bounds.bottom*fit.zoom <= fit.top+400)
  assert.equal(clampZoom(0.1),0.5)
  assert.equal(clampZoom(3),1.6)
  assert.equal(fittedViewport({left:0,top:0,right:4000,bottom:3000},300,200).clipped,true)
  assert.equal(fittedViewport({left:0,top:0,right:0,bottom:0},600,400).zoom,1.2)
  assert.deepEqual(zoomedScroll({width:400,height:200,left:100,top:50},1,1.5),{x:250,y:125})
})

test('50%和160%下拖动换算为同样逻辑位移，并计入滚动与边界', () => {
  const origin={x:180,y:90}
  assert.deepEqual(draggedPosition(origin,{x:45,y:18},{x:0,y:0},0.5),{x:270,y:126})
  assert.deepEqual(draggedPosition(origin,{x:144,y:57.6},{x:0,y:0},1.6),{x:270,y:126})
  assert.deepEqual(draggedPosition(origin,{x:0,y:0},{x:45,y:18},0.5),{x:270,y:126})
  assert.deepEqual(draggedPosition(origin,{x:-1000,y:-1000},{x:0,y:0},1),{x:12,y:20})
})

test('显示截断长条件但保留全文，图边界包含连线和标签', () => {
  const graph=split(); graph.edges[1].condition='amount > 100 AND description == "'+'很长'.repeat(30)+'"'
  const arranged=arrangeNodes(graph.nodes,graph.edges).nodes
  const routes=routeEdges(arranged,graph.edges), route=routes.find(item=>item.edge.id==='gate-a')
  assert.equal(route.fullText,graph.edges[1].condition)
  assert.ok(route.text.endsWith('…'))
  const bounds=graphBounds(arranged,routes)
  for(const item of routes) for(const point of item.points) assert.ok(point.x>=bounds.left && point.x<=bounds.right && point.y>=bounds.top && point.y<=bounds.bottom)
})
