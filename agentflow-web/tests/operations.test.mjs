import test from 'node:test'
import assert from 'node:assert/strict'
const { ApprovalOperationsQuery, defaultOperationsFilter, operationsFilter, operationsDuration } = await import(process.env.AGENTFLOW_TEST_OPERATIONS)
const now = new Date('2024-03-01T01:00:00Z')

test('UTC 日期包含闰日和边界，拒绝无效范围与脱离流程的版本', () => {
  assert.deepEqual(defaultOperationsFilter(new Date('2024-02-29T20:00:00-05:00')), { from: '2024-02-01', to: '2024-03-01', processKey: '' })
  assert.equal(operationsFilter('2023-03-02', '2024-03-01', ' flow ', '2', now).definitionVersion, 2)
  for (const [from, to, key, version] of [
    ['2023-02-29', '2024-03-01', '', ''], ['2023-03-01', '2024-03-01', '', ''],
    ['2024-03-02', '2024-03-01', '', ''], ['2024-03-01', '2024-03-02', '', ''],
    ['2024-02-29', '2024-03-01', '', '1'], ['2024-02-29', '2024-03-01', 'flow', '0'],
    ['2024-02-29', '2024-03-01', 'flow', '2147483648']
  ]) assert.throws(() => operationsFilter(from, to, key, version, now))
  assert.equal(operationsDuration(undefined), '—'); assert.equal(operationsDuration(0), '0 秒')
  assert.equal(operationsDuration(3661), '1 小时 1 分'); assert.equal(operationsDuration(90000), '1 天 1 小时')
})

test('账号和筛选切换取消旧查询，迟到结果与错误均不能回填', async () => {
  const requests = []
  const query = new ApprovalOperationsQuery((filter, signal) => new Promise((resolve, reject) => requests.push({ filter, signal, resolve, reject })))
  const filter = defaultOperationsFilter(now)
  const first = query.load('demo:admin', filter)
  filter.processKey = 'changed'
  assert.equal(requests[0].filter.processKey, '')
  const second = query.load('other:admin', filter)
  assert.equal(requests[0].signal.aborted, true)
  requests[1].resolve({ pendingTasks: 2 }); await second
  requests[0].resolve({ pendingTasks: 99 }); await first
  assert.equal(query.report.pendingTasks, 2)
  const third = query.load('other:admin', filter)
  assert.equal(query.report, null)
  query.clear(); requests[2].reject({ message: '旧租户错误' }); await third
  assert.equal(query.error, ''); assert.equal(query.report, null); assert.equal(query.loading, false)
  await query.load('', filter); assert.equal(requests.length, 3)
})

test('失败清除旧报告，重试成功才重新显示统计', async () => {
  let fail = false
  const query = new ApprovalOperationsQuery(async () => { if (fail) throw { message: '服务不可用' }; return { pendingTasks: 0 } })
  await query.load('demo:admin', defaultOperationsFilter(now)); assert.equal(query.report.pendingTasks, 0)
  fail = true; await query.load('demo:admin', defaultOperationsFilter(now))
  assert.equal(query.report, null); assert.equal(query.error, '服务不可用'); assert.equal(query.loading, false)
  fail = false; await query.load('demo:admin', defaultOperationsFilter(now)); assert.equal(query.error, '')
})

test('超时即取消，忽略取消的迟到成功不能成为新报告', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let finish, signal
  const query = new ApprovalOperationsQuery((_, abort) => { signal = abort; return new Promise(resolve => { finish = resolve }) })
  const pending = query.load('demo:admin', defaultOperationsFilter(now))
  t.mock.timers.tick(15000); assert.equal(signal.aborted, true)
  finish({ pendingTasks: 123 }); await pending
  assert.equal(query.report, null); assert.match(query.error, /超时/); assert.equal(query.loading, false)
})

test('运营 API 是可取消的认证只读请求，编码流程键且没有写入幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'operations-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let request
  globalThis.fetch = async (url, init) => { request = { url, ...init }; return Response.json({}) }
  const controller = new AbortController()
  await api.approvalOperations({ from: '2024-02-01', to: '2024-03-01', processKey: '流程/x & y', definitionVersion: 2 }, controller.signal)
  const url = new URL(request.url, 'http://localhost')
  assert.equal(url.pathname, '/api/v1/operations/approvals'); assert.equal(url.searchParams.get('processKey'), '流程/x & y')
  assert.equal(url.searchParams.get('definitionVersion'), '2'); assert.equal(request.signal, controller.signal)
  assert.equal(request.headers.get('Authorization'), 'Bearer operations-token')
  assert.equal(request.headers.has('Idempotency-Key'), false); assert.equal(request.body, undefined)
})
