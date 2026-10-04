import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_REPAYMENTREVIEWNOTIFICATIONDETAILPANEL)
const { readRepaymentReviewNotificationTarget } = await import(process.env.AGENTFLOW_TEST_REPAYMENT_REVIEW_NOTIFICATION)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_REPAYMENT_REVIEW_NOTIFICATION_NAVIGATION)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '还款复核需核对', businessNo: 'R-1', kind: 'REPAYMENT_REVIEW_ATTENTION', actor: 'system:repayment-reviews', roundNo: 2, createdAt: when, readAt: null, content: '原退回需要核对' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onRepaymentReviewOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供原退回专用读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看还款复核')) } finally { view.close() }
})






const later = '2026-10-03T12:05:00Z'
const target = () => ({ messageId: id(1), applicationId: id(3), advanceId: id(4), repaymentId: id(9), paymentId: id(5), checkId: id(6), roundNo: 2, fact: 'RETURN_REVIEW',
  version: 4, status: 'RESOLVED', requestedAt: when, updatedAt: when, issue: null,
  observation: { outcome: 'PARTIALLY_RETURNED', revision: 2, observedAt: when, validUntil: later },
  resolution: { id: id(7), advanceVersion: 3, outcome: 'PARTIALLY_RETURNED', resolvedAt: when } })

test('退回原件与实际登记分别验证，旧待确认消息可读取自身登记而不引入办理许可', () => {
  assert.equal(readRepaymentReviewNotificationTarget(target(), message()).fact, 'RETURN_REVIEW')
  const pending = { ...target(), status: 'CHECKED', version: 3, resolution: null }; assert.equal(readRepaymentReviewNotificationTarget(pending, message()), pending)
  for (const outcome of ['CONFIRMED', 'PARTIALLY_RETURNED', 'RETURNED']) {
    const value = target(); value.fact = 'RESOLVED'; value.observation.outcome = outcome; value.resolution.outcome = outcome
    assert.equal(readRepaymentReviewNotificationTarget(value, { ...message(), kind: 'REPAYMENT_REVIEW_RESULT' }), value)
  }
  for (const change of [v => v.messageId = id(8), v => v.applicationId = id(8), v => v.roundNo = 1, v => v.checkId = '1-1-1-1-1',
    v => v.observation = null, v => v.observation = [], v => v.resolution = false, v => delete v.resolution, v => v.resolution.id = 'invalid',
    v => v.resolution.advanceVersion = 0, v => v.resolution.outcome = 'UNRESOLVED', v => v.resolution.outcome = 'RETURNED', v => v.fact = 'RESOLVED',
    v => v.status = 'CHECKED', v => v.observation.outcome = 'CONFIRMED', v => v.requestedAt = later, v => v.fact = 'UNRESOLVED',
    v => v.actions = { register: true }, v => v.issue = 'private remote error', v => v.observation.validUntil = when]) {
    const value = target(); change(value); assert.throws(() => readRepaymentReviewNotificationTarget(value, message()), /不一致/)
  }
  const bad = { ...target(), fact: 'RESOLVED', status: 'CHECKED', resolution: null }; assert.throws(() => readRepaymentReviewNotificationTarget(bad, { ...message(), kind: 'REPAYMENT_REVIEW_RESULT' }), /不一致/)
})

test('还款复核消息 API 只执行可取消且禁止缓存的 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try { const abort = new AbortController(); await api.repaymentReviewNotificationTarget('original/id', abort.signal)
    assert.match(calls[0].url, /notifications\/original%2Fid\/repayment-review-target$/); assert.equal(calls[0].method ?? 'GET', 'GET')
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
  api.repaymentReviewNotificationTarget = async () => target(); const panel = mount()
  try { await settle(); assert.equal(panel.state.detail.status, 'RESOLVED')
    let fail; api.repaymentReviewNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
    const calls = []; api.repaymentReviewNotificationTarget = (_id, signal) => new Promise(resolve => calls.push({ signal, resolve }))
    panel.props.message = { ...message(), id: id(7) }; panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve({ ...target(), messageId: id(7) }); await settle(); calls[0].resolve(target()); await settle(); assert.equal(panel.state.detail.messageId, id(7))
    panel.props.scopeKey = ''; assert.equal(panel.state.detail, null)
  } finally { panel.close() }
})

test('超时和卸载不能被迟到的登记事实覆盖', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.repaymentReviewNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(target()); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
  api.repaymentReviewNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(target()); await settle(); assert.equal(removed.state.detail, null)
})



test('真实模板区分银行原件和财务登记，不提供财务裁决或付款操作', async () => {
  for (const recorded of [false, true]) {
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); let reads = 0
    api.repaymentReviewNotificationTarget = async () => { reads++; return recorded ? target() : { ...target(), version: 3, status: 'CHECKED', resolution: null } }
    const view = renderedInbox()
    try { await settle(); assert.equal(reads, 0); view.button('查看还款复核').props.onClick(); await settle(); assert.equal(reads, 1)
      assert.match(view.text(), /等待财务明确复核和裁决/); assert.match(view.text(), /当前原查询状态/); assert.match(view.text(), /原件显示部分还款退回/)
      assert.match(view.text(), recorded ? /本次依据已明确裁决/ : /本次查询尚未裁决/); assert.match(view.text(), /原放款、还款登记、还款退回和其他占用分别核对/)
      for (const label of ['登记退回', '确认资金', '确认原放款']) assert.equal(view.button(label), undefined)
      view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].roundNo, 2); assert.equal(reads, 1)
    } finally { view.close() }
  }
})

test('旧失败查询不显示后来的登记，来源错误后清除内容和导航', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  api.repaymentReviewNotificationTarget = async () => ({ ...target(), fact: 'UNAVAILABLE', status: 'UNAVAILABLE', issue: 'TIMEOUT', observation: null, resolution: null })
  const view = renderedInbox()
  try { await settle(); view.button('查看还款复核').props.onClick(); await settle(); assert.match(view.text(), /本次没有可展示的还款复核原件/); assert.doesNotMatch(view.text(), /本次依据已明确裁决/)
    api.repaymentReviewNotificationTarget = async () => ({ ...target(), applicationId: id(8) }); view.button('重新读取').props.onClick(); await settle()
    assert.match(view.text(), /不一致/); assert.equal(view.button('查看原申请轮次'), undefined)
  } finally { view.close() }
})

test('无退回确认与全额退回按各自原件展示，不能混同为资源调整完成', async () => {
  for (const [outcome, label] of [['CONFIRMED', '原件显示原还款有效'], ['RETURNED', '原件显示全额还款退回']]) {
    api.inbox = async () => ({ items: [{ ...message(), kind: 'REPAYMENT_REVIEW_RESULT' }], unreadCount: 1, nextCursor: null })
    api.repaymentReviewNotificationTarget = async () => { const value = target(); value.fact = 'RESOLVED'; value.observation.outcome = outcome; value.resolution.outcome = outcome; return value }
    const view = renderedInbox()
    try { await settle(); view.button('查看还款复核').props.onClick(); await settle(); assert.match(view.text(), new RegExp(label)); assert.match(view.text(), /本次依据已明确裁决/)
      assert.doesNotMatch(view.text(), /预算已释放|资源已恢复/)
    } finally { view.close() }
  }
})
test('真实 App 导航固定消息原轮次并服从未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})

test('微秒到期前已登记的历史原件不能被浏览器毫秒精度误判为无效', () => {
  const value = target(); value.fact = 'RESOLVED'; value.updatedAt = '2026-10-03T12:04:00.000500Z'
  value.resolution.resolvedAt = value.updatedAt; value.observation.validUntil = '2026-10-03T12:04:00.000900Z'
  assert.equal(readRepaymentReviewNotificationTarget(value, { ...message(), kind: 'REPAYMENT_REVIEW_RESULT' }), value)
})

test('已确认但与历史退回冲突的原查询必须显示重新核对，不能显示已裁决', async () => {
  const value = { ...target(), fact: 'REVIEW_REQUIRED', status: 'CHECKED', resolution: null, observation: { ...target().observation, outcome: 'CONFIRMED' } }
  assert.equal(readRepaymentReviewNotificationTarget(value, message()), value)
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.repaymentReviewNotificationTarget = async () => value
  const view = renderedInbox()
  try { await settle(); view.button('查看还款复核').props.onClick(); await settle(); assert.match(view.text(), /已确认的还款或退回依据不一致/)
    assert.doesNotMatch(view.text(), /本次依据已明确裁决/); assert.equal(view.button('确认原放款'), undefined)
  } finally { view.close() }
})
