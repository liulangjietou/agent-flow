import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, reactive } from 'vue'
import { renderToString } from '@vue/server-renderer'
const { policyGuidanceContext, policyGuidanceQuery, readPolicyGuidance, policyGuidanceMessages, applyAllowancePreview } = await import(process.env.AGENTFLOW_TEST_POLICY_GUIDANCE)
const { default: LineEditor } = await import(process.env.AGENTFLOW_TEST_EXPENSELINEEDITORRENDERED)
const { default: LineEditorPanel } = await import(process.env.AGENTFLOW_TEST_EXPENSELINEEDITORPANEL)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEPOLICYGUIDANCEPANEL)
const { default: Rendered } = await import(process.env.AGENTFLOW_TEST_EXPENSEPOLICYGUIDANCERENDERED)
const { api, bindAuthenticationActor, writeRequests } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { ...api }, copy = value => JSON.parse(JSON.stringify(value)), settle = () => new Promise(resolve => setImmediate(resolve))
const ENTITY = '00000000-0000-0000-0000-000000000001', POLICY = '00000000-0000-0000-0000-000000000002'
const context = () => ({ legalEntityId: ENTITY, reportType: 'TRAVEL', categoryCode: 'TRAVEL', cityCode: 'SH', incurredOn: '2026-10-02', currency: 'CNY', unit: 'NIGHT' })
const line = () => ({ lineNo: 1, categoryCode: 'TRAVEL', cityCode: 'SH', incurredOn: '2026-10-02', endedOn: null, quantity: '2', unit: 'NIGHT', claimedGross: { value: '400.00', currency: 'CNY' }, claimedTax: { value: '0.00', currency: 'CNY' }, invoiceIds: [], priorRequest: null, allocations: [], description: '', exceptionReason: null })
const catalog = () => ({ employeeId: 'alice', sourceVersion: 'v1', validUntil: new Date(Date.now() + 120000).toISOString(), legalEntities: [{ id: ENTITY }], categories: [{ code: 'TRAVEL', units: ['NIGHT'] }], cities: [{ code: 'SH' }], projects: [], costCenters: [] })
const result = (input = context()) => ({ context: copy(input), guidance: { policyId: POLICY, policyVersion: 1, policyName: '差旅标准', ruleKey: 'hotel', ruleName: '住宿规则', constraints: { effect: 'ALLOW', unitPriceLimit: { value: '200.00', currency: 'CNY' }, limitUnit: 'NIGHT', invoiceMaxAgeDays: 30, invoiceAgeAction: 'REQUIRE_REASON', allowedServiceLevels: ['STANDARD'], priorRequestRequired: true }, factSourceReference: 'verified-grade-city', validUntil: new Date(Date.now() + 60000).toISOString(), selection: { policyId: POLICY, policyVersion: 1, categoryRevision: 2, activeRevision: 3, definitionDigest: 'a'.repeat(64) } } })
function remove(node) { const list = node.parent?.children; if (list) { const index = list.indexOf(node); if (index >= 0) list.splice(index, 1) } }
const renderer = createRenderer({ createElement: tag => ({ tag, children: [], props: {} }), createText: text => ({ text }), createComment: () => ({}),
  setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
  insert(node, parent, anchor = null) { remove(node); parent.children ??= []; const index = parent.children.indexOf(anchor); if (index < 0) parent.children.push(node); else parent.children.splice(index, 0, node); node.parent = parent },
  remove, parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  patchProp: (node, key, previous, value) => { node.props[key] = value } })
function panel(rendered = false) {
  const props = reactive({ context: context(), line: line(), scopeKey: 'demo:alice', disabled: false }), root = { children: [] }, Component = rendered ? Rendered : Panel
  const app = renderer.createApp({ ...Component, setup: (_, ctx) => Component.setup(props, ctx), ...(rendered ? {} : { render: () => null }) }, props)
  const mounted = app.mount(root)
  return { props, root, state: mounted.$.setupState, close() { app.unmount(); Object.assign(api, originals) } }
}
function visible(node) { return [node.text ?? '', ...(node.children ?? []).map(visible)].join(' ') }

function allowanceResult() {
  const input = { ...context(), unit: 'DAY', incurredOn: '2024-02-28', endedOn: '2024-03-01' }, view = result(input)
  view.guidance.constraints = { effect: 'ALLOW', allowedServiceLevels: [], priorRequestRequired: false,
    fixedAllowance: { dailyRate: { value: '100.00', currency: 'CNY' }, dayCountBasis: 'CALENDAR_DAYS_INCLUSIVE' } }
  view.allowance = { policy: { selection: copy(view.guidance.selection), ruleKey: view.guidance.ruleKey, factSourceReference: view.guidance.factSourceReference },
    calculation: { startsOn: input.incurredOn, endsOn: input.endedOn, days: 3, rule: copy(view.guidance.constraints.fixedAllowance), gross: { value: '300.00', currency: 'CNY' } } }
  return view
}
function allowanceLine() {
  return { ...line(), unit: 'DAY', incurredOn: '2024-02-28', endedOn: '2024-03-01', allocations: [{ costCenter: 'IT', projectCode: null, amount: { value: '400.00', currency: 'CNY' } }] }
}

test('补贴提示绑定结束日期，验证含闰日的金额等式与同一制度来源', () => {
  const view = allowanceResult(), before = copy(view), parsed = readPolicyGuidance(view, view.context)
  assert.equal(new URLSearchParams(policyGuidanceQuery(view.context)).get('endedOn'), '2024-03-01')
  assert.equal(parsed.allowance.calculation.days, 3); assert.equal(parsed.allowance.calculation.gross.value, '300.00')
  parsed.allowance.calculation.rule.dailyRate.value = '900.00'; assert.deepEqual(view, before)
  for (const change of [v => { v.allowance.calculation.days = 4 }, v => { v.allowance.calculation.gross.value = '301.00' },
    v => { v.allowance.calculation.rule.dailyRate.value = '101.00' }, v => { v.allowance.calculation.endsOn = '2024-03-02' },
    v => { v.allowance.policy.selection.categoryRevision++ }, v => { v.allowance.policy.factSourceReference = 'wrong-source' },
    v => { v.guidance.selection = null }, v => { delete v.allowance }, v => { v.context.endedOn = '2024-03-02' }]) {
    const invalid = allowanceResult(); change(invalid); assert.throws(() => readPolicyGuidance(invalid, view.context))
  }
})

test('补贴未填结束日期只显示制度，普通按天费用不产生自动金额', () => {
  const pending = allowanceResult(); delete pending.context.endedOn; delete pending.allowance
  assert.equal(readPolicyGuidance(pending, pending.context).allowance, undefined)
  const ordinary = result({ ...context(), unit: 'DAY' }); ordinary.guidance.constraints.limitUnit = 'DAY'
  assert.equal(readPolicyGuidance(ordinary, ordinary.context).allowance, undefined)
  assert.match(policyGuidanceMessages(pending.guidance, { ...allowanceLine(), endedOn: null }).find(m => m.code === 'ALLOWANCE_DATES').text, /结束日期/)
})

test('采纳补贴计算保持精确金额，唯一分摊同步，多笔分摊及发票必须人工核对', () => {
  const view = readPolicyGuidance(allowanceResult(), allowanceResult().context), input = allowanceLine(), before = copy(input)
  const filled = applyAllowancePreview(input, view)
  assert.equal(filled.quantity, '3'); assert.equal(filled.claimedGross.value, '300.00'); assert.equal(filled.claimedTax.value, '0.00')
  assert.equal(filled.allocations[0].amount.value, '300.00'); assert.deepEqual(input, before)
  const split = { ...input, invoiceIds: [POLICY], allocations: [input.allocations[0], { costCenter: 'SALES', projectCode: null, amount: { value: '10.00', currency: 'CNY' } }] }
  const adjusted = applyAllowancePreview(split, view)
  assert.deepEqual(adjusted.allocations, split.allocations); assert.deepEqual(adjusted.invoiceIds, [POLICY])
  assert.throws(() => applyAllowancePreview({ ...input, endedOn: '2024-03-02' }, view), /费用条件已变化/)
})

test('费用编辑器显示补贴计算依据，并将金额天数和税额呈现为只读', async () => {
  const input = applyAllowancePreview(allowanceLine(), allowanceResult())
  const html = await renderToString(createSSRApp(LineEditor, { modelValue: input, catalog: catalog(), legalEntityId: ENTITY, reportType: 'TRAVEL', scopeKey: 'demo:alice', locked: false }))
  assert.match(html, /补贴天数（自动）/); assert.match(html, /补贴金额（自动）/); assert.match(html, /100.00 \/ 天 × 3 天 = 300.00/)
  assert.equal((html.match(/readonly/g) ?? []).length, 3)
  const ordinary = await renderToString(createSSRApp(LineEditor, { modelValue: line(), catalog: catalog(), legalEntityId: ENTITY, reportType: 'TRAVEL', scopeKey: 'demo:alice', locked: false }))
  assert.equal((ordinary.match(/readonly/g) ?? []).length, 0)
})

test('修改行程清除旧计算，迟到预览不能带回旧金额，锁定期间也不能修改', async () => {
  const props = reactive({ modelValue: allowanceLine(), catalog: catalog(), legalEntityId: ENTITY, reportType: 'TRAVEL', scopeKey: 'demo:alice', locked: false,
    'onUpdate:modelValue': value => { props.modelValue = value } })
  const app = renderer.createApp({ ...LineEditorPanel, setup: (_, ctx) => LineEditorPanel.setup(props, ctx), render: () => null }, props)
  // 查询维度必须来自本人类别目录。
  props.catalog.categories[0].units = ['DAY']
  const mounted = app.mount({ children: [] }), state = mounted.$.setupState
  try {
    const original = allowanceResult(); state.acceptGuidance(original)
    assert.equal(props.modelValue.claimedGross.value, '300.00'); assert.equal(state.isAllowance, true)
    props.modelValue.endedOn = '2024-03-02'; state.clearAllowance()
    assert.equal(props.modelValue.allowance, null); assert.equal(props.modelValue.claimedGross.value, '')
    state.acceptGuidance(original); assert.equal(props.modelValue.claimedGross.value, '')
    const next = allowanceResult(); next.context.endedOn = '2024-03-02'; next.allowance.calculation.endsOn = '2024-03-02'; next.allowance.calculation.days = 4; next.allowance.calculation.gross.value = '400.00'
    state.acceptGuidance(next); assert.equal(props.modelValue.claimedGross.value, '400.00')
    props.locked = true; state.clearAllowance(true); assert.equal(props.modelValue.allowance.calculation.days, 4)
  } finally { app.unmount() }
})

test('提示输入覆盖所有费用类型，只从本人目录选维度，不携带金额、职级或员工覆盖', () => {
  for (const type of ['TRAVEL', 'DAILY', 'ENTERTAINMENT', 'TRAINING', 'OTHER']) {
    const input = policyGuidanceContext(ENTITY, type, line(), catalog()); assert.equal(input.reportType, type)
    assert.deepEqual([...new URLSearchParams(policyGuidanceQuery(input)).keys()].sort(), Object.keys(context()).sort())
  }
  const changed = line(); changed.quantity = ''; changed.claimedGross.value = ''
  assert.deepEqual(policyGuidanceContext(ENTITY, 'TRAVEL', changed, catalog()), context())
  for (const mutate of [value => { value.categoryCode = 'SECRET' }, value => { value.cityCode = 'SECRET' }, value => { value.incurredOn = '2026-02-30' }, value => { value.claimedGross.currency = 'cny' }, value => { value.unit = 'DAY' }]) {
    const value = line(); mutate(value); assert.equal(policyGuidanceContext(ENTITY, 'TRAVEL', value, catalog()), null)
  }
})

test('响应必须匹配输入、币种、规则与有效的发布选择，失败不变成不限额', () => {
  const original = result(), before = copy(original), read = readPolicyGuidance(original, context())
  read.guidance.constraints.allowedServiceLevels.push('OTHER'); assert.deepEqual(original, before)
  for (const mutate of [value => { value.context.cityCode = 'OTHER' }, value => { value.guidance.policyVersion = 2 }, value => { value.guidance.selection.categoryRevision = 0 }, value => { value.guidance.constraints.unitPriceLimit.currency = 'USD' }, value => { value.guidance.constraints.unitPriceLimit.value = '200.001' }, value => { value.guidance.validUntil = '2000-01-01' }, value => { value.guidance.factSourceReference = '' }]) {
    const value = result(); mutate(value); assert.throws(() => readPolicyGuidance(value, context()))
  }
  const external = result(); delete external.guidance.selection
  assert.equal(readPolicyGuidance(external, context()).guidance.selection, null)
})

test('即时金额比较使用整数精度，等于上限不超标且三位数量不产生舍入假通过', () => {
  const advice = result().guidance, input = line(), before = copy(input)
  assert.equal(policyGuidanceMessages(advice, input).some(item => item.code === 'AMOUNT'), false)
  input.claimedGross.value = '400.01'; assert.equal(policyGuidanceMessages(advice, input).some(item => item.code === 'AMOUNT'), true)
  advice.constraints.unitPriceLimit.value = '2.00'; input.quantity = '0.005'; input.claimedGross.value = '0.01'
  assert.equal(policyGuidanceMessages(advice, input).some(item => item.code === 'AMOUNT'), false)
  input.claimedGross.value = '0.02'; assert.equal(policyGuidanceMessages(advice, input).some(item => item.code === 'AMOUNT'), true)
  const huge = result().guidance; huge.constraints.unitPriceLimit.value = '999999999999999.98'
  const large = line(); large.quantity = 1; large.claimedGross.value = '999999999999999.99'
  assert.equal(policyGuidanceMessages(huge, large).some(item => item.code === 'AMOUNT'), true)
  const clean = copy(before); policyGuidanceMessages(result().guidance, clean); assert.deepEqual(clean, before)
})

test('未填完整或单位不同时不宣称标准内，禁止项与事前要求保持明确', () => {
  const input = line(), advice = result().guidance; input.quantity = ''
  assert.equal(policyGuidanceMessages(advice, input).some(item => item.code === 'AMOUNT_INPUT'), false)
  input.quantity = '2'; input.unit = 'DAY'; assert.equal(policyGuidanceMessages(advice, input).some(item => item.code === 'UNIT_MISMATCH'), true)
  assert.equal(policyGuidanceMessages(advice, input).find(item => item.code === 'PRIOR_REQUEST').warning, true)
  input.priorRequest = { requestId: POLICY, lineNo: 1 }
  assert.match(policyGuidanceMessages(advice, input).find(item => item.code === 'PRIOR_REQUEST').text, /仍需预检/)
  advice.constraints = { effect: 'DENY' }
  assert.deepEqual(policyGuidanceMessages(advice, input).map(item => item.code), ['DENIED'])
})

test('实际提示组件显示版本与来源，金额修改即时更新且不会创建额外查询或提交', async () => {
  let reads = 0, writes = 0
  api.expensePolicyGuidance = async input => { reads++; return result(input) }
  api.submitExpense = async () => { writes++ }
  const p = panel(true)
  try {
    await p.state.refresh(); await settle()
    assert.match(visible(p.root), /差旅标准 · v1/); assert.match(visible(p.root), /verified-grade-city/)
    p.props.line.claimedGross.value = '400.01'; await settle()
    assert.equal(p.state.messages.some(item => item.code === 'AMOUNT'), true)
    assert.match(visible(p.root), /金额超过单价上限/); assert.match(visible(p.root), /最终以预检及审批结果为准/)
    assert.equal(reads, 1); assert.equal(writes, 0)
  } finally { p.close() }
})

test('条件变化后旧读取立即失效，迟到结果不能覆盖新规则', async () => {
  const pending = []; api.expensePolicyGuidance = (input, signal) => new Promise(resolve => pending.push({ input, signal, resolve }))
  const p = panel()
  try {
    const old = p.state.refresh(); await settle()
    p.props.context = { ...context(), cityCode: 'HZ' }; await settle()
    assert.equal(pending[0].signal.aborted, true); assert.equal(p.state.advice, null)
    const current = p.state.refresh(); pending[1].resolve(result(pending[1].input)); await current
    pending[0].resolve(result(pending[0].input)); await old
    assert.equal(p.state.advice.policyVersion, 1); assert.equal(p.state.error, '')
    assert.equal(pending.length, 2)
  } finally { p.close() }
})

test('账号切换或锁定时清除提示，已经返回的旧身份响应不会重现', async () => {
  let resolve, signal; api.expensePolicyGuidance = (input, requestSignal) => { signal = requestSignal; return new Promise(done => { resolve = () => done(result(input)) }) }
  const p = panel()
  try {
    const loading = p.state.refresh(); p.props.scopeKey = 'demo:bob'; p.props.disabled = true; await settle()
    assert.equal(signal.aborted, true); resolve(); await loading
    assert.equal(p.state.advice, null); assert.equal(p.state.loading, false)
  } finally { p.close() }
})

test('换版失败时清除先前规则，不沿用上次标准内提示', async () => {
  api.expensePolicyGuidance = async input => result(input); const p = panel()
  try {
    await p.state.refresh(); assert.ok(p.state.advice)
    api.expensePolicyGuidance = async () => { throw { code: 'POLICY_CONFIGURATION_CHANGED' } }
    await p.state.refresh(); assert.equal(p.state.advice, null); assert.match(p.state.error, /费用制度已更新/)
  } finally { p.close() }
})

test('查询超时终止请求，忽略未遵守取消信号的迟到响应', async () => {
  const originalTimer = globalThis.setTimeout; let timeout, release, signal
  globalThis.setTimeout = (fn, ms, ...args) => { if (ms === 12000) { timeout = fn; return originalTimer(() => {}, 12000) } return originalTimer(fn, ms, ...args) }
  api.expensePolicyGuidance = (input, current) => { signal = current; return new Promise(resolve => { release = () => resolve(result(input)) }) }
  const p = panel()
  try {
    const loading = p.state.refresh(); timeout(); await loading; release(); await settle()
    assert.equal(signal.aborted, true); assert.equal(p.state.advice, null); assert.match(p.state.error, /超时/)
  } finally { p.close(); globalThis.setTimeout = originalTimer }
})

test('来源有效期到达后移除提示并要求刷新', async () => {
  const originalTimer = globalThis.setTimeout; let expire
  globalThis.setTimeout = (fn, ms, ...args) => { if (ms > 1000 && ms < 6000) { expire = fn; return originalTimer(() => {}, ms) } return originalTimer(fn, ms, ...args) }
  api.expensePolicyGuidance = async input => { const value = result(input); value.guidance.validUntil = new Date(Date.now() + 4000).toISOString(); return value }
  const p = panel()
  try { await p.state.refresh(); assert.ok(p.state.advice); expire(); assert.equal(p.state.advice, null); assert.match(p.state.error, /已过期/) }
  finally { p.close(); globalThis.setTimeout = originalTimer }
})

test('API 使用无缓存 GET，只传匹配维度且不创建幂等写入', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.localStorage
  globalThis.localStorage = { getItem: () => 'token' }; bindAuthenticationActor({ tenantId: 'demo', userId: 'alice' })
  const captured = []; globalThis.fetch = async (url, init) => { captured.push({ url, init }); return Response.json(result()) }
  try {
    const value = await api.expensePolicyGuidance(context(), new AbortController().signal)
    assert.equal(value.guidance.policyId, POLICY); assert.equal(captured.length, 1)
    const call = captured[0], url = new URL(call.url, 'https://local.invalid')
    assert.equal(url.pathname, '/api/v1/finance/expense-policy-guidance'); assert.equal(call.init.method ?? 'GET', 'GET'); assert.equal(call.init.cache, 'no-store')
    assert.equal(new Headers(call.init.headers).has('Idempotency-Key'), false); assert.deepEqual([...url.searchParams.keys()].sort(), Object.keys(context()).sort())
  } finally { globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage; bindAuthenticationActor(null) }
})
