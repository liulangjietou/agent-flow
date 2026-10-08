import test from 'node:test'
import assert from 'node:assert/strict'
const { NotificationInboxQuery, isTaskNotification, notificationLabels } = await import(process.env.AGENTFLOW_TEST_NOTIFICATIONS)
const { createNavigation } = await import(process.env.AGENTFLOW_TEST_NOTIFICATION_NAVIGATION)
const item = id => ({ id, kind: 'TASK_PENDING' })

test('已批撤销通知显示明确结果并打开当前申请，不请求旧任务或显示新的审批权', async () => {
  const kind = 'APPLICATION_REVOKED'; assert.match(notificationLabels[kind] ?? '', /已撤销/)
  let requests = 0
  const deps = { busy: { value: false }, writesBlocked: { value: false }, actorScope: { value: 'demo:alice' },
    recordApplicationId: { value: '' }, notice: { value: '' }, api: { task: async () => { requests++; throw { status: 404 } } } }
  await createNavigation(deps)({ kind, applicationId: 'revoked', roundNo: 1, taskId: 'ended' })
  assert.equal(requests, 0); assert.equal(deps.recordApplicationId.value, 'revoked')
  assert.equal(isTaskNotification({ kind }), false); assert.equal(deps.notice.value, '')
})

test('结束或移除会签的消息直接打开申请，不请求已取消任务，也不显示待办异常提示', async () => {
  for (const kind of ['TASK_COUNTERSIGN_COMPLETED', 'TASK_COUNTERSIGN_REMOVED', 'APPLICATION_PAUSED', 'APPLICATION_RESUMED', 'APPLICATION_CANCELLED', 'ADVANCE_OVERDUE']) {
    let requests = 0
    const deps = { busy: { value: false }, writesBlocked: { value: false }, actorScope: { value: 'demo:finance' },
      recordApplicationId: { value: '' }, notice: { value: '' }, api: { task: async () => { requests++; throw { status: 404 } } } }
    await createNavigation(deps)({ kind, taskId: 'ended', applicationId: 'approved', roundNo: 1 })
    assert.equal(requests, 0, '结束通知不应先读取不存在的任务')
    assert.equal(deps.recordApplicationId.value, 'approved')
    assert.equal(deps.notice.value, '')
    assert.equal(isTaskNotification({ kind }), false)
  }
})

test('实际待办消息仍实时复核，抄送保持专用轮次读取入口', async () => {
  const selected = [], current = { taskId: 'task', applicationId: 'application' }
  const deps = { busy: { value: false }, writesBlocked: { value: false }, actorScope: { value: 'demo:finance' },
    recordApplicationId: { value: '' }, selectedCopy: { value: null }, notice: { value: '' }, page: { value: 'notifications' },
    api: { task: async id => { assert.equal(id, 'task'); return current } }, selectTask: async task => selected.push(task) }
  const open = createNavigation(deps)
  await open({ kind: 'TASK_PENDING', taskId: 'task', applicationId: 'application' })
  assert.deepEqual(selected, [current]); assert.equal(deps.page.value, 'workbench')
  await open({ kind: 'APPLICATION_COPIED', applicationId: 'copied', roundNo: 2 })
  assert.deepEqual(deps.selectedCopy.value, { applicationId: 'copied', roundNo: 2 })
  assert.equal(deps.recordApplicationId.value, '')
})

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
