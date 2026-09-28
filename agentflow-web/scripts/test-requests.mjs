import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import ts from 'typescript'
import { parse, compileScript } from 'vue/compiler-sfc'

// 测试产物只写入约定的临时目录，不改变应用构建配置。
const root = fileURLToPath(new URL('../', import.meta.url))
const output = mkdtempSync('/fyoung/tmp/agentflow-web-requests-')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}')
// 编译实际任务面板的 setup，验证按钮行为，避免只测试请求构造而遗漏直接提交。
const { descriptor } = parse(readFileSync(resolve(root, 'src/components/TaskActions.vue'), 'utf8'), { filename: 'TaskActions.vue' })
const component = compileScript(descriptor, { id: 'task-actions-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../taskActions'", "'./taskActions.js'")
writeFileSync(resolve(output, 'TaskActionPanel.js'), ts.transpileModule(component, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
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
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../initiatorContext'", "'./initiatorContext.js'")
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
for (const name of ['initiatorContext', 'organization', 'taskDeadline', 'notificationTexts', 'conditionGroups', 'assistRuns', 'definitionSelection', 'definitionCatalog', 'conditionSyntax', 'conditionPresentation', 'designerValidation', 'webhooks', 'auditSearch', 'portableTemplate', 'workbookExport', 'applicationExport', 'roundComparison', 'quickDesigner', 'conditionBuilder', 'roundDiagram', 'applicationSearch', 'businessCalendars', 'applicationComments', 'firstWorkflow', 'approvalOperations', 'definitionAssignees', 'apiReference', 'api', 'pendingWrites', 'formSchema', 'templateCenter', 'unsavedConfirmation', 'systemChecks', 'definitionSimulation', 'definitionComparison', 'designerGraph', 'designerLayout', 'draftAutosave', 'workspaceRecords', 'taskActions', 'notificationInbox', 'pendingTaskQueue']) {
  const path = resolve(root, `src/${name}.ts`)
  if (!existsSync(path)) continue
  const source = readFileSync(path, 'utf8').replace('import.meta.env.VITE_API_BASE', 'undefined')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
}
const result = spawnSync(process.execPath, ['--test', ...(process.argv.length > 2 ? process.argv.slice(2).map(name => resolve(root, 'tests', name)) : [resolve(root, 'tests/field-preview.test.mjs'), resolve(root, 'tests/field-editor.test.mjs'), resolve(root, 'tests/initiator-context.test.mjs'), resolve(root, 'tests/organization-panel.test.mjs'), resolve(root, 'tests/organization.test.mjs'), resolve(root, 'tests/task-deadline.test.mjs'), resolve(root, 'tests/definition-availability-panel.test.mjs'), resolve(root, 'tests/condition-groups.test.mjs'), resolve(root, 'tests/assist-runs.test.mjs'), resolve(root, 'tests/definition-selection.test.mjs'), resolve(root, 'tests/definition-catalog.test.mjs'), resolve(root, 'tests/condition-presentation.test.mjs'), resolve(root, 'tests/designer-validation.test.mjs'), resolve(root, 'tests/webhooks.test.mjs'), resolve(root, 'tests/audit-search.test.mjs'), resolve(root, 'tests/portable-template.test.mjs'), resolve(root, 'tests/application-export.test.mjs'), resolve(root, 'tests/audit-export.test.mjs'), resolve(root, 'tests/round-comparison.test.mjs'), resolve(root, 'tests/quick-designer.test.mjs'), resolve(root, 'tests/round-diagram.test.mjs'), resolve(root, 'tests/application-search.test.mjs'), resolve(root, 'tests/business-calendars.test.mjs'), resolve(root, 'tests/comments.test.mjs'), resolve(root, 'tests/first-workflow.test.mjs'), resolve(root, 'tests/operations.test.mjs'), resolve(root, 'tests/definition-assignees.test.mjs'), resolve(root, 'tests/api-reference.test.mjs'), resolve(root, 'tests/requests.test.mjs'), resolve(root, 'tests/authentication.test.mjs'), resolve(root, 'tests/forms.test.mjs'), resolve(root, 'tests/templates.test.mjs'), resolve(root, 'tests/confirmation.test.mjs'), resolve(root, 'tests/system-checks.test.mjs'), resolve(root, 'tests/simulation.test.mjs'), resolve(root, 'tests/comparison.test.mjs'), resolve(root, 'tests/designer-graph.test.mjs'), resolve(root, 'tests/layout.test.mjs'), resolve(root, 'tests/autosave.test.mjs'), resolve(root, 'tests/workspace.test.mjs'), resolve(root, 'tests/task-actions.test.mjs'), resolve(root, 'tests/task-action-panel.test.mjs'), resolve(root, 'tests/notifications.test.mjs'), resolve(root, 'tests/task-queue.test.mjs')])], {
  env: { ...process.env, AGENTFLOW_TEST_FIELD_PREVIEW: resolve(output, 'FieldPermissionPreview.js'), AGENTFLOW_TEST_FIELD_EDITOR: resolve(output, 'FormSchemaEditor.js'), AGENTFLOW_TEST_INITIATOR_PANEL: resolve(output, 'InitiatorAppointmentPicker.js'), AGENTFLOW_TEST_ORGANIZATION_PANEL: resolve(output, 'OrganizationDirectoryPanel.js'), AGENTFLOW_TEST_ORGANIZATION: resolve(output, 'organization.js'), AGENTFLOW_TEST_DEADLINE: resolve(output, 'taskDeadline.js'), AGENTFLOW_TEST_AVAILABILITY_PANEL: resolve(output, 'DefinitionAvailabilityPanel.js'), AGENTFLOW_TEST_TASK_PANEL: resolve(output, 'TaskActionPanel.js'), AGENTFLOW_TEST_WORKBOOK_EXPORT: resolve(output, 'workbookExport.js'), AGENTFLOW_TEST_GROUPS: resolve(output, 'conditionGroups.js'), AGENTFLOW_TEST_ASSIST: resolve(output, 'assistRuns.js'), AGENTFLOW_TEST_SELECTION: resolve(output, 'definitionSelection.js'), AGENTFLOW_TEST_CATALOG: resolve(output, 'definitionCatalog.js'), AGENTFLOW_TEST_PRESENTATION: resolve(output, 'conditionPresentation.js'), AGENTFLOW_TEST_VALIDATION: resolve(output, 'designerValidation.js'), AGENTFLOW_TEST_WEBHOOKS: resolve(output, 'webhooks.js'), AGENTFLOW_TEST_AUDIT_SEARCH: resolve(output, 'auditSearch.js'), AGENTFLOW_TEST_PORTABLE: resolve(output, 'portableTemplate.js'), AGENTFLOW_TEST_APPLICATION_EXPORT: resolve(output, 'applicationExport.js'), AGENTFLOW_TEST_ROUND_COMPARISON: resolve(output, 'roundComparison.js'), AGENTFLOW_TEST_QUICK: resolve(output, 'quickDesigner.js'), AGENTFLOW_TEST_CONDITIONS: resolve(output, 'conditionBuilder.js'), AGENTFLOW_TEST_DIAGRAM: resolve(output, 'roundDiagram.js'), AGENTFLOW_TEST_APPLICATION_SEARCH: resolve(output, 'applicationSearch.js'), AGENTFLOW_TEST_CALENDARS: resolve(output, 'businessCalendars.js'), AGENTFLOW_TEST_COMMENTS: resolve(output, 'applicationComments.js'), AGENTFLOW_TEST_GUIDE: resolve(output, 'firstWorkflow.js'), AGENTFLOW_TEST_OPERATIONS: resolve(output, 'approvalOperations.js'), AGENTFLOW_TEST_ASSIGNEES: resolve(output, 'definitionAssignees.js'), AGENTFLOW_TEST_API_REFERENCE: resolve(output, 'apiReference.js'), AGENTFLOW_TEST_TASK_QUEUE: resolve(output, 'pendingTaskQueue.js'), AGENTFLOW_TEST_NOTIFICATIONS: resolve(output, 'notificationInbox.js'), AGENTFLOW_TEST_TASK_ACTIONS: resolve(output, 'taskActions.js'), AGENTFLOW_TEST_WORKSPACE: resolve(output, 'workspaceRecords.js'), AGENTFLOW_TEST_API: resolve(output, 'api.js'), AGENTFLOW_TEST_FORMS: resolve(output, 'formSchema.js'), AGENTFLOW_TEST_TEMPLATES: resolve(output, 'templateCenter.js'), AGENTFLOW_TEST_SIMULATION: resolve(output, 'definitionSimulation.js'), AGENTFLOW_TEST_COMPARISON: resolve(output, 'definitionComparison.js'), AGENTFLOW_TEST_LAYOUT: resolve(output, 'designerLayout.js'), AGENTFLOW_TEST_AUTOSAVE: resolve(output, 'draftAutosave.js'), AGENTFLOW_TEST_DESIGNER_GRAPH: resolve(output, 'designerGraph.js'), AGENTFLOW_TEST_SYSTEM: resolve(output, 'systemChecks.js'), AGENTFLOW_TEST_CONFIRMATION: resolve(output, 'unsavedConfirmation.js') }, stdio: 'inherit'
})
process.exitCode = result.status ?? 1
