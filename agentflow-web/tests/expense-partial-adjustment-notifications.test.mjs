import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEPARTIALADJUSTMENTNOTIFICATIONDETAILPANEL)
const { readExpensePartialAdjustmentNotificationTarget } = await import(process.env.AGENTFLOW_TEST_EXPENSE_PARTIAL_ADJUSTMENT_NOTIFICATION)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_EXPENSE_PARTIAL_ADJUSTMENT_NOTIFICATION_NAVIGATION)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '报销部分调整需核对', businessNo: 'R-1', kind: 'EXPENSE_PARTIAL_ADJUSTMENT_ATTENTION', actor: 'system:expense-partial-adjustments', roundNo: 2, createdAt: when, readAt: null, content: '原报销部分调整需要核对' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onExpensePartialAdjustmentOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供报销部分调整的原操作读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看报销部分调整')) } finally { view.close() }
})


const target = () => ({ messageId: id(1), applicationId: id(3), reportId: id(4), adjustmentId: id(5), roundNo: 2, sourceType: 'BUDGET', sourceId: id(6), fact: 'BUDGET_UNKNOWN', preparation: null,
  budget: { id: id(6), version: 3, status: 'UNKNOWN', updatedAt: when, failure: 'CONNECTION', outcome: null, conflictingOutcome: null },
  accrual: { id: id(7), version: 3, status: 'POSTED', updatedAt: when, failure: null, outcome: 'POSTED', conflictingOutcome: null },
  adjustment: { version: 7, status: 'WAITING_FINANCE', updatedAt: when, resourcesCompleted: false, issue: null }, completion: null, retirement: null, resolution: null })
const resultMessage = () => ({ ...message(), kind: 'EXPENSE_PARTIAL_ADJUSTMENT_RESULT' })
const applied = () => { const v = target(); v.sourceType = 'ADJUSTMENT'; v.sourceId = v.adjustmentId; v.fact = 'COMPLETED'; v.budget.status = 'APPLIED'; v.budget.outcome = 'APPLIED'; v.budget.failure = null;
  v.adjustment.status = 'APPLIED'; v.adjustment.resourcesCompleted = true; v.completion = { budgetVersion: 3, accrualVersion: 3, completedAt: when }; return v }
const prepared = () => ({ ...target(), sourceType: 'PREPARATION', sourceId: id(8), fact: 'PREPARATION_UNAVAILABLE', preparation: { side: 'ACCRUAL', version: 3, status: 'UNAVAILABLE', updatedAt: when, issue: 'CONNECTION' }, budget: null, accrual: null, adjustment: null })

test('固定原消息和原操作，拒绝换侧、替换编号及混入财务正文', () => {
  for (const [v,m] of [[target(),message()],[applied(),resultMessage()],[prepared(),message()]]) assert.deepEqual(readExpensePartialAdjustmentNotificationTarget(v,m),v)
  for (const change of [v => { v.sourceId = id(9) }, v => { v.sourceType = 'ACCRUAL' }, v => { v.messageId = id(9) }, v => { v.roundNo++ },
    v => { v.budget.id = id(9) }, v => { v.budget.status = 'POSTED' }, v => { v.budget.amount = '100' }, v => { v.actions = ['RETRY'] },
    v => { v.budget.updatedAt = 'invalid' }, v => { v.adjustment.resourcesCompleted = true }, v => { delete v.resolution }]) {
    const v = target(); change(v); assert.throws(() => readExpensePartialAdjustmentNotificationTarget(v,message()), /不一致/)
  }
  const invalid = applied(); invalid.completion.accrualVersion++; assert.throws(() => readExpensePartialAdjustmentNotificationTarget(invalid,resultMessage()), /不一致/)
})

test('预算确认和资源完成分别呈现，真实入口不提供财务写入', async () => {
  const value = target(); value.fact = 'BUDGET_APPLIED'; value.budget.status = 'APPLIED'; value.budget.outcome = 'APPLIED'; value.budget.failure = null; value.adjustment.status = 'READY'
  api.inbox = async () => ({ items: [resultMessage()], unreadCount: 1, nextCursor: null }); let reads = 0
  api.expensePartialAdjustmentNotificationTarget = async () => { reads++; return value }; const view = renderedInbox()
  try { await settle(); assert.equal(reads, 0); view.button('查看报销部分调整').props.onClick(); await settle(); assert.equal(reads,1)
    assert.match(view.text(), /原预算调减已确认/); assert.match(view.text(), /尚无实际资源完成证明/)
    for (const label of ['授权本侧调整','明确重发','明确裁决']) assert.equal(view.button(label),undefined)
    const done = { ...applied(), sourceType: 'BUDGET', sourceId: id(6), fact: 'BUDGET_APPLIED' }; api.expensePartialAdjustmentNotificationTarget = async () => done
    view.button('重新读取').props.onClick(); await settle(); assert.match(view.text(), /实际资源调整已完成/)
    view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].applicationId,id(3)); assert.equal(view.opened[0].roundNo,2)
  } finally { view.close() }
})

test('原准备不关联后续授权，消息与当前冲突观察分开显示', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.expensePartialAdjustmentNotificationTarget = async () => prepared(); const view = renderedInbox()
  try { await settle(); view.button('查看报销部分调整').props.onClick(); await settle(); assert.match(view.text(), /尚未授权本侧命令/); assert.doesNotMatch(view.text(), /原预算操作最后记录/)
    const conflict = target(); conflict.budget.status = 'RECONCILING'; conflict.budget.failure = 'INCONSISTENT_OBSERVATION'; conflict.budget.outcome = 'APPLIED'; conflict.budget.conflictingOutcome = 'REJECTED'
    api.expensePartialAdjustmentNotificationTarget = async () => conflict; view.button('重新读取').props.onClick(); await settle(); assert.match(view.text(), /另一次冲突观察/)
    api.expensePartialAdjustmentNotificationTarget = async () => ({ ...target(), sourceId: id(9) }); view.button('重新读取').props.onClick(); await settle()
    assert.match(view.text(), /不一致/); assert.equal(view.button('查看原申请轮次'),undefined)
  } finally { view.close() }
})

test('实际裁决必须关联原侧原操作，历史事实允许保留已完成资源', () => {
  const v = applied(); v.sourceType = 'DISPUTE'; v.sourceId = id(8); v.fact = 'DISPUTE_RESOLVED'
  v.resolution = { id: id(8), side: 'BUDGET', operationId: id(6), beforeVersion: 6, afterVersion: 7, outcome: 'APPLIED', resolvedAt: when }
  assert.deepEqual(readExpensePartialAdjustmentNotificationTarget(v,resultMessage()),v)
  v.resolution.operationId = id(9); assert.throws(() => readExpensePartialAdjustmentNotificationTarget(v,resultMessage()), /不一致/)
  const old = { ...applied(), sourceType: 'BUDGET', sourceId: id(6), fact: 'BUDGET_UNKNOWN' }
  old.budget.status = 'RECONCILING'; old.budget.failure = 'INCONSISTENT_OBSERVATION'; old.budget.conflictingOutcome = 'REJECTED'; old.adjustment.status = 'REVIEW_REQUIRED'; old.adjustment.issue = 'BUDGET_RESULT_UNCONFIRMED'
  assert.deepEqual(readExpensePartialAdjustmentNotificationTarget(old,message()),old)
})

test('安全结束必须有对应证明且不能同时声称资源已完成', () => {
  const v = { ...target(), sourceType: 'ADJUSTMENT', sourceId: id(5), fact: 'RETIRED', budget: null, accrual: null }
  v.adjustment.status = 'RETIRED'; v.retirement = { retiredAt: when }; assert.deepEqual(readExpensePartialAdjustmentNotificationTarget(v,resultMessage()),v)
  v.retirement.retiredAt = '2026-10-04T12:00:00Z'; assert.throws(() => readExpensePartialAdjustmentNotificationTarget(v,resultMessage()), /不一致/)
})

test('部分调整消息 API 只执行可取消且禁止缓存的 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try { const abort = new AbortController(); await api.expensePartialAdjustmentNotificationTarget('original/id', abort.signal)
    assert.match(calls[0].url, /notifications\/original%2Fid\/expense-partial-adjustment-target$/); assert.equal(calls[0].method ?? 'GET','GET'); assert.equal(calls[0].cache,'no-store')
    assert.equal(calls[0].signal,abort.signal); assert.equal(calls[0].headers.has('Idempotency-Key'),false)
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
  api.expensePartialAdjustmentNotificationTarget = async () => target(); const panel = mount()
  try { await settle(); assert.equal(panel.state.detail.budget.status, 'UNKNOWN')
    let fail; api.expensePartialAdjustmentNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
    const calls = []; api.expensePartialAdjustmentNotificationTarget = (_id, signal) => new Promise(resolve => calls.push({ signal, resolve }))
    panel.props.message = { ...message(), id: id(7) }; panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve({ ...target(), messageId: id(7) }); await settle(); calls[0].resolve(target()); await settle(); assert.equal(panel.state.detail.messageId, id(7))
    panel.props.scopeKey = ''; assert.equal(panel.state.detail, null)
  } finally { panel.close() }
})

test('超时和卸载不能被迟到的报销部分调整结果覆盖', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.expensePartialAdjustmentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(target()); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
  api.expensePartialAdjustmentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(target()); await settle(); assert.equal(removed.state.detail, null)
})




test('真实 App 导航固定消息原轮次并服从未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})

