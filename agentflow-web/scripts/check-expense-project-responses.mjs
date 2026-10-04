import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import ts from 'typescript'

// 原生控制器和独立进程的真实响应共用页面解析器，不另建项目责任规则。
const [contract, ...inputs] = process.argv.slice(2)
assert.ok(contract && inputs.length, 'Pass the matching OpenAPI contract and captured response files')
const specSource = readFileSync(contract, 'utf8'), spec = JSON.parse(specSource)
const ajv = new Ajv2020({ strict: false, allErrors: true })
addFormats(ajv); ajv.addSchema({ $id: 'agentflow', components: spec.components })
const operation = spec.paths['/api/v1/expense-reports/{id}/project-approval']?.get
assert.ok(operation, 'Missing project approval read contract')
const check = ajv.compile(JSON.parse(JSON.stringify(operation.responses['200'].content['application/json'].schema)
  .replaceAll('"#/components/', '"agentflow#/components/')))
const root = fileURLToPath(new URL('../', import.meta.url)), output = mkdtempSync('/fyoung/tmp/agentflow-project-parser-')
const source = readFileSync(resolve(root, 'src/expenseProjectApproval.ts'), 'utf8')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}')
writeFileSync(resolve(output, 'expenseProjectApproval.js'), ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
const ui = await import(pathToFileURL(resolve(output, 'expenseProjectApproval.js')).href)
const records = inputs.flatMap(input => JSON.parse(readFileSync(input, 'utf8'))), applications = new Map()
for (const record of records) {
  if ((record.status ?? 200) < 300 && record.path.startsWith('/api/v1/expense-reports') && record.response?.id && record.response?.applicationId)
    applications.set(record.response.id, record.response.applicationId)
}
const counts = { parsedViews: 0, deniedReads: 0, precheckSources: 0 }, states = new Set(), rounds = new Set()
for (const record of records) {
  const url = new URL(record.path, 'http://127.0.0.1'), match = url.pathname.match(/^\/api\/v1\/expense-reports\/([^/]+)\/project-approval$/)
  if (match) {
    if ((record.status ?? 200) !== 200) { counts.deniedReads++; continue }
    const applicationId = record.applicationId ?? applications.get(match[1])
    assert.ok(applicationId, 'Missing original report application binding')
    assert.ok(check(record.response), record.path + ': ' + JSON.stringify(check.errors))
    const value = ui.readProjectApprovalView(record.response, match[1], applicationId, Number(url.searchParams.get('roundNo')))
    states.add(value.status); rounds.add(value.roundNo); counts.parsedViews++
    // 契约也须拒绝把未知或已记录状态与相反的正文配对。
    assert.equal(check({ ...value, status: 'RECORDED', details: null }), false)
    assert.equal(check({ ...value, status: 'NOT_RECORDED', details: {} }), false)
    if (value.status === 'RECORDED') assert.equal(check({ ...value, status: 'NO_PROJECT' }), false)
  }
  if (record.status === 200 && url.pathname.includes('/prechecks/') && record.response.projectOwners) {
    ui.readProjectOwners(record.response.projectOwners, record.response.initiator.legalEntityId); counts.precheckSources++
  }
}
assert.deepEqual([...states].sort(), ['NOT_RECORDED', 'NO_PROJECT', 'RECORDED'])
assert.ok([1, 2, 3].every(round => rounds.has(round)), 'Capture all three original submission rounds')
console.log(JSON.stringify({ result: 'PASS', counts, states: [...states].sort(), rounds: [...rounds].sort(),
  sourceSha256: createHash('sha256').update(source).digest('hex'), contractSha256: createHash('sha256').update(specSource).digest('hex'), output }))
