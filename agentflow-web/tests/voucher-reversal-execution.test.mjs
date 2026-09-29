import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { validateReversalExecution, reversalPrepareInput, reversalAuthorizeInput, reversalOperationInput, validateReversalExecutionReceipt } = await import(process.env.AGENTFLOW_TEST_VOUCHER_REVERSAL_EXECUTION)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_VOUCHERREVERSALEXECUTION)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch, copy = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const binding = () => ({ applicationId: 'app', operationId: 'voucher', roundNo: 1, applicationVersion: 8, businessVersion: 4, operationVersion: 6, kind: 'EXPENSE_ACCRUAL' })
const money = value => ({ value, currency: 'CNY' })
function view() {
  const at = Date.now() - 1000, time = offset => new Date(at + offset).toISOString()
  return { ...binding(), originalStatus: 'POSTED', originalHeld: false, original: { postingReference: 'original-posting', voucherReference: 'original-voucher', periodReference: '2026-09', accountingDate: '2026-09-29', total: money('100.00'), postedAt: time(-5000) }, canPrepare: true, operation: null,
    latestPreparation: { id: 'prepared', version: 3, status: 'READY', requestedAt: time(-500), updatedAt: time(50), issue: null, accountingDate: '2026-09-30', evidenceReference: 'ERP-PROOF', reason: '更正原件', canAuthorize: true, authorizationIssue: null,
      candidate: { createdAt: time(50), expiresAt: time(120000), originalRevision: 1, originalObservedAt: time(0), periodReference: '2026-09', periodSourceVersion: 'p1', lines: [
        { originalLineNo: 1, accountCode: 'expense', side: 'CREDIT', amount: money('100.00'), sourceLineNo: 1, costCenter: 'IT', projectCode: null, advanceId: null },
        { originalLineNo: 2, accountCode: 'payable', side: 'DEBIT', amount: money('100.00'), sourceLineNo: 0 }
      ] } } }
}
function held(status = 'UNKNOWN') {
  const value = view(), prepared = value.latestPreparation, candidate = prepared.candidate, at = Date.parse(candidate.createdAt), time = offset => new Date(at + offset).toISOString()
  value.operationVersion++; value.originalHeld = true; value.canPrepare = false; prepared.status = 'AUTHORIZED'; prepared.version++; prepared.canAuthorize = false; prepared.authorizationIssue = 'VOUCHER_REVERSAL_NOT_READY'; prepared.updatedAt = time(10)
  value.operation = { id: prepared.id, version: 3, status, attempts: 1, highestRevision: 0, failure: status === 'UNKNOWN' ? 'CONNECTION' : null, createdAt: time(10), sendExpiresAt: candidate.expiresAt, updatedAt: time(20), nextAttemptAt: status === 'UNKNOWN' ? time(5000) : null,
    authorizedBy: 'finance', accountingDate: prepared.accountingDate, evidenceReference: prepared.evidenceReference, reason: prepared.reason, lines: copy(candidate.lines), observation: status === 'NOT_FOUND' ? { status: 'NOT_FOUND', revision: 0, observedAt: time(20), acceptanceReference: null, posting: null, rejection: null } : null, conflictingObservation: null, canQuery: true, canResendOriginal: status === 'NOT_FOUND' }
  return value
}
function receipt(input) { return { applicationId: 'app', operationId: 'voucher', roundNo: 1, operationVersion: input.operationVersion + ('preparationId' in input ? 1 : 0), preparationId: input.preparationId ?? input.reversalId ?? 'new', preparationVersion: 'preparationId' in input ? input.preparationVersion + 1 : 'reversalId' in input ? 4 : 1, reversalId: input.preparationId ?? input.reversalId ?? null, reversalVersion: 'preparationId' in input ? 1 : 'reversalId' in input ? input.reversalVersion + 1 : null, auditEventId: 'audit' } }
let scope = 0
function panel(extra = {}) {
  const props = reactive({ ...binding(), scopeKey: `finance-${++scope}`, locked: false, ...extra }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(['busy', value]), onChanged: () => events.push(['changed']) })
  const mounted = app.mount({})
  return { props, events, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originals); bindAuthenticationActor(null); global.fetch = originalFetch } }
}
test('准备只传人工日期与材料，授权精确消费候选并递增原凭证版本', () => {
  const value = view(); assert.equal(validateReversalExecution(value, binding()), value)
  const input = reversalPrepareInput(value, '2026-09-30', ' ERP-PROOF ', ' 核对原件 ')
  assert.deepEqual(input, { roundNo: 1, applicationVersion: 8, businessVersion: 4, operationVersion: 6, accountingDate: '2026-09-30', evidenceReference: 'ERP-PROOF', comment: '核对原件' })
  const authorized = reversalAuthorizeInput(value, ' 审阅全部分录 ')
  assert.deepEqual(authorized, { roundNo: 1, applicationVersion: 8, businessVersion: 4, operationVersion: 6, preparationId: 'prepared', preparationVersion: 3, comment: '审阅全部分录' })
  for (const command of [input, authorized]) assert.doesNotThrow(() => validateReversalExecutionReceipt(receipt(command), value, command))
  for (const changes of [{ operationVersion: 6 }, { preparationVersion: 3 }, { preparationId: 'other' }, { reversalId: null }, { reversalVersion: 2 }, { applicationId: 'other' }]) assert.throws(() => validateReversalExecutionReceipt({ ...receipt(authorized), ...changes }, value, authorized))
})
test('错轮、不完整或不平衡分录、非法日期与错误能力不能进入确认', () => {
  const changes = [v => v.operationId = 'other', v => v.applicationVersion++, v => v.originalHeld = true,
    v => v.latestPreparation.candidate.lines.pop(), v => v.latestPreparation.candidate.lines[1].amount = money('99.99'), v => v.latestPreparation.candidate.lines[1].amount.currency = 'USD',
    v => v.latestPreparation.candidate.lines[1].originalLineNo = 1, v => v.latestPreparation.accountingDate = '2026-02-30', v => v.latestPreparation.accountingDate = '2026-09-28',
    v => v.latestPreparation.candidate.expiresAt = v.latestPreparation.candidate.createdAt, v => v.latestPreparation.status = 'RUNNING', v => v.latestPreparation.authorizationIssue = 'SOURCE_CHANGED']
  for (const change of changes) { const value = copy(view()); change(value); assert.throws(() => validateReversalExecution(value, binding())) }
  for (const date of ['', '2026-02-30', '2026-09-28']) assert.throws(() => reversalPrepareInput(view(), date, 'ERP', '核对'))
  for (const ref of ['', 'bad\nvalue', 'a'.repeat(129)]) assert.throws(() => reversalPrepareInput(view(), '2026-09-30', ref, '核对'))
})
test('授权过期不能发送，查询跨过期限仍可继续，查无重发不能延长原期限', () => {
  const value = view(); assert.throws(() => reversalAuthorizeInput(value, '核对', Date.parse(value.latestPreparation.candidate.expiresAt)))
  const pending = held(), missing = held('NOT_FOUND'); assert.doesNotThrow(() => validateReversalExecution(pending, { ...binding(), operationVersion: 7 }))
  const later = Date.parse(pending.operation.sendExpiresAt) + 1
  assert.equal(reversalOperationInput(pending, 'QUERY', '读取实际结果', later).reversalId, pending.operation.id)
  assert.throws(() => reversalOperationInput(pending, 'RESEND_ORIGINAL', '不能自动重发'))
  const resend = reversalOperationInput(missing, 'RESEND_ORIGINAL', '明确重发原编号'); assert.doesNotThrow(() => validateReversalExecutionReceipt(receipt(resend), missing, resend))
  assert.throws(() => reversalOperationInput(missing, 'RESEND_ORIGINAL', '依据过期', Date.parse(missing.operation.sendExpiresAt)))
  missing.operation.highestRevision = 1; assert.throws(() => validateReversalExecution(missing, missing))
})
test('ERP 已过账必须返回与授权一致的独立凭证和全部分录', () => {
  const value = held(); const op = value.operation
  op.status = 'POSTED'; op.highestRevision = 1; op.failure = null; op.nextAttemptAt = null
  op.observation = { status: 'POSTED', revision: 1, observedAt: op.updatedAt, acceptanceReference: 'ERP-ACCEPTED', rejection: null,
    posting: { postingReference: 'reverse-posting', voucherReference: 'reverse-voucher', periodReference: '2026-09', accountingDate: op.accountingDate, postedAt: op.updatedAt, lines: op.lines.map((line, index) => ({ ...line, entryReference: 'line-' + index })) } }
  assert.doesNotThrow(() => validateReversalExecution(value, value))
  for (const change of [v => v.operation.observation.posting.accountingDate = '2026-09-29', v => v.operation.observation.posting.lines[0].accountCode = 'different', v => v.operation.observation.posting.lines[0].costCenter = 'other', v => v.operation.observation.posting.voucherReference = v.original.voucherReference]) {
    const invalid = copy(value); change(invalid); assert.throws(() => validateReversalExecution(invalid, invalid))
  }
})
test('加载、展开准备和展开授权都不写入，确认后通知父凭证刷新新版本', async () => {
  let writes = 0, reads = 0; api.voucherReversalExecution = async () => { reads++; return view() }
  api.authorizeVoucherReversal = async (app, original, input) => { writes++; assert.equal(original, 'voucher'); return receipt(input) }
  const p = panel()
  try {
    await settle(); p.state.prepare('PREPARE'); assert.equal(writes, 0); p.state.prepare('AUTHORIZE'); p.state.comment = '审阅并授权'; await p.state.execute(); assert.equal(writes, 0)
    p.state.acknowledged = true; await p.state.execute(); assert.equal(writes, 1); assert.equal(reads, 1)
    assert.equal(p.state.view, null); assert.equal(p.state.requiresRefresh, true); assert.ok(p.events.some(event => event[0] === 'changed')); assert.deepEqual(p.events.at(-1), ['busy', false])
  } finally { p.close() }
})
test('准备明确提交后只重读准备，不自动授权；重复点击共用进行中的动作', async () => {
  let finish, writes = 0, authorizations = 0, input; api.voucherReversalExecution = async () => view()
  api.prepareVoucherReversal = async (_app, _original, value) => { writes++; input = value; return new Promise(resolve => finish = resolve) }; api.authorizeVoucherReversal = async () => authorizations++
  const p = panel()
  try {
    await settle(); p.state.prepare('PREPARE'); p.state.accountingDate = '2026-09-30'; p.state.reference = 'ERP'; p.state.comment = '准备核对'
    const first = p.state.execute(); await settle(); await p.state.execute(); assert.equal(writes, 1); finish(receipt(input)); await first
    assert.equal(authorizations, 0); assert.equal(p.state.pending, null); assert.match(p.state.notice, /再明确授权/)
  } finally { p.close() }
})
test('身份切换清除原材料，迟到读取和写入都不能恢复原候选或触发父级刷新', async () => {
  let deliver, reads = 0; api.voucherReversalExecution = () => ++reads === 1 ? new Promise(resolve => deliver = resolve) : Promise.resolve({ ...view(), canPrepare: false, latestPreparation: null })
  const p = panel()
  try {
    await settle(); p.props.scopeKey = 'other'; await settle(); deliver(view()); await settle(); assert.equal(p.state.view.latestPreparation, null)
    api.voucherReversalExecution = async () => view(); p.props.scopeKey = 'finance-again'; await settle()
    let finish, input; api.authorizeVoucherReversal = async (_app, _original, value) => { input = value; return new Promise(resolve => finish = resolve) }
    p.state.prepare('AUTHORIZE'); p.state.comment = '确认'; p.state.acknowledged = true; const pending = p.state.execute(); await settle()
    api.voucherReversalExecution = async () => ({ ...view(), canPrepare: false, latestPreparation: null }); p.props.scopeKey = 'other-again'; await settle(); finish(receipt(input)); await pending
    assert.equal(p.state.view.latestPreparation, null); assert.equal(p.state.comment, ''); assert.equal(p.state.acknowledged, false); assert.equal(p.events.some(event => event[0] === 'changed'), false)
  } finally { p.close() }
})
test('确认时权限撤销立即清空分录和输入，禁止继续办理', async () => {
  let writes = 0; api.voucherReversalExecution = async () => view(); api.authorizeVoucherReversal = async () => { writes++; throw { status: 403, code: 'FORBIDDEN' } }
  const p = panel()
  try {
    await settle(); p.state.prepare('AUTHORIZE'); p.state.comment = '确认'; p.state.acknowledged = true; await p.state.execute()
    assert.equal(writes, 1); assert.equal(p.state.view, null); assert.equal(p.state.comment, ''); assert.equal(p.state.requiresRefresh, true); await p.state.execute(); assert.equal(writes, 1)
  } finally { p.close() }
})
test('授权请求结果未知时保留原路径和字节，恢复后必须刷新父凭证才能继续', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'reversal-authorizer-' + ++scope, roles: ['FINANCE'] }); api.voucherReversalExecution = async () => view()
  const requests = []; let failed = true
  global.fetch = async (url, init) => { requests.push({ url, init }); if (failed) throw new Error('connection lost'); return new Response(JSON.stringify(receipt(JSON.parse(init.body))), { status: 202, headers: { 'Content-Type': 'application/json' } }) }
  const p = panel()
  try {
    await settle(); p.state.prepare('AUTHORIZE'); p.state.comment = '确认原分录'; p.state.acknowledged = true; await p.state.execute()
    assert.equal(p.state.unconfirmed, true); const entry = writeRequests.pending()[0]; assert.equal(entry.path, '/applications/app/vouchers/voucher/reversal-execution/authorizations')
    failed = false; await writeRequests.recover(entry.id); assert.equal(requests[0].init.body, requests[1].init.body); assert.equal(requests[0].init.headers.get('Idempotency-Key'), requests[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.unconfirmed, false); assert.equal(p.state.requiresRefresh, true); await p.state.execute(); assert.equal(requests.length, 2)
  } finally { p.close() }
})
