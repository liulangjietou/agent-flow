import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp, createRenderer, reactive, nextTick } from 'vue'
import { renderToString } from '@vue/server-renderer'
const { default: Stage } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSESTAGERENDERED)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEBUDGETREVIEWPANELPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_EXPENSEBUDGETREVIEWPANELRENDERED)
const originals = { ...api }, originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage
afterEach(() => { Object.assign(api, originals); globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage })
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(values = {}) {
  const props = reactive({ reportId: id(1), applicationId: id(2), roundNo: 1, scopeKey: 'alice', version: 1, locked: false, ...values })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const instance = app.mount({}); return { props, state: instance.$.setupState, close: () => app.unmount() }
}
test('预算负责人职责可在真实设计器中选择，并说明等待实际预算结果', async () => {
  const html = await renderToString(createSSRApp(Stage, { modelValue: 'BUDGET_REVIEW', formSchema: { schemaVersion: 2, fields: [{ key: 'expenseDetails' }] }, disabled: false }))
  assert.doesNotMatch(html, /待修正/)
  assert.match(html, /预算负责人/)
  assert.match(html, /单人/)
  assert.match(html, /预算确认/)
})
test('预算原轮次读取必须有独立只读入口', () => {
  assert.equal(typeof api.expenseBudgetReview, 'function')
})

const model = await import(process.env.AGENTFLOW_TEST_EXPENSE_BUDGET_REVIEW)
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const at = '2026-10-04T12:00:00.123456Z', later = '2026-10-04T12:05:00.123456Z'
const clone = value => JSON.parse(JSON.stringify(value))
function view(status = 'REVIEW_REQUIRED') { return { reportId: id(1), applicationId: id(2), roundNo: 1, status: 'RECORDED', details: {
  version: 2, financialVersion: 2, budgetNodeId: 'budget', policyReference: 'policy-flex-1', originalOperationId: id(3), originalOperationStatus: 'REJECTED',
  authorizedOperationId: null, authorizedOperationStatus: null, status, decision: null, automaticPass: null, closure: null, submittedAt: at, updatedAt: later,
} } }
function authorized() { const value = view('AUTHORIZED'); Object.assign(value.details, { authorizedOperationId: id(4), authorizedOperationStatus: 'UNKNOWN',
  decision: { actorId: 'manager', taskId: 'native-budget', auditEventId: id(5), approvedAt: later } }); return value }
const read = value => model.readBudgetReviewView(value, id(1), id(2), 1)
test('原轮次严格拒绝串单、串轮、不完整授权和伪造的预算成功', () => {
  assert.deepEqual(read(view()), view()); assert.deepEqual(read(authorized()), authorized())
  for (const change of [v => { v.reportId = id(9) }, v => { v.roundNo = 2 }, v => { v.details.status = 'CONFIRMED' },
    v => { v.details.policyReference = null }, v => { v.details.budgetNodeId = null }, v => { v.details.version = 0 },
    v => { v.details.originalOperationStatus = 'APPLIED' }, v => { v.details.targetDigest = 'private' },
    v => { v.details.updatedAt = '2026-10-03T12:00:00Z' }, v => { v.details.closure = 'WITHDRAWN' }]) {
    const changed = view(); change(changed); assert.throws(() => read(changed))
  }
  for (const change of [v => { v.details.authorizedOperationId = id(3) }, v => { v.details.decision = null },
    v => { v.details.authorizedOperationStatus = null }, v => { v.details.decision.actorId = '' },
    v => { v.details.status = 'CONFIRMED' }]) { const changed = authorized(); change(changed); assert.throws(() => read(changed)) }
})
test('缺历史依据、原冻结自动通过和关闭轮次分别保留事实', () => {
  const absent = { reportId: id(1), applicationId: id(2), roundNo: 1, status: 'NOT_RECORDED', details: null }
  assert.deepEqual(read(absent), absent); assert.throws(() => read({ ...absent, details: view().details }))
  const confirmed = view('CONFIRMED'); Object.assign(confirmed.details, { originalOperationStatus: 'APPLIED', policyReference: null,
    automaticPass: { taskId: 'native-budget', auditEventId: id(5), passedAt: later } })
  assert.deepEqual(read(confirmed), confirmed)
  const stopped = authorized(); stopped.details.status = 'CLOSED'; stopped.details.closure = 'WITHDRAWN'; assert.deepEqual(read(stopped), stopped)
  stopped.details.closure = null; assert.throws(() => read(stopped))
})

test('等待结果只能是本轮初始记录，后续状态必须有新版本', () => {
  const waiting = view('WAITING_BUDGET'); Object.assign(waiting.details, { version: 1, originalOperationStatus: 'UNKNOWN', updatedAt: at })
  assert.deepEqual(read(waiting), waiting)
  for (const change of [v => { v.details.version = 2 }, v => { v.details.updatedAt = later }]) {
    const changed = clone(waiting); change(changed); assert.throws(() => read(changed))
  }
  const changed = view(); changed.details.version = 1; assert.throws(() => read(changed))
})

test('实际 GET 只带单据与明确轮次，禁止浏览器缓存并沿用取消信号', async () => {
  const calls = [], controller = new AbortController()
  globalThis.localStorage = { getItem: () => null }
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response(JSON.stringify(view()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  await api.expenseBudgetReview(id(1), 1, controller.signal)
  assert.equal(calls.length, 1); assert.equal(calls[0].url, `/api/v1/expense-reports/${id(1)}/budget-review?roundNo=1`)
  assert.equal(calls[0].options.cache, 'no-store'); assert.equal(calls[0].options.signal, controller.signal)
  assert.equal(calls[0].options.body, undefined)
})

test('实际组件切换身份和轮次时取消并清空旧事实，迟到响应不能覆盖当前轮次', async () => {
  const pending = []
  api.expenseBudgetReview = (reportId, roundNo, signal) => new Promise(resolve => pending.push({ reportId, roundNo, signal, resolve }))
  const panel = mount()
  try {
    await settle(); assert.equal(pending.length, 1)
    panel.props.scopeKey = 'bob'; await settle(); assert.equal(pending[0].signal.aborted, true)
    pending[0].resolve(view()); await settle(); assert.equal(panel.state.query.value, null)
    pending[1].resolve(authorized()); await settle(); assert.equal(panel.state.query.value.details.status, 'AUTHORIZED')
    panel.props.roundNo = 2; await settle(); assert.equal(panel.state.query.value, null)
    const next = view(); next.roundNo = 2; pending[2].resolve(next); await settle(); assert.equal(panel.state.query.value.roundNo, 2)
    panel.props.scopeKey = ''; await settle(); assert.equal(panel.state.query.value, null)
  } finally { panel.close() }
})

test('预算依据授权失败或返回错误轮次时清空原事实并显示读取失败', async () => {
  api.expenseBudgetReview = async () => view()
  const panel = mount()
  try {
    await settle(); assert.equal(panel.state.query.value?.status, 'RECORDED')
    api.expenseBudgetReview = async () => { throw { status: 403 } }
    panel.props.version++; await settle(); assert.equal(panel.state.query.value, null); assert.match(panel.state.query.error, /无法读取/)
    api.expenseBudgetReview = async () => ({ ...view(), roundNo: 2 })
    panel.props.version++; await settle(); assert.equal(panel.state.query.value, null); assert.match(panel.state.query.error, /原轮次不符/)
  } finally { panel.close() }
})

test('真实页面分开展示人工授权、实际确认和历史关闭，不产生再次审批入口', async () => {
  api.expenseBudgetReview = async () => authorized()
  const panel = mount()
  try {
    await settle()
    let html = await renderToString(createSSRApp({ ...Rendered, setup: () => panel.state }, panel.props))
    assert.match(html, /例外已批准，等待预算确认/); assert.match(html, /manager/); assert.match(html, /结果未知/)
    assert.doesNotMatch(html, /<button[^>]*>.*(?:同意|重新授权|强制通过).*<\/button>/)
    panel.state.query.value = view()
    html = await renderToString(createSSRApp({ ...Rendered, setup: () => panel.state }, panel.props)); assert.match(html, /需要预算负责人审批/)
    const closed = authorized(); closed.details.status = 'CLOSED'; closed.details.closure = 'WITHDRAWN'; panel.state.query.value = closed
    html = await renderToString(createSSRApp({ ...Rendered, setup: () => panel.state }, panel.props)); assert.match(html, /申请人撤回/); assert.match(html, /保留原轮次事实/)
    panel.state.query.value = { reportId: id(1), applicationId: id(2), roundNo: 1, status: 'NOT_RECORDED', details: null }
    html = await renderToString(createSSRApp({ ...Rendered, setup: () => panel.state }, panel.props)); assert.match(html, /未保存预算审批依据/)
  } finally { panel.close() }
})
