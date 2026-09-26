import assert from 'node:assert/strict'
import test from 'node:test'
const { SimulationPreview, parseSimulationValues, simulationIssue } = await import(process.env.AGENTFLOW_TEST_SIMULATION)
const input = () => ({ graph: { nodes: [{ id: 'start', name: '开始' }], edges: [] }, formSchema: null, values: { amount: '2.5' } })

test('输入发送不可变快照，编辑清除旧结果与高亮，迟到响应无效', async () => {
  const pending = []
  const preview = new SimulationPreview((body, signal) => new Promise((resolve, reject) => pending.push({ body, signal, resolve, reject })))
  const body = input()
  const first = preview.run(body)
  body.values.amount = '999'
  assert.equal(pending[0].body.values.amount, '2.5')
  preview.clear()
  assert.equal(pending[0].signal.aborted, true)
  pending[0].resolve({ path: ['old'], edgeIds: [], decisions: [] })
  await first
  assert.equal(preview.result, null)
  assert.equal(preview.loading, false)
  const second = preview.run(input())
  pending[1].resolve({ path: ['new'], edgeIds: [], decisions: [] })
  await second
  assert.deepEqual(preview.result.path, ['new'])
  preview.clear()
  assert.equal(preview.result, null)
})

test('旧失败不能覆盖新模拟，表单和图错误只属于当前请求', async () => {
  const pending = []
  const preview = new SimulationPreview(() => new Promise((resolve, reject) => pending.push({ resolve, reject })))
  const old = preview.run(input())
  const current = preview.run(input())
  pending[1].reject({ code: 'FORM_VALIDATION_FAILED', message: '字段缺失', details: { fieldErrors: { amount: 'REQUIRED' } } })
  await current
  pending[0].reject({ message: '旧错误' })
  await old
  assert.equal(preview.error, '字段缺失')
  assert.deepEqual(preview.fieldErrors, { amount: 'REQUIRED' })
  const retry = preview.run(input())
  assert.deepEqual(preview.fieldErrors, {})
  pending[2].reject({ code: 'INVALID_DEFINITION', details: { definitionErrors: ['NODE_DEAD_END:approve'] } })
  await retry
  assert.match(preview.error, /修正流程配置/)
  assert.deepEqual(preview.definitionErrors, ['NODE_DEAD_END:approve'])
  preview.clear()
  assert.deepEqual(preview.definitionErrors, [])
})

test('历史流程测试 JSON 限制结构并防止把不精确数字默认为可靠输入', () => {
  assert.deepEqual(parseSimulationValues('{"amount":"999999999999999999.12","urgent":true,"other":null}'), { amount: '999999999999999999.12', urgent: true, other: null })
  for (const raw of ['[]', 'null', '{', '{"a":{}}', '{"a":[]}', '{"a":9007199254740993}', '{"a":1.5}']) assert.throws(() => parseSimulationValues(raw))
  assert.equal(parseSimulationValues('{"a":10}').a, 10)
  assert.deepEqual(simulationIssue('NODE_DEAD_END:node:part'), { label: '节点没有后续路径', target: 'node:part' })
  assert.equal(simulationIssue('GRAPH_LOOP').target, '')
  assert.equal(simulationIssue('toString').label, 'toString')
})

test('设计试算用只读 POST，携带完整未保存内容与取消信号，不进入写请求恢复', async () => {
  globalThis.localStorage = { getItem: () => 'simulation-token' }
  const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  writeRequests.setActor({ tenantId: 'demo', userId: 'admin' })
  const controller = new AbortController()
  let sent
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json({ path: ['start'], edgeIds: [], decisions: [] }) }
  await api.simulateDesign(input(), controller.signal)
  assert.ok(sent.url.endsWith('/process-definitions/simulate'))
  assert.equal(sent.method, 'POST')
  assert.deepEqual(JSON.parse(sent.body), input())
  assert.equal(sent.signal, controller.signal)
  assert.equal(sent.headers.has('Idempotency-Key'), false)
  globalThis.fetch = async () => { throw new TypeError('offline') }
  await assert.rejects(api.simulateDesign(input(), controller.signal), failure => failure.code === 'NETWORK_ERROR')
  assert.deepEqual(writeRequests.pending(), [])
})

test('条件错误定位保留含冒号的连线标识和字符位置', () => {
  const issue=simulationIssue('INVALID_CONDITION_AT:edge:part:9')
  assert.equal(issue.target,'edge:part');assert.match(issue.label,/第 9 个字符/)
})

test('标识冲突提示说明冲突类型，保留定位标识', () => {
  assert.deepEqual(simulationIssue('NODE_EDGE_ID_CONFLICT:approve'), { label: '连线与节点标识重复，请在高级画布删除该连线后重新连接', target: 'approve' })
  assert.deepEqual(simulationIssue('PROCESS_KEY_CONFLICT:edge:part'), { label: '流程标识与节点或连线标识重复，请更换流程标识', target: 'edge:part' })
})

test('条件升级只读请求携带原图与取消信号，不创建幂等写入', async () => {
  const {api,writeRequests}=await import(process.env.AGENTFLOW_TEST_API)
  const controller=new AbortController(), graph={nodes:[],edges:[],conditionLanguageVersion:1}
  let sent
  globalThis.fetch=async(url,init)=>{sent={url,...init};return Response.json({...graph,conditionLanguageVersion:2})}
  assert.equal((await api.upgradeConditions(graph,controller.signal)).conditionLanguageVersion,2)
  assert.ok(sent.url.endsWith('/process-definitions/upgrade-conditions'))
  assert.deepEqual(JSON.parse(sent.body),{graph});assert.equal(sent.signal,controller.signal)
  assert.equal(sent.headers.has('Idempotency-Key'),false);assert.deepEqual(writeRequests.pending(),[])
})
