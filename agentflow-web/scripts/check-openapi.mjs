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
ajv.addFormat('binary', true)
ajv.addSchema({ $id: 'agentflow', components: spec.components })
// 历史页明确序列化 null，不能与其他列表的省略语义混淆。
validate({ $ref: '#/components/schemas/HistoryPage' }, { items: [], nextCursor: null })
// 组织启用后动态规则尚未解析本轮任职；静态目录仍须具有实际可用成员。
const assigneeSchema = { $ref: '#/components/schemas/AssigneeOption' }
validate(assigneeSchema, { rule: 'role:ORG_SUPERVISOR_1', label: '本次任职一级主管', memberCount: 0, contextual: true })
validate(assigneeSchema, { rule: 'user:bob', label: 'Bob', memberCount: 1 })
validate(assigneeSchema, { rule: 'role:FINANCE', label: '财务', memberCount: 2, contextual: false })
for (const value of [
  { rule: 'user:bob', label: 'Bob', memberCount: 0 },
  { rule: 'user:bob', label: 'Bob', memberCount: 0, contextual: false },
  { rule: 'role:ORG_SUPERVISOR_1', label: '本次任职一级主管', memberCount: -1, contextual: true }
]) assert.equal(validator(assigneeSchema)(value), false, 'invalid assignee count must be rejected')
validate({ $ref: '#/components/schemas/GraphNode' }, {
  id: 'call', name: '固定版本子审批', type: 'SUB_PROCESS',
  properties: { subprocessKey: 'child-approval', subprocessVersion: '1', 'subprocessInput.total': 'amount' }
})
const ids = new Set()
let examples = 0
for (const methods of Object.values(spec.paths)) for (const operation of Object.values(methods)) {
  assert.ok(!ids.has(operation.operationId), 'duplicate operationId'); ids.add(operation.operationId)
  const body = operation.requestBody?.content['application/json']
  if (body?.example !== undefined) { validate(body.schema, body.example); examples++ }
  assert.equal(Boolean(operation.parameters?.find(p => p.name === 'Idempotency-Key')?.required), operation['x-idempotency'])
  for (const response of Object.values(operation.responses)) for (const media of Object.values(response.content ?? {})) validator(media.schema)
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
    const { user = 'admin', status = 200, body, raw, extraHeaders = {}, path = template, key = randomUUID() } = options
    const headers = { 'Content-Type': raw === undefined ? 'application/json' : 'application/octet-stream', ...extraHeaders }
    if (tokens[user]) headers.Authorization = `Bearer ${tokens[user]}`
    if (operation['x-idempotency']) headers['Idempotency-Key'] = key
    if (body !== undefined) validate(operation.requestBody.content['application/json'].schema, body)
    const response = await fetch(base + path, { method, headers, body: raw ?? (body === undefined ? undefined : JSON.stringify(body)), signal: AbortSignal.timeout(15000) })
    const binary = response.ok && Object.keys(operation.responses[String(status)]?.content ?? {}).find(type => type !== 'application/json')
    const text = binary ? '' : await response.text(), value = binary ? new Uint8Array(await response.arrayBuffer()) : text ? JSON.parse(text) : undefined
    assert.equal(response.status, status, `${method} ${template}: ${value?.code ?? 'unexpected status'}`)
    const declared = operation.responses[String(status)]
    assert.ok(declared, `undocumented status ${status}`)
    if (binary) { assert.equal(response.headers.get('content-type'), binary); if (binary.includes('spreadsheetml')) assert.deepEqual([...value.slice(0, 2)], [80, 75]); assert.equal(response.headers.get('cache-control'), 'no-store') }
    else if (declared.content) validate(declared.content['application/json'].schema, value)
    else assert.equal(value, undefined)
    if (status < 300 && operation['x-idempotency']) assert.ok(['true', 'false'].includes(response.headers.get('Idempotency-Replayed')))
    completed.add(operation.operationId); statuses.add(status)
    return value
  }
  function example(path) { return structuredClone(spec.paths[path].post.requestBody.content['application/json'].example) }
  assert.equal((await call('GET', '/api/v1/auth/options', { user: 'anonymous' })).mode, 'DEMO')
  const defs = '/api/v1/process-definitions', apps = '/api/v1/applications', tasksPath = '/api/v1/tasks'
  await call('GET', '/api/v1/openapi.json', { user: 'anonymous', status: 401 })
  for (const username of ['admin', 'alice', 'manager', 'bob']) tokens[username] = (await call('POST', '/api/v1/auth/login', { body: { tenantId: 'demo', username, password: 'demo' }, user: 'anonymous' })).token
  assert.deepEqual(await call('GET', '/api/v1/openapi.json'), spec, 'served contract matches checkout')
  const upgraded = await call('POST', '/api/v1/process-definitions/upgrade-conditions', { body: { graph: example(defs).graph } })
  assert.equal(upgraded.conditionLanguageVersion, 2)
  await call('GET', '/api/v1/process-definitions/search')
  await call('GET', '/api/v1/process-definitions/search', { user: 'alice' })
  await call('GET', '/api/v1/process-definitions/search', { path: '/api/v1/process-definitions/search?status=DRAFT', user: 'alice', status: 403 })
  await call('GET', '/api/v1/process-definitions/search', { path: '/api/v1/process-definitions/search?limit=101', status: 400 })
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

  assert.deepEqual((await call('POST', defs + '/validate', { body: { graph: create.graph, key: create.key } })).errors, [])
  const conflictingId = create.graph.nodes[0].id
  assert.ok((await call('POST', defs + '/validate', { body: { graph: create.graph, key: conflictingId } })).errors.includes('PROCESS_KEY_CONFLICT:' + conflictingId))
  const collidingGraph = structuredClone(create.graph)
  collidingGraph.edges[0].id = conflictingId
  assert.ok((await call('POST', defs + '/validate', { body: { graph: collidingGraph } })).errors.includes('NODE_EDGE_ID_CONFLICT:' + conflictingId))
  await call('POST', defs + '/simulate', { body: { graph: create.graph, values: {} } })
  const fieldPreview = example(defs + '/field-preview')
  assert.equal((await call('POST', defs + '/field-preview', { body: fieldPreview })).payload.amount, '已脱敏')
  await call('POST', defs + '/field-preview', { body: fieldPreview, user: 'alice', status: 403 })
  await call('POST', defs + '/{id}/simulate', { path: defPath + '/simulate', body: { values: {} } })
  definition = await call('PUT', defs + '/{id}', { path: defPath, body: { name: '接口契约验收流程', graph: create.graph, expectedRevision: definition.revision } })
  definition = await call('POST', defs + '/{id}/publish', { path: defPath + `/publish?expectedRevision=${definition.revision}`, body: example(defs + '/{id}/publish') })
  await call('GET', defs + '/{id}', { path: defPath }); await call('GET', defs)
  await call('GET', defs + '/{id}/initiator-requirements', { path: defPath + '/initiator-requirements', user: 'alice' })
  await call('GET', defs + '/{id}/publication', { path: defPath + '/publication' })
  definition = await call('POST', defs + '/{id}/availability', { path: defPath + '/availability', body: { startEnabled: false, expectedRevision: definition.revision, reason: '契约验证停用' } })
  assert.equal(definition.startEnabled, false)
  await call('GET', defs + '/{id}/availability-history', { path: defPath + '/availability-history?limit=1' })
  await call('GET', defs + '/{id}/availability-history', { path: defPath + '/availability-history', user: 'alice', status: 403 })
  definition = await call('POST', defs + '/{id}/availability', { path: defPath + '/availability', body: { startEnabled: true, expectedRevision: definition.revision, reason: '契约验证恢复' } })
  await call('POST', defs + '/{id}/compare', { path: defPath + '/compare', body: { key: definition.key, name: definition.name, graph: definition.graph, formSchema: definition.formSchema } })
  let application = await call('POST', apps, { user: 'alice', status: 201, body: { ...example(apps), businessNo: `API-${suffix}`, processKey: definition.key } })
  const appPath = `${apps}/${application.id}`
  await call('GET', apps + '/{id}/initiator-requirements', { path: appPath + '/initiator-requirements', user: 'alice' })
  const revise = { expectedVersion: application.version, title: '接口契约申请', payload: { amount: '100.01' } }, retryKey = randomUUID()
  application = await call('PUT', apps + '/{id}', { user: 'alice', path: appPath, body: revise, key: retryKey })
  assert.deepEqual(await call('PUT', apps + '/{id}', { user: 'alice', path: appPath, body: revise, key: retryKey }), application)
  await call('PUT', apps + '/{id}', { user: 'alice', path: appPath, body: { ...revise, title: '冲突内容' }, key: retryKey, status: 409 })
  application = await call('POST', apps + '/{id}/submit', { user: 'alice', path: appPath + '/submit', body: { expectedVersion: application.version } })
  const diagramTemplate = apps + '/{id}/rounds/{roundNo}/diagram', diagramPath = appPath + '/rounds/1/diagram'
  const diagram = await call('GET', diagramTemplate, { user: 'alice', path: diagramPath })
  assert.equal(diagram.applicationId, application.id); assert.equal(diagram.roundNo, 1)
  assert.ok(diagram.nodes.some(node => node.state === 'ACTIVE' && node.activeTasks > 0))
  await call('GET', diagramTemplate, { user: 'bob', path: diagramPath, status: 404 })
  await call('GET', diagramTemplate, { user: 'alice', path: appPath + '/rounds/0/diagram', status: 400 })
  const relationsTemplate = apps + '/{id}/rounds/{roundNo}/subprocesses', relationsPath = appPath + '/rounds/1/subprocesses'
  const relations = await call('GET', relationsTemplate, { user: 'alice', path: relationsPath })
  assert.equal(relations.applicationId, application.id); assert.equal(relations.roundNo, 1)
  assert.deepEqual(relations.children, []); assert.equal(relations.childApplication, false)
  await call('GET', relationsTemplate, { user: 'bob', path: relationsPath, status: 404 })
  await call('GET', relationsTemplate, { user: 'alice', path: relationsPath + '?limit=0', status: 400 })
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
  const assistPath = apps + '/{id}/assist-runs', assistUrl = apps + '/' + application.id + '/assist-runs'
  assert.deepEqual((await call('GET', assistPath, { user: 'alice', path: assistUrl })).items, [])
  await call('GET', assistPath, { user: 'anonymous', path: assistUrl, status: 401 })
  await call('GET', assistPath, { user: 'alice', path: assistUrl + '?tenantId=other', status: 400 })
  await call('GET', assistPath + '/{runId}', { user: 'alice', path: assistUrl + '/' + randomUUID(), status: 404 })
  const auditPath = '/api/v1/operations/audit'
  const auditFilters = '?applicationId=' + application.id
  const fullAudit = await call('GET', auditPath, { path: auditPath + auditFilters })
  assert.deepEqual(fullAudit.items.map(row => row.action).sort(), ['APPROVE', 'CREATE', 'REVISE', 'SUBMIT'])
  assert.ok(fullAudit.items.every(row => row.actor === (row.source === 'Task' ? 'manager' : 'alice')))
  assert.ok(fullAudit.items.every(row => row.applicationId === application.id && !('payload' in row) && !('comment' in row)))
  const firstAudit = await call('GET', auditPath, { path: auditPath + auditFilters + '&limit=2' })
  assert.equal(firstAudit.items.length, 2); assert.ok(firstAudit.nextCursor)
  const nextAudit = await call('GET', auditPath, { path: auditPath + auditFilters + '&limit=2&cursor=' + encodeURIComponent(firstAudit.nextCursor) })
  assert.deepEqual([...firstAudit.items, ...nextAudit.items], fullAudit.items); assert.ok(!nextAudit.nextCursor)
  const approvals = await call('GET', auditPath, { path: auditPath + auditFilters + '&actor=manager&action=APPROVE&source=Task' })
  assert.equal(approvals.items.length, 1)
  const cancelEvents = await call('GET', auditPath, { path: auditPath + '?applicationId=' + cancelled.id + '&actor=alice&action=CANCEL' })
  assert.equal(cancelEvents.items.length, 1)
  const withdrawEvents = await call('GET', auditPath, { path: auditPath + '?applicationId=' + withdrawn.id + '&actor=alice&action=WITHDRAW' })
  assert.equal(withdrawEvents.items.length, 1)
  await call('GET', auditPath, { user: 'anonymous', status: 401 })
  await call('GET', auditPath, { user: 'alice', status: 403 })
  await call('GET', auditPath, { path: auditPath + '?tenantId=other', status: 400 })
  await call('GET', auditPath, { path: auditPath + auditFilters + '&actor=manager&cursor=' + encodeURIComponent(firstAudit.nextCursor), status: 400 })
  const searchPath = '/api/v1/operations/applications'
  const searchFilters = '?processKey=' + definition.key + '&limit=1'
  const firstSearch = await call('GET', searchPath, { path: searchPath + searchFilters })
  assert.equal(firstSearch.items.length, 1); assert.ok(firstSearch.nextCursor)
  const nextSearch = await call('GET', searchPath, { path: searchPath + searchFilters + '&cursor=' + encodeURIComponent(firstSearch.nextCursor) })
  assert.equal(nextSearch.items.length, 1); assert.notEqual(nextSearch.items[0].id, firstSearch.items[0].id)
  const cancelledSearch = await call('GET', searchPath, { path: searchPath + '?processKey=' + definition.key + '&status=CANCELLED&applicant=alice&definitionVersion=1' })
  assert.deepEqual(cancelledSearch.items.map(row => row.id), [cancelled.id]); assert.ok(!('payload' in cancelledSearch.items[0]))
  const visiblePath = apps + '/search', visibleFilter = '?processKey=' + definition.key
  const visibleOwn = await call('GET', visiblePath, { user: 'alice', path: visiblePath + visibleFilter })
  assert.deepEqual(visibleOwn.items.map(row => row.id).sort(), [application.id, withdrawn.id, cancelled.id].sort())
  assert.ok(visibleOwn.items.every(row => !('payload' in row) && !('formSchema' in row)))
  assert.deepEqual((await call('GET', visiblePath, { user: 'bob', path: visiblePath + visibleFilter })).items, [])
  const visibleHandled = await call('GET', visiblePath, { user: 'manager', path: visiblePath + visibleFilter })
  // 原流程为角色候选审批；未领取即撤回不建立永久参与关系。
  assert.deepEqual(visibleHandled.items.map(row => row.id).sort(), [application.id].sort())
  const visibleFirst = await call('GET', visiblePath, { user: 'alice', path: visiblePath + visibleFilter + '&limit=1' })
  assert.ok(visibleFirst.nextCursor)
  const visibleNext = await call('GET', visiblePath, { user: 'alice', path: visiblePath + visibleFilter + '&limit=2&cursor=' + encodeURIComponent(visibleFirst.nextCursor) })
  assert.deepEqual([ ...visibleFirst.items, ...visibleNext.items ], visibleOwn.items)
  await call('GET', visiblePath, { user: 'anonymous', status: 401 })
  await call('GET', visiblePath, { user: 'alice', path: visiblePath + '?tenantId=other', status: 400 })
  await call('GET', visiblePath, { user: 'bob', path: visiblePath + visibleFilter + '&cursor=' + encodeURIComponent(visibleFirst.nextCursor), status: 400 })
  const auditExportPath = '/api/v1/operations/audit/export'
  await call('GET', auditExportPath, { path: auditExportPath + '?action=APPROVE' })
  await call('GET', auditExportPath, { user: 'alice', status: 403 })
  await call('GET', auditExportPath, { path: auditExportPath + '?limit=30', status: 400 })
  const exportPath = searchPath + '/export'
  await call('GET', exportPath, { path: exportPath + '?processKey=' + definition.key })
  await call('GET', exportPath, { user: 'alice', status: 403 })
  await call('GET', exportPath, { path: exportPath + '?limit=30', status: 400 })
  await call('GET', searchPath, { user: 'alice', status: 403 })
  await call('GET', searchPath, { path: searchPath + '?tenantId=other', status: 400 })
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
  const calendarOptions = defs + '/calendar-options'
  await call('GET', calendarOptions)
  await call('GET', calendarOptions, { user: 'alice', status: 403 })
  const optionVersions = await call('GET', calendarOptions + '/{id}/versions', { path: calendarOptions + '/' + calendar.id + '/versions?limit=1' })
  assert.equal(optionVersions.items[0].revision, 2)
  const fixedOption = await call('GET', calendarOptions + '/{id}/versions/{revision}', { path: calendarOptions + '/' + calendar.id + '/versions/1' })
  assert.equal(fixedOption.revision, 1); assert.equal(fixedOption.name, calendar.name); assert.equal(fixedOption.rules, undefined)
  for (const [revision, dueAt] of [[1, '2026-09-28T02:30:00Z'], [2, '2026-09-29T02:30:00Z']]) {
    const calculation = await call('POST', calendars + '/{id}/calculate', { path: calendarPath + '/calculate', body: { revision, startLocal: '2026-09-25T17:30:00', workingMinutes: 120 } })
    assert.equal(calculation.revision, revision); assert.equal(calculation.deadline.dueAt, dueAt)
  }
  await call('POST', '/api/v1/auth/logout', { user: 'bob' }); await call('GET', '/api/v1/auth/me', { user: 'bob', status: 401 })
  const wh = '/api/v1/integrations/webhooks'
  await call('GET', wh)
  const overview = await call('GET', wh + '/overview', { path: wh + '/overview?applicationId=' + application.id })
  assert.equal(overview.total, overview.pending + overview.inFlight + overview.retryWait + overview.delivered + overview.failed)
  await call('GET', wh + '/overview', { user: 'alice', status: 403 })
  await call('GET', wh + '/overview', { path: wh + '/overview?status=FAILED', status: 400 })
  await call('GET', wh, { user: 'alice', status: 403 })
  const deliveries = await call('GET', wh + '/deliveries', { path: wh + '/deliveries?applicationId=' + application.id })
  await call('GET', wh + '/deliveries', { path: wh + '/deliveries?tenantId=other', status: 400 })
  const missingDelivery = randomUUID()
  await call('GET', wh + '/deliveries/{id}', { path: wh + '/deliveries/' + missingDelivery, status: 404 })
  await call('POST', wh + '/deliveries/{id}/retry', { path: wh + '/deliveries/' + missingDelivery + '/retry', body: { expectedVersion: 1 }, status: 404 })
  for (const item of deliveries.items) {
    await call('GET', wh + '/deliveries/{id}', { path: wh + '/deliveries/' + item.id })
    assert.ok(!JSON.stringify(item).includes('payload_json'))
  }
  // 最后启用独立测试租户的本地组织，避免改变前面的演示目录验收前提。
  const fileOptions = await call('GET', '/api/v1/attachments/options', { user: 'alice' })
  const fileTemplate = apps + '/{applicationId}/attachments', fileContent = fileTemplate + '/{id}/content'
  if (fileOptions.enabled) {
    const fileCreate = example(defs); fileCreate.key = `attachment-contract-${suffix}`
    fileCreate.formSchema = { schemaVersion: 1, fields: [{ key: 'proof', label: '证明附件', type: 'ATTACHMENT', required: false, sensitive: true }] }
    let fileDefinition = await call('POST', defs, { body: fileCreate })
    fileDefinition = await call('POST', defs + '/{id}/publish', { path: defs + '/' + fileDefinition.id + '/publish?expectedRevision=' + fileDefinition.revision, body: example(defs + '/{id}/publish') })
    let fileApp = await call('POST', apps, { user: 'alice', status: 201, body: { businessNo: `FILE-${suffix}`, processKey: fileDefinition.key, definitionVersion: fileDefinition.version, title: '附件契约验收', payload: {} } })
    const filePath = apps + '/' + fileApp.id + '/attachments', input = example(fileTemplate), fileKey = randomUUID()
    input.expectedVersion = fileApp.version
    const attachment = await call('POST', fileTemplate, { user: 'alice', path: filePath, status: 201, body: input, key: fileKey })
    assert.deepEqual(await call('POST', fileTemplate, { user: 'alice', path: filePath, status: 201, body: input, key: fileKey }), attachment)
    const itemPath = filePath + '/' + attachment.id
    assert.equal((await call('PUT', fileContent, { user: 'alice', path: itemPath + '/content', raw: Buffer.from('abc'), extraHeaders: { 'X-Application-Version': String(fileApp.version) } })).status, 'READY')
    assert.equal((await call('GET', fileTemplate + '/{id}', { user: 'alice', path: itemPath })).sha256, input.sha256)
    assert.equal(Buffer.from(await call('GET', fileContent, { user: 'alice', path: itemPath + '/content' })).toString(), 'abc')
    await call('GET', fileContent, { user: 'admin', path: itemPath + '/content', status: 403 })
    fileApp = await call('PUT', apps + '/{id}', { user: 'alice', path: apps + '/' + fileApp.id, body: { expectedVersion: fileApp.version, title: fileApp.title, payload: { proof: [attachment.id] } } })
    await call('POST', apps + '/{id}/submit', { user: 'alice', path: apps + '/' + fileApp.id + '/submit', body: { expectedVersion: fileApp.version } })
    assert.equal(Buffer.from(await call('GET', fileContent, { user: 'alice', path: itemPath + '/content?roundNo=1' })).toString(), 'abc')
  } else {
    const unavailable = apps + '/' + randomUUID() + '/attachments', itemPath = unavailable + '/' + randomUUID()
    await call('POST', fileTemplate, { user: 'alice', path: unavailable, status: 503, body: example(fileTemplate) })
    await call('PUT', fileContent, { user: 'alice', path: itemPath + '/content', status: 404, raw: Buffer.from('abc'), extraHeaders: { 'X-Application-Version': '1' } })
    await call('GET', fileTemplate + '/{id}', { user: 'alice', path: itemPath, status: 404 })
    await call('GET', fileContent, { user: 'alice', path: itemPath + '/content', status: 404 })
  }
  const org = '/api/v1/organization'
  await call('GET', org + '/my-appointments', { user: 'alice' })
  await call('GET', org)
  await call('GET', org, { user: 'alice', status: 403 })
  const initKey = randomUUID()
  await call('POST', org + '/initialize', { status: 201, key: initKey })
  await call('POST', org + '/initialize', { status: 201, key: initKey })
  let company = await call('POST', org + '/units', { status: 201, body: example(org + '/units') })
  const department = await call('POST', org + '/units', { status: 201, body: { kind: 'DEPARTMENT', name: '验收部门', legalEntityId: company.id, active: true } })
  const position = await call('POST', org + '/units', { status: 201, body: { kind: 'POSITION', name: '验收岗位', legalEntityId: company.id, active: true } })
  company = await call('PUT', org + '/units/{id}', { path: org + '/units/' + company.id, body: { name: '修订法人', active: true, expectedRevision: 1 } })
  assert.equal(company.revision, 2)
  let person = await call('POST', org + '/people', { status: 201, body: example(org + '/people') })
  let appointment = await call('POST', org + '/appointments', { status: 201, body: { personId: person.id, departmentId: department.id, positionId: position.id, active: true } })
  await call('PUT', org + '/units/{id}/head', { path: org + '/units/' + department.id + '/head', body: { appointmentId: appointment.id, expectedRevision: department.revision } })
  appointment = await call('PUT', org + '/appointments/{id}/supervisor', { path: org + '/appointments/' + appointment.id + '/supervisor', body: { appointmentId: null, expectedRevision: appointment.revision } })
  appointment = await call('PUT', org + '/appointments/{id}', { path: org + '/appointments/' + appointment.id, body: { active: false, expectedRevision: appointment.revision } })
  assert.equal(appointment.active, false)
  person = await call('PUT', org + '/people/{id}', { path: org + '/people/' + person.id, body: { displayName: '已停用审批人', active: false, approvalEligible: true, expectedRevision: 1 } })
  assert.equal(person.active, false)
  await call('GET', org + '/units', { path: org + '/units?kind=LEGAL_ENTITY' })
  await call('GET', org + '/people')
  await call('GET', org + '/appointments')
  await call('GET', org + '/changes')
  await call('GET', org + '/people', { path: org + '/people?tenantId=foreign', status: 400 })
  assert.deepEqual([...completed].sort(), [...ids].sort())
  console.log(JSON.stringify({ result: 'PASS', base, operations: completed.size, statuses: [...statuses].sort(), processKey: definition.key, approved: application.id, withdrawn: withdrawn.id }))
}
