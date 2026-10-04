import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import ts from 'typescript'

// 原生接口与固定包的响应均经过同一实际页面解析器和公开契约。
const [contract, ...inputs] = process.argv.slice(2)
assert.ok(contract && inputs.length, 'Pass the matching contract and captured response files or directories')
const source = readFileSync(new URL('../src/expenseFinancialReporting.ts', import.meta.url), 'utf8')
const output = mkdtempSync('/fyoung/tmp/agentflow-financial-parser-')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}')
writeFileSync(resolve(output, 'report.js'), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
const ui = await import(pathToFileURL(resolve(output, 'report.js')).href)
const specSource = readFileSync(contract, 'utf8'), spec = JSON.parse(specSource)
const operation = spec.paths['/api/v1/reports/expense-finance']?.get
assert.ok(operation, 'Missing financial report contract')
const ajv = new Ajv2020({ strict: false, allErrors: true }); addFormats(ajv)
ajv.addSchema({ $id: 'agentflow', components: spec.components })
const validators = new Map()
const files = inputs.flatMap(path => statSync(path).isDirectory() ? readdirSync(path).filter(name => name.endsWith('.json')).sort().map(name => resolve(path, name)) : [path])
const records = files.flatMap(path => JSON.parse(readFileSync(path, 'utf8')))
let reports = 0, denied = 0, invalid = 0, submitted = 0
for (const record of records) {
  const url = new URL(record.path, 'http://127.0.0.1')
  if (url.pathname !== '/api/v1/reports/expense-finance') continue
  const status = record.status ?? 200
  const schema = operation.responses[String(status)]?.content?.['application/json']?.schema
  assert.ok(schema, `Undeclared financial report response ${status}`)
  if (!validators.has(status)) validators.set(status, ajv.compile(JSON.parse(JSON.stringify(schema).replaceAll('"#/components/', '"agentflow#/components/'))))
  const check = validators.get(status)
  assert.ok(check(record.response), `${record.path}: ${JSON.stringify(check.errors)}`)
  if (status !== 200) { if (status === 403) denied++; else invalid++; continue }
  const to = url.searchParams.get('to') ?? record.response.generatedAt.slice(0, 10)
  const from = url.searchParams.get('from') ?? new Date(Date.parse(to) - 29 * 86_400_000).toISOString().slice(0, 10)
  const filter = { from, to }
  for (const key of ['legalEntityId', 'departmentId', 'categoryCode']) if (url.searchParams.has(key)) filter[key] = url.searchParams.get(key)
  const value = ui.readFinancialReport(record.response, filter)
  reports++; submitted += value.totals.submitted
}
assert.ok(reports > 0, 'No actual financial report responses were checked')
console.log(JSON.stringify({ result: 'PASS', reports, denied, invalid, submittedAcrossSnapshots: submitted,
  sourceSha256: createHash('sha256').update(source).digest('hex'), contractSha256: createHash('sha256').update(specSource).digest('hex'), output }))
