import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { SubprocessRelationsQuery } = await import(process.env.AGENTFLOW_TEST_SUBPROCESS_RELATIONS)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_RELATIONS_PANEL)
const { default: Record } = await import(process.env.AGENTFLOW_TEST_APPLICATION_RECORD)
const { default: RenderedRecord } = await import(process.env.AGENTFLOW_TEST_RECORD_RENDERED)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { render } = await import(process.env.AGENTFLOW_TEST_RELATIONS_RENDER)
const settle = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((ok, no) => { resolve = ok; reject = no }); return { promise, resolve, reject } }
const reference = (applicationId = 'child', roundNo = 1) => ({ applicationId, roundNo, businessNo: 'CHILD', title: '子申请', processKey: 'child', definitionVersion: 1, status: 'IN_APPROVAL' })
const child = (id, target = reference()) => ({ id, nodeId: 'call', nodeName: '子核对', createdAt: '2026-10-01T12:00:00Z', target })
const page = (children = [], nextAfterId = null, roundNo = 1) => ({ applicationId: 'parent', roundNo, observedAt: '2026-10-01T12:00:00Z', childApplication: false, parent: null, children, nextAfterId })
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(Component, props, events) {
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, ...events })
  const vm = app.mount({}); return { state: vm.$.setupState, close: () => app.unmount() }
}

test('逐页保留准确轮次，拒绝错轮、重复记录和不前进的游标', async () => {
  const calls = []; let response = page([child('one', null)], 'one')
  const query = new SubprocessRelationsQuery(async (...args) => { calls.push(args); return response })
  await query.load('demo/alice', 'parent', 1)
  response = page([child('two')]); await query.more()
  assert.deepEqual(query.value.children.map(item => item.id), ['one', 'two'])
  assert.equal(query.value.children[0].target, null); assert.equal(calls[1][2], 'one')
  response = page([child('one')], 'one'); await query.load('demo/alice', 'parent', 1)
  await query.more(); assert.equal(query.value, null); assert.match(query.error, /一致|重复/)
  response = { ...page(), applicationId: 'other' }; await query.load('demo/alice', 'parent', 1); assert.equal(query.value, null)
  response = page([], null, 2); await query.load('demo/alice', 'parent', 1); assert.equal(query.value, null)
  query.clear()
})

test('轮次与身份切换取消旧分页，迟到的目标和错误不能回填', async () => {
  const requests = [], query = new SubprocessRelationsQuery((id, round, after, signal) => {
    const item = { id, round, after, signal, ...deferred() }; requests.push(item); return item.promise
  })
  const first = query.load('demo/alice', 'parent', 1); requests[0].resolve(page([child('one')], 'one')); await first
  const more = query.more(); const next = query.load('demo/manager', 'parent', 2)
  assert.equal(requests[1].signal.aborted, true)
  requests[2].resolve(page([child('new', null)], null, 2)); await next
  requests[1].resolve(page([child('old-private')])); await more
  assert.deepEqual(query.value.children.map(item => item.id), ['new']); assert.equal(query.value.children[0].target, null)
  const closed = query.load('demo/manager', 'parent', 2); query.clear(); requests[3].reject({ message: 'old' }); await closed
  assert.equal(query.value, null); assert.equal(query.error, '')
})

test('权限变化或分页超时清除旧链接，重试不继续使用旧游标', async () => {
  let fail = false
  const query = new SubprocessRelationsQuery(async () => { if (fail) throw { status: 403 }; return page([child('one')], 'one') })
  await query.load('demo/alice', 'parent', 1); fail = true; await query.more()
  assert.equal(query.value, null); assert.match(query.error, /无权/)
  const originalTimeout = globalThis.setTimeout, pending = [], callbacks = []
  globalThis.setTimeout = callback => { callbacks.push(callback); return 654 }
  try {
    const timed = new SubprocessRelationsQuery((id, round, after, signal) => { const item = { signal, ...deferred() }; pending.push(item); return item.promise })
    const running = timed.load('demo/alice', 'parent', 1); callbacks[0](); await running
    assert.equal(pending[0].signal.aborted, true); assert.equal(timed.loading, false); assert.equal(timed.value, null)
    pending[0].resolve(page([child('late')])); await settle(); assert.equal(timed.value, null); timed.clear()
  } finally { globalThis.setTimeout = originalTimeout; query.clear() }
})

test('真实面板按关联原轮次读取，只导航当前已授权目标且遵守编辑锁', async () => {
  const original = api.subprocessRelations, navigated = [], requested = []
  api.subprocessRelations = async (id, round) => { requested.push(round); return page([child('call')], null, round) }
  const props = reactive({ applicationId: 'parent', scopeKey: 'demo/alice', version: 3, initialRoundNo: 1,
    rounds: [{ roundNo: 2, status: 'IN_APPROVAL' }, { roundNo: 1, status: 'WITHDRAWN' }], locked: false })
  const mounted = mount(Panel, props, { onOpen: value => navigated.push(value) })
  try {
    await settle(); assert.equal(mounted.state.selectedRound, 1); assert.ok(requested.every(round => round === 1))
    const target = mounted.state.query.value.children[0].target
    mounted.state.open(null); mounted.state.open({ ...target }); assert.equal(navigated.length, 0)
    props.locked = true; mounted.state.open(target); assert.equal(navigated.length, 0)
    props.locked = false; mounted.state.open(target); assert.deepEqual(navigated[0], reference())
    mounted.state.selectedRound = 2; await settle(); mounted.state.open(target); assert.equal(navigated.length, 1)
  } finally { mounted.close(); api.subprocessRelations = original }
})

test('申请详情不因打开关联丢弃未保存字段、上传或结果未知的操作', async () => {
  const original = { ...api }, priorDocument = globalThis.document, navigated = []
  globalThis.document = { activeElement: null }
  api.application = async () => ({ id: 'parent', processKey: 'parent', definitionVersion: 1, createdBy: 'alice', status: 'DRAFT', title: '原标题', payload: {}, version: 1, roundNo: 0 })
  api.applicationRounds = async () => []
  api.applicationInitiatorRequirements = async () => ({ processKey: 'parent', definitionVersion: 1, appointmentRequired: false })
  const props = reactive({ applicationId: 'parent', userId: 'alice', scopeKey: 'demo/alice', commentRefreshVersion: 0, pendingWrites: [], recoveryError: '' })
  const mounted = mount(Record, props, { onOpenRelated: target => navigated.push(target) })
  try {
    await settle(); mounted.state.title = '尚未保存'; mounted.state.openRelated(reference()); assert.equal(navigated.length, 0)
    mounted.state.title = '原标题'; mounted.state.uploading = true; mounted.state.openRelated(reference()); assert.equal(navigated.length, 0)
    mounted.state.uploading = false; props.pendingWrites = [{}]; mounted.state.openRelated(reference()); assert.equal(navigated.length, 0)
    props.pendingWrites = []; mounted.state.openRelated(reference('original', 1)); assert.equal(navigated[0].roundNo, 1)
  } finally { mounted.close(); Object.assign(api, original); globalThis.document = priorDocument }
})

test('只读 API 编码申请和游标，不发送幂等键或业务内容', async () => {
  const previousFetch = globalThis.fetch, storage = globalThis.localStorage; let sent
  globalThis.localStorage = { getItem: () => 'synthetic' }
  globalThis.fetch = async (url, options) => { sent = { url, options }; return Response.json(page()) }
  try {
    const signal = new AbortController().signal
    await api.subprocessRelations('app/one', 2, 'cursor/one', signal)
    assert.equal(sent.url, '/api/v1/applications/app%2Fone/rounds/2/subprocesses?limit=30&afterId=cursor%2Fone')
    assert.equal(sent.options.cache, 'no-store'); assert.equal(sent.options.signal, signal)
    assert.equal(sent.options.body, undefined); assert.equal(new Headers(sent.options.headers).has('Idempotency-Key'), false)
  } finally { globalThis.fetch = previousFetch; globalThis.localStorage = storage }
})

test('关联进入旧轮次时，真实详情完成渲染后展开并滚动到原轮次', async () => {
  const original = { ...api }, priorDocument = globalThis.document, scrolled = []
  globalThis.document = { activeElement: null }
  const find = (el, round) => String(el.props['data-round-no']) === round ? el : el.children.map(child => find(child, round)).find(Boolean)
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null,
    addEventListener() {}, removeEventListener() {}, focus() {},
    querySelector(selector) { return find(this, selector.match(/data-round-no="(\d+)"/)?.[1]) ?? null },
    scrollIntoView() { scrolled.push(this.props['data-round-no']) } })
  const remove = child => { if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1); child.parent = null }
  const host = createRenderer({
    createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
    patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (child, parent, anchor = null) => { remove(child); child.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, child) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null
  })
  api.application = async () => ({ id: 'parent', processKey: 'parent', definitionVersion: 1, createdBy: 'alice', status: 'APPROVED', title: '当前标题', payload: {}, version: 5, roundNo: 2 })
  api.applicationRounds = async () => [1, 2].map(roundNo => ({ roundNo, status: roundNo === 1 ? 'WITHDRAWN' : 'APPROVED', title: `原轮次 ${roundNo}`, payload: {}, submittedAt: '2026-10-01T12:00:00Z' }))
  const root = node('root'), app = host.createApp(RenderedRecord, {
    applicationId: 'parent', initialRoundNo: 1, userId: 'alice', scopeKey: 'demo/alice', commentRefreshVersion: 0, pendingWrites: [], recoveryError: ''
  })
  try {
    app.mount(root); await settle()
    assert.equal(find(root, '1').props.open, true)
    assert.equal(find(root, '2').props.open, false)
    assert.deepEqual(scrolled, [1], '必须在 loading 结束并挂载轮次节点后定位')
  } finally { app.unmount(); Object.assign(api, original); globalThis.document = priorDocument }
})

test('真实模板不生成未授权目标入口，并由加载更多按钮实际读取下一页', async () => {
  let reads = 0
  const query = new SubprocessRelationsQuery(async () => ++reads === 1 ? page([child('one', null)], 'one') : page([child('two')]))
  await query.load('demo/alice', 'parent', 1)
  const context = { query, rounds: [{ roundNo: 1 }], sorted: [{ roundNo: 1 }], selectedRound: 1, locked: false, labels: {}, time: value => value, open() {}, load() {} }
  const flatten = node => !node || typeof node !== 'object' ? [] : [node, ...(Array.isArray(node.children) ? node.children.flatMap(flatten) : [])]
  const nodes = flatten(render(context, [])), buttons = nodes.filter(node => node.type === 'button')
  assert.equal(buttons.filter(node => node.props?.['aria-label']?.startsWith('打开 ')).length, 0)
  const button = buttons.find(node => node.children === '加载更多子调用')
  assert.ok(button); await button.props.onClick(); await settle()
  assert.equal(reads, 2); assert.equal(query.value.children.length, 2); query.clear()
})
