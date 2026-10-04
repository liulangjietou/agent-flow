import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { readFileSync } from 'node:fs'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'

const model = await import(process.env.AGENTFLOW_TEST_EXPENSE_RISK)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSERISKPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_EXPENSERISKRENDERED)
const { default: Scope } = await import(process.env.AGENTFLOW_TEST_EXPENSERISKSCOPE)
const { default: ScopeRendered } = await import(process.env.AGENTFLOW_TEST_EXPENSERISKSCOPERENDERED)
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_EXPENSEDETAIL)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const originals = { ...api }, fetch = globalThis.fetch, storage = globalThis.localStorage, document = globalThis.document
afterEach(() => { Object.assign(api, originals); globalThis.fetch = fetch; globalThis.localStorage = storage; globalThis.document = document; bindAuthenticationActor(null) })
const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const at = '2026-10-04T10:00:00Z', concern = 'expense:risk[1]'
const ids = ['expense:document[1]', 'expense:document[2]', 'expense:coverage', concern]
const source = id => ({ reference: { sourceId: id, contentDigest: 'a'.repeat(64) }, label: id, content: '{"fact":"<script>unsafe()</script>"}' })
const request = () => ({ taskId: 'task-1', scope: { documents: [{ reportId: uuid(1), roundNo: 1, lineNos: [1] }, { reportId: uuid(2), roundNo: 1, lineNos: [1] }] } })
const observations = () => [{ sourceId: concern, kind: 'CROSS_DOCUMENT', documents: [1, 2] }]
const input = () => ({ enabled: true, unavailableCode: null, inputDigest: 'b'.repeat(64), targetDigest: 'c'.repeat(64), providerId: 'fixture', model: 'fixture-v1', destination: '127.0.0.1:9899', concerns: observations(), sources: ids.map(source) })
const summary = () => ({ id: uuid(10), status: 'COMPLETED', version: 3, createdAt: at })
const detail = () => ({ ...summary(), taskId: 'task-1', roundNo: 1, startedAt: at, completedAt: at, concerns: observations(), sources: ids.map(source), reviewable: true, adoptable: true, unavailableCode: null,
  suggestion: { providerId: 'fixture', modelVersion: 'fixture-v1', promptVersion: 'expense-risk-explanation-v1', items: [{ concernSourceId: concern, kind: 'CROSS_DOCUMENT', explanation: '<script>unsafe()</script> 同类费用需核对。', limitations: '只覆盖两份明确选择的单据。', checks: ['核对实际业务目的。'], evidence: ids.map(id => source(id).reference) }] }, failure: null, review: null })
const queued = () => ({ ...detail(), status: 'QUEUED', version: 1, startedAt: null, completedAt: null, reviewable: false, adoptable: false, unavailableCode: 'AGENT_RUN_NOT_REVIEWABLE', suggestion: null })
const page = (n = 0) => ({ items: [summary()], total: 1, page: n, pageSize: 20 })
const receipt = (status = 'QUEUED') => ({ id: uuid(10), status, version: status === 'QUEUED' ? 1 : 4 })
const report = (n = 1) => ({ id: uuid(n), applicationId: uuid(n + 20), businessNo: `EXP-${n}`, applicationStatus: 'IN_APPROVAL', applicationVersion: 4, financialVersion: 2, roundNo: 1, editable: false,
  content: { legalEntityId: uuid(30), type: 'DAILY', title: '合成费用', lines: [{ lineNo: 1, incurredOn: '2026-10-04', categoryCode: 'OFFICE', claimedGross: { value: '100.00', currency: 'CNY' } }], advanceOffsets: [] }, financialRound: { roundNo: 1 } })
const calendars = () => ({ items: [{ id: uuid(40), key: 'WORK', name: '工作日历', zoneId: 'UTC', revision: 1 }], nextAfterKey: null })
const reads = () => {
  api.expenseRiskInput = async () => input(); api.expenseRiskRuns = async (_, __, n) => page(n); api.expenseRiskRun = async () => detail(); api.expenseRiskCalendars = async () => calendars()
  api.application = async id => ({ id, createdBy: 'alice', businessReference: { type: 'EXPENSE', id: id === uuid(21) ? uuid(1) : uuid(2) } })
  api.searchVisibleApplications = async () => ({ items: [{ id: uuid(22), businessNo: 'EXP-2', title: '对照费用', roundNo: 1 }], nextCursor: null })
  api.expenseReport = async () => report(2)
}
const settle = () => new Promise(resolve => setImmediate(resolve))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
let sequence = 0
function mount(Component = Panel, overrides = {}) {
  const props = reactive({ report: report(), taskId: 'task-1', scopeKey: `demo/risk-${++sequence}`, locked: false, resetVersion: 0, ...overrides }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, ctx) => Component.setup(props, ctx), render: () => null }, {
    ...props, onBusy: v => events.push(['busy', v]), onDirty: v => events.push(['dirty', v]), onPrepare: v => events.push(['prepare', v]), onChange: () => events.push(['change'])
  })
  return { state: app.mount({}).$.setupState, props, events, close: () => app.unmount() }
}
const unreadable = e => e.code === 'RESPONSE_UNREADABLE'

test('范围绑定主单原轮次，跨单总行数、重复单据及无效行号在预览前拒绝', () => {
  const selected = request().scope
  assert.deepEqual(model.riskScope(uuid(1), 1, selected.documents), selected)
  for (const values of [[], [selected.documents[1]], [...selected.documents, selected.documents[0]], [{ ...selected.documents[0], lineNos: [1, 1] }], [{ ...selected.documents[0], lineNos: [201] }], [{ ...selected.documents[0], lineNos: [] }],
    [{ ...selected.documents[0], lineNos: Array.from({ length: 200 }, (_, i) => i + 1) }, selected.documents[1]]]) assert.throws(() => model.riskScope(uuid(1), 1, values))
  assert.throws(() => model.riskScope(uuid(1), 2, selected.documents))
  const copy = model.riskScope(uuid(1), 1, selected.documents); copy.documents[0].lineNos.push(2); assert.deepEqual(selected.documents[0].lineNos, [1])
})
test('预览必须包括对应的全部单据、覆盖和观察，禁用目录不能夹带正文', () => {
  assert.deepEqual(model.readRiskInput(input(), request().scope), input())
  for (const change of [v => { v.sources.pop() }, v => { v.sources.splice(0, 1) }, v => { v.sources.push(source('form:secret')) }, v => { v.concerns[0].documents = [1] }, v => { v.concerns[0].kind = '__proto__' }, v => { v.targetDigest = null }, v => { v.enabled = false }, v => { v.concerns = [] }]) {
    const v = input(); change(v); assert.throws(() => model.readRiskInput(v, request().scope), unreadable)
  }
  assert.throws(() => model.readRiskInput(input(), { documents: request().scope.documents.slice(0, 1) }), unreadable)
  const disabled = { enabled: false, unavailableCode: 'NO_RISK_OBSERVATIONS', inputDigest: null, targetDigest: null, providerId: null, model: null, destination: null, concerns: [], sources: [] }
  assert.deepEqual(model.readRiskInput(disabled, request().scope), disabled)
})
test('发送逐项显式选择，拒绝缺失范围、未知来源、重复和超出编码大小', () => {
  assert.deepEqual(model.riskSelection(input(), ids), ids)
  for (const selected of [[], ids.slice(1), ids.slice(0, -1), [...ids, concern], [...ids, 'expense:risk[2]']]) assert.throws(() => model.riskSelection(input(), selected))
  const v = input(); v.sources[0].content = '汉'.repeat(23000); assert.throws(() => model.riskSelection(v, ids), /64 KiB/)
})
test('原解释逐条核对种类、范围、摘要、状态与轮次，损坏内容不能启用采纳', () => {
  assert.deepEqual(model.readRiskDetail(detail(), uuid(10), 1), detail()); assert.deepEqual(model.readRiskDetail(queued(), uuid(10), 1), queued())
  for (const change of [v => { v.roundNo = 2 }, v => { v.version = 4 }, v => { v.status = 'APPROVED' }, v => { v.reviewable = false }, v => { v.completedAt = null }, v => { v.failure = 'MODEL_TIMEOUT' },
    v => { v.suggestion.items[0].evidence.pop() }, v => { v.suggestion.items[0].evidence[0].contentDigest = 'f'.repeat(64) }, v => { v.suggestion.items[0].kind = 'SAME_DAY' }, v => { v.suggestion.items[0].limitations = '' },
    v => { v.suggestion.items[0].checks = [] }, v => { v.suggestion.items[0].explanation = 'x'.repeat(1001) }, v => { v.suggestion.items.push(v.suggestion.items[0]) }, v => { v.suggestion.promptVersion = 'other' }]) {
    const v = detail(); change(v); assert.throws(() => model.readRiskDetail(v, uuid(10), 1), unreadable)
  }
  const stale = detail(); stale.adoptable = false; stale.unavailableCode = 'AGENT_INPUT_CHANGED'; assert.equal(model.readRiskDetail(stale, uuid(10), 1).reviewable, true)
})
test('人工复核记录限定原观察，放弃不能夹带采纳项', () => {
  const v = detail(); Object.assign(v, { status: 'ADOPTED', version: 4, reviewable: false, adoptable: false, unavailableCode: 'AGENT_RUN_NOT_REVIEWABLE', review: { actor: 'manager', at, selectedConcernIds: [concern] } })
  assert.equal(model.readRiskDetail(v, uuid(10), 1).status, 'ADOPTED'); v.review.selectedConcernIds = ['expense:risk[9]']; assert.throws(() => model.readRiskDetail(v, uuid(10), 1), unreadable)
  v.status = 'DISMISSED'; v.review.selectedConcernIds = [concern]; assert.throws(() => model.readRiskDetail(v, uuid(10), 1), unreadable); v.review.selectedConcernIds = []; assert.equal(model.readRiskDetail(v, uuid(10), 1).status, 'DISMISSED')
})
test('分页和日历游标校验绑定当前目录，不接受重复记录及错页', () => {
  assert.deepEqual(model.readRiskPage(page(), 0), page()); assert.throws(() => model.readRiskPage(page(), 1), unreadable)
  assert.throws(() => model.readRiskPage({ ...page(), items: [summary(), summary()] }, 0), unreadable)
  assert.deepEqual(model.readRiskCalendars(calendars()), calendars()); assert.throws(() => model.readRiskCalendars({ ...calendars(), nextAfterKey: 'OTHER' }), unreadable)
})
test('缺少版本并同时伪造状态不能绕过摘要校验', () => {
  const row = summary(); delete row.version; row.status = '__proto__'
  assert.throws(() => model.readRiskPage({ ...page(), items: [row] }, 0), unreadable)
})
test('API 只读预览不分配幂等键，错误成功回执保留同键同正文恢复', async () => {
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'risk-request' }); const calls = []
  globalThis.fetch = async (_, options) => { calls.push(options); return Response.json(input()) }
  await api.expenseRiskInput(uuid(1), request(), new AbortController().signal)
  assert.equal(calls[0].method, 'POST'); assert.equal(calls[0].cache, 'no-store'); assert.equal(calls[0].headers['Idempotency-Key'], undefined); assert.equal(writeRequests.pending().length, 0)
  const body = { expectedRunVersion: 3, action: 'DISMISS', comment: '原说明' }; calls.length = 0
  globalThis.fetch = async (_, options) => { calls.push(options); return Response.json({ ...receipt('DISMISSED'), id: calls.length === 1 ? uuid(11) : uuid(10) }) }
  await assert.rejects(api.reviewExpenseRisk(uuid(1), uuid(10), body), unreadable); assert.equal(writeRequests.pending().length, 1)
  body.comment = '新说明'; const recovered = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.deepEqual(recovered.result, receipt('DISMISSED')); assert.equal(writeRequests.pending().length, 0); assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key'])
})
test('界面不自动选择发送来源，生成后定位原记录并清除未提交范围', async () => {
  reads(); const p = mount(); let sent
  try {
    await settle(); await p.state.preview(request()); assert.deepEqual(p.state.sourceIds, []); assert.equal(p.state.canGenerate, false)
    p.state.scopeDirty = true; p.state.sourceIds = [...ids]; api.generateExpenseRisk = async (_, body) => { sent = body; return receipt() }; api.expenseRiskRun = async () => queued()
    await p.state.generate(); assert.deepEqual(sent, { ...request(), inputDigest: 'b'.repeat(64), targetDigest: 'c'.repeat(64), sourceIds: ids }); assert.equal(p.state.detail.id, uuid(10)); assert.equal(p.state.dirty, false); assert.equal(p.state.sending, false)
  } finally { p.close() }
})
test('范围变更使旧预览失效，任务及账号变化丢弃迟到来源', async () => {
  reads(); const p = mount(); let finish, signal
  try {
    await settle(); await p.state.preview(request()); p.state.sourceIds = [...ids]; p.state.invalidateInput(); assert.equal(p.state.options, null); assert.deepEqual(p.state.sourceIds, [])
    api.expenseRiskInput = (_, __, s) => { signal = s; return new Promise(resolve => { finish = resolve }) }
    const waiting = p.state.preview(request()); p.props.scopeKey = 'other-user'; finish(input()); await waiting; assert.equal(signal.aborted, true); assert.equal(p.state.options, null)
  } finally { p.close() }
})
test('当前复核资格与采纳资格分开，旧依据只允许当前决定人放弃', async () => {
  reads(); const p = mount(); const sent = []
  try {
    await settle(); await p.state.loadDetail(uuid(10)); p.state.detail.adoptable = false; p.state.detail.unavailableCode = 'AGENT_INPUT_CHANGED'; p.state.selected = [concern]
    api.reviewExpenseRisk = async (_, __, body) => { sent.push(body); return receipt('DISMISSED') }
    await p.state.review('ADOPT'); assert.equal(sent.length, 0); await p.state.review('DISMISS'); assert.equal(sent.length, 1); assert.equal(sent[0].selectedConcernIds, undefined)
    p.state.detail.reviewable = false; await p.state.review('DISMISS'); assert.equal(sent.length, 1)
  } finally { p.close() }
})
test('任何原来源失权清空风险正文与说明，读取超时不接受迟到响应', async () => {
  reads(); const p = mount(), set = globalThis.setTimeout, clear = globalThis.clearTimeout; let finish, timeout
  try {
    await settle(); globalThis.setTimeout = callback => { timeout = callback; return 1 }; globalThis.clearTimeout = id => { if (typeof id === 'object') clear(id) }
    api.expenseRiskRun = () => new Promise(resolve => { finish = resolve }); const reading = p.state.loadDetail(uuid(10)); timeout(); await reading; finish(detail()); await settle()
    assert.equal(p.state.detail, null); assert.equal(p.state.loading.detail, false); assert.match(p.state.errors.detail, /超时/)
    globalThis.setTimeout = set; globalThis.clearTimeout = clear; reads(); await p.state.loadDetail(uuid(10)); p.state.comment = '私有意见'
    api.expenseRiskRun = async () => { throw { status: 403, code: 'FORBIDDEN' } }; await p.state.loadDetail(uuid(10), true)
    assert.equal(p.state.denied, true); assert.equal(p.state.detail, null); assert.equal(p.state.page, null); assert.equal(p.state.comment, '')
  } finally { globalThis.setTimeout = set; globalThis.clearTimeout = clear; p.close() }
})
test('结果未知禁止重新生成，恢复定位按账号、主单与原轮次隔离', async () => {
  reads(); const p = mount(); let calls = 0
  try {
    await settle(); await p.state.preview(request()); p.state.sourceIds = [...ids]; api.generateExpenseRisk = async () => { calls++; throw { status: 0, code: 'RESPONSE_UNREADABLE' } }
    await p.state.generate(); await p.state.generate(); assert.equal(calls, 1); assert.equal(p.state.unknown, true)
    model.acknowledgeRisk('other', model.riskPath(uuid(1)), JSON.stringify(request()), receipt()); assert.equal(p.state.unknown, true)
    const other = request(); other.scope.documents[0].roundNo = 2; model.acknowledgeRisk(p.props.scopeKey, model.riskPath(uuid(1)), JSON.stringify(other), receipt()); assert.equal(p.state.unknown, true)
    model.acknowledgeRisk(p.props.scopeKey, model.riskPath(uuid(1)), JSON.stringify(request()), receipt()); await settle(); assert.equal(p.state.unknown, false); assert.equal(p.state.detail.id, uuid(10)); assert.equal(calls, 1)
  } finally { p.close() }
})
test('写入期间切换身份不会接续旧单据读取或显示成功通知', async () => {
  reads(); const p = mount(); let finish; const loaded = []
  try {
    await settle(); await p.state.preview(request()); p.state.sourceIds = [...ids]; api.generateExpenseRisk = () => new Promise(resolve => { finish = resolve }); api.expenseRiskRun = async (_, id) => { loaded.push(id); return detail() }
    const writing = p.state.generate(); p.props.scopeKey = 'other-after-send'; finish(receipt()); await writing; assert.deepEqual(loaded, []); assert.equal(p.state.notice, '')
  } finally { p.close() }
})
test('应用的真实恢复入口只回到原轮次风险记录，不刷新丢失费用内容', async () => {
  reads(); const p = mount(), box = value => ({ value })
  try {
    await settle(); p.state.unknown = true
    const pending = { id: 'risk-request', path: model.riskPath(uuid(1)), body: JSON.stringify(request()), sending: false }
    globalThis.document = { querySelector: () => null }
    const env = { pendingWrites: box([pending]), draftScope: box(''), actorScope: box(p.props.scopeKey), acknowledgeRisk: model.acknowledgeRisk,
      confirmReplaceDefinition: async (_, action) => action(), busy: box(false), recoveryError: box(''), notice: box(''), recordApplicationId: box(uuid(99)), recordRefresh: box(0), templateRefresh: box(0),
      writeRequests: { recover: async () => ({ request: pending, result: receipt() }) }, refreshWorkspace: async () => {}, nextTick, workspace: box(null), errorMessage: e => e.message }
    await createRecovery(env)('risk-request'); await settle()
    assert.equal(env.recoveryError.value, ''); assert.equal(p.state.unknown, false); assert.equal(p.state.detail.id, uuid(10)); assert.equal(env.recordRefresh.value, 0); assert.equal(env.templateRefresh.value, 0)
  } finally { p.close() }
})
test('可访问申请检索固定主单申请人，比较范围只在明细读取成功后加入', async () => {
  reads(); const p = mount(Scope); let filters
  try {
    api.searchVisibleApplications = async f => { filters = f; return { items: [{ id: uuid(22), roundNo: 1 }], nextCursor: null } }
    p.state.query = 'EXP'; await p.state.search(); assert.equal(filters.applicant, 'alice'); assert.equal(filters.q, 'EXP'); assert.equal(filters.limit, 30)
    await p.state.add(p.state.results.items[0]); assert.equal(p.state.documents.length, 2); assert.deepEqual(p.state.documents[1].lineNos, [])
    p.state.documents.forEach(d => { d.lineNos = [1] }); p.state.prepare(); assert.deepEqual(p.events.find(e => e[0] === 'prepare')[1], request())
  } finally { p.close() }
})
test('非报销、跨法人及无读取权限的申请不能成为对照来源', async () => {
  for (const kind of ['OTHER', 'ENTITY', 'FORBIDDEN']) {
    reads(); const p = mount(Scope)
    try {
      await p.state.search(); const item = p.state.results.items[0]
      if (kind === 'OTHER') api.application = async () => ({ createdBy: 'alice', businessReference: { type: 'ADVANCE', id: uuid(2) } })
      if (kind === 'ENTITY') api.expenseReport = async () => ({ ...report(2), content: { ...report(2).content, legalEntityId: uuid(90) } })
      if (kind === 'FORBIDDEN') api.expenseReport = async () => { throw { status: 403, code: 'FORBIDDEN' } }
      await p.state.add(item); assert.equal(p.state.documents.length, 1); assert.ok(p.state.error)
    } finally { p.close() }
  }
})
test('日历明确选用，未选择时不推断周末；切换任务清除已选范围', async () => {
  reads(); const p = mount(Scope)
  try {
    assert.equal(p.state.calendarId, ''); await p.state.loadCalendars(); assert.equal(p.state.calendars.length, 1); assert.equal(p.state.calendarId, '')
    p.state.documents[0].lineNos = [1]; p.state.calendarId = uuid(40); p.state.prepare(); assert.equal(p.events.find(e => e[0] === 'prepare')[1].scope.calendarId, uuid(40))
    p.props.taskId = 'task-2'; assert.equal(p.state.calendarId, ''); assert.deepEqual(p.state.documents[0].lineNos, [])
  } finally { p.close() }
})
test('选择来源期间失权清除先前对照正文和日历选择，触发旧预览失效', async () => {
  reads(); const p = mount(Scope)
  try {
    await p.state.search(); await p.state.add(p.state.results.items[0]); p.state.documents.forEach(d => { d.lineNos = [1] }); p.state.calendarId = uuid(40)
    const changed = p.events.filter(e => e[0] === 'change').length
    api.expenseRiskCalendars = async () => { throw { status: 403, code: 'FORBIDDEN' } }; await p.state.loadCalendars()
    assert.equal(p.state.documents.length, 1); assert.deepEqual(p.state.documents[0].lineNos, []); assert.equal(p.state.calendarId, '')
    assert.ok(p.events.filter(e => e[0] === 'change').length > changed); assert.ok(p.state.error)
  } finally { p.close() }
})
test('真实模板转义模型和来源原文，并清楚区分解释局限与审批决定', async () => {
  reads(); const props = { report: report(), taskId: 'task-1', scopeKey: 'render-risk', locked: false }; model.rememberRisk(props.scopeKey, uuid(1), 1, uuid(10))
  const html = await renderToString(createSSRApp({ ...Rendered, async setup(props, ctx) { const render = Rendered.setup(props, ctx); await settle(); await settle(); return render } }, props))
  assert.ok(html.includes('&lt;script&gt;')); assert.ok(!html.includes('<script>')); assert.ok(html.includes('依据局限')); assert.ok(html.includes('记录采纳所选解释'))
  const scope = await renderToString(createSSRApp(ScopeRendered, { ...props, resetVersion: 0 })); assert.ok(scope.includes('不判断非工作日')); assert.ok(scope.includes('第 1 行')); assert.ok(!scope.includes(' checked'))
})
test('费用详情把风险发送和未提交复核纳入业务操作锁', async () => {
  api.expenseReport = async () => report(); api.expenseWorkflow = async () => null
  const p = mount(Detail, { reportId: uuid(1), applicationId: uuid(21) })
  try { await settle(); p.state.riskDirty = true; assert.equal(p.state.actionsLocked, true); assert.deepEqual(p.events.at(-1), ['busy', true]); p.state.riskDirty = false; assert.equal(p.state.actionsLocked, false) }
  finally { p.close() }
})
test('客户端完整样例与公开风险 schema 一致', () => {
  const spec = JSON.parse(readFileSync(new URL('../../agentflow-server/src/main/resources/api/openapi.json', import.meta.url), 'utf8'))
  const ajv = new Ajv2020({ strict: false }); addFormats(ajv); ajv.addSchema({ $id: 'risk-contract', components: spec.components })
  for (const [name, value] of [['ExpenseRiskInputOptions', input()], ['ExpenseRiskDetail', detail()], ['ExpenseRiskDetail', queued()], ['ExpenseRiskCalendars', calendars()], ['ExpenseRiskPage', page()]]) {
    const validate = ajv.compile({ $ref: `risk-contract#/components/schemas/${name}` }); assert.ok(validate(value), JSON.stringify(validate.errors))
  }
})
