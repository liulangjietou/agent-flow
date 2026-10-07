import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, h, nextTick, reactive } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { default: Line } = await import(process.env.AGENTFLOW_TEST_EXPENSELINEEDITORFEEDBACK)
const { default: Editor } = await import(process.env.AGENTFLOW_TEST_EXPENSEEDITORFEEDBACK)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { expenseDrafts } = await import(process.env.AGENTFLOW_TEST_EXPENSE_DRAFT)
const originals = { ...api }, originalDocument = globalThis.document
afterEach(() => { Object.assign(api, originals); globalThis.document = originalDocument })
const money = value => ({ value, currency: 'CNY' })
const line = (lineNo = 7) => ({ lineNo, categoryCode: 'OFFICE', cityCode: 'SH', incurredOn: '2026-10-07', endedOn: null,
  quantity: '1', unit: 'ITEM', claimedGross: money('100.00'), claimedTax: money('0.00'), invoiceIds: [], priorRequest: null,
  allocations: [{ costCenter: 'IT', projectCode: null, amount: money('79.99') }], description: '办公费', exceptionReason: null })
const catalog = () => ({ employeeId: 'alice', sourceVersion: 'v1', validUntil: new Date(Date.now() + 60000).toISOString(),
  legalEntities: [{ id: 'legal', name: '法人', baseCurrency: 'CNY', timeZone: 'Asia/Shanghai' }],
  categories: [{ code: 'OFFICE', name: '办公费', units: ['ITEM'] }], cities: [{ code: 'SH', name: '上海' }],
  costCenters: [{ legalEntityId: 'legal', code: 'IT', name: '研发' }], projects: [] })
const report = () => ({ id: 'report', applicationId: 'app', businessNo: 'EXP-1', applicationStatus: 'DRAFT',
  applicationVersion: 2, financialVersion: 4, roundNo: 1, editable: true, financialRound: null,
  content: { legalEntityId: 'legal', type: 'DAILY', title: '办公报销', lines: [line(3), line(7)], advanceOffsets: [] } })
const result = () => ({ job: { id: 'check', applicationVersion: 2, financialVersion: 4, status: 'BLOCKED', attempt: 1 },
  usable: false, unavailableCode: 'PRECHECK_NOT_READY', initiator: { appointmentId: 'appointment', legalEntityId: 'legal' },
  accountingDate: '2026-10-07', rateDate: null, validUntil: null, preview: null,
  findings: [{ stage: 'INPUT', lineNo: 7, nature: 'REJECTED', code: 'EXPENSE_EXCEPTION_REASON_REQUIRED' }] })
const nodes = element => [element, ...(element.children ?? []).flatMap(nodes)]
const text = element => [element.text ?? '', ...(element.children ?? []).map(text)].join(' ')
const tick = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const vnodes = node => node && typeof node === 'object' ? [node, ...vnodes(node.component?.subTree), ...(Array.isArray(node.children) ? node.children.flatMap(vnodes) : [])] : []
let sequence = 0
function mount(view = result(), detail = report()) {
  let focus = null, scroll = null, writes = 0
  globalThis.document = { activeElement: null }
  api.financeCatalog = async () => catalog()
  api.expensePrecheckOptions = async () => ({ applicationVersion: 2, financialVersion: 4, enabled: true, latestPrecheckId: 'check', targetDigest: 'a'.repeat(64) })
  api.expensePrecheck = async () => structuredClone(view)
  for (const key of ['queueExpensePrecheck', 'submitExpense', 'reviseExpense']) api[key] = async () => { writes++; throw new Error('Unexpected write') }
  const element = tag => ({ tag, tagName: tag.toUpperCase(), children: [], props: {}, listeners: {}, parent: null, text: '', value: '',
    addEventListener(kind, handler) { this.listeners[kind] = handler }, removeEventListener(kind) { delete this.listeners[kind] },
    get options() { return nodes(this).filter(node => node.tag === 'option') },
    focus() { focus = this; globalThis.document.activeElement = this }, scrollIntoView() { scroll = this } })
  const remove = node => { if (node.parent) { const index = node.parent.children.indexOf(node); if (index >= 0) node.parent.children.splice(index, 1); node.parent = null } }
  const renderer = createRenderer({ createElement: element, createText: value => ({ ...element('#text'), text: value }), createComment: () => element('#comment'),
    insert(node, parent, anchor) { remove(node); node.parent = parent; const index = parent.children.indexOf(anchor); parent.children.splice(index < 0 ? parent.children.length : index, 0, node) },
    remove, parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
    patchProp(node, key, old, value) { node.props[key] = value; if (key === 'value') node.value = value },
    setText: (node, value) => { node.text = value }, setElementText: (node, value) => { node.text = value; node.children = [] } })
  const props = reactive({ initial: detail, scopeKey: 'feedback-' + ++sequence, locked: false }), root = element('root')
  const app = renderer.createApp({ setup: () => () => h(Editor, props) }); app.mount(root)
  return { props, root, focus: () => focus, scroll: () => scroll, writes: () => writes,
    chooseAppointment() { vnodes(app._instance.subTree).find(node => node.type?.name === 'InitiatorAppointmentPicker').props['onUpdate:modelValue']('appointment') },
    close() { app.unmount(); expenseDrafts.clear(props.scopeKey, props.initial.id) } }
}

test('分摊提示显示实际差额，精确到大额的一分钱，未配平或未完成输入不伪报成功', async () => {
  async function render(value) { return renderToString(createSSRApp(Line, { modelValue: value, catalog: catalog(), legalEntityId: 'legal', reportType: 'DAILY', scopeKey: '', locked: false })) }
  const value = line(), before = structuredClone(value)
  assert.match(await render(value), /还差 CNY 20\.01/); assert.deepEqual(value, before)
  value.claimedGross.value = '999999999999999.99'; value.allocations[0].amount.value = '999999999999999.98'
  assert.match(await render(value), /还差 CNY 0\.01/)
  value.allocations.push({ costCenter: 'SALES', projectCode: null, amount: money('0.02') })
  assert.match(await render(value), /超出 CNY 0\.01/)
  value.allocations[1].amount.value = '0.01'; assert.match(await render(value), /分摊已配平/)
  value.allocations[1].amount.value = ''; const incomplete = await render(value)
  assert.match(incomplete, /填写完整金额/); assert.doesNotMatch(incomplete, /分摊已配平/)
  value.allocations[1].amount.value = '0.01'; value.allocations[1].amount.currency = 'USD'
  assert.match(await render(value), /币种/); assert.doesNotMatch(await render(value), /分摊已配平/)
})

test('本人发起的新预检阻断时定位第一条错误，读取历史或已经转移焦点时不抢焦点', async () => {
  for (const [code, target, moveAway, polled] of [['EXPENSE_EXCEPTION_REASON_REQUIRED', 'textarea', false, false], ['EXPENSE_POLICY_DENIED', 'button', false, false], ['EXPENSE_EXCEPTION_REASON_REQUIRED', 'input', true, false], ['EXPENSE_EXCEPTION_REASON_REQUIRED', 'textarea', false, true]]) {
    const page = mount(); let resolveQueue, queueCalls = 0, freshReads = 0
    try {
      await tick(); assert.equal(page.focus(), null)
      page.chooseAppointment()
      const date = nodes(page.root).find(node => node.tag === 'label' && text(node).trim().startsWith('会计日期')).children.find(node => node.tag === 'input')
      date.value = '2026-10-07'; date.listeners.input({ target: date }); await tick()
      api.queueExpensePrecheck = () => { queueCalls++; return new Promise(resolve => { resolveQueue = resolve }) }
      api.expensePrecheck = async () => ({ ...result(), job: { ...result().job, id: 'fresh', status: polled && ++freshReads === 1 ? 'RUNNING' : 'BLOCKED' }, findings: [{ ...result().findings[0], code }] })
      const queue = nodes(page.root).find(node => node.tag === 'button' && text(node) === '开始费用预检')
      queue.focus(); const pending = queue.props.onClick(); await tick(); assert.equal(queueCalls, 1)
      if (moveAway) date.focus()
      resolveQueue({ id: 'fresh' }); await pending; await tick()
      if (polled) {
        const deadline = Date.now() + 4000
        while (freshReads < 2 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 25))
        assert.equal(freshReads, 2); await tick()
      }
      assert.equal(page.focus()?.tag, target)
      if (moveAway) { assert.equal(page.focus(), date); assert.equal(page.scroll(), null) }
      else { assert.notEqual(page.focus(), queue); assert.match(text(page.scroll()), /第 7 行费用/) }
      assert.equal(page.writes(), 0)
    } finally { page.close() }
  }
})

test('实际预检去处理按钮跨父子组件定位原行说明，禁止项定位删除，过程不修改或提交费用', async () => {
  for (const [code, tag] of [['EXPENSE_EXCEPTION_REASON_REQUIRED', 'textarea'], ['EXPENSE_POLICY_DENIED', 'button']]) {
    const view = result(); view.findings[0].code = code
    const page = mount(view)
    try {
      await tick(); const before = expenseDrafts.get(page.props.scopeKey, 'report'), formBefore = formValues(page)
      const action = nodes(page.root).find(node => node.tag === 'button' && node.props['aria-label'] === '去处理第 7 行')
      assert.ok(action, '行级预检结果应有明确的处理入口'); action.props.onClick(); await tick()
      assert.equal(page.focus()?.tag, tag); assert.equal(page.scroll()?.tag, 'fieldset')
      assert.match(text(page.scroll()), /第 7 行费用/)
      if (tag === 'button') assert.equal(text(page.focus()), '移除此行')
      assert.deepEqual(expenseDrafts.get(page.props.scopeKey, 'report'), before)
      assert.deepEqual(formValues(page), formBefore); assert.equal(page.writes(), 0)
    } finally { page.close() }
  }
})

const actions = page => nodes(page.root).filter(node => node.tag === 'button' && node.props['aria-label']?.startsWith('去处理第 '))
const formValues = page => nodes(page.root).filter(node => ['input', 'textarea', 'select'].includes(node.tag)).map(node => [node.tag, node.value])

test('整单结果、旧版本、已删除行和写入锁定不提供定位，迟到的旧按钮也不能移动焦点', async () => {
  for (const change of [view => { view.findings[0].lineNo = null }, view => { view.job.applicationVersion-- },
    view => { view.job.financialVersion-- }, view => { view.findings[0].lineNo = 9 }]) {
    const view = result(); change(view); const page = mount(view)
    try { await tick(); assert.equal(actions(page).length, 0); assert.equal(page.focus(), null); assert.equal(page.writes(), 0) }
    finally { page.close() }
  }
  const page = mount()
  try {
    await tick(); const stale = actions(page)[0]; assert.ok(stale)
    page.props.locked = true; await tick(); assert.equal(actions(page).length, 0)
    stale.props.onClick(); await tick(); assert.equal(page.focus(), null); assert.equal(page.writes(), 0)
  } finally { page.close() }
})

test('输入分摊立即更新差额并关闭旧预检，切换账号后旧按钮不能定位新页面', async () => {
  const page = mount()
  try {
    await tick(); const stale = actions(page)[0]; assert.ok(stale)
    const target = nodes(page.root).find(node => node.tag === 'fieldset' && node.children.some(child => child.tag === 'legend' && text(child).includes('第 7 行费用')))
    const amount = nodes(target).find(node => node.tag === 'label' && text(node).trim().startsWith('分摊金额')).children.find(node => node.tag === 'input')
    amount.value = '100.00'; amount.listeners.input({ target: amount }); await tick()
    assert.match(text(target), /分摊已配平/)
    assert.equal(actions(page).length, 0); stale.props.onClick(); await tick(); assert.equal(page.focus(), null)
    assert.equal(expenseDrafts.get(page.props.scopeKey, 'report').content.lines[1].allocations[0].amount.value, '100.00')
    const previousScope = page.props.scopeKey
    page.props.scopeKey += '-next'; await tick(); assert.equal(actions(page).length, 1)
    stale.props.onClick(); await tick(); assert.equal(page.focus(), null); assert.equal(page.writes(), 0)
    expenseDrafts.clear(previousScope, 'report')
  } finally { page.close() }
})
