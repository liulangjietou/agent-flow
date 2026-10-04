import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_DEFINITIONSIMULATIONPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_DEFINITIONSIMULATIONRENDERED)
const { writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
globalThis.localStorage = { getItem: () => 'synthetic-simulation-token' }
writeRequests.setActor({ tenantId: 'demo', userId: 'admin' })
const schema = { schemaVersion: 2, fields: [
  { key: 'expenseDetails', label: '费用明细', type: 'TEXT', required: true, sensitive: true },
  { key: 'amount', label: '金额', type: 'NUMBER', required: true }, { key: 'currency', label: '币种', type: 'TEXT', required: true },
  { key: 'overPolicy', label: '超标', type: 'BOOLEAN', required: true }
] }
const graph = (mode = 'ENABLED') => ({ nodes: [
  { id: 'start', type: 'START', name: '开始', properties: { expenseSplitRisk: mode, expenseSplitWindowDays: '7', expenseSplitThreshold: '5000', expenseSplitCurrency: 'CNY' } },
  { id: 'business', type: 'EXCLUSIVE_GATEWAY', name: '业务判断', properties: { expenseSplitRouting: 'AGGREGATE_AMOUNT' } },
  { id: 'financial', type: 'EXCLUSIVE_GATEWAY', name: '财务复核判断', properties: {} }
], edges: [] })
const result = () => ({ path: ['start'], edgeIds: [], decisions: [] })
const values = () => ({ expenseDetails: '合成模拟', amount: '3000.00', currency: 'CNY', overPolicy: false })
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(input = {}) {
  const props = reactive({ graph: graph(), formSchema: schema, scopeKey: 'demo/admin', locked: false, ...input }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, { ...props, onResult: value => events.push(value) })
  const state = app.mount({}).$.setupState; state.values = values()
  return { state, props, events, close: () => app.unmount() }
}

test('启用规则后必须明确填写合成合计，不能用本单金额自动补齐', async () => {
  let requests = 0; globalThis.fetch = async () => { requests++; return Response.json(result()) }
  const p = mount()
  try { await p.state.run(); assert.equal(requests, 0); assert.match(p.state.inputError, /合成/); assert.equal(p.state.preview.result, null) }
  finally { p.close() }
})
test('合成合计独立于本单表单发送，网关说明区分业务和财务金额', async () => {
  let sent; globalThis.fetch = async (_, init) => { sent = JSON.parse(init.body); return Response.json(result()) }
  const p = mount()
  try {
    p.state.splitRoutingAmount = '60000.01'; await p.state.run()
    assert.deepEqual(sent.splitRoutingAmount, { value: '60000.01', currency: 'CNY' }); assert.deepEqual(sent.values, values())
    assert.deepEqual(p.state.values, values()); assert.equal('splitRoutingAmount' in p.state.values, false)
    const html = await renderToString(createSSRApp(Rendered, p.props))
    assert.match(html, /合成/); assert.match(html, /不会查询/); assert.match(html, /本单金额/)
  } finally { p.close() }
})
test('合成金额不接受指数、负值、隐式精度或低于本单的输入，币种须与规则一致', async () => {
  let requests = 0; globalThis.fetch = async () => { requests++; return Response.json(result()) }
  const p = mount()
  try {
    for (const value of ['1e4', '-1', '6000.001', '06000', '2999.99']) { p.state.splitRoutingAmount = value; await p.state.run(); assert.ok(p.state.inputError) }
    p.state.splitRoutingAmount = '6000'; p.state.values.currency = 'USD'; await p.state.run(); assert.match(p.state.inputError, /币/)
    assert.equal(requests, 0)
  } finally { p.close() }
})
test('编辑合成值立即取消旧结果，切换账号清空原测试输入，迟到响应不恢复高亮', async () => {
  const pending = []; globalThis.fetch = (url, init) => new Promise(resolve => pending.push({ init, resolve }))
  const p = mount()
  try {
    p.state.splitRoutingAmount = '6000'; const running = p.state.run(); await new Promise(resolve => setImmediate(resolve))
    assert.equal(pending.length, 1); p.state.splitRoutingAmount = '7000'
    assert.equal(pending[0].init.signal.aborted, true); pending[0].resolve(Response.json(result())); await running
    assert.equal(p.state.preview.result, null)
    p.props.scopeKey = 'another/admin'; assert.equal(p.state.splitRoutingAmount, ''); assert.deepEqual(p.state.values, {})
  } finally { p.close() }
})
test('规则关闭后不发送旧合成值，锁定或未知模式不能运行', async () => {
  const sent = []; globalThis.fetch = async (_, init) => { sent.push(JSON.parse(init.body)); return Response.json(result()) }
  const p = mount()
  try {
    p.state.splitRoutingAmount = '6000'; p.props.graph.nodes[0].properties.expenseSplitRisk = 'DISABLED'; await p.state.run()
    assert.equal(sent.length, 1); assert.equal('splitRoutingAmount' in sent[0], false)
    p.props.locked = true; await p.state.run(); assert.equal(sent.length, 1)
    p.props.locked = false; p.props.graph.nodes[0].properties.expenseSplitRisk = 'legacy-unknown'; await p.state.run()
    assert.equal(sent.length, 1); assert.match(p.state.inputError, /规则/)
  } finally { p.close() }
})
