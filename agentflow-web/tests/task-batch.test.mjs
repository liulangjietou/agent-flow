import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { TaskBatch, MAX_TASK_BATCH_SIZE } = await import(process.env.AGENTFLOW_TEST_TASK_BATCH)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_TASK_BATCH_PANEL)
const { default: RenderedPanel } = await import(process.env.AGENTFLOW_TEST_TASK_BATCH_RENDERED)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = globalThis.fetch
const item = id => ({ taskId: id, applicationId: 'app-' + id, businessNo: 'NO-' + id, title: '申请 ' + id, taskName: '复核', delegationState: 'NONE' })
const task = id => ({ ...item(id), version: 3, allowedActions: ['CLAIM', 'RELEASE'], assignee: null })
const settle = () => new Promise(resolve => setImmediate(resolve))

test('名单有界且去重，拒绝批准等其他动作，预检不产生写入', async () => {
  let reads = 0, writes = 0
  const batch = new TaskBatch(async id => { reads++; return task(id) }, async () => writes++)
  for (const [action, items] of [['APPROVE', [item('a')]], ['CLAIM', []], ['CLAIM', [item('a'), item('a')]],
    ['RELEASE', Array.from({ length: MAX_TASK_BATCH_SIZE + 1 }, (_, i) => item(String(i)))]]) {
    await assert.rejects(batch.prepare(action, items))
  }
  assert.equal(reads, 0); assert.equal(writes, 0)
  await batch.prepare('CLAIM', [item('a')]); assert.equal(reads, 1); assert.equal(writes, 0)
  assert.equal(batch.stage, 'PREVIEW'); assert.equal(batch.rows[0].version, 3)
})

test('预检核对原任务与申请绑定及当前允许动作，不可办理项不进入发送名单', async () => {
  const writes = []
  const batch = new TaskBatch(async id => id === 'gone' ? Promise.reject({ status: 404, message: '任务不可用' })
    : id === 'bound' ? { ...task(id), applicationId: 'other' } : id === 'delegated' ? { ...task(id), allowedActions: ['RESOLVE'] } : task(id),
  async (id, input) => writes.push({ id, input }))
  await batch.prepare('CLAIM', ['gone', 'bound', 'delegated', 'valid'].map(item))
  assert.deepEqual(batch.rows.map(row => row.state), ['UNAVAILABLE', 'UNAVAILABLE', 'UNAVAILABLE', 'READY'])
  await assert.rejects(batch.execute('  ')); await assert.rejects(batch.execute('字'.repeat(501)))
  await batch.execute(' 核对后领取 ')
  assert.deepEqual(writes, [{ id: 'valid', input: { action: 'CLAIM', expectedVersion: 3, comment: '核对后领取' } }])
})

test('确认后固定名单与版本，独立冲突不撤销已成功项也不更新版本自动重试', async () => {
  const writes = [], selected = ['a', 'conflict', 'c'].map(item)
  const batch = new TaskBatch(async id => task(id), async (id, input) => {
    writes.push({ id, input }); if (id === 'conflict') throw { status: 409, code: 'CONCURRENCY_CONFLICT', message: '版本已变化' }
  })
  await batch.prepare('RELEASE', selected)
  selected[0].taskId = 'injected'; selected.push(item('new'))
  await batch.execute('本次交还待领取队列')
  assert.deepEqual(writes.map(row => row.id), ['a', 'conflict', 'c'])
  assert.ok(writes.every(row => row.input.expectedVersion === 3 && row.input.action === 'RELEASE'))
  assert.deepEqual(batch.rows.map(row => row.state), ['SUCCEEDED', 'FAILED', 'SUCCEEDED'])
  await assert.rejects(batch.execute('重试'))
})

test('缺失或非法版本及权限列表不能进入确认清单', async () => {
  for (const invalid of [{ version: undefined }, { version: -1 }, { version: 1.5 }, { allowedActions: null }]) {
    const batch = new TaskBatch(async id => ({ ...task(id), ...invalid }), async () => assert.fail('不得写入'))
    await batch.prepare('CLAIM', [item('a')]); assert.equal(batch.rows[0].state, 'UNAVAILABLE')
    await assert.rejects(batch.execute('领取'))
  }
})

test('未知、服务异常、认证及 CSRF 失败立即停发后续任务', async () => {
  for (const error of [new Error('断网'), { status: 500 }, { status: 401 }, { status: 403, code: 'CSRF_INVALID' }, { status: 401, code: 'SESSION_CHANGED' }]) {
    const sent = []
    const batch = new TaskBatch(async id => task(id), async id => { sent.push(id); if (id === 'b') throw error })
    await batch.prepare('CLAIM', ['a', 'b', 'c'].map(item)); await batch.execute('接手本次审核')
    assert.deepEqual(sent, ['a', 'b']); assert.deepEqual(batch.rows.map(row => row.state), ['SUCCEEDED', 'UNKNOWN', 'SKIPPED'])
    await assert.rejects(batch.prepare('CLAIM', [item('c')]))
  }
})

test('重复确认共享当前执行，不发送第二次操作', async () => {
  let finish, writes = 0
  const batch = new TaskBatch(async id => task(id), () => { writes++; return new Promise(resolve => { finish = resolve }) })
  await batch.prepare('CLAIM', [item('a')]); const first = batch.execute('领取')
  await batch.execute('第二次点击'); assert.equal(writes, 1)
  finish({}); await first; assert.equal(batch.rows[0].state, 'SUCCEEDED')
})

test('离开时取消旧读取并隔离迟到结果，已发出的写入结束后不继续下一项', async () => {
  let finishRead, signal, writes = 0
  const batch = new TaskBatch((id, current) => { signal = current; return new Promise(resolve => { finishRead = () => resolve(task(id)) }) }, async () => writes++)
  const checking = batch.prepare('CLAIM', [item('a')]); batch.clear(); assert.equal(signal.aborted, true)
  finishRead(); await checking; assert.equal(batch.stage, 'EMPTY'); assert.deepEqual(batch.rows, [])
  let finishWrite
  const sending = new TaskBatch(async id => task(id), () => { writes++; return new Promise(resolve => { finishWrite = resolve }) })
  await sending.prepare('CLAIM', ['a', 'b'].map(item)); const started = sending.execute('领取')
  sending.clear(); finishWrite({}); await started; assert.equal(writes, 1); assert.equal(sending.stage, 'EMPTY')
})

test('读取超时可以取消且不残留计时器或写入', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let signal
  const batch = new TaskBatch((_id, current) => { signal = current; return new Promise(() => {}) }, async () => assert.fail('不得写入'))
  const pending = batch.prepare('CLAIM', [item('a')]); t.mock.timers.tick(12_000); await pending
  assert.equal(signal.aborted, true); assert.equal(batch.rows[0].state, 'UNAVAILABLE'); assert.match(batch.rows[0].message, /超时/)
})

test('实际请求响应丢失后只恢复原任务原键，恢复不发送批次剩余任务', async () => {
  globalThis.localStorage = { getItem: () => 'synthetic-batch-token' }
  const calls = []; bindAuthenticationActor({ tenantId: 'demo', userId: 'batch-recovery' })
  try {
    globalThis.fetch = async (url, init) => {
      calls.push({ url, ...init }); if (calls.length === 1) throw new Error('lost')
      return Response.json({ taskId: 'a', action: 'CLAIM', applicationStatus: 'IN_APPROVAL', version: 4 })
    }
    const batch = new TaskBatch(async id => task(id), api.taskAction)
    await batch.prepare('CLAIM', ['a', 'b'].map(item)); await batch.execute('核对后领取')
    assert.equal(calls.length, 1); assert.equal(writeRequests.pending().length, 1)
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls.length, 2); assert.equal(writeRequests.pending().length, 0)
    assert.equal(calls[0].url, calls[1].url); assert.equal(calls[0].body, calls[1].body)
    assert.equal(calls[0].headers.get('Idempotency-Key'), calls[1].headers.get('Idempotency-Key'))
    assert.deepEqual(batch.rows.map(row => row.state), ['UNKNOWN', 'SKIPPED'])
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('领取释放的 HTTP 200 空壳、串任务或错误版本不能计成功，原请求仍可恢复', async () => {
  globalThis.localStorage = { getItem: () => 'synthetic-batch-token' }
  for (const [index, invalid] of [{}, { taskId: 'other' }, { version: 3 }, { version: 5 }, { action: 'APPROVE' }, { applicationStatus: 'APPROVED' }].entries()) {
    const calls = []; bindAuthenticationActor({ tenantId: 'demo', userId: 'batch-invalid-' + index })
    const valid = { taskId: 'a', action: 'RELEASE', version: 4, applicationStatus: 'IN_APPROVAL' }
    try {
      globalThis.fetch = async (_url, init) => { calls.push(init); return Response.json(calls.length === 1 ? (index === 0 ? {} : { ...valid, ...invalid }) : valid) }
      await assert.rejects(api.taskAction('a', { action: 'RELEASE', expectedVersion: 3, comment: '交还队列' }), e => e.code === 'RESPONSE_UNREADABLE')
      assert.equal(writeRequests.pending().length, 1)
      await writeRequests.recover(writeRequests.pending()[0].id)
      assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].headers.get('Idempotency-Key'), calls[1].headers.get('Idempotency-Key'))
    } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
  }
})

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel() {
  const props = reactive({ items: [item('a'), item('b')], scopeKey: 'demo:manager', locked: false }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_props, context) => Panel.setup(props, context), render: () => null },
    { ...props, onChanged: () => events.push('changed'), onClose: () => events.push('close') })
  const mounted = app.mount({})
  return { state: mounted.$.setupState, props, events, close: () => app.unmount() }
}

test('实际面板先核对后明确确认，全局写入锁和账号切换阻断旧选择', async () => {
  const sent = []; api.task = async id => task(id); api.taskAction = async (id, input) => sent.push({ id, input })
  const p = panel()
  try {
    p.state.selected = ['a', 'b']; await p.state.prepare(); assert.equal(sent.length, 0); assert.equal(p.state.readyCount, 2)
    p.state.reason = '接手审核'; p.props.locked = true; await p.state.execute(); assert.equal(sent.length, 0)
    p.props.locked = false; await p.state.execute(); assert.equal(sent.length, 2); assert.deepEqual(p.events, ['changed'])
    p.props.scopeKey = 'demo:finance'; await settle(); assert.equal(p.state.batch.stage, 'EMPTY'); assert.deepEqual(p.events, ['changed', 'close'])
  } finally { p.close(); Object.assign(api, originalApi) }
})

test('实际面板关闭或切换动作会取消预检，迟到结果不会发送任务', async () => {
  let finish, signal; api.task = (id, current) => { signal = current; return new Promise(resolve => { finish = () => resolve(task(id)) }) }
  api.taskAction = async () => assert.fail('不得写入')
  const p = panel()
  try {
    p.state.selected = ['a']; const checking = p.state.prepare(); p.state.close(); assert.equal(signal.aborted, true)
    finish(); await checking; assert.deepEqual(p.events, ['close']); assert.equal(p.state.batch.stage, 'EMPTY')
    p.state.action = 'RELEASE'; assert.deepEqual(p.state.selected, [])
  } finally { p.close(); Object.assign(api, originalApi) }
})

test('实际模板展示部分成功与未知停发，只有确认表单发送命令', async () => {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null, value: '',
    get options() { return this.children.filter(child => child.tag === 'option') },
    addEventListener() {}, removeEventListener() {}, getRootNode: () => ({}) })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({
    createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
    patchProp: (el, key, _old, value) => { el.props[key] = value; if (key === 'value') el.value = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null
  })
  const all = el => [el, ...el.children.flatMap(all)], root = node('root'), sent = []
  const previousDocument = globalThis.Document, previousShadowRoot = globalThis.ShadowRoot
  globalThis.Document = class {}; globalThis.ShadowRoot = class {}
  api.task = async id => task(id)
  api.taskAction = async id => { sent.push(id); if (id === 'b') throw { status: 0, message: '响应未确认' } }
  const app = host.createApp(RenderedPanel, { items: ['a', 'b', 'c'].map(item), scopeKey: 'demo:manager', locked: false })
  try {
    app.mount(root)
    all(root).find(el => el.tag === 'input').props['onUpdate:modelValue'](['a', 'b', 'c']); await settle()
    await all(root).find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} }); await settle()
    assert.deepEqual(sent, []); assert.ok(all(root).some(el => el.tag === 'button' && el.text === '确认领取 3 项'))
    all(root).find(el => el.tag === 'textarea').props['onUpdate:modelValue']('本次接手审核'); await settle()
    await all(root).find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} }); await settle()
    assert.deepEqual(sent, ['a', 'b'])
    assert.deepEqual(all(root).filter(el => el.props['data-state']).map(el => el.props['data-state']), ['SUCCEEDED', 'UNKNOWN', 'SKIPPED'])
    assert.ok(all(root).some(el => el.props.role === 'alert' && el.text.includes('恢复待确认操作')))
    assert.equal(all(root).filter(el => el.tag === 'form').length, 0)
  } finally { app.unmount(); Object.assign(api, originalApi); globalThis.Document = previousDocument; globalThis.ShadowRoot = previousShadowRoot }
})
