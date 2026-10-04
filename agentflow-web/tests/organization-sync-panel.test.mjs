import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { createRenderer, reactive, nextTick } from 'vue'
import { uuid, local, overview, detail, summary, page, savedPlan, planSummary, history, sourceKey } from './organization-sync-fixtures.mjs'
const base = pathToFileURL(process.env.AGENTFLOW_TEST_API)
const model = await import(new URL('./organizationSync.js', base))
const organization = await import(new URL('./organization.js', base))
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_ORGANIZATIONSYNCHRONIZATIONPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_ORGANIZATIONSYNCHRONIZATIONRENDERED)
const { default: Directory } = await import(process.env.AGENTFLOW_TEST_ORGANIZATION_PANEL)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const { api, bindAuthenticationActor } = await import(base)
const originals = { ...api }, originalDocument = globalThis.document
globalThis.Document ??= class Document {}
globalThis.ShadowRoot ??= class ShadowRoot {}
const mounted = new Set(), deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
afterEach(() => { for (const p of [...mounted]) p.close(); Object.assign(api, originals); bindAuthenticationActor(null); globalThis.document = originalDocument })
function removeNode(node) { if (node.parent) { const i = node.parent.children.indexOf(node); if (i >= 0) node.parent.children.splice(i, 1); node.parent = null } }
const renderer = createRenderer({
  createElement: tag => ({ tag, tagName: tag.toUpperCase(), children: [], props: {}, text: '', style: {}, listeners: {}, value: '',
    addEventListener(name, listener) { this.listeners[name] = listener }, getRootNode: () => ({}),
    get options() { return this.children.filter(node => node.tag === 'option') } }), createText: text => ({ text, children: [] }), createComment: () => ({ comment: true, children: [] }),
  insert(node, parent, anchor) { if (node.parent) removeNode(node); parent.children ??= []; const i = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(i < 0 ? parent.children.length : i, 0, node); node.parent = parent },
  remove: removeNode,
  parentNode: node => node.parent ?? null, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text; node.children = [] }, patchProp(node, key, _, value) { node.props[key] = value; if (['value', 'checked', 'type', 'multiple'].includes(key)) node[key] = value }
})
const text = node => node.comment ? '' : (node.text ?? '') + (node.children ?? []).map(text).join('')
function descendants(node) { return [node, ...(node.children ?? []).flatMap(descendants)] }
const button = (p, label) => descendants(p.root).find(node => node.tag === 'button' && text(node).trim() === label)
async function settle() { for (let i = 0; i < 20; i++) await Promise.resolve(); await nextTick(); await new Promise(resolve => setImmediate(resolve)) }
let sequence = 0
function backend() {
  const state = { detail: detail(), overview: overview(), plan: savedPlan(), commands: [] }
  api.organizationSyncOverview = async () => structuredClone(state.overview)
  api.organizationSyncBatches = async n => page([summary(state.detail)], n)
  api.organizationSyncBatch = async () => structuredClone(state.detail)
  api.organizationSyncTransitions = async () => history(state.detail)
  api.organizationSyncPlans = async (_, n) => page([planSummary(state.plan)], n)
  api.organizationSyncPlan = async () => structuredClone(state.plan)
  api.organizationSyncOptions = async () => ({ items: [local()] })
  api.preflightOrganizationSync = async (id, body) => {
    state.commands.push(['preflight', id, structuredClone(body)])
    state.plan.plan.selections = body.selections.map(value => ({ key: { kind: value.kind, externalId: value.externalId }, localId: value.localId, expectedRevision: value.expectedRevision }))
    return { id: state.plan.plan.id, ready: state.plan.plan.conflicts.length === 0 }
  }
  api.applyOrganizationSync = async (id, body) => {
    state.commands.push(['apply', id, structuredClone(body)]); state.detail = detail('APPLIED'); state.overview.activeBatchId = null; state.overview.appliedRevision = 1; state.overview.sourceVersion = 2
    return { id, status: 'APPLIED', version: 4 }
  }
  api.queueOrganizationSync = async body => { state.commands.push(['queue', structuredClone(body)]); return { id: uuid(1), status: 'QUEUED', version: 1 } }
  api.organizationStatus = async () => ({ initialized: true }); api.organizationUnits = async () => ({ items: [] }); api.organizationPeople = async () => ({ items: [] }); api.organizationAppointments = async () => ({ items: [] })
  return state
}
function panel(Component = Panel, setup = {}) {
  const userId = `panel-${++sequence}`, scopeKey = setup.scopeKey ?? JSON.stringify(['tenant', userId])
  bindAuthenticationActor({ tenantId: 'tenant', userId })
  if (!setup.keepDraft) model.organizationSyncDrafts.put(scopeKey, { ...model.emptySyncDraft(), batchId: uuid(1), planId: setup.planId ?? uuid(2) })
  const props = reactive({ scopeKey, refreshVersion: 0, locked: false, accessDenied: false }), root = { children: [] }, events = []
  const component = { ...Component, setup: (_, ctx) => Component.setup(props, ctx), ...(Component === Rendered ? {} : { render: () => null }) }
  const app = renderer.createApp(component, { ...props, onBusy: value => events.push(['busy', value]), onApplied: () => events.push(['applied']) })
  const p = { props, root, events, state: app.mount(root).$.setupState, close() { if (mounted.delete(p)) { app.unmount(); model.organizationSyncDrafts.clear(scopeKey); organization.organizationDrafts.put(scopeKey, organization.emptyOrganizationDraft()) } } }
  mounted.add(p); return p
}

test('完整页面先保存明确选择，核对原计划后才执行一次应用', async () => {
  const server = backend(), p = panel(Rendered, { planId: '' }); await settle()
  assert.equal(server.commands.length, 0); assert.ok(text(p.root).includes('来源人员'))
  await p.state.chooseFact(p.state.facts[0]); p.state.selectedLocalId = local().id; p.state.adopt()
  assert.deepEqual(p.state.draft.selections, [{ ...sourceKey(), localId: local().id, expectedRevision: 2 }]); assert.equal(server.commands.length, 0)
  await p.state.preflight(); await settle()
  assert.equal(server.commands.length, 1); assert.equal(server.commands[0][0], 'preflight'); assert.equal(server.commands[0][2].expectedVersion, 3)
  assert.ok(text(p.root).includes('核对时的本地值')); assert.ok(text(p.root).includes('应用后的值')); assert.ok(text(p.root).includes('本地人员'))
  const apply = button(p, '核对并应用此计划'); assert.equal(apply.props.disabled, false); apply.props.onClick(); await settle()
  assert.equal(button(p, '确认应用').props.disabled, true); await p.state.confirm(); assert.equal(server.commands.length, 1)
  p.state.accepted = true; await p.state.confirm(); await settle()
  assert.equal(server.commands.length, 2); assert.deepEqual(server.commands[1], ['apply', uuid(1), { expectedVersion: 3, planId: uuid(2) }])
  assert.equal(p.state.detail.state.status, 'APPLIED'); assert.equal(p.events.filter(value => value[0] === 'applied').length, 1)
})

test('来源和选择变化使原确认失效，不能应用旧核对意图', async () => {
  const server = backend(), p = panel(); await settle()
  p.state.begin('apply'); p.state.accepted = true; p.state.overview.targetDigest = 'c'.repeat(64)
  await p.state.confirm(); assert.equal(server.commands.length, 0)
  p.state.confirmation = null; p.state.draft.selections = [{ ...sourceKey(), localId: local().id, expectedRevision: 2 }]
  assert.equal(p.state.canApply, false); p.state.begin('apply'); assert.equal(p.state.confirmation, null)
})

test('冲突计划可以检查和重新预检，模板中的应用按钮保持禁用', async () => {
  const server = backend(); server.plan.plan.conflicts = [{ key: sourceKey(), code: 'ORGANIZATION_SYNC_LOCAL_CHANGED', localId: local().id }]
  const p = panel(Rendered); await settle()
  assert.equal(p.state.canApply, false); assert.equal(button(p, '核对并应用此计划').props.disabled, true)
  assert.ok(text(p.root).includes('本地记录已修改')); await p.state.confirm(); assert.equal(server.commands.length, 0)
})

test('读取阶段不提供预检或应用；来源配置停用后仍可取消批次', async () => {
  const server = backend(); server.detail = detail('FETCHING'); Object.assign(server.overview, { configured: false, sourceKey: null, targetDigest: null })
  const p = panel(Rendered, { planId: '' }); await settle()
  assert.equal(p.state.canPreflight, false); assert.equal(p.state.canCancel, true); assert.equal(p.state.canQueue, false)
  assert.equal(button(p, '生成核对计划').props.disabled, true); assert.equal(button(p, '取消批次').props.disabled, false)
})

test('账号切换取消旧读取，来源正文和人工选项均不接受迟到结果', async () => {
  backend(); const p = panel(); await settle(); const pending = deferred()
  api.organizationSyncPlan = () => pending.promise; const old = p.state.loadPlan(uuid(2))
  api.organizationSyncOverview = async () => { throw { status: 403 } }; api.organizationSyncBatches = async () => page([])
  p.props.scopeKey = JSON.stringify(['foreign', 'admin']); await settle(); pending.resolve(savedPlan()); await old; await settle()
  assert.equal(p.state.denied, true); assert.equal(p.state.plan, null); assert.equal(p.state.detail, null); assert.equal(p.state.overview, null)
  assert.deepEqual(p.state.options, []); assert.deepEqual(p.state.draft.selections, [])
})

test('不响应 abort 的读取仍有总时限，超时后可再次读取计划', async () => {
  backend(); const p = panel(); await settle(); const original = globalThis.setTimeout, timers = []
  try {
    globalThis.setTimeout = callback => { timers.push(callback); return 123 }; api.organizationSyncPlan = () => new Promise(() => {})
    const job = p.state.loadPlan(uuid(2)); assert.equal(timers.length, 1); timers[0](); await job
    assert.equal(p.state.loading.plan, false); assert.equal(p.state.plan, null); assert.match(p.state.errors.plan, /超时/)
  } finally { globalThis.setTimeout = original }
  api.organizationSyncPlan = async () => savedPlan(); await p.state.loadPlan(uuid(2)); assert.equal(p.state.plan.plan.id, uuid(2)); assert.equal(p.state.errors.plan, '')
})

test('真实恢复入口定位原预检计划，不重发预检或顺带应用', async () => {
  const server = backend(), p = panel(); await settle(); p.state.unknown = true
  const body = JSON.stringify({ expectedVersion: 3, selections: [] }), request = { id: 'recover-sync', path: model.syncPath + '/batches/' + uuid(1) + '/preflight', body }
  const box = value => ({ value }); globalThis.document = { querySelector: () => null }
  const env = { pendingWrites: box([request]), draftScope: box(''), actorScope: box(p.props.scopeKey), organizationSyncDrafts: model.organizationSyncDrafts, syncPath: model.syncPath,
    confirmReplaceDefinition: async (_, action) => action(), busy: box(false), recoveryError: box(''), notice: box(''), recordApplicationId: box(null), recordRefresh: box(0), templateRefresh: box(0),
    writeRequests: { recover: async () => ({ request, result: { id: uuid(2), ready: true } }) }, refreshWorkspace: async () => {}, nextTick, workspace: box(null), errorMessage: e => e.message }
  await createRecovery(env)(request.id); assert.equal(env.recoveryError.value, ''); assert.equal(env.templateRefresh.value, 1)
  p.props.refreshVersion = env.templateRefresh.value; await settle()
  assert.equal(p.state.unknown, false); assert.equal(p.state.plan.plan.id, uuid(2)); assert.equal(server.commands.length, 0)
})

test('失权已清空核对草稿时，迟到的写回执不能重新填回原选择', async () => {
  backend(); const p = panel(); await settle(); const pending = deferred()
  p.state.draft.selections = [{ ...sourceKey(), localId: local().id, expectedRevision: 2 }]
  api.preflightOrganizationSync = () => pending.promise; const write = p.state.preflight()
  api.organizationSyncOverview = async () => { throw { status: 403 } }; await p.state.loadOverview()
  assert.equal(p.state.denied, true); assert.deepEqual(model.organizationSyncDrafts.get(p.props.scopeKey).selections, [])
  pending.resolve({ id: uuid(2), ready: true }); await write
  assert.deepEqual(model.organizationSyncDrafts.get(p.props.scopeKey).selections, [])
  assert.equal(model.organizationSyncDrafts.get(p.props.scopeKey).planId, '')
})

test('恢复组织页面时，本地未保存草稿优先显示，不能锁在同步标签页', async () => {
  backend(); const scopeKey = JSON.stringify(['tenant', 'local-draft-admin'])
  const draft = organization.emptyOrganizationDraft(); draft.form.name = '未保存法人'; organization.organizationDrafts.put(scopeKey, draft)
  model.organizationSyncDrafts.put(scopeKey, { ...model.emptySyncDraft(), batchId: uuid(1) })
  const p = panel(Directory, { scopeKey, keepDraft: true }); await settle()
  assert.equal(p.state.dirty, true); assert.equal(p.state.view, 'local')
})

test('取消保留具名意见，明确重试创建关联原记录的新批次', async () => {
  const server = backend(), p = panel(Rendered); await settle()
  api.cancelOrganizationSync = async (id, body) => {
    server.commands.push(['cancel', id, structuredClone(body)]); server.detail = detail('CANCELLED'); server.detail.state.decision.comment = body.comment; server.overview.activeBatchId = null
    return { id, status: 'CANCELLED', version: 4 }
  }
  api.retryOrganizationSync = async (id, body) => {
    server.commands.push(['retry', id, structuredClone(body)]); server.detail = detail('QUEUED', uuid(7)); server.detail.request.retryOf = id; server.overview.activeBatchId = uuid(7)
    return { id: uuid(7), status: 'QUEUED', version: 1 }
  }
  p.state.draft.comment = '来源修订需核对\n本次取消'; await nextTick(); p.state.begin('cancel'); p.state.accepted = true; await p.state.confirm(); await settle()
  assert.deepEqual(server.commands[0], ['cancel', uuid(1), { expectedVersion: 3, comment: '来源修订需核对\n本次取消' }])
  assert.ok(text(p.root).includes('来源修订需核对')); assert.equal(p.state.draft.comment, ''); assert.equal(p.state.canRetry, true)
  p.state.begin('retry'); p.state.accepted = true; await p.state.confirm(); await settle()
  assert.deepEqual(server.commands[1], ['retry', uuid(1), { expectedVersion: 4, expectedSourceVersion: 1, targetDigest: 'a'.repeat(64) }])
  assert.equal(p.state.draft.batchId, uuid(7)); assert.equal(p.state.detail.request.retryOf, uuid(1)); assert.equal(p.state.detail.state.status, 'QUEUED'); assert.equal(p.state.plan, null)
  assert.equal(server.commands.length, 2)
})

test('分页和追加本地选项保持所选批次，只采用实际读取的当前修订', async () => {
  backend(); const p = panel(); await settle(); const requests = []
  api.organizationSyncBatches = async index => { requests.push(['batch-page', index]); return { ...page([summary(detail('FAILED', uuid(9)))], index), total: 21 } }
  api.organizationSyncPlans = async (id, index) => { requests.push(['plan-page', id, index]); return { ...page([planSummary(savedPlan(uuid(1), uuid(8)))], index), total: 21 } }
  const second = { ...local(), id: uuid(6), subject: 'second-subject', revision: 19 }
  api.organizationSyncOptions = async (kind, cursor) => { requests.push(['option-page', kind, cursor]); return cursor ? { items: [local(), second] } : { items: [local()], nextAfterId: local().id } }
  await p.state.loadBatches(1); await p.state.loadPlans(1); assert.equal(p.state.detail.request.id, uuid(1)); assert.equal(p.state.plan.plan.id, uuid(2))
  await p.state.chooseFact(p.state.facts[0]); await p.state.loadOptions(true)
  assert.equal(p.state.options.length, 2); p.state.selectedLocalId = second.id; p.state.adopt()
  assert.deepEqual(p.state.draft.selections, [{ ...sourceKey(), localId: second.id, expectedRevision: 19 }])
  assert.deepEqual(requests, [['batch-page', 1], ['plan-page', uuid(1), 1], ['option-page', 'PERSON', undefined], ['option-page', 'PERSON', local().id]])
})

test('未知写结果保留选择并锁住后续操作，普通刷新不能伪装成回执恢复', async () => {
  backend(); const p = panel(); await settle(); let sends = 0
  const selection = { ...sourceKey(), localId: local().id, expectedRevision: 2 }; p.state.draft.selections = [selection]
  api.preflightOrganizationSync = async () => { sends++; throw { status: 0, code: 'RESPONSE_UNREADABLE' } }
  await p.state.preflight(); await settle(); assert.equal(p.state.unknown, true); assert.equal(p.state.controlsLocked, true)
  await p.state.refresh(); await p.state.preflight(); p.state.begin('apply')
  assert.equal(sends, 1); assert.equal(p.state.unknown, true); assert.equal(p.state.confirmation, null); assert.deepEqual(p.state.draft.selections, [selection])
})

test('正常卸载后的原成功回执保留在原账号，重新打开可定位原计划', async () => {
  backend(); const p = panel(); await settle(); const pending = deferred(), scope = p.props.scopeKey
  api.preflightOrganizationSync = () => pending.promise; const job = p.state.preflight(); p.close()
  pending.resolve({ id: uuid(2), ready: true }); await job
  assert.equal(model.organizationSyncDrafts.get(scope).planId, uuid(2)); assert.equal(model.organizationSyncDrafts.get(scope).batchId, uuid(1))
  model.organizationSyncDrafts.clear(scope)
})

test('切换账号恢复其本地草稿，仍能回到本地编辑入口', async () => {
  backend(); const p = panel(Directory); await settle(); assert.equal(p.state.view, 'sync')
  const next = JSON.stringify(['tenant', 'next-local-admin']), draft = organization.emptyOrganizationDraft(); draft.form.name = '另一个未保存法人'
  organization.organizationDrafts.put(next, draft); model.organizationSyncDrafts.put(next, { ...model.emptySyncDraft(), batchId: uuid(9) })
  p.props.scopeKey = next; await settle(); assert.equal(p.state.view, 'local'); assert.equal(p.state.dirty, true)
  organization.organizationDrafts.put(next, organization.emptyOrganizationDraft()); model.organizationSyncDrafts.clear(next)
})

test('前后值中姓名和主体即使与枚举同名也保持原文', async () => {
  const server = backend(); server.plan.plan.people[0].before.subject = 'PERSON'; server.plan.plan.people[0].before.displayName = 'APPOINTMENT'
  const p = panel(Rendered); await settle()
  assert.ok(text(p.root).includes('稳定主体PERSON')); assert.ok(text(p.root).includes('姓名APPOINTMENT'))
})

test('父级目录读到失权时同步页立即清空正文，迟到写入不能恢复选择', async () => {
  backend(); const p = panel(Rendered); await settle(); const pending = deferred()
  p.state.draft.selections = [{ ...sourceKey(), localId: local().id, expectedRevision: 2 }]
  api.preflightOrganizationSync = () => pending.promise; const job = p.state.preflight()
  p.props.accessDenied = true; await settle(); pending.resolve({ id: uuid(2), ready: true }); await job; await settle()
  assert.equal(p.state.denied, true); assert.equal(p.state.detail, null); assert.equal(p.state.plan, null)
  assert.equal(text(p.root).includes('来源人员'), false); assert.deepEqual(model.organizationSyncDrafts.get(p.props.scopeKey).selections, [])
})
