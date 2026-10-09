import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { createRenderer, reactive, nextTick } from 'vue'

const apiUrl = pathToFileURL(process.env.AGENTFLOW_TEST_API)
const { api, writeRequests, bindAuthenticationActor } = await import(apiUrl)
const model = await import(new URL('expenseHandling.js', apiUrl))
const recovery = await import(new URL('financeRecovery.js', apiUrl))
const { default: Panel } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_EXPENSEHANDLINGPANELPANEL))
const { default: Approval } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_EXPENSEAPPROVALASSISTANTPANEL))
const originals = { ...api }, originalFetch = globalThis.fetch, storage = globalThis.localStorage
afterEach(() => { Object.assign(api, originals); globalThis.fetch = originalFetch; globalThis.localStorage = storage; bindAuthenticationActor(null) })
const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const at = '2026-10-09T00:00:00Z', copy = value => structuredClone(value)
const task = () => ({ id: uuid(1), reportId: uuid(2), applicationId: uuid(3), goal: '核对材料', createdAt: at, version: 1, status: 'OPEN', applicationVersion: 1, financialVersion: 1, current: true, steps: [] })
const expense = () => ({ id: uuid(2), applicationId: uuid(3), applicationVersion: 1, financialVersion: 1, editable: true, roundNo: 0, content: { lines: [], advanceOffsets: [] } })
const step = () => ({ number: 1, tool: 'EXPENSE', referenceId: uuid(2), sourceVersion: 1, outcome: 'READ', applicationVersion: 1, financialVersion: 1, inputDigest: 'a'.repeat(64), startedAt: at, updatedAt: at })
const receipt = () => ({ task: { ...task(), version: 2, steps: [step()] }, result: { tool: 'EXPENSE', expense: expense() } })
const usage = () => ({ runId: uuid(4), subjectId: uuid(2), kind: 'PRECHECK_EXPLANATION', queuedAt: at, startedAt: at, completedAt: at, queueMillis: 0, executionMillis: 20, outcome: 'SUCCEEDED', providerId: 'fixture', modelVersion: 'fixture-v1', promptVersion: 'v1', usageStatus: 'NOT_REPORTED', inputTokens: null, outputTokens: null, totalTokens: null })
const settle = () => new Promise(resolve => setImmediate(resolve))
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(Component, props) {
  const app = renderer.createApp({ ...Component, setup: (_, ctx) => Component.setup(props, ctx), render: () => null }, props)
  return { app, state: app.mount({}).$.setupState }
}

test('办理历史拒绝跨单、重复记录、超过上限与伪造审批状态', () => {
  assert.deepEqual(model.readHandlingTasks([task()], uuid(2)), [task()])
  for (const change of [v => { v.reportId = uuid(8) }, v => { v.status = 'APPROVED' }, v => { v.steps = Array(33).fill(step()) }, v => { v.steps = [{ ...step(), number: 2 }] }, v => { v.steps = [{ ...step(), inputDigest: 'forged' }] }]) {
    const value = task(); change(value); assert.throws(() => model.readHandlingTasks([value], uuid(2)))
  }
  assert.throws(() => model.readHandlingTasks([task(), task()], uuid(2)))
})
test('工具回执绑定原办理、双版本、白名单及来源记录', () => {
  const path = `${model.handlingPath(uuid(2))}/${uuid(1)}/inspect`, input = JSON.stringify({ expectedVersion: 1, tool: 'EXPENSE' })
  assert.deepEqual(model.validateHandlingReceipt(receipt(), path, input), receipt())
  for (const change of [v => { v.task.id = uuid(9) }, v => { v.task.version++ }, v => { v.result.expense.financialVersion++ }, v => { v.result.tool = 'APPROVE' }, v => { v.result.invoice = {} }, v => { v.task.steps[0].outcome = 'SUBMITTED' }]) {
    const value = receipt(); change(value); assert.throws(() => model.validateHandlingReceipt(value, path, input))
  }
})
test('未知用量不转成零，真实零可报告，中断观测不伪造结束时间', () => {
  assert.equal(model.readAgentUsage([usage()], uuid(2))[0].totalTokens, null)
  assert.equal(model.readAgentUsage([{ ...usage(), usageStatus: 'REPORTED', inputTokens: 0, outputTokens: 0, totalTokens: 0 }])[0].totalTokens, 0)
  assert.throws(() => model.readAgentUsage([{ ...usage(), inputTokens: 0 }]))
  assert.throws(() => model.readAgentUsage([{ ...usage(), outcome: 'IN_PROGRESS' }]))
  assert.equal(model.readAgentUsage([{ ...usage(), outcome: 'IN_PROGRESS', completedAt: null, executionMillis: null }])[0].outcome, 'IN_PROGRESS')
  assert.throws(() => model.readAgentUsage([usage()], uuid(8)))
})
test('响应丢失按原幂等键恢复办理，不创建第二条任务', async () => {
  globalThis.localStorage = { getItem: () => 'fixture' }; bindAuthenticationActor({ tenantId: 'demo', userId: 'handling-recovery' })
  const sent = []; let fail = true
  globalThis.fetch = async (url, options) => { sent.push({ url, key: options.headers['Idempotency-Key'], body: options.body }); if (fail) throw new Error('lost'); return Response.json(task()) }
  await assert.rejects(api.startExpenseHandling(uuid(2), { applicationVersion: 1, financialVersion: 1, goal: '核对材料' }))
  const pending = writeRequests.pending()[0]; assert.ok(pending)
  fail = false; await writeRequests.recover(pending.id)
  assert.equal(writeRequests.pending().length, 0); assert.deepEqual(sent[1], sent[0])
})
test('畸形成功回执仍保留原请求，不能误当已成功的新任务', async () => {
  globalThis.localStorage = { getItem: () => 'fixture' }; bindAuthenticationActor({ tenantId: 'demo', userId: 'handling-malformed' })
  globalThis.fetch = async () => Response.json({ ...task(), reportId: uuid(8) })
  await assert.rejects(api.startExpenseHandling(uuid(2), { applicationVersion: 1, financialVersion: 1, goal: '核对材料' }))
  assert.equal(writeRequests.pending().length, 1)
})
test('办理界面只读恢复，切换身份后迟到响应不能带回前一人的任务', async () => {
  let finish, reads = 0, writes = 0
  api.expenseHandlingTasks = () => ++reads === 1 ? new Promise(resolve => { finish = resolve }) : Promise.resolve([])
  api.startExpenseHandling = async () => { writes++; return task() }
  const props = reactive({ report: expense(), scopeKey: 'demo:alice', dirty: false, locked: false }), mounted = mount(Panel, props)
  try {
    props.scopeKey = 'demo:bob'; await nextTick(); finish([task()]); await settle()
    assert.equal(mounted.state.tasks.length, 0); assert.equal(writes, 0); assert.equal(mounted.state.loading, false)
  } finally { mounted.app.unmount() }
})
test('未保存费用和未知请求阻止开始新办理；读取历史不调用模型或提交', async () => {
  api.expenseHandlingTasks = async () => []; let writes = 0
  api.startExpenseHandling = async () => { writes++; return task() }
  const props = reactive({ report: expense(), scopeKey: 'demo:alice', dirty: true }), mounted = mount(Panel, props)
  try { await settle(); await mounted.state.start(); assert.equal(writes, 0); mounted.state.pending = true; props.dirty = false; await mounted.state.start(); assert.equal(writes, 0) }
  finally { mounted.app.unmount() }
})
test('审批材料遇到权限拒绝清空旧事实，不给受限审批人显示财务金额', async () => {
  let denied = false
  api.expenseReport = async () => { if (denied) throw { status: 403 }; return { ...expense(), roundNo: 1, financialRound: { adjustments: [], originalLines: [] } } }
  api.expenseWorkflow = async () => ({ reportId: uuid(2), applicationId: uuid(3), applicationVersion: 1, financialVersion: 1, roundNo: 1, task: { taskId: 't' }, paper: null, budget: { confirmedCurrent: false } })
  const props = reactive({ reportId: uuid(2), applicationId: uuid(3), scopeKey: 'demo:finance', version: 1, roundNo: 1, taskId: 't' }), mounted = mount(Approval, props)
  try {
    await settle(); assert.ok(mounted.state.financial); assert.ok(mounted.state.missing.length)
    denied = true; props.scopeKey = 'demo:limited'; await settle()
    assert.equal(mounted.state.query.restricted, true); assert.equal(mounted.state.financial, undefined)
  } finally { mounted.app.unmount() }
})
test('未知资金结果只提示原交易查询，没有查询权限时不提示发起新付款', () => {
  const value = { payment: { id: uuid(4), operation: { status: 'UNKNOWN', disputed: false } }, actions: { query: true }, dispute: null }
  assert.match(recovery.paymentRecovery(value).next, /查询原交易/)
  value.actions.query = false; assert.match(recovery.paymentRecovery(value).next, /等待原交易/)
  assert.equal(recovery.paymentRecovery(value).originalId, uuid(4))
})
test('ERP 查无原操作也必须有服务端重发资格，不允许模型建议绕过权限', () => {
  const value = { operation: { id: uuid(5), status: 'NOT_FOUND', disputed: false }, actions: { resendOriginal: false }, dispute: null }
  assert.match(recovery.voucherRecovery(value).next, /不具备重发条件/)
  value.actions.resendOriginal = true; assert.match(recovery.voucherRecovery(value).next, /按原编号重发/)
  value.operation.status = 'UNKNOWN'; assert.doesNotMatch(recovery.voucherRecovery(value).next, /按原编号重发/)
})
