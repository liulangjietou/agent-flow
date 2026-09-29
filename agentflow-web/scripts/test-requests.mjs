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
  .replaceAll("'../api'", "'./api.js'").replaceAll("'../formSchema'", "'./formSchema.js'").replaceAll("'../initiatorContext'", "'./initiatorContext.js'")
writeFileSync(resolve(output, 'ApplicationRecord.js'), ts.transpileModule(recordComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_APPLICATION_RECORD = resolve(output, 'ApplicationRecord.js')
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
for (const name of ['SupplierCashierDetail', 'SupplierCashierWorkspace', 'SupplierFinanceStatus', 'ExpenseResourceAdjustment', 'ExpensePaymentReturn', 'AdvanceDisbursementReturn', 'AdvanceRepaymentReview', 'AdvanceRepaymentStatus', 'ExpenseArchiveStatus', 'ExpenseSettlementStatus', 'FinancePaymentStatus', 'CashierPaymentDetail', 'CashierWorkspace', 'VoucherReversal', 'VoucherReversalExecution', 'VoucherStatus', 'ExpenseActions', 'ExpenseDetail', 'ExpenseWorkspace', 'ExpenseEditor', 'ExpenseSubmission', 'ExpenseFundingPicker', 'InvoiceUploader', 'InvoiceVerification', 'InvoiceDetail', 'InvoiceWallet', 'ExpensePlanEditor', 'ExpensePlanSubmission', 'ExpensePlanDetail', 'ExpensePlanWorkspace', 'AdvanceRequestEditor', 'AdvanceRequestSubmission', 'AdvanceRequestDetail', 'AdvanceRequestWorkspace', 'ProcurementPaymentEditor', 'ProcurementPaymentSubmission', 'ProcurementPaymentDetail', 'ProcurementPaymentWorkspace', 'ProcurementPaymentTerms']) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${name}.vue`), 'utf8'), { filename: `${name}.vue` }).descriptor
  const component = compileScript(descriptor, { id: `expense-${name}-test`, inlineTemplate: name === 'ProcurementPaymentTerms' }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = {}')
    .replaceAll("'../api'", "'./api.js'").replaceAll("'../supplierCashier'", "'./supplierCashier.js'").replaceAll("'../supplierFinance'", "'./supplierFinance.js'").replaceAll("'../expenseResourceAdjustment'", "'./expenseResourceAdjustment.js'").replaceAll("'../expensePaymentReturn'", "'./expensePaymentReturn.js'").replaceAll("'../voucherReversal'", "'./voucherReversal.js'").replaceAll("'../voucherReversalExecution'", "'./voucherReversalExecution.js'").replaceAll("'../vouchers'", "'./vouchers.js'").replaceAll("'../payments'", "'./payments.js'").replaceAll("'../expenseSettlement'", "'./expenseSettlement.js'").replaceAll("'../expenseArchive'", "'./expenseArchive.js'").replaceAll("'../expenses'", "'./expenses.js'").replaceAll("'../expenseDraft'", "'./expenseDraft.js'").replaceAll("'../expensePlan'", "'./expensePlan.js'").replaceAll("'../advanceRequest'", "'./advanceRequest.js'").replaceAll("'../procurementPayment'", "'./procurementPayment.js'").replaceAll("'../advanceRepayment'", "'./advanceRepayment.js'").replaceAll("'../disbursementReturn'", "'./disbursementReturn.js'").replaceAll("'../repaymentReview'", "'./repaymentReview.js'").replaceAll("'../invoiceWallet'", "'./invoiceWallet.js'").replaceAll("'../attachments'", "'./attachments.js'").replaceAll("'../definitionSelection'", "'./definitionSelection.js'").replaceAll("'../initiatorContext'", "'./initiatorContext.js'")
  // macOS 默认忽略大小写，组件产物不能覆盖 voucherReversal.ts 模块。
  const outputName = ['ExpenseResourceAdjustment', 'ExpensePaymentReturn', 'VoucherReversal', 'VoucherReversalExecution'].includes(name) ? name + 'Panel' : name
  writeFileSync(resolve(output, `${outputName}.js`), ts.transpileModule(component, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${outputName}.js`)
}
process.env.AGENTFLOW_TEST_EXPENSE_ARCHIVE = resolve(output, 'expenseArchive.js')
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
process.env.AGENTFLOW_TEST_SUPPLIER_CASHIER = resolve(output, 'supplierCashier.js')
process.env.AGENTFLOW_TEST_SUPPLIER_FINANCE = resolve(output, 'supplierFinance.js')
process.env.AGENTFLOW_TEST_PROCUREMENT_PAYMENT = resolve(output, 'procurementPayment.js')
process.env.AGENTFLOW_TEST_ADVANCE_REQUEST = resolve(output, 'advanceRequest.js')
process.env.AGENTFLOW_TEST_EXPENSE_PLAN = resolve(output, 'expensePlan.js')
process.env.AGENTFLOW_TEST_EXPENSE_DRAFT = resolve(output, 'expenseDraft.js')
process.env.AGENTFLOW_TEST_INVOICE_WALLET = resolve(output, 'invoiceWallet.js')


for (const name of ['supplierCashier', 'supplierFinance', 'expenseResourceAdjustment', 'expensePaymentReturn', 'voucherReversalExecution', 'voucherReversal', 'disbursementReturn', 'repaymentReview', 'advanceRepayment', 'expenseArchive', 'expenseSettlement', 'payments', 'vouchers', 'advanceRequest', 'procurementPayment', 'invoiceWallet', 'expenseDraft', 'expensePlan', 'expenses', 'attachments', 'initiatorContext', 'organization', 'taskDeadline', 'notificationTexts', 'conditionGroups', 'assistRuns', 'definitionSelection', 'definitionCatalog', 'conditionSyntax', 'conditionPresentation', 'designerValidation', 'webhooks', 'auditSearch', 'portableTemplate', 'workbookExport', 'applicationExport', 'roundComparison', 'quickDesigner', 'conditionBuilder', 'roundDiagram', 'applicationSearch', 'businessCalendars', 'applicationComments', 'firstWorkflow', 'approvalOperations', 'definitionAssignees', 'apiReference', 'api', 'pendingWrites', 'formSchema', 'templateCenter', 'unsavedConfirmation', 'systemChecks', 'definitionSimulation', 'definitionComparison', 'designerGraph', 'designerLayout', 'draftAutosave', 'workspaceRecords', 'taskActions', 'notificationInbox', 'pendingTaskQueue']) {
  const path = resolve(root, `src/${name}.ts`)
  if (!existsSync(path)) continue
  const source = readFileSync(path, 'utf8').replace('import.meta.env.VITE_API_BASE', 'undefined')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
}
const result = spawnSync(process.execPath, ['--test', ...(process.argv.length > 2 ? process.argv.slice(2).map(name => resolve(root, 'tests', name)) : [resolve(root, 'tests/supplier-cashier.test.mjs'), resolve(root, 'tests/supplier-finance.test.mjs'), resolve(root, 'tests/expense-resource-adjustment.test.mjs'), resolve(root, 'tests/expense-payment-return.test.mjs'), resolve(root, 'tests/voucher-reversal-execution.test.mjs'), resolve(root, 'tests/voucher-reversal.test.mjs'), resolve(root, 'tests/disbursement-return.test.mjs'), resolve(root, 'tests/repayment-review.test.mjs'), resolve(root, 'tests/advance-repayment.test.mjs'), resolve(root, 'tests/expense-archive.test.mjs'), resolve(root, 'tests/expense-settlement.test.mjs'), resolve(root, 'tests/payments.test.mjs'), resolve(root, 'tests/vouchers.test.mjs'), resolve(root, 'tests/advance-request.test.mjs'), resolve(root, 'tests/procurement-payment.test.mjs'), resolve(root, 'tests/invoice-wallet.test.mjs'), resolve(root, 'tests/expense-origination.test.mjs'), resolve(root, 'tests/expense-plan.test.mjs'), resolve(root, 'tests/expenses.test.mjs'), resolve(root, 'tests/expense-actions.test.mjs'), resolve(root, 'tests/assist-actions.test.mjs'), resolve(root, 'tests/copy-record.test.mjs'), resolve(root, 'tests/attachments.test.mjs'), resolve(root, 'tests/field-preview.test.mjs'), resolve(root, 'tests/field-editor.test.mjs'), resolve(root, 'tests/initiator-context.test.mjs'), resolve(root, 'tests/organization-panel.test.mjs'), resolve(root, 'tests/organization.test.mjs'), resolve(root, 'tests/task-deadline.test.mjs'), resolve(root, 'tests/definition-availability-panel.test.mjs'), resolve(root, 'tests/condition-groups.test.mjs'), resolve(root, 'tests/assist-runs.test.mjs'), resolve(root, 'tests/definition-selection.test.mjs'), resolve(root, 'tests/definition-catalog.test.mjs'), resolve(root, 'tests/condition-presentation.test.mjs'), resolve(root, 'tests/designer-validation.test.mjs'), resolve(root, 'tests/webhooks.test.mjs'), resolve(root, 'tests/audit-search.test.mjs'), resolve(root, 'tests/portable-template.test.mjs'), resolve(root, 'tests/application-export.test.mjs'), resolve(root, 'tests/audit-export.test.mjs'), resolve(root, 'tests/round-comparison.test.mjs'), resolve(root, 'tests/quick-designer.test.mjs'), resolve(root, 'tests/round-diagram.test.mjs'), resolve(root, 'tests/application-search.test.mjs'), resolve(root, 'tests/business-calendars.test.mjs'), resolve(root, 'tests/comments.test.mjs'), resolve(root, 'tests/first-workflow.test.mjs'), resolve(root, 'tests/operations.test.mjs'), resolve(root, 'tests/definition-assignees.test.mjs'), resolve(root, 'tests/api-reference.test.mjs'), resolve(root, 'tests/requests.test.mjs'), resolve(root, 'tests/authentication.test.mjs'), resolve(root, 'tests/forms.test.mjs'), resolve(root, 'tests/templates.test.mjs'), resolve(root, 'tests/confirmation.test.mjs'), resolve(root, 'tests/system-checks.test.mjs'), resolve(root, 'tests/simulation.test.mjs'), resolve(root, 'tests/comparison.test.mjs'), resolve(root, 'tests/designer-graph.test.mjs'), resolve(root, 'tests/layout.test.mjs'), resolve(root, 'tests/autosave.test.mjs'), resolve(root, 'tests/workspace.test.mjs'), resolve(root, 'tests/task-actions.test.mjs'), resolve(root, 'tests/task-action-panel.test.mjs'), resolve(root, 'tests/notifications.test.mjs'), resolve(root, 'tests/task-queue.test.mjs')])], {
  env: { ...process.env, AGENTFLOW_TEST_ATTACHMENT_FIELD: resolve(output, 'AttachmentField.js'), AGENTFLOW_TEST_ATTACHMENTS: resolve(output, 'attachments.js'), AGENTFLOW_TEST_FIELD_PREVIEW: resolve(output, 'FieldPermissionPreview.js'), AGENTFLOW_TEST_FIELD_EDITOR: resolve(output, 'FormSchemaEditor.js'), AGENTFLOW_TEST_INITIATOR_PANEL: resolve(output, 'InitiatorAppointmentPicker.js'), AGENTFLOW_TEST_ORGANIZATION_PANEL: resolve(output, 'OrganizationDirectoryPanel.js'), AGENTFLOW_TEST_ORGANIZATION: resolve(output, 'organization.js'), AGENTFLOW_TEST_DEADLINE: resolve(output, 'taskDeadline.js'), AGENTFLOW_TEST_AVAILABILITY_PANEL: resolve(output, 'DefinitionAvailabilityPanel.js'), AGENTFLOW_TEST_TASK_PANEL: resolve(output, 'TaskActionPanel.js'), AGENTFLOW_TEST_WORKBOOK_EXPORT: resolve(output, 'workbookExport.js'), AGENTFLOW_TEST_GROUPS: resolve(output, 'conditionGroups.js'), AGENTFLOW_TEST_ASSIST: resolve(output, 'assistRuns.js'), AGENTFLOW_TEST_SELECTION: resolve(output, 'definitionSelection.js'), AGENTFLOW_TEST_CATALOG: resolve(output, 'definitionCatalog.js'), AGENTFLOW_TEST_PRESENTATION: resolve(output, 'conditionPresentation.js'), AGENTFLOW_TEST_VALIDATION: resolve(output, 'designerValidation.js'), AGENTFLOW_TEST_WEBHOOKS: resolve(output, 'webhooks.js'), AGENTFLOW_TEST_AUDIT_SEARCH: resolve(output, 'auditSearch.js'), AGENTFLOW_TEST_PORTABLE: resolve(output, 'portableTemplate.js'), AGENTFLOW_TEST_APPLICATION_EXPORT: resolve(output, 'applicationExport.js'), AGENTFLOW_TEST_ROUND_COMPARISON: resolve(output, 'roundComparison.js'), AGENTFLOW_TEST_QUICK: resolve(output, 'quickDesigner.js'), AGENTFLOW_TEST_CONDITIONS: resolve(output, 'conditionBuilder.js'), AGENTFLOW_TEST_DIAGRAM: resolve(output, 'roundDiagram.js'), AGENTFLOW_TEST_APPLICATION_SEARCH: resolve(output, 'applicationSearch.js'), AGENTFLOW_TEST_CALENDARS: resolve(output, 'businessCalendars.js'), AGENTFLOW_TEST_COMMENTS: resolve(output, 'applicationComments.js'), AGENTFLOW_TEST_GUIDE: resolve(output, 'firstWorkflow.js'), AGENTFLOW_TEST_OPERATIONS: resolve(output, 'approvalOperations.js'), AGENTFLOW_TEST_ASSIGNEES: resolve(output, 'definitionAssignees.js'), AGENTFLOW_TEST_API_REFERENCE: resolve(output, 'apiReference.js'), AGENTFLOW_TEST_TASK_QUEUE: resolve(output, 'pendingTaskQueue.js'), AGENTFLOW_TEST_NOTIFICATIONS: resolve(output, 'notificationInbox.js'), AGENTFLOW_TEST_TASK_ACTIONS: resolve(output, 'taskActions.js'), AGENTFLOW_TEST_WORKSPACE: resolve(output, 'workspaceRecords.js'), AGENTFLOW_TEST_API: resolve(output, 'api.js'), AGENTFLOW_TEST_FORMS: resolve(output, 'formSchema.js'), AGENTFLOW_TEST_TEMPLATES: resolve(output, 'templateCenter.js'), AGENTFLOW_TEST_SIMULATION: resolve(output, 'definitionSimulation.js'), AGENTFLOW_TEST_COMPARISON: resolve(output, 'definitionComparison.js'), AGENTFLOW_TEST_LAYOUT: resolve(output, 'designerLayout.js'), AGENTFLOW_TEST_AUTOSAVE: resolve(output, 'draftAutosave.js'), AGENTFLOW_TEST_DESIGNER_GRAPH: resolve(output, 'designerGraph.js'), AGENTFLOW_TEST_SYSTEM: resolve(output, 'systemChecks.js'), AGENTFLOW_TEST_CONFIRMATION: resolve(output, 'unsavedConfirmation.js') }, stdio: 'inherit'
})
process.exitCode = result.status ?? 1
