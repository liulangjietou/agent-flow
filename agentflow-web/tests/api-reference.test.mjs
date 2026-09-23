import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
const { entries, filterEntries, curlExample, ApiReferenceQuery } = await import(process.env.AGENTFLOW_TEST_API_REFERENCE)
const document = JSON.parse(readFileSync(new URL('../../agentflow-server/src/main/resources/api/openapi.json', import.meta.url)))
test('接口搜索按实际路径、方法、分组和标题组合', () => {
  const catalog = entries(document)
  assert.equal(catalog.length, Object.values(document.paths).reduce((count, methods) => count + Object.keys(methods).length, 0))
  assert.equal(filterEntries(catalog, 'assignee-options', '流程设计')[0].operation.operationId, 'definitionAssigneeOptions')
  assert.deepEqual(filterEntries(catalog, ' publish ', '流程设计').map(e => e.operation.operationId), ['publishDefinition'])
  assert.equal(filterEntries(catalog, 'publish', '任务').length, 0)
})
test('curl 保留发布查询版本和令牌占位，只读 POST 不增加幂等键', () => {
  const catalog = entries(document), find = id => catalog.find(e => e.operation.operationId === id)
  assert.match(curlExample(find('publishDefinition')), /publish\?expectedRevision=0/)
  assert.match(curlExample(find('publishDefinition')), /Idempotency-Key: \$REQUEST_KEY/)
  assert.match(curlExample(find('publishDefinition')), /Bearer \$TOKEN/)
  assert.doesNotMatch(curlExample(find('login')), /Authorization/)
  assert.doesNotMatch(curlExample(find('previewDefinition')), /Idempotency-Key/)
  const escaped = structuredClone(find('publishDefinition'))
  escaped.operation.requestBody.content['application/json'].example.changeNote = "a'$(touch nope)"
  assert.ok(curlExample(escaped).includes("a'\"'\"'$(touch nope)"))
})
test('切换与取消忽略旧文档和失败，刷新失败清除旧文档并可重试', async () => {
  const pending = []
  const query = new ApiReferenceQuery(signal => new Promise((resolve, reject) => pending.push({signal, resolve, reject})))
  const a = query.load('demo:alice'), b = query.load('demo:admin')
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve(document); await b
  pending[0].reject(new Error('old')); await a
  assert.equal(query.error, ''); assert.equal(query.document, document)
  const c = query.load('demo:admin'); assert.equal(query.document, null)
  pending[2].reject(new Error('offline')); await c; assert.equal(query.error, 'offline')
  const d = query.load('demo:admin'); pending[3].resolve(document); await d
  query.clear(); assert.equal(query.document, null)
  await query.load(''); assert.equal(pending.length, 4)
})
test('契约加载是认证 GET，不携带写入键或业务参数', async () => {
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  globalThis.localStorage = { getItem: () => 'test-session' }
  let sent
  globalThis.fetch = async (url, init) => { sent = {url, ...init}; return Response.json(document) }
  const controller = new AbortController()
  await api.openApi(controller.signal)
  assert.ok(sent.url.endsWith('/api/v1/openapi.json'))
  assert.equal(sent.headers.get('Authorization'), 'Bearer test-session')
  assert.equal(sent.headers.has('Idempotency-Key'), false)
  assert.equal(sent.signal, controller.signal)
  assert.equal(sent.body, undefined)
})
