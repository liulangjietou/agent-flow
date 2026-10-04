import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { dirname, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { readFileSync } from 'node:fs'
import { createRenderer, createSSRApp, nextTick, reactive } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { report, uuid, ageBands } from './fixtures/expense-financial-reporting.mjs'
const output = dirname(process.env.AGENTFLOW_TEST_API)
const model = await import(pathToFileURL(resolve(output, 'expenseFinancialReporting.js')))
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(pathToFileURL(resolve(output, 'ExpenseFinancialReportingPanel.js')))
const { default: Rendered } = await import(pathToFileURL(resolve(output, 'ExpenseFinancialReportingRendered.js')))
const original = { ...api }, fetchOriginal = globalThis.fetch
const storageOriginal = globalThis.localStorage
const now = new Date('2026-10-04T14:00:00Z'), filter = { from: '2026-09-05', to: '2026-10-04' }
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount() {
  const props = reactive({ scopeKey: 'demo:finance', refreshVersion: 1 })
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, props)
  const instance = app.mount({}); return { props, state: instance.$.setupState, close: () => app.unmount() }
}
afterEach(() => { Object.assign(api, original); globalThis.fetch = fetchOriginal; globalThis.localStorage = storageOriginal })

test('近三十天及含闰日的 366 天边界，保留原类别字符', () => {
  assert.deepEqual(model.defaultFinancialFilter(now), filter)
  assert.equal(model.financialFilter({ from: '2025-10-04', to: '2026-10-04', categoryCode: ' ORIGINAL CODE ' }, now).categoryCode, ' ORIGINAL CODE ')
  assert.equal(model.financialFilter({ from: '2024-02-29', to: '2025-02-28' }, now).from, '2024-02-29')
  for (const bad of [{ from: '2025-10-03' }, { from: '2026-02-30' }, { to: '2026-10-05' }, { legalEntityId: 'bad' }, { categoryCode: ' ' }]) {
    assert.throws(() => model.financialFilter({ ...filter, ...bad }, now))
  }
})
test('完整空报告保留明确 null，不将未知样本当零', () => assert.deepEqual(model.readFinancialReport(report(), filter), report()))
test('精确金额不受单笔上限或 JavaScript 数值精度影响', () => {
  const value = report(); value.totals.amounts = [{ currency: 'CNY', claimed: '9999999999999999999999.99', approved: '0.00', reduced: '0.00' }]
  assert.equal(model.readFinancialReport(value, filter).totals.amounts[0].claimed, '9999999999999999999999.99')
  assert.equal(model.financialAmount(value.totals.amounts[0].claimed), '9,999,999,999,999,999,999,999.99')
})
test('原名称不截断，未知原组织允许 null，原类别保持原值', () => {
  const value = report(); value.groups = [{ dimension: 'LEGAL_ENTITY', code: uuid(1), name: '原'.repeat(140), metrics: structuredClone(value.totals) },
    { dimension: 'DEPARTMENT', code: null, name: null, metrics: structuredClone(value.totals) },
    { dimension: 'CATEGORY', code: ' ORIGINAL CODE ', name: ' ORIGINAL CODE ', metrics: structuredClone(value.totals) }]
  assert.deepEqual(model.readFinancialReport(value, filter), value)
})
test('错范围、非完整数据、未知字段和非法数值都不能成为页面报告', () => {
  for (const mutate of [x => { x.scope = 'TENANT' }, x => { x.timeZone = 'Asia/Shanghai' }, x => { x.filters.departmentId = uuid(2) },
    x => { delete x.totals.approval.p50Seconds }, x => { x.totals.approval.p90Seconds = -1 }, x => { x.totals.submitted = 1.5 },
    x => { x.totals.returnRate = 2 }, x => { x.totals.amounts = [{ currency: 'CNY', claimed: 5, approved: '0.00', reduced: '0.00' }] },
    x => { x.activity.verification.failureRate = NaN }, x => { x.backlog.payments.SUCCEEDED = 0 }, x => { delete x.backlog.vouchers.UNKNOWN },
    x => { x.resources.asOf = '2026-01-01T00:00:00Z' }, x => { x.unreadableReports = 99 }, x => { x.generatedAt = 'yesterday' }]) {
    const value = report(); mutate(value); assert.throws(() => model.readFinancialReport(value, filter))
  }
})
test('无样本分位数和原类别筛选不能伪造适用的借款', () => {
  const value = report(); value.totals.approval.p50Seconds = 0; value.totals.approval.p90Seconds = 0
  assert.throws(() => model.readFinancialReport(value, filter))
  const category = { ...filter, categoryCode: 'TRAVEL' }, wrong = report(category); wrong.resources.advanceCategoryApplicable = true
  assert.throws(() => model.readFinancialReport(wrong, category))
})
test('币种分别保留，计划执行允许超过一，账龄含未知且不能重复', () => {
  const value = report(); value.resources.priorRequests = [{ currency: 'CNY', lines: 1, approved: '10.00', consumed: '11.00', reserved: '1.00', executionRate: 1.1 }]
  value.resources.advances = ['CNY','USD'].map(currency => ({ currency, accounts: 0, outstanding: '0.00', underReview: '0.00', ages: ageBands.map(band => ({ band, accounts: 0, outstanding: '0.00' })) }))
  assert.deepEqual(model.readFinancialReport(value, filter), value)
  value.resources.advances[0].ages[0].band = 'UNKNOWN'; assert.throws(() => model.readFinancialReport(value, filter))
})
test('查询持有筛选副本并忽略旧身份迟到响应', async () => {
  const pending = [], query = new model.ExpenseFinancialQuery((filter, signal) => new Promise(resolve => pending.push({ filter, signal, resolve })))
  const mutable = { ...filter }; const first = query.load('a', mutable); mutable.from = '2026-01-01'
  const second = query.load('b', filter); assert.equal(pending[0].signal.aborted, true); assert.equal(pending[0].filter.from, filter.from)
  pending[1].resolve(report()); await second; pending[0].resolve({}); await first; assert.deepEqual(query.report, report())
  query.clear(); assert.equal(query.report, null)
})
test('接口失败、契约错误、空身份清除旧数据，下一次合法查询可恢复', async () => {
  let result = report(); const query = new model.ExpenseFinancialQuery(async () => result)
  await query.load('a', filter); result = {}; await query.load('a', filter); assert.equal(query.report, null); assert.ok(query.error)
  result = report(); await query.load('', filter); assert.equal(query.report, null); await query.load('a', filter); assert.ok(query.report)
})
test('超时立即结束等待，即使调用方忽略取消也不显示迟到报告', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let finish, signal; const query = new model.ExpenseFinancialQuery((_, s) => { signal = s; return new Promise(resolve => { finish = resolve }) })
  const waiting = query.load('a', filter); t.mock.timers.tick(15_000); await waiting
  assert.equal(query.loading, false); assert.equal(signal.aborted, true); assert.match(query.error, /超时/)
  finish(report()); await Promise.resolve(); assert.equal(query.report, null)
})
test('真实组件筛选变化取消旧请求，身份变化同时清掉原组织标签', async () => {
  const pending = []; api.expenseFinancialReport = (filters, signal) => new Promise(resolve => pending.push({ filters, signal, resolve }))
  const panel = mount(); const initial = pending[0]; initial.resolve(report(initial.filters)); await settle(); assert.ok(panel.state.query.report)
  panel.state.chooseGroup({ dimension: 'DEPARTMENT', code: uuid(1), name: '前一身份部门' }); await nextTick()
  assert.equal(panel.state.query.report, null); assert.equal(pending[1].filters.departmentId, uuid(1))
  panel.props.scopeKey = 'other:finance'; await nextTick(); assert.equal(pending[1].signal.aborted, true)
  assert.equal(panel.state.filters.departmentId, undefined); assert.equal(panel.state.labels.departmentId, undefined)
  pending[1].resolve(report(pending[1].filters)); await settle(); assert.equal(panel.state.query.report, null)
  pending[2].resolve(report(pending[2].filters)); await settle(); assert.ok(panel.state.query.report)
  panel.close(); assert.equal(panel.state.query.report, null)
})
test('真实组件编辑日期立即清理，失败重试与卸载取消均有效', async () => {
  const pending = []; api.expenseFinancialReport = (filters, signal) => new Promise((resolve, reject) => pending.push({ filters, signal, resolve, reject }))
  const panel = mount(); panel.state.filters.from = '2026-01-01'; await nextTick(); assert.equal(pending[0].signal.aborted, true)
  panel.state.refresh(); pending[1].reject(new Error('权限已经改变')); await settle(); assert.equal(panel.state.query.report, null); assert.match(panel.state.query.error, /权限/)
  panel.state.refresh(); pending[2].resolve(report(pending[2].filters)); await settle(); assert.ok(panel.state.query.report)
  panel.state.refresh(); panel.close(); assert.equal(pending[3].signal.aborted, true); pending[3].resolve(report(pending[3].filters)); await settle(); assert.equal(panel.state.query.report, null)
})
test('实际模板保留未知、账龄口径及精确金额，原名称仅作文本', async () => {
  const value = report(); value.totals.amounts = [{ currency: 'CNY', claimed: '9999999999999999999999.99', approved: '0.00', reduced: '0.00' }]
  value.groups = [{ dimension: 'CATEGORY', code: '<img onerror=alert(1)>', name: '<img onerror=alert(1)>', metrics: structuredClone(value.totals) }]
  const component = { ...Rendered, setup: (props, ctx) => { const state = Rendered.setup(props, ctx); state.query.report = value; state.query.loading = false; return state } }
  const html = await renderToString(createSSRApp(component, { scopeKey: '', refreshVersion: 1 }))
  for (const text of ['当前可读范围','当前余额','原法人时区','历史未知','无有效样本','9,999,999,999,999,999,999,999.99','&lt;img']) assert.ok(html.includes(text), text)
  assert.ok(!html.includes('<img onerror'))
})
test('请求只使用 GET，原类别字符与取消信号保持一致', async () => {
  globalThis.localStorage = { getItem: () => null }
  let request; globalThis.fetch = async (url, options) => { request = { url, options }; return new Response(JSON.stringify(report()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  const controller = new AbortController(); await api.expenseFinancialReport({ ...filter, categoryCode: ' ORIGINAL CODE ' }, controller.signal)
  assert.equal(new URL(request.url, 'http://localhost').searchParams.get('categoryCode'), ' ORIGINAL CODE ')
  assert.equal(request.options.signal, controller.signal); assert.ok(!request.options.body); assert.ok(!request.options.method || request.options.method === 'GET')
})
test('入口与实际页面都要求财务角色，管理员身份不代替财务角色', async () => {
  const { workspaceMenu } = await import(pathToFileURL(resolve(output, 'workspaceNavigation.js')))
  assert.equal(workspaceMenu.flatMap(x => x.items).find(x => x.page === 'expense-reports').access, 'finance')
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  assert.match(app, /roles.includes\('FINANCE'\)/); assert.match(app, /page === 'expense-reports' && canReadFinancialReports/)
  assert.ok(/financialReportScope = computed\([^\n]+roles[^\n]+sort\(\)/.test(app), 'Report scope must include current roles')
  assert.ok(/<ExpenseFinancialReporting[^\n]+:key="financialReportScope"[^\n]+:scope-key="financialReportScope"/.test(app), 'Actual report component must reset on role changes')
})
