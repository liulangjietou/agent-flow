import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
const root = fileURLToPath(new URL('../..', import.meta.url)).replace(/\/$/, '')
const require = createRequire(root + '/agentflow-web/package.json')
const Ajv2020 = require('ajv/dist/2020.js'), addFormats = require('ajv-formats'), ts = require('typescript')
const run = process.argv[2]; assert.ok(run?.startsWith('/fyoung/tmp/agentflow-due-runtime-'))
const result = JSON.parse(readFileSync(run + '/result.json', 'utf8'))
assert.equal(result.status, 'PASS')
const contracts = {}
for (const name of ['baseline', 'fixed']) {
  const spec = JSON.parse(readFileSync(run + '/' + name + '-openapi.json', 'utf8'))
  const ajv = new Ajv2020({ strict: false, allErrors: true }); addFormats(ajv); ajv.addFormat('binary', true); ajv.addSchema({ $id: name, components: spec.components })
  const validator = schema => ajv.compile(JSON.parse(JSON.stringify(schema).replaceAll('"#/components/', '"' + name + '#/components/')))
  const paths = Object.entries(spec.paths).map(([path, methods]) => ({ path, methods, regex: new RegExp('^' + path.replaceAll(/\{[^}]+\}/g, '[^/]+') + '$'), score: (path.match(/\{/g) ?? []).length })).sort((a, b) => a.score - b.score)
  contracts[name] = { validator, paths }
}
let requests = 0, responses = 0, empty = 0, baselineResponses = 0
const exchanges = JSON.parse(readFileSync(run + '/http.json', 'utf8'))
for (const x of exchanges) {
  assert.deepEqual(JSON.parse(x.rawResponse), x.response, 'Captured response must preserve original HTTP bytes: ' + x.path)
  const baseline = result.boots.find(b => b.boot === x.boot)?.baseline
  const { paths, validator } = contracts[baseline ? 'baseline' : 'fixed']
  const pathname = x.path.split('?')[0], route = paths.find(p => p.regex.test(pathname)), operation = route?.methods[x.method.toLowerCase()]
  assert.ok(operation, 'Missing route: ' + x.method + ' ' + pathname)
  const response = operation.responses[String(x.status)] ?? operation.responses.default; assert.ok(response, 'Missing status ' + x.status + ' for ' + route.path)
  if (x.rawRequest && x.status < 400 && operation.requestBody?.content?.['application/json']?.schema) {
    const check = validator(operation.requestBody.content['application/json'].schema); assert.ok(check(JSON.parse(x.rawRequest)), JSON.stringify({ request: pathname, errors: check.errors })); requests++
  }
  if (x.status === 204) { assert.equal(x.response, null); empty++ }
  else {
    const check = validator(response.content['application/json'].schema); assert.ok(check(x.response), JSON.stringify({ response: pathname, status: x.status, errors: check.errors })); responses++
    if (baseline) baselineResponses++
  }
}
const client = run + '/client'; mkdirSync(client, { recursive: true }); writeFileSync(client + '/package.json', JSON.stringify({ type: 'module' }))
for (const name of ['cashierFilters', 'payments']) writeFileSync(client + '/' + name + '.js', ts.transpileModule(readFileSync(root + '/agentflow-web/src/' + name + '.ts', 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
const model = await import(pathToFileURL(client + '/cashierFilters.js').href)
const payments = await import(pathToFileURL(client + '/payments.js').href)
let reads = 0
for (const x of exchanges.filter(x => x.status === 200 && x.method === 'GET' && !result.boots.find(b => b.boot === x.boot)?.baseline)) {
  const url = new URL(x.path, 'http://fixture'), legal = url.searchParams.get('legalEntityId') ?? ''
  if (url.pathname === '/api/v1/cashier/payments') {
    model.validateCashierPaymentPage(x.response, model.cashierFilter(legal, url.searchParams.get('debitAccount') ?? '', url.searchParams.get('dueFrom') ?? '', url.searchParams.get('dueTo') ?? '', url.searchParams.get('undated') === 'true', url.searchParams.get('sort') ?? 'AUTHORIZED_AT_DESC'), url.searchParams.get('beforeId') ?? undefined); reads++
  } else if (url.pathname === '/api/v1/cashier/payments/filter-options') {
    model.validateCashierFilterOptions(x.response, legal, url.searchParams.get('afterAccountKey') ?? undefined); reads++
  } else if (/^\/api\/v1\/cashier\/payments\/[^/]+$/.test(url.pathname)) {
    payments.validateCashierPayment(x.response, url.pathname.split('/').at(-1)); reads++
  } else if (/^\/api\/v1\/applications\/[^/]+\/payments$/.test(url.pathname)) {
    payments.validateFinancePayment(x.response, { ...x.response, applicationId: url.pathname.split('/').at(-2) }); reads++
  }
}
assert.ok(reads > 20)
const summary = { result: 'PASS', httpExchanges: exchanges.length, responseContracts: responses, baselineResponses, fixedResponses: responses - baselineResponses, requestContracts: requests, emptyResponses: empty, realFrontendReads: reads, browserAcceptance: false }
writeFileSync(run + '/contract-summary.json', JSON.stringify(summary, null, 2) + '\n'); console.log(JSON.stringify(summary))
