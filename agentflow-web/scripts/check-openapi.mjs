import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import SwaggerParser from '@apidevtools/swagger-parser'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
const spec = JSON.parse(readFileSync(new URL('../../agentflow-server/src/main/resources/api/openapi.json', import.meta.url), 'utf8'))
await SwaggerParser.validate(structuredClone(spec))
const ajv = new Ajv2020({ strict: false, allErrors: true })
addFormats(ajv)
ajv.addSchema({ $id: 'agentflow', components: spec.components })
// 历史页明确序列化 null，不能与其他列表的省略语义混淆。
validate({ $ref: '#/components/schemas/HistoryPage' }, { items: [], nextCursor: null })
const ids = new Set()
let examples = 0
for (const methods of Object.values(spec.paths)) for (const operation of Object.values(methods)) {
  assert.ok(!ids.has(operation.operationId), 'duplicate operationId'); ids.add(operation.operationId)
  const body = operation.requestBody?.content['application/json']
  if (body?.example !== undefined) { validate(body.schema, body.example); examples++ }
  assert.equal(Boolean(operation.parameters?.find(p => p.name === 'Idempotency-Key')?.required), operation['x-idempotency'])
  for (const response of Object.values(operation.responses)) if (response.content) validator(response.content['application/json'].schema)
}
console.log(JSON.stringify({ result: 'PASS', operations: ids.size, schemas: Object.keys(spec.components.schemas).length, requestExamples: examples }))
// 写入型验收只在明确指定独立本机演示入口时运行，保留所有验收数据。
if (process.argv.length > 2) {
  assert.equal(process.argv[3], '--exercise', 'Pass --exercise only for an isolated demo database')
  const base = new URL(process.argv[2])
  assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname), 'Runtime exercise is restricted to loopback')
  assert.equal(base.pathname, '/')
  await exercise(base.origin)
}
function validator(schema) {
  return ajv.compile(JSON.parse(JSON.stringify(schema).replaceAll('"#/components/', '"agentflow#/components/')))
}
function validate(schema, value) { const check = validator(schema); assert.ok(check(value), JSON.stringify(check.errors)) }
async function exercise(base) {
  const completed = new Set(), tokens = {}, statuses = new Set()
  async function call(method, template, options = {}) {
    const operation = spec.paths[template][method.toLowerCase()]
    const { user = 'admin', status = 200, body, path = template, key = randomUUID() } = options
    const headers = { 'Content-Type': 'application/json' }
    if (tokens[user]) headers.Authorization = `Bearer ${tokens[user]}`
    if (operation['x-idempotency']) headers['Idempotency-Key'] = key
    if (body !== undefined) validate(operation.requestBody.content['application/json'].schema, body)
    const response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
    const text = await response.text(), value = text ? JSON.parse(text) : undefined
    assert.equal(response.status, status, `${method} ${template}: ${value?.code ?? 'unexpected status'}`)
    const declared = operation.responses[String(status)]
    assert.ok(declared, `undocumented status ${status}`)
    if (declared.content) validate(declared.content['application/json'].schema, value)
    else assert.equal(value, undefined)
    if (status < 300 && operation['x-idempotency']) assert.ok(['true', 'false'].includes(response.headers.get('Idempotency-Replayed')))
    completed.add(operation.operationId); statuses.add(status)
    return value
  }
  function example(path) { return structuredClone(spec.paths[path].post.requestBody.content['application/json'].example) }
  const defs = '/api/v1/process-definitions', apps = '/api/v1/applications', tasksPath = '/api/v1/tasks'
  await call('GET', '/api/v1/openapi.json', { user: 'anonymous', status: 401 })
  for (const username of ['admin', 'alice', 'manager', 'bob']) tokens[username] = (await call('POST', '/api/v1/auth/login', { body: { tenantId: 'demo', username, password: 'demo' }, user: 'anonymous' })).token
  assert.deepEqual(await call('GET', '/api/v1/openapi.json'), spec, 'served contract matches checkout')
  await call('GET', '/api/v1/process-definitions/assignee-options')
  await call('GET', '/api/v1/process-definitions/assignee-options', { user: 'alice', status: 403 })
  await call('GET', '/api/v1/auth/me')
  await call('GET', '/api/v1/system/checks')
  await call('GET', '/api/v1/system/checks', { user: 'alice', status: 403 })
  const template = (await call('GET', '/api/v1/process-templates'))[0], suffix = randomUUID().slice(0, 8)
  await call('GET', '/api/v1/process-templates/{key}/scenarios', { path: `/api/v1/process-templates/${template.key}/scenarios` })
  await call('POST', '/api/v1/process-templates/{key}/copy', { path: `/api/v1/process-templates/${template.key}/copy`, body: { key: `api-copy-${suffix}`, name: '接口契约验收副本', templateVersion: template.templateVersion } })
  const create = example(defs); create.key = `api-contract-${suffix}`
  let definition = await call('POST', defs, { body: create })
  const defPath = `${defs}/${definition.id}`
  const guidePath = '/api/v1/system/first-workflow', selectedGuide = guidePath + '?definitionId=' + definition.id
  const draftGuide = await call('GET', guidePath, {path:selectedGuide})
  assert.equal(draftGuide.definition.status, 'DRAFT'); assert.equal(draftGuide.submittedRounds, 0)
  await call('GET', guidePath, {user:'alice',status:403})
  await call('GET', guidePath, {path:guidePath + '?tenantId=other',status:400})

  assert.deepEqual((await call('POST', defs + '/validate', { body: { graph: create.graph } })).errors, [])
  await call('POST', defs + '/simulate', { body: { graph: create.graph, values: {} } })
  await call('POST', defs + '/{id}/simulate', { path: defPath + '/simulate', body: { values: {} } })
  definition = await call('PUT', defs + '/{id}', { path: defPath, body: { name: '接口契约验收流程', graph: create.graph, expectedRevision: definition.revision } })
  definition = await call('POST', defs + '/{id}/publish', { path: defPath + `/publish?expectedRevision=${definition.revision}`, body: example(defs + '/{id}/publish') })
  await call('GET', defs + '/{id}', { path: defPath }); await call('GET', defs)
  await call('GET', defs + '/{id}/publication', { path: defPath + '/publication' })
  await call('POST', defs + '/{id}/compare', { path: defPath + '/compare', body: { key: definition.key, name: definition.name, graph: definition.graph, formSchema: definition.formSchema } })
  let application = await call('POST', apps, { user: 'alice', status: 201, body: { ...example(apps), businessNo: `API-${suffix}`, processKey: definition.key } })
  const appPath = `${apps}/${application.id}`
  const revise = { expectedVersion: application.version, title: '接口契约申请', payload: { amount: '100.01' } }, retryKey = randomUUID()
  application = await call('PUT', apps + '/{id}', { user: 'alice', path: appPath, body: revise, key: retryKey })
  assert.deepEqual(await call('PUT', apps + '/{id}', { user: 'alice', path: appPath, body: revise, key: retryKey }), application)
  await call('PUT', apps + '/{id}', { user: 'alice', path: appPath, body: { ...revise, title: '冲突内容' }, key: retryKey, status: 409 })
  application = await call('POST', apps + '/{id}/submit', { user: 'alice', path: appPath + '/submit', body: { expectedVersion: application.version } })
  const commentTemplate = apps + '/{id}/comments', commentPath = appPath + '/comments', commentKey = randomUUID()
  const commentBody = { content: '契约验收：补充审批依据', expectedVersion: application.version }
  const comment = await call('POST', commentTemplate, { user: 'alice', path: commentPath, status: 201, body: commentBody, key: commentKey })
  assert.deepEqual(await call('POST', commentTemplate, { user: 'alice', path: commentPath, status: 201, body: commentBody, key: commentKey }), comment)
  const commentPage = await call('GET', commentTemplate, { user: 'alice', path: commentPath + '?limit=1&roundNo=1' })
  assert.deepEqual(commentPage.items, [comment]); assert.equal(commentPage.nextCursor, null)
  assert.equal(comment.author, 'alice'); assert.equal(comment.applicationVersion, application.version)
  await call('GET', commentTemplate, { user: 'bob', path: commentPath, status: 404 })
  await call('GET', commentTemplate, { user: 'alice', path: commentPath + '?limit=101', status: 400 })

  const tasks = await call('GET', '/api/v1/workspace/tasks', { user: 'manager', path: `/api/v1/workspace/tasks?processKey=${definition.key}&minAmount=100.01&limit=1` })
  assert.equal(tasks.total, 1)
  const taskPath = `${tasksPath}/${tasks.items[0].taskId}`
  const task = await call('GET', tasksPath + '/{taskId}', { user: 'manager', path: taskPath })
  await call('GET', tasksPath, { user: 'manager' })
  await call('GET', tasksPath + '/{taskId}/recipients', { user: 'manager', path: taskPath + '/recipients' })
  await call('POST', tasksPath + '/{taskId}/actions', { user: 'manager', path: taskPath + '/actions', body: { action: 'RETURN', expectedVersion: task.version }, status: 422 })
  await call('POST', tasksPath + '/{taskId}/actions', { user: 'manager', path: taskPath + '/actions', body: { action: 'APPROVE', expectedVersion: task.version } })
  await call('GET', tasksPath + '/{taskId}', { user: 'manager', path: taskPath, status: 404 })
  await call('GET', apps + '/{id}', { path: appPath }); await call('GET', apps)
  await call('GET', apps + '/{id}', { path: apps + '/not-a-uuid', status: 400 })
  for (const suffix of ['rounds', 'timeline', 'audit']) await call('GET', apps + '/{id}/' + suffix, { path: appPath + '/' + suffix })
  await call('GET', '/api/v1/workspace/applications', { user: 'alice' })
  await call('GET', '/api/v1/workspace/handled', { user: 'manager' })
  await call('GET', '/api/v1/workspace/tasks', { path: '/api/v1/workspace/tasks?limit=0', status: 400 })
  const message = (await call('GET', '/api/v1/notifications', { user: 'manager' })).items.find(m => m.applicationId === application.id)
  assert.ok(message)
  await call('POST', '/api/v1/notifications/{id}/read', { user: 'manager', path: `/api/v1/notifications/${message.id}/read` })
  let withdrawn = await call('POST', apps, { user: 'alice', status: 201, body: { ...example(apps), businessNo: `API-W-${suffix}`, processKey: definition.key } })
  const withdrawnPath = `${apps}/${withdrawn.id}`
  withdrawn = await call('POST', apps + '/{id}/submit', { user: 'alice', path: withdrawnPath + '/submit', body: { expectedVersion: withdrawn.version } })
  await call('POST', apps + '/{id}/withdraw', { user: 'alice', path: withdrawnPath + '/withdraw', body: { expectedVersion: withdrawn.version, comment: '接口撤回验收' } })
  const completedGuide = await call('GET', guidePath, {path:selectedGuide})
  assert.equal(completedGuide.definition.version, definition.version)
  assert.equal(completedGuide.submittedRounds, 2); assert.equal(completedGuide.approvedRounds, 1)
  assert.equal(completedGuide.latestApproval.applicationId, application.id)
  assert.equal(completedGuide.latestSubmission.applicationId, withdrawn.id)
  const operationsPath = '/api/v1/operations/approvals'
  const operations = await call('GET', operationsPath, { path: operationsPath + '?processKey=' + definition.key })
  assert.equal(operations.metrics.submittedRounds, 2); assert.equal(operations.metrics.approved, 1)
  assert.equal(operations.metrics.withdrawn, 1); assert.equal(operations.metrics.decidedRounds, 1)
  assert.equal(operations.metrics.returnRatePercent, 0); assert.equal(operations.pendingTasks, 0)
  const cancelledDraft = await call('POST', apps, { user: 'alice', status: 201, body: { ...example(apps), businessNo: `API-C-${suffix}`, processKey: definition.key } })
  const cancelPath = `${apps}/${cancelledDraft.id}/cancel`, cancelKey = randomUUID(), cancelBody = { expectedVersion: cancelledDraft.version, comment: '接口作废验收' }
  await call('POST', apps + '/{id}/cancel', { user: 'bob', path: cancelPath, body: cancelBody, status: 403 })
  const cancelled = await call('POST', apps + '/{id}/cancel', { user: 'alice', path: cancelPath, body: cancelBody, key: cancelKey })
  assert.equal(cancelled.status, 'CANCELLED'); assert.equal(cancelled.version, cancelledDraft.version + 1)
  assert.deepEqual(await call('POST', apps + '/{id}/cancel', { user: 'alice', path: cancelPath, body: cancelBody, key: cancelKey }), cancelled)
  await call('POST', apps + '/{id}/submit', { user: 'alice', path: `${apps}/${cancelledDraft.id}/submit`, body: { expectedVersion: cancelled.version }, status: 422 })
  const cancellationAudit = await call('GET', apps + '/{id}/audit', { user: 'alice', path: `${apps}/${cancelledDraft.id}/audit?action=CANCEL` })
  assert.equal(cancellationAudit.items.length, 1); assert.equal(cancellationAudit.items[0].comment, cancelBody.comment)
  assert.equal(cancellationAudit.items[0].previousStatus, 'DRAFT'); assert.equal(cancellationAudit.items[0].currentStatus, 'CANCELLED')
  assert.deepEqual((await call('GET', operationsPath, { path: operationsPath + '?processKey=' + definition.key })).metrics, operations.metrics)
  await call('GET', operationsPath, { user: 'alice', status: 403 })
  await call('GET', operationsPath, { path: operationsPath + '?tenantId=other', status: 400 })
  const calendars = '/api/v1/business-calendars', calendarBody = example(calendars), calendarKey = randomUUID()
  calendarBody.key = 'calendar-' + suffix
  const calendar = await call('POST', calendars, { body: calendarBody, status: 201, key: calendarKey })
  assert.deepEqual(await call('POST', calendars, { body: calendarBody, status: 201, key: calendarKey }), calendar)
  const calendarPath = calendars + '/' + calendar.id
  await call('GET', calendars)
  await call('GET', calendars, { user: 'alice', status: 403 })
  await call('GET', calendars, { path: calendars + '?tenantId=other', status: 400 })
  assert.deepEqual(await call('GET', calendars + '/{id}', { path: calendarPath }), calendar)
  const revised = await call('PUT', calendars + '/{id}', { path: calendarPath, body: { name: '新版日历', rules: { ...calendarBody.rules, overrides: [{ date: '2026-09-28', periods: [], note: '自定义休息' }] }, expectedRevision: 1 } })
  assert.equal(revised.revision, 2)
  await call('PUT', calendars + '/{id}', { path: calendarPath, body: { name: '过期修改', rules: calendarBody.rules, expectedRevision: 1 }, status: 409 })
  assert.deepEqual(await call('GET', calendars + '/{id}/versions/{revision}', { path: calendarPath + '/versions/1' }), calendar)
  const versions = await call('GET', calendars + '/{id}/versions', { path: calendarPath + '/versions?limit=1' })
  assert.equal(versions.items[0].revision, 2); assert.equal(versions.nextBeforeRevision, 2)
  for (const [revision, dueAt] of [[1, '2026-09-28T02:30:00Z'], [2, '2026-09-29T02:30:00Z']]) {
    const calculation = await call('POST', calendars + '/{id}/calculate', { path: calendarPath + '/calculate', body: { revision, startLocal: '2026-09-25T17:30:00', workingMinutes: 120 } })
    assert.equal(calculation.revision, revision); assert.equal(calculation.deadline.dueAt, dueAt)
  }
  await call('POST', '/api/v1/auth/logout', { user: 'bob' }); await call('GET', '/api/v1/auth/me', { user: 'bob', status: 401 })
  assert.deepEqual([...completed].sort(), [...ids].sort())
  console.log(JSON.stringify({ result: 'PASS', base, operations: completed.size, statuses: [...statuses].sort(), processKey: definition.key, approved: application.id, withdrawn: withdrawn.id }))
}
