import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_SUPPLIER_RETURN)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_SUPPLIERPAYMENTRETURN)
const { default: Parent } = await import(process.env.AGENTFLOW_TEST_SUPPLIERFINANCESTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve)), money = value => ({ value, currency: 'CNY' })
const id = number => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
const binding = () => ({ paymentId: id(1), requestId: id(2), applicationId: id(3), roundNo: 1 })
function view() {
  const at = new Date(Date.now() - 5000).toISOString()
  return { ...binding(), operationVersion: 5, bankStatus: 'SUCCEEDED', returnVersion: 2, reviewRequired: true,
    original: { paymentReference: 'original-bank', receiptReference: 'original-receipt', amount: money('100.00'), paidAt: new Date(Date.parse(at) - 30000).toISOString() },
    totalReturned: money('0.00'), netPaid: money('100.00'), returns: [], registrations: [], nextBeforeVersion: null, canQuery: true,
    latestCheck: { id: id(4), version: 3, status: 'CHECKED', requestedAt: at, updatedAt: at, issue: null, registrationIssue: null, canRegister: true,
      evidence: { outcome: 'PARTIALLY_RETURNED', revision: 1, observedAt: at, validUntil: new Date(Date.parse(at) + 300000).toISOString(), bankRevision: 1, bankStatus: 'SUCCEEDED',
        returns: [{ transactionReference: 'actual-return-1', amount: money('20.00'), receivedAt: at }], totalReturned: money('20.00'), newReturned: money('20.00') } } }
}
function receipt(input) { return { paymentId: id(1), operationVersion: input.operationVersion, checkId: 'checkId' in input ? input.checkId : id(5), checkVersion: 'checkId' in input ? input.checkVersion + 1 : 1, registrationId: 'checkId' in input ? id(6) : null, returnVersion: 'checkId' in input ? input.returnVersion + 1 : Math.max(1, input.returnVersion), auditEventId: id(7) } }
function registered(value = view()) {
  value.returnVersion = 3; value.returns = [{ registrationId: id(6), funding: structuredClone(value.latestCheck.evidence.returns[0]) }]
  value.totalReturned = money('20.00'); value.netPaid = money('80.00'); value.latestCheck.evidence.newReturned = money('0.00')
  value.latestCheck.status = 'RESOLVED'; value.latestCheck.canRegister = false; value.latestCheck.registrationIssue = 'SUPPLIER_PAYMENT_RETURN_EVIDENCE_UNAVAILABLE'; value.latestCheck.version = 4
  value.registrations = [{ id: id(6), returnVersion: 3, outcome: 'PARTIALLY_RETURNED', totalReturned: money('20.00'), registeredBy: 'finance', registeredAt: value.latestCheck.updatedAt, evidenceReference: 'proof-1', reason: '核对入款' }]
  return value
}
let scope = 0
function mount(component = Component) {
  const props = reactive({ ...binding(), applicationVersion: 3, requestVersion: 2, scopeKey: 'supplier-return-' + ++scope, locked: false }), changed = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, { ...props, onChanged: () => changed.push(true) })
  const instance = app.mount({})
  return { props, state: instance.$.setupState, changed, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}
function prepare(item) { item.state.prepare('REGISTER'); item.state.reference = 'proof'; item.state.comment = '核对公司实际入款'; item.state.acknowledged = true }

test('原付款、累计金额和实际入款不一致时禁止采用响应', () => {
  assert.doesNotThrow(() => rules.validateSupplierReturn(view(), binding()))
  const full = view(); full.bankStatus = 'REVERSED'; full.latestCheck.evidence.bankStatus = 'REVERSED'; full.latestCheck.evidence.outcome = 'RETURNED'
  full.latestCheck.evidence.returns[0].amount = money('100.00'); full.latestCheck.evidence.totalReturned = money('100.00'); full.latestCheck.evidence.newReturned = money('100.00')
  assert.doesNotThrow(() => rules.validateSupplierReturn(full, binding()))
  for (const change of [v => v.paymentId = id(99), v => v.roundNo++, v => v.operationVersion = null, v => v.netPaid.value = '99.00',
    v => v.latestCheck.evidence.returns[0].creditAccountReference = 'private', v => v.latestCheck.evidence.returns[0].amount.value = '20.01',
    v => v.latestCheck.evidence.returns.push(structuredClone(v.latestCheck.evidence.returns[0])), v => v.latestCheck.evidence.returns[0].transactionReference = 'original-receipt',
    v => v.latestCheck.evidence.bankStatus = 'REVERSED', v => v.latestCheck.evidence.outcome = 'RETURNED', v => v.latestCheck.evidence.newReturned.value = '0.00',
    v => v.latestCheck.evidence.validUntil = 'bad', v => v.latestCheck.evidence.bankRevision = 0, v => v.reviewRequired = false, v => v.bankStatus = 'UNKNOWN']) {
    const invalid = view(); change(invalid); assert.throws(() => rules.validateSupplierReturn(invalid, binding()))
  }
})
test('无首次成功付款时金额为空，不能展示查询能力', () => {
  const value = { ...view(), operationVersion: null, bankStatus: null, returnVersion: 0, original: null, totalReturned: null, netPaid: null, latestCheck: null, reviewRequired: false, canQuery: false }
  assert.doesNotThrow(() => rules.validateSupplierReturn(value, binding())); assert.equal(rules.supplierReturnAllowed(value, 'QUERY'), false)
  value.canQuery = true; assert.throws(() => rules.validateSupplierReturn(value, binding()))
})
test('历史分页不改当前累计资金，登记按版本倒序且游标必须指向页尾', () => {
  const value = registered(); value.returnVersion = 5
  value.registrations.unshift({ ...value.registrations[0], id: id(8), returnVersion: 5 }); value.nextBeforeVersion = 3
  assert.doesNotThrow(() => rules.validateSupplierReturn(value, binding()))
  const older = structuredClone(value); older.registrations = [older.registrations[1]]; older.nextBeforeVersion = null
  assert.doesNotThrow(() => rules.validateSupplierReturn(older, binding(), 5))
  for (const change of [v => v.registrations.reverse(), v => v.nextBeforeVersion = 4, v => v.registrations[1].totalReturned.value = '21.00', v => v.registrations[1].id = id(8)]) {
    const invalid = structuredClone(value); change(invalid); assert.throws(() => rules.validateSupplierReturn(invalid, binding()))
  }
  assert.throws(() => rules.validateSupplierReturn(older, binding(), 3))
})
test('已登记资金必须保持，陈旧原件可以展示但不能据此办理', () => {
  const value = registered(); value.latestCheck.status = 'CHECKED'; value.latestCheck.canRegister = true; value.latestCheck.registrationIssue = null
  assert.doesNotThrow(() => rules.validateSupplierReturn(value, binding()))
  value.latestCheck.evidence.returns = []; value.latestCheck.evidence.outcome = 'CONFIRMED'; value.latestCheck.evidence.totalReturned = money('0.00')
  assert.throws(() => rules.validateSupplierReturn(value, binding())); value.latestCheck.canRegister = false; value.latestCheck.registrationIssue = 'SUPPLIER_PAYMENT_RETURN_EVIDENCE_CHANGED'
  assert.doesNotThrow(() => rules.validateSupplierReturn(value, binding()))
})
test('确认只提交版本和人工说明，到期瞬间拒绝登记，回执严格对应原请求', () => {
  const value = view(), input = rules.supplierReturnInput(value, 'REGISTER', ' 核对 ', ' proof ')
  assert.deepEqual(input, { operationVersion: 5, returnVersion: 2, checkId: id(4), checkVersion: 3, outcome: 'PARTIALLY_RETURNED', comment: '核对', evidenceReference: 'proof' })
  assert.throws(() => rules.supplierReturnInput(value, 'REGISTER', '核对', 'proof', Date.parse(value.latestCheck.evidence.validUntil)))
  assert.doesNotThrow(() => rules.validateSupplierReturnReceipt(receipt(input), value, input))
  for (const change of [r => r.paymentId = id(99), r => r.operationVersion++, r => r.returnVersion++, r => r.checkVersion--, r => r.registrationId = null]) {
    const invalid = receipt(input); change(invalid); assert.throws(() => rules.validateSupplierReturnReceipt(invalid, value, input))
  }
})
test('选择登记和取消只改变表单，明确勾选前不产生写请求', async () => {
  let writes = 0; api.supplierReturns = async () => view(); api.registerSupplierReturns = async () => { writes++; throw new Error('unexpected') }
  const item = mount()
  try { await settle(); item.state.prepare('REGISTER'); assert.equal(writes, 0); item.state.reference = 'proof'; item.state.comment = '核对'; await item.state.execute(); assert.equal(writes, 0); assert.match(item.state.error, /确认/); item.state.pending = null; assert.equal(writes, 0) }
  finally { item.close() }
})
test('登记成功重读当前资金并通知上层，原银行版本保持', async () => {
  const value = view(); let stored = false, writes = 0
  api.supplierReturns = async () => stored ? registered(structuredClone(value)) : structuredClone(value)
  api.registerSupplierReturns = async (payment, input) => { assert.equal(payment, id(1)); assert.equal('amount' in input, false); writes++; stored = true; return receipt(input) }
  const item = mount()
  try { await settle(); prepare(item); await item.state.execute(); assert.equal(writes, 1); assert.equal(item.state.view.totalReturned.value, '20.00'); assert.equal(item.state.view.operationVersion, 5); assert.equal(item.changed.length, 1) }
  finally { item.close() }
})
test('身份切换后迟到的读取不会恢复旧金融信息', async () => {
  let resolve; api.supplierReturns = () => new Promise(done => { resolve = done })
  const item = mount()
  try { item.props.scopeKey = ''; resolve(view()); await settle(); assert.equal(item.state.view, null); assert.equal(item.state.pending, null) }
  finally { item.close() }
})
test('身份切换后迟到写回执不能显示成功或触发另一身份刷新', async () => {
  let resolve, input; api.supplierReturns = async () => view(); api.registerSupplierReturns = async (_, body) => { input = body; return new Promise(done => { resolve = done }) }
  const item = mount()
  try { await settle(); prepare(item); const saving = item.state.execute(); item.props.scopeKey = ''; resolve(receipt(input)); await saving; assert.equal(item.state.view, null); assert.equal(item.state.notice, ''); assert.equal(item.changed.length, 0) }
  finally { item.close() }
})
test('授权失效会清除原件，错误回执要求恢复并重新读取', async () => {
  api.supplierReturns = async () => view(); api.registerSupplierReturns = async () => { throw { status: 403, code: 'FORBIDDEN' } }
  const item = mount()
  try { await settle(); prepare(item); await item.state.execute(); assert.equal(item.state.view, null); assert.equal(item.state.pending, null); assert.equal(item.state.requiresRefresh, true) }
  finally { item.close() }
})
test('过期证据在提交时再次拦截，页面勾选不能延长期限', async () => {
  let writes = 0; api.supplierReturns = async () => view(); api.registerSupplierReturns = async () => { writes++ }
  const item = mount(), realNow = Date.now
  try { await settle(); prepare(item); const expires = Date.parse(item.state.view.latestCheck.evidence.validUntil); Date.now = () => expires; await item.state.execute(); assert.equal(writes, 0); assert.match(item.state.error, /核对期限/) }
  finally { Date.now = realNow; item.close() }
})
test('主付款页面协调回款办理锁，并在银行变化后重读回款', async () => {
  let reads = 0; api.supplierFinance = async () => { reads++; throw { status: 403, code: 'FORBIDDEN' } }
  const item = mount(Parent)
  try { await settle(); item.state.returnActivity(true); assert.equal(item.state.blocked, true); await item.state.load(); assert.equal(reads, 1); item.state.returnActivity(false); item.state.returnChanged(); await settle(); assert.equal(reads, 2); item.state.disputeChanged(); assert.equal(item.state.returnRevision, 1); assert.equal(item.state.settlementRevision, 1) }
  finally { item.close() }
})
test('响应丢失保留原正文与幂等键，恢复后必须重新核对当前资金', async () => {
  const calls = []; let lost = true
  bindAuthenticationActor({ tenantId: 'demo', userId: 'finance', roles: ['FINANCE'] }); api.supplierReturns = async () => view()
  global.fetch = async (url, options) => {
    const input = JSON.parse(options.body); calls.push({ url, body: options.body, key: options.headers['Idempotency-Key'] ?? options.headers.get?.('Idempotency-Key') })
    if (lost) throw new TypeError('Synthetic lost response')
    return new Response(JSON.stringify(receipt(input)), { status: 202, headers: { 'Content-Type': 'application/json' } })
  }
  const item = mount()
  try {
    await settle(); prepare(item); await item.state.execute(); assert.equal(item.state.unconfirmed, true)
    lost = false; await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls[0].key, calls[1].key); assert.equal(calls[0].body, calls[1].body); assert.match(calls[0].url, /returns\/registrations$/)
    assert.equal(item.state.requiresRefresh, true); await item.state.load(); assert.equal(item.state.requiresRefresh, false)
  } finally { item.close() }
})
test('真实 API 读取禁用缓存且限制历史页，写入使用原请求机制', async () => {
  const calls = []; bindAuthenticationActor({ tenantId: 'demo', userId: 'finance', roles: ['FINANCE'] })
  global.fetch = async (url, options) => {
    calls.push({ url, options }); const body = options.body ? receipt(JSON.parse(options.body)) : view()
    return new Response(JSON.stringify(body), { status: options.body ? 202 : 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    await originalApi.supplierReturns('id/a', 7, new AbortController().signal)
    const input = rules.supplierReturnInput(view(), 'QUERY', '读取入款'); await originalApi.querySupplierReturns('id/a', input)
    assert.match(calls[0].url, /supplier-payments\/id%2Fa\/returns\?beforeVersion=7&limit=25$/); assert.equal(calls[0].options.cache, 'no-store')
    assert.match(calls[1].url, /supplier-payments\/id%2Fa\/returns\/checks$/); assert.equal(calls[1].options.method, 'POST'); assert.deepEqual(JSON.parse(calls[1].options.body), input)
  } finally { global.fetch = originalFetch; bindAuthenticationActor(null) }
})
