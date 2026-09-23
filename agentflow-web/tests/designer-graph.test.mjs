import assert from 'node:assert/strict'
import test from 'node:test'
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const loaded = () => ({ id: 'approve', name: '审批', type: 'USER_TASK', x: 220, y: 180, assigneeRule: 'role:MANAGER',
  originalProperties: { assigneeRule: 'role:MANAGER', businessTag: '保留原属性' }, loadedPosition: { x: 220, y: 180 } })

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
