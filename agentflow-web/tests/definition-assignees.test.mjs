import test from 'node:test'
import assert from 'node:assert/strict'
const { assigneeLabel, DefinitionAssigneesQuery } = await import(process.env.AGENTFLOW_TEST_ASSIGNEES)

test('指定账号与角色可读，未知已有配置不被改为空值', () => {
  assert.equal(assigneeLabel('user:bob'), '指定账号 · bob')
  assert.equal(assigneeLabel('role:FINANCE'), '财务审批组')
  assert.equal(assigneeLabel('role:CUSTOM'), '角色 · CUSTOM')
  assert.equal(assigneeLabel('legacy'), 'legacy')
  assert.equal(assigneeLabel(''), '待配置')
})

test('切换会话和关闭面板会撤销目录请求，迟到结果与错误不能覆盖当前名单', async () => {
  const pending = []
  const query = new DefinitionAssigneesQuery(signal => new Promise((resolve, reject) => pending.push({ signal, resolve, reject })))
  const old = query.load('a'), current = query.load('b')
  assert.equal(pending[0].signal.aborted, true)
  const options = [{ rule: 'user:bob', label: 'bob', memberCount: 1 }]
  pending[1].resolve(options); await current
  pending[0].resolve([{ rule: 'user:other-tenant' }]); await old
  assert.deepEqual(query.options, options); assert.equal(query.loaded, true)
  const last = query.load('b'); query.clear(); pending[2].reject(new Error('旧目录错误')); await last
  assert.deepEqual(query.options, []); assert.equal(query.error, ''); assert.equal(query.loaded, false)
})

test('失败清除旧候选人，空会话不发请求，重试重新读取真实名单', async () => {
  let calls = 0
  const query = new DefinitionAssigneesQuery(async () => { if (++calls === 2) throw new Error('连接失败'); return [{ rule: 'role:FINANCE', label: 'FINANCE', memberCount: calls }] })
  await query.load('scope'); await query.load('scope')
  assert.deepEqual(query.options, []); assert.equal(query.error, '连接失败'); assert.equal(query.loaded, false)
  await query.load(''); assert.equal(calls, 2)
  await query.load('scope'); assert.equal(query.options[0].memberCount, 3); assert.equal(query.error, '')
})

test('审批人读取带认证和取消信号，不使用业务幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'test-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  const requests = []
  globalThis.fetch = async (url, init) => { requests.push({ url, ...init }); return Response.json([]) }
  const controller = new AbortController()
  await api.definitionAssignees(controller.signal)
  assert.ok(requests[0].url.endsWith('/process-definitions/assignee-options'))
  assert.equal(requests[0].signal, controller.signal)
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer test-token')
  assert.equal(requests[0].headers.has('Idempotency-Key'), false)
})
