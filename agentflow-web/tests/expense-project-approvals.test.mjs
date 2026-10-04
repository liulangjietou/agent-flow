import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, createSSRApp, nextTick, reactive } from 'vue'
import { renderToString } from '@vue/server-renderer'
const model = await import(process.env.AGENTFLOW_TEST_EXPENSE_PROJECT_APPROVAL)
const { api } = await import(process.env.AGENTFLOW_TEST_API)
const { default: Panel } = await import(process.env.AGENTFLOW_TEST_EXPENSEPROJECTAPPROVALPANELPANEL)
const { default: Facts } = await import(process.env.AGENTFLOW_TEST_EXPENSEPROJECTOWNERSRENDERED)
const { default: Assignee } = await import(process.env.AGENTFLOW_TEST_DEFINITIONASSIGNEEPANEL)
const { default: AssigneeRendered } = await import(process.env.AGENTFLOW_TEST_DEFINITIONASSIGNEERENDERED)
const { default: Stage } = await import(process.env.AGENTFLOW_TEST_DEFINITIONEXPENSESTAGERENDERED)
const clone = value => structuredClone(value), id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const original = { ...api }, originalFetch = globalThis.fetch
const source = () => ({ catalogVersion: 'original-catalog-v1', legalEntityId: id(3), projects: [
  { legalEntityId: id(3), code: 'A', name: '合成项目 A', ownerSubject: 'alice' },
  { legalEntityId: id(3), code: 'B', name: '合成项目 B', ownerSubject: 'bob' }
] })
function view(roundNo = 1) { return { reportId: id(1), applicationId: id(2), roundNo, status: 'RECORDED', details: {
  ruleVersion: 1, precheckId: id(4), precheckVersion: 3, applicationVersion: 2, financialVersion: 2, definitionId: id(5), definitionVersion: 1,
  nodeId: 'projects', submittedAt: '2026-10-04T12:00:00.123456Z', source: source(),
  initiator: { appointmentId: id(6), personId: id(7), subject: 'alice', directoryRevision: 10, legalEntityId: id(3), legalEntityName: '原法人',
    departmentId: id(8), departmentName: '原部门', positionId: id(9), positionName: '原岗位' },
  responsibility: { nodeName: '项目审批', stage: 'PROJECT_REVIEW', rule: 'expense:projectOwners', directoryRevision: 10,
    originalSubjects: ['alice', 'bob'], candidateSubjects: ['bob', 'manager'],
    escalation: { supervisorAppointmentId: id(10), originalSubject: 'alice', replacementSubject: 'manager', directoryRevision: 10 } }
} } }
const read = value => model.readProjectApprovalView(value, id(1), id(2), 1)
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
function mount(component, initial) {
  const props = reactive(initial), events = []
  const app = renderer.createApp({ ...component, setup: (_, context) => component.setup(props, { ...context, emit: (...args) => events.push(args) }), render: () => null }, props)
  const instance = app.mount({}); return { props, state: instance.$.setupState, events, close: () => app.unmount() }
}
afterEach(() => { Object.assign(api, original); globalThis.fetch = originalFetch })

test('原责任记录、无项目和未知历史保持三种不同状态', () => {
  assert.deepEqual(read(view()), view())
  const empty = view(); empty.status = 'NO_PROJECT'; empty.details.source.projects = []; empty.details.responsibility = null
  assert.deepEqual(read(empty), empty)
  const legacy = { reportId: id(1), applicationId: id(2), roundNo: 1, status: 'NOT_RECORDED', details: null }
  assert.deepEqual(read(legacy), legacy)
  assert.throws(() => read({ ...legacy, details: {} })); assert.throws(() => read({ ...empty, status: 'RECORDED' }))
})
test('合法长节点名称保留原轮次依据，空名称和目录字段越界仍拒绝', () => {
  const recorded = view(); recorded.details.responsibility.nodeName = '项目'.repeat(65)
  assert.equal(read(recorded).details.responsibility.nodeName, recorded.details.responsibility.nodeName)
  for (const invalid of ['', '   ', null, 130]) {
    const broken = view(); broken.details.responsibility.nodeName = invalid
    assert.throws(() => read(broken))
  }
  const invalidProject = view(); invalidProject.details.source.projects[0].name = '项'.repeat(129)
  assert.throws(() => read(invalidProject))
})
test('跨单、错轮、错法人、未知版本和不完整项目或责任集合均拒绝', () => {
  for (const mutate of [v => { v.reportId = id(99) }, v => { v.applicationId = id(99) }, v => { v.roundNo++ },
    v => { v.details.ruleVersion = 2 }, v => { v.details.precheckVersion = 2 }, v => { v.details.applicationVersion = 1.5 },
    v => { v.details.source.projects[1].legalEntityId = id(99) }, v => { v.details.source.projects[0].ownerSubject = null },
    v => { v.details.source.projects.push(clone(v.details.source.projects[0])) }, v => { v.details.source.projects.pop() },
    v => { v.details.responsibility.originalSubjects = ['alice'] }, v => { v.details.responsibility.candidateSubjects = ['manager'] },
    v => { v.details.responsibility.candidateSubjects.push('bob') }, v => { v.details.responsibility.escalation.originalSubject = 'bob' },
    v => { v.details.responsibility.escalation.replacementSubject = 'alice' }, v => { delete v.details.responsibility.escalation },
    v => { v.details.responsibility.stage = 'BUSINESS' }, v => { v.details.initiator.legalEntityId = id(99) },
    v => { v.details.unrelatedReport = id(99) }, v => { v.details.responsibility.escalation.directoryRevision++ }]) {
    const broken = view(); mutate(broken); assert.throws(() => read(broken))
  }
})
test('同人多项目保留全部行，上溯后合并到另一已有负责人只保留一份责任', () => {
  const same = view(); same.details.source.projects[0].ownerSubject = 'bob'; same.details.responsibility.originalSubjects = ['bob']
  same.details.responsibility.candidateSubjects = ['bob']; delete same.details.responsibility.escalation
  assert.equal(read(same).details.source.projects.length, 2)
  const escalated = view(); escalated.details.responsibility.escalation.replacementSubject = 'bob'; escalated.details.responsibility.candidateSubjects = ['bob']
  assert.equal(read(escalated).details.responsibility.candidateSubjects.length, 1)
})
test('预检来源必须覆盖同法人且拒绝未知项目属性', () => {
  assert.deepEqual(model.readProjectOwners(source(), id(3)), source())
  assert.throws(() => model.readProjectOwners(source(), id(99)))
  const bad = source(); bad.projects[0].approved = true; assert.throws(() => model.readProjectOwners(bad, id(3)))
})
test('实际只读组件按身份、单据、轮次和版本清理迟到结果', async () => {
  const pending = []; api.expenseProjectApproval = (report, round, signal) => new Promise(resolve => pending.push({ report, round, signal, resolve }))
  const panel = mount(Panel, { reportId: id(1), applicationId: id(2), roundNo: 1, scopeKey: 'demo:alice', version: 2 })
  panel.props.roundNo = 2; await nextTick(); assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve(view(2)); await settle(); assert.equal(panel.state.query.value.roundNo, 2)
  pending[0].resolve(view()); await settle(); assert.equal(panel.state.query.value.roundNo, 2)
  panel.props.scopeKey = ''; await nextTick(); assert.equal(panel.state.query.value, null); assert.equal(pending.length, 2)
  panel.props.scopeKey = 'demo:manager'; await nextTick(); panel.close(); assert.equal(pending[2].signal.aborted, true)
  pending[2].resolve(view(2)); await settle(); assert.equal(panel.state.query.value, null)
})
test('权限失效或损坏的正文清除旧内容，明确刷新后恢复', async () => {
  let next = view(); api.expenseProjectApproval = async () => { if (next instanceof Error) throw next; return next }
  const panel = mount(Panel, { reportId: id(1), applicationId: id(2), roundNo: 1, scopeKey: 'demo:alice', version: 2 }); await settle()
  next = Object.assign(new Error('Forbidden'), { status: 403 }); await panel.state.load()
  assert.equal(panel.state.query.value, null); assert.match(panel.state.query.error, /无法读取/)
  next = { ...view(), roundNo: 8 }; await panel.state.load(); assert.equal(panel.state.query.value, null); assert.match(panel.state.query.error, /不完整|不符/)
  next = view(); await panel.state.load(); assert.equal(panel.state.query.value.status, 'RECORDED'); panel.close()
})
test('实际来源组件同时显示两个项目原负责人和上溯责任，预检不伪造上溯', async () => {
  const value = view(); const html = await renderToString(createSSRApp(Facts, { source: value.details.source, responsibility: value.details.responsibility }))
  for (const text of ['合成项目 A', '合成项目 B', 'alice', 'bob', 'manager', 'original-catalog-v1', '本轮审批责任人']) assert.ok(html.includes(text), text)
  const precheck = await renderToString(createSSRApp(Facts, { source: source() })); assert.ok(!precheck.includes('manager'))
})
test('设计器允许专用项目来源并固定 ALL，不依赖普通目录读取', async () => {
  api.definitionAssignees = async () => []
  const schema = { schemaVersion: 2, fields: [{ key: 'expenseDetails', type: 'TEXT', required: true, sensitive: true }] }
  const panel = mount(Assignee, { modelValue: 'role:MANAGER', approvalMode: 'ANY', formSchema: schema, scopeKey: '', disabled: false })
  panel.state.chooseSource({ target: { value: 'project' } });
  assert.ok(panel.events.some(([key, rule]) => key === 'update:modelValue' && rule === 'expense:projectOwners'))
  assert.ok(panel.events.some(([key, mode]) => key === 'policy' && mode === 'ALL')); panel.close()
  const html = await renderToString(createSSRApp(AssigneeRendered, { modelValue: 'expense:projectOwners', approvalMode: 'ALL', formSchema: schema, scopeKey: '', disabled: false }))
  assert.match(html, /固定|本轮项目/); assert.match(html, /hasProjectAllocation/); assert.ok(!html.includes('已有配置当前匹配不到审批人'))
  const stage = await renderToString(createSSRApp(Stage, { modelValue: 'PROJECT_REVIEW', formSchema: schema, disabled: false }))
  assert.match(stage, /项目负责人全员会签/); assert.match(stage, /不能加减签/)
})
test('原轮次请求明确带轮次、禁缓存和取消信号，不申请写入幂等键', async () => {
  globalThis.localStorage = { getItem: () => 'test-token' }; const calls = []
  globalThis.fetch = async (url, options) => { calls.push({ url, ...options }); return Response.json(view()) }
  const abort = new AbortController(); await api.expenseProjectApproval(id(1), 1, abort.signal)
  assert.ok(calls[0].url.endsWith(`/expense-reports/${id(1)}/project-approval?roundNo=1`)); assert.equal(calls[0].cache, 'no-store')
  assert.equal(calls[0].signal, abort.signal); assert.equal(calls[0].headers.has('Idempotency-Key'), false)
})
