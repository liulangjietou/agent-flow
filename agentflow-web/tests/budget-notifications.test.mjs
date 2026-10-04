import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_BUDGETNOTIFICATIONDETAILPANEL)
const { readBudgetNotificationTarget, isBudgetNotification } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_BUDGET_NOTIFICATION_NAVIGATION)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z'
const settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '预算操作需核对', businessNo: 'B-1', kind: 'BUDGET_ATTENTION',
  actor: 'system:budgets', roundNo: 2, createdAt: when, readAt: null, content: '原预算操作结果暂不明确' })
const target = () => ({ messageId: id(1), operationId: id(2), applicationId: id(3), reportId: id(4), roundNo: 2, financialVersion: 4,
  action: 'ADJUST', status: 'UNKNOWN', version: 3, attempts: 1, updatedAt: when, observedStatus: null, issue: 'TIMEOUT', ledgerRevision: null, reference: null, appliedAt: null })
const applied = () => ({ ...target(), status: 'APPLIED', version: 5, attempts: 2, observedStatus: 'APPLIED', issue: null, ledgerRevision: 2, reference: 'original-ledger', appliedAt: when })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onBudgetOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供原预算专用读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看原预算')) } finally { view.close() }
})


test('预算响应严格区分真实应用、拒绝、处理中及查无，拒绝跨消息和附加动作', () => {
  assert.equal(isBudgetNotification(message()), true)
  const values = [target(), applied(), { ...target(), issue: 'LEASE_EXPIRED' }, { ...target(), issue: null, observedStatus: 'PENDING' },
    { ...target(), status: 'REJECTED', issue: 'LEDGER_VERSION_CONFLICT', observedStatus: 'REJECTED' },
    { ...target(), status: 'REJECTED', issue: 'BUDGET_EXCEPTION_REQUIRED', observedStatus: 'REJECTED' },
    ...['QUEUED', 'EXECUTING', 'QUERYING'].map(status => ({ ...target(), status, issue: null })),
    { ...target(), status: 'QUEUED', issue: null, observedStatus: 'NOT_FOUND' }]
  for (const value of values) assert.equal(readBudgetNotificationTarget(value, message()), value)
  for (const change of [v => v.messageId = id(9), v => v.applicationId = id(9), v => v.roundNo = 1, v => v.reportId = '1-1-1-1-1',
    v => v.financialVersion = 0, v => v.attempts = -1, v => v.version = 0, v => v.action = 'PAY', v => v.status = 'APPROVED', v => v.updatedAt = '',
    v => v.issue = null, v => v.issue = 'private gateway error', v => v.observedStatus = 'PENDING', v => v.observedStatus = 'REJECTED',
    v => v.reference = 'invented', v => v.ledgerRevision = 2, v => v.appliedAt = when, v => v.actions = { resend: true }, v => delete v.issue]) {
    const value = target(); change(value); assert.throws(() => readBudgetNotificationTarget(value, message()), /不一致/)
  }
  for (const change of [v => v.reference = null, v => v.ledgerRevision = 0, v => v.appliedAt = null, v => v.observedStatus = null, v => v.issue = 'TIMEOUT']) {
    const value = applied(); change(value); assert.throws(() => readBudgetNotificationTarget(value, message()), /不一致/)
  }
})

test('原预算 API 只按消息发起禁止缓存的可取消 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try {
    const request = new AbortController(); assert.deepEqual(await api.budgetNotificationTarget('original/id', request.signal), target())
    assert.match(calls[0].url, /notifications\/original%2Fid\/budget-target$/); assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, request.signal)
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

test('重新读取立即清除旧预算事实，失权响应不残留回执', async () => {
  api.budgetNotificationTarget = async () => applied(); const panel = mount()
  try {
    await settle(); assert.equal(panel.state.detail.reference, 'original-ledger')
    let fail; api.budgetNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
  } finally { panel.close() }
})

test('身份、消息切换及卸载使原请求迟到结果失效', async () => {
  const calls = []; api.budgetNotificationTarget = (id, signal) => new Promise((resolve, reject) => calls.push({ id, signal, resolve, reject }))
  const panel = mount()
  try {
    panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve(target()); await settle(); calls[0].reject({ status: 404 }); await settle()
    assert.equal(panel.state.error, ''); assert.equal(panel.state.detail.operationId, id(2))
    panel.props.message = { ...message(), id: id(7) }; assert.equal(panel.state.detail, null)
    panel.props.scopeKey = ''; assert.equal(calls[2].signal.aborted, true)
    calls[2].resolve({ ...applied(), messageId: id(7) }); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close() }
  let finish, signal; api.budgetNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(applied()); await settle(); assert.equal(removed.state.detail, null)
})

test('读取超时后原成功回执不能覆盖超时状态', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.budgetNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(applied()); await settle(); assert.equal(panel.state.detail, null); assert.equal(panel.state.loading, false)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
})

test('实际消息详情仅显式读取原预算，查无明确保留原命令自动重试语义', async () => {
  let reads = 0; api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  api.budgetNotificationTarget = async () => { reads++; return { ...target(), status: 'QUEUED', issue: null, observedStatus: 'NOT_FOUND' } }
  const view = renderedInbox()
  try {
    await settle(); assert.equal(reads, 0); view.button('查看原预算').props.onClick(); await settle()
    assert.equal(reads, 1); assert.match(view.text(), /查询查无，原命令待重试/); assert.match(view.text(), /保留同一命令和编号进行重试/)
    for (const label of ['发送预算', '重新冻结', '重新授权']) assert.equal(view.button(label), undefined)
    view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].operationId, id(2)); assert.equal(view.opened[0].roundNo, 2)
  } finally { view.close() }
})

test('预算正常处理中与技术未知在实际页面有不同含义', async () => {
  for (const [value, expected] of [[{ ...target(), issue: null, observedStatus: 'PENDING' }, /预算系统正在处理原操作/], [target(), /暂时无法确认原操作结果/], [applied(), /原操作已确认/]]) {
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.budgetNotificationTarget = async () => value
    const view = renderedInbox()
    try { await settle(); view.button('查看原预算').props.onClick(); await settle(); assert.match(view.text(), expected) } finally { view.close() }
  }
})

test('真实 App 导航保留消息的原申请轮次并尊重未完成写入锁', () => {
  const box = value => ({ value }), deps = { busy: box(false), writesBlocked: box(false), recordApplicationId: box(''), recordInitialRoundNo: box(0) }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps[key].value = true; open({ ...target(), applicationId: id(9), roundNo: 9 })
    assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2); deps[key].value = false }
})
