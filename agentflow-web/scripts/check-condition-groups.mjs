import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import ts from 'typescript'

// 仅在调用者明确指定的隔离本机环境创建合成数据，保留全部验收记录。
const base = new URL(process.argv[2])
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname) && base.pathname === '/' && process.argv[3] === '--exercise')
assert.ok(['8082', '8083'].includes(base.port), 'Use an isolated preview port')
const root = fileURLToPath(new URL('../', import.meta.url))
const temporary = mkdtempSync('/fyoung/tmp/agentflow-group-runtime-')
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}')
for (const name of ['conditionGroups', 'conditionBuilder', 'conditionSyntax', 'formSchema']) {
  writeFileSync(resolve(temporary, name + '.js'), ts.transpileModule(readFileSync(resolve(root, 'src/' + name + '.ts'), 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
}
const { parseConditionGroup, serializeConditionGroup } = await import(pathToFileURL(resolve(temporary, 'conditionGroups.js')))
const fixture = JSON.parse(readFileSync(resolve(root, '../agentflow-domain/src/test/resources/condition-group-cases.json'), 'utf8'))
const formSchema = { schemaVersion: 1, fields: fixture.fields }
const tokens = {}, prefix = 'groups-' + randomUUID().slice(0, 8)
async function call(method, path, user = 'admin', body, status = 200) {
  const headers = { 'Content-Type': 'application/json' }
  if (tokens[user]) headers.Authorization = 'Bearer ' + tokens[user]
  if (method !== 'GET' && !path.startsWith('/auth/')) headers['Idempotency-Key'] = randomUUID()
  const response = await fetch(base.origin + '/api/v1' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
  const value = await response.json()
  assert.equal(response.status, status, JSON.stringify({ path, code: value.code, message: value.message }))
  return value
}
for (const user of ['admin', 'alice', 'manager', 'finance']) {
  tokens[user] = (await call('POST', '/auth/login', 'anonymous', { tenantId: 'demo', username: user, password: 'demo' })).token
}
const reports = []
let editableId
for (const [index, test] of fixture.cases.entries()) {
  const key = prefix + '-' + index
  const condition = serializeConditionGroup(parseConditionGroup(test.source, fixture.fields, 2), fixture.fields)
  assert.equal(condition, test.serialized)
  const graph = {
    conditionLanguageVersion: 2,
    nodes: [
      { id: 'start', name: '开始', type: 'START', properties: {} },
      { id: 'gate', name: '条件分流', type: 'EXCLUSIVE_GATEWAY', properties: {} },
      { id: 'match', name: '命中条件', type: 'USER_TASK', properties: { assigneeRule: 'user:manager' } },
      { id: 'fallback', name: '默认分支', type: 'USER_TASK', properties: { assigneeRule: 'user:finance' } },
      { id: 'end', name: '结束', type: 'END', properties: {} }
    ],
    edges: [
      { id: 'begin', source: 'start', target: 'gate', condition: '', defaultBranch: false },
      { id: 'yes', source: 'gate', target: 'match', condition, defaultBranch: false },
      { id: 'no', source: 'gate', target: 'fallback', condition: '', defaultBranch: true },
      { id: 'finish-yes', source: 'match', target: 'end', condition: '', defaultBranch: false },
      { id: 'finish-no', source: 'fallback', target: 'end', condition: '', defaultBranch: false }
    ]
  }
  assert.deepEqual((await call('POST', '/process-definitions/validate', 'admin', { graph, formSchema })).errors, [])
  const draft = await call('POST', '/process-definitions', 'admin', { key, name: '条件组验收 ' + test.name, graph, formSchema })
  const saved = await call('GET', '/process-definitions/' + draft.id)
  assert.equal(saved.graph.edges.find(edge => edge.id === 'yes').condition, condition)
  await call('POST', '/process-definitions/' + draft.id + '/publish?expectedRevision=0', 'admin', { changeNote: '验证可视化条件组真实执行' })
  const cases = []
  for (const [sampleIndex, sample] of test.samples.entries()) {
    const simulation = await call('POST', '/process-definitions/simulate', 'admin', { graph, formSchema, values: sample.values })
    assert.equal(simulation.path.includes('match'), sample.expected)
    assert.equal(simulation.path.includes('fallback'), !sample.expected)
    let app = await call('POST', '/applications', 'alice', { businessNo: key + '-' + sampleIndex, processKey: key, definitionVersion: 1, title: '条件组样本 ' + sampleIndex, payload: sample.values }, 201)
    app = await call('POST', '/applications/' + app.id + '/submit', 'alice', { expectedVersion: app.version })
    const user = sample.expected ? 'manager' : 'finance'
    const tasks = await call('GET', '/tasks', user)
    const task = tasks.find(item => item.applicationId === app.id)
    assert.ok(task, 'Expected approver did not receive the task')
    const otherTasks = await call('GET', '/tasks', sample.expected ? 'finance' : 'manager')
    assert.ok(!otherTasks.some(item => item.applicationId === app.id))
    await call('POST', '/tasks/' + task.taskId + '/actions', user, { action: 'APPROVE', expectedVersion: app.version, comment: '条件组运行验收' })
    app = await call('GET', '/applications/' + app.id, 'alice')
    assert.equal(app.status, 'APPROVED')
    cases.push({ applicationId: app.id, expected: sample.expected, approver: user, status: app.status })
  }
  reports.push({ name: test.name, definitionId: draft.id, cases })
  if (index === 1) editableId = (await call('POST', '/process-definitions', 'admin', { key: prefix + '-edit', name: '嵌套条件组浏览器验收', graph, formSchema })).id
}
console.log(JSON.stringify({ result: 'PASS', base: base.origin, prefix, editableId, samples: reports.reduce((sum, report) => sum + report.cases.length, 0), reports }))
