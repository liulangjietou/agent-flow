import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_EXPENSE_PARTIAL_ADJUSTMENT)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_EXPENSEPARTIALADJUSTMENT)
const { default: Parent } = await import(process.env.AGENTFLOW_TEST_EXPENSESETTLEMENTSTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const settle = () => new Promise(resolve => setImmediate(resolve)), money = value => ({ value, currency: 'CNY' })
const id = number => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
const at = () => new Date(Date.now() - 10000).toISOString(), expiry = time => new Date(Date.parse(time) + 300000).toISOString()
const binding = () => ({ reportId: id(2), applicationId: id(1), roundNo: 1, applicationVersion: 8, businessVersion: 4 })
function amounts(gross = '100.00', tax = '10.00', offsets = '30.00', payable = '70.00') { return { gross: money(gross), tax: money(tax), offsets: money(offsets), payable: money(payable), lines: [{ lineNo: 1, gross: money(gross), tax: money(tax) }] } }
function view() {
  const time = at(), source = (number, status) => ({ id: id(number), version: 3, status, updatedAt: time, issue: null })
  return { ...binding(), settlementVersion: 6, settlementStatus: 'SETTLED', original: { amounts: amounts(), budget: source(3, 'APPLIED'), accrual: source(4, 'POSTED'), payment: source(5, 'SUCCEEDED'), paymentVoucher: source(6, 'POSTED') }, remaining: amounts(), previousId: null, previousVersion: 0, returnsVersion: 2, finance: true, adjustments: [], returns: [{ registrationId: id(7), fundsIdentity: 'bank-r1', amount: money('20.00'), receivedAt: time, available: true }, { registrationId: id(8), fundsIdentity: 'bank-r2', amount: money('50.00'), receivedAt: time, available: true }] }
}
function active() {
  const value = view(), time = at(); value.returns[0].available = false
  value.adjustments.push({ id: id(10), version: 1, status: 'WAITING_FINANCE', issue: null, requestedBy: 'finance', evidenceReference: 'material', reason: '部分费用取消', createdAt: time, updatedAt: time, before: amounts(), after: amounts('80.00', '8.00', '30.00', '50.00'), returnIds: ['bank-r1'], budget: null, accrual: null, completion: null, retirement: null, budgetPreparation: null, accrualPreparation: null, availableActions: [], canRetire: true, canQueryOriginals: true })
  return value
}
function prepared() {
  const value = active(), entry = value.adjustments[0], time = entry.updatedAt
  entry.budgetPreparation = { id: id(11), version: 3, status: 'READY', accountingDate: time.slice(0, 10), evidenceReference: 'period-proof', reason: '核对本侧', requestedAt: time, updatedAt: time, issue: null, periodReference: '2026-10', expiresAt: expiry(time), canAuthorize: true, authorizationIssue: null }
  entry.accrualPreparation = { ...entry.budgetPreparation, id: id(12) }
  return value
}
function operation(entry, side, status = side === 'BUDGET' ? 'APPLIED' : 'POSTED') {
  const time = entry.updatedAt
  const accepted = side === 'BUDGET' ? { status: 'APPLIED', observedAt: time, rejection: null, reference: 'budget-proof', reducedAmount: money('20.00'), appliedAt: time }
    : { status: 'POSTED', revision: 1, observedAt: time, rejection: null, acceptanceReference: 'accepted-proof', postingReference: 'posting-proof', voucherReference: 'voucher-proof', postedAt: time }
  return { id: id(side === 'BUDGET' ? 11 : 12), version: 3, status, attempts: 1, authorizedBy: 'finance', authorizedAt: time, accountingDate: time.slice(0, 10), expiresAt: expiry(time), updatedAt: time, issue: null, accepted, conflicting: null, canResolve: false, resolutionIssue: 'NOT_DISPUTED', candidateValidUntil: null, latestResolution: null }
}
function completed() {
  const value = active(), entry = value.adjustments[0]; entry.version = 8; entry.status = 'APPLIED'; entry.canRetire = false
  entry.budget = operation(entry, 'BUDGET'); entry.accrual = operation(entry, 'ACCRUAL'); entry.availableActions = ['QUERY_BUDGET', 'QUERY_ACCRUAL']
  entry.completion = { budgetVersion: 3, accrualVersion: 3, budgetReference: 'budget-proof', postingReference: 'posting-proof', voucherReference: 'voucher-proof', at: entry.updatedAt }
  value.previousId = entry.id; value.previousVersion = entry.version; value.remaining = structuredClone(entry.after)
  return value
}
function disputed(side = 'BUDGET') {
  const value = active(), entry = value.adjustments[0]; entry.version = 5; entry.canRetire = false
  const current = operation(entry, side, 'RECONCILING'); current.conflicting = structuredClone(current.accepted); current.canResolve = true; current.resolutionIssue = null; current.candidateValidUntil = expiry(current.conflicting.observedAt)
  entry[side === 'BUDGET' ? 'budget' : 'accrual'] = current; entry.availableActions = [side === 'BUDGET' ? 'QUERY_BUDGET' : 'QUERY_ACCRUAL']
  return value
}
const inputs = () => [{ lineNo: 1, remainingGross: '80.00', remainingTax: '8.00' }]
function receipt(intent, input) { return { reportId: id(2), roundNo: input.roundNo, adjustmentId: intent === 'ORIGINAL_QUERY' ? null : intent === 'CREATE' ? id(10) : input.adjustmentId, adjustmentVersion: intent === 'ORIGINAL_QUERY' ? null : intent === 'CREATE' ? 1 : input.adjustmentVersion + (['SOURCE_QUERY', 'PREPARE'].includes(intent) ? 0 : 1), preparationId: intent === 'PREPARE' ? id(40) : intent === 'AUTHORIZE' ? input.preparationId : null, preparationVersion: intent === 'PREPARE' ? 1 : intent === 'AUTHORIZE' ? input.preparationVersion + 1 : null, auditEventId: id(90) } }
let scope = 0
function mount(component = Component) {
  const props = reactive({ ...binding(), financialVersion: 4, scopeKey: 'partial-' + ++scope, locked: false }), changed = [], busy = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, { ...props, onChanged: () => changed.push(true), onBusy: value => busy.push(value) })
  const instance = app.mount({})
  return { props, state: instance.$.setupState, changed, busy, close() { app.unmount(); Object.assign(api, originalApi); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}
function confirm(item, intent, target = '', side = 'BUDGET') { item.state.prepare(intent, target, side); item.state.reference = 'proof'; item.state.comment = '核对并办理本次调整'; item.state.accountingDate = at().slice(0, 10); item.state.acknowledged = true }

test('原批准、已完成剩余和未完成意图分别核对，拒绝错单与虚构完成', () => {
  for (const value of [view(), active(), prepared(), completed(), disputed(), disputed('ACCRUAL')]) assert.equal(rules.validatePartial(value, binding()), value)
  for (const change of [v => v.reportId = id(99), v => v.roundNo++, v => v.businessVersion++, v => v.remaining.gross.value = '80', v => v.remaining.payable.value = '70.01', v => v.original.amounts.gross.value = 100, v => v.original.amounts.tax.currency = 'USD', v => v.previousVersion = 1, v => v.finance = 'true', v => v.adjustments = null, v => v.returns.push(structuredClone(v.returns[0]))]) {
    const invalid = view(); change(invalid); assert.throws(() => rules.validatePartial(invalid, binding()))
  }
  for (const change of [v => v.remaining = amounts(), v => v.previousVersion++, v => v.adjustments[0].completion = null, v => v.adjustments[0].budget = null, v => v.adjustments[0].canRetire = true, v => v.adjustments[0].after.lines[0].lineNo = 2, v => v.adjustments[0].budget.accepted.reducedAmount.value = '21.00', v => v.returns[0].available = true, v => v.adjustments.push(structuredClone(v.adjustments[0]))]) {
    const invalid = completed(); change(invalid); assert.throws(() => rules.validatePartial(invalid, binding()))
  }
})
test('只使用整分与已登记资金身份，额外入款不会被自动全选', () => {
  const value = view(), input = rules.partialCreateInput(value, inputs(), ['bank-r1'], ' proof ', ' 取消部分费用 ')
  assert.deepEqual(input, { roundNo: 1, applicationVersion: 8, businessVersion: 4, settlementVersion: 6, previousId: null, previousVersion: 0, returnsVersion: 2, lines: inputs(), returnIds: ['bank-r1'], evidenceReference: 'proof', reason: '取消部分费用' })
  for (const returns of [[], ['bank-r1', 'bank-r2'], ['bank-r1', 'bank-r1'], [id(7)], ['unknown']]) assert.throws(() => rules.partialCreateInput(value, inputs(), returns, 'proof', 'reason'))
  const large = view(); large.original.amounts = large.remaining = amounts('90071992547409.93', '0.00', '0.00', '90071992547409.93'); large.returns[0].amount = money('0.01')
  assert.equal(rules.partialPreview(large, [{ lineNo: 1, remainingGross: '90071992547409.92', remainingTax: '0' }], ['bank-r1']).reduction.value, '0.01')
})
test('剩余低于借款抵扣时按差额恢复借款，全抵扣路径不制造银行版本', () => {
  const value = view(), preview = rules.partialPreview(value, [{ lineNo: 1, remainingGross: '20', remainingTax: '2' }], ['bank-r2', 'bank-r1'])
  assert.equal(preview.bankReturn.value, '70.00'); assert.equal(preview.advanceRestored.value, '10.00'); assert.deepEqual(preview.returnIds, ['bank-r1', 'bank-r2']); assert.equal(preview.matched, true)
  const offset = view(); offset.original.amounts = offset.remaining = amounts('100', '10', '100', '0'); offset.original.payment = null; offset.original.paymentVoucher = null; offset.returns = []; offset.returnsVersion = 0
  assert.doesNotThrow(() => rules.validatePartial(offset, binding())); assert.equal(rules.partialPreview(offset, inputs(), []).advanceRestored.value, '20.00')
  const query = rules.partialOriginalInput(offset, '核对'); assert.equal(query.paymentVersion, 0); assert.equal(query.paymentVoucherVersion, 0)
  assert.doesNotThrow(() => rules.partialCreateInput(offset, inputs(), [], 'proof', '核对'))
})
test('逐行核减禁止税额重分类、增额、负数、重复行及无实际变化', () => {
  for (const lines of [[], inputs().concat(inputs()), [{ lineNo: 2, remainingGross: '80', remainingTax: '8' }], [{ lineNo: 1, remainingGross: '101', remainingTax: '10' }], [{ lineNo: 1, remainingGross: '100', remainingTax: '10' }], [{ lineNo: 1, remainingGross: '99', remainingTax: '0' }], [{ lineNo: 1, remainingGross: '8', remainingTax: '9' }], [{ lineNo: 1, remainingGross: '-1', remainingTax: '0' }], [{ lineNo: 1, remainingGross: '80.001', remainingTax: '8' }]]) assert.throws(() => rules.partialPreview(view(), lines, []))
})
test('连续调整引用实际完成的当前修订，不把活动意图当成剩余', () => {
  const value = completed(); assert.equal(rules.partialCanCreate(value), true)
  const input = rules.partialCreateInput(value, [{ lineNo: 1, remainingGross: '30', remainingTax: '3' }], ['bank-r2'], 'proof', '继续核减')
  assert.equal(input.previousId, id(10)); assert.equal(input.previousVersion, 8); assert.equal(input.lines[0].remainingGross, '30.00')
  value.adjustments[0].status = 'REVIEW_REQUIRED'; value.adjustments[0].issue = 'SOURCE_CHANGED'; assert.equal(rules.partialCanCreate(value), false)
  assert.equal(rules.partialCanCreate(active()), false); assert.throws(() => rules.partialOriginalInput(active(), '不能绕过活动意图'))
})
test('两侧准备独立授权，只发送本人准备版本且到期边界不再发送', () => {
  const value = prepared(), entry = value.adjustments[0]
  for (const side of rules.partialSides) {
    assert.equal(rules.partialAuthorizeInput(value, entry.id, side, '授权').preparationId, side === 'BUDGET' ? id(11) : id(12))
    assert.throws(() => rules.partialAuthorizeInput(value, entry.id, side, '授权', Date.parse(entry.budgetPreparation.expiresAt)), /过期/)
  }
  entry.budget = operation(entry, 'BUDGET'); entry.canRetire = false
  assert.equal(rules.partialCanPrepare(value, entry, 'BUDGET'), false); assert.equal(rules.partialCanPrepare(value, entry, 'ACCRUAL'), true)
  assert.throws(() => rules.partialPrepareInput(value, entry.id, 'ACCRUAL', '2026-02-31', 'proof', '核对'))
  entry.accrualPreparation.status = 'RUNNING'; assert.equal(rules.partialCanPrepare(value, entry, 'ACCRUAL'), false)
})
test('查询无发送期限，重发仍使用原号并拒绝过期授权', () => {
  const value = active(), entry = value.adjustments[0]; entry.budget = operation(entry, 'BUDGET', 'NOT_FOUND'); entry.budget.accepted = { status: 'NOT_FOUND', observedAt: entry.updatedAt, rejection: null, reference: null, reducedAmount: null, appliedAt: null }; entry.canRetire = false; entry.availableActions = ['QUERY_BUDGET', 'RESEND_BUDGET']
  rules.validatePartial(value, binding())
  const expiryAt = Date.parse(entry.budget.expiresAt)
  assert.equal(rules.partialActionInput(value, entry.id, 'QUERY_BUDGET', '核对', expiryAt).adjustmentVersion, entry.version)
  assert.throws(() => rules.partialActionInput(value, entry.id, 'RESEND_BUDGET', '重发', expiryAt), /过期/)
  assert.throws(() => rules.partialRetireInput(value, entry.id, 'proof', '不能结束查无'))
})
test('裁决采用实际候选，失权、非终态、失效与期限边界均不能写入', () => {
  for (const side of rules.partialSides) {
    const value = disputed(side), entry = value.adjustments[0], operation = rules.partialOperation(entry, side)
    const input = rules.partialDisputeInput(value, entry.id, side, 'proof', '明确裁决')
    assert.equal(input.outcome, side === 'BUDGET' ? 'APPLIED' : 'POSTED'); assert.equal('postingReference' in input, false)
    assert.throws(() => rules.partialDisputeInput(value, entry.id, side, 'proof', '核对', Date.parse(operation.candidateValidUntil)), /过期/)
    value.finance = false; assert.throws(() => rules.partialDisputeInput(value, entry.id, side, 'proof', '失权')); value.finance = true
    operation.canResolve = false; assert.throws(() => rules.partialDisputeInput(value, entry.id, side, 'proof', '不可裁决')); operation.canResolve = true
    operation.conflicting.status = 'PENDING'; assert.throws(() => rules.partialDisputeInput(value, entry.id, side, 'proof', '非终态'))
  }
})
test('重新授权后的旧决定仍绑定旧操作，申请人只读且不能含本人准备', () => {
  const value = completed(), entry = value.adjustments[0]; entry.budget.latestResolution = { id: id(100), operationId: id(101), beforeVersion: 4, afterVersion: 5, outcome: 'REJECTED', observedAt: entry.createdAt, resolvedBy: 'prior-finance', resolvedAt: entry.updatedAt, evidenceReference: 'old-proof', reason: '旧号失败' }
  value.finance = false; entry.availableActions = []; entry.canQueryOriginals = false
  assert.doesNotThrow(() => rules.validatePartial(value, binding())); assert.equal(entry.budget.latestResolution.operationId, id(101))
  entry.budgetPreparation = prepared().adjustments[0].budgetPreparation; assert.throws(() => rules.validatePartial(value, binding()))
})
test('完成凭据必须与两侧实际事实一致，已完成或已复核根不能开放新授权', () => {
  for (const change of [v => v.adjustments[0].completion.budgetReference = 'other-budget', v => v.adjustments[0].completion.voucherReference = 'other-voucher', v => v.adjustments[0].budgetPreparation = prepared().adjustments[0].budgetPreparation]) {
    const invalid = completed(); change(invalid); assert.throws(() => rules.validatePartial(invalid, binding()))
  }
  const invalid = prepared(); invalid.adjustments[0].status = 'REVIEW_REQUIRED'; invalid.adjustments[0].issue = 'SOURCE_CHANGED'; assert.throws(() => rules.validatePartial(invalid, binding()))
})
test('未知请求回执只能证明实际意图的版本变化，不能冒充自动完成', () => {
  const value = prepared(), target = id(10)
  const requests = [['CREATE', rules.partialCreateInput(view(), inputs(), ['bank-r1'], 'proof', '创建'), view()], ['ORIGINAL_QUERY', rules.partialOriginalInput(view(), '查询'), view()], ['PREPARE', rules.partialPrepareInput(value, target, 'BUDGET', at().slice(0, 10), 'proof', '准备'), value], ['AUTHORIZE', rules.partialAuthorizeInput(value, target, 'BUDGET', '授权'), value], ['SOURCE_QUERY', rules.partialSourceInput(value, target, '查询'), value], ['RETIRE', rules.partialRetireInput(value, target, 'proof', '结束'), value], ['DISPUTE', rules.partialDisputeInput(disputed(), target, 'BUDGET', 'proof', '裁决'), disputed()], ['QUERY_BUDGET', rules.partialActionInput(completed(), target, 'QUERY_BUDGET', '查询'), completed()]]
  for (const [intent, input, shown] of requests) {
    const result = receipt(intent, input); assert.doesNotThrow(() => rules.validatePartialReceipt(result, shown, intent, input))
    for (const change of [r => r.reportId = id(999), r => r.roundNo++, r => r.adjustmentVersion = 100, r => r.auditEventId = 'bad', r => r.preparationVersion = 100]) { const invalid = structuredClone(result); change(invalid); assert.throws(() => rules.validatePartialReceipt(invalid, shown, intent, input)) }
  }
})
test('真实组件初始仅查询，手动选择和确认之后才登记调整', async () => {
  const writes = []; api.expensePartialAdjustments = async () => view(); api.createExpensePartialAdjustment = async (_, input) => { writes.push(input); return receipt('CREATE', input) }
  const item = mount()
  try {
    await settle(); assert.equal(item.state.error, ''); assert.equal(writes.length, 0)
    confirm(item, 'CREATE'); assert.deepEqual([...item.state.returnIds], []); item.state.lines = inputs(); item.state.returnIds = ['bank-r1']; item.state.acknowledged = false
    await item.state.execute(); assert.equal(writes.length, 0); item.state.acknowledged = true; await item.state.execute()
    assert.equal(writes.length, 1); assert.deepEqual(writes[0].returnIds, ['bank-r1']); assert.match(item.state.notice, /分别准备/); assert.ok(item.busy.includes(true)); assert.equal(item.busy.at(-1), false)
  } finally { item.close() }
})
test('真实组件预算与 ERP 分侧授权、查询、准备及裁决均使用各自入口', async () => {
  let shown = prepared(); const writes = []
  api.expensePartialAdjustments = async () => structuredClone(shown)
  for (const [name, intent] of [['prepareExpensePartialAdjustment', 'PREPARE'], ['authorizeExpensePartialAdjustment', 'AUTHORIZE'], ['queryExpensePartialSources', 'SOURCE_QUERY'], ['actExpensePartialAdjustment', 'QUERY_BUDGET'], ['resolveExpensePartialDispute', 'DISPUTE'], ['retireExpensePartialAdjustment', 'RETIRE']]) api[name] = async (_, input) => { writes.push({ name, input }); return receipt(intent, input) }
  const item = mount()
  try {
    await settle(); confirm(item, 'PREPARE', id(10), 'ACCRUAL'); await item.state.execute(); assert.equal(writes.at(-1).input.side, 'ACCRUAL')
    confirm(item, 'AUTHORIZE', id(10), 'ACCRUAL'); await item.state.execute(); assert.equal(writes.at(-1).input.preparationId, id(12))
    confirm(item, 'SOURCE_QUERY', id(10)); await item.state.execute(); assert.equal(writes.at(-1).input.accrualVersion, 3)
    confirm(item, 'RETIRE', id(10)); await item.state.execute(); assert.equal(writes.at(-1).name, 'retireExpensePartialAdjustment')
    shown = disputed(); await item.state.load(); confirm(item, 'DISPUTE', id(10)); await item.state.execute(); assert.equal(writes.at(-1).input.outcome, 'APPLIED')
    confirm(item, 'QUERY_BUDGET', id(10)); await item.state.execute(); assert.equal(writes.at(-1).input.action, 'QUERY_BUDGET'); assert.equal(writes.length, 6); assert.equal(item.state.error, '')
  } finally { item.close() }
})
test('过期候选不发请求；取消与父级互斥锁不产生写入', async () => {
  const value = disputed(), operation = value.adjustments[0].budget, originalNow = Date.now
  let writes = 0; api.expensePartialAdjustments = async () => value; api.resolveExpensePartialDispute = async () => { writes++; throw new Error('不应写入') }
  const item = mount()
  try { await settle(); assert.equal(item.state.error, ''); confirm(item, 'DISPUTE', id(10)); Date.now = () => Date.parse(operation.candidateValidUntil); await item.state.execute(); assert.match(item.state.error, /过期/); assert.equal(writes, 0); item.state.cancel(); await item.state.execute(); item.props.locked = true; confirm(item, 'DISPUTE', id(10)); assert.equal(item.state.pending, null); assert.equal(writes, 0) } finally { Date.now = originalNow; item.close() }
})
test('身份切换立即清除敏感表单并丢弃旧读取、旧写入和卸载后的响应', async () => {
  let finishRead, finishWrite; api.expensePartialAdjustments = async () => prepared()
  api.authorizeExpensePartialAdjustment = (_, input) => new Promise(resolve => { finishWrite = () => resolve(receipt('AUTHORIZE', input)) })
  const item = mount()
  try {
    await settle(); confirm(item, 'AUTHORIZE', id(10)); const pendingWrite = item.state.execute(); assert.equal(item.state.saving, true)
    api.expensePartialAdjustments = () => new Promise(resolve => { finishRead = resolve }); item.props.scopeKey = 'new-identity'; assert.equal(item.state.view, null); assert.equal(item.state.comment, ''); assert.equal(item.state.pending, null)
    finishWrite(); await pendingWrite; assert.equal(item.state.notice, ''); assert.equal(item.state.view, null)
    item.props.scopeKey = ''; finishRead(prepared()); await settle(); assert.equal(item.state.view, null)
  } finally { item.close() }
})
test('服务端失权或错单读取清除旧内容，失败后必须重新核对', async () => {
  api.expensePartialAdjustments = async () => prepared(); api.authorizeExpensePartialAdjustment = async () => { throw Object.assign(new Error('Forbidden'), { status: 403 }) }
  const item = mount()
  try {
    await settle(); confirm(item, 'AUTHORIZE', id(10)); await item.state.execute(); assert.equal(item.state.view, null); assert.equal(item.state.comment, ''); assert.equal(item.state.requiresRefresh, true)
    api.expensePartialAdjustments = async () => ({ ...view(), reportId: id(100) }); await item.state.load(); assert.equal(item.state.view, null); assert.ok(item.state.error)
  } finally { item.close() }
})
test('实际 API 读取使用 no-store 和取消信号，全部写入口进入幂等恢复', async () => {
  bindAuthenticationActor({ tenantId: 't', userId: 'finance', roles: ['FINANCE'] }); const calls = []
  global.fetch = async (url, options) => { calls.push({ url, options }); return new Response(JSON.stringify(view()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  try {
    const signal = new AbortController().signal; await api.expensePartialAdjustments(id(2), 1, signal); assert.equal(calls[0].options.cache, 'no-store'); assert.ok(calls[0].options.signal); assert.match(calls[0].url, /partial-adjustments\?roundNo=1$/)
    const value = prepared(), shown = disputed(), requests = [
      ['createExpensePartialAdjustment', '', rules.partialCreateInput(view(), inputs(), ['bank-r1'], 'proof', '创建')], ['queryExpensePartialOriginals', '/original-queries', rules.partialOriginalInput(view(), '查询')], ['prepareExpensePartialAdjustment', '/preparations', rules.partialPrepareInput(value, id(10), 'BUDGET', at().slice(0, 10), 'proof', '准备')], ['authorizeExpensePartialAdjustment', '/authorizations', rules.partialAuthorizeInput(value, id(10), 'BUDGET', '授权')], ['queryExpensePartialSources', '/source-queries', rules.partialSourceInput(value, id(10), '查询')], ['retireExpensePartialAdjustment', '/retirements', rules.partialRetireInput(value, id(10), 'proof', '结束')], ['actExpensePartialAdjustment', '/actions', rules.partialActionInput(shown, id(10), 'QUERY_BUDGET', '查询')], ['resolveExpensePartialDispute', '/disputes', rules.partialDisputeInput(shown, id(10), 'BUDGET', 'proof', '裁决')]
    ]
    for (const [name, suffix, input] of requests) { await api[name](id(2), input); const request = calls.at(-1); assert.ok(request.url.endsWith('partial-adjustments' + suffix)); assert.equal(request.options.method, 'POST'); assert.deepEqual(JSON.parse(request.options.body), input); assert.ok(new Headers(request.options.headers).get('Idempotency-Key')) }
  } finally { global.fetch = originalFetch; bindAuthenticationActor(null) }
})
test('未确认创建覆盖无后缀路径，恢复保持原请求体和幂等键', async () => {
  bindAuthenticationActor({ tenantId: 't', userId: 'finance', roles: ['FINANCE'] }); const writes = []
  global.fetch = async (url, options) => { if (options.method === 'POST') { writes.push({ url, options }); throw new TypeError('network') } return new Response(JSON.stringify(view()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  const item = mount()
  try {
    await settle(); confirm(item, 'CREATE'); item.state.lines = inputs(); item.state.returnIds = ['bank-r1']; await item.state.execute()
    assert.equal(item.state.unconfirmed, true); assert.equal(writes.length, 1); const pending = writeRequests.pending(); assert.equal(pending.length, 1)
    await item.state.load(); confirm(item, 'CREATE'); assert.equal(item.state.pending, null)
    global.fetch = async (url, options) => { writes.push({ url, options }); return new Response(JSON.stringify(receipt('CREATE', JSON.parse(options.body))), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
    await writeRequests.recover(pending[0].id)
    assert.equal(writes[0].options.body, writes[1].options.body); assert.equal(new Headers(writes[0].options.headers).get('Idempotency-Key'), new Headers(writes[1].options.headers).get('Idempotency-Key')); assert.equal(item.state.requiresRefresh, true)
  } finally { item.close() }
})
test('结算父级在部分调整写入中禁止刷新，零核定不打开部分调整', async () => {
  let reads = 0; api.expenseSettlement = async () => { reads++; return { ...binding(), financialVersion: 4, settlement: null, canRetry: false } }
  const item = mount(Parent)
  try { await settle(); item.state.partialBusy = true; const before = reads; await item.state.load(); assert.equal(reads, before); assert.equal(item.state.blocked, true); item.props.scopeKey = ''; assert.equal(item.state.partialBusy, false) } finally { item.close() }
  const template = readFileSync(new URL('../src/components/ExpenseSettlementStatus.vue', import.meta.url), 'utf8')
  assert.match(template, /ExpensePartialAdjustment v-if="view\.settlement\?\.resourcesConsumed && view\.settlement\.funding !== 'ZERO_AMOUNT'"/)
  assert.match(template, /returnBusy \|\| partialBusy/); assert.match(template, /adjustmentBusy \|\| partialBusy/)
})

test('页面校验原因可见，远端异常正文不能混入财务界面', () => {
  let cause; try { rules.partialCreateInput(view(), inputs(), [], 'proof', '核对') } catch (error) { cause = error }
  assert.match(rules.partialError(cause), /完整且未占用的回款/); assert.doesNotMatch(rules.partialError(new Error('secret-bank-account')), /secret-bank-account/)
})
