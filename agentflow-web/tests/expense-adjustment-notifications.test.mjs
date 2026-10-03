import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEADJUSTMENTNOTIFICATIONDETAILPANEL)
const { readExpenseAdjustmentNotificationTarget } = await import(process.env.AGENTFLOW_TEST_EXPENSE_ADJUSTMENT_NOTIFICATION)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_EXPENSE_ADJUSTMENT_NOTIFICATION_NAVIGATION)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '报销资源调整需核对', businessNo: 'R-1', kind: 'EXPENSE_ADJUSTMENT_ATTENTION', actor: 'system:expense-adjustments', roundNo: 2, createdAt: when, readAt: null, content: '原报销资源调整需要核对' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onExpenseAdjustmentOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供报销资源调整的原操作读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看报销资源调整')) } finally { view.close() }
})

const target = () => ({ messageId: id(1), applicationId: id(3), reportId: id(4), adjustmentId: id(5), roundNo: 2, fact: 'BUDGET_UNKNOWN',
  preparation: { version: 4, status: 'AUTHORIZED', updatedAt: when, issue: null },
  budget: { version: 3, status: 'UNKNOWN', updatedAt: when, failure: 'CONNECTION', outcome: null, conflictingOutcome: null },
  adjustment: { version: 1, status: 'WAITING_BUDGET', updatedAt: when, resourcesReversed: false, issue: null }, completion: null, retirement: null })
const reviewed = () => ({ ...target(), fact: 'PREPARATION_UNAVAILABLE', preparation: { version: 3, status: 'UNAVAILABLE', updatedAt: when, issue: 'CONNECTION' }, budget: null, adjustment: null })
const applied = () => ({ ...target(), fact: 'COMPLETED', budget: { ...target().budget, status: 'APPLIED', failure: null, outcome: 'APPLIED' },
  adjustment: { ...target().adjustment, version: 3, status: 'APPLIED', resourcesReversed: true }, completion: { adjustmentVersion: 3, budgetVersion: 3, completedAt: when } })
const resultMessage = () => ({ ...message(), kind: 'EXPENSE_ADJUSTMENT_RESULT' })

test('原准备、预算与资源投影拒绝混入金额、办理许可和其他消息编号', () => {
  assert.deepEqual(readExpenseAdjustmentNotificationTarget(target(), message()), target())
  assert.deepEqual(readExpenseAdjustmentNotificationTarget(reviewed(), message()), reviewed())
  assert.deepEqual(readExpenseAdjustmentNotificationTarget(applied(), resultMessage()), applied())
  const changes = [v => { v.messageId = id(9) }, v => { v.applicationId = id(9) }, v => { v.roundNo++ }, v => { v.fact = 'RETIRED' },
    v => { v.actions = { retry: true } }, v => { v.budget.amount = '100' }, v => { v.budget.updatedAt = 'invalid' }, v => { v.budget.status = 'APPLIED' },
    v => { v.adjustment.resourcesReversed = true }, v => { v.preparation.status = 'UNAVAILABLE' }, v => { delete v.retirement }]
  for (const change of changes) { const value = target(); change(value); assert.throws(() => readExpenseAdjustmentNotificationTarget(value, message()), /不一致/) }
  const wrong = applied(); wrong.completion.budgetVersion++; assert.throws(() => readExpenseAdjustmentNotificationTarget(wrong, resultMessage()), /不一致/)
})

test('历史未知消息展示原编号后续完成，原预算回执冲突不会消除资源完成事实', () => {
  const value = { ...applied(), fact: 'BUDGET_UNKNOWN' }; assert.deepEqual(readExpenseAdjustmentNotificationTarget(value, message()), value)
  value.budget.status = 'RECONCILING'; value.budget.failure = 'INCONSISTENT_OBSERVATION'; value.budget.conflictingOutcome = 'REJECTED'
  value.adjustment.status = 'REVIEW_REQUIRED'; value.adjustment.issue = 'BUDGET_RECONCILING'
  assert.deepEqual(readExpenseAdjustmentNotificationTarget(value, message()), value)
  value.completion = null; assert.throws(() => readExpenseAdjustmentNotificationTarget(value, message()), /不一致/)
})

test('真实模板按点击读取，预算成功和实际资源完成分开呈现', async () => {
  const value = target(); value.fact = 'BUDGET_APPLIED'; value.budget.status = 'APPLIED'; value.budget.failure = null; value.budget.outcome = 'APPLIED'; value.adjustment.status = 'READY'
  api.inbox = async () => ({ items: [resultMessage()], unreadCount: 1, nextCursor: null }); let reads = 0
  api.expenseAdjustmentNotificationTarget = async () => { reads++; return value }; const view = renderedInbox()
  try { await settle(); assert.equal(reads, 0); view.button('查看报销资源调整').props.onClick(); await settle(); assert.equal(reads, 1)
    assert.match(view.text(), /预算冲正已确认/); assert.match(view.text(), /尚无实际资源完成证明/)
    for (const label of ['授权预算冲正与资源冲回', '恢复未完成资源冲回', '安全结束']) assert.equal(view.button(label), undefined)
    api.expenseAdjustmentNotificationTarget = async () => ({ ...applied(), fact: 'BUDGET_APPLIED' }); view.button('重新读取').props.onClick(); await settle()
    assert.match(view.text(), /实际资源恢复已完成/); view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].applicationId, id(3)); assert.equal(view.opened[0].roundNo, 2)
  } finally { view.close() }
})

test('准备失败不关联另一调整，真实消息详情展示单独的冲突结果', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.expenseAdjustmentNotificationTarget = async () => reviewed(); const view = renderedInbox()
  try { await settle(); view.button('查看报销资源调整').props.onClick(); await settle(); assert.match(view.text(), /尚未授权预算冲正/); assert.doesNotMatch(view.text(), /本地资源当前状态/)
    const conflict = target(); conflict.fact = 'BUDGET_RECONCILING'; conflict.budget.status = 'RECONCILING'; conflict.budget.failure = 'INCONSISTENT_OBSERVATION'; conflict.budget.outcome = 'APPLIED'; conflict.budget.conflictingOutcome = 'REJECTED'
    api.expenseAdjustmentNotificationTarget = async () => conflict; view.button('重新读取').props.onClick(); await settle(); assert.match(view.text(), /另一次冲突观察/)
    api.expenseAdjustmentNotificationTarget = async () => ({ ...target(), applicationId: id(9) }); view.button('重新读取').props.onClick(); await settle(); assert.match(view.text(), /不一致/); assert.equal(view.button('查看原申请轮次'), undefined)
  } finally { view.close() }
})

test('安全结束必须匹配原调整与停止预算的准确版本', () => {
  const value = target(); value.fact = 'RETIRED'; value.budget.status = 'VOIDED'; value.budget.failure = 'SOURCE_CHANGED'; value.adjustment.status = 'RETIRED'
  value.retirement = { adjustmentVersion: 1, budgetVersion: 3, retiredAt: when }
  assert.deepEqual(readExpenseAdjustmentNotificationTarget(value, resultMessage()), value)
  value.retirement.budgetVersion++; assert.throws(() => readExpenseAdjustmentNotificationTarget(value, resultMessage()), /不一致/)
})

test('报销资源调整消息 API 只执行可取消且禁止缓存的 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try { const abort = new AbortController(); await api.expenseAdjustmentNotificationTarget('original/id', abort.signal)
    assert.match(calls[0].url, /notifications\/original%2Fid\/expense-adjustment-target$/); assert.equal(calls[0].method ?? 'GET', 'GET')
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


test('重读先清除旧调整记录，失权和身份切换使迟到结果失效', async () => {
  api.expenseAdjustmentNotificationTarget = async () => target(); const panel = mount()
  try { await settle(); assert.equal(panel.state.detail.budget.status, 'UNKNOWN')
    let fail; api.expenseAdjustmentNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
    const calls = []; api.expenseAdjustmentNotificationTarget = (_id, signal) => new Promise(resolve => calls.push({ signal, resolve }))
    panel.props.message = { ...message(), id: id(7) }; panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve({ ...target(), messageId: id(7) }); await settle(); calls[0].resolve(target()); await settle(); assert.equal(panel.state.detail.messageId, id(7))
    panel.props.scopeKey = ''; assert.equal(panel.state.detail, null)
  } finally { panel.close() }
})

test('超时和卸载不能被迟到的报销结果覆盖', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.expenseAdjustmentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(target()); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
  api.expenseAdjustmentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(target()); await settle(); assert.equal(removed.state.detail, null)
})




test('真实 App 导航固定消息原轮次并服从未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})

