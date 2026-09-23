import assert from 'node:assert/strict'
import test from 'node:test'
const { SystemChecksQuery } = await import(process.env.AGENTFLOW_TEST_SYSTEM)

test('账号切换取消旧请求，忽略迟到成功和失败，卸载后不回填', async () => {
  const pending = []
  const query = new SystemChecksQuery(signal => new Promise((resolve, reject) => pending.push({ signal, resolve, reject })))
  const old = query.load('a:admin')
  const current = query.load('b:admin')
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve({ checkedAt: 'now', checks: [{ id: 'database', status: 'UP' }] })
  await current
  pending[0].reject({ message: '旧账号的失败' })
  await old
  assert.equal(query.error, '')
  assert.equal(query.report.checks[0].status, 'UP')
  const late = query.load('b:admin')
  assert.equal(query.report, null)
  query.clear()
  assert.equal(pending[2].signal.aborted, true)
  pending[2].resolve({ checkedAt: 'late', checks: [] })
  await late
  assert.equal(query.report, null)
  assert.equal(query.loading, false)
})

test('刷新失败清空旧成功结果，空账号不执行请求', async () => {
  let calls = 0
  const query = new SystemChecksQuery(async () => {
    calls++
    if (calls === 2) throw { message: '登录已失效，请重新登录。' }
    return { checkedAt: 'now', checks: [] }
  })
  await query.load('demo:admin')
  assert.ok(query.report)
  await query.load('demo:admin')
  assert.equal(query.report, null)
  assert.equal(query.error, '登录已失效，请重新登录。')
  await query.load('')
  assert.equal(calls, 2)
  assert.equal(query.error, '')
})

test('自检通过认证只读请求，携带取消信号且不生成幂等写操作', async () => {
  globalThis.localStorage = { getItem: () => 'test-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let sent
  globalThis.fetch = async (url, init) => { sent = { url, ...init }; return Response.json({ checkedAt: 'now', checks: [] }) }
  const controller = new AbortController()
  await api.systemChecks(controller.signal)
  assert.ok(sent.url.endsWith('/system/checks'))
  assert.equal(sent.method, undefined)
  assert.equal(sent.signal, controller.signal)
  assert.equal(sent.headers.get('Authorization'), 'Bearer test-token')
  assert.equal(sent.headers.has('Idempotency-Key'), false)
})
