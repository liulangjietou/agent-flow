import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'

// 基线响应使用基线包中的契约，不能用新版新增必填字段改写历史验收。
const [beforePath, afterPath, originalPath, restoredPath] = process.argv.slice(2)
const validators = [beforePath, afterPath].map(path => {
  const spec = JSON.parse(readFileSync(path, 'utf8')), ajv = new Ajv2020({ strict: false, allErrors: true })
  addFormats(ajv); ajv.addFormat('binary', true); ajv.addSchema({ $id: 'agentflow', components: spec.components })
  const cache = new Map()
  return { spec, validate(schema, value, label) {
    const key = JSON.stringify(schema)
    if (!cache.has(key)) cache.set(key, ajv.compile(JSON.parse(key.replaceAll('"#/components/', '"agentflow#/components/'))))
    const check = cache.get(key); assert.ok(check(value), `${label}: ${JSON.stringify(check.errors)}`)
  } }
})
let responses = 0, requests = 0, binaryResponses = 0, beforeResponses = 0
for (const [file, restored] of [[originalPath, false], [restoredPath, true]]) {
  for (const record of JSON.parse(readFileSync(file, 'utf8'))) {
    const baseline = !restored && record.boot === 1, validator = validators[baseline ? 0 : 1]
    const path = record.path.split('?')[0]
    const matches = Object.entries(validator.spec.paths).filter(([template, methods]) => methods[record.method.toLowerCase()] &&
      new RegExp('^' + template.replaceAll(/\{[^}]+\}/g, '[^/]+') + '$').test(path))
    // 与控制器一致，固定 options 路径优先于同层的 {jobId} 参数路径。
    matches.sort(([a], [b]) => (a.match(/\{/g)?.length ?? 0) - (b.match(/\{/g)?.length ?? 0))
    assert.ok(matches.length > 0, `${record.method} ${path}`)
    if (matches.length > 1) assert.notEqual((matches[0][0].match(/\{/g) ?? []).length, (matches[1][0].match(/\{/g) ?? []).length, `Ambiguous ${path}`)
    const [template, methods] = matches[0], operation = methods[record.method.toLowerCase()]
    const response = operation.responses[String(record.status)] ?? operation.responses.default
    assert.ok(response, `${record.status} ${template}`)
    const schema = response.content?.['application/json']?.schema
    if (schema) {
      validator.validate(schema, record.response, `boot=${record.boot} ${record.status} ${template}`); responses++
      if (baseline) beforeResponses++
    } else {
      assert.ok(record.response?.sha256 && record.response.size > 0, `Unrecorded binary ${path}`); binaryResponses++
    }
    const inputSchema = operation.requestBody?.content?.['application/json']?.schema
    if (record.status < 300 && inputSchema && record.request !== null && record.request !== undefined) {
      validator.validate(inputSchema, record.request, `request ${template}`); requests++
    }
  }
}
assert.ok(beforeResponses > 0 && responses > beforeResponses && binaryResponses > 0 && requests > 0)
console.log(JSON.stringify({ result: 'PASS', responses, requests, beforeResponses, binaryResponses }))
