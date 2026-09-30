import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_PAYMENT_BATCHES)
const { default: Composer } = await import(process.env.AGENTFLOW_TEST_PAYMENTBATCHCOMPOSER)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_PAYMENTBATCHWORKSPACE)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'batch-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const view = (id = 'one', amount = '100.01') => ({ payment: { id, applicationId: 'app-' + id, businessId: 'advance-' + id, roundNo: 1, applicationVersion: 8, businessVersion: 3, version: 1, status: 'AUTHORIZED', purpose: 'EMPLOYEE_ADVANCE', legalEntityId: 'entity', employeeId: 'alice', amount: { value: amount, currency: 'CNY' }, maskedPayeeAccount: '****1234', authorizedBy: 'finance', authorizedAt: new Date(Date.now() - 1000).toISOString(), expiresAt: new Date(Date.now() + 60000).toISOString(), executedBy: null, request: null, operation: null, retirement: null }, actions: { execute: true, query: false, resendOriginal: false } })
const options = id => ({ authorizationId: id, authorizationVersion: 1, validUntil: new Date(Date.now() + 60000).toISOString(), items: [{ reference: 'debit-1', displayName: '基本户', maskedAccount: '****4567', currency: 'CNY', sourceVersion: 'v1' }] })
const receipt = () => ({ batchId: 'batch', createdAt: new Date().toISOString(), itemCount: 2 })
const summary = () => ({ id: 'batch', legalEntityId: 'entity', currency: 'CNY', total: '200.02', itemCount: 2, cashier: 'cashier', createdAt: new Date().toISOString() })
const detail = () => ({ batch: summary(), comment: '批次说明', items: ['one', 'two'].map(id => {
  const current = view(id); current.actions.execute = false; current.payment.request = { id: 'request-' + id, version: 1, status: 'QUEUED', cashier: 'cashier', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), issue: null }
  return { authorizationVersion: 1, requestId: 'request-' + id, current }
}) })
let sequence = 0
function mount(Component, values = {}) {
  const props = reactive({ scopeKey: 'batch-scope-' + ++sequence, locked: false, items: [view(), view('two')], refreshVersion: 1, ...values }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(['busy', value]), onSubmitted: id => events.push(['submitted', id]) })
  const instance = app.mount({})
  return { props, events, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('组批固定同法人币种与原授权，合计超出单笔上限仍保持分位精度', () => {
  const values = [view('one', '999999999999999.99'), view('two', '999999999999999.99')]
  assert.equal(rules.batchTotal(values), '1999999999999999.98'); assert.doesNotThrow(() => rules.validateBatchSelection(values))
  for (const mutate of [items => items[1].payment.legalEntityId = 'other', items => items[1].payment.amount.currency = 'USD', items => items[1].payment.id = 'one', items => items[1].actions.execute = false, items => items[1].payment.version = 2, items => items[1].payment.expiresAt = items[1].payment.authorizedAt]) {
    const invalid = clone(values); mutate(invalid); assert.throws(() => rules.validateBatchSelection(invalid))
  }
  assert.throws(() => rules.validateBatchSelection([])); assert.throws(() => rules.validateBatchSelection(Array.from({ length: 26 }, (_, index) => view(String(index)))))
})

test('每笔目录必须新鲜且共同账户版本一致，未选账户不能生成请求', () => {
  const values = [view(), view('two')], directories = [options('one'), options('two')]
  assert.equal(rules.commonBatchAccounts(values, directories).length, 1)
  assert.deepEqual(rules.paymentBatchInput(values, directories, 'debit-1', ' 核对两笔 '), { items: [{ authorizationId: 'one', authorizationVersion: 1 }, { authorizationId: 'two', authorizationVersion: 1 }], debitAccountReference: 'debit-1', debitAccountVersion: 'v1', comment: '核对两笔' })
  assert.throws(() => rules.paymentBatchInput(values, directories, '', '说明')); assert.throws(() => rules.paymentBatchInput(values, directories, 'debit-1', '  '))
  for (const field of ['sourceVersion', 'maskedAccount', 'displayName', 'reference']) { const invalid = clone(directories); invalid[1].items[0][field] = field === 'maskedAccount' ? '****9999' : 'other'; assert.equal(rules.commonBatchAccounts(values, invalid).length, 0) }
  for (const mutate of [items => items.pop(), items => items[1].authorizationId = 'other', items => items[1].authorizationVersion = 2, items => items[1].validUntil = '2000-01-01T00:00:00Z']) {
    const invalid = clone(directories); mutate(invalid); assert.throws(() => rules.commonBatchAccounts(values, invalid))
  }
})

test('历史明细绑定原请求、原出纳及精确合计，不把登记回执解释为成功', () => {
  const value = detail(); assert.equal(rules.validateBatchDetail(value, 'batch'), value)
  for (const mutate of [v => v.batch.total = '200.01', v => v.batch.cashier = 'other', v => v.items[1].requestId = 'different', v => v.items[1].current.payment.legalEntityId = 'other', v => v.items[1] = clone(v.items[0]), v => v.items[0].current.payment.request = null, v => v.items[0].authorizationVersion = 2]) { const invalid = clone(value); mutate(invalid); assert.throws(() => rules.validateBatchDetail(invalid, 'batch')) }
  assert.throws(() => rules.validateBatchDetail(value, 'other'))
  const input = rules.paymentBatchInput([view(), view('two')], [options('one'), options('two')], 'debit-1', '确认')
  assert.doesNotThrow(() => rules.validateBatchReceipt(receipt(), input)); assert.throws(() => rules.validateBatchReceipt({ ...receipt(), itemCount: 1 }, input))
})

test('实际面板读取不付款，取消不写入，明确账户与说明后双击只登记一次', async () => {
  const writes = []; let complete; api.paymentAccounts = async id => options(id); api.submitPaymentBatch = input => { writes.push(input); return new Promise(resolve => complete = resolve) }
  const p = mount(Composer)
  try {
    await p.state.prepare(); assert.equal(p.state.selected, ''); assert.equal(writes.length, 0); p.state.clear(); assert.equal(writes.length, 0)
    await p.state.prepare(); p.state.comment = '核对两笔'; await p.state.execute(); assert.equal(writes.length, 0)
    p.state.selected = 'debit-1'; const first = p.state.execute(); await p.state.execute(); assert.equal(writes.length, 1)
    complete(receipt()); await first; assert.ok(p.events.some(event => event[0] === 'submitted' && event[1] === 'batch')); assert.equal(p.state.pending, false)
  } finally { p.close() }
})

test('选中成员变化会取消账户查询，迟到账户不能应用于新一批付款', async () => {
  const calls = []; api.paymentAccounts = (id, signal) => new Promise(resolve => calls.push({ id, signal, resolve })); const p = mount(Composer)
  try {
    const read = p.state.prepare(); p.props.items = [view('three')]; assert.equal(calls[0].signal.aborted, true)
    for (const call of calls) call.resolve(options(call.id)); await read
    assert.deepEqual(p.state.accounts, []); assert.equal(p.state.pending, false); assert.equal(p.state.selected, '')
  } finally { p.close() }
})

test('切换身份后清空说明与选择，旧身份登记回执不会触发当前页面成功事件', async () => {
  api.paymentAccounts = async id => options(id); let complete; api.submitPaymentBatch = () => new Promise(resolve => complete = resolve); const p = mount(Composer)
  try {
    await p.state.prepare(); p.state.selected = 'debit-1'; p.state.comment = '旧身份说明'; const write = p.state.execute(); p.props.scopeKey = 'new-cashier'
    complete(receipt()); await write; assert.equal(p.state.comment, ''); assert.equal(p.state.selected, ''); assert.equal(p.state.saving, false); assert.equal(p.events.filter(event => event[0] === 'submitted').length, 0)
  } finally { p.close() }
})

test('账户目录超时会中止整批读取，迟到数据不能恢复账户选择', async () => {
  const calls = []; api.paymentAccounts = (id, signal) => new Promise(resolve => calls.push({ id, signal, resolve })); const p = mount(Composer)
  const timer = global.setTimeout, clearTimer = global.clearTimeout; let expire
  try {
    global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const read = p.state.prepare(); expire(); assert.ok(calls.every(call => call.signal.aborted)); assert.equal(p.state.loading, false)
    for (const call of calls) call.resolve(options(call.id)); await read; assert.deepEqual(p.state.accounts, []); assert.match(p.state.error, /超时/)
  } finally { global.setTimeout = timer; global.clearTimeout = clearTimer; p.close() }
})

test('失联恢复保留整批原正文和幂等键，恢复后必须重新读取目录', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'cashier-' + ++sequence, roles: ['CASHIER'] })
  api.paymentAccounts = async id => options(id); const calls = []; let failing = true
  global.fetch = async (url, init) => { calls.push({ url, init }); if (failing) throw new Error('lost response'); return new Response(JSON.stringify(receipt()), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const p = mount(Composer)
  try {
    await p.state.prepare(); p.state.selected = 'debit-1'; p.state.comment = '原批次'; await p.state.execute(); assert.equal(calls.length, 1); assert.equal(p.state.unconfirmed, true)
    p.state.clear(); await p.state.prepare(); await p.state.execute(); assert.equal(calls.length, 1)
    const original = writeRequests.pending()[0]; assert.equal(original.path, '/payment-batches'); failing = false; await writeRequests.recover(original.id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key')); assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true)
  } finally { p.close() }
})

test('工作台拒绝跨法人勾选，重复分页不追加，刷新清空旧选择', async () => {
  const first = view(), second = view('two'); second.payment.legalEntityId = 'other'
  api.cashierPayments = async before => ({ items: before ? [first] : [first, second], nextBeforeId: before ? null : 'two' }); const p = mount(Workspace)
  try {
    await settle(); p.state.toggle(first); p.state.toggle(second); assert.deepEqual(p.state.checked, ['one']); assert.match(p.state.error, /同一法人/)
    await p.state.load(true); assert.equal(p.state.payments.length, 2); assert.match(p.state.error, /发生变化/)
    await p.state.load(); assert.deepEqual(p.state.checked, [])
  } finally { p.close() }
})

test('工作台身份变化后忽略旧批次目录和明细，空范围不显示上个账号付款', async () => {
  api.cashierPayments = async () => ({ items: [], nextBeforeId: null }); const calls = []
  api.paymentBatches = (before, signal) => new Promise(resolve => calls.push({ signal, resolve })); const p = mount(Workspace)
  try {
    await settle(); p.state.switchMode('history'); p.props.scopeKey = 'another'; assert.equal(calls[0].signal.aborted, true)
    calls[0].resolve({ items: [summary()], nextBeforeId: null }); await settle(); assert.deepEqual(p.state.batches, [])
    calls[1].resolve({ items: [], nextBeforeId: null }); await settle()
    let resolve; api.paymentBatch = () => new Promise(done => resolve = done); const read = p.state.open('batch'); p.props.scopeKey = ''; resolve(detail()); await read; assert.equal(p.state.detail, null)
  } finally { p.close() }
})

test('登记成功后的目录刷新不能覆盖用户随后切换的新建批次视图', async () => {
  api.cashierPayments = async () => ({ items: [view()], nextBeforeId: null }); let complete; const reads = []
  api.paymentBatches = () => new Promise(resolve => complete = resolve)
  api.paymentBatch = async id => { reads.push(id); return detail() }
  const p = mount(Workspace)
  try {
    await settle(); const pending = p.state.submitted('batch'); p.state.switchMode('create'); await settle()
    complete({ items: [summary()], nextBeforeId: null }); await pending
    assert.equal(p.state.mode, 'create'); assert.equal(p.state.payments.length, 1); assert.deepEqual(reads, []); assert.equal(p.state.detail, null)
  } finally { p.close() }
})

test('批次 API 使用有界分页、转义编号和禁用缓存', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}') }
  try { const signal = new AbortController().signal; await api.paymentBatches('before/id', signal); await api.paymentBatch('batch/id', signal)
    assert.match(calls[0].url, /payment-batches\?limit=25&beforeId=before%2Fid$/); assert.match(calls[1].url, /payment-batches\/batch%2Fid$/); assert.ok(calls.every(call => call.init.cache === 'no-store'))
  } finally { global.fetch = originalFetch }
})
