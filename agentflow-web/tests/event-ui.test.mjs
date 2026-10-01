import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_EVENTS)
const { default: Selector } = await import(process.env.AGENTFLOW_TEST_EVENT_DEFINITIONEVENTWAIT)
const { default: WaitPanel } = await import(process.env.AGENTFLOW_TEST_EVENT_EVENTWAITPANEL)
const { default: Contracts } = await import(process.env.AGENTFLOW_TEST_EVENT_EVENTCONTRACTS)
const { default: Inbox } = await import(process.env.AGENTFLOW_TEST_EVENT_EVENTINBOX)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const { loadDesignerNodes, serializeDesignerNodes } = await import(process.env.AGENTFLOW_TEST_DESIGNER_GRAPH)
const { editQuickGraph, projectQuickGraph } = await import(process.env.AGENTFLOW_TEST_QUICK)
const { parsePortableTemplate, serializePortableTemplate } = await import(process.env.AGENTFLOW_TEST_PORTABLE)
const originalApi = { ...api }, originalFetch = globalThis.fetch
globalThis.localStorage = { getItem: () => 'event-test-token', setItem() {}, removeItem() {} }
const settle = () => new Promise(resolve => setImmediate(resolve))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const at = '2026-10-01T00:00:00Z'
const option = (version = 1) => ({ key: 'goods', version, name: '验收完成', sourceKey: 'erp', eventType: 'GoodsAccepted', envelopeVersion: 1, enabled: true, availabilityRevision: 1 })
const publication = () => ({ expectedVersion: 0, name: '验收完成', sourceKey: 'erp', eventType: 'GoodsAccepted', reason: '明确接入验收事件' })
const contract = (version = 1) => { const { enabled, availabilityRevision, ...value } = option(version); return { ...value, publishedBy: 'admin', publishedAt: at, publicationReason: publication().reason, availability: { revision: availabilityRevision, enabled, changedBy: 'admin', changedAt: at, reason: publication().reason } } }
const item = () => ({ id: 'original', eventId: 'evt-original', sourceKey: 'erp', trustRevision: 1, eventType: 'GoodsAccepted', applicationId: 'application', roundNo: 1, waitId: 'wait-original', contractKey: 'goods', contractVersion: 1, version: 2, status: 'REVIEW_REQUIRED', reason: 'SOURCE_CHANGED', receivedAt: at, updatedAt: at, failures: 0 })
const retryInput = () => ({ expectedVersion: 2, reason: '原来源已核对' })
const receipt = () => ({ ...item(), version: 3, status: 'RECEIVED', reason: undefined, nextAttemptAt: at, requestedBy: 'admin', requestReason: retryInput().reason })
const waitView = () => ({ applicationId: 'application', roundNo: 1, applicationVersion: 3, items: [{ waitId: 'wait-original', nodeId: 'wait', nodeName: '事件等待', contractKey: 'goods', contractVersion: 1, createdAt: at, suspended: false, contractEnabled: true }] })
const props = () => reactive({ scopeKey: 'demo:admin', refreshVersion: 0, locked: false })
function mount(Component, values, handlers = {}) {
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(values, context), render: () => null }, { ...values, ...handlers })
  const instance = app.mount({})
  return { state: instance.$.setupState, close: () => { app.unmount(); Object.assign(api, originalApi) } }
}
function stubCatalog() {
  api.eventContracts = async () => ({ items: [option()], nextAfterKey: null })
  api.eventContractVersions = async () => ({ items: [option()], nextBeforeVersion: null })
  api.eventContract = async (_key, version) => contract(version)
  api.eventContractHistory = async () => ({ items: [contract().availability], nextBeforeRevision: null })
}
function stubInbox() {
  api.eventInbox = async () => ({ items: [item()] })
  api.eventInboxItem = async () => item()
  api.eventInboxHistory = async () => ({ items: [item()] })
}

test('事件引用在画布、快速模式和模板中保持确切版本，新节点不自动选择最新版', () => {
  const graph = { nodes: [{ id: 'start', name: '开始', type: 'START', properties: {} }, { id: 'wait', name: '事件等待', type: 'EVENT_WAIT', properties: { eventContractKey: 'goods', eventContractVersion: '7' } }, { id: 'review', name: '人工审批', type: 'USER_TASK', properties: { assigneeRule: 'user:finance' } }, { id: 'end', name: '结束', type: 'END', properties: {} }], edges: ['start>wait', 'wait>review', 'review>end'].map((pair, i) => ({ id: 'e' + i, source: pair.split('>')[0], target: pair.split('>')[1], condition: '', defaultBranch: false })) }
  const loaded = loadDesignerNodes(graph.nodes); assert.deepEqual(serializeDesignerNodes(loaded), graph.nodes)
  loaded[1].eventContractVersion = '01'; assert.equal(serializeDesignerNodes(loaded)[1].properties.eventContractVersion, '01')
  loaded[1].eventContractKey = undefined; loaded[1].eventContractVersion = undefined; assert.deepEqual(serializeDesignerNodes(loaded)[1].properties, {})
  assert.ok(projectQuickGraph(graph).sequence)
  let id = 0; const inserted = editQuickGraph(graph, { kind: 'insert', edgeId: 'e0', type: 'EVENT_WAIT' }, () => 'new' + ++id)
  assert.equal(inserted.nodes.filter(node => node.type === 'EVENT_WAIT').length, 2)
  assert.deepEqual(inserted.nodes.find(node => node.type === 'EVENT_WAIT' && node.id !== 'wait').properties, {})
  const moved = editQuickGraph(graph, { kind: 'swapTasks', firstId: 'wait', secondId: 'review' })
  assert.equal(moved.nodes.find(node => node.id === 'wait').properties.eventContractVersion, '7')
  assert.deepEqual(parsePortableTemplate(serializePortableTemplate({ key: 'events', name: '验收', graph, formSchema: null })).graph, graph)
})

test('选择器读取与刷新不改变已存引用，停用版本不可选择，确切版本由明确点选提交', async () => {
  const updates = [], values = reactive({ modelValue: { key: 'goods', version: '1' }, scopeKey: 'demo:admin', disabled: false })
  api.eventContractOptions = async () => ({ items: [option(3)], nextAfterKey: null })
  api.eventContractOption = async (_key, version) => ({ ...option(version), enabled: false })
  api.eventContractOptionVersions = async () => ({ items: [option(3), { ...option(2), enabled: false }, option(1)], nextBeforeVersion: null })
  const panel = mount(Selector, values, { 'onUpdate:modelValue': value => updates.push(value) })
  try {
    await settle(); assert.deepEqual(updates, []); assert.equal(panel.state.current.value.version, 1); assert.equal(panel.state.current.value.enabled, false)
    panel.state.browse('goods'); await settle(); panel.state.choose({ ...option(2), enabled: false }); assert.deepEqual(updates, [])
    panel.state.choose(option(3)); assert.deepEqual(updates, [{ key: 'goods', version: '3' }])
    values.disabled = true; panel.state.clear(); panel.state.choose(option(1)); assert.equal(updates.length, 1)
    values.disabled = false; panel.state.clear(); assert.deepEqual(updates[1], {})
  } finally { panel.close() }
})

test('取消旧事件读取后，迟到的成功或失败不能恢复旧账号数据', async () => {
  let finish, signal
  const query = new rules.EventRead(); const pending = query.load('old', value => { signal = value; return new Promise(resolve => { finish = resolve }) })
  query.clear(); assert.equal(signal.aborted, true)
  await query.load('new', async () => 'new-data'); finish('old-data'); await pending
  assert.equal(query.value, 'new-data'); assert.equal(query.error, '')
  let reject; const failed = query.load('old', () => new Promise((_resolve, failure) => { reject = failure }))
  query.clear(); reject(new Error('old-secret')); await failed; assert.equal(query.value, null); assert.equal(query.error, '')
})

test('实际等待绑定申请与轮次，重复身份、错误版本和无效启停状态都拒绝展示', () => {
  assert.deepEqual(rules.readEventWaits(waitView(), 'application', 1), waitView())
  for (const update of [v => { v.applicationId = 'other' }, v => { v.roundNo = 2 }, v => { v.items.push(v.items[0]) }, v => { v.items[0].contractVersion = 0 }, v => { v.items[0].suspended = 'false' }]) {
    const value = waitView(); update(value); assert.throws(() => rules.readEventWaits(value, 'application', 1))
  }
})

test('等待刷新发现业务版本前进后通知父页面重新读取，自动加载不产生刷新循环', async () => {
  const values = reactive({ applicationId: 'application', roundNo: 1, version: 3, scopeKey: 'demo:alice' }), changes = []
  api.eventWaits = async () => ({ ...waitView(), applicationVersion: 4, items: [] })
  const panel = mount(WaitPanel, values, { onChanged: () => changes.push(true) })
  try { await settle(); assert.deepEqual(changes, []); await panel.state.load(true); assert.equal(changes.length, 1) } finally { panel.close() }
})

test('目录按业务键翻页，版本与启停历史独立降序，错误游标不进入页面', () => {
  rules.readEventDirectory({ items: [option()], nextAfterKey: 'goods' })
  assert.throws(() => rules.readEventDirectory({ items: [option()], nextAfterKey: 'other' }))
  assert.throws(() => rules.readEventDirectory({ items: [option()], nextAfterKey: null }, 'goods'))
  rules.readEventVersions({ items: [option(7), option(2)], nextBeforeVersion: 2 }, 'goods')
  assert.throws(() => rules.readEventVersions({ items: [option(2), option(7)], nextBeforeVersion: null }, 'goods'))
  assert.throws(() => rules.readEventVersions({ items: [option()], nextBeforeVersion: null }, 'other'))
  assert.throws(() => rules.readEventContractHistory({ items: [{ ...contract().availability, revision: 5 }], nextBeforeRevision: 6 }))
})

test('发布与启停采用真实约束，拒绝缺省来源、控制字符或同状态操作', () => {
  assert.deepEqual(rules.eventPublication('goods', { ...publication(), name: ' 验收完成 ' }), publication())
  rules.eventPublication('goods', { ...publication(), name: '中'.repeat(200) })
  for (const update of [{ sourceKey: '' }, { name: 'a\nname' }, { name: '中'.repeat(201) }, { eventType: '${expression}' }, { reason: '' }, { expectedVersion: -1 }]) assert.throws(() => rules.eventPublication('goods', { ...publication(), ...update }))
  const value = { ...contract(7), availability: { ...contract().availability, revision: 13 } }
  assert.deepEqual(rules.eventAvailabilityInput(value, false, ' 暂停原来源 '), { expectedRevision: 13, enabled: false, reason: '暂停原来源' })
  assert.throws(() => rules.eventAvailabilityInput(value, true, '重复'))
})

test('管理页面只在明确提交后发布，查看旧版再发新版使用最新发布版本', async () => {
  stubCatalog(); const sent = [], values = props()
  api.eventContractVersions = async () => ({ items: [option(7), option(1)], nextBeforeVersion: null })
  api.publishEventContract = async (...args) => { sent.push(args); return contract(8) }
  const panel = mount(Contracts, values)
  try {
    await settle(); await panel.state.preparePublication(option(1)); assert.equal(panel.state.editor.expectedVersion, 7); assert.equal(sent.length, 0)
    panel.state.closeEditor(); await panel.state.publish(); assert.equal(sent.length, 0)
    await panel.state.preparePublication(option(1)); panel.state.editor.reason = '明确新版来源'; values.locked = true; await panel.state.publish(); assert.equal(sent.length, 0)
    values.locked = false; await panel.state.publish(); assert.equal(sent.length, 1); assert.equal(sent[0][1].expectedVersion, 7); assert.equal(sent[0][1].reason, '明确新版来源')
  } finally { panel.close() }
})

test('契约管理员切换账号后，旧发布响应不能在新账号下显示成功或重新打开详情', async () => {
  stubCatalog(); let finish; api.publishEventContract = () => new Promise(resolve => { finish = resolve })
  const values = props(), panel = mount(Contracts, values)
  try {
    await settle(); await panel.state.preparePublication(); panel.state.editor = { key: 'goods', ...publication() }; const pending = panel.state.publish()
    values.scopeKey = 'other:admin'; await settle(); finish(contract()); await pending
    assert.equal(panel.state.editor, null); assert.equal(panel.state.notice, ''); assert.equal(panel.state.detail.value, null)
  } finally { panel.close() }
})

for (const action of ['publish', 'availability']) for (const phase of ['directory', 'versions']) {
  test(`契约 ${action} 写入完成后的 ${phase} 刷新期间换账号，停止旧操作的后续详情读取`, async () => {
    stubCatalog(); const values = props(), panel = mount(Contracts, values)
    let finish, paused = false
    try {
      await settle()
      if (action === 'publish') {
        await panel.state.preparePublication(); panel.state.editor = { key: 'goods', ...publication() }
        api.publishEventContract = async () => contract()
      } else {
        await panel.state.inspect(option()); await settle(); panel.state.prepareAvailability(); panel.state.explanation = '核对后停用'
        api.changeEventAvailability = async () => ({ ...contract(), availability: { ...contract().availability, enabled: false, revision: 2 } })
      }
      const method = phase === 'directory' ? 'eventContracts' : 'eventContractVersions'
      api[method] = () => {
        if (paused) return Promise.resolve({ items: [] })
        paused = true; return new Promise(resolve => { finish = resolve })
      }
      const pending = action === 'publish' ? panel.state.publish() : panel.state.changeAvailability()
      await settle(); assert.equal(typeof finish, 'function')
      values.scopeKey = 'other:admin'; await settle(); finish({ items: [option()] }); await pending
      assert.equal(panel.state.selectedKey, ''); assert.equal(panel.state.detail.value, null); assert.equal(panel.state.notice, '')
    } finally { panel.close() }
  })
}

test('收件恢复只绑定待检查原件，终态不能恢复，历史接受省略的终页游标', () => {
  assert.deepEqual(rules.eventRetryInput(item(), ' 原来源已核对 '), retryInput())
  rules.readEventInboxHistory({ items: [item()] }, 'original')
  rules.readEventInboxPage({ items: [item()] })
  for (const value of ['', 'a'.repeat(501)]) assert.throws(() => rules.eventRetryInput(item(), value))
  assert.throws(() => rules.eventRetryInput({ ...item(), status: 'IGNORED', reason: 'TARGET_STALE' }, '原因'))
  assert.throws(() => rules.readEventInboxHistory({ items: [{ ...item(), id: 'other' }] }, 'original'))
  assert.throws(() => rules.readEventInboxPage({ items: [item(), item()] }))
  assert.throws(() => rules.readEventInboxItem({ ...item(), status: 'CONSUMED', reason: 'SOURCE_CHANGED' }))
})

test('收件页面明确原因后才恢复，取消、外部写锁及非待检查状态都不发送', async () => {
  stubInbox(); const sent = [], values = props(); api.retryEvent = async (...args) => { sent.push(args); return receipt() }
  const panel = mount(Inbox, values)
  try {
    await settle(); await panel.state.inspect('original'); await settle(); panel.state.prepareRetry(); assert.equal(sent.length, 0)
    panel.state.cancel(); await panel.state.retry(); assert.equal(sent.length, 0)
    panel.state.prepareRetry(); await panel.state.retry(); assert.equal(sent.length, 0); assert.ok(panel.state.error)
    panel.state.reason = retryInput().reason; values.locked = true; await panel.state.retry(); assert.equal(sent.length, 0)
    values.locked = false; await panel.state.retry(); assert.deepEqual(sent, [['original', retryInput()]])
  } finally { panel.close() }
})

test('恢复期间切换账号，旧回执不覆盖新详情和通知', async () => {
  stubInbox(); let finish; api.retryEvent = () => new Promise(resolve => { finish = resolve })
  const values = props(), panel = mount(Inbox, values)
  try {
    await settle(); await panel.state.inspect('original'); await settle(); panel.state.prepareRetry(); panel.state.reason = retryInput().reason; const pending = panel.state.retry()
    values.scopeKey = 'other:admin'; await settle(); finish(receipt()); await pending
    assert.equal(panel.state.detail.value, null); assert.equal(panel.state.notice, ''); assert.equal(panel.state.confirmation, false)
  } finally { panel.close() }
})

test('原事件恢复完成后刷新列表期间换账号，不继续打开旧账号的原件', async () => {
  stubInbox(); const values = props(), panel = mount(Inbox, values)
  let finish, paused = false
  try {
    await settle(); await panel.state.inspect('original'); await settle()
    panel.state.prepareRetry(); panel.state.reason = retryInput().reason; api.retryEvent = async () => receipt()
    api.eventInbox = () => {
      if (paused) return Promise.resolve({ items: [] })
      paused = true; return new Promise(resolve => { finish = resolve })
    }
    const pending = panel.state.retry(); await settle(); assert.equal(typeof finish, 'function')
    values.scopeKey = 'other:admin'; await settle(); finish({ items: [item()] }); await pending
    assert.equal(panel.state.detail.value, null); assert.equal(panel.state.notice, '')
  } finally { panel.close() }
})

test('事件查询不缓存，路径和独立历史游标编码；浏览器没有发送外部事件的接口', async () => {
  const calls = []
  try {
    globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); return Response.json({ items: [], nextBeforeVersion: null }) }
    const signal = new AbortController().signal; await api.eventInboxHistory('original/escaped', 7, signal)
    assert.ok(calls[0].url.includes('/integrations/events/original%2Fescaped/history?')); assert.ok(calls[0].url.includes('beforeVersion=7'))
    assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].signal, signal); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
    assert.equal(api.receiveWorkflowEvent, undefined)
  } finally { globalThis.fetch = originalFetch }
})

test('恢复回执串单或版本不对时保留原请求，按原幂等键和正文恢复', async () => {
  const calls = []; writeRequests.setActor({ tenantId: 'demo', userId: 'event-recovery' })
  try {
    globalThis.fetch = async (_url, init) => { calls.push(init); return Response.json({ ...receipt(), ...(calls.length === 1 ? { id: 'different' } : {}) }) }
    await assert.rejects(api.retryEvent('original', retryInput()), error => error.code === 'RESPONSE_UNREADABLE')
    assert.equal(writeRequests.pending().length, 1)
    await assert.rejects(api.retryEvent('original', { ...retryInput(), reason: '改原因' }), error => error.code === 'PENDING_REQUEST_CHANGED')
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].headers.get('Idempotency-Key'), calls[1].headers.get('Idempotency-Key')); assert.equal(writeRequests.pending().length, 0)
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('发布回执必须绑定新正文和相邻版本，启停回执必须保留原发布版本', async () => {
  const path = '/event-contracts/goods/versions'; rules.validateEventMutation(contract(), path, JSON.stringify(publication()))
  for (const value of [{ ...contract(), sourceKey: 'other' }, contract(2), { ...contract(), publicationReason: '另一个原因' }]) assert.throws(() => rules.validateEventMutation(value, path, JSON.stringify(publication())), error => error.code === 'RESPONSE_UNREADABLE')
  const input = { expectedRevision: 13, enabled: false, reason: '核对后停用' }, value = { ...contract(7), availability: { ...contract().availability, revision: 14, enabled: false, reason: input.reason } }
  rules.validateEventMutation(value, '/event-contracts/goods/versions/7/availability', JSON.stringify(input))
  assert.throws(() => rules.validateEventMutation({ ...value, version: 8 }, '/event-contracts/goods/versions/7/availability', JSON.stringify(input)), error => error.code === 'RESPONSE_UNREADABLE')
})
