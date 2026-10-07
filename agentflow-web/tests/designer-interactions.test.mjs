import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, computed } from 'vue'
const { createEditor } = await import(process.env.AGENTFLOW_TEST_CANVAS_INTERACTION)
const { render } = await import(process.env.AGENTFLOW_TEST_CANVAS_TEMPLATE)
const node = (id, type = 'USER_TASK', x = 180, y = 90) => ({ id, name: id, type, x, y, assigneeRule: 'user:alice' })
const edge = (id, source, target, condition = '', defaultBranch = false) => ({ id, source, target, condition, defaultBranch })
function editor(graph = [node('start', 'START', 36), node('middle'), node('end', 'END', 360)], links = [edge('incoming', 'start', 'middle'), edge('outgoing', 'middle', 'end')]) {
  const state = Object.fromEntries(Object.entries({ editorLocked: false, canManageDefinitions: true, history: [], future: [], definitionId: 'saved', definitionKey: 'leave', definitionName: '请假', definitionRiskPolicy: null, conditionLanguageVersion: 2, nodes: graph, edges: links, definitionFormSchema: null, definitionNotificationTexts: {}, selectedId: 'middle', selectedEdgeId: '', connectionTarget: '', canvasZoom: 1, page: 'designer', designerMode: 'advanced', notice: '', dragging: null, connectionPreview: null }).map(([key, value]) => [key, ref(value)]))
  state.palette = [{ type: 'USER_TASK', label: '审批' }]
  const canvas = { tagName: 'DIV', inside: true, contains: el => !!el?.inside, scrollLeft: 0, scrollTop: 0, getBoundingClientRect: () => ({ left: 0, top: 0, right: 800, bottom: 600 }) }
  state.canvas = ref(canvas); state.nextTick = async fn => fn?.()
  state.selectedNode = computed(() => state.nodes.value.find(n => n.id === state.selectedId.value) ?? null)
  state.selectedEdge = computed(() => state.edges.value.find(e => e.id === state.selectedEdgeId.value) ?? null)
  return { state, canvas, ...createEditor(state) }
}
function key(key, target = { tagName: 'DIV', inside: true }, changes = {}) {
  return { key, target, repeat: false, metaKey: false, ctrlKey: false, shiftKey: false, altKey: false, isComposing: false, prevented: false, preventDefault() { this.prevented = true }, ...changes }
}
const values = p => ({ nodes: JSON.parse(JSON.stringify(p.state.nodes.value)), edges: JSON.parse(JSON.stringify(p.state.edges.value)) })

test('删除快捷键只在画布焦点内生效，工具栏和可编辑文字保持原图', () => {
  for (const target of [{ tagName: 'BUTTON', inside: false }, { tagName: 'INPUT', inside: true }, { tagName: 'DIV', inside: true, isContentEditable: true }]) {
    const p = editor(), before = values(p), event = key('Delete', target)
    p.keyHandler(event); assert.deepEqual(values(p), before); assert.equal(event.prevented, false)
  }
})
test('删除一进一出步骤接续原条件边及标识，撤销重做保留原图', () => {
  const p = editor([node('gate', 'EXCLUSIVE_GATEWAY'), node('middle'), node('end', 'END')], [edge('incoming', 'gate', 'middle', 'amount > 50'), edge('outgoing', 'middle', 'end')])
  const before = values(p); p.deleteSelected()
  assert.deepEqual(p.state.edges.value, [edge('incoming', 'gate', 'end', 'amount > 50')])
  assert.equal(p.state.history.value.length, 1); p.undo(); assert.deepEqual(values(p), before)
  p.redo(); assert.equal(p.state.nodes.value.some(n => n.id === 'middle'), false)
})
test('有条件的后继不能静默拼接，开始节点不能删除', () => {
  const p = editor(undefined, [edge('in', 'start', 'middle'), edge('out', 'middle', 'end', 'amount > 0')])
  p.deleteSelected(); assert.equal(p.state.edges.value.length, 0)
  p.state.selectedId.value = 'start'; const before = values(p); p.deleteSelected(); assert.deepEqual(values(p), before)
})
test('Ctrl+Y 恢复最近撤销，输入框保留原生撤销行为', () => {
  const p = editor(); p.addNode('USER_TASK'); const changed = values(p); p.undo()
  const event = key('y', undefined, { ctrlKey: true }); p.keyHandler(event)
  assert.deepEqual(values(p), changed); assert.equal(event.prevented, true)
  const input = key('z', { tagName: 'INPUT', inside: true }, { ctrlKey: true }); p.keyHandler(input); assert.equal(input.prevented, false)
})
test('方向键九像素、长按一次撤销、Shift 大步，松键后新操作', () => {
  const p = editor(), before = values(p); p.keyHandler(key('ArrowRight'))
  for (let i = 0; i < 4; i++) p.keyHandler(key('ArrowRight', undefined, { repeat: true }))
  assert.equal(p.state.selectedNode.value.x, 225); assert.equal(p.state.history.value.length, 1)
  p.endCanvasNudge?.(); p.keyHandler(key('ArrowDown', undefined, { shiftKey: true }))
  assert.equal(p.state.selectedNode.value.y, 135); assert.equal(p.state.history.value.length, 2)
  p.undo(); assert.equal(p.state.selectedNode.value.y, 90); p.undo(); assert.deepEqual(values(p), before)
})
test('点击插入接续并右移后继，网关新增分支放在默认分支前', () => {
  const p = editor(); p.addNode('USER_TASK'); const added = p.state.selectedNode.value
  assert.equal(p.state.edges.value.find(e => e.id === 'outgoing').source, added.id)
  assert.ok(p.state.nodes.value.find(n => n.id === 'end').x > added.x)
  const g = editor([node('gate', 'EXCLUSIVE_GATEWAY'), node('end', 'END', 500)], [edge('default', 'gate', 'end', '', true)])
  g.state.selectedId.value = 'gate'; g.addNode('USER_TASK')
  assert.ok(g.state.edges.value.some(e => e.source === 'gate' && e.target === g.state.selectedId.value))
  assert.equal(g.state.edges.value.filter(e => e.source === 'gate').at(-1).id, 'default')
})
test('连线拒绝开始、自环、不存在节点和结束出线，不能只靠下拉框', () => {
  for (const [source, target] of [['middle', 'start'], ['middle', 'middle'], ['middle', 'missing'], ['end', 'middle']]) {
    const p = editor(); p.state.selectedId.value = source; p.state.connectionTarget.value = target
    const before = values(p); p.connectNode(); assert.deepEqual(values(p), before); assert.equal(p.state.history.value.length, 0)
  }
})
test('默认分支唯一且最后，普通分支排序保持原条件', () => {
  const p = editor([node('gate', 'EXCLUSIVE_GATEWAY'), node('a'), node('b'), node('c')], [edge('a', 'gate', 'a', 'amount > 0'), edge('b', 'gate', 'b', 'amount > 20'), edge('c', 'gate', 'c')])
  p.toggleDefault(p.state.edges.value[0]); assert.deepEqual(p.state.edges.value.map(e => e.id), ['b', 'c', 'a'])
  assert.equal(p.state.edges.value[2].condition, ''); assert.equal(p.state.edges.value[2].defaultBranch, true)
  assert.equal(typeof p.moveCanvasBranch, 'function'); p.moveCanvasBranch('c', -1)
  assert.deepEqual(p.state.edges.value.map(e => e.id), ['c', 'b', 'a']); assert.equal(p.state.edges.value[1].condition, 'amount > 20')
})
function all(vnode) { return !vnode || typeof vnode !== 'object' ? [] : [vnode, ...(Array.isArray(vnode.children) ? vnode.children.flatMap(all) : [])] }
test('实际画布端口绑定指针连接，画布收尾绑定真实处理器', () => {
  const ctx = { stageSize: { width: 800, height: 600 }, canvasZoom: 1, routedEdges: [], nodes: [node('middle')], selectedId: 'middle', selectedEdgeId: '', dragging: null, validationNodeIds: [], simulationResult: null, canManageDefinitions: true, editorLocked: false, canvasMessage: '', connectionPreview: null,
    isCountersignMode: () => false, assigneeLabel: () => '本人', moveNode() {}, selectNode() {}, beginConnection() {}, endCanvasNudge() {}, cancelCanvasInteraction() {} }
  const elements = all(render(ctx, [])), port = elements.find(e => String(e.props?.class).includes('port'))
  assert.ok(port); assert.equal(typeof port.props.onPointerdown, 'function')
  const canvas = elements.find(e => e.props?.class === 'canvas')
  assert.equal(typeof canvas.props.onKeyup, 'function'); assert.equal(typeof canvas.props.onFocusout, 'function')
})

test('非网格旧坐标按精确步长微调，未移动轴不能发生漂移', () => {
  const p = editor(); p.state.selectedNode.value.x = 190; p.state.selectedNode.value.y = 181
  p.keyHandler(key('ArrowRight')); assert.equal(p.state.selectedNode.value.x, 199); assert.equal(p.state.selectedNode.value.y, 181)
})

function pointers(p) {
  const listeners = new Map(), targetListeners = new Map(); let captured = false, hit = null
  const target = { setPointerCapture() { captured = true }, hasPointerCapture: () => captured, releasePointerCapture() { captured = false },
    addEventListener: (kind, fn) => targetListeners.set(kind, fn), removeEventListener: kind => targetListeners.delete(kind) }
  const oldWindow = globalThis.window, oldDocument = globalThis.document
  globalThis.window = { addEventListener: (kind, fn) => listeners.set(kind, fn), removeEventListener: kind => listeners.delete(kind) }
  globalThis.document = { elementFromPoint: () => hit }
  return {
    begin(pointerType = 'mouse') { p.beginConnection({ currentTarget: target, button: 0, pointerId: 7, pointerType }, p.state.selectedNode.value) },
    hit(id, inside = true) { const el = { inside, dataset: { nodeId: id } }; hit = { closest: () => el } },
    send(kind, changes = {}) { listeners.get(kind)?.({ pointerId: 7, clientX: 440, clientY: 90, ...changes }) },
    captured: () => captured, listeners,
    close() { p.cancelCanvasInteraction?.(); globalThis.window = oldWindow; globalThis.document = oldDocument }
  }
}
test('鼠标和触屏实际指针流程只在松开时写一次，替换普通出线且可以撤销', () => {
  for (const type of ['mouse', 'touch']) {
    const p = editor(); p.state.nodes.value.push(node('other', 'USER_TASK', 440)); const before = values(p), events = pointers(p)
    try {
      events.begin(type); assert.equal(events.captured(), true); events.send('pointermove')
      assert.deepEqual(values(p), before); assert.ok(p.state.connectionPreview.value)
      events.hit('other'); events.send('pointerup', { pointerId: 99 }); assert.deepEqual(values(p), before)
      events.send('pointerup'); assert.equal(events.captured(), false); assert.equal(events.listeners.size, 0)
      assert.equal(p.state.edges.value.find(e => e.source === 'middle').target, 'other'); assert.equal(p.state.history.value.length, 1)
      assert.equal(p.state.connectionPreview.value, null); p.undo(); assert.deepEqual(values(p), before)
    } finally { events.close() }
  }
})
test('取消、无命中、画布外节点、编辑锁和切换图均不提交半条连线', () => {
  for (const mode of ['cancel', 'blur', 'escape', 'outside', 'no-hit', 'locked', 'readonly', 'graph', 'page']) {
    const p = editor(); p.state.nodes.value.push(node('other')); const before = values(p), events = pointers(p)
    try {
      events.begin('touch'); events.hit('other', mode !== 'outside')
      if (mode === 'no-hit') events.hit(undefined)
      if (mode === 'locked') p.state.editorLocked.value = true
      if (mode === 'readonly') p.state.canManageDefinitions.value = false
      if (mode === 'graph') p.state.nodes.value = structuredClone(before.nodes)
      if (mode === 'page') p.state.page.value = 'workbench'
      if (mode === 'escape') p.keyHandler(key('Escape'))
      else events.send(mode === 'cancel' ? 'pointercancel' : mode === 'blur' ? 'blur' : 'pointerup')
      assert.deepEqual(values(p), before, mode); assert.equal(p.state.history.value.length, 0, mode)
      assert.equal(p.state.connectionPreview.value, null); assert.equal(events.listeners.size, 0)
    } finally { events.close() }
  }
})
test('无编辑权限及锁定时所有图操作保持原图，不污染历史', () => {
  for (const flag of ['editorLocked', 'canManageDefinitions']) {
    const p = editor(), before = values(p); p.state[flag].value = flag === 'editorLocked'
    p.addNode('USER_TASK'); p.deleteSelected(); p.state.connectionTarget.value = 'start'; p.connectNode()
    p.toggleDefault(p.state.edges.value[0]); p.moveCanvasBranch('incoming', 1); p.keyHandler(key('ArrowRight'))
    assert.deepEqual(values(p), before); assert.equal(p.state.history.value.length, 0)
  }
})
test('循环图点击插入有界结束，拖放不重接原路径，历史最多五十步', () => {
  const p = editor(undefined, [edge('in', 'start', 'middle'), edge('out', 'middle', 'end'), edge('loop', 'end', 'middle')])
  p.addNode('USER_TASK'); assert.equal(p.state.nodes.value.length, 4)
  const before = values(p); p.addNode('USER_TASK', { x: 99, y: 99 }); assert.deepEqual(p.state.edges.value, before.edges)
  for (let i = 0; i < 60; i++) p.addNode('USER_TASK', { x: 99, y: 99 })
  assert.equal(p.state.history.value.length, 50)
})
