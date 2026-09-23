import test from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'

let currentToken = 'test-token'
globalThis.localStorage = { getItem: () => currentToken }
const { api, writeRequests } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_API))
const body = { key: 'expense', name: '费用审批', graph: { nodes: [], edges: [] } }

test('包含版本化表单的失败请求保留原字节和小数表示，恢复不采用后续编辑', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'schema-recovery' })
  const schemaBody = { ...body, formSchema: { schemaVersion: 1, fields: [{ key: 'amount', label: '金额', type: 'NUMBER', required: true, minimum: '000.00' }] } }
  const original = JSON.stringify(schemaBody)
  const sent = []
  globalThis.fetch = async (_url, init) => {
    sent.push(init)
    if (sent.length === 1) throw new TypeError('lost')
    return Response.json({ id: 'schema-draft', formSchema: JSON.parse(original).formSchema })
  }
  await assert.rejects(api.definition(schemaBody))
  schemaBody.formSchema.fields[0].label = '后续修改'
  await assert.rejects(api.definition(schemaBody), error => error.code === 'PENDING_REQUEST_CHANGED')
  const result = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent.length, 2)
  assert.equal(sent[0].body, original)
  assert.equal(sent[1].body, original)
  assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
  assert.equal(result.result.formSchema.fields[0].minimum, '000.00')
})

test('表单422保留字段错误并结束原请求，修改后使用新键', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'form-errors' })
  const sent = []
  globalThis.fetch = async (_url, init) => {
    sent.push(init)
    if (sent.length === 1) return Response.json({ code: 'FORM_VALIDATION_FAILED', details: { fieldErrors: { amount: 'REQUIRED' } } }, { status: 422 })
    return Response.json({ id: 'app' })
  }
  await assert.rejects(api.updateApplication('app', { expectedVersion: 1, title: '金额', payload: {} }), error => error.status === 422 && error.details.fieldErrors.amount === 'REQUIRED' && error.message.includes('字段'))
  assert.equal(writeRequests.pending().length, 0)
  await api.updateApplication('app', { expectedVersion: 1, title: '金额', payload: { amount: '9007199254740993.01' } })
  assert.notEqual(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
  assert.equal(JSON.parse(sent[1].body).payload.amount, '9007199254740993.01')
})

test('响应丢失后再次保存复用原请求键，不创建第二份草稿', async () => {
  writeRequests?.setActor({ tenantId: 'demo', userId: 'same-key', roles: [] })
  const sent = []
  globalThis.fetch = async (_url, init) => {
    sent.push(init)
    if (sent.length === 1) throw new TypeError('connection lost')
    return Response.json({ id: 'one-draft' })
  }
  await assert.rejects(api.definition(body))
  await api.definition(body)
  assert.ok(sent[0].headers.get('Idempotency-Key'), '业务写请求必须带请求键')
  assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
  assert.equal(sent[0].body, sent[1].body)
})

test('未确认时修改正文会被阻止，恢复只发送原正文', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'changed-body' })
  const sent = []
  globalThis.fetch = async (url, init) => {
    sent.push({ url, ...init })
    if (sent.length === 1) throw new TypeError('lost')
    return Response.json({ id: 'original-draft' })
  }
  await assert.rejects(api.definition(body))
  await assert.rejects(api.definition({ ...body, name: '已修改' }), error => error.code === 'PENDING_REQUEST_CHANGED')
  assert.equal(sent.length, 1)
  const recovered = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(recovered.result.id, 'original-draft')
  assert.equal(sent[1].body, sent[0].body)
  assert.equal(sent[1].headers.get('Idempotency-Key'), sent[0].headers.get('Idempotency-Key'))
  assert.equal(writeRequests.pending().length, 0)
})

test('同一原请求的并发调用共享执行，成功后的新操作使用新键', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'concurrent' })
  const sent = []
  let complete
  globalThis.fetch = async (_url, init) => {
    sent.push(init)
    if (sent.length === 1) await new Promise(resolve => { complete = resolve })
    return Response.json({ id: 'draft' })
  }
  const first = api.definition(body)
  const second = api.definition(body)
  assert.equal(first, second)
  await Promise.resolve()
  assert.equal(sent.length, 1)
  complete()
  await Promise.all([first, second])
  await api.definition(body)
  assert.notEqual(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
})

for (const failure of ['503', 'text-read', 'invalid-json', 'empty-body']) {
  test(`${failure} 后保留原键并允许用户主动恢复`, async () => {
    writeRequests.setActor({ tenantId: 'demo', userId: failure })
    const sent = []
    globalThis.fetch = async (_url, init) => {
      sent.push(init)
      if (sent.length > 1) return Response.json({ id: 'saved' })
      if (failure === '503') return Response.json({ code: 'DEPENDENCY_UNAVAILABLE' }, { status: 503 })
      if (failure === 'text-read') return { ok: true, text: async () => { throw new TypeError('body disconnected') } }
      return new Response(failure === 'invalid-json' ? '{' : '', { status: 200 })
    }
    await assert.rejects(api.updateApplication('app', { expectedVersion: 1, title: '原始标题', payload: { amount: null } }))
    assert.equal(writeRequests.pending().length, 1)
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
    assert.equal(sent[0].body, sent[1].body)
  })
}

for (const [status, code, phrase] of [[409, 'IDEMPOTENCY_KEY_REUSED', '其他内容'], [409, 'IDEMPOTENCY_KEY_EXPIRED', '期限已过'], [409, 'CONCURRENCY_CONFLICT', '重新加载'], [403, 'FORBIDDEN', '没有执行']]) {
  test(`${code} 使用中文说明并结束当前请求键`, async () => {
    writeRequests.setActor({ tenantId: 'demo', userId: code })
    const sent = []
    globalThis.fetch = async (_url, init) => {
      sent.push(init)
      return sent.length === 1 ? Response.json({ code, message: 'English server message' }, { status }) : Response.json({ id: 'ok' })
    }
    await assert.rejects(api.definition(body), error => error.code === code && error.message.includes(phrase))
    assert.equal(writeRequests.pending().length, 0)
    await api.definition(body)
    assert.notEqual(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
  })
}

test('发布第二步丢失响应时仅恢复原发布，不再保存草稿或改变查询版本', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'publish-stage' })
  const sent = []
  globalThis.fetch = async (url, init) => {
    sent.push({ url, ...init })
    if (sent.length === 2) throw new TypeError('publish response lost')
    return Response.json({ id: 'flow', revision: 7 })
  }
  const saved = await api.updateDefinition('flow', { name: body.name, graph: body.graph, expectedRevision: 6 })
  await assert.rejects(api.publishDefinition(saved.id, saved.revision))
  await assert.rejects(api.publishDefinition(saved.id, 8), error => error.code === 'PENDING_REQUEST_CHANGED')
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent.filter(item => item.method === 'PUT').length, 1)
  assert.equal(sent.length, 3)
  assert.equal(sent[1].url, sent[2].url)
  assert.ok(sent[2].url.endsWith('expectedRevision=7'))
  assert.equal(sent[1].headers.get('Idempotency-Key'), sent[2].headers.get('Idempotency-Key'))
})

test('创建申请的恢复不会自动提交，提交的恢复保持原申请和版本', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'application-stage' })
  const sent = []
  globalThis.fetch = async (url, init) => {
    sent.push({ url, ...init })
    if (sent.length === 1 || sent.length === 3) throw new TypeError('lost')
    return Response.json({ id: 'application-original', version: 1 })
  }
  await assert.rejects(api.createApplication({ businessNo: 'A', processKey: 'expense', definitionVersion: 1, title: '原申请', payload: {} }))
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent.length, 2)
  assert.ok(sent.every(item => item.url.endsWith('/applications')))
  await assert.rejects(api.submitApplication('application-original', 1))
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent.length, 4)
  assert.equal(sent[2].url, sent[3].url)
  assert.equal(sent[2].body, sent[3].body)
})

test('任务完成响应丢失后不自动批准，只在恢复时重发原任务原动作', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'task-recovery' })
  const sent = []
  globalThis.fetch = async (url, init) => {
    sent.push({ url, ...init })
    if (sent.length === 1) throw new TypeError('lost')
    return Response.json({ taskId: 'task-original', action: 'APPROVE' })
  }
  await assert.rejects(api.taskAction('task-original', { action: 'APPROVE', expectedVersion: 3 }))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(sent.length, 1)
  await assert.rejects(api.taskAction('task-original', { action: 'RETURN', expectedVersion: 3, comment: 'changed' }), error => error.code === 'PENDING_REQUEST_CHANGED')
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent[1].body, sent[0].body)
  assert.equal(sent[1].url, sent[0].url)
})

test('同租户换用户与跨租户隔离，原用户重登可以恢复自己的请求', async () => {
  const sent = []
  globalThis.fetch = async (_url, init) => { sent.push(init); throw new TypeError('lost') }
  for (const [tenantId, userId] of [['tenant-a', 'alice'], ['tenant-a', 'bob'], ['tenant-b', 'alice']]) {
    writeRequests.setActor({ tenantId, userId })
    assert.equal(writeRequests.pending().length, 0)
    await assert.rejects(api.definition(body))
    assert.equal(writeRequests.pending().length, 1)
  }
  assert.equal(new Set(sent.map(init => init.headers.get('Idempotency-Key'))).size, 3)
  writeRequests.setActor(null)
  assert.equal(writeRequests.pending().length, 0)
  writeRequests.setActor({ tenantId: 'tenant-a', userId: 'alice' })
  globalThis.fetch = async (_url, init) => { sent.push(init); return Response.json({ id: 'original' }) }
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent[3].headers.get('Idempotency-Key'), sent[0].headers.get('Idempotency-Key'))
})

test('服务端成功响应丢失后恢复遇到401，重新登录同账号仍用原键恢复', async () => {
  const actor = { tenantId: 'demo', userId: 'expired-token' }
  writeRequests.setActor(actor)
  currentToken = 'old-token'
  const sent = []
  globalThis.fetch = async (_url, init) => {
    sent.push(init)
    if (sent.length === 1) return Response.json({ code: 'DEPENDENCY_UNAVAILABLE' }, { status: 503 })
    if (sent.length === 2) return Response.json({ code: 'UNAUTHENTICATED' }, { status: 401 })
    return Response.json({ id: 'one-draft' })
  }
  await assert.rejects(api.definition(body))
  await assert.rejects(writeRequests.recover(writeRequests.pending()[0].id), error => error.status === 401)
  assert.equal(writeRequests.pending().length, 1, '401不能丢弃尚未确认的原请求')
  writeRequests.setActor(null)
  currentToken = 'renewed-token'
  writeRequests.setActor(actor)
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent[0].headers.get('Idempotency-Key'), sent[2].headers.get('Idempotency-Key'))
  assert.equal(sent[2].headers.get('Authorization'), 'Bearer renewed-token')
  currentToken = 'test-token'
})

test('切用户后的旧响应不污染新页面，也不清理新用户未确认槽', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'old-actor' })
  let completeOld
  let calls = 0
  globalThis.fetch = async () => {
    if (++calls === 1) { await new Promise(resolve => { completeOld = resolve }); return Response.json({ id: 'old' }) }
    throw new TypeError('new actor network failure')
  }
  const old = api.definition(body)
  const oldFailure = assert.rejects(old, error => error.code === 'SESSION_CHANGED')
  await Promise.resolve()
  writeRequests.setActor(null)
  writeRequests.setActor({ tenantId: 'demo', userId: 'new-actor' })
  await assert.rejects(api.definition(body))
  const currentId = writeRequests.pending()[0].id
  completeOld()
  await oldFailure
  assert.equal(writeRequests.pending()[0].id, currentId)
  assert.equal(writeRequests.pending().length, 1)
})

test('8个业务写均带新键，校验和认证不附加业务幂等键', async () => {
  writeRequests.setActor({ tenantId: 'demo', userId: 'endpoint-contract' })
  const sent = []
  globalThis.fetch = async (url, init) => { sent.push({ url, ...init }); return Response.json({ id: 'ok' }) }
  await api.createApplication({ businessNo: 'A', processKey: 'p', definitionVersion: 1, title: 'a', payload: {} })
  await api.updateApplication('a', { expectedVersion: 1, title: 'a', payload: {} })
  await api.submitApplication('a', 1)
  await api.withdrawApplication('a', { expectedVersion: 2 })
  await api.taskAction('t', { action: 'APPROVE', expectedVersion: 1 })
  await api.definition(body)
  await api.updateDefinition('d', { name: 'd', graph: body.graph, expectedRevision: 0 })
  await api.publishDefinition('d', 1)
  assert.equal(new Set(sent.map(init => init.headers.get('Idempotency-Key'))).size, 8)
  assert.ok(sent.every(init => init.headers.get('Idempotency-Key')))
  await api.validateDefinition(body.graph)
  await api.login({ tenantId: 'demo', username: 'alice', password: 'demo' })
  await api.logout()
  assert.ok(sent.slice(8).every(init => !init.headers.has('Idempotency-Key')))
})
