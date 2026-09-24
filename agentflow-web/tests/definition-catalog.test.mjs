import test from 'node:test'
import assert from 'node:assert/strict'
const { DefinitionCatalogQuery } = await import(process.env.AGENTFLOW_TEST_CATALOG)

test('目录切换身份和筛选、关闭后均忽略迟到响应', async () => {
  const pending = []
  const query = new DefinitionCatalogQuery((filters, signal) => new Promise((resolve, reject) => pending.push({ filters, signal, resolve, reject })))
  const old = query.load('demo:admin', { q: 'old' })
  const fresh = query.load('other:admin', { q: 'new' })
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve({ items: [{ id: 'new' }] }); await fresh
  pending[0].resolve({ items: [{ id: 'secret' }] }); await old
  assert.deepEqual(query.items, [{ id: 'new' }])
  const closed = query.load('other:admin', {})
  query.clear(); pending[2].reject({ message: 'late error' }); await closed
  assert.deepEqual(query.items, []); assert.equal(query.error, ''); assert.equal(query.loading, false)
})

test('目录冻结查询条件、阻止重复翻页，失败可沿用游标重试并去重', async () => {
  const sent = []; let rejectPage
  const query = new DefinitionCatalogQuery(async filters => {
    sent.push(filters)
    if (sent.length === 1) return { items: [{ id: 'one' }], nextCursor: 'next' }
    if (sent.length === 2) return new Promise((_, reject) => { rejectPage = reject })
    return { items: [{ id: 'one' }, { id: 'two' }] }
  })
  const filters = { q: '合同', status: 'PUBLISHED', processKey: 'contract', version: 2, cursor: 'ignored' }
  await query.load('demo:admin', filters); filters.q = '尚未提交'
  const more = query.more(); await query.more(); assert.equal(sent.length, 2)
  rejectPage({ message: 'read failed' }); await more
  assert.equal(query.nextCursor, 'next'); assert.deepEqual(query.items, [{ id: 'one' }])
  await query.more(); assert.deepEqual(sent[1], sent[2]); assert.equal(sent[2].q, '合同')
  assert.deepEqual(query.items, [{ id: 'one' }, { id: 'two' }]); assert.equal(query.error, '')
})

test('目录首次查询失败清空旧页，空会话不请求，失效游标引导重新查询', async () => {
  let calls = 0
  const query = new DefinitionCatalogQuery(async () => {
    if (++calls === 1) return { items: [{ id: 'one' }], nextCursor: 'old' }
    throw { code: 'INVALID_DEFINITION_QUERY' }
  })
  await query.load('demo:admin', {}); await query.load('demo:admin', { status: 'DRAFT' })
  assert.deepEqual(query.items, []); assert.equal(query.loaded, false); assert.equal(query.nextCursor, null)
  assert.match(query.error, /重新查询/)
  await query.load('', {}); assert.equal(calls, 2)
})

test('目录 API 保留文本和取消信号，不携带写幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'catalog-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let sent
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json({ items: [] }) }
  const controller = new AbortController()
  await api.searchDefinitions({ q: '合同 & %_!', status: 'PUBLISHED', processKey: 'contract', version: 2, limit: 30, cursor: 'x+y/=' }, controller.signal)
  const url = new URL(sent.url, 'http://localhost')
  assert.equal(url.pathname, '/api/v1/process-definitions/search'); assert.equal(url.searchParams.get('q'), '合同 & %_!')
  assert.equal(url.searchParams.get('version'), '2'); assert.equal(url.searchParams.get('cursor'), 'x+y/=')
  assert.equal(sent.signal, controller.signal); assert.equal(sent.headers.has('Idempotency-Key'), false)
})
