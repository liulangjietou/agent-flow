import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { default: FinanceRendered } = await import(process.env.AGENTFLOW_TEST_FINANCEPAYMENTSTATUSRENDERED)
const rules = await import(process.env.AGENTFLOW_TEST_PAYMENTS)
const { default: Finance } = await import(process.env.AGENTFLOW_TEST_FINANCEPAYMENTSTATUS)
const { default: Cashier } = await import(process.env.AGENTFLOW_TEST_CASHIERPAYMENTDETAIL)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_CASHIERWORKSPACE)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const binding = () => ({ applicationId: 'app', businessId: 'report', roundNo: 1, applicationVersion: 9, businessVersion: 3 })
const payment = () => ({ ...binding(), id: 'authorization', version: 1, status: 'AUTHORIZED', purpose: 'EXPENSE_REIMBURSEMENT', legalEntityId: 'entity', employeeId: 'alice', amount: { value: '100.00', currency: 'CNY' }, maskedPayeeAccount: '****1234', authorizedBy: 'finance', authorizedAt: new Date(Date.now() - 60000).toISOString(), expiresAt: new Date(Date.now() + 60000).toISOString(), dueDate: null, executedBy: null, request: null, operation: null, retirement: null })
const finance = () => ({ ...binding(), voucherOperationId: 'voucher', voucherVersion: 3, payable: { value: '100.00', currency: 'CNY' }, payment: null, payeeReview: null, dispute: null, actions: { authorize: true, voidAuthorization: false, query: false, retire: false, reviewAccount: false, authorizeReviewed: false } })
const cashier = () => ({ payment: payment(), actions: { execute: true, query: false, resendOriginal: false }, debitAccount: null })
const accounts = () => ({ authorizationId: 'authorization', authorizationVersion: 1, validUntil: new Date(Date.now() + 60000).toISOString(), items: [{ reference: 'debit-1', displayName: '基本户', maskedAccount: '****4567', currency: 'CNY', sourceVersion: 'v1' }] })
const operation = () => ({ version: 4, status: 'UNKNOWN', updatedAt: new Date().toISOString(), observedStatus: null, paymentReference: null, receiptReference: null, completedAt: null, disputed: false, issue: 'CONNECTION' })
const disputedFinance = () => {
  const view = finance(), at = new Date().toISOString()
  view.payment = payment(); Object.assign(view.payment, { version: 2, status: 'EXECUTION_REGISTERED', executedBy: 'cashier', operation: { ...operation(), status: 'RECONCILING', disputed: true } })
  view.dispute = { candidate: { outcome: 'SUCCEEDED', revision: 3, observedAt: at, validUntil: new Date(Date.parse(at) + 300000).toISOString(), paymentReference: 'bank-original', receiptReference: 'receipt-original', completedAt: view.payment.authorizedAt, failure: null }, issue: null, canResolve: true, latest: null }
  view.actions = { ...view.actions, authorize: false, query: true }; return view
}
const cashierReceipt = input => ({ authorizationId: 'authorization', authorizationVersion: input.authorizationVersion, action: input.action, requestId: input.action === 'EXECUTE' ? 'request' : null, operationVersion: input.operationVersion ? input.operationVersion + 1 : null, auditEventId: 'audit' })
const financeReceipt = () => ({ applicationId: 'app', businessId: 'report', roundNo: 1, authorizationId: 'authorization', authorizationVersion: 1, action: 'AUTHORIZE', operationVersion: null, expiresAt: payment().expiresAt, auditEventId: 'audit' })
let scope = 0
function mount(Component, values) {
  const props = reactive({ scopeKey: 'payment-' + ++scope, locked: false, ...values }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value) })
  const mounted = app.mount({})
  return { props, events, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('已撤销报销的付款区区分无记录和原付款，保留事实读取且不提示重新授权', async () => {
  const value = finance(); value.actions.authorize = false
  api.financePayment = async () => clone(value)
  const p = mount(Finance, { ...binding(), revoked: true })
  const html = () => renderToString(createSSRApp({ ...FinanceRendered, setup: () => p.state }, { ...p.props }))
  try {
    await settle()
    assert.match(await html(), /审批已撤销/)
    assert.match(await html(), /本轮没有付款授权记录/)
    assert.doesNotMatch(await html(), /财务授权后由独立出纳办理/)
    value.payment = { ...payment(), status: 'EXECUTION_REGISTERED', version: 2, executedBy: 'cashier', operation: operation() }
    await p.state.load()
    assert.equal(p.state.view.payment.operation.status, 'UNKNOWN')
    assert.doesNotMatch(await html(), /本轮没有付款授权记录/)
    assert.equal(p.state.allowed('AUTHORIZE'), false)
  } finally { p.close() }
})

test('付款成功展示要求原绑定、精确金额和完整回单，原命令和另一轮次不能混用', () => {
  const value = payment(); assert.equal(rules.validatePayment(value), value)
  for (const change of [{ id: 'other' }, { amount: { value: 100, currency: 'CNY' } }, { amount: { value: '0.00', currency: 'CNY' } }, { maskedPayeeAccount: '1234567890123456' }, { status: 'SUCCEEDED' }, { expiresAt: 'invalid' }]) assert.throws(() => rules.validatePayment({ ...value, ...change }, 'authorization'))
  value.status = 'EXECUTION_REGISTERED'; value.version = 2; value.executedBy = 'cashier'; value.operation = operation(); value.operation.status = 'SUCCEEDED'
  assert.throws(() => rules.validatePayment(value)); Object.assign(value.operation, { observedStatus: 'SUCCEEDED', receiptReference: 'receipt', paymentReference: 'bank-payment', completedAt: value.authorizedAt })
  assert.doesNotThrow(() => rules.validatePayment(value)); value.operation.disputed = true; assert.throws(() => rules.validatePayment(value))
  const f = finance(); f.payment = payment(); f.payment.roundNo = 2; assert.throws(() => rules.validateFinancePayment(f, binding()))
  for (const key of Object.keys(binding())) assert.throws(() => rules.validateFinancePayment({ ...finance(), [key]: 'foreign' }, binding()))
})

test('财务授权只发送已展示版本和期限，不能把客户端金额与账户当作事实', () => {
  const view = finance(); view.amount = '999.99'; view.account = 'forged'
  const input = rules.financePaymentInput(view, 'AUTHORIZE', ' 核对批准凭证 ', 900, Date.now(), '2026-10-01')
  assert.deepEqual(input, { roundNo: 1, applicationVersion: 9, businessVersion: 3, voucherOperationId: 'voucher', voucherVersion: 3, validitySeconds: 900, dueDate: '2026-10-01', comment: '核对批准凭证' })
  for (const minutes of [0, 59, 86401, 1.5]) assert.throws(() => rules.financePaymentInput(view, 'AUTHORIZE', '原因', minutes, Date.now(), '2026-10-01'))
  assert.throws(() => rules.financePaymentInput(view, 'AUTHORIZE', '   ')); view.payable.value = '0.00'; assert.throws(() => rules.financePaymentInput(view, 'AUTHORIZE', '原因', 900, Date.now(), '2026-10-01'))
})

test('裁决只采用展示终态且要求新鲜证据、明确原因及对账编号', () => {
  const view = disputedFinance(); view.dispute.candidate.paidAmount = '999.00'
  const input = rules.paymentDisputeInput(view, ' BANK-001 ', ' 已核对原回单 ')
  assert.deepEqual(input, { authorizationVersion: 2, operationVersion: 4, outcome: 'SUCCEEDED', evidenceReference: 'BANK-001', comment: '已核对原回单' })
  assert.throws(() => rules.paymentDisputeInput(view, 'BANK-001', '依据', Date.parse(view.dispute.candidate.validUntil)))
  for (const reference of ['', '  ', 'x'.repeat(129), 'BANK\n001']) assert.throws(() => rules.paymentDisputeInput(view, reference, '依据'))
  for (const mutate of [v => v.dispute = null, v => v.dispute.canResolve = false, v => v.dispute.candidate.outcome = 'PENDING', v => v.dispute.issue = 'STALE_EVIDENCE', v => v.payment.operation.disputed = false, v => v.payment.operation.status = 'QUERYING', v => v.dispute.candidate.validUntil = v.dispute.candidate.observedAt]) {
    const invalid = clone(view); mutate(invalid); assert.throws(() => rules.paymentDisputeInput(invalid, 'BANK-001', '依据'))
  }
})

test('裁决回执必须属于原交易和精确下一版本', () => {
  const view = disputedFinance(), input = rules.paymentDisputeInput(view, 'BANK-001', '依据')
  const receipt = { applicationId: 'app', authorizationId: 'authorization', resolutionId: 'decision', operationVersion: 5, outcome: 'SUCCEEDED', auditEventId: 'audit' }
  assert.doesNotThrow(() => rules.validatePaymentDisputeReceipt(receipt, view, input))
  for (const change of [{ authorizationId: 'other' }, { applicationId: 'other' }, { operationVersion: 4 }, { operationVersion: 6 }, { outcome: 'FAILED' }, { resolutionId: '' }, { auditEventId: '' }]) assert.throws(() => rules.validatePaymentDisputeReceipt({ ...receipt, ...change }, view, input))
})

test('页面读取和刷新不自动裁决，填写凭据后才发送一次明确决定', async () => {
  const value = disputedFinance(), sent = []
  api.financePayment = async () => clone(value)
  api.resolvePaymentDispute = async (id, input) => { sent.push({ id, input }); return { applicationId: 'app', authorizationId: id, resolutionId: 'decision', operationVersion: input.operationVersion + 1, outcome: input.outcome, auditEventId: 'audit' } }
  const item = mount(Finance, binding())
  try {
    await settle(); await item.state.load(); assert.equal(sent.length, 0)
    item.state.prepare('RESOLVE_DISPUTE'); item.state.comment = '核对原回单'; await item.state.execute(); assert.equal(sent.length, 0)
    item.state.evidenceReference = 'BANK-001'; await item.state.execute(); assert.equal(sent.length, 1)
    assert.equal(sent[0].id, 'authorization'); assert.equal(sent[0].input.outcome, 'SUCCEEDED'); assert.equal(item.state.pending, null)
  } finally { item.close() }
})

test('切换身份会清空未提交裁决凭据并丢弃旧身份的迟到回执', async () => {
  let complete; const value = disputedFinance()
  api.financePayment = async () => clone(value)
  api.resolvePaymentDispute = () => new Promise(resolve => { complete = resolve })
  const item = mount(Finance, binding())
  try {
    await settle(); item.state.prepare('RESOLVE_DISPUTE'); item.state.evidenceReference = 'BANK-OLD'; item.state.comment = '旧身份原回单'
    const pending = item.state.execute(); item.props.scopeKey = 'another-finance'; await settle()
    assert.equal(item.state.evidenceReference, ''); assert.equal(item.state.pending, null)
    complete({ applicationId: 'app', authorizationId: 'authorization', resolutionId: 'old-decision', operationVersion: 5, outcome: 'SUCCEEDED', auditEventId: 'audit' }); await pending
    assert.equal(item.state.notice, ''); assert.equal(item.state.saving, false)
  } finally { item.close() }
})

test('账户目录有明确归属、时效和人工选择，提交时只能携带选中引用及版本', () => {
  const view = cashier(), options = accounts(); const input = rules.cashierPaymentInput(view, 'EXECUTE', ' 核对收款人 ', options, 'debit-1')
  assert.deepEqual(input, { action: 'EXECUTE', authorizationVersion: 1, debitAccountReference: 'debit-1', debitAccountVersion: 'v1', comment: '核对收款人' })
  assert.throws(() => rules.cashierPaymentInput(view, 'EXECUTE', '原因', options, ''))
  for (const mutate of [v => v.authorizationId = 'other', v => v.authorizationVersion++, v => v.items.push(clone(v.items[0])), v => v.items[0].currency = 'USD', v => v.items[0].maskedAccount = '123456789012', v => v.validUntil = '2000-01-01T00:00:00Z']) {
    const invalid = clone(options); mutate(invalid); assert.throws(() => rules.cashierPaymentInput(view, 'EXECUTE', '原因', invalid, 'debit-1'))
  }
  assert.throws(() => rules.cashierPaymentInput(view, 'EXECUTE', '原因', options, 'debit-1', Date.parse(view.payment.expiresAt)))
})

test('未知结果只能查询，重发仍绑定原版本和有效期，查询允许授权过期', () => {
  const view = cashier(); Object.assign(view.payment, { version: 2, status: 'EXECUTION_REGISTERED', executedBy: 'cashier', operation: operation() }); view.actions = { execute: false, query: true, resendOriginal: true }
  assert.throws(() => rules.cashierPaymentInput(view, 'RESEND_ORIGINAL', '原因'))
  view.payment.operation.status = 'NOT_FOUND'; const input = rules.cashierPaymentInput(view, 'RESEND_ORIGINAL', '权威查无')
  assert.deepEqual(input, { action: 'RESEND_ORIGINAL', authorizationVersion: 2, operationVersion: 4, comment: '权威查无' })
  view.payment.expiresAt = new Date(Date.parse(view.payment.authorizedAt) + 1000).toISOString()
  assert.throws(() => rules.cashierPaymentInput(view, 'RESEND_ORIGINAL', '原因'))
  assert.doesNotThrow(() => rules.cashierPaymentInput(view, 'QUERY', '过期后核对原交易'))
})

test('受理回执核对原授权和操作，不能显示别人的付款已登记', () => {
  const input = rules.cashierPaymentInput(cashier(), 'EXECUTE', '确认', accounts(), 'debit-1'), receipt = cashierReceipt(input)
  assert.doesNotThrow(() => rules.validateCashierPaymentReceipt(receipt, 'authorization', input))
  for (const change of [{ authorizationId: 'other' }, { authorizationVersion: 2 }, { action: 'QUERY' }, { requestId: null }, { operationVersion: 1 }, { auditEventId: '' }]) assert.throws(() => rules.validateCashierPaymentReceipt({ ...receipt, ...change }, 'authorization', input))
  const f = finance(), authorization = rules.financePaymentInput(f, 'AUTHORIZE', '确认', 900, Date.now(), '2026-10-01')
  assert.doesNotThrow(() => rules.validateFinancePaymentReceipt(financeReceipt(), f, authorization))
  assert.throws(() => rules.validateFinancePaymentReceipt({ ...financeReceipt(), roundNo: 2 }, f, authorization))
})

test('实际财务面板加载和取消不会写入，双击确认只有一份人工授权', async () => {
  api.financePayment = async () => finance(); const writes = []; let complete
  api.authorizePayment = (id, input) => { writes.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = mount(Finance, binding())
  try {
    await settle(); p.state.prepare('AUTHORIZE'); assert.equal(writes.length, 0); p.state.pending = null
    p.state.prepare('AUTHORIZE'); p.state.comment = '已核对'; p.state.dueDate = '2026-10-01'; const first = p.state.execute(); await p.state.execute()
    assert.equal(writes.length, 1); complete(financeReceipt()); await first; assert.match(p.state.notice, /已登记/); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('实际出纳面板只有确认时登记，账户查询不会自动选择或付款', async () => {
  api.cashierPayment = async () => cashier(); api.paymentAccounts = async () => accounts(); const writes = []; let complete
  api.cashierPaymentAction = (id, input) => { writes.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = mount(Cashier, { authorizationId: 'authorization' })
  try {
    await settle(); await p.state.prepare('EXECUTE'); assert.equal(p.state.selected, ''); assert.equal(writes.length, 0)
    p.state.comment = '核对原授权'; await p.state.execute(); assert.equal(writes.length, 0)
    p.state.selected = 'debit-1'; const first = p.state.execute(); await p.state.execute(); assert.equal(writes.length, 1)
    complete(cashierReceipt(writes[0].input)); await first; assert.match(p.state.notice, /已登记/); assert.equal(p.state.options, null)
  } finally { p.close() }
})

test('出纳切换身份或付款后清空账户，迟到目录不能恢复旧账号选择', async () => {
  api.cashierPayment = async id => { const v = cashier(); v.payment.id = id; return v }; const calls = []
  api.paymentAccounts = (id, signal) => new Promise(resolve => calls.push({ id, signal, resolve }))
  const p = mount(Cashier, { authorizationId: 'authorization' })
  try {
    await settle(); const pending = p.state.prepare('EXECUTE'); await settle(); assert.equal(calls.length, 1)
    p.props.scopeKey = 'another'; assert.equal(p.state.options, null); assert.equal(p.state.selected, ''); assert.equal(calls[0].signal.aborted, true)
    calls[0].resolve(accounts()); await pending; assert.equal(p.state.options, null); assert.equal(p.state.pending, null)
    await settle(); p.props.authorizationId = 'second'; await settle(); assert.equal(p.state.view.payment.id, 'second')
  } finally { p.close() }
})

test('旧身份的付款详情和写入回执都不能改写新身份面板', async () => {
  const calls = []; api.financePayment = (id, round, signal) => new Promise(resolve => calls.push({ signal, resolve }))
  let complete; api.authorizePayment = () => new Promise(resolve => complete = resolve)
  const p = mount(Finance, binding())
  try {
    p.props.scopeKey = 'another'; assert.equal(calls[0].signal.aborted, true); calls[0].resolve(finance()); await settle(); assert.equal(p.state.view, null)
    calls[1].resolve(finance()); await settle(); p.state.prepare('AUTHORIZE'); p.state.comment = '授权'; p.state.dueDate = '2026-10-01'; const writing = p.state.execute()
    p.props.scopeKey = 'third'; complete(financeReceipt()); await writing; assert.equal(p.state.notice, ''); assert.equal(p.state.saving, false)
    calls[2].resolve(finance()); await settle()
  } finally { p.close() }
})

test('目录分页受当前身份和响应序列约束，重复条目不能无限追加', async () => {
  api.cashierPaymentFilterOptions = async () => ({ legalEntities: [], accounts: [], nextAfterAccountKey: null })
  const calls = []; api.cashierPayments = (before, signal) => new Promise(resolve => calls.push({ before, signal, resolve }))
  const p = mount(Workspace, { refreshVersion: 1 })
  try {
    p.props.scopeKey = 'new-cashier'; calls[0].resolve({ items: [cashier()], nextBeforeId: null, totalCount: 2 }); await settle(); assert.equal(p.state.items.length, 0)
    calls[1].resolve({ items: [cashier()], nextBeforeId: 'authorization', totalCount: 2 }); await settle(); assert.equal(p.state.items.length, 1)
    const more = p.state.load(true); calls[2].resolve({ items: [cashier()], nextBeforeId: null, totalCount: 2 }); await more
    assert.match(p.state.error, /列表发生变化/); assert.equal(p.state.items.length, 0)
  } finally { p.close() }
})

test('付款请求结果未知时保留原幂等键与正文，刷新不会自动发送且恢复后必须复查', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'cashier-' + ++scope, roles: ['CASHIER'] })
  api.cashierPayment = async () => cashier(); api.paymentAccounts = async () => accounts(); const calls = []; let fail = true
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(cashierReceipt(JSON.parse(init.body))), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const p = mount(Cashier, { authorizationId: 'authorization' })
  try {
    await settle(); await p.state.prepare('EXECUTE'); p.state.selected = 'debit-1'; p.state.comment = '确认付款'; await p.state.execute()
    assert.equal(calls.length, 1); assert.equal(p.state.unconfirmed, true); await p.state.load(); await p.state.prepare('EXECUTE'); await p.state.execute(); assert.equal(calls.length, 1)
    const entry = writeRequests.pending()[0]; fail = false; await writeRequests.recover(entry.id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); await p.state.load(); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})

test('账户查询超时不保留旧选项，迟到成功也不能成为可选目录', async () => {
  api.cashierPayment = async () => cashier(); let complete, signal
  api.paymentAccounts = (id, value) => { signal = value; return new Promise(resolve => complete = resolve) }
  const p = mount(Cashier, { authorizationId: 'authorization' }); const timer = global.setTimeout, clear = global.clearTimeout; let expire
  try {
    await settle(); global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const waiting = p.state.prepare('EXECUTE'); expire(); assert.equal(signal.aborted, true); assert.equal(p.state.accountsLoading, false)
    complete(accounts()); await waiting; assert.equal(p.state.options, null); assert.match(p.state.error, /超时/)
  } finally { global.setTimeout = timer; global.clearTimeout = clear; p.close() }
})

test('安全结束要求服务端许可和原执行版本，回执允许保留银行失败版本且拒绝错绑', () => {
  const view = finance(); view.payment = payment(); Object.assign(view.payment, { version: 2, status: 'EXECUTION_REGISTERED', executedBy: 'cashier', operation: operation() })
  Object.assign(view.actions, { authorize: false, voidAuthorization: false, query: true, retire: true })
  for (const status of ['UNKNOWN', 'NOT_FOUND', 'SUCCEEDED', 'REVERSED', 'SENDING', 'QUERYING', 'RECONCILING']) {
    view.payment.operation.status = status; assert.throws(() => rules.financePaymentInput(view, 'RETIRE', '结束原付款'))
  }
  Object.assign(view.payment.operation, { status: 'FAILED', observedStatus: 'FAILED', paymentReference: 'original-bank-id' })
  const input = rules.financePaymentInput(view, 'RETIRE', ' 银行确认未付款 ')
  assert.deepEqual(input, { action: 'RETIRE', authorizationVersion: 2, operationVersion: 4, comment: '银行确认未付款' })
  const receipt = { ...financeReceipt(), action: 'RETIRE', authorizationVersion: 3, operationVersion: 4 }
  assert.doesNotThrow(() => rules.validateFinancePaymentReceipt(receipt, view, input))
  for (const change of [{ authorizationVersion: 2 }, { authorizationId: 'different' }, { operationVersion: 3 }, { operationVersion: 6 }]) assert.throws(() => rules.validateFinancePaymentReceipt({ ...receipt, ...change }, view, input))
  view.actions.retire = false; assert.throws(() => rules.financePaymentInput(view, 'RETIRE', '无权结束'))
})

test('已结束付款必须保留匹配依据，出纳无法用旧授权查询或重发', () => {
  const value = payment(); Object.assign(value, { status: 'RETIRED', version: 3, executedBy: 'cashier', operation: { ...operation(), status: 'VOIDED', observedStatus: null }, retirement: { retiredBy: 'finance', retiredAt: new Date().toISOString(), operationVersion: 4, basis: 'NEVER_DISPATCHED' } })
  assert.equal(rules.validatePayment(value), value)
  for (const mutate of [v => v.retirement = null, v => v.retirement.operationVersion = 3, v => v.retirement.basis = 'UNKNOWN', v => v.operation.status = 'NOT_FOUND', v => v.status = 'EXECUTION_REGISTERED']) {
    const invalid = clone(value); mutate(invalid); assert.throws(() => rules.validatePayment(invalid))
  }
  for (const action of ['QUERY', 'RESEND_ORIGINAL']) assert.throws(() => rules.cashierPaymentInput({ payment: value, actions: { execute: false, query: true, resendOriginal: true } }, action, '旧按钮'))
})

test('安全结束独立确认且双击只保存一次，结束后不会自动重新授权', async () => {
  const view = finance(); view.payment = payment(); Object.assign(view.payment, { version: 2, status: 'EXECUTION_REGISTERED', executedBy: 'cashier', operation: { ...operation(), status: 'QUEUED' } })
  Object.assign(view.actions, { authorize: false, voidAuthorization: false, query: false, retire: true })
  api.financePayment = async () => clone(view); let complete; const writes = [], authorizations = []
  api.financePaymentAction = (id, input) => { writes.push({ id, input }); return new Promise(resolve => complete = resolve) }
  api.authorizePayment = (...input) => authorizations.push(input)
  const p = mount(Finance, binding())
  try {
    await settle(); p.state.prepare('RETIRE'); assert.equal(writes.length, 0); p.state.comment = '从未发送，确认结束'
    const pending = p.state.execute(); await p.state.execute(); assert.equal(writes.length, 1)
    complete({ ...financeReceipt(), action: 'RETIRE', authorizationVersion: 3, operationVersion: 5 }); await pending
    assert.equal(authorizations.length, 0); assert.equal(p.state.pending, null); assert.equal(p.state.error, '')
  } finally { p.close() }
})

test('实际 API 读取禁用缓存，原授权路径正确转义，账户不能从其他路由获取', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  try {
    const signal = new AbortController().signal; await api.financePayment('app/other', 2, signal); await api.cashierPayment('id/other', signal); await api.paymentAccounts('id/other', signal)
    assert.match(calls[0].url, /app%2Fother\/payments\?roundNo=2$/); assert.match(calls[1].url, /cashier\/payments\/id%2Fother$/); assert.match(calls[2].url, /id%2Fother\/accounts$/)
    assert.ok(calls.every(call => call.init.cache === 'no-store'))
  } finally { global.fetch = originalFetch }
})

const reviewed = () => {
  const view = finance(); view.payment = { ...payment(), status: 'VOIDED', version: 2 }
  view.payeeReview = { id: 'review', version: 3, status: 'READY', requestedAt: new Date(Date.now() - 2000).toISOString(), checkedAt: new Date(Date.now() - 1000).toISOString(), validUntil: new Date(Date.now() + 60000).toISOString(), maskedAccount: '****9876', issue: null }
  Object.assign(view.actions, { reviewAccount: true, authorizeReviewed: true }); return view
}
test('按复核账户授权只发送证据标识，过期和未就绪结果无法用于新授权', () => {
  const view = reviewed(), input = rules.financePaymentInput(view, 'AUTHORIZE_REVIEWED', '财务已核对新账户', 900, Date.now(), '2026-10-02')
  assert.equal(input.payeeReviewId, 'review'); assert.equal(input.payeeReviewVersion, 3)
  for (const field of ['maskedAccount', 'amount', 'accountReference', 'accountDigest']) assert.equal(field in input, false)
  assert.equal('payeeReviewId' in rules.financePaymentInput(view, 'AUTHORIZE', '按原批准账户', 900, Date.now(), '2026-10-01'), false)
  assert.deepEqual(rules.financePaymentInput(view, 'REVIEW_ACCOUNT', '重新读取'), { authorizationVersion: 2, voucherVersion: 3, comment: '重新读取' })
  assert.throws(() => rules.financePaymentInput(view, 'AUTHORIZE_REVIEWED', '边界过期', 900, Date.parse(view.payeeReview.validUntil), '2026-10-01'))
  for (const mutate of [v => v.payeeReview = null, v => v.payeeReview.status = 'CONSUMED', v => v.payeeReview.maskedAccount = '123456789012', v => v.payeeReview.validUntil = new Date(Date.now() + 3600000).toISOString(), v => v.actions.authorizeReviewed = false]) {
    const changed = clone(view); mutate(changed); assert.throws(() => rules.financePaymentInput(changed, 'AUTHORIZE_REVIEWED', '拒绝旧证据', 900, Date.now(), '2026-10-01'))
  }
})
test('复核受理回执不能代替授权回执或绑定另一个原付款', () => {
  const view = reviewed(), receipt = { applicationId: 'app', authorizationId: 'authorization', reviewId: 'review-new', reviewVersion: 1, auditEventId: 'audit' }
  assert.doesNotThrow(() => rules.validatePayeeReviewReceipt(receipt, view))
  for (const change of [{ applicationId: 'other' }, { authorizationId: 'other' }, { reviewId: '' }, { reviewVersion: 2 }, { auditEventId: '' }]) assert.throws(() => rules.validatePayeeReviewReceipt({ ...receipt, ...change }, view))
  assert.throws(() => rules.validateFinancePaymentReceipt(receipt, view, rules.financePaymentInput(view, 'AUTHORIZE_REVIEWED', '确认', 900, Date.now(), '2026-10-01')))
})
test('真实面板复核及刷新只读取状态，按新账户授权需要独立确认且双击只发一次', async () => {
  api.financePayment = async () => reviewed(); const reviews = [], authorizations = []; let complete
  api.reviewPaymentPayee = async (id, input) => { reviews.push({ id, input }); return { applicationId: 'app', authorizationId: id, reviewId: 'review-new', reviewVersion: 1, auditEventId: 'audit' } }
  api.authorizePayment = (id, input) => { authorizations.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = mount(Finance, binding())
  try {
    await settle(); p.state.prepare('REVIEW_ACCOUNT'); assert.equal(reviews.length, 0); p.state.comment = '读取本人账户'; await p.state.execute()
    assert.equal(reviews.length, 1); assert.equal(authorizations.length, 0); await p.state.load(); assert.equal(authorizations.length, 0)
    p.state.prepare('AUTHORIZE_REVIEWED'); p.state.comment = '核对复核账户并授权'; p.state.dueDate = '2026-10-01'; const pending = p.state.execute(); await p.state.execute()
    assert.equal(authorizations.length, 1); assert.equal(authorizations[0].input.payeeReviewId, 'review')
    complete({ ...financeReceipt(), authorizationId: 'replacement' }); await pending; assert.equal(p.state.error, ''); assert.equal(p.state.pending, null)
  } finally { p.close() }
})
test('账户复核回执迟到时不能恢复已经切换身份的结果与按钮', async () => {
  api.financePayment = async () => reviewed(); let complete
  api.reviewPaymentPayee = () => new Promise(resolve => complete = resolve)
  const p = mount(Finance, binding())
  try {
    await settle(); p.state.prepare('REVIEW_ACCOUNT'); p.state.comment = '核对'; const pending = p.state.execute()
    p.props.scopeKey = 'another-finance'; complete({ applicationId: 'app', authorizationId: 'authorization', reviewId: 'old-review', reviewVersion: 1, auditEventId: 'audit' })
    await pending; assert.equal(p.state.notice, ''); assert.equal(p.state.pending, null); assert.equal(p.state.saving, false)
  } finally { p.close() }
})


test('新授权必须显式填写日期，既有日期只读且不接受不存在的日历日期', () => {
  const view = finance()
  assert.throws(() => rules.financePaymentInput(view, 'AUTHORIZE', '核对凭证'))
  for (const date of ['0001-01-01', '2020-02-29', '2026-10-01', '9999-12-31']) {
    assert.equal(rules.financePaymentInput(view, 'AUTHORIZE', '核对凭证', 900, Date.now(), date).dueDate, date)
    assert.doesNotThrow(() => rules.validatePayment({ ...payment(), dueDate: date }))
  }
  for (const date of ['', '0000-01-01', '2026-02-30', '2026-2-01', '2026-10-01T00:00:00Z']) {
    assert.throws(() => rules.financePaymentInput(view, 'AUTHORIZE', '核对凭证', 900, Date.now(), date))
    assert.throws(() => rules.validatePayment({ ...payment(), dueDate: date }))
  }
  assert.doesNotThrow(() => rules.validatePayment({ ...payment(), dueDate: null }))
  const missing = payment(); delete missing.dueDate; assert.throws(() => rules.validatePayment(missing))
})


test('财务日期表单无默认值且取消或切换身份后清空，历史日期独立展示', async () => {
  api.financePayment = async () => finance(); const sent = []
  api.authorizePayment = async (id, input) => { sent.push({ id, input }); return financeReceipt() }
  const p = mount(Finance, binding())
  try {
    await settle(); p.state.prepare('AUTHORIZE'); assert.equal(p.state.dueDate, '')
    let cancel
    const findCancel = node => node?.type === 'button' && node.children === '取消' ? node : Array.isArray(node?.children) ? node.children.map(findCancel).find(Boolean) : undefined
    const probe = { ...FinanceRendered, setup: () => p.state, render(...args) { const tree = FinanceRendered.render.apply(this, args); cancel = findCancel(tree)?.props.onClick; return tree } }
    const html = await renderToString(createSSRApp(probe, { ...binding(), scopeKey: p.props.scopeKey }))
    assert.match(html, /aria-label="付款到期日"/); assert.match(html, /type="date"/); assert.match(html, /required/)
    p.state.comment = '核对日期'; await p.state.execute(); assert.equal(sent.length, 0)
    p.state.dueDate = '2026-10-01'; assert.equal(typeof cancel, 'function'); cancel(); assert.equal(p.state.dueDate, ''); assert.equal(p.state.pending, null); p.state.prepare('AUTHORIZE')
    p.state.dueDate = '2026-10-02'; p.props.scopeKey = 'new-finance'; await settle(); assert.equal(p.state.dueDate, '')
    p.state.prepare('AUTHORIZE'); p.state.comment = '本次明确填写'; p.state.dueDate = '2026-10-03'; await p.state.execute()
    assert.equal(sent[0].input.dueDate, '2026-10-03'); assert.equal(p.state.dueDate, '')
  } finally { p.close() }
})

test('财务授权结果未知时恢复原日期和原键，后来输入不能替换已发送决定', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'finance-' + ++scope, roles: ['FINANCE'] })
  api.financePayment = async () => finance(); const calls = []; let fail = true
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(financeReceipt()), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const p = mount(Finance, binding())
  try {
    await settle(); p.state.prepare('AUTHORIZE'); p.state.comment = '本次付款期限'; p.state.dueDate = '2026-10-01'; await p.state.execute()
    assert.equal(p.state.unconfirmed, true); assert.equal(JSON.parse(calls[0].init.body).dueDate, '2026-10-01')
    p.state.dueDate = '2026-10-02'; await p.state.execute(); assert.equal(calls.length, 1)
    const entry = writeRequests.pending()[0]; fail = false; await writeRequests.recover(entry.id)
    assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); await p.state.load(); assert.equal(p.state.dueDate, ''); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})
