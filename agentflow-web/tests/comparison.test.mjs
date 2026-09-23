import assert from 'node:assert/strict'
import test from 'node:test'
const { DefinitionComparisonQuery, comparisonBaselines, comparisonProperty, comparisonValue } = await import(process.env.AGENTFLOW_TEST_COMPARISON)
const input = () => ({ key: 'leave', name: '请假', graph: { nodes: [], edges: [] }, formSchema: null })
const response = id => ({ baseline: { id, key: 'leave', name: '请假', version: 1 }, changes: [] })

test('比较基线只含同 key 的发布版本，版本降序且不修改原列表', () => {
  const versions = [
    { id: 'one', key: 'leave', status: 'PUBLISHED', version: 1 },
    { id: 'draft', key: 'leave', status: 'DRAFT', version: 0 },
    { id: 'foreign', key: 'expense', status: 'PUBLISHED', version: 9 },
    { id: 'two', key: 'leave', status: 'PUBLISHED', version: 2 }
  ]
  assert.deepEqual(comparisonBaselines(versions, ' leave ').map(item => item.id), ['two', 'one'])
  assert.equal(versions[0].id, 'one')
  assert.deepEqual(comparisonBaselines(versions, 'new'), [])
})

test('当前设计按快照发送，切换或编辑后取消旧比较，迟到响应不回填', async () => {
  const pending = []
  const query = new DefinitionComparisonQuery((id, body, signal) => new Promise((resolve, reject) => pending.push({ id, body, signal, resolve, reject })))
  const body = input()
  const old = query.run('v1', body)
  body.name = '后续编辑'
  assert.equal(pending[0].body.name, '请假')
  const current = query.run('v2', input())
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve(response('v2'))
  await current
  pending[0].resolve(response('v1'))
  await old
  assert.equal(query.result.baseline.id, 'v2')
  query.clear()
  assert.equal(query.result, null)
  assert.equal(query.loading, false)
})

test('当前失败清空旧差异并给出稳定提示，旧失败不覆盖新结果', async () => {
  const pending = []
  const query = new DefinitionComparisonQuery(() => new Promise((resolve, reject) => pending.push({ resolve, reject })))
  const old = query.run('v1', input())
  const current = query.run('v2', input())
  pending[1].resolve(response('v2')); await current
  pending[0].reject({ message: '旧错误' }); await old
  assert.equal(query.error, '')
  const failure = query.run('v1', input())
  assert.equal(query.result, null)
  pending[2].reject({ code: 'COMPARISON_ID_AMBIGUOUS' }); await failure
  assert.match(query.error, /标识重复/)
  query.clear(); assert.equal(query.error, '')
})

test('差异值保留精度、false、空值、选项与未知属性，不读取原型成员', () => {
  assert.equal(comparisonValue('1.000000000000000001', 'minimum'), '1.000000000000000001')
  assert.equal(comparisonValue(false), '否')
  assert.equal(comparisonValue(null), '未设置')
  assert.equal(comparisonValue(''), '空值')
  assert.equal(comparisonValue('USER_TASK', 'type'), '人工审批')
  assert.equal(comparisonValue('role:FINANCE', 'properties.assigneeRule'), '财务审批组')
  assert.equal(comparisonValue(['a', 'b'], 'branchOrder'), 'a → b')
  assert.match(comparisonValue([{ value: 'A', label: '选项甲' }]), /保存值：A\n字段名称：选项甲/)
  assert.equal(comparisonProperty('toString'), 'toString')
  assert.equal(comparisonValue('<img onerror=alert(1)>'), '<img onerror=alert(1)>')
})

test('只读比较 POST 编码基线标识，携带取消信号且不会生成写恢复任务', async () => {
  globalThis.localStorage = { getItem: () => 'comparison-test-token' }
  const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  writeRequests.setActor({ tenantId: 'demo', userId: 'admin' })
  const controller = new AbortController()
  let sent
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json(response('baseline')) }
  await api.compareDefinition('one/two', input(), controller.signal)
  assert.ok(sent.url.endsWith('/process-definitions/one%2Ftwo/compare'))
  assert.equal(sent.method, 'POST')
  assert.equal(sent.signal, controller.signal)
  assert.equal(sent.headers.has('Idempotency-Key'), false)
  assert.deepEqual(JSON.parse(sent.body), input())
  globalThis.fetch = async () => { throw new TypeError('offline') }
  await assert.rejects(api.compareDefinition('baseline', input(), controller.signal), error => error.code === 'NETWORK_ERROR')
  assert.deepEqual(writeRequests.pending(), [])
})
