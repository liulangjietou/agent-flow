import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { requireAdvanceOffsetSuggestion: requireSuggestion } = await import(process.env.AGENTFLOW_TEST_ADVANCE_OFFSET_SUGGESTION)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_ADVANCEOFFSETSUGGESTIONPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_ADVANCEOFFSETSUGGESTIONRENDERED)
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_EXPENSEEDITOROFFSETEDITOR)
const originalApi = { ...api }, originalFetch = globalThis.fetch
globalThis.localStorage = { getItem: () => null }
afterEach(() => { Object.assign(api, originalApi); globalThis.fetch = originalFetch })
const id = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const money = value => ({ value, currency: 'CNY' })
const until = new Date(Date.now() + 60_000).toISOString()
const detail = () => ({ id: id(1), applicationId: id(2), applicationVersion: 3, financialVersion: 2, editable: true, businessNo: 'FIFO',
  content: { legalEntityId: id(8), type: 'DAILY', title: '测试', lines: [], advanceOffsets: [] } })
const precheck = () => ({ job: { id: id(3), status: 'READY', applicationVersion: 3, financialVersion: 2 }, usable: true, validUntil: until, preview: { approvedGross: money('100.00') } })
const suggestion = () => ({ reportId: id(1), applicationId: id(2), applicationVersion: 3, financialVersion: 2, precheckId: id(3), validUntil: until,
  approvedGross: money('100.00'), offsetTotal: money('40.00'), payable: money('60.00'), selectionLimitReached: false,
  items: [{ advanceId: id(4), version: 1, paidOn: '2026-09-01', capacity: money('40.00'), amount: money('40.00') }] })
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel(extra = {}) {
  const props = reactive({ detail: detail(), precheck: precheck(), scopeKey: 'demo/alice', locked: false, ...extra }), applied = []
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, { ...props, onApply: value => applied.push(value) })
  return { props, applied, state: app.mount({}).$.setupState, close: () => app.unmount() }
}

test('建议查询只发无缓存 GET，不传入页面金额或申请人', async () => {
  let call
  globalThis.fetch = async (url, options) => { call = { url, options }; return new Response(JSON.stringify(suggestion()), { status: 200 }) }
  const signal = new AbortController().signal
  await api.advanceOffsetSuggestion(id(1), id(3), signal)
  assert.equal(call.url, `/api/v1/expense-reports/${id(1)}/prechecks/${id(3)}/advance-offset-suggestion`)
  assert.equal(call.options.cache, 'no-store'); assert.equal(call.options.signal, signal); assert.equal(call.options.body, undefined)
})

test('建议绑定双版本、原预检、有效期、精确金额、顺序和借款唯一性', () => {
  assert.deepEqual(requireSuggestion(suggestion(), detail(), precheck()), suggestion())
  const bad = [null, { ...suggestion(), reportId: id(9) }, { ...suggestion(), applicationId: id(9) },
    { ...suggestion(), applicationVersion: 4 }, { ...suggestion(), financialVersion: 3 }, { ...suggestion(), precheckId: id(9) },
    { ...suggestion(), validUntil: '2020-01-01T00:00:00Z' }, { ...suggestion(), offsetTotal: money('40.01') },
    { ...suggestion(), payable: money('59.99') }, { ...suggestion(), approvedGross: money('101') },
    { ...suggestion(), selectionLimitReached: true }, { ...suggestion(), items: [suggestion().items[0], suggestion().items[0]] },
    { ...suggestion(), items: [{ ...suggestion().items[0], amount: money('41') }] },
    { ...suggestion(), items: [{ ...suggestion().items[0], paidOn: '2026-02-30' }] },
    { ...suggestion(), items: [{ ...suggestion().items[0], amount: { value: 40, currency: 'CNY' } }] },
    { ...suggestion(), items: [{ ...suggestion().items[0], capacity: { value: '50', currency: 'USD' } }] }]
  for (const value of bad) assert.throws(() => requireSuggestion(value, detail(), precheck()))
  assert.throws(() => requireSuggestion(suggestion(), detail(), { ...precheck(), usable: false }))
  assert.throws(() => requireSuggestion(suggestion(), detail(), precheck(), Date.parse(until)))
})

test('初次展示不改草稿，明确确认后重新读取相同建议才采纳', async () => {
  let reads = 0; api.advanceOffsetSuggestion = async () => { reads++; return suggestion() }
  const p = panel()
  try {
    await settle(); assert.equal(reads, 1); assert.equal(p.applied.length, 0)
    await p.state.load(true); assert.equal(reads, 1)
    p.state.prepare(); assert.equal(p.state.confirming, true); await p.state.load(true)
    assert.equal(reads, 2); assert.deepEqual(p.applied, [suggestion()])
  } finally { p.close() }
})

test('复核发现借款变化时更新预览并要求再次明确确认', async () => {
  let current = suggestion(); api.advanceOffsetSuggestion = async () => structuredClone(current)
  const p = panel()
  try {
    await settle(); p.state.prepare(); current.items[0].version = 2; await p.state.load(true)
    assert.equal(p.applied.length, 0); assert.equal(p.state.confirming, false); assert.match(p.state.notice, /已变化/)
    p.state.prepare(); await p.state.load(true); assert.equal(p.applied.length, 1)
  } finally { p.close() }
})

test('已采用相同建议、锁定、无身份及过期时不覆盖草稿', async () => {
  let reads = 0; api.advanceOffsetSuggestion = async () => { reads++; return suggestion() }
  const current = detail(); current.content.advanceOffsets = suggestion().items.map(({ advanceId, amount }) => ({ advanceId, amount }))
  const p = panel({ detail: current })
  try { await settle(); p.state.prepare(); await p.state.load(true); assert.equal(reads, 1); assert.equal(p.applied.length, 0) } finally { p.close() }
  for (const extra of [{ locked: true }, { scopeKey: '' }]) {
    const q = panel(extra)
    try { await settle(); q.state.prepare(); await q.state.load(true); assert.equal(q.applied.length, 0); assert.equal(q.state.confirming, false) } finally { q.close() }
  }
})

test('切换身份或预检后，忽略晚到的旧读取及旧采纳请求', async () => {
  let done; api.advanceOffsetSuggestion = async () => new Promise(resolve => { done = resolve })
  const p = panel()
  try {
    const old = done; p.props.scopeKey = ''; old(suggestion()); await settle()
    assert.equal(p.state.suggestion, null); assert.equal(p.applied.length, 0)
    p.props.scopeKey = 'demo/alice'; await settle(); done(suggestion()); await settle(); p.state.prepare()
    const pending = p.state.load(true); p.props.precheck = { ...precheck(), usable: false }; done(suggestion()); await pending
    assert.equal(p.applied.length, 0); assert.equal(p.state.suggestion, null)
  } finally { p.close() }
})

test('采纳复核读取失败或损坏响应清空确认，不产生写入', async () => {
  api.advanceOffsetSuggestion = async () => suggestion(); const p = panel()
  try {
    await settle(); p.state.prepare(); api.advanceOffsetSuggestion = async () => { throw { code: 'RESOURCES_CHANGED' } }; await p.state.load(true)
    assert.equal(p.applied.length, 0); assert.equal(p.state.suggestion, null); assert.equal(p.state.confirming, false); assert.match(p.state.error, /已变化/)
    api.advanceOffsetSuggestion = async () => ({ ...suggestion(), reportId: id(99) }); await p.state.load()
    assert.equal(p.state.suggestion, null); assert.equal(p.applied.length, 0)
  } finally { p.close() }
})

test('真实编辑器采纳后变为待保存，旧版本和重复迟到采纳不能覆盖人工调整', async () => {
  api.financeCatalog = async () => ({ validUntil: until, legalEntities: [], categories: [] })
  const props = reactive({ scopeKey: 'fifo-editor/alice', initial: detail(), locked: false })
  const app = renderer.createApp({ ...Editor, setup: (_, ctx) => Editor.setup(props, ctx), render: () => null }, props)
  const state = app.mount({}).$.setupState
  try {
    await settle(); assert.equal(state.dirty, false)
    state.applyOffsets({ ...suggestion(), financialVersion: 99 }); assert.equal(state.dirty, false)
    state.applyOffsets(suggestion()); assert.equal(state.dirty, true); assert.equal(state.state.content.advanceOffsets[0].amount.value, '40.00')
    state.state.content.advanceOffsets[0].amount.value = '30.00'; state.applyOffsets(suggestion())
    assert.equal(state.state.content.advanceOffsets[0].amount.value, '30.00'); assert.match(state.notice, /保存后请重新预检/)
  } finally { state.leave(); app.unmount() }
})

test('实际模板区分建议与已预留，并显示确认替换及 50 笔上限', async () => {
  const view = suggestion(); view.selectionLimitReached = true
  const props = { detail: detail(), precheck: precheck(), scopeKey: '', locked: false }
  const app = createSSRApp({ ...Rendered, setup: (_, ctx) => { const state = Rendered.setup(props, ctx); state.suggestion.value = view; state.confirming.value = true; return state } }, props)
  const html = await renderToString(app)
  assert.match(html, /50 笔上限/); assert.match(html, /确认替换草稿冲销/); assert.match(html, /保存草稿并重新预检/); assert.match(html, /2026-09-01/)
})
