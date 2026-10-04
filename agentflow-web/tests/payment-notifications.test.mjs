import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { readPaymentNotificationTarget, isPaymentNotification } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_PAYMENTNOTIFICATIONDETAILPANEL)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_CASHIERWORKSPACE)
const { default: Facts } = await import(process.env.AGENTFLOW_TEST_PAYMENTFACTSRENDERED)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_PAYMENT_NOTIFICATION_NAVIGATION)
const originals = { ...api }, originalFetch = globalThis.fetch
const messageId = '12345678-1234-1234-1234-123456789001', paymentId = '12345678-1234-1234-1234-123456789002'
const applicationId = '12345678-1234-1234-1234-123456789003', otherId = '12345678-1234-1234-1234-123456789004'
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
globalThis.localStorage = { getItem: () => 'test-token' }
const message = () => ({ id: messageId, applicationId, title: '付款执行需核对', businessNo: 'AP-01', kind: 'PAYMENT_ATTENTION', actor: 'system:payments', roundNo: 2, createdAt: when, readAt: null, content: '结果未知，请查询原交易。' })
const payment = () => ({ id: paymentId, applicationId, businessId: otherId, roundNo: 2, applicationVersion: 9, businessVersion: 3,
  version: 2, status: 'EXECUTION_REGISTERED', purpose: 'EMPLOYEE_ADVANCE', legalEntityId: otherId, employeeId: 'alice', amount: { value: '100.00', currency: 'CNY' },
  maskedPayeeAccount: '****1234', authorizedBy: 'finance', authorizedAt: when, expiresAt: '2026-10-03T13:00:00Z', dueDate: null, executedBy: 'cashier', request: null, retirement: null,
  operation: { version: 4, status: 'UNKNOWN', updatedAt: when, observedStatus: null, paymentReference: null, receiptReference: null, completedAt: null, disputed: false, issue: 'CONNECTION' } })
const target = () => ({ messageId, paymentId, view: 'CASHIER_PAYMENT', applicationId, roundNo: 2, payment: payment() })
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(Component = Panel, input = {}) {
  const props = reactive({ scopeKey: 'demo:cashier', message: message(), locked: false, refreshVersion: 1, ...input })
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { props, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); globalThis.fetch = originalFetch } }
}

test('登记前异常可读取原授权，实际模板明确显示尚未发送付款', async () => {
  for (const [status, issue, authorizationStatus] of [['QUEUED', 'TIMEOUT', 'AUTHORIZED'], ['BLOCKED', 'ACCOUNT_CHANGED', 'AUTHORIZED'], ['VOIDED', 'SOURCE_CHANGED', 'VOIDED'], ['EXPIRED', 'AUTHORIZATION_EXPIRED', 'EXPIRED']]) {
    const value = target(); Object.assign(value.payment, { status: authorizationStatus, version: authorizationStatus === 'AUTHORIZED' ? 1 : 2, dueDate: null, executedBy: null, operation: null,
      request: { id: otherId, version: 3, status, cashier: 'cashier', createdAt: when, updatedAt: when, issue } })
    api.paymentNotificationTarget = async () => value
    const p = mount()
    try {
      await settle(); assert.equal(p.state.error, ''); assert.equal(p.state.detail.paymentId, paymentId)
      assert.equal(p.state.detail.payment.operation, null); assert.equal(p.state.detail.payment.request.status, status)
      const html = await renderToString(createSSRApp(Facts, { payment: p.state.detail.payment }))
      assert.match(html, /尚未发送付款/); assert.match(html, /cashier/); assert.doesNotMatch(html, /银行回单|资金交易号|付款成功/)
    } finally { p.close() }
  }
})

test('原消息、原付款和原轮次必须同时匹配，审批消息不能借用付款入口', () => {
  const value = target(); assert.equal(readPaymentNotificationTarget(value, message()), value)
  for (const changed of [{ messageId: otherId }, { paymentId: otherId }, { applicationId: otherId }, { roundNo: 1 }, { view: 'ADMIN' }, { paymentId: '1-1-1-1-1' }])
    assert.throws(() => readPaymentNotificationTarget({ ...value, ...changed }, message()))
  for (const changed of [{ applicationId: otherId }, { roundNo: 3 }, { id: otherId }, { amount: { value: 100, currency: 'CNY' } }])
    assert.throws(() => readPaymentNotificationTarget({ ...value, payment: { ...value.payment, ...changed } }, message()))
  assert.throws(() => readPaymentNotificationTarget(value, { ...message(), kind: 'APPLICATION_APPROVED' }))
  assert.equal(isPaymentNotification(message()), true)
  assert.equal(isPaymentNotification({ ...message(), kind: 'PAYMENT_RESULT' }), true)
  assert.equal(isPaymentNotification({ ...message(), kind: 'EXPENSE_ADJUSTED' }), false)
})

test('详情 API 只读、可取消且禁止缓存，不发送资金写入或标记已读请求', async () => {
  const calls = [], controller = new AbortController()
  globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return Response.json(target()) }
  try {
    await api.paymentNotificationTarget('message/1', controller.signal)
    assert.equal(calls.length, 1); assert.match(calls[0].url, /\/notifications\/message%2F1\/payment-target$/)
    assert.equal(calls[0].method ?? 'GET', 'GET'); assert.equal(calls[0].body, undefined); assert.equal(calls[0].cache, 'no-store')
    assert.equal(calls[0].signal, controller.signal); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
  } finally { globalThis.fetch = originalFetch }
})

test('实际详情组件只读取消息对应的付款，重新读取前立即清空旧资金记录', async () => {
  const reads = []; api.paymentNotificationTarget = (id, signal) => new Promise((resolve, reject) => reads.push({ id, signal, resolve, reject }))
  const p = mount()
  try {
    assert.equal(reads[0].id, messageId); reads[0].resolve(target()); await settle()
    assert.equal(p.state.detail.paymentId, paymentId)
    const reload = p.state.load(); assert.equal(p.state.detail, null); assert.equal(reads.length, 2)
    reads[1].reject({ status: 403 }); await reload
    assert.equal(p.state.detail, null); assert.match(p.state.error, /当前账号已无法读取/); assert.equal(p.state.loading, false)
  } finally { p.close() }
})

test('切换账号或消息会取消旧请求，迟到成功与失权错误不能污染新消息', async () => {
  const reads = []; api.paymentNotificationTarget = (id, signal) => new Promise((resolve, reject) => reads.push({ id, signal, resolve, reject }))
  const p = mount()
  try {
    p.props.scopeKey = 'demo:alice'; assert.equal(reads[0].signal.aborted, true)
    reads[1].resolve({ ...target(), view: 'APPLICATION_ROUND' }); await settle()
    reads[0].reject({ status: 404 }); await settle(); assert.equal(p.state.error, ''); assert.equal(p.state.detail.view, 'APPLICATION_ROUND')
    p.props.message = { ...message(), id: otherId }; assert.equal(p.state.detail, null)
    const pending = reads[2]; p.props.scopeKey = ''; assert.equal(pending.signal.aborted, true)
    pending.resolve({ ...target(), messageId: otherId }); await settle()
    assert.equal(p.state.detail, null); assert.equal(p.state.error, ''); assert.equal(p.state.loading, false)
  } finally { p.close() }
})

test('超时后即使底层忽略取消，迟到付款结果也不能重新显示', async () => {
  const originalSetTimeout = globalThis.setTimeout, originalClearTimeout = globalThis.clearTimeout
  let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }
  globalThis.clearTimeout = () => {}
  api.paymentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const p = mount()
  try {
    expire(); await settle(); assert.equal(signal.aborted, true); assert.equal(p.state.loading, false); assert.match(p.state.error, /超时/)
    finish(target()); await settle(); assert.equal(p.state.detail, null); assert.match(p.state.error, /超时/)
  } finally { p.close(); globalThis.setTimeout = originalSetTimeout; globalThis.clearTimeout = originalClearTimeout }
})

test('失配的成功响应不可导航，组件卸载后忽略迟到付款详情', async () => {
  api.paymentNotificationTarget = async () => ({ ...target(), paymentId: otherId })
  const p = mount()
  try { await settle(); assert.equal(p.state.detail, null); assert.notEqual(p.state.error, '') }
  finally { p.close() }
  let finish, signal
  api.paymentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const late = mount(); late.close(); assert.equal(signal.aborted, true)
  finish(target()); await settle(); assert.equal(late.state.detail, null)
})

test('实际主页面导航保留原付款或历史轮次，工作区角色和锁定状态仍生效', () => {
  const box = value => ({ value }), deps = { busy: box(false), writesBlocked: box(false), canCashier: box(true), notice: box(''),
    notificationPaymentId: box(''), cashierKind: box('supplier'), page: box('notifications'), recordApplicationId: box(''), recordInitialRoundNo: box(0) }
  const open = createNavigation(deps)
  for (const guard of ['busy', 'writesBlocked']) { deps[guard].value = true; open(target()); assert.equal(deps.page.value, 'notifications'); deps[guard].value = false }
  deps.canCashier.value = false; open(target()); assert.equal(deps.notificationPaymentId.value, ''); assert.match(deps.notice.value, /没有出纳/)
  deps.canCashier.value = true; open(target()); assert.equal(deps.notificationPaymentId.value, paymentId); assert.equal(deps.cashierKind.value, 'employee'); assert.equal(deps.page.value, 'cashier')
  open({ ...target(), view: 'APPLICATION_ROUND' }); assert.equal(deps.recordApplicationId.value, applicationId); assert.equal(deps.recordInitialRoundNo.value, 2)
})

test('原出纳工作台可以定位第一页之外的付款，身份清空时删除旧选择', async () => {
  api.cashierPayments = async () => ({ items: [], nextBeforeId: null, totalCount: 0 })
  const p = mount(Workspace, { initialPaymentId: paymentId })
  try {
    await settle(); assert.equal(p.state.items.length, 0); assert.equal(p.state.selected, paymentId)
    p.props.initialPaymentId = otherId; assert.equal(p.state.selected, otherId)
    p.props.scopeKey = ''; assert.equal(p.state.selected, ''); assert.equal(p.state.items.length, 0)
  } finally { p.close() }
})

test('消息实际模板仅在点击后读取原付款，展示未知结果且明确点击才导航', async () => {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) }, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), events = []
  let reads = 0, readMarks = 0
  const row = { ...message(), content: '<img src=x onerror=alert(1)>结果未知' }
  api.inbox = async () => ({ items: [row], unreadCount: 1, nextCursor: null })
  api.paymentNotificationTarget = async id => { assert.equal(id, messageId); reads++; return target() }
  const props = reactive({ scopeKey: 'demo:cashier', refreshVersion: 1, locked: false })
  const app = host.createApp({ ...Inbox, setup: (_, context) => Inbox.setup(props, context) }, { ...props, onPaymentOpen: value => events.push(value), onRead: () => readMarks++ })
  const button = label => all(root).find(n => n.tag === 'button' && text(n).includes(label))
  try {
    app.mount(root); await settle(); assert.equal(reads, 0); assert.match(text(root), /结果未知/)
    assert.equal(all(root).some(n => n.tag === 'img'), false); assert.equal(button('打开原付款工作区'), undefined)
    button('查看原付款').props.onClick(); await settle()
    assert.equal(reads, 1); assert.equal(readMarks, 0); assert.equal(events.length, 0)
    assert.match(text(root), /100.00/); assert.match(text(root), /结果未知/); assert.match(text(root), new RegExp(paymentId))
    button('打开原付款工作区').props.onClick(); assert.equal(events.length, 1); assert.equal(events[0].paymentId, paymentId)
    props.scopeKey = ''; await settle(); assert.equal(button('打开原付款工作区'), undefined); assert.doesNotMatch(text(root), /100.00/)
  } finally { app.unmount(); Object.assign(api, originals) }
})
