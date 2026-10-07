import test, { beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, h, nextTick, reactive } from 'vue'
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_INVOICEDETAILFEEDBACK)
const { default: Wallet } = await import(process.env.AGENTFLOW_TEST_INVOICEWALLETFEEDBACK)
const { default: Submission } = await import(process.env.AGENTFLOW_TEST_EXPENSESUBMISSIONFEEDBACK)
const { invoiceSelectable } = await import(process.env.AGENTFLOW_TEST_EXPENSE_DRAFT)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalDocument = globalThis.Document, originalShadowRoot = globalThis.ShadowRoot
beforeEach(() => { globalThis.Document = class {}; globalThis.ShadowRoot = class {} })
afterEach(() => { Object.assign(api, originals); globalThis.Document = originalDocument; globalThis.ShadowRoot = originalShadowRoot })
const money = value => ({ value, currency: 'CNY' })
const occupation = expense => ({ status: 'OCCUPIED', expense })
const reference = { reportId: 'private-report-id', applicationId: 'private-app-id', businessNo: 'BX-2026-123', roundNo: 2, lineNo: 3 }
const item = () => ({ id: 'invoice', version: 2, original: { id: 'file', filename: '发票.pdf', size: 8, status: 'READY', format: 'PDF', createdAt: '2026-10-07T00:00:00Z' }, verification: 'VERIFIED', occupation: 'AVAILABLE', facts: null, use: null })
const report = () => ({ id: 'report', applicationId: 'app', applicationVersion: 2, financialVersion: 4, applicationStatus: 'DRAFT', roundNo: 1, editable: true, content: { legalEntityId: 'legal', lines: [{ lineNo: 7, invoiceIds: ['invoice'] }] } })
const conflict = expense => ({ lineNo: 7, invoiceId: 'invoice', occupation: occupation(expense) })
const view = () => ({ job: { id: 'check', applicationVersion: 2, financialVersion: 4, status: 'BLOCKED', attempt: 1 }, usable: false, unavailableCode: 'PRECHECK_NOT_READY',
  initiator: { appointmentId: 'appointment', legalEntityId: 'legal' }, accountingDate: '2026-10-07', preview: null, findings: [{ stage: 'RESOURCES', code: 'INVOICE_OCCUPIED', nature: 'REJECTED', lineNo: null }] })
const nodes = node => [node, ...(node.children ?? []).flatMap(nodes)]
const text = node => [node.text ?? '', ...(node.children ?? []).map(text)].join(' ')
const vnodes = node => node && typeof node === 'object' ? [node, ...vnodes(node.component?.subTree), ...(Array.isArray(node.children) ? node.children.flatMap(vnodes) : [])] : []
const tick = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
function panel(Component, extra = {}) {
  const element = tag => ({ tag, children: [], parent: null, text: '', props: {}, listeners: {}, value: '',
    addEventListener(kind, handler) { this.listeners[kind] = handler }, removeEventListener(kind) { delete this.listeners[kind] }, getRootNode() { return { activeElement: null } } })
  const remove = node => { if (node.parent) { const i = node.parent.children.indexOf(node); if (i >= 0) node.parent.children.splice(i, 1); node.parent = null } }
  const renderer = createRenderer({ createElement: element, createComment: () => element('#comment'), createText: value => ({ ...element('#text'), text: value }),
    insert(node, parent, anchor) { remove(node); node.parent = parent; const i = parent.children.indexOf(anchor); parent.children.splice(i < 0 ? parent.children.length : i, 0, node) },
    remove, parentNode: node => node?.parent ?? null, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
    patchProp(node, key, old, value) { node.props[key] = value; if (key === 'value') node.value = value },
    setText: (node, value) => { node.text = value }, setElementText: (node, value) => { node.text = value; node.children = [] } })
  const props = reactive({ scopeKey: 'alice', locked: false, invoiceId: 'invoice', refreshVersion: 0, options: null, detail: report(), timeZone: 'Asia/Shanghai', ...extra }), root = element('root')
  const app = renderer.createApp({ setup: () => () => h(Component, props) }); app.mount(root)
  return { props, root, choose() { vnodes(app._instance.subTree).find(node => node.type?.name === 'InitiatorAppointmentPicker').props['onUpdate:modelValue']('appointment') }, close() { app.unmount() } }
}
function precheck(result) {
  api.expensePrecheckOptions = async () => ({ applicationVersion: 2, financialVersion: 4, enabled: true, latestPrecheckId: 'check' })
  api.expensePrecheck = async () => structuredClone(result)
}

test('同票号另一份 AVAILABLE 原件不能被误选为未占用，本单原占用替换仍保留原提交复核', () => {
  const invoice = { ...item(), facts: { legalEntityId: 'legal', validUntil: new Date(Date.now() + 60000).toISOString() } }
  assert.equal(invoiceSelectable(invoice, 'legal', 'current-report'), true)
  for (const claim of [occupation(reference), occupation(null), { status: 'CONSUMED', expense: reference }]) {
    assert.equal(invoiceSelectable({ ...invoice, activeClaim: claim }, 'legal', 'current-report'), false)
  }
  assert.equal(invoiceSelectable({ ...invoice, activeClaim: occupation({ ...reference, reportId: 'current-report' }) }, 'legal', 'current-report'), true)
})

test('票夹列表按当前票号占用显示状态，不把另一份未绑定原件标成可用', async () => {
  api.invoices = async () => ({ items: [{ ...item(), activeClaim: occupation(reference) }], nextBeforeId: null })
  api.invoiceWalletOptions = async () => ({ enabled: true, maxFileBytes: 100, formats: ['PDF'] })
  const p = panel(Wallet)
  try { await tick(); assert.match(text(p.root), /报销占用中/); assert.doesNotMatch(text(p.root), /未占用/) }
  finally { p.close() }
})

test('票夹显示当前有权访问的占用单号，受限来源不显示内部标识，切换身份清除旧提示', async () => {
  api.invoice = async () => ({ ...item(), activeClaim: occupation(reference) })
  const p = panel(Detail)
  try {
    await tick(); assert.match(text(p.root), /BX-2026-123/); assert.match(text(p.root), /第 2 轮.*第 3 行/)
    assert.doesNotMatch(text(p.root), /private-report-id|private-app-id/)
    let resolve, signal
    api.invoice = (_, s) => { signal = s; return new Promise(done => { resolve = done }) }
    nodes(p.root).find(node => node.tag === 'button' && text(node) === '刷新票据').props.onClick(); await tick()
    api.invoice = async () => ({ ...item(), activeClaim: occupation(null) })
    p.props.scopeKey = 'bob'; await tick(); assert.equal(signal.aborted, true)
    assert.doesNotMatch(text(p.root), /BX-2026-123/); assert.match(text(p.root), /占用.*无权查看|无权查看.*占用/)
    resolve({ ...item(), activeClaim: occupation(reference) }); await tick(); assert.doesNotMatch(text(p.root), /BX-2026-123/)
  } finally { p.close() }
})

test('预检实时冲突显示原费用行和可读单号，受限来源使用通用提示且不产生写请求', async () => {
  const result = { ...view(), invoiceConflicts: [conflict(reference), { ...conflict(null), lineNo: 8, invoiceId: 'other' }] }
  precheck(result); let writes = 0; api.submitExpense = async () => { writes++ }
  const p = panel(Submission)
  try {
    await tick(); assert.match(text(p.root), /第 7 行.*BX-2026-123/); assert.match(text(p.root), /第 8 行/)
    assert.match(text(p.root), /无权查看/); assert.doesNotMatch(text(p.root), /private-report-id|private-app-id/); assert.equal(writes, 0)
    api.expensePrecheck = async () => ({ ...view(), invoiceConflicts: [] })
    nodes(p.root).find(node => node.tag === 'button' && text(node) === '刷新预检结果').props.onClick(); await tick()
    assert.doesNotMatch(text(p.root), /BX-2026-123/); assert.equal(writes, 0)
  } finally { p.close() }
})

test('正式提交竞争失败展示授权单号并关闭确认，刷新与切换身份清除旧错误且不自动重发', async () => {
  const ready = { ...view(), job: { ...view().job, status: 'READY' }, usable: true, unavailableCode: null, findings: [], validUntil: new Date(Date.now() + 60000).toISOString(),
    preview: { roundNo: 1, approvedGross: money('100'), offsetTotal: money('0'), payable: money('100'), originalLines: [], maskedAccount: '***1234' } }
  precheck(ready); const sent = []
  api.submitExpense = async (id, input) => { sent.push({ id, input }); throw { status: 409, code: 'RESOURCES_CHANGED', details: { invoiceConflicts: [conflict(reference)] } } }
  const p = panel(Submission)
  try {
    await tick(); p.choose(); const date = nodes(p.root).find(node => node.tag === 'input' && node.props.type === 'date'); date.value = '2026-10-07'; date.listeners.input({ target: date }); await tick()
    nodes(p.root).find(node => node.tag === 'button' && text(node) === '核对并提交审批').props.onClick(); await tick()
    await nodes(p.root).find(node => node.tag === 'button' && text(node) === '确认正式提交').props.onClick(); await tick()
    assert.match(text(p.root), /第 7 行.*BX-2026-123/); assert.equal(sent.length, 1)
    assert.deepEqual(sent[0], { id: 'report', input: { applicationVersion: 2, financialVersion: 4, precheckId: 'check' } })
    assert.equal(nodes(p.root).some(node => node.tag === 'button' && text(node) === '确认正式提交'), false)
    assert.equal(nodes(p.root).find(node => node.tag === 'button' && text(node) === '核对并提交审批').props.disabled, true)
    p.props.scopeKey = 'bob'; await tick(); assert.doesNotMatch(text(p.root), /BX-2026-123/); assert.equal(sent.length, 1)
  } finally { p.close() }
})
