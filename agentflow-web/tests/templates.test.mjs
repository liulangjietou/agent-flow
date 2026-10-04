import test from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'

globalThis.localStorage = { getItem: () => 'test-token' }
const { api, writeRequests } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_API))
const { TemplateCatalog, FinancialExamplePreview, validateTemplateCopy } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_TEMPLATES))
const copy = { key: 'tenant-leave', name: '团队请假', templateVersion: 1 }

test('财务配套输入只读获取，不携带写入键或自动发布配置', async () => {
  const sent = []
  globalThis.fetch = async (url, init) => { sent.push({ url, ...init }); return Response.json({ key: 'employee-finance', scenarios: [] }) }
  assert.equal((await api.financialTemplateExamples('expense/report')).key, 'employee-finance')
  assert.equal(sent.length, 1)
  assert.ok(sent[0].url.endsWith('/process-templates/expense%2Freport/financial-examples'))
  assert.ok(!sent[0].method || sent[0].method === 'GET')
  assert.equal(sent[0].headers.has('Idempotency-Key'), false)
})

test('配套样例切换模板和账号后，迟到结果不能恢复旧预览或下载', async () => {
  const requests = []
  const preview = new FinancialExamplePreview(key => new Promise((resolve, reject) => requests.push({ key, resolve, reject })))
  const first = preview.load('a:admin', 'expense-report')
  const second = preview.load('b:admin', 'advance-request')
  requests[1].resolve({ key: 'current' }); await second
  requests[0].resolve({ key: 'stale' }); await first
  assert.equal(preview.value.key, 'current')
  const failed = preview.load('b:admin', 'expense-plan')
  assert.equal(preview.value, null)
  requests[2].reject(new Error('unavailable')); await failed
  assert.equal(preview.error, 'unavailable')
  const late = preview.load('b:admin', 'expense-plan')
  preview.clear(); requests[3].resolve({ key: 'unmounted' }); await late
  assert.equal(preview.value, null)
  assert.equal(preview.loading, false)
  assert.equal(preview.error, '')
})

test('目录和草稿读取无写请求键，复制传明确版本且返回草稿不自动发布', async () => {
  writeRequests.setActor({ tenantId: 'a', userId: 'admin' })
  const sent = []
  globalThis.fetch = async (url, init) => {
    sent.push({ url, ...init })
    return Response.json(url.endsWith('/process-templates') ? [{ key: 'leave-request', copies: [] }] : { id: 'draft', status: 'DRAFT', revision: 0, version: 0 })
  }
  assert.equal((await api.templates())[0].key, 'leave-request')
  assert.equal((await api.getDefinition('draft/id')).id, 'draft')
  const result = await api.copyTemplate('leave-request', copy)
  assert.equal(result.status, 'DRAFT')
  assert.ok(sent[1].url.endsWith('/process-definitions/draft%2Fid'))
  assert.ok(sent[2].url.endsWith('/process-templates/leave-request/copy'))
  assert.deepEqual(JSON.parse(sent[2].body), copy)
  assert.ok(sent[2].headers.has('Idempotency-Key'))
  assert.ok(sent.slice(0, 2).every(item => !item.headers.has('Idempotency-Key')))
  assert.equal(sent.length, 3)
})

test('复制503后锁住原目标和版本，恢复保持原字节且不发布', async () => {
  writeRequests.setActor({ tenantId: 'a', userId: 'copy-recovery' })
  const sent = []
  globalThis.fetch = async (url, init) => {
    sent.push({ url, ...init })
    return sent.length === 1 ? Response.json({ code: 'DEPENDENCY_UNAVAILABLE' }, { status: 503 }) : Response.json({ id: 'original', status: 'DRAFT' })
  }
  await assert.rejects(api.copyTemplate('leave-request', copy))
  await assert.rejects(api.copyTemplate('leave-request', { ...copy, key: 'changed', templateVersion: 2 }), error => error.code === 'PENDING_REQUEST_CHANGED')
  const result = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(result.result.id, 'original')
  assert.equal(result.request.path, '/process-templates/leave-request/copy')
  assert.equal(sent.length, 2)
  assert.equal(sent[1].body, sent[0].body)
  assert.equal(sent[1].headers.get('Idempotency-Key'), sent[0].headers.get('Idempotency-Key'))
})

test('复制响应丢失后跨租户不可恢复，原账号重登仍发送原请求', async () => {
  const owner = { tenantId: 'a', userId: 'copy-owner' }
  writeRequests.setActor(owner)
  globalThis.fetch = async () => { throw new TypeError('lost') }
  await assert.rejects(api.copyTemplate('leave-request', copy))
  const id = writeRequests.pending()[0].id
  writeRequests.setActor({ ...owner, tenantId: 'b' })
  assert.equal(writeRequests.pending().length, 0)
  await assert.rejects(writeRequests.recover(id), error => error.code === 'PENDING_REQUEST_GONE')
  writeRequests.setActor(owner)
  globalThis.fetch = async () => Response.json({ id: 'original' })
  assert.equal((await writeRequests.recover(id)).result.id, 'original')
})

test('重新加载立即清空目录和副本，旧账号延迟响应与旧失败不能覆盖新目录', async () => {
  const requests = []
  const catalog = new TemplateCatalog(() => new Promise((resolve, reject) => requests.push({ resolve, reject })))
  const first = catalog.load('a:admin')
  requests[0].resolve([{ key: 'private-a', copies: [{ definitionId: 'a-draft' }] }])
  await first
  const stale = catalog.load('a:admin')
  assert.deepEqual(catalog.templates, [])
  const current = catalog.load('b:admin')
  requests[2].resolve([{ key: 'private-b', copies: [] }])
  await current
  requests[1].reject({ message: 'old failure' })
  await stale
  assert.equal(catalog.templates[0].key, 'private-b')
  assert.equal(catalog.error, '')
  const late = catalog.load('b:admin')
  catalog.clear()
  requests[3].resolve([{ key: 'private-b', copies: [] }])
  await late
  assert.deepEqual(catalog.templates, [])
  assert.equal(catalog.loading, false)
})

test('复制字段定位非法标识与名称，修正后恢复有效且模板版本必须正整数', () => {
  assert.deepEqual(validateTemplateCopy(copy), {})
  assert.ok(validateTemplateCopy({ ...copy, key: '1bad' }).key)
  assert.ok(validateTemplateCopy({ ...copy, key: 'a'.repeat(65) }).key)
  assert.ok(validateTemplateCopy({ ...copy, name: ' ' }).name)
  assert.ok(validateTemplateCopy({ ...copy, name: '名'.repeat(129) }).name)
  assert.ok(validateTemplateCopy({ ...copy, templateVersion: 0 }).templateVersion)
  assert.ok(validateTemplateCopy({ ...copy, templateVersion: 1.5 }).templateVersion)
  assert.deepEqual(validateTemplateCopy({ ...copy, key: 'Team_leave-2' }), {})
})
