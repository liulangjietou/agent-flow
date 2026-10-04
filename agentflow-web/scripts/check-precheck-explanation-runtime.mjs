import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, resolve, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import ts from 'typescript'

// 独立运行夹具已停服后，核对实际响应与当前页面解析器；不向应用发新请求。
const directory = resolve(process.argv[2] ?? '')
assert.ok(process.argv[2], 'Pass the completed runtime evidence directory')
const evidence = JSON.parse(readFileSync(resolve(directory, 'result.json'), 'utf8'))
assert.equal(evidence.result, 'PASS')
assert.equal(evidence.ownedRuntimeStopped, true)
const root = fileURLToPath(new URL('../', import.meta.url))
const spec = JSON.parse(readFileSync(resolve(root, '../agentflow-server/src/main/resources/api/openapi.json'), 'utf8'))
const ajv = new Ajv2020({ strict: false, allErrors: true })
addFormats(ajv)
ajv.addSchema({ $id: 'agentflow', components: spec.components })
const validators = new Map()
function validate(schema, value, context) {
  const key = JSON.stringify(schema).replaceAll('"#/components/', '"agentflow#/components/')
  if (!validators.has(key)) validators.set(key, ajv.compile(JSON.parse(key)))
  const check = validators.get(key)
  assert.ok(check(value), context + ': ' + JSON.stringify(check.errors))
}

const output = mkdtempSync('/fyoung/tmp/agentflow-explanation-contract-')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}\n')
const compiled = new Map()
function compile(name) {
  if (compiled.has(name)) return
  const source = resolve(root, 'src', name + '.ts')
  assert.equal(dirname(source), resolve(root, 'src'), 'Only source modules from this workspace are allowed')
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
compile('precheckExplanation')
const ui = await import(pathToFileURL(resolve(output, 'precheckExplanation.js')).href)
const records = JSON.parse(readFileSync(resolve(directory, 'http-records.json'), 'utf8'))
const prefix = '/api/v1/expense-reports/{id}/precheck-explanations'
const counts = { responses: 0, successfulRequests: 0, input: 0, page: 0, detail: 0, receipt: 0, lostResponse: 0 }
const states = new Set(), outcomes = new Set(), failures = new Set(), operations = new Set()
for (const record of records) {
  const match = /^\/expense-reports\/[^/?]+\/precheck-explanations(?:\/(.*))?$/.exec(record.path.split('?')[0])
  if (!match) continue
  const suffix = match[1] ?? ''
  const template = prefix + (suffix === 'input' ? '/input' : suffix.endsWith('/review') ? '/{runId}/review' : suffix ? '/{runId}' : '')
  const operation = spec.paths[template][record.method.toLowerCase()]
  assert.ok(operation, 'Undocumented operation: ' + template)
  operations.add(operation.operationId)
  const schema = operation.responses[record.status]?.content?.['application/json']?.schema
  assert.ok(schema, 'Undocumented response: ' + template + ' ' + record.status)
  validate(schema, record.response, record.method + ' ' + record.path)
  counts.responses++
  if (record.status >= 300) continue
  if (record.method === 'POST') {
    validate(operation.requestBody.content['application/json'].schema, record.request, 'Request ' + record.path)
    counts.successfulRequests++
    ui.validateExplanationReceipt(record.response, record.path, JSON.stringify(record.request))
    counts.receipt++
  } else {
    assert.equal(record.cacheControl, 'no-store')
    if (suffix === 'input') {
      ui.readExplanationInput(record.response, new URL('http://localhost' + record.path).searchParams.get('precheckId'))
      counts.input++
    } else if (!suffix) {
      ui.readExplanationPage(record.response, 0)
      counts.page++
    } else {
      ui.readExplanationDetail(record.response, suffix)
      states.add(record.response.status)
      outcomes.add(record.response.result)
      if (record.response.failure) failures.add(record.response.failure)
      counts.detail++
    }
  }
}
const lossesPath = resolve(directory, 'lost-responses.json')
if (existsSync(lossesPath)) {
  for (const record of JSON.parse(readFileSync(lossesPath, 'utf8'))) {
    assert.ok(record.clientFailure && !record.proxyError)
    const template = prefix + (record.path.endsWith('/review') ? '/{runId}/review' : '')
    const operation = spec.paths[template].post
    validate(operation.requestBody.content['application/json'].schema, record.request, 'Lost request ' + record.path)
    validate(operation.responses[record.upstreamStatus].content['application/json'].schema, record.receipt, 'Lost receipt ' + record.path)
    ui.validateExplanationReceipt(record.receipt, record.path, JSON.stringify(record.request))
    counts.lostResponse++
  }
}
assert.equal(operations.size, 5)
assert.equal(counts.lostResponse, 2)
assert.ok(counts.input > 0 && counts.page > 0 && counts.detail > 0 && counts.receipt > 0)
assert.deepEqual([...outcomes].sort(), ['BLOCKED', 'READY', 'UNAVAILABLE'])
for (const state of ['ADOPTED', 'COMPLETED', 'DISMISSED', 'FAILED', 'RUNNING']) assert.ok(states.has(state), 'Missing actual state: ' + state)
assert.deepEqual([...failures].sort(), ['INPUT_UNAVAILABLE', 'INVALID_MODEL_OUTPUT', 'MODEL_TIMEOUT'])
const result = { result: 'PASS', counts, operations: [...operations].sort(), states: [...states].sort(),
  outcomes: [...outcomes].sort(), failures: [...failures].sort(), compiledSources: [...compiled.values()], compiledDirectory: output,
  scope: 'Actual packaged HTTP response contracts and current frontend parsers; no interactive browser or PostgreSQL verification.' }
writeFileSync(resolve(directory, 'contract-verification.json'), JSON.stringify(result, null, 2) + '\n')
console.log(JSON.stringify(result))
