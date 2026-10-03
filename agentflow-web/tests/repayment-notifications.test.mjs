import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_REPAYMENTNOTIFICATIONDETAILPANEL)
const { readRepaymentNotificationTarget } = await import(process.env.AGENTFLOW_TEST_REPAYMENT_NOTIFICATION)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_REPAYMENT_NOTIFICATION_NAVIGATION)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '借款还款需核对', businessNo: 'R-1', kind: 'REPAYMENT_ATTENTION', actor: 'system:repayments', roundNo: 2, createdAt: when, readAt: null, content: '原还款需要核对' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onRepaymentOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供原还款专用读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看借款还款')) } finally { view.close() }
})






const later = '2026-10-03T12:05:00Z'
const target = () => ({ messageId: id(1), applicationId: id(3), advanceId: id(4), paymentId: id(5), checkId: id(6), roundNo: 2, fact: 'REVIEW_REQUIRED',
  version: 3, status: 'CHECKED', requestedAt: when, updatedAt: when, issue: null, reviewRepaymentId: id(7),
  observation: { outcome: 'CONFIRMED', revision: 2, observedAt: when, validUntil: later }, record: null })
const recorded = () => ({ ...target(), fact: 'RECORDED', status: 'RECORDED', version: 4, reviewRepaymentId: null, record: { id: id(7), advanceVersion: 3, recordedAt: when } })

test('原查询、登记与当时触发的复核保持独立且拒绝伪造办理许可', () => {
  assert.equal(readRepaymentNotificationTarget(target(), message()).fact, 'REVIEW_REQUIRED')
  assert.equal(readRepaymentNotificationTarget(recorded(), { ...message(), kind: 'REPAYMENT_RESULT' }).record.advanceVersion, 3)
  for (const fact of ['NOT_FOUND', 'PENDING', 'REVERSED']) {
    const value = { ...target(), fact, reviewRepaymentId: null, observation: { ...target().observation, outcome: fact, revision: fact === 'NOT_FOUND' ? 0 : 2 } }
    assert.equal(readRepaymentNotificationTarget(value, message()), value)
  }
  for (const change of [v => v.messageId = id(8), v => v.applicationId = id(8), v => v.roundNo = 1, v => v.checkId = 'fake', v => v.canRecord = true,
    v => v.reviewRepaymentId = null, v => v.fact = 'RECORDED', v => v.observation.revision = 0, v => v.observation.outcome = 'UNRESOLVED', v => v.record = recorded().record]) {
    const value = target(); change(value); assert.throws(() => readRepaymentNotificationTarget(value, message()), /不一致/)
  }
})

test('借款还款消息 API 只执行可取消且禁止缓存的 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try { const abort = new AbortController(); await api.repaymentNotificationTarget('original/id', abort.signal)
    assert.match(calls[0].url, /notifications\/original%2Fid\/repayment-target$/); assert.equal(calls[0].method ?? 'GET', 'GET')
    assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, abort.signal); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
  } finally { globalThis.fetch = originalFetch }
})
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount() {
  const props = reactive({ scopeKey: 'demo:alice', message: message(), locked: false })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const instance = app.mount({})
  return { props, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originalApi) } }
}


test('重读先清除旧退回，失权和身份切换使迟到结果失效', async () => {
  api.repaymentNotificationTarget = async () => target(); const panel = mount()
  try { await settle(); assert.equal(panel.state.detail.status, 'CHECKED')
    let fail; api.repaymentNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
    const calls = []; api.repaymentNotificationTarget = (_id, signal) => new Promise(resolve => calls.push({ signal, resolve }))
    panel.props.message = { ...message(), id: id(7) }; panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve({ ...target(), messageId: id(7) }); await settle(); calls[0].resolve(target()); await settle(); assert.equal(panel.state.detail.messageId, id(7))
    panel.props.scopeKey = ''; assert.equal(panel.state.detail, null)
  } finally { panel.close() }
})

test('超时和卸载不能被迟到的登记事实覆盖', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.repaymentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(target()); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
  api.repaymentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(target()); await settle(); assert.equal(removed.state.detail, null)
})



test('真实模板展示当时触发复核与实际登记，不把读取作为新的财务决定', async () => {
  for (const value of [target(), recorded()]) {
    api.inbox = async () => ({ items: [{ ...message(), kind: value.fact === 'RECORDED' ? 'REPAYMENT_RESULT' : 'REPAYMENT_ATTENTION' }], unreadCount: 1, nextCursor: null })
    let reads = 0; api.repaymentNotificationTarget = async () => { reads++; return value }
    const view = renderedInbox()
    try { await settle(); assert.equal(reads, 0); view.button('查看借款还款').props.onClick(); await settle()
      assert.match(view.text(), /当前原查询状态/); assert.match(view.text(), /原件显示已收款并入账/)
      assert.match(view.text(), value.fact === 'RECORDED' ? /本次原件已明确登记还款/ : /本次查询触发原还款复核/)
      for (const label of ['确认还款', '登记还款', '解除冻结']) assert.equal(view.button(label), undefined)
      view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].roundNo, 2); assert.equal(reads, 1)
    } finally { view.close() }
  }
})
test('旧失败查询不显示后来的登记，读取失效清除内容和导航', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  api.repaymentNotificationTarget = async () => ({ ...target(), fact: 'UNAVAILABLE', status: 'UNAVAILABLE', issue: 'TIMEOUT', observation: null, record: null, reviewRepaymentId: null })
  const view = renderedInbox()
  try { await settle(); view.button('查看借款还款').props.onClick(); await settle(); assert.match(view.text(), /本次没有可展示的收款核对原件/)
    assert.doesNotMatch(view.text(), /本次原件已明确登记还款/)
    api.repaymentNotificationTarget = async () => ({ ...target(), applicationId: id(8) }); view.button('重新读取').props.onClick(); await settle()
    assert.match(view.text(), /不一致/); assert.equal(view.button('查看原申请轮次'), undefined)
  } finally { view.close() }
})
test('查无与未入账不能显示已还款，零版本只属于查无结果', async () => {
  for (const [fact, label] of [['NOT_FOUND', '本次未找到收款原件'], ['PENDING', '原件尚待确认'], ['REVERSED', '原件显示收款已撤销']]) {
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
    const value = { ...target(), fact, reviewRepaymentId: null, observation: { ...target().observation, outcome: fact, revision: fact === 'NOT_FOUND' ? 0 : 2 } }
    api.repaymentNotificationTarget = async () => value
    const view = renderedInbox()
    try { await settle(); view.button('查看借款还款').props.onClick(); await settle(); assert.match(view.text(), new RegExp(label)); assert.doesNotMatch(view.text(), /本次原件已明确登记还款/)
      if (fact === 'NOT_FOUND') { value.observation.revision = 1; assert.throws(() => readRepaymentNotificationTarget(value, message()), /不一致/) }
    } finally { view.close() }
  }
})
test('真实 App 导航固定消息原轮次并服从未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})

test('到期前已登记原件保留浏览器毫秒边界，超期登记不能通过', () => {
  const value = recorded(); value.updatedAt = '2026-10-03T12:04:00.000500Z'; value.record.recordedAt = value.updatedAt
  value.observation.validUntil = '2026-10-03T12:04:00.000900Z'
  assert.equal(readRepaymentNotificationTarget(value, { ...message(), kind: 'REPAYMENT_RESULT' }), value)
  value.record.recordedAt = value.updatedAt = '2026-10-03T12:04:00.002Z'
  assert.throws(() => readRepaymentNotificationTarget(value, { ...message(), kind: 'REPAYMENT_RESULT' }), /不一致/)
})
