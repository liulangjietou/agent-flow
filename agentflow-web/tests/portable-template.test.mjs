import test from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'

globalThis.localStorage = { getItem: () => 'test-token' }
const { parsePortableTemplate, serializePortableTemplate, PortableTemplateReview, TEMPLATE_FILE_LIMIT } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_PORTABLE))
const { api, writeRequests } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_API))
const { defaultFormSchema } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_FORMS))
const graph = { nodes: [
  { id: 'start', name: '开始', type: 'START', properties: { x: '40', y: '0' } },
  { id: 'review', name: '复核 🦉', type: 'USER_TASK', properties: { assigneeRule: 'role:MANAGER', approvalMode: 'ALL' } },
  { id: 'end', name: '结束', type: 'END', properties: {} }
], edges: [{ id: 'e1', source: 'start', target: 'review', condition: '', defaultBranch: false }, { id: 'e2', source: 'review', target: 'end', condition: '', defaultBranch: false }] }
const source = () => ({ key: 'source-key', name: '原流程', graph: structuredClone(graph), formSchema: defaultFormSchema() })
const file = value => ({ size: new TextEncoder().encode(value).length, text: async () => value })
const envelope = () => JSON.parse(serializePortableTemplate(source()))
const deferred = () => { let resolve, reject; const promise = new Promise((ok, no) => { resolve = ok; reject = no }); return { promise, resolve, reject } }

test('导出投影只保留配置，完整往返节点顺序、表单和精确数值，不改变原对象', () => {
  const value = { ...source(), id: 'private-id', tenantId: 'private-tenant', status: 'PUBLISHED', publication: { actor: 'secret' }, payload: { note: 'private' } }
  value.formSchema.fields[0].minimum = '9007199254740993.010000000000000001'
  value.formSchema.fields[1].helpText = '逐项说明\n保留原文 <script>不执行</script>'
  const before = structuredClone(value), raw = serializePortableTemplate(value)
  const parsed = parsePortableTemplate('\uFEFF' + raw)
  assert.deepEqual(parsed, { key: value.key, name: value.name, graph: value.graph, formSchema: value.formSchema })
  assert.deepEqual(value, before)
  assert.doesNotMatch(raw, /private-id|private-tenant|PUBLISHED|secret|private/)
  assert.equal(parsed.formSchema.fields[0].minimum, '9007199254740993.010000000000000001')
  parsed.graph.nodes[0].name = '独立副本'
  assert.equal(value.graph.nodes[0].name, '开始')
})

test('无表单与显式空表单往返不混淆', () => {
  for (const formSchema of [null, { schemaVersion: 1, fields: [] }]) {
    assert.deepEqual(parsePortableTemplate(serializePortableTemplate({ ...source(), formSchema })).formSchema, formSchema)
  }
})

test('格式版本、租户数据、脚本扩展、原型属性、非白名单节点整份拒绝', () => {
  const changes = [p => { p.formatVersion = 2 }, p => { p.tenantId = 'foreign' }, p => { p.process.id = 'foreign' },
    p => { p.process.graph.nodes[1].properties.delegateExpression = '${bean.run()}' },
    p => { p.process.graph.nodes[1].type = 'SERVICE_TASK' }, p => { p.process.formSchema.fields[0].readRoles = ['ADMIN'] },
    p => { p.process.graph.nodes[0].properties.x = 'Infinity' }, p => { p.process.graph.edges[0].defaultBranch = 'false' }]
  for (const change of changes) { const p = envelope(); change(p); assert.throws(() => parsePortableTemplate(JSON.stringify(p))) }
  assert.throws(() => parsePortableTemplate('{"format":"agentflow-process-template","formatVersion":1,"__proto__":{},"process":{}}'))
  assert.equal({}.polluted, undefined)
})

test('畸形表单不依赖类型断言通过：null、字符串、重复字段与不精确数字都拒绝', () => {
  const changes = [p => { p.process.formSchema.fields = null }, p => { p.process.formSchema.fields[0] = null },
    p => { p.process.formSchema.fields[0].required = 'true' }, p => { p.process.formSchema.fields[0].minimum = 0.1 },
    p => { p.process.formSchema.fields[0].options = [{ label: '选项', value: 'x' }] },
    p => { p.process.formSchema.fields[0].options = [null] }, p => { p.process.formSchema.fields.push(p.process.formSchema.fields[0]) }]
  for (const change of changes) { const p = envelope(); change(p); assert.throws(() => parsePortableTemplate(JSON.stringify(p))) }
})

test('文件大小按 UTF-8 字节检查，节点和连线有界，未知格式不兜底', async () => {
  assert.throws(() => parsePortableTemplate('中'.repeat(Math.floor(TEMPLATE_FILE_LIMIT / 3) + 1)), /1 MiB/)
  assert.throws(() => parsePortableTemplate('{bad json'), /JSON/)
  const p = envelope(); p.process.graph.nodes = Array(201).fill(graph.nodes[0])
  assert.throws(() => parsePortableTemplate(JSON.stringify(p)), /200/)
  p.process.graph.nodes = graph.nodes; p.process.graph.edges = Array(401).fill(graph.edges[0])
  assert.throws(() => parsePortableTemplate(JSON.stringify(p)), /400/)
  const review = new PortableTemplateReview(async () => ({ errors: [] }))
  await review.read({ size: TEMPLATE_FILE_LIMIT + 1, text: () => { throw Error('should not read') } })
  assert.match(review.error, /1 MiB/); assert.equal(review.value, null); assert.equal(review.canImport, false)
})

test('换文件后忽略旧读取成功与失败，离开后不留下模板', async () => {
  const review = new PortableTemplateReview(async () => ({ errors: [] })), a = deferred(), b = deferred()
  const first = review.read({ size: 100, text: () => a.promise })
  const second = review.read({ size: 100, text: () => b.promise })
  b.resolve(serializePortableTemplate({ ...source(), name: '最新文件' }))
  assert.equal((await second).name, '最新文件')
  a.resolve(serializePortableTemplate(source()))
  assert.equal(await first, undefined); assert.equal(review.value.name, '最新文件')
  const late = deferred(), pending = review.read({ size: 100, text: () => late.promise })
  review.clear(); late.reject(new Error('old file failure')); await pending
  assert.equal(review.value, null); assert.equal(review.error, ''); assert.equal(review.loading, false)
})

test('审批人不可用允许待修改草稿，结构错误阻断，检查中禁止重复检查', async () => {
  let errors = ['ASSIGNEE_NOT_AVAILABLE:review'], calls = 0
  const review = new PortableTemplateReview(async () => { calls++; return { errors } })
  await review.read(file(serializePortableTemplate(source())))
  assert.equal(review.canImport, false)
  const check = review.check(); await review.check(); await check
  assert.equal(calls, 1); assert.equal(review.canImport, true)
  errors = ['GRAPH_LOOP']; await review.check(); assert.equal(review.canImport, false)
  errors = []; await review.check(); assert.equal(review.canImport, true)
})

test('取消预检会中止请求，旧成功和失败不能更新新文件；失败后可以重新检查', async () => {
  const requests = []
  const review = new PortableTemplateReview((_g, _s, signal) => { const request = { ...deferred(), signal }; requests.push(request); return request.promise })
  await review.read(file(serializePortableTemplate(source())))
  const old = review.check()
  await review.read(file(serializePortableTemplate({ ...source(), name: '新版' })))
  assert.equal(requests[0].signal.aborted, true)
  requests[0].resolve({ errors: [] }); await old
  assert.equal(review.reviewed, false); assert.equal(review.canImport, false)
  const failed = review.check(); requests[1].reject({ message: 'network failed' }); await failed
  assert.equal(review.canImport, false); assert.equal(review.error, 'network failed')
  const retry = review.check(); requests[2].resolve({ errors: [] }); await retry
  assert.equal(review.canImport, true); assert.equal(review.error, '')
})

test('检查超时释放界面并阻断迟到成功', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const request = deferred(), review = new PortableTemplateReview(() => request.promise)
  await review.read(file(serializePortableTemplate(source())))
  const check = review.check(); context.mock.timers.tick(12_000); await check
  assert.equal(review.loading, false); assert.equal(review.canImport, false); assert.match(review.error, /超时/)
  request.resolve({ errors: [] }); await Promise.resolve()
  assert.equal(review.reviewed, false)
})

test('预检携带取消信号且不使用写请求键，创建使用既有幂等链路不自动发布', async () => {
  writeRequests.setActor({ tenantId: 'portable', userId: 'admin' })
  const sent = [], controller = new AbortController(), body = parsePortableTemplate(serializePortableTemplate(source()))
  globalThis.fetch = async (url, init) => { sent.push({ url, ...init }); return Response.json(url.endsWith('/validate') ? { errors: [] } : { id: 'new', status: 'DRAFT' }) }
  await api.validateDefinition(body.graph, body.formSchema, controller.signal)
  assert.equal(sent[0].signal, controller.signal); assert.equal(sent[0].headers.has('Idempotency-Key'), false)
  assert.equal((await api.definition(body)).status, 'DRAFT')
  assert.ok(sent[1].headers.has('Idempotency-Key')); assert.deepEqual(JSON.parse(sent[1].body), body)
  assert.equal(sent.length, 2); assert.ok(sent[1].url.endsWith('/process-definitions'))
})

test('导入响应丢失后复用原请求，改目标不可替代原请求', async () => {
  writeRequests.setActor({ tenantId: 'portable', userId: 'recovery' })
  const sent = [], body = source()
  globalThis.fetch = async (url, init) => { sent.push({ url, ...init }); if (sent.length === 1) throw new TypeError('lost'); return Response.json({ id: 'original-draft', status: 'DRAFT' }) }
  await assert.rejects(api.definition(body))
  await assert.rejects(api.definition({ ...body, key: 'different' }), error => error.code === 'PENDING_REQUEST_CHANGED')
  const result = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(result.result.id, 'original-draft'); assert.equal(sent.length, 2)
  assert.equal(sent[0].body, sent[1].body); assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
})

test('版本二明细模板完整往返，嵌套与未知列属性整份拒绝', () => {
  const value = source(); value.formSchema = { schemaVersion: 2, fields: [{ key: 'items', label: '明细', type: 'TABLE', required: true, maxRows: 4,
    columns: [{ key: 'amount', label: '金额', type: 'NUMBER', required: true, minimum: '0000.01' }] }] }
  assert.deepEqual(parsePortableTemplate(serializePortableTemplate(value)), value)
  for (const mutate of [s => s.fields[0].columns[0].hidden = true, s => s.fields[0].columns[0].type = 'TABLE', s => s.fields[0].columns = null,
    s => s.fields[0].maxRows = '4', s => s.schemaVersion = 1, s => s.fields[0].columns.push({ ...s.fields[0].columns[0] })]) {
    const bad = structuredClone(value); mutate(bad.formSchema); assert.throws(() => serializePortableTemplate(bad))
  }
})

test('导入导出保留条件语言版本，未知或强制转换版本拒绝', () => {
  for (const version of [undefined,1,2]) {
    const original=source()
    if(version!==undefined)original.graph.conditionLanguageVersion=version
    assert.deepEqual(parsePortableTemplate(serializePortableTemplate(original)).graph,original.graph)
  }
  for (const version of [null,0,3,'2',2.5,true]) {
    const value=envelope();value.process.graph.conditionLanguageVersion=version
    assert.throws(()=>parsePortableTemplate(JSON.stringify(value)),/版本/)
  }
})

test('区间遗漏可导入待修正草稿，结构错误仍阻止导入', async () => {
  let errors=['BRANCH_COVERAGE_GAP:route']
  const review=new PortableTemplateReview(async()=>({errors,branchDiagnostics:[]}))
  await review.read(file(serializePortableTemplate(source())));await review.check()
  assert.equal(review.canImport,true)
  errors.push('INVALID_CONDITION:route');await review.check();assert.equal(review.canImport,false)
})
