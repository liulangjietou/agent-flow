import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const { readNotificationPreferences, validateNotificationPreferencesReceipt } = await import(process.env.AGENTFLOW_TEST_NOTIFICATION_PREFERENCES)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_NOTIFICATION_PREFERENCES_PANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_NOTIFICATION_PREFERENCES_RENDERED)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const defaults = () => ({ inAppEnabled: true, emailEnabled: false, enterpriseImEnabled: false, version: 0, updatedAt: null })
const saved = (email = true, im = false, version = 1) => ({ ...defaults(), emailEnabled: email, enterpriseImEnabled: im, version, updatedAt: '2026-10-01T12:00:00Z' })
const originalApi = { ...api }, originalFetch = globalThis.fetch, settle = () => new Promise(resolve => setImmediate(resolve))
globalThis.localStorage = { getItem: () => null }

test('读取拒绝缺字段、关闭站内、伪默认和错误版本，原回执必须匹配两个开关与版本', () => {
  assert.deepEqual(readNotificationPreferences(defaults()), defaults())
  for (const invalid of [{}, null, [], { ...defaults(), inAppEnabled: false }, { ...defaults(), emailEnabled: true },
    { ...saved(), updatedAt: null }, { ...saved(), version: 1.2 }, { ...saved(), recipient: 'other' }]) assert.throws(() => readNotificationPreferences(invalid))
  for (const invalid of [saved(false), saved(true, true), saved(true, false, 8)]) {
    assert.throws(() => validateNotificationPreferencesReceipt(invalid, { emailEnabled: true, enterpriseImEnabled: false, expectedVersion: 0 }))
  }
  assert.deepEqual(validateNotificationPreferencesReceipt(defaults(), { emailEnabled: false, enterpriseImEnabled: false, expectedVersion: 0 }), defaults())
})

test('本人读取不带身份或写入键且不缓存，畸形成功和网络未知保留原键逐字节恢复', async () => {
  const calls = [], controller = new AbortController()
  bindAuthenticationActor({ tenantId: 'demo', userId: 'preference-api' })
  try {
    globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); return Response.json(defaults()) }
    await api.notificationPreferences(controller.signal)
    assert.match(calls[0].url, /\/notifications\/preferences$/); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
    assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, controller.signal)
    const sent = { emailEnabled: true, enterpriseImEnabled: false, expectedVersion: 0 }
    for (const invalid of [{}, { ...saved(), emailEnabled: false }, new Error('lost response')]) {
      const writes = []
      globalThis.fetch = async (url, init) => { writes.push({ url, ...init }); if (writes.length === 1 && invalid instanceof Error) throw invalid; return Response.json(writes.length === 1 ? invalid : saved()) }
      await assert.rejects(api.reviseNotificationPreferences(sent)); assert.equal(writeRequests.pending().length, 1)
      await writeRequests.recover(writeRequests.pending()[0].id)
      assert.equal(writes[0].method, 'PUT'); assert.equal(writes[0].body, writes[1].body)
      assert.equal(writes[0].headers.get('Idempotency-Key'), writes[1].headers.get('Idempotency-Key'))
      assert.equal(writeRequests.pending().length, 0)
    }
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function panel(read = async () => defaults()) {
  api.notificationPreferences = read
  const props = reactive({ scopeKey: 'demo:alice', refreshVersion: 0, locked: false })
  const app = renderer.createApp({ ...Panel, setup: (_props, context) => Panel.setup(props, context), render: () => null }, props)
  const mounted = app.mount({})
  return { state: mounted.$.setupState, props, close() { app.unmount(); Object.assign(api, originalApi) } }
}

test('查询失败没有可写默认值；成功才允许保存，写锁和无变化不发送', async () => {
  const p = panel(async () => { throw { message: '读取失败' } }); let writes = 0
  api.reviseNotificationPreferences = async () => { writes++; return saved() }
  try {
    await settle(); p.state.email = true; await p.state.save(); assert.equal(writes, 0); assert.equal(p.state.disabled, true)
    api.notificationPreferences = async () => defaults(); await p.state.load(); await p.state.save(); assert.equal(writes, 0)
    p.state.email = true; p.props.locked = true; await p.state.save(); assert.equal(writes, 0)
    p.props.locked = false; api.notificationPreferences = async () => saved(); await p.state.save()
    assert.equal(writes, 1); assert.equal(p.state.current.version, 1); assert.equal(p.state.changed, false)
  } finally { p.close() }
})

test('版本冲突保留输入并阻止旧版本重发；明确重新读取后才能重新选择', async () => {
  const p = panel(); let writes = 0
  api.reviseNotificationPreferences = async () => { writes++; throw { code: 'CONCURRENCY_CONFLICT' } }
  try {
    await settle(); p.state.email = true; await p.state.save(); await p.state.save()
    assert.equal(writes, 1); assert.equal(p.state.email, true); assert.equal(p.state.stale, true)
    assert.match(p.state.error, /其他页面更新/)
    api.notificationPreferences = async () => saved(false, true, 2); await p.state.load()
    assert.equal(p.state.email, false); assert.equal(p.state.enterpriseIm, true); assert.equal(p.state.stale, false)
  } finally { p.close() }
})

test('恢复后的读取使用当前设置，历史成功回执不会重新启用后来关闭的渠道', async () => {
  const p = panel(), writes = []
  api.reviseNotificationPreferences = async input => { writes.push(input); return saved() }
  try {
    await settle(); p.state.email = true; api.notificationPreferences = async () => saved(false, false, 2)
    await p.state.save(); assert.equal(p.state.email, false); assert.equal(p.state.current.version, 2)
    assert.deepEqual(writes, [{ emailEnabled: true, enterpriseImEnabled: false, expectedVersion: 0 }])
    assert.match(p.state.notice, /当前设置/)
  } finally { p.close() }
})

test('未知写入保留选择，恢复刷新重新读取；旧身份迟到读取和写入不覆盖新账号', async () => {
  const reads = [], p = panel(signal => new Promise((resolve, reject) => reads.push({ signal, resolve, reject })))
  let finish
  api.reviseNotificationPreferences = () => new Promise(resolve => { finish = resolve })
  try {
    reads[0].resolve(defaults()); await settle(); p.state.email = true; const writing = p.state.save()
    p.props.scopeKey = 'demo:bob'; assert.equal(p.state.email, false)
    reads[1].resolve(saved(false, true)); await settle(); finish(saved()); await writing
    assert.equal(p.state.email, false); assert.equal(p.state.enterpriseIm, true); assert.equal(p.state.notice, '')
    const old = p.state.load(); p.props.scopeKey = 'demo:carol'; assert.equal(reads[2].signal.aborted, true)
    reads[3].resolve(defaults()); await settle(); reads[2].resolve(saved()); await old; assert.equal(p.state.email, false)
    api.reviseNotificationPreferences = async () => { throw { status: 0, message: '恢复原操作' } }
    p.state.email = true; await p.state.save(); assert.equal(p.state.email, true); assert.match(p.state.error, /恢复/)
    api.notificationPreferences = async () => saved(); p.props.refreshVersion++; await settle(); assert.equal(p.state.current.version, 1)
  } finally { p.close() }
})

test('读取超时不继续等待或开放旧设置，迟到成功不能回填', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let finish, signal
  const p = panel(current => { signal = current; return new Promise(resolve => { finish = resolve }) })
  try {
    t.mock.timers.tick(12_000); await settle(); assert.equal(signal.aborted, true); assert.equal(p.state.loading, false)
    assert.equal(p.state.disabled, true); assert.match(p.state.error, /超时/)
    finish(saved()); await settle(); assert.equal(p.state.current, null)
  } finally { p.close() }
})

test('页面原操作恢复刷新通知偏好，不执行审批、不以历史回执代替当前设置', async () => {
  const box = value => ({ value }), env = { pendingWrites: box([{ id: 'original', path: '/notifications/preferences' }]), draftScope: box('scope'),
    confirmReplaceDefinition: async (_label, action) => action(), busy: box(false), recoveryError: box(''), templateRefresh: box(3), notice: box(''),
    writeRequests: { recover: async () => ({ request: { path: '/notifications/preferences' }, result: saved() }) }, errorMessage: e => e.message, nextTick: async () => {}, refreshWorkspace: async () => {}, workspace: box(null) }
  const oldDocument = globalThis.document; globalThis.document = { querySelector: () => null }
  try {
    await createRecovery(env)('original')
    assert.equal(env.templateRefresh.value, 4); assert.match(env.notice.value, /重新读取当前设置/); assert.equal(env.busy.value, false)
  } finally { globalThis.document = oldDocument }
})

test('真实模板提供两个明确开关，站内常开且说明接入与送达条件', async () => {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null, value: '', addEventListener() {}, removeEventListener() {}, getRootNode: () => ({}) })
  const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  const host = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] }, patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (el, parent, anchor = null) => { remove(el); el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null })
  const all = el => [el, ...el.children.flatMap(all)], root = node('root')
  const oldDocument = globalThis.Document, oldShadow = globalThis.ShadowRoot
  globalThis.Document = class {}; globalThis.ShadowRoot = class {}; api.notificationPreferences = async () => defaults()
  const app = host.createApp(Rendered, { scopeKey: 'demo:alice', refreshVersion: 0, locked: false })
  try {
    app.mount(root); await settle()
    assert.equal(all(root).filter(el => el.tag === 'input' && el.props.type === 'checkbox').length, 2)
    const text = all(root).map(el => el.text).join(' ')
    assert.match(text, /站内提醒始终开启/); assert.match(text, /接通并绑定收件账号/); assert.match(text, /不表示消息已经送达/)
    assert.equal(all(root).find(el => el.props.type === 'submit').props.disabled, true)
    api.reviseNotificationPreferences = async () => { throw { status: 0, message: '保存结果未知' } }
    all(root).find(el => el.props.type === 'checkbox').props['onUpdate:modelValue'](true); await settle()
    await all(root).find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} }); await settle()
    assert.match(all(root).find(el => el.tag === 'p' && el.text.includes('尚未保存个人偏好')).text, /^上次读取时：/, '未知写入后不能把旧默认值描述为当前状态')
  } finally { app.unmount(); Object.assign(api, originalApi); globalThis.Document = oldDocument; globalThis.ShadowRoot = oldShadow }
})
