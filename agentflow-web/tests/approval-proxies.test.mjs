import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive, nextTick } from 'vue'
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const { approvalProxyPath, approvalProxyDrafts, ApprovalProxyDrafts, emptyProxyForm, proxyCreateInput, proxyLocalTime,
  readApprovalProxy, readApprovalProxyPage, validateApprovalProxyReceipt } = await import(process.env.AGENTFLOW_TEST_APPROVAL_PROXIES)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_APPROVALPROXYMANAGER)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_APPROVALPROXYRENDERED)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const { workspaceMenu } = await import(process.env.AGENTFLOW_TEST_WORKSPACE_NAVIGATION)
const id = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const when = '2026-10-01T12:00:00Z', originalApi = { ...api }, originalFetch = globalThis.fetch
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
globalThis.localStorage = { getItem: () => null }
afterEach(() => { Object.assign(api, originalApi); globalThis.fetch = originalFetch; bindAuthenticationActor(null) })
function view(number = 1, revoked = false) {
  return { proxy: { id: id(number), definitionId: id(20), principalId: id(30), substituteId: id(31), startsAt: when,
    endsAt: '2050-12-01T12:00:00Z', reason: '原审批人休假', createdBy: 'admin', createdAt: when, revision: revoked ? 2 : 1,
    revocation: revoked ? { actor: 'other-admin', reason: '返岗', at: when } : null }, status: revoked ? 'REVOKED' : 'ACTIVE',
    observedAt: when, processKey: 'leave', definitionVersion: 4, definitionName: '请假',
    principal: { id: id(30), subject: 'manager', displayName: '原审批人甲', approvalEligible: true },
    substitute: { id: id(31), subject: 'backup', displayName: '代理人乙', approvalEligible: true } }
}
function page(items = [view()], nextAfterId = null) { return { items, nextAfterId, observedAt: when } }
function validForm() { return { definitionId: id(20), principalId: id(30), substituteId: id(31), startsLocal: '2050-11-01T09:00', endsLocal: '2050-11-02T18:00', reason: '  休假代理  ' } }
function stubReads() {
  api.approvalProxies = async () => page()
  api.approvalProxy = async identity => view(Number(identity.slice(-12)))
  api.organizationPeople = async () => ({ items: [30, 31].map(n => ({ id: id(n), subject: 'person' + n, displayName: '人员' + n, active: true, approvalEligible: true, revision: 1 })), nextAfterId: null })
  api.searchDefinitions = async () => ({ items: [{ id: id(20), key: 'leave', name: '请假', status: 'PUBLISHED', version: 4, revision: 1, startEnabled: false, createdAt: when, updatedAt: when }], nextCursor: null })
}
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
async function panel(scopeKey = 'proxy/' + Math.random()) {
  const props = reactive({ scopeKey, refreshVersion: 0, locked: false })
  const app = renderer.createApp({ ...Panel, setup: (_, context) => Panel.setup(props, context), render: () => null }, props)
  const mounted = app.mount({}); await settle()
  return { props, state: mounted.$.setupState, close: () => app.unmount() }
}

test('公开读取校验编号、双方关系、撤销事实、页尾游标和共同观察时间', () => {
  assert.equal(readApprovalProxy(view(), id(1)).definitionVersion, 4)
  assert.equal(readApprovalProxyPage(page([view()], id(1))).nextAfterId, id(1))
  for (const bad of [{}, { ...view(), principal: view().substitute }, { ...view(), status: 'REVOKED' }, { ...view(), status: 'toString' },
    { ...view(), proxy: { ...view().proxy, revocation: false } }, { ...view(), proxy: { ...view().proxy, revision: 2 } }]) assert.throws(() => readApprovalProxy(bad))
  assert.throws(() => readApprovalProxy(view(), id(2)))
  for (const bad of [page([view(), view()]), page([], id(1)), page([view()], id(2)), { ...page(), observedAt: '2026-10-02T12:00:00Z' }]) assert.throws(() => readApprovalProxyPage(bad))
})

test('真实服务省略空游标和未撤销事实时仍可读取，前端模型归一为 null', () => {
  const wire = view(); delete wire.proxy.revocation
  assert.deepEqual(readApprovalProxyPage({ items: [], observedAt: when }), page([]))
  const result = readApprovalProxyPage({ items: [wire], observedAt: when })
  assert.equal(result.nextAfterId, null); assert.equal(result.items[0].proxy.revocation, null)
  assert.equal(readApprovalProxy(wire, id(1)).proxy.revocation, null)
  assert.throws(() => readApprovalProxy({ ...wire, status: 'REVOKED' }))
})

test('本地期限拒绝无效日期、夏令时空隙、同人、无期限和控制字符原因', () => {
  const previous = process.env.TZ
  try {
    process.env.TZ = 'America/New_York'
    assert.equal(proxyLocalTime('2026-03-08T02:30'), null)
    assert.equal(proxyLocalTime('2030-02-31T10:00'), null)
    assert.equal(proxyLocalTime('0000-01-01T10:00'), null)
    assert.equal(proxyLocalTime('2050-11-01T09:00'), '2050-11-01T13:00:00.000Z')
    const input = proxyCreateInput(validForm())
    assert.equal(input.reason, '休假代理'); assert.match(input.startsAt, /Z$/)
    for (const form of [{ ...validForm(), substituteId: id(30) }, { ...validForm(), endsLocal: '' }, { ...validForm(), endsLocal: '2000-11-01T09:00' },
      { ...validForm(), endsLocal: validForm().startsLocal }, { ...validForm(), reason: '原因\n其他' }]) assert.throws(() => proxyCreateInput(form))
  } finally { if (previous === undefined) delete process.env.TZ; else process.env.TZ = previous }
})

test('创建和撤销损坏回执保留原键，恢复不改变原正文或重复创建', async () => {
  bindAuthenticationActor({ tenantId: 'proxy-wire', userId: 'admin', roles: ['ADMIN'] })
  const sent = []; let result = {}
  globalThis.fetch = async (url, options) => { sent.push({ url, body: options.body, key: options.headers.get('Idempotency-Key') }); return new Response(JSON.stringify(result), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  const input = proxyCreateInput(validForm())
  await assert.rejects(api.createApprovalProxy(input), error => error.code === 'RESPONSE_UNREADABLE')
  assert.equal(writeRequests.pending().length, 1)
  await assert.rejects(api.createApprovalProxy({ ...input, reason: '替换' }), error => error.code === 'PENDING_REQUEST_CHANGED')
  result = { proxyId: id(1), revision: 1 }; await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent[0].key, sent[1].key); assert.equal(sent[0].body, sent[1].body); assert.equal(writeRequests.pending().length, 0)
  result = { proxyId: id(2), revision: 2 }
  await assert.rejects(api.revokeApprovalProxy(id(1), { expectedRevision: 1, reason: '返岗' }), error => error.code === 'RESPONSE_UNREADABLE')
  result = { proxyId: id(1), revision: 2 }; await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent[2].key, sent[3].key); assert.equal(sent[2].body, sent[3].body)
  assert.equal(sent.length, 4); assert.equal(writeRequests.pending().length, 0)
  assert.throws(() => validateApprovalProxyReceipt({ proxyId: id(1), revision: 1, status: 'ACTIVE' }, approvalProxyPath, JSON.stringify(input)))
})

test('列表与原号读取带取消信号和禁止缓存，筛选正确编码，错号响应被拒绝', async () => {
  const reads = [], controller = new AbortController()
  globalThis.fetch = async (url, options) => { reads.push({ url, options }); return new Response(JSON.stringify(reads.length === 1 ? page() : view()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  await api.approvalProxies(id(30), id(1), controller.signal)
  const url = new URL(reads[0].url, 'http://localhost')
  assert.equal(url.searchParams.get('personId'), id(30)); assert.equal(url.searchParams.get('afterId'), id(1)); assert.equal(url.searchParams.get('limit'), '30')
  assert.equal(reads[0].options.signal, controller.signal); assert.equal(reads[0].options.cache, 'no-store')
  await assert.rejects(api.approvalProxy(id(2), controller.signal), error => error.code === 'RESPONSE_UNREADABLE')
})

test('真实组件只在有效选项和明确确认后创建，成功按原号重读当前撤销事实', async () => {
  stubReads(); const p = await panel(); let written
  api.createApprovalProxy = async body => { written = body; return { proxyId: id(1), revision: 1 } }
  api.approvalProxy = async () => view(1, true)
  try {
    p.state.openCreate(); p.state.form = validForm(); await p.state.create(); assert.equal(written, undefined)
    p.state.confirmed = true; p.props.locked = true; await p.state.create(); assert.equal(written, undefined)
    p.props.locked = false; assert.equal(p.state.canCreate, true); await p.state.create()
    assert.deepEqual(written, proxyCreateInput(validForm())); assert.equal(p.state.detail.status, 'REVOKED')
    assert.equal(p.state.detail.proxy.revision, 2); assert.equal(p.state.formOpen, false); assert.deepEqual({ ...p.state.form }, emptyProxyForm())
    assert.equal(approvalProxyDrafts.get(p.props.scopeKey).lastProxyId, id(1)); assert.equal(p.state.canRevoke, false)
  } finally { p.close() }
})

test('成功后读取失败保留原编号，离开再进入只重读，不重新发送创建', async () => {
  stubReads(); const scope = 'proxy-refresh/' + Math.random(), p = await panel(scope); let writes = 0
  api.createApprovalProxy = async () => { writes++; return { proxyId: id(1), revision: 1 } }
  api.approvalProxy = async () => { throw new Error('读取中断') }
  p.state.form = validForm(); p.state.confirmed = true; await p.state.create()
  assert.equal(writes, 1); assert.equal(p.state.selectedId, id(1)); assert.match(p.state.errors.detail, /读取中断/); p.close()
  api.approvalProxy = async () => view(1, true)
  const reopened = await panel(scope)
  try { assert.equal(reopened.state.detail.status, 'REVOKED'); assert.equal(writes, 1) } finally { reopened.close() }
})

test('期限展示把 UTC 偏移和钟点分开，避免 GMT-4 与 21 点连成 GMT-421', async () => {
  const previous = process.env.TZ; process.env.TZ = 'America/New_York'; stubReads(); const p = await panel()
  try { assert.match(p.state.time('2026-10-02T01:00:00Z'), /21:00:00 \(GMT-4\)$/) }
  finally { p.close(); if (previous === undefined) delete process.env.TZ; else process.env.TZ = previous }
})

test('真实 App 恢复分支保存原编号、清除匹配草稿，保留发送后另改的草稿', async () => {
  const drafts = new ApprovalProxyDrafts(), scope = 'proxy-recovery'
  const previousDocument = globalThis.document
  globalThis.document = { querySelector: () => null }
  drafts.put(scope, validForm()); const body = JSON.stringify(proxyCreateInput(validForm()))
  const deps = { approvalProxyDrafts: drafts, actorScope: { value: scope }, pendingWrites: { value: [{ id: 'request', path: approvalProxyPath }] }, draftScope: { value: 'draft' },
    confirmReplaceDefinition: async (_label, action) => action(), busy: { value: false }, recoveryError: { value: '' }, notice: { value: '' }, templateRefresh: { value: 0 },
    writeRequests: { recover: async () => ({ request: { path: approvalProxyPath, body }, result: { proxyId: id(1), revision: 1 } }) }, errorMessage: error => error.message,
    refreshWorkspace: async () => {}, nextTick, workspace: { value: null } }
  try {
    await createRecovery(deps)('request')
    assert.equal(deps.recoveryError.value, ''); assert.equal(deps.templateRefresh.value, 1); assert.equal(drafts.get(scope).lastProxyId, id(1))
    assert.deepEqual(drafts.get(scope).form, emptyProxyForm())
    drafts.put(scope, { ...validForm(), reason: '后来输入的新原因' }); await createRecovery(deps)('request')
    assert.equal(drafts.get(scope).form.reason, '后来输入的新原因'); assert.equal(drafts.get('other').lastProxyId, '')
  } finally { globalThis.document = previousDocument }
})

test('撤销使用已读版本，冲突后旧详情失效，重新读取后需要重新确认', async () => {
  stubReads(); const p = await panel(); let body
  try {
    await p.state.readDetail(id(1)); p.state.revokeReason = '返岗'; p.state.revokeConfirmed = true
    api.revokeApprovalProxy = async (_id, value) => { body = value; throw { status: 409, message: '版本已变化' } }
    await p.state.revoke(); assert.deepEqual(body, { expectedRevision: 1, reason: '返岗' }); assert.equal(p.state.stale, true)
    assert.equal(p.state.canRevoke, false); assert.equal(p.state.revokeReason, '返岗'); assert.equal(p.state.sending, false)
    api.approvalProxy = async () => view(1, true); await p.state.readDetail(id(1))
    assert.equal(p.state.canRevoke, false); assert.equal(p.state.detail.proxy.revocation.actor, 'other-admin')
  } finally { p.close() }
})

test('权限拒绝清空数据和草稿并解除等待，恢复权限后可以显式刷新', async () => {
  stubReads(); const p = await panel()
  try {
    p.state.form = validForm(); p.state.confirmed = true
    api.createApprovalProxy = async () => { throw { status: 403, message: '管理员角色已撤销' } }
    await p.state.create(); assert.equal(p.state.denied, true); assert.equal(p.state.sending, false)
    assert.deepEqual(p.state.rows, []); assert.deepEqual(p.state.people, []); assert.equal(p.state.detail, null)
    assert.deepEqual(approvalProxyDrafts.get(p.props.scopeKey).form, emptyProxyForm())
    p.state.refresh(); await settle(); assert.equal(p.state.denied, false); assert.equal(p.state.rows.length, 1)
  } finally { p.close() }
})

test('分页失败保留旧页和游标，重复游标拒绝，修改未提交筛选不改变下一页', async () => {
  stubReads(); api.approvalProxies = async () => page([view()], id(1)); const p = await panel(); const reads = []
  try {
    p.state.appliedPerson = id(30); p.state.filterPerson = id(31)
    api.approvalProxies = async (person, after) => { reads.push({ person, after }); throw new Error('分页失败') }
    await p.state.loadList(true); assert.equal(p.state.rows.length, 1); assert.equal(p.state.nextId, id(1))
    assert.deepEqual(reads[0], { person: id(30), after: id(1) })
    api.approvalProxies = async () => page([view(2)], id(1)); await p.state.loadList(true)
    assert.equal(p.state.rows.length, 1); assert.match(p.state.errors.list, /不完整/)
    api.approvalProxies = async () => page([view(2)]); await p.state.loadList(true)
    assert.equal(p.state.rows.length, 2); assert.equal(p.state.nextId, null)
  } finally { p.close() }
})

test('真实列表超时即使底层忽略取消也解除等待，迟到页不能补进列表', async () => {
  stubReads(); api.approvalProxies = async () => page([view()], id(1)); const p = await panel()
  const originalTimer = globalThis.setTimeout; let callback, signal, release
  try {
    api.approvalProxies = (_person, _after, abort) => { signal = abort; return new Promise(resolve => { release = resolve }) }
    globalThis.setTimeout = (fn, ms) => { assert.equal(ms, 12000); callback = fn; return 999 }
    const wait = p.state.loadList(true); callback(); await wait
    assert.equal(signal.aborted, true); assert.equal(p.state.loading.list, false); assert.equal(p.state.nextId, id(1)); assert.equal(p.state.rows.length, 1)
    release(page([view(2)])); await settle(); assert.equal(p.state.rows.length, 1)
  } finally { globalThis.setTimeout = originalTimer; p.close() }
})

test('账号切换清空旧选项、详情和确认，旧响应不能覆盖新身份', async () => {
  stubReads(); const p = await panel(); let release, oldSignal
  try {
    p.state.form = validForm(); p.state.confirmed = true
    api.approvalProxy = (_id, signal) => { oldSignal = signal; return new Promise(resolve => { release = resolve }) }
    const old = p.state.readDetail(id(1)); api.approvalProxies = async () => page([])
    api.organizationPeople = async () => ({ items: [], nextAfterId: null }); api.searchDefinitions = async () => ({ items: [], nextCursor: null })
    p.props.scopeKey = 'other-admin'; await settle()
    release(view()); await old
    assert.equal(oldSignal.aborted, true); assert.equal(p.state.detail, null); assert.equal(p.state.selectedId, '')
    assert.deepEqual(p.state.people, []); assert.deepEqual(p.state.rows, []); assert.equal(p.state.confirmed, false); assert.deepEqual({ ...p.state.form }, emptyProxyForm())
  } finally { p.close() }
})

test('人员选项和发布版本可继续翻页，后续页使用已提交的流程搜索', async () => {
  stubReads(); api.organizationPeople = async () => ({ items: [], nextAfterId: id(29) })
  api.searchDefinitions = async () => ({ items: [], nextCursor: 'first' }); const p = await panel(); const reads = []
  try {
    api.organizationPeople = async after => { assert.equal(after, id(29)); return { items: [{ id: id(31), subject: 'backup', displayName: '后来载入', active: true, approvalEligible: true }], nextAfterId: null } }
    await p.state.morePeople(); assert.equal(p.state.eligiblePeople.length, 1)
    api.searchDefinitions = async filter => { reads.push(filter); return { items: [], nextCursor: reads.length === 1 ? 'second' : null } }
    p.state.definitionSearch = ' 请假 '; await p.state.searchDefinitions(); p.state.definitionSearch = '尚未提交的新搜索'; await p.state.searchDefinitions(true)
    assert.equal(reads[1].q, '请假'); assert.equal(reads[1].cursor, 'second'); assert.equal(reads[1].status, 'PUBLISHED')
  } finally { p.close() }
})

test('实际模板展示本地时区与明确确认，管理导航只向管理员开放', async () => {
  stubReads(); const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null, addEventListener() {}, removeEventListener() {},
    get options() { return this.children.filter(value => value.tag === 'option') } })
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value },
    insert(el, parent, anchor = null) { if (el.parent) el.parent.children = el.parent.children.filter(item => item !== el); el.parent = parent; const at = parent.children.indexOf(anchor); parent.children.splice(at < 0 ? parent.children.length : at, 0, el) },
    remove(el) { if (el.parent) el.parent.children = el.parent.children.filter(item => item !== el) }, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], root = node('root'), oldDocument = globalThis.Document, oldShadow = globalThis.ShadowRoot
  globalThis.Document = class {}; globalThis.ShadowRoot = class {}
  const app = host.createApp(Rendered, { scopeKey: 'render-proxy', refreshVersion: 0, locked: false })
  try {
    app.mount(root); await settle()
    await all(root).find(el => el.tag === 'button' && el.text === '新建代理').props.onClick(); await settle()
    assert.equal(all(root).filter(el => el.tag === 'input' && el.props.type === 'datetime-local').length, 2)
    const content = all(root).map(el => el.text).join(' ')
    assert.match(content, /本次有效期/); assert.match(content, /确认创建这份代理/); assert.match(content, /已停用新发起/)
    assert.equal(all(root).find(el => el.props.type === 'submit' && el.text === '确认创建代理').props.disabled, true)
    assert.equal(workspaceMenu.flatMap(group => group.items).find(item => item.page === 'proxies').access, 'inspect')
  } finally { app.unmount(); globalThis.Document = oldDocument; globalThis.ShadowRoot = oldShadow }
})
