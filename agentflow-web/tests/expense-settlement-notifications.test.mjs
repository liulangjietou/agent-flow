import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSESETTLEMENTNOTIFICATIONDETAILPANEL)
const { readExpenseSettlementNotificationTarget } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_EXPENSE_SETTLEMENT_NOTIFICATION_NAVIGATION)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '报销结算需核对', businessNo: 'R-1', kind: 'EXPENSE_SETTLEMENT_ATTENTION', actor: 'system:reversal-checks', roundNo: 2, createdAt: when, readAt: null, content: '原冲销结果暂不明确' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onSettlementOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供原结算专用读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看原结算')) } finally { view.close() }
})




const target = () => ({ messageId: id(1), applicationId: id(3), reportId: id(4), roundNo: 2, financialVersion: 5, funding: 'PAYMENT', fundingConfirmedAt: when,
  notice: { version: 2, status: 'BLOCKED', resourcesConsumed: false, budgetOperationId: null, issue: 'INVOICE_VERIFICATION_REQUIRED', updatedAt: when },
  current: { version: 5, status: 'SETTLED', resourcesConsumed: true, budgetOperationId: id(6), issue: null, updatedAt: when } })

test('原结算解析区分旧失败与当前完成，拒绝伪造成功及办理许可', () => {
  for (const funding of ['PAYMENT', 'FULL_OFFSET', 'ZERO_AMOUNT']) { const value = { ...target(), funding }; assert.equal(readExpenseSettlementNotificationTarget(value, message()), value) }
  const review = target(); review.notice = { ...review.current }; review.current = { ...review.current, version: 6, status: 'REVIEW_REQUIRED', issue: 'EXPENSE_PAYMENT_REVIEW' }
  assert.equal(readExpenseSettlementNotificationTarget(review, { ...message(), kind: 'EXPENSE_SETTLEMENT_RESULT' }), review)
  for (const change of [v => v.messageId = id(7), v => v.applicationId = id(7), v => v.reportId = '1-1-1-1-1', v => v.roundNo = 1, v => v.financialVersion = 0,
    v => v.funding = 'BANK_UNKNOWN', v => v.fundingConfirmedAt = '', v => v.current = false, v => v.notice = [], v => delete v.current, v => v.notice.issue = null,
    v => v.notice.status = 'QUEUED', v => v.notice.budgetOperationId = id(6), v => v.current.resourcesConsumed = false, v => v.current.budgetOperationId = null,
    v => v.current.version = 1, v => v.current.version = v.notice.version, v => v.current.issue = 'private remote error', v => v.actions = { retry: true }]) {
    const value = target(); change(value); assert.throws(() => readExpenseSettlementNotificationTarget(value, message()), /不一致/)
  }
  assert.throws(() => readExpenseSettlementNotificationTarget(target(), { ...message(), kind: 'EXPENSE_SETTLEMENT_RESULT' }), /不一致/)
})

test('结算消息 API 只执行可取消且禁止缓存的 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try { const abort = new AbortController(); await api.expenseSettlementNotificationTarget('original/id', abort.signal)
    assert.match(calls[0].url, /notifications\/original%2Fid\/expense-settlement-target$/); assert.equal(calls[0].method ?? 'GET', 'GET')
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


test('重读先清除旧结算，失权和身份切换使迟到结果失效', async () => {
  api.expenseSettlementNotificationTarget = async () => target(); const panel = mount()
  try { await settle(); assert.equal(panel.state.detail.current.status, 'SETTLED')
    let fail; api.expenseSettlementNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
    const calls = []; api.expenseSettlementNotificationTarget = (_id, signal) => new Promise(resolve => calls.push({ signal, resolve }))
    panel.props.message = { ...message(), id: id(7) }; panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve({ ...target(), messageId: id(7) }); await settle(); calls[0].resolve(target()); await settle(); assert.equal(panel.state.detail.messageId, id(7))
    panel.props.scopeKey = ''; assert.equal(panel.state.detail, null)
  } finally { panel.close() }
})

test('超时和卸载不能被迟到的完成事实覆盖', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.expenseSettlementNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(target()); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
  api.expenseSettlementNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(target()); await settle(); assert.equal(removed.state.detail, null)
})

test('真实模板分别展示历史阻塞与当前完成，只提供读取和原轮次导航', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); let reads = 0
  api.expenseSettlementNotificationTarget = async () => { reads++; return target() }; const view = renderedInbox()
  try { await settle(); assert.equal(reads, 0); view.button('查看原结算').props.onClick(); await settle(); assert.equal(reads, 1)
    assert.match(view.text(), /消息发生时/); assert.match(view.text(), /核销需要处理/); assert.match(view.text(), /当前结算状态/); assert.match(view.text(), /核销已完成/)
    assert.match(view.text(), /原登记依据/); assert.match(view.text(), /付款凭证、归档/); assert.equal(view.button('重试预算'), undefined)
    view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].roundNo, 2); assert.equal(reads, 1)
  } finally { view.close() }
})

test('真实模板保留零核定与全额冲销说明，错误来源隐藏跳转', async () => {
  for (const [funding, label] of [['ZERO_AMOUNT', '本次核定金额为零'], ['FULL_OFFSET', '借款全额冲销']]) {
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.expenseSettlementNotificationTarget = async () => ({ ...target(), funding }); const view = renderedInbox()
    try { await settle(); view.button('查看原结算').props.onClick(); await settle(); assert.match(view.text(), new RegExp(label)); assert.doesNotMatch(view.text(), /银行已确认本次应付到账/) } finally { view.close() }
  }
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.expenseSettlementNotificationTarget = async () => ({ ...target(), applicationId: id(8) }); const view = renderedInbox()
  try { await settle(); view.button('查看原结算').props.onClick(); await settle(); assert.match(view.text(), /不一致/); assert.equal(view.button('查看原申请轮次'), undefined) } finally { view.close() }
})

test('真实 App 导航固定消息原轮次并服从未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})
