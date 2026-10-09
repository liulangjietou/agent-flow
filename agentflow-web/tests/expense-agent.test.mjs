import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { createRenderer, reactive, nextTick } from 'vue'

const apiUrl = pathToFileURL(process.env.AGENTFLOW_TEST_API)
const { api, writeRequests, bindAuthenticationActor } = await import(apiUrl)
const model = await import(new URL('expenseAgent.js', apiUrl))
const explanation = await import(new URL('precheckExplanation.js', apiUrl))
const { default: Panel } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_EXPENSEAGENTPANELPANEL))
const { default: Rendered } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_EXPENSEAGENTPANELRENDERED))
const originals = { ...api }, fetchOriginal = globalThis.fetch, storage = globalThis.localStorage
afterEach(() => { Object.assign(api, originals); globalThis.fetch = fetchOriginal; globalThis.localStorage = storage; bindAuthenticationActor(null) })
const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const now = new Date().toISOString(), until = new Date(Date.now() + 600000).toISOString()
const scope = () => ({ policyLineNos: [1], invoiceIds: [uuid(4)], precheckIds: [], maxSteps: 8 })
const run = () => ({ id: uuid(1), taskId: uuid(2), reportId: uuid(3), applicationVersion: 1, financialVersion: 1, scope: scope(), deadline: until,
  state: { version: 1, status: 'READY', steps: [], answers: [], updatedAt: now } })
const decision = () => ({ action: 'POLICY', referenceId: null, lineNo: 1, message: '核对适用制度' })
const step = () => ({ id: uuid(6), createdAt: now, decision: decision(), outcome: 'TOOL_READY', observation: null })
const preview = () => ({ goal: '核对资料', scope: scope(), applicationVersion: 1, financialVersion: 1, providerId: 'fixture', model: 'fixture', destination: '127.0.0.1', targetDigest: 'a'.repeat(64), consentDigest: 'b'.repeat(64), sendableData: { expense: {} } })
const settle = () => new Promise(resolve => setImmediate(resolve))
globalThis.Document ??= class Document {}; globalThis.ShadowRoot ??= class ShadowRoot {}
const descendants = node => [node, ...(node.children ?? []).flatMap(descendants)]
const content = node => node.tag === '#comment' ? '' : (node.text ?? '') + (node.children ?? []).map(content).join(' ')
function mountAgent(rendered = false) {
  const props = reactive({ report: { id: uuid(3), applicationVersion: 1, financialVersion: 1, content: { lines: [] } }, task: { id: uuid(2), current: true, goal: '核对材料', steps: [] }, scopeKey: 'alice', locked: false })
  const node = (tag, text = '') => ({ tag, tagName: tag.toUpperCase(), text, children: [], props: {}, listeners: {}, value: '',
    addEventListener(name, listener) { this.listeners[name] = listener }, getRootNode: () => ({}) })
  const remove = value => { if (value.parent) value.parent.children.splice(value.parent.children.indexOf(value), 1) }
  const renderer = createRenderer({ createElement: node, createComment: () => node('#comment'), createText: text => node('#text', text),
    insert(value, parent, anchor) { remove(value); value.parent = parent; const index = parent.children.indexOf(anchor); parent.children.splice(index < 0 ? parent.children.length : index, 0, value) }, remove,
    parentNode: value => value.parent, nextSibling: value => value.parent?.children[value.parent.children.indexOf(value) + 1],
    setText(value, text) { value.text = text }, setElementText(value, text) { value.text = text; value.children = [] }, patchProp(value, key, _old, next) { value.props[key] = next; if (['value', 'checked', 'type'].includes(key)) value[key] = next } })
  const Component = rendered ? Rendered : Panel, root = node('root')
  const app = renderer.createApp({ ...Component, setup: (_, ctx) => Component.setup(props, ctx), ...(rendered ? {} : { render: () => null }) }, props)
  return { props, app, root, state: app.mount(root).$.setupState }
}
function waitingRun() {
  const value = run(); value.state.status = 'NEEDS_INFORMATION'
  value.state.steps = [{ ...step(), decision: { action: 'ASK_USER', message: '请补充行程' }, outcome: 'NEEDS_INFORMATION' }]
  return value
}

test('后台刷新人工等待中的原运行时保留可输入状态', async () => {
  let finish
  api.expenseAgent = async () => waitingRun(); api.expenseHandlingReads = async () => []
  const panel = mountAgent(true)
  try {
    await settle(); panel.state.answer = '已填写的行程说明'
    api.expenseAgent = () => new Promise(resolve => { finish = resolve })
    const refresh = panel.state.load(); await nextTick()
    assert.equal(panel.state.blocked, false, '后台读取不能周期性禁用人工补充表单')
    assert.equal(descendants(panel.root).find(node => node.tag === 'textarea').props.disabled, false)
    assert.equal(panel.state.answer, '已填写的行程说明')
    finish(waitingRun()); await refresh
  } finally { panel.app.unmount(); finish?.(waitingRun()) }
})
test('后台读取中提交人工回答会取消旧读取，迟到结果不能覆盖新状态', async () => {
  let finish, signal, writes = 0
  api.expenseAgent = async () => waitingRun(); api.expenseHandlingReads = async () => []
  const panel = mountAgent()
  try {
    await settle(); panel.state.answer = '出差行程已确认'
    api.expenseAgent = (_report, _task, request) => { signal = request; return new Promise(resolve => { finish = resolve }) }
    const refresh = panel.state.load(), advanced = run(); advanced.state.version = 2
    api.resumeExpenseAgent = async (_report, _task, version, answer) => { writes++; assert.equal(version, 1); assert.equal(answer, '出差行程已确认'); api.expenseAgent = async () => advanced; return advanced }
    await panel.state.resume()
    assert.equal(writes, 1, '人工回答可在后台刷新时提交')
    assert.equal(signal.aborted, true)
    finish(waitingRun()); await refresh
    assert.equal(panel.state.run.state.version, 2); assert.equal(panel.state.run.state.status, 'READY')
  } finally { panel.app.unmount(); finish?.(waitingRun()) }
})
test('原运行读取失败后成功重试清除旧错误', async () => {
  api.expenseAgent = async () => { throw { status: 503 } }; api.expenseHandlingReads = async () => []
  const panel = mountAgent()
  try {
    await settle(); assert.ok(panel.state.error)
    api.expenseAgent = async () => waitingRun(); await panel.state.load()
    assert.equal(panel.state.error, ''); assert.equal(panel.state.run.state.status, 'NEEDS_INFORMATION')
  } finally { panel.app.unmount() }
})
test('人工处理区优先于执行历史，中断记录不显示为已完成或仍在执行', async () => {
  const interrupted = waitingRun(); interrupted.state.status = 'INTERRUPTED'; interrupted.state.steps[0] = { ...step(), decision: null, outcome: 'MODEL_RUNNING' }
  api.expenseAgent = async () => interrupted; api.expenseHandlingReads = async () => []
  const panel = mountAgent(true)
  try {
    await settle(); const nodes = descendants(panel.root)
    assert.ok(nodes.findIndex(node => node.tag === 'textarea') < nodes.findIndex(node => node.tag === 'ol'))
    assert.equal(nodes.find(node => node.tag === 'textarea').props.disabled, false)
    const rendered = content(panel.root)
    assert.match(rendered, /模型执行中断/); assert.match(rendered, /模型调用已登记/); assert.doesNotMatch(rendered, /100%|步骤已完成/)
  } finally { panel.app.unmount() }
})
test('票据分页失败后沿用原游标重试，保留原选择并合并后续票据', async () => {
  const first = { id: uuid(4), original: { filename: '首张票据.pdf' } }, second = { id: uuid(5), original: { filename: '历史票据.pdf' } }
  const cursors = []; let fail = true
  api.expenseAgent = async () => null; api.expenseHandlingReads = async () => []
  api.invoices = async filter => { cursors.push(filter.beforeId); if (!filter.beforeId) return { items: [first], nextBeforeId: uuid(4) }; if (fail) throw { status: 503 }; return { items: [first, second], nextBeforeId: null } }
  const panel = mountAgent()
  try {
    await settle(); panel.state.invoices = [uuid(4)]; await panel.state.loadMoreInvoices()
    assert.ok(panel.state.walletError); assert.equal(panel.state.walletNext, uuid(4)); assert.deepEqual(panel.state.invoices, [uuid(4)])
    fail = false; await panel.state.loadMoreInvoices()
    assert.deepEqual(cursors, [undefined, uuid(4), uuid(4)]); assert.deepEqual(panel.state.wallet.map(value => value.id), [uuid(4), uuid(5)])
    assert.deepEqual(panel.state.invoices, [uuid(4)]); assert.equal(panel.state.walletError, ''); assert.equal(panel.state.walletNext, null)
  } finally { panel.app.unmount() }
})
test('切换身份会中止票据分页，迟到票据和旧选择不能进入新身份', async () => {
  const first = { id: uuid(4), original: { filename: '原账号票据.pdf' } }; let finish, signal
  api.expenseAgent = async () => null; api.expenseHandlingReads = async () => []
  api.invoices = async () => ({ items: [first], nextBeforeId: uuid(4) })
  const panel = mountAgent()
  try {
    await settle(); panel.state.invoices = [uuid(4)]
    api.invoices = (_filter, request) => { signal = request; return new Promise(resolve => { finish = resolve }) }
    const page = panel.state.loadMoreInvoices(); api.invoices = async () => ({ items: [], nextBeforeId: null })
    panel.props.scopeKey = 'bob'; await settle(); assert.equal(signal.aborted, true)
    finish({ items: [first], nextBeforeId: null }); await page
    assert.deepEqual(panel.state.wallet, []); assert.deepEqual(panel.state.invoices, []); assert.equal(panel.state.walletLoading, false)
  } finally { panel.app.unmount(); finish?.({ items: [], nextBeforeId: null }) }
})
test('子任务开始编辑会取消后台读取，并阻止丢弃未确认字段的操作', async () => {
  let finish, signal, writes = 0
  api.expenseAgent = async () => waitingRun(); api.expenseHandlingReads = async () => []; api.cancelExpenseAgent = async () => { writes++ }
  const panel = mountAgent()
  try {
    await settle(); api.expenseAgent = (_report, _task, request) => { signal = request; return new Promise(resolve => { finish = resolve }) }
    const refresh = panel.state.load(); panel.state.childDirty = true
    assert.equal(signal.aborted, true); await panel.state.cancel(); assert.equal(writes, 0)
    finish(run()); await refresh; assert.equal(panel.state.run.state.status, 'NEEDS_INFORMATION')
  } finally { panel.app.unmount(); finish?.(run()) }
})
test('操作失败提示不被随后成功的历史读取覆盖', async () => {
  api.expenseAgent = async () => waitingRun(); api.expenseHandlingReads = async () => []
  api.resumeExpenseAgent = async () => { throw { status: 409 } }
  const panel = mountAgent()
  try { await settle(); panel.state.answer = '已补充'; await panel.state.resume(); assert.ok(panel.state.actionError); assert.equal(panel.state.answer, '已补充') }
  finally { panel.app.unmount() }
})

test('自动办理拒绝跨单回执、超预算与白名单之外的动作或参数', () => {
  const value = run(); value.state.status = 'TOOL_READY'; value.state.steps = [step()]
  assert.deepEqual(model.readExpenseAgent(value, uuid(3), uuid(2)), value)
  for (const mutate of [r => { r.taskId = uuid(9) }, r => { r.scope.maxSteps = 13 }, r => { r.state.steps[0].decision.action = 'APPROVE' },
    r => { r.state.steps[0].decision.lineNo = 2 }, r => { r.state.steps[0].decision.referenceId = uuid(7) }, r => { r.state.status = 'WAITING_CHILD' }]) {
    const changed = structuredClone(value); mutate(changed); assert.throws(() => model.readExpenseAgent(changed, uuid(3), uuid(2)))
  }
  assert.throws(() => model.readAgentPreview(preview(), { ...scope(), invoiceIds: [] }))
})
test('自动授权丢失回执按原键恢复，不重复创建一次模型授权', async () => {
  globalThis.localStorage = { getItem: () => 'fixture' }; bindAuthenticationActor({ tenantId: 'demo', userId: 'agent-recovery' })
  const sent = []; let fail = true
  globalThis.fetch = async (url, options) => { sent.push({ url, key: options.headers['Idempotency-Key'], body: options.body }); if (fail) throw new Error('lost'); return Response.json(run()) }
  await assert.rejects(api.startExpenseAgent(uuid(3), uuid(2), preview()))
  const pending = writeRequests.pending()[0]; assert.ok(pending)
  fail = false; await writeRequests.recover(pending.id)
  assert.deepEqual(sent[1], sent[0]); assert.equal(writeRequests.pending().length, 0)
})
test('自动授权成功响应身份损坏时保留原请求', async () => {
  globalThis.localStorage = { getItem: () => 'fixture' }; bindAuthenticationActor({ tenantId: 'demo', userId: 'agent-broken' })
  globalThis.fetch = async () => Response.json({ ...run(), taskId: uuid(9) })
  await assert.rejects(api.startExpenseAgent(uuid(3), uuid(2), preview()))
  assert.equal(writeRequests.pending().length, 1)
})
test('恢复读取记录只能包含已知只读工具及有界原输入', () => {
  const value = { id: uuid(5), input: { expectedVersion: 1, tool: 'POLICY', lineNo: 1 }, inputDigest: 'a'.repeat(64), version: 2, status: 'FAILED', failureCode: 'FINANCE_GATEWAY_UNAVAILABLE' }
  assert.deepEqual(model.readHandlingReads([value]), [value])
  assert.throws(() => model.readHandlingReads([{ ...value, input: { expectedVersion: 1, tool: 'PAY' } }]))
  assert.throws(() => model.readHandlingReads([value, value]))
})
test('身份切换丢弃迟到运行，读取状态不会自行发起模型请求', async () => {
  let complete, count = 0, writes = 0
  api.expenseAgent = () => ++count === 1 ? new Promise(resolve => { complete = resolve }) : Promise.resolve(null)
  api.expenseHandlingReads = async () => []; api.invoices = async () => ({ items: [] }); api.startExpenseAgent = async () => { writes++; return run() }
  const props = reactive({ report: { id: uuid(3), applicationVersion: 1, financialVersion: 1, content: { lines: [] } }, task: { id: uuid(2), current: true }, scopeKey: 'alice', locked: false })
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, props), state = app.mount({}).$.setupState
  try { props.scopeKey = 'bob'; await nextTick(); complete(run()); await settle(); assert.equal(state.run, null); assert.equal(writes, 0) }
  finally { app.unmount() }
})
test('结构化建议拒绝伪造比较值、金额字段、重复字段与缺少字段来源', () => {
  const issue = 'precheck:finding[0]', field = 'expense:field[1].DESCRIPTION'
  const source = (id, content) => ({ reference: { sourceId: id, contentDigest: 'a'.repeat(64) }, label: id, content })
  const sources = [source('precheck:result', '{}'), source(issue, '{}'), source(field, '办公用品')]
  const patch = { lineNo: 1, field: 'DESCRIPTION', beforeValue: '办公用品', afterValue: '办公打印耗材', impact: '保存后重新预检' }
  const value = { id: uuid(1), precheckId: uuid(2), applicationVersion: 1, financialVersion: 1, attempt: 1, status: 'COMPLETED', version: 3, createdAt: now,
    result: 'BLOCKED', checkedAt: now, validUntil: until, startedAt: now, completedAt: now, sources, canAdopt: true, unavailableCode: null, failure: null, review: null,
    suggestion: { providerId: 'fixture', modelVersion: 'fixture', promptVersion: 'expense-precheck-explanation-v2', items: [{ issueSourceId: issue, explanation: '核对费用用途', corrections: ['确认后重新预检'], evidence: sources.slice(1).map(v => v.reference), patches: [patch] }] } }
  assert.deepEqual(explanation.readExplanationDetail(value, uuid(1)), value)
  for (const mutate of [v => { v.suggestion.items[0].patches[0].beforeValue = '伪造' }, v => { v.suggestion.items[0].patches[0].field = 'CLAIMED_GROSS' },
    v => { v.suggestion.items[0].patches.push(patch) }, v => { v.sources.pop() }]) {
    const changed = structuredClone(value); mutate(changed); assert.throws(() => explanation.readExplanationDetail(changed, uuid(1)))
  }
})
