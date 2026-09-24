import test from 'node:test'
import assert from 'node:assert/strict'
const { AssistRunsQuery } = await import(process.env.AGENTFLOW_TEST_ASSIST)
const row = id => ({ id, status: 'COMPLETED', version: 3, roundNo: 1, applicationVersion: 2, createdAt: '2026-09-23T12:00:00Z' })
const detail = (id, applicationId = 'app') => ({ ...row(id), applicationId, suggestion: { claims: [{ text: '合成摘要' }] } })
const page = (ids, cursor = null) => ({ items: ids.map(row), nextCursor: cursor })

test('翻页固定账号申请和轮次，网络失败保留游标且阻止重复请求', async () => {
  const calls = []; let fail = true, resolve
  const query = new AssistRunsQuery(async (id, filters) => {
    calls.push({ id, filters })
    if (!filters.cursor) return page(['first'], 'next')
    if (fail) { fail = false; throw new Error('offline') }
    return new Promise(done => { resolve = done })
  }, async (_app, id) => detail(id))
  await query.load('alice', 'app', 2); await query.select('first')
  await query.load('bob', 'app', 2, true); await query.load('alice', 'other', 2, true); await query.load('alice', 'app', 1, true)
  assert.equal(calls.length, 1)
  await query.load('alice', 'app', 2, true)
  assert.equal(query.nextCursor, 'next'); assert.equal(query.detail.id, 'first')
  const pending = query.load('alice', 'app', 2, true); await query.load('alice', 'app', 2, true)
  assert.equal(calls.length, 3); assert.deepEqual(calls[1], calls[2])
  resolve(page(['second'])); await pending
  assert.deepEqual(query.items.map(x => x.id), ['first', 'second']); assert.equal(query.nextCursor, null)
})

test('切换账号和申请同时中断详情与目录，迟到正文不能恢复', async () => {
  const pages = [], details = []
  const query = new AssistRunsQuery((_app, _filters, signal) => new Promise((resolve, reject) => pages.push({ signal, resolve, reject })),
    (_app, id, signal) => new Promise((resolve, reject) => details.push({ id, signal, resolve, reject })))
  const first = query.load('alice', 'app'); pages[0].resolve(page(['old'], 'next')); await first
  const oldDetail = query.select('old'), more = query.load('alice', 'app', undefined, true)
  const current = query.load('bob', 'new')
  assert.equal(details[0].signal.aborted, true); assert.equal(pages[1].signal.aborted, true)
  assert.deepEqual(query.items, []); assert.equal(query.detail, null)
  pages[2].resolve(page(['current'])); await current
  details[0].resolve(detail('old')); pages[1].resolve(page(['leaked'])); await oldDetail; await more
  assert.deepEqual(query.items.map(x => x.id), ['current']); assert.equal(query.detail, null)
})

test('改选另一运行或收起详情后，旧详情成功和失败都不可覆盖', async () => {
  const pending = []
  const query = new AssistRunsQuery(async () => page(['one', 'two']), (_app, _run, signal) => new Promise((resolve, reject) => pending.push({ signal, resolve, reject })))
  await query.load('alice', 'app')
  const one = query.select('one'), two = query.select('two')
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve(detail('two')); await two
  pending[0].reject({ message: '迟到错误', status: 403 }); await one
  assert.equal(query.detail.id, 'two'); assert.equal(query.detailError, '')
  const retry = query.select('one'); query.closeDetail(); pending[2].resolve(detail('one')); await retry
  assert.equal(query.detail, null); assert.equal(query.selectedId, '')
})

for (const status of [401, 403, 404]) test(`详情失权 ${status} 会清空目录及并发翻页`, async () => {
  let finish
  const query = new AssistRunsQuery(async (_id, filters) => filters.cursor ? new Promise(done => { finish = done }) : page(['one'], 'next'),
    async () => { throw { status } })
  await query.load('alice', 'app'); const more = query.load('alice', 'app', undefined, true)
  await query.select('one'); finish(page(['late'])); await more
  assert.deepEqual(query.items, []); assert.equal(query.detail, null); assert.equal(query.nextCursor, null)
  assert.equal(query.loading, false); assert.equal(query.detailLoading, false); assert.match(query.error, /无法查看/)
})

test('翻页失权会丢弃正在读取的详情', async () => {
  let finish
  const query = new AssistRunsQuery(async (_id, filters) => { if (filters.cursor) throw { status: 404 }; return page(['one'], 'next') },
    async () => new Promise(done => { finish = done }))
  await query.load('alice', 'app'); const reading = query.select('one')
  await query.load('alice', 'app', undefined, true); finish(detail('one')); await reading
  assert.deepEqual(query.items, []); assert.equal(query.detail, null); assert.match(query.error, /无法查看/)
})

test('详情网络失败可重试，错误归属的响应不能进入当前申请', async () => {
  let count = 0
  const query = new AssistRunsQuery(async () => page(['one']), async () => {
    count++; if (count === 1) throw new Error('offline')
    return detail('one', count === 2 ? 'other-app' : 'app')
  })
  await query.load('alice', 'app'); await query.select('unlisted'); assert.equal(count, 0)
  await query.select('one'); assert.equal(query.detail, null); assert.ok(query.detailError)
  await query.select('one'); assert.equal(query.detail, null); assert.match(query.detailError, /归属/)
  await query.select('one'); assert.equal(query.detail.id, 'one'); assert.equal(query.detailError, '')
})

test('目录及详情超时后，迟到数据均不可显示', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let finishPage, finishDetail, pageSignal, detailSignal
  const query = new AssistRunsQuery((_id, _filters, signal) => { pageSignal = signal; return new Promise(done => { finishPage = done }) },
    (_id, _run, signal) => { detailSignal = signal; return new Promise(done => { finishDetail = done }) })
  let pending = query.load('alice', 'app'); t.mock.timers.tick(12_000)
  assert.equal(pageSignal.aborted, true); finishPage(page(['late'])); await pending
  assert.deepEqual(query.items, []); assert.match(query.error, /超时/)
  pending = query.load('alice', 'app'); finishPage(page(['one'])); await pending
  pending = query.select('one'); t.mock.timers.tick(12_000)
  assert.equal(detailSignal.aborted, true); finishDetail(detail('one')); await pending
  assert.equal(query.detail, null); assert.match(query.detailError, /超时/)
})

test('空主体与卸载清空不发新请求，刷新先丢弃已显示正文', async () => {
  let calls = 0
  const query = new AssistRunsQuery(async () => { calls++; return page(['one']) }, async () => detail('one'))
  await query.load('', 'app'); await query.load('alice', ''); assert.equal(calls, 0)
  await query.load('alice', 'app'); await query.select('one')
  const refresh = query.load('alice', 'app'); assert.equal(query.detail, null); await refresh
  query.clear(); assert.deepEqual(query.items, []); assert.equal(query.selectedId, '')
})

test('API 编码申请运行与游标，透传取消信号且没有写入幂等头', async () => {
  globalThis.localStorage = { getItem: () => 'assist-test-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  const requests = []; globalThis.fetch = async (url, init) => { requests.push({ url, ...init }); return Response.json(page([])) }
  const controller = new AbortController()
  await api.assistRuns('app/id', { roundNo: 2, cursor: 'a+/=', limit: 20 }, controller.signal)
  await api.assistRun('app/id', 'run/id', controller.signal)
  assert.ok(requests[0].url.includes('/applications/app%2Fid/assist-runs'))
  assert.equal(new URL(requests[0].url, 'http://localhost').searchParams.get('cursor'), 'a+/=')
  assert.ok(requests[1].url.endsWith('/app%2Fid/assist-runs/run%2Fid'))
  for (const request of requests) { assert.equal(request.signal, controller.signal); assert.equal(request.headers.get('Authorization'), 'Bearer assist-test-token'); assert.equal(request.headers.has('Idempotency-Key'), false) }
})
