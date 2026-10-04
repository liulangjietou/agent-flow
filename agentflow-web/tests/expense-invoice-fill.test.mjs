import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
const expense = await import(process.env.AGENTFLOW_TEST_EXPENSE_DRAFT)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEINVOICEASSISTPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_EXPENSEINVOICEASSISTRENDERED)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }
afterEach(() => Object.assign(api, originalApi))
const settle = () => new Promise(resolve => setImmediate(resolve))
const money = (value, currency = 'CNY') => ({ value, currency })
const invoice = () => ({ id: 'invoice', original: { id: 'original', sha256: 'a'.repeat(64), size: 128, format: 'XML', status: 'READY' }, verification: 'PENDING', occupation: 'AVAILABLE', facts: null })
const run = (selected = [{ field: 'GROSS_AMOUNT', value: '106.00' }]) => ({ id: 'run', status: 'CONFIRMED', version: 4,
  input: { invoiceId: 'invoice', originalId: 'original', originalDigest: 'a'.repeat(64), originalBytes: 128, format: 'XML', pageCount: 1 },
  review: { actor: 'alice', at: '2026-10-02T00:00:00Z', selected } })
const line = () => ({ lineNo: 1, categoryCode: 'OFFICE', incurredOn: '2026-09-30', endedOn: null, cityCode: 'SH', quantity: '1', unit: 'ITEM', claimedGross: money('100.00'), claimedTax: money('0.00'),
  invoiceIds: [], priorRequest: null, allocations: [{ costCenter: 'IT', projectCode: null, amount: money('60.00') }, { costCenter: 'IT2', projectCode: null, amount: money('40.00') }], description: '本人填写', exceptionReason: null })
const content = () => ({ legalEntityId: 'legal', title: '本人报销', type: 'DAILY', lines: [line(), { ...line(), lineNo: 2, description: '另一行' }], advanceOffsets: [] })
const history = page => ({ items: [run(), { ...run(), id: 'unconfirmed', status: 'COMPLETED', version: 3 }], total: 21, page, pageSize: 20 })

test('已计算补贴不能通过票面助手覆盖金额或币种', () => {
  const input = { ...line(), unit: 'DAY', endedOn: '2026-09-30', allowance: {
    policy: { selection: { policyId: 'daily-policy', policyVersion: 1, categoryRevision: 1, activeRevision: 1, definitionDigest: 'a'.repeat(64) }, ruleKey: 'daily', factSourceReference: 'trusted' },
    calculation: { startsOn: '2026-09-30', endsOn: '2026-09-30', days: 1, rule: { dailyRate: money('100.00'), dayCountBasis: 'CALENDAR_DAYS_INCLUSIVE' }, gross: money('100.00') } } }
  const before = JSON.stringify(input)
  assert.throws(() => expense.fillExpenseLineFromInvoice(input, invoice(), run(), ['GROSS_AMOUNT', 'CURRENCY'], true), /补贴金额按行程自动计算/)
  assert.equal(JSON.stringify(input), before)
})
function reads() {
  api.invoices = async () => ({ items: [invoice()], nextBeforeId: null })
  api.invoice = async () => invoice(); api.invoiceExtractionRuns = async (_, page) => history(page); api.invoiceExtractionRun = async () => run()
}
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel(extra = {}) {
  const props = reactive({ modelValue: content(), scopeKey: 'demo/alice', locked: false, ...extra }), updates = [], events = []
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, {
    ...props, 'onUpdate:modelValue': value => { updates.push(value); props.modelValue = value }, onBusy: value => events.push(value)
  })
  return { props, updates, events, state: app.mount({}).$.setupState, close: () => app.unmount() }
}
async function selectSource(p) { p.state.open = true; await settle(); p.state.lineNo = 1; await p.state.selectInvoice('invoice'); await p.state.selectRun('run') }

test('已确认票面可明确带入报销行，源字段缺失时不能推测币种', () => {
  assert.equal(typeof expense.fillExpenseLineFromInvoice, 'function')
  const value = line(), before = structuredClone(value)
  assert.throws(() => expense.fillExpenseLineFromInvoice(value, invoice(), run(), ['GROSS_AMOUNT'], false), /币种/)
  const result = expense.fillExpenseLineFromInvoice(value, invoice(), run(), ['GROSS_AMOUNT'], true)
  assert.equal(result.claimedGross.value, '106.00'); assert.deepEqual(value, before)
  assert.equal(result.incurredOn, value.incurredOn); assert.deepEqual(result.claimedTax, value.claimedTax)
  assert.deepEqual(result.allocations, value.allocations); assert.deepEqual(result.invoiceIds, [])
  assert.equal(invoice().verification, 'PENDING')
})

test('票面税额和开票日期不能作为可抵扣税额与费用发生日期自动带入', () => {
  assert.equal(typeof expense.invoiceFillChoices, 'function')
  const source = run([{ field: 'GROSS_AMOUNT', value: '106.00' }, { field: 'CURRENCY', value: 'CNY' }, { field: 'TAX_AMOUNT', value: '6.00' }, { field: 'ISSUE_DATE', value: '2026-10-02' }])
  assert.deepEqual(expense.invoiceFillChoices(source, line()).map(choice => choice.field), ['GROSS_AMOUNT', 'CURRENCY'])
  for (const field of ['TAX_AMOUNT', 'ISSUE_DATE', 'BUYER_NAME']) assert.throws(() => expense.fillExpenseLineFromInvoice(line(), invoice(), source, [field], true))
})

test('币种不同不能只复制金额；确认改变币种时不换算任何已有金额', () => {
  const source = run([{ field: 'GROSS_AMOUNT', value: '999999999999999.99' }, { field: 'CURRENCY', value: 'USD' }]), value = line()
  assert.throws(() => expense.fillExpenseLineFromInvoice(value, invoice(), source, ['GROSS_AMOUNT'], true), /同时选择币种/)
  assert.throws(() => expense.fillExpenseLineFromInvoice(value, invoice(), source, ['GROSS_AMOUNT', 'CURRENCY'], false), /明确核对/)
  const result = expense.fillExpenseLineFromInvoice(value, invoice(), source, ['GROSS_AMOUNT', 'CURRENCY'], true)
  assert.deepEqual(result.claimedGross, money('999999999999999.99', 'USD')); assert.deepEqual(result.claimedTax, money('0.00', 'USD'))
  assert.deepEqual(result.allocations.map(a => a.amount), [money('60.00', 'USD'), money('40.00', 'USD')])
  assert.deepEqual(value, line())
  source.review.selected[1].value = 'CNY'
  assert.equal(expense.fillExpenseLineFromInvoice(value, invoice(), source, ['GROSS_AMOUNT'], false).claimedGross.currency, 'CNY')
})

test('未确认、原件变化、空选择、重复字段与负数超限金额全部拒绝', () => {
  for (const status of ['COMPLETED', 'DISMISSED', 'FAILED']) assert.throws(() => expense.fillExpenseLineFromInvoice(line(), invoice(), { ...run(), status }, ['GROSS_AMOUNT'], true), /记录/)
  for (const original of [{ ...invoice().original, status: 'UPLOADING' }, { ...invoice().original, sha256: 'b'.repeat(64) }]) assert.throws(() => expense.fillExpenseLineFromInvoice(line(), { ...invoice(), original }, run(), ['GROSS_AMOUNT'], true), /原件/)
  for (const fields of [[], ['GROSS_AMOUNT', 'GROSS_AMOUNT'], ['CURRENCY']]) assert.throws(() => expense.fillExpenseLineFromInvoice(line(), invoice(), run(), fields, true))
  for (const amount of ['-1.00', '0.00', '1000000000000000.00']) {
    const source = run([{ field: 'GROSS_AMOUNT', value: amount }])
    assert.ok(expense.invoiceFillChoices(source, line())[0].unavailable)
    assert.throws(() => expense.fillExpenseLineFromInvoice(line(), invoice(), source, ['GROSS_AMOUNT'], true))
  }
})

test('面板默认关闭不读取；只展示已确认记录，打开来源不会修改报销或发票', async () => {
  reads(); let lists = 0, details = 0
  api.invoices = async () => { lists++; return { items: [invoice()], nextBeforeId: null } }
  api.invoiceExtractionRun = async () => { details++; return run() }
  const p = panel()
  try {
    await settle(); assert.equal(lists, 0); await selectSource(p)
    assert.equal(lists, 1); assert.equal(p.state.confirmedRuns.length, 1); assert.deepEqual(p.state.selected, []); assert.equal(p.state.canApply, false)
    await p.state.selectRun('unconfirmed'); assert.equal(details, 1)
    assert.deepEqual(p.props.modelValue, content()); assert.equal(p.updates.length, 0)
  } finally { p.close() }
})

test('带入只改选中行的本人确认金额，重新读取来源且不调用任何写接口', async () => {
  reads(); let metadataReads = 0, runReads = 0, writes = 0
  api.invoice = async () => { metadataReads++; return invoice() }; api.invoiceExtractionRun = async () => { runReads++; return run() }
  for (const name of ['createExpense', 'reviseExpense', 'submitExpense', 'queueExpensePrecheck', 'generateInvoiceExtraction', 'reviewInvoiceExtraction']) api[name] = async () => { writes++ }
  const p = panel()
  try {
    await selectSource(p); p.state.selected = ['GROSS_AMOUNT']; assert.equal(p.state.needsCurrencyConfirmation, true)
    await p.state.apply(); assert.equal(p.updates.length, 0); assert.match(p.state.error, /币种/)
    p.state.currencyConfirmed = true; await p.state.apply()
    assert.equal(metadataReads, 2); assert.equal(runReads, 2); assert.equal(p.updates.length, 1); assert.equal(writes, 0)
    assert.equal(p.props.modelValue.lines[0].claimedGross.value, '106.00'); assert.deepEqual(p.props.modelValue.lines[1], content().lines[1])
    assert.deepEqual(p.props.modelValue.lines[0].invoiceIds, []); assert.deepEqual(p.props.modelValue.lines[0].allocations, line().allocations)
    assert.equal(p.state.invoice.verification, 'PENDING'); assert.equal(p.state.applying, false); assert.equal(p.events.at(-1), false)
    assert.match(p.state.notice, /尚未保存/); assert.deepEqual(p.state.selected, [])
  } finally { p.close() }
})

test('更换目标行或币种会取消原勾选授权，不能以旧确认继续带入', async () => {
  reads(); const p = panel()
  try {
    await selectSource(p); p.state.selected = ['GROSS_AMOUNT']; p.state.currencyConfirmed = true
    p.props.modelValue.lines[0].claimedGross.currency = 'USD'; assert.equal(p.state.currencyConfirmed, false)
    p.state.currencyConfirmed = true; p.state.lineNo = 2; assert.equal(p.state.currencyConfirmed, false); assert.deepEqual(p.state.selected, [])
  } finally { p.close() }
})

test('等待来源复核时目标行被修改，保留新输入而不覆盖', async () => {
  reads(); const p = panel(); let finish
  try {
    await selectSource(p); p.state.selected = ['GROSS_AMOUNT']; p.state.currencyConfirmed = true
    api.invoiceExtractionRun = () => new Promise(resolve => { finish = resolve })
    const applying = p.state.apply(); await settle(); assert.equal(p.state.applying, true)
    p.props.modelValue.lines[0].description = '等待期间的新说明'; finish(run()); await applying
    assert.equal(p.updates.length, 0); assert.equal(p.props.modelValue.lines[0].description, '等待期间的新说明'); assert.match(p.state.error, /目标费用行已修改/)
  } finally { p.close() }
})

test('复核时来源或确认值改变，要求重新选择且不复制新值', async () => {
  for (const changed of ['original', 'review']) {
    reads(); const p = panel()
    try {
      await selectSource(p); p.state.selected = ['GROSS_AMOUNT']; p.state.currencyConfirmed = true
      if (changed === 'original') api.invoice = async () => ({ ...invoice(), original: { ...invoice().original, sha256: 'b'.repeat(64) } })
      else api.invoiceExtractionRun = async () => run([{ field: 'GROSS_AMOUNT', value: '999.00' }])
      await p.state.apply(); assert.equal(p.updates.length, 0); assert.match(p.state.error, /变化/)
    } finally { p.close() }
  }
})

test('复核时失去本人权限清除票夹和正文，不修改现有费用输入', async () => {
  reads(); const p = panel()
  try {
    await selectSource(p); p.state.selected = ['GROSS_AMOUNT']; p.state.currencyConfirmed = true
    api.invoiceExtractionRun = async () => { throw { status: 403, code: 'FORBIDDEN' } }
    await p.state.apply(); assert.equal(p.updates.length, 0); assert.equal(p.state.detail, null); assert.equal(p.state.invoice, null); assert.equal(p.state.invoices.items.length, 0)
    assert.equal(p.state.applying, false); assert.deepEqual(p.props.modelValue, content())
  } finally { p.close() }
})

test('来源复核超时释放忙碌，迟到成功不能填入费用行', async t => {
  reads(); const p = panel(); let finish
  try {
    await selectSource(p); p.state.selected = ['GROSS_AMOUNT']; p.state.currencyConfirmed = true
    api.invoiceExtractionRun = () => new Promise(resolve => { finish = resolve })
    t.mock.timers.enable({ apis: ['setTimeout'] }); const applying = p.state.apply(); await settle(); t.mock.timers.tick(12000); await applying
    assert.equal(p.state.applying, false); assert.match(p.state.error, /超时/); assert.equal(p.updates.length, 0)
    finish(run()); await settle(); assert.equal(p.updates.length, 0)
  } finally { p.close() }
})

test('账号切换中断旧读取，旧账号迟到拒绝不能清除新账号已读取来源', async () => {
  reads(); const p = panel(); let rejectOld
  try {
    api.invoices = () => new Promise((_, reject) => { rejectOld = reject })
    p.state.open = true; await settle(); p.props.scopeKey = 'demo/bob'; p.props.modelValue = content(); await settle()
    reads(); await selectSource(p); assert.ok(p.state.detail)
    rejectOld({ status: 403, code: 'FORBIDDEN' }); await settle(); assert.ok(p.state.detail)
    assert.equal(p.updates.length, 0)
  } finally { p.close() }
})

test('父级锁定或来源复核期间切换账号均不能回填', async () => {
  for (const changed of ['locked', 'actor']) {
    reads(); const p = panel(); let finish
    try {
      await selectSource(p); p.state.selected = ['GROSS_AMOUNT']; p.state.currencyConfirmed = true
      api.invoiceExtractionRun = () => new Promise(resolve => { finish = resolve })
      const applying = p.state.apply(); await settle()
      if (changed === 'locked') p.props.locked = true
      else p.props.scopeKey = 'demo/other'
      finish(run()); await applying; assert.equal(p.updates.length, 0); assert.equal(p.state.applying, false)
    } finally { p.close() }
  }
})

test('历史翻页只读取目标页，清除上页字段选择', async () => {
  reads(); const pages = []; api.invoiceExtractionRuns = async (_, page) => { pages.push(page); return history(page) }
  const p = panel()
  try {
    await selectSource(p); p.state.selected = ['GROSS_AMOUNT']; await p.state.selectInvoice('invoice', 1)
    assert.deepEqual(pages, [0, 1]); assert.equal(p.state.history.page, 1); assert.equal(p.state.detail, null); assert.deepEqual(p.state.selected, [])
  } finally { p.close() }
})

test('真实模板转义来源文本并明确提示日期税额与正式引用边界', async () => {
  reads(); const props = { modelValue: content(), scopeKey: 'demo/render', locked: false }
  const source = run([{ field: 'GROSS_AMOUNT', value: '106.00' }, { field: 'BUYER_NAME', value: '<img src=x onerror=alert(1)>' }])
  source.suggestion = { proposals: [{ field: 'BUYER_NAME', value: '<script>model()</script>', evidence: [{ quote: '<script>xml()</script>' }] }] }
  api.invoice = async () => ({ ...invoice(), original: { ...invoice().original, filename: '<img src=x onerror=filename()>.xml' } })
  api.invoiceExtractionRun = async () => source
  const html = await renderToString(createSSRApp({ ...Rendered, async setup(input, ctx) {
    const state = Rendered.setup(input, ctx)
    state.open.value = true; state.lineNo.value = 1
    await state.selectInvoice('invoice'); await state.selectRun('run')
    return state
  } }, props))
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/); assert.match(html, /&lt;script&gt;model\(\)&lt;\/script&gt;/)
  assert.match(html, /&lt;script&gt;xml\(\)&lt;\/script&gt;/); assert.match(html, /&lt;img src=x onerror=filename\(\)\&gt;\.xml/)
  assert.ok(!html.includes('<img src=x')); assert.ok(!html.includes('<script>'))
  assert.match(html, /开票日期不等于费用发生日期/); assert.match(html, /票面税额不等于可抵扣税额/); assert.match(html, /正式引用发票仍需先查验/)
})
