import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { InvoiceUploads, invoiceUploads, invoiceFileFormat } = await import(process.env.AGENTFLOW_TEST_INVOICE_WALLET)
const { fileDigest } = await import(process.env.AGENTFLOW_TEST_ATTACHMENTS)
const { default: Uploader } = await import(process.env.AGENTFLOW_TEST_INVOICEUPLOADER)
const { default: Verification } = await import(process.env.AGENTFLOW_TEST_INVOICEVERIFICATION)
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_INVOICEDETAIL)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} }
const settle = () => new Promise(resolve => setImmediate(resolve))
const file = new File(['abc'], '发票.pdf'), sha256 = await fileDigest(file)
const limits = { enabled: true, maxFileBytes: 1024, maxWalletBytes: 4096, maxWalletUploads: 10, formats: ['PDF', 'OFD', 'PNG', 'JPEG'] }
const item = (status = 'READY') => ({ id: 'invoice', version: 1, original: { id: 'original-file', filename: file.name, size: file.size, sha256, format: 'PDF', status }, verification: 'PENDING', occupation: 'AVAILABLE', facts: null })
const options = () => ({ invoiceVersion: 1, enabled: true, destination: 'finance.test', targetDigest: 'a'.repeat(64), confirmedLegalEntityId: null })
const catalog = () => ({ validUntil: new Date(Date.now() + 3600000).toISOString(), legalEntities: [{ id: 'legal', name: '本人法人' }] })
const job = (status = 'QUEUED') => ({ id: 'job', version: 1, status, invoiceVersion: 1, legalEntityId: 'legal', createdAt: '2026-09-28T12:00:00Z' })
let scope = 0
function defaults() {
  api.invoiceWalletOptions = async () => limits; api.invoice = async () => item(); api.reserveInvoice = async () => ({ id: 'invoice' }); api.uploadInvoice = async () => item().original
  api.invoiceVerificationOptions = async () => options(); api.financeCatalog = async () => catalog(); api.invoiceVerification = async () => job()
  api.invoiceVerifications = async () => ({ items: [], nextBeforeId: null })
}
function panel(Component, extra = {}) {
  const props = reactive({ scopeKey: 'invoice-test-' + ++scope, options: limits, locked: false, refreshVersion: 0, ...extra }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onUploaded: id => events.push(id), onChanged: () => events.push('changed') })
  const mounted = app.mount({})
  return { props, events, state: mounted.$.setupState, close(clear = true) { app.unmount(); Object.assign(api, originals); if (clear) invoiceUploads.clear(props.scopeKey) } }
}
function choose(p, value = file) { p.state.selected({ target: { files: [value], value: 'fakepath' } }) }

test('原件登记只接受支持格式和有界文件，内存恢复按账号隔离', () => {
  assert.equal(invoiceFileFormat(file, limits), 'PDF'); assert.equal(invoiceFileFormat(new File(['a'], '原件.JPG'), limits), 'JPEG')
  for (const invalid of [new File([], 'a.pdf'), new File(['abc'], '../a.pdf'), new File(['abc'], 'a.svg'), new File(['a'.repeat(1025)], 'a.pdf')]) assert.throws(() => invoiceFileFormat(invalid, limits))
  const store = new InvoiceUploads(), attempt = { file, key: 'original', registrationSent: false }; store.set('alice', attempt)
  assert.equal(store.get('alice').file, file); assert.equal(store.get('bob'), null); assert.equal(store.hasPending(), true); store.clear('alice'); assert.equal(store.hasPending(), false)
})

test('登记响应丢失后导航返回只重发原键和原正文，不新增原件', async () => {
  defaults(); const keys = [], bodies = []; let count = 0
  api.reserveInvoice = async (input, key) => { keys.push(key); bodies.push(JSON.stringify(input)); if (++count === 1) throw { status: 0 }; return { id: 'invoice' } }
  const p = panel(Uploader); choose(p); const originalScope = p.props.scopeKey
  await p.state.upload(); assert.equal(p.state.attempt.registrationSent, true); p.state.discard(); assert.notEqual(p.state.attempt, null)
  const retainedApi = { ...api }; p.close(false); Object.assign(api, retainedApi)
  const next = panel(Uploader, { scopeKey: originalScope })
  try { await next.state.upload(); assert.equal(keys.length, 2); assert.equal(keys[0], keys[1]); assert.equal(bodies[0], bodies[1]); assert.deepEqual(next.events, ['invoice']); assert.equal(invoiceUploads.get(originalScope), null) }
  finally { next.close() }
})

test('二进制响应未知后读取已就绪原件，不再登记也不重复发送内容', async () => {
  defaults(); let reserves = 0, uploads = 0, ready = false
  api.reserveInvoice = async () => { reserves++; return { id: 'invoice' } }; api.invoice = async () => item(ready ? 'READY' : 'UPLOADING')
  api.uploadInvoice = async () => { uploads++; ready = true; throw { status: 0 } }
  const p = panel(Uploader)
  try { choose(p); await p.state.upload(); assert.equal(p.state.attempt.invoiceId, 'invoice'); await p.state.upload(); assert.equal(reserves, 1); assert.equal(uploads, 1); assert.deepEqual(p.events, ['invoice']) }
  finally { p.close() }
})

test('恢复上传的文件摘要不匹配时不发送字节，允许重新选择原文件', async () => {
  defaults(); let uploads = 0; api.uploadInvoice = async () => { uploads++ }
  const p = panel(Uploader, { restoreId: 'invoice' })
  try { choose(p, new File(['xyz'], '不同.pdf')); await p.state.upload(); assert.equal(uploads, 0); assert.match(p.state.error, /与原登记不同/); p.state.discard(); assert.equal(p.state.attempt, null) }
  finally { p.close() }
})

test('切换账号取消原上传，迟到登记不能给新账号上传或回填', async () => {
  defaults(); let release, signal, uploads = 0
  api.reserveInvoice = (_, __, s) => { signal = s; return new Promise(resolve => { release = resolve }) }; api.uploadInvoice = async () => { uploads++ }
  const p = panel(Uploader), originalScope = p.props.scopeKey
  try {
    choose(p); const pending = p.state.upload(); while (!release) await settle()
    p.props.scopeKey = 'other-account'; await settle(); assert.equal(signal.aborted, true); release({ id: 'invoice' }); await pending
    assert.equal(p.state.attempt, null); assert.equal(uploads, 0); assert.deepEqual(p.events, []); assert.equal(invoiceUploads.get(originalScope).registrationSent, true)
  } finally { p.close(); invoiceUploads.clear(originalScope) }
})

test('上传超时保留请求身份，迟到回执不继续上传', async () => {
  defaults(); const realTimer = globalThis.setTimeout, timers = []; let release, signal, uploads = 0
  globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return 1 }
  api.reserveInvoice = (_, __, s) => { signal = s; return new Promise(resolve => { release = resolve }) }; api.uploadInvoice = async () => { uploads++ }
  const p = panel(Uploader)
  try {
    choose(p); const pending = p.state.upload(); while (!release) await settle(); assert.equal(timers[0].delay, 120000); timers[0].callback()
    assert.equal(signal.aborted, true); assert.equal(p.state.uploading, false); release({ id: 'invoice' }); await pending
    assert.equal(uploads, 0); assert.equal(p.state.attempt.invoiceId, undefined); assert.equal(p.state.attempt.registrationSent, true)
  } finally { globalThis.setTimeout = realTimer; p.close() }
})

test('验票必须选择法人并再次确认，202 仅显示排队，不伪造通过', async () => {
  defaults(); const sent = []; api.queueInvoiceVerification = async (id, input) => { sent.push({ id, input }); return { id: 'job' } }
  const p = panel(Verification, { item: item() })
  try {
    await settle(); await p.state.queue(); p.state.prepare(); assert.equal(p.state.confirm, false); p.state.legalEntityId = 'legal'; await settle()
    await p.state.queue(); assert.equal(sent.length, 0); p.state.prepare(); assert.equal(p.state.confirm, true); await p.state.queue()
    assert.deepEqual(sent, [{ id: 'invoice', input: { expectedInvoiceVersion: 1, legalEntityId: 'legal', targetDigest: 'a'.repeat(64) } }]); assert.equal(p.state.job.status, 'QUEUED'); assert.equal(p.props.item.verification, 'PENDING')
  } finally { p.close() }
})

test('实际排队时重新核对目录有效期和票据版本，缓存确认不会越过边界', async () => {
  defaults(); let sent = 0; api.queueInvoiceVerification = async () => { sent++; return { id: 'job' } }
  const p = panel(Verification, { item: item() })
  try {
    await settle(); p.state.legalEntityId = 'legal'; await settle(); p.state.prepare(); assert.equal(p.state.confirm, true)
    p.state.catalog.validUntil = '2000-01-01'; await p.state.queue(); assert.equal(sent, 0)
    p.state.catalog = catalog(); p.props.item.version = 2; await p.state.queue(); assert.equal(sent, 0)
  } finally { p.close() }
})

test('活动验票指针直接恢复队列，不读取历史第一页猜当前任务', async () => {
  defaults(); const ids = []; let historyCalls = 0
  api.invoiceVerificationOptions = async () => ({ ...options(), activeVerificationId: 'active' }); api.invoiceVerification = async (_, id) => { ids.push(id); return { ...job(), id } }
  api.invoiceVerifications = async () => { historyCalls++; return { items: [], nextBeforeId: null } }
  const p = panel(Verification, { item: item() })
  try { await settle(); assert.deepEqual(ids, ['active']); assert.equal(p.state.active, true); assert.equal(historyCalls, 0); p.state.prepare(); assert.equal(p.state.confirm, false) }
  finally { p.close() }
})

test('终态后的选项刷新迟到时不能回填到另一个账号', async () => {
  defaults(); let calls = 0, release
  api.invoiceVerificationOptions = async () => { if (++calls === 1) return { ...options(), activeVerificationId: 'job' }; if (calls === 2) return new Promise(resolve => { release = resolve }); throw { status: 404 } }
  api.invoiceVerification = async () => job('SUCCEEDED')
  const p = panel(Verification, { item: item() })
  try {
    while (!release) await settle(); p.props.scopeKey = 'another-actor'; await settle(); assert.equal(p.state.options, null)
    release({ ...options(), destination: 'old-private-target' }); await settle(); assert.equal(p.state.options, null); assert.deepEqual(p.events, [])
  } finally { p.close() }
})

test('父组件刷新同一张票面不会清掉刚完成的查验结果', async () => {
  defaults(); api.invoiceVerification = async () => job('SUCCEEDED')
  const p = panel(Verification, { item: item() })
  try {
    await settle(); await p.state.load('job'); assert.equal(p.state.job.status, 'SUCCEEDED')
    p.props.item = { ...item(), version: 2, verification: 'VERIFIED' }; await settle()
    assert.equal(p.state.job?.id, 'job'); assert.equal(p.state.job?.status, 'SUCCEEDED')
  } finally { p.close() }
})

test('当前记录刷新不会遗留正在下载状态，下载字节和摘要正确才保存', async () => {
  defaults(); let release, saves = 0; const originalDocument = globalThis.document
  globalThis.document = { createElement: () => ({ click: () => saves++ }) }; api.downloadInvoice = async () => new Promise(resolve => { release = resolve })
  const p = panel(Detail, { invoiceId: 'invoice' })
  try { await settle(); const pending = p.state.download(); while (!release) await settle(); await p.state.load(); release(file); await pending; assert.equal(p.state.downloading, false); assert.equal(saves, 1) }
  finally { globalThis.document = originalDocument; p.close() }
})

test('等长但摘要错误的原件下载不能保存，失权后清空旧票面', async () => {
  defaults(); let saves = 0; const originalDocument = globalThis.document
  globalThis.document = { createElement: () => ({ click: () => saves++ }) }; api.downloadInvoice = async () => new Blob(['xyz'])
  const p = panel(Detail, { invoiceId: 'invoice' })
  try { await settle(); await p.state.download(); assert.equal(saves, 0); assert.notEqual(p.state.error, ''); api.invoice = async () => { throw { status: 404 } }; await p.state.load(); assert.equal(p.state.item, null) }
  finally { globalThis.document = originalDocument; p.close() }
})

test('JSON 登记、二进制内容和验票请求使用各自真实契约，验票响应未知复用原请求', async () => {
  const originalFetch = globalThis.fetch, calls = []; let fail = true
  bindAuthenticationActor({ tenantId: 'invoice-api', userId: 'alice' })
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); if (url.endsWith('/verifications') && fail) { fail = false; throw new Error('lost') }; return new Response(JSON.stringify({ id: 'invoice' }), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  try {
    const input = { filename: file.name, size: 3, sha256, format: 'PDF' }, verification = { expectedInvoiceVersion: 1, legalEntityId: 'legal', targetDigest: 'a'.repeat(64) }
    await api.reserveInvoice(input, 'original-key', new AbortController().signal); await api.uploadInvoice('invoice', file, new AbortController().signal)
    await assert.rejects(api.queueInvoiceVerification('invoice', verification)); const [pending] = writeRequests.pending(); await writeRequests.recover(pending.id)
    assert.equal(calls[0].init.headers.get('Idempotency-Key'), 'original-key'); assert.deepEqual(JSON.parse(calls[0].init.body), input)
    assert.equal(calls[1].init.body, file); assert.equal(calls[1].init.headers.get('Content-Type'), 'application/octet-stream'); assert.equal(calls[1].init.headers.get('X-Application-Version'), null)
    assert.equal(calls[2].init.body, calls[3].init.body); assert.equal(calls[2].init.headers.get('Idempotency-Key'), calls[3].init.headers.get('Idempotency-Key'))
  } finally { globalThis.fetch = originalFetch }
})
