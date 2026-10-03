import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive, toRaw } from 'vue'
const rules = await import(process.env.AGENTFLOW_TEST_INSTANCE_CONTROL)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_INSTANCE_PANEL)
const { default: RenderedPanel } = await import(process.env.AGENTFLOW_TEST_INSTANCE_RENDERED)
const { default: Record } = await import(process.env.AGENTFLOW_TEST_APPLICATION_RECORD)
const { render: recordBinding } = await import(process.env.AGENTFLOW_TEST_INSTANCE_RECORD)
const { createRecordChanged } = await import(process.env.AGENTFLOW_TEST_RECORD_CHANGED)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originalApi = { ...api }, originalFetch = globalThis.fetch
globalThis.localStorage = { getItem: () => 'instance-test-token', setItem() {}, removeItem() {} }
const box = value => ({ value })
const settle = () => new Promise(resolve => setImmediate(resolve))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const view = () => ({ applicationId: 'application', roundNo: 1, applicationVersion: 3, state: 'RUNNING', canPause: true, canResume: false, canTerminate: true })
const paused = () => ({ ...view(), applicationVersion: 4, state: 'PAUSED', pausedAt: '2026-10-01T08:00:00Z', canPause: false, canResume: true })
const input = () => ({ expectedVersion: 3, reason: '核对当前审批资料' })
const ended = version => ({ ...view(), applicationVersion: version, state: 'ENDED', canPause: false, canResume: false, canTerminate: false })
const panelProps = () => reactive({ applicationId: 'application', roundNo: 1, version: 3, scopeKey: 'demo:admin', locked: false })
function mount(Component, props, handlers = {}) {
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, ...handlers })
  const instance = app.mount({})
  return { state: instance.$.setupState, close: () => { app.unmount(); Object.assign(api, originalApi) } }
}

test('取消确认后焦点返回重新挂载的原操作按钮，不丢到页面主体', async () => {
  let focused
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null,
    addEventListener() {}, removeEventListener() {}, focus() { focused = this } })
  const remove = child => { if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1); child.parent = null }
  const host = createRenderer({
    createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
    patchProp: (el, key, _old, value) => { el.props[key] = value }, remove,
    insert: (child, parent, anchor = null) => { remove(child); child.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, child) },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null
  })
  const find = (el, label) => el.tag === 'button' && el.text === label ? el : el.children.map(child => find(child, label)).find(Boolean)
  for (const [action, label, initial] of [['pause', '暂停本轮审批', view()], ['resume', '恢复本轮审批', paused()], ['terminate', '终止本轮审批', paused()]]) {
    api.instanceControl = async () => initial
    const root = node('root'), props = { ...panelProps(), version: initial.applicationVersion }
    const app = host.createApp(RenderedPanel, props)
    try {
      app.mount(root); await settle()
      const original = find(root, label)
      assert.ok(original, action); await original.props.onClick(); await settle()
      assert.ok(find(root, label) === undefined, '确认表单应卸载原操作按钮')
      find(root, '取消操作').props.onClick(); await settle()
      const restored = find(root, label)
      assert.ok(restored); assert.ok(restored !== original)
      assert.ok(toRaw(focused) === restored, `取消 ${action} 后应聚焦新挂载的原按钮`)
    } finally { app.unmount(); Object.assign(api, originalApi) }
  }
})

test('终止要求本轮管理权限和明确原因，只接受无后续操作能力的终态回执', () => {
  for (const initial of [view(), paused()]) {
    const command = rules.instanceControlInput(initial, initial.applicationVersion, 'terminate', '  明确终止原审批  ')
    assert.deepEqual(command, { expectedVersion: initial.applicationVersion, reason: '明确终止原审批' })
    rules.validateInstanceReceipt(ended(initial.applicationVersion + 1), 'application', 1, 'terminate', command)
    for (const invalid of [initial, { ...ended(initial.applicationVersion + 1), canTerminate: true },
      { ...ended(initial.applicationVersion + 1), pausedAt: paused().pausedAt }, ended(initial.applicationVersion)]) {
      assert.throws(() => rules.validateInstanceReceipt(invalid, 'application', 1, 'terminate', command), e => e.code === 'RESPONSE_UNREADABLE')
    }
    assert.throws(() => rules.instanceControlInput({ ...initial, canTerminate: false }, initial.applicationVersion, 'terminate', '原因'))
  }
})

test('运行和暂停中的终止面板均先确认，取消不发送，成功只刷新原申请', async () => {
  for (const initial of [view(), paused()]) {
    const props = panelProps(), sent = [], changed = []
    props.version = initial.applicationVersion
    api.instanceControl = async () => initial
    api.controlInstance = async (...args) => { sent.push(args); return ended(initial.applicationVersion + 1) }
    const p = mount(Panel, props, { onChanged: () => changed.push(true) })
    try {
      await settle(); await p.state.prepare('terminate'); assert.equal(p.state.selected, 'terminate')
      assert.equal(p.state.actionLabel, '终止审批'); assert.equal(sent.length, 0)
      p.state.cancel(); await p.state.execute(); assert.equal(sent.length, 0)
      await p.state.prepare('terminate'); await p.state.execute(); assert.equal(sent.length, 0)
      p.state.reason = '管理员确认不再办理'; await p.state.execute()
      assert.deepEqual(sent, [['application', 1, 'terminate', { expectedVersion: initial.applicationVersion, reason: '管理员确认不再办理' }]])
      assert.equal(changed.length, 1)
    } finally { p.close() }
  }
})

test('运行状态读取严格绑定申请、轮次和操作能力，原生暂停缺少依据时仅可读', () => {
  rules.validateInstanceView(view(), 'application', 1)
  rules.validateInstanceView({ ...paused(), pausedAt: undefined, canResume: false }, 'application', 1)
  for (const change of [{ applicationId: 'other' }, { roundNo: 2 }, { applicationVersion: 0 },
    { state: 'ENDED' }, { canResume: true }, { canPause: 'true' }, { pausedAt: 'invalid' }]) {
    assert.throws(() => rules.validateInstanceView({ ...view(), ...change }, 'application', 1))
  }
  assert.throws(() => rules.validateInstanceView({ ...paused(), pausedAt: undefined }, 'application', 1))
})

test('状态读取切换申请立即撤销旧能力，迟到响应和失败都不能开放操作', async () => {
  let finish, signal
  const query = new rules.InstanceControlQuery((_id, _round, current) => { signal = current; return new Promise(resolve => { finish = resolve }) })
  const pending = query.load('demo:admin', 'application', 1)
  query.clear(); assert.equal(signal.aborted, true); finish(view()); await pending
  assert.equal(query.value, null)
  const invalid = new rules.InstanceControlQuery(async () => ({ ...view(), applicationId: 'another' }))
  await invalid.load('demo:admin', 'application', 1)
  assert.equal(invalid.value, null); assert.ok(invalid.error); assert.equal(invalid.loading, false)
})

test('运行状态读取超时清理加载状态，迟到成功不能覆盖超时结果', async () => {
  const originalTimer = globalThis.setTimeout; let timeout, finish, signal
  globalThis.setTimeout = callback => { timeout = callback; return 1 }
  try {
    const query = new rules.InstanceControlQuery((_id, _round, current) => { signal = current; return new Promise(resolve => { finish = resolve }) })
    const pending = query.load('demo:admin', 'application', 1); timeout(); await pending
    assert.equal(signal.aborted, true); assert.equal(query.loading, false); assert.match(query.error, /超时/)
    finish(view()); await settle(); assert.equal(query.value, null)
  } finally { globalThis.setTimeout = originalTimer }
})

test('实际暂停和恢复面板要求原因，取消、锁定和过期详情均不发送', async () => {
  for (const action of ['pause', 'resume']) {
    const sent = [], changed = [], props = panelProps(), initial = action === 'pause' ? view() : paused()
    props.version = initial.applicationVersion
    api.instanceControl = async () => initial
    api.controlInstance = async (...args) => { sent.push(args); return action === 'pause' ? paused() : { ...view(), applicationVersion: 5 } }
    const p = mount(Panel, props, { onChanged: () => changed.push(true) })
    try {
      await settle(); await p.state.prepare(action); assert.equal(sent.length, 0)
      p.state.cancel(); await p.state.execute(); assert.equal(sent.length, 0)
      await p.state.prepare(action); await p.state.execute(); assert.equal(sent.length, 0); assert.ok(p.state.error)
      p.state.reason = '核对当前审批资料'; props.locked = true; await p.state.execute(); assert.equal(sent.length, 0)
      props.locked = false; await p.state.execute()
      assert.deepEqual(sent, [['application', 1, action, { expectedVersion: initial.applicationVersion, reason: input().reason }]])
      assert.equal(changed.length, 1)
      props.version = 99; await settle(); await p.state.prepare(action)
      assert.equal(p.state.selected, null); assert.equal(p.state.blocked, true)
    } finally { p.close() }
  }
})

test('正在发送时拒绝重复确认，切换身份后不把原响应显示为新上下文成功', async () => {
  let finish, sends = 0; const changes = [], busy = [], states = [], props = panelProps()
  api.instanceControl = async () => view()
  api.controlInstance = () => { sends++; return new Promise(resolve => { finish = resolve }) }
  const p = mount(Panel, props, { onChanged: () => changes.push(true), onBusy: value => busy.push(value), onState: value => states.push(value) })
  try {
    await settle(); await p.state.prepare('pause'); p.state.reason = input().reason
    const pending = p.state.execute(); await p.state.execute(); assert.equal(sends, 1); assert.equal(busy.at(-1), true)
    props.scopeKey = 'demo:alice'; await settle(); finish(paused()); await pending
    assert.equal(changes.length, 0); assert.equal(busy.at(-1), false); assert.equal(p.state.selected, null)
    assert.ok(states.includes(null))
  } finally { p.close() }
})

test('申请详情在状态未读、暂停、版本失配或操作中禁止撤回和财务办理', async () => {
  const before = globalThis.document; globalThis.document = { activeElement: null }
  const props = reactive({ applicationId: 'application', userId: 'alice', scopeKey: 'demo:alice', commentRefreshVersion: 0, pendingWrites: [], recoveryError: '' })
  let withdrawals = 0, closes = 0
  api.application = async () => ({ id: 'application', createdBy: 'alice', status: 'IN_APPROVAL', title: '申请', payload: {}, version: 3, roundNo: 1 })
  api.applicationRounds = async () => []
  api.withdrawApplication = async () => { withdrawals++; throw new Error('Unexpected withdrawal') }
  const p = mount(Record, props, { onClose: () => closes++ })
  try {
    await settle(); assert.equal(p.state.canWithdraw, false); assert.equal(p.state.businessLocked, true)
    p.state.runtimeUpdated({ ...view(), canPause: false }); assert.equal(p.state.canWithdraw, true)
    await p.state.openWithdrawal(); assert.equal(p.state.withdrawalOpen, true)
    p.state.runtimeUpdated({ ...paused(), applicationVersion: 3, canResume: false })
    assert.equal(p.state.withdrawalOpen, false); await p.state.withdraw(); assert.equal(withdrawals, 0)
    p.state.runtimeUpdated({ ...view(), applicationVersion: 4 }); assert.equal(p.state.businessLocked, true)
    p.state.runtimeUpdated(view()); p.state.runtimeBusy = true
    assert.equal(p.state.canWithdraw, false); p.state.close(); assert.equal(closes, 0)
    p.state.runtimeBusy = false; assert.equal(p.state.businessLocked, false)
    for (const type of ['EXPENSE', 'EXPENSE_PLAN', 'ADVANCE_REQUEST', 'PROCUREMENT_PAYMENT', 'BUDGET_ADJUSTMENT']) {
      p.state.application.businessReference = { type, id: 'finance-id' }
      p.state.runtimeUpdated(paused()); assert.equal(p.state.businessLocked, true); assert.equal(p.state.canWithdraw, false)
      p.state.application.status = 'APPROVED'; assert.equal(p.state.businessLocked, false)
      p.state.application.status = 'IN_APPROVAL'
    }
    await p.state.load(); assert.equal(p.state.runtimeState, null); assert.equal(p.state.businessLocked, true)
  } finally { p.close(); globalThis.document = before }
})

test('真实面板事件绑定会传递状态和繁忙并刷新申请及父队列，无需定时等待节点', async () => {
  let panel, loads = 0, changes = 0
  const context = reactive({ application: { id: 'application', roundNo: 1, version: 3, status: 'IN_APPROVAL' }, scopeKey: 'demo:admin',
    saving: false, expenseBusy: false, writesBlocked: false, runtimeBusy: false, runtimeState: null,
    runtimeUpdated: value => { context.runtimeState = value }, load: () => { loads++ }, emit: () => { changes++ } })
  const app = renderer.createApp({ components: { InstanceControlPanel: { setup(_props, { attrs }) { panel = attrs; return () => null } } }, setup: () => context, render: recordBinding })
  app.mount({})
  try {
    assert.equal(panel['application-id'], 'application')
    panel.onState(paused()); panel.onBusy(true); await panel.onChanged()
    assert.equal(context.runtimeState.state, 'PAUSED'); assert.equal(context.runtimeBusy, true)
    assert.equal(loads, 1); assert.equal(changes, 1)
  } finally { app.unmount() }
})

test('详情操作完成后清除同一申请的旧任务面板，不影响其他申请选择', async () => {
  let cleared = 0, refreshed = 0
  const deps = { activeTask: box({ applicationId: 'application' }), recordApplicationId: box('application'),
    clearTaskSelection: () => { cleared++ }, refreshPage: async () => { refreshed++ } }
  const changed = createRecordChanged(deps)
  await changed(); assert.equal(cleared, 1); assert.equal(refreshed, 1)
  deps.activeTask.value.applicationId = 'other'; await changed(); assert.equal(cleared, 1); assert.equal(refreshed, 2)
})

test('暂停恢复回执只接受原绑定及恰好一次版本变化，缺失暂停依据不能清除恢复槽', () => {
  rules.validateInstanceReceipt(paused(), 'application', 1, 'pause', input())
  rules.validateInstanceReceipt({ ...view(), applicationVersion: 5 }, 'application', 1, 'resume', { ...input(), expectedVersion: 4 })
  for (const change of [{ applicationId: 'other' }, { roundNo: 2 }, { applicationVersion: 3 }, { applicationVersion: 5 },
    { state: 'RUNNING' }, { pausedAt: undefined }, { canResume: false }]) {
    assert.throws(() => rules.validateInstanceReceipt({ ...paused(), ...change }, 'application', 1, 'pause', input()), e => e.code === 'RESPONSE_UNREADABLE')
  }
})

test('运行读取不缓存，写入结果未知时使用原键和原原因恢复', async () => {
  const calls = []; writeRequests.setActor({ tenantId: 'demo', userId: 'instance-lost' })
  try {
    globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); if (init.method === 'POST' && calls.length === 2) throw new Error('lost'); return Response.json(init.method === 'POST' ? paused() : view()) }
    const signal = new AbortController().signal; await api.instanceControl('application/encoded', 1, signal)
    assert.ok(calls[0].url.endsWith('/applications/application%2Fencoded/rounds/1/runtime')); assert.equal(calls[0].cache, 'no-store')
    assert.equal(calls[0].signal, signal); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
    await assert.rejects(api.controlInstance('application', 1, 'pause', input()))
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls[1].body, calls[2].body); assert.equal(calls[1].headers.get('Idempotency-Key'), calls[2].headers.get('Idempotency-Key'))
    assert.equal(writeRequests.pending().length, 0)
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('HTTP 200 串轮回执保留原请求，不能修改原因后重发', async () => {
  const calls = []; writeRequests.setActor({ tenantId: 'demo', userId: 'instance-unreadable' })
  try {
    globalThis.fetch = async (_url, init) => { calls.push(init); return Response.json({ ...paused(), ...(calls.length === 1 ? { roundNo: 2 } : {}) }) }
    await assert.rejects(api.controlInstance('application', 1, 'pause', input()), e => e.code === 'RESPONSE_UNREADABLE')
    assert.equal(writeRequests.pending().length, 1)
    await assert.rejects(api.controlInstance('application', 1, 'pause', { ...input(), reason: '新原因' }), e => e.code === 'PENDING_REQUEST_CHANGED')
    await writeRequests.recover(writeRequests.pending()[0].id)
    assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].headers.get('Idempotency-Key'), calls[1].headers.get('Idempotency-Key'))
    assert.equal(writeRequests.pending().length, 0)
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('终止响应丢失或终态回执不完整时保留原键，恢复不会创建第二次终止', async () => {
  const calls = []; writeRequests.setActor({ tenantId: 'demo', userId: 'termination-unreadable' })
  try {
    globalThis.fetch = async (url, init) => {
      calls.push({ url, ...init })
      if (calls.length === 1) throw new Error('lost termination response')
      return Response.json(calls.length === 2 ? { ...ended(4), canTerminate: true } : ended(4))
    }
    await assert.rejects(api.controlInstance('application', 1, 'terminate', input()))
    const original = writeRequests.pending()[0]
    assert.match(original.label, /终止/)
    await assert.rejects(writeRequests.recover(original.id), e => e.code === 'RESPONSE_UNREADABLE')
    assert.equal(writeRequests.pending()[0].id, original.id)
    await writeRequests.recover(original.id)
    assert.equal(writeRequests.pending().length, 0)
    assert.equal(new Set(calls.map(call => call.headers.get('Idempotency-Key'))).size, 1)
    assert.equal(new Set(calls.map(call => call.body)).size, 1)
    assert.ok(calls.every(call => call.url.endsWith('/runtime/terminate')))
  } finally { globalThis.fetch = originalFetch; bindAuthenticationActor(null) }
})

test('暂停恢复或终止原回执按申请子操作重读详情，不将运行回执当成完整申请', async () => {
  const before = globalThis.document; globalThis.document = { querySelector: () => null }
  try {
    for (const action of ['pause', 'resume', 'terminate']) {
      let cleared = 0
      const pending = { id: 'original', path: `/applications/application%2Fencoded/rounds/1/runtime/${action}`, sending: false }
      const deps = { pendingWrites: box([pending]), draftScope: box(''), confirmReplaceDefinition: async (_label, work) => work(),
        busy: box(false), recoveryError: box(''), writeRequests: { recover: async () => ({ request: pending,
          result: { applicationId: 'application', roundNo: 1, applicationVersion: 4, state: action === 'pause' ? 'PAUSED' : action === 'resume' ? 'RUNNING' : 'ENDED' } }) },
        notice: box(''), createdApplication: box(null), newApplicationOpen: box(false), recordApplicationId: box('application/encoded'),
        activeTask: box({ applicationId: 'application/encoded' }), clearTaskSelection: () => { cleared++ }, recordRefresh: box(0), templateRefresh: box(0), statusLabel: value => value, refreshWorkspace: async () => {},
        nextTick: async () => {}, workspace: box(null), errorMessage: e => e.message }
      await createRecovery(deps)('original')
      assert.equal(deps.recordRefresh.value, 1)
      assert.equal(cleared, 1)
      assert.equal(deps.recoveryError.value, '')
      assert.ok(!deps.notice.value.includes('undefined'))
      assert.equal(deps.busy.value, false)
    }
  } finally { globalThis.document = before }
})

test('完整申请提交、撤回、作废和保存的恢复仍读取完整申请结果', async () => {
  const before = globalThis.document; globalThis.document = { querySelector: () => null }
  try {
    for (const suffix of ['/submit', '/withdraw', '/cancel', '']) {
      const pending = { id: 'original', path: `/applications/application${suffix}`, sending: false }
      const deps = { pendingWrites: box([pending]), draftScope: box(''), confirmReplaceDefinition: async (_label, work) => work(),
        busy: box(false), recoveryError: box(''), writeRequests: { recover: async () => ({ request: pending,
          result: { id: 'application', businessNo: 'AF-001', status: 'IN_APPROVAL', payload: {} } }) },
        notice: box(''), createdApplication: box(null), newApplicationOpen: box(false), recordApplicationId: box('application'),
        activeTask: box(null), clearTaskSelection: () => {}, recordRefresh: box(0), templateRefresh: box(0), statusLabel: value => value, refreshWorkspace: async () => {},
        nextTick: async () => {}, workspace: box(null), errorMessage: e => e.message }
      await createRecovery(deps)('original')
      assert.equal(deps.recordRefresh.value, 1); assert.equal(deps.recoveryError.value, '')
      assert.match(deps.notice.value, /AF-001/)
    }
  } finally { globalThis.document = before }
})
