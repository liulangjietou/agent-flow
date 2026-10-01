import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_TIMER_WAITS)
const { default: Definition } = await import(process.env.AGENTFLOW_TEST_TIMER_DEFINITION)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_TIMER_PANEL)
const { render: recordBinding } = await import(process.env.AGENTFLOW_TEST_TIMER_RECORD)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const { parsePortableTemplate, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const { editQuickGraph, projectQuickGraph } = await import(process.env.AGENTFLOW_TEST_QUICK)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = globalThis.fetch
globalThis.localStorage = { getItem: () => 'timer-test-token', setItem() {}, removeItem() {} }
const settle = () => new Promise(resolve => setImmediate(resolve))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const entry = () => ({ jobId: 'wait-job', nodeId: 'wait', nodeName: '定时等待', executionId: 'execution', dueAt: '2026-01-01T00:00:00Z', state: 'FAILED', errorCode: 'DEPENDENCY_UNAVAILABLE', canRetry: true })
const view = () => ({ applicationId: 'application', roundNo: 1, applicationVersion: 3, items: [entry()] })
const input = () => ({ expectedVersion: 3, reason: '配置已修复' })
const receipt = () => ({ applicationId: 'application', roundNo: 1, applicationVersion: 4, jobId: 'wait-job', nodeId: 'wait', applicationStatus: 'IN_APPROVAL' })
function mount(Component, props, handlers = {}) {
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, ...handlers })
  const instance = app.mount({})
  return { state: instance.$.setupState, close: () => { app.unmount(); Object.assign(api, originalApi) } }
}
function panelProps() { return reactive({ applicationId: 'application', roundNo: 1, version: 3, scopeKey: 'demo:admin', locked: false }) }

test('等待配置不补默认值或改写非法原文，锁定时不修改', () => {
  const values = [], props = reactive({ modelValue: undefined, disabled: false })
  const p = mount(Definition, props, { 'onUpdate:modelValue': value => values.push(value) })
  try {
    assert.deepEqual(values, [])
    for (const value of ['', '01', '60', '${secret}']) p.state.change({ target: { value } })
    assert.deepEqual(values, ['', '01', '60', '${secret}'])
    props.disabled = true; p.state.change({ target: { value: '3600' } }); assert.equal(values.length, 4)
  } finally { p.close() }
})

test('等待节点在设计器、简洁步骤和模板往返时保留明确时长', () => {
  const nodes = [{ id: 'start', name: '开始', type: 'START', properties: {} },
    { id: 'review', name: '审批', type: 'USER_TASK', properties: { assigneeRule: 'user:finance' } },
    { id: 'wait', name: '等待', type: 'TIMER_WAIT', properties: { timerDelaySeconds: '60' } },
    { id: 'end', name: '结束', type: 'END', properties: {} }]
  const edges = ['start>review', 'review>wait', 'wait>end'].map((value, i) => ({ id: 'e' + i, source: value.split('>')[0], target: value.split('>')[1], condition: '', defaultBranch: false }))
  const graph = { nodes, edges }, loaded = loadDesignerNodes(nodes)
  assert.deepEqual(serializeDesignerNodes(loaded), nodes)
  loaded[2].timerDelaySeconds = '3600'; assert.equal(serializeDesignerNodes(loaded)[2].properties.timerDelaySeconds, '3600')
  assert.ok(projectQuickGraph(graph).sequence)
  let sequence = 0
  const inserted = editQuickGraph(graph, { kind: 'insert', edgeId: 'e0', type: 'TIMER_WAIT' }, () => 'new' + ++sequence)
  assert.equal(inserted.nodes.filter(node => node.type === 'TIMER_WAIT').length, 2)
  assert.equal(inserted.nodes.find(node => node.type === 'TIMER_WAIT' && node.id !== 'wait').properties.timerDelaySeconds, undefined)
  const exported = serializePortableTemplate({ key: 'timer-test', name: '等待验收', graph, formSchema: null })
  assert.deepEqual(parsePortableTemplate(exported).graph.nodes, nodes)
})

test('重试只绑定失败任务、当前版本和明确原因，不允许提前执行或替换到期时间', () => {
  assert.deepEqual(rules.timerRetryInput(view(), 'wait-job', ' 配置已修复 '), input())
  for (const reason of ['', ' ', 'a'.repeat(2001)]) assert.throws(() => rules.timerRetryInput(view(), 'wait-job', reason))
  for (const state of ['WAITING', 'SUSPENDED']) assert.throws(() => rules.timerRetryInput({ ...view(), items: [{ ...entry(), state }] }, 'wait-job', '原因'))
  assert.throws(() => rules.timerRetryInput({ ...view(), items: [{ ...entry(), canRetry: false }] }, 'wait-job', '原因'))
  assert.throws(() => rules.timerRetryInput(view(), 'another-job', '原因'))
})

test('轮次和身份切换取消旧读取，迟到结果与错误能力投影不能重新开放重试', async () => {
  let finish, signal
  const query = new rules.TimerWaitQuery((_id, _round, current) => { signal = current; return new Promise(resolve => { finish = resolve }) })
  const pending = query.load('demo:admin', 'application', 1)
  query.clear(); assert.equal(signal.aborted, true); finish(view()); await pending
  assert.equal(query.value, null)
  for (const change of [v => { v.roundNo = 2 }, v => { v.items.push(entry()) }, v => { v.items[0].state = 'WAITING' }, v => { v.items[0].dueAt = 'bad' }]) {
    const value = view(); change(value)
    const invalid = new rules.TimerWaitQuery(async () => value); await invalid.load('demo:admin', 'application', 1)
    assert.equal(invalid.value, null); assert.ok(invalid.error)
  }
})

test('重试回执必须绑定原申请、轮次、任务及实际状态版本', () => {
  rules.validateTimerReceipt(receipt(), 'application', 1, 'wait-job', input())
  rules.validateTimerReceipt({ ...receipt(), applicationStatus: 'APPROVED', applicationVersion: 5 }, 'application', 1, 'wait-job', input())
  for (const update of [{ applicationId: 'other' }, { roundNo: 2 }, { jobId: 'new-job' }, { nodeId: '' }, { applicationVersion: 3 }, { applicationStatus: 'APPROVED' }]) {
    assert.throws(() => rules.validateTimerReceipt({ ...receipt(), ...update }, 'application', 1, 'wait-job', input()), e => e.code === 'RESPONSE_UNREADABLE')
  }
})

test('实际面板先确认原因，取消与锁定均不发送，成功后才通知父页面', async () => {
  const sent = [], changed = [], props = panelProps()
  api.timerWaits = async () => view(); api.retryTimer = async (...args) => { sent.push(args); return receipt() }
  const p = mount(Panel, props, { onChanged: () => changed.push(true) })
  try {
    await settle(); p.state.prepare(entry()); assert.equal(sent.length, 0)
    p.state.cancel(); await p.state.retry(); assert.equal(sent.length, 0)
    p.state.prepare(entry()); await p.state.retry(); assert.equal(sent.length, 0); assert.ok(p.state.error)
    p.state.reason = '配置已修复'; props.locked = true; await p.state.retry(); assert.equal(sent.length, 0)
    props.locked = false; await p.state.retry()
    assert.deepEqual(sent, [['application', 1, 'wait-job', input()]]); assert.equal(changed.length, 1)
  } finally { p.close() }
})

test('切换申请后不把旧重试响应显示为新申请成功', async () => {
  let finish; const changed = [], props = panelProps()
  api.timerWaits = async (id, round) => ({ ...view(), applicationId: id, roundNo: round })
  api.retryTimer = () => new Promise(resolve => { finish = resolve })
  const p = mount(Panel, props, { onChanged: () => changed.push(true) })
  try {
    await settle(); p.state.prepare(entry()); p.state.reason = '配置已修复'; const pending = p.state.retry()
    props.applicationId = 'new-application'; await settle(); finish(receipt()); await pending
    assert.equal(changed.length, 0); assert.equal(p.state.selected, ''); assert.equal(p.state.query.value.applicationId, 'new-application')
  } finally { p.close() }
})

test('等待读取不缓存，网络结果未知时按原键原正文恢复', async () => {
  const calls = []; writeRequests.setActor({ tenantId: 'demo', userId: 'timer-lost' })
  try {
    globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); if (init.method === 'POST' && calls.length === 2) throw new Error('lost'); return Response.json(init.method === 'POST' ? receipt() : view()) }
    const signal = new AbortController().signal; await api.timerWaits('application/encoded', 1, signal)
    assert.ok(calls[0].url.endsWith('/applications/application%2Fencoded/rounds/1/timers')); assert.equal(calls[0].cache, 'no-store')
    assert.equal(calls[0].signal, signal); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
    await assert.rejects(api.retryTimer('application', 1, 'wait-job', input()))
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls[1].body, calls[2].body); assert.equal(calls[1].headers.get('Idempotency-Key'), calls[2].headers.get('Idempotency-Key'))
    assert.equal(writeRequests.pending().length, 0)
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('HTTP 200 串单回执保留恢复槽，禁止改原因后重新发送', async () => {
  const calls = []; writeRequests.setActor({ tenantId: 'demo', userId: 'timer-unreadable' })
  try {
    globalThis.fetch = async (_url, init) => { calls.push(init); return Response.json({ ...receipt(), ...(calls.length === 1 ? { roundNo: 2 } : {}) }) }
    await assert.rejects(api.retryTimer('application', 1, 'wait-job', input()), e => e.code === 'RESPONSE_UNREADABLE')
    assert.equal(writeRequests.pending().length, 1)
    await assert.rejects(api.retryTimer('application', 1, 'wait-job', { ...input(), reason: '改原因' }), e => e.code === 'PENDING_REQUEST_CHANGED')
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].headers.get('Idempotency-Key'), calls[1].headers.get('Idempotency-Key'))
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('真实申请详情事件绑定会重读当前状态和轮次，不能只刷新列表', async () => {
  let diagram, loads = 0, changes = 0
  const app = renderer.createApp({
    components: { RoundDiagram: { setup(_props, { attrs }) { diagram = attrs; return () => null } } },
    setup: () => ({ application: { id: 'application', version: 3 }, rounds: [], scopeKey: 'demo:admin', writesBlocked: false,
      load: async () => { loads++ }, emit: () => { changes++ } }), render: recordBinding
  })
  app.mount({})
  try { await diagram.onChanged(); assert.equal(loads, 1); assert.equal(changes, 1) }
  finally { app.unmount() }
})

test('父页面恢复等待回执时刷新对应申请，不把回执当成申请详情', async () => {
  const originalDocument = globalThis.document; globalThis.document = { querySelector: () => null }
  const box = value => ({ value }), pending = { id: 'original', path: '/applications/application/rounds/1/timers/wait-job/retry', sending: false }
  const env = { pendingWrites: box([pending]), draftScope: box(''), confirmReplaceDefinition: async (_label, action) => action(),
    busy: box(false), recoveryError: box(''), writeRequests: { recover: async () => ({ request: pending, result: receipt() }) },
    notice: box(''), createdApplication: box(null), newApplicationOpen: box(false), recordApplicationId: box('application'),
    activeTask: box(null), clearTaskSelection: () => {}, recordRefresh: box(0), statusLabel: value => value, refreshWorkspace: async () => {}, nextTick: async () => {}, workspace: box(null), errorMessage: e => e.message }
  try {
    await createRecovery(env)('original')
    assert.equal(env.recordRefresh.value, 1); assert.equal(env.recoveryError.value, '')
    assert.ok(!env.notice.value.includes('undefined')); assert.equal(env.busy.value, false)
  } finally { globalThis.document = originalDocument }
})
