import test from 'node:test'
import assert from 'node:assert/strict'
const { calendarInstant, CalendarRead, CalendarDrafts, calendarInput, calendarPayload, calendarDirty, emptyCalendar } = await import(process.env.AGENTFLOW_TEST_CALENDARS)
const saved = { id: 'one', key: 'office', name: '总部', revision: 2, rules: { zoneId: 'Asia/Shanghai', weeklyHours: { MONDAY: [{ start: '09:00', end: '18:00' }] }, overrides: [] } }
function draft() { return { baseline: structuredClone(saved), form: { ...calendarInput(saved), name: '修改后' } } }
test('日历草稿按账号隔离，恢复只有原资源原请求可推进基础版本', () => {
  const drafts = new CalendarDrafts(), value = draft(), response = { ...saved, revision: 3, name: '修改后' }
  drafts.put('demo:admin', value)
  value.form.rules.zoneId = 'UTC'
  assert.equal(drafts.get('other:admin'), null)
  assert.equal(drafts.get('demo:admin').form.rules.zoneId, 'Asia/Shanghai')
  const body = JSON.stringify(calendarPayload(drafts.get('demo:admin')))
  assert.equal(drafts.acknowledge('demo:admin', '/business-calendars/two', body, response), false)
  const current = drafts.get('demo:admin'); current.form.name = '后续编辑'; drafts.put('demo:admin', current)
  assert.equal(drafts.acknowledge('demo:admin', '/business-calendars/one', body, response), false)
  assert.equal(drafts.get('demo:admin').baseline.revision, 2)
  current.form.name = '修改后'; drafts.put('demo:admin', current)
  assert.equal(drafts.acknowledge('demo:admin', '/business-calendars/one', body, response), true)
  assert.equal(drafts.get('demo:admin').baseline.revision, 3); assert.equal(drafts.hasDrafts(), false)
})
test('新建与历史复用只提交业务字段，旧基础版本不能隐式升级', () => {
  assert.equal(calendarDirty({ baseline: null, form: emptyCalendar() }), false)
  const value = draft(), body = calendarPayload(value)
  assert.equal(body.expectedRevision, 2); assert.equal(body.key, undefined); assert.equal(body.id, undefined)
  body.rules.weeklyHours.MONDAY[0].start = '10:00'
  assert.equal(value.form.rules.weeklyHours.MONDAY[0].start, '09:00')
  const drafts = new CalendarDrafts(), fresh = { baseline: null, form: calendarInput(saved) }
  drafts.put('admin', fresh)
  const input = calendarPayload(fresh)
  assert.equal(input.expectedRevision, undefined); assert.equal(input.key, 'office')
  assert.equal(drafts.acknowledge('admin', '/business-calendars', JSON.stringify(input), saved), true)
  assert.equal(drafts.get('admin').baseline.id, 'one')
})
test('切换版本或账号后迟到结果与错误不能覆盖当前内容', async () => {
  const query = new CalendarRead(), calls = []
  const fetcher = signal => new Promise((resolve,reject) => calls.push({ signal, resolve, reject }))
  const old = query.load(fetcher), current = query.load(fetcher)
  assert.equal(calls[0].signal.aborted, true)
  calls[1].resolve({ revision: 2 }); await current
  calls[0].resolve({ revision: 1 }); assert.equal(await old, null)
  assert.deepEqual(query.value, { revision: 2 })
  const late = query.load(fetcher); query.clear(); calls[2].reject({ message: '旧错误' }); await late
  assert.equal(query.value, null); assert.equal(query.error, '')
})
for (const status of [401,403,404]) test(`失权或版本消失 ${status} 清除旧规则和结果`, async () => {
  const query = new CalendarRead(); await query.load(async () => saved)
  await query.load(async () => { throw { status, message: '不可见' } })
  assert.equal(query.value, null); assert.equal(query.status, status); assert.equal(query.loading, false)
})
test('超时后响应不能成为试算结果', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const query = new CalendarRead(); let resolve, signal
  const pending = query.load(s => { signal = s; return new Promise(done => { resolve = done }) })
  t.mock.timers.tick(12000); assert.equal(signal.aborted, true)
  resolve(saved); assert.equal(await pending, null); assert.equal(query.value, null); assert.match(query.error, /超时/)
})
test('日历写入断网恢复保留版本和幂等键，试算不发幂等键且使用明确修订', async () => {
  globalThis.localStorage = { getItem: () => 'calendar-token' }
  const { api, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  writeRequests.setActor({ tenantId: 'demo', userId: 'admin' })
  const requests = []
  globalThis.fetch = async (url,init) => { requests.push({ url, ...init }); if (requests.length === 1) throw new TypeError('lost'); return Response.json(saved) }
  const body = calendarPayload(draft())
  await assert.rejects(api.updateCalendar('one',body))
  body.expectedRevision = 3
  await assert.rejects(api.updateCalendar('one',body), failure => failure.code === 'PENDING_REQUEST_CHANGED')
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(requests[0].body,requests[1].body); assert.equal(JSON.parse(requests[1].body).expectedRevision,2)
  assert.equal(requests[0].headers.get('Idempotency-Key'),requests[1].headers.get('Idempotency-Key'))
  const controller = new AbortController()
  await api.calculateCalendar('one/id', { revision: 1, startLocal: '2024-11-03T01:30', workingMinutes: 60, overlapChoice: 'LATER' }, controller.signal)
  const calculation = requests[2]
  assert.ok(calculation.url.endsWith('/one%2Fid/calculate')); assert.equal(calculation.signal,controller.signal)
  assert.equal(calculation.headers.has('Idempotency-Key'),false); assert.equal(JSON.parse(calculation.body).revision,1)
  await api.calendars('a+/=',controller.signal)
  assert.equal(new URL(requests[3].url,'http://localhost').searchParams.get('afterKey'),'a+/=')
})

test('模拟浏览器不支持服务端时区别名时回退为明确 UTC 值', t => {
  t.mock.method(Intl, 'DateTimeFormat', function () { throw new RangeError('Unsupported time zone') })
  assert.equal(calendarInstant('2026-09-29T02:30:00Z','SystemV/EST5EDT'),'2026-09-29T02:30:00Z（UTC；浏览器无法显示 SystemV/EST5EDT）')
})
