import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_SUPPLIER_ADJUSTMENT)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_SUPPLIERADJUSTMENTSTATUS)
const { default: FinancePanel } = await import(process.env.AGENTFLOW_TEST_SUPPLIERFINANCESTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const binding = () => ({ paymentId: 'bank-payment', requestId: 'procurement', applicationId: 'application', roundNo: 2 })
const money = value => ({ value, currency: 'CNY' }), date = offset => new Date(Date.now() + offset).toISOString()
const view = () => ({ ...binding(), legalEntityId: 'entity', supplierName: '原供应商', amount: money('65.00'), maskedPayeeAccount: '****3456', minimumAccountingDate: '2026-09-29', bank: { version: 22, status: 'SUCCEEDED', cashier: 'cashier', updatedAt: date(-20000), disputed: false }, returnVersion: 2, reviewRequired: true, totalReturned: money('20.00'), accountedReturned: money('0.00'), pendingReturned: money('20.00'), netPaid: money('45.00'), preparation: null, activeAdjustmentId: null, items: [], nextBeforeId: null, completion: null, canPrepare: true })
const operation = (status = 'UNKNOWN') => ({ id: 'original-adjustment', version: 4, status, financeActor: 'finance', accountingDate: '2026-09-29', periodReference: '2026-09', returnVersion: 2, returnedAmount: money('20.00'), totalReturned: money('20.00'), netPaid: money('45.00'), recognizesOriginalPayment: true, createdAt: date(-10000), updatedAt: date(-1000), attempts: 1, dispatches: ['QUEUED', 'CHECKING', 'VOIDED'].includes(status) ? 0 : 1, observedStatus: ['ADJUSTED', 'REJECTED', 'NOT_FOUND'].includes(status) ? status : null, disputed: false, issue: null, rejection: status === 'REJECTED' ? 'ACCOUNTING_PERIOD_CLOSED' : null, posting: status === 'ADJUSTED' ? { adjustmentReference: 'erp-adjustment', recognitionVoucherReference: 'erp-original-voucher', returnedAmount: money('20.00'), totalReturned: money('20.00'), netPaid: money('45.00'), payableSettledBefore: money('30.00'), payableSettledAfter: money('75.00'), entries: [{ transactionReference: 'bank-return', amount: money('20.00'), voucherReference: 'return-voucher', entryReference: 'return-entry' }], adjustedAt: date(-5000) } : null, retirement: null, completion: null, actions: { query: ['UNKNOWN', 'ADJUSTED', 'REJECTED', 'NOT_FOUND'].includes(status), retryOriginal: status === 'NOT_FOUND', retire: ['QUEUED', 'CHECKING', 'VOIDED', 'REJECTED'].includes(status) } })
const registered = (status = 'UNKNOWN') => ({ ...view(), preparation: { id: 'original-adjustment', version: 3, status: 'READY', financeActor: 'finance', accountingDate: '2026-09-29', updatedAt: date(-10000), issue: null }, activeAdjustmentId: 'original-adjustment', items: [operation(status)], canPrepare: false })
const receipt = (value = view(), action = 'PREPARE') => ({ ...binding(), action, preparationId: action === 'PREPARE' ? 'new-preparation' : null, preparationVersion: action === 'PREPARE' ? 1 : null, adjustmentId: action === 'PREPARE' ? null : value.items[0].id, adjustmentVersion: action === 'PREPARE' ? null : value.items[0].version + (action !== 'RETIRE' || ['QUEUED', 'CHECKING'].includes(value.items[0].status) ? 1 : 0), auditEventId: 'audit' })
let scope = 0
function mount(component = Panel) {
  const props = reactive({ ...binding(), applicationVersion: 9, requestVersion: 4, scopeKey: 'adjustment-' + ++scope, locked: false }), events = [], changed = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value), onChanged: () => changed.push(true) })
  const instance = app.mount({})
  return { props, events, changed, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('财务必须明确选择有效会计日期，大金额保持精确且请求不携带金融事实', () => {
  const value = view(); value.amount = money('999999999999999.99'); value.netPaid = money('999999999999979.99')
  assert.deepEqual(rules.supplierAdjustmentPreparationInput(value, '2026-09-30', ' 已核对原付款 '), { paymentVersion: 22, returnVersion: 2, accountingDate: '2026-09-30', comment: '已核对原付款' })
  assert.equal(value.amount.value, '999999999999999.99')
  for (const invalid of ['', '2026-09-28', '2026-09-31', '2027-02-29', '2026-09-29T00:00:00Z']) assert.throws(() => rules.supplierAdjustmentPreparationInput(value, invalid, '核对'))
  assert.doesNotThrow(() => rules.supplierAdjustmentPreparationInput(value, '2028-02-29', '核对'))
})

test('跨付款、缺失事实、原始账号和矛盾能力不允许显示调整操作', () => {
  for (const mutate of [v => v.paymentId = 'foreign', v => v.roundNo++, v => v.amount.value = 65, v => v.maskedPayeeAccount = '6222000034567890', v => v.bank = null, v => v.bank.disputed = true, v => v.bank.status = 'UNKNOWN', v => v.pendingReturned.value = '19.99', v => v.minimumAccountingDate = '2026-02-30', v => delete v.completion, v => v.canPrepare = 'true']) {
    const value = view(); mutate(value); assert.throws(() => rules.validateSupplierAdjustment(value, binding()))
  }
  const value = registered(); value.canPrepare = true; assert.throws(() => rules.validateSupplierAdjustment(value, binding()))
})

test('准备完成、ERP 调整和本地完成独立；历史完成凭据保留后续银行或调整争议', () => {
  const value = registered('ADJUSTED'); assert.equal(rules.validateSupplierAdjustment(value, binding()).completion, null)
  value.completion = { adjustmentId: value.items[0].id, adjustmentVersion: 4, returnVersion: 3, accountedEntries: 1, completedAt: date(-500) }; value.items[0].completion = clone(value.completion); value.returnVersion = 3; value.accountedReturned = money('20.00'); value.pendingReturned = money('0.00'); value.reviewRequired = false; value.activeAdjustmentId = null
  assert.equal(rules.validateSupplierAdjustment(value, binding()).bank.status, 'SUCCEEDED')
  value.bank.status = 'UNKNOWN'; value.bank.version++
  const original = value.items[0]; original.version++; original.status = 'RECONCILING'; original.disputed = true; original.issue = 'INCONSISTENT_OBSERVATION'
  assert.equal(rules.validateSupplierAdjustment(value, binding()).completion.adjustmentVersion, 4)
  original.version = 4; assert.throws(() => rules.validateSupplierAdjustment(value, binding()))
  original.version = 5; original.posting.returnedAmount.value = '19.99'; assert.throws(() => rules.validateSupplierAdjustment(value, binding()))
})

test('未知和查无不能安全结束，已有调整拒绝不能用于改换日期', () => {
  for (const status of ['UNKNOWN', 'NOT_FOUND', 'ADJUSTED']) {
    const value = registered(status); value.items[0].actions.retire = true
    assert.throws(() => rules.supplierAdjustmentActionInput(value, value.items[0].id, 'RETIRE', '结束'))
  }
  const rejected = registered('REJECTED'); rejected.items[0].rejection = 'ALREADY_ADJUSTED'
  assert.throws(() => rules.validateSupplierAdjustment(rejected, binding()))
  rejected.items[0].actions.retire = false; assert.doesNotThrow(() => rules.validateSupplierAdjustment(rejected, binding()))
  const stopped = registered('VOIDED'); stopped.items[0].retirement = { retiredBy: 'finance', retiredAt: date(0), basis: 'NEVER_DISPATCHED' }; stopped.items[0].actions = { query: false, retryOriginal: false, retire: false }; stopped.activeAdjustmentId = null
  assert.doesNotThrow(() => rules.validateSupplierAdjustment(stopped, binding()))
})

test('回执严格绑定原操作和精确版本，包括未发出调整安全结束的版本变化', () => {
  for (const status of ['QUEUED', 'CHECKING', 'VOIDED', 'REJECTED']) {
    const value = registered(status), result = receipt(value, 'RETIRE')
    assert.doesNotThrow(() => rules.validateSupplierAdjustmentReceipt(result, value, 'RETIRE', value.items[0].id))
    result.adjustmentVersion++; assert.throws(() => rules.validateSupplierAdjustmentReceipt(result, value, 'RETIRE', value.items[0].id))
  }
  for (const change of [{ paymentId: 'foreign' }, { roundNo: 3 }, { preparationVersion: 2 }, { preparationId: null }, { adjustmentId: 'new-write' }, { auditEventId: null }]) assert.throws(() => rules.validateSupplierAdjustmentReceipt({ ...receipt(), ...change }, view(), 'PREPARE'))
})

test('打开登记表单不发送请求、不自动选日期；明确确认和双击只保存一次', async () => {
  const value = view(); api.supplierAdjustments = async () => clone(value); let complete; const calls = []
  api.prepareSupplierAdjustment = (id, input) => { calls.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = mount()
  try {
    await settle(); p.state.prepare('PREPARE'); assert.equal(calls.length, 0); assert.equal(p.state.accountingDate, '')
    p.state.comment = '核对原银行回单'; await p.state.execute(); assert.equal(calls.length, 0); assert.match(p.state.error, /会计日期/)
    p.state.accountingDate = '2026-09-30'; const sending = p.state.execute(); await p.state.execute(); assert.equal(calls.length, 1); assert.equal(p.state.saving, true)
    complete(receipt()); await sending
    assert.deepEqual(calls[0], { id: 'bank-payment', input: { paymentVersion: 22, returnVersion: 2, accountingDate: '2026-09-30', comment: '核对原银行回单' } })
    assert.equal(p.state.error, ''); assert.equal(p.state.pending, null); assert.equal(p.events.includes(true), true); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('错误回执不能显示成功或继续登记，必须先刷新核对', async () => {
  api.supplierAdjustments = async () => view(); api.prepareSupplierAdjustment = async () => ({ ...receipt(), paymentId: 'foreign' })
  const p = mount()
  try {
    await settle(); p.state.prepare('PREPARE'); p.state.accountingDate = '2026-09-30'; p.state.comment = '核对'; await p.state.execute()
    assert.equal(p.state.notice, ''); assert.equal(p.state.blocked, true); assert.equal(p.state.requiresRefresh, true); assert.match(p.state.error, /回执/)
  } finally { p.close() }
})

test('原查询不转为重试，查无后也仅在人工确认时按原编号和版本重试', async () => {
  let value = registered(); const calls = []; api.supplierAdjustments = async () => clone(value)
  api.supplierAdjustmentAction = async (id, input) => { calls.push({ id, input }); return receipt(value, input.action) }
  api.prepareSupplierAdjustment = async () => { throw new Error('Unexpected replacement') }
  const p = mount()
  try {
    await settle(); p.state.prepare('RETRY', value.items[0].id); assert.equal(p.state.pending, null)
    p.state.prepare('QUERY', value.items[0].id); p.state.comment = '核对原号'; await p.state.execute(); assert.equal(calls.length, 1)
    value = registered('NOT_FOUND'); await p.state.load(); assert.equal(calls.length, 1)
    p.state.prepare('RETRY', value.items[0].id); assert.equal(calls.length, 1); p.state.comment = '确认原日期重试'; await p.state.execute()
    assert.deepEqual(calls[1], { id: 'original-adjustment', input: { action: 'RETRY', adjustmentVersion: 4, comment: '确认原日期重试' } })
  } finally { p.close() }
})

test('身份变化立即清空日期、历史与说明，迟到读取和保存都不能恢复旧事实', async () => {
  const reads = []; api.supplierAdjustments = () => new Promise(resolve => reads.push(resolve)); const p = mount()
  try {
    p.props.scopeKey = 'next-finance'; reads[0](view()); await settle(); assert.equal(p.state.view, null)
    reads[1](view()); await settle(); let complete
    api.prepareSupplierAdjustment = () => new Promise(resolve => complete = resolve)
    p.state.prepare('PREPARE'); p.state.accountingDate = '2026-09-30'; p.state.comment = '旧身份决定'; const sending = p.state.execute(); p.props.scopeKey = 'third-finance'
    assert.equal(p.state.accountingDate, ''); assert.equal(p.state.comment, ''); assert.equal(p.state.pending, null); assert.equal(p.state.view, null)
    complete(receipt()); await sending; assert.equal(p.state.notice, ''); reads[2](view()); await settle()
  } finally { p.close() }
})

test('读取超时取消原请求，迟到状态不会恢复调整按钮', async () => {
  api.supplierAdjustments = async () => view(); const p = mount(); const originalTimer = global.setTimeout, originalClear = global.clearTimeout; let complete, expire, signal
  try {
    await settle(); api.supplierAdjustments = (_id, _cursor, value) => { signal = value; return new Promise(resolve => complete = resolve) }
    global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const pending = p.state.load(); expire(); assert.equal(signal.aborted, true); assert.equal(p.state.view, null)
    complete(view()); await pending; assert.equal(p.state.view, null); assert.match(p.state.error, /超时/)
  } finally { global.setTimeout = originalTimer; global.clearTimeout = originalClear; p.close() }
})

test('历史整页替换，当前调整在页外不丢失绑定；重复或未前进游标拒绝展示', async () => {
  const current = registered(); current.nextBeforeId = current.items[0].id
  const old = registered('VOIDED'); old.items[0].id = 'old'; old.items[0].actions = { query: false, retryOriginal: false, retire: false }; old.items[0].retirement = { retiredBy: 'finance', retiredAt: date(0), basis: 'NEVER_DISPATCHED' }
  const calls = []; api.supplierAdjustments = async (_id, cursor) => { calls.push(cursor); return clone(cursor ? old : current) }
  const p = mount()
  try {
    await settle(); await p.state.load(current.nextBeforeId); assert.equal(p.state.view.items.length, 1); assert.equal(p.state.view.items[0].id, 'old'); assert.equal(p.state.active, undefined)
    assert.equal(p.state.view.activeAdjustmentId, 'original-adjustment'); await p.state.load(); assert.equal(p.state.beforeId, undefined)
    api.supplierAdjustments = async () => clone(current); await p.state.load(current.nextBeforeId); assert.equal(p.state.view, null); assert.match(p.state.error, /游标/)
    const duplicate = registered(); duplicate.items.push(clone(duplicate.items[0])); assert.throws(() => rules.validateSupplierAdjustment(duplicate, binding()))
    assert.deepEqual(calls, [undefined, 'original-adjustment', undefined])
  } finally { p.close() }
})

test('未知写入恢复保留原幂等键和正文，刷新不重发；恢复后必须重新核对', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'adjustment-finance-' + ++scope, roles: ['FINANCE'] })
  const value = registered(); api.supplierAdjustments = async () => clone(value); let fail = true; const calls = []
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(receipt(value, 'QUERY')), { status: 202 }) }
  const p = mount()
  try {
    await settle(); p.state.prepare('QUERY', value.items[0].id); p.state.comment = '核对原调整'; await p.state.execute()
    assert.equal(p.state.unconfirmed, true); await p.state.load(); p.state.prepare('QUERY', value.items[0].id); await p.state.execute(); assert.equal(calls.length, 1)
    fail = false; await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); await p.state.load(); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})

test('父面板聚合调整忙碌状态，保存期间不能刷新卸载子面板或被空闲子事件解锁', async () => {
  let reads = 0; api.supplierFinance = async () => { reads++; return { requestId: 'procurement', applicationId: 'application', roundNo: 2, applicationVersion: 9, requestVersion: 4, approvedAmount: money('65.00'), review: null, authorization: null, hold: null, actions: { review: true, authorize: false, query: false, retry: false, retire: false } } }
  const p = mount(FinancePanel)
  try {
    await settle(); p.state.adjustmentActivity(true); assert.equal(p.state.blocked, true); assert.equal(p.events.at(-1), true)
    await p.state.load(); assert.equal(reads, 1)
    p.state.saving = true; p.state.adjustmentActivity(false); assert.equal(p.events.at(-1), true)
    p.state.saving = false; p.state.adjustmentActivity(false); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('真实 API 禁用读取缓存、有界分页、路径编码并使用既有幂等写入', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  bindAuthenticationActor({ tenantId: 'demo', userId: 'adjustment-paths-' + ++scope, roles: ['FINANCE'] })
  try {
    await api.supplierAdjustments('bank/other', 'history/other', new AbortController().signal)
    await api.prepareSupplierAdjustment('bank/other', { paymentVersion: 22, returnVersion: 2, accountingDate: '2026-09-30', comment: '登记' })
    await api.supplierAdjustmentAction('adjustment/other', { action: 'QUERY', adjustmentVersion: 4, comment: '核对原号' })
    assert.equal(calls[0].url, '/api/v1/supplier-payments/bank%2Fother/adjustments?beforeId=history%2Fother&limit=25'); assert.equal(calls[0].init.cache, 'no-store')
    assert.equal(calls[1].url, '/api/v1/supplier-payments/bank%2Fother/adjustment-preparations'); assert.ok(calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(calls[2].url, '/api/v1/supplier-adjustments/adjustment%2Fother/finance-actions'); assert.ok(calls[2].init.headers.get('Idempotency-Key'))
  } finally { global.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('追加回款保留前次完成凭据，当前待入账金额只包含新增资金', () => {
  const value = registered('ADJUSTED'), completion = { adjustmentId: value.items[0].id, adjustmentVersion: 4, returnVersion: 3, accountedEntries: 1, completedAt: date(-500) }
  value.completion = completion; value.items[0].completion = clone(completion); value.activeAdjustmentId = null; value.returnVersion = 4; value.totalReturned = money('30.00'); value.accountedReturned = money('20.00'); value.pendingReturned = money('10.00'); value.netPaid = money('35.00'); value.canPrepare = true
  assert.equal(rules.validateSupplierAdjustment(value, binding()).items[0].totalReturned.value, '20.00')
  value.completion = Object.fromEntries(Object.entries(completion).reverse()); assert.doesNotThrow(() => rules.validateSupplierAdjustment(value, binding()))
  value.accountedReturned.value = '30.00'; assert.throws(() => rules.validateSupplierAdjustment(value, binding()))
})

test('逐笔回款分录不能重复、冒用原付款凭证或篡改原应付余额', () => {
  for (const mutate of [v => v.items[0].posting.entries[0].amount.value = '19.99', v => v.items[0].posting.entries.push(clone(v.items[0].posting.entries[0])), v => v.items[0].posting.entries[0].voucherReference = 'erp-original-voucher', v => v.items[0].posting.payableSettledAfter.value = '74.99', v => v.items[0].posting.entries = []]) {
    const value = registered('ADJUSTED'); mutate(value); assert.throws(() => rules.validateSupplierAdjustment(value, binding()))
  }
})

test('本地完成触发关联回款和核销页面重读，重复刷新不反复发出变更', async () => {
  let current = registered('ADJUSTED'); api.supplierAdjustments = async () => clone(current); const p = mount()
  try {
    await settle(); current.returnVersion = 3; current.accountedReturned = money('20.00'); current.pendingReturned = money('0.00'); current.reviewRequired = false; current.activeAdjustmentId = null
    current.completion = { adjustmentId: current.items[0].id, adjustmentVersion: 4, returnVersion: 3, accountedEntries: 1, completedAt: date(-500) }; current.items[0].completion = clone(current.completion)
    await p.state.load(); assert.equal(p.changed.length, 1); await p.state.load(); assert.equal(p.changed.length, 1); assert.equal(p.state.view.completion.adjustmentVersion, 4); assert.equal(p.state.view.canPrepare, false)
    assert.equal(p.state.view.activeAdjustmentId, null); assert.equal(rules.supplierAdjustmentAllowed(p.state.view, current.items[0].id, 'QUERY'), true)
  } finally { p.close() }
})
