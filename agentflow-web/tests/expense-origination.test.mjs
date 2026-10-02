import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { emptyExpense, expenseContent, expenseDefinition, newExpenseLine, usablePrecheck, invoiceSelectable, fillExpenseLineFromInvoice, ExpenseDrafts, expenseDrafts } = await import(process.env.AGENTFLOW_TEST_EXPENSE_DRAFT)
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_EXPENSEEDITOR)
const { default: Submission } = await import(process.env.AGENTFLOW_TEST_EXPENSESUBMISSION)
const { default: Funding } = await import(process.env.AGENTFLOW_TEST_EXPENSEFUNDINGPICKER)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const copy = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const money = (value, currency = 'CNY') => ({ value, currency })
const catalog = () => ({ employeeId: 'alice', sourceVersion: 'v1', validUntil: new Date(Date.now() + 3600000).toISOString(), legalEntities: [{ id: 'legal', name: '甲公司', baseCurrency: 'CNY', timeZone: 'Asia/Shanghai' }], categories: [{ code: 'OFFICE', name: '办公费', units: ['ITEM'] }], cities: [{ code: 'SH', name: '上海' }], costCenters: [{ legalEntityId: 'legal', code: 'IT', name: '研发' }], projects: [] })
const content = () => ({ legalEntityId: 'legal', title: ' 办公费 ', type: 'DAILY', lines: [{ lineNo: 3, categoryCode: 'OFFICE', cityCode: 'SH', incurredOn: '2026-09-28', endedOn: null, quantity: '1.001', unit: 'ITEM', claimedGross: money('100.00'), claimedTax: money('6.00'), invoiceIds: [], priorRequest: null, description: ' 办公耗材 ', exceptionReason: null, allocations: [{ costCenter: 'IT', projectCode: null, amount: money('100.00') }] }], advanceOffsets: [] })
const definition = () => ({ id: 'definition', key: 'expense', name: '费用', version: 2, status: 'PUBLISHED', startEnabled: true, formSchema: { fields: Object.entries({ expenseDetails: 'TEXT', amount: 'NUMBER', currency: 'TEXT', overPolicy: 'BOOLEAN' }).map(([key, type]) => ({ key, type, required: true, ...(key === 'expenseDetails' ? { sensitive: true } : {}) })) } })
const detail = () => ({ id: 'report', applicationId: 'app', businessNo: 'EXP-1', applicationStatus: 'DRAFT', applicationVersion: 2, financialVersion: 4, roundNo: 1, editable: true, content: content(), financialRound: null })
const options = () => ({ applicationVersion: 2, financialVersion: 4, enabled: true, latestPrecheckId: 'precheck', destination: 'finance.test', targetDigest: 'a'.repeat(64) })
const view = () => ({ job: { id: 'precheck', applicationVersion: 2, financialVersion: 4, status: 'READY', attempt: 1 }, usable: true, unavailableCode: null, initiator: { appointmentId: 'appointment', legalEntityId: 'legal' }, accountingDate: '2026-09-28', validUntil: new Date(Date.now() + 60000).toISOString(), findings: [], preview: { roundNo: 1, payable: money('100.00'), approvedGross: money('100.00'), offsetTotal: money('0.00') } })
let scopeIndex = 0
function panel(Component, initial) {
  const props = reactive(initial), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onSubmitted: id => events.push(id), onClose: () => events.push('close') })
  const mounted = app.mount({})
  return { state: mounted.$.setupState, props, events, close(cleanup = true) { app.unmount(); Object.assign(api, originals); if (cleanup) expenseDrafts.clear(props.scopeKey, props.initial?.id ?? '') } }
}
function editor(initial) {
  api.financeCatalog = async () => catalog()
  return panel(Editor, { scopeKey: 'editor-' + ++scopeIndex, initial, locked: false })
}
function submission() {
  api.expensePrecheckOptions = async () => options(); api.expensePrecheck = async () => view()
  return panel(Submission, { detail: detail(), scopeKey: 'alice', timeZone: 'Asia/Shanghai', locked: false })
}

test('保存边界按整数分校验大额分摊，数量为数字而金额保持原字符串，不修改输入', () => {
  const value = content(); value.lines[0].claimedGross = money('999999999999999.99'); value.lines[0].allocations[0].amount = money('999999999999999.99')
  const before = copy(value), result = expenseContent(value, catalog())
  assert.deepEqual(value, before); assert.equal(result.lines[0].quantity, 1.001); assert.equal(result.lines[0].claimedGross.value, '999999999999999.99'); assert.equal(result.title, '办公费')
  result.lines[0].allocations[0].amount.value = '0'; assert.deepEqual(value, before)
  assert.equal(newExpenseLine(value, 'CNY').lineNo, 1)
})

test('错误归属、过期目录、金额与分摊差额和跨行重用发票均在保存前提示', () => {
  assert.throws(() => expenseContent(content(), { ...catalog(), validUntil: '2000-01-01' }), /过期/)
  for (const modify of [value => { value.legalEntityId = 'other' }, value => { value.lines[0].allocations[0].amount.value = '99.99' }, value => { value.lines[0].allocations[0].costCenter = 'foreign' }, value => { value.lines[0].claimedTax.value = '100.01' }, value => { value.lines[0].quantity = '1e2' }, value => { value.lines[0].invoiceIds = ['same', 'same'] }]) {
    const value = content(); modify(value); assert.throws(() => expenseContent(value, catalog()))
  }
  assert.equal(expenseDefinition(definition()), true)
  for (const modify of [value => { value.formSchema.fields[0].sensitive = false }, value => { value.formSchema.fields.pop() }, value => { value.formSchema.fields[1].type = 'TEXT' }]) { const value = definition(); modify(value); assert.equal(expenseDefinition(value), false) }
})

test('历史 READY 必须匹配当前保存版本、下一轮、任职、法人、会计日和有效期', () => {
  assert.equal(usablePrecheck(view(), detail(), 'appointment', '2026-09-28'), true)
  for (const applicationStatus of ['RETURNED', 'WITHDRAWN']) {
    const returned = { ...detail(), applicationStatus }, next = view(); next.preview.roundNo = 2
    assert.equal(usablePrecheck(next, returned, 'appointment', '2026-09-28'), true)
    assert.equal(usablePrecheck(view(), returned, 'appointment', '2026-09-28'), false)
  }
  for (const modify of [value => { value.usable = false }, value => { value.job.financialVersion++ }, value => { value.job.applicationVersion++ }, value => { value.preview.roundNo++ }, value => { value.initiator.legalEntityId = 'other' }, value => { value.accountingDate = '2026-09-29' }, value => { value.validUntil = '2000-01-01' }]) { const value = view(); modify(value); assert.equal(usablePrecheck(value, detail(), 'appointment', '2026-09-28'), false) }
})

test('发票只选本人当前法人未过期且可用的原件，保留本单已有占用', () => {
  const value = { id: 'invoice', original: { status: 'READY' }, verification: 'VERIFIED', occupation: 'AVAILABLE', facts: { legalEntityId: 'legal', validUntil: new Date(Date.now() + 10000).toISOString() }, use: null }
  assert.equal(invoiceSelectable(value, 'legal'), true)
  assert.equal(invoiceSelectable({ ...value, occupation: 'OCCUPIED', use: { reportId: 'report' } }, 'legal', 'report'), true)
  for (const patch of [{ occupation: 'CONSUMED' }, { occupation: 'OCCUPIED' }, { verification: 'PENDING' }, { original: { status: 'UPLOADING' } }, { facts: { ...value.facts, legalEntityId: 'other' } }]) assert.equal(invoiceSelectable({ ...value, ...patch }, 'legal'), false)
})

test('保存恢复按账号、原路径和原正文绑定，只确认原草稿而不触发提交', () => {
  const drafts = new ExpenseDrafts(), result = detail(), body = JSON.stringify({ content: result.content })
  drafts.put('alice', '', { detail: null, content: result.content, businessNo: 'EXP-1', definition: definition(), baseline: '{}', pending: { path: '/expense-reports', body }, requiresRefresh: true })
  assert.equal(drafts.get('bob', ''), null); assert.equal(drafts.hasDrafts(), true)
  assert.equal(drafts.acknowledge('bob', '/expense-reports', body, result), false)
  assert.equal(drafts.acknowledge('alice', '/expense-reports', '{}', result), false)
  assert.equal(drafts.acknowledge('alice', '/expense-reports', body, result), true)
  const stored = drafts.get('alice', ''); assert.equal(stored.detail.id, 'report'); assert.equal(stored.requiresRefresh, false); assert.equal(drafts.hasDrafts(), false)
  stored.content.title = '外部修改'; assert.notEqual(drafts.get('alice', '').content.title, '外部修改')
})

test('只填写业务编号时离开浏览器也属于未保存内容，保存或显式放弃后解除提示', () => {
  const drafts = new ExpenseDrafts(), value = emptyExpense()
  drafts.put('alice', '', { detail: null, content: value, businessNo: 'EXP-ONLY-NUMBER', definition: null, baseline: JSON.stringify(value), pending: null, requiresRefresh: false })
  assert.equal(drafts.hasDrafts(), true)
  drafts.clear('alice', ''); assert.equal(drafts.hasDrafts(), false)
})

test('实际编辑器创建后复用返回双版本修改，保存本身不会调用预检或正式提交', async () => {
  const p = editor(), calls = []; let unexpected = 0
  api.queueExpensePrecheck = api.submitExpense = async () => { unexpected++ }
  api.createExpense = async body => { calls.push(body); return { ...detail(), content: body.content } }
  api.reviseExpense = async (id, body) => { calls.push([id, body]); return { ...detail(), applicationVersion: 3, financialVersion: 5, content: body.content } }
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-1'; p.state.state.definition = definition()
    await p.state.save(); assert.equal(p.state.state.detail.id, 'report'); assert.equal(p.state.dirty, false); assert.equal(p.state.state.pending, null)
    p.state.state.content.title = '修改后的费用'; await p.state.save()
    assert.equal(calls[0].processKey, 'expense'); assert.equal(calls[1][0], 'report'); assert.equal(calls[1][1].applicationVersion, 2); assert.equal(calls[1][1].financialVersion, 4); assert.equal(unexpected, 0)
    p.state.close(); assert.deepEqual(p.events, ['close']); assert.equal(expenseDrafts.get(p.props.scopeKey, ''), null)
  } finally { p.close() }
})

test('版本冲突保留本地内容，阻止再次写入，用户明确读服务器版后才可继续', async () => {
  const p = editor(detail()); let writes = 0
  api.reviseExpense = async () => { writes++; throw { status: 409, code: 'CONCURRENCY_CONFLICT' } }
  api.expenseReport = async () => ({ ...detail(), applicationVersion: 9, financialVersion: 10 })
  try {
    await settle(); p.state.state.content.title = '不能自动覆盖'; await p.state.save(); await p.state.save()
    assert.equal(writes, 1); assert.equal(p.state.state.content.title, '不能自动覆盖'); assert.equal(p.state.state.detail.applicationVersion, 2)
    await p.state.reloadSaved(); assert.equal(p.state.state.detail.applicationVersion, 9); assert.equal(p.state.state.requiresRefresh, false); assert.equal(writes, 1)
  } finally { p.close() }
})

test('票面复核期间不能保存或离开；带入后仍需通过原分摊校验再明确保存', async () => {
  const p = editor(), calls = []; let catalogReads = 0, unexpected = 0
  api.financeCatalog = async () => { catalogReads++; return catalog() }
  api.queueExpensePrecheck = api.submitExpense = async () => { unexpected++ }
  api.createExpense = async body => { calls.push(body); return { ...detail(), content: body.content } }
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-FILL'; p.state.state.definition = definition()
    p.state.assistBusy = true
    await p.state.save(); p.state.close(); p.state.leave(); await p.state.loadCatalog()
    assert.equal(calls.length, 0); assert.equal(catalogReads, 0); assert.deepEqual(p.events, []); assert.equal(p.state.blocked, true)
    p.state.assistBusy = false
    const original = { id: 'invoice', original: { id: 'original', status: 'READY', sha256: 'a'.repeat(64), size: 128, format: 'XML' } }
    const source = { status: 'CONFIRMED', input: { invoiceId: 'invoice', originalId: 'original', originalDigest: 'a'.repeat(64), originalBytes: 128, format: 'XML' }, review: { selected: [{ field: 'GROSS_AMOUNT', value: '105.50' }] } }
    p.state.state.content.lines[0] = fillExpenseLineFromInvoice(p.state.state.content.lines[0], original, source, ['GROSS_AMOUNT'], true)
    assert.equal(p.state.dirty, true); await p.state.save()
    assert.equal(calls.length, 0); assert.match(p.state.error, /分摊之和/)
    p.state.state.content.lines[0].allocations[0].amount.value = '105.50'; await p.state.save()
    assert.equal(calls.length, 1); assert.equal(calls[0].content.lines[0].claimedGross.value, '105.50'); assert.equal(calls[0].content.lines[0].claimedTax.value, '6.00')
    assert.deepEqual(calls[0].content.lines[0].invoiceIds, []); assert.equal(p.state.dirty, false); assert.equal(unexpected, 0)
  } finally { p.close() }
})

test('未知保存响应用原回执恢复，清除等待后编辑器继续原单而不重复创建', async () => {
  const p = editor(); let count = 0
  api.createExpense = async () => { count++; throw { status: 0, code: 'REQUEST_TIMEOUT' } }
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-1'; p.state.state.definition = definition()
    await p.state.save(); assert.equal(p.state.state.requiresRefresh, true)
    const original = p.state.state.pending
    assert.equal(expenseDrafts.acknowledge(p.props.scopeKey, original.path, original.body, detail()), true)
    assert.equal(p.state.state.detail.id, 'report'); assert.equal(p.state.state.requiresRefresh, false); assert.equal(count, 1)
  } finally { p.close() }
})

test('切换身份与目录超时清空财务目录，迟到响应不能回填', async t => {
  const reads = []; api.financeCatalog = (_signal) => new Promise(resolve => reads.push(resolve))
  const p = panel(Editor, { scopeKey: 'old-scope', locked: false })
  try {
    p.state.state.content.title = '旧账号私密草稿'; p.props.scopeKey = 'new-scope'
    assert.equal(p.state.state.content.title, ''); reads[0](catalog()); await settle(); assert.equal(p.state.catalog, null)
    reads[1](catalog()); await settle(); assert.ok(p.state.catalog)
    t.mock.timers.enable({ apis: ['setTimeout'] }); const loading = p.state.loadCatalog(); t.mock.timers.tick(12000)
    assert.equal(p.state.loading, false); assert.equal(p.state.catalog, null); reads[2](catalog()); await loading; assert.equal(p.state.catalog, null)
  } finally { p.close(); expenseDrafts.clear('old-scope', '') }
})

test('原草稿换页后恢复，编辑器显式放弃后不由卸载重新保存', async () => {
  const p = editor(); await settle(); const scope = p.props.scopeKey
  p.state.state.content.title = '尚未保存'; p.close(false)
  assert.equal(expenseDrafts.get(scope, '').content.title, '尚未保存')
  api.financeCatalog = async () => catalog()
  const other = panel(Editor, { scopeKey: scope, locked: false }); await settle()
  try { assert.equal(other.state.state.content.title, '尚未保存'); other.state.close(); assert.equal(other.state.discard, true); other.state.leave() }
  finally { other.close(false) }
  assert.equal(expenseDrafts.get(scope, ''), null)
})

test('预检必须显式选择任职和会计日，排队只发送保存版本及实际目标', async () => {
  const p = submission(), calls = []
  api.expensePrecheckOptions = async () => ({ ...options(), latestPrecheckId: null })
  api.queueExpensePrecheck = async (...args) => { calls.push(args); return { id: 'precheck' } }
  try {
    await settle(); await p.state.queue(); assert.equal(calls.length, 0)
    p.state.appointment = 'appointment'; p.state.accountingDate = '2026-09-28'; await p.state.queue()
    assert.deepEqual(calls, [['report', { applicationVersion: 2, financialVersion: 4, initiatorAppointmentId: 'appointment', accountingDate: '2026-09-28', targetDigest: 'a'.repeat(64) }]])
    assert.equal(p.state.saving, false); assert.equal(p.state.ready, true)
  } finally { p.close() }
})

test('正式提交需二次确认，使用原预检和双版本；改变任职撤销确认', async () => {
  const p = submission(), calls = []
  api.submitExpense = async (...args) => { calls.push(args); return { reportId: 'report', applicationId: 'app' } }
  try {
    await settle(); p.state.appointment = 'appointment'; p.state.accountingDate = '2026-09-28'; await settle()
    await p.state.submit(); assert.equal(calls.length, 0); p.state.prepare(); assert.equal(p.state.confirm, true)
    p.state.appointment = 'different'; await settle(); assert.equal(p.state.confirm, false)
    p.state.appointment = 'appointment'; await settle(); p.state.prepare(); await p.state.submit()
    assert.deepEqual(calls, [['report', { applicationVersion: 2, financialVersion: 4, precheckId: 'precheck' }]]); assert.deepEqual(p.events, ['app'])
  } finally { p.close() }
})

test('确认期间财务事实到期，即使已缓存 READY 也不发送正式提交', async t => {
  const p = submission(); let writes = 0
  api.submitExpense = async () => { writes++; return { reportId: 'report', applicationId: 'app' } }
  try {
    await settle(); p.state.appointment = 'appointment'; p.state.accountingDate = '2026-09-28'; await settle(); p.state.prepare(); assert.equal(p.state.ready, true)
    t.mock.method(Date, 'now', () => Date.parse(p.state.result.validUntil) + 1)
    await p.state.submit(); assert.equal(writes, 0)
  } finally { p.close() }
})

test('预检状态读取超时或版本不匹配不显示旧账户和可提交按钮', async t => {
  const p = submission()
  try {
    await settle(); assert.ok(p.state.result)
    api.expensePrecheckOptions = async () => ({ ...options(), financialVersion: 8 }); await p.state.load(); assert.equal(p.state.options, null); assert.equal(p.state.result, null)
    t.mock.timers.enable({ apis: ['setTimeout'] }); let finish
    api.expensePrecheckOptions = () => new Promise(resolve => { finish = resolve }); const waiting = p.state.load(); t.mock.timers.tick(12000)
    assert.equal(p.state.reading, false); assert.equal(p.state.result, null); finish(options()); await waiting; assert.equal(p.state.result, null)
  } finally { p.close() }
})

test('实际资源选择器过滤法人、失效发票和他单占用，单据内不能重复引用', async () => {
  const invoice = { id: 'invoice', original: { status: 'READY' }, verification: 'VERIFIED', occupation: 'AVAILABLE', facts: { legalEntityId: 'legal', validUntil: new Date(Date.now() + 60000).toISOString() } }
  api.invoices = async () => ({ items: [invoice, { ...invoice, id: 'foreign', facts: { ...invoice.facts, legalEntityId: 'foreign' } }], nextBeforeId: null })
  const p = panel(Funding, { scopeKey: 'alice', reportId: 'report', baseCurrency: 'CNY', locked: false, modelValue: content() })
  try {
    p.state.open = true; await settle(); p.state.lineNo = 3
    assert.deepEqual(p.state.availableInvoices.map(item => item.id), ['invoice'])
    p.state.addInvoice(invoice); p.state.addInvoice(invoice); assert.deepEqual(p.props.modelValue.lines[0].invoiceIds, ['invoice'])
    p.props.locked = true; p.state.addInvoice({ ...invoice, id: 'second' }); assert.deepEqual(p.props.modelValue.lines[0].invoiceIds, ['invoice'])
    p.props.scopeKey = 'bob'; assert.deepEqual(p.state.invoices.items, []); assert.equal(p.state.open, false)
  } finally { p.close() }
})

test('实际资源选择器仅引用当前法人的开放批准行与本位币借款', async () => {
  api.expenseRequests = async () => ({ items: [{ id: 'prior', legalEntityId: 'legal', closed: false, lines: [{ lineNo: 5 }] }, { id: 'closed', legalEntityId: 'legal', closed: true, lines: [{ lineNo: 6 }] }] })
  api.employeeAdvances = async () => ({ items: [{ id: 'advance', legalEntityId: 'legal', paid: money('80.00'), status: 'PAID_OUT' }, { id: 'foreign', legalEntityId: 'legal', paid: money('90.00', 'USD'), status: 'PAID_OUT' }, { id: 'held', legalEntityId: 'legal', paid: money('80.00'), status: 'PAYMENT_REVIEW' }] })
  const p = panel(Funding, { scopeKey: 'alice', reportId: 'report', baseCurrency: 'CNY', locked: false, modelValue: content() })
  try {
    p.state.kind = 'requests'; p.state.open = true; await settle(); p.state.lineNo = 3
    p.state.addPrior('closed', 6); assert.equal(p.props.modelValue.lines[0].priorRequest, null)
    p.state.addPrior('prior', 5); assert.deepEqual(p.props.modelValue.lines[0].priorRequest, { requestId: 'prior', lineNo: 5 })
    p.state.kind = 'advances'; await settle(); p.state.addAdvance('foreign'); p.state.addAdvance('held'); assert.equal(p.props.modelValue.advanceOffsets.length, 0)
    p.state.addAdvance('advance'); p.state.addAdvance('advance'); assert.deepEqual(p.props.modelValue.advanceOffsets, [{ advanceId: 'advance', amount: money('') }])
  } finally { p.close() }
})

test('费用 API 保留精确正文、双版本及查询无缓存，未知创建按原幂等键恢复', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, calls = []
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'origination', roles: [] })
  let lose = true
  globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); if (lose) { lose = false; throw new TypeError('network') } return new Response(JSON.stringify(detail()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  try {
    const body = { businessNo: 'EXP-1', processKey: 'expense', definitionVersion: 2, content: content() }
    await assert.rejects(api.createExpense(body)); const pending = writeRequests.pending()[0]; await writeRequests.recover(pending.id)
    assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']); assert.equal(JSON.parse(calls[1].body).content.lines[0].claimedGross.value, '100.00')
    await api.expensePrecheck('id/with?chars', 'job/name', new AbortController().signal)
    assert.match(calls[2].url, /id%2Fwith%3Fchars\/prechecks\/job%2Fname/); assert.equal(calls[2].cache, 'no-store')
  } finally { bindAuthenticationActor(null); globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage }
})
