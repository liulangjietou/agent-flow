import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_DISBURSEMENT_RETURN)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_ADVANCEDISBURSEMENTRETURN)
const { default: Parent } = await import(process.env.AGENTFLOW_TEST_ADVANCEREPAYMENTSTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve)), money = value => ({ value, currency: 'CNY' })
const binding = () => ({ applicationId: 'application', advanceId: 'advance', roundNo: 1 })
function proof(reference, value, at) {
  return { funding: { channel: 'BANK_TRANSFER', transactionReference: reference, amount: money(value), receivedAt: at }, posting: { voucherReference: 'credit-' + reference, entryReference: 'row-1', amount: money(value), accountingDate: at.slice(0, 10), postedAt: at } }
}
function view() {
  const at = new Date(Date.now() - 5000).toISOString()
  return { ...binding(), balance: { version: 3, status: 'PAYMENT_REVIEW', paid: money('100.00'), available: money('0.00'), reserved: money('0.00'), offset: money('0.00'), repaid: money('20.00'), outstanding: money('80.00'), receivedRepayments: money('20.00'), returnedRepayments: money('0.00'), returnedDisbursements: money('0.00') },
    original: { paymentId: 'payment', paymentReference: 'original-payment', amount: money('100.00'), paidAt: new Date(Date.parse(at) - 30000).toISOString() }, returns: [], canQuery: true, latestDecision: null,
    latestCheck: { id: 'check', version: 3, status: 'CHECKED', requestedAt: at, updatedAt: at, issue: null, canResolve: true, confirmationIssue: null,
      evidence: { status: 'PARTIALLY_RETURNED', revision: 2, observedAt: at, validUntil: new Date(Date.parse(at) + 300000).toISOString(), originalRevision: 1, originalStatus: 'SUCCEEDED', returns: [proof('bank-return', '40.00', at)] } } }
}
function accepted(value) {
  const item = value.latestCheck.evidence.returns[0]
  value.returns = [{ resolutionId: 'first-resolution', proof: structuredClone(item) }]
  value.balance.returnedDisbursements = money('40.00'); value.balance.outstanding = money('40.00')
  value.latestDecision = { id: 'first-resolution', outcome: 'PARTIALLY_RETURNED', resolvedBy: 'finance', resolvedAt: value.latestCheck.updatedAt, evidenceReference: 'first-proof' }
  return value
}
const receipt = input => ({ advanceId: 'advance', checkId: 'checkId' in input ? input.checkId : 'new-check', checkVersion: 'checkId' in input ? input.checkVersion + 1 : 1, resolutionId: 'checkId' in input ? 'resolution' : null, advanceVersion: input.advanceVersion + ('checkId' in input ? 1 : 0), auditEventId: 'audit' })
let scope = 0
function mount(component = Component) {
  const props = reactive({ ...binding(), scopeKey: 'bank-review-' + ++scope, locked: false })
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, { ...props })
  const instance = app.mount({})
  return { props, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('原放款、余额与独立银行贷方证据绑定，部分和全额退回状态一致', () => {
  assert.doesNotThrow(() => rules.validateDisbursementReturn(view(), binding()))
  for (const change of [v => v.advanceId = 'other', v => v.roundNo = 2, v => v.original.amount.value = '99.00', v => v.balance.returnedDisbursements = null,
    v => v.latestCheck.evidence.returns[0].funding.channel = 'CASH', v => v.latestCheck.evidence.returns[0].posting.amount.value = '39.99',
    v => v.latestCheck.evidence.returns[0].funding.transactionReference = 'original-payment', v => v.latestCheck.evidence.returns[0].posting.accountingDate = '2026-02-31',
    v => v.latestCheck.evidence.returns.push(structuredClone(v.latestCheck.evidence.returns[0])), v => v.latestCheck.evidence.status = 'RETURNED',
    v => v.latestCheck.evidence.originalStatus = 'REVERSED', v => v.latestCheck.evidence.originalRevision = 0, v => v.latestCheck.evidence.validUntil = 'invalid',
    v => delete v.latestDecision, v => v.returns = null, v => v.latestCheck.evidence.returns = null]) {
    const invalid = view(); change(invalid); assert.throws(() => rules.validateDisbursementReturn(invalid, binding()))
  }
  const full = view(); full.latestCheck.evidence.returns = [proof('full-bank-return', '100.00', full.latestCheck.updatedAt)]
  full.latestCheck.evidence.status = 'RETURNED'; full.latestCheck.evidence.originalStatus = 'REVERSED'
  full.balance.repaid = money('0.00'); full.balance.receivedRepayments = money('0.00'); full.balance.outstanding = money('100.00')
  assert.doesNotThrow(() => rules.validateDisbursementReturn(full, binding()))
})
test('已确认退回必须保留完整原件，累计与本次新增分别合计', () => {
  const value = accepted(view()); value.latestCheck.evidence.returns.push(proof('second-return', '10.00', value.latestCheck.updatedAt))
  assert.doesNotThrow(() => rules.validateDisbursementReturn(value, binding()))
  assert.deepEqual(rules.disbursementReturnTotal(value.latestCheck.evidence.returns, 'CNY'), money('50.00'))
  for (const change of [v => v.latestCheck.evidence.returns.shift(), v => v.latestCheck.evidence.returns[0].posting.entryReference = 'changed', v => v.balance.returnedDisbursements.value = '30.00', v => v.latestDecision.outcome = 'RETURNED']) {
    const invalid = structuredClone(value); change(invalid); assert.throws(() => rules.validateDisbursementReturn(invalid, binding()))
  }
})
test('确认只传结论和连续版本，不能提交金额或使用过期依据', () => {
  const value = view(), input = rules.disbursementResolutionInput(value, ' proof ', ' 核对 ')
  assert.deepEqual(input, { advanceVersion: 3, checkId: 'check', checkVersion: 3, outcome: 'PARTIALLY_RETURNED', evidenceReference: 'proof', comment: '核对' })
  assert.throws(() => rules.disbursementResolutionInput(value, '', '核对')); assert.throws(() => rules.disbursementResolutionInput(value, 'proof', ''))
  assert.throws(() => rules.disbursementResolutionInput(value, 'proof', '核对', Date.parse(value.latestCheck.evidence.validUntil)))
  assert.doesNotThrow(() => rules.validateDisbursementReturnReceipt(receipt(input), value, input))
  for (const invalid of [{ advanceId: 'other' }, { resolutionId: null }, { checkId: 'other' }, { checkVersion: 3 }, { advanceVersion: 3 }]) assert.throws(() => rules.validateDisbursementReturnReceipt({ ...receipt(input), ...invalid }, value, input))
})
test('真实组件只读刷新，明确查询及确认并展示新增金额', async () => {
  const value = accepted(view()); value.latestCheck.evidence.returns.push(proof('second-return', '10.00', value.latestCheck.updatedAt))
  const writes = []; api.advanceDisbursementReview = async () => value
  api.queryAdvanceDisbursementReview = async (loan, input) => { writes.push({ loan, input }); return receipt(input) }
  api.resolveAdvanceDisbursement = async (loan, input) => { writes.push({ loan, input }); return receipt(input) }
  const item = mount()
  try {
    await settle(); await item.state.load(); assert.equal(item.state.error, ''); assert.equal(writes.length, 0)
    assert.equal(item.state.confirmedReturns.length, 1); assert.equal(item.state.candidateReturns.length, 2); assert.equal(item.state.newReturns.length, 1)
    assert.deepEqual(rules.disbursementReturnTotal(item.state.newReturns, 'CNY'), money('10.00'))
    item.state.prepare('QUERY'); item.state.comment = '读取原件'; await item.state.execute(); assert.deepEqual(writes[0], { loan: 'advance', input: { advanceVersion: 3, comment: '读取原件' } })
    item.state.prepare('RESOLVE'); item.state.reference = 'proof'; item.state.comment = '确认新增十元'; await item.state.execute()
    assert.equal(writes.length, 2); assert.equal(writes[1].input.outcome, 'PARTIALLY_RETURNED'); assert.match(item.state.notice, /退回复核已保存/)
  } finally { item.close() }
})
test('申请人只读与身份切换清除旧财务材料和迟到成功', async () => {
  let finish; api.advanceDisbursementReview = async () => view(); api.resolveAdvanceDisbursement = () => new Promise(resolve => { finish = resolve }); const item = mount()
  try {
    await settle(); item.state.prepare('RESOLVE'); item.state.reference = 'proof'; item.state.comment = '原材料'; const write = item.state.execute()
    api.advanceDisbursementReview = async () => ({ ...view(), canQuery: false, latestCheck: null }); item.props.scopeKey = 'owner'; await settle()
    finish(receipt({ advanceVersion: 3, checkId: 'check', checkVersion: 3 })); await write
    assert.equal(item.state.notice, ''); assert.equal(item.state.reference, ''); assert.equal(item.state.comment, ''); item.state.prepare('QUERY'); assert.equal(item.state.pending, null)
  } finally { item.close() }
})
test('失权撤下原放款证据，未知结果使用原幂等请求恢复后重新读取', async () => {
  api.advanceDisbursementReview = async () => view(); api.resolveAdvanceDisbursement = async () => { throw { status: 403, code: 'FORBIDDEN' } }; const denied = mount()
  try {
    await settle(); denied.state.prepare('RESOLVE'); denied.state.reference = 'proof'; denied.state.comment = '核对'; await denied.state.execute()
    assert.equal(denied.state.view, null); assert.equal(denied.state.reference, ''); assert.equal(denied.state.pending, null)
  } finally { denied.close() }
  const calls = []; bindAuthenticationActor({ tenantId: 'demo', userId: 'finance', roles: ['FINANCE'] }); api.advanceDisbursementReview = async () => view(); let lost = true
  global.fetch = async (url, options) => { const input = JSON.parse(options.body); calls.push({ url, input, key: options.headers['Idempotency-Key'] ?? options.headers.get?.('Idempotency-Key') }); if (lost) throw new TypeError('Synthetic lost response'); return new Response(JSON.stringify(receipt(input)), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const item = mount()
  try {
    await settle(); item.state.prepare('RESOLVE'); item.state.reference = 'proof'; item.state.comment = '完整核对'; await item.state.execute(); assert.equal(item.state.unconfirmed, true)
    lost = false; await writeRequests.recover(writeRequests.pending()[0].id); assert.equal(calls[0].key, calls[1].key); assert.deepEqual(calls[0].input, calls[1].input)
    assert.match(calls[0].url, /advance-requests\/advance\/disbursement-resolutions$/); assert.equal(item.state.requiresRefresh, true)
    await item.state.load(); assert.equal(item.state.requiresRefresh, false)
  } finally { item.close() }
})
test('放款复核更新父余额并废弃旧还款候选，身份变化关闭复核面板', async () => {
  const value = view(); api.advanceRepayments = async () => ({ ...binding(), balance: value.balance, canQuery: false, records: [], nextBeforeId: null, latestCheck: null })
  const item = mount(Parent)
  try {
    await settle(); item.state.showDisbursement = true
    const changed = accepted(view()); changed.balance.version = 4; item.state.disbursementRefreshed(changed)
    assert.equal(item.state.view.balance.version, 4); assert.equal(item.state.view.balance.returnedDisbursements.value, '40.00'); assert.equal(item.state.requiresRefresh, true)
    item.props.scopeKey = 'other-actor'; await settle(); assert.equal(item.state.showDisbursement, false)
  } finally { item.close() }
})
