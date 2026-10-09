import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, reactive, computed, createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
const { createForm } = await import(process.env.AGENTFLOW_TEST_APPLICATION_FORM_EXPERIENCE)
const { UnsavedConfirmation } = await import(process.env.AGENTFLOW_TEST_CONFIRMATION)
const { action } = await import(process.env.AGENTFLOW_TEST_ACTION_FOCUS)
const { render: formRender } = await import(process.env.AGENTFLOW_TEST_APPLICATION_FORM_TEMPLATE)
const { createWorkflow } = await import(process.env.AGENTFLOW_TEST_COUNTERSIGN_WORKSPACE)

function formFixture() {
  const confirmation = reactive(new UnsavedConfirmation()), loads = []
  const state = {
    busy: ref(false), writesBlocked: ref(false), actorScope: ref('demo:alice'), newApplicationOpen: ref(false), createdApplication: ref(null),
    applicationTitle: ref(''), applicationBusinessNo: ref(''), applicationPayload: ref({}), applicationAmount: ref(''), applicationDescription: ref(''),
    applicationFieldErrors: ref({}), applicationFormError: ref(''), initiatorAppointmentId: ref(''), requestedApplicationDefinition: ref(''),
    applicationSelection: reactive({ definition: null, loading: false, error: '', async load(scope, id) { loads.push([scope, id]); this.definition = { id, key: id, version: 1 }; return this.definition }, clear() { this.definition = null } }),
    applicationFormSchema: ref({ schemaVersion: 1, fields: [] }), applicationRequirements: { submissionError: () => '' },
    confirmation, confirmationOpen: computed(() => !!confirmation.active), confirmationReturnFocus: ref(null), applicationDialog: ref(null),
    api: {}, page: ref('workbench'), templateRefresh: ref(0), refreshWorkspace: async () => {}, notice: ref(''), recordApplicationId: ref(''),
    errorMessage: error => error.message, statusLabel: value => value, viewActive: true, nextTick: async () => {}, sessionExpired: ref(false),
    authOptions: ref({ mode: 'OIDC', loginUrl: '/api/v1/auth/oidc/authorize/enterprise' }), actor: ref({ tenantId: 'demo', userId: 'alice' }), pendingWrites: ref([]), bindAuthenticationActor() {}
  }
  return { state, loads, form: createForm(state) }
}
async function started() { const p = formFixture(); await p.form.prepareApplication('first'); return p }
globalThis.document ??= { activeElement: null }

test('切换流程前确认未保存输入；取消保持原流程与全部输入', async () => {
  const p = await started(); p.state.applicationPayload.value = { reason: '原说明' }
  const pending = p.form.selectApplicationDefinition('second')
  assert.ok(p.state.confirmation.active, '切换不能静默清空业务字段')
  assert.equal(p.state.confirmation.active.title, '有未保存的申请内容')
  p.state.confirmation.answer(p.state.confirmation.active.id, false); await pending
  assert.equal(p.state.applicationSelection.definition.id, 'first')
  assert.deepEqual(p.state.applicationPayload.value, { reason: '原说明' }); assert.equal(p.loads.length, 1)
})

test('关闭申请需确认，创建成功后的明确草稿入口打开原申请', async () => {
  const p = await started(); p.state.applicationTitle.value = '未保存标题'
  assert.equal(typeof p.form.closeApplicationForm, 'function', '关闭按钮需要受保护的统一处理函数')
  const closing = p.form.closeApplicationForm()
  assert.ok(p.state.confirmation.active); p.state.confirmation.answer(p.state.confirmation.active.id, false); await closing
  assert.equal(p.state.newApplicationOpen.value, true)
  p.state.createdApplication.value = { id: 'saved-draft', businessNo: 'D-1', payload: {}, version: 1 }
  assert.equal(typeof p.form.openCreatedApplication, 'function', '已保存草稿必须有可操作的直接入口')
  await p.form.openCreatedApplication(); assert.equal(p.state.recordApplicationId.value, 'saved-draft')
  assert.equal(p.state.newApplicationOpen.value, false)
})

test('领取成功保留原任务并重读最新详情，不要求用户再次查找待办', async () => {
  let reread = 0
  const state = { activeTask: ref({ taskId: 'task' }), activeApplication: ref({ id: 'app', version: 1 }), busy: ref(false), writesBlocked: ref(false), expenseTaskBusy: ref(false), actorScope: ref('demo:alice'), page: ref('workbench'), api: { taskAction: async () => ({ applicationStatus: 'IN_REVIEW' }) }, refreshWorkspace: async () => {}, notice: ref(''), taskActionLabels: { CLAIM: '领取' }, statusLabel: s => s, errorMessage: e => e.message, nextTick: async () => {}, operationStatus: ref(null), selectTask: async task => { assert.equal(state.busy.value, false); reread++; state.activeTask.value = { ...task, version: 2, assignee: 'alice' }; state.activeApplication.value = { id: 'app', version: 2 } }, detailError: ref('') }
  await action(state)({ action: 'CLAIM' })
  assert.equal(reread, 1); assert.equal(state.activeTask.value.taskId, 'task'); assert.equal(state.activeApplication.value.version, 2)
})

test('确认切换只清空业务字段，标题与单号仍保留；同一流程不清空输入', async () => {
  const p = await started(); p.state.applicationTitle.value = '调休申请'; p.state.applicationBusinessNo.value = 'LEAVE-1'
  p.state.applicationPayload.value = { reason: '家事' }; p.state.initiatorAppointmentId.value = 'position'
  await p.form.selectApplicationDefinition('first'); assert.deepEqual(p.state.applicationPayload.value, { reason: '家事' })
  const changing = p.form.selectApplicationDefinition('second'); p.state.confirmation.answer(p.state.confirmation.active.id, true); await changing
  assert.equal(p.state.applicationSelection.definition.id, 'second'); assert.deepEqual(p.state.applicationPayload.value, {})
  assert.equal(p.state.initiatorAppointmentId.value, ''); assert.equal(p.state.applicationTitle.value, '调休申请'); assert.equal(p.state.applicationBusinessNo.value, 'LEAVE-1')
  const closing = p.form.closeApplicationForm(); assert.ok(p.state.confirmation.active, '保留下来的标题仍是未保存输入')
  p.state.confirmation.answer(p.state.confirmation.active.id, true); await closing; assert.equal(p.state.newApplicationOpen.value, false)
})

test('未编辑的申请可直接关闭；重复发起不能覆盖正在填写的内容', async () => {
  const untouched = await started(); await untouched.form.closeApplicationForm(); assert.equal(untouched.state.newApplicationOpen.value, false); assert.equal(untouched.state.confirmation.active, null)
  const p = await started(); p.state.applicationTitle.value = '保留标题'; await p.form.prepareApplication('second')
  assert.equal(p.state.applicationTitle.value, '保留标题'); assert.equal(p.loads.length, 1)
})

test('关闭、切换与草稿入口在写入或未知结果期间全部锁定；确认后再次核对身份和锁', async () => {
  for (const locked of ['busy', 'writesBlocked']) {
    const p = await started(); p.state.applicationPayload.value = { reason: '保留' }; p.state[locked].value = true
    await p.form.closeApplicationForm(); await p.form.selectApplicationDefinition('second')
    p.state.createdApplication.value = { id: 'draft' }; await p.form.openCreatedApplication()
    assert.equal(p.state.newApplicationOpen.value, true); assert.equal(p.loads.length, 1); assert.equal(p.state.recordApplicationId.value, '')
    assert.equal(p.state.confirmation.active, null)
  }
  for (const changed of ['actorScope', 'busy', 'writesBlocked']) {
    const p = await started(); p.state.applicationTitle.value = '原输入'; const closing = p.form.closeApplicationForm()
    p.state[changed].value = changed === 'actorScope' ? 'demo:bob' : true
    p.state.confirmation.answer(p.state.confirmation.active.id, true); await closing
    assert.equal(p.state.newApplicationOpen.value, true); assert.equal(p.state.applicationTitle.value, '原输入')
  }
})

test('创建成功提交失败后重试只提交原草稿，打开草稿也沿用原 ID', async () => {
  const p = await started(), submissions = []; let creates = 0
  p.state.applicationTitle.value = '申请'; p.state.api.createApplication = async input => { creates++; return { ...input, id: 'saved', version: 1, payload: {}, formSchema: { schemaVersion: 1, fields: [] } } }
  p.state.api.submitApplication = async id => { submissions.push(id); throw new Error('岗位已失效') }
  await p.form.createAndSubmitApplication(); assert.equal(creates, 1); assert.equal(p.state.createdApplication.value.id, 'saved')
  await p.form.createAndSubmitApplication(); assert.equal(creates, 1); assert.deepEqual(submissions, ['saved', 'saved'])
  assert.match(p.state.applicationFormError.value, /草稿已保留/)
  await p.form.openCreatedApplication(); assert.equal(p.state.recordApplicationId.value, 'saved')
})

test('创建结果未知时不再次创建，迟到的旧身份创建回执不污染新账号', async () => {
  const p = await started(); let creates = 0; p.state.applicationTitle.value = '申请'
  p.state.api.createApplication = async () => { creates++; p.state.writesBlocked.value = true; throw new Error('结果未知') }
  await p.form.createAndSubmitApplication(); await p.form.createAndSubmitApplication(); await p.form.closeApplicationForm()
  assert.equal(creates, 1); assert.equal(p.state.newApplicationOpen.value, true); assert.equal(p.state.createdApplication.value, null)
  const late = await started(); late.state.applicationTitle.value = '原身份'; let finish, submissions = 0
  late.state.api.createApplication = () => new Promise(resolve => { finish = resolve }); late.state.api.submitApplication = async () => { submissions++ }
  const saving = late.form.createAndSubmitApplication(); late.state.actorScope.value = 'demo:bob'; late.state.newApplicationOpen.value = false
  finish({ id: 'old-draft', version: 1 }); await saving; assert.equal(late.state.createdApplication.value, null); assert.equal(submissions, 0)
})

test('真实申请模板的遮罩、关闭按钮和草稿入口全部连接受保护函数', async () => {
  const p = await started(); p.state.createdApplication.value = { id: 'saved', businessNo: 'D-1', processKey: 'flow', definitionVersion: 1 }
  let tree
  const app = createSSRApp({ setup: () => ({ ...p.state, ...p.form, applicationDefinitionId: 'first', visiblePendingWrites: [], recoveryError: '', recoverOperation() {}, loadApplicationRequirements() {} }), render() { tree = formRender(this, []); return tree } })
  for (const name of ['DefinitionPicker', 'RequestRecovery', 'FormFields', 'InitiatorRequirementNotice', 'InitiatorAppointmentPicker']) app.component(name, { render: () => null })
  const markup = await renderToString(app), nodes = node => node && typeof node === 'object' ? [node, ...(Array.isArray(node.children) ? node.children.flatMap(nodes) : [])] : []
  assert.match(markup, /打开已保存草稿/)
  const all = nodes(tree), close = all.find(node => node.props?.['aria-label'] === '关闭申请表单'), draft = all.find(node => node.type === 'button' && node.children === '打开已保存草稿')
  p.state.writesBlocked.value = true; await close.props.onClick(); assert.equal(p.state.newApplicationOpen.value, true)
  p.state.writesBlocked.value = false; await draft.props.onClick(); assert.equal(p.state.recordApplicationId.value, 'saved')
  p.state.newApplicationOpen.value = true; p.state.createdApplication.value = { id: 'saved' }
  const root = {}; await tree.props.onClick({ target: root, currentTarget: root }); assert.equal(p.state.newApplicationOpen.value, false)
})

function workflowFixture() {
  const state = { activeTask: ref(null), activeApplication: ref(null), busy: ref(false), writesBlocked: ref(false), expenseTaskBusy: ref(false), actorScope: ref('demo:alice'), api: { task: async id => ({ taskId: id, applicationId: 'app', version: 2 }), application: async () => ({ id: 'app', version: 2 }) }, refreshWorkspace: async () => {}, notice: ref(''), errorMessage: e => e.message, detailLoading: ref(false), detailError: ref(''), taskTab: ref('detail'), nextTick: async () => {}, page: ref('assist'), taskDetailPanel: ref(null) }
  return { state, workflow: createWorkflow(state) }
}
test('助理入口复核任务和申请权限，失败与旧身份不能加载旧申请', async () => {
  const p = workflowFixture(); await p.workflow.openTaskAssistant({ taskId: 't' }); assert.equal(p.state.page.value, 'workbench'); assert.equal(p.state.taskTab.value, 'assist')
  const failed = workflowFixture(); failed.state.api.application = async () => { throw new Error('权限不可用') }; await failed.workflow.openTaskAssistant({ taskId: 't' }); assert.equal(failed.state.activeApplication.value, null); assert.match(failed.state.detailError.value, /权限不可用/)
  const late = workflowFixture(); let finish; late.state.api.task = () => new Promise(resolve => { finish = resolve })
  const opening = late.workflow.openTaskAssistant({ taskId: 't' }); late.state.actorScope.value = 'demo:bob'; late.state.taskTab.value = 'detail'; finish({ taskId: 't', applicationId: 'app' }); await opening
  assert.equal(late.state.taskTab.value, 'detail'); assert.equal(late.state.activeApplication.value, null)
})

test('申请弹窗内的 401 恢复入口可达，复用当前会话恢复且不清除原请求', async () => {
  const p = await started(); p.state.sessionExpired.value = true; p.state.writesBlocked.value = true; p.state.applicationTitle.value = '保留标题'
  p.state.pendingWrites.value = [{ id: 'original', sending: false }]
  p.state.api.authOptions = async () => p.state.authOptions.value; p.state.api.me = async () => ({ actor: { ...p.state.actor.value } })
  let tree
  const app = createSSRApp({ setup: () => ({ ...p.state, ...p.form, applicationDefinitionId: 'first', visiblePendingWrites: p.state.pendingWrites, recoveryError: '', recoverOperation() {}, loadApplicationRequirements() {} }), render() { tree = formRender(this, []); return tree } })
  for (const name of ['DefinitionPicker', 'RequestRecovery', 'FormFields', 'InitiatorRequirementNotice', 'InitiatorAppointmentPicker']) app.component(name, { render: () => null })
  const markup = await renderToString(app)
  assert.match(markup, /恢复当前会话/); assert.match(markup, /重新登录/)
  const nodes = node => node && typeof node === 'object' ? [node, ...(Array.isArray(node.children) ? node.children.flatMap(nodes) : [])] : []
  const restore = nodes(tree).find(node => node.type === 'button' && node.children === '恢复当前会话')
  assert.equal(restore.props.disabled, false); await restore.props.onClick()
  assert.equal(p.state.sessionExpired.value, false); assert.equal(p.state.applicationTitle.value, '保留标题'); assert.equal(p.state.newApplicationOpen.value, true)
  assert.deepEqual(p.state.pendingWrites.value, [{ id: 'original', sending: false }])
})

test('助理读取完成不覆盖等待期间主动切换的页签，也不抢占申请弹窗焦点', async () => {
  const p = workflowFixture(); let finish, focused = 0
  p.state.newApplicationOpen = ref(false); p.state.recordApplicationId = ref(''); p.state.confirmationOpen = ref(false)
  p.state.taskDetailPanel.value = { scrollIntoView() {}, focus() { focused++ } }; p.state.api.application = () => new Promise(resolve => { finish = resolve })
  p.workflow = createWorkflow(p.state)
  const opening = p.workflow.openTaskAssistant({ taskId: 't' }); await Promise.resolve(); await Promise.resolve()
  p.state.taskTab.value = 'timeline'; p.state.newApplicationOpen.value = true
  finish({ id: 'app', version: 2 }); await opening
  assert.equal(p.state.taskTab.value, 'timeline'); assert.equal(focused, 0)
})

test('申请 Escape 使用同一确认；处理中不能用键盘关闭', async () => {
  const p = await started(); let prevented = 0, stopped = 0
  const event = { key: 'Escape', preventDefault() { prevented++ }, stopPropagation() { stopped++ } }
  p.state.applicationTitle.value = '保留'; p.form.handleApplicationKeydown(event)
  assert.ok(p.state.confirmation.active); p.state.confirmation.answer(p.state.confirmation.active.id, false); await Promise.resolve()
  p.state.writesBlocked.value = true; p.form.handleApplicationKeydown(event); await Promise.resolve()
  assert.equal(p.state.newApplicationOpen.value, true); assert.equal(p.state.confirmation.active, null); assert.equal(prevented, 2); assert.equal(stopped, 2)
})

test('会话恢复在发送中或忙碌时不读取身份，账号不匹配保留原上下文', async () => {
  for (const locked of ['busy', 'sending']) {
    const p = await started(); let reads = 0; p.state.sessionExpired.value = true
    p.state.api.authOptions = async () => { reads++; return p.state.authOptions.value }; p.state.api.me = async () => ({ actor: p.state.actor.value })
    if (locked === 'busy') p.state.busy.value = true
    else p.state.pendingWrites.value = [{ id: 'original', sending: true }]
    assert.equal(await p.form.restoreEnterpriseSession(), false); assert.equal(reads, 0); assert.equal(p.state.sessionExpired.value, true)
  }
  const p = await started(); p.state.sessionExpired.value = true; p.state.applicationTitle.value = '原账号内容'
  p.state.api.authOptions = async () => p.state.authOptions.value; p.state.api.me = async () => ({ actor: { tenantId: 'demo', userId: 'bob' } })
  assert.equal(await p.form.restoreEnterpriseSession(), false); assert.equal(p.state.actor.value.userId, 'alice')
  assert.equal(p.state.applicationTitle.value, '原账号内容'); assert.equal(p.state.busy.value, false)
})
