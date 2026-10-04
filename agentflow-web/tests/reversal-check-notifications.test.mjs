import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_REVERSALCHECKNOTIFICATIONDETAILPANEL)
const { readReversalCheckNotificationTarget } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_REVERSAL_CHECK_NOTIFICATION_NAVIGATION)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '外部冲销核对需处理', businessNo: 'R-1', kind: 'REVERSAL_CHECK_ATTENTION', actor: 'system:reversal-checks', roundNo: 2, createdAt: when, readAt: null, content: '原冲销结果暂不明确' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onReversalCheckOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供原核对专用读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看原核对')) } finally { view.close() }
})



const target = () => ({ messageId: id(1), checkId: id(2), operationId: id(5), applicationId: id(3), businessId: id(4), roundNo: 2,
  kind: 'EXPENSE_ACCRUAL', originalStatus: 'REVERSED', originalHeld: false, version: 3, status: 'UNAVAILABLE', requestedAt: when, updatedAt: when,
  issue: 'TIMEOUT', observation: null, record: null })
const unresolved = () => ({ ...target(), status: 'CHECKED', issue: null, observation: { status: 'UNRESOLVED', revision: 1, observedAt: when,
  validUntil: '2026-10-03T12:02:00Z', voucherReference: null, accountingDate: null, postedAt: null } })
const recorded = () => { const value = unresolved(); value.status = 'RECORDED'; value.version = 4; Object.assign(value.observation, { status: 'VERIFIED', voucherReference: 'original-reverse', accountingDate: '2026-10-03', postedAt: when }); value.record = { id: id(6), recordedAt: when }; return value }

test('原核对解析保留未核清与实际登记，拒绝错误身份、伪造过账和附加动作', () => {
  for (const value of [target(), unresolved(), recorded(), { ...target(), status: 'VOIDED', issue: 'SOURCE_CHANGED' }]) assert.equal(readReversalCheckNotificationTarget(value, message()), value)
  for (const change of [v => v.messageId = id(9), v => v.applicationId = id(9), v => v.roundNo = 1, v => v.checkId = v.operationId,
    v => v.businessId = '1-1-1-1-1', v => v.kind = 'PAY', v => v.originalStatus = 'APPROVED', v => v.originalHeld = null, v => v.version = 0,
    v => v.updatedAt = '', v => v.issue = 'remote private error', v => v.issue = null, v => v.status = 'RECORDED', v => v.record = false,
    v => v.observation = false, v => delete v.observation, v => delete v.record, v => v.actions = { record: true }]) {
    const value = target(); change(value); assert.throws(() => readReversalCheckNotificationTarget(value, message()), /不一致/)
  }
  for (const change of [v => v.record = null, v => v.record.id = 'fake', v => v.record.actions = { refund: true }, v => v.observation.status = 'UNRESOLVED',
    v => v.observation.voucherReference = null, v => v.observation.accountingDate = null, v => v.observation.postedAt = null, v => v.observation.revision = 0,
    v => v.observation.entries = []]) {
    const value = recorded(); change(value); assert.throws(() => readReversalCheckNotificationTarget(value, message()), /不一致/)
  }
})

test('原核对 API 只发起可取消且禁止缓存的 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try { const request = new AbortController(); assert.deepEqual(await api.reversalCheckNotificationTarget('original/id', request.signal), target())
    assert.match(calls[0].url, /notifications\/original%2Fid\/reversal-check-target$/); assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, request.signal)
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

test('重新读取先清除旧登记，失权不能残留反向凭证', async () => {
  api.reversalCheckNotificationTarget = async () => recorded(); const panel = mount()
  try { await settle(); assert.equal(panel.state.detail.record.id, id(6))
    let fail; api.reversalCheckNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
  } finally { panel.close() }
})

test('身份、消息切换与卸载丢弃旧核对的迟到响应', async () => {
  const calls = []; api.reversalCheckNotificationTarget = (id, signal) => new Promise((resolve, reject) => calls.push({ id, signal, resolve, reject }))
  const panel = mount()
  try {
    panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve(target()); await settle(); calls[0].reject({ status: 404 }); await settle(); assert.equal(panel.state.error, '')
    panel.props.message = { ...message(), id: id(7) }; assert.equal(panel.state.detail, null)
    panel.props.scopeKey = ''; assert.equal(calls[2].signal.aborted, true); calls[2].resolve({ ...recorded(), messageId: id(7) }); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close() }
  let finish, signal; api.reversalCheckNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(recorded()); await settle(); assert.equal(removed.state.detail, null)
})

test('超时后的迟到登记响应不覆盖错误或恢复页面', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.reversalCheckNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(recorded()); await settle(); assert.equal(panel.state.detail, null); assert.equal(panel.state.loading, false)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
})

test('实际消息详情只显式读取原核对，未核清不显示登记或重发动作', async () => {
  let reads = 0; api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  api.reversalCheckNotificationTarget = async () => { reads++; return unresolved() }; const view = renderedInbox()
  try { await settle(); assert.equal(reads, 0); view.button('查看原核对').props.onClick(); await settle()
    assert.equal(reads, 1); assert.match(view.text(), /本次查询未核清外部反向凭证/); assert.match(view.text(), /未核清不表示未发生冲销/)
    for (const label of ['登记冲销', '重新查询 ERP', '退款', '发送冲销']) assert.equal(view.button(label), undefined)
    view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].checkId, id(2)); assert.equal(view.opened[0].roundNo, 2)
  } finally { view.close() }
})

test('实际模板区分旧查询失败与明确登记，始终说明资金和资源没有自动恢复', async () => {
  for (const [value, expected] of [[target(), [/本次核对没有登记记录/, /其他核对的处理结果请在原申请分别查看/]], [recorded(), [/本次已明确登记/, /original-reverse/, /登记编号/]]]) {
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.reversalCheckNotificationTarget = async () => value
    const view = renderedInbox(); try { await settle(); view.button('查看原核对').props.onClick(); await settle(); for (const text of expected) assert.match(view.text(), text); assert.match(view.text(), /不自动恢复预算、借款或费用资源/) } finally { view.close() }
  }
})

test('真实页面拒绝返回其他核对的登记身份并隐藏申请跳转', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.reversalCheckNotificationTarget = async () => ({ ...recorded(), applicationId: id(99) })
  const view = renderedInbox(); try { await settle(); view.button('查看原核对').props.onClick(); await settle(); assert.match(view.text(), /记录不一致/); assert.equal(view.button('查看原申请轮次'), undefined); assert.doesNotMatch(view.text(), /original-reverse/) } finally { view.close() }
})

test('真实 App 导航保留原轮次并尊重未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})
