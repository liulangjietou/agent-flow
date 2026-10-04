import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'

const { default: Panel } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSEDUPLICATEAPPROVALPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSEDUPLICATEAPPROVALRENDERED)
const { parsePortableTemplate, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const { comparisonValue, comparisonProperty } = await import(process.env.AGENTFLOW_TEST_COMPARISON)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const schema = { schemaVersion: 2, fields: [{ key: 'expenseDetails', label: '费用明细', type: 'TEXT', required: true }] }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const event = value => ({ target: { value } })
function mount(input = {}) {
  const props = reactive({ modelValue: undefined, selfApproval: 'ESCALATE_SUPERVISOR', formSchema: schema, disabled: false, ...input }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, {
    ...props, onBeforeChange: () => events.push('before'), 'onUpdate:modelValue': value => { events.push(value); props.modelValue = value }
  })
  return { props, events, state: app.mount({}).$.setupState, close: () => app.unmount() }
}

test('明确启用和清除各保留一个撤销点，锁定和未知输入不能改动', () => {
  const p = mount()
  try {
    p.state.choose(event('AUTO_PASS_ADJACENT'))
    assert.deepEqual(p.events, ['before', 'AUTO_PASS_ADJACENT'])
    p.state.choose(event('AUTO_PASS_ADJACENT')); p.state.choose(event('SKIP_ALL'))
    p.props.disabled = true; p.state.choose(event(''))
    assert.equal(p.events.length, 2)
    p.props.disabled = false; p.state.choose(event(''))
    assert.deepEqual(p.events, ['before', 'AUTO_PASS_ADJACENT', 'before', undefined])
  } finally { p.close() }
})

test('未满足费用和职责策略不能新增，依赖被撤销时保留已有配置并允许明确清除', async () => {
  for (const input of [{ formSchema: null }, { selfApproval: undefined }]) {
    const p = mount(input)
    try {
      p.state.choose(event('AUTO_PASS_ADJACENT')); assert.deepEqual(p.events, [])
      p.props.modelValue = 'AUTO_PASS_ADJACENT'
      const html = await renderToString(createSSRApp(Rendered, p.props))
      assert.match(html, /已有配置已保留/)
      assert.match(html, /<option[^>]+value="AUTO_PASS_ADJACENT"[^>]*disabled/)
      assert.equal(p.props.modelValue, 'AUTO_PASS_ADJACENT')
      p.state.choose(event('')); assert.deepEqual(p.events, ['before', undefined])
    } finally { p.close() }
  }
})

test('普通表单隐藏新增入口，历史异常配置保持可见且不被改写', async () => {
  const normal = await renderToString(createSSRApp(Rendered, { formSchema: null, disabled: false }))
  assert.doesNotMatch(normal, /相邻重复审批/)
  const p = mount({ modelValue: 'legacy-invalid', disabled: true })
  try {
    const html = await renderToString(createSSRApp(Rendered, p.props))
    assert.match(html, /legacy-invalid/); assert.match(html, /已有配置已保留/)
    p.state.choose(event('AUTO_PASS_ADJACENT')); p.state.choose(event(''))
    assert.deepEqual(p.events, []); assert.equal(p.props.modelValue, 'legacy-invalid')
  } finally { p.close() }
})

test('启用说明保留财务人工、非相邻及多候选边界，明确审计和旧轮次不变', async () => {
  const html = await renderToString(createSSRApp(Rendered, { formSchema: schema, selfApproval: 'ESCALATE_SUPERVISOR', modelValue: 'AUTO_PASS_ADJACENT', disabled: false }))
  for (const text of ['实际经过的路径', '来源任务和规则版本', '非相邻重复', '多人候选', '多人会签', '财务签收', '始终人工办理', '原审批轮次']) assert.ok(html.includes(text), text)
})

test('相邻重复策略经两种视图和便携模板往返保留，清除一条策略不丢另一条', () => {
  const source = { key: 'expense-example', name: '费用示例', formSchema: schema, graph: {
    nodes: [
      { id: 'start', name: '开始', type: 'START', properties: { expenseSelfApproval: 'ESCALATE_SUPERVISOR', expenseDuplicateApproval: 'AUTO_PASS_ADJACENT' } },
      { id: 'review', name: '业务审批', type: 'USER_TASK', properties: { assigneeRule: 'user:manager' } },
      { id: 'end', name: '结束', type: 'END', properties: {} }
    ], edges: [{ id: 'a', source: 'start', target: 'review', condition: '', defaultBranch: false }, { id: 'b', source: 'review', target: 'end', condition: '', defaultBranch: false }]
  } }
  assert.deepEqual(serializeDesignerNodes(loadDesignerNodes(source.graph.nodes)), source.graph.nodes)
  assert.deepEqual(parsePortableTemplate(serializePortableTemplate(source)), source)
  const editable = loadDesignerNodes(source.graph.nodes)
  editable[0].originalProperties = { ...editable[0].originalProperties }
  delete editable[0].originalProperties.expenseDuplicateApproval
  assert.equal(serializeDesignerNodes(editable)[0].properties.expenseSelfApproval, 'ESCALATE_SUPERVISOR')
  assert.equal(serializeDesignerNodes(editable)[0].properties.expenseDuplicateApproval, undefined)
  assert.equal(source.graph.nodes[0].properties.expenseDuplicateApproval, 'AUTO_PASS_ADJACENT')
})

test('版本比较明确标明自动通过策略及异常值', () => {
  assert.equal(comparisonProperty('properties.expenseDuplicateApproval'), '相邻重复审批')
  assert.equal(comparisonValue('AUTO_PASS_ADJACENT', 'properties.expenseDuplicateApproval'), '自动通过相邻同人业务审批')
  assert.equal(comparisonValue('legacy-unknown', 'expenseDuplicateApproval'), '待修正：legacy-unknown')
})

test('两种设计器连接同一设置并传入依赖，模拟明确不代替实际审批', () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const quick = readFileSync(new URL('../src/components/QuickDesigner.vue', import.meta.url), 'utf8')
  assert.match(app, /@expense-duplicate-approval="patchExpenseDuplicateApproval"/)
  assert.match(app, /DefinitionExpenseDuplicateApproval v-if="selectedNode.type === 'START'"[^\n]+:self-approval="selectedNode.originalProperties\?\.expenseSelfApproval"/)
  assert.match(quick, /DefinitionExpenseDuplicateApproval v-if="selected.type === 'START'"[^\n]+:self-approval="selected.properties.expenseSelfApproval"/)
  const simulation = readFileSync(new URL('../src/components/DefinitionSimulation.vue', import.meta.url), 'utf8')
  assert.match(simulation, /不以模拟路径代替批准事实/)
})
