import test from 'node:test'
import assert from 'node:assert/strict'
const { RoundDiagramQuery, traversalRecords } = await import(process.env.AGENTFLOW_TEST_DIAGRAM)

test('流转记录只读取连线证据，不根据已离开的端点推断默认分支', () => {
  const diagram = { nodes: [{ id: 'gate', name: '金额分支', state: 'LEFT' }, { id: 'end', name: '结束', state: 'LEFT' }], edges: [
    { id: 'default', source: 'gate', target: 'end', defaultBranch: true, state: 'NOT_RECORDED', traversalCount: 0 },
    { id: 'recorded', source: 'gate', target: 'finance', defaultBranch: false, state: 'TAKEN', traversalCount: 2,
      firstTakenAt: '2026-09-23T10:00:00Z', lastTakenAt: '2026-09-23T11:00:00Z' }
  ] }
  const before = structuredClone(diagram), records = traversalRecords(diagram)
  assert.equal(records.length, 1); assert.equal(records[0].id, 'recorded')
  assert.equal(records[0].sourceName, '金额分支'); assert.equal(records[0].targetName, 'finance')
  assert.equal(records[0].traversalCount, 2); assert.equal(records[0].lastTakenAt, '2026-09-23T11:00:00Z')
  assert.deepEqual(diagram, before)
})

test('连线记录按首次时间稳定展示，缺失时间保留事实且空证据不生成记录', () => {
  const edge = (id, time) => ({ id, source: 's', target: 't', state: 'TAKEN', traversalCount: 1, firstTakenAt: time })
  assert.deepEqual(traversalRecords({ nodes: [], edges: [edge('z', '2026-09-23T12:00:00Z'), edge('a', '2026-09-23T12:00:00Z'), edge('old', null)] }).map(record => record.id), ['old', 'a', 'z'])
  assert.deepEqual(traversalRecords({ nodes: [], edges: [] }), [])
})

test('轮次或账号切换取消旧请求，迟到成功和失败均不能回填', async () => {
  const pending = []
  const query = new RoundDiagramQuery((id, round, signal) => new Promise((resolve, reject) => pending.push({ id, round, signal, resolve, reject })))
  const first = query.load('demo:alice', 'one', 1)
  const next = query.load('demo:alice', 'one', 2)
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve({ roundNo: 2 }); await next
  pending[0].resolve({ roundNo: 1 }); await first
  assert.equal(query.value.roundNo, 2)
  const late = query.load('demo:alice', 'one', 2)
  query.clear(); pending[2].reject({ message: 'previous account' }); await late
  assert.equal(query.value, null); assert.equal(query.error, ''); assert.equal(query.loading, false)
})

test('读取失败清除旧节点，404 不伪造空图；重试与无轮次状态正确', async () => {
  let calls = 0
  const query = new RoundDiagramQuery(async () => {
    if (++calls === 2) throw { status: 404 }
    return { nodes: [{ state: 'ACTIVE' }] }
  })
  await query.load('demo:alice', 'one', 1); assert.ok(query.value)
  await query.load('demo:alice', 'one', 1); assert.equal(query.value, null); assert.match(query.error, /不可用/)
  await query.load('demo:alice', 'one', 1); assert.ok(query.value); assert.equal(query.error, '')
  await query.load('', 'one', 1); await query.load('demo:alice', 'one', 0)
  assert.equal(calls, 3); assert.equal(query.value, null)
})

test('流程图 API 使用只读方法、精确轮次及取消信号', async () => {
  globalThis.localStorage = { getItem: () => 'diagram-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let sent
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json({ nodes: [] }) }
  const controller = new AbortController()
  await api.roundDiagram('app/id', 2, controller.signal)
  assert.equal(sent.url, '/api/v1/applications/app%2Fid/rounds/2/diagram')
  assert.equal(sent.signal, controller.signal); assert.equal(sent.headers.has('Idempotency-Key'), false)
  assert.ok(sent.method === undefined || sent.method === 'GET')
})
