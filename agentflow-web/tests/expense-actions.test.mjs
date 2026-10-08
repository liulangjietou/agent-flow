import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, reactive } from 'vue'
const { default: Actions } = await import(process.env.AGENTFLOW_TEST_EXPENSEACTIONS)
const { default: Detail } = await import(process.env.AGENTFLOW_TEST_EXPENSEDETAIL)
const { default: Workspace } = await import(process.env.AGENTFLOW_TEST_EXPENSEWORKSPACE)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const originals = { receiveExpense: api.receiveExpense, reduceExpense: api.reduceExpense, withdrawExpense: api.withdrawExpense, cancelExpense: api.cancelExpense, revokeExpense: api.revokeExpense,
  expenseReport: api.expenseReport, expenseWorkflow: api.expenseWorkflow, expenseReports: api.expenseReports, expenseRequests: api.expenseRequests, employeeAdvances: api.employeeAdvances }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
const money = value => ({ value, currency: 'CNY' })
const detail = () => ({ id: 'report', applicationId: 'app', applicationVersion: 2, financialVersion: 5, roundNo: 1,
  content: { lines: [] }, financialRound: { baseCurrency: 'CNY', originalLines: [{ claimedBase: money('100.00') }], offsetTotal: money('0.00'), approvedLines: [{ lineNo: 1, gross: money('100.00'), tax: money('5.00') }] } })
const workflow = () => ({ reportId: 'report', applicationId: 'app', applicationVersion: 2, financialVersion: 5, roundNo: 1,
  canWithdraw: true, canCancel: true, task: { taskId: 'task', stage: 'FINANCE_REVIEW', canReceive: true, canReduce: true }, budget: { confirmedCurrent: true } })
const receipt = () => ({ reportId: 'report', applicationId: 'app', applicationVersion: 3, financialVersion: 6 })
const confirmOriginals = p => { if (p.state.pending === 'RECEIVE') p.state.receivedOriginals = p.state.originals.map(item => item.key) }
const settle = () => new Promise(resolve => setImmediate(resolve))
function panel(Component = Actions, initial = { scopeKey: 'demo/alice', detail: detail(), workflow: workflow(), locked: false }) {
  const props = reactive(initial), events = []
  const app = renderer.createApp({ ...Component, setup: (_, context) => Component.setup(props, context), render: () => null }, { ...props, onChanged: () => events.push('changed') })
  const mounted = app.mount({})
  return { state: mounted.$.setupState, props, events, close: () => { app.unmount(); Object.assign(api, originals) } }
}

const proxy = id => ({ proxyId: id, revision: 1, definitionId: 'published', principalId: id, principal: id,
  startsAt: new Date(Date.now() - 60_000).toISOString(), endsAt: new Date(Date.now() + 60_000).toISOString() })

test('已批准报销撤销须当前财务能力和人工确认，固定双版本且不调用作废', async () => {
  const calls = []; api.revokeExpense = async (...args) => { calls.push(args); return { ...receipt(), financialVersion: 5, status: 'REVOKED' } }
  api.cancelExpense = async () => { assert.fail('撤销不得改走草稿作废') }
  const p = panel()
  try {
    Object.assign(p.props.workflow, { canWithdraw: false, canCancel: false, task: null, revocation: { allowed: true, unavailable: null } })
    p.state.prepare('REVOKE'); assert.equal(p.state.pending, 'REVOKE'); assert.equal(calls.length, 0)
    await p.state.execute(); assert.equal(calls.length, 0); assert.match(p.state.error, /说明/)
    p.state.comment = '  已确认不再报销  '; await p.state.execute()
    assert.deepEqual(calls, [['report', { applicationVersion: 2, financialVersion: 5, comment: '已确认不再报销' }]])
    assert.deepEqual(p.events, ['changed'])
  } finally { p.close() }
})

test('撤销确认期间失去权限或凭证开始外发须立即清除意图', async () => {
  let calls = 0; api.revokeExpense = async () => { calls++; return receipt() }; const p = panel()
  try {
    p.props.workflow.revocation = { allowed: true, unavailable: null }
    p.state.prepare('REVOKE'); p.state.comment = '旧撤销意见'; assert.equal(p.state.pending, 'REVOKE')
    p.props.workflow.revocation = { allowed: false, unavailable: 'EXPENSE_REVOCATION_VOUCHER_STARTED' }
    assert.equal(p.state.pending, null); assert.equal(p.state.comment, '')
    p.state.prepare('REVOKE'); await p.state.execute(); assert.equal(calls, 0)
  } finally { p.close() }
})

test('撤销未知结果不能重复发送，迟到回执不能刷新另一个账号', async () => {
  let resolve, calls = 0
  api.revokeExpense = () => { calls++; return new Promise(done => { resolve = done }) }
  const p = panel()
  try {
    p.props.workflow.revocation = { allowed: true, unavailable: null }
    p.state.prepare('REVOKE'); p.state.comment = '原账号确认'; const first = p.state.execute(); await p.state.execute()
    assert.equal(calls, 1)
    p.props.scopeKey = 'tenant/other'; resolve({ ...receipt(), financialVersion: 5, status: 'REVOKED' }); await first
    assert.deepEqual(p.events, []); assert.equal(p.state.pending, null); assert.equal(p.state.comment, '')
    api.revokeExpense = async () => { calls++; throw { code: 'REQUEST_TIMEOUT' } }
    p.state.prepare('REVOKE'); p.state.comment = '当前确认'; await p.state.execute(); await p.state.execute()
    assert.equal(calls, 2); assert.equal(p.state.requiresRefresh, true); assert.match(p.state.error, /恢复/)
  } finally { p.close() }
})

test('撤销回执必须证明当前申请递增且财务版本未改，错误回执不宣告成功', async () => {
  for (const returned of [{ ...receipt(), status: 'REVOKED' }, { ...receipt(), financialVersion: 5, status: 'CANCELLED' },
    { ...receipt(), financialVersion: 5, applicationVersion: 4, status: 'REVOKED' }]) {
    api.revokeExpense = async () => returned; const p = panel()
    try {
      p.props.workflow.revocation = { allowed: true, unavailable: null }
      p.state.prepare('REVOKE'); p.state.comment = '财务确认'; await p.state.execute()
      assert.deepEqual(p.events, []); assert.equal(p.state.requiresRefresh, true)
    } finally { p.close() }
  }
})

test('代理签收和核减必须固定本次原审批人，多项依据不能静默选择', async () => {
  for (const [action, method] of [['RECEIVE', 'receiveExpense'], ['REDUCE', 'reduceExpense']]) {
    const calls = []; api[method] = async (...args) => { calls.push(args); return receipt() }; const p = panel()
    try {
      Object.assign(p.props.workflow.task, { canActDirectly: false, proxyOptions: [proxy('first'), proxy('second')] })
      p.state.prepare(action); confirmOriginals(p); p.state.comment = '已核对'; p.state.reason = 'OTHER'
      if (action === 'REDUCE') p.state.inputs[0].approvedGross = '80.00'
      await p.state.execute(); assert.equal(calls.length, 0); assert.match(p.state.error, /选择.*代理/)
      p.state.selectedProxy = 'second'; await p.state.execute()
      assert.equal(calls.length, 1); assert.equal(calls[0][2].proxyId, 'second')
      assert.equal(calls[0][2].applicationVersion, 2); assert.equal(calls[0][2].financialVersion, 5)
    } finally { p.close() }
  }
})

test('单一代理自动填入确认，同版本撤销或变更权限清空原意图，过期依据不提交', async () => {
  const calls = []; api.receiveExpense = async (...args) => { calls.push(args); return receipt() }; const p = panel()
  try {
    Object.assign(p.props.workflow.task, { canActDirectly: false, proxyOptions: [proxy('first')] })
    p.state.prepare('RECEIVE'); confirmOriginals(p); assert.equal(p.state.selectedProxy, 'first'); p.state.comment = '旧说明'
    p.props.workflow.task.proxyOptions = []
    assert.equal(p.state.pending, null); assert.equal(p.state.comment, ''); assert.equal(p.state.selectedProxy, '')
    p.props.workflow.task.proxyOptions = [{ ...proxy('expired'), endsAt: new Date(Date.now() - 1_000).toISOString() }]
    p.state.prepare('RECEIVE'); confirmOriginals(p); p.state.comment = '签收'; await p.state.execute()
    assert.equal(calls.length, 0); assert.match(p.state.error, /代理.*失效|代理.*不可用/)
  } finally { p.close() }
})

test('本人职责默认不带代理，失效的显式选择不得改为本人或另一条代理', async () => {
  const calls = []; api.receiveExpense = async (...args) => { calls.push(args); return receipt() }; const p = panel()
  try {
    Object.assign(p.props.workflow.task, { canActDirectly: true, proxyOptions: [proxy('valid')] })
    p.state.prepare('RECEIVE'); confirmOriginals(p); assert.equal(p.state.selectedProxy, '')
    p.state.selectedProxy = 'invalid'; p.state.comment = '签收'; await p.state.execute()
    assert.equal(calls.length, 0); assert.match(p.state.error, /代理.*失效|代理.*不可用/)
    p.state.selectedProxy = ''; await p.state.execute()
    assert.equal(calls.length, 1); assert.equal(Object.hasOwn(calls[0][2], 'proxyId'), false)
  } finally { p.close() }
})

test('收单、撤回、作废先确认且说明必填，只发送当前双版本', async () => {
  for (const [action, method] of [['RECEIVE', 'receiveExpense'], ['WITHDRAW', 'withdrawExpense'], ['CANCEL', 'cancelExpense']]) {
    const calls = []; api[method] = async (...args) => { calls.push(args); return receipt() }; const p = panel()
    try {
      p.state.prepare(action); confirmOriginals(p); assert.equal(calls.length, 0); p.state.cancel(); assert.equal(p.state.pending, null)
      p.state.prepare(action); confirmOriginals(p); await p.state.execute(); assert.equal(calls.length, 0); assert.match(p.state.error, /说明/)
      p.state.comment = ' 已核对原件或本次意图 '; await p.state.execute()
      const input = { applicationVersion: 2, financialVersion: 5, comment: '已核对原件或本次意图' }
      assert.deepEqual(calls, [action === 'RECEIVE' ? ['report', 'task', input] : ['report', input]])
      assert.deepEqual(p.events, ['changed']); assert.equal(p.state.requiresRefresh, true); p.state.prepare(action); confirmOriginals(p); assert.equal(p.state.pending, null)
    } finally { p.close() }
  }
})

test('打开确认表单后键盘焦点进入首个输入，取消后不执行迟到焦点跳转', async () => {
  const p = panel(); let focused = 0
  try {
    p.state.formElement = { querySelector: () => ({ focus: () => focused++ }) }
    p.state.prepare('RECEIVE'); confirmOriginals(p); await settle(); assert.equal(focused, 1)
    p.state.cancel(); p.state.prepare('REDUCE'); p.state.cancel(); await settle(); assert.equal(focused, 1)
  } finally { p.close() }
})

test('核减默认保留原金额，填写原因和变化后才发送精确字符串，不修改原始正文', async () => {
  const calls = []; api.reduceExpense = async (...args) => { calls.push(args); return receipt() }; const p = panel()
  try {
    p.state.prepare('REDUCE'); p.state.comment = '部分无效'; await p.state.execute(); assert.match(p.state.error, /至少减少/)
    p.state.reason = 'INVALID_INVOICE'; await p.state.execute(); assert.match(p.state.error, /至少减少/); assert.equal(calls.length, 0)
    p.state.inputs[0].approvedGross = '25.00'; p.state.inputs[0].approvedTax = '1.25'; await p.state.execute()
    assert.deepEqual(calls, [['report', 'task', { applicationVersion: 2, financialVersion: 5, comment: '部分无效', reasonCode: 'INVALID_INVOICE', lines: [{ lineNo: 1, approvedGross: '25.00', approvedTax: '1.25', reasonCode: 'INVALID_INVOICE' }] }]])
    assert.equal(p.props.detail.financialRound.approvedLines[0].gross.value, '100.00')
  } finally { p.close() }
})

test('直接触发签收处理器也不能绕过尚未逐项确认的纸质材料', async () => {
  let calls = 0; api.receiveExpense = async () => { calls++; return receipt() }
  const p = panel()
  try {
    p.props.detail.content = { lines: [{ lineNo: 7, invoiceIds: ['invoice'] }] }
    p.state.prepare('RECEIVE'); p.state.comment = '材料已核对'; await p.state.execute()
    assert.equal(calls, 0); assert.match(p.state.error, /纸质材料|原件/)
  } finally { p.close() }
})

test('委派或预算未确认时不能开启核减；提交锁与并发点击只发送一次', async () => {
  let resolve; const calls = []; api.reduceExpense = (...args) => { calls.push(args); return new Promise(done => { resolve = done }) }; const p = panel()
  try {
    p.props.workflow.task.canReduce = false; p.state.prepare('REDUCE'); assert.equal(p.state.pending, null)
    p.props.workflow.task.canReduce = true; p.props.locked = true; p.state.prepare('REDUCE'); assert.equal(p.state.pending, null)
    p.props.locked = false; p.state.prepare('REDUCE'); p.state.inputs[0].approvedGross = '80'; p.state.comment = '核对'; p.state.reason = 'OTHER'
    const first = p.state.execute(); await p.state.execute(); p.state.cancel(); assert.equal(p.state.pending, 'REDUCE'); assert.equal(calls.length, 1)
    resolve(receipt()); await first
  } finally { p.close() }
})

test('身份、任务或任一版本变化都会取消原意图；旧写入结果不刷新新账号', async () => {
  let resolve; api.receiveExpense = () => new Promise(done => { resolve = done }); const p = panel()
  try {
    for (const change of [() => p.props.detail.applicationVersion++, () => p.props.detail.financialVersion++, () => p.props.workflow.task.taskId = 'new-task']) {
      p.state.prepare('RECEIVE'); confirmOriginals(p); p.state.comment = '旧说明'; change(); assert.equal(p.state.pending, null); assert.equal(p.state.comment, '')
    }
    p.state.prepare('RECEIVE'); confirmOriginals(p); p.state.comment = '原账号'; const waiting = p.state.execute(); p.props.scopeKey = 'demo/bob'
    resolve(receipt()); await waiting; assert.deepEqual(p.events, []); assert.equal(p.state.saving, false); assert.equal(p.state.pending, null)
  } finally { p.close() }
})

test('冲突不自动更新版本重试；未知结果只提示恢复，不伪造成功', async () => {
  for (const failure of [{ code: 'CONCURRENCY_CONFLICT', status: 409 }, { code: 'REQUEST_TIMEOUT', status: 0 }]) {
    let calls = 0; api.reduceExpense = async () => { calls++; throw failure }; const p = panel()
    try {
      p.state.prepare('REDUCE'); p.state.reason = 'OTHER'; p.state.comment = '核减'; p.state.inputs[0].approvedGross = '50'
      await p.state.execute(); await p.state.execute(); assert.equal(calls, 1); assert.deepEqual(p.events, []); assert.equal(p.state.requiresRefresh, true)
      assert.match(p.state.error, failure.status ? /刷新/ : /恢复/); assert.equal(p.props.detail.applicationVersion, 2); assert.equal(p.props.detail.financialVersion, 5)
    } finally { p.close() }
  }
})

test('关闭组件后迟到回执不更新界面', async () => {
  let resolve; api.cancelExpense = () => new Promise(done => { resolve = done }); const p = panel()
  p.state.prepare('CANCEL'); p.state.comment = '取消计划'; const waiting = p.state.execute(); p.close(); resolve(receipt()); await waiting; assert.deepEqual(p.events, [])
})

test('实际费用详情组件区分旧冻结与本版本确认，账号切换清空旧内容', async () => {
  const calls = []; api.expenseReport = (_id, _round, signal) => new Promise(resolve => calls.push({ signal, resolve })); api.expenseWorkflow = async () => ({ ...workflow(), budget: { confirmedCurrent: false, ledgerStatus: 'FROZEN', operationStatus: 'QUEUED' } })
  const p = panel(Detail, { reportId: 'report', applicationId: 'app', scopeKey: 'alice', version: 2 })
  try {
    calls[0].resolve(detail()); await settle(); assert.match(p.state.budgetLabel, /等待/)
    p.props.scopeKey = 'bob'; assert.equal(p.state.query.detail, null); assert.equal(calls.length, 2)
    calls[1].resolve(detail()); await settle(); assert.ok(p.state.query.detail)
    p.state.query.workflow.budget.confirmedCurrent = true; assert.match(p.state.budgetLabel, /当前金额预算已确认/)
    p.props.roundNo = 1; assert.equal(p.state.query.workflow, null); calls[2].resolve(detail()); await settle()
  } finally { p.close() }
})

test('实际工作区切换视图和账号立即清空余额，分页按当前视图读取', async () => {
  const calls = []; api.expenseReports = async () => ({ items: [{ id: 'one' }], nextBeforeId: 'next' })
  api.expenseRequests = (filter, signal) => new Promise(resolve => calls.push({ filter, signal, resolve }))
  api.employeeAdvances = async () => ({ items: [{ id: 'advance' }], nextBeforeId: null })
  const p = panel(Workspace, { scopeKey: 'alice', refreshVersion: 0 })
  try {
    await settle(); assert.equal(p.state.reports.items.length, 1)
    p.state.tab = 'requests'; assert.deepEqual(p.state.reports.items, []); assert.equal(calls.length, 1)
    p.props.scopeKey = 'bob'; assert.equal(calls[0].signal.aborted, true)
    calls[0].resolve({ items: [{ id: 'leaked' }], nextBeforeId: null }); calls[1].resolve({ items: [], nextBeforeId: null }); await settle(); assert.deepEqual(p.state.requests.items, [])
    p.state.tab = 'advances'; await settle(); assert.equal(p.state.advances.items[0].id, 'advance'); p.props.refreshVersion++; assert.deepEqual(p.state.advances.items, [])
  } finally { p.close() }
})

test('申请弹窗按业务绑定关闭通用修改撤回入口，财务请求中 Escape 不关闭弹窗', async () => {
  const { default: Record } = await import(process.env.AGENTFLOW_TEST_APPLICATION_RECORD)
  const document = globalThis.document, originalApplication = api.application, originalRounds = api.applicationRounds
  globalThis.document = { activeElement: null }
  api.application = async () => ({ id: 'app', createdBy: 'alice', status: 'DRAFT', title: '费用草稿', payload: {}, version: 1, roundNo: 0, businessReference: { type: 'EXPENSE', id: 'report' } })
  api.applicationRounds = async () => []
  let closed = 0
  const app = renderer.createApp({ ...Record, render: () => null }, { applicationId: 'app', userId: 'alice', scopeKey: 'demo/alice', commentRefreshVersion: 0, pendingWrites: [], recoveryError: '', onClose: () => closed++ })
  const state = app.mount({}).$.setupState
  try {
    await settle(); assert.equal(state.expenseId, 'report'); assert.equal(state.canEdit, false)
    state.setApplication({ ...state.application, status: 'IN_APPROVAL' }); assert.equal(state.canWithdraw, false)
    state.expenseBusy = true; state.trapFocus({ key: 'Escape', preventDefault() {} }); assert.equal(closed, 0)
    state.expenseBusy = false; state.trapFocus({ key: 'Escape', preventDefault() {} }); assert.equal(closed, 1)
    state.setApplication({ ...state.application, status: 'DRAFT', businessReference: null }); assert.equal(state.canEdit, true)
  } finally { app.unmount(); globalThis.document = document; api.application = originalApplication; api.applicationRounds = originalRounds }
})
