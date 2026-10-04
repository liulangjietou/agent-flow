import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import ts from 'typescript'

// 原始响应同时通过指定安装包契约和页面真实解析器，不另写一份预算状态规则。
const [contract, ...inputs] = process.argv.slice(2)
assert.ok(contract && inputs.length, 'Pass the matching OpenAPI contract and captured response files or directories')
const specSource = readFileSync(contract, 'utf8'), spec = JSON.parse(specSource)
const ajv = new Ajv2020({ strict: false, allErrors: true })
addFormats(ajv); ajv.addFormat('binary', true); ajv.addSchema({ $id: 'agentflow', components: spec.components })
const validators = new Map()
function validate(schema, value, context) {
  assert.ok(schema, 'Missing response schema: ' + context)
  const key = JSON.stringify(schema).replaceAll('"#/components/', '"agentflow#/components/')
  if (!validators.has(key)) validators.set(key, ajv.compile(JSON.parse(key)))
  const check = validators.get(key); assert.ok(check(value), context + ': ' + JSON.stringify(check.errors))
}
const root = fileURLToPath(new URL('../', import.meta.url)), output = mkdtempSync('/fyoung/tmp/agentflow-budget-parser-')
const source = readFileSync(resolve(root, 'src/expenseBudgetReview.ts'), 'utf8')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}')
writeFileSync(resolve(output, 'expenseBudgetReview.js'), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
const ui = await import(pathToFileURL(resolve(output, 'expenseBudgetReview.js')).href)
const records = inputs.flatMap(input => {
  const files = statSync(input).isDirectory() ? readdirSync(input).filter(name => name.endsWith('.json')).map(name => resolve(input, name)) : [input]
  return files.flatMap(file => { const value = JSON.parse(readFileSync(file, 'utf8')); return Array.isArray(value) ? value : [value] })
})
const paths = Object.entries(spec.paths).map(([template, methods]) => ({ template, methods, parts: template.split('/'), parameters: (template.match(/\{/g) ?? []).length }))
  .sort((a, b) => a.parameters - b.parameters)
const applications = new Map()
for (const record of records) {
  if (record.status < 300 && record.path.startsWith('/api/v1/expense-reports') && record.response?.id && record.response?.applicationId) {
    applications.set(record.response.id, record.response.applicationId)
  }
}
const counts = { responses: 0, binaryResponses: 0, successfulRequests: 0, parsedViews: 0, deniedReads: 0, prechecksWithPolicy: 0, taskOptions: 0 }
const states = new Set(), decisions = new Set()
for (const record of records) {
  const url = new URL(record.path, 'http://127.0.0.1'), parts = url.pathname.split('/')
  const matched = paths.find(path => path.parts.length === parts.length && path.parts.every((part, i) => part === parts[i] || /^\{[^}]+\}$/.test(part)))
  const api = matched?.methods[record.method.toLowerCase()]; assert.ok(api, `Undocumented ${record.method} ${record.path}`)
  const response = api.responses[record.status] ?? api.responses.default
  const schema = response?.content?.['application/json']?.schema
  if (schema) {
    validate(schema, record.response, `${record.status} ${record.path}`); counts.responses++
  } else {
    // 配套恢复会下载原件；二进制响应核对声明及实际摘要，不能套用 JSON 模式。
    assert.ok(Object.values(response?.content ?? {}).some(value => value.schema?.type === 'string' && value.schema?.format === 'binary'), 'Missing binary response schema: ' + record.path)
    assert.ok(/^[0-9a-f]{64}$/.test(record.response?.sha256) && Number.isSafeInteger(record.response?.size) && record.response.size > 0, 'Missing binary evidence: ' + record.path)
    counts.binaryResponses++
  }
  if (record.status < 300 && api.requestBody?.content?.['application/json']) {
    validate(api.requestBody.content['application/json'].schema, record.request, 'Request ' + record.path); counts.successfulRequests++
  }
  const budget = url.pathname.match(/^\/api\/v1\/expense-reports\/([^/]+)\/budget-review$/)
  if (budget) {
    if (record.status !== 200) { counts.deniedReads++; continue }
    assert.ok(applications.has(budget[1]), 'Missing original report application binding')
    const value = ui.readBudgetReviewView(record.response, budget[1], applications.get(budget[1]), Number(url.searchParams.get('roundNo')))
    states.add(value.details?.status ?? value.status); if (value.details?.decision) decisions.add(value.details.decision.actorId)
    counts.parsedViews++
  }
  if (record.status === 200 && url.pathname.includes('/prechecks/') && record.response.budgetExceptionPolicy) counts.prechecksWithPolicy++
  if (record.status === 200 && url.pathname.endsWith('/workflow') && record.response.task) counts.taskOptions++
}
assert.ok(counts.parsedViews > 0 && counts.deniedReads > 0 && counts.prechecksWithPolicy > 0 && counts.taskOptions > 0, 'Capture all budget read and denial surfaces')
console.log(JSON.stringify({ result: 'PASS', counts, states: [...states].sort(), actors: [...decisions].sort(),
  sourceSha256: createHash('sha256').update(source).digest('hex'), contractSha256: createHash('sha256').update(specSource).digest('hex'), output }))
