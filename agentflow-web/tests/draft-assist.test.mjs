import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { readFileSync } from 'node:fs'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'

const draft = await import(process.env.AGENTFLOW_TEST_DRAFT_ASSIST)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_DRAFTASSISTPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_DRAFTASSISTRENDERED)
const { default: Record } = await import(process.env.AGENTFLOW_TEST_APPLICATION_RECORD)
const { createRecovery } = await import(process.env.AGENTFLOW_TEST_TIMER_RECOVERY)
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, originalFetch = globalThis.fetch, originalDocument = globalThis.document, originalStorage = globalThis.localStorage
afterEach(() => { Object.assign(api, originals); globalThis.fetch = originalFetch; globalThis.document = originalDocument; globalThis.localStorage = originalStorage; bindAuthenticationActor(null) })
const settle = () => new Promise(resolve => setImmediate(resolve))
const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const when = '2026-10-02T00:00:00Z'
const schema = () => ({ schemaVersion: 2, fields: [
  { key: 'reason', label: '用途', type: 'TEXTAREA', required: false, maxLength: 100 },
  { key: 'amount', label: '金额', type: 'NUMBER', required: false },
  { key: 'urgent', label: '加急', type: 'BOOLEAN', required: false },
  { key: 'lines', label: '明细', type: 'TABLE', required: false, columns: [{ key: 'name', label: '名称', type: 'TEXT', required: false }] }
] })
const source = sourceId => ({ reference: { sourceId, contentDigest: 'a'.repeat(64) }, label: '已授权来源', content: '"已授权原文"' })
const input = () => ({ applicationVersion: 1, enabled: true, unavailableCode: null, providerId: 'fixture', model: 'fixture-v1', destination: '127.0.0.1', targetDigest: 'b'.repeat(64), targetSchema: schema(), sources: [source('form:reason')] })
const summary = () => ({ id: uuid(2), applicationVersion: 1, status: 'COMPLETED', version: 3, createdAt: when })
const page = (number = 0) => ({ items: [summary()], total: 1, page: number, pageSize: 20 })
const detail = () => ({ ...summary(), startedAt: when, completedAt: when, targetSchema: schema(), sources: [source('application:brief'), source('form:reason')],
  suggestion: { providerId: 'fixture', modelVersion: 'model-v1', promptVersion: 'application-draft-v1', proposals: [
    { targetId: 'form:reason', value: '模型原值', evidence: [source('form:reason').reference] },
    { targetId: 'application:title', value: '模型标题', evidence: [source('application:brief').reference] }
  ] }, failure: null, review: null, canAdopt: true })
const stubReads = () => { api.draftAssistInput = async () => input(); api.draftAssistRuns = async (_, number) => page(number); api.draftAssistRun = async () => detail() }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
let scope = 0
function panel() {
  const props = reactive({ applicationId: uuid(1), scopeKey: `demo/draft-${++scope}`, version: 1, editable: true, locked: false, applicationDirty: false }), events = []
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, {
    ...props, onSaved: () => events.push(['saved']), onBusy: value => events.push(['busy', value]), onDirty: value => events.push(['dirty', value])
  })
  return { state: app.mount({}).$.setupState, props, events, close: () => app.unmount() }
}
const unreadable = error => error.code === 'RESPONSE_UNREADABLE'

test('契约接纳实际返回的明细列权限，仍拒绝嵌套表格和任意权限值', () => {
  const spec = JSON.parse(readFileSync(new URL('../../agentflow-server/src/main/resources/api/openapi.json', import.meta.url)))
  const ajv = new Ajv2020({ strict: false }); addFormats(ajv)
  ajv.addSchema({ $id: 'agentflow', components: spec.components })
  const validate = ajv.compile({ $ref: 'agentflow#/components/schemas/FormSchema' })
  const value = schema(), column = value.fields[3].columns[0]
  column.sensitive = true; column.nodeAccess = { review: 'MASKED' }
  assert.equal(validate(value), true, JSON.stringify(validate.errors))
  column.nodeAccess.review = 'EDITABLE'; assert.equal(validate(value), false)
  column.nodeAccess.review = 'READ_ONLY'; column.type = 'TABLE'; assert.equal(validate(value), false)
})

test('输入目录、分页与原始建议拒绝敏感字段、错误版本和伪造来源', () => {
  assert.deepEqual(draft.readDraftInput(input()), input())
  assert.deepEqual(draft.readDraftPage(page(), 0), page())
  assert.deepEqual(draft.readDraftDetail(detail(), uuid(2)), detail())
  const badInput = input(); badInput.targetSchema.fields[0].sensitive = true
  assert.throws(() => draft.readDraftInput(badInput), unreadable)
  assert.throws(() => draft.readDraftInput({ ...input(), targetDigest: null }), unreadable)
  assert.throws(() => draft.readDraftPage(page(1), 0), unreadable)
  assert.throws(() => draft.readDraftPage({ ...page(), items: [summary(), summary()] }, 0), unreadable)
  for (const change of [v => { v.status = 'UNKNOWN' }, v => { v.version = 9 }, v => { v.id = uuid(3) },
    v => { v.suggestion.proposals[0].evidence[0].contentDigest = 'f'.repeat(64) },
    v => { v.suggestion.proposals[0] = { ...v.suggestion.proposals[0], targetId: 'form:amount', value: '不是数字' } },
    v => { v.status = 'ADOPTED'; v.version = 4; v.canAdopt = false }]) {
    const value = detail(); change(value); assert.throws(() => draft.readDraftDetail(value, uuid(2)), unreadable)
  }
})

test('仅保存明确选择的真实目标，精确金额、false与明细不改变原模型值', () => {
  const value = detail()
  for (const [targetId, initial] of [['form:amount', '1.2'], ['form:urgent', true], ['form:lines', [{ name: '原值' }]]]) {
    value.suggestion.proposals.push({ targetId, value: initial, evidence: [source('application:brief').reference] })
  }
  const values = { 'form:amount': '9007199254740993.02', 'form:urgent': false, 'form:lines': [{ name: '人工值' }] }
  const selected = draft.draftSelections(value, Object.keys(values), values)
  assert.deepEqual(selected.map(v => v.value), ['9007199254740993.02', false, [{ name: '人工值' }]])
  values['form:lines'][0].name = '后续修改'
  assert.equal(selected[2].value[0].name, '人工值'); assert.equal(value.suggestion.proposals[0].value, '模型原值')
  for (const ids of [[], ['form:reason', 'form:reason'], ['form:missing']]) assert.throws(() => draft.draftSelections(value, ids, values))
  for (const [id, bad] of [['form:reason', null], ['form:amount', 'NaN'], ['application:title', ' ']]) assert.throws(() => draft.draftSelections(value, [id], { [id]: bad }))
})

test('首次读取模型建议不算人工修改，输入和采纳默认不勾选', async () => {
  stubReads(); const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(2))
    assert.deepEqual(p.state.sourceIds, []); assert.deepEqual(p.state.selected, [])
    assert.equal(p.state.dirty, false); assert.equal(p.state.canNavigate, true)
    p.state.brief = '整理用途'; assert.equal(p.state.canGenerate, true); assert.equal(p.state.canReview, false)
  } finally { p.close() }
})

test('刷新完成的详情同时更新目录中的原运行状态', async () => {
  stubReads(); api.draftAssistRuns = async () => ({ ...page(), items: [{ ...summary(), status: 'QUEUED', version: 1 }] })
  const p = panel()
  try {
    await settle(); assert.equal(p.state.page.items[0].status, 'QUEUED')
    await p.state.loadDetail(uuid(2)); assert.equal(p.state.page.items[0].status, 'COMPLETED'); assert.equal(p.state.page.items[0].version, 3)
  } finally { p.close() }
})

test('只发送已展示目的地、版本和选中来源，申请未保存或锁定时禁止生成', async () => {
  stubReads(); const sent = []
  api.generateDraftAssist = async (...args) => { sent.push(args); return { id: uuid(2), status: 'QUEUED', version: 1, savedApplicationVersion: null } }
  const p = panel()
  try {
    await settle(); await p.state.generate(); assert.equal(sent.length, 0)
    p.state.brief = '整理用途'; p.state.sourceIds = ['form:reason']; p.props.applicationDirty = true
    await p.state.generate(); p.props.applicationDirty = false; p.props.locked = true
    await p.state.generate(); assert.equal(sent.length, 0); p.props.locked = false; await p.state.generate()
    assert.deepEqual(sent, [[uuid(1), { expectedVersion: 1, targetDigest: 'b'.repeat(64), brief: '整理用途', sourceIds: ['form:reason'] }]])
    assert.equal(p.state.brief, ''); assert.equal(p.events.some(e => e[0] === 'saved'), false)
  } finally { p.close() }
})

test('部分人工采纳携带两个版本，保留模型原文且成功后只通知一次重读申请', async () => {
  stubReads(); const sent = []
  api.reviewDraftAssist = async (...args) => {
    sent.push(args)
    api.draftAssistRun = async () => ({ ...detail(), status: 'ADOPTED', version: 4, canAdopt: false, review: { actor: 'alice', at: when, selected: args[2].selected, appliedApplicationVersion: 2 } })
    return { id: uuid(2), status: 'ADOPTED', version: 4, savedApplicationVersion: 2 }
  }
  const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(2)); p.state.selected = ['form:reason']; p.state.values['form:reason'] = '人工用途'
    await p.state.review('ADOPT')
    assert.deepEqual(sent, [[uuid(1), uuid(2), { expectedRunVersion: 3, expectedApplicationVersion: 1, action: 'ADOPT', comment: '', selected: [{ targetId: 'form:reason', value: '人工用途' }] }]])
    assert.equal(p.state.detail.suggestion.proposals[0].value, '模型原值'); assert.equal(p.events.filter(e => e[0] === 'saved').length, 1)
    assert.equal(p.state.sending, false); assert.deepEqual(p.state.selected, [])
  } finally { p.close() }
})

test('未保存核对不被翻页或切换覆盖，旧版本只允许记录未采纳', async () => {
  stubReads(); const sent = []; api.reviewDraftAssist = async (...args) => { sent.push(args); return {} }
  const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(2)); p.state.selected = ['form:reason']; p.state.values['form:reason'] = '尚未保存'
    await p.state.loadDetail(uuid(3)); await p.state.loadPage(1)
    assert.equal(p.state.selectedId, uuid(2)); assert.equal(p.state.page.page, 0); assert.equal(p.state.values['form:reason'], '尚未保存')
    p.props.version = 2; await settle(); await p.state.review('ADOPT'); assert.equal(sent.length, 0)
    await p.state.review('DISMISS')
    assert.deepEqual(sent[0][2], { expectedRunVersion: 3, action: 'DISMISS', comment: '' })
    assert.equal(p.events.some(e => e[0] === 'saved'), false)
  } finally { p.close() }
})

test('账号变化清空私有输入和模型正文，旧输入响应不能回填新身份', async () => {
  stubReads(); const pending = []; api.draftAssistInput = (_, signal) => new Promise(resolve => pending.push({ signal, resolve }))
  const p = panel()
  try {
    await settle(); await p.state.loadDetail(uuid(2)); p.state.brief = '旧账号输入'
    p.props.scopeKey = 'other/new'
    assert.equal(p.state.detail, null); assert.equal(p.state.brief, ''); assert.equal(p.state.options, null); assert.equal(pending[0].signal.aborted, true)
    pending[1].resolve({ ...input(), providerId: 'new' }); await settle()
    pending[0].resolve(input()); await settle(); assert.equal(p.state.options.providerId, 'new')
  } finally { p.close() }
})

test('成功写入后的分页迟到不跨账号读取旧运行', async () => {
  stubReads(); const pages = [], details = []
  const p = panel()
  try {
    await settle(); api.draftAssistRuns = () => new Promise(resolve => pages.push(resolve))
    api.draftAssistRun = async (_, id) => { details.push(id); return detail() }
    api.generateDraftAssist = async () => ({ id: uuid(2) })
    p.state.brief = '本次输入'; const saving = p.state.generate(); await settle()
    p.props.scopeKey = 'other/current'; await settle()
    pages[1](page()); await settle(); pages[0](page()); await saving
    assert.deepEqual(details, []); assert.equal(p.state.notice, ''); assert.equal(p.state.sending, false)
  } finally { p.close() }
})

test('详情超时解除读取状态并拒绝迟到正文，拒绝访问后清空全部私有数据', async () => {
  stubReads(); const p = panel(); const originalTimeout = globalThis.setTimeout, originalClear = globalThis.clearTimeout
  let resolve, timeout
  try {
    await settle(); globalThis.setTimeout = callback => { timeout = callback; return 1 }; globalThis.clearTimeout = () => {}
    api.draftAssistRun = () => new Promise(done => { resolve = done })
    const reading = p.state.loadDetail(uuid(2)); timeout(); assert.equal(p.state.loading.detail, false)
    assert.match(p.state.errors.detail, /超时/); resolve(detail()); await reading; assert.equal(p.state.detail, null)
    stubReads(); await p.state.loadDetail(uuid(2)); p.state.brief = '私有内容'
    api.draftAssistInput = async () => { throw { status: 403, code: 'FORBIDDEN' } }
    await p.state.loadInput(); assert.equal(p.state.detail, null); assert.equal(p.state.options, null); assert.equal(p.state.brief, '')
  } finally { globalThis.setTimeout = originalTimeout; globalThis.clearTimeout = originalClear; p.close() }
})

test('保存成功后的重读即使忽略取消也有等待上限，不永久锁住原表单', async () => {
  stubReads(); const p = panel(); const originalTimeout = globalThis.setTimeout, originalClear = globalThis.clearTimeout
  const timers = []; let resolveList, done = false, saving
  try {
    await settle(); globalThis.setTimeout = callback => { timers.push(callback); return timers.length }; globalThis.clearTimeout = () => {}
    api.draftAssistRuns = () => new Promise(resolve => { resolveList = resolve })
    api.generateDraftAssist = async () => ({ id: uuid(2) })
    p.state.brief = '本次输入'; saving = p.state.generate().then(() => { done = true }); await settle()
    timers[0](); await settle()
    assert.equal(done, true); assert.equal(p.state.sending, false)
  } finally { resolveList?.(page()); await saving; globalThis.setTimeout = originalTimeout; globalThis.clearTimeout = originalClear; p.close() }
})

test('实际模板转义模型及来源内容，并展示明确的人工保存入口', async () => {
  stubReads(); const value = detail(); value.suggestion.proposals[0].value = '<img src=x onerror=alert(1)>'
  value.sources[1].content = JSON.stringify('<script>alert(2)</script>'); api.draftAssistRun = async () => value
  const props = { applicationId: uuid(1), scopeKey: 'demo/render', version: 1, editable: true, locked: false, applicationDirty: false }
  draft.rememberDraftRun(props.scopeKey, props.applicationId, uuid(2))
  const html = await renderToString(createSSRApp({ ...Rendered, async setup(props, ctx) { const render = Rendered.setup(props, ctx); await settle(); await settle(); return render } }, props))
  assert.ok(html.includes('&lt;img')); assert.ok(html.includes('&lt;script&gt;')); assert.ok(!html.includes('<script>'))
  assert.match(html, /模型原值/); assert.match(html, /查看来源/); assert.match(html, /保存勾选字段到草稿/)
})

test('所有读取禁用缓存，错误成功回执保留原请求字节和幂等键供恢复', async () => {
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'draft-request' })
  const reads = []; globalThis.fetch = async (url, options) => { reads.push(options); return Response.json(url.endsWith('/input') ? input() : url.includes('?') ? page() : detail()) }
  await api.draftAssistInput(uuid(1)); await api.draftAssistRuns(uuid(1), 0); await api.draftAssistRun(uuid(1), uuid(2))
  assert.ok(reads.every(options => options.cache === 'no-store'))
  const sent = [], receipt = { id: uuid(2), status: 'ADOPTED', version: 4, savedApplicationVersion: 2 }
  globalThis.fetch = async (_, options) => { sent.push(options); return Response.json({ ...receipt, id: sent.length === 1 ? uuid(3) : uuid(2) }) }
  const body = { expectedRunVersion: 3, expectedApplicationVersion: 1, action: 'ADOPT', selected: [{ targetId: 'form:reason', value: '人工值' }] }
  await assert.rejects(api.reviewDraftAssist(uuid(1), uuid(2), body), unreadable)
  assert.equal(writeRequests.pending().length, 1); body.selected[0].value = '后续编辑'
  const recovered = await writeRequests.recover(writeRequests.pending()[0].id)
  assert.deepEqual(recovered.result, receipt); assert.equal(writeRequests.pending().length, 0)
  assert.equal(sent[0].body, sent[1].body); assert.equal(sent[0].headers.get('Idempotency-Key'), sent[1].headers.get('Idempotency-Key'))
})

test('恢复原草稿建议请求只重读原申请并记住运行，不将回执作为完整申请', async () => {
  const box = value => ({ value }), path = `/applications/${uuid(1)}/draft-assist-runs/${uuid(2)}/review`
  const pending = { id: 'request', path, sending: false }, actorScope = 'demo/recover-draft'
  globalThis.document = { querySelector: () => null }
  const env = { pendingWrites: box([pending]), draftScope: box(''), actorScope: box(actorScope), rememberDraftRun: draft.rememberDraftRun,
    confirmReplaceDefinition: async (_, action) => action(), busy: box(false), recoveryError: box(''), notice: box(''), recordApplicationId: box(uuid(1)), recordRefresh: box(0),
    writeRequests: { recover: async () => ({ request: pending, result: { id: uuid(2), status: 'ADOPTED', version: 4, savedApplicationVersion: 2 } }) },
    refreshWorkspace: async () => {}, nextTick, workspace: box(null), errorMessage: error => error.message }
  await createRecovery(env)('request')
  assert.equal(env.recoveryError.value, ''); assert.equal(env.recordRefresh.value, 1)
  assert.equal(draft.focusedDraftRun(actorScope, uuid(1)), uuid(2)); assert.equal(draft.focusedDraftRun('other', uuid(1)), '')
  assert.match(env.notice.value, /尚未自动提交审批/)
})

const application = () => ({ id: uuid(1), processKey: 'draft', definitionVersion: 1, createdBy: 'alice', status: 'DRAFT', title: '原标题', payload: {}, version: 1, roundNo: 0, formSchema: schema() })
function applicationRecord() {
  globalThis.document = { activeElement: null }; api.application = async () => application(); api.applicationRounds = async () => []
  api.applicationInitiatorRequirements = async () => ({ processKey: 'draft', definitionVersion: 1, appointmentRequired: false })
  const events = [], app = renderer.createApp({ ...Record, render: () => null }, { applicationId: uuid(1), userId: 'alice', scopeKey: 'demo/alice', commentRefreshVersion: 0, pendingWrites: [], recoveryError: '', onClose: () => events.push('close') })
  return { state: app.mount({}).$.setupState, events, close: () => app.unmount() }
}

test('申请详情在助手忙碌或未保存时不关闭，已保存后读取失败不显示旧可编辑正文', async () => {
  const p = applicationRecord()
  try {
    await settle(); p.state.draftAssistBusy = true; p.state.close(); assert.deepEqual(p.events, [])
    p.state.draftAssistBusy = false; p.state.draftAssistDirty = true; p.state.close(); assert.deepEqual(p.events, [])
    p.state.draftAssistDirty = false; api.application = async () => { throw { message: '重读失败' } }
    await p.state.draftAssistSaved(); assert.equal(p.state.application, null); assert.equal(p.state.canEdit, false); assert.equal(p.state.error, '重读失败')
  } finally { p.close() }
})

test('历史建议仍在人工核对或保存时不能同时打开撤回申请', async () => {
  const p = applicationRecord()
  try {
    await settle(); p.state.application = { ...application(), status: 'IN_APPROVAL', roundNo: 1 }
    p.state.runtimeState = { applicationId: uuid(1), applicationVersion: 1, roundNo: 1, state: 'RUNNING' }
    assert.equal(p.state.canWithdraw, true)
    p.state.draftAssistBusy = true; await p.state.openWithdrawal(); assert.equal(p.state.withdrawalOpen, false)
    p.state.draftAssistBusy = false; p.state.draftAssistDirty = true; await p.state.openWithdrawal(); assert.equal(p.state.withdrawalOpen, false)
  } finally { p.close() }
})

test('助手有未保存内容时不能从原表单保存或提交导致建议输入失效', async () => {
  const p = applicationRecord(), writes = []
  api.updateApplication = async () => { writes.push('save'); return application() }
  api.submitApplication = async () => { writes.push('submit'); return application() }
  try {
    await settle(); p.state.title = '更改标题'; p.state.draftAssistDirty = true
    await p.state.save(false); await p.state.save(true); assert.deepEqual(writes, [])
  } finally { p.close() }
})
