import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import ts from 'typescript'

// 仅向独立本机演示环境创建合成申请，保留退回、撤回和重提记录供浏览器验收。
const origin = new URL(process.argv[2])
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname) && origin.pathname === '/' && process.argv[3] === '--exercise')
const temporary = mkdtempSync('/fyoung/tmp/agentflow-round-comparison-runtime-')
writeFileSync(resolve(temporary, 'package.json'), '{"type":"module"}')
const source = readFileSync(fileURLToPath(new URL('../src/roundComparison.ts', import.meta.url)), 'utf8')
writeFileSync(resolve(temporary, 'roundComparison.js'), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
const { compareSubmissionRounds } = await import(pathToFileURL(resolve(temporary, 'roundComparison.js')))
const tokens = {}, prefix = 'round-diff-' + randomUUID().slice(0, 8)
async function call(method, path, user = 'admin', body, status = 200) {
  const headers = { 'Content-Type': 'application/json' }
  if (tokens[user]) headers.Authorization = 'Bearer ' + tokens[user]
  if (method !== 'GET' && !path.startsWith('/auth/')) headers['Idempotency-Key'] = randomUUID()
  const response = await fetch(origin.origin + '/api/v1' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
  const value = await response.json()
  assert.equal(response.status, status, JSON.stringify({ path, code: value.code }))
  return value
}
for (const user of ['admin', 'alice', 'manager', 'bob']) tokens[user] = (await call('POST', '/auth/login', 'anonymous', { tenantId: 'demo', username: user, password: 'demo' })).token
await call('POST', '/auth/login', 'anonymous', { tenantId: 'round-diff-other', username: 'admin', password: 'demo' }, 401)
const field = (key, label, type = 'TEXT') => ({ key, label, type, required: false })
const schema = { schemaVersion: 1, fields: [field('amount', '申请金额', 'NUMBER'), field('flag', '是否出差', 'BOOLEAN'), field('nullable', '可清空内容'), field('empty', '空白说明'), field('removed', '补正前字段'), field('added', '新增说明'), field('zero', '零值金额', 'NUMBER'), field('markup', '原文说明', 'TEXTAREA')] }
const graph = { nodes: [{ id: 'start', name: '开始', type: 'START', properties: {} }, { id: 'review', name: '核对补正内容', type: 'USER_TASK', properties: { assigneeRule: 'role:MANAGER' } }, { id: 'end', name: '结束', type: 'END', properties: {} }], edges: [{ id: 'first', source: 'start', target: 'review', condition: '', defaultBranch: false }, { id: 'last', source: 'review', target: 'end', condition: '', defaultBranch: false }] }
async function publish(formSchema) {
  const draft = await call('POST', '/process-definitions', 'admin', { key: prefix, name: '轮次内容对比验收', graph, formSchema })
  return call('POST', '/process-definitions/' + draft.id + '/publish?expectedRevision=0', 'admin', { changeNote: '验证提交快照对比及旧表单隔离' })
}
const published = await publish(schema)
const beforePayload = { amount: '99999999999999999999.123456789', flag: false, nullable: null, empty: '', removed: '原有内容', zero: '0', markup: '<img src=x onerror=alert(1)>\n仅作为申请原文' }
const afterPayload = { amount: '99999999999999999999.123456788', flag: true, nullable: '', empty: null, added: '补充后的说明', zero: '0', markup: beforePayload.markup }
async function create(suffix) {
  return call('POST', '/applications', 'alice', { businessNo: prefix + '-' + suffix, processKey: prefix, definitionVersion: 1, title: '差旅申请（原始提交）', payload: beforePayload }, 201)
}
const post = (app, operation, comment) => call('POST', '/applications/' + app.id + '/' + operation, 'alice', { expectedVersion: app.version, ...(comment ? { comment } : {}) })
let app = await post(await create('resubmitted'), 'submit')
const task = (await call('GET', '/tasks', 'manager')).find(task => task.applicationId === app.id)
await call('POST', '/tasks/' + task.taskId + '/actions', 'manager', { action: 'RETURN', expectedVersion: app.version, comment: '补充说明并核对金额末位' })
app = await call('GET', '/applications/' + app.id, 'alice')
app = await call('PUT', '/applications/' + app.id, 'alice', { expectedVersion: app.version, title: '差旅申请（已补正）', payload: afterPayload })
const newerSchema = structuredClone(schema)
newerSchema.fields[0].label = '新版金额（不应出现在旧轮次）'
await publish(newerSchema)
app = await post(app, 'submit')
app = await post(app, 'withdraw', '保持填写内容，仅重新发起审批')
app = await post(app, 'submit')
const rounds = await call('GET', '/applications/' + app.id + '/rounds', 'manager')
assert.deepEqual(rounds.map(round => round.roundNo), [1, 2, 3])
assert.ok(rounds.every(round => round.definitionVersion === 1 && round.formSchema.fields[0].label === '申请金额'))
assert.deepEqual(rounds[0].payload, beforePayload)
assert.deepEqual(rounds[1].payload, afterPayload)
assert.equal(compareSubmissionRounds(rounds[0], rounds[1]).valueChanges, 7)
assert.equal(compareSubmissionRounds(rounds[1], rounds[2]).valueChanges, 0)
assert.equal(compareSubmissionRounds(rounds[0], rounds[2]).schemaChanged, false)
await call('GET', '/applications/' + app.id + '/rounds', 'bob', undefined, 404)
const draft = await create('draft'), firstOnly = await post(await create('first-only'), 'submit')
assert.deepEqual(await call('GET', '/applications/' + draft.id + '/rounds', 'alice'), [])
assert.equal((await call('GET', '/applications/' + firstOnly.id + '/rounds', 'alice')).length, 1)
async function facts() {
  const result = {}
  for (const path of ['', '/rounds', '/audit?limit=100', '/timeline?limit=100']) result[path] = await call('GET', '/applications/' + app.id + path, 'alice')
  return result
}
const original = await facts()
for (const user of ['alice', 'manager', 'admin']) compareSubmissionRounds(...(await call('GET', '/applications/' + app.id + '/rounds', user)).slice(0, 2))
assert.deepEqual(await facts(), original)
console.log(JSON.stringify({ result: 'PASS', origin: origin.origin, processKey: prefix, publishedId: published.id, applicationId: app.id, draftId: draft.id, firstOnlyId: firstOnly.id, expectedChanges: 7, latestChanges: 0, reads: 'EXACT_MATCH', permission: 'bob 404; other tenant demo login 401' }))
