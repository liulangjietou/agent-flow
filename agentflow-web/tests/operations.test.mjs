import test from 'node:test'
import assert from 'node:assert/strict'
const { ApprovalOperationsQuery, defaultOperationsFilter, operationsFilter, operationsDuration } = await import(process.env.AGENTFLOW_TEST_OPERATIONS)
const now = new Date('2024-03-01T01:00:00Z')

test('UTC 日期包含闰日和边界，拒绝无效范围与脱离流程的版本', () => {
  assert.deepEqual(defaultOperationsFilter(new Date('2024-02-29T20:00:00-05:00')), { from: '2024-02-01', to: '2024-03-01', processKey: '', organization: '' })
  assert.equal(operationsFilter('2023-03-02', '2024-03-01', ' flow ', '2', '', now).definitionVersion, 2)
  for (const [from, to, key, version] of [
    ['2023-02-29', '2024-03-01', '', ''], ['2023-03-01', '2024-03-01', '', ''],
    ['2024-03-02', '2024-03-01', '', ''], ['2024-03-01', '2024-03-02', '', ''],
    ['2024-02-29', '2024-03-01', '', '1'], ['2024-02-29', '2024-03-01', 'flow', '0'],
    ['2024-02-29', '2024-03-01', 'flow', '2147483648']
  ]) assert.throws(() => operationsFilter(from, to, key, version, '', now))
  assert.equal(operationsDuration(undefined), '—'); assert.equal(operationsDuration(0), '0 秒')
  assert.equal(operationsDuration(3661), '1 小时 1 分'); assert.equal(operationsDuration(90000), '1 天 1 小时')
})

test('组织名称保留字面量，限制长度且不会沿用旧筛选', () => {
  const result = operationsFilter('2024-02-01', '2024-03-01', '', '', '  R&D%_!室  ', now)
  assert.equal(result.organization, 'R&D%_!室')
  for (const organization of ['x'.repeat(129), 'x\ny']) {
    assert.throws(() => operationsFilter('2024-02-01', '2024-03-01', '', '', organization, now), /组织名称/)
  }
  assert.equal(defaultOperationsFilter(now).organization, '')
})

test('账号和筛选切换取消旧查询，迟到结果与错误均不能回填', async () => {
  const requests = []
  const query = new ApprovalOperationsQuery((filter, signal) => new Promise((resolve, reject) => requests.push({ filter, signal, resolve, reject })))
  const filter = defaultOperationsFilter(now)
  const first = query.load('demo:admin', filter)
  filter.processKey = 'changed'; filter.organization = '另一个组织'
  assert.equal(requests[0].filter.processKey, '')
  assert.equal(requests[0].filter.organization, '')
  const second = query.load('other:admin', filter)
  assert.equal(requests[0].signal.aborted, true)
  requests[1].resolve({ pendingTasks: 2 }); await second
  requests[0].resolve({ pendingTasks: 99 }); await first
  assert.equal(query.report.pendingTasks, 2)
  const third = query.load('other:admin', filter)
  assert.equal(query.report, null)
  query.clear(); requests[2].reject({ message: '旧租户错误' }); await third
  assert.equal(query.error, ''); assert.equal(query.report, null); assert.equal(query.loading, false)
  await query.load('', filter); assert.equal(requests.length, 3)
})

test('失败清除旧报告，重试成功才重新显示统计', async () => {
  let fail = false
  const query = new ApprovalOperationsQuery(async () => { if (fail) throw { message: '服务不可用' }; return { pendingTasks: 0 } })
  await query.load('demo:admin', defaultOperationsFilter(now)); assert.equal(query.report.pendingTasks, 0)
  fail = true; await query.load('demo:admin', defaultOperationsFilter(now))
  assert.equal(query.report, null); assert.equal(query.error, '服务不可用'); assert.equal(query.loading, false)
  fail = false; await query.load('demo:admin', defaultOperationsFilter(now)); assert.equal(query.error, '')
})

test('超时即取消，忽略取消的迟到成功不能成为新报告', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let finish, signal
  const query = new ApprovalOperationsQuery((_, abort) => { signal = abort; return new Promise(resolve => { finish = resolve }) })
  const pending = query.load('demo:admin', defaultOperationsFilter(now))
  t.mock.timers.tick(15000); assert.equal(signal.aborted, true)
  finish({ pendingTasks: 123 }); await pending
  assert.equal(query.report, null); assert.match(query.error, /超时/); assert.equal(query.loading, false)
})

test('运营 API 是可取消的认证只读请求，编码流程键且没有写入幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'operations-token' }
  const { api } = await import(process.env.AGENTFLOW_TEST_API)
  let request
  globalThis.fetch = async (url, init) => { request = { url, ...init }; return Response.json({}) }
  const controller = new AbortController()
  await api.approvalOperations({ from: '2024-02-01', to: '2024-03-01', processKey: '流程/x & y', definitionVersion: 2, organization: 'R&D%_!室' }, controller.signal)
  const url = new URL(request.url, 'http://localhost')
  assert.equal(url.pathname, '/api/v1/operations/approvals'); assert.equal(url.searchParams.get('processKey'), '流程/x & y')
  assert.equal(url.searchParams.get('definitionVersion'), '2'); assert.equal(request.signal, controller.signal)
  assert.equal(url.searchParams.get('organization'), 'R&D%_!室')
  assert.equal(request.headers.get('Authorization'), 'Bearer operations-token')
  assert.equal(request.headers.has('Idempotency-Key'), false); assert.equal(request.body, undefined)
})

// 使用生产模板检查无样本、真实零值和恢复后历史的业务含义。
test('实际指标模板区分无样本和零比例，受理与人工采纳均不冒充业务完成', async () => {
  const { createRenderer, h, reactive, nextTick } = await import('vue')
  const { default: Panel } = await import(process.env.AGENTFLOW_TEST_OUTCOME_METRICS)
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const renderer = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
    patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], root = node('root')
  const props = reactive({
    sla: { decidedTasks: 0, timedTasks: 0, violatedTasks: 0, withoutDeadlineTasks: 0, invalidTimingTasks: 0, cancelledTasks: 1, unfinishedTasks: 2, unrecordedDecisionTasks: 0, unverifiedRounds: 2 },
    notifications: { deliveries: 7, accepted: 1, failed: 1, retryWaiting: 1, unknown: 1, suppressed: 1, pending: 1, inFlight: 1, previouslyFailed: 3 },
    agent: { runs: 7, queued: 1, running: 1, awaitingReview: 1, failed: 1, adopted: 2, dismissed: 1, reviewedRuns: 3, adoptionRatePercent: 66.7 }
  })
  const app = renderer.createApp({ render: () => h(Panel, props) })
  try {
    app.mount(root)
    const text = () => all(root).map(el => el.text).join(' ')
    const strong = () => all(root).filter(el => el.tag === 'strong').map(el => el.text)
    assert.equal(strong()[0], '—'); assert.equal(strong()[2], '66.7%')
    assert.match(text(), /曾明确失败 3 条，恢复成功仍保留此历史计数/)
    assert.match(text(), /2 个轮次无法核实引擎历史/);
    assert.match(text(), /结果未知/); assert.match(text(), /渠道受理不代表最终送达/)
    assert.match(text(), /采纳包含人工修改后采纳，不等于审批通过/)
    assert.match(text(), /提交日期、流程版本及提交时组织/)
    Object.assign(props.sla, { decidedTasks: 1, timedTasks: 1, violationRatePercent: 0 })
    Object.assign(props.agent, { adopted: 0, dismissed: 1, reviewedRuns: 1, adoptionRatePercent: 0 })
    await nextTick(); assert.equal(strong()[0], '0.0%'); assert.equal(strong()[2], '0.0%')
    delete props.sla.violationRatePercent; delete props.agent.adoptionRatePercent
    await nextTick(); assert.equal(strong()[0], '—'); assert.equal(strong()[2], '—')
  } finally { app.unmount() }
})
