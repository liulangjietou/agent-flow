import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_SUPPLIER_DISPUTE)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_SUPPLIERDISPUTESTATUS)
const { default: FinancePanel } = await import(process.env.AGENTFLOW_TEST_SUPPLIERFINANCESTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const binding = () => ({ paymentId: 'bank-payment', requestId: 'procurement', applicationId: 'application', roundNo: 2 })
const date = offset => new Date(Date.now() + offset).toISOString()
function fact(revision = 3) {
  const observedAt = date(-1000)
  return { outcome: 'SUCCEEDED', revision, observedAt, validUntil: new Date(Date.parse(observedAt) + 300000).toISOString(), paymentReference: 'bank-transaction', receiptReference: 'original-receipt', completedAt: date(-60000), failure: null }
}
const view = () => ({ ...binding(), operationVersion: 12, status: 'RECONCILING', observed: fact(1), candidate: fact(), issue: null, canQuery: true, canResolve: true, latest: null })
const receipt = (value = view(), action = 'RESOLVE') => ({ ...binding(), action, operationVersion: value.operationVersion + 1, status: action === 'QUERY' ? 'UNKNOWN' : value.candidate.outcome, resolutionId: action === 'QUERY' ? null : 'decision', auditEventId: 'audit' })
let scope = 0
function mount(component = Panel) {
  const props = reactive({ ...binding(), applicationVersion: 9, requestVersion: 4, scopeKey: 'dispute-' + ++scope, locked: false }), events = [], changes = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value), onChanged: () => changes.push(true) })
  const instance = app.mount({})
  return { props, events, changes, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('裁决只提交展示版本、原候选终态和人工凭据，查询不会注入银行事实', () => {
  const value = view()
  assert.deepEqual(rules.supplierDisputeInput(value, 'RESOLVE', ' 核对原银行回单 ', ' BANK-1 '), { operationVersion: 12, outcome: 'SUCCEEDED', evidenceReference: 'BANK-1', comment: '核对原银行回单' })
  assert.deepEqual(rules.supplierDisputeInput(value, 'QUERY', ' 查询原号 '), { operationVersion: 12, comment: '查询原号' })
  for (const reference of ['', 'x'.repeat(129), 'BANK\nINJECTED']) assert.throws(() => rules.supplierDisputeInput(value, 'RESOLVE', '核对', reference))
  for (const comment of ['', 'x'.repeat(2001)]) assert.throws(() => rules.supplierDisputeInput(value, 'QUERY', comment))
})

test('候选五分钟边界立即停止裁决，原查询仍可用且未核实失败不能提交', () => {
  const value = view(), expiry = Date.parse(value.candidate.validUntil)
  assert.equal(rules.supplierDisputeAllowed(value, 'RESOLVE', expiry - 1), true)
  assert.equal(rules.supplierDisputeAllowed(value, 'RESOLVE', expiry), false)
  assert.throws(() => rules.supplierDisputeInput(value, 'RESOLVE', '核对', 'BANK-1', expiry))
  assert.equal(rules.supplierDisputeAllowed(value, 'QUERY', expiry + 1), true)
  value.issue = 'DIFFERENT_SETTLEMENT'; value.canResolve = false
  assert.equal(rules.validateSupplierDispute(value, binding()), value)
  assert.throws(() => rules.supplierDisputeInput(value, 'RESOLVE', '核对', 'BANK-1'))
})

test('跨付款、跨轮次、缺失回单或矛盾能力不能显示为可办理', () => {
  for (const mutate of [v => v.paymentId = 'foreign', v => v.roundNo++, v => v.operationVersion = 0, v => v.canQuery = 'true', v => v.candidate = null, v => v.candidate.receiptReference = null, v => v.candidate.completedAt = date(10000), v => v.candidate.revision = -1, v => v.candidate.validUntil = date(600000), v => v.issue = 'DIFFERENT_PAYMENT', v => v.status = 'QUERYING', v => delete v.latest]) {
    const value = view(); mutate(value); assert.throws(() => rules.validateSupplierDispute(value, binding()))
  }
})

test('只读参与人、尚未登记付款和历史决定保留后续争议', () => {
  const value = view(); value.canQuery = false; value.canResolve = false
  value.latest = { id: 'older-decision', operationVersion: 9, outcome: 'SUCCEEDED', resolvedBy: 'finance', resolvedAt: date(-30000), evidenceReference: 'STATEMENT-0' }
  assert.equal(rules.validateSupplierDispute(value, binding()).status, 'RECONCILING')
  assert.equal(rules.supplierDisputeAllowed(value, 'RESOLVE'), false)
  value.latest.operationVersion = 13; assert.throws(() => rules.validateSupplierDispute(value, binding()))
  const absent = { ...binding(), operationVersion: null, status: null, observed: null, candidate: null, issue: null, latest: null, canQuery: false, canResolve: false }
  assert.equal(rules.validateSupplierDispute(absent, binding()).status, null)
})

test('终态失败和退回按实际候选提交，查无或处理中不能强制裁决', () => {
  const value = view(); value.observed = null; value.candidate = { ...fact(), outcome: 'FAILED', receiptReference: null, completedAt: null, failure: 'PAYMENT_REJECTED' }
  assert.equal(rules.supplierDisputeInput(value, 'RESOLVE', '核对明确失败', 'BANK-2').outcome, 'FAILED')
  value.candidate = { ...fact(), outcome: 'REVERSED' }
  assert.equal(rules.supplierDisputeInput(value, 'RESOLVE', '核对真实退回', 'BANK-3').outcome, 'REVERSED')
  for (const outcome of ['NOT_FOUND', 'PENDING']) {
    value.candidate = { ...fact(), outcome, revision: outcome === 'NOT_FOUND' ? 0 : 4, paymentReference: outcome === 'NOT_FOUND' ? null : 'bank-transaction', receiptReference: null, completedAt: null }
    assert.throws(() => rules.validateSupplierDispute(value, binding()))
    value.canResolve = false; value.issue = 'NON_TERMINAL'; assert.doesNotThrow(() => rules.validateSupplierDispute(value, binding()))
    value.canResolve = true; value.issue = null
  }
})

test('回执必须对应原付款、原版本和明确动作，不能把查询受理当作到账', () => {
  const value = view()
  rules.validateSupplierDisputeReceipt(receipt(value), value, 'RESOLVE')
  rules.validateSupplierDisputeReceipt(receipt(value, 'QUERY'), value, 'QUERY')
  for (const mutate of [r => r.requestId = 'foreign', r => r.operationVersion++, r => r.status = 'FAILED', r => r.resolutionId = null, r => r.action = 'QUERY', r => r.auditEventId = '']) {
    const result = receipt(value); mutate(result); assert.throws(() => rules.validateSupplierDisputeReceipt(result, value, 'RESOLVE'))
  }
})

test('真实组件先核对再提交且保存后重读，重复点击只发送一次', async () => {
  const value = view(), calls = []; let complete, reads = 0
  api.supplierDispute = async () => { reads++; return clone(value) }
  api.resolveSupplierDispute = (id, input) => { calls.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = mount()
  try {
    await settle(); p.state.prepare('RESOLVE'); assert.equal(calls.length, 0)
    p.state.comment = '核对原回单'; p.state.evidenceReference = 'BANK-1'
    const saving = p.state.execute(); await p.state.execute(); assert.equal(calls.length, 1); assert.equal(p.events.at(-1), true)
    complete(receipt(value)); await saving
    assert.deepEqual(calls[0], { id: 'bank-payment', input: { operationVersion: 12, outcome: 'SUCCEEDED', comment: '核对原回单', evidenceReference: 'BANK-1' } })
    assert.equal(reads, 2); assert.equal(p.changes.length, 1); assert.equal(p.state.pending, null); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('查询也要求明确说明；无权限、锁定或期限到达均不能点击办理', async () => {
  const value = view(), calls = []; api.supplierDispute = async () => clone(value)
  api.querySupplierDispute = async (id, input) => { calls.push({ id, input }); return receipt(value, 'QUERY') }
  const p = mount()
  try {
    await settle(); p.props.locked = true; p.state.prepare('QUERY'); assert.equal(p.state.pending, null)
    p.props.locked = false; p.state.prepare('QUERY'); await p.state.execute(); assert.equal(calls.length, 0)
    p.state.comment = '查询原交易'; await p.state.execute(); assert.equal(calls.length, 1)
    p.state.now = Date.parse(value.candidate.validUntil); p.state.prepare('RESOLVE'); assert.equal(p.state.pending, null)
    value.canQuery = false; value.canResolve = false; await p.state.load(); p.state.prepare('QUERY'); assert.equal(p.state.pending, null)
  } finally { p.close() }
})

test('身份切换清空旧回执、凭据和说明，迟到读写不能恢复旧事实', async () => {
  const reads = []; api.supplierDispute = () => new Promise(resolve => reads.push(resolve)); const p = mount()
  try {
    p.props.scopeKey = 'new-finance'; reads[0](view()); await settle(); assert.equal(p.state.view, null)
    reads[1](view()); await settle(); let complete
    api.resolveSupplierDispute = () => new Promise(resolve => complete = resolve)
    p.state.prepare('RESOLVE'); p.state.evidenceReference = 'old-evidence'; p.state.comment = '旧身份说明'; const saving = p.state.execute()
    p.props.scopeKey = 'third-finance'; assert.equal(p.state.view, null); assert.equal(p.state.evidenceReference, ''); assert.equal(p.state.comment, '')
    complete(receipt()); await saving; assert.equal(p.state.notice, ''); assert.equal(p.changes.length, 0)
    reads[2](view()); await settle()
  } finally { p.close() }
})

test('读取超时取消原请求，迟到响应不能重新显示裁决能力', async () => {
  api.supplierDispute = async () => view(); const p = mount(), originalTimer = global.setTimeout, originalClear = global.clearTimeout; let complete, expire, signal
  try {
    await settle(); api.supplierDispute = (_id, value) => { signal = value; return new Promise(resolve => complete = resolve) }
    global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const loading = p.state.load(); expire(); assert.equal(signal.aborted, true); assert.equal(p.state.view, null)
    complete(view()); await loading; assert.equal(p.state.view, null); assert.match(p.state.error, /超时/)
  } finally { global.setTimeout = originalTimer; global.clearTimeout = originalClear; p.close() }
})

test('权限撤销或提交回执不匹配时清除事实并要求重新核对', async () => {
  api.supplierDispute = async () => view(); api.resolveSupplierDispute = async () => ({ ...receipt(), paymentId: 'foreign' }); const p = mount()
  try {
    await settle(); p.state.prepare('RESOLVE'); p.state.comment = '核对'; p.state.evidenceReference = 'BANK-1'; await p.state.execute()
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.changes.length, 0)
    api.supplierDispute = async () => { throw { code: 'FORBIDDEN' } }; await p.state.load()
    assert.equal(p.state.view, null); assert.equal(p.state.pending, null); assert.equal(p.state.evidenceReference, ''); assert.match(p.state.error, /权限/)
  } finally { p.close() }
})

test('未知裁决按原幂等键恢复，刷新不重发，恢复后仍需核对', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'dispute-recovery-' + ++scope, roles: ['FINANCE'] })
  const value = view(), calls = []; let fail = true; api.supplierDispute = async () => clone(value)
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(receipt(value)), { status: 202 }) }
  const p = mount()
  try {
    await settle(); p.state.prepare('RESOLVE'); p.state.comment = '核对'; p.state.evidenceReference = 'BANK-1'; await p.state.execute()
    assert.equal(p.state.unconfirmed, true); await p.state.load(); p.state.prepare('RESOLVE'); await p.state.execute(); assert.equal(calls.length, 1)
    fail = false; await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); await p.state.load(); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})

test('父面板汇总裁决及结算写入状态，裁决后刷新结算且期间不能卸载', async () => {
  let reads = 0; api.supplierFinance = async () => { reads++; return { requestId: 'procurement', applicationId: 'application', roundNo: 2, applicationVersion: 9, requestVersion: 4, approvedAmount: { value: '65.00', currency: 'CNY' }, review: null, authorization: null, hold: null, actions: { review: true, authorize: false, query: false, retry: false, retire: false } } }
  const p = mount(FinancePanel)
  try {
    await settle(); p.state.disputeActivity(true); assert.equal(p.state.blocked, true); assert.equal(p.events.at(-1), true)
    await p.state.load(); assert.equal(reads, 1)
    p.state.settlementActivity(false); assert.equal(p.events.at(-1), true)
    p.state.disputeActivity(false); assert.equal(p.events.at(-1), false)
    p.state.disputeChanged(); assert.equal(p.state.settlementRevision, 1)
    p.state.saving = true; p.state.disputeActivity(false); assert.equal(p.events.at(-1), true)
  } finally { p.close() }
})

test('真实 API 编码原号、禁用读缓存，并使用现有幂等写入登记', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  bindAuthenticationActor({ tenantId: 'demo', userId: 'dispute-paths-' + ++scope, roles: ['FINANCE'] })
  try {
    await api.supplierDispute('bank/other', new AbortController().signal)
    await api.querySupplierDispute('bank/other', { operationVersion: 12, comment: '核对' })
    await api.resolveSupplierDispute('bank/other', { operationVersion: 12, outcome: 'SUCCEEDED', evidenceReference: 'BANK-1', comment: '核对' })
    assert.equal(calls[0].url, '/api/v1/supplier-payments/bank%2Fother/dispute'); assert.equal(calls[0].init.cache, 'no-store')
    assert.equal(calls[1].url, '/api/v1/supplier-payments/bank%2Fother/dispute/queries'); assert.ok(calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(calls[2].url, '/api/v1/supplier-payments/bank%2Fother/dispute/resolutions'); assert.ok(calls[2].init.headers.get('Idempotency-Key'))
  } finally { global.fetch = originalFetch; bindAuthenticationActor(null) }
})
