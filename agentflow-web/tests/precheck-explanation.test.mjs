import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { readFileSync } from 'node:fs'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'

const model = await import(process.env.AGENTFLOW_TEST_PRECHECK_EXPLANATION)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_PRECHECKEXPLANATIONPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_PRECHECKEXPLANATIONRENDERED)
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_EXPENSEEDITOR)
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_EXPENSEDETAIL)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, originalDocument = globalThis.document
afterEach(() => { Object.assign(api, originals); globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage; globalThis.document = originalDocument; bindAuthenticationActor(null) })
const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const checkedAt = new Date(Date.now() - 10_000).toISOString(), validUntil = new Date(Date.now() + 120_000).toISOString()
const finding = 'precheck:finding[0]', sourceIds = ['precheck:result', finding]
const source = id => ({ reference: { sourceId: id, contentDigest: 'a'.repeat(64) }, label: id === finding ? '整单检查问题' : '本次预检结论',
  content: JSON.stringify(id === finding ? { stage: 'BUDGET', nature: 'REJECTED', code: 'BUDGET_INSUFFICIENT' } : { status: 'BLOCKED', checkedAt, accountingDate: '2026-10-03' }) })
const input = () => ({ precheckId: uuid(3), applicationVersion: 2, financialVersion: 4, attempt: 1, result: 'BLOCKED', checkedAt, validUntil,
  enabled: true, unavailableCode: null, providerId: 'fixture', model: 'fixture-v1', destination: '127.0.0.1:9899', targetDigest: 'b'.repeat(64), sources: sourceIds.map(source) })
const summary = () => ({ id: uuid(2), precheckId: uuid(3), applicationVersion: 2, financialVersion: 4, attempt: 1, status: 'COMPLETED', version: 3, createdAt: checkedAt })
const page = (number = 0) => ({ items: [summary()], total: 1, page: number, pageSize: 20 })
const detail = () => ({ ...summary(), result: 'BLOCKED', checkedAt, validUntil, startedAt: checkedAt, completedAt: checkedAt,
  sources: sourceIds.map(source), suggestion: { providerId: 'fixture', modelVersion: 'fixture-v1', promptVersion: 'expense-precheck-explanation-v1',
    items: [{ issueSourceId: finding, explanation: '这次检查返回预算不足。', corrections: ['请联系预算负责人核对可用额度，再重新预检。'], evidence: [source(finding).reference] }] },
  failure: null, review: null, canAdopt: true, unavailableCode: null })
const queued = () => ({ ...detail(), status: 'QUEUED', version: 1, startedAt: null, completedAt: null, suggestion: null, canAdopt: false, unavailableCode: 'AGENT_RUN_NOT_REVIEWABLE' })
const receipt = (status = 'QUEUED') => ({ id: uuid(2), status, version: status === 'QUEUED' ? 1 : 4 })
const stubReads = () => {
  api.expensePrecheckOptions = async () => ({ applicationVersion: 2, financialVersion: 4, latestPrecheckId: uuid(3) })
  api.precheckExplanationInput = async () => input(); api.precheckExplanationRuns = async (_, n) => page(n); api.precheckExplanationRun = async () => detail()
}
const settle = () => new Promise(resolve => setImmediate(resolve))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
let scope = 0
function panel(Component = Panel, initial = {}) {
  const props = reactive({ reportId: uuid(1), scopeKey: `demo/explanation-${++scope}`, applicationVersion: 2, financialVersion: 4,
    editable: true, locked: false, applicationDirty: false, ...initial }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, ctx) => Component.setup(props, ctx), render: () => null }, {
    ...props, onBusy: value => events.push(['busy', value]), onDirty: value => events.push(['dirty', value]), onClose: () => events.push(['close'])
  })
  return { state: app.mount({}).$.setupState, props, events, close: () => app.unmount() }
}
const unreadable = error => error.code === 'RESPONSE_UNREADABLE'

test('输入、分页及原解释契约绑定预检、运行、双版本和来源摘要', () => {
  assert.deepEqual(model.readExplanationInput(input(), uuid(3)), input())
  assert.deepEqual(model.readExplanationPage(page(), 0), page())
  assert.deepEqual(model.readExplanationDetail(detail(), uuid(2)), detail())
  assert.deepEqual(model.readExplanationDetail(queued(), uuid(2)), queued())
  assert.throws(() => model.readExplanationInput(input(), uuid(4)), unreadable)
  for (const change of [v => { v.applicationVersion = 0 }, v => { v.sources.push(source('form:secret')) },
    v => { v.sources.push(source(finding)) }, v => { v.enabled = false }, v => { v.targetDigest = null },
    v => { v.result = 'READY' }, v => { v.checkedAt = null }, v => { v.validUntil = checkedAt }]) {
    const v = input(); change(v); assert.throws(() => model.readExplanationInput(v, uuid(3)), unreadable)
  }
  assert.throws(() => model.readExplanationPage(page(1), 0), unreadable)
  assert.throws(() => model.readExplanationPage({ ...page(), items: [summary(), summary()] }, 0), unreadable)
  for (const change of [v => { v.id = uuid(8) }, v => { v.version = 8 }, v => { v.status = 'APPROVED' },
    v => { v.suggestion.items[0].evidence[0].contentDigest = 'f'.repeat(64) }, v => { v.suggestion.items[0].issueSourceId = 'precheck:finding[9]' },
    v => { v.suggestion.items[0].evidence = [source('precheck:result').reference] }, v => { v.suggestion.items = [] },
    v => { v.suggestion.items[0].corrections = [] }, v => { v.suggestion.items[0].explanation = 'x'.repeat(1001) },
    v => { v.completedAt = null }, v => { v.failure = 'MODEL_TIMEOUT' }, v => { v.status = 'ADOPTED'; v.version = 4 }]) {
    const v = detail(); change(v); assert.throws(() => model.readExplanationDetail(v, uuid(2)), unreadable)
  }
})

test('预检历史拒绝未知状态与缺失版本同时出现，不把两个 undefined 当成合法版本', () => {
  for (const status of ['UNKNOWN', 'constructor', 'toString']) {
    const item = { ...summary(), status }; delete item.version
    assert.throws(() => model.readExplanationPage({ ...page(), items: [item] }, 0), unreadable)
  }
})

test('预检详情拒绝未知状态与缺失版本，即使其余结束时间和空结果形状合法', () => {
  for (const status of ['UNKNOWN', 'constructor', 'toString']) {
    const value = { ...detail(), status, suggestion: null, canAdopt: false, unavailableCode: 'AGENT_RUN_NOT_REVIEWABLE' }
    delete value.version
    assert.throws(() => model.readExplanationDetail(value, uuid(2)), unreadable)
  }
})

test('可用 READY、失效目录和已复核历史保留真实状态，不补造可采纳性', () => {
  const ready = { ...input(), result: 'READY', sources: [source('precheck:result')] }
  assert.deepEqual(model.readExplanationInput(ready, uuid(3)), ready)
  const unavailable = { ...input(), enabled: false, unavailableCode: 'FACTS_EXPIRED', providerId: null, model: null, destination: null, targetDigest: null, sources: [] }
  assert.deepEqual(model.readExplanationInput(unavailable, uuid(3)), unavailable)
  const reviewed = { ...detail(), status: 'ADOPTED', version: 4, canAdopt: false, unavailableCode: 'AGENT_RUN_NOT_REVIEWABLE',
    review: { actor: 'alice', at: checkedAt, selectedIssueIds: [finding] } }
  assert.deepEqual(model.readExplanationDetail(reviewed, uuid(2)), reviewed)
  reviewed.review.selectedIssueIds = ['precheck:finding[9]']; assert.throws(() => model.readExplanationDetail(reviewed, uuid(2)), unreadable)
  const failed = { ...queued(), status: 'FAILED', version: 3, startedAt: checkedAt, completedAt: checkedAt, failure: 'MODEL_TIMEOUT' }
  assert.deepEqual(model.readExplanationDetail(failed, uuid(2)), failed)
})

test('目录和人工结果夹具通过公开 OpenAPI 模型，不把范围测试当作实际运行', () => {
  const spec = JSON.parse(readFileSync(new URL('../../agentflow-server/src/main/resources/api/openapi.json', import.meta.url)))
  const ajv = new Ajv2020({ strict: false }); addFormats(ajv); ajv.addSchema({ $id: 'agentflow', components: spec.components })
  for (const [name, v] of [['PrecheckExplanationInputOptions', input()], ['PrecheckExplanationPage', page()], ['PrecheckExplanationDetail', detail()], ['PrecheckExplanationReceipt', receipt()]]) {
    const validate = ajv.compile({ $ref: 'agentflow#/components/schemas/' + name }); assert.equal(validate(v), true, name + JSON.stringify(validate.errors))
  }
})

test('发送必须明确选择结论和问题，问题数和 UTF-8 大小有界且原始金额字节保持', () => {
  assert.deepEqual(model.explanationSelection(input(), sourceIds), sourceIds)
  for (const ids of [[], [finding], ['precheck:result'], [...sourceIds, finding], [...sourceIds, 'expense:line[9]']]) assert.throws(() => model.explanationSelection(input(), ids))
  const v = input(), raw = '{"claimedGross":{"value":999999999999999.99,"currency":"CNY"}}'
  v.sources.push({ ...source('expense:line[1]'), content: raw }); model.explanationSelection(v, [...sourceIds, 'expense:line[1]']); assert.equal(v.sources[2].content, raw)
  v.sources[2].content = '汉'.repeat(22000); assert.throws(() => model.explanationSelection(v, [...sourceIds, 'expense:line[1]']), /64 KiB/)
  const many = { ...input(), sources: [source('precheck:result'), ...Array.from({ length: 21 }, (_, i) => source(`precheck:finding[${i}]`))] }
  assert.throws(() => model.explanationSelection(many, many.sources.map(s => s.reference.sourceId)), /20 个问题/)
})

test('真实组件默认不勾选，生成只发来源标识和双版本，不写财务或审批', async () => {
  stubReads(); const calls = [], p = panel()
  api.generatePrecheckExplanation = async (...args) => { calls.push(args); return receipt() }; api.precheckExplanationRun = async () => queued()
  api.reviseExpense = api.submitExpense = async () => { throw new Error('Unexpected financial mutation') }
  try {
    await settle(); assert.deepEqual(p.state.sourceIds, []); assert.equal(p.state.canGenerate, false)
    p.state.sourceIds = [...sourceIds]; assert.equal(p.state.canGenerate, true); await p.state.generate()
    assert.deepEqual(calls, [[uuid(1), { precheckId: uuid(3), applicationVersion: 2, financialVersion: 4, targetDigest: 'b'.repeat(64), sourceIds }]])
    assert.equal(p.state.detail.status, 'QUEUED'); assert.equal(p.state.dirty, false); assert.equal(p.state.sending, false)
    assert.equal(model.focusedExplanation(p.props.scopeKey, uuid(1)), uuid(2))
  } finally { p.close() }
})

test('人工采纳只记录明确问题，过期或双版本变化后拒绝采纳但允许放弃', async () => {
  stubReads(); const calls = [], p = panel()
  api.reviewPrecheckExplanation = async (...args) => { calls.push(args); return receipt(args[2].action === 'ADOPT' ? 'ADOPTED' : 'DISMISSED') }
  try {
    await settle(); await p.state.loadDetail(uuid(2)); await p.state.review('ADOPT'); assert.equal(calls.length, 0)
    p.state.selected = [finding]; p.state.comment = '核对后补正'; await p.state.review('ADOPT')
    assert.deepEqual(calls[0], [uuid(1), uuid(2), { expectedRunVersion: 3, action: 'ADOPT', comment: '核对后补正', selectedIssueIds: [finding] }])
    p.state.selected = [finding]; p.props.financialVersion++; await p.state.review('ADOPT'); assert.equal(calls.length, 1)
    await p.state.review('DISMISS'); assert.deepEqual(calls[1][2], { expectedRunVersion: 3, action: 'DISMISS', comment: '' })
    assert.equal(p.state.selected.length, 0)
  } finally { p.close() }
})

test('来源选择和人工复核互斥，未保存费用、外部写入锁定及截止时刻均阻止发送', async () => {
  stubReads(); const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(2)); p.state.sourceIds = [...sourceIds]
    assert.equal(p.state.canReview, false); p.props.applicationDirty = true; assert.equal(p.state.canGenerate, false)
    p.props.applicationDirty = false; p.props.locked = true; assert.equal(p.state.canGenerate, false)
    p.props.locked = false; p.state.now = Date.parse(validUntil); assert.equal(p.state.canGenerate, false); assert.equal(p.state.current, false)
    p.state.clearSelection(); assert.equal(p.state.canReview, true)
  } finally { p.close() }
})

test('新的预检出现后即使费用版本未变，也立即关闭旧解释采纳入口', async () => {
  stubReads(); const p = panel(); let resolve
  try {
    await settle(); await p.state.loadDetail(uuid(2)); p.state.selected = [finding]; assert.equal(p.state.current, true)
    api.precheckExplanationInput = () => new Promise(done => { resolve = done })
    api.expensePrecheckOptions = async () => ({ applicationVersion: 2, financialVersion: 4, latestPrecheckId: uuid(4) })
    p.props.refreshVersion = 1; await settle(); assert.equal(p.state.current, false)
    resolve({ ...input(), precheckId: uuid(4) }); await settle(); assert.equal(p.state.current, false)
    assert.deepEqual(p.state.selected, [finding]); assert.equal(p.state.canReview, true)
  } finally { resolve?.(input()); p.close() }
})

test('账号切换中断读取并清空选择，旧响应不能回填新账号', async () => {
  stubReads(); const reads = []; api.precheckExplanationRun = (_, id, signal) => new Promise(resolve => reads.push({ id, signal, resolve }))
  const p = panel()
  try {
    await settle(); const old = p.state.loadDetail(uuid(2)); p.state.comment = '原账号说明'; p.props.scopeKey = 'demo/other'; await settle()
    assert.equal(reads[0].signal.aborted, true); assert.equal(p.state.comment, ''); reads[0].resolve(detail()); await old
    assert.equal(p.state.detail, null); assert.equal(p.state.selectedId, '')
  } finally { p.close() }
})

test('来源读取切换单据后不继续发出旧预检请求', async () => {
  stubReads(); let resolve, signal, calls = 0
  api.expensePrecheckOptions = (_, value) => { signal = value; return new Promise(done => { resolve = done }) }
  api.precheckExplanationInput = async () => { calls++; return input() }
  const p = panel()
  try {
    const originalResolve = resolve, originalSignal = signal
    p.props.editable = false; originalResolve({ applicationVersion: 2, financialVersion: 4, latestPrecheckId: uuid(3) }); await settle()
    assert.equal(originalSignal.aborted, true); assert.equal(calls, 0); assert.equal(p.state.options, null)
  } finally { p.close() }
})

test('详情超时解除锁定且不接纳迟到正文，失权清空目录历史及说明', async () => {
  stubReads(); const p = panel(), set = globalThis.setTimeout, clear = globalThis.clearTimeout; let done, timeout
  try {
    await settle(); globalThis.setTimeout = callback => { timeout = callback; return 1 }; globalThis.clearTimeout = timer => { if (typeof timer === 'object') clear(timer) }
    api.precheckExplanationRun = () => new Promise(resolve => { done = resolve })
    const reading = p.state.loadDetail(uuid(2)); timeout(); await reading; assert.equal(p.state.loading.detail, false)
    done(detail()); await settle(); assert.equal(p.state.detail, null); assert.match(p.state.errors.detail, /超时/)
    globalThis.setTimeout = set; globalThis.clearTimeout = clear; stubReads(); await p.state.loadDetail(uuid(2)); p.state.comment = '私有说明'
    api.precheckExplanationInput = async () => { throw { status: 404, code: 'NOT_FOUND' } }; await p.state.loadInput()
    assert.equal(p.state.detail, null); assert.equal(p.state.page, null); assert.equal(p.state.options, null); assert.equal(p.state.comment, ''); assert.equal(p.state.denied, true)
  } finally { globalThis.setTimeout = set; globalThis.clearTimeout = clear; p.close() }
})

test('成功写入后的重读有界，分页失败不丢原运行，也不跨账号继续读详情', async () => {
  stubReads(); const p = panel(), set = globalThis.setTimeout, clear = globalThis.clearTimeout, timers = [], details = []; let resolve
  try {
    await settle(); globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return timers.length }; globalThis.clearTimeout = timer => { if (typeof timer === 'object') clear(timer) }
    api.generatePrecheckExplanation = async () => receipt(); api.precheckExplanationRuns = () => new Promise(done => { resolve = done })
    api.precheckExplanationRun = async (_, id) => { details.push(id); return queued() }
    p.state.sourceIds = [...sourceIds]; const writing = p.state.generate(); await settle(); timers.find(t => t.delay === 12_000).callback(); await writing
    assert.equal(p.state.sending, false); assert.equal(p.state.detail.id, uuid(2)); assert.deepEqual(details, [uuid(2)])
    assert.match(p.state.errors.list, /超时/); assert.equal(model.focusedExplanation(p.props.scopeKey, uuid(1)), uuid(2)); resolve(page())
  } finally { globalThis.setTimeout = set; globalThis.clearTimeout = clear; p.close() }
})

test('写入等待期间切换身份不触发旧单据的后续读取', async () => {
  stubReads(); const p = panel(), reads = []; let finish
  try {
    await settle(); api.generatePrecheckExplanation = () => new Promise(resolve => { finish = resolve })
    api.precheckExplanationRun = async (...args) => { reads.push(args); return detail() }
    p.state.sourceIds = [...sourceIds]; const writing = p.state.generate(); p.props.scopeKey = 'demo/new-owner'
    finish(receipt()); await writing; await settle(); assert.deepEqual(reads, []); assert.equal(p.state.sending, false); assert.equal(p.state.notice, '')
  } finally { p.close() }
})

test('认证失败与网络结果未知使用平台恢复语义，不允许重复发起新请求', async () => {
  for (const cause of [{ status: 0, code: 'RESPONSE_UNREADABLE' }, { status: 403, code: 'CSRF_INVALID' }]) {
    stubReads(); const p = panel(); let calls = 0
    api.generatePrecheckExplanation = async () => { calls++; throw cause }
    try {
      await settle(); p.state.sourceIds = [...sourceIds]; await p.state.generate(); assert.equal(p.state.unknown, true)
      await p.state.generate(); assert.equal(calls, 1)
    } finally { p.close() }
  }
})

test('实际模板转义来源、模型文本和复核说明，独立显示原检查结论', async () => {
  stubReads(); const v = detail(); v.suggestion.items[0].explanation = '<img src=x onerror=alert(1)>'; v.sources[1].content = '<script>alert(2)</script>'
  v.validUntil = checkedAt; api.precheckExplanationInput = async () => ({ ...input(), validUntil: checkedAt })
  api.precheckExplanationRun = async () => v
  const props = { reportId: uuid(1), scopeKey: 'demo/explanation-render', applicationVersion: 2, financialVersion: 4, editable: true, locked: false, applicationDirty: false }
  model.rememberExplanation(props.scopeKey, props.reportId, uuid(2))
  const html = await renderToString(createSSRApp({ ...Rendered, async setup(props, ctx) { const render = Rendered.setup(props, ctx); await settle(); await settle(); return render } }, props))
  assert.match(html, /&lt;img/); assert.match(html, /&lt;script&gt;/); assert.ok(!html.includes('<script>'))
  assert.match(html, /原检查结论：费用检查未通过/); assert.match(html, /记录采纳所选解释/); assert.match(html, /查看将发送的原文/)
})

test('读取禁用缓存；错误成功回执保留原请求字节及幂等键供恢复', async () => {
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'explanation-request' })
  const reads = []; globalThis.fetch = async (url, options) => { reads.push(options); return Response.json(url.includes('/input?') ? input() : url.includes('?') ? page() : detail()) }
  await api.precheckExplanationInput(uuid(1), uuid(3)); await api.precheckExplanationRuns(uuid(1), 0); await api.precheckExplanationRun(uuid(1), uuid(2))
  assert.ok(reads.every(r => r.cache === 'no-store'))
  const sent = [], response = receipt('ADOPTED')
  globalThis.fetch = async (_, options) => { sent.push(options); return Response.json({ ...response, id: sent.length === 1 ? uuid(9) : uuid(2) }) }
  const body = { expectedRunVersion: 3, action: 'ADOPT', selectedIssueIds: [finding], comment: '原说明' }
  await assert.rejects(api.reviewPrecheckExplanation(uuid(1), uuid(2), body), unreadable); assert.equal(writeRequests.pending().length, 1)
  body.comment = '后续修改'; const recovered = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.deepEqual(recovered.result, response); assert.equal(writeRequests.pending().length, 0)
  assert.equal(sent[0].body, sent[1].body); assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
})

test('真实恢复入口回到原运行并解锁已挂载页面，不刷新丢失费用草稿', async () => {
  stubReads(); const p = panel(), box = value => ({ value })
  try {
    await settle(); p.state.sourceIds = [...sourceIds]; p.state.unknown = true
    const pending = { id: 'request', path: model.explanationPath(uuid(1)), sending: false }
    globalThis.document = { querySelector: () => null }
    const env = { pendingWrites: box([pending]), draftScope: box(''), actorScope: box(p.props.scopeKey), acknowledgeExplanation: model.acknowledgeExplanation,
      confirmReplaceDefinition: async (_, action) => action(), busy: box(false), recoveryError: box(''), notice: box(''), recordApplicationId: box(uuid(8)), recordRefresh: box(0), templateRefresh: box(0),
      writeRequests: { recover: async () => ({ request: pending, result: receipt() }) }, refreshWorkspace: async () => {}, nextTick, workspace: box(null), errorMessage: error => error.message }
    await createRecovery(env)('request'); await settle()
    assert.equal(env.recoveryError.value, ''); assert.equal(env.recordRefresh.value, 0); assert.equal(env.templateRefresh.value, 0)
    assert.equal(p.state.unknown, false); assert.equal(p.state.dirty, false); assert.equal(p.state.detail.id, uuid(2)); assert.match(env.notice.value, /费用金额、检查结论和审批状态保持不变/)
    assert.equal(model.focusedExplanation('other', uuid(1)), '')
  } finally { p.close() }
})

test('费用编辑器及本人详情聚合解释锁定，其他子组件空闲事件不解除保护', async () => {
  api.financeCatalog = async () => ({ validUntil, legalEntities: [] })
  const editor = panel(Editor)
  try {
    await settle(); editor.state.explanationDirty = true; editor.state.close(); assert.equal(editor.state.blocked, true); assert.ok(!editor.events.some(e => e[0] === 'close'))
    editor.state.explanationDirty = false; editor.state.explanationBusy = true; editor.state.leave(); assert.ok(!editor.events.some(e => e[0] === 'close'))
    editor.state.explanationBusy = false; editor.state.close(); assert.ok(editor.events.some(e => e[0] === 'close'))
  } finally { editor.close() }
  api.expenseReport = async () => { throw { status: 404 } }; api.expenseWorkflow = async () => { throw { status: 404 } }
  const d = panel(Detail, { applicationId: uuid(7) })
  try {
    await settle(); d.state.explanationDirty = true; d.state.businessBusy = true; d.state.businessBusy = false
    assert.equal(d.state.actionsLocked, true); assert.deepEqual(d.events.at(-1), ['busy', true])
    d.state.explanationDirty = false; assert.deepEqual(d.events.at(-1), ['busy', false])
  } finally { d.close() }
})
