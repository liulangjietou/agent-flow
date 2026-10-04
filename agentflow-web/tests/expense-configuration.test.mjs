import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive, nextTick } from 'vue'
import { renderToString } from '@vue/server-renderer'
const { api, writeRequests, bindAuthenticationActor } = await import(process.env.AGENTFLOW_TEST_API)
const model = await import(process.env.AGENTFLOW_TEST_EXPENSE_CONFIGURATION)
const drafts = await import(process.env.AGENTFLOW_TEST_EXPENSE_CONFIGURATION_DRAFTS)
const { ConfigurationRead } = await import(process.env.AGENTFLOW_TEST_EXPENSE_CONFIGURATION_READ)
const { default: Manager } = await import(process.env.AGENTFLOW_TEST_EXPENSECONFIGURATIONMANAGERPANEL)
const { default: History } = await import(process.env.AGENTFLOW_TEST_EXPENSECONFIGURATIONHISTORYPANEL)
const { default: HistoryRendered } = await import(process.env.AGENTFLOW_TEST_EXPENSECONFIGURATIONHISTORYRENDERED)
const { default: Rules } = await import(process.env.AGENTFLOW_TEST_EXPENSEPOLICYRULESPANEL)
const { default: Summary } = await import(process.env.AGENTFLOW_TEST_EXPENSEPOLICYSUMMARYRENDERED)
const { workspaceMenu } = await import(process.env.AGENTFLOW_TEST_WORKSPACE_NAVIGATION)
const originalApi = { ...api }, originalFetch = globalThis.fetch, clone = value => JSON.parse(JSON.stringify(value))
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const actor = { tenantId: 'configuration-tests', userId: 'config-admin', roles: ['FINANCE_CONFIG_ADMIN'] }
const at = '2026-10-03T10:00:00Z', settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
globalThis.localStorage = { getItem: () => null }
afterEach(() => { Object.assign(api, originalApi); globalThis.fetch = originalFetch; bindAuthenticationActor(null) })
function categories(version = 1) { return { tenantId: actor.tenantId, version, categories: version ? [{ code: 'OFFICE', name: '办公费', units: ['ITEM'], active: true }] : [] } }
function definition() { return { name: '本地验收制度', rules: [{ key: 'office', name: '办公标准', match: { legalEntityIds: [id(2)], categoryCodes: ['OFFICE'], cityTiers: ['T2', 'T1'], employeeGrades: ['G2'], fromDate: '2026-01-01', throughDate: null, currency: 'CNY' }, constraints: { effect: 'ALLOW', unitPriceLimit: { value: '20.00', currency: 'CNY' }, limitUnit: 'ITEM', invoiceMaxAgeDays: 30, invoiceAgeAction: 'REQUIRE_REASON', allowedServiceLevels: [], priorRequestRequired: true } }] } }
function draft(revision = 1) { return { id: id(1), tenantId: actor.tenantId, key: 'expense-standard', revision, definition: definition(), publishedVersion: 0, publishedDraftRevision: 0 } }
function published(version = 1) { return { policyId: id(1), tenantId: actor.tenantId, key: 'expense-standard', version, draftRevision: version, categoryRevision: 1, definition: definition(), publishedBy: actor.userId, publishedAt: at, comment: '确认发布' } }
function current(publishedNow = false) { return { categories: categories(), activeRevision: publishedNow ? 1 : 0, activePolicy: publishedNow ? published() : null } }

test('定额补贴编辑保留日额与天数口径，排除混合约束且历史摘要可读', async () => {
  const data = definition(), row = data.rules[0], panel = await mount(Rules, { definition: data, disabled: false })
  try {
    panel.state.allowance(row, { target: { checked: true } })
    assert.equal(row.constraints.unitPriceLimit, null); assert.equal(row.constraints.invoiceMaxAgeDays, null)
    row.constraints.fixedAllowance.dailyRate.value = '100.00'
    const parsed = model.readPolicyDefinition(data)
    assert.equal(parsed.rules[0].constraints.fixedAllowance.dailyRate.value, '100.00')
    const normalized = clone(data); normalized.rules[0].constraints.fixedAllowance.dailyRate.value = '100'
    assert.equal(model.policyFingerprint(data), model.policyFingerprint(normalized))
    for (const mutate of [rule => { rule.constraints.fixedAllowance.dailyRate.value = '0' }, rule => { rule.constraints.fixedAllowance.dayCountBasis = 'WORK_DAYS' },
      rule => { rule.constraints.fixedAllowance.dailyRate.currency = 'USD' }, rule => { rule.match.categoryCodes = [] }, rule => { rule.constraints.allowedServiceLevels = ['BUSINESS'] }]) {
      const bad = clone(data); mutate(bad.rules[0]); assert.throws(() => model.readPolicyDefinition(bad))
    }
    const html = await renderToString(createSSRApp(Summary, { definition: data }))
    assert.match(html, /100.00 \/ 天/); assert.match(html, /自然日含起止日/)
    panel.state.effect(row, { target: { value: 'DENY' } }); assert.equal(row.constraints.fixedAllowance, null)
    assert.equal(model.readPolicyDefinition(data).rules[0].constraints.effect, 'DENY')
  } finally { panel.close() }
})
function summary(value = draft()) { return { id: value.id, key: value.key, name: value.definition.name, revision: value.revision, publishedVersion: value.publishedVersion, publishedDraftRevision: value.publishedDraftRevision, updatedBy: actor.userId, updatedAt: at } }
function stubReads() {
  api.expenseConfiguration = async () => current()
  api.expensePolicies = async () => ({ items: [summary()], nextAfterKey: null })
  api.expensePolicyDraft = async () => draft()
}
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
async function mount(component = Manager, extra = {}) {
  const props = reactive({ scopeKey: 'config/' + Math.random(), refreshVersion: 0, locked: false, ...extra })
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, context), render: () => null }, props)
  const vm = app.mount({}); await settle()
  return { props, state: vm.$.setupState, close: () => app.unmount() }
}

test('当前配置精确绑定租户、制度身份及版本；未配置不是畸形响应的默认值', () => {
  assert.equal(model.readExpenseConfiguration(current(), actor).activePolicy, null)
  assert.equal(model.readExpenseConfiguration(current(true), actor).activePolicy.policyId, id(1))
  for (const value of [{}, { ...current(), activePolicy: undefined }, { ...current(), activeRevision: 1 },
    { ...current(true), activeRevision: 0 }, { ...current(true), categories: categories(0) },
    { ...current(true), activePolicy: { ...published(), tenantId: 'other' } }]) assert.throws(() => model.readExpenseConfiguration(value, actor))
  assert.throws(() => model.readExpenseCategories(categories(), { ...actor, tenantId: 'other' }))
  assert.throws(() => model.readPolicyDraft(draft(), actor, 'other'))
  assert.throws(() => model.readPublishedPolicy(published(), actor, 'expense-standard', 2))
})

test('定义保留所有维度和顺序，兼容省略空字段且金额按精确十进制比较', () => {
  const source = definition(), read = model.readPolicyDefinition(source)
  assert.deepEqual(read.rules[0].match.cityTiers, ['T1', 'T2']); assert.equal(source.rules[0].match.cityTiers[0], 'T2')
  const normalized = clone(source); normalized.rules[0].constraints.unitPriceLimit.value = '020.000'
  assert.equal(model.policyFingerprint(normalized), model.policyFingerprint(source))
  const withoutNull = clone(source); delete withoutNull.rules[0].match.throughDate
  assert.equal(model.readPolicyDefinition(withoutNull).rules[0].match.throughDate, null)
  const denied = clone(source); denied.rules[0].constraints.effect = 'DENY'; assert.throws(() => model.readPolicyDefinition(denied))
  const duplicate = clone(source); duplicate.rules.push({ ...clone(duplicate.rules[0]), key: 'other' }); assert.throws(() => model.readPolicyDefinition(duplicate))
  const invalidDate = clone(source); invalidDate.rules[0].match.fromDate = '2026-02-30'; assert.throws(() => model.readPolicyDefinition(invalidDate))
  const moneyAsNumber = clone(source); moneyAsNumber.rules[0].constraints.unitPriceLimit.value = 20; assert.throws(() => model.readPolicyDefinition(moneyAsNumber))
})

test('审计理由允许多行，真实成功回执不因合法理由被判为未知结果', () => {
  const value = published(); value.comment = '调整办公标准\n业务已核对'
  assert.equal(model.readPublishedPolicy(value, actor).comment, value.comment)
  assert.equal(model.readCategoryRevision({ catalog: categories(), updatedBy: actor.userId, updatedAt: at, comment: value.comment }, actor, 1).comment, value.comment)
})

test('实际接口返回的类别历史首页可读取，分页校验使用响应对象的版本', () => {
  const wire = { items: [{ version: 1, categoryCount: 1, updatedBy: 'admin', updatedAt: '2026-10-03T02:21:00.791Z', comment: '本地合成类别\n验证多行修改理由' }], nextBeforeVersion: null }
  assert.deepEqual(model.readCategoryHistory(wire), wire)
})

for (const operation of ['categories', 'draft', 'publish']) test(`${operation} 畸形成功保留原键，恢复校验原版本和内容`, async () => {
  bindAuthenticationActor({ ...actor, tenantId: actor.tenantId })
  const sent = []; let value = {}
  globalThis.fetch = async (url, options) => { sent.push({ url, body: options.body, key: options.headers.get('Idempotency-Key') }); return new Response(JSON.stringify(value)) }
  const input = operation === 'categories' ? { expectedVersion: 0, categories: categories().categories, comment: '新增类别' }
    : operation === 'draft' ? { expectedRevision: 0, definition: definition(), comment: '新增制度' }
    : { expectedDraftRevision: 1, expectedCategoryRevision: 1, expectedActiveRevision: 0, comment: '确认发布' }
  const invoke = body => operation === 'categories' ? api.saveExpenseCategories(body) : operation === 'draft' ? api.saveExpensePolicyDraft('expense-standard', body) : api.publishExpensePolicy('expense-standard', body)
  await assert.rejects(invoke(input), error => error.code === 'RESPONSE_UNREADABLE')
  assert.equal(writeRequests.pending().length, 1)
  await assert.rejects(invoke({ ...input, comment: '另一次编辑' }), error => error.code === 'PENDING_REQUEST_CHANGED')
  value = operation === 'categories' ? categories() : operation === 'draft' ? draft() : current(true)
  await writeRequests.recover(writeRequests.pending()[0].id)
  assert.equal(sent.length, 2); assert.equal(sent[0].key, sent[1].key); assert.equal(sent[0].body, sent[1].body); assert.equal(writeRequests.pending().length, 0)
})

test('配置写入超时后保留原请求，迟到成功不能提前清理恢复槽', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] }); bindAuthenticationActor(actor)
  let finish; const sent = []
  globalThis.fetch = (url, options) => { sent.push(options); return new Promise(resolve => { finish = resolve }) }
  const promise = api.saveExpenseCategories({ expectedVersion: 0, categories: categories().categories, comment: '新增' })
  const rejected = assert.rejects(promise, error => error.code === 'REQUEST_TIMEOUT')
  await Promise.resolve(); t.mock.timers.tick(12_001); await rejected
  assert.equal(sent[0].signal.aborted, true); assert.equal(writeRequests.pending().length, 1)
  finish(new Response(JSON.stringify(categories()))); await settle(); assert.equal(writeRequests.pending().length, 1)
  globalThis.fetch = async () => new Response(JSON.stringify(categories()))
  await writeRequests.recover(writeRequests.pending()[0].id); assert.equal(writeRequests.pending().length, 0)
})

test('实际读取无缓存、可取消，历史版本和游标精确绑定', async () => {
  bindAuthenticationActor(actor); const calls = [], signal = new AbortController().signal
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response(JSON.stringify(current())) }
  await api.expenseConfiguration(signal)
  assert.equal(calls[0].options.cache, 'no-store'); assert.equal(calls[0].options.signal, signal)
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response(JSON.stringify({ items: [], nextBeforeVersion: null })) }
  await api.expensePolicyVersions('expense-standard', 9, signal)
  const url = new URL(calls.at(-1).url, 'http://localhost'); assert.equal(url.searchParams.get('beforeVersion'), '9'); assert.equal(url.searchParams.get('limit'), '25')
  assert.throws(() => model.readCategoryHistory({ items: [], nextBeforeVersion: 3 }))
  assert.throws(() => model.readPolicyDirectory({ items: [summary(), summary()], nextAfterKey: null }))
  globalThis.fetch = async () => new Response(JSON.stringify(published()))
  await assert.rejects(api.expensePolicyVersion('expense-standard', 2, signal), error => error.code === 'RESPONSE_UNREADABLE')
})

test('新建草稿切换页面后保留输入标识，按原请求确认后才清理', async () => {
  stubReads(); const panel = await mount(); const scope = panel.props.scopeKey
  panel.state.newPolicy(); panel.state.policy.key = 'new-policy'; panel.state.policy.definition = definition(); panel.state.policy.comment = '新建草稿'
  await settle(); panel.close()
  const next = await mount(Manager, { scopeKey: scope }); next.state.newPolicy()
  assert.equal(next.state.policy.key, 'new-policy')
  drafts.configurationDrafts.acknowledge(scope, '/admin/expense-policies/new-policy/draft', JSON.stringify(drafts.policyInput(next.state.policy)))
  assert.equal(drafts.configurationDrafts.policy(scope, ''), null); next.close()
})

test('新建保存结果恢复后重新读取已保存草稿，不继续停留在零修订创建表单', async () => {
  stubReads(); const panel = await mount(), scope = panel.props.scopeKey
  panel.state.newPolicy(); panel.state.policy.key = 'expense-standard'; panel.state.policy.definition = definition(); panel.state.policy.comment = '新建草稿'
  const body = JSON.stringify(drafts.policyInput(panel.state.policy))
  drafts.configurationDrafts.acknowledge(scope, '/admin/expense-policies/expense-standard/draft', body)
  panel.props.refreshVersion++; await settle(); await settle()
  assert.equal(panel.state.policy.baseline?.revision, 1)
  assert.equal(panel.state.policy.comment, ''); panel.close()
})

test('类别刷新保留本地旧基线并阻止覆盖；显式放弃后采用最新版本', async () => {
  stubReads(); const panel = await mount()
  panel.state.categories.categories[0].name = '本地修改'; panel.state.categories.comment = '本地理由'
  api.expenseConfiguration = async () => ({ ...current(), categories: categories(2) })
  await panel.state.load()
  assert.equal(panel.state.categories.baseline.version, 1); assert.equal(panel.state.categories.categories[0].name, '本地修改'); assert.equal(panel.state.categoryStale, true)
  api.saveExpenseCategories = async () => assert.fail('旧基线不可保存')
  await panel.state.saveCategories()
  panel.state.discard = 'categories'; panel.state.discardChanges()
  assert.equal(panel.state.categories.baseline.version, 2); assert.equal(panel.state.categories.categories[0].name, '办公费'); panel.close()
})

test('全局原请求恢复的刷新不被尚未释放的编辑锁挡住', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectPolicy('expense-standard')
  panel.state.policy.definition.name = '恢复后的制度'; panel.state.policy.comment = '修改'
  panel.props.locked = true
  drafts.configurationDrafts.acknowledge(panel.props.scopeKey, '/admin/expense-policies/expense-standard/draft', JSON.stringify(drafts.policyInput(panel.state.policy)))
  api.expensePolicyDraft = async () => ({ ...draft(2), definition: { ...definition(), name: '恢复后的制度' } })
  api.saveExpensePolicyDraft = async () => assert.fail('恢复刷新只读')
  panel.props.refreshVersion++; await settle(); await settle()
  panel.props.locked = false; await settle()
  assert.equal(panel.state.policy.baseline.revision, 2); assert.equal(panel.state.policy.definition.name, '恢复后的制度'); assert.equal(panel.state.policy.comment, ''); panel.close()
})

test('发布先读三版本，未确认不发送；确认后仅发送保存的三版本和理由', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectPolicy('expense-standard')
  let request; api.publishExpensePolicy = async (key, input) => { request = { key, input }; return current(true) }
  await panel.state.preparePublication(); assert.equal(panel.state.publishReview.draft.revision, 1)
  await panel.state.publish(); assert.equal(request, undefined)
  panel.state.publishComment = '确认发布'; panel.state.publishConfirmed = true; await panel.state.publish()
  assert.deepEqual(request, { key: 'expense-standard', input: { expectedDraftRevision: 1, expectedCategoryRevision: 1, expectedActiveRevision: 0, comment: '确认发布' } })
  assert.equal(panel.state.publishReview, null); panel.close()
})

test('未保存编辑、被别人修改的草稿及已发布修订均不能进入发布确认', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectPolicy('expense-standard')
  panel.state.policy.definition.name = '未保存'; await panel.state.preparePublication(); assert.equal(panel.state.publishReview, null)
  panel.state.discard = 'policy'; panel.state.discardChanges()
  api.expensePolicyDraft = async () => draft(2); await panel.state.preparePublication(); assert.equal(panel.state.publishReview, null); assert.equal(panel.state.policyStale, true)
  panel.state.discard = 'policy'; panel.state.discardChanges()
  api.expensePolicyDraft = async () => ({ ...draft(2), publishedVersion: 1, publishedDraftRevision: 2 })
  await panel.state.preparePublication(); assert.equal(panel.state.publishReview, null); assert.match(panel.state.error, /已发布/); panel.close()
})

test('发布时类别目录变化使用刚读取的版本，并拒绝停用类别', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectPolicy('expense-standard')
  api.expenseConfiguration = async () => ({ ...current(), categories: categories(4) })
  await panel.state.preparePublication(); assert.equal(panel.state.publishReview.current.categories.version, 4)
  panel.state.cancelPublication(); api.expenseConfiguration = async () => { const value = current(); value.categories.categories[0].active = false; return value }
  await panel.state.preparePublication(); assert.equal(panel.state.publishReview, null); assert.match(panel.state.error, /启用类别/); panel.close()
})

test('切换账号立即清除规则和表单，迟到的前一账号读取不能恢复正文', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectPolicy('expense-standard')
  let finish
  api.expenseConfiguration = () => new Promise(resolve => { finish = resolve })
  const pending = panel.state.load(); await settle()
  panel.props.scopeKey = ''; await settle(); assert.equal(panel.state.policy, null); assert.equal(panel.state.categories, null); assert.deepEqual(panel.state.directory, [])
  finish(current()); await pending; assert.equal(panel.state.current, null); panel.close()
})

test('403 清除已显示规则和目录，禁止在失权页面继续写入', async () => {
  stubReads(); const panel = await mount(); await panel.state.selectPolicy('expense-standard')
  api.expenseConfiguration = async () => { throw { status: 403 } }; await panel.state.load()
  assert.equal(panel.state.denied, true); assert.equal(panel.state.policy, null); assert.equal(panel.state.categories, null)
  api.publishExpensePolicy = async () => assert.fail('失权不能提交'); await panel.state.publish(); panel.close()
})

test('读取超时解除等待，迟到数据无权填回当前值', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] }); const query = new ConfigurationRead(); let finish
  const pending = query.load(() => new Promise(resolve => { finish = resolve }))
  t.mock.timers.tick(12_001); assert.equal(await pending, null); assert.equal(query.loading, false); assert.match(query.error, /超时/)
  finish(current()); await settle(); assert.equal(query.value, null)
})

test('历史分页失败保留原游标；查看不可变版本没有写入或自动发布', async () => {
  const calls = [], item = n => ({ version: n, categoryCount: 1, updatedBy: actor.userId, updatedAt: at, comment: '理由' })
  api.expenseCategoryVersions = async before => { calls.push(before); if (calls.length === 2) throw { status: 0, message: '连接失败' }; return { items: before ? [item(1)] : [item(2)], nextBeforeVersion: before ? null : 2 } }
  api.expenseCategoryVersion = async version => ({ catalog: categories(version), updatedBy: actor.userId, updatedAt: at, comment: '历史类别' })
  const panel = await mount(History, { mode: 'categories' }); await panel.state.load(true)
  assert.equal(panel.state.nextVersion, 2); assert.equal(panel.state.entries.length, 1)
  await panel.state.load(true); assert.deepEqual(calls, [undefined, 2, 2]); assert.equal(panel.state.entries.length, 2)
  await panel.state.open(panel.state.entries[1]); assert.equal(panel.state.detail.value.categories.version, 1); panel.close()
})

test('规则顺序、明确禁用和单位选择遵循编辑锁，金额不会转成浮点数', async () => {
  const data = definition(); data.rules.push({ ...clone(data.rules[0]), key: 'second', name: '第二规则', match: { ...data.rules[0].match, categoryCodes: ['OTHER'] } })
  const panel = await mount(Rules, { definition: data, disabled: false })
  panel.state.move(1, -1); assert.equal(data.rules[0].key, 'second')
  panel.state.effect(data.rules[0], { target: { value: 'DENY' } }); assert.equal(data.rules[0].constraints.unitPriceLimit, null); assert.equal(data.rules[0].constraints.priorRequestRequired, false)
  panel.props.disabled = true; panel.state.remove(0); assert.equal(data.rules.length, 2); panel.close()
})

test('只读历史正文转义输入，管理导航使用独立的财务配置权限', async () => {
  const data = definition(); data.name = '<img src=x onerror=alert(1)>'; data.rules[0].name = '<script>alert(1)</script>'
  const html = await renderToString(createSSRApp(Summary, { definition: data }))
  assert.ok(html.includes('&lt;img')); assert.ok(html.includes('&lt;script')); assert.ok(!html.includes('<script>'))
  const item = workspaceMenu.flatMap(group => group.items).find(item => item.page === 'expense-configuration')
  assert.equal(item.access, 'finance-config')
})

test('真实类别编辑器不预设容差，并精确保留输入百分比与原恢复正文', async () => {
  stubReads(); const panel = await mount()
  try {
    panel.state.changePriorMode(0, { target: { value: 'TOLERANCE' } })
    const row = panel.state.categories.categories[0]; assert.equal(row.priorControl.toleranceFraction, null)
    panel.state.changeTolerance(0, { target: { value: '12.3456' } }); assert.equal(row.priorControl.toleranceFraction, 0.123456)
    assert.equal(drafts.categoryInput(panel.state.categories).categories[0].priorControl.toleranceFraction, 0.123456)
    panel.state.changeTolerance(0, { target: { value: '12.345678' } }); panel.state.categories.comment = '合成比例'
    await panel.state.saveCategories(); assert.match(panel.state.error, /最多四位小数/)
    panel.state.changePriorMode(0, { target: { value: 'NONE' } }); assert.deepEqual(row.priorControl, { mode: 'NONE' })
    panel.state.changePriorMode(0, { target: { value: 'LEGACY' } }); assert.equal(Object.hasOwn(row, 'priorControl'), false)
  } finally { panel.close() }
})


test('历史类别正文展示原修订的控制模式及容差，缺失控制保持历史语义', async () => {
  api.expenseCategoryVersions = async () => ({ items: [], nextBeforeVersion: null })
  api.expenseCategoryVersion = async () => ({ catalog: { ...categories(2), categories: [
    ...categories().categories, { code: 'TRAVEL', name: '差旅', units: ['DAY'], active: true, priorControl: { mode: 'TOLERANCE', toleranceFraction: 0.123456 } },
    { code: 'OTHER', name: '其他', units: ['ITEM'], active: true, priorControl: { mode: 'NONE' } }
  ] }, updatedBy: actor.userId, updatedAt: at, comment: '保存原控制' })
  const panel = await mount(History, { mode: 'categories' })
  try {
    await panel.state.open({ version: 2 })
    const html = await renderToString(createSSRApp({ ...HistoryRendered, setup: () => panel.state }, panel.props))
    assert.match(html, /历史硬上限/); assert.match(html, /容差 12.3456%/); assert.match(html, /事前额度：不限制额度/)
  } finally { panel.close() }
})
