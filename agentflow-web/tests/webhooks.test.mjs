import test from 'node:test'
import assert from 'node:assert/strict'
const { WebhookQuery, WebhookRead, webhookRetryable, webhookOverviewFilters } = await import(process.env.AGENTFLOW_TEST_WEBHOOKS)

test('切换租户、筛选和详情时取消旧读取，迟到响应不能回填', async () => {
  const pending = []
  const query = new WebhookQuery((filters, signal) => new Promise(resolve => pending.push({ filters, signal, resolve })))
  const first = query.load('demo:admin', { target: 'erp' })
  const second = query.load('other:admin', { status: 'FAILED' })
  pending[1].resolve({ items: [{ id: 'current' }] }); await second
  pending[0].resolve({ items: [{ id: 'private' }] }); await first
  assert.equal(pending[0].signal.aborted, true); assert.deepEqual(query.items, [{ id: 'current' }])
  const detail = new WebhookRead(); let resolve, signal
  const old = detail.load(s => { signal = s; return new Promise(r => { resolve = r }) })
  detail.clear(); resolve({ delivery: { id: 'private' } }); await old
  assert.equal(signal.aborted, true); assert.equal(detail.value, null)
})

test('投递翻页失败保留条件与游标，不重复加载同一页', async () => {
  const sent = []; let reject
  const query = new WebhookQuery(async filters => {
    sent.push(filters)
    if (sent.length === 1) return { items: [{ id: 'one' }], nextCursor: 'cursor' }
    if (sent.length === 2) return new Promise((_, r) => { reject = r })
    return { items: [{ id: 'one' }, { id: 'two' }] }
  })
  const filters = { target: 'erp', status: 'FAILED' }
  await query.load('demo:admin', filters); filters.target = 'edited'
  const more = query.more(); await query.more(); reject({ message: 'offline' }); await more
  assert.equal(query.nextCursor, 'cursor'); await query.more()
  assert.equal(sent.length, 3); assert.deepEqual(sent[1], sent[2]); assert.equal(sent[2].target, 'erp')
  assert.deepEqual(query.items.map(x => x.id), ['one', 'two'])
})

test('永不返回的详情请求受超时限制，清除后可重新加载', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const detail = new WebhookRead(); let signal
  const request = detail.load(s => { signal = s; return new Promise(() => {}) })
  context.mock.timers.tick(12001); await request
  assert.equal(signal.aborted, true); assert.match(detail.error, /超时/); assert.equal(detail.loading, false)
  await detail.load(async () => ({ id: 'new' })); assert.deepEqual(detail.value, { id: 'new' }); assert.equal(detail.error, '')
})

test('人工重试发送原版本，未知响应通过原幂等键恢复，不携带目的地覆盖', async () => {
  globalThis.localStorage = { getItem: () => 'token' }
  const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  writeRequests.setActor({ tenantId: 'demo', userId: 'admin' })
  const sent = []
  globalThis.fetch = async (url, init) => {
    sent.push({ url, ...init })
    if (sent.length === 1) throw new Error('connection lost')
    return Response.json({ id: 'delivery', status: 'PENDING', version: 4 })
  }
  await assert.rejects(api.retryWebhook('delivery', 3))
  assert.equal(writeRequests.pending().length, 1)
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent.length, 2); assert.equal(sent[0].url, '/api/v1/integrations/webhooks/deliveries/delivery/retry')
  assert.equal(sent[0].body, '{"expectedVersion":3}'); assert.equal(sent[0].body, sent[1].body)
  assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
  assert.equal(writeRequests.pending().length, 0)
  for (const status of ['PENDING', 'IN_FLIGHT']) assert.equal(webhookRetryable({ status }), false)
  for (const status of ['FAILED', 'DELIVERED', 'RETRY_WAIT']) assert.equal(webhookRetryable({ status }), true)
})


test('投递概况只发送已查询的目的地和申请，状态分页不改变统计范围', async () => {
  globalThis.localStorage = { getItem: () => 'overview-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  const fields = { target: 'erp', applicationId: '12345678-1234-1234-1234-123456789012', status: 'FAILED', limit: 30, cursor: 'next' }
  const filters = webhookOverviewFilters(fields); fields.target = 'edited'
  assert.deepEqual(filters, { target: 'erp', applicationId: fields.applicationId })
  assert.deepEqual(webhookOverviewFilters({ status: 'FAILED', target: '' }), {})
  let sent
  const value = { queriedAt: '2026-09-24T00:00:00Z', total: 40, pending: 20, inFlight: 3, retryWait: 5, delivered: 7, failed: 5 }
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json(value) }
  const signal = new AbortController().signal
  assert.deepEqual(await api.webhookOverview(filters, signal), value)
  const url = new URL(sent.url, 'http://localhost')
  assert.equal(url.pathname, '/api/v1/integrations/webhooks/overview')
  assert.deepEqual(Object.fromEntries(url.searchParams), filters)
  assert.equal(sent.signal, signal); assert.equal(sent.headers.get('Authorization'), 'Bearer overview-token')
  assert.equal(sent.headers.has('Idempotency-Key'), false)
})

test('概况加载和失败清除旧数字，跨账号迟到响应不能恢复，空结果才显示零', async () => {
  const state = new WebhookRead()
  await state.load(async () => ({ total: 42 }))
  let finish, signal
  const old = state.load(s => { signal = s; return new Promise(resolve => { finish = resolve }) })
  assert.equal(state.value, null); assert.equal(state.loading, true)
  const current = state.load(async () => { throw { message: 'Unavailable' } }); await current
  finish({ total: 99 }); await old
  assert.equal(signal.aborted, true); assert.equal(state.value, null); assert.equal(state.error, 'Unavailable')
  await state.load(async () => ({ total: 0, pending: 0, inFlight: 0, retryWait: 0, delivered: 0, failed: 0 }))
  assert.equal(state.value.total, 0); assert.equal(state.error, '')
  state.clear(); assert.equal(state.value, null)
})
