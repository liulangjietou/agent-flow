import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { validateVoucherView, voucherActionInput, validateVoucherReceipt, voucherDisputeInput, validateVoucherDisputeReceipt } = await import(process.env.AGENTFLOW_TEST_VOUCHERS)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_VOUCHERSTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const copy = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const binding = () => ({ applicationId: 'app', businessId: 'report', businessType: 'EXPENSE', roundNo: 2, applicationVersion: 10, businessVersion: 6 })
const view = () => ({ ...binding(), kind: 'EXPENSE_ACCRUAL', preparation: { id: 'operation', status: 'READY', attempt: 1, createdAt: new Date().toISOString(), completedAt: new Date().toISOString(), issue: null },
  operation: { id: 'operation', version: 3, kind: 'EXPENSE_ACCRUAL', status: 'NOT_FOUND', attempts: 1, accountingDate: '2026-09-28', updatedAt: new Date().toISOString(), sendExpiresAt: new Date(Date.now() + 60000).toISOString(), observedStatus: 'NOT_FOUND', voucherReference: null, postedAt: null, disputed: false, issue: null }, actions: { prepare: false, query: true, resendOriginal: true } })
const receipt = input => ({ applicationId: 'app', businessId: 'report', roundNo: 2, kind: 'EXPENSE_ACCRUAL', action: input.action, preparationId: input.action === 'PREPARE' ? 'preparation' : null, operationId: input.operationId ?? null, operationVersion: input.operationVersion ? input.operationVersion + 1 : null, auditEventId: 'audit' })
let scope = 0
const disputedView = () => {
  const value = view(), observedAt = Date.now() - 1000
  value.operation.status = 'RECONCILING'; value.operation.observedStatus = 'POSTED'; value.operation.disputed = true; value.operation.voucherReference = 'ERP-ORIGINAL'; value.operation.postedAt = new Date(observedAt - 1000).toISOString()
  value.actions.resendOriginal = false
  value.dispute = { candidate: { outcome: 'POSTED', revision: 3, observedAt: new Date(observedAt).toISOString(), validUntil: new Date(observedAt + 300000).toISOString(), postingReference: 'ERP-POSTING', voucherReference: 'ERP-ORIGINAL', postedAt: value.operation.postedAt, failure: null }, issue: null, canResolve: true, latest: null }
  return value
}
const decisionReceipt = input => ({ applicationId: 'app', operationId: 'operation', roundNo: 2, kind: 'EXPENSE_ACCRUAL', resolutionId: 'decision', operationVersion: input.operationVersion + 1, outcome: input.outcome, auditEventId: 'audit' })

test('凭证科目摘要区分平台发布和 ERP 管理，原命令必须带实际 ERP 来源版本', () => {
  const value = view(), mappingId = '00000000-0000-4000-8000-000000000001'
  value.mapping = { source: 'PLATFORM_PUBLISHED', legalEntityId: mappingId, currency: 'CNY', mappingId, mappingVersion: 2, categoryRevision: 1, activeRevision: 3, definitionDigest: 'a'.repeat(64), erpSourceVersion: 'erp-v1' }
  assert.equal(validateVoucherView(value, binding()).mapping.mappingVersion, 2)
  for (const change of [{ mappingVersion: 0 }, { categoryRevision: -1 }, { activeRevision: null }, { definitionDigest: 'short' }, { mappingId: 'invalid' },
    { source: 'CURRENT_CONFIGURATION' }, { erpSourceVersion: null }, { legalEntityId: 'foreign' }, { currency: 'cny' }]) {
    assert.throws(() => validateVoucherView({ ...value, mapping: { ...value.mapping, ...change } }, binding()))
  }
  const unmanaged = { source: 'ERP_MANAGED', legalEntityId: mappingId, currency: 'CNY', mappingId: null, mappingVersion: null, categoryRevision: null, activeRevision: null, definitionDigest: null, erpSourceVersion: 'legacy-v1' }
  assert.equal(validateVoucherView({ ...value, mapping: unmanaged }, binding()).mapping.erpSourceVersion, 'legacy-v1')
  assert.throws(() => validateVoucherView({ ...value, mapping: { ...unmanaged, mappingVersion: 1 } }, binding()))
})

test('准备中的科目选择尚无 ERP 来源，旧接口省略摘要仍可读取', () => {
  const value = view(); assert.doesNotThrow(() => validateVoucherView(value, binding()))
  value.operation = null; value.preparation.status = 'RUNNING'; value.mapping = { source: 'ERP_MANAGED', legalEntityId: '00000000-0000-4000-8000-000000000001', currency: 'CNY', mappingId: null, mappingVersion: null, categoryRevision: null, activeRevision: null, definitionDigest: null, erpSourceVersion: null }
  assert.equal(validateVoucherView(value, binding()).mapping.source, 'ERP_MANAGED')
  assert.throws(() => validateVoucherView({ ...value, mapping: { ...value.mapping, erpSourceVersion: 'unconfirmed' } }, binding()))
  value.preparation.status = 'QUEUED'; assert.throws(() => validateVoucherView(value, binding()))
  value.mapping = null; assert.doesNotThrow(() => validateVoucherView(value, binding()))
})
function panel(overrides = {}) {
  const props = reactive({ ...binding(), scopeKey: `finance-${++scope}`, locked: false, ...overrides }), events = [], changes = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value), onChanged: () => changes.push(true) })
  const mounted = app.mount({})
  return { props, events, changes, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('两个冲销子面板分别锁定父级，切换身份不被旧子操作阻止刷新', async () => {
  let reads = 0; api.vouchers = async () => { reads++; return disputedView() }
  const p = panel()
  try {
    await settle(); p.state.reversalBusyChanged(true); p.state.executionBusyChanged(true); p.state.reversalBusyChanged(false)
    assert.equal(p.state.blocked, true); assert.equal(p.events.at(-1), true)
    p.props.scopeKey = 'another-current-finance'; await settle()
    assert.equal(reads, 2); assert.equal(p.state.view.applicationId, 'app'); assert.equal(p.state.executionBusy, false); assert.equal(p.state.reversalBusy, false)
  } finally { p.close() }
})

test('凭证裁决只传展示版本与终态，过期、非终态和不同意图的回执均拒绝', () => {
  const value = disputedView(), input = voucherDisputeInput(value, ' ERP-REVIEW ', ' 核对原凭证 ')
  assert.deepEqual(input, { roundNo: 2, applicationVersion: 10, businessVersion: 6, operationVersion: 3, outcome: 'POSTED', evidenceReference: 'ERP-REVIEW', comment: '核对原凭证' })
  assert.doesNotThrow(() => validateVoucherDisputeReceipt(decisionReceipt(input), value, input))
  for (const change of [{ applicationId: 'foreign' }, { operationId: 'foreign' }, { roundNo: 1 }, { kind: 'PAYMENT' }, { outcome: 'REVERSED' }, { operationVersion: 5 }, { resolutionId: '' }, { auditEventId: '' }]) {
    assert.throws(() => validateVoucherDisputeReceipt({ ...decisionReceipt(input), ...change }, value, input))
  }
  for (const mutate of [v => v.dispute.canResolve = false, v => v.dispute.issue = 'DIFFERENT_POSTING', v => v.dispute.candidate.outcome = 'PENDING', v => v.dispute.candidate.validUntil = new Date(0).toISOString(), v => v.operation.disputed = false, v => v.dispute.candidate.voucherReference = '', v => v.dispute.candidate.validUntil = 'bad']) {
    const invalid = copy(value); mutate(invalid); assert.throws(() => voucherDisputeInput(invalid, 'ERP-REVIEW', '核对'))
  }
  assert.throws(() => voucherDisputeInput(value, 'ERP\nFAKE', '核对')); assert.throws(() => voucherDisputeInput(value, '   ', '核对'))
  assert.throws(() => voucherDisputeInput(value, 'ERP', '   ')); assert.throws(() => voucherDisputeInput(value, 'ERP', '核对', Date.parse(value.dispute.candidate.validUntil)))
})

test('实际凭证裁决面板双击只提交一次，完成后重新读取实际状态', async () => {
  api.vouchers = async () => disputedView(); let complete, input, writes = 0
  api.resolveVoucherDispute = async (app, id, body) => { assert.equal(app, 'app'); assert.equal(id, 'operation'); input = body; writes++; return new Promise(resolve => complete = resolve) }
  const p = panel()
  try {
    await settle(); p.state.prepare('RESOLVE_DISPUTE'); assert.equal(writes, 0)
    p.state.comment = '原凭证仍有效'; p.state.evidenceReference = 'ERP-CHECK'
    const first = p.state.execute(); await settle(); await p.state.execute(); assert.equal(writes, 1)
    complete(decisionReceipt(input)); await first; assert.match(p.state.notice, /裁决已记录/); assert.equal(p.state.pending, null)
  } finally { p.close() }
})

test('确认凭证裁决时权限已撤销则立即清空原敏感状态与输入', async () => {
  api.vouchers = async () => disputedView(); api.resolveVoucherDispute = async () => { throw Object.assign(new Error('forbidden'), { status: 403 }) }
  const p = panel()
  try {
    await settle(); p.state.prepare('RESOLVE_DISPUTE'); p.state.comment = '核对'; p.state.evidenceReference = 'ERP-CHECK'; await p.state.execute()
    assert.equal(p.state.view, null); assert.equal(p.state.pending, null); assert.equal(p.state.comment, ''); assert.equal(p.state.evidenceReference, ''); assert.match(p.state.error, /无权/)
  } finally { p.close() }
})

test('凭证裁决未知写入保留原路径和幂等字节，恢复后必须重新读取', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'voucher-decision-' + ++scope, roles: ['FINANCE'] }); api.vouchers = async () => disputedView()
  const requests = []; let fail = true
  global.fetch = async (url, init) => { requests.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(decisionReceipt(JSON.parse(init.body))), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const p = panel()
  try {
    await settle(); p.state.prepare('RESOLVE_DISPUTE'); p.state.comment = '核对'; p.state.evidenceReference = 'ERP-CHECK'; await p.state.execute()
    assert.equal(p.state.unconfirmed, true); const entry = writeRequests.pending()[0]; assert.equal(entry.path, '/applications/app/vouchers/operation/dispute-resolutions')
    fail = false; await writeRequests.recover(entry.id)
    assert.equal(requests[0].init.body, requests[1].init.body); assert.equal(requests[0].init.headers.get('Idempotency-Key'), requests[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); await p.state.load(); assert.equal(p.state.unconfirmed, false)
  } finally { p.close() }
})

test('裁决响应迟到及身份切换不能恢复上一身份的凭证或材料', async () => {
  api.vouchers = async () => disputedView(); let complete, input
  api.resolveVoucherDispute = async (app, id, body) => { input = body; return new Promise(resolve => complete = resolve) }
  const p = panel()
  try {
    await settle(); p.state.prepare('RESOLVE_DISPUTE'); p.state.comment = '核对'; p.state.evidenceReference = 'ERP-CHECK'; const executing = p.state.execute(); await settle()
    p.props.scopeKey = ''; complete(decisionReceipt(input)); await executing; await settle()
    assert.equal(p.state.view, null); assert.equal(p.state.comment, ''); assert.equal(p.state.evidenceReference, ''); assert.equal(p.state.notice, '')
  } finally { p.close() }
})

test('凭证响应必须绑定同一业务、轮次、双版本和准备编号，不能显示不完整的成功事实', () => {
  const value = view(); assert.equal(validateVoucherView(value, binding()), value)
  for (const key of ['applicationId', 'businessId', 'businessType', 'roundNo', 'applicationVersion', 'businessVersion']) {
    assert.throws(() => validateVoucherView({ ...value, [key]: key.endsWith('Version') || key === 'roundNo' ? 999 : 'foreign' }, binding()))
  }
  for (const mutate of [v => v.preparation.id = 'foreign', v => v.operation.kind = 'PAYMENT', v => v.operation.status = 'PAID', v => v.actions.query = 'true', v => v.operation.version = 0, v => v.operation.sendExpiresAt = 'invalid', v => v.operation.status = 'POSTED']) {
    const invalid = copy(value); mutate(invalid); assert.throws(() => validateVoucherView(invalid, binding()))
  }
})

test('付款凭证即使尚无操作记录也必须携带付款类型，不能借用挂账响应', () => {
  const expected = { ...binding(), kind: 'PAYMENT' }, value = { ...view(), kind: 'PAYMENT', preparation: null, operation: null }
  assert.equal(validateVoucherView(value, expected), value)
  assert.throws(() => validateVoucherView(value, binding()))
  assert.throws(() => validateVoucherView({ ...value, kind: 'EXPENSE_ACCRUAL' }, expected))
  value.operation = view().operation; assert.throws(() => validateVoucherView(value, expected))
  value.operation.kind = 'PAYMENT'; assert.equal(validateVoucherView(value, expected), value)
  const input = voucherActionInput(value, 'QUERY', '核对付款入账')
  assert.throws(() => validateVoucherReceipt(receipt(input), value, input))
  assert.doesNotThrow(() => validateVoucherReceipt({ ...receipt(input), kind: 'PAYMENT' }, value, input))
})

test('真实付款凭证面板使用独立读写入口且只发送原操作身份', async () => {
  const value = view(); value.kind = 'PAYMENT'; value.operation.kind = 'PAYMENT'
  let reads = 0, writes = 0
  api.vouchers = async () => { throw new Error('不应读取挂账入口') }
  api.voucherAction = async () => { throw new Error('不应写入挂账入口') }
  api.paymentVouchers = async () => { reads++; return value }
  api.paymentVoucherAction = async (id, input) => { writes++; assert.equal(id, 'app'); assert.equal(input.operationId, 'operation'); return { ...receipt(input), kind: 'PAYMENT' } }
  const p = panel({ payment: true })
  try {
    await settle(); assert.equal(p.state.view.kind, 'PAYMENT'); assert.equal(p.state.title, '付款凭证')
    p.state.prepare('QUERY'); p.state.comment = '核对银行回单对应凭证'; await p.state.execute()
    assert.equal(writes, 1); assert.equal(reads, 2); assert.match(p.state.notice, /已登记/)
  } finally { p.close() }
})

test('切换挂账和付款凭证时取消旧读取，迟到的挂账结果不能覆盖付款状态', async () => {
  let complete, aborted
  api.vouchers = (id, round, signal) => { aborted = signal; return new Promise(resolve => complete = resolve) }
  const value = view(); value.kind = 'PAYMENT'; value.operation.kind = 'PAYMENT'; api.paymentVouchers = async () => value
  const p = panel()
  try {
    p.props.payment = true; await settle(); assert.equal(aborted.aborted, true); assert.equal(p.state.view.kind, 'PAYMENT')
    complete(view()); await settle(); assert.equal(p.state.view.kind, 'PAYMENT'); assert.equal(p.state.pending, null)
  } finally { p.close() }
})

test('付款凭证未知写入保留独立路径与原请求，恢复回执也校验凭证类型', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'payment-voucher-unknown-' + ++scope, roles: ['FINANCE'] })
  const value = view(); value.kind = 'PAYMENT'; value.operation.kind = 'PAYMENT'; api.paymentVouchers = async () => value
  const requests = []; let fail = true
  global.fetch = async (url, init) => {
    requests.push({ url, init }); if (fail) throw new Error('connection lost')
    return new Response(JSON.stringify({ ...receipt(JSON.parse(init.body)), kind: 'PAYMENT' }), { status: 202, headers: { 'Content-Type': 'application/json' } })
  }
  const p = panel({ payment: true })
  try {
    await settle(); p.state.prepare('QUERY'); p.state.comment = '恢复原会计查询'; await p.state.execute()
    assert.equal(p.state.unconfirmed, true); const entry = writeRequests.pending()[0]
    assert.equal(entry.path, '/applications/app/vouchers/payment/actions'); fail = false; await writeRequests.recover(entry.id)
    assert.equal(requests[0].init.body, requests[1].init.body)
    assert.equal(requests[0].init.headers.get('Idempotency-Key'), requests[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); await p.state.load(); assert.equal(p.state.unconfirmed, false)
  } finally { p.close() }
})

test('重发只携带原编号与显示版本，确认时再次校验期限和权威查无', () => {
  const value = view(); value.account = 'forged'; value.amount = '999.99'
  const result = voucherActionInput(value, 'RESEND_ORIGINAL', ' 核对后原编号重发 ')
  assert.deepEqual(result, { action: 'RESEND_ORIGINAL', roundNo: 2, applicationVersion: 10, businessVersion: 6, operationId: 'operation', operationVersion: 3, comment: '核对后原编号重发' })
  assert.throws(() => voucherActionInput(value, 'RESEND_ORIGINAL', '原因', Date.parse(value.operation.sendExpiresAt)))
  for (const mutate of [v => v.operation.status = 'UNKNOWN', v => v.operation.disputed = true, v => v.actions.resendOriginal = false]) {
    const invalid = copy(value); mutate(invalid); assert.throws(() => voucherActionInput(invalid, 'RESEND_ORIGINAL', '原因'))
  }
  assert.throws(() => voucherActionInput(value, 'QUERY', '   ')); assert.throws(() => voucherActionInput(value, 'QUERY', '字'.repeat(2001)))
})

test('准备已受理、原凭证存在及零金额不能另发准备命令', () => {
  const value = view(); value.operation = null; value.preparation.status = 'UNAVAILABLE'; value.actions.prepare = true
  const input = voucherActionInput(value, 'PREPARE', '配置已恢复')
  assert.deepEqual(Object.keys(input).sort(), ['action', 'roundNo', 'applicationVersion', 'businessVersion', 'comment'].sort())
  for (const status of ['QUEUED', 'RUNNING', 'READY', 'NOT_REQUIRED']) { value.preparation.status = status; assert.throws(() => voucherActionInput(value, 'PREPARE', '原因')) }
  value.preparation.status = 'UNAVAILABLE'; value.operation = view().operation; assert.throws(() => voucherActionInput(value, 'PREPARE', '原因'))
})

test('受理回执需匹配原动作与版本，不能接受另一份业务的结果', () => {
  const value = view(), input = voucherActionInput(value, 'QUERY', '核对')
  assert.doesNotThrow(() => validateVoucherReceipt(receipt(input), value, input))
  for (const change of [{ applicationId: 'other' }, { businessId: 'other' }, { roundNo: 1 }, { action: 'PREPARE' }, { operationId: 'other' }, { operationVersion: 3 }, { preparationId: 'unexpected' }, { auditEventId: '' }]) assert.throws(() => validateVoucherReceipt({ ...receipt(input), ...change }, value, input))
})

test('实际凭证面板只读加载，选择和取消操作不产生写请求，双击确认共用一个意图', async () => {
  api.vouchers = async () => view(); const writes = []; let complete
  api.voucherAction = (id, input) => { writes.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = panel()
  try {
    await settle(); assert.equal(writes.length, 0); p.state.prepare('QUERY'); assert.equal(writes.length, 0)
    p.state.pending = null; p.state.prepare('RESEND_ORIGINAL'); p.state.comment = '核对查无原命令'; const first = p.state.execute(); await p.state.execute()
    assert.equal(writes.length, 1); assert.equal(p.state.saving, true); assert.equal(p.events.at(-1), true)
    complete(receipt(writes[0].input)); await first; assert.equal(p.state.saving, false); assert.equal(p.state.pending, null); assert.match(p.state.notice, /已登记/)
  } finally { p.close() }
})

test('真实面板在无操作权限或发送期限已过时阻止提交', async () => {
  const value = view(); value.actions = { prepare: false, query: false, resendOriginal: false }; api.vouchers = async () => value
  let writes = 0; api.voucherAction = async () => { writes++ }
  const p = panel()
  try {
    await settle(); p.state.prepare('QUERY'); assert.equal(p.state.pending, null)
    value.actions.resendOriginal = true; await p.state.load(); p.state.prepare('RESEND_ORIGINAL'); p.state.comment = '原因'; value.operation.sendExpiresAt = '2000-01-01T00:00:00Z'
    await p.state.execute(); assert.equal(writes, 0); assert.match(p.state.error, /期限/)
  } finally { p.close() }
})

test('切换身份后清空旧状态与确认说明，迟到查询不能显示旧财务内容', async () => {
  const calls = []; api.vouchers = (id, roundNo, signal) => new Promise(resolve => calls.push({ resolve, signal }))
  const p = panel()
  try {
    assert.equal(calls.length, 1); p.props.scopeKey = 'another'; assert.equal(p.state.view, null); assert.equal(calls[0].signal.aborted, true)
    calls[0].resolve(view()); await settle(); assert.equal(p.state.view, null)
    calls[1].resolve(view()); await settle(); p.state.prepare('QUERY'); p.state.comment = '原身份说明'
    p.props.roundNo = 3; assert.equal(p.state.view, null); assert.equal(p.state.comment, ''); assert.equal(p.state.pending, null)
    calls[2].resolve(view()); await settle(); assert.equal(p.state.view, null); assert.match(p.state.error, /轮次或版本/)
  } finally { p.close() }
})

test('读取超时立即清空状态，忽略超时后的成功响应', async () => {
  const timeout = global.setTimeout, clear = global.clearTimeout; let expire, complete, signal
  global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
  api.vouchers = (id, roundNo, input) => { signal = input; return new Promise(resolve => complete = resolve) }
  const p = panel()
  try {
    expire(); assert.equal(signal.aborted, true); assert.equal(p.state.loading, false); assert.match(p.state.error, /超时/)
    complete(view()); await settle(); assert.equal(p.state.view, null); assert.match(p.state.error, /超时/)
  } finally { p.close(); global.setTimeout = timeout; global.clearTimeout = clear }
})

test('写入响应来自旧身份时不应用到新身份面板，也不自动刷新或重发', async () => {
  let complete, reads = 0; api.vouchers = async () => { reads++; return view() }; api.voucherAction = () => new Promise(resolve => complete = resolve)
  const p = panel()
  try {
    await settle(); p.state.prepare('QUERY'); p.state.comment = '查询'; const task = p.state.execute()
    p.props.scopeKey = 'new-identity'; await settle(); const currentReads = reads
    complete(receipt(voucherActionInput(view(), 'QUERY', '查询'))); await task
    assert.equal(reads, currentReads); assert.equal(p.state.notice, ''); assert.equal(p.state.saving, false)
  } finally { p.close() }
})

test('未确认请求保留原键及正文，刷新与关闭面板不能自动重发，恢复后重新核对状态', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'voucher-unknown-' + ++scope, roles: ['FINANCE'] })
  const calls = []; let fail = true
  api.vouchers = async () => view()
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(receipt(JSON.parse(init.body))), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const p = panel()
  try {
    await settle(); p.state.prepare('QUERY'); p.state.comment = '查询原凭证'; await p.state.execute()
    assert.equal(calls.length, 1); assert.equal(p.state.unconfirmed, true); assert.equal(p.state.blocked, true)
    await p.state.load(); p.state.prepare('RESEND_ORIGINAL'); await p.state.execute(); assert.equal(calls.length, 1)
    const entry = writeRequests.pending()[0]; fail = false; await writeRequests.recover(entry.id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body)
    const key = calls[0].init.headers.get('Idempotency-Key'); assert.ok(key); assert.equal(key, calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.blocked, true, '恢复完成也必须先读取最新凭证版本'); assert.equal(p.state.requiresRefresh, true)
    await p.state.load(); assert.equal(p.state.unconfirmed, false); assert.equal(writeRequests.pending().length, 0)
  } finally { p.close() }
})

test('实际面板收到不匹配的受理回执后保留核对提示，不显示登记成功', async () => {
  api.vouchers = async () => view(); api.voucherAction = async (id, input) => ({ ...receipt(input), roundNo: 99 })
  const p = panel()
  try {
    await settle(); p.state.prepare('QUERY'); p.state.comment = '核对原结果'; await p.state.execute()
    assert.equal(p.state.notice, ''); assert.equal(p.state.requiresRefresh, true); assert.match(p.state.error, /回执/)
  } finally { p.close() }
})

test('接口读取显式带轮次且禁用缓存，路径正确转义', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify(view()), { status: 200 }) }
  try {
    await api.vouchers('app/other', 2, new AbortController().signal)
    assert.match(calls[0].url, /applications\/app%2Fother\/vouchers\?roundNo=2$/); assert.equal(calls[0].init.cache, 'no-store')
  } finally { global.fetch = originalFetch }
})

test('冲销或原凭证修订变化后通知资金区域重读，同版本刷新和旧身份结果不触发联动', async () => {
  let current = view(); api.vouchers = async () => copy(current)
  const p = panel()
  try {
    await settle(); assert.equal(p.changes.length, 0); await p.state.load(); assert.equal(p.changes.length, 0)
    current.operation.version++; await p.state.load(); assert.equal(p.changes.length, 1)
    let finish; api.vouchers = async () => new Promise(resolve => finish = resolve)
    const loading = p.state.load(); await settle(); api.vouchers = async () => copy(current); p.props.scopeKey = 'new-finance'; await settle()
    const old = copy(current); old.operation.version++; finish(old); await loading
    assert.equal(p.changes.length, 1)
  } finally { p.close() }
})
