import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { readPaymentNotificationTarget, readSupplierPaymentNotificationTarget, isPaymentNotification } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_PAYMENTNOTIFICATIONDETAILPANEL)
const { default: Facts } = await import(process.env.AGENTFLOW_TEST_SUPPLIERPAYMENTFACTSRENDERED)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_SUPPLIERCASHIERWORKSPACE)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_PAYMENT_NOTIFICATION_NAVIGATION)
const originals = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve)), clone = v => JSON.parse(JSON.stringify(v))
globalThis.localStorage = { getItem: () => 'test-token' }
const message = () => ({ id: id(1), applicationId: id(3), title: '供应商付款需核对', businessNo: 'P-1', kind: 'SUPPLIER_PAYMENT_ATTENTION', actor: 'system', roundNo: 2, createdAt: when, readAt: null, content: '原选择检查未通过' })
const target = (status = 'BLOCKED') => ({ messageId: id(1), paymentId: id(2), executionRequestId: id(4), view: 'CASHIER_PAYMENT', applicationId: id(3), roundNo: 2, canOpenCashier: ['QUEUED', 'RUNNING', 'READY'].includes(status),
  payment: { authorizationId: id(2), requestId: id(5), applicationId: id(3), roundNo: 2, legalEntityId: id(6), employeeId: 'alice', supplierName: '原供应商', amount: { value: '70.00', currency: 'CNY' }, maskedPayeeAccount: '****3456', authorizedBy: 'finance', authorizedAt: when, expiresAt: '2026-10-03T13:00:00Z', retiredAt: null,
    hold: { version: 3, status: 'HELD', updatedAt: when }, preparation: { id: id(4), version: 3, status, cashier: 'original-cashier', updatedAt: when, issue: status === 'READY' ? null : 'EVIDENCE_CHANGED' },
    operation: status === 'READY' ? { version: 4, status: 'SUCCEEDED', cashier: 'original-cashier', updatedAt: when, observedStatus: 'SUCCEEDED', paymentReference: 'bank-1', receiptReference: 'receipt-1', completedAt: when, disputed: false, issue: null } : null,
    actions: { execute: false, query: false, resendOriginal: false } } })
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(Component = Panel, input = {}) {
  const props = reactive({ scopeKey: 'demo:cashier', message: message(), locked: false, refreshVersion: 1, ...input })
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { props, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); globalThis.fetch = originalFetch } }
}

test('供应商消息必须固定原授权和原请求，只读详情不能携带资金动作', () => {
  const value = target(); assert.equal(readSupplierPaymentNotificationTarget(value, message()), value); assert.equal(isPaymentNotification(message()), true)
  for (const mutate of [v => v.paymentId = id(8), v => v.executionRequestId = id(9), v => v.payment.preparation.id = id(9), v => v.applicationId = id(8), v => v.roundNo = 1,
    v => v.payment.actions.query = true, v => v.canOpenCashier = true, v => v.view = 'ADMIN', v => v.executionRequestId = '1-1-1-1-1', v => v.payment.maskedPayeeAccount = '6222000012345678']) {
    const bad = clone(value); mutate(bad); assert.throws(() => readSupplierPaymentNotificationTarget(bad, message()))
  }
  assert.throws(() => readSupplierPaymentNotificationTarget(value, { ...message(), kind: 'PAYMENT_ATTENTION' }))
  assert.throws(() => readPaymentNotificationTarget(value, message()))
})

test('供应商原消息 API 使用固定只读路径、取消信号和禁用缓存', async () => {
  const calls = [], control = new AbortController()
  globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return Response.json(target()) }
  try {
    await api.supplierPaymentNotificationTarget('notice/1', control.signal)
    assert.equal(calls.length, 1); assert.match(calls[0].url, /\/notifications\/notice%2F1\/supplier-payment-target$/)
    assert.equal(calls[0].method ?? 'GET', 'GET'); assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, control.signal)
    assert.equal(calls[0].body, undefined); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
  } finally { globalThis.fetch = originalFetch }
})

test('真实详情组件显示未发送的原出纳选择，不混入后来付款或办理按钮', async () => {
  api.paymentNotificationTarget = async () => { throw new Error('不应调用员工付款入口') }
  for (const status of ['BLOCKED', 'VOIDED', 'EXPIRED']) {
    api.supplierPaymentNotificationTarget = async () => target(status)
    const p = mount()
    try {
      await settle(); assert.equal(p.state.error, ''); assert.equal(p.state.detail.executionRequestId, id(4)); assert.equal(p.state.canNavigate, false)
      const html = await renderToString(createSSRApp(Facts, { view: p.state.detail.payment }))
      assert.match(html, /尚未登记银行指令/); assert.match(html, /original-cashier/); assert.doesNotMatch(html, /银行已确认到账|银行回单|<button|<form/)
    } finally { p.close() }
  }
})

test('真实只读模板明确区分银行成功和仍须核对的原应付结算', async () => {
  api.supplierPaymentNotificationTarget = async () => target('READY')
  const p = mount()
  try {
    await settle(); assert.equal(p.state.error, ''); assert.equal(p.state.canNavigate, true)
    const html = await renderToString(createSSRApp(Facts, { view: p.state.detail.payment }))
    assert.match(html, /银行已确认到账/); assert.match(html, /原应付结算状态仍需由财务继续核对/); assert.match(html, /receipt-1/); assert.doesNotMatch(html, /<button|<form/)
  } finally { p.close() }
})

test('供应商详情失权后清空旧记录，身份切换丢弃忽略取消的迟到结果', async () => {
  api.supplierPaymentNotificationTarget = async () => target('READY'); const p = mount()
  try {
    await settle(); assert.notEqual(p.state.detail, null)
    api.supplierPaymentNotificationTarget = async () => { throw { status: 403 } }; await p.state.load()
    assert.equal(p.state.detail, null); assert.equal(p.state.canNavigate, false); assert.match(p.state.error, /无法读取/)
    let finish, signal; api.supplierPaymentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
    const waiting = p.state.load(); p.props.scopeKey = ''; assert.equal(signal.aborted, true); finish(target('READY')); await waiting
    assert.equal(p.state.detail, null); assert.equal(p.state.error, '')
  } finally { p.close() }
})

test('原供应商付款导航受角色和原选择占用限制，采购轮次始终保持', () => {
  const box = value => ({ value }), deps = { busy: box(false), writesBlocked: box(false), canCashier: box(true), notice: box(''), notificationPaymentId: box(''), cashierKind: box('employee'), page: box('notifications'), recordApplicationId: box(''), recordInitialRoundNo: box(0) }
  const open = createNavigation(deps); open(target()); assert.equal(deps.page.value, 'notifications'); assert.equal(deps.notificationPaymentId.value, '')
  deps.canCashier.value = false; open(target('READY')); assert.equal(deps.page.value, 'notifications')
  deps.canCashier.value = true; open(target('READY')); assert.equal(deps.notificationPaymentId.value, id(2)); assert.equal(deps.cashierKind.value, 'supplier'); assert.equal(deps.page.value, 'cashier')
  open({ ...target(), view: 'APPLICATION_ROUND' }); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
})

test('供应商工作区可定位第一页之外的原授权，身份清空后不留旧选择', async () => {
  api.supplierCashierPayments = async () => ({ items: [], nextBeforeId: null }); const p = mount(Workspace, { initialPaymentId: id(2) })
  try { await settle(); assert.equal(p.state.selected, id(2)); assert.equal(p.state.items.length, 0); p.props.scopeKey = ''; assert.equal(p.state.selected, '') }
  finally { p.close() }
})

test('消息实际模板点击才读取供应商原请求，停止的选择没有跳转到新付款的按钮', async () => {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'), setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) }, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root')
  let reads = 0; api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.supplierPaymentNotificationTarget = async () => { reads++; return target() }
  const app = host.createApp(Inbox, { scopeKey: 'demo:cashier', refreshVersion: 1, locked: false }); app.mount(root)
  try {
    await settle(); assert.equal(reads, 0)
    const open = all(root).find(n => n.tag === 'button' && text(n).includes('查看原付款')); assert.ok(open); open.props.onClick(); await settle()
    assert.equal(reads, 1); assert.match(text(root), /尚未登记银行指令/); assert.match(text(root), /原出纳登记/); assert.match(text(root), /这次出纳选择已停止/)
    assert.equal(all(root).some(n => n.tag === 'button' && text(n).includes('打开原付款工作区')), false)
  } finally { app.unmount(); Object.assign(api, originals); globalThis.fetch = originalFetch }
})
