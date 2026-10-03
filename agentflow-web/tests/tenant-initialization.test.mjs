import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const model = await import(process.env.AGENTFLOW_TEST_INITIALIZATION)
const { default: Wizard } = await import(process.env.AGENTFLOW_TEST_TENANTINITIALIZATIONWIZARD_PANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_TENANTINITIALIZATIONWIZARD_RENDERED)
const { default: Calendar } = await import(process.env.AGENTFLOW_TEST_INITIALIZATIONCALENDAR_PANEL)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const ids = Array.from({ length: 7 }, (_, index) => `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`)
const actor = { tenantId: 'initialization-demo', userId: 'issuer:管理员/一', roles: ['ADMIN', 'APPROVER'] }
const originalApi = { ...api }, originalFetch = globalThis.fetch
const settle = () => new Promise(resolve => setImmediate(resolve))
globalThis.localStorage = { getItem: () => null }
const rules = () => ({ zoneId: 'Asia/Shanghai', weeklyHours: { MONDAY: [{ start: '09:00', end: '18:00' }] }, overrides: [] })
const preferences = () => ({ tenantId: actor.tenantId, recipient: actor.userId, emailEnabled: false, enterpriseImEnabled: false, version: 0, emailGeneration: 0, enterpriseImGeneration: 0 })
const state = () => ({ tenantId: actor.tenantId, currentSubject: actor.userId, currentRoles: actor.roles, organizationRevision: 0, currentAdministratorPerson: null,
  initialization: null, currentNotifications: preferences(), channelBindings: [{ channel: 'EMAIL', configured: false, digest: null }, { channel: 'ENTERPRISE_IM', configured: false, digest: null }] })
const calendar = (revision = 1, id = ids[5]) => ({ id, key: 'work', name: '已确认日历', revision, rules: rules(), updatedBy: actor.userId, updatedAt: '2026-10-01T10:00:00Z' })
const appointment = (revision = 6) => ({ appointmentId: ids[1], personId: ids[2], subject: actor.userId, directoryRevision: revision,
  legalEntityId: ids[3], legalEntityName: '本地法人', departmentId: ids[4], departmentName: '运营部', positionId: ids[6], positionName: '管理员岗位' })
function fill(draft) {
  Object.assign(draft.form, { workspaceName: '验收工作区', legalEntityName: '本地法人', departmentName: '运营部', positionName: '管理员岗位', administratorName: '管理员姓名',
    newCalendar: { key: 'work', name: '已确认日历', rules: rules() } })
  return draft
}
const input = () => model.initializationRequest(fill(model.newInitializationDraft(state())))
const receipt = () => ({ id: ids[0], tenantId: actor.tenantId, workspaceName: '验收工作区', initializedBy: actor.userId, initializedAt: '2026-10-01T11:00:00Z',
  administratorRoles: actor.roles, administratorName: '管理员姓名', organization: appointment(), calendar: { ...calendar(), tenantId: actor.tenantId }, notifications: preferences() })

test('状态读取拒绝缺字段、跨身份、畸形渠道及伪完成；原快照允许由同租户另一管理员创建', () => {
  assert.deepEqual(model.readInitializationState(state(), actor), state())
  for (const bad of [{}, null, [], { ...state(), initialization: undefined }, { ...state(), currentSubject: 'other' }, { ...state(), tenantId: 'other' },
    { ...state(), channelBindings: [] }, { ...state(), channelBindings: [state().channelBindings[0], state().channelBindings[0]] },
    { ...state(), initialization: {} }, { ...state(), currentNotifications: { ...preferences(), emailEnabled: true } }]) {
    assert.throws(() => model.readInitializationState(bad, actor), { code: 'RESPONSE_UNREADABLE' })
  }
  const value = receipt(); value.initializedBy = 'previous-admin'; value.organization.subject = 'previous-admin'; value.notifications.recipient = 'previous-admin'
  assert.equal(model.readInitializationState({ ...state(), initialization: value }, actor).initialization.initializedBy, 'previous-admin')
})

test('成功回执核对原租户、账号、组织、日历规则和通知选择，不完整响应保持未知', () => {
  assert.deepEqual(model.validateInitializationReceipt(receipt(), input(), actor), receipt())
  const mutations = [r => { r.id = '' }, r => { r.initializedBy = 'other' }, r => { r.tenantId = 'other' }, r => { r.calendar.revision = 2 },
    r => { r.organization.departmentName = '错误部门' }, r => { r.calendar.rules.weeklyHours.MONDAY[0].end = '17:00' }, r => { r.notifications.version = 7 },
    r => { r.notifications.emailEnabled = true }, r => { r.administratorRoles = ['EMPLOYEE'] }]
  for (const change of mutations) { const bad = receipt(); change(bad); assert.throws(() => model.validateInitializationReceipt(bad, input(), actor)) }
  const existing = input(); existing.organization = { source: 'EXISTING', appointmentId: ids[1] }; existing.calendar = { source: 'EXISTING', id: ids[5], revision: 1 }
  assert.equal(model.validateInitializationReceipt(receipt(), existing, actor).id, ids[0])
  existing.calendar.revision = 2; assert.throws(() => model.validateInitializationReceipt(receipt(), existing, actor))
})

test('请求只提交所选来源，沿用已有姓名；关闭渠道不夹带摘要，原基线不随当前版本刷新', () => {
  const baseline = state(); baseline.organizationRevision = 6; baseline.currentAdministratorPerson = { id: ids[2], subject: actor.userId, displayName: '现有人名', active: true, approvalEligible: false, revision: 1 }
  const draft = fill(model.newInitializationDraft(baseline)); draft.form.administratorName = '页面不得改名'
  assert.equal(model.initializationRequest(draft).organization.administratorName, '现有人名')
  draft.form.organizationSource = 'EXISTING'; draft.form.appointment = appointment(); draft.form.calendarSource = 'EXISTING'; draft.form.calendar = calendar()
  const value = model.initializationRequest(draft)
  assert.deepEqual(value.organization, { source: 'EXISTING', appointmentId: ids[1] }); assert.deepEqual(value.calendar, { source: 'EXISTING', id: ids[5], revision: 1 })
  assert.equal('tenantId' in value, false); assert.equal('emailBindingDigest' in value.notifications, false)
  assert.equal(model.initializationProblem(draft), '')
  draft.form.emailEnabled = true; assert.match(model.initializationProblem(draft), /尚未绑定/)
  draft.form.emailEnabled = false; draft.form.appointment.directoryRevision = 7; assert.match(model.initializationProblem(draft), /目录有变化/)
  assert.notEqual(model.initializationBaseline(baseline), model.initializationBaseline({ ...baseline, organizationRevision: 7 }))
})

test('草稿按身份深拷贝，只有原请求内容匹配才可清除；未修改的空草稿不触发离开提醒', () => {
  const drafts = new model.InitializationDrafts(), draft = model.newInitializationDraft(state())
  drafts.put('a', draft); assert.equal(drafts.hasDrafts(), false)
  fill(draft); drafts.put('a', draft); assert.equal(drafts.hasDrafts(), true); assert.equal(drafts.get('b'), null)
  const sent = JSON.stringify(model.initializationRequest(draft)); const copy = drafts.get('a'); copy.form.workspaceName = '之后的修改'; drafts.put('a', copy)
  assert.equal(drafts.acknowledge('a', sent), false); assert.equal(drafts.get('a').form.workspaceName, '之后的修改')
  assert.equal(drafts.acknowledge('a', JSON.stringify(model.initializationRequest(copy))), true); assert.equal(drafts.hasDrafts(), false)
})

test('API读取绑定原身份、禁止缓存；网络未知和错误成功保留原键逐字节恢复', async () => {
  bindAuthenticationActor(actor)
  try {
    const calls = [], signal = new AbortController().signal
    globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); return Response.json(state()) }
    await api.tenantInitialization(signal)
    assert.match(calls[0].url, /\/system\/initialization$/); assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, signal)
    assert.equal(calls[0].headers.has('Idempotency-Key'), false)
    for (const first of [{}, new Error('lost response')]) {
      const writes = []
      globalThis.fetch = async (url, init) => { writes.push({ url, ...init }); if (writes.length === 1 && first instanceof Error) throw first; return Response.json(writes.length === 1 ? first : receipt(), { status: 201 }) }
      await assert.rejects(api.initializeTenant(input())); assert.equal(writeRequests.pending().length, 1)
      await writeRequests.recover(writeRequests.pending()[0].id)
      assert.equal(writes[0].body, writes[1].body); assert.equal(writes[0].headers.get('Idempotency-Key'), writes[1].headers.get('Idempotency-Key'))
      assert.equal(writeRequests.pending().length, 0)
    }
  } finally { bindAuthenticationActor(null); globalThis.fetch = originalFetch }
})

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function wizard(read = async () => state(), retain = false) {
  api.tenantInitialization = read
  const props = reactive({ scopeKey: 'scope-a', refreshVersion: 0, locked: false, enterpriseAuth: true })
  const app = renderer.createApp({ ...Wizard, setup: (_props, context) => Wizard.setup(props, context), render: () => null }, props)
  const mounted = app.mount({})
  return { props, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originalApi); if (!retain) for (const scope of ['scope-a', 'scope-b', 'scope-c']) model.initializationDrafts.discard(scope) } }
}

test('读取失败不生成可提交默认值，恢复读取后保留草稿；未确认或全局写锁均不发送', async () => {
  const p = wizard(async () => { throw { message: '状态读取失败' } }); let writes = 0
  api.initializeTenant = async () => { writes++; return receipt() }
  try {
    await settle(); assert.equal(p.state.draft, null); assert.equal(p.state.disabled, true); await p.state.initialize(); assert.equal(writes, 0)
    api.tenantInitialization = async () => state(); await p.state.load(); fill(p.state.draft); p.state.step = 4
    await p.state.initialize(); assert.equal(writes, 0)
    p.state.confirmed = true; p.props.locked = true; await p.state.initialize(); assert.equal(writes, 0)
    p.props.locked = false; api.tenantInitialization = async () => ({ ...state(), initialization: receipt(), organizationRevision: 6 })
    await p.state.initialize(); assert.equal(writes, 1); assert.equal(p.state.receipt.id, ids[0]); assert.equal(p.state.draft, null)
  } finally { p.close() }
})

test('刷新不替换草稿基线，版本或绑定变化需明确重新核对；所有输入变化撤销最终确认', async () => {
  const p = wizard(); let writes = 0
  api.initializeTenant = async () => { writes++; return receipt() }
  try {
    await settle(); fill(p.state.draft); p.state.step = 4; p.state.confirmed = true; p.state.draft.form.positionName = '新岗位'
    assert.equal(p.state.confirmed, false)
    api.tenantInitialization = async () => ({ ...state(), organizationRevision: 8 }); await p.state.load()
    assert.equal(p.state.draft.baseline.organizationRevision, 0); assert.equal(p.state.stale, true)
    p.state.confirmed = true; await p.state.initialize(); assert.equal(writes, 0)
    p.state.adoptLatest(); await settle(); assert.equal(p.state.draft.baseline.organizationRevision, 8); assert.equal(p.state.stale, false)
    assert.equal(p.state.confirmed, false); assert.equal(p.state.step, 1); assert.equal(p.state.draft.form.positionName, '新岗位')
    p.state.step = 4; p.state.confirmed = true; api.initializeTenant = async () => { throw { status: 409, code: 'CONCURRENCY_CONFLICT', message: '版本已变化' } }
    await p.state.initialize(); assert.equal(p.state.requiresReview, true); assert.equal(p.state.confirmed, false); assert.equal(p.state.draft.form.workspaceName, '验收工作区')
  } finally { p.close() }
})

test('离开后返回保留来源与输入；查询超时不开放旧配置，迟到响应不能恢复页面', async t => {
  let p = wizard(async () => state(), true)
  await settle(); fill(p.state.draft); p.close()
  p = wizard(); await settle(); assert.equal(p.state.draft.form.workspaceName, '验收工作区')
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let finish, signal
  api.tenantInitialization = current => { signal = current; return new Promise(resolve => { finish = resolve }) }
  try {
    const reading = p.state.load(); t.mock.timers.tick(12_000); await reading
    assert.equal(signal.aborted, true); assert.equal(p.state.disabled, true); assert.match(p.state.stateQuery.error, /超时/)
    finish(state()); await settle(); assert.equal(p.state.state, null); assert.equal(p.state.draft.form.workspaceName, '验收工作区')
  } finally { p.close() }
})

test('切换身份清除显示；旧身份的迟到读取和写入不覆盖新身份或删除后来的草稿', async () => {
  const reads = [], p = wizard(signal => new Promise(resolve => reads.push({ signal, resolve }))); let complete
  api.initializeTenant = () => new Promise(resolve => { complete = resolve })
  try {
    p.props.scopeKey = 'scope-b'; reads[0].resolve(state()); await settle(); assert.equal(p.state.draft, null)
    reads[1].resolve(state()); await settle(); fill(p.state.draft); p.state.step = 4; p.state.confirmed = true
    const writing = p.state.initialize(); const changed = model.initializationDrafts.get('scope-b'); changed.form.workspaceName = '之后的草稿'; model.initializationDrafts.put('scope-b', changed)
    p.props.scopeKey = 'scope-c'; reads[2].resolve({ ...state(), currentSubject: '另一账号', currentNotifications: { ...preferences(), recipient: '另一账号' } }); await settle()
    complete(receipt()); await writing
    assert.equal(p.state.receipt, null); assert.equal(p.state.draft.form.workspaceName, ''); assert.equal(p.state.saving, false)
    assert.equal(model.initializationDrafts.get('scope-b').form.workspaceName, '之后的草稿')
  } finally { p.close() }
})

test('未知写入保留原草稿；应用恢复入口只清理匹配请求并刷新，不触发第二次初始化', async () => {
  const p = wizard(); api.initializeTenant = async () => { throw { status: 0, message: '结果未知，请恢复原操作' } }
  try {
    await settle(); fill(p.state.draft); p.state.step = 4; p.state.confirmed = true; await p.state.initialize()
    assert.match(p.state.error, /原操作/); assert.equal(p.state.confirmed, false); assert.equal(model.initializationDrafts.hasDrafts(), true)
    const box = value => ({ value }), env = { actorScope: box('scope-a'), initializationDrafts: model.initializationDrafts,
      pendingWrites: box([{ id: 'original', path: '/system/initialization' }]), draftScope: box('scope'),
      confirmReplaceDefinition: async (_label, action) => action(), busy: box(false), recoveryError: box(''), templateRefresh: box(2), notice: box(''),
      writeRequests: { recover: async () => ({ request: { path: '/system/initialization', body: JSON.stringify(input()) }, result: receipt() }) },
      errorMessage: e => e.message, nextTick: async () => {}, refreshWorkspace: async () => {}, workspace: box(null) }
    const oldDocument = globalThis.document; globalThis.document = { querySelector: () => null }
    try { await createRecovery(env)('original') } finally { globalThis.document = oldDocument }
    assert.equal(env.templateRefresh.value, 3); assert.equal(model.initializationDrafts.get('scope-a'), null); assert.match(env.notice.value, /当前设置会重新读取/)
    api.tenantInitialization = async () => ({ ...state(), organizationRevision: 6, initialization: receipt() }); p.props.refreshVersion++; await settle()
    assert.equal(p.state.receipt.id, ids[0]); assert.equal(p.state.draft, null)
    assert.equal(p.state.error, '', '已读到初始化记录后不得继续显示原操作结果未知')
  } finally { p.close() }
})

function calendarPanel() {
  api.calendars = async () => ({ items: [calendar()], nextAfterKey: null })
  api.calendarVersions = async () => ({ items: [calendar(2), calendar(1)], nextBeforeRevision: null })
  const props = reactive({ scopeKey: 'scope-a', source: 'CREATE', created: { key: '', name: '', rules: { zoneId: '', weeklyHours: {}, overrides: [] } }, selected: null, locked: false })
  const changes = []
  const app = renderer.createApp({ ...Calendar, setup: (_props, context) => Calendar.setup(props, context), render: () => null }, {
    ...props, 'onUpdate:selected': value => { changes.push(value); props.selected = value }, 'onUpdate:created': value => { props.created = value }
  })
  const mounted = app.mount({})
  return { props, changes, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originalApi) } }
}

test('日历只采用已读到的精确版本；读取失败清除选择，改变来源或身份后不采用迟到版本', async () => {
  const p = calendarPanel(), reads = []
  api.calendarVersion = (id, revision, signal) => new Promise(resolve => reads.push({ id, revision, signal, resolve }))
  try {
    p.props.source = 'EXISTING'; await settle(); const choosing = p.state.chooseVersion(ids[5], 1)
    reads[0].resolve(calendar(2)); await choosing; assert.equal(p.props.selected, null); assert.match(p.state.detail.error, /响应不完整/)
    const old = p.state.chooseVersion(ids[5], 1); p.props.source = 'CREATE'; reads[1].resolve(calendar(1)); await old; assert.equal(p.props.selected, null)
    p.props.source = 'EXISTING'; await settle(); const latest = p.state.chooseVersion(ids[5], 1); reads[2].resolve(calendar(1)); await latest
    assert.equal(p.props.selected.revision, 1)
    const pending = p.state.chooseVersion(ids[5], 2); p.props.scopeKey = 'scope-b'; reads[3].resolve(calendar(2)); await pending; assert.equal(p.props.selected, null)
  } finally { p.close() }
})

test('日历示例只在主动点击后填入，日期例外与时段编辑保持原规则的其他部分', () => {
  const p = calendarPanel()
  try {
    assert.deepEqual(p.props.created.rules.weeklyHours, {}); p.state.example(); assert.equal(Object.keys(p.props.created.rules.weeklyHours).length, 5)
    p.state.addOverride(); p.state.setOverride(0, { date: '2026-10-01', note: '假日' }); assert.equal(p.props.created.rules.overrides[0].date, '2026-10-01')
    p.state.setWeek('MONDAY', [{ start: '10:00', end: '16:00' }]); assert.equal(p.props.created.rules.overrides.length, 1)
    p.state.removeOverride(0); assert.equal(p.props.created.rules.overrides.length, 0); assert.equal(p.props.created.rules.weeklyHours.MONDAY[0].start, '10:00')
  } finally { p.close() }
})

function renderHost() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null, value: '', addEventListener() {}, removeEventListener() {}, getRootNode: () => ({}), reportValidity: () => true, focus() {} })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  return { host, root: node('root') }
}

test('真实模板提供五步、可信身份及明确最终确认；已完成记录与当前个人通知分开展示', async () => {
  const { host, root } = renderHost(), flatten = el => [el, ...el.children.flatMap(flatten)]
  const oldDocument = globalThis.Document, oldShadow = globalThis.ShadowRoot
  globalThis.Document = class {}; globalThis.ShadowRoot = class {}; api.tenantInitialization = async () => state()
  model.initializationDrafts.put('scope-a', fill(model.newInitializationDraft(state())))
  const app = host.createApp(Rendered, { scopeKey: 'scope-a', refreshVersion: 0, locked: false, enterpriseAuth: true })
  try {
    app.mount(root); await settle()
    const texts = () => flatten(root).map(el => el.text).join(' ')
    assert.match(texts(), /身份与空间/); assert.match(texts(), /组织任职/); assert.match(texts(), /当前租户/)
    assert.equal(flatten(root).filter(el => el.tag === 'input').length, 1)
    const content = el => flatten(el).map(item => item.text).join(' ')
    const reviewStep = flatten(root).find(el => el.tag === 'button' && content(el).includes('确认初始化'))
    await reviewStep.props.onClick(); await settle()
    const submit = flatten(root).find(el => el.tag === 'button' && el.text === '确认并初始化工作区')
    assert.ok(submit); assert.equal(submit.props.disabled, true)
    assert.match(texts(), /09:00—18:00/); assert.match(texts(), /确认启用本地目录/)
    const checkbox = flatten(root).find(el => el.tag === 'input' && el.props.type === 'checkbox')
    checkbox.props['onUpdate:modelValue'](true); await settle(); assert.equal(submit.props.disabled, false)
    app.unmount()
  } finally { globalThis.Document = oldDocument; globalThis.ShadowRoot = oldShadow; Object.assign(api, originalApi); model.initializationDrafts.discard('scope-a') }
  const { host: second, root: completed } = renderHost()
  api.tenantInitialization = async () => ({ ...state(), initialization: receipt() })
  const finished = second.createApp(Rendered, { scopeKey: 'scope-a', refreshVersion: 0, locked: false, enterpriseAuth: true })
  try {
    finished.mount(completed); await settle(); const content = flatten(completed).map(el => el.text).join(' ')
    assert.match(content, /初始化操作者当时的通知选择/); assert.match(content, /你的当前通知选择/); assert.match(content, /继续配置第一条流程/)
    assert.equal(flatten(completed).some(el => el.text === '确认并初始化工作区'), false)
  } finally { finished.unmount(); Object.assign(api, originalApi); model.initializationDrafts.discard('scope-a') }
})
