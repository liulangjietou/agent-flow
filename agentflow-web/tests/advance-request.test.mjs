import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { emptyAdvance, advanceContent, advanceDefinition, usableAdvanceCheck, AdvanceDrafts, advanceDrafts } = await import(process.env.AGENTFLOW_TEST_ADVANCE_REQUEST)
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_ADVANCEREQUESTEDITOR)
const { default: Submission } = await import(process.env.AGENTFLOW_TEST_ADVANCEREQUESTSUBMISSION)
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_ADVANCEREQUESTDETAIL)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const copy = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const money = (value, currency = 'CNY') => ({ value, currency })
const catalog = () => ({ employeeId: 'alice', sourceVersion: 'v1', validUntil: new Date(Date.now() + 3600000).toISOString(), legalEntities: [{ id: 'legal', name: '甲公司', baseCurrency: 'CNY', timeZone: 'Asia/Shanghai' }], categories: [{ code: 'OFFICE', name: '办公费', units: ['ITEM'] }], cities: [{ code: 'SH', name: '上海' }], costCenters: [{ legalEntityId: 'legal', code: 'IT', name: '研发' }], projects: [] })
const content = () => ({ legalEntityId: 'legal', title: ' 出差借款 ', purpose: ' 客户现场交流 ', amount: money('100.00'), dueOn: '2026-10-10' })
const definition = () => ({ id: 'definition', key: 'expense', name: '费用', version: 2, status: 'PUBLISHED', startEnabled: true, formSchema: { fields: Object.entries({ advanceRequestDetails: 'TEXT', amount: 'NUMBER', currency: 'TEXT' }).map(([key, type]) => ({ key, type, required: true, ...(key === 'advanceRequestDetails' ? { sensitive: true } : {}) })) } })
const detail = () => ({ id: 'report', applicationId: 'app', businessNo: 'EXP-1', status: 'DRAFT', applicationVersion: 2, requestVersion: 4, roundNo: 1, editable: true, content: content(), financialRound: null })
const options = () => ({ applicationVersion: 2, requestVersion: 4, enabled: true, latestPrecheckId: 'precheck', destination: 'finance.test', targetDigest: 'a'.repeat(64) })
const view = () => ({ job: { id: 'precheck', applicationVersion: 2, requestVersion: 4, status: 'READY', attempt: 1 }, usable: true, unavailableCode: null, initiator: { appointmentId: 'appointment', legalEntityId: 'legal' }, validUntil: new Date(Date.now() + 60000).toISOString(), preview: { roundNo: 1, submittedRequestVersion: 4, content: content(), legalEntity: catalog().legalEntities[0], maskedAccount: '****1234' } })
const receipt = (value = detail()) => Object.fromEntries(['id', 'applicationId', 'applicationVersion', 'requestVersion', 'roundNo', 'status'].map(key => [key, value[key]]))
let scopeIndex = 0
function panel(Component, initial) {
  const props = reactive(initial), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onSubmitted: id => events.push(id), onClose: () => events.push('close') })
  const mounted = app.mount({})
  return { state: mounted.$.setupState, props, events, close(cleanup = true) { app.unmount(); Object.assign(api, originals); if (cleanup) advanceDrafts.clear(props.scopeKey, props.initial?.id ?? '') } }
}
function editor(initial) {
  api.financeCatalog = async () => catalog()
  return panel(Editor, { scopeKey: 'editor-' + ++scopeIndex, initial, locked: false })
}
function submission() {
  api.advanceCheckOptions = async () => options(); api.advanceCheck = async () => view()
  return panel(Submission, { detail: detail(), scopeKey: 'alice', timeZone: 'Asia/Shanghai', locked: false })
}

test('保存保持精确十进制金额，仅发送借款约定，不携带账号和批准事实', () => {
  const value = { ...content(), accountReference: 'forged', accountDigest: 'a'.repeat(64), approved: true }; value.amount.value = '999999999999999.99'
  const before = copy(value), result = advanceContent(value, catalog())
  assert.deepEqual(value, before); assert.equal(result.amount.value, '999999999999999.99'); assert.equal(result.title, '出差借款')
  assert.deepEqual(Object.keys(result).sort(), ['legalEntityId', 'title', 'purpose', 'amount', 'dueOn'].sort())
  result.amount.value = '0'; assert.deepEqual(value, before)
})

test('历史 READY 必须匹配当前保存版本、下一轮、任职、法人和有效期', () => {
  assert.equal(usableAdvanceCheck(view(), detail(), 'appointment'), true)
  for (const status of ['RETURNED', 'WITHDRAWN']) {
    const returned = { ...detail(), status }, next = view(); next.preview.roundNo = 2
    assert.equal(usableAdvanceCheck(next, returned, 'appointment'), true)
    assert.equal(usableAdvanceCheck(view(), returned, 'appointment'), false)
  }
  for (const modify of [value => { value.usable = false }, value => { value.job.requestVersion++ }, value => { value.job.applicationVersion++ }, value => { value.preview.roundNo++ }, value => { value.initiator.legalEntityId = 'other' }, value => { value.preview.submittedRequestVersion++ }, value => { value.validUntil = '2000-01-01' }]) { const value = view(); modify(value); assert.equal(usableAdvanceCheck(value, detail(), 'appointment'), false) }
})

test('保存恢复按账号、原路径和原正文绑定，只确认原草稿而不触发提交', () => {
  const drafts = new AdvanceDrafts(), result = detail(), body = JSON.stringify({ content: result.content })
  drafts.put('alice', '', { detail: null, content: result.content, businessNo: 'EXP-1', definition: definition(), baseline: '{}', pending: { path: '/advance-requests', body }, requiresRefresh: true })
  assert.equal(drafts.get('bob', ''), null); assert.equal(drafts.hasDrafts(), true)
  assert.equal(drafts.acknowledge('bob', '/advance-requests', body, receipt(result)), false)
  assert.equal(drafts.acknowledge('alice', '/advance-requests', '{}', result), false)
  assert.equal(drafts.acknowledge('alice', '/advance-requests', body, receipt(result)), true)
  const stored = drafts.get('alice', ''); assert.equal(stored.detail, null); assert.equal(stored.receipt.id, 'report'); assert.equal(stored.requiresRefresh, true); assert.equal(stored.pending, null)
  stored.content.title = '外部修改'; assert.notEqual(drafts.get('alice', '').content.title, '外部修改')
})

test('只填写业务编号时离开浏览器也属于未保存内容，保存或显式放弃后解除提示', () => {
  const drafts = new AdvanceDrafts(), value = emptyAdvance()
  drafts.put('alice', '', { detail: null, content: value, businessNo: 'EXP-ONLY-NUMBER', definition: null, baseline: JSON.stringify(value), pending: null, requiresRefresh: false })
  assert.equal(drafts.hasDrafts(), true)
  drafts.clear('alice', ''); assert.equal(drafts.hasDrafts(), false)
})

test('实际编辑器用最小保存回执重新读取，随后修改沿用服务端双版本', async () => {
  const p = editor(), calls = []; let unexpected = 0, saved = detail(), reads = 0
  api.queueAdvanceCheck = api.submitAdvanceRequest = async () => { unexpected++ }
  api.createAdvanceRequest = async body => { calls.push(body); saved = { ...detail(), content: body.content }; return receipt(saved) }
  api.reviseAdvanceRequest = async (id, body) => { calls.push([id, body]); saved = { ...saved, applicationVersion: 3, requestVersion: 5, content: body.content }; return receipt(saved) }
  api.advanceRequest = async () => { reads++; return copy(saved) }
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-1'; p.state.state.definition = definition()
    await p.state.save(); assert.equal(p.state.state.detail.id, 'report'); assert.equal(p.state.dirty, false); assert.equal(p.state.state.pending, null)
    p.state.state.content.title = '修改后的借款'; await p.state.save()
    assert.equal(calls[0].processKey, 'expense'); assert.equal(calls[1][0], 'report'); assert.equal(calls[1][1].applicationVersion, 2); assert.equal(calls[1][1].requestVersion, 4); assert.equal(unexpected, 0); assert.equal(reads, 2)
    p.state.close(); assert.deepEqual(p.events, ['close']); assert.equal(advanceDrafts.get(p.props.scopeKey, ''), null)
  } finally { p.close() }
})

test('版本冲突保留本地内容，阻止再次写入，用户明确读服务器版后才可继续', async () => {
  const p = editor(detail()); let writes = 0
  api.reviseAdvanceRequest = async () => { writes++; throw { status: 409, code: 'CONCURRENCY_CONFLICT' } }
  api.advanceRequest = async () => ({ ...detail(), applicationVersion: 9, requestVersion: 10 })
  try {
    await settle(); p.state.state.content.title = '不能自动覆盖'; await p.state.save(); await p.state.save()
    assert.equal(writes, 1); assert.equal(p.state.state.content.title, '不能自动覆盖'); assert.equal(p.state.state.detail.applicationVersion, 2)
    await p.state.reloadSaved(); assert.equal(p.state.state.detail.applicationVersion, 9); assert.equal(p.state.state.requiresRefresh, false); assert.equal(writes, 1)
  } finally { p.close() }
})

test('未知保存响应用原最小回执恢复，重新读取原单后才允许继续提交', async () => {
  const p = editor(); let count = 0
  api.createAdvanceRequest = async () => { count++; throw { status: 0, code: 'REQUEST_TIMEOUT' } }
  api.advanceRequest = async () => detail()
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-1'; p.state.state.definition = definition()
    await p.state.save(); assert.equal(p.state.state.requiresRefresh, true)
    const original = p.state.state.pending
    assert.equal(advanceDrafts.acknowledge(p.props.scopeKey, original.path, original.body, receipt()), true)
    assert.equal(p.state.state.detail, null); assert.equal(p.state.state.requiresRefresh, true)
    await p.state.save(); assert.equal(count, 1)
    await p.state.reloadSaved(); assert.equal(p.state.state.detail.id, 'report'); assert.equal(p.state.state.requiresRefresh, false); assert.equal(count, 1)
  } finally { p.close() }
})

test('切换身份与目录超时清空财务目录，迟到响应不能回填', async t => {
  const reads = []; api.financeCatalog = (_signal) => new Promise(resolve => reads.push(resolve))
  const p = panel(Editor, { scopeKey: 'old-scope', locked: false })
  try {
    p.state.state.content.title = '旧账号私密草稿'; p.props.scopeKey = 'new-scope'
    assert.equal(p.state.state.content.title, ''); reads[0](catalog()); await settle(); assert.equal(p.state.catalog, null)
    reads[1](catalog()); await settle(); assert.ok(p.state.catalog)
    t.mock.timers.enable({ apis: ['setTimeout'] }); const loading = p.state.loadCatalog(); t.mock.timers.tick(12000)
    assert.equal(p.state.loading, false); assert.equal(p.state.catalog, null); reads[2](catalog()); await loading; assert.equal(p.state.catalog, null)
  } finally { p.close(); advanceDrafts.clear('old-scope', '') }
})

test('原草稿换页后恢复，编辑器显式放弃后不由卸载重新保存', async () => {
  const p = editor(); await settle(); const scope = p.props.scopeKey
  p.state.state.content.title = '尚未保存'; p.close(false)
  assert.equal(advanceDrafts.get(scope, '').content.title, '尚未保存')
  api.financeCatalog = async () => catalog()
  const other = panel(Editor, { scopeKey: scope, locked: false }); await settle()
  try { assert.equal(other.state.state.content.title, '尚未保存'); other.state.close(); assert.equal(other.state.discard, true); other.state.leave() }
  finally { other.close(false) }
  assert.equal(advanceDrafts.get(scope, ''), null)
})

test('预检必须显式选择任职，排队只发送保存版本及实际目标', async () => {
  const p = submission(), calls = []
  api.advanceCheckOptions = async () => ({ ...options(), latestPrecheckId: null })
  api.queueAdvanceCheck = async (...args) => { calls.push(args); return { id: 'precheck' } }
  try {
    await settle(); await p.state.queue(); assert.equal(calls.length, 0)
    p.state.appointment = 'appointment'; await p.state.queue()
    assert.deepEqual(calls, [['report', { applicationVersion: 2, requestVersion: 4, initiatorAppointmentId: 'appointment', targetDigest: 'a'.repeat(64) }]])
    assert.equal(p.state.saving, false); assert.equal(p.state.ready, true)
  } finally { p.close() }
})

test('正式提交需二次确认，使用原预检和双版本；改变任职撤销确认', async () => {
  const p = submission(), calls = []
  api.submitAdvanceRequest = async (...args) => { calls.push(args); return { id: 'report', applicationId: 'app' } }
  try {
    await settle(); p.state.appointment = 'appointment'; await settle()
    await p.state.submit(); assert.equal(calls.length, 0); p.state.prepare(); assert.equal(p.state.confirm, true)
    p.state.appointment = 'different'; await settle(); assert.equal(p.state.confirm, false)
    p.state.appointment = 'appointment'; await settle(); p.state.prepare(); await p.state.submit()
    assert.deepEqual(calls, [['report', { applicationVersion: 2, requestVersion: 4, precheckId: 'precheck' }]]); assert.deepEqual(p.events, ['app'])
  } finally { p.close() }
})

test('确认期间财务事实到期，即使已缓存 READY 也不发送正式提交', async t => {
  const p = submission(); let writes = 0
  api.submitAdvanceRequest = async () => { writes++; return { id: 'report', applicationId: 'app' } }
  try {
    await settle(); p.state.appointment = 'appointment'; await settle(); p.state.prepare(); assert.equal(p.state.ready, true)
    t.mock.method(Date, 'now', () => Date.parse(p.state.result.validUntil) + 1)
    await p.state.submit(); assert.equal(writes, 0)
  } finally { p.close() }
})

test('预检状态读取超时或版本不匹配不显示旧借款和可提交按钮', async t => {
  const p = submission()
  try {
    await settle(); assert.ok(p.state.result)
    api.advanceCheckOptions = async () => ({ ...options(), requestVersion: 8 }); await p.state.load(); assert.equal(p.state.options, null); assert.equal(p.state.result, null)
    t.mock.timers.enable({ apis: ['setTimeout'] }); let finish
    api.advanceCheckOptions = () => new Promise(resolve => { finish = resolve }); const waiting = p.state.load(); t.mock.timers.tick(12000)
    assert.equal(p.state.reading, false); assert.equal(p.state.result, null); finish(options()); await waiting; assert.equal(p.state.result, null)
  } finally { p.close() }
})

test('借款 API 保留精确正文、双版本及查询无缓存，未知创建按原幂等键恢复', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, calls = []
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'origination', roles: [] })
  let lose = true
  globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); if (lose) { lose = false; throw new TypeError('network') } return new Response(JSON.stringify(detail()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  try {
    const body = { businessNo: 'EXP-1', processKey: 'expense', definitionVersion: 2, content: content() }
    await assert.rejects(api.createAdvanceRequest(body)); const pending = writeRequests.pending()[0]; await writeRequests.recover(pending.id)
    assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']); assert.equal(JSON.parse(calls[1].body).content.amount.value, '100.00')
    await api.advanceCheck('id/with?chars', 'job/name', new AbortController().signal)
    assert.match(calls[2].url, /id%2Fwith%3Fchars\/prechecks\/job%2Fname/); assert.equal(calls[2].cache, 'no-store')
  } finally { bindAuthenticationActor(null); globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage }
})

test('借款契约、日期、本位币与正金额在保存前核对，不在浏览器推算归还政策', () => {
  assert.equal(advanceDefinition(definition()), true)
  for (const modify of [value => { value.formSchema.fields[0].sensitive = false }, value => { value.formSchema.fields.push({ key: 'tax', type: 'NUMBER', required: true }) }, value => { value.formSchema.fields[1].type = 'TEXT' }]) {
    const value = definition(); modify(value); assert.equal(advanceDefinition(value), false)
  }
  for (const modify of [value => { value.legalEntityId = 'other' }, value => { value.dueOn = '2026-02-30' }, value => { value.purpose = ' ' }, value => { value.amount.value = '0.00' }, value => { value.amount.value = '1.001' }, value => { value.amount.currency = 'USD' }]) {
    const value = content(); modify(value); assert.throws(() => advanceContent(value, catalog()))
  }
  assert.throws(() => advanceContent(content(), { ...catalog(), validUntil: '2000-01-01' }), /过期/)
  assert.equal(advanceContent({ ...content(), dueOn: '2020-01-01' }, catalog()).dueOn, '2020-01-01')
})

test('父组件用相同身份与版本的新对象刷新时保留已选择的任职与提交确认', async () => {
  const p = submission()
  try {
    await settle(); p.state.appointment = 'appointment'; await settle(); p.state.prepare()
    p.props.detail = copy(p.props.detail); await settle()
    assert.equal(p.state.appointment, 'appointment'); assert.equal(p.state.confirm, true)
    p.props.detail.requestVersion++; await settle()
    assert.equal(p.state.appointment, ''); assert.equal(p.state.confirm, false); assert.equal(p.state.ready, false)
  } finally { p.close() }
})

function advanceDetail(input = {}) {
  api.advanceRequest = async () => detail()
  return panel(Detail, { requestId: 'report', applicationId: 'app', scopeKey: 'alice', version: 2, owner: true, ...input })
}

test('借款撤回和作废由本人显式确认，说明与双版本一起提交', async () => {
  const p = advanceDetail(), calls = []
  api.cancelAdvanceRequest = async (...args) => { calls.push(args); return { id: 'report', applicationId: 'app' } }
  try {
    await settle(); await p.state.execute(); assert.equal(calls.length, 0)
    p.state.prepare('WITHDRAW'); assert.equal(p.state.pending, null)
    p.state.prepare('CANCEL'); await p.state.execute(); assert.equal(calls.length, 0)
    p.state.comment = ' 借款取消 '; await p.state.execute()
    assert.deepEqual(calls, [['report', { applicationVersion: 2, requestVersion: 4, comment: '借款取消' }]])
    api.advanceRequest = async () => ({ ...detail(), status: 'IN_APPROVAL' }); await p.state.load()
    api.withdrawAdvanceRequest = async (...args) => { calls.push(args); return { id: 'report', applicationId: 'app' } }
    p.state.prepare('WITHDRAW'); p.state.comment = '补充借款'; await p.state.execute(); assert.equal(calls.length, 2)
  } finally { p.close() }
})

test('非本人和历史快照不能调用借款生命周期写入，批准后也不能作废', async () => {
  for (const [input, status] of [[{ owner: false }, 'DRAFT'], [{ roundNo: 1 }, 'IN_APPROVAL'], [{}, 'APPROVED']]) {
    const p = advanceDetail(input); let writes = 0
    api.advanceRequest = async () => ({ ...detail(), status, financialRound: view().preview })
    api.cancelAdvanceRequest = api.withdrawAdvanceRequest = async () => { writes++; throw new Error('unexpected') }
    try {
      await settle(); await p.state.load(); p.state.prepare('CANCEL'); p.state.prepare('WITHDRAW'); await p.state.execute()
      assert.equal(p.state.pending, null); assert.equal(writes, 0)
    } finally { p.close() }
  }
})

test('借款详情按申请和历史轮次绑定，403 不保留完整内容并启用字段投影展示', async () => {
  const p = advanceDetail()
  try {
    await settle(); assert.ok(p.state.detail)
    api.advanceRequest = async () => ({ ...detail(), applicationId: 'different' }); await p.state.load(); assert.equal(p.state.detail, null)
    api.advanceRequest = async () => ({ ...detail(), financialRound: { ...view().preview, roundNo: 2 } })
    p.props.roundNo = 1; await settle(); assert.equal(p.state.detail, null)
    api.advanceRequest = async () => { throw { status: 403 } }; await p.state.load()
    assert.equal(p.state.detail, null); assert.equal(p.state.restricted, true)
  } finally { p.close() }
})

test('借款详情切换身份和读取超时后不会显示迟到的敏感内容', async t => {
  const p = advanceDetail(), reads = []
  try {
    await settle(); api.advanceRequest = () => new Promise(resolve => reads.push(resolve))
    p.props.scopeKey = 'bob'; assert.equal(p.state.detail, null)
    p.props.scopeKey = 'carol'; reads[0](detail()); await settle(); assert.equal(p.state.detail, null)
    reads[1](detail()); await settle(); assert.ok(p.state.detail)
    t.mock.timers.enable({ apis: ['setTimeout'] }); const pending = p.state.load(); t.mock.timers.tick(12000)
    assert.equal(p.state.loading, false); assert.equal(p.state.detail, null); reads[2](detail()); await pending; assert.equal(p.state.detail, null)
  } finally { p.close() }
})

test('借款写入失败后禁止更换版本重放，刷新最新状态后才能重新发起', async () => {
  const p = advanceDetail(); let writes = 0
  api.cancelAdvanceRequest = async () => { writes++; throw { status: 409, code: 'CONCURRENCY_CONFLICT' } }
  try {
    await settle(); p.state.prepare('CANCEL'); p.state.comment = '不再需要'; await p.state.execute(); await p.state.execute()
    assert.equal(writes, 1); assert.equal(p.state.requiresRefresh, true)
    api.advanceRequest = async () => ({ ...detail(), applicationVersion: 10 }); await p.state.load()
    assert.equal(p.state.pending, null); assert.equal(p.state.requiresRefresh, false); assert.equal(p.state.detail.applicationVersion, 10)
  } finally { p.close() }
})

test('保存回执读取失败保留原单标识，刷新内容不会再次创建', async () => {
  const p = editor(); let creates = 0
  api.createAdvanceRequest = async () => { creates++; return receipt() }
  api.advanceRequest = async () => { throw { status: 503 } }
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-1'; p.state.state.definition = definition()
    await p.state.save(); assert.equal(p.state.state.detail, null); assert.equal(p.state.state.receipt.id, 'report'); assert.equal(p.state.state.requiresRefresh, true)
    await p.state.save(); assert.equal(creates, 1)
    api.advanceRequest = async () => detail(); await p.state.reloadSaved()
    assert.equal(p.state.state.detail.id, 'report'); assert.equal(p.state.state.receipt, null); assert.equal(p.state.dirty, false)
  } finally { p.close() }
})

test('刷新目录期间读取保存回执不会中断目录并留下永久加载状态', async () => {
  const p = editor(detail()); let finishCatalog, reads = 0
  try {
    await settle(); api.financeCatalog = () => new Promise(resolve => { finishCatalog = resolve })
    api.advanceRequest = async () => { reads++; return detail() }
    const loading = p.state.loadCatalog(); await p.state.reloadSaved()
    assert.equal(reads, 0)
    finishCatalog(catalog()); await loading; assert.equal(p.state.loading, false); assert.ok(p.state.catalog)
    await p.state.reloadSaved(); assert.equal(reads, 1)
  } finally { p.close() }
})
