import test, { beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from '@vue/server-renderer'
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const model = await import(process.env.AGENTFLOW_TEST_ACCOUNT_MAPPINGS)
const drafts = await import(process.env.AGENTFLOW_TEST_ACCOUNT_MAPPING_DRAFTS)
const { default: Manager } = await import(process.env.AGENTFLOW_TEST_ACCOUNTMAPPINGMANAGERPANEL)
const { default: History } = await import(process.env.AGENTFLOW_TEST_ACCOUNTMAPPINGHISTORYPANEL)
const { default: Entries } = await import(process.env.AGENTFLOW_TEST_ACCOUNTMAPPINGENTRIESPANEL)
const { default: Summary } = await import(process.env.AGENTFLOW_TEST_ACCOUNTMAPPINGENTRIESRENDERED)
const { workspaceMenu } = await import(process.env.AGENTFLOW_TEST_WORKSPACE_NAVIGATION)
const originalApi = { ...api }, originalFetch = globalThis.fetch, clone = value => JSON.parse(JSON.stringify(value))
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const scope = { legalEntityId: id(2), currency: 'CNY' }, at = '2026-10-03T10:00:00Z', key = 'company-mapping'
let actor, sequence = 0
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
globalThis.localStorage = { getItem: () => null }
beforeEach(() => { actor = { tenantId: 'mapping-tests-' + sequence++, userId: 'config-admin', roles: ['FINANCE_CONFIG_ADMIN'] }; bindAuthenticationActor(actor) })
afterEach(() => { Object.assign(api, originalApi); globalThis.fetch = originalFetch; bindAuthenticationActor(null) })
function definition() { return { name: '本地合成科目', ...scope, entries: [{ key: { role: 'EXPENSE', selector: 'OFFICE' }, accountCode: 'TEST.EXPENSE' }, { key: { role: 'EMPLOYEE_PAYABLE', selector: '' }, accountCode: 'TEST.PAYABLE' }] } }
function draft(revision = 1) { return { id: id(1), tenantId: actor.tenantId, key, revision, definition: definition(), publishedVersion: 0, publishedDraftRevision: 0 } }
function published() { return { mappingId: id(1), tenantId: actor.tenantId, key, version: 1, draftRevision: 1, categoryRevision: 1, definition: definition(), targetDigest: 'a'.repeat(64), publishedBy: actor.userId, publishedAt: at, comment: '核对发布\n本地样例' } }
function current(active = false) { return { ...scope, categoryRevision: 1, activeRevision: active ? 1 : 0, activeMapping: active ? published() : null } }
function revision() { return { mappingId: id(1), revision: 1, definition: definition(), updatedBy: actor.userId, updatedAt: at, comment: '保存样例' } }
function categories(version = 1) { return { tenantId: actor.tenantId, version, categories: [{ code: 'OFFICE', name: '办公费', units: ['ITEM'], active: true }] } }
function summary() { return { ...scope, id: id(1), key, name: definition().name, revision: 1, publishedVersion: 0, publishedDraftRevision: 0, updatedBy: actor.userId, updatedAt: at } }
const publishInput = () => ({ expectedDraftRevision: 1, expectedCategoryRevision: 1, expectedActiveRevision: 0, comment: published().comment })
function stubReads() {
  api.accountMappings = async () => ({ items: [summary()], nextAfterKey: null })
  api.expenseCategories = async () => categories()
  api.accountMappingDraft = async () => draft()
  api.accountMappingCurrent = async () => current()
}
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
async function mount(component = Manager, extra = {}) {
  const props = reactive({ scopeKey: 'mapping/' + Math.random(), refreshVersion: 0, locked: false, ...extra })
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, props)
  const vm = app.mount({}); await settle()
  return { props, state: vm.$.setupState, close: () => app.unmount() }
}

test('固定范围读取拒绝别租户、别法人、畸形版本与无发布证据的生效指针', () => {
  assert.equal(model.readMappingCurrent(current(), actor, scope).activeMapping, null)
  assert.equal(model.readMappingCurrent(current(true), actor, scope).activeMapping.mappingId, id(1))
  for (const value of [{}, { ...current(), activeMapping: undefined }, { ...current(), activeRevision: 1 },
    { ...current(true), activeRevision: 0 }, { ...current(true), legalEntityId: id(3) },
    { ...current(true), activeMapping: { ...published(), tenantId: 'foreign' } },
    { ...current(true), activeMapping: { ...published(), categoryRevision: 9 } }]) assert.throws(() => model.readMappingCurrent(value, actor, scope))
  assert.throws(() => model.readMappingDraft(draft(), actor, 'another'))
  assert.throws(() => model.readPublishedMapping(published(), actor, key, 2))
})

test('科目定义按服务端顺序规范化，并拒绝重复用途、伪造角色和缺失选择器', () => {
  const source = definition(), normalized = model.readMappingDefinition(source)
  assert.equal(normalized.entries[0].key.role, 'EMPLOYEE_PAYABLE'); assert.equal(source.entries[0].key.role, 'EXPENSE')
  for (const change of [v => v.entries.push(clone(v.entries[0])), v => v.entries[0].key.role = 'toString',
    v => v.entries[0].key.role = ['EXPENSE'], v => v.entries[0].key.selector = '', v => v.entries[1].key.selector = 'x',
    v => v.entries[0].accountCode = ' hidden ', v => v.currency = 'cny']) {
    const invalid = definition(); change(invalid); assert.throws(() => model.readMappingDefinition(invalid))
  }
})

test('非费用映射可以绑定零类别修订，费用映射不能缺少类别来源', () => {
  const value = published(); value.categoryRevision = 0
  assert.throws(() => model.readPublishedMapping(value, actor))
  value.definition.entries = value.definition.entries.filter(e => e.key.role !== 'EXPENSE')
  assert.equal(model.readPublishedMapping(value, actor).categoryRevision, 0)
})

test('目录与历史分页保持单调边界，拒绝跳页与范围混入', () => {
  const wire = { items: [summary()], nextAfterKey: null }
  assert.deepEqual(model.readMappingDirectory(wire, scope), wire)
  assert.throws(() => model.readMappingDirectory(wire, { ...scope, currency: 'USD' }))
  assert.throws(() => model.readMappingDirectory(wire, scope, key))
  assert.throws(() => model.readMappingDirectory({ ...wire, nextAfterKey: key }, scope))
  assert.throws(() => model.readMappingActivations({ items: [], nextBeforeVersion: 3 }))
  const item = { revision: 2, key, mappingId: id(1), mappingVersion: 1, activatedBy: actor.userId, activatedAt: at, comment: '切换' }
  assert.throws(() => model.readMappingActivations({ items: [item], nextBeforeVersion: null }, 2))
})

test('真实接口只传法人与币种查询，无缓存且可以取消', async () => {
  const calls = [], signal = new AbortController().signal
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response(JSON.stringify(current())) }
  await api.accountMappingCurrent(definition(), signal)
  const url = new URL(calls[0].url, 'http://localhost')
  assert.deepEqual([...url.searchParams.keys()].sort(), ['currency', 'legalEntityId']); assert.equal(url.searchParams.get('legalEntityId'), scope.legalEntityId)
  assert.equal(calls[0].options.cache, 'no-store'); assert.equal(calls[0].options.signal, signal)
})

test('草稿畸形成功保留原键，改变正文不能替代原请求', async () => {
  const calls = []; let response = { ...draft(), definition: { ...definition(), currency: 'USD' } }
  globalThis.fetch = async (url, options) => { calls.push({ url, body: options.body, key: options.headers.get('Idempotency-Key') }); return new Response(JSON.stringify(response)) }
  const input = { expectedRevision: 0, definition: definition(), comment: '新建样例' }
  await assert.rejects(api.saveAccountMappingDraft(key, input), e => e.code === 'RESPONSE_UNREADABLE')
  assert.equal(writeRequests.pending().length, 1)
  await assert.rejects(api.saveAccountMappingDraft(key, { ...input, comment: '别的操作' }), e => e.code === 'PENDING_REQUEST_CHANGED')
  response = draft(); await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(writeRequests.pending().length, 0); assert.deepEqual(calls[0], calls[1])
})

test('发布必须核对原草稿正文，后续证据读取失败仍保留原发布键', async () => {
  const calls = []; let historyFails = true, changed = false
  globalThis.fetch = async (url, options) => {
    calls.push({ url, body: options.body, key: options.headers.get('Idempotency-Key') })
    if (url.endsWith('/publish')) return new Response(JSON.stringify(current(true)))
    if (historyFails) return new Response(JSON.stringify({ code: 'ACCESS_DENIED', message: 'denied' }), { status: 403 })
    const original = revision(); if (changed) original.definition.entries[0].accountCode = 'OTHER'
    return new Response(JSON.stringify(original))
  }
  await assert.rejects(api.publishAccountMapping(key, publishInput()), e => e.code === 'RESPONSE_UNREADABLE')
  const pending = writeRequests.pending()[0]; assert.ok(pending)
  historyFails = false; changed = true
  await assert.rejects(writeRequests.recover(pending.id), e => e.code === 'RESPONSE_UNREADABLE')
  changed = false; await writeRequests.recover(pending.id)
  assert.equal(writeRequests.pending().length, 0)
  const posts = calls.filter(c => c.url.endsWith('/publish')); assert.equal(posts.length, 3); assert.deepEqual(posts[0], posts[2])
})

test('发布回执逐一绑定草稿、类别、生效修订和原操作者', () => {
  const path = '/admin/account-mappings/' + key + '/publish', input = JSON.stringify(publishInput())
  assert.equal(model.validateMappingReceipt(current(true), path, input, actor).activeRevision, 1)
  for (const change of [v => v.activeRevision = 2, v => v.categoryRevision = 2, v => v.activeMapping.draftRevision = 2,
    v => v.activeMapping.categoryRevision = 2, v => v.activeMapping.publishedBy = 'other', v => v.activeMapping.comment = 'other']) {
    const value = current(true); change(value); assert.throws(() => model.validateMappingReceipt(value, path, input, actor))
  }
})

test('保存超时后迟到响应不能清理原恢复槽', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] }); let finish
  globalThis.fetch = () => new Promise(resolve => { finish = resolve })
  const pending = api.saveAccountMappingDraft(key, { expectedRevision: 0, definition: definition(), comment: '保存' })
  const rejected = assert.rejects(pending, e => e.code === 'REQUEST_TIMEOUT')
  await Promise.resolve(); t.mock.timers.tick(12_001); await rejected
  finish(new Response(JSON.stringify(draft()))); await settle(); assert.equal(writeRequests.pending().length, 1)
  globalThis.fetch = async () => new Response(JSON.stringify(draft()))
  await writeRequests.recover(writeRequests.pending()[0].id); assert.equal(writeRequests.pending().length, 0)
})

test('草稿按主体隔离，原请求确认不删除发送后的新修改', () => {
  const store = new drafts.MappingDrafts(), value = { key, baseline: null, definition: definition(), comment: '保存' }
  store.put('a', value); assert.equal(store.get('b', ''), null)
  const original = JSON.stringify(drafts.mappingInput(value)); value.definition.name = '后续修改'; store.put('a', value)
  store.acknowledge('a', '/admin/account-mappings/' + key + '/draft', original)
  assert.equal(store.get('a', '').definition.name, '后续修改')
})

test('新建草稿离开页面保留，原请求恢复后转为已保存修订', async () => {
  stubReads(); const panel = await mount(), actorScope = panel.props.scopeKey
  panel.state.newMapping(); panel.state.edit.key = key; panel.state.edit.definition = definition(); panel.state.edit.comment = '保存'
  panel.close(); const restored = await mount(Manager, { scopeKey: actorScope }); restored.state.newMapping()
  assert.equal(restored.state.edit.key, key)
  drafts.mappingDrafts.acknowledge(actorScope, '/admin/account-mappings/' + key + '/draft', JSON.stringify(drafts.mappingInput(restored.state.edit)))
  restored.props.refreshVersion++; await settle(); await settle()
  assert.equal(restored.state.edit.baseline.revision, 1); assert.equal(restored.state.edit.comment, ''); restored.close()
})

test('刷新不把本地修改重套新基线，旧草稿需显式放弃', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectMapping(key)
  panel.state.edit.definition.name = '本地修改'; panel.state.edit.comment = '调整'
  api.accountMappingDraft = async () => draft(2); await panel.state.load()
  assert.equal(panel.state.edit.baseline.revision, 1); assert.equal(panel.state.stale, true)
  api.saveAccountMappingDraft = async () => assert.fail('不能覆盖新版'); await panel.state.saveDraft()
  panel.state.discardChanges(); assert.equal(panel.state.edit.baseline.revision, 2); panel.close()
})

test('发布确认重新读取三个版本，明确确认后才发送', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectMapping(key)
  let sent; api.publishAccountMapping = async (key, body) => { sent = { key, body }; return current(true) }
  await panel.state.preparePublication(); assert.equal(panel.state.review.draft.revision, 1)
  await panel.state.publish(); assert.equal(sent, undefined)
  panel.state.publishComment = published().comment; panel.state.publishConfirmed = true; await panel.state.publish()
  assert.deepEqual(sent, { key, body: publishInput() }); assert.equal(panel.state.review, null); panel.close()
})

test('类别读取跨越更新时不能显示可发布确认', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectMapping(key)
  api.expenseCategories = async () => categories(2)
  await panel.state.preparePublication(); assert.equal(panel.state.review, null); assert.match(panel.state.error, /类别目录刚刚变化/); panel.close()
})

test('停用类别、未保存编辑与已发布修订均拒绝发布', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectMapping(key)
  panel.state.edit.comment = '未保存'; await panel.state.preparePublication(); assert.equal(panel.state.review, null)
  panel.state.discardChanges(); api.expenseCategories = async () => { const value = categories(); value.categories[0].active = false; return value }
  await panel.state.preparePublication(); assert.equal(panel.state.review, null); assert.match(panel.state.error, /启用/)
  api.accountMappingDraft = async () => ({ ...draft(), publishedVersion: 1, publishedDraftRevision: 1 })
  await panel.state.preparePublication(); assert.equal(panel.state.review, null); assert.match(panel.state.error, /已发布/); panel.close()
})

test('切换账号立即清空页面，旧草稿迟到不能恢复内容', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectMapping(key)
  let finish; api.accountMappingDraft = () => new Promise(resolve => { finish = resolve })
  const loading = panel.state.selectMapping(key, true); await settle(); panel.props.scopeKey = ''; await settle()
  assert.equal(panel.state.edit, null); assert.deepEqual(panel.state.directory, [])
  finish(draft()); await loading; assert.equal(panel.state.edit, null); assert.equal(panel.state.current, null); panel.close()
})

test('失去读取权限后清除已展示科目与历史', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectMapping(key)
  api.accountMappingCurrent = async () => { throw { status: 403 } }
  await panel.state.selectMapping(key, true)
  assert.equal(panel.state.denied, true); assert.equal(panel.state.edit, null); assert.equal(panel.state.current, null); assert.deepEqual(panel.state.directory, []); panel.close()
})

test('目录翻页失败保留原游标，重试不跳过结果', async () => {
  stubReads(); api.accountMappings = async () => ({ items: [summary()], nextAfterKey: key })
  const panel = await mount(); const cursors = []
  api.accountMappings = async (_, cursor) => { cursors.push(cursor); throw { status: 503 } }
  await panel.state.loadDirectory(true); assert.equal(panel.state.nextKey, key)
  api.accountMappings = async (_, cursor) => { cursors.push(cursor); return { items: [], nextAfterKey: null } }
  await panel.state.loadDirectory(true); assert.deepEqual(cursors, [key, key]); assert.equal(panel.state.directory.length, 1); panel.close()
})

test('历史详情切换法人后拒绝旧响应，草稿修订读取绑定原配置', async () => {
  api.accountMappingVersions = async () => ({ items: [], nextBeforeVersion: null })
  const panel = await mount(History, { scope, mode: 'versions', mappingKey: key, mappingId: id(1), draftRevision: 3 })
  let finish; api.accountMappingVersion = () => new Promise(resolve => { finish = resolve })
  const opening = panel.state.open({ key, version: 1 }); panel.props.scope = { ...scope, legalEntityId: id(9) }; await settle()
  finish(published()); await opening; assert.equal(panel.state.detail.value, null)
  let sent; api.accountMappingDraftVersion = async (...args) => { sent = args.slice(0, 3); return revision() }
  panel.state.draftNumber = 2; await panel.state.openDraft(); assert.deepEqual(sent, [key, id(1), 2]); panel.close()
})

test('大量明细分页显示，切换用途清空原选择器，只读模式禁止变更', async () => {
  const data = definition(); data.entries = Array.from({ length: 51 }, (_, i) => ({ key: { role: 'EXPENSE', selector: 'C' + i }, accountCode: 'TEST.' + i }))
  const panel = await mount(Entries, { definition: data, disabled: false })
  assert.equal(panel.state.visible.length, 50); panel.state.page = 1; assert.equal(panel.state.visible.length, 1)
  panel.state.remove(0); assert.equal(data.entries.length, 50); await settle(); assert.equal(panel.state.page, 0)
  panel.state.changeRole(data.entries[0]); assert.equal(data.entries[0].key.selector, '')
  panel.props.readonly = true; panel.state.remove(0); panel.state.add(); assert.equal(data.entries.length, 50); panel.close()
})

test('只读科目正文转义输入，入口仅向财务配置角色展示', async () => {
  const data = definition(); data.entries[0].accountCode = '<img src=x onerror=alert(1)>'
  const html = await renderToString(createSSRApp(Summary, { definition: data, readonly: true }))
  assert.ok(html.includes('&lt;img')); assert.ok(!html.includes('<img')); assert.ok(!html.includes('<input'))
  assert.equal(workspaceMenu.flatMap(g => g.items).find(item => item.page === 'account-mappings').access, 'finance-config')
})
