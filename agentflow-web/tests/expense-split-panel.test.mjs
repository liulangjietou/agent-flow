import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { readFileSync } from 'node:fs'
import { uuid, view, document, money } from './fixtures/expense-split-routing.mjs'
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSESPLITROUTINGPANELPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_EXPENSESPLITROUTINGPANELRENDERED)
const { api, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, fetch = globalThis.fetch, storage = globalThis.localStorage
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve))
const initial = () => ({ reportId: uuid(1), applicationId: uuid(21), roundNo: 1, scopeKey: 'demo/alice', version: 4, locked: false })
afterEach(() => { Object.assign(api, originals); if (!Object.hasOwn(originals, 'expenseSplitRouting')) delete api.expenseSplitRouting; globalThis.fetch = fetch; globalThis.localStorage = storage; bindAuthenticationActor(null) })
function mount() {
  const props = reactive(initial())
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, props)
  let closed = false
  return { state: app.mount({}).$.setupState, props, close: () => { if (!closed) { closed = true; app.unmount() } } }
}
async function rendered(value) {
  api.expenseSplitRouting = async () => value
  return renderToString(createSSRApp({ ...Rendered, setup(props, ctx) {
    const state = Rendered.setup(props, ctx); state.query.clear(); state.query.view = value; return state
  } }, initial()))
}

test('实际组件同步清除账号切换前的正文，旧响应不能覆盖新身份', async () => {
  const pending = []; api.expenseSplitRouting = (id, round, signal) => new Promise(resolve => pending.push({ id, round, signal, resolve }))
  const p = mount()
  try {
    assert.equal(pending.length, 1); pending[0].resolve(view()); await settle(); assert.equal(p.state.query.view.status, 'SPLIT_SUSPECTED')
    p.props.scopeKey = 'demo/manager'; assert.equal(p.state.query.view, null); assert.equal(pending.length, 2)
    p.props.scopeKey = 'demo/admin'; assert.equal(pending[1].signal.aborted, true)
    pending[2].resolve(view('RESTRICTED')); await settle(); pending[1].resolve(view()); await settle()
    assert.equal(p.state.query.view.status, 'RESTRICTED')
  } finally { for (const request of pending) request.resolve(view('RESTRICTED')); p.close() }
})
test('切换原轮次和版本会重新读取，卸载中止未完成请求并清空正文', async () => {
  const pending = []; api.expenseSplitRouting = (id, round, signal) => new Promise(resolve => pending.push({ id, round, signal, resolve }))
  const p = mount()
  try {
    assert.equal(pending.length, 1); p.props.roundNo = 2; assert.equal(pending[0].signal.aborted, true)
    assert.equal(pending[1].round, 2); p.props.version++
    assert.equal(pending[1].signal.aborted, true); p.close(); assert.equal(pending[2].signal.aborted, true)
    for (const request of pending) request.resolve({ ...view('RESTRICTED'), roundNo: request.round })
    await settle(); assert.equal(p.state.query.view, null)
  } finally { for (const request of pending) request.resolve(view('RESTRICTED')); p.close() }
})
test('受限、历史未记录和明确关闭分别展示，受限状态不包含金额、来源或安全结论', async () => {
  const restricted = await rendered(view('RESTRICTED'))
  assert.match(restricted, /跨单依据受限/); assert.doesNotMatch(restricted, /8,000|4,000|来源单据|未命中拆单风险|已经检查通过/)
  assert.match(await rendered(view('NOT_RECORDED')), /本轮未记录跨单检查/)
  assert.match(await rendered(view('UNCONFIGURED')), /本轮未配置跨单规则/)
  assert.match(await rendered(view('DISABLED')), /本轮已关闭跨单规则/)
})
test('已授权正文展示冻结规则、本单和路由金额，原状态不冒充当前审批状态', async () => {
  const html = await rendered(view())
  for (const text of ['本轮命中拆单风险', 'CNY 8,000.00', 'CNY 4,000.00', 'CNY 5,000.00', '7 × 24 小时', '来源单据', '流程版本']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(html, /DRAFT|IN_APPROVAL|当前状态：草稿/)
  const escaped = view(); escaped.details.processKey = '<script>unsafe()</script>'
  const safe = await rendered(escaped); assert.doesNotMatch(safe, /<script>/); assert.ok(safe.includes('&lt;script&gt;'))
})
test('实际 API 只发送明确原轮次的只读请求，保留取消信号并禁止缓存', async () => {
  assert.equal(typeof api.expenseSplitRouting, 'function')
  globalThis.localStorage = { getItem: () => 'synthetic', setItem() {}, removeItem() {} }
  bindAuthenticationActor({ tenantId: 'demo', userId: 'alice', roles: ['EMPLOYEE'] })
  const calls = []; globalThis.fetch = async (url, init) => { calls.push({ url: String(url), init }); return new Response(JSON.stringify(view()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  const signal = new AbortController().signal; await api.expenseSplitRouting(uuid(1), 1, signal)
  assert.equal(calls.length, 1); const request = calls[0]
  assert.equal(request.url, `/api/v1/expense-reports/${uuid(1)}/split-routing?roundNo=1`)
  assert.equal(request.init.method ?? 'GET', 'GET'); assert.equal(request.init.body, undefined)
  assert.equal(request.init.cache, 'no-store'); assert.equal(request.init.signal, signal)
})
test('费用详情只在已有提交轮次中装载只读依据面板，并绑定账号和业务版本', () => {
  const source = readFileSync(new URL('../src/components/ExpenseDetail.vue', import.meta.url), 'utf8')
  assert.match(source, /ExpenseSplitRoutingPanel v-if="query.detail.financialRound && query.detail.roundNo > 0"/)
  const binding = source.split('\n').find(line => line.includes('<ExpenseSplitRoutingPanel'))
  for (const prop of [':report-id="query.detail.id"', ':application-id="query.detail.applicationId"', ':round-no="query.detail.roundNo"', ':scope-key="scopeKey"', ':version="query.detail.applicationVersion"']) assert.ok(binding.includes(prop), prop)
})

test('来源较多时每页只展示十单，切页和权限刷新清除已展开的来源', async () => {
  const value = view(); value.details.assessment.sources.push(...Array.from({ length: 10 }, (_, i) => document(i + 3, 'IN_APPROVAL')))
  value.details.assessment.categories[0].reportCount = 12; value.details.assessment.categories[0].total = money('48000.00')
  value.details.assessment.routingAmount = money('48000.00'); let calls = 0
  api.expenseSplitRouting = async () => ++calls === 1 ? value : view('RESTRICTED')
  const p = mount()
  try {
    await settle(); assert.equal(p.state.shownSources.length, 10); assert.equal(p.state.pageCount, 2)
    p.state.toggleSource(uuid(1)); assert.equal(p.state.expanded, uuid(1))
    p.state.changePage(1); assert.equal(p.state.shownSources.length, 2); assert.equal(p.state.expanded, '')
    p.state.changePage(99); assert.equal(p.state.page, 1)
    p.props.scopeKey = 'demo/manager'; assert.equal(p.state.shownSources.length, 0); assert.equal(p.state.page, 0)
    await settle(); assert.equal(p.state.query.view.status, 'RESTRICTED'); assert.equal(p.state.shownSources.length, 0)
  } finally { p.close() }
})
