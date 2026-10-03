import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const { readExpenseRequestCloseReceipt } = await import(process.env.AGENTFLOW_TEST_EXPENSE_REQUEST_CLOSE)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEREQUESTCLOSEPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_EXPENSEREQUESTCLOSERENDERED)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_EXPENSEWORKSPACECLOSURE)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const id = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const originalApi = { ...api }, originalFetch = globalThis.fetch
globalThis.localStorage = { getItem: () => null }
afterEach(() => { Object.assign(api, originalApi); globalThis.fetch = originalFetch; bindAuthenticationActor(null) })
const receipt = (version = 8) => ({ requestId: id(1), applicationId: id(2), version, closed: true, eventId: id(3) })
const credit = () => ({ id: id(1), applicationId: id(2), legalEntityId: id(4), version: 7, closed: false, lines: [] })
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel(extra = {}) {
  const props = reactive({ item: credit(), scopeKey: 'demo/alice', locked: false, ...extra }), changes = []
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, { ...props, onChanged: () => changes.push('changed') })
  return { props, changes, state: app.mount({}).$.setupState, close: () => app.unmount() }
}

test('关闭额度的损坏成功回执保留原键，恢复仍使用原版本和原因', async () => {
  bindAuthenticationActor({ tenantId: 'credit-close-wire', userId: 'alice', roles: ['EMPLOYEE'] })
  const sent = []; let result = { ...receipt(), requestId: id(8) }
  globalThis.fetch = async (url, options) => {
    sent.push({ url, body: options.body, key: options.headers.get('Idempotency-Key') })
    return new Response(JSON.stringify(result), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  const input = { expectedVersion: 7, comment: '出差结束，不再使用剩余额度' }
  await assert.rejects(api.closeExpenseRequest(id(1), input), error => error.code === 'RESPONSE_UNREADABLE')
  assert.equal(writeRequests.pending().length, 1)
  await assert.rejects(api.closeExpenseRequest(id(1), { ...input, expectedVersion: 8 }), error => error.code === 'PENDING_REQUEST_CHANGED')
  result = receipt()
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent.length, 2)
  assert.equal(sent[0].url, '/api/v1/expense-requests/' + id(1) + '/close')
  assert.equal(sent[0].key, sent[1].key); assert.equal(sent[0].body, sent[1].body)
  assert.deepEqual(JSON.parse(sent[0].body), input)
  assert.equal(writeRequests.pending().length, 0)
})

test('回执拒绝错误版本、未关闭状态、额外明细、缺字段及非法标识', () => {
  const input = { expectedVersion: 7, comment: '结束' }
  for (const value of [{ ...receipt(), version: 7 }, { ...receipt(), version: '8' }, { ...receipt(), closed: false },
    { ...receipt(), balances: [] }, { ...receipt(), eventId: null }, { ...receipt(), applicationId: 'bad' }, {}, null, []]) {
    assert.throws(() => readExpenseRequestCloseReceipt(value, id(1), input), error => error.code === 'RESPONSE_UNREADABLE')
  }
  assert.deepEqual(readExpenseRequestCloseReceipt(receipt(), id(1), input), receipt())
})

test('明确确认及有效原因之前没有写入，提交采用当前额度版本且一次只发送一笔', async () => {
  let done; const sent = []
  api.closeExpenseRequest = async (identity, body) => { sent.push({ identity, body }); return new Promise(resolve => { done = resolve }) }
  const p = panel()
  try {
    await p.state.confirm(); assert.equal(sent.length, 0)
    p.state.begin(); p.state.comment = '   '; await p.state.confirm(); assert.equal(sent.length, 0)
    p.state.comment = 'x'.repeat(2001); await p.state.confirm(); assert.equal(sent.length, 0)
    p.state.cancel(); assert.equal(p.state.comment, ''); assert.equal(p.state.confirming, false)
    p.state.begin(); p.state.comment = '  出差结束  '
    const pending = p.state.confirm(); await p.state.confirm()
    assert.deepEqual(sent, [{ identity: id(1), body: { expectedVersion: 7, comment: '出差结束' } }])
    done(receipt()); await pending
    assert.deepEqual(p.changes, ['changed']); assert.equal(p.state.completed, true); assert.equal(p.state.comment, '')
  } finally { p.close() }
})

test('已关闭、页面锁定和无身份时都不能触发关闭', async () => {
  let sent = 0; api.closeExpenseRequest = async () => { sent++; return receipt() }
  for (const props of [{ locked: true }, { item: { ...credit(), closed: true } }, { scopeKey: '' }]) {
    const p = panel(props)
    try { p.state.begin(); p.state.comment = '结束'; await p.state.confirm(); assert.equal(p.state.confirming, false) }
    finally { p.close() }
  }
  assert.equal(sent, 0)
})

test('版本冲突后必须重新读取并再次确认，不能带旧原因或旧版本重发', async () => {
  let sent = 0; api.closeExpenseRequest = async () => { sent++; throw { status: 409, code: 'CONCURRENCY_CONFLICT' } }
  const p = panel()
  try {
    p.state.begin(); p.state.comment = '结束'; await p.state.confirm()
    assert.match(p.state.error, /刷新/); assert.equal(p.state.refreshRequired, true)
    await p.state.confirm(); assert.equal(sent, 1)
    p.props.item = { ...credit(), version: 8 }; await settle()
    assert.equal(p.state.comment, ''); assert.equal(p.state.confirming, false); assert.equal(p.state.refreshRequired, false)
    await p.state.confirm(); assert.equal(sent, 1)
  } finally { p.close() }
})

test('切换本人身份或卸载时清除原因，迟到成功不污染新页面', async () => {
  for (const switchAccount of [true, false]) {
    let done; api.closeExpenseRequest = async () => new Promise(resolve => { done = resolve })
    const p = panel()
    try {
      p.state.begin(); p.state.comment = '本人原因'; const pending = p.state.confirm()
      if (switchAccount) p.props.scopeKey = 'demo/bob'; else p.close()
      done(receipt()); await pending; await settle()
      assert.equal(p.state.comment, ''); assert.deepEqual(p.changes, []); assert.equal(p.state.completed, false)
    } finally { if (switchAccount) p.close() }
  }
})

test('超时停止等待并保留原键，迟到响应不会取消恢复入口', async () => {
  bindAuthenticationActor({ tenantId: 'credit-close-timeout', userId: 'alice', roles: ['EMPLOYEE'] })
  const originalTimeout = globalThis.setTimeout, originalClear = globalThis.clearTimeout
  let expire, late; const sent = []
  globalThis.setTimeout = callback => { expire = callback; return 1 }; globalThis.clearTimeout = () => {}
  globalThis.fetch = async (_, options) => { sent.push({ key: options.headers.get('Idempotency-Key'), signal: options.signal }); return new Promise(resolve => { late = resolve }) }
  try {
    const pending = api.closeExpenseRequest(id(1), { expectedVersion: 7, comment: '结束' })
    await settle(); expire()
    await assert.rejects(pending, error => ['REQUEST_TIMEOUT', 'NETWORK_ERROR'].includes(error.code))
    assert.equal(sent[0].signal.aborted, true); assert.equal(writeRequests.pending().length, 1)
    late(new Response(JSON.stringify(receipt()), { status: 200 })); await settle()
    assert.equal(writeRequests.pending().length, 1)
    globalThis.fetch = async (_, options) => { sent.push({ key: options.headers.get('Idempotency-Key') }); return new Response(JSON.stringify(receipt()), { status: 200 }) }
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(sent[0].key, sent[1].key); assert.equal(writeRequests.pending().length, 0)
  } finally { globalThis.setTimeout = originalTimeout; globalThis.clearTimeout = originalClear }
})

test('真实确认模板转义原因、关联标签，并展示关闭后的结算边界', async () => {
  const html = await renderToString(createSSRApp({ ...Rendered, setup(input, ctx) {
    const state = Rendered.setup(input, ctx); state.begin(); state.comment.value = '<script>alert(1)</script>'; return state
  } }, { item: credit(), scopeKey: 'demo/render', locked: false }))
  assert.match(html, /确认关闭额度/); assert.match(html, /已有预留仍可结算或释放/)
  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/); assert.ok(!html.includes('<script>'))
  assert.match(html, new RegExp('for="credit-close-reason-' + id(1) + '"'))
  const closed = await renderToString(createSSRApp(Rendered, { item: { ...credit(), closed: true }, scopeKey: 'demo/render' }))
  assert.ok(!closed.includes('确认关闭额度')); assert.ok(!closed.includes('关闭剩余额度')); assert.match(closed, /额度已关闭/)
})

test('本人工作台接入关闭组件，已关闭资源的余额明确标记为停用', async () => {
  const money = { value: '100.00', currency: 'CNY' }
  api.expenseReports = async () => ({ items: [], nextBeforeId: null })
  api.expenseRequests = async () => ({ items: [{ ...credit(), closed: true, lines: [{ lineNo: 1, approved: money, limit: money, available: money, reserved: money, consumed: money }] }, { ...credit(), id: id(5) }], nextBeforeId: null })
  const html = await renderToString(createSSRApp({ ...Workspace, async setup(input, ctx) {
    const state = Workspace.setup(input, ctx); state.tab.value = 'requests'; await state.load(); return state
  } }, { scopeKey: 'demo/workspace', refreshVersion: 0, locked: false }))
  assert.match(html, /未用余额（已停用）/); assert.match(html, /关闭剩余额度/); assert.match(html, /已有预留仍可结算或释放/)
})

test('真实 App 原键恢复分支刷新额度工作台，不把回执当成审批结果', async () => {
  const previousDocument = globalThis.document; globalThis.document = { querySelector: () => null }
  const path = '/expense-requests/' + id(1) + '/close'
  const deps = { actorScope: { value: 'demo/alice' }, draftScope: { value: 'draft' }, pendingWrites: { value: [{ id: 'pending', path }] },
    confirmReplaceDefinition: async (_, action) => action(), busy: { value: false }, recoveryError: { value: '' }, notice: { value: '' }, templateRefresh: { value: 0 },
    writeRequests: { recover: async () => ({ request: { path, body: JSON.stringify({ expectedVersion: 7, comment: '结束' }) }, result: receipt() }) },
    errorMessage: error => error.message, refreshWorkspace: async () => {}, nextTick, workspace: { value: null } }
  try {
    await createRecovery(deps)('pending'); assert.equal(deps.recoveryError.value, '')
    assert.equal(deps.templateRefresh.value, 1); assert.match(deps.notice.value, /原额度关闭结果已确认/)
  } finally { globalThis.document = previousDocument }
})
