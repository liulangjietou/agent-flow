import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import ts from 'typescript'

// 直接运行当前页面解析器，不另外实现一份验收用规则。
const root = fileURLToPath(new URL('../', import.meta.url)), output = mkdtempSync('/fyoung/tmp/agentflow-prior-parser-')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}')
for (const name of ['expenses', 'expensePriorControl']) {
  const source = readFileSync(resolve(root, 'src', name + '.ts'), 'utf8')
  writeFileSync(resolve(output, name + '.js'), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
}
const model = await import(pathToFileURL(resolve(output, 'expensePriorControl.js')).href)
let originalRounds = 0, prechecks = 0, selectorPages = 0
const records = process.argv.slice(2).flatMap(input => {
  const files = statSync(input).isDirectory() ? readdirSync(input).filter(name => name.endsWith('.json')).map(name => resolve(input, name)) : [input]
  return files.flatMap(file => { const value = JSON.parse(readFileSync(file, 'utf8')); return Array.isArray(value) ? value : [value] })
})
for (const record of records) {
  if (record.status !== 200 || record.method !== 'GET') continue
  const url = new URL(record.path, 'http://127.0.0.1'), value = record.response
  const match = url.pathname.match(/\/expense-reports\/([^/]+)\/prior-control$/)
  if (match) {
    model.readPriorControlView(value, match[1], value.applicationId, Number(url.searchParams.get('roundNo'))); originalRounds++
  } else if (url.pathname.endsWith('/expense-requests')) { model.readPriorRequestPage(value); selectorPages++ }
  else if (url.pathname.includes('/prechecks/') && value.priorControls != null) { model.readPriorAssessments(value.priorControls); prechecks++ }
}
assert.ok(originalRounds > 0 && prechecks > 0 && selectorPages > 0, 'Capture all three prior-control read surfaces')
console.log(JSON.stringify({ result: 'PASS', originalRounds, prechecks, selectorPages, total: originalRounds + prechecks + selectorPages, output }))
