import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { createRenderer, reactive, nextTick, h } from 'vue'
const base = pathToFileURL(process.env.AGENTFLOW_TEST_API), rules = await import(new URL('./cashierFilters.js', base))
const { api, bindAuthenticationActor } = await import(base)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_CASHIERWORKSPACERENDERED)
const originals = { ...api }, originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage
const mounted = new Set(), pending = new Set()
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`, LEGAL_A = id(1), LEGAL_B = id(2), KEY_A = '1'.repeat(64), KEY_B = '2'.repeat(64)
const clone = value => JSON.parse(JSON.stringify(value))
const option = (key = KEY_A, legalEntityId = LEGAL_A) => ({ key, legalEntityId, currency: 'CNY', displayName: key === KEY_A ? '基本户' : '结算户', maskedAccount: '****5678' })
const options = () => ({ legalEntities: [{ id: LEGAL_A, name: '甲公司' }, { id: LEGAL_B, name: '乙公司' }], accounts: [option(), option(KEY_B, LEGAL_B)], nextAfterAccountKey: null })
function row(n = 10, legal = LEGAL_A, account = null) {
  const at = new Date(Date.now() - 60_000).toISOString()
  return { payment: { id: id(n), version: account ? 2 : 1, status: account ? 'EXECUTION_REGISTERED' : 'AUTHORIZED', purpose: 'EXPENSE_REIMBURSEMENT', applicationId: id(n + 100), businessId: id(n + 200), roundNo: 1, applicationVersion: 9, businessVersion: 3, legalEntityId: legal, employeeId: 'alice', amount: { value: '100.00', currency: 'CNY' }, maskedPayeeAccount: '****1234', authorizedBy: 'finance', authorizedAt: at, expiresAt: new Date(Date.now() + 3600_000).toISOString(), dueDate: null, executedBy: account ? 'cashier' : null, request: null, operation: account ? { version: 1, status: 'QUEUED', updatedAt: at, observedStatus: null, paymentReference: null, receiptReference: null, completedAt: null, disputed: false, issue: null } : null, retirement: null }, actions: { execute: !account, query: false, resendOriginal: false }, debitAccount: account }
}
const page = (items = [row()], nextBeforeId = null, totalCount = items.length) => ({ items, nextBeforeId, totalCount })
function deferred() {
  let complete; const result = { promise: new Promise(resolve => { complete = resolve }), resolve(value) { pending.delete(result); complete(value) } }; pending.add(result); return result
}
function remove(node) { if (node.parent) { const i = node.parent.children.indexOf(node); if (i >= 0) node.parent.children.splice(i, 1); node.parent = null } }
const renderer = createRenderer({
  createElement: tag => ({ tag, props: {}, children: [], text: '', style: {} }), createText: text => ({ text, children: [] }), createComment: () => ({ comment: true, children: [] }),
  insert(node, parent, anchor) { remove(node); const index = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(index < 0 ? parent.children.length : index, 0, node); node.parent = parent },
  remove, parentNode: node => node.parent ?? null, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text; node.children = [] }, patchProp(node, key, previous, value) { node.props[key] = value }
})
const descendants = node => [node, ...(node.children ?? []).flatMap(descendants)]
const text = node => node.comment ? '' : (node.text ?? '') + (node.children ?? []).map(text).join('')
const button = (p, label) => descendants(p.root).find(node => node.tag === 'button' && text(node).trim() === label)
const select = (p, label) => descendants(p.root).find(node => node.tag === 'select' && node.props['aria-label'] === label)
async function settle() { for (let i = 0; i < 20; i++) await Promise.resolve(); await nextTick(); await new Promise(resolve => setImmediate(resolve)) }
function mount(values = {}) {
  const props = reactive({ scopeKey: 'cashier-a', refreshVersion: 1, locked: false, ...values }), root = { children: [] }
  const app = renderer.createApp({ setup: () => () => h(Rendered, props) }), instance = app.mount(root)
  const result = { props, root, state: instance.$.subTree.component.setupState, close() { app.unmount(); mounted.delete(result) } }; mounted.add(result); return result
}
function backend() {
  const state = { rows: [row(), row(11, LEGAL_A, option()), row(12, LEGAL_B, option(KEY_B, LEGAL_B))], reads: [], optionReads: [] }
  api.cashierPayments = async (before, signal, filter) => { state.reads.push({ before, signal, filter: clone(filter) }); return page(clone(state.rows.filter(item => (!filter.legalEntityId || item.payment.legalEntityId === filter.legalEntityId) && (!filter.debitAccount || (filter.debitAccount === 'UNASSIGNED' ? item.debitAccount === null : item.debitAccount?.key === filter.debitAccount))))) }
  api.cashierPaymentFilterOptions = async (legal, after, signal) => { state.optionReads.push({ legal, after, signal }); const value = options(); value.accounts = value.accounts.filter(item => !legal || item.legalEntityId === legal); return value }
  return state
}
afterEach(async () => { for (const p of [...mounted]) p.close(); for (const task of [...pending]) task.resolve(undefined); await settle(); Object.assign(api, originals); bindAuthenticationActor(null); globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage })

test('筛选和选项严格检查标识、掩码、顺序与当前法人范围', () => {
  assert.deepEqual(rules.cashierFilter('', ''), {}); assert.deepEqual(rules.cashierFilter(LEGAL_A, KEY_A), { legalEntityId: LEGAL_A, debitAccount: KEY_A })
  for (const [legal, account] of [['1-1-1-1-1', ''], ['', 'secret-reference'], [LEGAL_A, 'A'.repeat(64)]]) assert.throws(() => rules.cashierFilter(legal, account))
  assert.doesNotThrow(() => rules.validateCashierFilterOptions(options()))
  for (const change of [v => v.accounts[0].maskedAccount = '1234567890123456', v => v.accounts[0].reference = 'secret', v => v.accounts.reverse(), v => v.accounts.push(v.accounts[0]), v => v.legalEntities.pop(), v => v.nextAfterAccountKey = 'f'.repeat(64)]) { const v = options(); change(v); assert.throws(() => rules.validateCashierFilterOptions(v)) }
  assert.throws(() => rules.validateCashierFilterOptions(options(), LEGAL_A))
})

test('分页总数和实际账户必须与筛选一致，缺失事实不能当作空目录', () => {
  const assigned = row(11, LEGAL_A, option()), value = page([assigned])
  assert.doesNotThrow(() => rules.validateCashierPaymentPage(value, { legalEntityId: LEGAL_A, debitAccount: KEY_A }))
  for (const change of [v => delete v.totalCount, v => v.totalCount = -1, v => v.totalCount = 9007199254740992, v => v.totalCount = 0, v => delete v.items[0].debitAccount, v => v.items[0].debitAccount = null, v => v.items[0].debitAccount.currency = 'USD']) { const v = clone(value); change(v); assert.throws(() => rules.validateCashierPaymentPage(v, {})) }
  assert.throws(() => rules.validateCashierPaymentPage(value, { legalEntityId: LEGAL_B })); assert.throws(() => rules.validateCashierPaymentPage(value, { debitAccount: 'UNASSIGNED' }))
  assert.throws(() => rules.validateCashierPaymentPage(page([row()]), { debitAccount: KEY_A }))
})

test('真实模板选择法人和账户重新读取第一页并显示同条件总数', async () => {
  const state = backend(), p = mount(); await settle(); assert.match(text(p.root), /共 3 笔/)
  select(p, '法人筛选').props.onChange({ target: { value: LEGAL_A } }); await settle()
  assert.deepEqual(state.reads.at(-1).filter, { legalEntityId: LEGAL_A }); assert.equal(state.reads.at(-1).before, undefined); assert.match(text(p.root), /共 2 笔/)
  select(p, '出款账户筛选').props.onChange({ target: { value: KEY_A } }); await settle()
  assert.deepEqual(state.reads.at(-1).filter, { legalEntityId: LEGAL_A, debitAccount: KEY_A }); assert.equal(p.state.items.length, 1); assert.match(text(p.root), /基本户/)
  select(p, '出款账户筛选').props.onChange({ target: { value: 'UNASSIGNED' } }); await settle(); assert.equal(p.state.items[0].debitAccount, null)
  button(p, '清除筛选').props.onClick(); await settle(); assert.deepEqual(state.reads.at(-1).filter, {}); assert.match(text(p.root), /共 3 笔/)
})

test('切换法人后旧响应即使迟到也不能覆盖新范围', async () => {
  backend(); const p = mount(); await settle(); const calls = []
  api.cashierPayments = (before, signal, filter) => { const task = deferred(); calls.push({ ...task, signal, filter }); return task.promise }
  p.state.changeLegalEntity(LEGAL_A); await settle(); p.state.changeLegalEntity(LEGAL_B); await settle()
  assert.equal(calls[0].signal.aborted, true); calls[1].resolve(page([row(12, LEGAL_B, option(KEY_B, LEGAL_B))])); await settle()
  calls[0].resolve(page([row()])); await settle(); assert.equal(p.state.items[0].payment.legalEntityId, LEGAL_B); assert.equal(p.state.totalCount, 1)
})

test('身份变化同时清空旧筛选、账户选项和目录，迟到正文被忽略', async () => {
  backend(); const reads = [], choices = []
  api.cashierPayments = () => { const task = deferred(); reads.push(task); return task.promise }
  api.cashierPaymentFilterOptions = () => { const task = deferred(); choices.push(task); return task.promise }
  const p = mount(); p.props.scopeKey = 'cashier-b'; await settle()
  reads[0].resolve(page()); choices[0].resolve(options()); await settle(); assert.equal(p.state.items.length, 0); assert.equal(p.state.accountOptions.length, 0); assert.equal(p.state.totalCount, null)
  reads[1].resolve(page([])); choices[1].resolve({ legalEntities: [], accounts: [], nextAfterAccountKey: null }); await settle(); assert.equal(p.state.totalCount, 0); assert.match(text(p.root), /没有付款授权/)
})

test('账户选项继续分页，重复游标不能造成无限加载', async () => {
  backend(); const calls = []; api.cashierPaymentFilterOptions = async (legal, after) => { calls.push({ legal, after }); return { ...options(), accounts: after ? [option(KEY_B, LEGAL_B)] : [option()], nextAfterAccountKey: after ? KEY_B : KEY_A } }
  const p = mount(); await settle(); button(p, '加载更多账户').props.onClick(); await settle(); assert.equal(p.state.accountOptions.length, 2); assert.equal(calls[1].after, KEY_A)
  button(p, '加载更多账户').props.onClick(); await settle(); assert.match(p.state.optionsError, /选项未通过校验/); assert.equal(p.state.nextAccountKey, null)
})

test('翻页失败清空不再可信的旧结果和总数，重新筛选不复用旧游标', async () => {
  backend(); const calls = []; api.cashierPayments = async (before, signal, filter) => { calls.push({ before, filter }); return before ? page([row()], null, 2) : page([row()], id(10), 2) }
  const p = mount(); await settle(); await p.state.load(true); assert.match(p.state.error, /列表发生变化/); assert.equal(p.state.items.length, 0); assert.equal(p.state.totalCount, null); assert.equal(p.state.nextBeforeId, null)
  p.state.changeAccount('UNASSIGNED'); await settle(); assert.equal(calls.at(-1).before, undefined); assert.deepEqual(calls.at(-1).filter, { debitAccount: 'UNASSIGNED' })
})

test('执行中的详情和外部锁都阻止改筛选，模板控件同步禁用', async () => {
  const state = backend(), p = mount(); await settle(); const count = state.reads.length
  p.state.saving = true; await settle(); assert.equal(select(p, '法人筛选').props.disabled, true); assert.equal(select(p, '出款账户筛选').props.disabled, true)
  p.state.changeAccount('UNASSIGNED'); p.state.changeLegalEntity(LEGAL_A); p.state.clearFilters(); assert.equal(state.reads.length, count)
  p.state.saving = false; p.props.locked = true; await settle(); p.state.changeAccount('UNASSIGNED'); assert.equal(state.reads.length, count); assert.equal(select(p, '出款账户筛选').props.disabled, true)
})

test('目录和选项各自有总超时，超时后的旧正文不能恢复旧数据', async () => {
  backend(); const list = deferred(), choices = deferred(), timers = [], originalSet = globalThis.setTimeout, originalClear = globalThis.clearTimeout
  api.cashierPayments = () => list.promise; api.cashierPaymentFilterOptions = () => choices.promise
  globalThis.setTimeout = (callback, delay) => { assert.equal(delay, 12_000); timers.push(callback); return timers.length }
  globalThis.clearTimeout = () => {}
  try {
    const p = mount(); assert.equal(timers.length, 2); timers.forEach(callback => callback()); await settle()
    assert.match(p.state.error, /超时/); assert.match(p.state.optionsError, /超时/); assert.equal(p.state.loading, false); assert.equal(p.state.optionsLoading, false)
    list.resolve(page()); choices.resolve(options()); await settle(); assert.equal(p.state.items.length, 0); assert.equal(p.state.accountOptions.length, 0); assert.equal(p.state.totalCount, null)
  } finally { globalThis.setTimeout = originalSet; globalThis.clearTimeout = originalClear }
})

test('真实 API 仅序列化允许的筛选参数，保持无缓存和取消信号', async () => {
  globalThis.localStorage = { getItem: () => 'synthetic', setItem() {}, removeItem() {} }; bindAuthenticationActor({ tenantId: 'demo', userId: 'cashier', roles: ['CASHIER'] })
  const calls = []; globalThis.fetch = async (url, init) => { calls.push({ url: String(url), init }); return new Response(JSON.stringify(String(url).includes('filter-options') ? options() : page()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  const signal = new AbortController().signal
  await api.cashierPayments(id(10), signal, { legalEntityId: LEGAL_A, debitAccount: KEY_A, tenantId: 'foreign' })
  await api.cashierPaymentFilterOptions(LEGAL_A, KEY_A, signal)
  for (const call of calls) { assert.equal(call.init.cache, 'no-store'); assert.equal(call.init.signal, signal); assert.equal(new URL(call.url, 'http://fixture').searchParams.has('tenantId'), false) }
  const first = new URL(calls[0].url, 'http://fixture'); assert.equal(first.searchParams.get('legalEntityId'), LEGAL_A); assert.equal(first.searchParams.get('debitAccount'), KEY_A); assert.equal(first.searchParams.get('beforeId'), id(10))
  assert.equal(new URL(calls[1].url, 'http://fixture').searchParams.get('afterAccountKey'), KEY_A)
})


test('日期范围与历史空值筛选互斥，查询保留明确日期和排序', () => {
  assert.deepEqual(rules.cashierFilter(LEGAL_A, KEY_A, '2026-10-01', '2026-10-02', false, 'DUE_DATE_ASC'),
    { legalEntityId: LEGAL_A, debitAccount: KEY_A, dueFrom: '2026-10-01', dueTo: '2026-10-02', sort: 'DUE_DATE_ASC' })
  assert.deepEqual(rules.cashierFilter('', '', '', '', true, 'DUE_DATE_ASC'), { undated: true, sort: 'DUE_DATE_ASC' })
  for (const args of [['', '', '2026-02-30'], ['', '', '2026-10-02', '2026-10-01'], ['', '', '2026-10-01', '', true], ['', '', '', '', false, 'garbage']]) {
    assert.throws(() => rules.cashierFilter(...args))
  }
  const dated = row(); dated.payment.dueDate = '2026-10-01'
  assert.throws(() => rules.validateCashierPaymentPage(page([dated]), { dueFrom: '2026-10-02' }))
  assert.throws(() => rules.validateCashierPaymentPage(page([dated]), { undated: true }))
})

test('日期或排序改变会废弃旧游标和迟到页，执行期间保持锁定', async () => {
  backend(); const p = mount(); await settle(); const calls = []
  api.cashierPayments = (before, signal, filter) => { const task = deferred(); calls.push({ ...task, signal, filter }); return task.promise }
  p.state.changeDueDate('from', '2026-10-01'); await settle()
  p.state.changeSort('DUE_DATE_ASC'); await settle(); assert.equal(calls[0].signal.aborted, true)
  const value = row(); value.payment.dueDate = '2026-10-02'
  calls[1].resolve(page([value])); await settle(); calls[0].resolve(page([])); await settle()
  assert.equal(p.state.items.length, 1); assert.equal(calls[1].before, undefined); assert.equal(calls[1].filter.sort, 'DUE_DATE_ASC')
  p.state.saving = true; p.state.changeDueDate('to', '2026-10-05'); p.state.changeSort('AUTHORIZED_AT_DESC'); p.state.changeUndated(true)
  assert.equal(calls.length, 2)
  p.state.saving = false; p.state.changeUndated(true); await settle()
  assert.equal(p.state.dueFrom, ''); assert.equal(p.state.dueTo, ''); assert.deepEqual(calls[2].filter, { undated: true, sort: 'DUE_DATE_ASC' })
  const legacy = row(); legacy.payment.dueDate = null; calls[2].resolve(page([legacy])); await settle(); assert.match(text(p.root), /历史未设置/)
})


test('真实读取请求传递完整日期条件并禁用缓存', async () => {
  globalThis.localStorage = { getItem: () => 'test-token' }; const calls = []
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify(page([])), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  await originals.cashierPayments(undefined, new AbortController().signal, { legalEntityId: LEGAL_A, dueFrom: '2026-10-01', dueTo: '2026-10-02', sort: 'DUE_DATE_ASC' })
  await originals.cashierPayments(undefined, new AbortController().signal, { undated: true, sort: 'DUE_DATE_ASC' })
  const dated = new URL(calls[0].url, 'http://local.test'), legacy = new URL(calls[1].url, 'http://local.test')
  assert.equal(dated.searchParams.get('dueFrom'), '2026-10-01'); assert.equal(dated.searchParams.get('dueTo'), '2026-10-02'); assert.equal(dated.searchParams.get('sort'), 'DUE_DATE_ASC')
  assert.equal(legacy.searchParams.get('undated'), 'true'); assert.equal(legacy.searchParams.has('dueFrom'), false); assert.ok(calls.every(call => call.init.cache === 'no-store'))
})
