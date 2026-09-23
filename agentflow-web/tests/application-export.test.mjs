import test from 'node:test'
import assert from 'node:assert/strict'
const { ApplicationExportQuery, workbookType } = await import(process.env.AGENTFLOW_TEST_APPLICATION_EXPORT)

test('导出冻结筛选，阻断双击，清理后迟到文件及错误不能跨账号或筛选回填', async () => {
  const pending = []
  const query = new ApplicationExportQuery((filters, signal) => new Promise((resolve, reject) => pending.push({ filters, signal, resolve, reject })))
  const filters = { processKey: 'leave', q: '旧条件' }
  const old = query.generate('demo:admin', filters)
  filters.q = '已编辑'; await query.generate('demo:admin', filters)
  assert.equal(pending.length, 1); assert.equal(pending[0].filters.q, '旧条件')
  query.clear(); assert.equal(pending[0].signal.aborted, true)
  const current = query.generate('other:admin', {})
  const workbook = new Blob(['PK-new'], { type: workbookType })
  pending[1].resolve(workbook); await current
  pending[0].resolve(new Blob(['old-secret'])); await old
  assert.equal(query.file, workbook); assert.equal(query.error, '')
  query.clear(); assert.equal(query.file, null)
  const late = query.generate('other:admin', {})
  query.clear(); pending[2].reject({ message: '旧错误' }); await late
  assert.equal(query.file, null); assert.equal(query.error, ''); assert.equal(query.loading, false)
})

test('导出失败不保留旧文件，允许重试，空身份不请求', async () => {
  let calls = 0
  const file = new Blob(['PK'], { type: workbookType })
  const query = new ApplicationExportQuery(async () => { calls++; if (calls === 2) throw { message: '请缩小筛选' }; return file })
  await query.generate('', {}); assert.equal(calls, 0)
  await query.generate('demo:admin', {}); assert.equal(query.file, file)
  await query.generate('demo:admin', {}); assert.equal(query.file, null); assert.equal(query.error, '请缩小筛选')
  await query.generate('demo:admin', {}); assert.equal(query.file, file); assert.equal(query.error, '')
})

test('导出超时取消请求，迟到文件不会出现', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  let resolve, signal
  const query = new ApplicationExportQuery((_, suppliedSignal) => { signal = suppliedSignal; return new Promise(done => { resolve = done }) })
  const request = query.generate('demo:admin', {})
  context.mock.timers.tick(60_000); await request
  assert.equal(signal.aborted, true); assert.equal(query.loading, false); assert.match(query.error, /超时/)
  resolve(new Blob(['late'])); await Promise.resolve(); assert.equal(query.file, null)
})

test('导出 API 带认证和原筛选，读取二进制，并保留明确的错误码提示', async () => {
  globalThis.localStorage = { getItem: () => 'export-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let sent
  const controller = new AbortController()
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return new Response('PK workbook', { headers: { 'Content-Type': workbookType } }) }
  const file = await api.exportApplications({ q: '合同 & %_!', status: 'RETURNED', processKey: 'leave', definitionVersion: 1, applicant: 'alice', from: '2020-01-01', to: '2020-01-02' }, controller.signal)
  const url = new URL(sent.url, 'http://localhost')
  assert.equal(url.pathname, '/api/v1/operations/applications/export'); assert.equal(url.searchParams.get('q'), '合同 & %_!')
  assert.equal(url.searchParams.has('limit'), false); assert.equal(url.searchParams.has('cursor'), false)
  assert.equal(sent.signal, controller.signal); assert.equal(sent.headers.get('Authorization'), 'Bearer export-token')
  assert.equal(sent.headers.has('Idempotency-Key'), false); assert.equal(await file.text(), 'PK workbook')
  for (const [code, status, message] of [['APPLICATION_EXPORT_LIMIT_EXCEEDED', 422, /10,000/], ['APPLICATION_EXPORT_BUSY', 429, /稍后重试/], ['UNAUTHENTICATED', 401, /重新登录/]]) {
    globalThis.fetch = async () => Response.json({ code, message: 'English' }, { status })
    await assert.rejects(api.exportApplications({}, controller.signal), error => error.code === code && message.test(error.message))
  }
})

test('错误格式、空文件和下载中断不会冒充有效工作簿', async () => {
  globalThis.localStorage = { getItem: () => 'export-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  const signal = new AbortController().signal
  for (const response of [Response.json({ ok: true }), new Response('', { headers: { 'Content-Type': workbookType } }), { ok: true, headers: new Headers({ 'Content-Type': workbookType }), blob: async () => { throw new Error('truncated') } }]) {
    globalThis.fetch = async () => response
    await assert.rejects(api.exportApplications({}, signal), error => error.code === 'RESPONSE_UNREADABLE')
  }
  globalThis.fetch = async () => { throw new Error('offline') }
  await assert.rejects(api.exportApplications({}, signal), error => error.code === 'NETWORK_ERROR')
})
