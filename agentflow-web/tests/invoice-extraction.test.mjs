import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'
const extraction = await import(process.env.AGENTFLOW_TEST_INVOICE_EXTRACTION)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_INVOICEEXTRACTIONPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_INVOICEEXTRACTIONRENDERED)
const { default: InvoiceDetail } = await import(process.env.AGENTFLOW_TEST_INVOICEDETAIL)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, originalDocument = globalThis.document
afterEach(() => { Object.assign(api, originalApi); globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage; globalThis.document = originalDocument; bindAuthenticationActor(null) })
const settle = () => new Promise(resolve => setImmediate(resolve))
const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const when = '2026-10-02T00:00:00Z'
const input = (format = 'XML') => ({ invoiceId: uuid(1), originalId: uuid(2), originalDigest: 'a'.repeat(64), format, originalBytes: 321, pageCount: format === 'PDF' ? 3 : 1 })
const item = () => ({ id: uuid(1), version: 1, original: { id: uuid(2), filename: '本人原件.xml', sha256: 'a'.repeat(64), size: 321, format: 'XML', status: 'READY' }, verification: 'PENDING', occupation: 'AVAILABLE', facts: null, use: null, failureCode: null, checkedAt: null })
const options = (method = 'STRUCTURED_XML', format = 'XML') => ({ input: input(format), method, transmission: method === 'STRUCTURED_XML' ? 'NONE' : format === 'XML' ? 'XML_TEXT' : 'ORIGINAL_BYTES', enabled: true, unavailableCode: null,
  providerId: method === 'MODEL' ? 'fixture' : null, model: method === 'MODEL' ? 'fixture-v1' : null, destination: method === 'MODEL' ? '127.0.0.1' : null, targetDigest: method === 'MODEL' ? 'b'.repeat(64) : null, supportedFormats: ['XML', 'PNG', 'JPEG', 'PDF'] })
const summary = () => ({ id: uuid(3), method: 'STRUCTURED_XML', status: 'COMPLETED', version: 3, createdAt: when })
const page = (number = 0) => ({ items: [summary()], total: 1, page: number, pageSize: 20 })
const detail = () => ({ ...summary(), input: input(), startedAt: when, completedAt: when, failure: null, review: null, canConfirm: true,
  suggestion: { method: 'STRUCTURED_XML', providerId: 'local-xml', processorVersion: 'einvoice-0.31-v1', contractVersion: 'invoice-extraction-v1', proposals: [
    { field: 'INVOICE_NUMBER', value: '0000123', confidence: 'HIGH', evidence: [{ originalId: uuid(2), originalDigest: 'a'.repeat(64), page: 1, quote: '0000123', xmlPath: '/EInvoice[1]/InvoiceNumber[1]' }] },
    { field: 'GROSS_AMOUNT', value: '100.00', confidence: 'HIGH', evidence: [{ originalId: uuid(2), originalDigest: 'a'.repeat(64), page: 1, quote: '100.00', xmlPath: '/EInvoice[1]/Amount[1]' }] }
  ] } })
const queued = () => ({ ...detail(), status: 'QUEUED', version: 1, startedAt: null, completedAt: null, suggestion: null, canConfirm: false })
const reads = () => { api.invoiceExtractionInput = async () => options(); api.invoiceExtractionRuns = async (_, number) => page(number); api.invoiceExtractionRun = async () => detail() }
const unreadable = error => error.code === 'RESPONSE_UNREADABLE'
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
let index = 0
function panel(extra = {}) {
  const props = reactive({ scopeKey: 'demo/extraction-' + ++index, item: item(), refreshVersion: 0, locked: false, ...extra }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, { ...props, onBusy: v => events.push(['busy', v]), onDirty: v => events.push(['dirty', v]) })
  return { props, events, state: app.mount({}).$.setupState, close: () => app.unmount() }
}

test('票夹提供来源、历史、详情、发起和人工复核入口', () => {
  for (const name of ['invoiceExtractionInput', 'invoiceExtractionRuns', 'invoiceExtractionRun', 'generateInvoiceExtraction', 'reviewInvoiceExtraction']) assert.equal(typeof api[name], 'function', name)
})

test('来源目录区分本地与完整外发，拒绝伪造票据身份及矛盾确认范围', () => {
  for (const value of [options(), options('MODEL'), options('MODEL', 'PDF')]) assert.deepEqual(extraction.readExtractionOptions(value, uuid(1)), value)
  assert.equal(extraction.extractionMatches(input(), item()), true)
  assert.equal(extraction.extractionMatches(input(), { ...item(), original: { ...item().original, sha256: 'f'.repeat(64) } }), false)
  const disabled = { ...options('MODEL'), enabled: false, unavailableCode: 'AGENT_MODEL_DISABLED', providerId: null, model: null, destination: null, targetDigest: null }
  assert.deepEqual(extraction.readExtractionOptions(disabled, uuid(1)), disabled)
  for (const change of [v => { v.input.invoiceId = uuid(9) }, v => { v.input.pageCount = 2 }, v => { v.transmission = 'ORIGINAL_BYTES' }, v => { v.model = 'implicit-model' }, v => { v.supportedFormats = ['PDF'] }]) {
    const value = options(); change(value); assert.throws(() => extraction.readExtractionOptions(value, uuid(1)), unreadable)
  }
  assert.throws(() => extraction.readExtractionOptions({ ...options('MODEL'), targetDigest: null }, uuid(1)), unreadable)
  assert.throws(() => extraction.readExtractionOptions({ ...disabled, destination: 'hidden-target' }, uuid(1)), unreadable)
})

test('历史分页和详情绑定原请求，空识别合法，伪造证据或已确认值拒绝', () => {
  assert.deepEqual(extraction.readExtractionPage(page(), 0), page())
  assert.deepEqual(extraction.readExtractionDetail(detail(), uuid(1), uuid(3)), detail())
  assert.deepEqual(extraction.readExtractionDetail(queued(), uuid(1), uuid(3)), queued())
  const empty = detail(); empty.suggestion.proposals = []; assert.deepEqual(extraction.readExtractionDetail(empty, uuid(1), uuid(3)), empty)
  assert.throws(() => extraction.readExtractionPage(page(1), 0), unreadable)
  assert.throws(() => extraction.readExtractionPage({ ...page(), items: [summary(), summary()] }, 0), unreadable)
  for (const change of [v => { v.id = uuid(9) }, v => { v.status = '__proto__' }, v => { v.version = 2 }, v => { v.input.invoiceId = uuid(8) },
    v => { v.suggestion.proposals[0].value = 123 }, v => { v.suggestion.proposals[0].field = 'APPROVED' }, v => { v.suggestion.proposals[0].evidence[0].originalDigest = 'f'.repeat(64) },
    v => { v.suggestion.proposals[0].evidence[0].page = 2 }, v => { v.suggestion.proposals[0].evidence[0].xmlPath = null }, v => { v.suggestion.method = 'MODEL' },
    v => { v.status = 'CONFIRMED'; v.version = 4; v.canConfirm = false; v.review = { actor: 'alice', at: when, selected: [{ field: 'CURRENCY', value: 'CNY' }] } }]) {
    const value = detail(); change(value); assert.throws(() => extraction.readExtractionDetail(value, uuid(1), uuid(3)), unreadable)
  }
})

test('本人选择保留前导零、精确大额和负数，不补造未识别字段或修正算术', () => {
  const value = detail(), selected = ['INVOICE_NUMBER', 'GROSS_AMOUNT'], values = { INVOICE_NUMBER: '0000987', GROSS_AMOUNT: '-9007199254740993.02' }
  assert.deepEqual(extraction.extractionSelections(value, selected, values), [{ field: 'INVOICE_NUMBER', value: '0000987' }, { field: 'GROSS_AMOUNT', value: '-9007199254740993.02' }])
  assert.equal(value.suggestion.proposals[0].value, '0000123')
  for (const [field, bad] of [['GROSS_AMOUNT', 100], ['GROSS_AMOUNT', '1e9'], ['GROSS_AMOUNT', '1.001'], ['GROSS_AMOUNT', '00.01'], ['INVOICE_NUMBER', '12A'], ['BUYER_NAME', ' a'], ['ISSUE_DATE', '2026-02-30']]) assert.equal(extraction.validExtractionValue(field, bad), false)
  assert.equal(extraction.validExtractionValue('ISSUE_DATE', '2024-02-29'), true)
  for (const bad of [[], ['INVOICE_NUMBER', 'INVOICE_NUMBER'], ['CURRENCY']]) assert.throws(() => extraction.extractionSelections(value, bad, values))
})

test('初次读取没有人工选择或修订，本地 XML 不要求模型外发确认', async () => {
  reads(); const sent = []; api.generateInvoiceExtraction = async (...args) => { sent.push(args); return { id: uuid(3), status: 'QUEUED', version: 1 } }
  const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(3))
    assert.deepEqual(p.state.selected, []); assert.equal(p.state.dirty, false); assert.equal(p.state.externalSendConfirmed, false)
    assert.equal(p.state.canGenerate, true); await p.state.generate()
    assert.deepEqual(sent, [[uuid(1), { expectedOriginalId: uuid(2), expectedOriginalDigest: 'a'.repeat(64), method: 'STRUCTURED_XML', targetDigest: null, externalSendConfirmed: false }]])
    assert.equal(p.props.item.verification, 'PENDING'); assert.equal(p.props.item.facts, null)
  } finally { p.close() }
})

test('模型默认不授权，刷新目的地取消旧勾选，锁定时不能发送', async () => {
  reads(); api.invoiceExtractionInput = async () => options('MODEL'); const sent = []
  api.generateInvoiceExtraction = async (...args) => { sent.push(args); return { id: uuid(3), status: 'QUEUED', version: 1 } }
  const p = panel()
  try {
    await settle(); await p.state.generate(); assert.equal(sent.length, 0)
    p.state.externalSendConfirmed = true; p.props.locked = true; await p.state.generate(); assert.equal(sent.length, 0)
    p.props.locked = false; await p.state.loadInput(); assert.equal(p.state.externalSendConfirmed, false)
    p.state.externalSendConfirmed = true; await p.state.generate()
    assert.equal(sent[0][1].externalSendConfirmed, true); assert.equal(sent[0][1].targetDigest, 'b'.repeat(64)); assert.equal(p.state.externalSendConfirmed, false)
  } finally { p.close() }
})

test('源目录与当前票据不符时拒绝启用，已有运行不能再次生成', async () => {
  reads(); api.invoiceExtractionInput = async () => ({ ...options(), input: { ...input(), originalDigest: 'e'.repeat(64) } })
  const p = panel()
  try {
    await settle(); assert.equal(p.state.options, null); assert.match(p.state.errors.input, /原件身份/); assert.equal(p.state.canGenerate, false)
    api.invoiceExtractionInput = async () => options(); await p.state.loadInput()
    api.invoiceExtractionRuns = async () => ({ ...page(), items: [{ ...summary(), status: 'RUNNING', version: 2 }] })
    await p.state.loadPage(); assert.equal(p.state.canGenerate, false)
  } finally { p.close() }
})

test('部分确认使用原运行版本，人工值和原值分别显示，查验财务状态保持', async () => {
  reads(); const sent = []; api.reviewInvoiceExtraction = async (...args) => {
    sent.push(args); api.invoiceExtractionRun = async () => ({ ...detail(), status: 'CONFIRMED', version: 4, canConfirm: false, review: { actor: 'alice', at: when, selected: args[2].selected, comment: args[2].comment } })
    return { id: uuid(3), status: 'CONFIRMED', version: 4 }
  }
  const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(3)); p.state.selected = ['INVOICE_NUMBER']; p.state.values.INVOICE_NUMBER = '0000456'; p.state.comment = '已对照原件'
    await p.state.review('CONFIRM')
    assert.deepEqual(sent, [[uuid(1), uuid(3), { expectedRunVersion: 3, action: 'CONFIRM', comment: '已对照原件', selected: [{ field: 'INVOICE_NUMBER', value: '0000456' }] }]])
    assert.equal(p.state.detail.suggestion.proposals[0].value, '0000123'); assert.equal(p.state.detail.review.selected[0].value, '0000456')
    assert.equal(p.state.page.items[0].status, 'CONFIRMED'); assert.equal(p.state.dirty, false); assert.equal(p.props.item.facts, null)
    assert.equal(extraction.extractionDrafts.get(p.props.scopeKey, uuid(1)), null)
  } finally { p.close() }
})

test('空结果和过期原件只能放弃；无选择、错误值及过长意见均不发送确认', async () => {
  reads(); const sent = []; api.reviewInvoiceExtraction = async (...args) => { sent.push(args); return { id: uuid(3), status: 'DISMISSED', version: 4 } }
  const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(3)); await p.state.review('CONFIRM'); assert.match(p.state.error, /逐项勾选/)
    p.state.selected = ['GROSS_AMOUNT']; p.state.values.GROSS_AMOUNT = '1e8'; await p.state.review('CONFIRM'); assert.equal(sent.length, 0)
    p.state.values.GROSS_AMOUNT = '100.00'; p.state.comment = 'x'.repeat(2001); await p.state.review('CONFIRM'); assert.equal(sent.length, 0)
    p.state.discardEdits(); api.invoiceExtractionRun = async () => ({ ...detail(), canConfirm: false, suggestion: { ...detail().suggestion, proposals: [] } })
    await p.state.loadDetail(uuid(3)); await p.state.review('CONFIRM'); assert.equal(sent.length, 0)
    await p.state.review('DISMISS'); assert.deepEqual(sent[0][2], { expectedRunVersion: 3, action: 'DISMISS', comment: '' })
  } finally { p.close() }
})

test('未保存复核阻止翻页和切换，导航返回仍保留本人内存修订', async () => {
  reads(); const p = panel(); let next
  try {
    await settle(); await p.state.loadDetail(uuid(3)); p.state.selected = ['INVOICE_NUMBER']; p.state.values.INVOICE_NUMBER = '0000666'
    await p.state.loadDetail(uuid(4)); await p.state.loadPage(1)
    assert.equal(p.state.selectedId, uuid(3)); assert.equal(p.state.page.page, 0); assert.equal(p.state.canGenerate, false)
    const scopeKey = p.props.scopeKey; p.close(); next = panel({ scopeKey }); await settle(); await settle()
    assert.equal(next.state.detail.id, uuid(3)); assert.equal(next.state.values.INVOICE_NUMBER, '0000666'); assert.deepEqual(next.state.selected, ['INVOICE_NUMBER'])
    assert.equal(extraction.extractionDrafts.get('other', uuid(1)), null)
    next.state.discardEdits(); assert.equal(next.state.values.INVOICE_NUMBER, '0000123'); assert.equal(next.state.dirty, false)
  } finally { next ? next.close() : p.close() }
})

test('刷新版本变化不覆盖本地修订，也不能用新版本提交旧修订', async () => {
  reads(); const sent = []; api.reviewInvoiceExtraction = async (...args) => { sent.push(args) }; const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(3)); p.state.selected = ['INVOICE_NUMBER']; p.state.values.INVOICE_NUMBER = '0000666'
    api.invoiceExtractionRun = async () => ({ ...detail(), status: 'DISMISSED', version: 4, canConfirm: false, review: { actor: 'alice', at: when } })
    p.props.refreshVersion++; await settle(); await settle()
    assert.equal(p.state.values.INVOICE_NUMBER, '0000666'); assert.equal(p.state.staleEdits, true); assert.equal(p.state.editVersion, 3)
    await p.state.review('CONFIRM'); assert.equal(sent.length, 0); p.state.discardEdits(); assert.equal(p.state.staleEdits, false)
  } finally { p.close() }
})

test('账号切换清除显示并中断读取，旧账号迟到响应不能回填', async () => {
  reads(); const pending = []; api.invoiceExtractionInput = (_, signal) => new Promise(resolve => pending.push({ signal, resolve }))
  const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(3)); p.state.selected = ['INVOICE_NUMBER']; const previousScope = p.props.scopeKey
    p.props.scopeKey = 'other/' + ++index; assert.equal(p.state.detail, null); assert.equal(p.state.options, null); assert.equal(pending[0].signal.aborted, true)
    pending[0].resolve(options()); await settle(); assert.equal(p.state.options, null)
    pending[1].resolve(options()); await settle(); assert.deepEqual(p.state.selected, [])
    assert.equal(extraction.extractionDrafts.get(previousScope, uuid(1)).selected[0], 'INVOICE_NUMBER')
    extraction.extractionDrafts.clear(previousScope, uuid(1))
  } finally { pending.forEach(v => v.resolve(options())); p.close() }
})

test('旧票据写入晚到不打开新票据记录，身份和归属变化不会自动外发', async () => {
  reads(); let resolveWrite; api.generateInvoiceExtraction = () => new Promise(resolve => { resolveWrite = resolve })
  const p = panel()
  try {
    await settle(); const promise = p.state.generate(); await settle()
    p.props.item = { ...item(), id: uuid(4) }; await settle(); resolveWrite({ id: uuid(3), status: 'QUEUED', version: 1 }); await promise
    assert.equal(p.state.selectedId, ''); assert.equal(p.state.detail, null); assert.equal(p.state.notice, ''); assert.equal(p.state.sending, false)
  } finally { p.close() }
})

test('本人读取被拒绝后同时清除可见正文和授权，其他并发读取不能恢复它', async () => {
  reads(); const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(3)); let late
    api.invoiceExtractionInput = () => new Promise(resolve => { late = resolve }); const pending = p.state.loadInput(); await settle()
    api.invoiceExtractionRun = async () => { throw { status: 403, code: 'FORBIDDEN' } }; await p.state.loadDetail(uuid(3))
    assert.equal(p.state.detail, null); assert.equal(p.state.page, null); assert.equal(p.state.canGenerate, false)
    late(options()); await pending; assert.equal(p.state.options, null); assert.match(p.state.errors.detail, /当前账号/)
  } finally { p.close() }
})

test('读取超时释放等待，忽略超时后的结果；成功写入后的历史超时不锁死操作', async () => {
  reads(); const p = panel(), originalTimer = globalThis.setTimeout, originalClear = globalThis.clearTimeout
  let finishList, pending
  try {
    await settle(); const timers = []; globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return timers.length }; globalThis.clearTimeout = () => {}
    api.invoiceExtractionRuns = () => new Promise(resolve => { finishList = resolve }); api.generateInvoiceExtraction = async () => ({ id: uuid(3), status: 'QUEUED', version: 1 })
    pending = p.state.generate(); await settle(); assert.equal(timers[0].delay, 12000); timers[0].callback(); await settle(); await pending
    assert.equal(p.state.sending, false); assert.match(p.state.errors.list, /超时/); assert.equal(p.state.page, null)
    finishList(page()); await settle(); assert.equal(p.state.page, null); assert.equal(p.state.detail.id, uuid(3))
  } finally { finishList?.(page()); await pending; globalThis.setTimeout = originalTimer; globalThis.clearTimeout = originalClear; p.close() }
})

test('实际模板转义模型值、XML 摘录与人工值，展示全文件授权说明', async () => {
  reads(); const value = detail(); value.suggestion.proposals[0].field = 'BUYER_NAME'; value.suggestion.proposals[0].value = '<img src=x onerror=alert(1)>'
  value.suggestion.proposals[0].evidence[0].quote = '<script>alert(2)</script>'; api.invoiceExtractionRun = async () => value
  api.invoiceExtractionInput = async () => options('MODEL', 'PDF')
  const props = { scopeKey: 'demo/render', item: { ...item(), original: { ...item().original, format: 'PDF' } }, refreshVersion: 0, locked: false }
  extraction.rememberExtraction(props.scopeKey, uuid(1), uuid(3))
  const html = await renderToString(createSSRApp({ ...Rendered, async setup(props, ctx) { const render = Rendered.setup(props, ctx); await settle(); await settle(); return render } }, props))
  assert.ok(html.includes('&lt;img')); assert.ok(html.includes('&lt;script&gt;')); assert.ok(!html.includes('<script>'))
  assert.match(html, /完整原文件/); assert.match(html, /内部元数据/); assert.match(html, /原始提取值/); assert.match(html, /确认值/)
  assert.match(html, /查看来源摘录/); assert.match(html, /查验结论仍以正式查验为准/)
})

test('所有读取禁用缓存并绑定票据，错误成功回执保留原键和原正文', async () => {
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'extraction-requests-' + ++index })
  const seen = []; globalThis.fetch = async (url, init) => { seen.push(init); return Response.json(url.endsWith('/input') ? options() : url.includes('?') ? page() : detail()) }
  await api.invoiceExtractionInput(uuid(1)); await api.invoiceExtractionRuns(uuid(1), 0); await api.invoiceExtractionRun(uuid(1), uuid(3))
  assert.ok(seen.every(v => v.cache === 'no-store'))
  const sent = [], receipt = { id: uuid(3), status: 'CONFIRMED', version: 4 }
  globalThis.fetch = async (_, init) => { sent.push(init); return Response.json({ ...receipt, id: sent.length === 1 ? uuid(8) : uuid(3) }) }
  const body = { expectedRunVersion: 3, action: 'CONFIRM', selected: [{ field: 'INVOICE_NUMBER', value: '0000123' }], comment: '' }
  await assert.rejects(api.reviewInvoiceExtraction(uuid(1), uuid(3), body), unreadable); assert.equal(writeRequests.pending().length, 1)
  body.selected[0].value = '0000999'; const recovered = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.deepEqual(recovered.result, receipt); assert.equal(writeRequests.pending().length, 0)
  assert.equal(sent[0].body, sent[1].body); assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
})

test('写入超时不清除原请求，迟到成功不能改写恢复状态，同键恢复完成', async () => {
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'extraction-timeout-' + ++index })
  const originalTimer = globalThis.setTimeout, originalClear = globalThis.clearTimeout, timers = [], sent = []; let finish
  try {
    globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return timers.length }; globalThis.clearTimeout = () => {}
    globalThis.fetch = (_, init) => { sent.push(init); return new Promise(resolve => { finish = resolve }) }
    const body = { expectedOriginalId: uuid(2), expectedOriginalDigest: 'a'.repeat(64), method: 'STRUCTURED_XML', targetDigest: null, externalSendConfirmed: false }
    const call = api.generateInvoiceExtraction(uuid(1), body); const failed = assert.rejects(call, error => error.code === 'REQUEST_TIMEOUT')
    await settle(); assert.equal(timers[0].delay, 25000); timers[0].callback(); await failed
    assert.equal(writeRequests.pending()[0].sending, false); assert.equal(sent[0].signal.aborted, true)
    finish(Response.json({ id: uuid(3), status: 'QUEUED', version: 1 })); await settle(); assert.equal(writeRequests.pending().length, 1)
    globalThis.fetch = async (_, init) => { sent.push(init); return Response.json({ id: uuid(3), status: 'QUEUED', version: 1 }) }
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(sent[0].body, sent[1].body); assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key')); assert.equal(writeRequests.pending().length, 0)
  } finally { globalThis.setTimeout = originalTimer; globalThis.clearTimeout = originalClear }
})

test('成功回执严格匹配动作、版本与原运行，不能用最终状态替代排队回执', () => {
  const path = extraction.extractionPath(uuid(1)), body = JSON.stringify({ expectedRunVersion: 3, action: 'CONFIRM', comment: '' })
  for (const invalid of [{ id: uuid(3), status: 'COMPLETED', version: 3 }, { id: uuid(3), status: 'QUEUED', version: 2 }, { id: 'bad', status: 'QUEUED', version: 1 }]) assert.throws(() => extraction.validateExtractionReceipt(invalid, path, '{}'), unreadable)
  for (const invalid of [{ id: uuid(4), status: 'CONFIRMED', version: 4 }, { id: uuid(3), status: 'DISMISSED', version: 4 }, { id: uuid(3), status: 'CONFIRMED', version: 3 }]) assert.throws(() => extraction.validateExtractionReceipt(invalid, path + '/' + uuid(3) + '/review', body), unreadable)
})

test('全局恢复只刷新原票据记录，不把提取回执当申请或查验成功', async () => {
  const box = value => ({ value }), path = extraction.extractionPath(uuid(1)) + '/' + uuid(3) + '/review', scopeKey = 'demo/recover-extraction'
  const body = { expectedRunVersion: 3, action: 'CONFIRM', selected: [{ field: 'INVOICE_NUMBER', value: '0000123' }], comment: '' }
  const pending = { id: 'request', path, body: JSON.stringify(body), sending: false }
  extraction.extractionDrafts.set(scopeKey, uuid(1), { runId: uuid(3), version: 3, selected: ['INVOICE_NUMBER'], values: { INVOICE_NUMBER: '0000123' }, comment: '' })
  globalThis.document = { querySelector: () => null }
  const env = { pendingWrites: box([pending]), draftScope: box(''), actorScope: box(scopeKey), acknowledgeExtraction: extraction.acknowledgeExtraction,
    confirmReplaceDefinition: async (_, action) => action(), busy: box(false), recoveryError: box(''), notice: box(''), templateRefresh: box(0),
    writeRequests: { recover: async () => ({ request: pending, result: { id: uuid(3), status: 'CONFIRMED', version: 4 } }) }, refreshWorkspace: async () => {}, nextTick, workspace: box(null), errorMessage: error => error.message }
  await createRecovery(env)('request')
  assert.equal(env.recoveryError.value, ''); assert.equal(env.templateRefresh.value, 1); assert.match(env.notice.value, /查验与财务状态保持不变/)
  assert.equal(extraction.focusedExtraction(scopeKey, uuid(1)), uuid(3)); assert.equal(extraction.extractionDrafts.get(scopeKey, uuid(1)), null)
})

test('回执确认不清除其他票据、账号或后续不同的本地修订', () => {
  const scopeKey = 'demo/retain-edits', draft = { runId: uuid(3), version: 3, selected: ['INVOICE_NUMBER'], values: { INVOICE_NUMBER: '0000999' }, comment: '' }
  extraction.extractionDrafts.set(scopeKey, uuid(1), draft); draft.values.INVOICE_NUMBER = 'mutated'
  const path = extraction.extractionPath(uuid(1)) + '/' + uuid(3) + '/review', body = JSON.stringify({ expectedRunVersion: 3, action: 'CONFIRM', selected: [{ field: 'INVOICE_NUMBER', value: '0000123' }], comment: '' })
  extraction.acknowledgeExtraction(scopeKey, path, body, { id: uuid(3), status: 'CONFIRMED', version: 4 })
  assert.equal(extraction.extractionDrafts.get(scopeKey, uuid(1)).values.INVOICE_NUMBER, '0000999')
  assert.equal(extraction.extractionDrafts.get('other', uuid(1)), null); extraction.extractionDrafts.clear(scopeKey, uuid(1))
})

test('发起与复核的原请求恢复后清除旧错误，并打开原记录保留最新结果', async () => {
  for (const action of ['GENERATE', 'CONFIRM']) {
    reads()
    api.generateInvoiceExtraction = async () => { throw new TypeError('Failed to fetch') }
    api.reviewInvoiceExtraction = async () => { throw new TypeError('Failed to fetch') }
    const p = panel()
    try {
      await settle()
      let path = extraction.extractionPath(uuid(1)), body = '{}', receipt = { id: uuid(3), status: 'QUEUED', version: 1 }
      if (action === 'GENERATE') await p.state.generate()
      else {
        await p.state.loadDetail(uuid(3)); p.state.selected = ['INVOICE_NUMBER']; p.state.values.INVOICE_NUMBER = '0000456'
        await p.state.review('CONFIRM')
        path += '/' + uuid(3) + '/review'
        body = JSON.stringify({ expectedRunVersion: 3, action, selected: [{ field: 'INVOICE_NUMBER', value: '0000456' }], comment: '' })
        receipt = { id: uuid(3), status: 'CONFIRMED', version: 4 }
        api.invoiceExtractionRun = async () => ({ ...detail(), ...receipt, canConfirm: false, review: { actor: 'alice', at: when, selected: JSON.parse(body).selected } })
      }
      assert.match(p.state.error, /恢复上次操作/)
      extraction.acknowledgeExtraction(p.props.scopeKey, path, body, receipt)
      p.props.refreshVersion++; await settle(); await settle()
      assert.equal(p.state.error, '', action)
      assert.equal(p.state.detail.id, uuid(3)); assert.equal(p.state.dirty, false)
      assert.equal(p.state.detail.status, action === 'GENERATE' ? 'COMPLETED' : 'CONFIRMED')
      if (action === 'CONFIRM') assert.equal(p.state.detail.review.selected[0].value, '0000456')
    } finally { p.close() }
  }
})

test('票据详情把提取忙碌和未保存修订纳入导航与查验互斥', async () => {
  api.invoice = async () => item(); const events = [], app = renderer.createApp({ ...InvoiceDetail, render: () => null }, { invoiceId: uuid(1), scopeKey: 'detail-extraction', refreshVersion: 0, options: null, locked: false, onBusy: value => events.push(value) })
  const state = app.mount({}).$.setupState
  try {
    await settle(); assert.equal(state.interactionsBusy, false); state.extractionBusy = true; assert.equal(state.interactionsBusy, true)
    state.extractionBusy = false; state.extractionDirty = true; assert.equal(state.interactionsBusy, true); assert.equal(events.at(-1), true)
    state.extractionDirty = false; assert.equal(state.interactionsBusy, false)
  } finally { app.unmount() }
})
