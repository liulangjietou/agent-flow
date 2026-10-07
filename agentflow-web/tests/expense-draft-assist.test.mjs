import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRenderer, reactive, nextTick, createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
const model = await import(process.env.AGENTFLOW_TEST_EXPENSE_DRAFT_ASSIST)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEDRAFTASSISTPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_EXPENSEDRAFTASSISTRENDERED)
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_EXPENSEEDITOR)
const { default: EditorRendered } = await import(process.env.AGENTFLOW_TEST_EXPENSEEDITORRENDERED)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, originalDocument = globalThis.document
afterEach(() => { Object.assign(api, originals); globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage; globalThis.document = originalDocument; bindAuthenticationActor(null) })
const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const at = new Date(Date.now() - 10_000).toISOString(), until = new Date(Date.now() + 600_000).toISOString()
const leg = () => ({ id: 1, startsOn: '2026-10-03', endsOn: '2026-10-04', cityCode: 'SH', purpose: '参加培训' })
const request = () => ({ applicationVersion: 2, financialVersion: 3, brief: '推荐交通与住宿费用', itinerary: [leg()], catalog: { categoryCodes: ['HOTEL'], costCenterCodes: ['IT'], projectCodes: [] } })
const options = () => ({ categories: [{ code: 'HOTEL', name: '住宿', units: ['NIGHT'] }], costCenters: [{ code: 'IT', name: '研发' }], projects: [], cities: [{ code: 'SH', name: '上海' }] })
const source = (id, content) => ({ reference: { sourceId: id, contentDigest: createHash('sha256').update(content).digest('hex') }, label: id, content })
function input() {
  const o = options()
  return { reportId: uuid(1), applicationId: uuid(2), applicationVersion: 2, financialVersion: 3, legalEntityId: uuid(3), reportType: 'TRAVEL', catalogVersion: 'catalog-v1',
    validUntil: until, financeTargetDigest: 'a'.repeat(64), itinerary: [leg()], options: o,
    sources: [source('expense:brief', JSON.stringify(request().brief)), source('expense:catalog', JSON.stringify({ reportType: 'TRAVEL', catalogVersion: 'catalog-v1', options: o })), source('expense:itinerary[1]', JSON.stringify(leg()))] }
}
const preview = () => ({ input: input(), providerId: 'fixture', model: 'fixture-v1', destination: '127.0.0.1:9999', targetDigest: 'b'.repeat(64), consentDigest: 'c'.repeat(64) })
const row = () => ({ id: 'hotel', itineraryId: 1, categoryCode: 'HOTEL', unit: 'NIGHT', description: '上海培训住宿', allocations: [{ costCenter: 'IT', percent: 100 }], evidence: input().sources.slice(1).map(s => s.reference) })
const detail = () => ({ id: uuid(4), input: input(), status: 'COMPLETED', version: 3, createdAt: at, startedAt: at, completedAt: at, suggestion: { providerId: 'fixture', modelVersion: 'fixture-v1', promptVersion: 'expense-draft-assist-v1', lines: [row()] }, failure: null, review: null, canConfirm: true, unavailableCode: null })
const selection = (parts = ['ITINERARY', 'ALLOCATION']) => ({ proposalId: 'hotel', parts })
const confirmed = (parts) => ({ ...detail(), status: 'CONFIRMED', version: 4, canConfirm: false, unavailableCode: 'AGENT_RUN_NOT_REVIEWABLE', review: { actor: 'alice', at, selected: [selection(parts)] } })
const page = (n = 0) => ({ items: [{ id: uuid(4), applicationVersion: 2, financialVersion: 3, status: 'COMPLETED', version: 3, createdAt: at }], total: 1, page: n, pageSize: 20 })
const catalog = () => ({ employeeId: 'alice', sourceVersion: 'catalog-v1', validUntil: until, legalEntities: [{ id: uuid(3), name: '本人法人', baseCurrency: 'CNY', timeZone: 'Asia/Shanghai', paperReceiptRequired: false }],
  categories: options().categories, costCenters: [{ ...options().costCenters[0], legalEntityId: uuid(3) }, { code: 'PRIVATE', name: '其他法人', legalEntityId: uuid(9) }], projects: [], cities: options().cities })
const content = () => ({ legalEntityId: uuid(3), type: 'TRAVEL', title: '本人填写标题', lines: [], advanceOffsets: [] })
const report = () => ({ id: uuid(1), applicationId: uuid(2), applicationVersion: 2, financialVersion: 3, businessNo: 'EXP-1', applicationStatus: 'DRAFT', editable: true, roundNo: 0, content: content(), financialRound: null })
const receipt = (status = 'QUEUED') => ({ id: uuid(4), status, version: status === 'QUEUED' ? 1 : 4 })
const unreadable = error => error.code === 'RESPONSE_UNREADABLE'
const settle = () => new Promise(resolve => setImmediate(resolve))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
let scope = 0
function reads(run = detail()) { api.expenseAssistRuns = async (_, n) => page(n); api.expenseAssistRun = async () => structuredClone(run); api.expenseAssistPreview = async () => preview() }
function panel(Component = Panel, initial = {}) {
  const props = reactive({ scopeKey: `demo/draft-${++scope}`, report: report(), catalog: catalog(), locked: false, applicationDirty: false, ...initial }), events = []
  const app = renderer.createApp({ ...Component, setup: (_, ctx) => Component.setup(props, ctx), render: () => null }, { ...props, onBusy: v => events.push(['busy', v]), onDirty: v => events.push(['dirty', v]), onFill: v => events.push(['fill', v]), onClose: () => events.push(['close']) })
  return { props, events, state: app.mount({}).$.setupState, close: () => app.unmount() }
}
function fillInput(p) { p.state.brief = request().brief; p.state.itinerary = [leg()]; p.state.categories = ['HOTEL']; p.state.centers = ['IT'] }

test('真实来源摘要、原预览请求和六种运行状态严格绑定', async () => {
  assert.deepEqual(await model.readExpenseAssistPreview(preview(), uuid(1), request()), preview())
  assert.deepEqual(model.readExpenseAssistPage(page(), 0), page())
  for (const status of ['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CONFIRMED', 'DISMISSED']) {
    const d = status === 'CONFIRMED' ? confirmed() : detail(); d.status = status; d.version = { QUEUED: 1, RUNNING: 2, COMPLETED: 3, FAILED: 3, CONFIRMED: 4, DISMISSED: 4 }[status]
    if (status !== 'COMPLETED') { d.canConfirm = false; d.unavailableCode = 'AGENT_RUN_NOT_REVIEWABLE' }
    if (['QUEUED', 'RUNNING', 'FAILED'].includes(status)) d.suggestion = null
    if (status === 'QUEUED') d.startedAt = null
    if (['QUEUED', 'RUNNING'].includes(status)) d.completedAt = null
    if (status === 'FAILED') d.failure = 'INPUT_UNAVAILABLE'
    if (status === 'DISMISSED') d.review = { actor: 'alice', at, selected: [] }
    assert.deepEqual(await model.readExpenseAssistDetail(d, uuid(1), uuid(4)), d)
  }
})
test('拒绝被替换的来源、目录、日期、金额、引用和不合法分摊响应', async () => {
  for (const change of [v => v.input.sources[0].content = '"篡改"', v => v.input.sources[0].reference.contentDigest = 'f'.repeat(64),
    v => v.input.options.categories[0].name = '篡改', v => v.input.itinerary[0].startsOn = '2026-02-30', v => v.input.reportId = uuid(9),
    v => v.suggestion.lines[0].amount = '100', v => v.suggestion.lines[0].evidence.pop(), v => v.suggestion.lines[0].evidence[0].contentDigest = 'f'.repeat(64),
    v => v.suggestion.lines[0].allocations[0].costCenter = 'PRIVATE', v => v.suggestion.lines[0].allocations[0].percent = 99.999,
    v => v.suggestion.lines[0].allocations.push({ costCenter: 'IT', percent: 1 }), v => v.suggestion.lines[0].itineraryId = 2,
    v => v.suggestion.lines[0].unit = 'DAY', v => v.canConfirm = false, v => v.status = 'APPROVED', v => v.completedAt = null]) {
    const d = detail(); change(d); await assert.rejects(model.readExpenseAssistDetail(d, uuid(1), uuid(4)), unreadable)
  }
  await assert.rejects(model.readExpenseAssistPreview(preview(), uuid(1), { ...request(), brief: '另一请求' }), unreadable)
  assert.throws(() => model.readExpenseAssistPage({ ...page(), items: [page().items[0], page().items[0]] }, 0), unreadable)
})
test('合法空建议与微秒到期前确认保留，越过期限或缺少行程选择拒绝', async () => {
  const empty = detail(); empty.suggestion.lines = []; empty.canConfirm = false; empty.unavailableCode = 'AGENT_NO_SUGGESTIONS'
  assert.deepEqual(await model.readExpenseAssistDetail(empty, uuid(1), uuid(4)), empty)
  // 微秒边界用完整的历史时间线，不能混用运行当天的创建时间和固定到期日。
  const d = confirmed(); d.createdAt = '2026-10-04T23:59:50Z'; d.startedAt = '2026-10-04T23:59:51Z'; d.completedAt = '2026-10-04T23:59:52Z'
  d.input.validUntil = '2026-10-05T00:00:00.000500Z'; d.review.at = '2026-10-05T00:00:00.000499Z'
  assert.deepEqual(await model.readExpenseAssistDetail(d, uuid(1), uuid(4)), d)
  const createdTooLate = structuredClone(d); createdTooLate.createdAt = d.input.validUntil
  await assert.rejects(model.readExpenseAssistDetail(createdTooLate, uuid(1), uuid(4)), unreadable)
  d.review.at = d.input.validUntil; await assert.rejects(model.readExpenseAssistDetail(d, uuid(1), uuid(4)), unreadable)
  const invalid = confirmed(['CATEGORY']); await assert.rejects(model.readExpenseAssistDetail(invalid, uuid(1), uuid(4)), unreadable)
})
test('只追加已确认的字段组，金额、已有费用及单据正文不由模型改写', () => {
  const original = report(), before = structuredClone(original)
  const result = model.fillExpenseFromAssist(original.content, original, catalog(), confirmed())
  assert.deepEqual(original, before); assert.equal(result.title, before.content.title); assert.equal(result.lines.length, 1)
  assert.equal(result.lines[0].categoryCode, ''); assert.equal(result.lines[0].unit, 'ITEM'); assert.equal(result.lines[0].incurredOn, leg().startsOn)
  assert.equal(result.lines[0].claimedGross.value, ''); assert.equal(result.lines[0].allocations[0].amount.value, ''); assert.equal(result.lines[0].allocations[0].costCenter, 'IT')
  assert.deepEqual(result.lines[0].invoiceIds, []); assert.equal(result.lines[0].allowance, undefined)
  const categoriesOnly = model.fillExpenseFromAssist(original.content, original, catalog(), confirmed(['ITINERARY', 'CATEGORY']))
  assert.equal(categoriesOnly.lines[0].categoryCode, 'HOTEL'); assert.equal(categoriesOnly.lines[0].unit, 'NIGHT'); assert.equal(categoriesOnly.lines[0].allocations[0].costCenter, '')
  const withExisting = { ...report(), content: result }, existingBefore = structuredClone(result)
  assert.deepEqual(model.fillExpenseFromAssist(result, withExisting, catalog(), confirmed()).lines[0], existingBefore.lines[0])
})
test('填入要求原双版本、新鲜目录和干净草稿，并限制最多二百行', () => {
  const d = report()
  for (const changed of [{ ...d, applicationVersion: 3 }, { ...d, financialVersion: 4 }, { ...d, editable: false }, { ...d, id: uuid(7) }])
    assert.throws(() => model.fillExpenseFromAssist(d.content, changed, catalog(), confirmed()))
  assert.throws(() => model.fillExpenseFromAssist({ ...d.content, title: '未保存' }, d, catalog(), confirmed()))
  assert.throws(() => model.fillExpenseFromAssist(d.content, d, { ...catalog(), sourceVersion: 'new' }, confirmed()))
  assert.throws(() => model.fillExpenseFromAssist(d.content, d, catalog(), confirmed(), Date.parse(until)))
  assert.throws(() => model.fillExpenseFromAssist(d.content, d, catalog(), detail()))
  const full = report(); full.content.lines = Array.from({ length: 200 }, (_, i) => ({ lineNo: i + 1 })); assert.throws(() => model.fillExpenseFromAssist(full.content, full, catalog(), confirmed()), /200/)
})
test('发送必须先预览并明确同意，改动行程立即使原清单失效', async () => {
  reads(); const p = panel(); let calls = 0, sent
  api.generateExpenseAssist = async (_, body) => { calls++; sent = body; return receipt() }
  try {
    await settle(); fillInput(p); await p.state.showPreview(); assert.equal(p.state.canGenerate, false)
    await p.state.generate(); assert.equal(calls, 0); p.state.consent = true; assert.equal(p.state.canGenerate, true)
    p.state.itinerary[0].purpose = '修改用途'; assert.equal(p.state.preview, null); assert.equal(p.state.consent, false)
    p.state.itinerary = [leg()]; await p.state.showPreview(); p.state.consent = true; await p.state.generate()
    assert.equal(calls, 1); assert.deepEqual(sent, { input: request(), validUntil: until, targetDigest: 'b'.repeat(64), consentDigest: 'c'.repeat(64) })
    assert.equal(p.state.draftDirty, false); assert.equal(p.state.detail.id, uuid(4)); assert.equal(p.events.filter(e => e[0] === 'fill').length, 0)
  } finally { p.close() }
})
test('确认与填入分开，类别或分摊不隐式确认行程，填入前重新授权读取', async () => {
  reads(); const p = panel(); let sent
  api.confirmExpenseAssist = async (_, __, body) => { sent = structuredClone(body); api.expenseAssistRun = async () => confirmed(body.selected[0].parts); return receipt('CONFIRMED') }
  try {
    await settle(); await p.state.loadDetail(uuid(4)); p.state.choices.hotel = ['ALLOCATION']; await p.state.review(true); assert.equal(sent, undefined)
    p.state.choices.hotel = ['ITINERARY', 'ALLOCATION']; await p.state.review(true); assert.deepEqual(sent.selected, [selection()])
    assert.equal(p.events.filter(e => e[0] === 'fill').length, 0); await p.state.fill(); assert.equal(p.events.filter(e => e[0] === 'fill').length, 1)
    api.expenseAssistRun = async () => { throw { status: 403 } }; await p.state.fill(); assert.equal(p.state.detail, null); assert.equal(p.events.filter(e => e[0] === 'fill').length, 1)
  } finally { p.close() }
})
test('空建议不能确认，历史过期建议仍能明确放弃', async () => {
  const d = detail(); d.suggestion.lines = []; d.canConfirm = false; d.unavailableCode = 'AGENT_NO_SUGGESTIONS'; d.input.validUntil = at; d.createdAt = d.startedAt = d.completedAt = new Date(Date.parse(at) - 1000).toISOString()
  reads(d); const p = panel(); let dismissed = 0
  api.dismissExpenseAssist = async () => { dismissed++; return receipt('DISMISSED') }
  try { await settle(); await p.state.loadDetail(uuid(4)); assert.equal(p.state.canReview, true); assert.equal(p.state.current, false); await p.state.review(true); await p.state.review(false); assert.equal(dismissed, 1) } finally { p.close() }
})
test('记录人工确认前检查剩余行数，超出容量不能产生不可填入的确认', async () => {
  reads(); const full = report(); full.content.lines = Array.from({ length: 200 }, (_, i) => ({ lineNo: i + 1 }))
  const p = panel(Panel, { report: full }); let confirms = 0
  api.confirmExpenseAssist = async () => { confirms++; return receipt('CONFIRMED') }
  try { await settle(); await p.state.loadDetail(uuid(4)); p.state.choices.hotel = ['ITINERARY']; await p.state.review(true); assert.equal(confirms, 0) } finally { p.close() }
})
test('新费用行补齐金额后沿用原保存服务和双版本，不触发模型或提交', async () => {
  api.financeCatalog = async () => catalog(); const p = panel(Editor, { initial: report() }); let body, submissions = 0
  api.reviseExpense = async (id, value) => { assert.equal(id, uuid(1)); body = structuredClone(value); return { ...report(), content: value.content, applicationVersion: 3, financialVersion: 4 } }
  api.submitExpense = async () => { submissions++ }; api.generateExpenseAssist = async () => { throw new Error('unexpected model generation') }
  try {
    await settle(); p.state.applyDraftAssist(confirmed(['ITINERARY', 'CATEGORY', 'ALLOCATION']))
    const line = p.state.state.content.lines[0]; line.claimedGross.value = '120.50'; line.allocations[0].amount.value = '120.50'
    await p.state.save(); assert.ok(body); assert.equal(body.applicationVersion, 2); assert.equal(body.financialVersion, 3)
    assert.equal(body.content.lines[0].claimedGross.value, '120.50'); assert.equal(p.state.state.detail.applicationVersion, 3); assert.equal(p.state.dirty, false); assert.equal(submissions, 0)
    p.state.applyDraftAssist(confirmed()); assert.equal(p.state.state.content.lines.length, 1)
  } finally { p.close() }
})
test('来源到期或本地费用改变后不能继续发送、确认或填入', async () => {
  reads(confirmed()); const p = panel(); let sent = 0
  api.generateExpenseAssist = async () => { sent++; return receipt() }
  try {
    await settle(); fillInput(p); await p.state.showPreview(); p.state.consent = true; p.state.now = Date.parse(until)
    assert.equal(p.state.canGenerate, false); p.state.clearDraft(); await p.state.loadDetail(uuid(4)); p.state.now = Date.parse(until); assert.equal(p.state.canFill, false)
    p.state.now = Date.now(); p.props.applicationDirty = true; await p.state.fill(); assert.equal(p.events.filter(e => e[0] === 'fill').length, 0); assert.equal(sent, 0)
  } finally { p.close() }
})
test('读取超时和身份切换丢弃迟到响应，禁止跨身份恢复与自动填入', async () => {
  reads(); const p = panel(); let resolve, timeout; const set = globalThis.setTimeout, clear = globalThis.clearTimeout
  try {
    await settle(); api.expenseAssistRun = () => new Promise(r => { resolve = r })
    globalThis.setTimeout = fn => { timeout = fn; return 1 }; globalThis.clearTimeout = t => { if (typeof t === 'object') clear(t) }
    const loading = p.state.loadDetail(uuid(4)); timeout(); await loading; assert.equal(p.state.loading.detail, false)
    resolve(detail()); await settle(); assert.equal(p.state.detail, null)
    globalThis.setTimeout = set; globalThis.clearTimeout = clear
    const old = p.state.loadDetail(uuid(4)); p.props.scopeKey = 'demo/other'; resolve(detail()); await old; await settle()
    assert.equal(p.state.detail, null); model.acknowledgeExpenseAssist('demo/elsewhere', model.expenseAssistPath(uuid(1)), receipt()); await settle()
    assert.equal(p.state.detail, null); assert.equal(p.events.filter(e => e[0] === 'fill').length, 0)
  } finally { globalThis.setTimeout = set; globalThis.clearTimeout = clear; p.close() }
})
test('写入结果未知时阻止重复发送，身份变化不触发旧运行后续读取', async () => {
  reads(); const p = panel(); let resolve, calls = 0
  try {
    await settle(); fillInput(p); await p.state.showPreview(); p.state.consent = true
    api.generateExpenseAssist = async () => { calls++; throw { status: 0, code: 'NETWORK_ERROR' } }
    await p.state.generate(); await p.state.generate(); assert.equal(calls, 1); assert.equal(p.state.unknown, true)
    p.state.unknown = false; api.generateExpenseAssist = () => new Promise(r => { resolve = r })
    const writing = p.state.generate(); p.props.scopeKey = 'demo/changed'; resolve(receipt()); await writing; await settle()
    assert.equal(p.state.detail, null); assert.equal(p.state.preview, null)
  } finally { p.close() }
})
test('真实接口回执校验保留未知请求原字节和幂等键，预览不创建幂等写入', async () => {
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'draft-request' })
  const reads = []; globalThis.fetch = async (url, init) => { reads.push(init); return Response.json(url.endsWith('/preview') ? preview() : url.includes('?') ? page() : detail()) }
  await api.expenseAssistPreview(uuid(1), request()); await api.expenseAssistRuns(uuid(1), 0); await api.expenseAssistRun(uuid(1), uuid(4))
  assert.ok(reads.every(r => r.cache === 'no-store')); assert.equal(reads[0].headers.get('Idempotency-Key'), null)
  const sent = []; globalThis.fetch = async (_, init) => { sent.push(init); return Response.json({ ...receipt('CONFIRMED'), id: sent.length === 1 ? uuid(9) : uuid(4) }) }
  const body = { expectedRunVersion: 3, applicationVersion: 2, financialVersion: 3, selected: [selection()], comment: '原说明' }
  await assert.rejects(api.confirmExpenseAssist(uuid(1), uuid(4), body), unreadable); assert.equal(writeRequests.pending().length, 1)
  body.comment = '修改后的说明'; await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(writeRequests.pending().length, 0); assert.equal(sent[0].body, sent[1].body); assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
  assert.throws(() => model.validateExpenseAssistReceipt(receipt(), model.expenseAssistPath(uuid(1)) + '/' + uuid(4) + '/dismiss', '{"expectedRunVersion":3}'), unreadable)
})
test('真实全局恢复入口只定位原建议，不重新发送或修改费用', async () => {
  reads(confirmed()); const p = panel(), box = value => ({ value })
  try {
    await settle(); fillInput(p); p.state.unknown = true; const pending = { id: 'request', path: model.expenseAssistPath(uuid(1)), sending: false }
    globalThis.document = { querySelector: () => null }
    const env = { pendingWrites: box([pending]), draftScope: box(''), actorScope: box(p.props.scopeKey), acknowledgeExpenseAssist: model.acknowledgeExpenseAssist,
      confirmReplaceDefinition: async (_, action) => action(), busy: box(false), recoveryError: box(''), notice: box(''), recordApplicationId: box(uuid(8)), recordRefresh: box(0), templateRefresh: box(0),
      writeRequests: { recover: async () => ({ request: pending, result: receipt('CONFIRMED') }) }, refreshWorkspace: async () => {}, nextTick, workspace: box(null), errorMessage: e => e.message }
    await createRecovery(env)('request'); await settle(); assert.equal(env.recoveryError.value, ''); assert.equal(env.recordRefresh.value, 0)
    assert.equal(p.state.unknown, false); assert.equal(p.state.draftDirty, false); assert.equal(p.state.detail.id, uuid(4)); assert.equal(p.events.filter(e => e[0] === 'fill').length, 0)
  } finally { p.close() }
})
test('费用编辑器聚合助手锁，明确填入后成为脏草稿，重复事件不会追加', async () => {
  api.financeCatalog = async () => catalog(); const p = panel(Editor, { initial: report() })
  try {
    await settle(); p.state.draftAssistDirty = true; p.state.assistBusy = false; assert.equal(p.state.blocked, true); p.state.close(); assert.equal(p.events.some(e => e[0] === 'close'), false)
    p.state.applyDraftAssist(confirmed()); assert.equal(p.state.state.content.lines.length, 0)
    p.state.draftAssistDirty = false; p.state.applyDraftAssist(confirmed()); assert.equal(p.state.state.content.lines.length, 1); assert.equal(p.state.dirty, true)
    p.state.applyDraftAssist(confirmed()); assert.equal(p.state.state.content.lines.length, 1); assert.match(p.state.notice, /尚未保存/)
    await p.state.save(); assert.equal(p.state.state.pending, null); assert.match(p.state.error, /类别|金额/)
  } finally { p.close() }
})
test('实际父模板在两个助手之间传递互斥锁，避免同时处理同一份费用草稿', async () => {
  api.financeCatalog = async () => catalog(); const p = panel(Editor, { initial: report() })
  function child(name) {
    const find = node => node?.type === p.state[name] ? node : Array.isArray(node?.children) ? node.children.map(find).find(Boolean) : null
    let found; const probe = renderer.createApp({ render() { found = find(EditorRendered.render({}, [], p.props, p.state, {}, {})); return null } })
    probe.mount({}); probe.unmount(); return found
  }
  try {
    await settle(); p.state.draftAssistDirty = true
    assert.equal(child('PrecheckExplanationPanel').props.locked, true)
    p.state.draftAssistDirty = false; p.state.explanationDirty = true
    assert.equal(child('ExpenseDraftAssistPanel').props.locked, true)
    p.state.explanationDirty = false
    assert.equal(child('PrecheckExplanationPanel').props.locked, false)
    assert.equal(child('ExpenseDraftAssistPanel').props.locked, false)
  } finally { p.close() }
})

test('实际模板显示目的地确认与逐项选择，来源文本经过转义', async () => {
  reads(); const setup = Rendered.setup
  const component = { ...Rendered, setup(props, context) { const render = setup(props, context); return render } }
  const set = globalThis.setTimeout
  // SSR 无挂载后的卸载回调；测试中保留真实计时器行为，但不让刷新时效计时器阻止进程退出。
  globalThis.setTimeout = (...args) => { const timer = set(...args); timer.unref(); return timer }
  try {
    const html = await renderToString(createSSRApp(component, { scopeKey: 'demo/ssr', report: report(), catalog: { ...catalog(), cities: [{ code: 'SH', name: '<script>danger</script>' }] }, locked: false, applicationDirty: false }))
    assert.match(html, /按行程生成费用草稿/); assert.match(html, /预览将发送的内容/); assert.match(html, /&lt;script&gt;danger&lt;\/script&gt;/); assert.ok(!html.includes('<script>danger'))
    await settle()
  } finally { globalThis.setTimeout = set }
})

if (process.env.AGENTFLOW_EXPENSE_ASSIST_OBSERVATIONS) test('前端解析实际后端成功响应并绑定原请求，覆盖全部六个操作', async () => {
  const directory = process.env.AGENTFLOW_EXPENSE_ASSIST_OBSERVATIONS, operations = new Set(); let checked = 0, differentPageSizeRejected = 0
  for (const name of readdirSync(directory).filter(n => /^\d+\.json$/.test(n)).sort()) {
    const v = JSON.parse(readFileSync(resolve(directory, name), 'utf8')); if (v.status < 200 || v.status >= 300) continue
    try {
    if (v.method === 'POST' && v.suffix === '/preview') { await model.readExpenseAssistPreview(v.response, v.response.input.reportId, v.request); operations.add('preview') }
    else if (v.method === 'GET' && (!v.suffix || v.suffix.startsWith('?'))) {
      if (v.response.pageSize !== 20) { assert.throws(() => model.readExpenseAssistPage(v.response, v.response.page), unreadable); differentPageSizeRejected++; continue }
      model.readExpenseAssistPage(v.response, v.response.page); operations.add('list')
    }
    else if (v.method === 'GET') { await model.readExpenseAssistDetail(v.response, v.response.input.reportId, v.suffix.slice(1)); operations.add('detail') }
    else { model.validateExpenseAssistReceipt(v.response, model.expenseAssistPath(uuid(1)) + v.suffix, JSON.stringify(v.request)); operations.add(v.suffix ? v.suffix.split('/').at(-1) : 'queue') }
    } catch (cause) { throw new Error(`Observed response ${name} ${v.method} ${v.suffix} failed client validation`, { cause }) }
    checked++
  }
  assert.equal(operations.size, 6); assert.ok(checked > 60)
  const result = { result: 'PASS', successfulResponses: checked, differentPageSizeRejected, operations: [...operations].sort(), source: directory }
  if (process.env.AGENTFLOW_EXPENSE_ASSIST_CONTRACT_RESULT) writeFileSync(process.env.AGENTFLOW_EXPENSE_ASSIST_CONTRACT_RESULT, JSON.stringify(result, null, 2) + '\n')
})
