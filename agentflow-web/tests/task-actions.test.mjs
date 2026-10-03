import test from 'node:test'
import assert from 'node:assert/strict'
const { taskActionInput, TaskRecipientsQuery } = await import(process.env.AGENTFLOW_TEST_TASK_ACTIONS)

const proxy = (id, principal = id) => ({ proxyId: id, revision: 1, definitionId: 'published', principalId: principal,
  principal, startsAt: new Date(Date.now() - 60_000).toISOString(), endsAt: new Date(Date.now() + 60_000).toISOString() })

test('代理决定固定明确依据，多名原审批人必须选择，原生权利不替代失效的显式选择', () => {
  const first = proxy('first', 'manager'), second = proxy('second', 'finance')
  const task = { version: 4, allowedActions: ['APPROVE', 'RETURN'], canActDirectly: false, proxyOptions: [first] }
  assert.equal(taskActionInput(task, 'APPROVE', '', '', []).proxyId, 'first')
  const multiple = { ...task, proxyOptions: [first, second] }
  assert.throws(() => taskActionInput(multiple, 'APPROVE', '', '', []), /选择.*代理/)
  assert.equal(taskActionInput(multiple, 'RETURN', '补材料', '', [], 'second').proxyId, 'second')
  const native = { ...multiple, canActDirectly: true }
  assert.equal(Object.hasOwn(taskActionInput(native, 'APPROVE', '', '', []), 'proxyId'), false)
  assert.equal(taskActionInput(native, 'APPROVE', '', '', [], 'first').proxyId, 'first')
  assert.throws(() => taskActionInput(native, 'APPROVE', '', '', [], 'missing'), /代理.*失效|代理.*不可用/)
})

test('过期或尚未开始的代理不能提交，代理不能领取或再委派', () => {
  const task = { version: 4, canActDirectly: false, allowedActions: ['APPROVE', 'CLAIM', 'DELEGATE'], proxyOptions: [proxy('first')] }
  assert.throws(() => taskActionInput(task, 'CLAIM', '', '', []), /代理.*不支持/)
  assert.throws(() => taskActionInput(task, 'DELEGATE', '', 'target', ['target']), /代理.*不支持/)
  for (const option of [{ ...proxy('first'), endsAt: new Date(Date.now() - 1_000).toISOString() },
    { ...proxy('first'), startsAt: new Date(Date.now() + 60_000).toISOString() }]) {
    assert.throws(() => taskActionInput({ ...task, proxyOptions: [option] }, 'APPROVE', '', '', []), /代理.*失效|代理.*不可用/)
  }
  assert.throws(() => taskActionInput({ ...task, proxyOptions: [] }, 'APPROVE', '', '', []), /代理.*失效|代理.*不可用/)
})

test('回交不携带伪造接收人，保留原版本并要求真实处理意见', () => {
  const task = { taskId: 'delegated', version: 3, owner: 'finance', allowedActions: ['RESOLVE'] }
  assert.throws(() => taskActionInput(task, 'APPROVE', '', '', []), /不允许/)
  assert.throws(() => taskActionInput(task, 'RESOLVE', ' ', '', []), /处理意见/)
  assert.deepEqual(taskActionInput(task, 'RESOLVE', ' 已核对 ', 'forged', []), { action: 'RESOLVE', expectedVersion: 3, comment: '已核对', targetUser: undefined })
  const ordinary = { ...task, version: 4, allowedActions: ['TRANSFER', 'DELEGATE'] }
  assert.throws(() => taskActionInput(ordinary, 'DELEGATE', '', 'missing', ['bob']), /可用/)
  assert.equal(taskActionInput(ordinary, 'TRANSFER', '', 'bob', ['bob']).targetUser, 'bob')
})

test('任务或账号切换取消接收人查询，迟到响应和失败不能回填', async () => {
  const pending = []
  const query = new TaskRecipientsQuery((id, signal) => new Promise((resolve, reject) => pending.push({ id, signal, resolve, reject })))
  const old = query.load('demo:finance', 'old')
  const fresh = query.load('demo:bob', 'new')
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve(['manager']); await fresh
  pending[0].resolve(['alice']); await old
  assert.deepEqual(query.users, ['manager'])
  const late = query.load('demo:bob', 'new')
  query.clear(); pending[2].reject({ message: '旧面板错误' }); await late
  assert.deepEqual(query.users, []); assert.equal(query.error, ''); assert.equal(query.loading, false)
})

test('重新读取目录失败不保留过期人员，空会话不查询', async () => {
  let calls = 0
  const query = new TaskRecipientsQuery(async () => { if (++calls === 2) throw { message: '身份已失效' }; return ['bob'] })
  await query.load('demo:finance', 'task')
  await query.load('demo:finance', 'task')
  assert.deepEqual(query.users, []); assert.equal(query.error, '身份已失效')
  await query.load('', 'task'); assert.equal(calls, 2)
})

test('批准与回交响应丢失后复用原意见和幂等键，目录查询保持只读', async () => {
  globalThis.localStorage = { getItem: () => 'test-token' }
  const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  const reads = []
  globalThis.fetch = async (url, init) => { reads.push({ url, ...init }); return Response.json(['finance']) }
  const controller = new AbortController()
  await api.taskRecipients('task/1', controller.signal)
  assert.ok(reads[0].url.endsWith('/tasks/task%2F1/recipients'))
  assert.equal(reads[0].signal, controller.signal); assert.equal(reads[0].headers.has('Idempotency-Key'), false)
  writeRequests.setActor({ tenantId: 'demo', userId: 'bob' })
  for (const action of ['APPROVE', 'RESOLVE']) {
    const writes = []
    globalThis.fetch = async (_url, init) => {
      writes.push(init)
      if (writes.length === 1) throw new Error('lost response')
      return Response.json({ taskId: 'task', action, applicationStatus: 'IN_APPROVAL', version: 4 })
    }
    const input = { action, expectedVersion: 3, comment: '原意见', ...(action === 'APPROVE' ? { proxyId: 'original-proxy' } : {}) }
    await assert.rejects(api.taskAction('task', input))
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(writes[0].body, writes[1].body)
    assert.equal(JSON.parse(writes[1].body).comment, '原意见')
    assert.equal(JSON.parse(writes[1].body).proxyId, input.proxyId)
    assert.equal(writes[0].headers.get('Idempotency-Key'), writes[1].headers.get('Idempotency-Key'))
    assert.equal(writeRequests.pending().length, 0)
  }
})
