import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { readVoucherNotificationTarget, isVoucherNotification } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_VOUCHERNOTIFICATIONDETAILPANEL)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_VOUCHER_NOTIFICATION_NAVIGATION)
const originals = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve)), clone = value => JSON.parse(JSON.stringify(value))
globalThis.localStorage = { getItem: () => 'test-token' }
const message = () => ({ id: id(1), applicationId: id(3), title: '凭证处理需核对', businessNo: 'V-1', kind: 'VOUCHER_ATTENTION', actor: 'system:vouchers', roundNo: 2, createdAt: when, readAt: null, content: '原凭证准备暂不可用' })
const target = () => ({ messageId: id(1), voucherId: id(2), applicationId: id(3), businessId: id(4), roundNo: 2, kind: 'EXPENSE_ACCRUAL', reversalBound: false,
  preparation: { id: id(2), status: 'UNAVAILABLE', attempt: 1, createdAt: when, completedAt: when, issue: 'NOT_CONFIGURED' }, operation: null })
const posted = () => ({ ...target(), preparation: { ...target().preparation, status: 'READY', issue: null },
  operation: { id: id(2), version: 3, kind: 'EXPENSE_ACCRUAL', status: 'POSTED', attempts: 1, accountingDate: '2026-10-03', updatedAt: when,
    sendExpiresAt: '2026-10-03T13:00:00Z', observedStatus: 'POSTED', voucherReference: 'original-voucher', postedAt: when, disputed: false, issue: null } })
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount() {
  const props = reactive({ scopeKey: 'demo:alice', message: message(), locked: false })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { props, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); globalThis.fetch = originalFetch } }
}

test('原凭证响应必须匹配消息、轮次、准备和操作编号，不能夹带写入动作', () => {
  assert.equal(isVoucherNotification(message()), true)
  for (const value of [target(), posted(), { ...posted(), preparation: null }]) assert.equal(readVoucherNotificationTarget(value, message()), value)
  for (const change of [v => v.messageId = id(8), v => v.applicationId = id(8), v => v.roundNo = 1, v => v.voucherId = id(8), v => v.preparation.id = id(8),
    v => v.preparation.status = 'POSTED', v => v.preparation.attempt = 0, v => v.preparation.completedAt = null, v => v.preparation = null,
    v => v.kind = 'SUPPLIER', v => v.actions = { query: true }, v => v.voucherId = '1-1-1-1-1', v => v.reversalBound = true, v => v.reversalBound = 'false']) {
    const value = target(); change(value); assert.throws(() => readVoucherNotificationTarget(value, message()))
  }
  for (const change of [v => v.operation.id = id(8), v => v.operation.kind = 'PAYMENT', v => v.operation.version = 0, v => v.operation.status = 'SUCCEEDED',
    v => v.operation.updatedAt = 'invalid', v => v.operation.attempts = -1, v => v.operation.disputed = 'false', v => v.preparation.status = 'UNAVAILABLE']) {
    const value = posted(); change(value); assert.throws(() => readVoucherNotificationTarget(value, message()))
  }
  assert.throws(() => readVoucherNotificationTarget(target(), { ...message(), kind: 'PAYMENT_ATTENTION' }))
})

test('凭证消息 API 只使用原消息 GET、取消信号和禁用缓存', async () => {
  const calls = [], controller = new AbortController()
  globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return Response.json(target()) }
  try {
    await api.voucherNotificationTarget('message/1', controller.signal)
    assert.equal(calls.length, 1); assert.match(calls[0].url, /\/notifications\/message%2F1\/voucher-target$/)
    assert.equal(calls[0].method ?? 'GET', 'GET'); assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, controller.signal)
    assert.equal(calls[0].body, undefined); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
  } finally { globalThis.fetch = originalFetch }
})

test('真实详情重新读取先清空旧凭证，失权不保留旧财务事实', async () => {
  api.voucherNotificationTarget = async () => posted(); const panel = mount()
  try {
    await settle(); assert.equal(panel.state.detail.operation.voucherReference, 'original-voucher')
    let reject; api.voucherNotificationTarget = () => new Promise((_resolve, fail) => { reject = fail })
    const pending = panel.state.load(); assert.equal(panel.state.detail, null)
    reject({ status: 403 }); await pending
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/); assert.equal(panel.state.loading, false)
  } finally { panel.close() }
})

test('切换身份、消息或卸载后，忽略取消的旧响应不能恢复原详情', async () => {
  const calls = []; api.voucherNotificationTarget = (id, signal) => new Promise((resolve, reject) => calls.push({ id, signal, resolve, reject }))
  const panel = mount()
  try {
    panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve(target()); await settle(); calls[0].reject({ status: 404 }); await settle()
    assert.equal(panel.state.error, ''); assert.equal(panel.state.detail.voucherId, id(2))
    panel.props.message = { ...message(), id: id(7) }; assert.equal(panel.state.detail, null)
    panel.props.scopeKey = ''; assert.equal(calls[2].signal.aborted, true)
    calls[2].resolve({ ...posted(), messageId: id(7) }); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close() }
  let finish, signal; api.voucherNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(posted()); await settle(); assert.equal(removed.state.detail, null)
})

test('超时后迟到的凭证成功响应不能替换已失败的读取', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.voucherNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try {
    expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(posted()); await settle(); assert.equal(panel.state.detail, null); assert.equal(panel.state.loading, false)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
})

test('真实 App 导航保持原申请轮次，忙碌和未确认操作时不改变入口', () => {
  const box = value => ({ value }), deps = { busy: box(false), writesBlocked: box(false), recordApplicationId: box(''), recordInitialRoundNo: box(0) }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const blocked of ['busy', 'writesBlocked']) {
    deps[blocked].value = true; open({ ...target(), applicationId: id(8), roundNo: 8 })
    assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2); deps[blocked].value = false
  }
})

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'), setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) }, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onVoucherOpen: value => opened.push(value) }); app.mount(root)
  return { all: () => all(root), text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originals); globalThis.fetch = originalFetch } }
}

test('真实消息中心显式点击才读原凭证，失败准备不显示后来的过账或办理按钮', async () => {
  let reads = 0; api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.voucherNotificationTarget = async () => { reads++; return target() }
  const view = renderedInbox()
  try {
    await settle(); assert.equal(reads, 0); view.button('查看原凭证').props.onClick(); await settle()
    assert.equal(reads, 1); assert.match(view.text(), /尚未登记过账命令/); assert.match(view.text(), /第 1 次准备/)
    assert.equal(view.button('重新准备凭证'), undefined); assert.equal(view.button('按原编号重发'), undefined); assert.equal(view.button('查询 ERP 结果'), undefined)
    view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].voucherId, id(2)); assert.equal(view.opened[0].roundNo, 2)
  } finally { view.close() }
})

test('真实原凭证模板区分处理中、冲突和已过账，资金与结算另行核对', async () => {
  for (const status of ['UNKNOWN', 'RECONCILING', 'POSTED']) {
    const value = posted(); value.operation.status = status
    if (status === 'UNKNOWN') Object.assign(value.operation, { issue: null, observedStatus: 'PENDING', voucherReference: null, postedAt: null })
    if (status === 'RECONCILING') Object.assign(value.operation, { issue: 'INCONSISTENT_OBSERVATION', disputed: true })
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.voucherNotificationTarget = async () => value
    const view = renderedInbox()
    try {
      await settle(); view.button('查看原凭证').props.onClick(); await settle()
      assert.match(view.text(), status === 'UNKNOWN' ? /ERP 正在处理原操作/ : status === 'RECONCILING' ? /此前确认的凭证号/ : /已过账凭证号/)
      assert.match(view.text(), /资金到账、业务结算与资源恢复需分别核对/)
      if (status !== 'POSTED') assert.doesNotMatch(view.text(), /已过账凭证号/)
    } finally { view.close() }
  }
})

test('已授权独立冲销的原凭证保留过账事实并明确当前停用', async () => {
  const value = { ...posted(), reversalBound: true }
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.voucherNotificationTarget = async () => value
  const view = renderedInbox()
  try {
    await settle(); view.button('查看原凭证').props.onClick(); await settle()
    assert.match(view.text(), /已过账凭证号/); assert.match(view.text(), /已绑定独立冲销/); assert.match(view.text(), /不能作为新的财务依据/)
  } finally { view.close() }
})
