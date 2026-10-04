import test, { afterEach } from 'node:test'
import { createRenderer, reactive, nextTick, createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import assert from 'node:assert/strict'
const configuration = await import(process.env.AGENTFLOW_TEST_EXPENSE_CONFIGURATION)
const draft = await import(process.env.AGENTFLOW_TEST_EXPENSE_DRAFT)
const actor = { tenantId: 'demo', userId: 'admin', roles: ['ADMIN'] }
const category = control => ({ code: 'OFFICE', name: '办公费', units: ['ITEM'], active: true, ...(control ? { priorControl: control } : {}) })
const form = controlled => ({ formSchema: { schemaVersion: 2, fields: [
  { key: 'expenseDetails', type: 'TEXT', required: true, sensitive: true }, { key: 'amount', type: 'NUMBER', required: true },
  { key: 'currency', type: 'TEXT', required: true }, { key: 'overPolicy', type: 'BOOLEAN', required: true },
  ...(controlled ? [{ key: 'priorRequestOverTolerance', type: 'BOOLEAN', required: true }] : [])
] } })

test('类别读取和保存确认保留明确控制，不能静默删掉容差配置', () => {
  const value = { tenantId: 'demo', version: 1, categories: [category({ mode: 'TOLERANCE', toleranceFraction: 0.125 })] }
  assert.deepEqual(configuration.readExpenseCategories(value, actor), value)
  for (const control of [{ mode: 'UNKNOWN' }, { mode: 'TOLERANCE' }, { mode: 'STRICT', toleranceFraction: 0.1 }]) {
    assert.throws(() => configuration.readExpenseCategories({ ...value, categories: [category(control)] }, actor))
  }
})

test('费用入口接受新五字段与历史四字段，仍拒绝伪造字段和类型', () => {
  assert.equal(draft.expenseDefinition(form(true)), true)
  assert.equal(draft.expenseDefinition(form(false)), true)
  const extra = form(true); extra.formSchema.fields.push({ key: 'approved', type: 'BOOLEAN', required: true })
  assert.equal(draft.expenseDefinition(extra), false)
  const wrong = form(true); wrong.formSchema.fields[4].type = 'TEXT'; assert.equal(draft.expenseDefinition(wrong), false)
})

const model = await import(process.env.AGENTFLOW_TEST_EXPENSE_PRIOR_CONTROL)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEPRIORCONTROLPANELPANEL)
const { default: Facts } = await import(process.env.AGENTFLOW_TEST_EXPENSEPRIORCONTROLFACTSRENDERED)
const { default: Stage } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSESTAGERENDERED)
const { default: Picker } = await import(process.env.AGENTFLOW_TEST_EXPENSEFUNDINGPICKERPANEL)
const { default: PickerRendered } = await import(process.env.AGENTFLOW_TEST_EXPENSEFUNDINGPICKERRENDERED)
const { default: PlanLines } = await import(process.env.AGENTFLOW_TEST_EXPENSEPLANLINESRENDERED)
const originals = { ...api }, clone = value => JSON.parse(JSON.stringify(value))
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const money = value => ({ value, currency: 'CNY' })
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(Component, values) {
  const props = reactive(values), app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, props)
  const instance = app.mount({}); return { props, state: instance.$.setupState, close: () => app.unmount() }
}
afterEach(() => { Object.assign(api, originals) })
function assessment() { return { lineNo: 1, requestId: id(3), requestVersion: 1,
  source: { lineNo: 1, approvedAmount: money('80.00'), toleranceFraction: 0.1, policyReference: 'synthetic',
    control: { categoryCode: 'OFFICE', categoryRevision: 1, control: { mode: 'TOLERANCE', toleranceFraction: 0.1 } } },
  threshold: money('88.00'), consumed: money('0.00'), otherReserved: money('0.00'), roundReserved: money('100.00'),
  lineAmount: money('100.00'), totalExposure: money('100.00'), exceeded: money('12.00') } }
function view(roundNo = 1) { return { reportId: id(1), applicationId: id(2), roundNo, status: 'RECORDED', requiresApproval: true,
  details: { tenantId: 'demo', reportId: id(1), applicationId: id(2), roundNo, applicationVersion: 3, financialVersion: 2,
    definitionId: id(4), definitionVersion: 1, submittedAt: '2026-10-04T12:00:00.123456Z', assessments: [assessment()] } } }
const read = value => model.readPriorControlView(value, id(1), id(2), 1)

test('比例保留百万分之一边界；旧缺配置不改写成新模式', () => {
  for (const ratio of [0, 0.000001, 0.123456, 1]) assert.equal(model.readPriorControl({ mode: 'TOLERANCE', toleranceFraction: ratio }).toleranceFraction, ratio)
  for (const ratio of ['0.1', 0.0000001, -0.1, 1.1, null]) assert.throws(() => model.readPriorControl({ mode: 'TOLERANCE', toleranceFraction: ratio }))
  assert.deepEqual(configuration.readExpenseCategories({ tenantId: 'demo', version: 1, categories: [category()] }, actor).categories[0], category())
})
test('原轮次证据按精确分复算，拒绝错误总额、标志、身份、类别模式及未知正文', () => {
  assert.deepEqual(read(view()), view())
  for (const change of [v => { v.roundNo = 2 }, v => { v.details.applicationId = id(9) }, v => { v.requiresApproval = false },
    v => { v.details.assessments[0].source.control.control.mode = 'STRICT' }, v => { v.details.assessments[0].exceeded.value = '0.00' },
    v => { v.details.assessments[0].totalExposure.value = '200.00' }, v => { v.details.assessments[0].lineAmount.value = '50.00' },
    v => { v.details.assessments[0].roundReserved.currency = 'USD' }, v => { v.details.assessments[0].otherReports = ['private'] }]) {
    const value = view(); change(value); assert.throws(() => read(value))
  }
  const unknown = { reportId: id(1), applicationId: id(2), roundNo: 1, status: 'NOT_RECORDED', requiresApproval: null, details: null }
  assert.deepEqual(read(unknown), unknown); assert.throws(() => read({ ...unknown, requiresApproval: false }))
})
test('同源多行各自用量必须组成同一份累计金额，不能重复享有阈值', () => {
  const one = assessment(), two = clone(one); one.lineAmount = money('40.00'); two.lineNo = 2; two.lineAmount = money('60.00')
  assert.equal(model.readPriorAssessments([one, two]).length, 2)
  two.lineAmount = money('70.00'); assert.throws(() => model.readPriorAssessments([one, two]))
})
test('NONE 仅显示参考余额，控制与硬上限标志不一致必须拒绝', () => {
  const line = { lineNo: 1, approved: money('80.00'), limit: money('80.00'), available: money('0.00'), reserved: money('100.00'), consumed: money('0.00'),
    hardLimit: false, exceeded: money('20.00'), control: { categoryCode: 'OFFICE', categoryRevision: 1, control: { mode: 'NONE' } } }
  const value = { items: [{ id: id(3), applicationId: id(4), legalEntityId: id(5), version: 2, closed: false, lines: [line] }] }
  assert.deepEqual(model.readPriorRequestPage(value), value)
  assert.match(model.priorBalanceLabel(line), /不按额度阻断.*参考余额/); assert.doesNotMatch(model.priorBalanceLabel(line), /可用额度/)
  const changed = clone(value); changed.items[0].lines[0].hardLimit = true; assert.throws(() => model.readPriorRequestPage(changed))
})
test('真实组件清理旧身份和旧轮次，迟到结果不会重新显示敏感依据', async () => {
  const pending = []
  api.expensePriorControl = (id, round, signal) => new Promise(resolve => pending.push({ id, round, signal, resolve }))
  const panel = mount(Panel, { reportId: id(1), applicationId: id(2), roundNo: 1, scopeKey: 'alice', version: 1, locked: false })
  try {
    await settle(); panel.props.scopeKey = 'bob'; await settle()
    assert.equal(pending[0].signal.aborted, true); assert.equal(panel.state.query.value, null)
    pending[0].resolve(view()); await settle(); assert.equal(panel.state.query.value, null)
    pending[1].resolve(view()); await settle(); assert.equal(panel.state.query.value.requiresApproval, true)
    panel.props.roundNo = 2; await settle(); assert.equal(panel.state.query.value, null)
    pending[2].resolve(view(2)); await settle(); assert.equal(panel.state.query.value.roundNo, 2)
    panel.props.scopeKey = ''; await settle(); assert.equal(panel.state.query.value, null)
  } finally { panel.close() }
})
test('真实金额组件显示原用量与独立审批，新职责可在设计器选择', async () => {
  const html = await renderToString(createSSRApp(Facts, { assessments: [assessment()] }))
  assert.match(html, /累计阈值/); assert.match(html, /CNY 88.00/); assert.match(html, /CNY 12.00/); assert.match(html, /独立的事前额度例外审批/)
  const stage = await renderToString(createSSRApp(Stage, { modelValue: 'PRIOR_REQUEST_REVIEW', formSchema: form(true).formSchema, disabled: false }))
  assert.match(stage, /事前额度例外审批/); assert.doesNotMatch(stage, /待修正/)
})
test('选择器按冻结类别和币种过滤，宽松模式也不能跨类别引用', async () => {
  const content = { legalEntityId: id(5), lines: [{ lineNo: 1, categoryCode: 'OFFICE', priorRequest: null }], advanceOffsets: [] }
  const panel = mount(Picker, { modelValue: content, scopeKey: 'alice', baseCurrency: 'CNY', locked: false })
  try {
    await settle(); panel.state.lineNo = 1
    const line = { lineNo: 1, approved: money('80.00'), control: { categoryCode: 'TRAVEL', categoryRevision: 1, control: { mode: 'NONE' } } }
    const resource = { id: id(3), legalEntityId: id(5), closed: false, lines: [line] }; panel.state.requests.items = [resource]
    assert.equal(panel.state.canSelectPrior(resource, line), false); panel.state.addPrior(id(3), 1); assert.equal(content.lines[0].priorRequest, null)
    line.control.categoryCode = 'OFFICE'; assert.equal(panel.state.canSelectPrior(resource, line), true)
    panel.state.addPrior(id(3), 1); assert.deepEqual(content.lines[0].priorRequest, { requestId: id(3), lineNo: 1 })
    line.approved.currency = 'USD'; assert.equal(panel.state.canSelectPrior(resource, line), false)
  } finally { panel.close() }
})


test('额度选择器明确显示零参考余额之外已经超出的金额', async () => {
  const content = { legalEntityId: id(5), lines: [{ lineNo: 1, categoryCode: 'OFFICE', priorRequest: null }], advanceOffsets: [] }
  const panel = mount(Picker, { modelValue: content, scopeKey: 'alice', baseCurrency: 'CNY', locked: false })
  try {
    await settle(); panel.state.kind = 'requests'; await settle(); panel.state.open = true; panel.state.lineNo = 1
    panel.state.requests.items = [{ id: id(3), legalEntityId: id(5), closed: false, lines: [{ lineNo: 1, approved: money('80.00'),
      available: money('0.00'), exceeded: money('12.00'), control: { categoryCode: 'OFFICE', categoryRevision: 1, control: { mode: 'NONE' } } }] }]
    const html = await renderToString(createSSRApp({ ...PickerRendered, setup: () => panel.state }, panel.props))
    assert.match(html, /参考余额 CNY 0.00/); assert.match(html, /超出参考金额 CNY 12.00/)
  } finally { panel.close() }
})

test('计划冻结阈值向下取整到分，严格与无上限模式保留明确语义', async () => {
  const original = { lineNo: 1, categoryCode: 'OFFICE', plannedOn: '2026-10-04', cityCode: 'SH', amount: money('0.03'), allocations: [], description: '合成计划' }
  for (const [control, expected] of [[{ mode: 'TOLERANCE', toleranceFraction: 0.5 }, /累计控制阈值 CNY 0.04/],
    [{ mode: 'STRICT' }, /累计控制阈值 CNY 0.03/], [{ mode: 'NONE' }, /参考金额 CNY 0.03，额度不设上限/]]) {
    const financial = { managedCategoryRevision: 1, lines: [{ original, amount: money('0.03'), allocations: [],
      rate: { fromCurrency: 'CNY', toCurrency: 'CNY', rate: 1, rateDate: '2026-10-04', source: 'synthetic' },
      priorControl: { categoryCode: 'OFFICE', categoryRevision: 1, control } }] }
    const html = await renderToString(createSSRApp(PlanLines, { content: { lines: [original] }, financial }))
    assert.match(html, expected)
  }
})


test('费用入口接受项目派生字段与事前控制组合，仍拒绝多余或非布尔字段', () => {
  for (const prior of [false, true]) {
    const value = form(prior); value.formSchema.fields.push({ key: 'hasProjectAllocation', type: 'BOOLEAN', required: true })
    assert.equal(draft.expenseDefinition(value), true)
    value.formSchema.fields.at(-1).type = 'TEXT'; assert.equal(draft.expenseDefinition(value), false)
  }
})
