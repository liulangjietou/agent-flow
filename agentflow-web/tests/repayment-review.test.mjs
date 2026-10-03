import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_REPAYMENT_REVIEW)
const returns = await import(process.env.AGENTFLOW_TEST_ADVANCE_REPAYMENT)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_ADVANCEREPAYMENTREVIEW)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve)), money = value => ({ value, currency: 'CNY' })
const binding = () => ({ applicationId: 'application', advanceId: 'advance', repaymentId: 'repayment', roundNo: 1 })
function view() {
  const at = new Date(Date.now() - 5000).toISOString()
  return { applicationId: 'application', advanceId: 'advance', roundNo: 1,
    balance: { version: 3, status: 'REPAYMENT_REVIEW', paid: money('100.00'), available: money('0.00'), reserved: money('0.00'), offset: money('0.00'), repaid: money('25.00'), outstanding: money('75.00'), receivedRepayments: money('25.00'), returnedRepayments: money('0.00') },
    original: { id: 'repayment', receiptReference: 'receipt', channel: 'CASH', amount: money('25.00'), receivedAt: at, voucherReference: 'original-voucher', entryReference: 'credit', accountingDate: at.slice(0, 10), postedAt: at, recordedBy: 'finance', recordedAt: at, reviewRequired: true, returned: null },
    canQuery: true, latestDecision: null, latestCheck: { id: 'check', version: 3, status: 'CHECKED', requestedAt: at, updatedAt: at, issue: null, canResolve: true, confirmationIssue: null,
      evidence: { status: 'RETURNED', revision: 2, observedAt: at, validUntil: new Date(Date.parse(at) + 300000).toISOString(), originalRevision: 2,
        fundsReturn: { channel: 'CASH', transactionReference: 'refund', amount: money('25.00'), returnedAt: at }, posting: { voucherReference: 'return-voucher', entryReference: 'debit', amount: money('25.00'), accountingDate: at.slice(0, 10), postedAt: at } } } }
}
function partialView() {
  const value = view(), evidence = value.latestCheck.evidence
  evidence.status = 'PARTIALLY_RETURNED'
  evidence.fundsReturn.amount = money('5.00'); evidence.posting.amount = money('5.00')
  evidence.additionalReturns = [{ fundsReturn: { ...evidence.fundsReturn, transactionReference: 'second-refund', amount: money('7.00') }, posting: { ...evidence.posting, entryReference: 'second-debit', amount: money('7.00') } }]
  return value
}
const receipt = input => ({ advanceId: 'advance', repaymentId: 'repayment', checkId: 'checkId' in input ? input.checkId : 'new-check', checkVersion: 'checkId' in input ? input.checkVersion + 1 : 1, resolutionId: 'checkId' in input ? 'resolution' : null, advanceVersion: input.advanceVersion + ('checkId' in input ? 1 : 0), auditEventId: 'audit' })
let scope = 0
function mount() {
  const props = reactive({ ...binding(), scopeKey: 'review-' + ++scope, locked: false })
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props })
  const instance = app.mount({})
  return { props, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('原凭证同时冻结时仍可独立核对员工还款，不将凭证冻结当成已解除', () => {
  const held = view(); held.balance.status = 'VOUCHER_REVIEW'
  assert.doesNotThrow(() => rules.validateRepaymentReview(held, binding()))
  held.balance.available = money('75.00')
  assert.throws(() => rules.validateRepaymentReview(held, binding()))
})

test('原收款绑定、净额守恒、真实退回全额及独立借方分录不能被页面替换', () => {
  assert.doesNotThrow(() => rules.validateRepaymentReview(view(), binding()))
  for (const change of [v => v.original.id = 'other', v => v.roundNo = 2, v => v.balance.returnedRepayments.value = '25.00', v => v.latestCheck.evidence.fundsReturn.amount.value = '24.99', v => v.latestCheck.evidence.posting = null, v => v.latestCheck.evidence.originalRevision = 0, v => v.latestCheck.evidence.status = 'UNRESOLVED', v => v.latestCheck.evidence.validUntil = 'invalid', v => v.latestCheck.evidence.posting.accountingDate = '2026-02-31', v => v.original.reviewRequired = false, v => delete v.latestDecision]) {
    const value = view(); change(value); assert.throws(() => rules.validateRepaymentReview(value, binding()))
  }
  const returned = view(); returned.balance.status = 'PAID_OUT'; returned.balance.available = money('100.00'); returned.balance.outstanding = money('100.00'); returned.balance.repaid = money('0.00'); returned.balance.returnedRepayments = money('25.00')
  returned.original.reviewRequired = false; returned.original.returned = { resolutionId: 'resolution', repaymentId: 'repayment', fundsReturn: returned.latestCheck.evidence.fundsReturn, posting: returned.latestCheck.evidence.posting }
  returned.latestDecision = { id: 'resolution', outcome: 'RETURNED', resolvedBy: 'finance', resolvedAt: returned.latestCheck.updatedAt, evidenceReference: 'proof' }; returned.latestCheck = null
  assert.doesNotThrow(() => rules.validateRepaymentReview(returned, binding()))
})
test('裁决只发送展示结论与版本，过期、空材料或跨还款回执均拒绝', () => {
  const value = view(), input = rules.repaymentResolutionInput(value, ' proof ', ' 核对 ')
  assert.deepEqual(input, { advanceVersion: 3, checkId: 'check', checkVersion: 3, outcome: 'RETURNED', evidenceReference: 'proof', comment: '核对' })
  assert.throws(() => rules.repaymentResolutionInput(value, '', '核对')); assert.throws(() => rules.repaymentResolutionInput(value, 'proof', ''))
  assert.throws(() => rules.repaymentResolutionInput(value, 'proof', '核对', Date.parse(value.latestCheck.evidence.validUntil)))
  assert.doesNotThrow(() => rules.validateRepaymentReviewReceipt(receipt(input), value, input))
  for (const change of [{ repaymentId: 'other' }, { resolutionId: null }, { checkVersion: 3 }, { advanceVersion: 3 }, { checkId: 'other' }]) assert.throws(() => rules.validateRepaymentReviewReceipt({ ...receipt(input), ...change }, value, input))
})
test('多笔部分退回必须独立配对且累计不超过原款，旧单笔字段仍兼容', () => {
  const value = partialView()
  assert.doesNotThrow(() => rules.validateRepaymentReview(value, binding()))
  assert.deepEqual(returns.repaymentReturnTotal(rules.repaymentReviewReturns(value.latestCheck.evidence), 'CNY'), money('12.00'))
  for (const change of [
    v => v.latestCheck.evidence.additionalReturns[0].fundsReturn.transactionReference = 'refund',
    v => v.latestCheck.evidence.additionalReturns[0].posting.entryReference = 'debit',
    v => v.latestCheck.evidence.additionalReturns[0].posting.amount = money('6.99'),
    v => { v.latestCheck.evidence.additionalReturns[0].fundsReturn.amount = money('20.01'); v.latestCheck.evidence.additionalReturns[0].posting.amount = money('20.01') },
    v => { v.latestCheck.evidence.additionalReturns[0].fundsReturn.amount = money('20.00'); v.latestCheck.evidence.additionalReturns[0].posting.amount = money('20.00') },
    v => v.latestCheck.evidence.status = 'RETURNED',
    v => { v.latestCheck.evidence.fundsReturn = null; v.latestCheck.evidence.posting = null },
    v => v.latestCheck.evidence.additionalReturns = null,
    v => v.original.additionalReturns = null
  ]) { const invalid = partialView(); change(invalid); assert.throws(() => rules.validateRepaymentReview(invalid, binding())) }
  value.latestCheck.evidence.additionalReturns[0].fundsReturn.amount = money('20.00')
  value.latestCheck.evidence.additionalReturns[0].posting.amount = money('20.00')
  value.latestCheck.evidence.status = 'RETURNED'
  assert.doesNotThrow(() => rules.validateRepaymentReview(value, binding()))
})
test('已确认五元和新增七元分别展示，确认只发送版本和部分退回结论', async () => {
  const value = partialView(), evidence = value.latestCheck.evidence
  value.original.returned = { resolutionId: 'first-resolution', repaymentId: 'repayment', fundsReturn: evidence.fundsReturn, posting: evidence.posting }
  value.balance.returnedRepayments = money('5.00'); value.balance.repaid = money('20.00'); value.balance.outstanding = money('80.00')
  value.latestDecision = { id: 'first-resolution', outcome: 'PARTIALLY_RETURNED', resolvedBy: 'finance', resolvedAt: value.latestCheck.updatedAt, evidenceReference: 'first-proof' }
  const writes = []; api.advanceRepaymentReview = async () => value
  api.resolveAdvanceRepayment = async (loan, repayment, input) => { writes.push(input); return receipt(input) }
  const item = mount()
  try {
    await settle(); assert.equal(item.state.error, '')
    assert.equal(item.state.confirmedReturns.length, 1); assert.equal(item.state.candidateReturns.length, 2); assert.equal(item.state.newReturns.length, 1)
    assert.deepEqual(returns.repaymentReturnTotal(item.state.newReturns, 'CNY'), money('7.00'))
    item.state.prepare('RESOLVE'); item.state.reference = 'second-proof'; item.state.comment = '核对累计退回十二元'; await item.state.execute()
    assert.deepEqual(writes, [{ advanceVersion: 3, checkId: 'check', checkVersion: 3, outcome: 'PARTIALLY_RETURNED', evidenceReference: 'second-proof', comment: '核对累计退回十二元' }])
    value.original.additionalReturns = [{ resolutionId: 'second-resolution', repaymentId: 'repayment', ...evidence.additionalReturns[0] }]
    value.balance.returnedRepayments = money('12.00'); value.balance.repaid = money('13.00'); value.balance.outstanding = money('87.00'); value.balance.available = money('87.00'); value.balance.status = 'PARTIALLY_SETTLED'
    value.original.reviewRequired = false; value.latestCheck = null
    await item.state.load(); assert.equal(item.state.error, ''); assert.equal(item.state.confirmedReturns.length, 2); assert.equal(item.state.newReturns.length, 0)
  } finally { item.close() }
})
test('读取和刷新没有写入，查询与裁决分别明确确认', async () => {
  const writes = []; api.advanceRepaymentReview = async () => view()
  api.queryAdvanceRepaymentReview = async (loan, repayment, input) => { writes.push({ loan, repayment, input }); return receipt(input) }
  api.resolveAdvanceRepayment = async (loan, repayment, input) => { writes.push({ loan, repayment, input }); return receipt(input) }
  const item = mount()
  try {
    await settle(); await item.state.load(); assert.equal(writes.length, 0)
    item.state.prepare('QUERY'); item.state.comment = '读取原件'; await item.state.execute(); assert.deepEqual(writes[0].input, { advanceVersion: 3, comment: '读取原件' }); assert.match(item.state.notice, /查询已登记/)
    item.state.prepare('RESOLVE'); item.state.reference = 'proof'; item.state.comment = '核对完整退回'; await item.state.execute()
    assert.equal(writes.length, 2); assert.equal(writes[1].repayment, 'repayment'); assert.equal(writes[1].input.outcome, 'RETURNED'); assert.match(item.state.notice, /复核已保存/)
  } finally { item.close() }
})
test('申请人只读、身份切换及迟到写入不会显示旧财务的成功或材料', async () => {
  let finish; api.advanceRepaymentReview = async () => view(); api.resolveAdvanceRepayment = () => new Promise(resolve => { finish = resolve }); const item = mount()
  try {
    await settle(); item.state.prepare('RESOLVE'); item.state.reference = 'proof'; item.state.comment = '原身份材料'; const write = item.state.execute()
    api.advanceRepaymentReview = async () => ({ ...view(), canQuery: false, latestCheck: null }); item.props.scopeKey = 'owner'; await settle()
    finish(receipt({ advanceVersion: 3, checkId: 'check', checkVersion: 3 })); await write
    assert.equal(item.state.notice, ''); assert.equal(item.state.comment, ''); assert.equal(item.state.reference, ''); item.state.prepare('QUERY'); assert.equal(item.state.pending, null)
  } finally { item.close() }
})
test('写入失权撤下原收款、退款依据和未提交材料', async () => {
  api.advanceRepaymentReview = async () => view(); api.resolveAdvanceRepayment = async () => { throw { status: 403, code: 'FORBIDDEN' } }; const item = mount()
  try {
    await settle(); item.state.prepare('RESOLVE'); item.state.reference = 'proof'; item.state.comment = '原材料'; await item.state.execute()
    assert.equal(item.state.view, null); assert.equal(item.state.pending, null); assert.equal(item.state.reference, ''); assert.equal(item.state.comment, '')
  } finally { item.close() }
})
test('未知裁决结果用原幂等键恢复，恢复成功后必须重新核对原件', async () => {
  const calls = []; bindAuthenticationActor({ tenantId: 'demo', userId: 'finance', roles: ['FINANCE'] }); api.advanceRepaymentReview = async () => view(); let lost = true
  global.fetch = async (url, options) => { const input = JSON.parse(options.body); calls.push({ url, input, key: options.headers['Idempotency-Key'] ?? options.headers.get?.('Idempotency-Key') }); if (lost) throw new TypeError('Synthetic lost response'); return new Response(JSON.stringify(receipt(input)), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const item = mount()
  try {
    await settle(); item.state.prepare('RESOLVE'); item.state.reference = 'proof'; item.state.comment = '完整核对'; await item.state.execute(); assert.equal(item.state.unconfirmed, true)
    lost = false; await writeRequests.recover(writeRequests.pending()[0].id); assert.equal(calls[0].key, calls[1].key); assert.deepEqual(calls[0].input, calls[1].input); assert.equal(item.state.requiresRefresh, true)
    await item.state.load(); assert.equal(item.state.requiresRefresh, false); assert.equal(calls.length, 2)
  } finally { item.close() }
})
