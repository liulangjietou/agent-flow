import test from 'node:test'
import assert from 'node:assert/strict'
const { readSplitRouting, SplitRoutingQuery } = await import(process.env.AGENTFLOW_TEST_EXPENSESPLITROUTING)
import { uuid, at, from, money, document, view } from './fixtures/expense-split-routing.mjs'
const read = v => readSplitRouting(v, uuid(1), 1, uuid(21))
const unreadable = error => error.code === 'RESPONSE_UNREADABLE'

test('六种状态保持独立语义，完整依据接受十进制金额和原版本', () => {
  for (const status of ['NOT_RECORDED', 'UNCONFIGURED', 'DISABLED', 'CLEAR', 'SPLIT_SUSPECTED', 'RESTRICTED']) assert.deepEqual(read(view(status)), view(status))
  const disabled = view('DISABLED'); delete disabled.details.configuration.rule; assert.deepEqual(read(disabled), disabled)
})
test('身份、轮次、未知状态或缺少规则及财务版本不能成为有效响应', () => {
  for (const mutate of [v => { v.reportId = uuid(99) }, v => { v.applicationId = uuid(99) }, v => { v.roundNo = 2 }, v => { v.roundNo = 0 },
    v => { v.status = '__proto__' }, v => { v.status = 'constructor' }, v => { v.status = 'NEW' }, v => { delete v.details.ruleVersion }, v => { v.details.ruleVersion = 2 },
    v => { delete v.details.definitionVersion }, v => { v.details.primary.applicationVersion = 0 }, v => { delete v.details.primary.financialVersion }, v => { v.details.primary.roundNo = 2 },
    v => { v.details.primary.applicationId = uuid(99) }, v => { v.details.primary.status = 'APPROVED' }]) {
    const value = view(); mutate(value); assert.throws(() => read(value), unreadable)
  }
})
test('受限或无记录响应拒绝正文、数量和实际风险结果，不把拒绝读取显示为安全', () => {
  for (const status of ['NOT_RECORDED', 'RESTRICTED']) {
    for (const extra of [{ details: view().details }, { reportCount: 2 }, { sourcesReadable: true }, { status: 'SPLIT_SUSPECTED' }, { status: 'CLEAR' }]) assert.throws(() => read({ ...view(status), ...extra }), unreadable)
  }
  for (const status of ['UNCONFIGURED', 'DISABLED', 'CLEAR', 'SPLIT_SUSPECTED']) assert.throws(() => read({ ...view(status), details: null }), unreadable)
})
test('每层对象拒绝未知字段，不能把内部或未授权内容传给展示组件', () => {
  for (const mutate of [v => { v.secret = 'x' }, v => { v.details.rawProcess = 'x' }, v => { v.details.configuration.script = 'x' }, v => { v.details.configuration.rule.extra = 'x' },
    v => { v.details.primary.secret = 'x' }, v => { v.details.primary.scope.account = 'x' }, v => { v.details.primary.lines[0].extra = 'x' }, v => { v.details.primary.lines[0].approvedGross.extra = 'x' },
    v => { v.details.assessment.extra = 'x' }, v => { v.details.assessment.categories[0].extra = 'x' }, v => { v.details.assessment.sources[1].secret = 'x' }]) {
    const value = view(); mutate(value); assert.throws(() => read(value), unreadable)
  }
})
test('来源须同口径且保留唯一单据和行号，首份必须等于本单冻结依据', () => {
  for (const mutate of [v => { v.details.assessment.sources[1].scope.tenantId = 'foreign' }, v => { v.details.assessment.sources[1].scope.employeeId = 'bob' },
    v => { v.details.assessment.sources[1].scope.legalEntityId = uuid(99) }, v => { v.details.assessment.sources[1].scope.currency = 'USD' },
    v => { v.details.assessment.sources[1].status = 'WITHDRAWN' }, v => { v.details.assessment.sources.reverse() }, v => { v.details.assessment.sources.push(document(2, 'IN_APPROVAL')) },
    v => { v.details.assessment.sources[1].applicationId = uuid(21) }, v => { v.details.assessment.sources[1].lines.push(v.details.assessment.sources[1].lines[0]) },
    v => { v.details.assessment.sources[1].lines[0].lineNo = 201 }, v => { v.details.assessment.sources[0].financialVersion = 9 },
    v => { v.details.assessment.sources = Array.from({ length: 1001 }, (_, n) => document(n + 1, 'IN_APPROVAL')) }]) {
    const value = view(); mutate(value); assert.throws(() => read(value), unreadable)
  }
})
test('窗口保留微秒边界，未来和下边界之前的来源不能混入', () => {
  const lower = view(); lower.details.assessment.sources[1].submittedAt = from; assert.deepEqual(read(lower), lower)
  for (const mutate of [v => { v.details.assessment.windowFrom = '2026-09-27T10:00:00Z' }, v => { v.details.assessment.assessedAt = '2026-10-04T10:00:01Z' },
    v => { v.details.assessment.sources[1].submittedAt = '2026-09-27T10:00:00Z' }, v => { v.details.assessment.sources[1].submittedAt = '2026-10-04T10:00:00.000002Z' },
    v => { v.details.primary.submittedAt = '2026-02-31T10:00:00Z' }]) {
    const value = view(); mutate(value); assert.throws(() => read(value), unreadable)
  }
})
test('规则、金额、类别与风险状态必须自洽，浮点金额不能参与展示', () => {
  for (const mutate of [v => { v.details.configuration.rule.windowDays = 0 }, v => { v.details.configuration.rule.threshold = money('0.00') },
    v => { v.details.configuration.rule.threshold.currency = 'USD' }, v => { v.details.configuration.gatewayIds = [] }, v => { v.details.configuration.mode = 'DISABLED' },
    v => { v.details.assessment.routingAmount.value = 8000 }, v => { v.details.assessment.routingAmount.value = '1e3' }, v => { v.details.assessment.ownAmount.value = '1.234' },
    v => { v.details.assessment.categories[0].total.currency = 'USD' }, v => { v.details.assessment.categories[0].reportCount = 3 }, v => { v.details.assessment.categories[0].triggered = false },
    v => { v.details.assessment.routingAmount = money('3000.00') }, v => { v.details.assessment.categories[0].categoryCode = 'UNRELATED' }]) {
    const value = view(); mutate(value); assert.throws(() => read(value), unreadable)
  }
})
test('大额十进制响应不经浮点损失，读取不改写输入', () => {
  const value = view('CLEAR'), huge = '999999999999999.99'; value.details.primary.lines[0].approvedGross = money(huge)
  value.details.assessment.sources[0] = structuredClone(value.details.primary)
  value.details.assessment.ownAmount = money(huge); value.details.assessment.routingAmount = money(huge); value.details.assessment.categories[0].total = money(huge)
  const before = structuredClone(value); assert.deepEqual(read(value), before); assert.deepEqual(value, before)
})
test('账号或原轮次切换立即清空正文并中止旧读取，迟到成功不能覆盖新页面', async () => {
  const pending = [], query = new SplitRoutingQuery((id, round, signal) => new Promise(resolve => pending.push({ id, round, signal, resolve })))
  const old = query.load('demo/old', uuid(1), uuid(21), 1)
  const current = query.load('demo/new', uuid(1), uuid(21), 1)
  assert.equal(pending[0].signal.aborted, true); assert.equal(query.view, null)
  pending[1].resolve(view('RESTRICTED')); await current
  pending[0].resolve(view()); await old
  assert.equal(query.view.status, 'RESTRICTED'); assert.equal(query.error, '')
})
test('清除或新轮次完成后的旧错误不能清空新结果，卸载不保留跨单依据', async () => {
  const pending = [], query = new SplitRoutingQuery((id, round, signal) => new Promise((resolve, reject) => pending.push({ signal, resolve, reject })))
  const old = query.load('demo/alice', uuid(1), uuid(21), 1); const current = query.load('demo/alice', uuid(1), uuid(21), 2)
  const value = { ...view('RESTRICTED'), roundNo: 2 }; pending[1].resolve(value); await current
  pending[0].reject(new Error('old failure')); await old; assert.equal(query.error, ''); assert.deepEqual(query.view, value)
  const late = query.load('demo/alice', uuid(1), uuid(21), 1); query.clear(); assert.equal(pending[2].signal.aborted, true)
  pending[2].resolve(view()); await late; assert.equal(query.view, null); assert.equal(query.loading, false)
})
test('错误绑定或接口失败均清空正文并给出错误，未提交轮次不发起读取', async () => {
  let calls = 0; const query = new SplitRoutingQuery(async () => { calls++; return view() })
  await query.load('demo/alice', uuid(1), uuid(21), 0); assert.equal(calls, 0); assert.equal(query.view, null)
  await query.load('demo/alice', uuid(1), uuid(21), 2); assert.equal(query.view, null); assert.ok(query.error)
})

test('受限和无记录摘要也必须遵守最大原轮次边界，越界不发请求', async () => {
  for (const status of ['NOT_RECORDED', 'RESTRICTED']) {
    const value = { ...view(status), roundNo: 2147483648 }
    assert.throws(() => readSplitRouting(value, uuid(1), value.roundNo, uuid(21)), unreadable)
  }
  let calls = 0; const query = new SplitRoutingQuery(async () => { calls++; return view('RESTRICTED') })
  await query.load('demo/alice', uuid(1), uuid(21), 2147483648); assert.equal(calls, 0)
})
