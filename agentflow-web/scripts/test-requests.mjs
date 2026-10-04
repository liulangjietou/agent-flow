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

// 费用制度执行实际编辑、发布确认和历史组件，验证身份隔离与原请求恢复。
for (const component of ['ExpenseSplitRoutingPanel', 'CashierWorkspace', 'SignaturePanel', 'OrganizationSyncValue', 'OrganizationSynchronization', 'ExpenseEditor', 'DefinitionExpenseDuplicateApproval', 'DefinitionExpenseSelfApproval', 'AccountMappingManager', 'AccountMappingEntries', 'AccountMappingHistory', 'ExpenseConfigurationManager', 'ExpenseConfigurationHistory', 'ExpensePolicyRules', 'ExpensePolicySummary', 'ExpensePolicyGuidance', 'ExpenseLineEditor', 'ServiceTaskRuntimePanel', 'PaymentFacts', 'SupplierPaymentFacts', 'PaymentNotificationDetail', 'VoucherNotificationDetail', 'BudgetNotificationDetail', 'ReversalNotificationDetail', 'ReversalCheckNotificationDetail', 'ExpenseSettlementNotificationDetail', 'SupplierAdjustmentNotificationDetail', 'SupplierSettlementNotificationDetail', 'SupplierReturnNotificationDetail', 'ExpenseReturnNotificationDetail', 'DisbursementReturnNotificationDetail', 'RepaymentNotificationDetail', 'RepaymentReviewNotificationDetail', 'SupplierPayableNotificationDetail', 'BudgetAdjustmentNotificationDetail', 'ExpensePartialAdjustmentNotificationDetail', 'ExpenseAdjustmentNotificationDetail', 'NotificationInbox']) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${component}.vue`), 'utf8'), { filename: `${component}.vue` }).descriptor
  for (const rendered of [false, true]) {
    const name = component + (rendered ? 'Rendered' : 'Panel'), script = compileScript(descriptor, { id: name })
    let source = script.content
    if (rendered) {
      const template = compileTemplate({ source: descriptor.template.content, filename: `${component}.vue`, id: name, compilerOptions: { bindingMetadata: script.bindings } })
      if (template.errors.length) throw new Error(template.errors.join('\n'))
      source = source.replace('export default', 'const component =') + '\n' + template.code + '\ncomponent.render = render; export default component;'
    }
    if (rendered) source = source.replace("import OrganizationSyncValue from './OrganizationSyncValue.vue'", "import OrganizationSyncValue from './OrganizationSyncValueRendered.js'")
    if (rendered) source = source.replace("import BudgetAdjustmentNotificationDetail from './BudgetAdjustmentNotificationDetail.vue'", "import BudgetAdjustmentNotificationDetail from './BudgetAdjustmentNotificationDetailRendered.js'")
    if (rendered) source = source.replace("import SupplierPayableNotificationDetail from './SupplierPayableNotificationDetail.vue'", "import SupplierPayableNotificationDetail from './SupplierPayableNotificationDetailRendered.js'")
    if (rendered) source = source.replace("import ExpenseAdjustmentNotificationDetail from './ExpenseAdjustmentNotificationDetail.vue'", "import ExpenseAdjustmentNotificationDetail from './ExpenseAdjustmentNotificationDetailRendered.js'")
    if (rendered) source = source.replace("import ExpensePartialAdjustmentNotificationDetail from './ExpensePartialAdjustmentNotificationDetail.vue'", "import ExpensePartialAdjustmentNotificationDetail from './ExpensePartialAdjustmentNotificationDetailRendered.js'")
    if (rendered) source = source.replace("import RepaymentReviewNotificationDetail from './RepaymentReviewNotificationDetail.vue'", "import RepaymentReviewNotificationDetail from './RepaymentReviewNotificationDetailRendered.js'").replace("import RepaymentNotificationDetail from './RepaymentNotificationDetail.vue'", "import RepaymentNotificationDetail from './RepaymentNotificationDetailRendered.js'").replace("import DisbursementReturnNotificationDetail from './DisbursementReturnNotificationDetail.vue'", "import DisbursementReturnNotificationDetail from './DisbursementReturnNotificationDetailRendered.js'").replace("import ExpenseReturnNotificationDetail from './ExpenseReturnNotificationDetail.vue'", "import ExpenseReturnNotificationDetail from './ExpenseReturnNotificationDetailRendered.js'").replace("import SupplierAdjustmentNotificationDetail from './SupplierAdjustmentNotificationDetail.vue'", "import SupplierAdjustmentNotificationDetail from './SupplierAdjustmentNotificationDetailRendered.js'").replace("import SupplierReturnNotificationDetail from './SupplierReturnNotificationDetail.vue'", "import SupplierReturnNotificationDetail from './SupplierReturnNotificationDetailRendered.js'").replace("import SupplierSettlementNotificationDetail from './SupplierSettlementNotificationDetail.vue'", "import SupplierSettlementNotificationDetail from './SupplierSettlementNotificationDetailRendered.js'").replace("import ExpenseSettlementNotificationDetail from './ExpenseSettlementNotificationDetail.vue'", "import ExpenseSettlementNotificationDetail from './ExpenseSettlementNotificationDetailRendered.js'").replace("import ReversalCheckNotificationDetail from './ReversalCheckNotificationDetail.vue'", "import ReversalCheckNotificationDetail from './ReversalCheckNotificationDetailRendered.js'").replace("import ReversalNotificationDetail from './ReversalNotificationDetail.vue'", "import ReversalNotificationDetail from './ReversalNotificationDetailRendered.js'").replace("import BudgetNotificationDetail from './BudgetNotificationDetail.vue'", "import BudgetNotificationDetail from './BudgetNotificationDetailRendered.js'").replace("import PaymentFacts from './PaymentFacts.vue'", "import PaymentFacts from './PaymentFactsRendered.js'")
      .replace("import SupplierPaymentFacts from './SupplierPaymentFacts.vue'", "import SupplierPaymentFacts from './SupplierPaymentFactsRendered.js'")
      .replace("import VoucherNotificationDetail from './VoucherNotificationDetail.vue'", "import VoucherNotificationDetail from './VoucherNotificationDetailRendered.js'")
      .replace("import PaymentNotificationDetail from './PaymentNotificationDetail.vue'", "import PaymentNotificationDetail from './PaymentNotificationDetailRendered.js'")
    source = source.replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
      .replace(/['"]\.\.\/([^'"]+)['"]/g, (_, module) => `'./${module.endsWith('.js') ? module : module + '.js'}'`)
      .replace(/import (\w+) from '[^']+\.vue'/g, component === 'ExpenseEditor' ? 'const $1 = { name: \"$1\", render: () => null }' : 'const $1 = { render: () => null }')
    writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
    process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
  }
}
process.env.AGENTFLOW_TEST_ACCOUNT_MAPPINGS = resolve(output, 'accountMappings.js')
process.env.AGENTFLOW_TEST_ACCOUNT_MAPPING_DRAFTS = resolve(output, 'accountMappingDrafts.js')
process.env.AGENTFLOW_TEST_POLICY_GUIDANCE = resolve(output, 'expensePolicyGuidance.js')
process.env.AGENTFLOW_TEST_EXPENSE_CONFIGURATION = resolve(output, 'expenseConfiguration.js')
process.env.AGENTFLOW_TEST_EXPENSE_CONFIGURATION_DRAFTS = resolve(output, 'expenseConfigurationDrafts.js')
process.env.AGENTFLOW_TEST_EXPENSE_CONFIGURATION_READ = resolve(output, 'expenseConfigurationRead.js')

// 借款建议及草稿采纳使用真实组件，验证只读复核和旧身份迟到响应。
for (const [component, suffix, rendered] of [['AdvanceOffsetSuggestion', 'Panel', false], ['AdvanceOffsetSuggestion', 'Rendered', true], ['ExpenseEditor', 'OffsetEditor', false], ['ExpenseSubmission', 'OffsetSubmission', false]]) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${component}.vue`), 'utf8'), { filename: `${component}.vue` }).descriptor
  const name = component + suffix, script = compileScript(descriptor, { id: name })
  let source = script.content
  if (rendered) {
    const template = compileTemplate({ source: descriptor.template.content, filename: `${component}.vue`, id: name, compilerOptions: { bindingMetadata: script.bindings } })
    if (template.errors.length) throw new Error(template.errors.join('\n'))
    source = source.replace('export default', 'const component =') + '\n' + template.code + '\ncomponent.render = render; export default component;'
  }
  source = source.replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/['"]\.\.\/([^'"]+)['"]/g, (_, module) => `'./${module.endsWith('.js') ? module : module + '.js'}'`)
    .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = { render: () => null }')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_ADVANCE_OFFSET_SUGGESTION = resolve(output, 'advanceOffsetSuggestion.js')

// 额度关闭使用真实确认组件和工作台模板，验证原请求恢复及身份变化。
for (const [component, suffix, rendered] of [['ExpenseRequestClose', 'Panel', false], ['ExpenseRequestClose', 'Rendered', true], ['ExpenseWorkspace', 'Closure', true]]) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${component}.vue`), 'utf8'), { filename: `${component}.vue` }).descriptor
  const name = component + suffix
  const script = compileScript(descriptor, { id: name })
  let source = script.content
  if (rendered) {
    const template = compileTemplate({ source: descriptor.template.content, filename: `${component}.vue`, id: name, compilerOptions: { bindingMetadata: script.bindings } })
    if (template.errors.length) throw new Error(template.errors.join('\n'))
    source = source.replace('export default', 'const component =') + '\n' + template.code + '\ncomponent.render = render; export default component;'
  }
  source = source.replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(api|expenses|expenseRequestClosure|pendingWrites|advanceRepayment)'/g, "'./$1.js'")
    .replace("import ExpenseRequestClose from './ExpenseRequestClose.vue'", "import ExpenseRequestClose from './ExpenseRequestCloseRendered.js'")
    .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = { render: () => null }')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_EXPENSE_REQUEST_CLOSE = resolve(output, 'expenseRequestClosure.js')

// 票面提取使用真实组件与模板，校验外发确认、逐字段修订和本人隔离。
for (const [name, inlineTemplate] of [['InvoiceExtractionPanel', false], ['InvoiceExtractionRendered', true]]) {
  const descriptor = parse(readFileSync(resolve(root, 'src/components/InvoiceExtraction.vue'), 'utf8'), { filename: 'InvoiceExtraction.vue' }).descriptor
  const source = compileScript(descriptor, { id: name, inlineTemplate }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(api|invoiceExtraction)'/g, "'./$1.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_INVOICE_EXTRACTION = resolve(output, 'invoiceExtraction.js')

// 票面辅助填报验证真实组件的明确选择和来源复核，不发送新的模型请求。
for (const name of ['ExpenseInvoiceAssistPanel', 'ExpenseInvoiceAssistRendered']) {
  const descriptor = parse(readFileSync(resolve(root, 'src/components/ExpenseInvoiceAssist.vue'), 'utf8'), { filename: 'ExpenseInvoiceAssist.vue' }).descriptor
  const script = compileScript(descriptor, { id: name })
  let source = script.content
  if (name.endsWith('Rendered')) {
    const template = compileTemplate({ source: descriptor.template.content, filename: 'ExpenseInvoiceAssist.vue', id: name, compilerOptions: { bindingMetadata: script.bindings } })
    if (template.errors.length) throw new Error(template.errors.join('\n'))
    source = source.replace('export default', 'const component =') + '\n' + template.code + '\ncomponent.render = render; export default component;'
  }
  source = source
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(api|expenses|expenseDraft|invoiceExtraction)'/g, "'./$1.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}

// 草稿建议使用实际组件和转义后的模板，校验人工确认及身份边界。
for (const [name, inlineTemplate] of [['DraftAssistPanel', false], ['DraftAssistRendered', true]]) {
  const descriptor = parse(readFileSync(resolve(root, 'src/components/DraftAssistPanel.vue'), 'utf8'), { filename: 'DraftAssistPanel.vue' }).descriptor
  const source = compileScript(descriptor, { id: name, inlineTemplate }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(api|draftAssist|formSchema)'/g, "'./$1.js'")
    .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = { render: () => null }')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_DRAFT_ASSIST = resolve(output, 'draftAssist.js')

// 预检解释执行实际脚本和内联模板，验证来源确认、时效及原请求恢复。
for (const [name, component, inlineTemplate] of [['PrecheckExplanationPanel', 'PrecheckExplanationPanel', false], ['PrecheckExplanationRendered', 'PrecheckExplanationPanel', true], ['ExpenseDraftAssistPanel', 'ExpenseDraftAssistPanel', false], ['ExpenseDraftAssistRendered', 'ExpenseDraftAssistPanel', true]]) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${component}.vue`), 'utf8'), { filename: `${component}.vue` }).descriptor
  const source = compileScript(descriptor, { id: name, inlineTemplate }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/['"]\.\.\/([^'"]+)['"]/g, (_, module) => `'./${module.endsWith('.js') ? module : module + '.js'}'`)
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_PRECHECK_EXPLANATION = resolve(output, 'precheckExplanation.js')
process.env.AGENTFLOW_TEST_EXPENSE_DRAFT_ASSIST = resolve(output, 'expenseDraftAssist.js')

// 风险解释分别执行范围选择与复核组件；所有显示文本仍经 Vue 转义。
for (const [component, name, inlineTemplate] of [['ExpenseRiskPanel', 'ExpenseRiskPanel', false], ['ExpenseRiskPanel', 'ExpenseRiskRendered', true], ['ExpenseRiskScope', 'ExpenseRiskScope', false], ['ExpenseRiskScope', 'ExpenseRiskScopeRendered', true]]) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${component}.vue`), 'utf8'), { filename: `${component}.vue` }).descriptor
  const source = compileScript(descriptor, { id: name, inlineTemplate }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/['"]\.\.\/([^'"]+)['"]/g, (_, module) => `'./${module.endsWith('.js') ? module : module + '.js'}'`)
    .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = { render: () => null }')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_EXPENSESPLITROUTING = resolve(output, 'expenseSplitRouting.js')
process.env.AGENTFLOW_TEST_EXPENSE_RISK = resolve(output, 'expenseRisk.js')



// 表单选人执行真实组件的选择、账号切换和表单更新，并保留模板转义检查。
for (const component of ['DefinitionAssignee', 'OrganizationFormOptions', 'FormSchemaEditor']) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${component}.vue`), 'utf8'), { filename: `${component}.vue` }).descriptor
  for (const [suffix, inlineTemplate] of [['FieldPanel', false], ['FieldRendered', true]]) {
    const source = compileScript(descriptor, { id: component + suffix, inlineTemplate }).content
      .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
      .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
      .replace(/'\.\.\/(api|definitionAssignees|formAssignees|formSchema|approvalPolicy)'/g, "'./$1.js'")
      .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = { render: () => null }')
    const name = component + suffix
    writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
    process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
  }
}
process.env.AGENTFLOW_TEST_FORM_ASSIGNEES = resolve(output, 'formAssignees.js')

// 代理管理采用真实组件和模板，校验身份隔离、明确期限与原请求恢复。
for (const [name, inlineTemplate] of [['ApprovalProxyManager', false], ['ApprovalProxyRendered', true]]) {
  const descriptor = parse(readFileSync(resolve(root, 'src/components/ApprovalProxyManager.vue'), 'utf8'), { filename: 'ApprovalProxyManager.vue' }).descriptor
  const source = compileScript(descriptor, { id: name, inlineTemplate }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(api|approvalProxies)'/g, "'./$1.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_APPROVAL_PROXIES = resolve(output, 'approvalProxies.js')
process.env.AGENTFLOW_TEST_WORKSPACE_NAVIGATION = resolve(output, 'workspaceNavigation.js')

// 职责分离使用真实配置组件及模板，检查明确修改、无效引用保留和锁定权限。
for (const [name, inlineTemplate] of [['ResponsibilitiesPanel', false], ['ResponsibilitiesRendered', true]]) {
  const descriptor = parse(readFileSync(resolve(root, 'src/components/DefinitionResponsibilities.vue'), 'utf8'), { filename: 'DefinitionResponsibilities.vue' }).descriptor
  const source = compileScript(descriptor, { id: name, inlineTemplate }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("'../approvalResponsibilities'", "'./approvalResponsibilities.js'")
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_${name.toUpperCase()}`] = resolve(output, `${name}.js`)
}
process.env.AGENTFLOW_TEST_RESPONSIBILITIES = resolve(output, 'approvalResponsibilities.js')

// 初始化使用真实向导与日历组件，检查来源核对、账号隔离和明确确认。
for (const component of ['TenantInitializationWizard', 'InitializationCalendar']) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${component}.vue`), 'utf8'), { filename: `${component}.vue` }).descriptor
  for (const [suffix, inlineTemplate] of [['Panel', false], ['Rendered', true]]) {
    const source = compileScript(descriptor, { id: component + suffix, inlineTemplate }).content
      .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
      .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
      .replace(/'\.\.\/(api|tenantInitialization|businessCalendars|initiatorContext)'/g, "'./$1.js'")
      .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = {}')
    const name = component + suffix
    writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
    process.env[`AGENTFLOW_TEST_${component.toUpperCase()}_${suffix.toUpperCase()}`] = resolve(output, `${name}.js`)
  }
}
process.env.AGENTFLOW_TEST_INITIALIZATION = resolve(output, 'tenantInitialization.js')

// 风险配置、筛选和展示使用真实组件，字段权限与未评估文案不能靠复制实现验证。
for (const [name, inlineTemplate] of [['DefinitionRiskPolicy', false], ['PendingTaskQueue', false], ['SubmissionRiskStatus', true]]) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${name}.vue`), 'utf8'), { filename: `${name}.vue` }).descriptor
  const source = compileScript(descriptor, { id: `risk-${name}`, inlineTemplate }).content
    .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/'\.\.\/(submissionRisk|api|pendingTaskQueue)'/g, "'./$1.js'")
    .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = {}')
  writeFileSync(resolve(output, `Risk${name}Component.js`), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
  process.env[`AGENTFLOW_TEST_RISK_${name.toUpperCase()}`] = resolve(output, `Risk${name}Component.js`)
}
process.env.AGENTFLOW_TEST_SUBMISSION_RISK = resolve(output, 'submissionRisk.js')

// 编译真实统计卡片，验证零分母、未知投递与采纳边界的展示。
const outcomeDescriptor = parse(readFileSync(resolve(root, 'src/components/OperationsOutcomeMetrics.vue'), 'utf8'), { filename: 'OperationsOutcomeMetrics.vue' }).descriptor
const outcomeSource = compileScript(outcomeDescriptor, { id: 'operations-outcomes', inlineTemplate: true }).content
  .replaceAll('from "vue"', `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
writeFileSync(resolve(output, 'OperationsOutcomeMetrics.js'), ts.transpileModule(outcomeSource, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
process.env.AGENTFLOW_TEST_OUTCOME_METRICS = resolve(output, 'OperationsOutcomeMetrics.js')

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
  .replaceAll("'../formAssignees'", "'./formAssignees.js'")
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
// 服务任务使用实际配置组件验证精确版本、敏感字段映射和迟到响应。
const serviceDescriptor = parse(readFileSync(resolve(root, 'src/components/DefinitionServiceTask.vue'), 'utf8'), { filename: 'DefinitionServiceTask.vue' }).descriptor
const serviceComponent = compileScript(serviceDescriptor, { id: 'service-task-designer-test' }).content
  .replaceAll("from 'vue'", `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
  .replace(/'\.\.\/(api|formSchema|serviceTasks)'/g, "'./$1.js'")
writeFileSync(resolve(output, 'DefinitionServiceTask.js'), ts.transpileModule(serviceComponent, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_SERVICE_TASK_PANEL = resolve(output, 'DefinitionServiceTask.js')
process.env.AGENTFLOW_TEST_SERVICE_TASKS = resolve(output, 'serviceTasks.js')
process.env.AGENTFLOW_TEST_SERVICE_TASK_RUNTIME = resolve(output, 'serviceTaskRuntime.js')
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
// 运行设计器实际保存、读取和撤销函数，确认图元之外的规则不会丢失。
const riskEditorNames = ['snapshot', 'restore', 'graphPayload', 'applyDefinition']
const riskEditorFunctions = riskEditorNames.map(name => {
  const found = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)
  if (!found) throw new Error(`Missing actual risk editor function: ${name}`)
  return found.getText(appSyntax)
}).join('\n')
const riskEditorDependencies = 'definitionId, definitionKey, definitionName, definitionRiskPolicy, conditionLanguageVersion, nodes, edges, definitionFormSchema, definitionNotificationTexts, editorSession, autosave, composing, definitionRevision, definitionVersion, definitionStatus, definitionStartEnabled, availabilityError, selectedId, resetEditor, savedSnapshot, tenantId'
writeFileSync(resolve(output, 'RiskEditorState.js'), ts.transpileModule(`import { copyRiskPolicy } from './submissionRisk.js'; import { cloneSchema } from './formSchema.js'; import { copyNotificationTexts } from './notificationTexts.js'; import { loadDesignerNodes, serializeDesignerNodes } from './designerGraph.js'; export function createEditor(deps) { const { ${riskEditorDependencies} } = deps; let pendingDraftCheckpoint = null; ${riskEditorFunctions}; return { ${riskEditorNames.join(', ')} }; }`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
process.env.AGENTFLOW_TEST_RISK_EDITOR = resolve(output, 'RiskEditorState.js')

// 使用父页面真实的编辑和撤销函数，不能用只测试序列化来代替视图接线。
const responsibilityEditorNames = ['patchResponsibilities', 'remember', 'snapshot', 'restore', 'undo', 'redo']
const responsibilityEditorFunctions = responsibilityEditorNames.map(name => {
  const node = appSyntax.statements.find(value => ts.isFunctionDeclaration(value) && value.name?.text === name)
  if (!node) throw new Error('Missing actual responsibility editor function: ' + name)
  return node.getText(appSyntax)
}).join('\n')
const responsibilityEditorDependencies = 'editorLocked, canManageDefinitions, history, future, definitionId, definitionKey, definitionName, definitionRiskPolicy, conditionLanguageVersion, nodes, edges, definitionFormSchema, definitionNotificationTexts'
writeFileSync(resolve(output, 'ResponsibilitiesEditor.js'), ts.transpileModule(`import { copyRiskPolicy } from './submissionRisk.js'; import { cloneSchema } from './formSchema.js'; import { copyNotificationTexts } from './notificationTexts.js'; import { writeResponsibilities } from './approvalResponsibilities.js'; export function createEditor(deps) { const { ${responsibilityEditorDependencies} } = deps; let stopNodeDrag = null; ${responsibilityEditorFunctions}; return { ${responsibilityEditorNames.join(', ')} }; }`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
process.env.AGENTFLOW_TEST_RESPONSIBILITIES_EDITOR = resolve(output, 'ResponsibilitiesEditor.js')

const recoverFunction = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'recoverOperation')
if (!recoverFunction) throw new Error('Missing actual operation recovery')
const timerRecoveryDependencies = 'rememberSignatureOperation, organizationSyncDrafts, syncPath, acknowledgeExpenseAssist, acknowledgeExplanation, acknowledgeRisk, acknowledgeExtraction, rememberDraftRun, approvalProxyDrafts, initializationDrafts, actorScope, pendingWrites, draftScope, confirmReplaceDefinition, busy, recoveryError, writeRequests, notice, createdApplication, newApplicationOpen, recordApplicationId, recordRefresh, templateRefresh, statusLabel, refreshWorkspace, nextTick, workspace, errorMessage, activeTask, clearTaskSelection'
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
// 付款消息导航提取实际函数，验证原付款、原轮次和出纳角色边界。
const paymentNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openPaymentNotification')
if (!paymentNotificationNavigation) throw new Error('Missing payment notification navigation function')
writeFileSync(resolve(output, 'PaymentNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, canCashier, notice, notificationPaymentId, cashierKind, page, recordApplicationId, recordInitialRoundNo } = deps; ${paymentNotificationNavigation.getText(appSyntax)}; return openPaymentNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_PAYMENT_NOTIFICATION_NAVIGATION = resolve(output, 'PaymentNotificationNavigation.js')
const voucherNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openVoucherNotification')
if (!voucherNotificationNavigation) throw new Error('Missing real voucher notification navigation')
writeFileSync(resolve(output, 'VoucherNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${voucherNotificationNavigation.getText(appSyntax)}; return openVoucherNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_VOUCHER_NOTIFICATION_NAVIGATION = resolve(output, 'VoucherNotificationNavigation.js')
const budgetNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openBudgetNotification')
if (!budgetNotificationNavigation) throw new Error('Missing real budget notification navigation')
writeFileSync(resolve(output, 'BudgetNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${budgetNotificationNavigation.getText(appSyntax)}; return openBudgetNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_BUDGET_NOTIFICATION_NAVIGATION = resolve(output, 'BudgetNotificationNavigation.js')
const reversalNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openReversalNotification')
if (!reversalNotificationNavigation) throw new Error('Missing real reversal notification navigation')
writeFileSync(resolve(output, 'ReversalNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${reversalNotificationNavigation.getText(appSyntax)}; return openReversalNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_REVERSAL_NOTIFICATION_NAVIGATION = resolve(output, 'ReversalNotificationNavigation.js')
const reversalCheckNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openReversalCheckNotification')
if (!reversalCheckNotificationNavigation) throw new Error('Missing real reversal notification navigation')
writeFileSync(resolve(output, 'ReversalCheckNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${reversalCheckNotificationNavigation.getText(appSyntax)}; return openReversalCheckNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_REVERSAL_CHECK_NOTIFICATION_NAVIGATION = resolve(output, 'ReversalCheckNotificationNavigation.js')

const settlementNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openExpenseSettlementNotification')
if (!settlementNotificationNavigation) throw new Error('Missing real reversal notification navigation')
writeFileSync(resolve(output, 'ExpenseSettlementNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${settlementNotificationNavigation.getText(appSyntax)}; return openExpenseSettlementNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_EXPENSE_SETTLEMENT_NOTIFICATION_NAVIGATION = resolve(output, 'ExpenseSettlementNotificationNavigation.js')
const supplierSettlementNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openSupplierSettlementNotification')
if (!supplierSettlementNotificationNavigation) throw new Error('Missing real reversal notification navigation')
writeFileSync(resolve(output, 'SupplierSettlementNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${supplierSettlementNotificationNavigation.getText(appSyntax)}; return openSupplierSettlementNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_SUPPLIER_SETTLEMENT_NOTIFICATION_NAVIGATION = resolve(output, 'SupplierSettlementNotificationNavigation.js')
const supplierAdjustmentNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openSupplierAdjustmentNotification')
if (!supplierAdjustmentNotificationNavigation) throw new Error('Missing real reversal notification navigation')
writeFileSync(resolve(output, 'SupplierAdjustmentNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${supplierAdjustmentNotificationNavigation.getText(appSyntax)}; return openSupplierAdjustmentNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_SUPPLIER_ADJUSTMENT_NOTIFICATION_NAVIGATION = resolve(output, 'SupplierAdjustmentNotificationNavigation.js')
const supplierReturnNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openSupplierReturnNotification')
if (!supplierReturnNotificationNavigation) throw new Error('Missing real reversal notification navigation')
writeFileSync(resolve(output, 'SupplierReturnNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${supplierReturnNotificationNavigation.getText(appSyntax)}; return openSupplierReturnNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_SUPPLIER_RETURN_NOTIFICATION_NAVIGATION = resolve(output, 'SupplierReturnNotificationNavigation.js')
process.env.AGENTFLOW_TEST_SUPPLIER_RETURN_NOTIFICATION = resolve(output, 'supplierReturnNotification.js')
const expenseReturnNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openExpenseReturnNotification')
if (!expenseReturnNotificationNavigation) throw new Error('Missing real expense return notification navigation')
writeFileSync(resolve(output, 'ExpenseReturnNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${expenseReturnNotificationNavigation.getText(appSyntax)}; return openExpenseReturnNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_EXPENSE_RETURN_NOTIFICATION_NAVIGATION = resolve(output, 'ExpenseReturnNotificationNavigation.js')
process.env.AGENTFLOW_TEST_EXPENSE_RETURN_NOTIFICATION = resolve(output, 'expenseReturnNotification.js')
const disbursementReturnNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openDisbursementReturnNotification')
if (!disbursementReturnNotificationNavigation) throw new Error('Missing real expense return notification navigation')
writeFileSync(resolve(output, 'DisbursementReturnNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${disbursementReturnNotificationNavigation.getText(appSyntax)}; return openDisbursementReturnNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_DISBURSEMENT_RETURN_NOTIFICATION_NAVIGATION = resolve(output, 'DisbursementReturnNotificationNavigation.js')
process.env.AGENTFLOW_TEST_DISBURSEMENT_RETURN_NOTIFICATION = resolve(output, 'disbursementReturnNotification.js')
const repaymentReviewNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openRepaymentReviewNotification')
if (!repaymentReviewNotificationNavigation) throw new Error('Missing real expense return notification navigation')
writeFileSync(resolve(output, 'RepaymentReviewNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${repaymentReviewNotificationNavigation.getText(appSyntax)}; return openRepaymentReviewNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_REPAYMENT_REVIEW_NOTIFICATION_NAVIGATION = resolve(output, 'RepaymentReviewNotificationNavigation.js')
const supplierPayableNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openSupplierPayableNotification')
if (!supplierPayableNotificationNavigation) throw new Error('Missing real supplier payable notification navigation')
writeFileSync(resolve(output, 'SupplierPayableNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${supplierPayableNotificationNavigation.getText(appSyntax)}; return openSupplierPayableNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_SUPPLIER_PAYABLE_NOTIFICATION_NAVIGATION = resolve(output, 'SupplierPayableNotificationNavigation.js')
const budgetAdjustmentNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openBudgetAdjustmentNotification')
if (!budgetAdjustmentNotificationNavigation) throw new Error('Missing real expense return notification navigation')
writeFileSync(resolve(output, 'BudgetAdjustmentNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${budgetAdjustmentNotificationNavigation.getText(appSyntax)}; return openBudgetAdjustmentNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_BUDGET_ADJUSTMENT_NOTIFICATION_NAVIGATION = resolve(output, 'BudgetAdjustmentNotificationNavigation.js')
const expenseAdjustmentNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openExpenseAdjustmentNotification')
if (!expenseAdjustmentNotificationNavigation) throw new Error('Missing real expense adjustment notification navigation')
writeFileSync(resolve(output, 'ExpenseAdjustmentNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${expenseAdjustmentNotificationNavigation.getText(appSyntax)}; return openExpenseAdjustmentNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_EXPENSE_ADJUSTMENT_NOTIFICATION_NAVIGATION = resolve(output, 'ExpenseAdjustmentNotificationNavigation.js')
process.env.AGENTFLOW_TEST_EXPENSE_ADJUSTMENT_NOTIFICATION = resolve(output, 'expenseAdjustmentNotification.js')
const expensePartialAdjustmentNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openExpensePartialAdjustmentNotification')
if (!expensePartialAdjustmentNotificationNavigation) throw new Error('Missing real expense adjustment notification navigation')
writeFileSync(resolve(output, 'ExpensePartialAdjustmentNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${expensePartialAdjustmentNotificationNavigation.getText(appSyntax)}; return openExpensePartialAdjustmentNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_EXPENSE_PARTIAL_ADJUSTMENT_NOTIFICATION_NAVIGATION = resolve(output, 'ExpensePartialAdjustmentNotificationNavigation.js')
process.env.AGENTFLOW_TEST_EXPENSE_PARTIAL_ADJUSTMENT_NOTIFICATION = resolve(output, 'expensePartialAdjustmentNotification.js')
process.env.AGENTFLOW_TEST_REPAYMENT_REVIEW_NOTIFICATION = resolve(output, 'repaymentReviewNotification.js')
process.env.AGENTFLOW_TEST_BUDGET_ADJUSTMENT_NOTIFICATION = resolve(output, 'budgetAdjustmentNotification.js')
process.env.AGENTFLOW_TEST_SUPPLIER_PAYABLE_NOTIFICATION = resolve(output, 'supplierPayableNotification.js')
const repaymentNotificationNavigation = appSyntax.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'openRepaymentNotification')
if (!repaymentNotificationNavigation) throw new Error('Missing real repayment notification navigation')
writeFileSync(resolve(output, 'RepaymentNotificationNavigation.js'), ts.transpileModule(`export function createNavigation(deps) { const { busy, writesBlocked, recordApplicationId, recordInitialRoundNo } = deps; ${repaymentNotificationNavigation.getText(appSyntax)}; return openRepaymentNotification; }`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
}).outputText)
process.env.AGENTFLOW_TEST_REPAYMENT_NOTIFICATION_NAVIGATION = resolve(output, 'RepaymentNotificationNavigation.js')
process.env.AGENTFLOW_TEST_REPAYMENT_NOTIFICATION = resolve(output, 'repaymentNotification.js')
process.env.AGENTFLOW_TEST_SUPPLIER_ADJUSTMENT_NOTIFICATION = resolve(output, 'supplierAdjustmentNotification.js')
process.env.AGENTFLOW_TEST_SUPPLIER_SETTLEMENT_NOTIFICATION = resolve(output, 'supplierSettlementNotification.js')
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
  .replaceAll("'../organizationSync'", "'./organizationSync.js'")
  .replace(/import OrganizationSynchronization from '[^']+\.vue'/g, 'const OrganizationSynchronization = { render: () => null }')
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
  .replace(/import (\w+) from ['"][^'"]+\.vue['"]/g, 'const $1 = { render: () => null }')
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
for (const name of ['ExpenseBudgetRetentionStatus', 'ExpensePartialAdjustment', 'SupplierAdjustmentDisputeStatus', 'SupplierAdjustmentStatus', 'SupplierPaymentReturn', 'SupplierSettlementDisputeStatus', 'SupplierDisputeStatus', 'BudgetFinanceStatus', 'BudgetFinancePositions', 'PaymentBatchComposer', 'PaymentBatchWorkspace', 'PaymentCallbackInbox', 'SupplierSettlementStatus', 'SupplierCashierDetail', 'SupplierCashierWorkspace', 'SupplierFinanceStatus', 'ExpenseResourceAdjustment', 'ExpensePaymentReturn', 'AdvanceDisbursementReturn', 'AdvanceRepaymentReview', 'AdvanceRepaymentStatus', 'ExpenseArchiveStatus', 'ExpenseSettlementStatus', 'FinancePaymentStatus', 'CashierPaymentDetail', 'CashierWorkspace', 'VoucherReversal', 'VoucherReversalExecution', 'VoucherStatus', 'ExpenseActions', 'ExpenseDetail', 'ExpenseWorkspace', 'ExpenseEditor', 'ExpenseSubmission', 'ExpenseFundingPicker', 'InvoiceUploader', 'InvoiceVerification', 'InvoiceDetail', 'InvoiceWallet', 'ExpensePlanEditor', 'ExpensePlanSubmission', 'ExpensePlanDetail', 'ExpensePlanWorkspace', 'AdvanceRequestEditor', 'AdvanceRequestSubmission', 'AdvanceRequestDetail', 'AdvanceRequestWorkspace', 'ProcurementPaymentEditor', 'ProcurementPaymentSubmission', 'ProcurementPaymentDetail', 'ProcurementPaymentWorkspace', 'ProcurementPaymentTerms', 'BudgetAdjustmentEditor', 'BudgetAdjustmentSubmission', 'BudgetAdjustmentDetail', 'BudgetAdjustmentWorkspace', 'BudgetAdjustmentTerms']) {
  const descriptor = parse(readFileSync(resolve(root, `src/components/${name}.vue`), 'utf8'), { filename: `${name}.vue` }).descriptor
  const component = compileScript(descriptor, { id: `expense-${name}-test`, inlineTemplate: ['ExpenseBudgetRetentionStatus', 'ProcurementPaymentTerms', 'BudgetAdjustmentTerms', 'BudgetFinancePositions'].includes(name) }).content
    .replace(/from ['"]vue['"]/g, `from '${pathToFileURL(resolve(root, 'node_modules/vue/dist/vue.runtime.esm-bundler.js')).href}'`)
    .replace(/import (\w+) from '[^']+\.vue'/g, 'const $1 = {}')
    .replaceAll("'../cashierFilters'", "'./cashierFilters.js'").replaceAll("'../api'", "'./api.js'").replaceAll("'../supplierAdjustmentDispute'", "'./supplierAdjustmentDispute.js'").replaceAll("'../supplierAdjustment'", "'./supplierAdjustment.js'").replaceAll("'../supplierReturn'", "'./supplierReturn.js'").replaceAll("'../budgetFinance'", "'./budgetFinance.js'").replaceAll("'../paymentBatches'", "'./paymentBatches.js'").replaceAll("'../paymentCallbacks'", "'./paymentCallbacks.js'").replaceAll("'../supplierSettlementDispute'", "'./supplierSettlementDispute.js'").replaceAll("'../supplierDispute'", "'./supplierDispute.js'").replaceAll("'../supplierSettlement'", "'./supplierSettlement.js'").replaceAll("'../supplierCashier'", "'./supplierCashier.js'").replaceAll("'../supplierFinance'", "'./supplierFinance.js'").replaceAll("'../expensePartialAdjustment'", "'./expensePartialAdjustment.js'").replaceAll("'../expenseResourceAdjustment'", "'./expenseResourceAdjustment.js'").replaceAll("'../expensePaymentReturn'", "'./expensePaymentReturn.js'").replaceAll("'../voucherReversal'", "'./voucherReversal.js'").replaceAll("'../voucherReversalExecution'", "'./voucherReversalExecution.js'").replaceAll("'../vouchers'", "'./vouchers.js'").replaceAll("'../payments'", "'./payments.js'").replaceAll("'../expenseSettlement'", "'./expenseSettlement.js'").replaceAll("'../expenseArchive'", "'./expenseArchive.js'").replaceAll("'../expenses'", "'./expenses.js'").replaceAll("'../taskActions'", "'./taskActions.js'").replaceAll("'../expenseDraft'", "'./expenseDraft.js'").replaceAll("'../expenseDraftAssist'", "'./expenseDraftAssist.js'").replaceAll("'../expensePlan'", "'./expensePlan.js'").replaceAll("'../advanceRequest'", "'./advanceRequest.js'").replaceAll("'../procurementPayment'", "'./procurementPayment.js'").replaceAll("'../budgetAdjustment'", "'./budgetAdjustment.js'").replaceAll("'../advanceRepayment'", "'./advanceRepayment.js'").replaceAll("'../disbursementReturn'", "'./disbursementReturn.js'").replaceAll("'../repaymentReview'", "'./repaymentReview.js'").replaceAll("'../invoiceWallet'", "'./invoiceWallet.js'").replaceAll("'../attachments'", "'./attachments.js'").replaceAll("'../definitionSelection'", "'./definitionSelection.js'").replaceAll("'../initiatorContext'", "'./initiatorContext.js'").replaceAll("'../initiatorRequirements'", "'./initiatorRequirements.js'")
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


for (const name of ['expenseSplitRouting', 'expenseRisk', 'cashierFilters', 'signatures', 'organizationSync', 'organizationSyncRead', 'expenseDraftAssist', 'repaymentReviewNotification', 'expensePartialAdjustmentNotification', 'expenseAdjustmentNotification', 'supplierPayableNotification', 'budgetAdjustmentNotification', 'repaymentNotification', 'disbursementReturnNotification', 'expenseReturnNotification', 'supplierAdjustmentNotification', 'supplierReturnNotification', 'supplierSettlementNotification', 'precheckExplanation', 'serviceTaskRuntime', 'serviceTasks', 'accountMappings', 'accountMappingDrafts', 'expensePolicyGuidance', 'expenseConfiguration', 'expenseConfigurationDrafts', 'expenseConfigurationRead', 'advanceOffsetSuggestion', 'expenseRequestClosure', 'invoiceExtraction', 'draftAssist', 'formAssignees', 'approvalProxies', 'workspaceNavigation', 'approvalResponsibilities', 'tenantInitialization', 'submissionRisk', 'notificationDeliveries', 'notificationPreferences', 'commentMentions', 'taskBatch', 'subprocessRelations', 'initiatorRequirements', 'subprocessDesigner', 'events', 'instanceControl', 'timerWaits', 'approvalPolicy', 'countersignMembership', 'expensePartialAdjustment', 'supplierAdjustmentDispute', 'supplierAdjustment', 'supplierReturn', 'supplierSettlementDispute', 'supplierDispute', 'budgetFinance', 'paymentBatches', 'paymentCallbacks', 'supplierSettlement', 'supplierCashier', 'supplierFinance', 'expenseResourceAdjustment', 'expensePaymentReturn', 'voucherReversalExecution', 'voucherReversal', 'disbursementReturn', 'repaymentReview', 'advanceRepayment', 'expenseArchive', 'expenseSettlement', 'payments', 'vouchers', 'advanceRequest', 'procurementPayment', 'budgetAdjustment', 'invoiceWallet', 'expenseDraft', 'expensePlan', 'expenses', 'attachments', 'initiatorContext', 'organization', 'taskDeadline', 'notificationTexts', 'conditionGroups', 'assistRuns', 'definitionSelection', 'definitionCatalog', 'conditionSyntax', 'conditionPresentation', 'designerValidation', 'webhooks', 'auditSearch', 'portableTemplate', 'workbookExport', 'applicationExport', 'roundComparison', 'quickDesigner', 'conditionBuilder', 'roundDiagram', 'applicationSearch', 'businessCalendars', 'applicationComments', 'firstWorkflow', 'approvalOperations', 'definitionAssignees', 'apiReference', 'api', 'pendingWrites', 'formSchema', 'templateCenter', 'unsavedConfirmation', 'systemChecks', 'definitionSimulation', 'definitionComparison', 'designerGraph', 'designerLayout', 'draftAutosave', 'workspaceRecords', 'taskActions', 'notificationInbox', 'pendingTaskQueue']) {
  const path = resolve(root, `src/${name}.ts`)
  if (!existsSync(path)) continue
  const source = readFileSync(path, 'utf8').replace('import.meta.env.VITE_API_BASE', 'undefined')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
}
const result = spawnSync(process.execPath, ['--test', ...(process.argv.length > 2 ? process.argv.slice(2).map(name => { const file = resolve(root, 'tests', name); if (!existsSync(file)) throw new Error(`Requested test file does not exist: ${name}`); return file }) : [resolve(root, 'tests/expense-split-panel.test.mjs'), resolve(root, 'tests/expense-split-routing.test.mjs'), resolve(root, 'tests/expense-risk.test.mjs'), resolve(root, 'tests/cashier-filters.test.mjs'), resolve(root, 'tests/signatures.test.mjs'), resolve(root, 'tests/signature-panel.test.mjs'), resolve(root, 'tests/organization-sync.test.mjs'), resolve(root, 'tests/organization-sync-panel.test.mjs'), resolve(root, 'tests/expense-draft-assist.test.mjs'), resolve(root, 'tests/disbursement-return-notifications.test.mjs'), resolve(root, 'tests/expense-return-notifications.test.mjs'), resolve(root, 'tests/supplier-adjustment-notifications.test.mjs'), resolve(root, 'tests/supplier-return-notifications.test.mjs'), resolve(root, 'tests/supplier-settlement-notifications.test.mjs'), resolve(root, 'tests/reversal-check-notifications.test.mjs'), resolve(root, 'tests/expense-settlement-notifications.test.mjs'), resolve(root, 'tests/reversal-notifications.test.mjs'), resolve(root, 'tests/budget-notifications.test.mjs'), resolve(root, 'tests/precheck-explanation.test.mjs'), resolve(root, 'tests/service-task-runtime.test.mjs'), resolve(root, 'tests/service-tasks.test.mjs'), resolve(root, 'tests/expense-budget-retention.test.mjs'), resolve(root, 'tests/account-mappings.test.mjs'), resolve(root, 'tests/expense-policy-guidance.test.mjs'), resolve(root, 'tests/expense-configuration.test.mjs'), resolve(root, 'tests/expense-request-close.test.mjs'), resolve(root, 'tests/expense-invoice-fill.test.mjs'), resolve(root, 'tests/invoice-extraction.test.mjs'), resolve(root, 'tests/draft-assist.test.mjs'), resolve(root, 'tests/form-assignees.test.mjs'), resolve(root, 'tests/approval-proxies.test.mjs'), resolve(root, 'tests/approval-responsibilities.test.mjs'), resolve(root, 'tests/expense-self-approval.test.mjs'), resolve(root, 'tests/expense-duplicate-approval.test.mjs'), resolve(root, 'tests/tenant-initialization.test.mjs'), resolve(root, 'tests/submission-risk.test.mjs'), resolve(root, 'tests/comment-mentions.test.mjs'), resolve(root, 'tests/task-batch.test.mjs'), resolve(root, 'tests/subprocess-relations.test.mjs'), resolve(root, 'tests/initiator-requirements.test.mjs'), resolve(root, 'tests/subprocess-designer.test.mjs'), resolve(root, 'tests/event-ui.test.mjs'), resolve(root, 'tests/instance-control.test.mjs'), resolve(root, 'tests/timer-waits.test.mjs'), resolve(root, 'tests/approval-policy.test.mjs'), resolve(root, 'tests/countersign-membership.test.mjs'), resolve(root, 'tests/expense-partial-adjustment.test.mjs'), resolve(root, 'tests/supplier-adjustment.test.mjs'), resolve(root, 'tests/supplier-return.test.mjs'), resolve(root, 'tests/supplier-settlement-dispute.test.mjs'), resolve(root, 'tests/supplier-dispute.test.mjs'), resolve(root, 'tests/budget-finance.test.mjs'), resolve(root, 'tests/payment-batches.test.mjs'), resolve(root, 'tests/payment-callbacks.test.mjs'), resolve(root, 'tests/supplier-settlement.test.mjs'), resolve(root, 'tests/supplier-cashier.test.mjs'), resolve(root, 'tests/supplier-finance.test.mjs'), resolve(root, 'tests/expense-resource-adjustment.test.mjs'), resolve(root, 'tests/expense-payment-return.test.mjs'), resolve(root, 'tests/voucher-reversal-execution.test.mjs'), resolve(root, 'tests/voucher-reversal.test.mjs'), resolve(root, 'tests/disbursement-return.test.mjs'), resolve(root, 'tests/repayment-review.test.mjs'), resolve(root, 'tests/advance-repayment.test.mjs'), resolve(root, 'tests/expense-archive.test.mjs'), resolve(root, 'tests/expense-settlement.test.mjs'), resolve(root, 'tests/payments.test.mjs'), resolve(root, 'tests/vouchers.test.mjs'), resolve(root, 'tests/advance-request.test.mjs'), resolve(root, 'tests/procurement-payment.test.mjs'), resolve(root, 'tests/budget-adjustment.test.mjs'), resolve(root, 'tests/invoice-wallet.test.mjs'), resolve(root, 'tests/expense-origination.test.mjs'), resolve(root, 'tests/expense-plan.test.mjs'), resolve(root, 'tests/expenses.test.mjs'), resolve(root, 'tests/expense-actions.test.mjs'), resolve(root, 'tests/assist-actions.test.mjs'), resolve(root, 'tests/copy-record.test.mjs'), resolve(root, 'tests/attachments.test.mjs'), resolve(root, 'tests/field-preview.test.mjs'), resolve(root, 'tests/field-editor.test.mjs'), resolve(root, 'tests/initiator-context.test.mjs'), resolve(root, 'tests/organization-panel.test.mjs'), resolve(root, 'tests/organization.test.mjs'), resolve(root, 'tests/task-deadline.test.mjs'), resolve(root, 'tests/definition-availability-panel.test.mjs'), resolve(root, 'tests/condition-groups.test.mjs'), resolve(root, 'tests/assist-runs.test.mjs'), resolve(root, 'tests/definition-selection.test.mjs'), resolve(root, 'tests/definition-catalog.test.mjs'), resolve(root, 'tests/condition-presentation.test.mjs'), resolve(root, 'tests/designer-validation.test.mjs'), resolve(root, 'tests/webhooks.test.mjs'), resolve(root, 'tests/audit-search.test.mjs'), resolve(root, 'tests/portable-template.test.mjs'), resolve(root, 'tests/application-export.test.mjs'), resolve(root, 'tests/audit-export.test.mjs'), resolve(root, 'tests/round-comparison.test.mjs'), resolve(root, 'tests/quick-designer.test.mjs'), resolve(root, 'tests/round-diagram.test.mjs'), resolve(root, 'tests/application-search.test.mjs'), resolve(root, 'tests/business-calendars.test.mjs'), resolve(root, 'tests/comments.test.mjs'), resolve(root, 'tests/first-workflow.test.mjs'), resolve(root, 'tests/operations.test.mjs'), resolve(root, 'tests/definition-assignees.test.mjs'), resolve(root, 'tests/api-reference.test.mjs'), resolve(root, 'tests/requests.test.mjs'), resolve(root, 'tests/authentication.test.mjs'), resolve(root, 'tests/forms.test.mjs'), resolve(root, 'tests/templates.test.mjs'), resolve(root, 'tests/confirmation.test.mjs'), resolve(root, 'tests/system-checks.test.mjs'), resolve(root, 'tests/simulation.test.mjs'), resolve(root, 'tests/comparison.test.mjs'), resolve(root, 'tests/designer-graph.test.mjs'), resolve(root, 'tests/layout.test.mjs'), resolve(root, 'tests/autosave.test.mjs'), resolve(root, 'tests/workspace.test.mjs'), resolve(root, 'tests/task-actions.test.mjs'), resolve(root, 'tests/task-action-panel.test.mjs'), resolve(root, 'tests/escalation-config.test.mjs'), resolve(root, 'tests/notification-deliveries.test.mjs'), resolve(root, 'tests/notification-preferences.test.mjs'), resolve(root, 'tests/notifications.test.mjs'), resolve(root, 'tests/expense-partial-adjustment-notifications.test.mjs'), resolve(root, 'tests/expense-adjustment-notifications.test.mjs'), resolve(root, 'tests/payment-notifications.test.mjs'), resolve(root, 'tests/supplier-payment-notifications.test.mjs'), resolve(root, 'tests/voucher-notifications.test.mjs'), resolve(root, 'tests/task-queue.test.mjs')])], {
  env: { ...process.env, AGENTFLOW_TEST_ATTACHMENT_FIELD: resolve(output, 'AttachmentField.js'), AGENTFLOW_TEST_ATTACHMENTS: resolve(output, 'attachments.js'), AGENTFLOW_TEST_FIELD_PREVIEW: resolve(output, 'FieldPermissionPreview.js'), AGENTFLOW_TEST_FIELD_EDITOR: resolve(output, 'FormSchemaEditor.js'), AGENTFLOW_TEST_INITIATOR_PANEL: resolve(output, 'InitiatorAppointmentPicker.js'), AGENTFLOW_TEST_ORGANIZATION_PANEL: resolve(output, 'OrganizationDirectoryPanel.js'), AGENTFLOW_TEST_ORGANIZATION: resolve(output, 'organization.js'), AGENTFLOW_TEST_DEADLINE: resolve(output, 'taskDeadline.js'), AGENTFLOW_TEST_AVAILABILITY_PANEL: resolve(output, 'DefinitionAvailabilityPanel.js'), AGENTFLOW_TEST_TASK_PANEL: resolve(output, 'TaskActionPanel.js'), AGENTFLOW_TEST_WORKBOOK_EXPORT: resolve(output, 'workbookExport.js'), AGENTFLOW_TEST_GROUPS: resolve(output, 'conditionGroups.js'), AGENTFLOW_TEST_ASSIST: resolve(output, 'assistRuns.js'), AGENTFLOW_TEST_SELECTION: resolve(output, 'definitionSelection.js'), AGENTFLOW_TEST_CATALOG: resolve(output, 'definitionCatalog.js'), AGENTFLOW_TEST_PRESENTATION: resolve(output, 'conditionPresentation.js'), AGENTFLOW_TEST_VALIDATION: resolve(output, 'designerValidation.js'), AGENTFLOW_TEST_WEBHOOKS: resolve(output, 'webhooks.js'), AGENTFLOW_TEST_AUDIT_SEARCH: resolve(output, 'auditSearch.js'), AGENTFLOW_TEST_PORTABLE: resolve(output, 'portableTemplate.js'), AGENTFLOW_TEST_APPLICATION_EXPORT: resolve(output, 'applicationExport.js'), AGENTFLOW_TEST_ROUND_COMPARISON: resolve(output, 'roundComparison.js'), AGENTFLOW_TEST_QUICK: resolve(output, 'quickDesigner.js'), AGENTFLOW_TEST_CONDITIONS: resolve(output, 'conditionBuilder.js'), AGENTFLOW_TEST_DIAGRAM: resolve(output, 'roundDiagram.js'), AGENTFLOW_TEST_APPLICATION_SEARCH: resolve(output, 'applicationSearch.js'), AGENTFLOW_TEST_CALENDARS: resolve(output, 'businessCalendars.js'), AGENTFLOW_TEST_COMMENTS: resolve(output, 'applicationComments.js'), AGENTFLOW_TEST_GUIDE: resolve(output, 'firstWorkflow.js'), AGENTFLOW_TEST_OPERATIONS: resolve(output, 'approvalOperations.js'), AGENTFLOW_TEST_ASSIGNEES: resolve(output, 'definitionAssignees.js'), AGENTFLOW_TEST_API_REFERENCE: resolve(output, 'apiReference.js'), AGENTFLOW_TEST_TASK_QUEUE: resolve(output, 'pendingTaskQueue.js'), AGENTFLOW_TEST_NOTIFICATIONS: resolve(output, 'notificationInbox.js'), AGENTFLOW_TEST_TASK_ACTIONS: resolve(output, 'taskActions.js'), AGENTFLOW_TEST_WORKSPACE: resolve(output, 'workspaceRecords.js'), AGENTFLOW_TEST_API: resolve(output, 'api.js'), AGENTFLOW_TEST_FORMS: resolve(output, 'formSchema.js'), AGENTFLOW_TEST_TEMPLATES: resolve(output, 'templateCenter.js'), AGENTFLOW_TEST_SIMULATION: resolve(output, 'definitionSimulation.js'), AGENTFLOW_TEST_COMPARISON: resolve(output, 'definitionComparison.js'), AGENTFLOW_TEST_LAYOUT: resolve(output, 'designerLayout.js'), AGENTFLOW_TEST_AUTOSAVE: resolve(output, 'draftAutosave.js'), AGENTFLOW_TEST_DESIGNER_GRAPH: resolve(output, 'designerGraph.js'), AGENTFLOW_TEST_SYSTEM: resolve(output, 'systemChecks.js'), AGENTFLOW_TEST_CONFIRMATION: resolve(output, 'unsavedConfirmation.js') }, stdio: 'inherit'
})
process.exitCode = result.status ?? 1
