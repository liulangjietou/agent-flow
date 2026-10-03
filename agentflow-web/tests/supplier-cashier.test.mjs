import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_SUPPLIER_CASHIER)
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_SUPPLIERCASHIERDETAIL)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_SUPPLIERCASHIERWORKSPACE)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve)), date = offset => new Date(Date.now() + offset).toISOString()
const view = (id = 'authorization') => ({ authorizationId: id, requestId: 'procurement', applicationId: 'app', roundNo: 2, legalEntityId: 'entity', employeeId: 'alice', supplierName: '原供应商', amount: { value: '70.00', currency: 'CNY' }, maskedPayeeAccount: '****3456', authorizedBy: 'finance', authorizedAt: date(-60000), expiresAt: date(60000), retiredAt: null, hold: { version: 3, status: 'HELD', updatedAt: date(-50000) }, preparation: null, operation: null, actions: { execute: true, query: false, resendOriginal: false } })
const accounts = (id = 'authorization') => ({ authorizationId: id, holdVersion: 3, validUntil: date(60000), items: [{ reference: 'debit-1', displayName: '基本户', maskedAccount: '****5678', currency: 'CNY', sourceVersion: 'v1' }] })
const registered = (status = 'UNKNOWN') => ({ ...view(), preparation: { id: 'preparation', version: 3, status: 'READY', cashier: 'cashier', updatedAt: date(-10000), issue: null }, operation: { version: 4, status, cashier: 'cashier', updatedAt: date(-1000), observedStatus: status === 'NOT_FOUND' ? 'NOT_FOUND' : null, paymentReference: null, receiptReference: null, completedAt: null, disputed: false, issue: status === 'UNKNOWN' ? 'TIMEOUT' : null }, actions: { execute: false, query: true, resendOriginal: status === 'NOT_FOUND' } })
const receipt = (input, id = 'authorization') => ({ authorizationId: id, action: input.action, preparationId: input.action === 'EXECUTE' ? 'preparation' : null, preparationVersion: input.action === 'EXECUTE' ? 1 : null, operationVersion: input.action === 'EXECUTE' ? null : input.operationVersion + 1, auditEventId: 'audit' })
let scope = 0
function mount(Component = Detail, values = {}) {
  const props = reactive({ scopeKey: 'cashier-' + ++scope, authorizationId: 'authorization', refreshVersion: 1, locked: false, ...values }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value) })
  const mounted = app.mount({})
  return { props, events, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('供应商付款保留精确金额，确认只提交原预留版本与明确账户选择', () => {
  const value = view(); value.amount.value = '999999999999999.99'
  assert.equal(rules.validateSupplierCashier(value), value)
  assert.deepEqual(rules.supplierCashierInput(value, 'EXECUTE', ' 已核对 ', accounts(), 'debit-1'), { action: 'EXECUTE', holdVersion: 3, debitAccountReference: 'debit-1', debitAccountVersion: 'v1', comment: '已核对' })
  assert.equal(value.amount.value, '999999999999999.99')
  assert.throws(() => rules.supplierCashierInput(value, 'EXECUTE', '核对', accounts(), ''))
  assert.throws(() => rules.supplierCashierInput(value, 'EXECUTE', '核对', accounts(), 'other'))
})

test('原授权、目录预留版本、错币种、完整账号和重复引用均拒绝', () => {
  for (const mutate of [v => v.authorizationId = 'foreign', v => v.holdVersion = 2, v => v.items[0].currency = 'USD', v => v.items[0].maskedAccount = '6222000012345678', v => v.items.push(clone(v.items[0])), v => v.validUntil = date(-1), v => v.validUntil = date(600000)]) {
    const value = accounts(); mutate(value); assert.throws(() => rules.validateSupplierCashierAccounts(value, view()))
  }
  for (const mutate of [v => v.authorizationId = 'other', v => v.maskedPayeeAccount = '6222000012345678', v => v.amount.value = 70, v => v.hold.status = 'PAID', v => v.actions.execute = 'true', v => v.operation = undefined]) {
    const value = view(); mutate(value); assert.throws(() => rules.validateSupplierCashier(value, 'authorization'))
  }
})

test('只有原银行完整回单才显示到账，准备完成、争议及终态错配均不能冒充', () => {
  const value = registered('SUCCEEDED'); assert.throws(() => rules.validateSupplierCashier(value))
  Object.assign(value.operation, { observedStatus: 'SUCCEEDED', paymentReference: 'bank-1', receiptReference: 'receipt-1', completedAt: date(-5000) })
  assert.equal(rules.validateSupplierCashier(value).operation.status, 'SUCCEEDED')
  for (const mutate of [v => v.operation.disputed = true, v => v.operation.cashier = 'another', v => v.preparation.status = 'RUNNING', v => v.operation.receiptReference = null, v => v.actions.execute = true]) {
    const invalid = clone(value); mutate(invalid); assert.throws(() => rules.validateSupplierCashier(invalid))
  }
  const missing = registered('NOT_FOUND'); assert.deepEqual(rules.supplierCashierInput(missing, 'RESEND_ORIGINAL', '原号重试', null, ''), { action: 'RESEND_ORIGINAL', operationVersion: 4, comment: '原号重试' })
})

test('确认时重查授权与账户截止点，过期后仍可查询原交易', () => {
  const value = view(), option = accounts(), deadline = Date.parse(option.validUntil)
  value.expiresAt = option.validUntil
  assert.throws(() => rules.supplierCashierInput(value, 'EXECUTE', '核对', option, 'debit-1', deadline))
  assert.equal(rules.supplierCashierAllowed(value, 'EXECUTE', deadline - 1), true)
  const unknown = registered(); unknown.expiresAt = date(-1)
  assert.equal(rules.supplierCashierInput(unknown, 'QUERY', '查询', null, '').operationVersion, 4)
})

test('回执绑定原授权和精确下一版本，不能以其他交易回执解除待核对状态', () => {
  const input = rules.supplierCashierInput(view(), 'EXECUTE', '核对', accounts(), 'debit-1')
  assert.doesNotThrow(() => rules.validateSupplierCashierReceipt(receipt(input), 'authorization', input))
  for (const change of [{ authorizationId: 'foreign' }, { action: 'QUERY' }, { preparationVersion: 2 }, { preparationId: null }, { operationVersion: 1 }, { auditEventId: null }]) assert.throws(() => rules.validateSupplierCashierReceipt({ ...receipt(input), ...change }, 'authorization', input))
  const query = { action: 'QUERY', operationVersion: 4, comment: '查询' }; assert.throws(() => rules.validateSupplierCashierReceipt({ ...receipt(query), operationVersion: 4 }, 'authorization', query))
})

test('读取账户不会默认选中或发送，确认双击只登记一次', async () => {
  api.supplierCashierPayment = async () => view(); api.supplierCashierAccounts = async () => accounts(); let finish; const calls = []
  api.supplierCashierAction = (id, input) => { calls.push({ id, input }); return new Promise(resolve => finish = resolve) }
  const p = mount()
  try {
    await settle(); await p.state.prepare('EXECUTE'); assert.equal(p.state.selected, ''); assert.equal(calls.length, 0)
    p.state.selected = 'debit-1'; p.state.comment = '核对原应付'; const saving = p.state.execute(); await p.state.execute()
    assert.equal(calls.length, 1); assert.equal(p.state.saving, true); assert.equal(p.events.at(-1), true)
    finish(receipt(calls[0].input)); await saving; assert.equal(p.state.error, ''); assert.equal(p.state.options, null); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('错误回执必须刷新核对后才能再次办理', async () => {
  api.supplierCashierPayment = async () => registered(); let writes = 0
  api.supplierCashierAction = async (_, input) => { writes++; return receipt(input, 'foreign') }
  const p = mount()
  try {
    await settle(); await p.state.prepare('QUERY'); p.state.comment = '查询'; await p.state.execute()
    assert.equal(p.state.requiresRefresh, true); await p.state.execute(); assert.equal(writes, 1)
  } finally { p.close() }
})

test('切换原付款和账号后清空账户选项，迟到目录不能恢复旧选择', async () => {
  api.supplierCashierPayment = async id => view(id); let finish
  api.supplierCashierAccounts = () => new Promise(resolve => finish = resolve)
  const p = mount()
  try {
    await settle(); const loading = p.state.prepare('EXECUTE'); p.state.comment = '旧说明'; p.props.authorizationId = 'second'; p.props.scopeKey = 'other-cashier'; await settle()
    finish(accounts()); await loading; assert.equal(p.state.view.authorizationId, 'second'); assert.equal(p.state.options, null); assert.equal(p.state.selected, ''); assert.equal(p.state.comment, ''); assert.equal(p.state.pending, null)
  } finally { p.close() }
})

test('账户读取超过十二秒后解除读取状态并丢弃迟到结果', async () => {
  api.supplierCashierPayment = async () => view(); let finish
  api.supplierCashierAccounts = () => new Promise(resolve => finish = resolve)
  const p = mount(), originalTimeout = global.setTimeout, originalClear = global.clearTimeout; let timeout
  try {
    await settle(); global.setTimeout = (callback, delay) => { assert.equal(delay, 12000); timeout = callback; return 999 }; global.clearTimeout = () => {}
    const loading = p.state.prepare('EXECUTE'); timeout(); assert.equal(p.state.accountsLoading, false)
    finish(accounts()); await loading; assert.equal(p.state.options, null); assert.match(p.state.error, /超时/)
  } finally { global.setTimeout = originalTimeout; global.clearTimeout = originalClear; p.close() }
})

test('父目录刷新不能在办理中清除忙碌状态，账号切换仍清除原目录', async () => {
  let reads = 0; api.supplierCashierPayments = async () => { reads++; return { items: [view()], nextBeforeId: null } }
  const p = mount(Workspace)
  try {
    await settle(); p.state.selected = 'authorization'; p.state.saving = true; p.props.refreshVersion++; await settle()
    assert.equal(reads, 1); assert.equal(p.state.selected, 'authorization'); assert.equal(p.state.saving, true)
    let finish; api.supplierCashierPayments = () => new Promise(resolve => finish = resolve); p.props.scopeKey = 'another'; await settle(); assert.deepEqual(p.state.items, []); assert.equal(p.state.selected, '')
    finish({ items: [], nextBeforeId: null }); await settle()
  } finally { p.close() }
})

test('目录拒绝重复授权和越界游标', async () => {
  api.supplierCashierPayments = async () => ({ items: [view(), view()], nextBeforeId: null })
  const p = mount(Workspace)
  try {
    await settle(); assert.equal(p.state.items.length, 0); assert.match(p.state.error, /列表发生变化/)
    api.supplierCashierPayments = async () => ({ items: [view()], nextBeforeId: 'foreign' }); await p.state.load(); assert.equal(p.state.items.length, 0); assert.match(p.state.error, /分页结果/)
  }
  finally { p.close() }
})

test('结果未知保留原键与正文，刷新不会重发，恢复后必须核对当前状态', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'supplier-cashier-' + ++scope, roles: ['CASHIER'] })
  api.supplierCashierPayment = async () => view(); api.supplierCashierAccounts = async () => accounts(); const calls = []; let fail = true
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(receipt(JSON.parse(init.body))), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const p = mount()
  try {
    await settle(); await p.state.prepare('EXECUTE'); p.state.selected = 'debit-1'; p.state.comment = '核对原预留'; await p.state.execute()
    assert.equal(calls.length, 1); assert.equal(p.state.unconfirmed, true); await p.state.load(); await p.state.prepare('EXECUTE'); await p.state.execute(); assert.equal(calls.length, 1)
    const entry = writeRequests.pending()[0]; assert.match(entry.path, /cashier\/supplier-payments\/authorization\/actions$/); fail = false; await writeRequests.recover(entry.id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); await p.state.load(); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})

test('四个出纳 API 使用供应商路径，读取禁止缓存并转义原授权号', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  try {
    const signal = new AbortController().signal; await api.supplierCashierPayments(undefined, signal); await api.supplierCashierPayment('id/other', signal); await api.supplierCashierAccounts('id/other', signal)
    assert.match(calls[0].url, /cashier\/supplier-payments\?limit=25$/); assert.match(calls[1].url, /supplier-payments\/id%2Fother$/); assert.match(calls[2].url, /id%2Fother\/accounts$/)
    assert.ok(calls.every(call => call.init.cache === 'no-store'))
  } finally { global.fetch = originalFetch }
})
