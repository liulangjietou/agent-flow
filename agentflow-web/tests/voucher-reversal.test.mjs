import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { validateVoucherReversal, reversalQueryInput, reversalRecordInput, validateReversalReceipt } = await import(process.env.AGENTFLOW_TEST_VOUCHER_REVERSAL)
const { default: Component } = await import(process.env.AGENTFLOW_TEST_VOUCHERREVERSAL)
const { api, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, copy = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
global.localStorage = { getItem: () => 'test-token', setItem() {}, removeItem() {} }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const binding = () => ({ applicationId: 'app', operationId: 'voucher', roundNo: 1, applicationVersion: 8, businessVersion: 4, operationVersion: 6, kind: 'EXPENSE_ACCRUAL' })
const money = value => ({ value, currency: 'CNY' })
function view() {
  const at = Date.now() - 1000, time = offset => new Date(at + offset).toISOString()
  return { ...binding(), originalStatus: 'REVERSED', original: { postingReference: 'original-posting', voucherReference: 'original-voucher', periodReference: '2026-09', accountingDate: '2026-09-29', total: money('100.00'), postedAt: time(-2000) }, canQuery: true, record: null,
    latestCheck: { id: 'check', version: 3, status: 'CHECKED', requestedAt: time(-100), updatedAt: time(50), issue: null, canRecord: true, confirmationIssue: null,
      evidence: { status: 'VERIFIED', revision: 1, observedAt: time(0), validUntil: time(120000), originalRevision: 2, originalStatus: 'REVERSED', reversal: { postingReference: 'reverse-posting', voucherReference: 'reverse-voucher', periodReference: '2026-09', accountingDate: '2026-09-29', postedAt: time(-1000), lines: [
        { entryReference: 'one', originalLineNo: 1, accountCode: 'expense', side: 'CREDIT', amount: money('100.00'), sourceLineNo: 1, costCenter: 'IT', projectCode: null, advanceId: null },
        { entryReference: 'two', originalLineNo: 2, accountCode: 'payable', side: 'DEBIT', amount: money('100.00'), sourceLineNo: 0 }
      ] } } } }
}
const receipt = input => ({ applicationId: 'app', operationId: 'voucher', roundNo: 1, operationVersion: 6, checkId: input.checkId ?? 'new-check', checkVersion: input.checkId ? input.checkVersion + 1 : 1, recordId: input.checkId ? 'record' : null, auditEventId: 'audit' })
let scope = 0
function panel() {
  const props = reactive({ ...binding(), scopeKey: `finance-${++scope}`, locked: false }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onBusy: value => events.push(value) })
  const mounted = app.mount({})
  return { props, events, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originals); bindAuthenticationActor(null) } }
}
test('冲销只提交原页面版本和已核验查询，不改变原凭证版本或手填分录', () => {
  const value = view(); assert.equal(validateVoucherReversal(value, binding()), value)
  const query = reversalQueryInput(value, ' 查询原件 '), input = reversalRecordInput(value, ' ERP-PROOF ', ' 核实反向分录 ')
  assert.deepEqual(query, { roundNo: 1, applicationVersion: 8, businessVersion: 4, operationVersion: 6, comment: '查询原件' })
  assert.deepEqual(input, { ...query, comment: '核实反向分录', checkId: 'check', checkVersion: 3, evidenceReference: 'ERP-PROOF' })
  assert.doesNotThrow(() => validateReversalReceipt(receipt(query), value, query)); assert.doesNotThrow(() => validateReversalReceipt(receipt(input), value, input))
  for (const change of [{ operationVersion: 7 }, { checkId: 'foreign' }, { checkVersion: 5 }, { applicationId: 'foreign' }, { recordId: null }]) assert.throws(() => validateReversalReceipt({ ...receipt(input), ...change }, value, input))
})
test('错轮、重复或缺失分录、不平衡金额及伪造冲销身份均不能展示为可确认', () => {
  const changes = [v => v.operationId = 'other', v => v.businessVersion++, v => v.latestCheck.evidence.reversal.voucherReference = v.original.voucherReference,
    v => v.latestCheck.evidence.reversal.lines.pop(), v => v.latestCheck.evidence.reversal.lines[1].entryReference = 'one', v => v.latestCheck.evidence.reversal.lines[1].amount = money('99.00'),
    v => v.latestCheck.evidence.reversal.lines[0].amount.currency = 'USD', v => v.latestCheck.evidence.reversal.lines[1].originalLineNo = 3,
    v => v.latestCheck.evidence.originalStatus = 'POSTED', v => v.latestCheck.evidence.reversal.accountingDate = '2026-02-30', v => v.latestCheck.evidence.reversal.accountingDate = '2026-09-28',
    v => v.latestCheck.evidence.reversal.postedAt = new Date(Date.now() + 1000).toISOString(), v => v.latestCheck.status = 'RUNNING']
  for (const change of changes) { const value = copy(view()); change(value); assert.throws(() => validateVoucherReversal(value, binding())) }
})
test('依据过期、未核清、非独立确认和非法材料编号不能发起登记', () => {
  const value = view(); assert.throws(() => reversalRecordInput(value, 'proof', '说明', Date.parse(value.latestCheck.evidence.validUntil)))
  value.latestCheck.canRecord = false; assert.throws(() => reversalRecordInput(value, 'proof', '说明'))
  for (const ref of ['', 'bad\nvalue', 'a'.repeat(129)]) assert.throws(() => reversalRecordInput(view(), ref, '说明'))
  const unresolved = view(); unresolved.latestCheck.evidence.status = 'UNRESOLVED'; unresolved.latestCheck.evidence.reversal = null; unresolved.latestCheck.canRecord = false
  assert.doesNotThrow(() => validateVoucherReversal(unresolved, binding())); assert.throws(() => reversalRecordInput(unresolved, 'proof', '说明'))
})
test('读取和打开确认表单不产生写入，成功登记保持原版本并重新读取', async () => {
  let writes = 0, reads = 0, latest = view(); api.voucherReversal = async () => { reads++; return copy(latest) }
  api.recordVoucherReversal = async (app, op, input) => {
    writes++; assert.equal(app, 'app'); assert.equal(op, 'voucher'); assert.equal(input.operationVersion, 6)
    latest.record = { id: 'record', operationVersion: 6, reversal: latest.latestCheck.evidence.reversal, recordedBy: 'finance', recordedAt: new Date().toISOString(), evidenceReference: input.evidenceReference, comment: input.comment }
    latest.canQuery = false; latest.latestCheck.status = 'RECORDED'; latest.latestCheck.version++; latest.latestCheck.canRecord = false; latest.latestCheck.updatedAt = latest.record.recordedAt
    return receipt(input)
  }
  const p = panel()
  try {
    await settle(); assert.equal(writes, 0); p.state.prepare('RECORD'); assert.equal(writes, 0)
    p.state.reference = 'ERP'; p.state.comment = '确认完整反向分录'; await p.state.execute(); await settle()
    assert.equal(writes, 1); assert.equal(reads, 2); assert.equal(p.state.view.record.id, 'record'); assert.equal(p.state.error, '')
    assert.ok(p.events.includes(true)); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})
test('身份切换后清空核验材料，迟到读取不能恢复旧记录', async () => {
  let deliver, reads = 0; api.voucherReversal = () => ++reads === 1 ? new Promise(resolve => { deliver = resolve }) : Promise.resolve({ ...view(), canQuery: false, latestCheck: null })
  const p = panel()
  try {
    await settle(); p.props.scopeKey = 'new-identity'; await settle(); deliver(view()); await settle()
    assert.equal(p.state.view.latestCheck, null); assert.equal(p.state.view.canQuery, false); assert.equal(p.state.reference, '')
  } finally { p.close() }
})
test('确认时权限撤销清除分录并阻止再次提交', async () => {
  let writes = 0; api.voucherReversal = async () => view(); api.recordVoucherReversal = async () => { writes++; throw { status: 403, code: 'FORBIDDEN' } }
  const p = panel()
  try {
    await settle(); p.state.prepare('RECORD'); p.state.reference = 'ERP'; p.state.comment = '核对'; await p.state.execute()
    assert.equal(writes, 1); assert.equal(p.state.view, null); assert.equal(p.state.reference, ''); assert.equal(p.state.requiresRefresh, true)
    await p.state.execute(); assert.equal(writes, 1)
  } finally { p.close() }
})
