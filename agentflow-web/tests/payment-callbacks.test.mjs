import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_PAYMENT_CALLBACKS)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_PAYMENTCALLBACKINBOX)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const id = '00000000-0000-4000-8000-000000000001', bank = '00000000-0000-4000-8000-000000000002', older = '00000000-0000-4000-8000-000000000003'
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const row = () => ({ id, eventId: 'evt_original', kind: 'SUPPLIER', authorizationId: bank, sourceRevision: 2, version: 2, status: 'REVIEW_REQUIRED', receivedAt: '2026-09-29T10:00:00Z', updatedAt: '2026-09-29T10:00:01Z', nextAttemptAt: null, failures: 0, queryVersion: null, reason: 'NEVER_DISPATCHED', requestedBy: null, requestReason: null })
const detail = () => ({ callback: row(), history: [row(), { ...row(), version: 1, status: 'RECEIVED', reason: null, updatedAt: row().receivedAt, nextAttemptAt: row().receivedAt }] })
const page = () => ({ items: [row()], nextBeforeId: null })
const receipt = reason => ({ ...row(), version: 3, status: 'RECEIVED', reason: null, updatedAt: '2026-09-29T10:00:02Z', nextAttemptAt: '2026-09-29T10:00:02Z', requestedBy: 'admin', requestReason: reason })
let scope = 0
function mount() {
  const props = reactive({ scopeKey: 'demo/admin-' + ++scope, refreshVersion: 0, locked: false })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { props, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}
function reads() { api.paymentCallbacks = async () => page(); api.paymentCallback = async () => detail() }

test('实际 HTTP 省略空字段时仍能读取列表、历史和恢复回执', async () => {
  const wire = value => JSON.parse(JSON.stringify(value, (_key, field) => field === null ? undefined : field))
  const source = wire(detail())
  assert.deepEqual(rules.validateCallbackDetail(source, id), detail())
  assert.equal(source.callback.nextAttemptAt, undefined)
  assert.deepEqual(rules.validateCallbackPage(wire(page())), page())
  assert.deepEqual(rules.validateCallbackRetry(wire(receipt('核对')), row(), '核对'), receipt('核对'))
  assert.throws(() => rules.validatePaymentCallback({ ...wire(row()), reason: undefined }))
  assert.throws(() => rules.validatePaymentCallback({ ...wire(receipt('核对')), nextAttemptAt: undefined }))
  api.paymentCallbacks = async () => wire(page()); api.paymentCallback = async () => wire(detail())
  const p = mount()
  try { await settle(); await p.state.inspect(id); assert.equal(p.state.error, ''); assert.equal(p.state.detail.callback.status, 'REVIEW_REQUIRED') }
  finally { p.close() }
})

test('处理状态与银行结果分开，未完整或矛盾状态不能开启恢复', () => {
  assert.equal(rules.validatePaymentCallback(row()).status, 'REVIEW_REQUIRED')
  for (const mutate of [v => v.status = 'SUCCEEDED', v => v.status = 'QUERY_QUEUED', v => v.nextAttemptAt = v.updatedAt, v => v.sourceRevision = '2', v => v.queryVersion = 5, v => v.requestedBy = 'admin', v => v.failures = 11, v => v.updatedAt = '2025-01-01', v => v.id = 'foreign', v => v.status = '__proto__']) {
    const value = row(); mutate(value); assert.throws(() => rules.validatePaymentCallback(value))
  }
  const queried = { ...row(), status: 'QUERY_QUEUED', queryVersion: 8, reason: 'ALREADY_OBSERVED' }
  assert.equal(rules.validatePaymentCallback(queried).queryVersion, 8); assert.equal(rules.callbackStatuses.QUERY_QUEUED, '已登记原号查询')
})
test('有界分页拒绝重复游标，详情与每次修订固定同一事件', () => {
  assert.doesNotThrow(() => rules.validateCallbackPage(page()))
  assert.throws(() => rules.validateCallbackPage(page(), id)); assert.throws(() => rules.validateCallbackPage({ items: [row(), row()], nextBeforeId: null }))
  assert.throws(() => rules.validateCallbackPage({ ...page(), nextBeforeId: older }))
  assert.doesNotThrow(() => rules.validateCallbackDetail(detail(), id))
  for (const change of [value => value.callback.id = older, value => value.history[1].eventId = 'other', value => value.history[1].version = 4, value => value.history = []]) {
    const value = detail(); change(value); assert.throws(() => rules.validateCallbackDetail(value, id))
  }
  const reordered = detail(); reordered.history[0] = Object.fromEntries(Object.entries(reordered.callback).reverse())
  assert.doesNotThrow(() => rules.validateCallbackDetail(reordered, id))
})
test('原事件恢复要求明确原因、精确版本和回执，不携带资金事实', () => {
  assert.deepEqual(rules.callbackRetryInput(row(), ' 核对后恢复 '), { expectedVersion: 2, reason: '核对后恢复' })
  for (const reason of ['', ' ', 'a'.repeat(501)]) assert.throws(() => rules.callbackRetryInput(row(), reason))
  assert.throws(() => rules.callbackRetryInput(receipt('核对'), '核对'))
  assert.doesNotThrow(() => rules.validateCallbackRetry(receipt('核对'), row(), ' 核对 '))
  for (const changed of [{ id: older }, { authorizationId: older }, { sourceRevision: 3 }, { version: 4 }, { requestReason: 'other' }, { requestedBy: null }]) {
    assert.throws(() => rules.validateCallbackRetry({ ...receipt('核对'), ...changed }, row(), '核对'))
  }
})
test('只在明确确认后恢复，连续点击仅发送一次并刷新处理状态', async () => {
  reads(); let finish, calls = 0
  api.retryPaymentCallback = async (key, input) => { calls++; assert.equal(key, id); assert.deepEqual(input, { expectedVersion: 2, reason: '核对' }); return new Promise(resolve => finish = resolve) }
  const p = mount()
  try {
    await settle(); await p.state.inspect(id); await p.state.retry(); assert.equal(calls, 0)
    p.state.prepareRetry(); p.state.reason = '核对'; const sending = p.state.retry(); await p.state.retry(); assert.equal(calls, 1); assert.equal(p.state.saving, true)
    p.props.refreshVersion++; await settle(); assert.equal(p.state.saving, true)
    finish(receipt('核对')); await sending; assert.equal(p.state.saving, false); assert.equal(p.state.detail, null); assert.match(p.state.notice, /原回调已重新排队/)
  } finally { p.close() }
})
test('身份切换清空旧详情、恢复原因和忙碌状态，迟到写入不能污染新账号', async () => {
  reads(); let finish; api.retryPaymentCallback = async () => new Promise(resolve => finish = resolve); const p = mount()
  try {
    await settle(); await p.state.inspect(id); p.state.prepareRetry(); p.state.reason = '旧账号核对'; const sending = p.state.retry()
    api.paymentCallbacks = async () => ({ items: [], nextBeforeId: null }); p.props.scopeKey = 'foreign/admin'; await settle()
    assert.equal(p.state.detail, null); assert.equal(p.state.reason, ''); assert.equal(p.state.saving, false); assert.equal(p.state.confirmation, false)
    finish(receipt('旧账号核对')); await sending; assert.equal(p.state.notice, ''); assert.deepEqual(p.state.page.items, [])
  } finally { p.close() }
})
test('旧详情迟到与读取超时不能重新打开处理按钮', async () => {
  reads(); let finish, signal; const p = mount(); const timer = global.setTimeout, clear = global.clearTimeout
  try {
    await settle(); api.paymentCallback = async (_id, value) => { signal = value; return new Promise(resolve => finish = resolve) }
    const old = p.state.inspect(id); p.props.scopeKey = 'another/admin'; await settle(); assert.equal(signal.aborted, true)
    finish(detail()); await old; assert.equal(p.state.detail, null)
    let expire; global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const timeout = p.state.inspect(id); expire(); assert.equal(signal.aborted, true); finish(detail()); await timeout
    assert.equal(p.state.detail, null); assert.match(p.state.error, /超时/)
  } finally { global.setTimeout = timer; global.clearTimeout = clear; p.close() }
})
test('历史翻页替换整页，失败时清除旧记录和操作意图', async () => {
  reads(); api.paymentCallbacks = async cursor => cursor ? { items: [{ ...row(), id: older }], nextBeforeId: null } : { ...page(), nextBeforeId: id }
  const p = mount()
  try {
    await settle(); await p.state.inspect(id); p.state.prepareRetry(); await p.state.load(id)
    assert.equal(p.state.page.items.length, 1); assert.equal(p.state.page.items[0].id, older); assert.equal(p.state.detail, null); assert.equal(p.state.confirmation, false)
    api.paymentCallbacks = async () => { throw { code: 'FORBIDDEN' } }; await p.state.load(); assert.equal(p.state.page, null); assert.match(p.state.error, /权限/)
  } finally { p.close() }
})
test('错误恢复回执阻止再次提交，必须刷新并重新核对原记录', async () => {
  reads(); let calls = 0; api.retryPaymentCallback = async () => { calls++; return { ...receipt('核对'), id: older } }; const p = mount()
  try {
    await settle(); await p.state.inspect(id); p.state.prepareRetry(); p.state.reason = '核对'; await p.state.retry()
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); await p.state.retry(); assert.equal(calls, 1)
    await p.state.load(); assert.equal(p.state.requiresRefresh, false); assert.equal(p.state.detail, null); assert.equal(p.state.confirmation, false)
  } finally { p.close() }
})
test('结果未确认时沿用原幂等键和正文恢复，刷新本身不重发', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'callback-admin-' + ++scope, roles: ['ADMIN'] }); reads()
  let failed = true; const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); if (failed) throw new Error('lost response'); return new Response(JSON.stringify(receipt('核对')), { status: 200 }) }
  const p = mount()
  try {
    await settle(); await p.state.inspect(id); p.state.prepareRetry(); p.state.reason = '核对'; await p.state.retry(); assert.equal(p.state.unconfirmed, true)
    await p.state.load(); await p.state.inspect(id); p.state.prepareRetry(); await p.state.retry(); assert.equal(calls.length, 1)
    failed = false; await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); await p.state.load(); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})
test('真实 API 有界分页、路径编码及禁用缓存，恢复进入原写入队列', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  bindAuthenticationActor({ tenantId: 'demo', userId: 'callback-path-' + ++scope, roles: ['ADMIN'] })
  try {
    await api.paymentCallbacks('before/other', new AbortController().signal)
    await api.paymentCallback('callback/other', new AbortController().signal)
    await api.retryPaymentCallback('callback/other', { expectedVersion: 2, reason: '核对' })
    const list = new URL(calls[0].url, 'http://localhost'); assert.equal(list.pathname, '/api/v1/integrations/payment/callbacks')
    assert.deepEqual(Object.fromEntries(list.searchParams), { limit: '25', beforeId: 'before/other' }); assert.equal(calls[0].init.cache, 'no-store')
    assert.equal(calls[1].url, '/api/v1/integrations/payment/callbacks/callback%2Fother'); assert.equal(calls[1].init.cache, 'no-store')
    assert.equal(calls[2].url, '/api/v1/integrations/payment/callbacks/callback%2Fother/retry'); assert.ok(calls[2].init.headers.get('Idempotency-Key'))
  } finally { global.fetch = originalFetch; bindAuthenticationActor(null) }
})
