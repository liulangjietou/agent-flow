import test from 'node:test'
import assert from 'node:assert/strict'
const { CommentDrafts, CommentsQuery } = await import(process.env.AGENTFLOW_TEST_COMMENTS)
const item = id => ({ id, content: '正文 ' + id })

test('草稿按租户账号和申请隔离，读取副本不能改写保留内容', () => {
  const drafts = new CommentDrafts()
  drafts.put('demo:alice', 'one', '原内容', 2)
  assert.equal(drafts.get('other:alice', 'one'), null)
  assert.equal(drafts.get('demo:bob', 'one'), null)
  assert.equal(drafts.get('demo:alice', 'two'), null)
  drafts.get('demo:alice', 'one').content = '外部改写'
  assert.equal(drafts.get('demo:alice', 'one').content, '原内容')
  assert.equal(drafts.hasDrafts(), true)
  drafts.discard('demo:alice', 'one'); assert.equal(drafts.hasDrafts(), false)
})
test('刷新版本不自动转移旧草稿，显式核对后才更新上下文', () => {
  const drafts = new CommentDrafts()
  drafts.put('alice', 'one', '第一版', 2)
  drafts.put('alice', 'one', '继续编辑', 5)
  assert.deepEqual(drafts.get('alice', 'one'), { content: '继续编辑', expectedVersion: 2 })
  drafts.adopt('alice', 'one', 5)
  assert.deepEqual(drafts.get('alice', 'one'), { content: '继续编辑', expectedVersion: 5 })
})
test('确认原请求只移除同内容同版本草稿，不能误删另一申请或后续编辑', () => {
  const drafts = new CommentDrafts()
  drafts.put('alice', 'one', ' 原内容 ', 2); drafts.put('alice', 'two', '其他申请', 2)
  drafts.acknowledge('alice', 'one', { content: '原内容', expectedVersion: 3 })
  assert.ok(drafts.get('alice', 'one'))
  drafts.put('alice', 'one', '后续编辑', 2)
  drafts.acknowledge('alice', 'one', { content: '原内容', expectedVersion: 2 })
  assert.equal(drafts.get('alice', 'one').content, '后续编辑')
  drafts.acknowledge('alice', 'one', { content: '后续编辑', expectedVersion: 2 })
  assert.equal(drafts.get('alice', 'one'), null); assert.ok(drafts.get('alice', 'two'))
  drafts.put('alice', 'two', ' \n\t', 2); assert.equal(drafts.hasDrafts(), false)
})
test('翻页保留原筛选，重复加载被阻止，网络错误可使用原游标恢复', async () => {
  const calls = []; let fail = true, finish
  const query = new CommentsQuery(async (id, parameters) => {
    calls.push({ id, parameters })
    if (!parameters.cursor) return { items: [item('one')], nextCursor: 'next' }
    if (fail) { fail = false; throw { message: '断网' } }
    return new Promise(resolve => { finish = resolve })
  })
  await query.load('alice', 'app', 2)
  await query.load('alice', 'app', 1, true); assert.equal(calls.length, 1)
  await query.load('alice', 'app', 2, true)
  assert.equal(query.nextCursor, 'next'); assert.deepEqual(query.items, [item('one')])
  const retry = query.load('alice', 'app', 2, true); await query.load('alice', 'app', 2, true)
  assert.equal(calls.length, 3); assert.deepEqual(calls[1], calls[2])
  finish({ items: [item('two')], nextCursor: null }); await retry
  assert.deepEqual(query.items, [item('one'), item('two')]); assert.equal(query.nextCursor, null)
})
test('切换申请账号清除旧正文，迟到成功和错误不能覆盖当前请求', async () => {
  const calls = []
  const query = new CommentsQuery((id, parameters, signal) => new Promise((resolve, reject) => calls.push({ signal, resolve, reject })))
  const old = query.load('alice', 'one'), current = query.load('bob', 'two')
  assert.equal(calls[0].signal.aborted, true)
  calls[1].resolve({ items: [item('current')] }); await current
  calls[0].resolve({ items: [item('secret')] }); await old
  assert.deepEqual(query.items, [item('current')])
  const previous = query.load('bob', 'two'); query.clear()
  calls[2].reject({ message: '迟到错误' }); await previous
  assert.deepEqual(query.items, []); assert.equal(query.error, '')
})
for (const status of [401, 403, 404]) test(`翻页失权 ${status} 时清除已加载正文与游标`, async () => {
  const query = new CommentsQuery(async (_id, params) => {
    if (params.cursor) throw { status, message: '不可见' }
    return { items: [item('secret')], nextCursor: 'next' }
  })
  await query.load('alice', 'one'); await query.load('alice', 'one', undefined, true)
  assert.deepEqual(query.items, []); assert.equal(query.nextCursor, null)
})
test('超时后迟到的结果不能进入页面', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let resolve, signal
  const query = new CommentsQuery((_id, _params, s) => { signal = s; return new Promise(done => { resolve = done }) })
  const pending = query.load('alice', 'one')
  t.mock.timers.tick(12_000); assert.equal(signal.aborted, true)
  resolve({ items: [item('late')] }); await pending
  assert.deepEqual(query.items, []); assert.match(query.error, /超时/); assert.equal(query.loading, false)
})
test('API 编码申请与游标，断网重试保留原正文和幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'comments-token' }
  const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  writeRequests.setActor({ tenantId: 'demo', userId: 'alice' })
  const requests = []
  globalThis.fetch = async (url, init) => { requests.push({ url, ...init }); return Response.json({ items: [] }) }
  const controller = new AbortController()
  await api.applicationComments('app/id', { roundNo: 2, limit: 3, cursor: 'a+/=' }, controller.signal)
  assert.equal(new URL(requests[0].url, 'http://localhost').searchParams.get('cursor'), 'a+/=')
  assert.ok(requests[0].url.includes('/applications/app%2Fid/comments'))
  assert.equal(requests[0].signal, controller.signal); assert.equal(requests[0].headers.get('Authorization'), 'Bearer comments-token')
  assert.equal(requests[0].headers.has('Idempotency-Key'), false)
  globalThis.fetch = async (url, init) => {
    requests.push({ url, ...init })
    if (requests.length === 2) throw new TypeError('lost')
    return Response.json({ id: 'comment', applicationId: 'app', author: 'alice', content: '原评论\n第二行', applicationVersion: 2, roundNo: 1, applicationStatus: 'IN_APPROVAL', createdAt: '2026-10-01T12:00:00Z' }, { status: 201 })
  }
  const body = { content: '原评论\n第二行', expectedVersion: 2 }
  await assert.rejects(api.addApplicationComment('app', body))
  body.content = '新内容'
  await assert.rejects(api.addApplicationComment('app', body), error => error.code === 'PENDING_REQUEST_CHANGED')
  const { result } = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(result.id, 'comment'); assert.equal(requests[1].body, requests[2].body)
  assert.equal(requests[1].headers.get('Idempotency-Key'), requests[2].headers.get('Idempotency-Key'))
  assert.equal(writeRequests.pending().length, 0)
})
