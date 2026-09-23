import test from 'node:test'
import assert from 'node:assert/strict'
const { NotificationInboxQuery } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const item = id => ({ id, kind: 'TASK_PENDING' })

test('切换账号与未读筛选清空消息和计数，迟到成功或失败不能回填', async () => {
  const requests = []
  const query = new NotificationInboxQuery((params, signal) => new Promise((resolve, reject) => requests.push({ params, signal, resolve, reject })))
  const old = query.load('demo:alice', 'all')
  const fresh = query.load('demo:finance', 'unread')
  assert.equal(requests[0].signal.aborted, true)
  requests[1].resolve({ items: [item('finance')], unreadCount: 2 }); await fresh
  requests[0].resolve({ items: [item('alice')], unreadCount: 99 }); await old
  assert.deepEqual(query.items, [item('finance')]); assert.equal(query.unreadCount, 2)
  const stale = query.load('demo:finance', 'all')
  query.clear(); requests[2].reject({ message: '旧错误' }); await stale
  assert.deepEqual(query.items, []); assert.equal(query.unreadCount, 0); assert.equal(query.error, '')
})

test('分页失败保留消息与游标，重试去重且末页省略游标可停止加载', async () => {
  const calls = []
  let fail = true
  const query = new NotificationInboxQuery(async params => {
    calls.push(params)
    if (!params.cursor) return { items: [item('first')], nextCursor: 'next', unreadCount: 2 }
    if (fail) { fail = false; throw { message: '暂时失败' } }
    return { items: [item('first'), item('last')], unreadCount: 1 }
  })
  await query.load('demo:alice', 'all'); await query.more()
  assert.deepEqual(query.items, [item('first')]); assert.equal(query.nextCursor, 'next'); assert.equal(query.unreadCount, 2)
  await query.more()
  assert.deepEqual(calls[1], calls[2]); assert.deepEqual(query.items, [item('first'), item('last')])
  assert.equal(query.nextCursor, null); assert.equal(query.unreadCount, 1)
  await query.more(); assert.equal(calls.length, 3)
})

test('重复加载更多只查询一次，筛选变化不沿用旧分页结果', async () => {
  let finish
  const query = new NotificationInboxQuery(async params => {
    if (params.cursor) return new Promise(resolve => { finish = resolve })
    return { items: [item(params.read)], unreadCount: 1, nextCursor: params.read === 'all' ? 'more' : undefined }
  })
  await query.load('demo:alice', 'all')
  const more = query.more(); await query.more()
  await query.load('demo:alice', 'unread')
  finish({ items: [item('old')], unreadCount: 50 }); await more
  assert.deepEqual(query.items, [item('unread')]); assert.equal(query.unreadCount, 1)
})

test('消息查询只读且可取消，标记已读丢失响应后重放原请求', async () => {
  globalThis.localStorage = { getItem: () => 'test-token' }
  const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  const requests = []
  globalThis.fetch = async (url, init) => { requests.push({ url, ...init }); return Response.json({ items: [], unreadCount: 0 }) }
  const controller = new AbortController()
  await api.inbox({ read: 'unread', cursor: 'a+/=', limit: 30 }, controller.signal)
  const url = new URL(requests[0].url, 'http://localhost')
  assert.equal(url.searchParams.get('cursor'), 'a+/='); assert.equal(url.searchParams.get('read'), 'unread')
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer test-token')
  assert.equal(requests[0].headers.has('Idempotency-Key'), false); assert.equal(requests[0].signal, controller.signal)
  writeRequests.setActor({ tenantId: 'demo', userId: 'finance' })
  const writes = []
  globalThis.fetch = async (url, init) => {
    writes.push({ url, ...init }); if (writes.length === 1) throw new Error('lost response')
    return Response.json({ id: 'message/1', readAt: '2026-01-01T00:00:00Z' })
  }
  await assert.rejects(api.readNotification('message/1'))
  assert.equal(writeRequests.pending().length, 1)
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.ok(writes[0].url.endsWith('/notifications/message%2F1/read'))
  assert.equal(writes[0].body, writes[1].body)
  assert.equal(writes[0].headers.get('Idempotency-Key'), writes[1].headers.get('Idempotency-Key'))
  assert.equal(writeRequests.pending().length, 0)
})
