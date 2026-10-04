import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_COUNTERSIGN)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_COUNTERSIGN_PANEL)
const { createWorkflow } = await import(process.env.AGENTFLOW_TEST_COUNTERSIGN_WORKSPACE)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const original = { ...api }, originalFetch = globalThis.fetch
globalThis.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const task = () => ({ taskId: 'source', applicationId: 'application', assignee: 'finance', version: 2, delegationState: 'NONE', countersign: { total: 2, completed: 0 } })
const view = () => ({ taskId: 'source', applicationId: 'application', roundNo: 1, applicationVersion: 2, processInstanceId: 'process', nodeId: 'review', executionId: 'scope', originalMembers: ['admin', 'finance'], total: 2, completed: 0, pending: [{ taskId: 'source', user: 'finance', assignee: 'finance', delegated: false, canRemove: false }, { taskId: 'other', user: 'admin', assignee: 'admin', delegated: false, canRemove: true }], completedUsers: [], canChange: true, canAdd: true, additions: ['bob', 'manager'] })
const receipt = () => ({ taskId: 'source', applicationId: 'application', roundNo: 1, applicationVersion: 3, processInstanceId: 'process', nodeId: 'review', executionId: 'scope', action: 'ADD', targetTaskId: 'new', targetUser: 'bob', totalBefore: 2, totalAfter: 3, completed: 0, auditEventId: 'event' })
const input = () => ({ action: 'ADD', targetUser: 'bob', reason: '独立复核', expectedVersion: 2 })
const settle = () => new Promise(resolve => setImmediate(resolve))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount() {
  const props = reactive({ task: task(), scopeKey: 'demo:finance', locked: false }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null },
    { ...props, onExecute: (...args) => events.push(args) })
  const instance = app.mount({})
  return { props, state: instance.$.setupState, events, close() { app.unmount(); Object.assign(api, original) } }
}

test('名单精确绑定任务及版本，计数、重复责任和错误能力投影均拒绝', () => {
  assert.equal(rules.validateCountersignView(view(), task()).total, 2)
  for (const change of [v => { v.taskId = 'foreign' }, v => { v.applicationId = 'foreign' }, v => { v.applicationVersion++ },
    v => { v.total++ }, v => { v.completedUsers = ['finance']; v.completed = 1; v.total = 3 },
    v => { v.pending[1].user = 'finance' }, v => { v.pending[1].taskId = 'source' }, v => { v.pending[0].canRemove = true },
    v => { v.additions.push('admin') }, v => { v.canChange = false }, v => { v.canAdd = false }]) {
    const value = view(); change(value); assert.throws(() => rules.validateCountersignView(value, task()), /不一致/)
  }
})

test('委派名单只读，禁止借受托身份增减责任', () => {
  const value = view(), delegated = task(); delegated.assignee = 'bob'; delegated.delegationState = 'PENDING'
  value.pending[0].assignee = 'bob'; value.pending[0].delegated = true; value.pending[1].canRemove = false
  value.canChange = false; value.canAdd = false; value.additions = []; value.issue = 'TASK_DELEGATION_PENDING'
  rules.validateCountersignView(value, delegated)
  assert.throws(() => rules.countersignInput(value, 'ADD', 'manager', '复核'), /回交/)
})

test('输入保留读取版本，减签使用实际任务且不能移除自己或伪造人员', () => {
  assert.deepEqual(rules.countersignInput(view(), 'ADD', 'bob', ' 独立复核 '), input())
  assert.deepEqual(rules.countersignInput(view(), 'REMOVE', 'other', '调整责任'), { action: 'REMOVE', targetTaskId: 'other', reason: '调整责任', expectedVersion: 2 })
  assert.throws(() => rules.countersignInput(view(), 'REMOVE', 'source', '原因'), /其他/)
  assert.throws(() => rules.countersignInput(view(), 'ADD', 'admin', '原因'), /可加签/)
  assert.throws(() => rules.countersignInput(view(), 'ADD', 'bob', ' '), /原因/)
  assert.throws(() => rules.countersignInput(view(), 'ADD', 'bob', 'a'.repeat(2001)), /原因/)
})

test('打开和取消面板只读取，明确确认后才发送一次原目标和原因', async () => {
  let reads = 0; api.taskCountersignMembers = async () => { reads++; return view() }
  const p = mount()
  try {
    await settle(); assert.equal(reads, 1); assert.equal(p.events.length, 0)
    p.state.prepare('ADD'); p.state.target = 'bob'; p.state.reason = '独立复核'; p.state.cancel()
    assert.equal(p.events.length, 0); assert.equal(p.state.target, ''); assert.equal(p.state.reason, '')
    p.state.prepare('ADD'); p.state.target = 'bob'; p.state.reason = '独立复核'; p.state.execute()
    assert.deepEqual(p.events[0], [input(), view()])
    p.props.locked = true; p.state.execute(); assert.equal(p.events.length, 1)
  } finally { p.close() }
})

test('任务版本改变立即清空已选目标与原因，不沿用旧确认', async () => {
  api.taskCountersignMembers = async () => view()
  const p = mount()
  try {
    await settle(); p.state.prepare('REMOVE'); p.state.target = 'other'; p.state.reason = '原原因'
    p.props.task = { ...task(), version: 3 }; await settle()
    assert.equal(p.state.action, null); assert.equal(p.state.target, ''); assert.equal(p.state.reason, '')
    assert.equal(p.state.query.view, null); assert.match(p.state.query.error, /不一致/); assert.equal(p.events.length, 0)
  } finally { p.close() }
})

test('身份切换撤销旧查询，迟到响应和错误不能回填', async () => {
  const calls = []; const query = new rules.CountersignQuery((_task, signal) => new Promise((resolve, reject) => calls.push({ signal, resolve, reject })))
  const old = query.load('old-actor', task()), fresh = query.load('new-actor', task())
  assert.equal(calls[0].signal.aborted, true)
  calls[1].resolve(view()); await fresh
  calls[0].reject({ message: '旧身份错误' }); await old
  assert.equal(query.view.taskId, 'source'); assert.equal(query.error, '')
  const late = query.load('new-actor', task()); query.clear(); calls[2].resolve(view()); await late
  assert.equal(query.view, null); assert.equal(query.loading, false)
})

test('读取超时取消请求，稍后成功也不能启用旧名单', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let resolve, signal
  const query = new rules.CountersignQuery((_id, passed) => { signal = passed; return new Promise(done => { resolve = done }) })
  const pending = query.load('actor', task()); t.mock.timers.tick(12001); await pending
  assert.equal(signal.aborted, true); assert.match(query.error, /超时/); assert.equal(query.view, null)
  resolve(view()); await settle(); assert.equal(query.view, null)
})

test('错误动作、目标、版本、计数及完成票回执保持结果未知', () => {
  rules.validateCountersignReceipt(receipt(), 'source', input())
  for (const change of [r => { r.action = 'REMOVE' }, r => { r.targetUser = 'manager' }, r => { r.taskId = 'foreign' },
    r => { r.applicationVersion++ }, r => { r.completed = 2 }, r => { r.totalAfter = 2 }, r => { r.auditEventId = '' }]) {
    const value = receipt(); change(value); assert.throws(() => rules.validateCountersignReceipt(value, 'source', input()), e => e.code === 'RESPONSE_UNREADABLE')
  }
})

test('加签读取保持只读且禁缓存，丢失响应按原键原正文恢复', async () => {
  const calls = []; writeRequests.setActor({ tenantId: 'demo', userId: 'membership-lost' })
  try {
    globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); if (init.method === 'POST' && calls.length === 2) throw new Error('lost'); return Response.json(init.method === 'POST' ? receipt() : view()) }
    const signal = new AbortController().signal
    await api.taskCountersignMembers('source/encoded', signal)
    assert.ok(calls[0].url.endsWith('/tasks/source%2Fencoded/countersign-members')); assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, signal)
    assert.equal(calls[0].headers.has('Idempotency-Key'), false)
    await assert.rejects(api.changeCountersignMembers('source', input()))
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls[1].body, calls[2].body); assert.equal(calls[1].headers.get('Idempotency-Key'), calls[2].headers.get('Idempotency-Key'))
    assert.equal(writeRequests.pending().length, 0)
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('HTTP 成功但错配回执不能清除恢复槽或改人重发', async () => {
  const calls = []; writeRequests.setActor({ tenantId: 'demo', userId: 'membership-unreadable' })
  try {
    globalThis.fetch = async (_url, init) => { calls.push(init); return Response.json({ ...receipt(), ...(calls.length === 1 ? { targetUser: 'unexpected' } : {}) }) }
    await assert.rejects(api.changeCountersignMembers('source', input()), e => e.code === 'RESPONSE_UNREADABLE')
    assert.equal(writeRequests.pending().length, 1)
    await assert.rejects(api.changeCountersignMembers('source', { ...input(), targetUser: 'manager' }), e => e.code === 'PENDING_REQUEST_CHANGED')
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls.length, 2); assert.equal(calls[0].body, calls[1].body)
    assert.equal(calls[0].headers.get('Idempotency-Key'), calls[1].headers.get('Idempotency-Key'))
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('父页面加签成功后实际重读新版本，不能被写入期间 busy 拦住', async () => {
  const box = value => ({ value }), reads = [], env = {
    activeTask: box(task()), activeApplication: box({ id: 'application', version: 2 }), busy: box(false), writesBlocked: box(false),
    actorScope: box('demo:finance'), notice: box(''), detailLoading: box(false), detailError: box(''), taskTab: box('detail'),
    page: box('workbench'), taskDetailPanel: box(null), nextTick: async () => {}, errorMessage: error => error.message,
    refreshWorkspace: async () => {}, api: {
      changeCountersignMembers: async () => receipt(),
      task: async id => { reads.push(id); return { ...task(), version: 3, countersign: { total: 3, completed: 0 } } },
      application: async () => ({ id: 'application', version: 3 })
    }
  }
  await createWorkflow(env).performMembershipChange(input(), view())
  assert.deepEqual(reads, ['source'])
  assert.equal(env.activeTask.value.version, 3); assert.equal(env.activeTask.value.countersign.total, 3)
  assert.equal(env.activeApplication.value.version, 3); assert.equal(env.busy.value, false)
})


test('项目固定名单明确只读，不能把固定责任或异常能力投影解释为可加减签', () => {
  const fixed = view(); fixed.canChange = false; fixed.canAdd = false; fixed.additions = []; fixed.issue = 'EXPENSE_PROJECT_MEMBERS_FIXED'
  fixed.pending.forEach(member => { member.canRemove = false })
  assert.equal(rules.validateCountersignView(fixed, task()), fixed)
  assert.throws(() => rules.countersignInput(fixed, 'REMOVE', 'other', '减少责任'), /项目.*固定/)
  for (const mutate of [v => { v.canChange = true }, v => { v.canAdd = true }, v => { v.pending[1].canRemove = true }, v => { v.issue = 'UNKNOWN' }]) {
    const broken = structuredClone(fixed); mutate(broken); assert.throws(() => rules.validateCountersignView(broken, task()))
  }
})
