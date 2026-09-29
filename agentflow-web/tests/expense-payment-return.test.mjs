import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_EXPENSE_PAYMENT_RETURN)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_EXPENSEPAYMENTRETURN)
const { default: Parent } = await import(process.env.AGENTFLOW_TEST_EXPENSESETTLEMENTSTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve)), money = value => ({ value, currency: 'CNY' })
const id = number => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
const binding = () => ({ applicationId: id(1), reportId: id(2), roundNo: 1 })
function proof(reference, value, at) {
  return { funding: { transactionReference: reference, amount: money(value), receivedAt: at }, posting: { voucherReference: 'credit-' + reference, entryReference: 'row-1', accountCode: '2241-EMPLOYEE', amount: money(value), accountingDate: at.slice(0, 10), postedAt: at } }
}
function view() {
  const at = new Date(Date.now() - 5000).toISOString()
  return { ...binding(), settlementVersion: 4, settlementStatus: 'REVIEW_REQUIRED', returnVersion: 2, reviewRequired: true,
    original: { paymentId: id(3), paymentReference: 'original-payment', amount: money('100.00'), paidAt: new Date(Date.parse(at) - 30000).toISOString() }, totalReturned: money('0.00'), netPaid: money('100.00'), returns: [], registrations: [], canQuery: true,
    latestCheck: { id: id(4), version: 3, status: 'CHECKED', requestedAt: at, updatedAt: at, issue: null, canRegister: true, registrationIssue: null,
      evidence: { status: 'PARTIALLY_RETURNED', revision: 2, observedAt: at, validUntil: new Date(Date.parse(at) + 300000).toISOString(), originalRevision: 1, originalStatus: 'SUCCEEDED', returns: [proof('bank-return', '40.00', at)], totalReturned: money('40.00'), newReturned: money('40.00') } } }
}
function accepted(value) {
  const item = value.latestCheck.evidence.returns[0]
  value.returns = [{ registrationId: id(10), proof: structuredClone(item) }]
  value.totalReturned = money('40.00'); value.netPaid = money('60.00'); value.latestCheck.evidence.newReturned = money('0.00')
  value.registrations = [{ id: id(10), outcome: 'PARTIALLY_RETURNED', totalReturned: money('40.00'), registeredBy: 'finance', registeredAt: value.latestCheck.updatedAt, evidenceReference: 'first-proof', reason: '核对原件' }]
  return value
}
const receipt = input => ({ reportId: id(2), checkId: 'checkId' in input ? input.checkId : id(7), checkVersion: 'checkId' in input ? input.checkVersion + 1 : 1, registrationId: 'checkId' in input ? id(8) : null, returnVersion: 'checkId' in input ? input.returnVersion + 1 : Math.max(1, input.returnVersion), settlementVersion: input.settlementVersion, auditEventId: id(9) })
let scope = 0
function mount(component = Component) {
  const props = reactive({ ...binding(), applicationVersion: 3, financialVersion: 2, scopeKey: 'expense-return-' + ++scope, locked: false }), changed = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, { ...props, onChanged: () => changed.push(true) })
  const instance = app.mount({})
  return { props, state: instance.$.setupState, changed, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}
function prepareRegistration(item) { item.state.prepare('REGISTER'); item.state.reference = 'proof'; item.state.comment = '核对银行和应付原件'; item.state.acknowledged = true }

test('报销原付款与独立应付贷方必须一致，金额及原件错误不展示办理按钮', () => {
  assert.doesNotThrow(() => rules.validateExpenseReturn(view(), binding()))
  for (const change of [v => v.reportId = id(99), v => v.roundNo = 2, v => v.netPaid.value = '99.00', v => v.reviewRequired = false,
    v => v.latestCheck.evidence.returns[0].funding.channel = 'CASH', v => v.latestCheck.evidence.returns[0].posting.amount.value = '39.99',
    v => v.latestCheck.evidence.returns[0].funding.transactionReference = 'original-payment', v => v.latestCheck.evidence.returns[0].posting.accountCode = '',
    v => v.latestCheck.evidence.returns[0].posting.accountingDate = '2026-02-31', v => v.latestCheck.evidence.returns.push(structuredClone(v.latestCheck.evidence.returns[0])),
    v => v.latestCheck.evidence.status = 'RETURNED', v => v.latestCheck.evidence.originalStatus = 'REVERSED', v => v.latestCheck.evidence.originalRevision = 0,
    v => v.latestCheck.evidence.validUntil = 'invalid', v => v.latestCheck.evidence.newReturned.value = '80.00', v => delete v.registrations, v => v.returns = null]) {
    const invalid = view(); change(invalid); assert.throws(() => rules.validateExpenseReturn(invalid, binding()))
  }
  const full = view(); full.latestCheck.evidence.returns = [proof('full-return', '100.00', full.latestCheck.updatedAt)]
  full.latestCheck.evidence.status = 'RETURNED'; full.latestCheck.evidence.originalStatus = 'REVERSED'; full.latestCheck.evidence.totalReturned = money('100.00'); full.latestCheck.evidence.newReturned = money('100.00')
  assert.doesNotThrow(() => rules.validateExpenseReturn(full, binding()))
})
test('累计退回与本次新增分开，已登记原件及首次归属必须保留', () => {
  const value = accepted(view()); value.latestCheck.evidence.returns.push(proof('second-return', '10.00', value.latestCheck.updatedAt))
  value.latestCheck.evidence.totalReturned = money('50.00'); value.latestCheck.evidence.newReturned = money('10.00')
  assert.doesNotThrow(() => rules.validateExpenseReturn(value, binding()))
  for (const change of [v => v.latestCheck.evidence.returns.shift(), v => v.latestCheck.evidence.returns[0].posting.entryReference = 'changed', v => v.totalReturned.value = '30.00',
    v => v.returns[0].registrationId = id(77), v => v.registrations[0].totalReturned.value = '50.00', v => v.latestCheck.evidence.newReturned.value = '50.00', v => v.registrations[0].outcome = 'RETURNED']) {
    const invalid = structuredClone(value); change(invalid); assert.throws(() => rules.validateExpenseReturn(invalid, binding()))
  }
})
test('登记只提交当前版本和结论，过期证据及错单回执拒绝采用', () => {
  const value = view(), input = rules.expenseReturnRegisterInput(value, ' proof ', ' 核对 ')
  assert.deepEqual(input, { settlementVersion: 4, returnVersion: 2, checkId: id(4), checkVersion: 3, outcome: 'PARTIALLY_RETURNED', evidenceReference: 'proof', comment: '核对' })
  assert.throws(() => rules.expenseReturnRegisterInput(value, 'proof', '核对', Date.parse(value.latestCheck.evidence.validUntil)), /过期/)
  assert.doesNotThrow(() => rules.validateExpenseReturnReceipt(receipt(input), value, input))
  for (const change of [r => r.reportId = id(99), r => r.returnVersion++, r => r.settlementVersion++, r => r.checkVersion--, r => r.registrationId = null]) {
    const invalid = receipt(input); change(invalid); assert.throws(() => rules.validateExpenseReturnReceipt(invalid, value, input))
  }
  const confirmed = { ...input, outcome: 'CONFIRMED' }; assert.doesNotThrow(() => rules.validateExpenseReturnReceipt({ ...receipt(confirmed), settlementVersion: 5 }, value, confirmed))
})
test('真实组件刷新只读，明确查询与勾选确认后才发送登记', async () => {
  const writes = []; api.expensePaymentReturn = async () => view()
  api.queryExpensePaymentReturn = async (report, input) => { writes.push({ report, input }); return receipt(input) }
  api.registerExpensePaymentReturn = async (report, input) => { writes.push({ report, input }); return receipt(input) }
  const item = mount()
  try {
    await settle(); await item.state.load(); assert.equal(item.state.error, ''); assert.equal(writes.length, 0)
    item.state.prepare('QUERY'); item.state.comment = '读取原件'; await item.state.execute(); assert.deepEqual(writes[0], { report: id(2), input: { settlementVersion: 4, returnVersion: 2, comment: '读取原件' } })
    item.state.prepare('REGISTER'); item.state.reference = 'proof'; item.state.comment = '核对'; await item.state.execute(); assert.equal(writes.length, 1)
    item.state.acknowledged = true; await item.state.execute(); assert.equal(writes.length, 2); assert.match(item.state.notice, /退回复核已保存/)
  } finally { item.close() }
})
test('身份切换清除财务材料与迟到写回，申请人不能沿用旧按钮', async () => {
  let finish; api.expensePaymentReturn = async () => view(); api.registerExpensePaymentReturn = () => new Promise(resolve => { finish = resolve }); const item = mount()
  try {
    await settle(); prepareRegistration(item); const write = item.state.execute()
    api.expensePaymentReturn = async () => ({ ...view(), canQuery: false, latestCheck: null }); item.props.scopeKey = 'owner'; await settle()
    finish(receipt({ settlementVersion: 4, returnVersion: 2, checkId: id(4), checkVersion: 3 })); await write
    assert.equal(item.state.notice, ''); assert.equal(item.state.reference, ''); assert.equal(item.state.comment, ''); assert.equal(item.state.acknowledged, false)
    item.state.prepare('QUERY'); assert.equal(item.state.pending, null)
  } finally { item.close() }
})
test('失权清除原件，未知写入保留原正文和幂等键恢复', async () => {
  api.expensePaymentReturn = async () => view(); api.registerExpensePaymentReturn = async () => { throw { status: 403, code: 'FORBIDDEN' } }; const denied = mount()
  try { await settle(); prepareRegistration(denied); await denied.state.execute(); assert.equal(denied.state.view, null); assert.equal(denied.state.reference, '') }
  finally { denied.close() }
  const calls = []; bindAuthenticationActor({ tenantId: 'demo', userId: 'finance', roles: ['FINANCE'] }); api.expensePaymentReturn = async () => view(); let lost = true
  global.fetch = async (url, options) => { const input = JSON.parse(options.body); calls.push({ url, body: options.body, key: options.headers['Idempotency-Key'] ?? options.headers.get?.('Idempotency-Key') }); if (lost) throw new TypeError('Synthetic lost response'); return new Response(JSON.stringify(receipt(input)), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const item = mount()
  try {
    await settle(); prepareRegistration(item); await item.state.execute(); assert.equal(item.state.unconfirmed, true)
    lost = false; await writeRequests.recover(writeRequests.pending()[0].id); assert.equal(calls[0].key, calls[1].key); assert.equal(calls[0].body, calls[1].body)
    assert.match(calls[0].url, /payment-return\/registrations$/); assert.equal(item.state.requiresRefresh, true)
    await item.state.load(); assert.equal(item.state.requiresRefresh, false)
  } finally { item.close() }
})
test('只有已确认版本变化通知父级刷新结算和归档，初次及身份迟到响应不触发循环', async () => {
  let current = view(); api.expensePaymentReturn = async () => structuredClone(current); const item = mount()
  try {
    await settle(); await item.state.load(); assert.equal(item.changed.length, 0)
    current.returnVersion++; await item.state.load(); assert.equal(item.changed.length, 1)
    let finish; api.expensePaymentReturn = () => new Promise(resolve => { finish = resolve }); const old = item.state.load()
    api.expensePaymentReturn = async () => ({ ...view(), canQuery: false, latestCheck: null }); item.props.scopeKey = 'new-owner'; await settle()
    finish({ ...view(), returnVersion: 99 }); await old; assert.equal(item.changed.length, 1); assert.equal(item.state.view.canQuery, false)
  } finally { item.close() }
})
test('退回操作中的身份切换不会卡住父结算读取', async () => {
  let reads = 0; api.expenseSettlement = async () => { reads++; return { ...binding(), applicationVersion: 3, financialVersion: 2, settlement: null, canRetry: false } }
  const item = mount(Parent)
  try { await settle(); item.state.returnBusy = true; item.props.scopeKey = 'new-scope'; await settle(); assert.equal(item.state.returnBusy, false); assert.equal(reads, 2); assert.equal(item.state.error, '') }
  finally { item.close() }
})
