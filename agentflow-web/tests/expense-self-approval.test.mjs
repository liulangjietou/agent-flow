import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'

const { default: Panel } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSESELFAPPROVALPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSESELFAPPROVALRENDERED)
const { default: Responsibilities } = await import(process.env.AGENTFLOW_TEST_RESPONSIBILITIESPANEL)
const { default: ResponsibilitiesRendered } = await import(process.env.AGENTFLOW_TEST_RESPONSIBILITIESRENDERED)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const { parsePortableTemplate, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const { comparisonValue, comparisonProperty } = await import(process.env.AGENTFLOW_TEST_COMPARISON)
const schema = { schemaVersion: 2, fields: [{ key: 'expenseDetails', label: '费用明细', type: 'TEXT', required: true }] }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const event = value => ({ target: { value } })
function mount(component = Panel, input = {}) {
  const props = reactive({ modelValue: undefined, formSchema: schema, disabled: false, ...input }), events = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, {
    ...props, onBeforeChange: () => events.push('before'), 'onUpdate:modelValue': value => { events.push(value); props.modelValue = value }
  })
  return { props, events, state: app.mount({}).$.setupState, close: () => app.unmount() }
}

test('费用策略须明确选择，每次修改保留一个撤销点，锁定和非法输入不会写入', () => {
  const p = mount()
  try {
    p.state.choose(event('ESCALATE_SUPERVISOR'))
    assert.deepEqual(p.events, ['before', 'ESCALATE_SUPERVISOR'])
    p.state.choose(event('ESCALATE_SUPERVISOR')); p.state.choose(event('SKIP'))
    assert.equal(p.events.length, 2)
    p.props.disabled = true; p.state.choose(event('')); assert.equal(p.events.length, 2)
    p.props.disabled = false; p.state.choose(event(''))
    assert.deepEqual(p.events, ['before', 'ESCALATE_SUPERVISOR', 'before', undefined])
  } finally { p.close() }
})

test('普通表单不显示新增策略，历史异常配置保留原文并允许明确清除', async () => {
  const normal = await renderToString(createSSRApp(Rendered, { formSchema: null, disabled: false }))
  assert.doesNotMatch(normal, /费用自审批处理/)
  const p = mount(Panel, { formSchema: null, modelValue: 'legacy-invalid' })
  try {
    p.state.choose(event('ESCALATE_SUPERVISOR')); assert.equal(p.events.length, 0)
    const html = await renderToString(createSSRApp(Rendered, p.props))
    assert.match(html, /legacy-invalid/); assert.match(html, /修正配置后重新发布/)
    assert.equal(p.props.modelValue, 'legacy-invalid')
    p.state.choose(event('')); assert.deepEqual(p.events, ['before', undefined])
  } finally { p.close() }
})

test('已启用界面明确说明冻结轮次、空上级阻断和财务人工职责', async () => {
  const html = await renderToString(createSSRApp(Rendered, { formSchema: schema, modelValue: 'ESCALATE_SUPERVISOR', disabled: true }))
  for (const text of ['本次任职', '无有效主管则阻断提交', '新轮次生效', '财务签收', '人工办理', '已发布版本']) assert.ok(html.includes(text), text)
  assert.match(html, /<select[^>]*disabled/)
})

test('费用策略的强制排除不能被节点设置关闭，同时保留旧设置和显式引用', async () => {
  const graph = { nodes: [], edges: [] }
  const p = mount(Responsibilities, { modelValue: { excludeApplicant: 'false' }, graph, nodeId: 'review', expensePolicyEnabled: true })
  try {
    p.state.chooseApplicant(event('false')); p.state.chooseApplicant(event('true'))
    assert.deepEqual(p.events, []); assert.deepEqual(p.props.modelValue, { excludeApplicant: 'false' })
    const html = await renderToString(createSSRApp(ResponsibilitiesRendered, p.props))
    assert.match(html, /强制禁止申请人办理/); assert.doesNotMatch(html, /<select/)
    p.props.expensePolicyEnabled = false; p.state.chooseApplicant(event('true'))
    assert.deepEqual(p.events, ['before', { excludeApplicant: 'true' }])
  } finally { p.close() }
})

test('设计视图切换与模板导出保留费用策略及财务职责，版本比较可解释', () => {
  const graph = { nodes: [
    { id: 'start', name: '开始', type: 'START', properties: { expenseSelfApproval: 'ESCALATE_SUPERVISOR' } },
    { id: 'finance', name: '财务', type: 'USER_TASK', properties: { assigneeRule: 'user:finance', expenseStage: 'FINANCE_REVIEW' } },
    { id: 'end', name: '结束', type: 'END', properties: {} }
  ], edges: [{ id: 'a', source: 'start', target: 'finance', condition: '', defaultBranch: false }, { id: 'b', source: 'finance', target: 'end', condition: '', defaultBranch: false }] }
  assert.deepEqual(serializeDesignerNodes(loadDesignerNodes(graph.nodes)), graph.nodes)
  const source = { key: 'expense-example', name: '费用示例', graph, formSchema: schema }
  assert.deepEqual(parsePortableTemplate(serializePortableTemplate(source)), source)
  assert.equal(comparisonProperty('properties.expenseSelfApproval'), '费用自审批处理')
  assert.equal(comparisonValue('ESCALATE_SUPERVISOR', 'properties.expenseSelfApproval'), '申请人转本次任职的直属主管')
})

test('快速和完整设计器接入同一设置，审批轨迹和审计提供具名上溯动作', () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const quick = readFileSync(new URL('../src/components/QuickDesigner.vue', import.meta.url), 'utf8')
  assert.match(app, /@expense-self-approval="patchExpenseSelfApproval"/)
  assert.match(app, /DefinitionExpenseSelfApproval v-if="selectedNode.type === 'START'"/)
  assert.match(quick, /DefinitionExpenseSelfApproval v-if="selected.type === 'START'"/)
  for (const file of ['ApplicationHistory', 'AuditSearch']) {
    assert.match(readFileSync(new URL(`../src/components/${file}.vue`, import.meta.url), 'utf8'), /SELF_APPROVAL_ESCALATED/)
  }
})
