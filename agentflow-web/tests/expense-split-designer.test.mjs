import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
const model = await import(process.env.AGENTFLOW_TEST_EXPENSESPLITPOLICY)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSESPLITRISKPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSESPLITRISKRENDERED)
const { parsePortableTemplate, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const { comparisonValue, comparisonProperty } = await import(process.env.AGENTFLOW_TEST_COMPARISON)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const schema = { schemaVersion: 2, fields: [
  { key: 'expenseDetails', label: '费用明细', type: 'TEXT', required: true, sensitive: true },
  { key: 'amount', label: '金额', type: 'NUMBER', required: true }, { key: 'currency', label: '币种', type: 'TEXT', required: true },
  { key: 'overPolicy', label: '超标', type: 'BOOLEAN', required: true }
] }
const fields = () => ({ mode: 'ENABLED', windowDays: '7', threshold: '5000.00', currency: 'CNY' })
const startProperties = () => ({ expenseSplitRisk: 'ENABLED', expenseSplitWindowDays: '7', expenseSplitThreshold: '5000.00', expenseSplitCurrency: 'CNY', expenseSelfApproval: 'ESCALATE_SUPERVISOR', x: '40' })
const node = (id, type, properties = {}) => ({ id, name: id, type, properties })
function graph() { return { nodes: [node('start', 'START', startProperties()), node('business', 'USER_TASK'), node('first', 'EXCLUSIVE_GATEWAY', { expenseSplitRouting: 'AGGREGATE_AMOUNT' }), node('receipt', 'USER_TASK', { expenseStage: 'RECEIPT' }), node('afterReceipt', 'EXCLUSIVE_GATEWAY'), node('finance', 'USER_TASK', { expenseStage: 'FINANCE_REVIEW' }), node('late', 'EXCLUSIVE_GATEWAY'), node('end', 'END')],
  edges: [['start', 'business', ''], ['business', 'first', ''], ['first', 'receipt', 'amount > 5000'], ['receipt', 'afterReceipt', ''], ['afterReceipt', 'finance', 'amount > 5000'], ['finance', 'late', ''], ['late', 'end', 'amount > 10000']].map(([source, target, condition], i) => ({ id: 'e' + i, source, target, condition, defaultBranch: false })) } }
const event = value => ({ target: { value } })
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(input = {}) {
  const props = reactive({ nodeId: 'start', graph: graph(), formSchema: schema, disabled: false, ...input }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, {
    ...props, onBeforeChange: () => events.push('before'), onRule: (id, value) => { events.push(['rule', id, value]); const n = props.graph.nodes.find(n => n.id === id); n.properties = model.writeSplitRule(n.properties, value) },
    onGateway: (id, value) => { events.push(['gateway', id, value]); const n = props.graph.nodes.find(n => n.id === id); n.properties = model.writeSplitGateway(n.properties, value) }
  })
  return { state: app.mount({}).$.setupState, props, events, close: () => app.unmount() }
}

test('配置不补造企业默认值，编辑或清除只影响本次选择的四项策略', () => {
  assert.deepEqual(model.readSplitRule({}), { mode: '', windowDays: '', threshold: '', currency: '' })
  const input = startProperties(); assert.deepEqual(model.readSplitRule(input), fields())
  const changed = model.writeSplitRule(input, { ...fields(), mode: 'DISABLED', windowDays: '30' })
  assert.equal(changed.expenseSplitRisk, 'DISABLED'); assert.equal(changed.expenseSplitWindowDays, '30'); assert.equal(changed.expenseSelfApproval, input.expenseSelfApproval)
  assert.deepEqual(model.writeSplitRule(input, { mode: '', windowDays: '', threshold: '', currency: '' }), { expenseSelfApproval: 'ESCALATE_SUPERVISOR', x: '40' })
  assert.deepEqual(input, startProperties())
})
test('网关独立编辑，规则字段变更不会静默修正另一节点的未知标记', () => {
  const input = { expenseSplitRouting: 'legacy-unknown', x: '50' }
  assert.deepEqual(model.writeSplitGateway(input, undefined), { x: '50' })
  assert.deepEqual(model.writeSplitGateway(input, 'AGGREGATE_AMOUNT'), { expenseSplitRouting: 'AGGREGATE_AMOUNT', x: '50' })
  const unknown = { ...startProperties(), expenseSplitRisk: 'legacy-unknown' }
  assert.equal(model.readSplitRule(unknown).mode, 'legacy-unknown')
  assert.equal(model.writeSplitRule(unknown, { ...model.readSplitRule(unknown), threshold: '6000' }).expenseSplitRisk, 'legacy-unknown')
  assert.equal(input.expenseSplitRouting, 'legacy-unknown')
})
test('所有财务后续路径都禁止合计网关，签收之后仍可配置业务网关', () => {
  const value = graph(); assert.equal(model.splitGatewayIssue(value, 'first'), undefined); assert.equal(model.splitGatewayIssue(value, 'afterReceipt'), undefined)
  assert.match(model.splitGatewayIssue(value, 'late'), /财务/); assert.ok(model.splitGatewayIssue(value, 'business'))
  value.edges.push({ id: 'alternative', source: 'finance', target: 'first', condition: '', defaultBranch: false })
  assert.match(model.splitGatewayIssue(value, 'first'), /财务/)
})
test('amount 必须是真实字段引用，字符串字面量和解析失败不能当作业务金额条件', () => {
  const value = graph(), edge = value.edges.find(edge => edge.source === 'first')
  for (const condition of ['memo == "amount"', 'overPolicy == true', 'amount + unknown']) { edge.condition = condition; assert.match(model.splitGatewayIssue(value, 'first'), /amount/) }
  edge.condition = '!(amount <= 5000) AND (overPolicy == true OR currency == "CNY")'; assert.equal(model.splitGatewayIssue(value, 'first'), undefined)
})
test('启用必须有完整明确规则和业务网关，关闭可清空参数但不接受半套配置', () => {
  assert.deepEqual(model.splitRuleIssues(graph(), fields()), [])
  for (const input of [{ ...fields(), windowDays: '' }, { ...fields(), windowDays: '01' }, { ...fields(), windowDays: '366' }, { ...fields(), threshold: '0' }, { ...fields(), threshold: '1e3' }, { ...fields(), currency: 'cny' }, { ...fields(), mode: 'constructor' }]) assert.ok(model.splitRuleIssues(graph(), input).length)
  const value = graph(); delete value.nodes.find(n => n.id === 'first').properties.expenseSplitRouting; assert.ok(model.splitRuleIssues(value, fields()).length)
  assert.deepEqual(model.splitRuleIssues(value, { mode: 'DISABLED', windowDays: '', threshold: '', currency: '' }), [])
  assert.ok(model.splitRuleIssues(value, { mode: 'DISABLED', windowDays: '7', threshold: '', currency: '' }).length)
})
test('开始节点的明确修改保留一个撤销点，重复事件和锁定事件不写入', () => {
  const p = mount()
  try {
    p.state.chooseMode(event('DISABLED')); assert.deepEqual(p.events, ['before', ['rule', 'start', { ...fields(), mode: 'DISABLED' }]])
    p.state.chooseMode(event('DISABLED')); p.state.chooseMode(event('UNKNOWN')); assert.equal(p.events.length, 2)
    p.state.changeField('threshold', event('9000')); assert.equal(p.props.graph.nodes[0].properties.expenseSplitThreshold, '9000')
    p.props.disabled = true; p.state.chooseMode(event('')); p.state.changeField('currency', event('USD')); assert.equal(p.events.length, 4)
  } finally { p.close() }
})
test('财务后置或无 amount 的网关不能新增标记，但现有错误标记允许明确清除', () => {
  const p = mount({ nodeId: 'late' })
  try {
    p.state.chooseGateway(event('AGGREGATE_AMOUNT')); assert.deepEqual(p.events, [])
    p.props.graph.nodes.find(n => n.id === 'late').properties.expenseSplitRouting = 'AGGREGATE_AMOUNT'
    p.state.chooseGateway(event('')); assert.deepEqual(p.events, ['before', ['gateway', 'late', undefined]])
    p.props.nodeId = 'first'; p.state.chooseGateway(event('')); p.state.chooseGateway(event('AGGREGATE_AMOUNT'))
    assert.equal(p.props.graph.nodes.find(n => n.id === 'first').properties.expenseSplitRouting, 'AGGREGATE_AMOUNT')
  } finally { p.close() }
})
test('未知配置保持可见，普通表单不获得新增跨单规则的入口', async () => {
  const value = graph(); value.nodes[0].properties.expenseSplitRisk = 'legacy-unknown'
  const html = await renderToString(createSSRApp(Rendered, { nodeId: 'start', graph: value, formSchema: schema, disabled: false }))
  assert.match(html, /legacy-unknown/); assert.match(html, /保留/)
  const p = mount({ formSchema: null })
  try { p.state.chooseMode(event('ENABLED')); assert.deepEqual(p.events, []) } finally { p.close() }
})
test('缺少模式的残留参数仍能明确清除', () => {
  const value = graph(); delete value.nodes[0].properties.expenseSplitRisk
  const p = mount({ graph: value })
  try {
    p.state.chooseMode(event(''))
    assert.deepEqual(p.events, ['before', ['rule', 'start', { mode: '', windowDays: '', threshold: '', currency: '' }]])
    assert.equal(p.props.graph.nodes[0].properties.expenseSplitWindowDays, undefined)
  } finally { p.close() }
})
test('开始节点误放的网关标记保持可见并可清除', async () => {
  const invalid = graph(); invalid.nodes[0].properties.expenseSplitRouting = 'misplaced-gateway'
  const html = await renderToString(createSSRApp(Rendered, { nodeId: 'start', graph: invalid, formSchema: schema, disabled: false }))
  assert.match(html, /misplaced-gateway/); assert.match(html, /金额依据/)
  const p = mount({ graph: invalid })
  try { p.state.chooseGateway(event('')); assert.equal(p.props.graph.nodes[0].properties.expenseSplitRouting, undefined) }
  finally { p.close() }
})
test('五个属性在两种视图及便携模板中完整往返，未知额外属性仍被拒绝', () => {
  const value = { key: 'expense-test', name: '合成费用', graph: graph(), formSchema: schema }
  assert.deepEqual(serializeDesignerNodes(loadDesignerNodes(value.graph.nodes)), value.graph.nodes)
  assert.deepEqual(parsePortableTemplate(serializePortableTemplate(value)), value)
  value.graph.nodes[0].properties.expenseSplitSecret = 'hidden'; assert.throws(() => serializePortableTemplate(value))
})
test('版本差异显示规则含义，未知模式保留原文并提示修正', () => {
  for (const key of ['expenseSplitRisk', 'expenseSplitWindowDays', 'expenseSplitThreshold', 'expenseSplitCurrency', 'expenseSplitRouting']) assert.notEqual(comparisonProperty('properties.' + key), 'properties.' + key)
  assert.equal(comparisonValue('DISABLED', 'properties.expenseSplitRisk'), '关闭跨单规则')
  assert.equal(comparisonValue('AGGREGATE_AMOUNT', 'properties.expenseSplitRouting'), '使用冻结跨单路由金额')
  assert.equal(comparisonValue('legacy-unknown', 'expenseSplitRisk'), '待修正：legacy-unknown')
})
test('两种设计器都接到同一规则组件和写入处理器', () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8'), quick = readFileSync(new URL('../src/components/QuickDesigner.vue', import.meta.url), 'utf8')
  for (const source of [app, quick]) assert.match(source, /<DefinitionExpenseSplitRisk/)
  assert.match(app, /@expense-split-rule="patchExpenseSplitRule"/); assert.match(app, /@expense-split-gateway="patchExpenseSplitGateway"/)
})
