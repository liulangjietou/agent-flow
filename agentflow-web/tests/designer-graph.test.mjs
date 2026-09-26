import assert from 'node:assert/strict'
import test from 'node:test'
const { loadDesignerNodes, serializeDesignerNodes, readDesignerDeadline } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const loaded = () => ({ id: 'approve', name: '审批', type: 'USER_TASK', x: 220, y: 180, assigneeRule: 'role:MANAGER',
  originalProperties: { assigneeRule: 'role:MANAGER', businessTag: '保留原属性' }, loadedPosition: { x: 220, y: 180 } })

test('快速模式读取的期限副本与画布一致，编辑或清除不污染原属性及审批人', () => {
  const properties = { ...loaded().originalProperties, deadlineCalendarId: 'e7251050-b46b-40c3-9c4c-cc5d90f85688',
    deadlineCalendarRevision: '1', deadlineWorkingMinutes: '480' }
  const node = loadDesignerNodes([{ id: 'approve', name: '审批', type: 'USER_TASK', properties }])[0]
  const quickDeadline = readDesignerDeadline(properties)
  assert.deepEqual(quickDeadline, node.deadline)
  quickDeadline.workingMinutes = '960'
  assert.equal(properties.deadlineWorkingMinutes, '480')
  assert.equal(node.deadline.workingMinutes, '480')
  node.deadline = quickDeadline
  assert.equal(serializeDesignerNodes([node])[0].properties.deadlineWorkingMinutes, '960')
  node.deadline = undefined
  assert.deepEqual(serializeDesignerNodes([node])[0].properties, loaded().originalProperties)
})

test('期限保存明确日历修订，重新加载和撤销快照不改变引用', () => {
  const nodes = loadDesignerNodes([{ id: 'approve', name: '审批', type: 'USER_TASK', properties: loaded().originalProperties }])
  assert.equal(nodes[0].deadline, undefined)
  nodes[0].deadline = { calendarId: 'e7251050-b46b-40c3-9c4c-cc5d90f85688', calendarRevision: '1', workingMinutes: '480' }
  const snapshot = serializeDesignerNodes(nodes)
  assert.deepEqual(loadDesignerNodes(snapshot)[0].deadline, nodes[0].deadline)
  nodes[0].deadline.calendarRevision = '2'
  assert.equal(loadDesignerNodes(snapshot)[0].deadline.calendarRevision, '1')
  nodes[0].deadline = undefined
  assert.deepEqual(serializeDesignerNodes(nodes)[0].properties, loaded().originalProperties)
})

test('未编辑的缺项、非法期限和错误节点类型原样往返，不能静默当成无期限', () => {
  for (const type of ['USER_TASK', 'START']) for (const deadline of [
    { deadlineCalendarId: '' },
    { deadlineCalendarId: 'missing', deadlineCalendarRevision: 'latest', deadlineWorkingMinutes: '0' }
  ]) {
    const graph = [{ id: 'legacy', name: '旧配置', type, properties: { ...deadline, businessTag: '保留' } }]
    assert.deepEqual(serializeDesignerNodes(loadDesignerNodes(graph)), graph)
  }
})

test('会签方式可保存、重新加载和切回单人审批，旧节点保持缺省属性', () => {
  const graph = [{ id: 'approve', name: '审批', type: 'USER_TASK', properties: loaded().originalProperties }]
  const nodes = loadDesignerNodes(graph)
  assert.equal(nodes[0].approvalMode, 'SINGLE')
  assert.deepEqual(serializeDesignerNodes(nodes), graph)
  nodes[0].approvalMode = 'ALL'
  const reloaded = loadDesignerNodes(serializeDesignerNodes(nodes))
  assert.equal(reloaded[0].approvalMode, 'ALL')
  reloaded[0].approvalMode = 'SINGLE'
  assert.equal(serializeDesignerNodes(reloaded)[0].properties.approvalMode, 'SINGLE')
  assert.equal(serializeDesignerNodes(reloaded)[0].properties.businessTag, '保留原属性')
})

test('旧节点未修改时不写入虚构坐标，保留编辑器未知属性', () => {
  const node = loadDesignerNodes([{ id: 'approve', name: '审批', type: 'USER_TASK', properties: loaded().originalProperties }])[0]
  const result = serializeDesignerNodes([node])[0]
  assert.deepEqual(result.properties, { assigneeRule: 'role:MANAGER', businessTag: '保留原属性' })
  assert.equal(node.originalProperties.businessTag, '保留原属性')
  assert.equal('x' in node.originalProperties, false)
})

test('只持久化实际移动的坐标，未改变的原坐标文本保持不变', () => {
  const node = loaded()
  node.originalProperties.y = '180.00'
  node.x = 300
  assert.deepEqual(serializeDesignerNodes([node])[0].properties, { assigneeRule: 'role:MANAGER', businessTag: '保留原属性', x: '300', y: '180.00' })
  node.x = node.loadedPosition.x
  assert.equal('x' in serializeDesignerNodes([node])[0].properties, false)
})

test('清空审批人规则移除旧值，但保留其他配置；新节点正常保存坐标', () => {
  const node = loaded()
  node.assigneeRule = ''
  assert.deepEqual(serializeDesignerNodes([node])[0].properties, { businessTag: '保留原属性' })
  assert.deepEqual(serializeDesignerNodes([{ id: 'new', name: '新审批', type: 'USER_TASK', x: 40, y: 60, assigneeRule: 'role:FINANCE' }])[0].properties,
    { assigneeRule: 'role:FINANCE', x: '40', y: '60' })
})


test('非法布局坐标采用可展示位置，未移动时仍保留原始属性', () => {
  const original = [{ id: 'bad', name: '审批', type: 'USER_TASK', properties: { x: 'not-a-number', y: 'Infinity', businessTag: '保留' } }]
  const loadedNodes = loadDesignerNodes(original)
  assert.ok(Number.isFinite(loadedNodes[0].x))
  assert.ok(Number.isFinite(loadedNodes[0].y))
  assert.deepEqual(serializeDesignerNodes(loadedNodes), original)
})
