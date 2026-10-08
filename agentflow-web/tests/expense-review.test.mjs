import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, effectScope, h, nextTick, reactive, ref } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { default: Actions } = await import(process.env.AGENTFLOW_TEST_EXPENSEACTIONSREVIEW)
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_EXPENSEDETAILREVIEW)
const { default: TaskPanel } = await import(process.env.AGENTFLOW_TEST_TASKACTIONSREVIEW)
const { action } = await import(process.env.AGENTFLOW_TEST_ACTION_FOCUS)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { previewReduction } = await import(process.env.AGENTFLOW_TEST_EXPENSES)
const { state: taskState } = await import(process.env.AGENTFLOW_TEST_EXPENSETASKSTATE)
const { render: taskRender } = await import(process.env.AGENTFLOW_TEST_EXPENSETASKAREA)
const { default: WorkspaceTabs } = await import(process.env.AGENTFLOW_TEST_WORKSPACETABSRENDERED)
const originals = { ...api }
afterEach(() => Object.assign(api, originals))
const money = value => ({ value, currency: 'CNY' })
const detail = () => ({ id: 'report', applicationId: 'app', applicationVersion: 2, financialVersion: 5, roundNo: 1,
  applicationStatus: 'IN_APPROVAL', editable: false, content: { title: '办公报销', type: 'DAILY', lines: [] },
  financialRound: { approvedLines: [{ lineNo: 7, gross: money('100.00'), tax: money('5.00'), allocations: [] }],
    originalLines: [{ claimedBase: money('120.00') }], baseCurrency: 'CNY', adjustments: [], advanceOffsets: [],
    approvedGross: money('100.00'), approvedTax: money('5.00'), offsetTotal: money('80.00'), payable: money('20.00'), maskedAccount: '****1234' } })
const workflow = () => ({ reportId: 'report', applicationId: 'app', applicationVersion: 2, financialVersion: 5, roundNo: 1,
  canWithdraw: false, canCancel: false, paper: null, budget: { confirmedCurrent: true },
  task: { taskId: 'task', stage: 'FINANCE_REVIEW', canReceive: false, canReduce: true, canApprove: true } })
const nodes = node => [node, ...(node.children ?? []).flatMap(nodes)]
const text = node => [node.text ?? '', ...(node.children ?? []).map(text)].join(' ')
const tick = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
function mount(Component = Actions, report = detail(), flow = workflow(), initial = null) {
  const events = [], taskEvents = [], returnEvents = [], executeEvents = [], instance = ref(null)
  let readFailure = null
  api.expenseReport = async () => { if (readFailure) throw readFailure; return structuredClone(report) }; api.expenseWorkflow = async () => flow
  const element = tag => ({ tag, tagName: tag.toUpperCase(), children: [], props: {}, listeners: {}, parent: null, text: '', value: '',
    addEventListener(kind, handler) { this.listeners[kind] = handler }, removeEventListener(kind) { delete this.listeners[kind] },
    get options() { return nodes(this).filter(node => node.tag === 'option') },
    querySelector() { return nodes(this).find(node => ['input', 'select', 'textarea'].includes(node.tag)) }, focus() {} })
  const remove = node => { if (node.parent) { const index = node.parent.children.indexOf(node); if (index >= 0) node.parent.children.splice(index, 1); node.parent = null } }
  const renderer = createRenderer({ createElement: element, createText: value => ({ ...element('#text'), text: value }), createComment: () => element('#comment'),
    insert(node, parent, anchor) { remove(node); node.parent = parent; const index = parent.children.indexOf(anchor); parent.children.splice(index < 0 ? parent.children.length : index, 0, node) },
    remove, parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
    patchProp(node, key, old, value) { node.props[key] = value; if (key === 'value') node.value = value },
    setText: (node, value) => { node.text = value }, setElementText: (node, value) => { node.text = value; node.children = [] } })
  const props = reactive(initial ?? (Component === Actions ? { detail: report, workflow: flow, scopeKey: 'tenant:alice', locked: false }
    : { reportId: 'report', applicationId: 'app', taskId: 'task', version: 2, scopeKey: 'tenant:alice', locked: false }))
  const root = element('root'), app = renderer.createApp({ setup: () => () => h(Component, { ...props, ref: instance, onBusy: value => events.push(value), onTaskActivity: value => taskEvents.push(value), onReturnMissing: value => returnEvents.push(value), onExecute: value => executeEvents.push(value) }) })
  app.mount(root)
  return { props, root, events, taskEvents, returnEvents, executeEvents, instance: () => instance.value, close: () => app.unmount(), failRead(cause) { readFailure = cause },
    button(label) { return nodes(root).find(node => node.tag === 'button' && text(node).trim() === label) },
    async input(label, value) { const node = nodes(root).find(node => node.props['aria-label'] === label); assert.ok(node, label); node.value = value; node.listeners.input({ target: node }); await tick() },
    async reason(value) { const node = nodes(root).find(node => node.props['aria-label'] === '统一填写核减原因'); for (const option of node.options) option.selected = option.props.value === value; node.listeners.change({ target: node }); await tick() },
    async comment(value) { const node = nodes(root).find(node => node.tag === 'textarea'); node.value = value; node.listeners.input({ target: node }); await tick() },
    async open() { this.button('核减费用').props.onClick(); await tick() } }
}

test('已批准撤销在实际模板中显示影响和确认，外发阻断只有财务可见', async () => {
  const flow = { ...workflow(), task: null, revocation: { allowed: true, unavailable: null } }
  const p = mount(Actions, { ...detail(), applicationStatus: 'APPROVED' }, flow)
  try {
    p.button('撤销已批准报销').props.onClick(); await tick()
    assert.match(text(p.root), /不能编辑或重提/); assert.match(text(p.root), /预算等待外部释放确认/)
    assert.match(text(p.root), /原批准轮次及财务记录保留/)
    p.props.workflow.revocation = { allowed: false, unavailable: 'EXPENSE_REVOCATION_VOUCHER_STARTED' }; await tick()
    assert.equal(nodes(p.root).some(node => node.tag === 'form'), false)
    assert.match(text(p.root), /凭证已开始外发/)
    p.props.workflow.revocation = null; await tick()
    assert.doesNotMatch(text(p.root), /凭证已开始外发|撤销已批准报销/)
  } finally { p.close() }
})

test('已撤销报销明确显示终态，保留金额历史但不再提示等待付款核销', async () => {
  const report = { ...detail(), applicationStatus: 'REVOKED' }, flow = { ...workflow(), task: null }
  const p = mount(Detail, report, flow, { reportId: 'report', applicationId: 'app', version: 2, scopeKey: 'tenant:finance', locked: false })
  const names = vnode => !vnode ? [] : [vnode.type?.name, ...(vnode.component ? names(vnode.component.subTree) : []),
    ...(Array.isArray(vnode.children) ? vnode.children.flatMap(names) : [])]
  try {
    await tick(); await tick()
    assert.match(text(p.root), /已撤销.*不会再付款或核销/)
    assert.match(text(p.root), /原申报合计/); assert.match(text(p.root), /当前核定合计/)
    const children = names(p.instance().$.subTree)
    assert.equal(children.includes('FinancePaymentStatus'), false)
    assert.equal(children.includes('ExpenseSettlementStatus'), false)
    assert.equal(children.filter(name => name === 'VoucherStatus').length, 1)
  } finally { p.close() }
})

test('核减输入即时提示增额与税额越界，未填原因说明不允许提交且不写请求', async () => {
  let writes = 0; api.reduceExpense = async () => { writes++; throw new Error('Unexpected write') }
  const p = mount()
  try {
    await p.open(); await p.input('第 7 行核减后含税额', '101.00')
    assert.match(text(p.root), /第 7 行只能减少金额/)
    assert.equal(p.button('确认财务核减').props.disabled, true)
    await p.input('第 7 行核减后含税额', '2'); await p.input('第 7 行核减后可抵扣税额', '3')
    assert.match(text(p.root), /税额不能超过含税额/)
    await p.input('第 7 行核减后可抵扣税额', '0')
    assert.equal(p.button('确认财务核减').props.disabled, true)
    await p.reason('OTHER'); assert.equal(p.button('确认财务核减').props.disabled, true)
    await p.comment('核对原始票据后减少'); assert.equal(p.button('确认财务核减').props.disabled, false)
    await p.input('第 7 行核减后含税额', '')
    assert.equal(p.button('确认财务核减').props.disabled, true); assert.equal(writes, 0)
  } finally { p.close() }
})

test('逐行核减必须各自填写原因，不同原因一次提交且未修改行不要求原因', async () => {
  const report = detail(), requests = []
  report.financialRound.approvedLines.push({ lineNo: 11, gross: money('30.00'), tax: money('0.00'), allocations: [] },
    { lineNo: 19, gross: money('10.00'), tax: money('0.00'), allocations: [] })
  api.reduceExpense = async (...args) => { requests.push(args); return { reportId: 'report', applicationId: 'app' } }
  const p = mount(Actions, report)
  const choose = async (label, value) => {
    const node = nodes(p.root).find(node => node.tag === 'select' && node.props['aria-label'] === label)
    assert.ok(node, label)
    for (const option of node.options) option.selected = option.props.value === value
    node.listeners.change({ target: node }); await tick()
  }
  try {
    await p.open(); await p.input('第 7 行核减后含税额', '80'); await p.input('第 11 行核减后含税额', '20')
    await p.comment('逐行核对票据和报销范围')
    await choose('第 7 行核减原因', 'INVALID_INVOICE')
    assert.equal(p.button('确认财务核减').props.disabled, true)
    nodes(p.root).find(node => node.tag === 'form').props.onSubmit({ preventDefault() {} }); await tick()
    assert.equal(requests.length, 0)
    await choose('第 11 行核减原因', 'INELIGIBLE_COST')
    assert.equal(p.button('确认财务核减').props.disabled, false)
    nodes(p.root).find(node => node.tag === 'form').props.onSubmit({ preventDefault() {} }); await tick()
    assert.equal(requests.length, 1)
    assert.equal(requests[0][2].reasonCode, 'OTHER')
    assert.deepEqual(requests[0][2].lines, [
      { lineNo: 7, approvedGross: '80', approvedTax: '5.00', reasonCode: 'INVALID_INVOICE' },
      { lineNo: 11, approvedGross: '20', approvedTax: '0.00', reasonCode: 'INELIGIBLE_COST' }])
  } finally { p.close() }
})

test('核减历史逐行显示原因，旧行记录使用原统一原因且不补写历史', async () => {
  const report = detail(), change = (lineNo, reasonCode) => ({ lineNo, previousGross: money('100'), approvedGross: money('80'), previousTax: money('5'), approvedTax: money('3'), ...(reasonCode ? { reasonCode } : {}) })
  report.financialRound.adjustments = [{ id: 'adjustment', reasonCode: 'OTHER', comment: '原统一说明', adjustedBy: 'finance', adjustedAt: '2026-10-07T12:00:00Z',
    lineChanges: [change(7, 'INVALID_INVOICE'), change(11)], offsetChanges: [] }]
  const p = mount(Detail, report)
  try {
    await tick(); await tick()
    const rows = nodes(p.root).filter(node => node.tag === 'li').map(text)
    assert.ok(rows.some(row => /第 7 行/.test(row) && /票据不符合要求/.test(row)))
    assert.ok(rows.some(row => /第 11 行/.test(row) && /其他原因/.test(row)))
    assert.equal(report.financialRound.adjustments[0].lineChanges[1].reasonCode, undefined)
  } finally { p.close() }
})

test('收单必须逐项核对纸质材料，缺失项阻止签收并预填退回且不直接写入', async () => {
  let writes = 0; api.receiveExpense = async () => { writes++; return { reportId: 'report', applicationId: 'app' } }
  const report = detail(), flow = workflow()
  report.content.lines = [{ lineNo: 7, invoiceIds: ['invoice-private-first', 'invoice-private-second'] }]
  Object.assign(flow.task, { stage: 'RECEIPT', canReceive: true, canReduce: false })
  const p = mount(Actions, report, flow)
  try {
    p.button('确认原件签收').props.onClick(); await tick(); await p.comment('核对材料')
    assert.equal(p.button('确认原件签收').props.disabled, true)
    const checks = nodes(p.root).filter(node => node.tag === 'input' && node.props.type === 'checkbox')
    assert.equal(checks.length, 3)
    for (const node of checks.slice(0, 2)) { node.checked = true; node.listeners.change({ target: node }); await tick() }
    await tick(); assert.equal(p.button('确认原件签收').props.disabled, true)
    assert.equal(p.button('缺失并退回').props.disabled, false)
    p.button('缺失并退回').props.onClick(); await tick()
    assert.equal(p.returnEvents.length, 1); assert.equal(p.events.at(-1), false)
    assert.deepEqual(p.returnEvents[0], { applicationId: 'app', draft: { scopeKey: 'tenant:alice', taskId: 'task', expectedVersion: 2,
      comment: '纸质材料缺失，请补齐后重新提交：第 7 行第 2 份发票纸质材料。\n核对材料' } })
    assert.doesNotMatch(p.returnEvents[0].draft.comment, /invoice-private/)
    assert.equal(writes, 0)
  } finally { p.close() }
})

test('全部纸质材料勾选后才能签收，取消再打开与版本变化不能继承上次确认', async () => {
  const flow = workflow(); Object.assign(flow.task, { stage: 'RECEIPT', canReceive: true, canReduce: false })
  const p = mount(Actions, detail(), flow), calls = []
  api.receiveExpense = async (...args) => { calls.push(args); return { reportId: 'report', applicationId: 'app' } }
  const confirm = async () => { const node = nodes(p.root).find(node => node.tag === 'input' && node.props.type === 'checkbox'); node.checked = true; node.listeners.change({ target: node }); await tick() }
  try {
    p.button('确认原件签收').props.onClick(); await tick(); await confirm()
    assert.equal(p.button('缺失并退回').props.disabled, true)
    p.button('取消').props.onClick(); await tick(); p.button('确认原件签收').props.onClick(); await tick()
    assert.equal(p.button('确认原件签收').props.disabled, true)
    await confirm(); p.props.detail.applicationVersion++; p.props.workflow.applicationVersion++; await tick()
    p.button('确认原件签收').props.onClick(); await tick(); assert.equal(p.button('确认原件签收').props.disabled, true)
    await confirm(); await p.comment('原件逐项核对无缺失')
    await nodes(p.root).find(node => node.tag === 'form').props.onSubmit({ preventDefault() {} }); await tick()
    assert.equal(calls.length, 1); assert.deepEqual(calls[0], ['report', 'task', { applicationVersion: 3, financialVersion: 5, comment: '原件逐项核对无缺失' }])
  } finally { p.close() }
})

test('缺件预填进入真实退回表单并允许修改，确认前不执行；旧上下文和现有人工意见不能被覆盖', async () => {
  const props = { task: { taskId: 'task', version: 2, allowedActions: ['RETURN', 'APPROVE'] }, scopeKey: 'tenant:alice', locked: false }
  const p = mount(TaskPanel, detail(), workflow(), props)
  const draft = { scopeKey: 'tenant:alice', taskId: 'task', expectedVersion: 2, comment: '缺少第 7 行纸质材料' }
  try {
    for (const invalid of [{ scopeKey: 'tenant:bob' }, { taskId: 'other' }, { expectedVersion: 1 }]) assert.equal(p.instance().prepareReturn({ ...draft, ...invalid }), false)
    p.props.locked = true; await tick(); assert.equal(p.instance().prepareReturn(draft), false); p.props.locked = false; await tick()
    assert.equal(p.instance().prepareReturn(draft), true); await tick()
    assert.equal(nodes(p.root).find(node => node.tag === 'textarea').value, draft.comment); assert.deepEqual(p.executeEvents, [])
    await p.comment('人工补充：请补齐后重新提交')
    assert.equal(p.instance().prepareReturn({ ...draft, comment: '迟到内容' }), false)
    assert.equal(nodes(p.root).find(node => node.tag === 'textarea').value, '人工补充：请补齐后重新提交')
    await nodes(p.root).find(node => node.tag === 'form').props.onSubmit({ preventDefault() {} })
    assert.deepEqual(p.executeEvents, [{ action: 'RETURN', expectedVersion: 2, comment: '人工补充：请补齐后重新提交', targetUser: undefined }])
    p.props.task.version++; await tick(); assert.equal(!!nodes(p.root).find(node => node.tag === 'textarea'), false)
    p.props.task.allowedActions = ['APPROVE']; await tick(); assert.equal(p.instance().prepareReturn({ ...draft, expectedVersion: 3 }), false)
  } finally { p.close() }
})

test('实际核减表单预览申报核定冲销应付，低于借款时下调冲销且不改原金额', async () => {
  const p = mount(), original = JSON.parse(JSON.stringify(p.props.detail))
  try {
    await p.open(); await p.input('第 7 行核减后含税额', '60.00')
    const preview = () => nodes(p.root).find(node => node.props['aria-label'] === '尚未保存的核减预览')
    assert.ok(preview()); assert.match(text(preview()), /120\.00/); assert.match(text(preview()), /60\.00/)
    assert.match(text(preview()), /冲销[\s\S]*60\.00/); assert.match(text(preview()), /应付[\s\S]*0\.00/)
    await p.input('第 7 行核减后含税额', '90.01')
    assert.match(text(preview()), /冲销[\s\S]*80\.00/); assert.match(text(preview()), /应付[\s\S]*10\.01/)
    assert.deepEqual(p.props.detail, original)
  } finally { p.close() }
})

test('财务详情顶部固定的四项金额同步未保存核减，取消恢复当前已确认金额', async () => {
  const p = mount(Detail)
  try {
    await tick(); await p.open(); await p.input('第 7 行核减后含税额', '60.00')
    const summary = () => nodes(p.root).find(node => String(node.props.class).includes('amount-equation'))
    assert.match(text(summary()), /原申报[\s\S]*120\.00[\s\S]*核定[\s\S]*60\.00[\s\S]*冲销[\s\S]*60\.00[\s\S]*应付[\s\S]*0\.00/)
    assert.match(text(summary()), /尚未保存/)
    p.button('取消').props.onClick(); await tick()
    assert.match(text(summary()), /核定[\s\S]*100\.00[\s\S]*冲销[\s\S]*80\.00[\s\S]*应付[\s\S]*20\.00/)
    assert.doesNotMatch(text(summary()), /尚未保存/)
  } finally { p.close() }
})

test('真实费用详情在打开未保存核减时通知父任务锁定，取消释放且不自锁核减表单', async () => {
  const p = mount(Detail)
  try {
    await tick(); await p.open()
    assert.equal(p.events.at(-1), true)
    assert.deepEqual(p.taskEvents.at(-1), { scopeKey: 'tenant:alice', applicationId: 'app', taskId: 'task', applicationVersion: 2, busy: true })
    await p.input('第 7 行核减后含税额', '90'); await p.reason('OTHER'); await p.comment('核减说明')
    assert.equal(p.button('确认财务核减').props.disabled, false)
    p.button('取消').props.onClick(); await tick(); assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('实际 App 审批入口在费用仍未保存时拒绝批准，不调用接口也不清空原任务', async () => {
  let writes = 0
  const state = { activeTask: ref({ taskId: 'task' }), activeApplication: ref({ id: 'app' }), busy: ref(false), writesBlocked: ref(false), expenseTaskBusy: ref(true),
    actorScope: ref('tenant:alice'), page: ref('workbench'), api: { taskAction: async () => { writes++; return { applicationStatus: 'APPROVED' } } }, refreshWorkspace: async () => {},
    notice: ref(''), taskActionLabels: { APPROVE: '批准' }, statusLabel: s => s, errorMessage: e => e.message, nextTick: async () => {}, operationStatus: ref(null) }
  await action(state)({ action: 'APPROVE' }); assert.equal(writes, 0); assert.equal(state.activeTask.value.taskId, 'task')
})

test('核减预览以稳定行号合并未改行，大额一分与只减税额均不发生浮点舍入', () => {
  const round = detail().financialRound
  round.approvedLines = [{ lineNo: 3, gross: money('999999999999998.99'), tax: money('0.03'), allocations: [] },
    { lineNo: 7, gross: money('1.00'), tax: money('0.00'), allocations: [] }]
  round.originalLines = [{ claimedBase: money('999999999999999.99') }]; round.offsetTotal = money('999999999999999.97')
  const before = structuredClone(round)
  const preview = previewReduction(round, [{ lineNo: 3, approvedGross: '999999999999998.98', approvedTax: '0.02' }])
  assert.equal(preview.gross.value, '999999999999999.98'); assert.equal(preview.payable.value, '0.01'); assert.equal(preview.tax.value, '0.02')
  assert.equal(preview.original.value, '999999999999999.99'); assert.deepEqual(round, before)
  const taxOnly = previewReduction(round, [{ lineNo: 3, approvedGross: '999999999999998.99', approvedTax: '0.01' }])
  assert.equal(taxOnly.gross.value, '999999999999999.99'); assert.equal(taxOnly.payable.value, '0.02')
  assert.equal(previewReduction(round, []).lines.length, 0)
  assert.throws(() => previewReduction(round, [{ lineNo: 8, approvedGross: '0', approvedTax: '0' }]), /费用行已变化/)
  assert.throws(() => previewReduction(round, [{ lineNo: 7, approvedGross: '0', approvedTax: '0' }, { lineNo: 7, approvedGross: '0', approvedTax: '0' }]), /费用行已变化/)
})

test('核减结果未确认时取消表单仍保持审批锁，当前费用版本刷新后才解除', async () => {
  let writes = 0; api.reduceExpense = async () => { writes++; throw { status: 0, code: 'REQUEST_TIMEOUT' } }
  const p = mount()
  try {
    await p.open(); await p.input('第 7 行核减后含税额', '90'); await p.reason('OTHER'); await p.comment('核减说明')
    const form = nodes(p.root).find(node => node.tag === 'form')
    await form.props.onSubmit({ preventDefault() {} }); await tick()
    assert.equal(writes, 1); assert.equal(p.events.at(-1), true); assert.match(text(p.root), /恢复入口/)
    p.button('取消').props.onClick(); await tick(); assert.equal(p.events.at(-1), true)
    assert.equal(p.button('刷新费用状态').props.disabled, false)
    p.props.detail.financialVersion++; p.props.workflow.financialVersion++; await tick()
    assert.equal(p.events.at(-1), false)
  } finally { p.close() }
})

test('实际父页面接线禁止审批与加签且保持核减可编辑，旧身份任务版本回调不能解锁新任务', async () => {
  const scope = effectScope(); let membershipWrites = 0
  const deps = { activeTask: ref({ taskId: 'task', applicationId: 'app', version: 2 }), activeApplication: ref({ id: 'app', version: 2, roundNo: 1, businessReference: { type: 'EXPENSE', id: 'report' } }),
    actorScope: ref('tenant:alice'), busy: ref(false), writesBlocked: ref(false), api: { changeCountersignMembers: async () => { membershipWrites++; return {} } },
    clearTaskSelection() {}, refreshWorkspace: async () => {}, notice: ref(''), errorMessage: e => e.message, selectTask: async () => {}, nextTick, taskActionsPanel: ref(null) }
  const state = scope.run(() => taskState(deps))
  let expenseAttrs, taskAttrs
  async function render() {
    const app = createSSRApp({ setup: () => ({ ...deps, ...state, taskTab: 'detail', detailError: '', expenseTaskChanged() {}, performAction() {} }), render: taskRender })
    app.component('WorkspaceTabs', WorkspaceTabs)
    for (const name of ['RoundComparison', 'ApplicationHistory', 'AssistRunRecords', 'ApplicationComments', 'FormFields', 'ExpensePlanDetail', 'AdvanceRequestDetail', 'ProcurementPaymentDetail', 'BudgetAdjustmentDetail', 'TaskDeadlineStatus']) app.component(name, { render: () => null })
    app.component('ExpenseDetail', { setup(_, context) { expenseAttrs = context.attrs; return () => null } })
    app.component('TaskActions', { setup(_, context) { taskAttrs = context.attrs; return () => null } })
    return renderToString(app)
  }
  const current = () => ({ scopeKey: deps.actorScope.value, applicationId: deps.activeApplication.value.id, taskId: deps.activeTask.value.taskId, applicationVersion: deps.activeApplication.value.version, busy: true })
  try {
    await render(); assert.equal(typeof expenseAttrs.onTaskActivity, 'function'); assert.equal(typeof expenseAttrs.onReturnMissing, 'function')
    const original = current(); expenseAttrs.onTaskActivity(original)
    const locked = await render(); assert.equal(taskAttrs.locked, true); assert.equal(expenseAttrs.locked, false)
    assert.match(locked, /费用操作尚未结束/); assert.match(locked, /role="tab"[^>]*disabled/)
    await state.performMembershipChange({ expectedVersion: 2, action: 'ADD' }, { taskId: 'task' }); assert.equal(membershipWrites, 0)
    for (const change of ['scopeKey', 'applicationId', 'taskId', 'applicationVersion']) {
      state.expenseTaskActivity({ ...original, [change]: change === 'applicationVersion' ? 3 : 'another', busy: false })
      assert.equal(state.expenseTaskBusy.value, true, change)
    }
    deps.activeTask.value = { taskId: 'new-task', applicationId: 'app', version: 2 }; assert.equal(state.expenseTaskBusy.value, false)
    state.expenseTaskActivity(current()); state.expenseTaskActivity({ ...original, busy: false }); assert.equal(state.expenseTaskBusy.value, true)
    deps.activeApplication.value = { ...deps.activeApplication.value, version: 3 }; assert.equal(state.expenseTaskBusy.value, false)
    state.expenseTaskActivity(current()); deps.actorScope.value = 'tenant:bob'; assert.equal(state.expenseTaskBusy.value, false)
    state.expenseTaskActivity({ ...original, busy: true }); assert.equal(state.expenseTaskBusy.value, false)
  } finally { scope.stop() }
})

test('缺件从真实费用详情转交，父待办在下次渲染前后复核上下文，不把迟到草稿带入新任务', async () => {
  const scope = effectScope(), prepared = []
  const deps = { activeTask: ref({ taskId: 'task', version: 2 }), activeApplication: ref({ id: 'app', version: 2 }), actorScope: ref('tenant:alice'),
    busy: ref(false), writesBlocked: ref(false), notice: ref(''), taskActionsPanel: ref({ prepareReturn: value => { prepared.push(value); return true } }), nextTick }
  const state = scope.run(() => taskState(deps)), flow = workflow()
  Object.assign(flow.task, { stage: 'RECEIPT', canReceive: true, canReduce: false })
  const p = mount(Detail, detail(), flow)
  try {
    await tick(); p.button('确认原件签收').props.onClick(); await tick(); p.button('缺失并退回').props.onClick(); await tick()
    assert.equal(p.returnEvents.length, 1); assert.equal(p.taskEvents.at(-1).busy, false)
    const request = p.returnEvents[0]
    await state.prepareExpenseReturn(request); assert.deepEqual(prepared, [request.draft])
    for (const flag of [deps.busy, deps.writesBlocked, state.expenseTaskBusy]) { flag.value = true; await state.prepareExpenseReturn(request); flag.value = false }
    assert.equal(prepared.length, 1)
    const waiting = state.prepareExpenseReturn(request); deps.activeTask.value = { taskId: 'new', version: 2 }; await waiting
    assert.equal(prepared.length, 1)
    deps.activeTask.value = { taskId: 'task', version: 2 }; deps.activeApplication.value = { id: 'app', version: 3 }; await state.prepareExpenseReturn(request)
    deps.activeApplication.value = { id: 'other', version: 2 }; await state.prepareExpenseReturn(request)
    deps.activeApplication.value = { id: 'app', version: 2 }; deps.actorScope.value = 'tenant:bob'; await state.prepareExpenseReturn(request)
    assert.equal(prepared.length, 1)
  } finally { p.close(); scope.stop() }
})

test('业务审批人无法读取敏感费用时完成受限读取仍解除加载锁，不以完整财务权限代替任务授权', async () => {
  const p = mount(Detail)
  try {
    await tick(); p.failRead({ status: 403 })
    p.props.version++; await tick()
    assert.equal(p.taskEvents.at(-1).busy, false)
    assert.equal(!!p.button('核减费用'), false)
  } finally { p.close() }
})
