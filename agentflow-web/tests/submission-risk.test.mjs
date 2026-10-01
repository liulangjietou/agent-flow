import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, ref } from 'vue'
import { renderToString } from 'vue/server-renderer'

const { riskConditionSchema, copyRiskPolicy } = await import(process.env.AGENTFLOW_TEST_SUBMISSION_RISK)
const { parsePortableTemplate, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const { createEditor } = await import(process.env.AGENTFLOW_TEST_RISK_EDITOR)
const { default: PolicyPanel } = await import(process.env.AGENTFLOW_TEST_RISK_DEFINITIONRISKPOLICY)
const { default: QueuePanel } = await import(process.env.AGENTFLOW_TEST_RISK_PENDINGTASKQUEUE)
const { default: Status } = await import(process.env.AGENTFLOW_TEST_RISK_SUBMISSIONRISKSTATUS)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { simulationIssue } = await import(process.env.AGENTFLOW_TEST_SIMULATION)
const policy = () => ({ rules: [{ id: 'large', label: '重点复核', level: 'HIGH', condition: 'amount > 100' }] })
const schema = () => ({ schemaVersion: 1, fields: [{ key: 'amount', label: '金额', type: 'NUMBER', required: false }] })
const settle = () => new Promise(resolve => setImmediate(resolve))
globalThis.localStorage = { getItem: () => null, setItem() {} }

test('风险条件选择排除敏感字段、节点脱敏字段及含受限列的表格，保留已有原文', () => {
  const source = schema()
  source.fields.push({ key: 'secret', type: 'TEXT', sensitive: true },
    { key: 'masked', type: 'TEXT', nodeAccess: { finance: 'MASKED' } },
    { key: 'hidden', type: 'TEXT', nodeAccess: { manager: 'HIDDEN' } },
    { key: 'readonly', type: 'TEXT', nodeAccess: { manager: 'READ_ONLY' } },
    { key: 'table', type: 'TABLE', columns: [{ key: 'private', sensitive: true }] })
  assert.deepEqual(riskConditionSchema(source).fields.map(field => field.key), ['amount', 'readonly'])
  assert.equal(source.fields.length, 6)
  const sourcePolicy = policy(), copied = copyRiskPolicy(sourcePolicy)
  copied.rules[0].condition = 'secret EXISTS'
  assert.equal(sourcePolicy.rules[0].condition, 'amount > 100')
  assert.equal(copyRiskPolicy(undefined), null)
})

test('模板往返完整保留风险规则，不把旧模板补成已评估，也不接受强制类型转换', () => {
  const value = { key: 'risk', name: '风险模板', graph: { nodes: [], edges: [], conditionLanguageVersion: 2, riskPolicy: policy() }, formSchema: schema() }
  assert.deepEqual(parsePortableTemplate(serializePortableTemplate(value)), value)
  for (const patch of [{ level: 3 }, { level: 'UNASSESSED' }, { label: true }, { condition: '' }, { label: '说明\n敏感值' }, { extra: 'ignored' }]) {
    const invalid = structuredClone(value)
    Object.assign(invalid.graph.riskPolicy.rules[0], patch)
    assert.throws(() => serializePortableTemplate(invalid))
  }
  const duplicate = structuredClone(value)
  duplicate.graph.riskPolicy.rules.push({ ...duplicate.graph.riskPolicy.rules[0] })
  assert.throws(() => serializePortableTemplate(duplicate))
  delete value.graph.riskPolicy
  assert.equal(parsePortableTemplate(serializePortableTemplate(value)).graph.riskPolicy, undefined)
})

test('真实设计器读取、保存与撤销保留风险策略，读取旧定义清除上一个流程规则', () => {
  const deps = Object.fromEntries(['definitionId', 'definitionKey', 'definitionName', 'definitionRiskPolicy', 'conditionLanguageVersion',
    'nodes', 'edges', 'definitionFormSchema', 'definitionNotificationTexts', 'editorSession', 'composing', 'definitionRevision',
    'definitionVersion', 'definitionStatus', 'definitionStartEnabled', 'availabilityError', 'selectedId', 'savedSnapshot', 'tenantId'].map(key => [key, ref(null)]))
  deps.editorSession.value = 0; deps.tenantId.value = 'demo'; deps.autosave = { reset() {} }; deps.resetEditor = () => {}
  const state = createEditor(deps)
  const source = { id: 'one', key: 'first', name: '原定义', graph: { nodes: [], edges: [], conditionLanguageVersion: 2, riskPolicy: policy() }, formSchema: schema(), revision: 0, version: 1, status: 'PUBLISHED', startEnabled: true }
  state.applyDefinition(source)
  const saved = state.snapshot()
  deps.definitionRiskPolicy.value.rules[0].level = 'LOW'
  assert.equal(source.graph.riskPolicy.rules[0].level, 'HIGH')
  assert.equal(state.graphPayload().riskPolicy.rules[0].level, 'LOW')
  state.restore(saved)
  assert.equal(state.graphPayload().riskPolicy.rules[0].level, 'HIGH')
  const outgoing = state.graphPayload()
  outgoing.riskPolicy.rules[0].label = '独立请求'
  assert.equal(deps.definitionRiskPolicy.value.rules[0].label, '重点复核')
  state.applyDefinition({ ...source, id: 'legacy', graph: { nodes: [], edges: [] } })
  assert.equal(deps.definitionRiskPolicy.value, null)
  assert.equal(state.graphPayload().riskPolicy, null)
})

test('真实规则组件显式添加、修改、删除，锁定后拒绝更改且不修改传入对象', async () => {
  const original = policy()
  const mounted = mount(PolicyPanel, { modelValue: original, formSchema: schema(), languageVersion: 2, locked: false }, true)
  try {
    mounted.state.change(0, { level: 'LOW' })
    assert.equal(original.rules[0].level, 'HIGH')
    assert.equal(mounted.props.modelValue.rules[0].level, 'LOW')
    assert.deepEqual(mounted.events, ['beforeChange', 'update'])
    mounted.state.add(); assert.equal(mounted.props.modelValue.rules.length, 2)
    mounted.props.locked = true
    mounted.state.change(0, { label: '不可修改' }); mounted.state.add(); mounted.state.remove(0)
    assert.equal(mounted.props.modelValue.rules.length, 2); assert.equal(mounted.props.modelValue.rules[0].label, '重点复核')
    mounted.props.locked = false
    mounted.state.remove(1); mounted.state.remove(0); assert.equal(mounted.props.modelValue, null)
    mounted.props.formSchema = { schemaVersion: 1, fields: [{ key: 'secret', sensitive: true }] }
    mounted.state.add(); assert.equal(mounted.props.modelValue, null)
    await settle()
  } finally { mounted.close() }
})

test('真实队列查询、分页及清空沿用已应用风险条件，编辑表单不污染后页', async () => {
  const previous = api.taskPage, requests = []
  api.taskPage = async params => { requests.push(params); return { items: [{ taskId: params.cursor ? 'next' : 'first' }], total: 2, nextCursor: params.cursor ? null : 'cursor' } }
  const mounted = mount(QueuePanel, { view: 'board', scopeKey: 'demo:manager', refreshVersion: 0, locked: false })
  try {
    await settle()
    mounted.state.filters.risk = 'high'; mounted.state.search(); await settle()
    assert.equal(requests.at(-1).risk, 'high')
    mounted.state.filters.risk = 'low'; await mounted.state.query.more()
    assert.equal(requests.at(-1).risk, 'high'); assert.equal(requests.at(-1).cursor, 'cursor')
    mounted.state.reset(); await settle()
    assert.equal(requests.at(-1).risk, 'all'); assert.equal(requests.at(-1).cursor, undefined)
  } finally { mounted.close(); api.taskPage = previous }
})

test('真实展示区分缺少依据、未命中和已分类，并转义管理员公开说明', async () => {
  for (const [level, label] of Object.entries({ UNASSESSED: '未评估', UNMATCHED: '规则未命中', LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' })) {
    const risk = { level, definitionVersion: level === 'UNASSESSED' ? 0 : 3, matches: [] }
    const html = await renderToString(createSSRApp(Status, { risk }))
    assert.ok(html.includes(`提交时风险：${label}`))
    if (level === 'UNASSESSED') assert.match(html, /不能据此判断为低风险/)
    else assert.match(html, /流程 v3/)
  }
  const html = await renderToString(createSSRApp(Status, { compact: true, risk: { level: 'HIGH', definitionVersion: 3, matches: [{ ruleId: 'unsafeLabel', label: '<script>alert(1)</script>', level: 'HIGH' }] } }))
  assert.doesNotMatch(html, /<script>/); assert.match(html, /&lt;script&gt;/)
  assert.equal(simulationIssue('RISK_FIELD_RESTRICTED:risk:secret').label, '风险规则 secret：风险规则不能引用敏感、隐藏或脱敏字段')
})

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(Panel, initial, updateModel = false) {
  const props = reactive(initial), events = []
  const app = renderer.createApp({ ...Panel, setup: (_props, context) => Panel.setup(props, context), render: () => null }, {
    ...props, onBeforeChange: () => events.push('beforeChange'), 'onUpdate:modelValue': value => { events.push('update'); if (updateModel) props.modelValue = value }
  })
  const mounted = app.mount({})
  return { props, events, state: mounted.$.setupState, close: () => app.unmount() }
}
