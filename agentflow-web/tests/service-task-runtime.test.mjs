import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const runtime = await import(process.env.AGENTFLOW_TEST_SERVICE_TASK_RUNTIME)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_SERVICETASKRUNTIMEPANELPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_SERVICETASKRUNTIMEPANELRENDERED)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, APP = '00000000-0000-0000-0000-000000000001'
const id = number => '00000000-0000-0000-0000-' + String(number).padStart(12, '0')
const copy = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const entry = (number = 2, changes = {}) => ({ id: id(number), nodeId: 'service', nodeName: '登记申请', operationKey: 'receipt.register', operationVersion: '9223372036854775807', operationName: '登记服务', version: '1', status: 'QUEUED', progress: 'PENDING', attempts: 0, createdAt: '2026-10-03T12:00:00Z', updatedAt: '2026-10-03T12:00:00Z', configurationAvailable: true, ...changes })
const page = (changes = {}) => ({ applicationId: APP, applicationVersion: 2, roundNo: 1, roundStatus: 'IN_APPROVAL', items: [entry()], ...changes })
const values = () => reactive({ applicationId: APP, version: 2, scopeKey: 'demo:alice', rounds: [{ roundNo: 1 }], initialRoundNo: null })

test('运行 API 使用精确轮次、只读请求、取消信号和禁止缓存，并核对返回身份', async () => {
  const savedFetch = globalThis.fetch, savedStorage = globalThis.localStorage, calls = [], controller = new AbortController()
  globalThis.localStorage = { getItem: () => null }
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response(JSON.stringify(page({ items: [entry(3)] })), { status: 200 }) }
  try {
    const value = await api.serviceTaskRuntime(APP, 1, id(2), controller.signal)
    assert.equal(value.items[0].id, id(3)); assert.match(calls[0].url, new RegExp(`/applications/${APP}/rounds/1/service-tasks\\?`))
    assert.match(calls[0].url, /limit=25/); assert.match(calls[0].url, new RegExp('afterId=' + id(2)))
    assert.equal(calls[0].options.method ?? 'GET', 'GET'); assert.equal(calls[0].options.cache, 'no-store')
    assert.equal(calls[0].options.signal, controller.signal); assert.equal(calls[0].options.headers.has('Idempotency-Key'), false)
    globalThis.fetch = async () => new Response(JSON.stringify(page({ applicationId: id(9) })), { status: 200 })
    await assert.rejects(api.serviceTaskRuntime(APP, 1, undefined, controller.signal), /不一致/)
  } finally { globalThis.fetch = savedFetch; if (savedStorage === undefined) delete globalThis.localStorage; else globalThis.localStorage = savedStorage }
})
function remove(node) { const children = node.parent?.children; if (children) { const index = children.indexOf(node); if (index >= 0) children.splice(index, 1) } }
const renderer = createRenderer({
  createElement: tag => ({ tag, children: [], props: {}, get options() { return this.children.filter(child => child.tag === 'option') }, addEventListener() {}, removeEventListener() {} }),
  createText: text => ({ text }), createComment: () => ({}), setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text; node.children = [] },
  insert(node, parent, anchor = null) { remove(node); parent.children ??= []; const index = parent.children.indexOf(anchor); if (index < 0) parent.children.push(node); else parent.children.splice(index, 0, node); node.parent = parent },
  remove, parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  patchProp: (node, key, previous, value) => { node.props[key] = value; if (key === 'value') node._value = value },
})
const descendants = node => [node, ...(node.children ?? []).flatMap(descendants)]
const text = node => descendants(node).map(child => child.text ?? '').join(' ')
function mount(props = values(), rendered = false) {
  const changes = [], root = { children: [] }, Component = rendered ? Rendered : Panel
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), ...(rendered ? {} : { render: () => null }) }, { ...props, onChanged: () => changes.push(true) })
  const instance = app.mount(root)
  return { props, root, changes, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals) } }
}

test('运行响应绑定申请轮次并保留完整版本，未知字段和不一致终态拒绝显示', () => {
  assert.equal(runtime.readServiceTaskRuntime(page(), APP, 1).items[0].operationVersion, '9223372036854775807')
  for (const bad of [page({ applicationId: id(9) }), page({ roundNo: 2 }), page({ inputs: { secret: 'private' } }),
    page({ items: [entry(2, { operationVersion: 1 })] }), page({ items: [entry(2, { input: 'private' })] }),
    page({ items: [entry(), entry()] }), page({ items: [entry(2, { progress: 'ADVANCED' })] }),
    page({ items: [entry(2, { status: 'APPLIED' })] }), page({ nextAfterId: id(3) })]) {
    assert.throws(() => runtime.readServiceTaskRuntime(bad, APP, 1))
  }
  const full = page({ items: Array.from({ length: 25 }, (_, index) => entry(index + 2)), nextAfterId: id(26) })
  assert.equal(runtime.readServiceTaskRuntime(full, APP, 1).nextAfterId, id(26))
  assert.throws(() => runtime.readServiceTaskRuntime(full, APP, 1, id(2)))
  assert.throws(() => runtime.readServiceTaskRuntime(page({ items: Array.from({ length: 26 }, (_, index) => entry(index + 2)) }), APP, 1))
  const longNode = page({ items: [entry(2, { nodeName: '原节点名称'.repeat(60) })] })
  assert.equal(runtime.readServiceTaskRuntime(longNode, APP, 1), longNode)
})

test('未知结果、外部成功和原轮次结束显示不同结论，不把服务完成显示成人工批准', () => {
  const unknown = entry(2, { status: 'UNKNOWN', attempts: 1, failure: 'TIMEOUT' })
  assert.equal(runtime.serviceTaskState(unknown, 'IN_APPROVAL'), '结果待确认')
  assert.match(runtime.serviceTaskExplanation(unknown, 'WITHDRAWN'), /原操作编号/)
  const applied = entry(2, { status: 'APPLIED', attempts: 1, completedAt: '2026-10-03T12:00:00Z' })
  assert.match(runtime.serviceTaskState(applied, 'IN_APPROVAL'), /等待流程继续/)
  assert.match(runtime.serviceTaskState(applied, 'WITHDRAWN'), /原轮次已结束/)
  assert.match(runtime.serviceTaskState({ ...applied, progress: 'STALE' }, 'WITHDRAWN'), /原轮次已结束/)
  assert.match(runtime.serviceTaskExplanation(applied, 'WITHDRAWN'), /不会推进新轮次/)
  assert.match(runtime.serviceTaskExplanation({ ...applied, progress: 'ADVANCED' }, 'IN_APPROVAL'), /审批人/)
  assert.match(runtime.serviceTaskExplanation(entry(2, { configurationAvailable: false }), 'IN_APPROVAL'), /恢复/)
})

test('账号切换会清空旧记录并取消读取，迟到响应不能写回新账号', async () => {
  const first = deferred(), second = deferred(), calls = []
  api.serviceTaskRuntime = (application, round, after, signal) => { calls.push(signal); return calls.length === 1 ? first.promise : second.promise }
  const panel = mount()
  try {
    panel.props.scopeKey = 'other:reader'; await settle(); assert.equal(calls[0].aborted, true); assert.equal(panel.state.items.length, 0)
    second.resolve(page({ items: [entry(8)] })); await settle(); assert.equal(panel.state.items[0].id, id(8))
    first.resolve(page()); await settle(); assert.equal(panel.state.items[0].id, id(8))
  } finally { panel.close() }
})

test('轮次切换取消旧读取；注销和卸载都清除可见数据', async () => {
  const pending = deferred(); let oldSignal
  api.serviceTaskRuntime = async (application, round, after, signal) => {
    if (round === 1) { oldSignal = signal; return pending.promise }
    return page({ roundNo: round, roundStatus: 'WITHDRAWN', items: [entry(8)] })
  }
  const props = values(); props.rounds = [{ roundNo: 1 }, { roundNo: 2 }]; props.initialRoundNo = 1
  const panel = mount(props)
  try {
    panel.state.selectedRound = 2; await settle(); assert.equal(oldSignal.aborted, true); assert.equal(panel.state.roundStatus, 'WITHDRAWN')
    pending.resolve(page()); await settle(); assert.equal(panel.state.items[0].id, id(8))
    props.scopeKey = ''; await settle(); assert.equal(panel.state.items.length, 0); assert.equal(panel.state.reader.value, null)
  } finally { panel.close() }
})

test('分页沿用当前轮次，发生申请版本变化时重读首页而不混合快照', async () => {
  const first = page({ items: [entry(2)], nextAfterId: id(2) }), calls = []
  api.serviceTaskRuntime = async (application, round, after) => { calls.push(after); return calls.length === 1 ? first : page({ applicationVersion: 3, items: [entry(after ? 3 : 4)] }) }
  const panel = mount()
  try {
    await settle(); await panel.state.load(true); await settle()
    assert.deepEqual(calls, [undefined, id(2), undefined]); assert.deepEqual(panel.state.items.map(item => item.id), [id(4)])
  } finally { panel.close() }
})

test('权限读取失败不保留旧记录，显式刷新较新版本通知详情重新加载', async () => {
  api.serviceTaskRuntime = async () => page()
  const panel = mount()
  try {
    await settle(); api.serviceTaskRuntime = async () => { throw { status: 404 } }
    await panel.state.load(false, true); assert.equal(panel.state.items.length, 0); assert.match(panel.state.reader.error, /不可访问/)
    api.serviceTaskRuntime = async () => page({ applicationVersion: 3 })
    await panel.state.load(false, true); assert.deepEqual(panel.changes, [true])
  } finally { panel.close() }
})

test('真实组件模板展示轮次、状态和原操作编号，草稿不发送零轮次请求', async () => {
  let reads = 0
  api.serviceTaskRuntime = async () => { reads++; return page({ items: [entry(2, { status: 'UNKNOWN', attempts: 1, failure: 'TIMEOUT' })] }) }
  const panel = mount(values(), true)
  try {
    await settle(); assert.match(text(panel.root), /结果待确认/); assert.match(text(panel.root), /原操作编号/)
    assert.match(text(panel.root), /9223372036854775807/); assert.match(text(panel.root), new RegExp(id(2)))
    const refresh = descendants(panel.root).find(node => node.tag === 'button' && text(node).includes('刷新运行记录'))
    api.serviceTaskRuntime = async () => { reads++; return page({ roundStatus: 'WITHDRAWN', items: [entry(2, { status: 'APPLIED', attempts: 1, completedAt: '2026-10-03T12:00:00Z' })] }) }
    refresh.props.onClick(); await settle(); assert.equal(reads, 2)
    assert.match(text(panel.root), /原轮次已结束/); assert.doesNotMatch(text(panel.root), /等待流程继续/)
    panel.props.rounds = []; await settle(); assert.match(text(panel.root), /尚未提交/); assert.equal(reads, 2)
  } finally { panel.close() }
})
