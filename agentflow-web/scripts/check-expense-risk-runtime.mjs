import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import ts from 'typescript'

// 在独立运行结束后，用原始 HTTP 记录核对契约及页面解析器，不冒充浏览器验收。
assert.ok(process.argv[2], 'Pass the completed risk runtime evidence directory')
const directory = resolve(process.argv[2])
const evidence = JSON.parse(readFileSync(resolve(directory, 'evidence.json'), 'utf8'))
assert.equal(evidence.status, 'PASSED')
assert.equal(evidence.ownedProcessesStopped, true)
const root = fileURLToPath(new URL('../', import.meta.url))
const spec = JSON.parse(readFileSync(resolve(root, '../agentflow-server/src/main/resources/api/openapi.json'), 'utf8'))
const ajv = new Ajv2020({ strict: false, allErrors: true })
addFormats(ajv)
ajv.addSchema({ $id: 'agentflow', components: spec.components })
const validators = new Map()
function validate(schema, value, context) {
  assert.ok(schema, 'Missing OpenAPI schema: ' + context)
  const key = JSON.stringify(schema).replaceAll('"#/components/', '"agentflow#/components/')
  if (!validators.has(key)) validators.set(key, ajv.compile(JSON.parse(key)))
  const check = validators.get(key)
  assert.ok(check(value), context + ': ' + JSON.stringify(check.errors))
}

const output = mkdtempSync('/fyoung/tmp/agentflow-risk-contract-')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}\n')
const compiled = new Map()
function compile(name) {
  if (compiled.has(name)) return
  const source = resolve(root, 'src', name + '.ts')
  assert.equal(dirname(source), resolve(root, 'src'), 'Only workspace source modules are allowed')
  const input = readFileSync(source, 'utf8')
  compiled.set(name, { path: relative(resolve(root, '..'), source), sha256: createHash('sha256').update(input).digest('hex') })
  const code = ts.transpileModule(input, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
    .replace(/from (['"])(\.\/[^'"]+)\1/g, (_, quote, module) => {
      const dependency = module.slice(2).replace(/\.js$/, '')
      compile(dependency)
      return `from './${dependency}.js'`
    })
  writeFileSync(resolve(output, name + '.js'), code)
}
compile('expenseRisk')
const ui = await import(pathToFileURL(resolve(output, 'expenseRisk.js')).href)
const records = ['original', 'restored'].flatMap(name => JSON.parse(readFileSync(resolve(directory, name, 'http-records.json'), 'utf8')))
const prefix = '/api/v1/expense-reports/{id}/risk-explanations'
const counts = { responses: 0, successfulRequests: 0, input: 0, calendars: 0, page: 0, detail: 0, receipt: 0, lostResponse: 0 }
const states = new Set(), kinds = new Set(), failures = new Set(), operations = new Set()
for (const record of records) {
  const match = /^\/api\/v1\/expense-reports\/[^/?]+\/risk-explanations(?:\/(.*))?$/.exec(record.path.split('?')[0])
  if (!match) continue
  const suffix = match[1] ?? ''
  const template = prefix + (['input', 'calendars'].includes(suffix) ? '/' + suffix : suffix.endsWith('/review') ? '/{runId}/review' : suffix ? '/{runId}' : '')
  const operation = spec.paths[template][record.method.toLowerCase()]
  assert.ok(operation, 'Undocumented operation: ' + template)
  operations.add(operation.operationId)
  validate(operation.responses[record.status]?.content?.['application/json']?.schema, record.response, record.method + ' ' + record.path)
  counts.responses++
  if (record.status >= 300) continue
  assert.ok(record.cacheControl?.split(',').map(value => value.trim().toLowerCase()).includes('no-store'))
  if (record.method === 'POST') {
    validate(operation.requestBody.content['application/json'].schema, record.request, 'Request ' + record.path)
    counts.successfulRequests++
    if (suffix === 'input') {
      const input = ui.readRiskInput(record.response, record.request.scope)
      ui.riskSelection(input, input.sources.map(source => source.reference.sourceId))
      input.concerns.forEach(concern => kinds.add(concern.kind))
      counts.input++
    } else {
      ui.validateRiskReceipt(record.response, record.path, JSON.stringify(record.request))
      states.add(record.response.status)
      counts.receipt++
    }
  } else if (suffix === 'calendars') {
    ui.readRiskCalendars(record.response)
    counts.calendars++
  } else if (!suffix) {
    ui.readRiskPage(record.response, 0)
    record.response.items.forEach(item => states.add(item.status))
    counts.page++
  } else {
    ui.readRiskDetail(record.response, suffix, 1)
    states.add(record.response.status)
    if (record.response.failure) failures.add(record.response.failure)
    counts.detail++
  }
}
for (const record of JSON.parse(readFileSync(resolve(directory, 'original/lost-responses.json'), 'utf8'))) {
  assert.ok(record.clientFailure && !record.error)
  const template = prefix + (record.path.endsWith('/review') ? '/{runId}/review' : '')
  const operation = spec.paths[template].post
  validate(operation.requestBody.content['application/json'].schema, record.request, 'Lost request ' + record.path)
  validate(operation.responses[record.status]?.content?.['application/json']?.schema, record.response, 'Lost receipt ' + record.path)
  ui.validateRiskReceipt(record.response, record.path, JSON.stringify(record.request))
  counts.lostResponse++
}
assert.equal(counts.lostResponse, 2)
assert.ok(counts.input > 0 && counts.calendars > 0 && counts.page > 0 && counts.detail > 0 && counts.receipt > 0)
assert.equal(operations.size, 6)
assert.deepEqual([...kinds].sort(), ['CONSECUTIVE_INVOICES', 'CROSS_DOCUMENT', 'NON_WORKING_DAY', 'SAME_DAY'])
assert.deepEqual([...states].sort(), ['ADOPTED', 'COMPLETED', 'DISMISSED', 'FAILED', 'QUEUED', 'RUNNING'])
assert.deepEqual([...failures].sort(), ['INPUT_UNAVAILABLE', 'MODEL_TIMEOUT'])
const result = { status: 'PASSED', counts, operations: [...operations].sort(), states: [...states].sort(),
  kinds: [...kinds].sort(), failures: [...failures].sort(), compiledSources: [...compiled.values()], compiledDirectory: output,
  scope: 'Actual packaged HTTP response contracts and frontend parsers; no interactive browser or PostgreSQL verification.' }
writeFileSync(resolve(directory, 'contract-verification.json'), JSON.stringify(result, null, 2) + '\n')
console.log(JSON.stringify(result))
