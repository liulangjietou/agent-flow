import test from 'node:test'
import assert from 'node:assert/strict'
const { AuditSearchQuery } = await import(process.env.AGENTFLOW_TEST_AUDIT_SEARCH)

test('审计检索切换账号和筛选时清除旧结果，迟到响应不能回填', async () => {
  const pending = []
  const query = new AuditSearchQuery((filters, signal) => new Promise((resolve, reject) => pending.push({ filters, signal, resolve, reject })))
  const old = query.load('demo:admin', { q: '旧单据' })
  const current = query.load('other:admin', { q: '新单据' })
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve({ items: [{ id: 'new' }] }); await current
  pending[0].resolve({ items: [{ id: 'secret' }] }); await old
  assert.deepEqual(query.items, [{ id: 'new' }])
  const late = query.load('other:admin', {})
  query.clear(); pending[2].reject({ message: '旧错误' }); await late
  assert.deepEqual(query.items, []); assert.equal(query.error, ''); assert.equal(query.loading, false)
})

test('查询条件冻结、重复翻页阻断，翻页失败沿用原条件及游标重试', async () => {
  const sent = []; let release
  const query = new AuditSearchQuery(async filters => {
    sent.push(filters)
    if (sent.length === 1) return { items: [{ id: 'one' }], nextCursor: 'next' }
    if (sent.length === 2) return new Promise((_, reject) => { release = reject })
    return { items: [{ id: 'one' }, { id: 'two' }] }
  })
  const filters = { q: '合同', action: 'RETURN', source: 'Task', applicationId: 'app-id', actor: 'alice', from: '2020-01-01', to: '2020-01-02', cursor: 'ignored' }
  await query.load('demo:admin', filters)
  filters.q = '未提交的编辑'
  const more = query.more(); await query.more(); assert.equal(sent.length, 2)
  release({ message: '暂时不可用' }); await more
  assert.equal(query.nextCursor, 'next'); assert.deepEqual(query.items, [{ id: 'one' }])
  await query.more(); assert.deepEqual(sent[1], sent[2]); assert.equal(sent[2].q, '合同')
  assert.deepEqual(query.items, [{ id: 'one' }, { id: 'two' }]); assert.equal(query.error, '')
  await query.more(); assert.equal(sent.length, 3)
})

test('刷新失败清除旧记录，空会话不请求，错误游标提示重新查询', async () => {
  let calls = 0
  const query = new AuditSearchQuery(async () => {
    calls++
    if (calls === 1) return { items: [{ id: 'old' }], nextCursor: 'old-next' }
    throw { code: 'INVALID_AUDIT_QUERY', message: 'Invalid query' }
  })
  await query.load('demo:admin', {}); await query.load('demo:admin', { action: 'APPROVE' })
  assert.deepEqual(query.items, []); assert.equal(query.nextCursor, null); assert.equal(query.loaded, false)
  assert.match(query.error, /重新查询/)
  await query.load('', {}); assert.equal(calls, 2); assert.equal(query.error, '')
})

test('审计查询 API 保留精确筛选、特殊文本和取消信号，不携带写请求幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'search-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let sent
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json({ items: [] }) }
  const controller = new AbortController()
  await api.searchAudit({ q: '合同 & %_!', action: 'CANCEL', source: 'Application', applicationId: 'app-id', actor: 'alice', from: '2020-01-01', to: '2020-01-02', limit: 30, cursor: 'a+b/=' }, controller.signal)
  const url = new URL(sent.url, 'http://localhost')
  assert.equal(url.pathname, '/api/v1/operations/audit')
  assert.equal(url.searchParams.get('q'), '合同 & %_!'); assert.equal(url.searchParams.get('cursor'), 'a+b/=')
  assert.equal(url.searchParams.get('applicationId'), 'app-id'); assert.equal(url.searchParams.get('from'), '2020-01-01')
  assert.equal(sent.signal, controller.signal); assert.equal(sent.headers.get('Authorization'), 'Bearer search-token')
  assert.equal(sent.headers.has('Idempotency-Key'), false)
})


test('审计请求超时取消并允许重试，不保留旧分页', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  let signal
  const query = new AuditSearchQuery((_, requestSignal) => { signal = requestSignal; return new Promise(() => {}) })
  const pending = query.load('demo:admin', {})
  context.mock.timers.tick(12001); await pending
  assert.equal(signal.aborted, true); assert.equal(query.loading, false)
  assert.match(query.error, /超时/); assert.equal(query.nextCursor, null)
  query.clear(); assert.equal(query.error, '')
})
