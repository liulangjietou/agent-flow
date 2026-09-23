import test from 'node:test'
import assert from 'node:assert/strict'
const { PendingTaskQueueQuery } = await import(process.env.AGENTFLOW_TEST_TASK_QUEUE)
const item = taskId => ({ taskId })

test('查询保存已提交筛选，编辑中的金额和账号不能污染分页', async () => {
  const calls = []
  const query = new PendingTaskQueueQuery(async params => {
    calls.push(params)
    return params.cursor ? { items: [item('second')], total: 2 } : { items: [item('first')], total: 2, nextCursor: 'cursor' }
  })
  const filters = { q: '发票', applicant: 'alice', minAmount: '999.123456789012345678' }
  await query.load('demo:finance', filters)
  filters.applicant = 'bob'; filters.minAmount = '0'
  await query.more()
  assert.equal(calls[1].applicant, 'alice'); assert.equal(calls[1].minAmount, '999.123456789012345678')
  assert.equal(query.total, 2); assert.deepEqual(query.items, [item('first'), item('second')])
})

test('筛选与会话切换取消旧请求，迟到成功失败均不能回填', async () => {
  const requests = []
  const query = new PendingTaskQueueQuery((params, signal) => new Promise((resolve, reject) => requests.push({ params, signal, resolve, reject })))
  const first = query.load('demo:manager', { assignment: 'all' })
  const second = query.load('demo:finance', { assignment: 'delegated' })
  assert.equal(requests[0].signal.aborted, true)
  requests[1].resolve({ items: [item('mine')], total: 1 }); await second
  requests[0].resolve({ items: [item('other')], total: 50 }); await first
  assert.deepEqual(query.items, [item('mine')]); assert.equal(query.total, 1)
  const old = query.load('demo:finance', { minAmount: '5' })
  query.clear(); requests[2].reject({ message: '旧账号错误' }); await old
  assert.deepEqual(query.items, []); assert.equal(query.total, 0); assert.equal(query.error, '')
})

test('分页失败保留原游标，双击不重复加载，完成任务后的重复行不叠加', async () => {
  let finish, fail = true
  const calls = []
  const query = new PendingTaskQueueQuery(async params => {
    calls.push(params)
    if (!params.cursor) return { items: [item('a')], total: 3, nextCursor: 'next' }
    if (fail) { fail = false; throw { message: '断网' } }
    return new Promise(resolve => { finish = resolve })
  })
  await query.load('demo:manager', {}); await query.more()
  assert.equal(query.nextCursor, 'next'); assert.deepEqual(query.items, [item('a')])
  const retry = query.more(); await query.more()
  assert.equal(calls.length, 3); assert.deepEqual(calls[1], calls[2])
  finish({ items: [item('a'), item('b')], total: 2 }); await retry
  assert.deepEqual(query.items, [item('a'), item('b')]); assert.equal(query.total, 2); assert.equal(query.nextCursor, null)
  await query.more(); assert.equal(calls.length, 3)
})

test('分页和单项任务查询只读、可取消并保留精确数值与特殊标识', async () => {
  globalThis.localStorage = { getItem: () => 'task-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  const requests = []
  globalThis.fetch = async (url, init) => { requests.push({ url, ...init }); return Response.json({ items: [], total: 0 }) }
  const controller = new AbortController()
  await api.taskPage({ q: '%_ !', processKey: '流程/x', assignment: 'delegated', minAmount: '999.123456789012345678', cursor: 'a+/=' }, controller.signal)
  await api.task('task/id', controller.signal)
  const params = new URL(requests[0].url, 'http://localhost').searchParams
  assert.equal(params.get('q'), '%_ !'); assert.equal(params.get('processKey'), '流程/x')
  assert.equal(params.get('minAmount'), '999.123456789012345678'); assert.equal(params.get('cursor'), 'a+/=')
  assert.ok(requests[1].url.endsWith('/tasks/task%2Fid'))
  for (const request of requests) {
    assert.equal(request.signal, controller.signal); assert.equal(request.headers.get('Authorization'), 'Bearer task-token')
    assert.equal(request.headers.has('Idempotency-Key'), false); assert.equal(request.body, undefined)
  }
})
