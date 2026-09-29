import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_ADVANCE_REPAYMENT)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_ADVANCEREPAYMENTSTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const money = value => ({ value, currency: 'CNY' }), binding = () => ({ applicationId: 'application', advanceId: 'advance', roundNo: 1 })
function view() {
  const at = new Date(Date.now() - 5000).toISOString()
  return { ...binding(), balance: { version: 1, status: 'PAID_OUT', paid: money('100.00'), available: money('100.00'), reserved: money('0.00'), offset: money('0.00'), repaid: money('0.00'), outstanding: money('100.00'), receivedRepayments: money('0.00'), returnedRepayments: money('0.00') }, canQuery: true,
    latestCheck: { id: 'check', version: 3, status: 'CHECKED', receiptReference: 'receipt-original', requestedAt: at, updatedAt: at, issue: null, canRecord: true, confirmationIssue: null,
      evidence: { status: 'CONFIRMED', revision: 1, observedAt: at, validUntil: new Date(Date.parse(at) + 300000).toISOString(), funding: { channel: 'BANK_TRANSFER', transactionReference: 'bank-original', amount: money('25.00'), receivedAt: at }, posting: { voucherReference: 'erp-original', entryReference: 'row-1', amount: money('25.00'), accountingDate: at.slice(0, 10), postedAt: at } } }, records: [], nextBeforeId: null }
}
let scope = 0
function mount() {
  const props = reactive({ ...binding(), scopeKey: 'repayment-' + ++scope, locked: false }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value) })
  const instance = app.mount({})
  return { props, events, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}
const receipt = input => ({ advanceId: 'advance', checkId: 'checkId' in input ? input.checkId : 'new-check', checkVersion: 'checkId' in input ? input.checkVersion + 1 : 1, repaymentId: 'checkId' in input ? 'repayment' : null, advanceVersion: input.advanceVersion + ('checkId' in input ? 1 : 0), auditEventId: 'audit' })

test('余额采用精确分，原借款绑定和资金守恒不允许跨轮次、缺失值或矛盾金额', () => {
  assert.doesNotThrow(() => rules.validateRepaymentView(view(), binding()))
  for (const mutate of [v => v.advanceId = 'other', v => v.applicationId = 'other', v => v.roundNo = 2, v => v.balance.paid.value = 100, v => v.balance.repaid.value = '0.01', v => v.balance.available.currency = 'USD', v => v.balance.status = 'SETTLED', v => delete v.latestCheck, v => v.latestCheck.evidence.posting.amount.value = '24.99', v => v.latestCheck.evidence.funding.receivedAt = 'invalid', v => v.latestCheck.evidence.posting.accountingDate = '2026-02-31', v => v.latestCheck.evidence.status = 'PENDING', v => v.nextBeforeId = 'stranger']) {
    const invalid = view(); mutate(invalid); assert.throws(() => rules.validateRepaymentView(invalid, binding()))
  }
  const held = view(); held.balance.status = 'REPAYMENT_REVIEW'; held.balance.available.value = '0.00'; held.latestCheck.canRecord = false; held.latestCheck.confirmationIssue = 'ADVANCE_REPAYMENT_REVIEW_REQUIRED'
  assert.doesNotThrow(() => rules.validateRepaymentView(held, binding()))
  const unpaid = { ...binding(), balance: null, canQuery: false, latestCheck: null, records: [], nextBeforeId: null }
  assert.doesNotThrow(() => rules.validateRepaymentView(unpaid, binding()))
})

test('查询与确认只发送引用和原版本，证据过期或说明为空不能继续', () => {
  const value = view(); value.amount = '999'; value.employeeId = 'forged'
  assert.deepEqual(rules.repaymentQueryInput(value, ' RECEIPT-1 ', ' 查询原收款 '), { advanceVersion: 1, receiptReference: 'RECEIPT-1', comment: '查询原收款' })
  assert.deepEqual(rules.repaymentRecordInput(value, ' 核对原分录 '), { advanceVersion: 1, checkId: 'check', checkVersion: 3, comment: '核对原分录' })
  for (const reference of ['', 'x'.repeat(129), 'BANK\n01']) assert.throws(() => rules.repaymentQueryInput(value, reference, '依据'))
  assert.throws(() => rules.repaymentRecordInput(value, '')); assert.throws(() => rules.repaymentRecordInput(value, '确认', Date.parse(value.latestCheck.evidence.validUntil)))
  value.canQuery = false; assert.throws(() => rules.repaymentQueryInput(value, 'REF', '确认'))
  value.latestCheck.canRecord = false; assert.throws(() => rules.repaymentRecordInput(value, '确认'))
})

test('原放款退回独立减少欠款，全额退回不能伪装为主动还款或保留可用额度', () => {
  const value = view(); value.balance.returnedDisbursements = money('40.00'); value.balance.outstanding = money('60.00'); value.balance.available = money('60.00'); value.balance.status = 'PARTIALLY_SETTLED'
  assert.doesNotThrow(() => rules.validateRepaymentView(value, binding()))
  for (const change of [v => v.balance.available = money('100.00'), v => v.balance.outstanding = money('100.00'), v => v.balance.returnedDisbursements = null, v => v.balance.returnedDisbursements = money('40.01'), v => v.balance.repaid = money('40.00')]) {
    const invalid = clone(value); change(invalid); assert.throws(() => rules.validateRepaymentView(invalid, binding()))
  }
  value.balance.returnedDisbursements = money('100.00'); value.balance.available = money('0.00'); value.balance.outstanding = money('0.00'); value.balance.status = 'RETURNED'; value.latestCheck.canRecord = false
  assert.doesNotThrow(() => rules.validateRepaymentView(value, binding()))
  value.balance.status = 'SETTLED'; assert.throws(() => rules.validateRepaymentView(value, binding()))
})

test('动作回执必须匹配原借款、原查询及精确下一版本', () => {
  const value = view(), input = rules.repaymentRecordInput(value, '确认')
  assert.doesNotThrow(() => rules.validateRepaymentReceipt(receipt(input), value, input))
  for (const change of [{ advanceId: 'other' }, { checkId: 'other' }, { checkVersion: 3 }, { checkVersion: 5 }, { advanceVersion: 1 }, { advanceVersion: 3 }, { repaymentId: null }, { auditEventId: '' }]) assert.throws(() => rules.validateRepaymentReceipt({ ...receipt(input), ...change }, value, input))
})

test('读取与刷新不自动确认，确认表单必须有说明且只发送一次原凭据', async () => {
  const sent = []; api.advanceRepayments = async () => view(); api.recordAdvanceRepayment = async (id, input) => { sent.push({ id, input }); return receipt(input) }
  const item = mount()
  try {
    await settle(); await item.state.load(); assert.equal(sent.length, 0)
    item.state.prepare('RECORD'); await item.state.execute(); assert.equal(sent.length, 0)
    item.state.comment = '核对实际入账'; await item.state.execute(); assert.equal(sent.length, 1)
    assert.deepEqual(sent[0], { id: 'advance', input: { advanceVersion: 1, checkId: 'check', checkVersion: 3, comment: '核对实际入账' } }); assert.match(item.state.notice, /已确认/)
  } finally { item.close() }
})

test('查询登记不会被当作还款成功，排队期间刷新仍只读', async () => {
  const sent = []; let queued = false
  api.advanceRepayments = async () => { const value = view(); if (queued) { value.canQuery = false; value.latestCheck = { ...value.latestCheck, id: 'new-check', version: 1, status: 'QUEUED', canRecord: false, evidence: null, confirmationIssue: 'ADVANCE_REPAYMENT_EVIDENCE_UNAVAILABLE' } } return value }
  api.queryAdvanceRepayment = async (id, input) => { sent.push({ id, input }); queued = true; return receipt(input) }
  const item = mount()
  try {
    await settle(); item.state.prepare('QUERY'); item.state.reference = 'REF'; item.state.comment = '核对收款'; await item.state.execute()
    assert.equal(sent.length, 1); assert.equal(item.state.view.latestCheck.status, 'QUEUED'); assert.match(item.state.notice, /查询已登记/)
    item.state.prepare('RECORD'); assert.equal(item.state.pending, null); await item.state.load(); assert.equal(sent.length, 1)
  } finally { item.close() }
})

test('申请人只读与异常回执不会获得财务确认或成功提示', async () => {
  api.advanceRepayments = async () => ({ ...view(), canQuery: false, latestCheck: null }); const item = mount()
  try {
    await settle(); item.state.prepare('QUERY'); assert.equal(item.state.pending, null); item.state.prepare('RECORD'); assert.equal(item.state.pending, null)
    api.advanceRepayments = async () => view(); await item.state.load()
    api.recordAdvanceRepayment = async () => ({ advanceId: 'other' }); item.state.prepare('RECORD'); item.state.comment = '核对'; await item.state.execute()
    assert.equal(item.state.notice, ''); assert.equal(item.state.requiresRefresh, true)
  } finally { item.close() }
})

test('身份切换清除未提交材料并丢弃旧身份的迟到读写响应', async () => {
  let completeRead, completeWrite; api.advanceRepayments = () => new Promise(resolve => { completeRead = resolve }); const item = mount()
  try {
    const previousRead = completeRead; item.props.scopeKey = 'next'; await settle(); previousRead(view()); await settle(); assert.equal(item.state.view, null)
    completeRead(view()); await settle(); api.advanceRepayments = async () => ({ ...view(), canQuery: false, latestCheck: null })
    api.recordAdvanceRepayment = () => new Promise(resolve => { completeWrite = resolve })
    item.state.prepare('RECORD'); item.state.comment = '旧身份确认'; const pending = item.state.execute()
    item.props.scopeKey = 'owner'; await settle(); assert.equal(item.state.comment, ''); assert.equal(item.state.pending, null)
    completeWrite(receipt({ advanceVersion: 1, checkId: 'check', checkVersion: 3 })); await pending
    assert.equal(item.state.notice, ''); assert.equal(item.state.saving, false); assert.equal(item.state.view.canQuery, false)
  } finally { item.close() }
})

test('未知提交结果使用同一个幂等键恢复，恢复后必须刷新资金资料', async () => {
  const inputs = [], calls = []; bindAuthenticationActor({ tenantId: 'demo', userId: 'finance', roles: ['FINANCE'] })
  api.advanceRepayments = async () => view(); let lost = true
  global.fetch = async (url, options) => { calls.push({ url, key: options.headers['Idempotency-Key'] ?? options.headers.get?.('Idempotency-Key') }); const input = JSON.parse(options.body); inputs.push(input); if (lost) throw new TypeError('Synthetic connection loss'); return new Response(JSON.stringify(receipt(input)), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const item = mount()
  try {
    await settle(); item.state.prepare('RECORD'); item.state.comment = '核对'; await item.state.execute()
    assert.equal(item.state.unconfirmed, true); assert.equal(writeRequests.pending().length, 1)
    lost = false; await writeRequests.recover(writeRequests.pending()[0].id); assert.equal(calls.length, 2); assert.equal(calls[0].key, calls[1].key)
    assert.deepEqual(inputs[0], inputs[1]); assert.equal(item.state.requiresRefresh, true); await item.state.load(); assert.equal(item.state.requiresRefresh, false)
  } finally { item.close() }
})

test('翻页失去字段权限立即清空已显示的收款记录和余额', async () => {
  const page = view(), at = page.latestCheck.updatedAt
  page.records = Array.from({ length: 25 }, (_, index) => ({ id: `record-${index}`, receiptReference: `REF-${index}`, channel: 'CASH', amount: money('1.00'), receivedAt: at, voucherReference: `ERP-${index}`, entryReference: '1', accountingDate: at.slice(0, 10), postedAt: at, recordedBy: 'finance', recordedAt: at, reviewRequired: false, returned: null }))
  page.nextBeforeId = 'record-24'; page.balance.status = 'PARTIALLY_SETTLED'; page.balance.repaid = money('25.00'); page.balance.receivedRepayments = money('25.00'); page.balance.outstanding = money('75.00'); page.balance.available = money('75.00')
  api.advanceRepayments = async (id, round, before) => { if (before) throw { status: 403, code: 'FORBIDDEN' }; return page }
  const item = mount()
  try { await settle(); assert.equal(item.state.records.length, 25); await item.state.load(true); assert.equal(item.state.view, null); assert.deepEqual(item.state.records, []) }
  finally { item.close() }
})


test('提交时失去字段权限立即撤下余额、原收款及确认表单', async () => {
  api.advanceRepayments = async () => view(); api.recordAdvanceRepayment = async () => { throw { status: 403, code: 'FORBIDDEN' } }; const item = mount()
  try {
    await settle(); item.state.prepare('RECORD'); item.state.comment = '失权前原材料'; await item.state.execute()
    assert.equal(item.state.view, null); assert.deepEqual(item.state.records, []); assert.equal(item.state.pending, null); assert.equal(item.state.comment, '')
  } finally { item.close() }
})

test('逐笔复核更新余额后撤下父面板旧查询与待提交表单，明确要求刷新', async () => {
  api.advanceRepayments = async () => view(); const item = mount()
  try {
    await settle(); item.state.selectedRepayment = 'repayment'; item.state.prepare('QUERY'); item.state.reference = 'old'; item.state.comment = '旧材料'
    item.state.reviewRefreshed({ advanceId: 'advance', balance: { ...view().balance, version: 2 }, original: { id: 'repayment' } })
    assert.equal(item.state.view.latestCheck, null); assert.equal(item.state.pending, null); assert.equal(item.state.reference, ''); assert.equal(item.state.comment, '')
    assert.equal(item.state.requiresRefresh, true); assert.match(item.state.notice, /刷新还款记录/)
  } finally { item.close() }
})
