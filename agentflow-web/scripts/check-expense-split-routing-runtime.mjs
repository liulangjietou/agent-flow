import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import ts from 'typescript'

// 固定安装包的原始 HTTP 记录同时经过接口契约和真实页面解析器，不替代浏览器操作验收。
assert.ok(process.argv[2], 'Pass the completed split routing runtime evidence directory')
const directory = resolve(process.argv[2])
const evidence = JSON.parse(readFileSync(resolve(directory, 'evidence.json'), 'utf8'))
assert.equal(evidence.status, 'PASSED')
assert.equal(evidence.ownedProcessesStopped, true)
assert.equal(evidence.modelCalls, 0)
const root = fileURLToPath(new URL('../', import.meta.url))
const contractSource = readFileSync(resolve(root, '../agentflow-server/src/main/resources/api/openapi.json'), 'utf8')
const spec = JSON.parse(contractSource)
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

const output = mkdtempSync('/fyoung/tmp/agentflow-split-contract-')
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
compile('expenseSplitRouting')
const ui = await import(pathToFileURL(resolve(output, 'expenseSplitRouting.js')).href)
const recordFiles = ['original', 'restored'].map(name => resolve(directory, name, 'http-records.json'))
const records = recordFiles.flatMap(path => JSON.parse(readFileSync(path, 'utf8')))
const paths = Object.entries(spec.paths).map(([template, methods]) => ({ template, methods,
  parts: template.split('/'), parameters: (template.match(/\{/g) ?? []).length })).sort((a, b) => a.parameters - b.parameters)
function operation(record) {
  const parts = record.path.split('?')[0].split('/')
  const path = paths.find(path => path.parts.length === parts.length && path.parts.every((part, i) => part === parts[i] || /^\{[^}]+\}$/.test(part)))
  assert.ok(path, 'Undocumented path: ' + record.path)
  const result = path.methods[record.method.toLowerCase()]
  assert.ok(result, 'Undocumented method: ' + record.method + ' ' + record.path)
  return result
}

const applications = new Map()
for (const record of records) {
  const value = record.response
  if (record.status < 300 && record.path.startsWith('/api/v1/expense-reports') && value?.id && value?.applicationId)
    applications.set(value.id, value.applicationId)
}
const counts = { responses: 0, successfulRequests: 0, splitResponses: 0, parsedViews: 0, repeatedFrozenViews: 0, deniedReads: 0 }
const states = new Set(), statuses = new Set(), operations = new Set(), frozen = new Map()
for (const record of records) {
  const api = operation(record)
  const response = api.responses[record.status] ?? api.responses.default
  assert.ok(response, 'Undocumented response: ' + record.status + ' ' + record.path)
  const schema = response.content?.['application/json']?.schema
  if (schema) {
    validate(schema, record.response, record.method + ' ' + record.status + ' ' + record.path)
    counts.responses++
  }
  if (record.status < 300 && api.requestBody?.content?.['application/json']) {
    validate(api.requestBody.content['application/json'].schema, record.request, 'Request ' + record.path)
    counts.successfulRequests++
  }
  const match = /^\/api\/v1\/expense-reports\/([^/?]+)\/split-routing\?/.exec(record.path)
  if (!match) continue
  counts.splitResponses++
  statuses.add(record.status)
  operations.add(api.operationId)
  if (record.status !== 200) { counts.deniedReads++; continue }
  assert.ok(record.cacheControl?.split(',').map(value => value.trim().toLowerCase()).includes('no-store'))
  const roundNo = Number(new URLSearchParams(record.path.split('?')[1]).get('roundNo'))
  assert.ok(applications.has(match[1]), 'Missing original report application binding')
  ui.readSplitRouting(record.response, match[1], roundNo, applications.get(match[1]))
  states.add(record.response.status)
  counts.parsedViews++
  if (record.actor === 'alice') {
    const key = match[1] + '/' + roundNo
    if (frozen.has(key)) { assert.deepEqual(record.response, frozen.get(key)); counts.repeatedFrozenViews++ }
    else frozen.set(key, record.response)
  }
}
assert.deepEqual([...states].sort(), ['CLEAR', 'DISABLED', 'NOT_RECORDED', 'RESTRICTED', 'SPLIT_SUSPECTED', 'UNCONFIGURED'])
assert.deepEqual([...statuses].sort(), [200, 400, 403, 404])
assert.ok(counts.repeatedFrozenViews >= 6)
assert.equal(operations.size, 1)
const result = { status: 'PASSED', counts, operations: [...operations], states: [...states].sort(), statuses: [...statuses].sort(),
  openApiSha256: createHash('sha256').update(contractSource).digest('hex'), compiledSources: [...compiled.values()], compiledDirectory: output,
  recordFiles: recordFiles.map(path => ({ path: relative(directory, path), sha256: createHash('sha256').update(readFileSync(path)).digest('hex') })),
  scope: 'Actual fixed-package HTTP contracts and frontend parsers; no interactive browser or PostgreSQL verification.' }
writeFileSync(resolve(directory, 'contract-verification.json'), JSON.stringify(result, null, 2) + '\n')
console.log(JSON.stringify(result))
