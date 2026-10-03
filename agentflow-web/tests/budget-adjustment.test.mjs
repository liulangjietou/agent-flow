import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from '@vue/server-renderer'
const { emptyBudgetAdjustment, budgetAdjustmentContent, budgetAdjustmentDefinition, usableBudgetAdjustmentCheck, BudgetAdjustmentDrafts, budgetAdjustmentDrafts } = await import(process.env.AGENTFLOW_TEST_BUDGET_ADJUSTMENT)
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_BUDGETADJUSTMENTEDITOR)
const { default: Submission } = await import(process.env.AGENTFLOW_TEST_BUDGETADJUSTMENTSUBMISSION)
const { default: Terms } = await import(process.env.AGENTFLOW_TEST_BUDGETADJUSTMENTTERMS)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_BUDGETADJUSTMENTWORKSPACE)
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_BUDGETADJUSTMENTDETAIL)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const copy = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const money = (value, currency = 'CNY') => ({ value, currency })
const catalog = () => ({ employeeId: 'alice', sourceVersion: 'v1', validUntil: new Date(Date.now() + 3600000).toISOString(), legalEntities: [{ id: 'legal', name: '甲公司', baseCurrency: 'CNY', timeZone: 'Asia/Shanghai' }], categories: [{ code: 'OFFICE', name: '办公费', units: ['ITEM'] }], cities: [{ code: 'SH', name: '上海' }], costCenters: [{ legalEntityId: 'legal', code: 'IT', name: '研发' }], projects: [] })
const content = () => ({ legalEntityId: 'legal', title: ' 出差预算调整 ', purpose: ' 客户现场交流 ', amount: money('100.00'), type: 'TRANSFER', accountingDate: '2026-09-29', sourceBudgetReference: 'BUDGET-A', targetBudgetReference: 'BUDGET-B' })
const definition = () => ({ id: 'definition', key: 'expense', name: '费用', version: 2, status: 'PUBLISHED', startEnabled: true, formSchema: { fields: Object.entries({ budgetAdjustmentDetails: 'TEXT', amount: 'NUMBER', currency: 'TEXT' }).map(([key, type]) => ({ key, type, required: true, ...(key === 'budgetAdjustmentDetails' ? { sensitive: true } : {}) })) } })
const detail = () => ({ id: 'report', applicationId: 'app', businessNo: 'EXP-1', status: 'DRAFT', applicationVersion: 2, requestVersion: 4, roundNo: 1, editable: true, content: content(), financialRound: null })
const options = () => ({ applicationVersion: 2, requestVersion: 4, enabled: true, latestPrecheckId: 'precheck', destination: 'finance.test', targetDigest: 'a'.repeat(64) })
const view = () => ({ job: { id: 'precheck', applicationVersion: 2, requestVersion: 4, status: 'READY', attempt: 1 }, usable: true, unavailableCode: null, initiator: { appointmentId: 'appointment', legalEntityId: 'legal' }, validUntil: new Date(Date.now() + 60000).toISOString(), preview: { roundNo: 1, submittedRequestVersion: 4, content: content(), legalEntity: catalog().legalEntities[0], ledgerVersion: 'ledger-01', observedAt: new Date().toISOString(), positions: positions() } })
const positions = () => ['BUDGET-A', 'BUDGET-B'].map((budgetReference, index) => ({ budgetReference, name: index ? '营销预算' : '研发预算', version: '01', periodReference: 'FY2026', periodStart: '2026-01-01', periodEnd: '2026-12-31', periodStatus: 'OPEN', beforeLimit: money('1000'), committed: money('200'), consumed: money('100'), available: money('700'), proposedLimit: money(index ? '1100' : '900') }))
const receipt = (value = detail()) => Object.fromEntries(['id', 'applicationId', 'applicationVersion', 'requestVersion', 'roundNo', 'status'].map(key => [key, value[key]]))
let scopeIndex = 0
function panel(Component, initial) {
  const props = reactive(initial), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onSubmitted: id => events.push(id), onClose: () => events.push('close') })
  const mounted = app.mount({})
  return { state: mounted.$.setupState, props, events, close(cleanup = true) { app.unmount(); Object.assign(api, originals); if (cleanup) budgetAdjustmentDrafts.clear(props.scopeKey, props.initial?.id ?? '') } }
}
function editor(initial) {
  api.financeCatalog = async () => catalog()
  return panel(Editor, { scopeKey: 'editor-' + ++scopeIndex, initial, locked: false })
}
function submission() {
  api.budgetAdjustmentCheckOptions = async () => options(); api.budgetAdjustmentCheck = async () => view()
  return panel(Submission, { detail: detail(), scopeKey: 'alice', timeZone: 'Asia/Shanghai', locked: false })
}

test('保存保持精确十进制金额，仅发送预算意图，不携带台账和批准事实', () => {
  const value = { ...content(), beforeLimit: money('1000000'), ledgerVersion: 'forged', approved: true }; value.amount.value = '999999999999999.99'
  const before = copy(value), result = budgetAdjustmentContent(value, catalog())
  assert.deepEqual(value, before); assert.equal(result.amount.value, '999999999999999.99'); assert.equal(result.title, '出差预算调整')
  assert.deepEqual(Object.keys(result).sort(), ['legalEntityId', 'title', 'purpose', 'type', 'accountingDate', 'amount', 'sourceBudgetReference', 'targetBudgetReference'].sort())
  result.amount.value = '0'; assert.deepEqual(value, before)
})

test('历史 READY 必须匹配当前保存版本、下一轮、任职、法人和有效期', () => {
  assert.equal(usableBudgetAdjustmentCheck(view(), detail(), 'appointment'), true)
  for (const status of ['RETURNED', 'WITHDRAWN']) {
    const returned = { ...detail(), status }, next = view(); next.preview.roundNo = 2
    assert.equal(usableBudgetAdjustmentCheck(next, returned, 'appointment'), true)
    assert.equal(usableBudgetAdjustmentCheck(view(), returned, 'appointment'), false)
  }
  for (const modify of [value => { value.usable = false }, value => { value.job.requestVersion++ }, value => { value.job.applicationVersion++ }, value => { value.preview.roundNo++ }, value => { value.initiator.legalEntityId = 'other' }, value => { value.preview.submittedRequestVersion++ }, value => { value.validUntil = '2000-01-01' }]) { const value = view(); modify(value); assert.equal(usableBudgetAdjustmentCheck(value, detail(), 'appointment'), false) }
})

test('保存恢复按账号、原路径和原正文绑定，只确认原草稿而不触发提交', () => {
  const drafts = new BudgetAdjustmentDrafts(), result = detail(), body = JSON.stringify({ content: result.content })
  drafts.put('alice', '', { detail: null, content: result.content, businessNo: 'EXP-1', definition: definition(), baseline: '{}', pending: { path: '/budget-adjustments', body }, requiresRefresh: true })
  assert.equal(drafts.get('bob', ''), null); assert.equal(drafts.hasDrafts(), true)
  assert.equal(drafts.acknowledge('bob', '/budget-adjustments', body, receipt(result)), false)
  assert.equal(drafts.acknowledge('alice', '/budget-adjustments', '{}', result), false)
  assert.equal(drafts.acknowledge('alice', '/budget-adjustments', body, receipt(result)), true)
  const stored = drafts.get('alice', ''); assert.equal(stored.detail, null); assert.equal(stored.receipt.id, 'report'); assert.equal(stored.requiresRefresh, true); assert.equal(stored.pending, null)
  stored.content.title = '外部修改'; assert.notEqual(drafts.get('alice', '').content.title, '外部修改')
})

test('只填写业务编号时离开浏览器也属于未保存内容，保存或显式放弃后解除提示', () => {
  const drafts = new BudgetAdjustmentDrafts(), value = emptyBudgetAdjustment()
  drafts.put('alice', '', { detail: null, content: value, businessNo: 'EXP-ONLY-NUMBER', definition: null, baseline: JSON.stringify(value), pending: null, requiresRefresh: false })
  assert.equal(drafts.hasDrafts(), true)
  drafts.clear('alice', ''); assert.equal(drafts.hasDrafts(), false)
})

test('实际编辑器用最小保存回执重新读取，随后修改沿用服务端双版本', async () => {
  const p = editor(), calls = []; let unexpected = 0, saved = detail(), reads = 0
  api.queueBudgetAdjustmentCheck = api.submitBudgetAdjustment = async () => { unexpected++ }
  api.createBudgetAdjustment = async body => { calls.push(body); saved = { ...detail(), content: body.content }; return receipt(saved) }
  api.reviseBudgetAdjustment = async (id, body) => { calls.push([id, body]); saved = { ...saved, applicationVersion: 3, requestVersion: 5, content: body.content }; return receipt(saved) }
  api.budgetAdjustment = async () => { reads++; return copy(saved) }
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-1'; p.state.state.definition = definition()
    await p.state.save(); assert.equal(p.state.state.detail.id, 'report'); assert.equal(p.state.dirty, false); assert.equal(p.state.state.pending, null)
    p.state.state.content.title = '修改后的预算调整'; await p.state.save()
    assert.equal(calls[0].processKey, 'expense'); assert.equal(calls[1][0], 'report'); assert.equal(calls[1][1].applicationVersion, 2); assert.equal(calls[1][1].requestVersion, 4); assert.equal(unexpected, 0); assert.equal(reads, 2)
    p.state.close(); assert.deepEqual(p.events, ['close']); assert.equal(budgetAdjustmentDrafts.get(p.props.scopeKey, ''), null)
  } finally { p.close() }
})

test('版本冲突保留本地内容，阻止再次写入，用户明确读服务器版后才可继续', async () => {
  const p = editor(detail()); let writes = 0
  api.reviseBudgetAdjustment = async () => { writes++; throw { status: 409, code: 'CONCURRENCY_CONFLICT' } }
  api.budgetAdjustment = async () => ({ ...detail(), applicationVersion: 9, requestVersion: 10 })
  try {
    await settle(); p.state.state.content.title = '不能自动覆盖'; await p.state.save(); await p.state.save()
    assert.equal(writes, 1); assert.equal(p.state.state.content.title, '不能自动覆盖'); assert.equal(p.state.state.detail.applicationVersion, 2)
    await p.state.reloadSaved(); assert.equal(p.state.state.detail.applicationVersion, 9); assert.equal(p.state.state.requiresRefresh, false); assert.equal(writes, 1)
  } finally { p.close() }
})

test('未知保存响应用原最小回执恢复，重新读取原单后才允许继续提交', async () => {
  const p = editor(); let count = 0
  api.createBudgetAdjustment = async () => { count++; throw { status: 0, code: 'REQUEST_TIMEOUT' } }
  api.budgetAdjustment = async () => detail()
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-1'; p.state.state.definition = definition()
    await p.state.save(); assert.equal(p.state.state.requiresRefresh, true)
    const original = p.state.state.pending
    assert.equal(budgetAdjustmentDrafts.acknowledge(p.props.scopeKey, original.path, original.body, receipt()), true)
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
  } finally { p.close(); budgetAdjustmentDrafts.clear('old-scope', '') }
})

test('原草稿换页后恢复，编辑器显式放弃后不由卸载重新保存', async () => {
  const p = editor(); await settle(); const scope = p.props.scopeKey
  p.state.state.content.title = '尚未保存'; p.close(false)
  assert.equal(budgetAdjustmentDrafts.get(scope, '').content.title, '尚未保存')
  api.financeCatalog = async () => catalog()
  const other = panel(Editor, { scopeKey: scope, locked: false }); await settle()
  try { assert.equal(other.state.state.content.title, '尚未保存'); other.state.close(); assert.equal(other.state.discard, true); other.state.leave() }
  finally { other.close(false) }
  assert.equal(budgetAdjustmentDrafts.get(scope, ''), null)
})

test('预检必须显式选择任职，排队只发送保存版本及实际目标', async () => {
  const p = submission(), calls = []
  api.budgetAdjustmentCheckOptions = async () => ({ ...options(), latestPrecheckId: null })
  api.queueBudgetAdjustmentCheck = async (...args) => { calls.push(args); return { id: 'precheck' } }
  try {
    await settle(); await p.state.queue(); assert.equal(calls.length, 0)
    p.state.appointment = 'appointment'; await p.state.queue()
    assert.deepEqual(calls, [['report', { applicationVersion: 2, requestVersion: 4, initiatorAppointmentId: 'appointment', targetDigest: 'a'.repeat(64) }]])
    assert.equal(p.state.saving, false); assert.equal(p.state.ready, true)
  } finally { p.close() }
})

test('正式提交需二次确认，使用原预检和双版本；改变任职撤销确认', async () => {
  const p = submission(), calls = []
  api.submitBudgetAdjustment = async (...args) => { calls.push(args); return { id: 'report', applicationId: 'app' } }
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
  api.submitBudgetAdjustment = async () => { writes++; return { id: 'report', applicationId: 'app' } }
  try {
    await settle(); p.state.appointment = 'appointment'; await settle(); p.state.prepare(); assert.equal(p.state.ready, true)
    t.mock.method(Date, 'now', () => Date.parse(p.state.result.validUntil) + 1)
    await p.state.submit(); assert.equal(writes, 0)
  } finally { p.close() }
})

test('预检状态读取超时或版本不匹配不显示旧预算调整和可提交按钮', async t => {
  const p = submission()
  try {
    await settle(); assert.ok(p.state.result)
    api.budgetAdjustmentCheckOptions = async () => ({ ...options(), requestVersion: 8 }); await p.state.load(); assert.equal(p.state.options, null); assert.equal(p.state.result, null)
    t.mock.timers.enable({ apis: ['setTimeout'] }); let finish
    api.budgetAdjustmentCheckOptions = () => new Promise(resolve => { finish = resolve }); const waiting = p.state.load(); t.mock.timers.tick(12000)
    assert.equal(p.state.reading, false); assert.equal(p.state.result, null); finish(options()); await waiting; assert.equal(p.state.result, null)
  } finally { p.close() }
})

test('预算调整 API 保留精确正文、双版本及查询无缓存，未知创建按原幂等键恢复', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage, calls = []
  globalThis.localStorage = { getItem: () => null }; bindAuthenticationActor({ tenantId: 'demo', userId: 'origination', roles: [] })
  let lose = true
  globalThis.fetch = async (url, init) => { calls.push({ url, ...init }); if (lose) { lose = false; throw new TypeError('network') } return new Response(JSON.stringify(detail()), { status: 200, headers: { 'Content-Type': 'application/json' } }) }
  try {
    const body = { businessNo: 'EXP-1', processKey: 'expense', definitionVersion: 2, content: content() }
    await assert.rejects(api.createBudgetAdjustment(body)); const pending = writeRequests.pending()[0]; await writeRequests.recover(pending.id)
    assert.equal(calls[0].body, calls[1].body); assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']); assert.equal(JSON.parse(calls[1].body).content.amount.value, '100.00')
    await api.budgetAdjustmentCheck('id/with?chars', 'job/name', new AbortController().signal)
    assert.match(calls[2].url, /id%2Fwith%3Fchars\/prechecks\/job%2Fname/); assert.equal(calls[2].cache, 'no-store')
  } finally { bindAuthenticationActor(null); globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage }
})

test('预算调整契约、预算编号、本位币与正金额在保存前核对', () => {
  assert.equal(budgetAdjustmentDefinition(definition()), true)
  for (const modify of [value => { value.formSchema.fields[0].sensitive = false }, value => { value.formSchema.fields.push({ key: 'tax', type: 'NUMBER', required: true }) }, value => { value.formSchema.fields[1].type = 'TEXT' }]) {
    const value = definition(); modify(value); assert.equal(budgetAdjustmentDefinition(value), false)
  }
  for (const modify of [value => { value.legalEntityId = 'other' }, value => { value.targetBudgetReference = '' }, value => { value.sourceBudgetReference = 'x'.repeat(129) }, value => { value.targetBudgetReference = 'AP\n1' }, value => { value.purpose = 'text\ntext' }, value => { value.purpose = ' ' }, value => { value.amount.value = '0.00' }, value => { value.amount.value = '1.001' }, value => { value.amount.currency = 'USD' }]) {
    const value = content(); modify(value); assert.throws(() => budgetAdjustmentContent(value, catalog()))
  }
  assert.throws(() => budgetAdjustmentContent(content(), { ...catalog(), validUntil: '2000-01-01' }), /过期/)
  assert.equal(budgetAdjustmentContent({ ...content(), sourceBudgetReference: ' SUP-2 ' }, catalog()).sourceBudgetReference, 'SUP-2')
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

function budgetAdjustmentDetail(input = {}) {
  api.budgetAdjustment = async () => detail()
  return panel(Detail, { requestId: 'report', applicationId: 'app', scopeKey: 'alice', version: 2, owner: true, ...input })
}

test('预算调整撤回和作废由本人显式确认，说明与双版本一起提交', async () => {
  const p = budgetAdjustmentDetail(), calls = []
  api.cancelBudgetAdjustment = async (...args) => { calls.push(args); return { id: 'report', applicationId: 'app' } }
  try {
    await settle(); await p.state.execute(); assert.equal(calls.length, 0)
    p.state.prepare('WITHDRAW'); assert.equal(p.state.pending, null)
    p.state.prepare('CANCEL'); await p.state.execute(); assert.equal(calls.length, 0)
    p.state.comment = ' 预算调整取消 '; await p.state.execute()
    assert.deepEqual(calls, [['report', { applicationVersion: 2, requestVersion: 4, comment: '预算调整取消' }]])
    api.budgetAdjustment = async () => ({ ...detail(), status: 'IN_APPROVAL' }); await p.state.load()
    api.withdrawBudgetAdjustment = async (...args) => { calls.push(args); return { id: 'report', applicationId: 'app' } }
    p.state.prepare('WITHDRAW'); p.state.comment = '补充预算调整'; await p.state.execute(); assert.equal(calls.length, 2)
  } finally { p.close() }
})

test('非本人和历史快照不能调用预算调整生命周期写入，批准后也不能作废', async () => {
  for (const [input, status] of [[{ owner: false }, 'DRAFT'], [{ roundNo: 1 }, 'IN_APPROVAL'], [{}, 'APPROVED']]) {
    const p = budgetAdjustmentDetail(input); let writes = 0
    api.budgetAdjustment = async () => ({ ...detail(), status, financialRound: view().preview })
    api.cancelBudgetAdjustment = api.withdrawBudgetAdjustment = async () => { writes++; throw new Error('unexpected') }
    try {
      await settle(); await p.state.load(); p.state.prepare('CANCEL'); p.state.prepare('WITHDRAW'); await p.state.execute()
      assert.equal(p.state.pending, null); assert.equal(writes, 0)
    } finally { p.close() }
  }
})

test('预算调整详情按申请和历史轮次绑定，403 不保留完整内容并启用字段投影展示', async () => {
  const p = budgetAdjustmentDetail()
  try {
    await settle(); assert.ok(p.state.detail)
    api.budgetAdjustment = async () => ({ ...detail(), applicationId: 'different' }); await p.state.load(); assert.equal(p.state.detail, null)
    api.budgetAdjustment = async () => ({ ...detail(), financialRound: { ...view().preview, roundNo: 2 } })
    p.props.roundNo = 1; await settle(); assert.equal(p.state.detail, null)
    api.budgetAdjustment = async () => { throw { status: 403 } }; await p.state.load()
    assert.equal(p.state.detail, null); assert.equal(p.state.restricted, true)
  } finally { p.close() }
})

test('预算调整详情切换身份和读取超时后不会显示迟到的敏感内容', async t => {
  const p = budgetAdjustmentDetail(), reads = []
  try {
    await settle(); api.budgetAdjustment = () => new Promise(resolve => reads.push(resolve))
    p.props.scopeKey = 'bob'; assert.equal(p.state.detail, null)
    p.props.scopeKey = 'carol'; reads[0](detail()); await settle(); assert.equal(p.state.detail, null)
    reads[1](detail()); await settle(); assert.ok(p.state.detail)
    t.mock.timers.enable({ apis: ['setTimeout'] }); const pending = p.state.load(); t.mock.timers.tick(12000)
    assert.equal(p.state.loading, false); assert.equal(p.state.detail, null); reads[2](detail()); await pending; assert.equal(p.state.detail, null)
  } finally { p.close() }
})

test('预算调整写入失败后禁止更换版本重放，刷新最新状态后才能重新发起', async () => {
  const p = budgetAdjustmentDetail(); let writes = 0
  api.cancelBudgetAdjustment = async () => { writes++; throw { status: 409, code: 'CONCURRENCY_CONFLICT' } }
  try {
    await settle(); p.state.prepare('CANCEL'); p.state.comment = '不再需要'; await p.state.execute(); await p.state.execute()
    assert.equal(writes, 1); assert.equal(p.state.requiresRefresh, true)
    api.budgetAdjustment = async () => ({ ...detail(), applicationVersion: 10 }); await p.state.load()
    assert.equal(p.state.pending, null); assert.equal(p.state.requiresRefresh, false); assert.equal(p.state.detail.applicationVersion, 10)
  } finally { p.close() }
})

test('保存回执读取失败保留原单标识，刷新内容不会再次创建', async () => {
  const p = editor(); let creates = 0
  api.createBudgetAdjustment = async () => { creates++; return receipt() }
  api.budgetAdjustment = async () => { throw { status: 503 } }
  try {
    await settle(); p.state.state.content = content(); p.state.state.businessNo = 'EXP-1'; p.state.state.definition = definition()
    await p.state.save(); assert.equal(p.state.state.detail, null); assert.equal(p.state.state.receipt.id, 'report'); assert.equal(p.state.state.requiresRefresh, true)
    await p.state.save(); assert.equal(creates, 1)
    api.budgetAdjustment = async () => detail(); await p.state.reloadSaved()
    assert.equal(p.state.state.detail.id, 'report'); assert.equal(p.state.state.receipt, null); assert.equal(p.state.dirty, false)
  } finally { p.close() }
})

test('刷新目录期间读取保存回执不会中断目录并留下永久加载状态', async () => {
  const p = editor(detail()); let finishCatalog, reads = 0
  try {
    await settle(); api.financeCatalog = () => new Promise(resolve => { finishCatalog = resolve })
    api.budgetAdjustment = async () => { reads++; return detail() }
    const loading = p.state.loadCatalog(); await p.state.reloadSaved()
    assert.equal(reads, 0)
    finishCatalog(catalog()); await loading; assert.equal(p.state.loading, false); assert.ok(p.state.catalog)
    await p.state.reloadSaved(); assert.equal(reads, 1)
  } finally { p.close() }
})


test('同版本的预算编号、金额或原因与预检不一致时，不恢复提交入口', () => {
  for (const change of [value => { value.preview.content.targetBudgetReference = 'OTHER' }, value => { value.preview.content.sourceBudgetReference = 'OTHER' }, value => { value.preview.content.amount.value = '99' }, value => { value.preview.content.amount.currency = 'USD' }, value => { value.preview.content.purpose = 'changed' }, value => { value.preview.content.legalEntityId = 'other' }]) {
    const result = view(); change(result); assert.equal(usableBudgetAdjustmentCheck(result, detail(), 'appointment'), false)
  }
})

test('更换法人清除原预算编号和金额，避免带入另一法人依据', async () => {
  const p = editor(detail())
  try { await settle(); p.state.state.content.legalEntityId = ''; p.state.changeEntity(); assert.equal(p.state.state.content.targetBudgetReference, ''); assert.equal(p.state.state.content.sourceBudgetReference, ''); assert.deepEqual(p.state.state.content.amount, money('', '')) }
  finally { p.close() }
})

for (const [code, message] of [['BUDGET_ADJUSTMENT_INSUFFICIENT', /调出金额超过未占用且未使用的余额/], ['BUDGET_PERIOD_CLOSED', /预算期间已关闭/]]) test(`${code} 时关闭确认并禁止自动重发`, async () => {
  const p = submission(); let sent = 0
  api.submitBudgetAdjustment = async () => { sent++; throw { status: 409, code } }
  try {
    await settle(); p.state.appointment = 'appointment'; await settle(); p.state.prepare(); await p.state.submit(); await p.state.submit()
    assert.equal(sent, 1); assert.match(p.state.error, message); assert.equal(p.state.requiresRefresh, true); assert.equal(p.state.confirm, false)
  } finally { p.close() }
})

test('预算列表换身份清空旧内容，只使用本业务分页读取', async () => {
  const requests = []; api.budgetAdjustments = (_filter, signal) => new Promise(resolve => requests.push({ resolve, signal }))
  const p = panel(Workspace, { scopeKey: 'alice-list', refreshVersion: 0, locked: false })
  try {
    requests[0].resolve({ items: [{ ...detail(), title: 'alice-private' }], nextBeforeId: null }); await settle(); assert.equal(p.state.query.items.length, 1)
    p.props.scopeKey = 'bob-list'; assert.equal(p.state.query.items.length, 0)
    requests[1].resolve({ items: [], nextBeforeId: null }); await settle(); assert.equal(p.state.query.items.length, 0)
  } finally { p.close() }
})

test('实际预算模板展示原额度和拟调整值，金额保留精度且预算名称按文本转义', async () => {
  const frozen = view().preview; frozen.positions[0].name = '<script>alert(1)</script>'; frozen.positions[0].beforeLimit = money('999999999999999.99')
  const html = await renderToString(createSSRApp(Terms, { content: content(), financial: frozen }))
  assert.ok(html.includes('999,999,999,999,999.99')); assert.ok(html.includes('BUDGET-A')); assert.ok(html.includes('BUDGET-B'))
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;')); assert.ok(!html.includes('<script>alert'))
  for (const label of ['原预算额度', '拟调整后额度', '已占用', '已使用', '查询时可用', '尚未实际生效']) assert.ok(html.includes(label), label)
})

test('三种方向只提交对应编号，调拨不能同源同目标，日期和本位币必须有效', () => {
  for (const type of ['INCREASE', 'DECREASE', 'TRANSFER']) {
    const result = budgetAdjustmentContent({ ...content(), type }, catalog())
    assert.equal(result.sourceBudgetReference, type === 'INCREASE' ? null : 'BUDGET-A')
    assert.equal(result.targetBudgetReference, type === 'DECREASE' ? null : 'BUDGET-B')
  }
  for (const mutation of [
    { type: 'UNKNOWN' }, { type: '__proto__' }, { type: 'constructor' }, { accountingDate: '2026-02-30' }, { accountingDate: '2026-13-01' }, { accountingDate: '' },
    { sourceBudgetReference: 'BUDGET-B' }, { targetBudgetReference: '' }, { sourceBudgetReference: '' },
    { amount: money('100', 'USD') }, { amount: money('0') }, { amount: money('-1') }, { amount: money('0.001') }
  ]) assert.throws(() => budgetAdjustmentContent({ ...content(), ...mutation }, catalog()), JSON.stringify(mutation))
})

test('切换调整类型清空隐藏编号，重新调拨需补充缺失的一端', async () => {
  const p = editor(detail())
  try {
    await settle(); p.state.state.content.type = 'INCREASE'; p.state.changeType()
    assert.equal(p.state.state.content.sourceBudgetReference, null); assert.equal(p.state.state.content.targetBudgetReference, 'BUDGET-B')
    p.state.state.content.type = 'TRANSFER'; p.state.changeType(); assert.equal(p.state.state.content.sourceBudgetReference, '')
    p.state.state.content.sourceBudgetReference = 'BUDGET-A'; p.state.state.content.type = 'DECREASE'; p.state.changeType()
    assert.equal(p.state.state.content.targetBudgetReference, null); assert.equal(p.state.state.content.sourceBudgetReference, 'BUDGET-A')
  } finally { p.close() }
})

test('调整类型或日期变化时旧预检不可提交，缺省空编号与服务端省略字段等价', () => {
  for (const field of ['type', 'accountingDate']) {
    const checked = view(); checked.preview.content[field] = field === 'type' ? 'DECREASE' : '2026-09-30'
    assert.equal(usableBudgetAdjustmentCheck(checked, detail(), 'appointment'), false)
  }
  const draft = detail(), checked = view(); draft.content.type = checked.preview.content.type = 'INCREASE'
  draft.content.sourceBudgetReference = null; delete checked.preview.content.sourceBudgetReference
  assert.equal(usableBudgetAdjustmentCheck(checked, draft, 'appointment'), true)
})
