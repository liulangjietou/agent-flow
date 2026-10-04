import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { createRenderer, reactive, nextTick } from 'vue'
import { APP, OPERATION, DOCUMENT, id, options, source, application, metadata, receipt, view, deferred } from './signature-fixtures.mjs'
const base = pathToFileURL(process.env.AGENTFLOW_TEST_API), model = await import(new URL('./signatures.js', base))
const { api, writeRequests, bindAuthenticationActor } = await import(base)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_SIGNATUREPANELPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_SIGNATUREPANELRENDERED)
const { default: Record } = await import(process.env.AGENTFLOW_TEST_APPLICATION_RECORD)
const { default: RenderedRecord } = await import(process.env.AGENTFLOW_TEST_RECORD_RENDERED)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const originals = { ...api }, originalDocument = globalThis.document, originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, originalCreateUrl = URL.createObjectURL, originalRevokeUrl = URL.revokeObjectURL
const mounted = new Set(); let sequence = 0
globalThis.Document ??= class Document {}; globalThis.ShadowRoot ??= class ShadowRoot {}
afterEach(() => { for (const panel of [...mounted]) panel.close(); Object.assign(api, originals); bindAuthenticationActor(null); globalThis.document = originalDocument; globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage; URL.createObjectURL = originalCreateUrl; URL.revokeObjectURL = originalRevokeUrl })
function remove(node) { if (node.parent) { const i = node.parent.children.indexOf(node); if (i >= 0) node.parent.children.splice(i, 1); node.parent = null } }
const renderer = createRenderer({
  createElement: tag => ({ tag, tagName: tag.toUpperCase(), props: {}, children: [], text: '', style: {}, listeners: {}, value: '', focus() {},
    addEventListener(name, listener) { this.listeners[name] = listener }, getRootNode: () => ({}), get options() { return this.children.filter(node => node.tag === 'option') } }),
  createText: text => ({ text, children: [] }), createComment: () => ({ comment: true, children: [] }), insert(node, parent, anchor) { remove(node); parent.children ??= []; const i = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(i < 0 ? parent.children.length : i, 0, node); node.parent = parent },
  remove, parentNode: node => node.parent ?? null, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text; node.children = [] }, patchProp(node, key, previous, value) { node.props[key] = value; if (['value', 'checked', 'type', 'multiple'].includes(key)) node[key] = value }
})
const descendants = node => [node, ...(node.children ?? []).flatMap(descendants)]
const text = node => node.comment ? '' : (node.text ?? '') + (node.children ?? []).map(text).join('')
const button = (panel, label) => descendants(panel.root).find(node => node.tag === 'button' && text(node).trim() === label)
async function settle() { for (let i = 0; i < 25; i++) await Promise.resolve(); await nextTick(); await new Promise(resolve => setImmediate(resolve)) }
function backend() {
  const state = { options: options(), page: { items: [] }, detail: view(), commands: [], downloads: [] }
  api.signatureOptions = async () => structuredClone(state.options); api.signaturePage = async () => structuredClone(state.page)
  api.signatureDetail = async () => structuredClone(state.detail); api.attachment = async () => metadata()
  api.createSignature = async (app, body) => { state.commands.push(['create', app, JSON.parse(JSON.stringify(body))]); state.page = { items: [receipt()] }; state.detail = { ...view(), purpose: body.purpose }; return receipt() }
  api.cancelSignature = async (app, operation, version) => { state.commands.push(['cancel', app, operation, version]); state.detail = view('CANCELLED'); state.detail.operation.version = '2'; state.page = { items: [state.detail.operation] }; return state.detail.operation }
  api.downloadSignature = async (...args) => { state.downloads.push(['signed', ...args.slice(0, 3)]); return new Blob(['signed']) }
  api.downloadAttachment = async (...args) => { state.downloads.push(['original', ...args.slice(0, 3)]); return new Blob(['original']) }
  return state
}
function mount(rendered = false, overrides = {}) {
  const tenant = 'signature-panel-' + (++sequence); bindAuthenticationActor({ tenantId: tenant, userId: 'alice' })
  const props = reactive({ application: application(), rounds: [source()], scopeKey: JSON.stringify([tenant, 'alice']), userId: 'alice', locked: false, ...overrides })
  const root = { children: [] }, events = [], Component = rendered ? Rendered : Panel
  const app = renderer.createApp({ ...Component, setup: (_, ctx) => Component.setup(props, ctx), ...(rendered ? {} : { render: () => null }) }, { ...props, onBusy: value => events.push(['busy', value]), onDirty: value => events.push(['dirty', value]) })
  const panel = { props, root, events, state: app.mount(root).$.setupState, close() { if (mounted.delete(panel)) app.unmount() } }; mounted.add(panel); return panel
}
function fill(panel) { panel.state.profileSelection = JSON.stringify(['contract-seal', '9007199254740993']); panel.state.selectedDocuments = [DOCUMENT]; panel.state.purpose = '签署已批准合同' }

test('真实模板先选原件和资料再明确确认，登记一次后打开原操作而不自动签署', async () => {
  const server = backend(), panel = mount(true); await settle()
  assert.equal(server.commands.length, 0); assert.match(text(panel.root), /合同原件/)
  fill(panel); await panel.state.reviewCreate(); await settle()
  assert.equal(button(panel, '确认授权签署').props.disabled, true); await panel.state.confirm(); assert.equal(server.commands.length, 0)
  panel.state.accepted = true; await panel.state.confirm(); await settle()
  assert.equal(server.commands.length, 1, panel.state.error); const body = server.commands[0][2]
  assert.equal(body.expectedVersion, '4'); assert.equal(body.profileVersion, '9007199254740993'); assert.deepEqual(body.documentIds, [DOCUMENT]); assert.ok(Date.parse(body.validUntil) > Date.now())
  assert.equal(panel.state.detail.operation.id, OPERATION); assert.equal(panel.state.running, false); assert.equal(panel.state.dirty, false)
  assert.match(text(panel.root), /等待发送/); assert.equal(button(panel, '下载签署结果'), undefined)
})

test('真实页面确认经 API 序列化响应式正文，长版本和原件选择完整保留', async () => {
  backend(); api.createSignature = originals.createSignature
  const calls = []; globalThis.localStorage = { getItem: () => 'synthetic-signature-token' }
  globalThis.fetch = async (path, init) => { calls.push({ path, body: JSON.parse(init.body), key: init.headers.get('Idempotency-Key') }); return Response.json(receipt()) }
  const panel = mount(true); await settle(); fill(panel); await panel.state.reviewCreate(); panel.state.accepted = true
  await panel.state.confirm(); await settle()
  assert.equal(calls.length, 1, panel.state.error); assert.ok(calls[0].key); assert.equal(calls[0].body.expectedVersion, '4')
  assert.equal(calls[0].body.profileVersion, '9007199254740993'); assert.deepEqual(calls[0].body.documentIds, [DOCUMENT]); assert.equal(writeRequests.pending().length, 0)
  assert.equal(panel.state.detail.operation.id, OPERATION)
})

test('修改用途、资料或来源使原确认失效，不能继续发送旧意图', async () => {
  const server = backend(), panel = mount(); await settle(); fill(panel); await panel.state.reviewCreate(); panel.state.accepted = true
  panel.state.purpose = '已修改用途'; await panel.state.confirm(); assert.equal(server.commands.length, 0); assert.equal(panel.state.confirmation, null)
  await panel.state.reviewCreate(); panel.state.accepted = true; panel.props.application.version++ ; await settle(); await panel.state.confirm()
  assert.equal(server.commands.length, 0); assert.equal(panel.state.selectedDocuments.length, 0)
})

test('超时未知结果禁止重新创建，成功迟到也不能写入新身份', async () => {
  const server = backend(), pending = deferred(); api.createSignature = async (...args) => { server.commands.push(args); return pending.promise }
  const panel = mount(); await settle(); fill(panel); await panel.state.reviewCreate(); panel.state.accepted = true
  const work = panel.state.confirm(); assert.equal(panel.state.running, true); await panel.state.confirm(); assert.equal(server.commands.length, 1)
  api.signatureOptions = async () => { throw { status: 403 } }; panel.props.scopeKey = JSON.stringify(['other', 'bob']); panel.props.userId = 'bob'; await settle()
  pending.resolve(receipt()); await work; await settle(); assert.equal(panel.state.detail, null); assert.equal(panel.state.reader.value, null); assert.equal(panel.state.notice, ''); assert.equal(panel.state.running, false)
})

test('读取失权后清空旧文件和详情，换账号不会使用旧正文', async () => {
  backend(); const panel = mount(true); await settle(); await panel.state.openOperation(OPERATION); assert.match(text(panel.root), /合同原件/)
  const old = deferred(); let aborted
  api.signatureDetail = (_, __, ___, signal) => { aborted = signal; return old.promise }
  const loading = panel.state.openOperation(OPERATION); api.signatureOptions = async () => { throw { status: 403 } }
  panel.props.scopeKey = JSON.stringify(['other', 'admin']); await settle(); assert.equal(aborted.aborted, true); assert.equal(panel.state.detail, null)
  old.resolve(view()); await loading; await settle(); assert.ok(!text(panel.root).includes('合同原件')); assert.ok(!text(panel.root).includes('签署已批准合同'))
})

test('过滤后的空页仍可翻页，循环游标显示错误且不保留混合结果', async () => {
  const server = backend(), calls = []; api.signaturePage = async (_, __, after) => { calls.push(after); return after ? { items: [receipt('SIGNED', id(9))], nextAfterId: OPERATION } : { items: [], nextAfterId: OPERATION } }
  const panel = mount(true); await settle(); assert.ok(button(panel, '加载更多签署记录'))
  await panel.state.load(true); assert.deepEqual(calls, [undefined, OPERATION]); assert.equal(panel.state.reader.value, null); assert.match(panel.state.reader.error, /重新加载/); assert.equal(server.commands.length, 0)
})

test('取消仅对当前可取消详情确认一次，原版本文本保持不变', async () => {
  const server = backend(), panel = mount(true); await settle(); await panel.state.openOperation(OPERATION); await panel.state.reviewCancel(); await settle()
  assert.equal(button(panel, '确认取消签署').props.disabled, true); await panel.state.confirm(); assert.equal(server.commands.length, 0)
  panel.state.accepted = true; await panel.state.confirm(); assert.deepEqual(server.commands, [['cancel', APP, OPERATION, '1']]); assert.equal(panel.state.detail.canCancel, false)
})

test('结果收集阶段没有签署结果下载入口，完整结果与原件分别下载并清理地址', async () => {
  const server = backend(); server.detail = view('COLLECTING'); const panel = mount(true); await settle(); await panel.state.openOperation(OPERATION); await settle()
  assert.equal(button(panel, '下载签署结果'), undefined); await panel.state.download(DOCUMENT, true); assert.equal(server.downloads.length, 0)
  server.detail = view('SIGNED'); await panel.state.openOperation(OPERATION); await settle(); assert.ok(button(panel, '下载签署结果')); assert.ok(button(panel, '下载原件'))
  const links = [], revoked = []; globalThis.document = { createElement: () => { const a = { click() { links.push({ href: this.href, filename: this.download }) } }; return a } }
  URL.createObjectURL = () => 'blob:signature-test'; URL.revokeObjectURL = value => revoked.push(value)
  await panel.state.download(DOCUMENT, true); await panel.state.download(DOCUMENT, false)
  assert.deepEqual(links.map(v => v.filename), ['signed-合同原件.pdf', '合同原件.pdf']); assert.deepEqual(server.downloads.map(v => v[0]), ['signed', 'original'])
  panel.close(); assert.ok(revoked.includes('blob:signature-test'))
})

test('下载失败、短内容与迟到正文均不产生文件保存动作', async () => {
  const server = backend(); server.detail = view('SIGNED'); const panel = mount(); await settle(); await panel.state.openOperation(OPERATION)
  let created = 0; URL.createObjectURL = () => { created++; return 'blob:unexpected' }; api.downloadSignature = async () => new Blob(['x'])
  await panel.state.download(DOCUMENT, true); assert.match(panel.state.downloadReader.error, /不完整/); assert.equal(created, 0)
  const pending = deferred(); api.downloadSignature = () => pending.promise; const job = panel.state.download(DOCUMENT, true)
  panel.props.scopeKey = 'foreign'; await settle(); pending.resolve(new Blob(['signed'])); await job; assert.equal(created, 0)
})

test('旧轮次、资料停用、不可读原件和不安全申请版本均不能形成新授权', async () => {
  const server = backend(); server.options.enabled = false
  const panel = mount(true); await settle(); fill(panel); await panel.state.reviewCreate(); assert.equal(panel.state.confirmation, null)
  server.options.enabled = true; panel.props.application.version = Number.MAX_SAFE_INTEGER + 1; await settle(); assert.equal(panel.state.canStart, false)
  panel.props.application.version = 4; panel.props.rounds = [source(), source(2)]; panel.props.application.roundNo = 2; await settle(); panel.state.selectedRound = 1; await settle(); assert.equal(panel.state.canStart, false)
  assert.equal(server.commands.length, 0)
})

test('存在未确认操作时可读取记录，但确认和新授权均保持禁用', async () => {
  const server = backend(), panel = mount(true); await settle(); fill(panel); await panel.state.reviewCreate(); panel.state.accepted = true; panel.props.locked = true
  await panel.state.confirm(); await settle(); assert.equal(server.commands.length, 0); assert.equal(button(panel, '确认授权签署').props.disabled, true)
  assert.match(text(panel.root), /恢复上次操作/)
})

test('真实恢复函数记住原操作和轮次，仅刷新同一申请而不顺带再次创建', async () => {
  backend(); const panel = mount(); await settle(); const box = value => ({ value }), request = { id: 'recover-signature', path: model.signaturePath(APP), body: JSON.stringify({ roundNo: 1 }) }
  globalThis.document = { querySelector: () => null }
  const env = { rememberSignatureOperation: model.rememberSignatureOperation, pendingWrites: box([request]), draftScope: box(''), actorScope: box(panel.props.scopeKey),
    confirmReplaceDefinition: async (_, action) => action(), busy: box(false), recoveryError: box(''), notice: box(''), recordApplicationId: box(APP), recordRefresh: box(0),
    writeRequests: { recover: async () => ({ request, result: receipt() }) }, refreshWorkspace: async () => {}, nextTick, workspace: box(null), errorMessage: e => e.message }
  await createRecovery(env)(request.id); assert.equal(env.recoveryError.value, ''); assert.equal(env.recordRefresh.value, 1)
  assert.equal(model.recalledSignatureOperation(panel.props.scopeKey, APP).id, OPERATION); assert.equal(model.recalledSignatureOperation('other', APP), null)
})

test('申请详情关闭遵守签署填写与执行状态，不丢弃未确认授权意图', async () => {
  backend(); globalThis.document = { activeElement: null }
  api.application = async () => application(); api.applicationRounds = async () => [source()]
  const props = reactive({ applicationId: APP, userId: 'alice', scopeKey: 'record-scope', commentRefreshVersion: 0, pendingWrites: [], recoveryError: '' }), events = []
  const root = { children: [] }, app = renderer.createApp({ ...Record, setup: (_, ctx) => Record.setup(props, ctx), render: () => null }, { ...props, onClose: () => events.push('closed') })
  const state = app.mount(root).$.setupState
  try { await settle(); state.signatureDirty = true; state.close(); assert.equal(events.length, 0); state.signatureDirty = false; state.signatureBusy = true; state.close(); assert.equal(events.length, 0); state.signatureBusy = false; state.close(); assert.equal(events.length, 1) }
  finally { app.unmount() }
})


test('真实申请详情向电子签传递原轮次和恢复锁，所有关闭与切换入口遵守填写状态', async () => {
  backend(); globalThis.document = { activeElement: null }
  api.application = async () => application(); api.applicationRounds = async () => [source()]
  const props = reactive({ applicationId: APP, userId: 'alice', scopeKey: 'record-signature-scope', commentRefreshVersion: 0, pendingWrites: [], recoveryError: '' })
  const root = { children: [] }, app = renderer.createApp({ ...RenderedRecord, setup: (_, ctx) => RenderedRecord.setup(props, ctx) }, { ...props })
  const nodes = vnode => !vnode || typeof vnode !== 'object' ? [] : [vnode, ...(Array.isArray(vnode.children) ? vnode.children.flatMap(nodes) : [])]
  const signature = () => nodes(app._instance.subTree).find(node => node.props?.rounds && node.props?.onDirty && node.props?.onBusy)
  try {
    app.mount(root); await settle(); button({ root }, '电子签').props.onClick(); await settle()
    const child = signature(); assert.equal(child.props.application.id, APP); assert.equal(child.props.rounds[0].roundNo, 1); assert.equal(child.props['scope-key'], props.scopeKey); assert.equal(child.props['user-id'], 'alice')
    props.pendingWrites = [{}]; await settle(); assert.equal(signature().props.locked, true)
    signature().props.onDirty(true); await settle()
    assert.equal(button({ root }, '提交轮次').props.disabled, true); assert.equal(button({ root }, '关闭').props.disabled, true)
    const closeIcon = descendants(root).find(node => node.props?.['aria-label'] === '关闭申请详情')
    assert.equal(closeIcon.props.disabled, true, '标题关闭按钮也应显示禁用，不能外观可用但点击无效')
    assert.equal(button({ root }, '放弃修改并关闭').props.disabled, false)
    signature().props.onBusy(true); await settle(); assert.equal(button({ root }, '放弃修改并关闭').props.disabled, true)
  } finally { app.unmount() }
})

test('超过文件数量或总字节上限，以及确认超时，均不会发送授权', async () => {
  const server = backend(), many = source(); many.payload.contract = Array.from({ length: 11 }, (_, i) => id(40 + i))
  api.attachment = async (_, doc) => metadata(doc)
  const panel = mount(false, { rounds: [many] }); await settle(); fill(panel); panel.state.selectedDocuments = many.payload.contract
  await panel.state.reviewCreate(); assert.equal(panel.state.confirmation, null); assert.match(panel.state.error, /最多 10/)
  api.attachment = async (_, doc) => ({ ...metadata(doc), size: 16777216 }); await panel.state.load(); fill(panel); panel.state.selectedDocuments = many.payload.contract.slice(0, 3)
  await panel.state.reviewCreate(); assert.equal(panel.state.confirmation, null); assert.match(panel.state.error, /32 MB/)
  panel.state.selectedDocuments = [many.payload.contract[0]]; panel.state.minutes = 1
  await panel.state.reviewCreate(); panel.state.accepted = true
  const originalNow = Date.now, expired = Date.parse(panel.state.confirmation.body.validUntil) + 1
  try { Date.now = () => expired; await panel.state.confirm() } finally { Date.now = originalNow }
  assert.equal(panel.state.confirmation, null); assert.match(panel.state.error, /期限/); assert.equal(server.commands.length, 0)
})
