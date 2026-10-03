import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_SUPPLIERADJUSTMENTNOTIFICATIONDETAILPANEL)
const { readSupplierAdjustmentNotificationTarget } = await import(process.env.AGENTFLOW_TEST_SUPPLIER_ADJUSTMENT_NOTIFICATION)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_SUPPLIER_ADJUSTMENT_NOTIFICATION_NAVIGATION)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONINBOXRENDERED)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const id = n => `12345678-1234-1234-1234-${String(n).padStart(12, '0')}`
const when = '2026-10-03T12:00:00Z', settle = () => new Promise(resolve => setImmediate(resolve))
const message = () => ({ id: id(1), applicationId: id(3), title: '供应商应付调整需核对', businessNo: 'R-1', kind: 'SUPPLIER_ADJUSTMENT_ATTENTION', actor: 'system:supplier-adjustments', roundNo: 2, createdAt: when, readAt: null, content: '原应付调整事实存在争议' })
globalThis.localStorage = { getItem: () => 'test-token' }

function renderedInbox() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], text = el => all(el).map(n => n.text).join(' '), root = node('root'), opened = []
  const app = host.createApp(Inbox, { scopeKey: 'demo:alice', refreshVersion: 1, locked: false, onSupplierAdjustmentOpen: value => opened.push(value) }); app.mount(root)
  return { text: () => text(root), button: label => all(root).find(n => n.tag === 'button' && text(n).includes(label)), opened,
    close() { app.unmount(); Object.assign(api, originalApi); globalThis.fetch = originalFetch } }
}

test('真实消息中心提供原应付调整专用读取入口', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  const view = renderedInbox()
  try { await settle(); assert.ok(view.button('查看供应商应付调整')) } finally { view.close() }
})

const target = () => ({ messageId: id(1), applicationId: id(3), requestId: id(4), paymentId: id(5), adjustmentId: id(6), roundNo: 2, accountingDate: '2026-10-03', fact: 'RECONCILING',
  preparation: { version: 3, status: 'READY', issue: null, updatedAt: when }, operation: { version: 8, status: 'ADJUSTED', issue: null, updatedAt: when },
  retirement: null, completion: { adjustmentId: id(6), adjustmentVersion: 8, paymentId: id(5), paymentVersion: 4, returnVersion: 4, completedAt: when } })

test('原应付调整解析区分 ERP 成功与实际本地完成，拒绝混入另一次应付调整', () => {
  assert.equal(readSupplierAdjustmentNotificationTarget(target(), message()).fact, 'RECONCILING')
  const pending = { ...target(), fact: 'ERP_ADJUSTED', completion: null }; assert.equal(readSupplierAdjustmentNotificationTarget(pending, { ...message(), kind: 'SUPPLIER_ADJUSTMENT_RESULT' }), pending)
  for (const change of [v => v.messageId = id(8), v => v.applicationId = id(8), v => v.roundNo = 1, v => v.adjustmentId = '1-1-1-1-1',
    v => v.preparation = null, v => v.operation = [], v => delete v.completion, v => v.completion.adjustmentId = id(8), v => v.completion.paymentId = id(8),
    v => v.completion.adjustmentVersion = 9, v => v.completion.paymentVersion = 0, v => v.completion.returnVersion = 0, v => v.operation.status = 'UNKNOWN', v => v.fact = 'PENDING', v => v.fact = 'COMPLETED', v => v.accountingDate = '2026-02-30',
    v => v.actions = { retry: true }, v => v.operation.issue = 'private remote error', v => v.retirement = { basis: 'NEVER_DISPATCHED', retiredAt: when }]) {
    const value = target(); change(value); assert.throws(() => readSupplierAdjustmentNotificationTarget(value, message()), /不一致/)
  }
  const unpaid = { ...target(), fact: 'COMPLETED', completion: null }; assert.throws(() => readSupplierAdjustmentNotificationTarget(unpaid, { ...message(), kind: 'SUPPLIER_ADJUSTMENT_RESULT' }), /不一致/)
})

test('供应商应付调整消息 API 只执行可取消且禁止缓存的 GET', async () => {
  const calls = []; globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return new Response(JSON.stringify(target()), { status: 200 }) }
  try { const abort = new AbortController(); await api.supplierAdjustmentNotificationTarget('original/id', abort.signal)
    assert.match(calls[0].url, /notifications\/original%2Fid\/supplier-adjustment-target$/); assert.equal(calls[0].method ?? 'GET', 'GET')
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

test('重读先清除旧应付调整，失权和身份切换使迟到结果失效', async () => {
  api.supplierAdjustmentNotificationTarget = async () => target(); const panel = mount()
  try { await settle(); assert.equal(panel.state.detail.operation.status, 'ADJUSTED')
    let fail; api.supplierAdjustmentNotificationTarget = () => new Promise((_resolve, reject) => { fail = reject })
    const read = panel.state.load(); assert.equal(panel.state.detail, null); fail({ status: 403 }); await read
    assert.equal(panel.state.detail, null); assert.match(panel.state.error, /财务字段权限/)
    const calls = []; api.supplierAdjustmentNotificationTarget = (_id, signal) => new Promise(resolve => calls.push({ signal, resolve }))
    panel.props.message = { ...message(), id: id(7) }; panel.props.scopeKey = 'demo:finance'; assert.equal(calls[0].signal.aborted, true)
    calls[1].resolve({ ...target(), messageId: id(7) }); await settle(); calls[0].resolve(target()); await settle(); assert.equal(panel.state.detail.messageId, id(7))
    panel.props.scopeKey = ''; assert.equal(panel.state.detail, null)
  } finally { panel.close() }
})

test('超时和卸载不能被迟到的完成事实覆盖', async () => {
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout; let expire, finish, signal
  globalThis.setTimeout = (fn, duration) => { assert.equal(duration, 12000); expire = fn; return 1 }; globalThis.clearTimeout = () => {}
  api.supplierAdjustmentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const panel = mount()
  try { expire(); await settle(); assert.equal(signal.aborted, true); assert.match(panel.state.error, /超时/)
    finish(target()); await settle(); assert.equal(panel.state.detail, null)
  } finally { panel.close(); globalThis.setTimeout = set; globalThis.clearTimeout = clear }
  api.supplierAdjustmentNotificationTarget = (_id, current) => { signal = current; return new Promise(resolve => { finish = resolve }) }
  const removed = mount(); removed.close(); assert.equal(signal.aborted, true); finish(target()); await settle(); assert.equal(removed.state.detail, null)
})

test('真实模板分别显示消息事实、当前 ERP 调整和本地完成，不提供办理动作', async () => {
  for (const complete of [false, true]) {
    api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null }); let reads = 0
    api.supplierAdjustmentNotificationTarget = async () => { reads++; return { ...target(), completion: complete ? target().completion : null } }
    const view = renderedInbox()
    try { await settle(); assert.equal(reads, 0); view.button('查看供应商应付调整').props.onClick(); await settle(); assert.equal(reads, 1)
      assert.match(view.text(), /原 ERP 调整事实存在矛盾/); assert.match(view.text(), /当前原应付调整状态/); assert.match(view.text(), /ERP 已确认调整/)
      assert.match(view.text(), complete ? /原本地账务已完成/ : /本次尚无本地账务完成记录/)
      for (const label of ['重试调整', '安全结束本次调整', '登记应付应付调整']) assert.equal(view.button(label), undefined)
      view.button('查看原申请轮次').props.onClick(); assert.equal(view.opened[0].roundNo, 2); assert.equal(reads, 1)
    } finally { view.close() }
  }
})

test('旧失败准备无 ERP 操作，来源错误后隐藏导航', async () => {
  api.inbox = async () => ({ items: [message()], unreadCount: 1, nextCursor: null })
  api.supplierAdjustmentNotificationTarget = async () => ({ ...target(), fact: 'PREPARATION_BLOCKED', preparation: { version: 3, status: 'BLOCKED', issue: 'ACCOUNTING_PERIOD_REJECTED', updatedAt: when }, operation: null, completion: null })
  const view = renderedInbox()
  try { await settle(); view.button('查看供应商应付调整').props.onClick(); await settle(); assert.match(view.text(), /本次尚未登记 ERP 调整指令/)
    api.supplierAdjustmentNotificationTarget = async () => ({ ...target(), applicationId: id(8) }); view.button('重新读取').props.onClick(); await settle()
    assert.match(view.text(), /不一致/); assert.equal(view.button('查看原申请轮次'), undefined)
  } finally { view.close() }
})
test('真实 App 导航固定消息原轮次并服从未完成写入锁', () => {
  const deps = { busy: { value: false }, writesBlocked: { value: false }, recordApplicationId: { value: null }, recordInitialRoundNo: { value: null } }
  const open = createNavigation(deps); open(target()); assert.equal(deps.recordApplicationId.value, id(3)); assert.equal(deps.recordInitialRoundNo.value, 2)
  for (const key of ['busy', 'writesBlocked']) { deps.recordApplicationId.value = null; deps[key].value = true; open(target()); assert.equal(deps.recordApplicationId.value, null); deps[key].value = false }
})


test('后续查询及争议保留该次实际完成，安全结束必须有自身记录', () => {
  const value = target(); value.operation.version = 9; value.operation.status = 'RECONCILING'; value.operation.issue = 'INCONSISTENT_OBSERVATION'
  assert.equal(readSupplierAdjustmentNotificationTarget(value, message()), value)
  const retired = { ...target(), fact: 'RETIRED', completion: null, operation: { version: 8, status: 'REJECTED', issue: null, updatedAt: when }, retirement: { basis: 'CONFIRMED_REJECTED', retiredAt: when } }
  assert.equal(readSupplierAdjustmentNotificationTarget(retired, { ...message(), kind: 'SUPPLIER_ADJUSTMENT_RESULT' }), retired)
  assert.throws(() => readSupplierAdjustmentNotificationTarget({ ...retired, retirement: null }, { ...message(), kind: 'SUPPLIER_ADJUSTMENT_RESULT' }), /不一致/)
})
