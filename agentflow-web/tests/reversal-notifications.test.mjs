import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_REVERSALNOTIFICATIONDETAILPANEL)
const { readReversalNotificationTarget, isReversalNotification } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_REVERSAL_NOTIFICATION_NAVIGATION)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '独立冲销需核对', businessNo: 'R-1', kind: 'REVERSAL_ATTENTION', actor: 'system:reversals', roundNo: 2, createdAt: when, readAt: null, content: '原冲销结果暂不明确' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onReversalOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供原冲销专用读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看原冲销')) } finally { view.close() }
})



const target = () => ({ messageId: id(1), reversalId: id(2), operationId: id(5), applicationId: id(3), businessId: id(4), roundNo: 2,
  kind: 'EXPENSE_ACCRUAL', originalStatus: 'POSTED', originalHeld: true,
  preparation: { id: id(2), version: 4, status: 'AUTHORIZED', requestedAt: when, updatedAt: when, accountingDate: '2026-10-03', issue: null },
  operation: { id: id(2), version: 3, status: 'UNKNOWN', attempts: 1, highestRevision: 0, updatedAt: when, expiresAt: '2026-10-03T12:15:00Z', observedStatus: null,
    issue: 'TIMEOUT', disputed: false, voucherReference: null, postedAt: null }, retirement: null })
const posted = () => { const value = target(); Object.assign(value.operation, { status: 'POSTED', observedStatus: 'POSTED', issue: null, voucherReference: 'original-reverse', postedAt: when, highestRevision: 2 }); return value }
const retired = () => { const value = target(); Object.assign(value.operation, { status: 'VOIDED', issue: 'FINANCE_RETIRED', attempts: 0 });
  value.retirement = { id: id(8), retiredAt: when, basis: 'NEVER_DISPATCHED' }; value.originalHeld = false; return value }

test('冲销响应接受原准备、受理、冲突和独立结束，拒绝替换身份及附加写入动作', () => {
  assert.equal(isReversalNotification(message()), true)
  const ready = target(); ready.preparation.status = 'READY'; ready.operation = null; ready.originalHeld = false
  const pending = target(); Object.assign(pending.operation, { observedStatus: 'PENDING', highestRevision: 1, issue: null })
  const conflict = posted(); Object.assign(conflict.operation, { status: 'RECONCILING', disputed: true, issue: 'INCONSISTENT_OBSERVATION' })
  for (const value of [target(), posted(), retired(), ready, pending, conflict]) assert.equal(readReversalNotificationTarget(value, message()), value)
  for (const mutate of [v => v.messageId = id(9), v => v.applicationId = id(9), v => v.roundNo = 1, v => v.businessId = '1-1-1-1-1',
    v => v.operationId = v.reversalId, v => v.kind = 'PAY', v => v.originalStatus = 'APPROVED', v => v.originalHeld = null,
    v => v.preparation.id = id(9), v => v.preparation.version = 0, v => v.preparation.status = 'READY', v => v.preparation.issue = 'private text',
    v => { v.operation = false; v.preparation.status = 'READY' }, v => v.retirement = false,
    v => delete v.operation, v => v.operation.id = id(9), v => v.operation.version = 0, v => v.operation.attempts = -1,
    v => v.operation.highestRevision = -1, v => v.operation.expiresAt = '', v => v.operation.status = 'POSTED',
    v => v.operation.status = 'RECONCILING', v => v.operation.observedStatus = 'PAID', v => v.operation.voucherReference = 'invented',
    v => v.operation.postedAt = when, v => v.operation.actions = { resend: true }, v => v.actions = { retire: true },
    v => v.retirement = { id: id(8), retiredAt: when, basis: 'CONFIRMED_FAILED' }, v => delete v.retirement]) {
    const value = target(); mutate(value); assert.throws(() => readReversalNotificationTarget(value, message()), /不一致/)
  }
})

test('原冲销 API 使用可取消且不缓存的只读 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try {
    const request = new AbortController(); assert.deepEqual(await api.reversalNotificationTarget('original/id', request.signal), target())
    assert.match(calls[0].url, /notifications\/original%2Fid\/reversal-target$/); assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, request.signal)
    assert.equal(calls[0].method ?? 'GET', 'GET'); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
  } finally { globalThis.fetch = originalFetch }
})

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount() {
  const props = reactive({ scopeKey: 'demo:alice', message: message(), locked: false })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { props, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originalApi) } }
}

test('重新读取先清除旧反向凭证，失权后不保留详情', async () => {
  api.reversalNotificationTarget = async () => posted(); const panel = mount()
  try {
    await settle(); assert.equal(panel.state.detail.operation.voucherReference, 'original-reverse')
    let fail; api.reversalNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
  } finally { panel.close() }
})

test('身份切换、消息切换和卸载丢弃旧冲销迟到结果', async () => {
  const calls = []; api.reversalNotificationTarget = (id, signal) => new Promise((resolve, reject) => calls.push({ id, signal, resolve, reject }))
  const panel = mount()
  try {
    panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve(target()); await settle(); calls[0].reject({ status: 404 }); await settle(); assert.equal(panel.state.error, '')
    panel.props.message = { ...message(), id: id(7) }; assert.equal(panel.state.detail, null)
    panel.props.scopeKey = ''; assert.equal(calls[2].signal.aborted, true); calls[2].resolve({ ...posted(), messageId: id(7) }); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close() }
  let finish, signal; api.reversalNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(posted()); await settle(); assert.equal(removed.state.detail, null)
})

test('读取超时不能被迟到成功回执覆盖', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.reversalNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(posted()); await settle(); assert.equal(panel.state.detail, null); assert.equal(panel.state.loading, false)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
})

test('实际冲销详情只读取原号，查无明确不自动重发且导航保持原轮次', async () => {
  let reads = 0; api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  api.reversalNotificationTarget = async () => { reads++; const value = target(); Object.assign(value.operation, { status: 'NOT_FOUND', observedStatus: 'NOT_FOUND', issue: null }); return value }
  const view = renderedInbox()
  try {
    await settle(); assert.equal(reads, 0); view.button('查看原冲销').props.onClick(); await settle()
    assert.equal(reads, 1); assert.match(view.text(), /系统未自动重发/)
    for (const label of ['发送冲销', '重新授权', '安全结束']) assert.equal(view.button(label), undefined)
    view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].reversalId, id(2)); assert.equal(view.opened[0].roundNo, 2)
  } finally { view.close() }
})

test('实际页面区分未授权准备、正常受理和执行未知', async () => {
  const unavailable = target(); unavailable.operation = null; unavailable.originalHeld = false; Object.assign(unavailable.preparation, { status: 'UNAVAILABLE', issue: 'TIMEOUT' })
  const pending = target(); Object.assign(pending.operation, { observedStatus: 'PENDING', issue: null, highestRevision: 1 })
  for (const [value, expected] of [[unavailable, /本次准备暂不可用，未登记冲销命令/], [pending, /ERP 正在处理原冲销/], [target(), /暂时无法确认原冲销结果/]]) {
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.reversalNotificationTarget = async () => value
    const view = renderedInbox(); try { await settle(); view.button('查看原冲销').props.onClick(); await settle(); assert.match(view.text(), expected) } finally { view.close() }
  }
})

test('实际页面保留已接受的反向凭证，结束旧冲销后可并列显示另一冲销绑定', async () => {
  const conflict = posted(); Object.assign(conflict.operation, { status: 'RECONCILING', disputed: true, issue: 'INCONSISTENT_OBSERVATION' })
  const ended = retired(); ended.originalHeld = true
  for (const [value, expected] of [[conflict, [/回执存在冲突/, /original-reverse/]], [ended, [/已安全结束本次冲销/, /绑定可能来自另一次冲销/]]]) {
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.reversalNotificationTarget = async () => value
    const view = renderedInbox(); try { await settle(); view.button('查看原冲销').props.onClick(); await settle(); for (const text of expected) assert.match(view.text(), text); assert.match(view.text(), /不表示银行已退款/) } finally { view.close() }
  }
})

test('真实 App 导航保留原轮次并尊重未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})
