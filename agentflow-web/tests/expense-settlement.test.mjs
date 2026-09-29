import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_EXPENSE_SETTLEMENT)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_EXPENSESETTLEMENTSTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve))
const binding = () => ({ reportId: 'report', applicationId: 'app', roundNo: 1, applicationVersion: 9, financialVersion: 3 })
const view = () => ({ ...binding(), canRetry: true, settlement: { version: 3, status: 'BUDGET_REJECTED', resourcesConsumed: true, budgetStatus: 'REJECTED', issue: 'BUDGET_ACCOUNTING_PERIOD_CLOSED', funding: 'PAYMENT', fundingConfirmedAt: '2026-09-28T10:00:00Z', updatedAt: '2026-09-28T10:01:00Z' } })
const receipt = () => ({ reportId: 'report', applicationId: 'app', roundNo: 1, settlementVersion: 4, auditEventId: 'audit' })
let serial = 0
function mount() {
  const props = reactive({ ...binding(), scopeKey: 'settlement-' + ++serial, locked: false }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value) })
  const instance = app.mount({})
  return { props, events, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('核销完成需要资源与预算同时完成，原报销轮次和版本不能替换', () => {
  assert.deepEqual(rules.validateSettlement(view(), binding()), view())
  for (const key of Object.keys(binding())) assert.throws(() => rules.validateSettlement({ ...view(), [key]: 'foreign' }, binding()))
  for (const change of [{ status: 'SETTLED' }, { resourcesConsumed: false }, { budgetStatus: null }, { status: 'UNKNOWN' }, { issue: null }, { updatedAt: 'bad' }, { funding: 'UNKNOWN' }]) {
    const value = view(); Object.assign(value.settlement, change); assert.throws(() => rules.validateSettlement(value, binding()))
  }
  const complete = view(); Object.assign(complete, { canRetry: false }); Object.assign(complete.settlement, { status: 'SETTLED', budgetStatus: 'APPLIED', issue: null })
  assert.doesNotThrow(() => rules.validateSettlement(complete, binding()))
  assert.throws(() => rules.validateSettlement({ ...complete, canRetry: true }, binding()))
  assert.doesNotThrow(() => rules.validateSettlement({ ...binding(), canRetry: false, settlement: null }, binding()))
})

test('零应付与全额冲销不冒充银行成功，重试只提交版本和人工理由', () => {
  for (const funding of ['FULL_OFFSET', 'ZERO_AMOUNT']) {
    const value = view(); value.settlement.funding = funding; value.amount = 'forged'; value.resourcesConsumed = false
    assert.deepEqual(rules.settlementRetry(value, ' 核对期间 '), { roundNo: 1, applicationVersion: 9, financialVersion: 3, settlementVersion: 3, comment: '核对期间' })
  }
  assert.throws(() => rules.settlementRetry(view(), ' ')); assert.throws(() => rules.settlementRetry({ ...view(), canRetry: false }, '原因'))
  const input = rules.settlementRetry(view(), '原因'); assert.doesNotThrow(() => rules.validateSettlementReceipt(receipt(), view(), input))
  for (const change of [{ reportId: 'other' }, { applicationId: 'other' }, { roundNo: 2 }, { settlementVersion: 3 }, { auditEventId: '' }]) assert.throws(() => rules.validateSettlementReceipt({ ...receipt(), ...change }, view(), input))
})

test('真实组件只在手工确认后发送，重复点击不会重复登记，回执后读取实际进度', async () => {
  api.expenseSettlement = async () => view(); const calls = []; let complete
  api.retryExpenseSettlement = (id, input) => { calls.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const page = mount()
  try {
    await settle(); await page.state.execute(); assert.equal(calls.length, 0)
    page.state.prepare(); page.state.comment = '重新核对'; const writing = page.state.execute(); await page.state.execute()
    assert.equal(calls.length, 1); assert.equal(page.state.saving, true); assert.equal(page.events.at(-1), true)
    complete(receipt()); await writing; assert.equal(page.state.confirming, false); assert.match(page.state.notice, /已登记/)
  } finally { page.close() }
})

test('切换身份丢弃旧响应和旧写入回执，切换轮次会清空处理说明', async () => {
  const reads = []; api.expenseSettlement = (id, round, signal) => new Promise(resolve => reads.push({ signal, resolve }))
  let complete; api.retryExpenseSettlement = () => new Promise(resolve => complete = resolve)
  const page = mount()
  try {
    page.props.scopeKey = 'new'; assert.equal(reads[0].signal.aborted, true); reads[0].resolve(view()); await settle(); assert.equal(page.state.view, null)
    reads[1].resolve(view()); await settle(); page.state.prepare(); page.state.comment = '确认'; const writing = page.state.execute()
    page.props.scopeKey = 'another'; complete(receipt()); await writing; assert.equal(page.state.notice, ''); assert.equal(page.state.view, null)
    reads[2].resolve(view()); await settle(); page.state.prepare(); page.state.comment = '不能继承'; page.props.roundNo = 2
    assert.equal(page.state.comment, ''); assert.equal(page.state.confirming, false); assert.equal(page.state.view, null)
    reads[3].resolve(view()); await settle(); assert.equal(page.state.view, null); assert.match(page.state.error, /轮次或版本/)
  } finally { page.close() }
})

test('超时丢弃迟到成功，读取失败清空旧按钮', async () => {
  api.expenseSettlement = async () => view(); const page = mount(); const timer = global.setTimeout, clear = global.clearTimeout; let expire, complete, signal
  try {
    await settle(); api.expenseSettlement = (id, round, value) => { signal = value; return new Promise(resolve => complete = resolve) }
    global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const reading = page.state.load(); assert.equal(page.state.view, null); expire(); assert.equal(signal.aborted, true)
    complete(view()); await reading; assert.equal(page.state.view, null); assert.match(page.state.error, /超时/)
  } finally { global.setTimeout = timer; global.clearTimeout = clear; page.close() }
})

test('未知写入按原幂等键恢复，恢复后必须重新读取才允许下一次办理', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'finance-' + ++serial, roles: ['FINANCE'] }); api.expenseSettlement = async () => view()
  const calls = []; let fail = true
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('lost response'); return new Response(JSON.stringify(receipt()), { status: 202 }) }
  const page = mount()
  try {
    await settle(); page.state.prepare(); page.state.comment = '核对'; await page.state.execute(); assert.equal(page.state.unconfirmed, true)
    await page.state.load(); page.state.prepare(); await page.state.execute(); assert.equal(calls.length, 1)
    const entry = writeRequests.pending()[0]; fail = false; await writeRequests.recover(entry.id)
    assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(page.state.requiresRefresh, true); assert.equal(page.state.blocked, true); await page.state.load(); assert.equal(page.state.blocked, false)
  } finally { page.close() }
})

test('实际 API 转义报销标识并禁用结算读取缓存', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  try { await api.expenseSettlement('report/other', 2, new AbortController().signal); assert.match(calls[0].url, /report%2Fother\/settlement\?roundNo=2$/); assert.equal(calls[0].init.cache, 'no-store') }
  finally { global.fetch = originalFetch }
})
