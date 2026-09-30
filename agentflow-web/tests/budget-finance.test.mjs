import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from '@vue/server-renderer'
const rules = await import(process.env.AGENTFLOW_TEST_BUDGET_FINANCE)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_BUDGETFINANCESTATUS)
const { default: Positions } = await import(process.env.AGENTFLOW_TEST_BUDGETFINANCEPOSITIONS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const clone = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const binding = () => ({ requestId: 'budget', applicationId: 'application', roundNo: 2, applicationVersion: 9, requestVersion: 4 })
const money = value => ({ value, currency: 'CNY' }), date = offset => new Date(Date.now() + offset).toISOString()
const content = () => ({ legalEntityId: 'entity', title: '预算调拨', purpose: '調拨原因', type: 'TRANSFER', accountingDate: '2026-09-30', sourceBudgetReference: 'source', targetBudgetReference: 'target', amount: money('70.00') })
const positions = () => ['source', 'target'].map((budgetReference, i) => ({ budgetReference, name: i ? '调入预算' : '调出预算', version: 'v1', periodReference: '2026', periodStart: '2026-01-01', periodEnd: '2026-12-31', periodStatus: 'OPEN', beforeLimit: money('1000.00'), committed: money('300.00'), consumed: money('450.00'), available: money('250.00'), proposedLimit: money(i ? '1070.00' : '930.00') }))
const view = () => ({ ...binding(), destinationReady: true, review: null, operation: null, actions: { review: true, authorize: false, query: false, retry: false, retire: false } })
const ready = () => ({ ...view(), review: { id: 'review', version: 3, status: 'READY', requestedAt: date(-30000), checkedAt: date(-10000), validUntil: date(60000), positions: positions(), issue: null }, actions: { ...view().actions, authorize: true } })
const observed = (status = 'APPLIED') => ({ status, revision: status === 'NOT_FOUND' ? 0 : 1, observedAt: date(-5000), reference: status === 'APPLIED' ? 'external-budget' : null, appliedAt: status === 'APPLIED' ? date(-10000) : null, rejection: status === 'REJECTED' ? 'LEDGER_VERSION_CONFLICT' : null })
const authorized = (status = 'QUEUED') => ({ ...view(), operation: { id: 'operation', version: status === 'QUEUED' ? 1 : 3, status, authorizedBy: 'finance', reason: '确认原台账与调整额度', authorizedAt: date(-30000), expiresAt: date(60000), updatedAt: date(-1000), positions: positions(), observation: ['APPLIED', 'REJECTED', 'NOT_FOUND'].includes(status) ? observed(status) : null, conflictingObservation: null, failure: null, retirement: null }, actions: { review: false, authorize: false, query: !['QUEUED', 'EXECUTING', 'QUERYING', 'EXPIRED', 'VOIDED'].includes(status), retry: status === 'NOT_FOUND', retire: ['QUEUED', 'EXPIRED', 'VOIDED', 'REJECTED'].includes(status) } })
const receipt = (action = 'REVIEW', value = view()) => ({ requestId: value.requestId, applicationId: value.applicationId, roundNo: value.roundNo, action, reviewId: action === 'REVIEW' ? 'new-review' : action === 'AUTHORIZE' ? value.review.id : null, reviewVersion: action === 'REVIEW' ? 1 : action === 'AUTHORIZE' ? value.review.version + 1 : null, operationId: action === 'REVIEW' ? null : action === 'AUTHORIZE' ? 'operation' : value.operation.id, operationVersion: action === 'REVIEW' ? null : action === 'AUTHORIZE' ? 1 : value.operation.version + (action !== 'RETIRE' || value.operation.status === 'QUEUED' ? 1 : 0), auditEventId: 'audit' })
let scope = 0
function mount() {
  const props = reactive({ ...binding(), content: content(), scopeKey: 'budget-finance-' + ++scope, locked: false }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value) })
  const instance = app.mount({})
  return { props, events, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('授权只提交批准双版本与复核身份，大金额两端不经浮点转换', () => {
  const value = ready()
  for (const [i, position] of value.review.positions.entries()) {
    position.beforeLimit = money('90071992547409.91'); position.available = money('90071992546659.91'); position.proposedLimit = money(i ? '90071992547479.91' : '90071992547339.91')
  }
  assert.deepEqual(rules.budgetFinanceInput(value, content(), 'AUTHORIZE', ' 已核对 '), { roundNo: 2, applicationVersion: 9, requestVersion: 4, reviewId: 'review', reviewVersion: 3, comment: '已核对' })
  assert.deepEqual(rules.budgetFinanceInput(view(), content(), 'REVIEW', '读取'), { roundNo: 2, applicationVersion: 9, requestVersion: 4, comment: '读取' })
})

test('追加与调减验证单端，调拨必须两端完整且金额守恒', () => {
  for (const type of ['INCREASE', 'DECREASE']) {
    const intent = content(); intent.type = type; intent[type === 'INCREASE' ? 'sourceBudgetReference' : 'targetBudgetReference'] = null
    const value = ready(); value.review.positions = [value.review.positions[type === 'INCREASE' ? 1 : 0]]
    assert.equal(rules.validateBudgetFinance(value, binding(), intent), value)
  }
  for (const mutate of [v => v.review.positions.pop(), v => v.review.positions[1].budgetReference = 'source', v => v.review.positions[0].proposedLimit.value = '929.99', v => v.review.positions[1].proposedLimit.value = '1070.01', v => v.review.positions[0].available.value = '249.99', v => v.review.positions[1].periodReference = 'other', v => v.review.positions[0].periodStatus = 'CLOSED', v => v.review.positions[0].beforeLimit.value = 1000, v => v.review.positions[0].consumed.currency = 'USD']) {
    const value = ready(); mutate(value); assert.throws(() => rules.validateBudgetFinance(value, binding(), content()))
  }
})

test('已经超预算的原台账仍允许追加修复，零可用额不禁止调入', () => {
  for (const type of ['INCREASE', 'TRANSFER']) {
    const intent = content(); intent.type = type
    if (type === 'INCREASE') intent.sourceBudgetReference = null
    const value = ready(); if (type === 'INCREASE') value.review.positions.shift()
    const target = value.review.positions.find(position => position.budgetReference === 'target')
    target.consumed = money('800.00'); target.available = money('0.00')
    assert.equal(rules.validateBudgetFinance(value, binding(), intent), value)
    assert.equal(rules.budgetFinanceInput(value, intent, 'AUTHORIZE', '按批准补充超预算项').reviewId, 'review')
  }
})

test('实际金额表保留两端精确字符串，并把预算名称作为文本转义', async () => {
  const values = positions(); values[0].name = '<script>alert(1)</script>'; values[0].beforeLimit = money('999999999999999.99')
  const html = await renderToString(createSSRApp(Positions, { positions: values, label: '原授权依据' }))
  for (const label of ['999999999999999.99', 'source', 'target', '调整前额度', '本次目标额度', '已占用', '已使用', '原授权依据']) assert.ok(html.includes(label), label)
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;')); assert.ok(!html.includes('<script>alert'))
})

test('跨申请、旧版本、未知状态与伪造动作不能生成可办理视图', () => {
  for (const mutate of [v => v.requestId = 'other', v => v.roundNo++, v => v.requestVersion++, v => v.review.status = 'APPLIED', v => v.review.checkedAt = null, v => v.actions.authorize = 'true', v => v.actions.query = true, v => v.destinationReady = false]) {
    const value = ready(); mutate(value); assert.throws(() => rules.validateBudgetFinance(value, binding(), content()))
  }
  const occupied = authorized('APPLIED'); occupied.review = ready().review; occupied.actions.authorize = true
  assert.throws(() => rules.validateBudgetFinance(occupied, binding(), content()))
})

test('授权与查无重发在截止点失效，查询仍可核对原指令', () => {
  const value = ready(), deadline = Date.parse(value.review.validUntil)
  assert.equal(rules.budgetActionAllowed(value, 'AUTHORIZE', deadline - 1), true); assert.equal(rules.budgetActionAllowed(value, 'AUTHORIZE', deadline), false)
  assert.throws(() => rules.budgetFinanceInput(value, content(), 'AUTHORIZE', '确认', deadline))
  const operation = authorized('NOT_FOUND'), expiry = Date.parse(operation.operation.expiresAt)
  assert.equal(rules.budgetActionAllowed(operation, 'RETRY', expiry - 1), true); assert.equal(rules.budgetActionAllowed(operation, 'RETRY', expiry), false)
  assert.equal(rules.budgetActionAllowed(operation, 'QUERY', expiry + 1), true)
})

test('缺失成功凭据或存在矛盾时不能显示无争议生效', () => {
  assert.equal(rules.validateBudgetFinance(authorized('APPLIED'), binding(), content()).operation.status, 'APPLIED')
  for (const mutate of [v => v.operation.observation = null, v => v.operation.observation.reference = null, v => v.operation.observation.appliedAt = null, v => v.operation.observation.status = 'NOT_FOUND', v => v.operation.conflictingObservation = observed('REJECTED'), v => v.operation.observation.revision = -1]) {
    const value = authorized('APPLIED'); mutate(value); assert.throws(() => rules.validateBudgetFinance(value, binding(), content()))
  }
  const disputed = authorized('RECONCILING'); disputed.operation.observation = observed('APPLIED'); disputed.operation.conflictingObservation = observed('REJECTED')
  assert.equal(rules.validateBudgetFinance(disputed, binding(), content()), disputed)
  for (const action of ['RETRY', 'RETIRE']) { const value = clone(disputed); value.actions[rules.budgetActionKeys[action]] = true; assert.throws(() => rules.budgetFinanceInput(value, content(), action, '确认')) }
})

test('安全结束必须匹配原编号、版本与停止证明，未知不能释放', () => {
  const value = authorized('VOIDED'); value.operation.retirement = { operationId: 'operation', operationVersion: 3, basis: 'NEVER_SENT', retiredBy: 'finance', retiredAt: date(0) }; value.actions.retire = false
  assert.equal(rules.validateBudgetFinance(value, binding(), content()), value)
  for (const mutate of [v => v.operation.retirement.operationId = 'other', v => v.operation.retirement.operationVersion = 1, v => v.operation.status = 'UNKNOWN', v => v.operation.retirement.basis = 'REJECTED', v => v.actions.retire = true]) {
    const changed = clone(value); mutate(changed); assert.throws(() => rules.validateBudgetFinance(changed, binding(), content()))
  }
  assert.throws(() => rules.validateBudgetFinance(value, binding(), content(), 'other'))
})

test('历史页拒绝重复指令、错误游标与不完整额度', () => {
  const operation = authorized('APPLIED').operation
  assert.deepEqual(rules.validateBudgetFinancePage({ items: [operation], nextBefore: null }, content()).items, [operation])
  for (const page of [{ items: [operation, operation], nextBefore: null }, { items: [operation], nextBefore: 'foreign' }, { items: [{ ...operation, positions: [] }], nextBefore: null }]) assert.throws(() => rules.validateBudgetFinancePage(page, content()))
})

test('最小回执对应原决定与后继版本，202 不解释为生效', () => {
  const value = ready(); assert.doesNotThrow(() => rules.validateBudgetFinanceReceipt(receipt('AUTHORIZE', value), value, 'AUTHORIZE'))
  for (const change of [{ reviewId: 'other' }, { roundNo: 1 }, { reviewVersion: 3 }, { operationVersion: 2 }, { auditEventId: '' }]) assert.throws(() => rules.validateBudgetFinanceReceipt({ ...receipt('AUTHORIZE', value), ...change }, value, 'AUTHORIZE'))
  for (const status of ['QUEUED', 'REJECTED']) { const old = authorized(status); assert.doesNotThrow(() => rules.validateBudgetFinanceReceipt(receipt('RETIRE', old), old, 'RETIRE')) }
})

test('准备授权不发送请求，明确确认和双击只保存一次', async () => {
  const value = ready(); api.budgetFinance = async () => clone(value); let complete; const calls = []
  api.authorizeBudgetAdjustment = (id, input) => { calls.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = mount()
  try {
    await settle(); p.state.prepare('AUTHORIZE'); assert.equal(calls.length, 0); p.state.comment = '确认两端额度'
    const sending = p.state.execute(); await p.state.execute(); assert.equal(calls.length, 1); assert.equal(p.state.saving, true)
    complete(receipt('AUTHORIZE', value)); await sending
    assert.equal(calls[0].id, 'budget'); assert.equal(calls[0].input.reviewId, 'review'); assert.equal(p.state.error, ''); assert.equal(p.state.pending, null)
    assert.equal(p.events.includes(true), true); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('错误回执要求恢复原请求与刷新，不能重复决定', async () => {
  api.budgetFinance = async () => ready(); let calls = 0
  api.authorizeBudgetAdjustment = async () => { calls++; return { ...receipt('AUTHORIZE', ready()), requestId: 'foreign' } }
  const p = mount()
  try { await settle(); p.state.prepare('AUTHORIZE'); p.state.comment = '确认'; await p.state.execute(); await p.state.execute(); assert.equal(calls, 1); assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); assert.match(p.state.error, /原申请/) }
  finally { p.close() }
})

test('身份变化清空台账和输入，旧读取与写入回执不能回填', async () => {
  const reads = []; api.budgetFinance = () => new Promise(resolve => reads.push(resolve)); const p = mount()
  try {
    p.props.scopeKey = 'new-finance'; reads[0](ready()); await settle(); assert.equal(p.state.view, null)
    reads[1](ready()); await settle(); let complete; api.authorizeBudgetAdjustment = () => new Promise(resolve => complete = resolve)
    p.state.prepare('AUTHORIZE'); p.state.comment = '旧身份决定'; const sending = p.state.execute(); p.props.scopeKey = 'third-finance'
    assert.equal(p.state.comment, ''); assert.equal(p.state.pending, null); assert.equal(p.state.view, null)
    complete(receipt('AUTHORIZE', ready())); await sending; assert.equal(p.state.notice, ''); assert.equal(p.state.saving, false); reads[2](view()); await settle()
  } finally { p.close() }
})

test('读取超时撤销请求，迟到成功不能恢复金融事实', async () => {
  api.budgetFinance = async () => ready(); const p = mount(); const originalTimer = global.setTimeout, originalClear = global.clearTimeout; let complete, expire, signal
  try {
    await settle(); api.budgetFinance = (_id, _round, _operation, value) => { signal = value; return new Promise(resolve => complete = resolve) }
    global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const pending = p.state.load(); expire(); assert.equal(signal.aborted, true); assert.equal(p.state.view, null)
    complete(ready()); await pending; assert.equal(p.state.view, null); assert.match(p.state.error, /超时/)
  } finally { global.setTimeout = originalTimer; global.clearTimeout = originalClear; p.close() }
})

test('结果未知保留原幂等键和正文，刷新不重发，恢复后重新核对', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'budget-finance-' + ++scope, roles: ['FINANCE'] })
  api.budgetFinance = async () => view(); let fail = true; const calls = []
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(receipt()), { status: 202 }) }
  const p = mount()
  try {
    await settle(); p.state.prepare('REVIEW'); p.state.comment = '读取原台账'; await p.state.execute()
    assert.equal(calls.length, 1); assert.equal(p.state.unconfirmed, true); await p.state.load(); p.state.prepare('REVIEW'); await p.state.execute(); assert.equal(calls.length, 1)
    fail = false; await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.blocked, true); await p.state.load(); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})

test('安全结束只操作原编号与版本，不自动复核或授权替代', async () => {
  const value = authorized('QUEUED'); api.budgetFinance = async () => clone(value); const calls = []
  api.budgetOperationAction = async (id, input) => { calls.push({ id, input }); return receipt(input.action, value) }
  api.reviewBudgetLedger = api.authorizeBudgetAdjustment = async () => { throw new Error('Unexpected replacement') }
  const p = mount()
  try { await settle(); p.state.prepare('RETIRE'); p.state.comment = '从未发送，结束原指令'; await p.state.execute(); assert.deepEqual(calls, [{ id: 'operation', input: { action: 'RETIRE', operationVersion: 1, comment: '从未发送，结束原指令' } }]); assert.equal(p.state.error, '') }
  finally { p.close() }
})

test('历史逐页追加并按原号读取，身份变化取消迟到历史', async () => {
  api.budgetFinance = async (_id, _round, operationId) => { const value = authorized('APPLIED'); if (operationId) value.operation.id = operationId; return value }
  const first = authorized('APPLIED').operation, second = { ...clone(first), id: 'second' }, cursors = []
  api.budgetFinanceHistory = async (_id, _round, before) => { cursors.push(before); return before ? { items: [second], nextBefore: null } : { items: [first], nextBefore: first.id } }
  const p = mount()
  try {
    await settle(); await p.state.loadHistory(); await p.state.loadHistory(true); assert.deepEqual(cursors, [undefined, 'operation']); assert.equal(p.state.history.length, 2)
    await p.state.load('second'); assert.equal(p.state.view.operation.id, 'second'); assert.equal(p.state.selected, 'second')
    let complete, signal; api.budgetFinanceHistory = (_id, _round, _before, value) => { signal = value; return new Promise(resolve => complete = resolve) }
    const pending = p.state.loadHistory(); p.props.scopeKey = 'new-history-owner'; assert.equal(signal.aborted, true); complete({ items: [first], nextBefore: null }); await pending
    assert.equal(p.state.history.length, 0); assert.equal(p.state.historyLoaded, false)
  } finally { p.close() }
})

test('字段权限撤销后刷新必须同时清除已读执行历史', async () => {
  api.budgetFinance = async () => authorized('APPLIED'); api.budgetFinanceHistory = async () => ({ items: [authorized('APPLIED').operation], nextBefore: null })
  const p = mount()
  try {
    await settle(); await p.state.loadHistory(); assert.equal(p.state.history.length, 1)
    api.budgetFinance = async () => { throw { status: 403, code: 'FORBIDDEN' } }; await p.state.load()
    assert.equal(p.state.view, null); assert.equal(p.state.history.length, 0); assert.equal(p.state.historyLoaded, false)
  } finally { p.close() }
})

test('历史接口拒绝当前权限时清除已展示额度和确认输入', async () => {
  api.budgetFinance = async () => ready(); api.budgetFinanceHistory = async () => { throw { status: 403, code: 'FORBIDDEN' } }
  const p = mount()
  try {
    await settle(); p.state.prepare('AUTHORIZE'); p.state.comment = '旧权限确认'; await p.state.loadHistory()
    assert.equal(p.state.view, null); assert.equal(p.state.pending, null); assert.equal(p.state.comment, ''); assert.equal(p.state.blocked, true)
  } finally { p.close() }
})

test('实际 API 转义原申请与操作路径，读取无缓存且写入保留幂等键', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  bindAuthenticationActor({ tenantId: 'demo', userId: 'budget-paths-' + ++scope, roles: ['FINANCE'] })
  try {
    await api.budgetFinance('req/other', 2, 'operation/other', new AbortController().signal)
    await api.budgetFinanceHistory('req/other', 2, 'cursor', new AbortController().signal)
    await api.budgetOperationAction('op/other', { action: 'QUERY', operationVersion: 4, comment: '原号查询' })
    assert.equal(calls[0].url, '/api/v1/budget-adjustments/req%2Fother/execution?roundNo=2&operationId=operation%2Fother'); assert.equal(calls[0].init.cache, 'no-store')
    assert.equal(calls[1].url, '/api/v1/budget-adjustments/req%2Fother/execution/history?roundNo=2&before=cursor&limit=25'); assert.equal(calls[1].init.cache, 'no-store')
    assert.equal(calls[2].url, '/api/v1/budget-adjustment-operations/op%2Fother/actions'); assert.ok(calls[2].init.headers.get('Idempotency-Key'))
  } finally { global.fetch = originalFetch; bindAuthenticationActor(null) }
})
