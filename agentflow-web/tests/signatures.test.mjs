import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { APP, OPERATION, DOCUMENT, id, profile, options, source, metadata, receipt, view, input, deferred } from './signature-fixtures.mjs'
const base = pathToFileURL(process.env.AGENTFLOW_TEST_API), model = await import(new URL('./signatures.js', base))
const { api, writeRequests, bindAuthenticationActor } = await import(base)
const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage
const unreadable = error => error.code === 'RESPONSE_UNREADABLE' && error.status === 0
let sequence = 0
function actor() { globalThis.localStorage = { getItem: () => 'synthetic-signature-token' }; bindAuthenticationActor({ tenantId: 'demo', userId: 'signature-' + (++sequence) }) }
afterEach(() => { globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage; bindAuthenticationActor(null) })

test('所有公开状态可读，长版本按文本保持，未知账户字段拒绝渲染', () => {
  for (const status of Object.keys(model.signatureStatuses)) assert.equal(model.readSignatureView(view(status), OPERATION, 1, 'alice').operation.status, status)
  const original = view('PENDING'); original.operation.version = '9223372036854775807'
  assert.equal(model.readSignatureView(original, OPERATION, 1, 'alice').operation.version, original.operation.version)
  for (const value of [0, 1, '0', '01', '-1', '1.5', '9223372036854775808']) assert.equal(model.signatureVersion(value), false)
  assert.equal(model.signatureSourceVersion(Number.MAX_SAFE_INTEGER + 1), null)
  assert.equal(model.signatureSourceVersion(4), '4')
  for (const bad of [{ ...view(), providerSubject: 'private-account' }, { ...view(), roundNo: 2 }, { ...view(), profileVersion: 2 }, { ...view(), canCancel: false }, { ...view(), operation: receipt('QUEUED', id(99)) }]) assert.throws(() => model.readSignatureView(bad, OPERATION, 1, 'alice'), unreadable)
})

test('未完成文件不能宣称可下载，部分结果和错误签署集合拒绝读取', () => {
  for (const bad of [view('SIGNED'), view('COLLECTING'), view('SIGNED'), view('SIGNED')].map((v, i) => {
    if (i === 0) delete v.documents[0].signedBytes
    if (i === 1) v.documents[0].downloadable = true
    if (i === 2) v.documents.push({ ...v.documents[0] })
    if (i === 3) v.documents[0].filename = '../secret'
    return v
  })) assert.throws(() => model.readSignatureView(bad, OPERATION, 1, 'alice'), unreadable)
  assert.throws(() => model.readSignatureView(view(), OPERATION, 1, 'admin'), unreadable)
})

test('资料选项只包含当前公开字段和完整版本，关闭时不冒充可授权', () => {
  assert.equal(model.readSignatureOptions(options()).profiles[0].version, profile().version)
  assert.equal(model.readSignatureOptions({ ...options(), enabled: false, profiles: [] }).enabled, false)
  for (const bad of [{ ...options(), profiles: [] }, { ...options(), profiles: [profile(), profile()] }, { ...options(), token: 'private' }, { ...options(), profiles: [{ ...profile(), version: 2 }] }]) assert.throws(() => model.readSignatureOptions(bad), unreadable)
})

test('权限过滤空页允许继续，游标循环、重复操作和超量页均拒绝', () => {
  assert.equal(model.readSignaturePage({ items: [], nextAfterId: OPERATION }).nextAfterId, OPERATION)
  for (const bad of [{ items: [], nextAfterId: OPERATION }, { items: [receipt(), receipt()] }, { items: Array.from({ length: 26 }, (_, i) => receipt('SIGNED', id(i + 20))) }, { items: [], hidden: 'secret' }]) assert.throws(() => model.readSignaturePage(bad, OPERATION), unreadable)
})

test('仅扫描冻结附件字段和明细附件列，隐藏值与普通文本不能变成原件', async () => {
  const value = source(); value.formSchema.fields.push({ key: 'hidden', type: 'ATTACHMENT', label: '隐藏' }, { key: 'note', type: 'TEXT', label: '文字' }, { key: 'lines', type: 'TABLE', label: '明细', columns: [{ key: 'proof', type: 'ATTACHMENT', label: '附件' }] })
  Object.assign(value.payload, { hidden: '已脱敏', note: [id(8)], other: [id(9)], lines: [{ proof: [id(4)] }] })
  assert.deepEqual(model.signatureReferences(value).map(v => [v.id, v.fieldPath]), [[DOCUMENT, 'contract'], [id(4), 'lines.proof']])
  const selected = await model.signatureChoices(value, async doc => { if (doc === id(4)) throw { status: 403 }; return metadata() }, new AbortController().signal)
  assert.deepEqual(selected.map(v => v.id), [DOCUMENT]); assert.ok(!JSON.stringify(selected).includes(id(4)))
  await assert.rejects(model.signatureChoices(source(), async () => ({ ...metadata(), fieldPath: 'other' }), new AbortController().signal), unreadable)
  await assert.rejects(model.signatureChoices(source(), async () => { throw { status: 503 } }, new AbortController().signal), e => e.status === 503)
})

test('附件权限复查最多并发四个请求，未完成上一批不扩张并发', async () => {
  const value = source(); value.payload.contract = Array.from({ length: 9 }, (_, i) => id(30 + i))
  const jobs = [], signal = new AbortController().signal
  const work = model.signatureChoices(value, document => { const job = deferred(); jobs.push({ ...job, document }); return job.promise }, signal)
  assert.equal(jobs.length, 4)
  const release = async (from, to) => { for (const job of jobs.slice(from, to)) job.resolve(metadata(job.document)); for (let i = 0; i < 8; i++) await Promise.resolve() }
  await release(0, 4); assert.equal(jobs.length, 8); await release(4, 8); assert.equal(jobs.length, 9); await release(8, 9)
  assert.equal((await work).length, 9)
})

test('身份或请求代次变化取消旧读取，迟到正文不能覆盖新记录', async () => {
  const reader = new model.SignatureRead(), old = deferred(), newer = deferred(); let oldSignal
  const first = reader.load('alice', signal => { oldSignal = signal; return old.promise }), second = reader.load('bob', () => newer.promise)
  assert.equal(oldSignal.aborted, true); newer.resolve('current'); await second; old.resolve('private-old'); await first
  assert.equal(reader.value, 'current'); reader.clear(); assert.equal(reader.value, null)
})

test('不响应取消的读取也有总时限，超时后可以重新读取', async () => {
  const reader = new model.SignatureRead(), oldSet = globalThis.setTimeout; let expire
  try { globalThis.setTimeout = fn => { expire = fn; return 123 }; const pending = reader.load('alice', () => new Promise(() => {})); expire(); await pending; assert.match(reader.error, /超时/); assert.equal(reader.loading, false) }
  finally { globalThis.setTimeout = oldSet }
  assert.equal(await reader.load('alice', async () => 'recovered'), 'recovered')
})

test('真实写入响应丢失后只恢复原正文和原键，不以新授权替换', async () => {
  actor(); const calls = []; let failed = true
  globalThis.fetch = async (path, init) => { calls.push({ path, body: init.body, key: init.headers.get('Idempotency-Key') }); if (failed) throw new Error('lost response'); return Response.json(receipt()) }
  await assert.rejects(api.createSignature(APP, input()), e => e.code === 'NETWORK_ERROR')
  const pending = writeRequests.pending()[0]; assert.ok(pending); assert.equal(calls.length, 1)
  await assert.rejects(api.createSignature(APP, { ...input(), purpose: 'other' }), e => e.code === 'PENDING_REQUEST_CHANGED')
  failed = false; await writeRequests.recover(pending.id); assert.equal(calls.length, 2); assert.deepEqual(calls[1], calls[0]); assert.equal(writeRequests.pending().length, 0)
})

test('畸形签署成功回执仍保留待恢复请求，取消回执绑定原编号与文本版本', async () => {
  actor(); globalThis.fetch = async () => Response.json(receipt('SIGNED'))
  await assert.rejects(api.createSignature(APP, input()), unreadable); assert.equal(writeRequests.pending().length, 1)
  globalThis.fetch = async () => Response.json(receipt()); await writeRequests.recover(writeRequests.pending()[0].id)
  const path = model.signaturePath(APP) + '/' + OPERATION + '/cancel', body = JSON.stringify({ expectedVersion: '1' })
  assert.equal(model.validateSignatureReceipt({ id: OPERATION, version: '2', status: 'CANCELLED' }, path, body).version, '2')
  for (const bad of [{ id: id(9), version: '2', status: 'CANCELLED' }, { id: OPERATION, version: 2, status: 'CANCELLED' }, { id: OPERATION, version: '1', status: 'CANCELLED' }]) assert.throws(() => model.validateSignatureReceipt(bad, path, body), unreadable)
})

test('签署操作响应超时保留恢复槽，迟到成功不能清除原请求', async () => {
  actor(); const pending = deferred(), oldSet = globalThis.setTimeout; let expire, signal
  globalThis.fetch = async (_, init) => { signal = init.signal; return pending.promise }
  try {
    globalThis.setTimeout = fn => { expire = fn; return 123 }
    const request = api.createSignature(APP, input()); await Promise.resolve(); await Promise.resolve(); expire()
    await assert.rejects(request, e => e.code === 'REQUEST_TIMEOUT'); assert.equal(signal.aborted, true); assert.equal(writeRequests.pending().length, 1)
    pending.resolve(Response.json(receipt())); await Promise.resolve(); assert.equal(writeRequests.pending().length, 1)
  } finally { globalThis.setTimeout = oldSet }
  globalThis.fetch = async () => Response.json(receipt()); await writeRequests.recover(writeRequests.pending()[0].id)
})

test('读取和下载走本申请本轮次，不产生写请求且拒绝错误二进制类型', async () => {
  actor(); const calls = []
  globalThis.fetch = async (path, init) => { calls.push([path, init]); return Response.json({ items: [], nextAfterId: OPERATION }) }
  assert.equal((await api.signaturePage(APP, 2, undefined, new AbortController().signal)).nextAfterId, OPERATION)
  assert.match(calls[0][0], /roundNo=2/); assert.equal(calls[0][1].cache, 'no-store'); assert.equal(calls[0][1].headers.has('Idempotency-Key'), false)
  await assert.rejects(api.downloadSignature(APP, OPERATION, DOCUMENT, new AbortController().signal), unreadable)
})
