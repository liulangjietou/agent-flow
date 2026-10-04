import test from 'node:test'
import assert from 'node:assert/strict'
const { ExpenseDetailQuery, ExpensePageQuery, amountMinor, moneyLabel, changedReductions } = await import(process.env.AGENTFLOW_TEST_EXPENSES)
const money = value => ({ value, currency: 'CNY' })
const detail = (id = 'report', applicationId = 'app', version = 2) => ({ id, applicationId, applicationVersion: version, financialVersion: 5, roundNo: 1, content: { title: '财务正文' } })
const workflow = () => ({ reportId: 'report', applicationId: 'app', applicationVersion: 2, financialVersion: 5, roundNo: 1, task: { taskId: 'task' } })
const page = (ids, nextBeforeId = null) => ({ items: ids.map(id => ({ id })), nextBeforeId })

test('金额按分精确计算，最大金额展示不发生浮点丢分', () => {
  assert.equal(amountMinor('0.10') + amountMinor('0.20'), amountMinor('0.30'))
  assert.equal(amountMinor('999999999999999.99'), 99999999999999999n)
  assert.equal(moneyLabel(money('999999999999999.99')), 'CNY 999,999,999,999,999.99')
  assert.equal(moneyLabel(money('0')), 'CNY 0.00')
  for (const input of ['1e2', '00.1', '1.234', '-1', ' 1 ', '.5', '1.', '1000000000000000', 1, null]) assert.throws(() => amountMinor(input))
})

test('核减只发送变化行，拒绝增加、重复、未知行与税额超过含税额', () => {
  const before = [{ lineNo: 1, gross: money('999999999999999.99'), tax: money('0.30') }, { lineNo: 2, gross: money('2.00'), tax: money('0.00') }]
  const input = [{ lineNo: 1, approvedGross: '999999999999999.98', approvedTax: '0.30' }, { lineNo: 2, approvedGross: '2', approvedTax: '0' }]
  assert.deepEqual(changedReductions(before, input), [input[0]])
  assert.equal(before[0].gross.value, '999999999999999.99')
  for (const lines of [[], [{ lineNo: 2, approvedGross: '2', approvedTax: '0' }], [{ lineNo: 2, approvedGross: '3', approvedTax: '0' }], [{ lineNo: 1, approvedGross: '0', approvedTax: '0.1' }], [{ lineNo: 1, approvedGross: '1', approvedTax: '0.31' }], [input[0], input[0]], [{ ...input[0], lineNo: 3 }]]) assert.throws(() => changedReductions(before, lines))
  assert.deepEqual(changedReductions(before, [{ lineNo: 1, approvedGross: '0', approvedTax: '0' }]), [{ lineNo: 1, approvedGross: '0', approvedTax: '0' }])
})

test('详情与控制任何归属或版本冲突均不展示金额或按钮', async () => {
  for (const patch of [{ reportId: 'other' }, { applicationId: 'other' }, { applicationVersion: 3 }, { financialVersion: 6 }, { roundNo: 2 }, { task: { taskId: 'other' } }]) {
    const query = new ExpenseDetailQuery(async () => detail(), async () => ({ ...workflow(), ...patch }))
    await query.load('alice', 'report', 'app', 'task')
    assert.equal(query.detail, null); assert.equal(query.workflow, null); assert.match(query.error, /变化/)
  }
  const query = new ExpenseDetailQuery(async () => detail('wrong'), async () => workflow())
  await query.load('alice', 'report', 'app'); assert.equal(query.detail, null)
})

test('历史费用必须匹配所选轮次且不请求当前办理控制', async () => {
  let calls = 0
  const query = new ExpenseDetailQuery(async (_id, roundNo) => ({ ...detail(), roundNo }), async () => { calls++; return workflow() })
  await query.load('alice', 'report', 'app', undefined, 3)
  assert.equal(query.detail.roundNo, 3); assert.equal(query.workflow, null); assert.equal(calls, 0)
})

test('切换身份立即清除正文，中断两个请求并丢弃迟到成功与错误', async () => {
  const reads = [], controls = []
  const query = new ExpenseDetailQuery((_id, _round, signal) => new Promise((resolve, reject) => reads.push({ signal, resolve, reject })),
    (_id, _task, signal) => new Promise((resolve, reject) => controls.push({ signal, resolve, reject })))
  const old = query.load('alice', 'report', 'app')
  const current = query.load('bob', 'report', 'app')
  assert.equal(reads[0].signal.aborted, true); assert.equal(controls[0].signal.aborted, true); assert.equal(query.detail, null)
  reads[1].resolve(detail()); controls[1].resolve(workflow()); await current
  reads[0].resolve(detail('leaked')); controls[0].reject({ status: 403 }); await old
  assert.equal(query.detail.id, 'report'); assert.equal(query.error, '')
  query.clear(); assert.equal(query.detail, null)
})

for (const status of [401, 403, 404]) test(`详情失权 ${status} 清空旧正文`, async () => {
  let fail = false
  const query = new ExpenseDetailQuery(async () => { if (fail) throw { status }; return detail() }, async () => workflow())
  await query.load('alice', 'report', 'app'); assert.ok(query.detail)
  fail = true; await query.load('alice', 'report', 'app'); assert.equal(query.detail, null); assert.equal(query.workflow, null); assert.match(query.error, /无法查看/)
  assert.equal(query.restricted, status === 403); query.clear(); assert.equal(query.restricted, false)
})

test('详情超时立即解除等待，迟到完整结果不能恢复正文', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] }); let finish
  const query = new ExpenseDetailQuery(() => new Promise(resolve => { finish = resolve }), async () => workflow())
  const waiting = query.load('alice', 'report', 'app'); t.mock.timers.tick(12_000)
  assert.equal(query.loading, false); assert.match(query.error, /超时/)
  finish(detail()); await waiting; assert.equal(query.detail, null)
})

test('资金翻页固定身份和筛选，网络失败保留原游标，失权清空已有余额', async () => {
  let fail = true, denied = false; const calls = []
  const query = new ExpensePageQuery(async filter => { calls.push(filter); if (denied) throw { status: 403 }; if (!filter.beforeId) return page(['one'], 'next'); if (fail) { fail = false; throw new Error('network') }; return page(['two']) })
  await query.load('alice', 'DRAFT'); await query.load('bob', 'DRAFT', true); await query.load('alice', 'APPROVED', true)
  assert.equal(calls.length, 1)
  await query.load('alice', 'DRAFT', true); assert.equal(query.nextBeforeId, 'next'); assert.deepEqual(query.items, [{ id: 'one' }])
  await query.load('alice', 'DRAFT', true); assert.deepEqual(calls[1], calls[2]); assert.deepEqual(query.items.map(x => x.id), ['one', 'two'])
  denied = true; await query.load('alice'); assert.deepEqual(query.items, []); assert.equal(query.nextBeforeId, null)
})

test('分页超时或切换身份后丢弃旧余额，翻页超时保持可重试边界', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] }); const calls = []
  const query = new ExpensePageQuery((filter, signal) => new Promise(resolve => calls.push({ filter, signal, resolve })))
  const first = query.load('alice'); calls[0].resolve(page(['one'], 'next')); await first
  const more = query.load('alice', undefined, true); await query.load('alice', undefined, true); assert.equal(calls.length, 2)
  t.mock.timers.tick(12_000); assert.equal(query.loading, false); assert.equal(query.nextBeforeId, 'next'); assert.match(query.error, /超时/)
  calls[1].resolve(page(['late'])); await more; assert.deepEqual(query.items, [{ id: 'one' }])
  const again = query.load('alice', undefined, true), other = query.load('bob')
  assert.equal(calls[2].signal.aborted, true); assert.deepEqual(query.items, [])
  calls[3].resolve(page(['bob'])); await other; calls[2].resolve(page(['alice'])); await again
  assert.deepEqual(query.items, [{ id: 'bob' }]); query.clear()
})

test('财务 API 编码路径、只读无缓存，写入保持双版本与精确金额且可用原请求恢复', async () => {
  globalThis.localStorage = { getItem: () => 'fixture-token' }
  const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
  bindAuthenticationActor({ tenantId: 'demo', userId: 'finance' })
  const calls = []; let fail = false
  globalThis.fetch = async (url, init) => {
    calls.push({ url, ...init }); if (fail) { fail = false; throw new Error('offline') }
    return Response.json(new URL(url, 'http://localhost').pathname === '/api/v1/expense-requests' ? { items: [] } : { reportId: 'report', applicationId: 'app' })
  }
  const signal = new AbortController().signal
  await api.expenseReports({ status: 'DRAFT', beforeId: 'next+/=' }, signal); await api.expenseRequests({}, signal); await api.employeeAdvances({}, signal)
  await api.expenseReport('report/id', 2, signal); await api.expenseWorkflow('report/id', 'task/id', signal)
  assert.ok(calls[3].url.endsWith('/report%2Fid?roundNo=2')); assert.equal(new URL(calls[4].url, 'http://localhost').searchParams.get('taskId'), 'task/id')
  for (const call of calls) { assert.equal(call.signal, signal); assert.equal(call.cache, 'no-store'); assert.equal(call.headers.has('Idempotency-Key'), false) }
  const input = { applicationVersion: 2, financialVersion: 5, comment: '明确核减', reasonCode: 'OTHER', proxyId: 'original-proxy', lines: [{ lineNo: 1, approvedGross: '999999999999999.98', approvedTax: '0.01' }] }
  fail = true; await assert.rejects(api.reduceExpense('report/id', 'task/id', input))
  const pending = writeRequests.pending(); assert.equal(pending.length, 1); assert.equal('body' in pending[0], false)
  await writeRequests.recover(pending[0].id)
  assert.deepEqual(JSON.parse(calls[5].body), input); assert.equal(calls[5].body, calls[6].body); assert.equal(calls[5].headers.get('Idempotency-Key'), calls[6].headers.get('Idempotency-Key'))
  assert.ok(calls[5].url.endsWith('/report%2Fid/tasks/task%2Fid/reduce')); assert.equal(writeRequests.pending().length, 0)
})
