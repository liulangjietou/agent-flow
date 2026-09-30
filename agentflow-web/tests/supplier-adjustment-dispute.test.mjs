import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_SUPPLIER_ADJUSTMENT_DISPUTE)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_SUPPLIERADJUSTMENTDISPUTESTATUS)
const { default: Parent } = await import(process.env.AGENTFLOW_TEST_SUPPLIERADJUSTMENTSTATUS)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = global.fetch
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const clone = v => JSON.parse(JSON.stringify(v)), settle = () => new Promise(resolve => setImmediate(resolve))
const money = value => ({ value, currency: 'CNY' }), date = offset => new Date(Date.now() + offset).toISOString()
const binding = () => ({ adjustmentId: 'erp-operation', paymentId: 'bank-payment', requestId: 'procurement', applicationId: 'application', roundNo: 2, amount: money('65.00'), returnedAmount: money('20.00'), totalReturned: money('30.00'), netPaid: money('35.00'), recognizesOriginalPayment: false })
function fact(revision = 3) {
  const observedAt = date(-1000)
  return { outcome: 'ADJUSTED', revision, observedAt, validUntil: new Date(Date.parse(observedAt) + 300000).toISOString(), rejection: null,
    posting: { adjustmentReference: 'erp-reference', recognitionVoucherReference: 'original-voucher', returnedAmount: money('20.00'), totalReturned: money('30.00'), netPaid: money('35.00'), payableSettledBefore: money('95.00'), payableSettledAfter: money('75.00'), entries: [{transactionReference: 'bank-return-2', amount: money('20.00'), voucherReference: 'return-voucher', entryReference: 'entry-2'}], periodReference: '2026-09', accountingDate: '2026-09-30', adjustedAt: date(-60000) } }

}
const view = () => ({ ...binding(), adjustmentVersion: 12, status: 'RECONCILING', observed: fact(1), candidate: fact(), issue: null, canResolve: true, latest: null })
const receipt = (v = view()) => ({ adjustmentId: v.adjustmentId, paymentId: v.paymentId, requestId: v.requestId, applicationId: v.applicationId, roundNo: v.roundNo, adjustmentVersion: v.adjustmentVersion + 1, status: v.candidate.outcome, resolutionId: 'decision', auditEventId: 'audit' })
let scope = 0
function mount(component = Panel) {
  const props = reactive({ ...binding(), scopeKey: 'erp-dispute-' + ++scope, locked: false }), events = [], changes = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, { ...props, onBusy: v => events.push(v), onChanged: () => changes.push(true) })
  const instance = app.mount({})
  return { props, events, changes, state: instance.$.setupState, close() { app.unmount(); Object.assign(api, originals); global.fetch = originalFetch; bindAuthenticationActor(null) } }
}

test('裁决只提交原调整版本、候选终态和人工凭据，金额与凭证由服务端取得', () => {
  const v = view()
  assert.deepEqual(rules.adjustmentDisputeInput(v, ' 核对原调整 ', ' ERP-1 '), { adjustmentVersion: 12, outcome: 'ADJUSTED', evidenceReference: 'ERP-1', comment: '核对原调整' })
  for (const evidence of ['', 'x'.repeat(129), 'ERP\nFORGED']) assert.throws(() => rules.adjustmentDisputeInput(v, '核对', evidence))
  for (const comment of ['', 'x'.repeat(2001)]) assert.throws(() => rules.adjustmentDisputeInput(v, comment, 'ERP-1'))
  const expiry = Date.parse(v.candidate.validUntil)
  assert.equal(rules.adjustmentDisputeAllowed(v, expiry - 1), true); assert.equal(rules.adjustmentDisputeAllowed(v, expiry), false)
  assert.throws(() => rules.adjustmentDisputeInput(v, '核对', 'ERP-1', expiry))
})

test('跨调整、资金范围、前后余额和逐笔入款错误拒绝读取，超大金额保持精确', () => {
  for (const mutate of [v => v.adjustmentId = 'foreign', v => v.paymentId = 'foreign', v => v.roundNo++, v => v.amount.value = '64.99', v => v.returnedAmount.value = '19.99', v => v.recognizesOriginalPayment = true, v => v.candidate.posting.returnedAmount.value = '19.99', v => v.candidate.posting.payableSettledBefore.value = '95.01', v => v.candidate.posting.payableSettledAfter.currency = 'USD', v => v.candidate.posting.recognitionVoucherReference = '', v => v.candidate.posting.accountingDate = '2026-02-30', v => v.candidate.posting.adjustedAt = date(60000), v => v.candidate.validUntil = date(600000), v => delete v.latest, v => v.candidate.posting.entries.push(clone(v.candidate.posting.entries[0])), v => v.candidate.posting.entries[0].voucherReference = 'original-voucher', v => v.candidate.posting.entries[0].amount.value = '19.99', v => delete v.candidate.posting.entries]) {
    const v = view(); mutate(v); assert.throws(() => rules.validateAdjustmentDispute(v, binding()))
  }
  const v = view(); v.amount = money('999999999999999.99'); v.returnedAmount = money('0.01'); v.totalReturned = money('0.01'); v.netPaid = money('999999999999999.98')
  for (const f of [v.observed, v.candidate]) { Object.assign(f.posting, { returnedAmount: v.returnedAmount, totalReturned: v.totalReturned, netPaid: v.netPaid, payableSettledBefore: money('0.01'), payableSettledAfter: money('0.00') }); f.posting.entries[0].amount = money('0.01') }
  assert.equal(rules.validateAdjustmentDispute(v, v).amount.value, '999999999999999.99')
  const original = view(); original.recognizesOriginalPayment = true
  for (const f of [original.observed, original.candidate]) { f.posting.payableSettledBefore = money('30.00'); f.posting.payableSettledAfter = money('75.00') }
  assert.doesNotThrow(() => rules.validateAdjustmentDispute(original, original))
})

test('拒绝、查无和处理中各自保持原事实，权限与历史决定不掩盖后续争议', () => {
  const v = view(); v.observed = null; v.candidate = { ...fact(), outcome: 'REJECTED', posting: null, rejection: 'ACCOUNTING_PERIOD_CLOSED' }
  assert.equal(rules.adjustmentDisputeInput(v, '核对拒绝', 'ERP-2').outcome, 'REJECTED')
  v.canResolve = false; v.issue = 'ADJUSTMENT_ALREADY_OBSERVED'
  v.latest = { id: 'older', adjustmentVersion: 8, outcome: 'ADJUSTED', resolvedBy: 'finance', resolvedAt: date(-30000), evidenceReference: 'ERP-0' }
  assert.equal(rules.validateAdjustmentDispute(v, binding()).status, 'RECONCILING'); assert.equal(rules.adjustmentDisputeAllowed(v), false)
  v.latest.adjustmentVersion = 13; assert.throws(() => rules.validateAdjustmentDispute(v, binding())); v.latest = null
  for (const outcome of ['PENDING', 'NOT_FOUND']) {
    v.candidate = { ...fact(), outcome, revision: outcome === 'NOT_FOUND' ? 0 : 3, posting: null }; v.issue = 'NON_TERMINAL'
    assert.doesNotThrow(() => rules.validateAdjustmentDispute(v, binding()))
    v.canResolve = true; assert.throws(() => rules.validateAdjustmentDispute(v, binding())); v.canResolve = false
  }
})

test('回执绑定调整号、相邻版本、实际候选终态和决定审计', () => {
  const v = view(); assert.doesNotThrow(() => rules.validateAdjustmentDisputeReceipt(receipt(v), v))
  for (const change of [{ adjustmentId: 'other' }, { paymentId: 'other' }, { roundNo: 3 }, { adjustmentVersion: 14 }, { status: 'REJECTED' }, { resolutionId: null }, { auditEventId: '' }]) assert.throws(() => rules.validateAdjustmentDisputeReceipt({ ...receipt(v), ...change }, v))
})

test('真实组件先确认再保存，重复点击只执行一次并重新读取', async () => {
  const v = view(), calls = []; let complete, reads = 0
  api.supplierAdjustmentDispute = async () => { reads++; return clone(v) }
  api.resolveSupplierAdjustmentDispute = (id, input) => { calls.push({ id, input }); return new Promise(resolve => complete = resolve) }
  const p = mount()
  try {
    await settle(); p.state.prepare(); assert.equal(calls.length, 0); await p.state.execute(); assert.equal(calls.length, 0)
    p.state.comment = '核对原 ERP'; p.state.evidenceReference = 'ERP-1'; const saving = p.state.execute(); await p.state.execute()
    assert.equal(calls.length, 1); assert.equal(p.events.at(-1), true); complete(receipt(v)); await saving
    assert.deepEqual(calls[0], { id: 'erp-operation', input: { adjustmentVersion: 12, outcome: 'ADJUSTED', evidenceReference: 'ERP-1', comment: '核对原 ERP' } })
    assert.equal(reads, 2); assert.equal(p.changes.length, 1); assert.equal(p.state.pending, false); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('身份变更清空旧候选和输入，迟到读写均不能恢复旧事实', async () => {
  const reads = []; api.supplierAdjustmentDispute = () => new Promise(resolve => reads.push(resolve)); const p = mount()
  try {
    p.props.scopeKey = 'new-finance'; reads[0](view()); await settle(); assert.equal(p.state.view, null)
    reads[1](view()); await settle(); let complete; api.resolveSupplierAdjustmentDispute = () => new Promise(resolve => complete = resolve)
    p.state.prepare(); p.state.comment = '旧说明'; p.state.evidenceReference = 'OLD'; const saving = p.state.execute()
    p.props.scopeKey = 'another-finance'; assert.equal(p.state.view, null); assert.equal(p.state.evidenceReference, ''); assert.equal(p.state.comment, '')
    complete(receipt()); await saving; assert.equal(p.changes.length, 0); assert.equal(p.state.notice, ''); reads[2](view()); await settle()
  } finally { p.close() }
})

test('裁决后的刷新若跨身份完成，不通知新身份重新办理', async () => {
  let read = 0, finishOld, finishNew; api.supplierAdjustmentDispute = () => ++read === 1 ? Promise.resolve(view()) : new Promise(resolve => read === 2 ? finishOld = resolve : finishNew = resolve)
  api.resolveSupplierAdjustmentDispute = async () => receipt(); const p = mount()
  try {
    await settle(); p.state.prepare(); p.state.comment = '核对'; p.state.evidenceReference = 'ERP-1'; const saving = p.state.execute(); await settle()
    p.props.scopeKey = 'new-reader'; finishNew(view()); await settle(); finishOld(view()); await saving
    assert.equal(p.changes.length, 0)
  } finally { p.close() }
})

test('超时取消读取，回执不匹配或权限撤销要求重新核对', async () => {
  api.supplierAdjustmentDispute = async () => view(); const p = mount(), timer = global.setTimeout, clear = global.clearTimeout; let complete, expire, signal
  try {
    await settle(); api.supplierAdjustmentDispute = (_id, value) => { signal = value; return new Promise(resolve => complete = resolve) }
    global.setTimeout = callback => { expire = callback; return 1 }; global.clearTimeout = () => {}
    const loading = p.state.load(); expire(); assert.equal(signal.aborted, true); complete(view()); await loading; assert.equal(p.state.view, null)
    global.setTimeout = timer; global.clearTimeout = clear; api.supplierAdjustmentDispute = async () => view(); await p.state.load()
    api.resolveSupplierAdjustmentDispute = async () => ({ ...receipt(), adjustmentId: 'foreign' }); p.state.prepare(); p.state.comment = '核对'; p.state.evidenceReference = 'ERP-1'; await p.state.execute()
    assert.equal(p.state.requiresRefresh, true); assert.equal(p.changes.length, 0)
    api.supplierAdjustmentDispute = async () => { throw { code: 'FORBIDDEN' } }; await p.state.load(); assert.equal(p.state.view, null); assert.equal(p.state.comment, ''); assert.match(p.state.error, /权限/)
  } finally { global.setTimeout = timer; global.clearTimeout = clear; p.close() }
})

test('未知裁决恢复原幂等键与正文，刷新不能重发且恢复后仍需读取', async () => {
  bindAuthenticationActor({ tenantId: 'demo', userId: 'erp-recovery-' + ++scope, roles: ['FINANCE'] })
  const calls = []; let fail = true; api.supplierAdjustmentDispute = async () => view()
  global.fetch = async (url, init) => { calls.push({ url, init }); if (fail) throw new Error('connection lost'); return new Response(JSON.stringify(receipt()), { status: 202 }) }
  const p = mount()
  try {
    await settle(); p.state.prepare(); p.state.comment = '核对'; p.state.evidenceReference = 'ERP-1'; await p.state.execute()
    assert.equal(p.state.unconfirmed, true); await p.state.load(); p.state.prepare(); await p.state.execute(); assert.equal(calls.length, 1)
    fail = false; await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls.length, 2); assert.equal(calls[0].init.body, calls[1].init.body); assert.equal(calls[0].init.headers.get('Idempotency-Key'), calls[1].init.headers.get('Idempotency-Key'))
    assert.equal(p.state.requiresRefresh, true); await p.state.load(); assert.equal(p.state.blocked, false)
  } finally { p.close() }
})

test('调整父面板在裁决保存期间禁止刷新卸载或切换记录，并汇总忙碌状态', async () => {
  let reads = 0; api.supplierAdjustments = async () => { reads++; throw new Error('synthetic unavailable') }; const p = mount(Parent)
  try {
    await settle(); p.state.toggleDispute('erp-operation'); p.state.disputeActivity(true)
    assert.equal(p.state.blocked, true); assert.equal(p.events.at(-1), true); await p.state.load(); assert.equal(reads, 1)
    p.state.toggleDispute('other'); assert.equal(p.state.disputeId, 'erp-operation'); p.state.disputeActivity(false); assert.equal(p.events.at(-1), false)
    p.state.saving = true; p.state.disputeActivity(false); assert.equal(p.events.at(-1), true)
    p.props.scopeKey = ''; assert.equal(p.state.disputeId, null)
  } finally { p.close() }
})

test('锁定、没有裁决资格与证据到期时不能打开确认', async () => {
  const v = view(); api.supplierAdjustmentDispute = async () => clone(v); const p = mount()
  try {
    await settle(); p.props.locked = true; p.state.prepare(); assert.equal(p.state.pending, false)
    p.props.locked = false; p.state.now = Date.parse(v.candidate.validUntil); p.state.prepare(); assert.equal(p.state.pending, false)
    p.state.now = Date.now(); v.canResolve = false; await p.state.load(); p.state.prepare(); assert.equal(p.state.pending, false)
  } finally { p.close() }
})

test('实际 API 编码调整号、禁止读缓存并登记幂等裁决', async () => {
  const calls = []; global.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
  bindAuthenticationActor({ tenantId: 'demo', userId: 'erp-api-' + ++scope, roles: ['FINANCE'] })
  try {
    await api.supplierAdjustmentDispute('erp/other', new AbortController().signal)
    await api.resolveSupplierAdjustmentDispute('erp/other', { adjustmentVersion: 12, outcome: 'ADJUSTED', evidenceReference: 'ERP-1', comment: '核对' })
    assert.equal(calls[0].url, '/api/v1/supplier-adjustments/erp%2Fother/dispute'); assert.equal(calls[0].init.cache, 'no-store')
    assert.equal(calls[1].url, '/api/v1/supplier-adjustments/erp%2Fother/dispute/resolutions'); assert.ok(calls[1].init.headers.get('Idempotency-Key'))
  } finally { global.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('调整父面板的裁决后刷新跨身份返回时，不向新身份发出变更通知', async () => {
  api.supplierAdjustments = async () => { throw new Error('initial unavailable') }; const p = mount(Parent)
  try {
    await settle(); const reads = []
    api.supplierAdjustments = () => new Promise((resolve, reject) => reads.push(reject))
    const refreshing = p.state.disputeChanged(); p.props.scopeKey = 'another-parent-reader'
    reads[1](new Error('new unavailable')); await settle(); reads[0](new Error('old unavailable')); await refreshing
    assert.equal(p.changes.length, 0)
  } finally { p.close() }
})
