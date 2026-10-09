import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { createRenderer, reactive, nextTick } from 'vue'

const apiUrl = pathToFileURL(process.env.AGENTFLOW_TEST_API)
const { api, writeRequests, bindAuthenticationActor } = await import(apiUrl)
const model = await import(new URL('expenseAgent.js', apiUrl))
const explanation = await import(new URL('precheckExplanation.js', apiUrl))
const { default: Panel } = await import(pathToFileURL(process.env.AGENTFLOW_TEST_EXPENSEAGENTPANELPANEL))
const originals = { ...api }, fetchOriginal = globalThis.fetch, storage = globalThis.localStorage
afterEach(() => { Object.assign(api, originals); globalThis.fetch = fetchOriginal; globalThis.localStorage = storage; bindAuthenticationActor(null) })
const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const now = new Date().toISOString(), until = new Date(Date.now() + 600000).toISOString()
const scope = () => ({ policyLineNos: [1], invoiceIds: [uuid(4)], precheckIds: [], maxSteps: 8 })
const run = () => ({ id: uuid(1), taskId: uuid(2), reportId: uuid(3), applicationVersion: 1, financialVersion: 1, scope: scope(), deadline: until,
  state: { version: 1, status: 'READY', steps: [], answers: [], updatedAt: now } })
const decision = () => ({ action: 'POLICY', referenceId: null, lineNo: 1, message: '核对适用制度' })
const step = () => ({ id: uuid(6), createdAt: now, decision: decision(), outcome: 'TOOL_READY', observation: null })
const preview = () => ({ goal: '核对资料', scope: scope(), applicationVersion: 1, financialVersion: 1, providerId: 'fixture', model: 'fixture', destination: '127.0.0.1', targetDigest: 'a'.repeat(64), consentDigest: 'b'.repeat(64), sendableData: { expense: {} } })
const settle = () => new Promise(resolve => setImmediate(resolve))

test('自动办理拒绝跨单回执、超预算与白名单之外的动作或参数', () => {
  const value = run(); value.state.status = 'TOOL_READY'; value.state.steps = [step()]
  assert.deepEqual(model.readExpenseAgent(value, uuid(3), uuid(2)), value)
  for (const mutate of [r => { r.taskId = uuid(9) }, r => { r.scope.maxSteps = 13 }, r => { r.state.steps[0].decision.action = 'APPROVE' },
    r => { r.state.steps[0].decision.lineNo = 2 }, r => { r.state.steps[0].decision.referenceId = uuid(7) }, r => { r.state.status = 'WAITING_CHILD' }]) {
    const changed = structuredClone(value); mutate(changed); assert.throws(() => model.readExpenseAgent(changed, uuid(3), uuid(2)))
  }
  assert.throws(() => model.readAgentPreview(preview(), { ...scope(), invoiceIds: [] }))
})
test('自动授权丢失回执按原键恢复，不重复创建一次模型授权', async () => {
  globalThis.localStorage = { getItem: () => 'fixture' }; bindAuthenticationActor({ tenantId: 'demo', userId: 'agent-recovery' })
  const sent = []; let fail = true
  globalThis.fetch = async (url, options) => { sent.push({ url, key: options.headers['Idempotency-Key'], body: options.body }); if (fail) throw new Error('lost'); return Response.json(run()) }
  await assert.rejects(api.startExpenseAgent(uuid(3), uuid(2), preview()))
  const pending = writeRequests.pending()[0]; assert.ok(pending)
  fail = false; await writeRequests.recover(pending.id)
  assert.deepEqual(sent[1], sent[0]); assert.equal(writeRequests.pending().length, 0)
})
test('自动授权成功响应身份损坏时保留原请求', async () => {
  globalThis.localStorage = { getItem: () => 'fixture' }; bindAuthenticationActor({ tenantId: 'demo', userId: 'agent-broken' })
  globalThis.fetch = async () => Response.json({ ...run(), taskId: uuid(9) })
  await assert.rejects(api.startExpenseAgent(uuid(3), uuid(2), preview()))
  assert.equal(writeRequests.pending().length, 1)
})
test('恢复读取记录只能包含已知只读工具及有界原输入', () => {
  const value = { id: uuid(5), input: { expectedVersion: 1, tool: 'POLICY', lineNo: 1 }, inputDigest: 'a'.repeat(64), version: 2, status: 'FAILED', failureCode: 'FINANCE_GATEWAY_UNAVAILABLE' }
  assert.deepEqual(model.readHandlingReads([value]), [value])
  assert.throws(() => model.readHandlingReads([{ ...value, input: { expectedVersion: 1, tool: 'PAY' } }]))
  assert.throws(() => model.readHandlingReads([value, value]))
})
test('身份切换丢弃迟到运行，读取状态不会自行发起模型请求', async () => {
  let complete, count = 0, writes = 0
  api.expenseAgent = () => ++count === 1 ? new Promise(resolve => { complete = resolve }) : Promise.resolve(null)
  api.expenseHandlingReads = async () => []; api.invoices = async () => ({ items: [] }); api.startExpenseAgent = async () => { writes++; return run() }
  const props = reactive({ report: { id: uuid(3), applicationVersion: 1, financialVersion: 1, content: { lines: [] } }, task: { id: uuid(2), current: true }, scopeKey: 'alice', locked: false })
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
  const app = renderer.createApp({ ...Panel, setup: (_, ctx) => Panel.setup(props, ctx), render: () => null }, props), state = app.mount({}).$.setupState
  try { props.scopeKey = 'bob'; await nextTick(); complete(run()); await settle(); assert.equal(state.run, null); assert.equal(writes, 0) }
  finally { app.unmount() }
})
test('结构化建议拒绝伪造比较值、金额字段、重复字段与缺少字段来源', () => {
  const issue = 'precheck:finding[0]', field = 'expense:field[1].DESCRIPTION'
  const source = (id, content) => ({ reference: { sourceId: id, contentDigest: 'a'.repeat(64) }, label: id, content })
  const sources = [source('precheck:result', '{}'), source(issue, '{}'), source(field, '办公用品')]
  const patch = { lineNo: 1, field: 'DESCRIPTION', beforeValue: '办公用品', afterValue: '办公打印耗材', impact: '保存后重新预检' }
  const value = { id: uuid(1), precheckId: uuid(2), applicationVersion: 1, financialVersion: 1, attempt: 1, status: 'COMPLETED', version: 3, createdAt: now,
    result: 'BLOCKED', checkedAt: now, validUntil: until, startedAt: now, completedAt: now, sources, canAdopt: true, unavailableCode: null, failure: null, review: null,
    suggestion: { providerId: 'fixture', modelVersion: 'fixture', promptVersion: 'expense-precheck-explanation-v2', items: [{ issueSourceId: issue, explanation: '核对费用用途', corrections: ['确认后重新预检'], evidence: sources.slice(1).map(v => v.reference), patches: [patch] }] } }
  assert.deepEqual(explanation.readExplanationDetail(value, uuid(1)), value)
  for (const mutate of [v => { v.suggestion.items[0].patches[0].beforeValue = '伪造' }, v => { v.suggestion.items[0].patches[0].field = 'CLAIMED_GROSS' },
    v => { v.suggestion.items[0].patches.push(patch) }, v => { v.sources.pop() }]) {
    const changed = structuredClone(value); mutate(changed); assert.throws(() => explanation.readExplanationDetail(changed, uuid(1)))
  }
})
