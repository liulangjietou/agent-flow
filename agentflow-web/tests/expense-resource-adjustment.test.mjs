import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_EXPENSE_RESOURCE_ADJUSTMENT)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_EXPENSERESOURCEADJUSTMENT)
const { default: Parent } = await import(process.env.AGENTFLOW_TEST_EXPENSESETTLEMENTSTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve)), money = value => ({ value, currency: 'CNY' })
const id = number => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
const binding = () => ({ applicationId: id(1), reportId: id(2), roundNo: 1, applicationVersion: 8, businessVersion: 4 })
function view() {
  const at = new Date(Date.now() - 10000).toISOString(), expiry = new Date(Date.parse(at) + 300000).toISOString()
  return { ...binding(), settlementVersion: 6, original: { gross: money('100.00'), offsets: money('50.00'), payable: money('50.00'), budgetOperationId: id(3) }, finance: true, canPrepare: true, preparationIssue: null, adjustments: [],
    latestPreparation: { id: id(4), version: 3, status: 'READY', accountingDate: at.slice(0, 10), evidenceReference: 'cancel-proof', reason: '已核清原财务依据', requestedAt: at, updatedAt: at, issue: null, periodReference: '2026-09', expiresAt: expiry, canAuthorize: true, authorizationIssue: null } }
}
function authorized(status = 'WAITING_BUDGET') {
  const value = view(), preparation = value.latestPreparation
  value.canPrepare = false; value.preparationIssue = 'EXPENSE_ADJUSTMENT_EXISTS'; preparation.status = 'AUTHORIZED'; preparation.canAuthorize = false; preparation.authorizationIssue = 'EXPENSE_ADJUSTMENT_NOT_READY'
  const applied = status === 'APPLIED' || status === 'REVIEW_REQUIRED'
  value.adjustments.push({ id: id(4), version: applied ? 3 : 1, status, resourcesReversed: applied, issue: status === 'REVIEW_REQUIRED' ? 'BUDGET_RECHECK_REQUIRED' : null, authorizedBy: 'finance', authorizedAt: preparation.updatedAt, updatedAt: preparation.updatedAt,
    accountingDate: preparation.accountingDate, evidenceReference: 'cancel-proof', reason: '已核清原财务依据',
    budget: { version: applied ? 3 : 1, status: applied ? 'APPLIED' : 'QUEUED', attempts: applied ? 1 : 0, expiresAt: preparation.expiresAt, updatedAt: preparation.updatedAt, issue: null, acceptedReference: applied ? 'budget-reversal-proof' : null, acceptedAt: applied ? preparation.updatedAt : null },
    availableActions: status === 'REVIEW_REQUIRED' ? ['QUERY', 'CONFIRM_COMPLETED'] : applied ? ['QUERY'] : [], canRetire: !applied, retirement: null })
  return value
}
function preparationReceipt(input) { const authorized = 'preparationId' in input; return { reportId: id(2), roundNo: 1, preparationId: authorized ? input.preparationId : id(10), preparationVersion: authorized ? input.preparationVersion + 1 : 1, adjustmentId: authorized ? input.preparationId : null, adjustmentVersion: authorized ? 1 : null, budgetVersion: authorized ? 1 : null, auditEventId: id(9) } }
function actionReceipt(input, value) {
  const entry = value.adjustments.find(item => item.id === input.adjustmentId), action = input.action ?? 'RETIRE'
  const adjustmentDelta = action === 'QUERY' ? (['APPLIED', 'READY'].includes(entry.status) ? 1 : 0) : action === 'RESEND_ORIGINAL' ? 0 : 1
  const budgetDelta = ['QUERY', 'RESEND_ORIGINAL'].includes(action) || action === 'RETIRE' && entry.budget.status === 'QUEUED' ? 1 : 0
  return { reportId: id(2), roundNo: 1, adjustmentId: entry.id, adjustmentVersion: input.adjustmentVersion + adjustmentDelta, budgetVersion: input.budgetVersion + budgetDelta,
    status: action === 'QUERY' ? (adjustmentDelta ? 'REVIEW_REQUIRED' : entry.status) : action === 'RESEND_ORIGINAL' ? entry.status : action === 'RETRY_RESOURCES' ? 'READY' : action === 'CONFIRM_COMPLETED' ? 'APPLIED' : 'RETIRED',
    budgetStatus: action === 'QUERY' ? 'UNKNOWN' : action === 'RESEND_ORIGINAL' ? 'QUEUED' : action === 'RETIRE' && budgetDelta ? 'VOIDED' : entry.budget.status, resourcesReversed: entry.resourcesReversed, auditEventId: id(9) }
}
let scope = 0
function mount(component = Component) {
  const props = reactive({ reportId: id(2), applicationId: id(1), roundNo: 1, applicationVersion: 8, financialVersion: 4, scopeKey: 'resource-adjustment-' + ++scope, locked: false }), changed = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, { ...props, onChanged: () => changed.push(true) })
  const instance = app.mount({})
  return { props, state: instance.$.setupState, changed, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}
function confirm(item, action, target = '') { item.state.prepare(action, target); item.state.reference = 'proof'; item.state.comment = '核对原财务依据'; item.state.acknowledged = true }

test('原单绑定和金额精确核对，预算成功与资源完成不能混淆', () => {
  assert.doesNotThrow(() => rules.validateAdjustment(view(), binding()))
  assert.doesNotThrow(() => rules.validateAdjustment(authorized('APPLIED'), binding()))
  const zero = view(); zero.original.offsets = money('100.00'); zero.original.payable = money('0.00'); assert.doesNotThrow(() => rules.validateAdjustment(zero, binding()))
  for (const change of [v => v.reportId = id(99), v => v.businessVersion++, v => v.roundNo++, v => v.original.payable.value = '49.99', v => v.original.offsets.currency = 'USD',
    v => v.original.gross.value = 100, v => v.latestPreparation.accountingDate = '2026-02-31', v => v.latestPreparation.expiresAt = 'bad', v => v.finance = false, v => v.adjustments = null]) {
    const invalid = view(); change(invalid); assert.throws(() => rules.validateAdjustment(invalid, binding()))
  }
  for (const change of [v => v.adjustments[0].resourcesReversed = false, v => v.adjustments[0].budget.acceptedReference = null, v => v.adjustments[0].canRetire = true,
    v => v.adjustments.push(structuredClone(v.adjustments[0])), v => v.adjustments[0].availableActions.push('RETRY_RESOURCES'), v => v.canPrepare = true,
    v => v.adjustments[0].budget.attempts = 0, v => v.adjustments[0].issue = 'BROKEN']) {
    const invalid = authorized('APPLIED'); change(invalid); assert.throws(() => rules.validateAdjustment(invalid, binding()))
  }
})
test('当前权限和期限约束授权与重发，查询不依赖发送期限', () => {
  const value = view(); const prepare = rules.adjustmentPrepareInput(value, '2026-09-29', ' proof ', ' 取消整笔报销 ')
  assert.deepEqual(prepare, { roundNo: 1, applicationVersion: 8, businessVersion: 4, settlementVersion: 6, accountingDate: '2026-09-29', evidenceReference: 'proof', reason: '取消整笔报销' })
  const authorize = rules.adjustmentAuthorizeInput(value, '明确授权'); assert.equal(authorize.preparationId, id(4)); assert.equal('amount' in authorize, false)
  assert.throws(() => rules.adjustmentAuthorizeInput(value, '授权', Date.parse(value.latestPreparation.expiresAt)), /过期/)
  const waiting = authorized(); waiting.adjustments[0].budget.status = 'NOT_FOUND'; waiting.adjustments[0].budget.attempts = 2; waiting.adjustments[0].availableActions = ['QUERY', 'RESEND_ORIGINAL']; waiting.adjustments[0].canRetire = false
  assert.doesNotThrow(() => rules.adjustmentOperationInput(waiting, id(4), 'QUERY', '核对', Date.parse(waiting.adjustments[0].budget.expiresAt)))
  assert.throws(() => rules.adjustmentOperationInput(waiting, id(4), 'RESEND_ORIGINAL', '重发', Date.parse(waiting.adjustments[0].budget.expiresAt)), /过期/)
  assert.throws(() => rules.adjustmentRetireInput(waiting, id(4), 'proof', '不能结束查无'))
})
test('接受回执不能冒充资源完成，也不能接受错单或错误版本', () => {
  const value = view(), input = rules.adjustmentAuthorizeInput(value, '授权'), receipt = preparationReceipt(input)
  assert.doesNotThrow(() => rules.validateAdjustmentPreparationReceipt(receipt, value, input))
  for (const change of [r => r.reportId = id(90), r => r.roundNo = 2, r => r.adjustmentId = id(80), r => r.adjustmentVersion = 3, r => r.budgetVersion = 2]) {
    const invalid = structuredClone(receipt); change(invalid); assert.throws(() => rules.validateAdjustmentPreparationReceipt(invalid, value, input))
  }
  const applied = authorized('APPLIED'), query = rules.adjustmentOperationInput(applied, id(4), 'QUERY', '查询'), result = actionReceipt(query, applied)
  assert.doesNotThrow(() => rules.validateAdjustmentActionReceipt(result, applied, query))
  for (const change of [r => r.resourcesReversed = false, r => r.status = 'APPLIED', r => r.budgetStatus = 'APPLIED', r => r.adjustmentVersion++, r => r.budgetVersion++]) {
    const invalid = structuredClone(result); change(invalid); assert.throws(() => rules.validateAdjustmentActionReceipt(invalid, applied, query))
  }
})
test('真实组件只读不自动授权，确认勾选后才发送原准备', async () => {
  const writes = []; api.expenseResourceAdjustment = async () => view()
  api.prepareExpenseResourceAdjustment = async (_, input) => { writes.push(input); return preparationReceipt(input) }
  api.authorizeExpenseResourceAdjustment = async (_, input) => { writes.push(input); return preparationReceipt(input) }
  const item = mount()
  try {
    await settle(); await item.state.load(); assert.equal(item.state.error, ''); assert.equal(writes.length, 0)
    confirm(item, 'PREPARE'); await item.state.execute(); assert.equal(writes.length, 1); assert.equal('preparationId' in writes[0], false)
    item.state.prepare('AUTHORIZE'); item.state.comment = '明确授权'; await item.state.execute(); assert.equal(writes.length, 1)
    item.state.acknowledged = true; await item.state.execute(); assert.equal(writes.length, 2); assert.equal(writes[1].preparationId, id(4)); assert.match(item.state.notice, /已授权/)
  } finally { item.close() }
})
test('预算复核和本地确认发送不同动作，不会重新提交准备或资源列表', async () => {
  let current = authorized('APPLIED'); const writes = []; api.expenseResourceAdjustment = async () => structuredClone(current)
  api.actExpenseResourceAdjustment = async (_, input) => { writes.push(input); return actionReceipt(input, current) }
  const item = mount()
  try {
    await settle(); confirm(item, 'QUERY', id(4)); await item.state.execute(); assert.equal(writes[0].action, 'QUERY')
    current = authorized('REVIEW_REQUIRED'); await item.state.load(); confirm(item, 'CONFIRM_COMPLETED', id(4)); await item.state.execute()
    assert.equal(writes[1].action, 'CONFIRM_COMPLETED'); assert.equal('resources' in writes[1], false)
    item.state.prepare('RETRY_RESOURCES', id(4)); assert.equal(item.state.pending, null)
  } finally { item.close() }
})
test('结束只接受可安全结束原调整，原授权历史仍保留', async () => {
  const current = authorized(), writes = []; api.expenseResourceAdjustment = async () => current
  api.retireExpenseResourceAdjustment = async (_, input) => { writes.push(input); return actionReceipt(input, current) }
  const item = mount()
  try { await settle(); confirm(item, 'RETIRE', id(4)); await item.state.execute(); assert.equal(writes.length, 1); assert.equal(writes[0].adjustmentId, id(4)); assert.equal(writes[0].evidenceReference, 'proof') }
  finally { item.close() }
})
test('身份切换会清除材料并丢弃迟到授权结果', async () => {
  let finish; api.expenseResourceAdjustment = async () => view(); api.authorizeExpenseResourceAdjustment = () => new Promise(resolve => { finish = resolve })
  const item = mount()
  try {
    await settle(); confirm(item, 'AUTHORIZE'); const writing = item.state.execute()
    api.expenseResourceAdjustment = async () => ({ ...view(), finance: false, canPrepare: false, preparationIssue: 'INDEPENDENT_FINANCE_REQUIRED', latestPreparation: null }); item.props.scopeKey = 'owner'; await settle()
    finish(preparationReceipt(rules.adjustmentAuthorizeInput(view(), '授权'))); await writing
    assert.equal(item.state.notice, ''); assert.equal(item.state.reference, ''); assert.equal(item.state.comment, ''); assert.equal(item.state.acknowledged, false)
    item.state.prepare('AUTHORIZE'); assert.equal(item.state.pending, null)
  } finally { item.close() }
})
test('失权清除已读财务数据，失败读取不保留旧按钮', async () => {
  api.expenseResourceAdjustment = async () => view(); api.authorizeExpenseResourceAdjustment = async () => { throw { status: 403, code: 'FORBIDDEN' } }
  const item = mount()
  try { await settle(); confirm(item, 'AUTHORIZE'); await item.state.execute(); assert.equal(item.state.view, null); assert.equal(item.state.comment, '') }
  finally { item.close() }
  api.expenseResourceAdjustment = async () => view(); const refresh = mount()
  try { await settle(); api.expenseResourceAdjustment = async () => { throw new Error('Synthetic read failure') }; await refresh.state.load(); assert.equal(refresh.state.view, null); assert.equal(refresh.state.requiresRefresh, true) }
  finally { refresh.close() }
})
test('未知授权恢复沿用原正文和幂等键，恢复后仍须刷新', async () => {
  const calls = []; let lost = true; bindAuthenticationActor({ tenantId: 'demo', userId: 'finance', roles: ['FINANCE'] }); api.expenseResourceAdjustment = async () => view()
  global.fetch = async (url, options) => { const input = JSON.parse(options.body); calls.push({ url, body: options.body, key: options.headers['Idempotency-Key'] ?? options.headers.get?.('Idempotency-Key') }); if (lost) throw new TypeError('Synthetic response lost'); return new Response(JSON.stringify(preparationReceipt(input)), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const item = mount()
  try {
    await settle(); confirm(item, 'AUTHORIZE'); await item.state.execute(); assert.equal(item.state.unconfirmed, true)
    lost = false; await writeRequests.recover(writeRequests.pending()[0].id); assert.equal(calls.length, 2); assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].key, calls[1].key); assert.match(calls[0].url, /resource-adjustment\/authorizations$/)
    assert.equal(item.state.requiresRefresh, true); await item.state.load(); assert.equal(item.state.requiresRefresh, false)
  } finally { item.close() }
})
test('初次读取不触发循环，独立资源变化才通知原报销刷新', async () => {
  let current = authorized(); api.expenseResourceAdjustment = async () => structuredClone(current); const item = mount()
  try {
    await settle(); await item.state.load(); assert.equal(item.changed.length, 0)
    current = authorized('APPLIED'); await item.state.load(); assert.equal(item.changed.length, 1)
    await item.state.load(); assert.equal(item.changed.length, 1)
  } finally { item.close() }
})
test('父结算面板在独立调整写入时禁止结算重试和卸载刷新', async () => {
  api.expenseSettlement = async () => { throw new Error('Synthetic absent settlement') }; const item = mount(Parent)
  try { await settle(); item.state.requiresRefresh = false; item.state.adjustmentBusy = true; assert.equal(item.state.blocked, true); const before = item.state.error; await item.state.load(); assert.equal(item.state.error, before) }
  finally { item.close() }
})
