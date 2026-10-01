import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'

const { default: Assignee } = await import(process.env.AGENTFLOW_TEST_ASSIGNEE_PANEL)
const { default: TaskActions } = await import(process.env.AGENTFLOW_TEST_TASK_PANEL)
const { render } = await import(process.env.AGENTFLOW_TEST_POLICY_RENDER)
const { approvalPolicyLabel, isCountersignMode } = await import(process.env.AGENTFLOW_TEST_APPROVAL_POLICY)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const { serializePortableTemplate, parsePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const { comparisonValue, comparisonProperty } = await import(process.env.AGENTFLOW_TEST_COMPARISON)
const { simulationIssue } = await import(process.env.AGENTFLOW_TEST_SIMULATION)
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(Component, props, handlers = {}) {
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, ...handlers })
  const mounted = app.mount({})
  return { state: mounted.$.setupState, close: () => app.unmount() }
}
const event = value => ({ target: { value } })

test('真实设计组件保持未填写比例，按一次编辑同时切换策略并清除残留比例', () => {
  const props = reactive({ modelValue: 'role:APPROVER', scopeKey: '', disabled: false, approvalMode: 'SINGLE', approvalPercentage: undefined })
  const events = []
  const p = mount(Assignee, props, { onBeforeChange: () => events.push('before'), onPolicy: (mode, percentage) => {
    events.push([mode, percentage]); props.approvalMode = mode; props.approvalPercentage = percentage
  } })
  try {
    p.state.chooseMode(event('PERCENT'))
    assert.deepEqual(events, ['before', ['PERCENT', undefined]], '不得擅自设置默认比例')
    p.state.choosePercentage(event('67'))
    assert.deepEqual(events.at(-1), ['PERCENT', '67'])
    p.state.chooseMode(event('ANY'))
    assert.deepEqual(events.slice(-2), ['before', ['ANY', undefined]])
    p.state.chooseMode(event('PERCENT'))
    assert.deepEqual(events.at(-1), ['PERCENT', undefined])
    p.state.choosePercentage(event('50.5'))
    assert.deepEqual(events.at(-1), ['PERCENT', '50.5'], '非法输入交由发布校验，不自动取整')
  } finally { p.close() }
})

test('锁定设计器及未知方式不能产生编辑，读取旧非法配置不隐式修正', () => {
  const props = reactive({ modelValue: 'user:finance', scopeKey: '', disabled: true, approvalMode: 'PERCENT', approvalPercentage: 'wrong' })
  const events = [], p = mount(Assignee, props, { onPolicy: (...args) => events.push(args), onBeforeChange: () => events.push('before') })
  try {
    p.state.chooseMode(event('ANY')); p.state.choosePercentage(event('50')); assert.deepEqual(events, [])
    props.disabled = false; p.state.chooseMode(event('FUTURE')); assert.deepEqual(events, [])
    assert.equal(props.approvalPercentage, 'wrong')
  } finally { p.close() }
})

test('画布加载和保存完整保留比例与非法原文，切换模式时可明确移除，旧单人属性不变', () => {
  for (const policy of [{}, { approvalMode: 'ANY' }, { approvalMode: 'PERCENT', approvalPercentage: '67' },
    { approvalMode: 'PERCENT', approvalPercentage: '' }, { approvalMode: 'ALL', approvalPercentage: 'bad' }]) {
    const graph = [{ id: 'review', name: '复核', type: 'USER_TASK', properties: { assigneeRule: 'role:APPROVER', ...policy } }]
    const nodes = loadDesignerNodes(graph)
    assert.deepEqual(serializeDesignerNodes(nodes), graph)
    nodes[0].approvalMode = 'ANY'; nodes[0].approvalPercentage = undefined
    assert.deepEqual(serializeDesignerNodes(nodes)[0].properties, { assigneeRule: 'role:APPROVER', approvalMode: 'ANY' })
    assert.deepEqual(graph[0].properties, { assigneeRule: 'role:APPROVER', ...policy })
  }
})

test('比例模板完整往返且版本比较展示实际策略和比例', () => {
  const value = { key: 'percentage', name: '比例验收', graph: { nodes: [
    { id: 'start', name: '开始', type: 'START', properties: {} },
    { id: 'review', name: '复核', type: 'USER_TASK', properties: { assigneeRule: 'role:APPROVER', approvalMode: 'PERCENT', approvalPercentage: '67' } },
    { id: 'end', name: '结束', type: 'END', properties: {} }
  ], edges: [['start', 'review'], ['review', 'end']].map(([source, target], i) => ({ id: `e${i}`, source, target, condition: '', defaultBranch: false })) }, formSchema: null }
  assert.deepEqual(parsePortableTemplate(serializePortableTemplate(value)), value)
  assert.equal(comparisonValue('ANY', 'properties.approvalMode'), '任一人通过')
  assert.equal(comparisonValue('PERCENT', 'properties.approvalMode'), '按比例会签')
  assert.equal(comparisonProperty('properties.approvalPercentage'), '通过比例（%）')
  assert.match(simulationIssue('APPROVAL_PERCENTAGE_REQUIRED:review').label, /明确填写/)
  assert.equal(simulationIssue('APPROVAL_PERCENTAGE_INVALID:review').target, 'review')
})

test('实际待办组件只为全员会签开放加减签，兼容旧进度响应', () => {
  for (const [countersign, allowed] of [[undefined, false], [{ total: 3, completed: 1 }, true],
    [{ total: 3, completed: 1, mode: 'ALL', required: 3 }, true],
    [{ total: 3, completed: 1, mode: 'PERCENT', percentage: 67, required: 3 }, false],
    [{ total: 3, completed: 0, mode: 'ANY', required: 1 }, false]]) {
    const props = reactive({ task: { taskId: 't', version: 2, allowedActions: ['APPROVE'], countersign }, scopeKey: 'demo:finance', locked: false })
    const p = mount(TaskActions, props)
    try { assert.equal(p.state.membershipAllowed, allowed) } finally { p.close() }
  }
})

test('实际任务模板显示服务端固定门槛，剩余待办结束不误写成全员同意', async () => {
  for (const [mode, percentage, required] of [['ANY', null, 1], ['PERCENT', 50, 2], ['PERCENT', 67, 3]]) {
    const html = await renderToString(createSSRApp({ render, setup: () => ({
      task: { countersign: { mode, percentage, required, total: 3, completed: 0 }, delegationState: 'NONE' },
      approvalPolicyLabel, membershipAllowed: false, membershipOpen: false, delegated: false, pending: null,
      actions: [], error: '', locked: false, actionBar: null
    }) }))
    assert.ok(html.includes(approvalPolicyLabel(mode, percentage)))
    assert.ok(html.includes(`本节点需要 ${required} 人同意`))
    assert.ok(html.includes('不替未处理人员记录同意'))
    assert.ok(!html.includes('查看与增减会签人'))
  }
  assert.equal(isCountersignMode('SINGLE'), false)
  assert.equal(isCountersignMode('ANY'), true)
  assert.match(approvalPolicyLabel('FUTURE'), /FUTURE/)
})
