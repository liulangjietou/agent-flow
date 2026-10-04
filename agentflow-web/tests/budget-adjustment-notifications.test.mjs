import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_BUDGETADJUSTMENTNOTIFICATIONDETAILPANEL)
const { readBudgetAdjustmentNotificationTarget } = await import(process.env.AGENTFLOW_TEST_BUDGET_ADJUSTMENT_NOTIFICATION)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_BUDGET_ADJUSTMENT_NOTIFICATION_NAVIGATION)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '预算调整需核对', businessNo: 'R-1', kind: 'BUDGET_ADJUSTMENT_ATTENTION', actor: 'system:budget-adjustments', roundNo: 2, createdAt: when, readAt: null, content: '原预算操作需要核对' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onBudgetAdjustmentOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供预算调整的原操作读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看预算调整')) } finally { view.close() }
})

const target = () => ({ messageId: id(1), applicationId: id(3), requestId: id(4), roundNo: 2, sourceType: 'OPERATION', sourceId: id(5), fact: 'UNKNOWN', review: null,
  operation: { id: id(5), version: 3, status: 'UNKNOWN', createdAt: when, updatedAt: when, failure: 'CONNECTION', observation: null, conflictingObservation: null }, retirement: null })
const reviewed = () => ({ ...target(), sourceType: 'REVIEW', fact: 'REVIEW_UNAVAILABLE', operation: null,
  review: { id: id(5), version: 3, status: 'UNAVAILABLE', requestedAt: when, updatedAt: when, issue: 'CONNECTION' } })
const observation = (outcome = 'APPLIED') => ({ outcome, revision: outcome === 'NOT_FOUND' ? 0 : 1, observedAt: when, appliedAt: outcome === 'APPLIED' ? when : null, rejection: outcome === 'REJECTED' ? 'LEDGER_VERSION_CONFLICT' : null })
const applied = () => ({ ...target(), fact: 'APPLIED', operation: { ...target().operation, status: 'APPLIED', failure: null, observation: observation() } })
const resultMessage = () => ({ ...message(), kind: 'BUDGET_ADJUSTMENT_RESULT' })

test('原复核和原指令严格区分，拒绝替换编号、外部台账和办理许可', () => {
  assert.deepEqual(readBudgetAdjustmentNotificationTarget(target(), message()), target())
  assert.deepEqual(readBudgetAdjustmentNotificationTarget(reviewed(), message()), reviewed())
  assert.deepEqual(readBudgetAdjustmentNotificationTarget(applied(), resultMessage()), applied())
  const mutations = [v => { v.messageId = id(8) }, v => { v.applicationId = id(8) }, v => { v.roundNo++ }, v => { v.sourceId = id(8) },
    v => { v.sourceType = 'REVIEW' }, v => { v.fact = 'RETIRED' }, v => { v.fact = 'APPLIED' }, v => { v.actions = { authorize: true } },
    v => { v.operation.ledger = {} }, v => { delete v.operation.observation }, v => { v.operation.failure = 'UNTRUSTED' }, v => { v.operation.updatedAt = 'invalid' }]
  for (const change of mutations) { const value = target(); change(value); assert.throws(() => readBudgetAdjustmentNotificationTarget(value, message()), /不一致/) }
  const invalid = applied(); invalid.operation.observation.amount = '100'; assert.throws(() => readBudgetAdjustmentNotificationTarget(invalid, resultMessage()), /不一致/)
})

test('历史未知消息可展示同一原指令的后续生效，但不能冒充安全结束', () => {
  const value = { ...applied(), fact: 'UNKNOWN' }; assert.deepEqual(readBudgetAdjustmentNotificationTarget(value, message()), value)
  value.retirement = { operationId: id(5), operationVersion: value.operation.version, basis: 'NEVER_SENT', retiredAt: when }
  assert.throws(() => readBudgetAdjustmentNotificationTarget(value, message()), /不一致/)
  for (const outcome of ['PENDING', 'NOT_FOUND', 'REJECTED']) {
    const current = target(); current.operation.status = outcome === 'PENDING' ? 'UNKNOWN' : outcome; current.operation.failure = null; current.operation.observation = observation(outcome)
    assert.deepEqual(readBudgetAdjustmentNotificationTarget(current, message()), current)
  }
})

test('消息点击才读取原指令，真实模板区分未知、原结果与冲突结果', async () => {
  const value = applied(); value.fact = 'RECONCILING'; value.operation.status = 'RECONCILING'; value.operation.failure = 'INCONSISTENT_OBSERVATION'; value.operation.conflictingObservation = observation('REJECTED')
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); let reads = 0
  api.budgetAdjustmentNotificationTarget = async () => { reads++; return value }; const view = renderedInbox()
  try { await settle(); assert.equal(reads, 0); view.button('查看预算调整').props.onClick(); await settle(); assert.equal(reads, 1)
    assert.match(view.text(), /回执存在矛盾/); assert.match(view.text(), /原系统观察/); assert.match(view.text(), /另一次冲突观察/); assert.match(view.text(), /已生效/); assert.match(view.text(), /明确拒绝/)
    for (const label of ['确认预算调整授权', '重发原调整指令', '安全结束原指令']) assert.equal(view.button(label), undefined)
    view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].applicationId, id(3)); assert.equal(view.opened[0].roundNo, 2); assert.equal(reads, 1)
  } finally { view.close() }
})

test('失败台账复核保持原尝试，不显示后来的授权或执行结果', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); api.budgetAdjustmentNotificationTarget = async () => reviewed(); const view = renderedInbox()
  try { await settle(); view.button('查看预算调整').props.onClick(); await settle(); assert.match(view.text(), /本次台账复核/); assert.match(view.text(), /尚未形成新的调整授权/)
    assert.doesNotMatch(view.text(), /当前原指令状态/); api.budgetAdjustmentNotificationTarget = async () => ({ ...reviewed(), sourceId: id(8) }); view.button('重新读取').props.onClick(); await settle()
    assert.match(view.text(), /不一致/); assert.equal(view.button('查看原申请轮次'), undefined)
  } finally { view.close() }
})

test('实际安全结束依据必须匹配原指令修订，模板不提供新授权', async () => {
  const value = target(); value.fact = 'RETIRED'; value.operation.status = 'VOIDED'; value.operation.failure = 'SOURCE_CHANGED'
  value.retirement = { operationId: id(5), operationVersion: value.operation.version, basis: 'NEVER_SENT', retiredAt: when }
  assert.deepEqual(readBudgetAdjustmentNotificationTarget(value, resultMessage()), value)
  const wrong = structuredClone(value); wrong.retirement.operationVersion++; assert.throws(() => readBudgetAdjustmentNotificationTarget(wrong, resultMessage()), /不一致/)
  api.inbox = async () => ({ items: [resultMessage()], unreadCount: 1, nextCursor: null }); api.budgetAdjustmentNotificationTarget = async () => value; const view = renderedInbox()
  try { await settle(); view.button('查看预算调整').props.onClick(); await settle(); assert.match(view.text(), /本次已安全结束/); assert.match(view.text(), /原指令从未发送/); assert.equal(view.button('确认预算调整授权'), undefined) } finally { view.close() }
})

test('预算调整消息 API 只执行可取消且禁止缓存的 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try { const abort = new AbortController(); await api.budgetAdjustmentNotificationTarget('original/id', abort.signal)
    assert.match(calls[0].url, /notifications\/original%2Fid\/budget-adjustment-target$/); assert.equal(calls[0].method ?? 'GET', 'GET')
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


test('重读先清除旧预算记录，失权和身份切换使迟到结果失效', async () => {
  api.budgetAdjustmentNotificationTarget = async () => target(); const panel = mount()
  try { await settle(); assert.equal(panel.state.detail.operation.status, 'UNKNOWN')
    let fail; api.budgetAdjustmentNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
    const calls = []; api.budgetAdjustmentNotificationTarget = (_id, signal) => new Promise(resolve => calls.push({ signal, resolve }))
    panel.props.message = { ...message(), id: id(7) }; panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve({ ...target(), messageId: id(7) }); await settle(); calls[0].resolve(target()); await settle(); assert.equal(panel.state.detail.messageId, id(7))
    panel.props.scopeKey = ''; assert.equal(panel.state.detail, null)
  } finally { panel.close() }
})

test('超时和卸载不能被迟到的预算结果覆盖', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.budgetAdjustmentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(target()); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
  api.budgetAdjustmentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(target()); await settle(); assert.equal(removed.state.detail, null)
})




test('真实 App 导航固定消息原轮次并服从未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})

