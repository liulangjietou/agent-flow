import test from 'node:test'
import assert from 'node:assert/strict'
const { WorkbookExportQuery, workbookType } = await import(process.env.AGENTFLOW_TEST_WORKBOOK_EXPORT)

test('审计导出冻结已查询的账号和日期，取消后旧文件不能覆盖新的筛选结果', async () => {
  const calls = []
  const exporter = new WorkbookExportQuery((filters, signal) => new Promise(resolve => calls.push({ filters, signal, resolve })))
  const filters = { actor: 'alice', action: 'RETURN', from: '2020-01-01' }
  const first = exporter.generate('demo:admin', filters)
  filters.actor = 'bob'
  await exporter.generate('demo:admin', filters)
  assert.equal(calls.length, 1); assert.equal(calls[0].filters.actor, 'alice')
  exporter.clear(); assert.equal(calls[0].signal.aborted, true)
  const current = exporter.generate('other:admin', { actor: 'bob' })
  const file = new Blob(['current'], { type: workbookType })
  calls[1].resolve(file); await current
  calls[0].resolve(new Blob(['old audit'])); await first
  assert.equal(exporter.file, file); assert.equal(exporter.error, '')
  exporter.clear(); assert.equal(exporter.file, null)
})

test('审计导出 API 使用认证与全部审计筛选，读取二进制且不发送分页和幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'audit-export-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let sent
  const signal = new AbortController().signal
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return new Response('PK audit', { headers: { 'Content-Type': workbookType } }) }
  const filters = { q: '合同 & %_!', actor: 'alice', action: 'APPROVE', source: 'Task', applicationId: '12345678-1234-1234-1234-123456789012', from: '2020-01-01', to: '2020-01-02' }
  const file = await api.exportAudit(filters, signal)
  const url = new URL(sent.url, 'http://localhost')
  assert.equal(url.pathname, '/api/v1/operations/audit/export')
  for (const [key, value] of Object.entries(filters)) assert.equal(url.searchParams.get(key), value)
  assert.equal(url.searchParams.has('limit'), false); assert.equal(url.searchParams.has('cursor'), false)
  assert.equal(sent.signal, signal); assert.equal(sent.headers.get('Authorization'), 'Bearer audit-export-token')
  assert.equal(sent.headers.has('Idempotency-Key'), false); assert.equal(await file.text(), 'PK audit')
})

test('审计导出超限、繁忙、参数和生成失败均给出可恢复提示，不接收错误格式文件', async () => {
  globalThis.localStorage = { getItem: () => 'audit-export-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  const signal = new AbortController().signal
  for (const [code, status, pattern] of [['AUDIT_EXPORT_LIMIT_EXCEEDED', 422, /10,000.*未生成截断文件/], ['AUDIT_EXPORT_BUSY', 429, /稍后重试/], ['AUDIT_EXPORT_FAILED', 503, /重试/], ['INVALID_AUDIT_QUERY', 400, /重新查询/]]) {
    globalThis.fetch = async () => Response.json({ code, message: 'English' }, { status })
    await assert.rejects(api.exportAudit({}, signal), error => error.code === code && pattern.test(error.message))
  }
  globalThis.fetch = async () => Response.json({ ok: true })
  await assert.rejects(api.exportAudit({}, signal), error => error.code === 'RESPONSE_UNREADABLE')
})
