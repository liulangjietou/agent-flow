import test from 'node:test'
import assert from 'node:assert/strict'
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
globalThis.localStorage = { getItem: () => 'stale-demo-token' }
const enterprise = { mode: 'OIDC', loginUrl: '/api/v1/auth/oidc/authorize/enterprise', csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'masked-csrf-one' }

test('企业模式不发送旧演示令牌，写请求携带内存中的 CSRF 和当前页面身份', async () => {
  globalThis.fetch = async () => Response.json(enterprise)
  await api.authOptions()
  bindAuthenticationActor({ tenantId: 'tenant-a', userId: 'user 甲', roles: ['EMPLOYEE'] })
  const sent = []
  globalThis.fetch = async (url, init) => { sent.push({ url, ...init }); return Response.json({}) }
  await api.me()
  await api.definition({ key: 'example', name: '示例', graph: { nodes: [], edges: [] } })
  assert.equal(sent[0].headers.has('Authorization'), false)
  assert.equal(sent[0].headers.has('X-CSRF-TOKEN'), false)
  assert.equal(sent[0].headers.has('X-AgentFlow-Actor'), false)
  assert.equal(sent[1].headers.has('Authorization'), false)
  assert.equal(sent[1].headers.get('X-CSRF-TOKEN'), enterprise.csrfToken)
  assert.deepEqual(JSON.parse(decodeURIComponent(sent[1].headers.get('X-AgentFlow-Actor'))), ['tenant-a', 'user 甲'])
})

test('企业会话失效可恢复原请求，更新 CSRF 后仍使用原键和原正文', async () => {
  bindAuthenticationActor({ tenantId: 'tenant-a', userId: 'recovery', roles: ['EMPLOYEE'] })
  const sent = []
  globalThis.fetch = async (url, init) => {
    if (url.endsWith('/auth/options')) return Response.json({ ...enterprise, csrfToken: 'masked-csrf-two' })
    sent.push(init)
    if (sent.length === 1) return Response.json({ code: 'UNAUTHENTICATED' }, { status: 401 })
    return Response.json({ id: 'saved' })
  }
  await assert.rejects(api.definition({ key: 'old', name: '原操作', graph: { nodes: [], edges: [] } }), error => error.status === 401)
  const id = writeRequests.pending()[0].id
  await api.authOptions()
  bindAuthenticationActor({ tenantId: 'tenant-a', userId: 'recovery', roles: ['EMPLOYEE'] })
  await writeRequests.recover(id)
  assert.equal(sent[1].headers.get('X-CSRF-TOKEN'), 'masked-csrf-two')
  assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
  assert.equal(sent[0].body, sent[1].body)
})

test('登录入口拒绝外部跳转地址、未知模式或缺失 CSRF 配置', async () => {
  for (const options of [{ ...enterprise, loginUrl: 'https://evil.invalid' }, { ...enterprise, mode: 'other' },
    { ...enterprise, csrfHeader: 'Authorization' }, { ...enterprise, csrfToken: '' }]) {
    globalThis.fetch = async () => Response.json(options)
    await assert.rejects(api.authOptions(), error => error.code === 'AUTH_CONFIGURATION_INVALID')
  }
})

test('切回演示模式不发送企业 CSRF 和身份头', async () => {
  globalThis.fetch = async () => Response.json({ mode: 'DEMO' })
  await api.authOptions()
  let headers
  globalThis.fetch = async (_url, init) => { headers = init.headers; return Response.json({}) }
  await api.logout()
  assert.equal(headers.get('Authorization'), 'Bearer stale-demo-token')
  assert.equal(headers.has('X-CSRF-TOKEN'), false)
  assert.equal(headers.has('X-AgentFlow-Actor'), false)
})
