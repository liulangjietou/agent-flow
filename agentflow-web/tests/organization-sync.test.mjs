import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { uuid, detail, overview, savedPlan, history, sourceKey } from './organization-sync-fixtures.mjs'
const base = pathToFileURL(process.env.AGENTFLOW_TEST_API)
const model = await import(new URL('./organizationSync.js', base))
const read = await import(new URL('./organizationSyncRead.js', base))
const { api, writeRequests, bindAuthenticationActor } = await import(base)
const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage
afterEach(() => { globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage; bindAuthenticationActor(null) })
const unreadable = error => error.code === 'RESPONSE_UNREADABLE' && error.status === 0

test('六种实际批次阶段与人工计划可读取，客户端不把收到事实当成已应用', () => {
  for (const state of ['QUEUED', 'FETCHING', 'RECEIVED', 'FAILED', 'APPLIED', 'CANCELLED']) {
    const value = detail(state)
    assert.deepEqual(read.readSyncDetail(value, uuid(1)), value)
    assert.deepEqual(read.readSyncTransitions(history(value)), history(value))
  }
  assert.deepEqual(read.readSyncOverview(overview()), overview())
  assert.deepEqual(read.readSyncPlan(savedPlan(), uuid(2), uuid(1), 'tenant'), savedPlan())
})

for (const variant of ['wrong-id', 'missing-delta', 'wrong-source', 'wrong-cursor', 'role-field', 'invalid-person-key', 'fake-application', 'readable-without-stage']) {
  test(`拒绝来源与阶段不一致：${variant}`, () => {
    const value = detail()
    if (variant === 'wrong-id') value.request.id = uuid(90)
    if (variant === 'missing-delta') delete value.state.delta
    if (variant === 'wrong-source') value.state.delta.sourceKey = 'other'
    if (variant === 'wrong-cursor') value.state.delta.afterRevision = 10
    if (variant === 'role-field') value.state.delta.people[0].roles = ['ADMIN']
    if (variant === 'invalid-person-key') value.state.delta.people[0].key.kind = 'APPOINTMENT'
    if (variant === 'fake-application') value.appliedPlanId = uuid(2)
    if (variant === 'readable-without-stage') Object.assign(value.state, { status: 'QUEUED', version: 1 })
    assert.throws(() => read.readSyncDetail(value, uuid(1)), unreadable)
  })
}

test('计划绑定当前租户、批次与标识，残缺前后值不能作为可应用计划', () => {
  for (const args of [[uuid(8), uuid(1), 'tenant'], [uuid(2), uuid(9), 'tenant'], [uuid(2), uuid(1), 'foreign']]) assert.throws(() => read.readSyncPlan(savedPlan(), ...args), unreadable)
  const value = savedPlan(); value.plan.people[0].after.id = uuid(99)
  assert.throws(() => read.readSyncPlan(value, uuid(2), uuid(1), 'tenant'), unreadable)
  const bad = overview(); bad.sourceVersion = '1'; assert.throws(() => read.readSyncOverview(bad), unreadable)
})

test('畸形成功回执仍保留原请求，恢复使用原正文和幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'synthetic-token' }; bindAuthenticationActor({ tenantId: 'tenant', userId: 'recover-admin' })
  const calls = [], body = { expectedSourceVersion: 0, targetDigest: 'a'.repeat(64) }
  globalThis.fetch = async (url, init) => { calls.push({ url, body: init.body, key: init.headers.get('Idempotency-Key') }); return Response.json(calls.length === 1 ? { id: uuid(1), status: 'APPLIED', version: 4 } : { id: uuid(1), status: 'QUEUED', version: 1 }) }
  await assert.rejects(api.queueOrganizationSync(body), unreadable)
  assert.equal(writeRequests.pending().length, 1)
  const recovered = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(recovered.result.status, 'QUEUED'); assert.equal(writeRequests.pending().length, 0)
  assert.equal(calls.length, 2); assert.deepEqual(calls[0], calls[1]); assert.equal(calls[0].body, JSON.stringify(body))
})

test('管理员计划读取保持当前身份与禁止缓存，拒绝返回另一租户的正文', async () => {
  globalThis.localStorage = { getItem: () => 'synthetic-token' }; bindAuthenticationActor({ tenantId: 'tenant', userId: 'admin' })
  let sent
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json(savedPlan(uuid(1), uuid(2), 'foreign')) }
  const controller = new AbortController()
  await assert.rejects(api.organizationSyncPlan(uuid(2), uuid(1), controller.signal), unreadable)
  assert.equal(sent.signal, controller.signal); assert.equal(sent.cache, 'no-store'); assert.equal(sent.headers.has('Idempotency-Key'), false)
})

test('写回执绑定原动作和批次，预检只返回计划标识与预检结果', () => {
  const root = model.syncPath + '/batches/' + uuid(1)
  for (const [path, body, value] of [
    [root + '/apply', { expectedVersion: 3 }, { id: uuid(9), status: 'APPLIED', version: 4 }],
    [root + '/cancel', { expectedVersion: 2 }, { id: uuid(1), status: 'CANCELLED', version: 4 }],
    [root + '/retry', { expectedVersion: 3 }, { id: uuid(1), status: 'QUEUED', version: 1 }],
    [root + '/preflight', { expectedVersion: 3 }, { id: uuid(2), ready: true, people: [{ subject: 'private' }] }]
  ]) assert.throws(() => read.validateSyncReceipt(value, path, JSON.stringify(body)), unreadable)
  assert.deepEqual(read.validateSyncReceipt({ id: uuid(2), ready: false }, root + '/preflight', '{}'), { id: uuid(2), ready: false })
})

test('核对草稿按身份隔离，原预检恢复定位原计划，不自动应用也不覆盖另一账号选择', () => {
  const store = new model.OrganizationSyncDrafts(), choice = { ...sourceKey(), localId: uuid(5), expectedRevision: 2 }
  store.put('a', { ...model.emptySyncDraft(), batchId: uuid(1), selections: [choice] })
  store.put('b', { ...model.emptySyncDraft(), batchId: uuid(8), comment: '其他账号的意见' })
  assert.equal(store.hasDrafts(), true)
  store.acknowledge('a', model.syncPath + '/batches/' + uuid(1) + '/preflight', JSON.stringify({ selections: [choice] }), { id: uuid(2), ready: true })
  assert.equal(store.get('a').planId, uuid(2)); assert.deepEqual(store.get('a').plannedSelections, [choice]); assert.equal(store.get('b').comment, '其他账号的意见')
  const copy = store.get('a'); copy.selections[0].expectedRevision = 99; assert.equal(store.get('a').selections[0].expectedRevision, 2)
  store.clear('b'); assert.equal(store.hasDrafts(), false)
})

test('实际管理接口捕获可以被生产客户端解析，所有十一种操作均覆盖', { skip: !process.env.AGENTFLOW_TEST_SYNC_CAPTURE }, () => {
  const rows = JSON.parse(readFileSync(process.env.AGENTFLOW_TEST_SYNC_CAPTURE, 'utf8')), operations = new Set(); let responses = 0, writes = 0
  for (const row of rows.filter(row => row.status >= 200 && row.status < 300)) {
    const url = new URL(row.path, 'https://local.invalid'), path = url.pathname.replace('/api/v1', ''), value = row.response
    const ids = path.match(/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}/g) ?? []
    if (row.method === 'POST') { read.validateSyncReceipt(value, path, row.rawRequest); writes++ }
    else if (path === model.syncPath) read.readSyncOverview(value)
    else if (path === model.syncPath + '/batches') read.readSyncBatches(value, Number(url.searchParams.get('page') ?? 0), Number(url.searchParams.get('pageSize') ?? 20))
    else if (path.endsWith('/transitions')) read.readSyncTransitions(value)
    else if (path.endsWith('/plans')) read.readSyncPlans(value, Number(url.searchParams.get('page') ?? 0), Number(url.searchParams.get('pageSize') ?? 20))
    else if (path.startsWith(model.syncPath + '/plans/')) read.readSyncPlan(value, ids[0], value.plan.batchId, value.plan.tenantId)
    else read.readSyncDetail(value, ids[0])
    responses++; operations.add(row.method + ' ' + row.template)
  }
  assert.equal(operations.size, 11); assert.ok(responses >= 70); assert.ok(writes >= 30)
  if (process.env.AGENTFLOW_TEST_SYNC_CAPTURE_RESULT) writeFileSync(process.env.AGENTFLOW_TEST_SYNC_CAPTURE_RESULT, JSON.stringify({ responses, successfulWrites: writes, operations: [...operations].sort() }, null, 2) + '\n')
})
