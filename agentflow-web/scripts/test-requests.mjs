import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import ts from 'typescript'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'

// 测试产物只写入约定的临时目录，不改变应用构建配置。
const root = fileURLToPath(new URL('../', import.meta.url))
const output = mkdtempSync('/fyoung/tmp/agentflow-web-requests-')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}')

// 个人设置使用实际组件，覆盖原请求恢复和切换身份时的迟到响应。
const preferenceDescriptor = parse(readFileSync(resolve(root, 'src/components/NotificationPreferencesPanel.vue'), 'utf8'), { filename: 'NotificationPreferencesPanel.vue' }).descriptor
for (const [name, inlineTemplate] of [['NotificationPreferencesPanel', false], ['NotificationPreferencesRendered', true]]) {
  const source = compileScript(preferenceDescriptor, { id: name, inlineTemplate }).content
    .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(api|notificationPreferences)'/g, "'./$1.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
}
process.env.AGENTFLOW_TEST_NOTIFICATION_PREFERENCES = resolve(output, 'notificationPreferences.js')
process.env.AGENTFLOW_TEST_NOTIFICATION_PREFERENCES_PANEL = resolve(output, 'NotificationPreferencesPanel.js')
process.env.AGENTFLOW_TEST_NOTIFICATION_PREFERENCES_RENDERED = resolve(output, 'NotificationPreferencesRendered.js')

// 投递的真实组件验证未知结果确认、分页和原请求恢复，不模拟按钮背后的状态规则。
const deliveryDescriptor = parse(readFileSync(resolve(root, 'src/components/NotificationDeliveriesPanel.vue'), 'utf8'), { filename: 'NotificationDeliveriesPanel.vue' }).descriptor
for (const [name, inlineTemplate] of [['NotificationDeliveryPanel', false], ['NotificationDeliveryRendered', true]]) {
  const source = compileScript(deliveryDescriptor, { id: name, inlineTemplate }).content
    .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(api|notificationDeliveries)'/g, "'./$1.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
}
process.env.AGENTFLOW_TEST_NOTIFICATION_DELIVERIES = resolve(output, 'notificationDeliveries.js')
process.env.AGENTFLOW_TEST_NOTIFICATION_DELIVERY_PANEL = resolve(output, 'NotificationDeliveryPanel.js')
process.env.AGENTFLOW_TEST_NOTIFICATION_DELIVERY_RENDERED = resolve(output, 'NotificationDeliveryRendered.js')

// 升级配置使用实际输入与身份切换逻辑，不用序列化测试替代组件行为。
const escalationDescriptor = parse(readFileSync(resolve(root, 'src/components/DefinitionEscalation.vue'), 'utf8'), { filename: 'DefinitionEscalation.vue' }).descriptor
const escalationComponent = compileScript(escalationDescriptor, { id: 'definition-escalation' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace(/'\.\.\/(api|definitionAssignees)'/g, "'./$1.js'")
writeFileSync(resolve(output, 'DefinitionEscalation.js'), ts.transpileModule(escalationComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_ESCALATION_PANEL = resolve(output, 'DefinitionEscalation.js')

// 编译真实评论组件，验证提醒选择、草稿及账号隔离。
const commentDescriptor = parse(readFileSync(resolve(root, 'src/components/ApplicationComments.vue'), 'utf8'), { filename: 'ApplicationComments.vue' }).descriptor
for (const [name, inlineTemplate] of [['CommentPanel', false], ['CommentRendered', true]]) {
  const source = compileScript(commentDescriptor, { id: name, inlineTemplate }).content
    .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(api|applicationComments|commentMentions)'/g, "'./$1.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
}
process.env.AGENTFLOW_TEST_COMMENT_PANEL = resolve(output, 'CommentPanel.js')
process.env.AGENTFLOW_TEST_COMMENT_RENDERED = resolve(output, 'CommentRendered.js')
process.env.AGENTFLOW_TEST_COMMENT_MENTIONS = resolve(output, 'commentMentions.js')

// 批次先核对再确认；同时编译真实模板，验证部分成功和未知结果的停发提示。
const taskBatchDescriptor = parse(readFileSync(resolve(root, 'src/components/TaskBatchPanel.vue'), 'utf8'), { filename: 'TaskBatchPanel.vue' }).descriptor
for (const [name, inlineTemplate] of [['TaskBatchPanel', false], ['TaskBatchRendered', true]]) {
  const source = compileScript(taskBatchDescriptor, { id: name, inlineTemplate }).content
    .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("'../api'", "'./api.js'").replaceAll("'../taskBatch'", "'./taskBatch.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
}
process.env.AGENTFLOW_TEST_TASK_BATCH = resolve(output, 'taskBatch.js')
process.env.AGENTFLOW_TEST_TASK_BATCH_PANEL = resolve(output, 'TaskBatchPanel.js')
process.env.AGENTFLOW_TEST_TASK_BATCH_RENDERED = resolve(output, 'TaskBatchRendered.js')

// 财务状态提示必须使用真实模板验证，避免已消费授权继续提示重新准备。
const partialTemplate = parse(readFileSync(resolve(root, 'src/components/ExpensePartialAdjustment.vue'), 'utf8')).descriptor.template.content
const partialRender = compileTemplate({ source: partialTemplate, id: 'partial-render', filename: 'ExpensePartialAdjustment.vue', compilerOptions: { expressionPlugins: ['typescript'] } })
if (partialRender.errors.length) throw new Error(String(partialRender.errors))
writeFileSync(resolve(output, 'expensePartialRender.js'), ts.transpileModule(partialRender.code.replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_EXPENSE_PARTIAL_RENDER = resolve(output, 'expensePartialRender.js')
// 编译实际任务面板的 setup，验证按钮行为，避免只测试请求构造而遗漏直接提交。
const { descriptor } = parse(readFileSync(resolve(root, 'src/components/TaskActions.vue'), 'utf8'), { filename: 'TaskActions.vue' })
const component = compileScript(descriptor, { id: 'task-actions-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../taskActions'", "'./taskActions.js'").replaceAll("'../approvalPolicy'", "'./approvalPolicy.js'")
  .replace("import CountersignMembers from './CountersignMembers.vue'", 'const CountersignMembers = {}')
writeFileSync(resolve(output, 'TaskActionPanel.js'), ts.transpileModule(component, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
// 审批方式既校验真实编辑事件，也渲染实际办理提示与加减签入口。
const assigneeDescriptor = parse(readFileSync(resolve(root, 'src/components/DefinitionAssignee.vue'), 'utf8'), { filename: 'DefinitionAssignee.vue' }).descriptor
const assigneeComponent = compileScript(assigneeDescriptor, { id: 'definition-assignee-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../definitionAssignees'", "'./definitionAssignees.js'").replaceAll("'../approvalPolicy'", "'./approvalPolicy.js'")
writeFileSync(resolve(output, 'DefinitionAssignee.js'), ts.transpileModule(assigneeComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
const taskRender = compileTemplate({ source: descriptor.template.content, id: 'task-policy-render', filename: 'TaskActions.vue' })
if (taskRender.errors.length) throw new Error(String(taskRender.errors))
writeFileSync(resolve(output, 'taskPolicyRender.js'), taskRender.code.replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`))
process.env.AGENTFLOW_TEST_ASSIGNEE_PANEL = resolve(output, 'DefinitionAssignee.js')
process.env.AGENTFLOW_TEST_POLICY_RENDER = resolve(output, 'taskPolicyRender.js')
process.env.AGENTFLOW_TEST_APPROVAL_POLICY = resolve(output, 'approvalPolicy.js')
// 事件页面使用实际 setup 验证选择、确认、取消和身份切换，不以请求构造代替交互。
for (const name of ['DefinitionEventWait', 'EventWaitPanel', 'EventContracts', 'EventInbox']) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${name}.vue`), 'utf8'), { filename: `${name}.vue` }).descriptor
  const component = compileScript(descriptor, { id: `event-${name}` }).content
    .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("'../api'", "'./api.js'").replaceAll("'../events'", "'./events.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(component, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_EVENT_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_EVENTS = resolve(output, 'events.js')
// 子版本选择使用实际配置组件，覆盖迟到读取、明确替换和输入权限约束。
const subprocessDescriptor = parse(readFileSync(resolve(root, 'src/components/DefinitionSubprocess.vue'), 'utf8'), { filename: 'DefinitionSubprocess.vue' }).descriptor
const subprocessComponent = compileScript(subprocessDescriptor, { id: 'subprocess-designer-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace(/'\.\.\/(api|definitionSelection|formSchema|subprocessDesigner|initiatorRequirements)'/g, "'./$1.js'")
  .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = {}')
writeFileSync(resolve(output, 'DefinitionSubprocess.js'), ts.transpileModule(subprocessComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_SUBPROCESS_PANEL = resolve(output, 'DefinitionSubprocess.js')
process.env.AGENTFLOW_TEST_SUBPROCESS_DESIGNER = resolve(output, 'subprocessDesigner.js')
const relationsDescriptor = parse(readFileSync(resolve(root, 'src/components/SubprocessRelations.vue'), 'utf8'), { filename: 'SubprocessRelations.vue' }).descriptor
const relationsComponent = compileScript(relationsDescriptor, { id: 'subprocess-relations-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace(/'\.\.\/(api|subprocessRelations)'/g, "'./$1.js'")
writeFileSync(resolve(output, 'SubprocessRelationsPanel.js'), ts.transpileModule(relationsComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_RELATIONS_PANEL = resolve(output, 'SubprocessRelationsPanel.js')
process.env.AGENTFLOW_TEST_SUBPROCESS_RELATIONS = resolve(output, 'subprocessRelations.js')
const relationsRender = compileTemplate({ source: relationsDescriptor.template.content, id: 'relations-render', filename: 'SubprocessRelations.vue' })
if (relationsRender.errors.length) throw new Error(String(relationsRender.errors))
writeFileSync(resolve(output, 'relationsRender.js'), relationsRender.code.replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`))
process.env.AGENTFLOW_TEST_RELATIONS_RENDER = resolve(output, 'relationsRender.js')
process.env.AGENTFLOW_TEST_INITIATOR_REQUIREMENTS = resolve(output, 'initiatorRequirements.js')
// 编译实际等待配置、恢复面板及申请详情中的事件绑定。
for (const [name, key] of [['DefinitionTimerWait', 'DEFINITION'], ['TimerWaitPanel', 'PANEL']]) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${name}.vue`), 'utf8'), { filename: `${name}.vue` }).descriptor
  const component = compileScript(descriptor, { id: `timer-${key}` }).content
    .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("'../api'", "'./api.js'").replaceAll("'../timerWaits'", "'./timerWaits.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(component, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
  process.env[`AGENTFLOW_TEST_TIMER_${key}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_TIMER_WAITS = resolve(output, 'timerWaits.js')
const recordSource = readFileSync(resolve(root, 'src/components/ApplicationRecord.vue'), 'utf8')
const diagramElement = recordSource.match(/<RoundDiagram\s[\s\S]*?\/>/)?.[0].replace(/ v-else-if="[^"]*"/, '')
if (!diagramElement) throw new Error('Missing actual application diagram binding')
const diagramBinding = compileTemplate({ source: diagramElement, id: 'timer-record-binding', filename: 'ApplicationRecord.vue' })
if (diagramBinding.errors.length) throw new Error(String(diagramBinding.errors))
writeFileSync(resolve(output, 'timerRecordBinding.js'), diagramBinding.code.replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`))
process.env.AGENTFLOW_TEST_TIMER_RECORD = resolve(output, 'timerRecordBinding.js')

// 实例运维必须经过真实确认面板，并将状态与繁忙事件交回申请详情。
const instanceDescriptor = parse(readFileSync(resolve(root, 'src/components/InstanceControlPanel.vue'), 'utf8'), { filename: 'InstanceControlPanel.vue' }).descriptor
const instanceComponent = compileScript(instanceDescriptor, { id: 'instance-control-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../instanceControl'", "'./instanceControl.js'")
writeFileSync(resolve(output, 'InstanceControlPanel.js'), ts.transpileModule(instanceComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_INSTANCE_PANEL = resolve(output, 'InstanceControlPanel.js')
process.env.AGENTFLOW_TEST_INSTANCE_CONTROL = resolve(output, 'instanceControl.js')
// 挂载完整模板，覆盖确认表单切换时按钮引用的卸载与重新创建。
const instanceRendered = compileScript(instanceDescriptor, { id: 'instance-control-rendered-test', inlineTemplate: true }).content
  .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../instanceControl'", "'./instanceControl.js'")
writeFileSync(resolve(output, 'InstanceControlRendered.js'), ts.transpileModule(instanceRendered, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_INSTANCE_RENDERED = resolve(output, 'InstanceControlRendered.js')
const instanceElement = recordSource.match(/<InstanceControlPanel\s[\s\S]*?\/>/)?.[0]
if (!instanceElement) throw new Error('Missing actual instance control binding')
const instanceBinding = compileTemplate({ source: instanceElement, id: 'instance-record-binding', filename: 'ApplicationRecord.vue' })
if (instanceBinding.errors.length) throw new Error(String(instanceBinding.errors))
writeFileSync(resolve(output, 'instanceRecordBinding.js'), instanceBinding.code.replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`))
process.env.AGENTFLOW_TEST_INSTANCE_RECORD = resolve(output, 'instanceRecordBinding.js')

// 编译真实会签办理面板，校验确认、取消和任务版本切换。
const countersignDescriptor = parse(readFileSync(resolve(root, 'src/components/CountersignMembers.vue'), 'utf8'), { filename: 'CountersignMembers.vue' }).descriptor
const countersignComponent = compileScript(countersignDescriptor, { id: 'countersign-members-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../countersignMembership'", "'./countersignMembership.js'")
writeFileSync(resolve(output, 'CountersignMembers.js'), ts.transpileModule(countersignComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_COUNTERSIGN_PANEL = resolve(output, 'CountersignMembers.js')
process.env.AGENTFLOW_TEST_COUNTERSIGN = resolve(output, 'countersignMembership.js')
// 运行父页面真实函数，覆盖写入完成时 busy 与详情重读的实际衔接。
const appScript = parse(readFileSync(resolve(root, 'src/App.vue'), 'utf8')).descriptor.scriptSetup.content
const appSyntax = ts.createSourceFile('App.ts', appScript, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const recoverFunction = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'recoverOperation')
if (!recoverFunction) throw new Error('Missing actual operation recovery')
const timerRecoveryDependencies = 'pendingWrites, draftScope, confirmReplaceDefinition, busy, recoveryError, writeRequests, notice, createdApplication, newApplicationOpen, recordApplicationId, recordRefresh, templateRefresh, statusLabel, refreshWorkspace, nextTick, workspace, errorMessage, activeTask, clearTaskSelection'
writeFileSync(resolve(output, 'timerRecovery.js'), ts.transpileModule(`export function createRecovery(deps) { const { ${timerRecoveryDependencies} } = deps; let pendingDraftCheckpoint = null; ${recoverFunction.getText(appSyntax)}; return recoverOperation; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_TIMER_RECOVERY = resolve(output, 'timerRecovery.js')

const recordChanged = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'applicationRecordChanged')
if (!recordChanged) throw new Error('Missing actual application change handler')
writeFileSync(resolve(output, 'recordChanged.js'), ts.transpileModule(`export function createRecordChanged(deps) { const { activeTask, recordApplicationId, clearTaskSelection, refreshPage } = deps; ${recordChanged.getText(appSyntax)}; return applicationRecordChanged; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_RECORD_CHANGED = resolve(output, 'recordChanged.js')

const workflowFunctions = ['performMembershipChange', 'selectTask', 'clearTaskSelection'].map(name => {
  const found = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)
  if (!found) throw new Error(`Missing actual workflow function: ${name}`)
  return found.getText(appSyntax)
}).join('\n')
const workflowDependencies = 'activeTask, activeApplication, busy, writesBlocked, actorScope, api, refreshWorkspace, notice, errorMessage, detailLoading, detailError, taskTab, nextTick, page, taskDetailPanel'
writeFileSync(resolve(output, 'CountersignWorkspace.js'), ts.transpileModule(`export function createWorkflow(deps) { const { ${workflowDependencies} } = deps; let taskDetailRequest = null; ${workflowFunctions}; return { performMembershipChange, selectTask }; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_COUNTERSIGN_WORKSPACE = resolve(output, 'CountersignWorkspace.js')
const notificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openNotification')
if (!notificationNavigation) throw new Error('Missing notification navigation function')
writeFileSync(resolve(output, 'NotificationNavigation.js'), ts.transpileModule(`import { isTaskNotification } from './notificationInbox.js'; export function createNavigation(deps) { const { busy, writesBlocked, selectedCopy, recordApplicationId, actorScope, api, page, notice, selectTask, errorMessage } = deps; ${notificationNavigation.getText(appSyntax)}; return openNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_NOTIFICATION_NAVIGATION = resolve(output, 'NotificationNavigation.js')
// 编译实际版本治理组件，验证确认意图、身份切换和历史响应竞争。
const availabilityDescriptor = parse(readFileSync(resolve(root, 'src/components/DefinitionAvailability.vue'), 'utf8'), { filename: 'DefinitionAvailability.vue' }).descriptor
const availabilityComponent = compileScript(availabilityDescriptor, { id: 'definition-availability-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'")
writeFileSync(resolve(output, 'DefinitionAvailabilityPanel.js'), ts.transpileModule(availabilityComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
// 编译实际组织组件，验证账号切换、失败重试和请求超时边界。
const organizationDescriptor = parse(readFileSync(resolve(root, 'src/components/OrganizationDirectory.vue'), 'utf8'), { filename: 'OrganizationDirectory.vue' }).descriptor
const organizationComponent = compileScript(organizationDescriptor, { id: 'organization-directory-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../organization'", "'./organization.js'")
writeFileSync(resolve(output, 'OrganizationDirectoryPanel.js'), ts.transpileModule(organizationComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
// 编译任职组件以验证账号切换与分页。
const initiatorDescriptor = parse(readFileSync(resolve(root, 'src/components/InitiatorAppointmentPicker.vue'), 'utf8'), { filename: 'InitiatorAppointmentPicker.vue' }).descriptor
const initiatorComponent = compileScript(initiatorDescriptor, { id: 'initiator-picker-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../initiatorContext'", "'./initiatorContext.js'").replaceAll("'../initiatorRequirements'", "'./initiatorRequirements.js'")
writeFileSync(resolve(output, 'InitiatorAppointmentPicker.js'), ts.transpileModule(initiatorComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
const fieldsDescriptor = parse(readFileSync(resolve(root, 'src/components/FormSchemaEditor.vue'), 'utf8'), { filename: 'FormSchemaEditor.vue' }).descriptor
const fieldsComponent = compileScript(fieldsDescriptor, { id: 'field-editor-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace("import FormFields from './FormFields.vue'", 'const FormFields = {}')
  .replace("import FieldPermissionPreview from './FieldPermissionPreview.vue'", 'const FieldPermissionPreview = {}')
  .replaceAll("'../formSchema'", "'./formSchema.js'")
writeFileSync(resolve(output, 'FormSchemaEditor.js'), ts.transpileModule(fieldsComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
const previewDescriptor = parse(readFileSync(resolve(root, 'src/components/FieldPermissionPreview.vue'), 'utf8'), { filename: 'FieldPermissionPreview.vue' }).descriptor
const previewComponent = compileScript(previewDescriptor, { id: 'field-permission-preview-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace("import FormFields from './FormFields.vue'", 'const FormFields = {}')
  .replaceAll("'../api'", "'./api.js'")
writeFileSync(resolve(output, 'FieldPermissionPreview.js'), ts.transpileModule(previewComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
// 编译真实附件组件，覆盖二阶段上传恢复及身份、超时边界。
const attachmentDescriptor = parse(readFileSync(resolve(root, 'src/components/AttachmentField.vue'), 'utf8'), { filename: 'AttachmentField.vue' }).descriptor
const attachmentComponent = compileScript(attachmentDescriptor, { id: 'attachment-field-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../attachments'", "'./attachments.js'")
writeFileSync(resolve(output, 'AttachmentField.js'), ts.transpileModule(attachmentComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
// 申请弹窗的键盘关闭也必须遵守上传状态；子组件展示不参与此行为测试。
const recordDescriptor = parse(readFileSync(resolve(root, 'src/components/ApplicationRecord.vue'), 'utf8'), { filename: 'ApplicationRecord.vue' }).descriptor
const recordComponent = compileScript(recordDescriptor, { id: 'application-record-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = {}')
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../formSchema'", "'./formSchema.js'").replaceAll("'../initiatorContext'", "'./initiatorContext.js'").replaceAll("'../initiatorRequirements'", "'./initiatorRequirements.js'")
writeFileSync(resolve(output, 'ApplicationRecord.js'), ts.transpileModule(recordComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_APPLICATION_RECORD = resolve(output, 'ApplicationRecord.js')
// 原轮次定位依赖 loading 分支切换后的真实 DOM，不能只运行 setup 验证。
const renderedRecord = compileScript(recordDescriptor, { id: 'application-record-rendered', inlineTemplate: true }).content
  .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = { render: () => null }')
  .replace(/'\.\.\/(api|formSchema|initiatorContext|initiatorRequirements)'/g, "'./$1.js'")
writeFileSync(resolve(output, 'ApplicationRecordRendered.js'), ts.transpileModule(renderedRecord, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_RECORD_RENDERED = resolve(output, 'ApplicationRecordRendered.js')
// 编译抄送只读弹窗，验证身份切换和超时后不展示旧快照。
const copyDescriptor = parse(readFileSync(resolve(root, 'src/components/CopyRecord.vue'), 'utf8'), { filename: 'CopyRecord.vue' }).descriptor
const copyComponent = compileScript(copyDescriptor, { id: 'copy-record-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace("import FormFields from './FormFields.vue'", 'const FormFields = {}').replaceAll("'../api'", "'./api.js'")
writeFileSync(resolve(output, 'CopyRecord.js'), ts.transpileModule(copyComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_COPY_RECORD = resolve(output, 'CopyRecord.js')

// 编译实际摘要操作组件，验证字段授权与身份、请求恢复边界。
const assistActionsDescriptor = parse(readFileSync(resolve(root, 'src/components/AssistRunActions.vue'), 'utf8'), { filename: 'AssistRunActions.vue' }).descriptor
const assistActionsComponent = compileScript(assistActionsDescriptor, { id: 'assist-actions-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'")
writeFileSync(resolve(output, 'AssistRunActions.js'), ts.transpileModule(assistActionsComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_ASSIST_ACTIONS = resolve(output, 'AssistRunActions.js')

// 财务实际组件验证明确确认、双版本与身份切换，不只检查请求对象。
for (const name of ['ExpensePartialAdjustment', 'SupplierAdjustmentDisputeStatus', 'SupplierAdjustmentStatus', 'SupplierPaymentReturn', 'SupplierSettlementDisputeStatus', 'SupplierDisputeStatus', 'BudgetFinanceStatus', 'BudgetFinancePositions', 'PaymentBatchComposer', 'PaymentBatchWorkspace', 'PaymentCallbackInbox', 'SupplierSettlementStatus', 'SupplierCashierDetail', 'SupplierCashierWorkspace', 'SupplierFinanceStatus', 'ExpenseResourceAdjustment', 'ExpensePaymentReturn', 'AdvanceDisbursementReturn', 'AdvanceRepaymentReview', 'AdvanceRepaymentStatus', 'ExpenseArchiveStatus', 'ExpenseSettlementStatus', 'FinancePaymentStatus', 'CashierPaymentDetail', 'CashierWorkspace', 'VoucherReversal', 'VoucherReversalExecution', 'VoucherStatus', 'ExpenseActions', 'ExpenseDetail', 'ExpenseWorkspace', 'ExpenseEditor', 'ExpenseSubmission', 'ExpenseFundingPicker', 'InvoiceUploader', 'InvoiceVerification', 'InvoiceDetail', 'InvoiceWallet', 'ExpensePlanEditor', 'ExpensePlanSubmission', 'ExpensePlanDetail', 'ExpensePlanWorkspace', 'AdvanceRequestEditor', 'AdvanceRequestSubmission', 'AdvanceRequestDetail', 'AdvanceRequestWorkspace', 'ProcurementPaymentEditor', 'ProcurementPaymentSubmission', 'ProcurementPaymentDetail', 'ProcurementPaymentWorkspace', 'ProcurementPaymentTerms', 'BudgetAdjustmentEditor', 'BudgetAdjustmentSubmission', 'BudgetAdjustmentDetail', 'BudgetAdjustmentWorkspace', 'BudgetAdjustmentTerms']) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${name}.vue`), 'utf8'), { filename: `${name}.vue` }).descriptor
  const component = compileScript(descriptor, { id: `expense-${name}-test`, inlineTemplate: ['ProcurementPaymentTerms', 'BudgetAdjustmentTerms', 'BudgetFinancePositions'].includes(name) }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = {}')
    .replaceAll("'../api'", "'./api.js'").replaceAll("'../supplierAdjustmentDispute'", "'./supplierAdjustmentDispute.js'").replaceAll("'../supplierAdjustment'", "'./supplierAdjustment.js'").replaceAll("'../supplierReturn'", "'./supplierReturn.js'").replaceAll("'../budgetFinance'", "'./budgetFinance.js'").replaceAll("'../paymentBatches'", "'./paymentBatches.js'").replaceAll("'../paymentCallbacks'", "'./paymentCallbacks.js'").replaceAll("'../supplierSettlementDispute'", "'./supplierSettlementDispute.js'").replaceAll("'../supplierDispute'", "'./supplierDispute.js'").replaceAll("'../supplierSettlement'", "'./supplierSettlement.js'").replaceAll("'../supplierCashier'", "'./supplierCashier.js'").replaceAll("'../supplierFinance'", "'./supplierFinance.js'").replaceAll("'../expensePartialAdjustment'", "'./expensePartialAdjustment.js'").replaceAll("'../expenseResourceAdjustment'", "'./expenseResourceAdjustment.js'").replaceAll("'../expensePaymentReturn'", "'./expensePaymentReturn.js'").replaceAll("'../voucherReversal'", "'./voucherReversal.js'").replaceAll("'../voucherReversalExecution'", "'./voucherReversalExecution.js'").replaceAll("'../vouchers'", "'./vouchers.js'").replaceAll("'../payments'", "'./payments.js'").replaceAll("'../expenseSettlement'", "'./expenseSettlement.js'").replaceAll("'../expenseArchive'", "'./expenseArchive.js'").replaceAll("'../expenses'", "'./expenses.js'").replaceAll("'../expenseDraft'", "'./expenseDraft.js'").replaceAll("'../expensePlan'", "'./expensePlan.js'").replaceAll("'../advanceRequest'", "'./advanceRequest.js'").replaceAll("'../procurementPayment'", "'./procurementPayment.js'").replaceAll("'../budgetAdjustment'", "'./budgetAdjustment.js'").replaceAll("'../advanceRepayment'", "'./advanceRepayment.js'").replaceAll("'../disbursementReturn'", "'./disbursementReturn.js'").replaceAll("'../repaymentReview'", "'./repaymentReview.js'").replaceAll("'../invoiceWallet'", "'./invoiceWallet.js'").replaceAll("'../attachments'", "'./attachments.js'").replaceAll("'../definitionSelection'", "'./definitionSelection.js'").replaceAll("'../initiatorContext'", "'./initiatorContext.js'").replaceAll("'../initiatorRequirements'", "'./initiatorRequirements.js'")
  // macOS 默认忽略大小写，组件产物不能覆盖 voucherReversal.ts 模块。
  const outputName = ['ExpensePartialAdjustment', 'ExpenseResourceAdjustment', 'ExpensePaymentReturn', 'VoucherReversal', 'VoucherReversalExecution'].includes(name) ? name + 'Panel' : name
  writeFileSync(resolve(output, `${outputName}.js`), ts.transpileModule(component, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${outputName}.js`)
}
process.env.AGENTFLOW_TEST_EXPENSE_ARCHIVE = resolve(output, 'expenseArchive.js')
process.env.AGENTFLOW_TEST_EXPENSE_PARTIAL_ADJUSTMENT = resolve(output, 'expensePartialAdjustment.js')
process.env.AGENTFLOW_TEST_EXPENSE_RESOURCE_ADJUSTMENT = resolve(output, 'expenseResourceAdjustment.js')
process.env.AGENTFLOW_TEST_EXPENSE_PAYMENT_RETURN = resolve(output, 'expensePaymentReturn.js')
process.env.AGENTFLOW_TEST_EXPENSE_SETTLEMENT = resolve(output, 'expenseSettlement.js')
process.env.AGENTFLOW_TEST_PAYMENTS = resolve(output, 'payments.js')
process.env.AGENTFLOW_TEST_VOUCHER_REVERSAL_EXECUTION = resolve(output, 'voucherReversalExecution.js')
process.env.AGENTFLOW_TEST_VOUCHER_REVERSAL = resolve(output, 'voucherReversal.js')
process.env.AGENTFLOW_TEST_VOUCHERS = resolve(output, 'vouchers.js')
process.env.AGENTFLOW_TEST_EXPENSES = resolve(output, 'expenses.js')
process.env.AGENTFLOW_TEST_DISBURSEMENT_RETURN = resolve(output, 'disbursementReturn.js')
process.env.AGENTFLOW_TEST_REPAYMENT_REVIEW = resolve(output, 'repaymentReview.js')
process.env.AGENTFLOW_TEST_ADVANCE_REPAYMENT = resolve(output, 'advanceRepayment.js')
process.env.AGENTFLOW_TEST_PAYMENT_BATCHES = resolve(output, 'paymentBatches.js')
process.env.AGENTFLOW_TEST_PAYMENT_CALLBACKS = resolve(output, 'paymentCallbacks.js')
process.env.AGENTFLOW_TEST_SUPPLIER_ADJUSTMENT = resolve(output, 'supplierAdjustment.js')
process.env.AGENTFLOW_TEST_SUPPLIER_RETURN = resolve(output, 'supplierReturn.js')
process.env.AGENTFLOW_TEST_SUPPLIER_ADJUSTMENT_DISPUTE = resolve(output, 'supplierAdjustmentDispute.js')
process.env.AGENTFLOW_TEST_SUPPLIER_SETTLEMENT_DISPUTE = resolve(output, 'supplierSettlementDispute.js')
process.env.AGENTFLOW_TEST_SUPPLIER_DISPUTE = resolve(output, 'supplierDispute.js')
process.env.AGENTFLOW_TEST_SUPPLIER_SETTLEMENT = resolve(output, 'supplierSettlement.js')
process.env.AGENTFLOW_TEST_SUPPLIER_CASHIER = resolve(output, 'supplierCashier.js')
process.env.AGENTFLOW_TEST_BUDGET_FINANCE = resolve(output, 'budgetFinance.js')
process.env.AGENTFLOW_TEST_SUPPLIER_FINANCE = resolve(output, 'supplierFinance.js')
process.env.AGENTFLOW_TEST_BUDGET_ADJUSTMENT = resolve(output, 'budgetAdjustment.js')
process.env.AGENTFLOW_TEST_PROCUREMENT_PAYMENT = resolve(output, 'procurementPayment.js')
process.env.AGENTFLOW_TEST_ADVANCE_REQUEST = resolve(output, 'advanceRequest.js')
process.env.AGENTFLOW_TEST_EXPENSE_PLAN = resolve(output, 'expensePlan.js')
process.env.AGENTFLOW_TEST_EXPENSE_DRAFT = resolve(output, 'expenseDraft.js')
process.env.AGENTFLOW_TEST_INVOICE_WALLET = resolve(output, 'invoiceWallet.js')


for (const name of ['notificationDeliveries', 'notificationPreferences', 'commentMentions', 'taskBatch', 'subprocessRelations', 'initiatorRequirements', 'subprocessDesigner', 'events', 'instanceControl', 'timerWaits', 'approvalPolicy', 'countersignMembership', 'expensePartialAdjustment', 'supplierAdjustmentDispute', 'supplierAdjustment', 'supplierReturn', 'supplierSettlementDispute', 'supplierDispute', 'budgetFinance', 'paymentBatches', 'paymentCallbacks', 'supplierSettlement', 'supplierCashier', 'supplierFinance', 'expenseResourceAdjustment', 'expensePaymentReturn', 'voucherReversalExecution', 'voucherReversal', 'disbursementReturn', 'repaymentReview', 'advanceRepayment', 'expenseArchive', 'expenseSettlement', 'payments', 'vouchers', 'advanceRequest', 'procurementPayment', 'budgetAdjustment', 'invoiceWallet', 'expenseDraft', 'expensePlan', 'expenses', 'attachments', 'initiatorContext', 'organization', 'taskDeadline', 'notificationTexts', 'conditionGroups', 'assistRuns', 'definitionSelection', 'definitionCatalog', 'conditionSyntax', 'conditionPresentation', 'designerValidation', 'webhooks', 'auditSearch', 'portableTemplate', 'workbookExport', 'applicationExport', 'roundComparison', 'quickDesigner', 'conditionBuilder', 'roundDiagram', 'applicationSearch', 'businessCalendars', 'applicationComments', 'firstWorkflow', 'approvalOperations', 'definitionAssignees', 'apiReference', 'api', 'pendingWrites', 'formSchema', 'templateCenter', 'unsavedConfirmation', 'systemChecks', 'definitionSimulation', 'definitionComparison', 'designerGraph', 'designerLayout', 'draftAutosave', 'workspaceRecords', 'taskActions', 'notificationInbox', 'pendingTaskQueue']) {
  const path = resolve(root, `src/${name}.ts`)
  if (!existsSync(path)) continue
  const source = readFileSync(path, 'utf8').replace('import.meta.env.VITE_API_BASE', 'undefined')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
}
const result = spawnSync(process.execPath, ['--test', ...(process.argv.length > 2 ? process.argv.slice(2).map(name => resolve(root, 'tests', name)) : [resolve(root, 'tests/comment-mentions.test.mjs'), resolve(root, 'tests/task-batch.test.mjs'), resolve(root, 'tests/subprocess-relations.test.mjs'), resolve(root, 'tests/initiator-requirements.test.mjs'), resolve(root, 'tests/subprocess-designer.test.mjs'), resolve(root, 'tests/event-ui.test.mjs'), resolve(root, 'tests/instance-control.test.mjs'), resolve(root, 'tests/timer-waits.test.mjs'), resolve(root, 'tests/approval-policy.test.mjs'), resolve(root, 'tests/countersign-membership.test.mjs'), resolve(root, 'tests/expense-partial-adjustment.test.mjs'), resolve(root, 'tests/supplier-adjustment.test.mjs'), resolve(root, 'tests/supplier-return.test.mjs'), resolve(root, 'tests/supplier-settlement-dispute.test.mjs'), resolve(root, 'tests/supplier-dispute.test.mjs'), resolve(root, 'tests/budget-finance.test.mjs'), resolve(root, 'tests/payment-batches.test.mjs'), resolve(root, 'tests/payment-callbacks.test.mjs'), resolve(root, 'tests/supplier-settlement.test.mjs'), resolve(root, 'tests/supplier-cashier.test.mjs'), resolve(root, 'tests/supplier-finance.test.mjs'), resolve(root, 'tests/expense-resource-adjustment.test.mjs'), resolve(root, 'tests/expense-payment-return.test.mjs'), resolve(root, 'tests/voucher-reversal-execution.test.mjs'), resolve(root, 'tests/voucher-reversal.test.mjs'), resolve(root, 'tests/disbursement-return.test.mjs'), resolve(root, 'tests/repayment-review.test.mjs'), resolve(root, 'tests/advance-repayment.test.mjs'), resolve(root, 'tests/expense-archive.test.mjs'), resolve(root, 'tests/expense-settlement.test.mjs'), resolve(root, 'tests/payments.test.mjs'), resolve(root, 'tests/vouchers.test.mjs'), resolve(root, 'tests/advance-request.test.mjs'), resolve(root, 'tests/procurement-payment.test.mjs'), resolve(root, 'tests/budget-adjustment.test.mjs'), resolve(root, 'tests/invoice-wallet.test.mjs'), resolve(root, 'tests/expense-origination.test.mjs'), resolve(root, 'tests/expense-plan.test.mjs'), resolve(root, 'tests/expenses.test.mjs'), resolve(root, 'tests/expense-actions.test.mjs'), resolve(root, 'tests/assist-actions.test.mjs'), resolve(root, 'tests/copy-record.test.mjs'), resolve(root, 'tests/attachments.test.mjs'), resolve(root, 'tests/field-preview.test.mjs'), resolve(root, 'tests/field-editor.test.mjs'), resolve(root, 'tests/initiator-context.test.mjs'), resolve(root, 'tests/organization-panel.test.mjs'), resolve(root, 'tests/organization.test.mjs'), resolve(root, 'tests/task-deadline.test.mjs'), resolve(root, 'tests/definition-availability-panel.test.mjs'), resolve(root, 'tests/condition-groups.test.mjs'), resolve(root, 'tests/assist-runs.test.mjs'), resolve(root, 'tests/definition-selection.test.mjs'), resolve(root, 'tests/definition-catalog.test.mjs'), resolve(root, 'tests/condition-presentation.test.mjs'), resolve(root, 'tests/designer-validation.test.mjs'), resolve(root, 'tests/webhooks.test.mjs'), resolve(root, 'tests/audit-search.test.mjs'), resolve(root, 'tests/portable-template.test.mjs'), resolve(root, 'tests/application-export.test.mjs'), resolve(root, 'tests/audit-export.test.mjs'), resolve(root, 'tests/round-comparison.test.mjs'), resolve(root, 'tests/quick-designer.test.mjs'), resolve(root, 'tests/round-diagram.test.mjs'), resolve(root, 'tests/application-search.test.mjs'), resolve(root, 'tests/business-calendars.test.mjs'), resolve(root, 'tests/comments.test.mjs'), resolve(root, 'tests/first-workflow.test.mjs'), resolve(root, 'tests/operations.test.mjs'), resolve(root, 'tests/definition-assignees.test.mjs'), resolve(root, 'tests/api-reference.test.mjs'), resolve(root, 'tests/requests.test.mjs'), resolve(root, 'tests/authentication.test.mjs'), resolve(root, 'tests/forms.test.mjs'), resolve(root, 'tests/templates.test.mjs'), resolve(root, 'tests/confirmation.test.mjs'), resolve(root, 'tests/system-checks.test.mjs'), resolve(root, 'tests/simulation.test.mjs'), resolve(root, 'tests/comparison.test.mjs'), resolve(root, 'tests/designer-graph.test.mjs'), resolve(root, 'tests/layout.test.mjs'), resolve(root, 'tests/autosave.test.mjs'), resolve(root, 'tests/workspace.test.mjs'), resolve(root, 'tests/task-actions.test.mjs'), resolve(root, 'tests/task-action-panel.test.mjs'), resolve(root, 'tests/escalation-config.test.mjs'), resolve(root, 'tests/notification-deliveries.test.mjs'), resolve(root, 'tests/notification-preferences.test.mjs'), resolve(root, 'tests/notifications.test.mjs'), resolve(root, 'tests/task-queue.test.mjs')])], {
  env: { ...process.env, AGENTFLOW_TEST_ATTACHMENT_FIELD: resolve(output, 'AttachmentField.js'), AGENTFLOW_TEST_ATTACHMENTS: resolve(output, 'attachments.js'), AGENTFLOW_TEST_FIELD_PREVIEW: resolve(output, 'FieldPermissionPreview.js'), AGENTFLOW_TEST_FIELD_EDITOR: resolve(output, 'FormSchemaEditor.js'), AGENTFLOW_TEST_INITIATOR_PANEL: resolve(output, 'InitiatorAppointmentPicker.js'), AGENTFLOW_TEST_ORGANIZATION_PANEL: resolve(output, 'OrganizationDirectoryPanel.js'), AGENTFLOW_TEST_ORGANIZATION: resolve(output, 'organization.js'), AGENTFLOW_TEST_DEADLINE: resolve(output, 'taskDeadline.js'), AGENTFLOW_TEST_AVAILABILITY_PANEL: resolve(output, 'DefinitionAvailabilityPanel.js'), AGENTFLOW_TEST_TASK_PANEL: resolve(output, 'TaskActionPanel.js'), AGENTFLOW_TEST_WORKBOOK_EXPORT: resolve(output, 'workbookExport.js'), AGENTFLOW_TEST_GROUPS: resolve(output, 'conditionGroups.js'), AGENTFLOW_TEST_ASSIST: resolve(output, 'assistRuns.js'), AGENTFLOW_TEST_SELECTION: resolve(output, 'definitionSelection.js'), AGENTFLOW_TEST_CATALOG: resolve(output, 'definitionCatalog.js'), AGENTFLOW_TEST_PRESENTATION: resolve(output, 'conditionPresentation.js'), AGENTFLOW_TEST_VALIDATION: resolve(output, 'designerValidation.js'), AGENTFLOW_TEST_WEBHOOKS: resolve(output, 'webhooks.js'), AGENTFLOW_TEST_AUDIT_SEARCH: resolve(output, 'auditSearch.js'), AGENTFLOW_TEST_PORTABLE: resolve(output, 'portableTemplate.js'), AGENTFLOW_TEST_APPLICATION_EXPORT: resolve(output, 'applicationExport.js'), AGENTFLOW_TEST_ROUND_COMPARISON: resolve(output, 'roundComparison.js'), AGENTFLOW_TEST_QUICK: resolve(output, 'quickDesigner.js'), AGENTFLOW_TEST_CONDITIONS: resolve(output, 'conditionBuilder.js'), AGENTFLOW_TEST_DIAGRAM: resolve(output, 'roundDiagram.js'), AGENTFLOW_TEST_APPLICATION_SEARCH: resolve(output, 'applicationSearch.js'), AGENTFLOW_TEST_CALENDARS: resolve(output, 'businessCalendars.js'), AGENTFLOW_TEST_COMMENTS: resolve(output, 'applicationComments.js'), AGENTFLOW_TEST_GUIDE: resolve(output, 'firstWorkflow.js'), AGENTFLOW_TEST_OPERATIONS: resolve(output, 'approvalOperations.js'), AGENTFLOW_TEST_ASSIGNEES: resolve(output, 'definitionAssignees.js'), AGENTFLOW_TEST_API_REFERENCE: resolve(output, 'apiReference.js'), AGENTFLOW_TEST_TASK_QUEUE: resolve(output, 'pendingTaskQueue.js'), AGENTFLOW_TEST_NOTIFICATIONS: resolve(output, 'notificationInbox.js'), AGENTFLOW_TEST_TASK_ACTIONS: resolve(output, 'taskActions.js'), AGENTFLOW_TEST_WORKSPACE: resolve(output, 'workspaceRecords.js'), AGENTFLOW_TEST_API: resolve(output, 'api.js'), AGENTFLOW_TEST_FORMS: resolve(output, 'formSchema.js'), AGENTFLOW_TEST_TEMPLATES: resolve(output, 'templateCenter.js'), AGENTFLOW_TEST_SIMULATION: resolve(output, 'definitionSimulation.js'), AGENTFLOW_TEST_COMPARISON: resolve(output, 'definitionComparison.js'), AGENTFLOW_TEST_LAYOUT: resolve(output, 'designerLayout.js'), AGENTFLOW_TEST_AUTOSAVE: resolve(output, 'draftAutosave.js'), AGENTFLOW_TEST_DESIGNER_GRAPH: resolve(output, 'designerGraph.js'), AGENTFLOW_TEST_SYSTEM: resolve(output, 'systemChecks.js'), AGENTFLOW_TEST_CONFIRMATION: resolve(output, 'unsavedConfirmation.js') }, stdio: 'inherit'
})
process.exitCode = result.status ?? 1
