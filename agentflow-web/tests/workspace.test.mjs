import test from 'node:test'
import assert from 'node:assert/strict'
const { WorkspaceRecordsQuery } = await import(process.env.AGENTFLOW_TEST_WORKSPACE)

test('工作台切换筛选、账号和卸载取消旧查询，迟到成功与失败不能回填', async () => {
  const pending = []
  const query = new WorkspaceRecordsQuery((mode, filters, signal) => new Promise((resolve, reject) => pending.push({ mode, filters, signal, resolve, reject })))
  const old = query.load('demo:alice', 'started', '旧申请')
  const current = query.load('demo:bob', 'drafts', '新申请', 'APPROVED')
  assert.equal(pending[0].signal.aborted, true)
  assert.deepEqual(query.items, [])
  assert.equal(pending[1].filters.status, '')
  pending[1].resolve({ items: [{ id: 'bob-draft' }], nextCursor: null })
  await current
  pending[0].resolve({ items: [{ id: 'alice-secret' }], nextCursor: 'old' })
  await old
  assert.deepEqual(query.items, [{ id: 'bob-draft' }])
  const late = query.load('demo:bob', 'handled', '', 'RETURN')
  assert.deepEqual(pending[2].filters, { q: '', action: 'RETURN', limit: 30 })
  query.clear()
  assert.equal(pending[2].signal.aborted, true)
  pending[2].reject({ message: '旧页面错误' })
  await late
  assert.deepEqual(query.items, []); assert.equal(query.error, ''); assert.equal(query.loading, false)
})

test('追加失败保留当前页并复用原游标重试，刷新失败清除旧记录', async () => {
  const sent = []
  let attempt = 0
  const query = new WorkspaceRecordsQuery(async (mode, filters) => {
    sent.push({ mode, ...filters }); attempt++
    if (attempt === 1) return { items: [{ id: 'a' }], nextCursor: 'page2' }
    if (attempt === 2 || attempt === 4) throw { message: '服务暂不可用' }
    return { items: [{ id: 'a' }, { id: 'b' }] }
  })
  await query.load('demo:alice', 'started', ' 合同 ', 'IN_APPROVAL')
  await query.more()
  assert.equal(query.nextCursor, 'page2'); assert.deepEqual(query.items, [{ id: 'a' }])
  assert.equal(query.error, '服务暂不可用')
  await query.more()
  assert.deepEqual(sent[1], sent[2]); assert.equal(sent[2].q, '合同')
  assert.deepEqual(query.items, [{ id: 'a' }, { id: 'b' }]); assert.equal(query.error, '')
  await query.more(); assert.equal(attempt, 3)
  await query.load('demo:alice', 'drafts')
  assert.deepEqual(query.items, []); assert.equal(query.nextCursor, null); assert.equal(query.loaded, false)
  await query.load('', 'started'); assert.equal(attempt, 4)
})

test('加载更多禁止重复请求，筛选切换忽略旧分页响应', async () => {
  let resolvePage, signal
  let calls = 0
  const query = new WorkspaceRecordsQuery(async (_mode, filters, abort) => {
    calls++
    if (filters.cursor) { signal = abort; return new Promise(resolve => { resolvePage = resolve }) }
    return { items: [{ id: filters.q || 'first' }], nextCursor: 'next' }
  })
  await query.load('demo:manager', 'handled')
  const more = query.more(); await query.more(); assert.equal(calls, 2)
  await query.load('demo:manager', 'handled', '新查询', 'APPROVE')
  assert.equal(signal.aborted, true)
  resolvePage({ items: [{ id: '旧分页' }], nextCursor: null }); await more
  assert.deepEqual(query.items, [{ id: '新查询' }]); assert.equal(query.nextCursor, 'next')
})

test('个人清单只读认证请求正确编码特殊搜索、筛选和游标，携带取消信号', async () => {
  globalThis.localStorage = { getItem: () => 'workspace-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  const sent = []
  globalThis.fetch = async (url, init) => { sent.push({ url, ...init }); return Response.json({ items: [], nextCursor: null }) }
  const controller = new AbortController()
  await api.workspaceApplications({ view: 'drafts', q: '合同 & %_', cursor: 'a+b/=', limit: 30 }, controller.signal)
  await api.workspaceHandled({ action: 'TRANSFER', q: '合同', limit: 30 }, controller.signal)
  const first = new URL(sent[0].url, 'http://localhost')
  assert.equal(first.pathname, '/api/v1/workspace/applications')
  assert.equal(first.searchParams.get('q'), '合同 & %_'); assert.equal(first.searchParams.get('cursor'), 'a+b/=')
  assert.ok(sent[1].url.includes('/workspace/handled?action=TRANSFER'))
  for (const request of sent) {
    assert.equal(request.method, undefined); assert.equal(request.signal, controller.signal)
    assert.equal(request.headers.has('Idempotency-Key'), false)
    assert.equal(request.headers.get('Authorization'), 'Bearer workspace-token')
  }
})
