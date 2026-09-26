import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import ts from 'typescript'

// 由真实快速编辑函数生成流程，仅向显式指定的独立本机环境写入合成验收数据。
const base = new URL(process.argv[2])
assert.ok(base.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname)
  && base.port && !['8080', '8180', '5193'].includes(base.port) && base.pathname === '/'
  && !base.username && !base.password && !base.search && !base.hash
  && process.argv.length === 4 && process.argv[3] === '--exercise', 'Use an isolated loopback demo port with --exercise')
const source = fileURLToPath(new URL('../src/quickDesigner.ts', import.meta.url))
const temporary = mkdtempSync('/fyoung/tmp/agentflow-quick-parallel-runtime-')
const modulePath = resolve(temporary, 'quickDesigner.mjs')
writeFileSync(modulePath, ts.transpileModule(readFileSync(source, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
const { editQuickGraph, projectQuickGraph, quickStep } = await import(pathToFileURL(modulePath))
const tokens = {}, key = 'quick-parallel-' + randomUUID().slice(0, 8)
let operations = 0
async function call(method, path, user = 'admin', body, status = 200) {
  const headers = { 'Content-Type': 'application/json' }
  if (tokens[user]) headers.Authorization = 'Bearer ' + tokens[user]
  if (method !== 'GET' && !path.startsWith('/auth/')) headers['Idempotency-Key'] = randomUUID()
  const response = await fetch(base.origin + '/api/v1' + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000)
  })
  const value = await response.json()
  assert.equal(response.status, status, JSON.stringify({ path, code: value.code }))
  operations++
  return value
}
const users = ['admin', 'bob', 'finance', 'manager']
for (const user of [...users, 'alice']) tokens[user] = (await call('POST', '/auth/login', 'anonymous', { tenantId: 'demo', username: user, password: 'demo' })).token
const formSchema = { schemaVersion: 1, fields: [{ key: 'amount', label: '金额', type: 'NUMBER', required: true }] }
let graph = { conditionLanguageVersion: 2, nodes: [
  { id: 'start', name: '开始', type: 'START', properties: {} }, { id: 'end', name: '结束', type: 'END', properties: {} }
], edges: [{ id: 'initial', source: 'start', target: 'end', condition: '', defaultBranch: false }] }
graph = editQuickGraph(graph, { kind: 'insert', edgeId: 'initial', type: 'PARALLEL_GATEWAY' })
const fork = graph.nodes.find(node => node.type === 'PARALLEL_GATEWAY' && graph.edges.filter(edge => edge.source === node.id).length === 2)
graph = editQuickGraph(graph, { kind: 'addBranch', nodeId: fork.id })
const branches = quickStep(projectQuickGraph(graph).sequence, fork.id).branches
graph = editQuickGraph(graph, { kind: 'insert', edgeId: branches[0].sequence.tailEdge, type: 'EXCLUSIVE_GATEWAY' })
const choice = graph.nodes.find(node => node.type === 'EXCLUSIVE_GATEWAY' && graph.edges.filter(edge => edge.source === node.id).length === 2)
graph.edges.find(edge => edge.source === choice.id && !edge.defaultBranch).condition = 'amount > 10'
graph = editQuickGraph(graph, { kind: 'insert', edgeId: branches[1].edgeId, type: 'PARALLEL_GATEWAY' })
graph.nodes.filter(node => node.type === 'USER_TASK').forEach((node, index) => {
  node.name = `审批 ${index + 1}`
  node.properties.assigneeRule = 'user:' + users[index % users.length]
})
assert.ok(projectQuickGraph(graph).sequence)
assert.deepEqual((await call('POST', '/process-definitions/validate', 'admin', { graph, formSchema })).errors, [])
const draft = await call('POST', '/process-definitions', 'admin', { key, name: '快速并行运行验收', graph, formSchema })
await call('POST', `/process-definitions/${draft.id}/publish?expectedRevision=0`, 'admin', { changeNote: '三分支、嵌套并行及条件汇合实际运行' })
const cases = []
for (const amount of ['5', '20']) {
  const simulation = await call('POST', '/process-definitions/simulate', 'admin', { graph, formSchema, values: { amount } })
  const expected = graph.nodes.filter(node => node.type === 'USER_TASK' && simulation.path.includes(node.id)).map(node => node.name).sort()
  let application = await call('POST', '/applications', 'alice', { businessNo: `${key}-${amount}`, title: '快速并行验收 ' + amount, processKey: key, definitionVersion: 1, payload: { amount } }, 201)
  application = await call('POST', `/applications/${application.id}/submit`, 'alice', { expectedVersion: application.version })
  const completed = []
  for (let turn = 0; turn <= expected.length; turn++) {
    application = await call('GET', `/applications/${application.id}`, 'alice')
    const available = []
    for (const user of users) for (const task of await call('GET', '/tasks', user)) if (task.applicationId === application.id) available.push({ user, task })
    if (application.status === 'APPROVED') { assert.equal(available.length, 0); break }
    assert.ok(available.length > 0, 'Parallel process must not wait for an unselected conditional path')
    const { user, task } = available[0]
    assert.ok(!completed.includes(task.taskName), 'Each selected approval must execute exactly once')
    await call('POST', `/tasks/${task.taskId}/actions`, user, { action: 'APPROVE', expectedVersion: task.version, comment: '快速并行运行验收' })
    completed.push(task.taskName)
  }
  assert.equal(application.status, 'APPROVED')
  assert.deepEqual([...completed].sort(), expected)
  const diagram = await call('GET', `/applications/${application.id}/rounds/1/diagram`, 'alice')
  assert.ok(diagram.nodes.every(node => node.activeTasks === 0))
  cases.push({ amount, applicationId: application.id, status: application.status, completed })
}
assert.equal(cases[1].completed.length, cases[0].completed.length + 1)
console.log(JSON.stringify({ result: 'PASS', base: base.origin, operations, processKey: key, definitionId: draft.id, nodes: graph.nodes.length, edges: graph.edges.length, cases }))
