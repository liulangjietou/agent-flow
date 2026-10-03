import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_SUPPLIER_FINANCE)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_SUPPLIERFINANCESTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const binding = () => ({ requestId: 'procurement', applicationId: 'application', roundNo: 2, applicationVersion: 9, requestVersion: 4 })
const money = value => ({ value, currency: 'CNY' }), date = offset => new Date(Date.now() + offset).toISOString()
const view = () => ({ ...binding(), approvedAmount: money('70.00'), review: null, authorization: null, hold: null, actions: { review: true, authorize: false, query: false, retry: false, retire: false } })
const ready = () => ({ ...view(), review: { id: 'review', version: 3, status: 'READY', requestedAt: date(-30000), checkedAt: date(-10000), validUntil: date(60000), settled: money('30.00'), outstanding: money('70.00'), maskedAccount: '****1234', issue: null }, actions: { ...view().actions, authorize: true } })
const authorized = (status = 'QUEUED') => ({ ...view(), authorization: { id: 'authorization', authorizedBy: 'finance', authorizedAt: date(-60000), expiresAt: date(60000), maskedAccount: '****1234', retiredAt: null, retirementBasis: null }, hold: { version: 1, status, updatedAt: date(-1000), observedAt: null, failure: null }, actions: { review: false, authorize: false, query: !['QUEUED', 'RESERVING', 'QUERYING'].includes(status), retry: status === 'NOT_FOUND', retire: ['QUEUED', 'EXPIRED', 'VOIDED', 'REJECTED'].includes(status) } })
const receipt = (action = 'REVIEW', value = view()) => ({ requestId: value.requestId, applicationId: value.applicationId, roundNo: value.roundNo, action, reviewId: action === 'REVIEW' ? 'new-review' : action === 'AUTHORIZE' ? value.review.id : null, reviewVersion: action === 'REVIEW' ? 1 : action === 'AUTHORIZE' ? value.review.version + 1 : null, authorizationId: action === 'REVIEW' ? null : 'authorization', holdVersion: action === 'REVIEW' ? null : action === 'AUTHORIZE' ? 1 : value.hold.version + (action !== 'RETIRE' || value.hold.status === 'QUEUED' ? 1 : 0), auditEventId: 'audit' })
let scope = 0
function mount() {
  const props = reactive({ ...binding(), scopeKey: 'supplier-' + ++scope, locked: false }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value) })
  const instance = app.mount({})
  return { props, events, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('复核与授权只携带原版本和证据身份，精确大金额不经过浮点转换', () => {
  const value = ready(); value.approvedAmount = value.review.outstanding = money('999999999999999.99')
  const input = rules.supplierFinanceInput(value, 'AUTHORIZE', ' 已核对原应付 ')
  assert.deepEqual(input, { roundNo: 2, applicationVersion: 9, requestVersion: 4, reviewId: 'review', reviewVersion: 3, comment: '已核对原应付' })
  assert.equal(value.approvedAmount.value, '999999999999999.99')
  assert.deepEqual(rules.supplierFinanceInput(view(), 'REVIEW', '复核'), { roundNo: 2, applicationVersion: 9, requestVersion: 4, comment: '复核' })
})

test('错误轮次、完整账号、未知状态、缺失字段与矛盾结束证据都拒绝展示', () => {
  assert.equal(rules.validateSupplierFinance(ready(), binding()).review.status, 'READY')
  for (const mutate of [v => v.requestId = 'foreign', v => v.requestVersion++, v => v.review.maskedAccount = '6222000012345678', v => v.review.status = 'PAID', v => v.review.outstanding.value = 70, v => v.review.checkedAt = null, v => v.review.outstanding.value = '69.99', v => v.actions.query = true, v => v.actions.authorize = 'true']) {
    const value = ready(); mutate(value); assert.throws(() => rules.validateSupplierFinance(value, binding()))
  }
  const ended = authorized('VOIDED'); Object.assign(ended.authorization, { retiredAt: date(0), retirementBasis: 'NEVER_DISPATCHED' }); Object.assign(ended.actions, { retire: false, query: false })
  assert.equal(rules.validateSupplierFinance(ended, binding()), ended)
  ended.hold.status = 'UNKNOWN'; assert.throws(() => rules.validateSupplierFinance(ended, binding()))
})

test('确认时重新检查证据截止点，过期 READY 和已消费复核不能再次授权', () => {
  const value = ready(), deadline = Date.parse(value.review.validUntil)
  assert.equal(rules.supplierActionAllowed(value, 'AUTHORIZE', deadline - 1), true)
  assert.equal(rules.supplierActionAllowed(value, 'AUTHORIZE', deadline), false)
  assert.throws(() => rules.supplierFinanceInput(value, 'AUTHORIZE', '确认', deadline))
  value.review.status = 'CONSUMED'; assert.throws(() => rules.supplierFinanceInput(value, 'AUTHORIZE', '确认'))
})

test('最小回执绑定原申请和精确下一版本，预留与未知状态不能冒充已付款', () => {
  const value = ready(); assert.doesNotThrow(() => rules.validateSupplierFinanceReceipt(receipt('AUTHORIZE', value), value, 'AUTHORIZE'))
  for (const change of [{ requestId: 'foreign' }, { roundNo: 3 }, { reviewVersion: 3 }, { holdVersion: 2 }, { authorizationId: null }, { auditEventId: null }]) {
    assert.throws(() => rules.validateSupplierFinanceReceipt({ ...receipt('AUTHORIZE', value), ...change }, value, 'AUTHORIZE'))
  }
  for (const status of ['HELD', 'UNKNOWN', 'QUERYING', 'RECONCILING', 'NOT_FOUND']) {
    const current = authorized(status); current.actions.retire = true
    assert.throws(() => rules.supplierFinanceInput(current, 'RETIRE', '结束'))
  }
  assert.equal(rules.holdLabels.HELD, '原应付已预留')
})

test('财务准备授权不发送请求，明确确认和双击只保存一次', async () => {
  const value = ready(); api.supplierFinance = async () => clone(value); let complete; const calls = []
  api.authorizeSupplierPayment = (id, input) => { calls.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = mount()
  try {
    await settle(); p.state.prepare('AUTHORIZE'); assert.equal(calls.length, 0); p.state.comment = '确认余额与账户'
    const sending = p.state.execute(); await p.state.execute(); assert.equal(calls.length, 1); assert.equal(p.state.saving, true)
    complete(receipt('AUTHORIZE', value)); await sending
    assert.equal(calls[0].id, 'procurement'); assert.equal(calls[0].input.reviewId, 'review'); assert.equal(p.state.error, ''); assert.equal(p.state.pending, null)
    assert.equal(p.events.includes(true), true); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('错误回执保留待核对状态，不能继续执行第二笔人工决定', async () => {
  api.supplierFinance = async () => ready(); let calls = 0
  api.authorizeSupplierPayment = async () => { calls++; return { ...receipt('AUTHORIZE', ready()), requestId: 'foreign' } }
  const p = mount()
  try {
    await settle(); p.state.prepare('AUTHORIZE'); p.state.comment = '确认'; await p.state.execute(); await p.state.execute()
    assert.equal(calls, 1); assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); assert.match(p.state.error, /原申请/)
  } finally { p.close() }
})

test('切换身份清空复核、输入和按钮，旧读取及旧写入回执不能回填', async () => {
  const reads = []; api.supplierFinance = () => new Promise(resolve => reads.push(resolve)); const p = mount()
  try {
    p.props.scopeKey = 'new-finance'; reads[0](ready()); await settle(); assert.equal(p.state.view, null)
    reads[1](ready()); await settle(); let complete
    api.authorizeSupplierPayment = () => new Promise(resolve => complete = resolve)
    p.state.prepare('AUTHORIZE'); p.state.comment = '旧身份决定'; const sending = p.state.execute(); p.props.scopeKey = 'third-finance'
    assert.equal(p.state.comment, ''); assert.equal(p.state.pending, null); assert.equal(p.state.view, null)
    complete(receipt('AUTHORIZE', ready())); await sending; assert.equal(p.state.notice, ''); assert.equal(p.state.saving, false)
    reads[2](view()); await settle()
  } finally { p.close() }
})

test('读取超时撤销原请求，迟到成功不会恢复旧金融事实', async () => {
  api.supplierFinance = async () => ready(); const p = mount(); const originalTimer = global.setTimeout, originalClear = global.clearTimeout; let complete, expire, signal
  try {
    await settle(); api.supplierFinance = (_id, _round, value) => { signal = value; return new Promise(resolve => complete = resolve) }
    global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const pending = p.state.load(); expire(); assert.equal(signal.aborted, true); assert.equal(p.state.view, null)
    complete(ready()); await pending; assert.equal(p.state.view, null); assert.match(p.state.error, /超时/)
  } finally { global.setTimeout = originalTimer; global.clearTimeout = originalClear; p.close() }
})

test('结果未知时保留原幂等键和正文，刷新不重发，恢复后必须重新核对', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'supplier-finance-' + ++scope, roles: ['FINANCE'] })
  api.supplierFinance = async () => view(); let fail = true; const calls = []
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(receipt()), { status: 202 }) }
  const p = mount()
  try {
    await settle(); p.state.prepare('REVIEW'); p.state.comment = '复核原应付'; await p.state.execute()
    assert.equal(calls.length, 1); assert.equal(p.state.unconfirmed, true); await p.state.load(); p.state.prepare('REVIEW'); await p.state.execute(); assert.equal(calls.length, 1)
    fail = false; await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); await p.state.load(); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})

test('安全结束与原号查询都保留原预留版本，不自动发起新复核或新授权', async () => {
  const value = authorized('QUEUED'); api.supplierFinance = async () => clone(value); const calls = []
  api.supplierHoldAction = async (id, input) => { calls.push({ id, input }); return receipt(input.action, value) }
  api.reviewSupplierPayable = api.authorizeSupplierPayment = async () => { throw new Error('Unexpected replacement') }
  const p = mount()
  try {
    await settle(); p.state.prepare('RETIRE'); p.state.comment = '从未外发，结束原授权'; await p.state.execute()
    assert.deepEqual(calls, [{ id: 'authorization', input: { action: 'RETIRE', holdVersion: 1, comment: '从未外发，结束原授权' } }]); assert.equal(p.state.error, '')
  } finally { p.close() }
})

test('实际 API 禁用读取缓存并转义原申请和授权路径', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  bindAuthenticationActor({ tenantId: 'demo', userId: 'paths-' + ++scope, roles: ['FINANCE'] })
  try {
    await api.supplierFinance('req/other', 2, new AbortController().signal)
    await api.supplierHoldAction('auth/other', { action: 'QUERY', holdVersion: 4, comment: '原号查询' })
    assert.equal(calls[0].url, '/api/v1/procurement-payments/req%2Fother/supplier-payment?roundNo=2'); assert.equal(calls[0].init.cache, 'no-store')
    assert.equal(calls[1].url, '/api/v1/supplier-payments/auth%2Fother/finance-actions'); assert.ok(calls[1].init.headers.get('Idempotency-Key'))
  } finally { global.fetch = originalFetch; bindAuthenticationActor(null) }
})
