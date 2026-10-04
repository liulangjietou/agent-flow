import { readPriorRequestPage, readPriorAssessments } from './expensePriorControl.js'
import type { ExpensePartialAdjustmentNotificationTarget } from './expensePartialAdjustmentNotification'
export type { ExpensePartialAdjustmentNotificationTarget } from './expensePartialAdjustmentNotification'
import type { ExpenseAdjustmentNotificationTarget } from './expenseAdjustmentNotification'
export type { ExpenseAdjustmentNotificationTarget } from './expenseAdjustmentNotification'
import { readServiceTaskDirectory, readServiceTaskOption, readServiceTaskVersions, type ServiceTaskDirectory, type ServiceTaskOption, type ServiceTaskVersions } from './serviceTasks.js'
import { policyGuidanceQuery, readPolicyGuidance, type PolicyGuidanceContext } from './expensePolicyGuidance.js'
import { readMappingCurrent, readMappingDraft, readMappingDirectory, readPublishedMapping, readMappingVersions, readMappingActivations, readMappingDraftRevision, validateMappingReceipt, validateMappingPublicationHistory, type MappingScope, type MappingDraft, type MappingCurrent, type MappingDraftInput, type MappingPublishInput } from './accountMappings.js'
import { readExpenseCategories, readExpenseConfiguration, readPolicyDraft, readPublishedPolicy, readPolicyDirectory, readCategoryHistory, readPolicyHistory, readActivationHistory, readCategoryRevision, readPolicyDraftRevision, validateConfigurationReceipt, type CategoryInput, type PolicyDraftInput, type PolicyPublishInput, type ExpenseCategories, type ExpensePolicyDraft, type ExpenseConfigurationCurrent } from './expenseConfiguration.js'
import type { AdvanceOffsetSuggestion } from './advanceOffsetSuggestion'
import { readExpenseRequestCloseReceipt, type ExpenseRequestCloseInput, type ExpenseRequestCloseReceipt } from './expenseRequestClosure.js'
import { draftAssistPath, readDraftInput, readDraftPage, readDraftDetail, validateDraftReceipt, type DraftAssistReceipt, type GenerateDraftInput, type ReviewDraftInput } from './draftAssist.js'
import { explanationPath, readExplanationInput, readExplanationPage, readExplanationDetail, validateExplanationReceipt, type ExplanationReceipt, type ExplanationGenerate, type ExplanationReview } from './precheckExplanation.js'
import { riskPath, readRiskInput, readRiskPage, readRiskDetail, readRiskCalendars, validateRiskReceipt, type RiskRequest, type RiskGenerate, type RiskReview, type RiskReceipt } from './expenseRisk.js'
import { expenseAssistPath, readExpenseAssistPreview, readExpenseAssistPage, readExpenseAssistDetail, validateExpenseAssistReceipt, type ExpenseAssistRequest, type ExpenseAssistGenerate, type ExpenseAssistConfirm, type ExpenseAssistDismiss, type ExpenseAssistReceipt } from './expenseDraftAssist.js'
import { extractionPath, readExtractionOptions, readExtractionPage, readExtractionDetail, validateExtractionReceipt, type ExtractionReceipt, type ExtractionGenerate, type ExtractionReview } from './invoiceExtraction.js'
import { readNotificationPreferences, validateNotificationPreferencesReceipt, type NotificationPreferences, type NotificationPreferencesInput } from './notificationPreferences.js'
import { approvalProxyPath, readApprovalProxy, readApprovalProxyPage, validateApprovalProxyReceipt, type ApprovalProxyInput, type ApprovalProxyReceipt } from './approvalProxies.js'
import { syncPath, type SyncPlanReceipt, type SyncReceipt, type SyncSelection } from './organizationSync.js'
import { readSyncBatches, readSyncDetail, readSyncLocalPage, readSyncOverview, readSyncPlan, readSyncPlans, readSyncTransitions, validateSyncReceipt } from './organizationSyncRead.js'
import { readInitializationState, validateInitializationReceipt, type InitializationReceipt, type InitializationRequest } from './tenantInitialization.js'
import { readDeliveryPage, readDeliveryDetail, readDeliveryHistory, validateDeliveryRetryReceipt, type NotificationDelivery, type NotificationDeliveryFilters, type NotificationDeliveryRetryInput } from './notificationDeliveries.js'
import type { AdjustmentDisputeView, AdjustmentDisputeInput, AdjustmentDisputeReceipt } from './supplierAdjustmentDispute'
import type { SupplierAdjustmentView, SupplierAdjustmentPrepareInput, SupplierAdjustmentActionInput, SupplierAdjustmentReceipt } from './supplierAdjustment'
import type { PaymentBatchInput, PaymentBatchReceipt, PaymentBatchPage, PaymentBatchDetail } from './paymentBatches'
import type { SupplierCashierView, SupplierCashierPage, SupplierCashierAccounts, SupplierCashierInput, SupplierCashierReceipt } from './supplierCashier'
import type { SupplierFinanceView, SupplierReviewInput, SupplierAuthorizeInput, SupplierHoldInput, SupplierFinanceReceipt } from './supplierFinance'
import type { BudgetFinanceView, BudgetFinancePage, BudgetFinanceReviewInput, BudgetFinanceAuthorizeInput, BudgetFinanceActionInput, BudgetFinanceReceipt } from './budgetFinance'
import type { SupplierDisputeView, SupplierDisputeQueryInput, SupplierDisputeResolveInput, SupplierDisputeReceipt } from './supplierDispute'
import type { SettlementDisputeView, SettlementDisputeInput, SettlementDisputeReceipt } from './supplierSettlementDispute'
import type { SupplierReturnView, SupplierReturnQueryInput, SupplierReturnRegisterInput, SupplierReturnReceipt } from './supplierReturn'
import type { SupplierSettlementView, SupplierSettlementPrepareInput, SupplierSettlementActionInput, SupplierSettlementReceipt } from './supplierSettlement'
import type { OrganizationUnit, OrganizationPerson, OrganizationAppointment, OrganizationRecord, OrganizationPage, OrganizationChange } from './organization'
import type { InitiatorContext, InitiatorAppointmentPage } from './initiatorContext'
import type { InitiatorRequirementsView } from './initiatorRequirements'
import type { SubprocessRelationsPage } from './subprocessRelations'
import type { NotificationTexts } from './notificationTexts'
import type { AssistRunDetail, AssistRunFilter, AssistRunPage, AssistInputOptions, AssistReceipt, AssistGenerateRequest, AssistReviewRequest } from './assistRuns'
import type { WebhookFilters, WebhookPage, WebhookTarget, WebhookDetail, WebhookItem, WebhookOverview, WebhookOverviewFilters } from './webhooks'
import type { RoundDiagram } from './roundDiagram'
import { validateTimerReceipt, type TimerView, type TimerRetryInput, type TimerReceipt } from './timerWaits.js'
import { validateInstanceReceipt, type InstanceControlAction, type InstanceControlInput, type InstanceControlView } from './instanceControl.js'
import type { AuditSearchFilters, AuditSearchPage } from './auditSearch'
import type { ApplicationSearchFilters, ApplicationSearchPage } from './applicationSearch'
import { readServiceTaskRuntime, type ServiceTaskRuntimeView } from './serviceTaskRuntime.js'
import { signaturePath, signatureWritePath, readSignatureOptions, readSignaturePage, readSignatureView, validateSignatureReceipt, type SignatureReceipt, type SignatureInput } from './signatures.js'
import type { DefinitionCatalogFilters, DefinitionCatalogPage } from './definitionCatalog'
import { workbookType, type ApplicationExportFilters } from './applicationExport.js'
import type { AuditExportFilters } from './auditSearch'
import type { BusinessCalendar, CalendarInput, CalendarUpdate, CalendarPage, CalendarVersionPage, CalendarCalculationInput, CalendarCalculation, CalendarSummary } from './businessCalendars'
import type { FirstWorkflowReport } from './firstWorkflow'
import type { ApplicationComment, CommentDraft, CommentPage, CommentQuery } from './applicationComments'
import { readCommentMentionPage, validateCommentReceipt, type CommentMentionFilter, type CommentMentionPage } from './commentMentions.js'
import type { OperationsFilter, OperationsReport } from './approvalOperations'
import type { AssigneeOption } from './definitionAssignees'
import type { FormAssigneeOption } from './formAssignees'
import type { ApiDocument } from './apiReference'
import { PendingWrites, type WriteRequest } from './pendingWrites.js'
import { validateTaskAssignmentReceipt } from './taskActions.js'
import { validateCountersignReceipt, type CountersignInput, type CountersignReceipt, type CountersignView } from './countersignMembership.js'
import type { PaymentCallbackPage, PaymentCallbackDetail, PaymentCallbackView } from './paymentCallbacks'
import { readEventDirectory, readEventVersions, readEventOption, readEventContract, readEventContractHistory, readEventInboxItem, readEventInboxPage, readEventInboxHistory, readEventWaits, validateEventMutation, type EventDirectory, type EventVersions, type EventOption, type EventContract, type EventContractHistory, type EventPublication, type EventAvailabilityInput, type EventInboxItem, type EventInboxPage, type EventInboxHistory, type EventRetryInput, type EventWaitView } from './events.js'
import type { FieldErrors, FormSchema } from './formSchema'
import type { AttachmentInput, AttachmentMetadata, AttachmentOptions } from './attachments'
import type { ExpenseDetail, ExpenseWorkflow, ExpensePage, ExpenseItem, ExpenseFilter, PriorRequestItem, AdvanceItem, ExpenseCommand, ExpenseTaskCommand, ExpenseReduction, ExpenseReceipt } from './expenses'
import type { FinanceCatalog, ExpenseCreate, ExpenseRevise, PrecheckOptions, PrecheckInput, PrecheckView, InvoiceItem } from './expenseDraft'
import type { InvoiceOriginal, InvoiceUploadInput, InvoiceWalletOptions, InvoiceVerificationOptions, InvoiceVerificationInput, InvoiceVerificationJob } from './invoiceWallet'
import type { AdvanceRequestItem, AdvanceDetail, AdvanceCreate, AdvanceRevise, AdvanceReceipt, AdvanceVersions, AdvanceCheckOptions, AdvanceCheckInput, AdvanceCheckView } from './advanceRequest'
import type { ProcurementPaymentItem, ProcurementDetail, ProcurementCreate, ProcurementRevise, ProcurementReceipt, ProcurementVersions, ProcurementCheckOptions, ProcurementCheckInput, ProcurementCheckView } from './procurementPayment'
import type { BudgetAdjustmentItem, BudgetAdjustmentDetail, BudgetAdjustmentCreate, BudgetAdjustmentRevise, BudgetAdjustmentReceipt, BudgetAdjustmentVersions, BudgetAdjustmentCheckOptions, BudgetAdjustmentCheckInput, BudgetAdjustmentCheckView } from './budgetAdjustment'
import type { RepaymentView, RepaymentQueryInput, RepaymentRecordInput, RepaymentActionReceipt } from './advanceRepayment'
import type { DisbursementReturnView, DisbursementReturnQueryInput, DisbursementResolutionInput, DisbursementReturnActionReceipt } from './disbursementReturn'
import type { RepaymentReviewView, RepaymentReviewQueryInput, RepaymentResolutionInput, RepaymentReviewActionReceipt } from './repaymentReview'
import type { PaymentView, FinancePaymentView, CashierPaymentView, CashierPaymentPage, CashierPaymentFilter, CashierFilterOptions, PaymentAccounts, PaymentAuthorizationInput, FinancePaymentActionInput, CashierPaymentActionInput, FinancePaymentReceipt, CashierPaymentReceipt, PayeeReviewInput, PayeeReviewReceipt, PaymentDisputeInput, PaymentDisputeReceipt } from './payments'
import type { VoucherActionInput, VoucherReceipt, VoucherView, VoucherDisputeInput, VoucherDisputeReceipt } from './vouchers'
import type { VoucherReversalView, ReversalQueryInput, ReversalRecordInput, ReversalActionReceipt } from './voucherReversal'
import type { VoucherReversalExecutionView, ReversalPrepareInput, ReversalAuthorizeInput, ReversalOperationInput, ReversalExecutionReceipt, ReversalRetirementInput, ReversalRetirementReceipt } from './voucherReversalExecution'
import type { SettlementView, SettlementRetry, SettlementReceipt } from './expenseSettlement'
import type { ExpenseReturnView, ExpenseReturnQueryInput, ExpenseReturnRegisterInput, ExpenseReturnActionReceipt } from './expensePaymentReturn'
import type { AdjustmentView, AdjustmentPrepareInput, AdjustmentAuthorizeInput, AdjustmentOperationInput, AdjustmentRetireInput, AdjustmentPreparationReceipt, AdjustmentActionReceipt } from './expenseResourceAdjustment'
import type { PartialView, PartialReceipt, PartialCreateInput, PartialOriginalInput, PartialSourceInput, PartialPrepareInput, PartialAuthorizeInput, PartialActionInput, PartialRetireInput, PartialDisputeInput } from './expensePartialAdjustment'
import type { ExpenseArchiveView } from './expenseArchive'
import type { PlanItem, PlanDetail, PlanCreate, PlanRevise, PlanReceipt, PlanVersions, PlanCheckOptions, PlanCheckInput, PlanCheckView } from './expensePlan'
const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

/** 登录方式由部署配置决定，防伪令牌只保留在内存。@author owlzhangfq@gmail.com */
export interface AuthOptions { mode: 'DEMO' | 'OIDC' | 'UNCONFIGURED'; loginUrl?: string; csrfHeader?: string; csrfToken?: string; csrfParameter?: string; providerLogoutUrl?: string }
const AUTH_OPTIONS_TIMEOUT_MS = 10_000
let authentication: AuthOptions | null = null
let requestActor: Pick<Actor, 'tenantId' | 'userId'> | null = null

/** 请求绑定页面已接受的账号，防止其他标签切换会话后以新账号提交旧页面。 */
export function bindAuthenticationActor(actor: Actor | null) {
  requestActor = actor
  writeRequests.setActor(actor)
}

export interface ApiError { status: number; code: string; message: string; details?: { fieldErrors?: FieldErrors; definitionErrors?: string[] } }
/** 当前设计的模拟输入。@author owlzhangfq@gmail.com */
export interface SimulationInput { graph: Graph; formSchema: FormSchema | null; values: Record<string, unknown>; splitRoutingAmount?: import('./expenses').Money }
/** 仅使用设计器测试填写内容的字段权限预览。@author owlzhangfq@gmail.com */
export interface FieldPreviewInput { formSchema: FormSchema; values: Record<string, unknown>; nodeIds: string[] }
/** 正式读取与设计预览共用的服务端投影。@author owlzhangfq@gmail.com */
export interface FieldPreviewResult { schema: FormSchema; payload: Record<string, unknown>; restricted: boolean }
/** 与发布基线比较的完整当前配置。@author owlzhangfq@gmail.com */
export interface ComparisonInput { key: string; name: string; graph: Graph; formSchema: FormSchema | null; notificationTexts?: NotificationTexts }
/** 一个稳定对象的配置变化；缺少前后值表示该侧未配置。@author owlzhangfq@gmail.com */
export interface ComparisonChange {
  area: 'DEFINITION' | 'NODE' | 'EDGE' | 'ROUTING' | 'FORM' | 'FIELD' | 'LAYOUT'
  kind: 'ADDED' | 'REMOVED' | 'MODIFIED'; targetId: string; label: string; property: string; before?: unknown; after?: unknown
}
/** 服务端确认的基线和差异列表。@author owlzhangfq@gmail.com */
export interface ComparisonResult { baseline: { id: string; key: string; name: string; version: number }; changes: ComparisonChange[] }
/** 数字分支检查使用精确字符串示例，未填写单独表达。@author owlzhangfq@gmail.com */
export interface BranchDiagnostic {
  severity: 'ERROR' | 'WARNING'
  code: 'BRANCH_COVERAGE_GAP' | 'BRANCH_OVERLAP' | 'BRANCH_COVERAGE_UNPROVEN'
  gatewayId: string; field: string; edgeIds: string[]; sampleValue?: string | null; missingValue: boolean
}
/** 发布就绪检查同时返回阻断规则码与可定位的分支诊断。@author owlzhangfq@gmail.com */
export interface ValidationResult { errors: string[]; branchDiagnostics: BranchDiagnostic[] }
/** 服务端保存的发布事实；旧版本可能缺少完整记录。@author owlzhangfq@gmail.com */
export interface PublicationResponse {
  recorded: boolean
  publication: null | { definitionId: string; definitionVersion: number; publishedBy: string; authorizedRole: string; publishedAt: string; changeNote: string; validation: { nodeCount: number; edgeCount: number; fieldCount: number; formBound: boolean; checks: string[]; branchDiagnostics?: BranchDiagnostic[] } }
}
/** 不包含原测试数据的路径与分支依据。@author owlzhangfq@gmail.com */
export interface SimulationResult {
  path: string[]; edgeIds: string[]
  decisions: Array<{ nodeId: string; selectedEdgeId: string; branches: Array<{ edgeId: string; targetNodeId: string; condition: string; outcome: 'MATCHED' | 'NOT_MATCHED' | 'SKIPPED' | 'DEFAULT_SELECTED' | 'DEFAULT_SKIPPED' }> }>
}
export interface Actor { tenantId: string; userId: string; roles: string[] }
export interface GraphNode { id: string; name: string; type: string; properties: Record<string, string> }
export interface GraphEdge { id: string; source: string; target: string; condition: string; defaultBranch: boolean }
/** 提交时规则的明确分级，未知与未命中不等于低风险。@author owlzhangfq@gmail.com */
export type SubmissionRiskLevel = 'UNASSESSED' | 'UNMATCHED' | 'LOW' | 'MEDIUM' | 'HIGH'
/** 随流程版本发布的公共规则。@author owlzhangfq@gmail.com */
export interface RiskRule { id: string; label: string; level: 'LOW' | 'MEDIUM' | 'HIGH'; condition: string }
/** 有界、显式的风险规则集合。@author owlzhangfq@gmail.com */
export interface RiskPolicy { rules: RiskRule[] }
/** 本轮固定的风险结果及定义来源，不携带字段取值。@author owlzhangfq@gmail.com */
export interface SubmissionRisk { level: SubmissionRiskLevel; definitionId?: string | null; definitionVersion: number; matches: { ruleId: string; label: string; level: 'LOW' | 'MEDIUM' | 'HIGH' }[] }
export interface Graph { nodes: GraphNode[]; edges: GraphEdge[]; conditionLanguageVersion?: 1 | 2; riskPolicy?: RiskPolicy | null }
export interface Definition { id: string; key: string; name: string; revision: number; version: number; status: string; graph: Graph; formSchema: FormSchema | null; startEnabled?: boolean; notificationTexts?: NotificationTexts }
/** 版本停用恢复的实际操作依据。@author owlzhangfq@gmail.com */
export interface DefinitionAvailabilityChange { tenantId: string; definitionId: string; revision: number; previousEnabled: boolean; startEnabled: boolean; changedBy: string; authorizedRole: string; changedAt: string; reason: string }
/** 按修订降序的操作记录页。@author owlzhangfq@gmail.com */
export interface DefinitionAvailabilityHistory { items: DefinitionAvailabilityChange[]; nextBeforeRevision?: number }
/** 身份由服务端确定的治理操作。@author owlzhangfq@gmail.com */
export interface DefinitionAvailabilityInput { startEnabled: boolean; expectedRevision: number; reason: string }
export interface TemplateScenario { id: string; name: string; description: string; payload: Record<string, unknown>; expectedPath: string[]; expectedFieldErrors: Record<string, string> }
export interface TemplateCopy { definitionId: string; processKey: string; name: string; status: string; version: number; revision: number; templateVersion: number; copiedBy: string; copiedAt: string }
export interface TemplateCompanionSummary { key: string; version: number; name: string; description: string; scenarioCount: number }
export interface FinancialTemplateExamples {
  schemaVersion: number; key: string; version: number; name: string; description: string; templateKeys: string[]
  bindings: { key: string; exampleValue: string; instruction: string }[]; setupSteps: string[]
  configuration: Record<string, unknown>
  scenarios: { id: string; name: string; templateKey: string; content: Record<string, unknown>; steps: string[]; expected: string[]; reductions?: Record<string, unknown>[] }[]
}
export interface ProcessTemplate {
  key: string; templateVersion: number; name: string; category: string; description: string; scope: string; businessType: 'FORM' | 'PROCUREMENT_PAYMENT' | 'BUDGET_ADJUSTMENT' | 'EXPENSE' | 'EXPENSE_PLAN' | 'ADVANCE_REQUEST'
  dependencies: string[]; defaultRoles: string[]; fieldDescriptions: Record<string, string>; risks: string[]; upgradePolicy: string
  notificationTexts: Record<string, string>; notificationsAvailable: boolean; graph: Graph; formSchema: FormSchema
  scenarios: TemplateScenario[]; copies: TemplateCopy[]; companion?: TemplateCompanionSummary
}
export interface TemplateCopyInput { key: string; name: string; templateVersion: number }
/** 自检只读快照。@author owlzhangfq@gmail.com */
export interface SystemCheckReport {
  checkedAt: string
  checks: Array<{ id: string; status: 'UP' | 'DOWN' | 'UNKNOWN' | 'WARNING' | 'NOT_IMPLEMENTED'; code: string; message: string }>
}
export type TaskAction = 'CLAIM' | 'RELEASE' | 'TRANSFER' | 'DELEGATE' | 'RESOLVE' | 'RETURN' | 'REJECT' | 'APPROVE'
/** 当前可操作的任务快照；动作由服务端按委派状态限制。@author owlzhangfq@gmail.com */
export interface Task { taskId: string; taskName: string; assignee?: string; applicationId: string; createdAt: string; version: number; owner?: string; delegationState: 'NONE' | 'PENDING' | 'RESOLVED'; allowedActions: TaskAction[]; countersign?: { total: number; completed: number; mode?: 'ALL' | 'ANY' | 'PERCENT'; percentage?: number | null; required?: number }; dueAt?: string | null; canActDirectly?: boolean; proxyOptions?: ApprovalProxyOption[] }
/** 本次任务的直接代理依据；是否仍有效由提交时重新核对。@author owlzhangfq@gmail.com */
export interface ApprovalProxyOption {
  proxyId: string; revision: number; definitionId: string; principalId: string; principal: string; startsAt: string; endsAt: string
}
/** 审计保留办理当时的授权，后续到期或撤销不覆盖历史。@author owlzhangfq@gmail.com */
export interface ApprovalProxyUse extends ApprovalProxyOption { substituteId: string; authorizedAt: string }
/** 待办只读摘要不携带审批正文或可直接提交的动作版本。@author owlzhangfq@gmail.com */
export interface PendingTaskItem {
  taskId: string; taskName: string; applicationId: string; businessNo: string; title: string; processKey: string
  definitionVersion: number; applicant: string; amount: string | null; roundNo: number; assignee?: string
  owner?: string; delegationState: 'NONE' | 'PENDING' | 'RESOLVED'; createdAt: string; dueAt?: string | null
  legalEntityName?: string | null; departmentName?: string | null; positionName?: string | null
  risk: SubmissionRisk
}
/** 服务端筛选与分页参数。@author owlzhangfq@gmail.com */
export interface PendingTaskQuery {
  q?: string; processKey?: string; applicant?: string; organization?: string; assignment?: 'all' | 'assigned' | 'unclaimed' | 'delegated'
  deadline?: 'all' | 'overdue' | 'pending' | 'unrecorded'
  risk?: 'all' | 'unassessed' | 'unmatched' | 'low' | 'medium' | 'high'
  minAmount?: string; maxAmount?: string; limit?: number; cursor?: string
}
/** 当前筛选计数不会被已加载条数替代。@author owlzhangfq@gmail.com */
export interface PendingTaskPage { items: PendingTaskItem[]; nextCursor?: string | null; total: number }
/** 提交时保留任务快照版本，不在冲突后自动更新版本。@author owlzhangfq@gmail.com */
export interface TaskActionInput { action: TaskAction; comment?: string; targetUser?: string; expectedVersion: number; proxyId?: string }
export interface Application { id: string; businessNo: string; processKey: string; definitionVersion: number; createdBy: string; title: string; payload: Record<string, unknown>; formSchema: FormSchema | null; status: string; roundNo: number; version: number; businessReference?: { type: string; id: string } | null }
export interface SubmissionRound { roundNo: number; processInstanceId: string; definitionVersion: number; title: string; payload: Record<string, unknown>; formSchema: FormSchema | null; submittedBy: string; submittedAt: string; status: string; reason: string | null; completedBy: string | null; completedAt: string | null; initiatorContext?: InitiatorContext | null; risk: SubmissionRisk }
export interface HistoryEvent {
  id: string; sequence: number; occurredAt: string; source: string; action: string
  aggregateVersion?: number; roundNo?: number; actor?: string; targetUser?: string; comment?: string
  nodeId?: string; nodeName?: string; nodeType?: string; taskId?: string; processInstanceId?: string; definitionVersion?: number
  previousStatus?: string; currentStatus?: string
  membershipChange?: { executionId: string; targetTaskId: string; totalBefore: number; totalAfter: number; completed: number }
  proxyUse?: ApprovalProxyUse | null
}
export interface HistoryPage { items: HistoryEvent[]; nextCursor?: string | null }
export interface HistoryQuery { roundNo?: number; action?: string; from?: string; to?: string; cursor?: string; limit?: number }

/** 个人申请清单不携带表单正文。@author owlzhangfq@gmail.com */
export interface WorkspaceApplication {
  id: string; businessNo: string; title: string; processKey: string; definitionVersion: number
  status: string; roundNo: number; createdAt: string; updatedAt: string
}
/** 每一行代表本人的一次真实办理，保留当时结果与当前状态。@author owlzhangfq@gmail.com */
export interface WorkspaceHandled {
  id: string; taskId: string; applicationId: string; businessNo: string; title: string; processKey: string
  definitionVersion: number; applicationStatus: string; action: string; handledAt: string
  roundNo: number | null; nodeName: string | null; comment: string | null; targetUser: string | null; handledStatus: string | null
}
/** 个人工作台游标分页响应。@author owlzhangfq@gmail.com */
export interface WorkspacePage<T> { items: T[]; nextCursor?: string | null }
/** 服务端白名单内的工作台筛选。@author owlzhangfq@gmail.com */
export interface WorkspaceQuery { view?: 'started' | 'drafts'; q?: string; status?: string; action?: string; cursor?: string; limit?: number }

/** 消息保留发生时摘要；访问申请与任务仍需实时授权。@author owlzhangfq@gmail.com */
export interface InboxMessage {
  id: string; applicationId: string; title: string; businessNo: string; actor: string; roundNo: number
  kind: 'SUPPLIER_PAYABLE_RESULT' | 'SUPPLIER_PAYABLE_ATTENTION' | 'EXPENSE_PARTIAL_ADJUSTMENT_RESULT' | 'EXPENSE_PARTIAL_ADJUSTMENT_ATTENTION' | 'EXPENSE_ADJUSTMENT_RESULT' | 'EXPENSE_ADJUSTMENT_ATTENTION' | 'DISBURSEMENT_RETURN_RESULT' | 'DISBURSEMENT_RETURN_ATTENTION' | 'REPAYMENT_RESULT' | 'REPAYMENT_ATTENTION' | 'REPAYMENT_REVIEW_RESULT' | 'REPAYMENT_REVIEW_ATTENTION' | 'BUDGET_ADJUSTMENT_RESULT' | 'BUDGET_ADJUSTMENT_ATTENTION' | 'EXPENSE_RETURN_RESULT' | 'EXPENSE_RETURN_ATTENTION' | 'SUPPLIER_RETURN_RESULT' | 'SUPPLIER_RETURN_ATTENTION' | 'SUPPLIER_ADJUSTMENT_RESULT' | 'SUPPLIER_ADJUSTMENT_ATTENTION' | 'SUPPLIER_SETTLEMENT_RESULT' | 'SUPPLIER_SETTLEMENT_ATTENTION' | 'REVERSAL_RESULT' | 'REVERSAL_ATTENTION' | 'REVERSAL_CHECK_RESULT' | 'REVERSAL_CHECK_ATTENTION' | 'EXPENSE_SETTLEMENT_RESULT' | 'EXPENSE_SETTLEMENT_ATTENTION' | 'BUDGET_RESULT' | 'BUDGET_ATTENTION' | 'VOUCHER_RESULT' | 'VOUCHER_ATTENTION' | 'SUPPLIER_PAYMENT_RESULT' | 'SUPPLIER_PAYMENT_ATTENTION' | 'PAYMENT_RESULT' | 'PAYMENT_ATTENTION' | 'ADVANCE_OVERDUE' | 'TASK_ESCALATED' | 'COMMENT_MENTIONED' | 'APPLICATION_SUBMITTED' | 'TASK_PENDING' | 'APPLICATION_RETURNED' | 'APPLICATION_REJECTED' | 'APPLICATION_APPROVED' | 'APPLICATION_WITHDRAWN' | 'APPLICATION_CANCELLED' | 'TASK_TRANSFERRED' | 'TASK_DELEGATED' | 'TASK_RESOLVED' | 'TASK_OVERDUE' | 'APPLICATION_COPIED' | 'EXPENSE_ADJUSTED' | 'TASK_COUNTERSIGN_REMOVED' | 'TASK_COUNTERSIGN_COMPLETED' | 'APPLICATION_PAUSED' | 'APPLICATION_RESUMED'
  taskId?: string; nodeName?: string; createdAt: string; readAt?: string; content?: string | null
}
/** 个人消息列表和未读总数。@author owlzhangfq@gmail.com */
export interface CopySnapshot { applicationId: string; businessNo: string; roundNo: number; definitionVersion: number; title: string; status: string; submittedAt: string; nodeNames: string[]; formSchema: FormSchema | null; payload: Record<string, unknown> }
export interface InboxPage { items: InboxMessage[]; nextCursor?: string | null; unreadCount: number }
/** 明确打开本人消息后，按当前业务权限读取原付款。 */
export interface PaymentNotificationTarget { messageId: string; paymentId: string; view: 'APPLICATION_ROUND' | 'CASHIER_PAYMENT'; applicationId: string; roundNo: number; payment: PaymentView }
export interface SupplierPaymentNotificationTarget { messageId: string; paymentId: string; executionRequestId: string; view: 'APPLICATION_ROUND' | 'CASHIER_PAYMENT'; applicationId: string; roundNo: number; canOpenCashier: boolean; payment: SupplierCashierView }
export type FinancialNotificationTarget = PaymentNotificationTarget | SupplierPaymentNotificationTarget
export interface VoucherNotificationTarget {
  messageId: string; voucherId: string; applicationId: string; businessId: string; roundNo: number
  kind: VoucherView['kind']; preparation: VoucherView['preparation']; operation: VoucherView['operation']; reversalBound: boolean
}
/** 原预算操作只读摘要；不返回命令、分摊、账户或办理许可。 */
export interface BudgetNotificationTarget {
  messageId: string; operationId: string; applicationId: string; reportId: string; roundNo: number; financialVersion: number
  action: 'FREEZE' | 'ADJUST' | 'RELEASE' | 'CONSUME'; status: 'QUEUED' | 'EXECUTING' | 'UNKNOWN' | 'QUERYING' | 'APPLIED' | 'REJECTED'
  version: number; attempts: number; updatedAt: string; observedStatus: 'PENDING' | 'NOT_FOUND' | 'APPLIED' | 'REJECTED' | null
  issue: string | null; ledgerRevision: number | null; reference: string | null; appliedAt: string | null
}
/** 结算修订的最小事实，不包含办理许可或财务明细。 */
export interface ExpenseSettlementNotificationState {
  version: number; status: NonNullable<SettlementView['settlement']>['status']; resourcesConsumed: boolean
  budgetOperationId: string | null; issue: string | null; updatedAt: string
}
/** 原结算修订与当前状态分别展示，不携带核销或重试许可。 */
export interface ExpenseSettlementNotificationTarget {
  messageId: string; applicationId: string; reportId: string; roundNo: number; financialVersion: number
  funding: NonNullable<SettlementView['settlement']>['funding']; fundingConfirmedAt: string
  notice: ExpenseSettlementNotificationState; current: ExpenseSettlementNotificationState
}
/** 原外部冲销核对摘要，不包含登记许可或反向分录。 */
export interface ReversalCheckNotificationTarget {
  messageId: string; checkId: string; operationId: string; applicationId: string; businessId: string; roundNo: number
  kind: VoucherView['kind']; originalStatus: NonNullable<VoucherView['operation']>['status']; originalHeld: boolean
  version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RECORDED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null
  observation: { status: 'UNRESOLVED' | 'VERIFIED'; revision: number; observedAt: string; validUntil: string; voucherReference: string | null; accountingDate: string | null; postedAt: string | null } | null
  record: { id: string; recordedAt: string } | null
}
/** 固定原冲销准备或命令；安全结束记录与 ERP 结果分别展示。 */
export interface ReversalNotificationTarget {
  messageId: string; reversalId: string; operationId: string; applicationId: string; businessId: string; roundNo: number
  kind: VoucherView['kind']; originalStatus: NonNullable<VoucherView['operation']>['status']; originalHeld: boolean
  preparation: { id: string; version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'AUTHORIZED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; accountingDate: string; issue: string | null }
  operation: { id: string; version: number; status: 'QUEUED' | 'POSTING' | 'QUERYING' | 'UNKNOWN' | 'POSTED' | 'FAILED' | 'NOT_FOUND' | 'EXPIRED' | 'VOIDED' | 'RECONCILING'; attempts: number; highestRevision: number; updatedAt: string; expiresAt: string; observedStatus: 'PENDING' | 'POSTED' | 'FAILED' | 'NOT_FOUND' | null; issue: string | null; disputed: boolean; voucherReference: string | null; postedAt: string | null } | null
  retirement: { id: string; retiredAt: string; basis: 'NEVER_DISPATCHED' | 'CONFIRMED_FAILED' } | null
}
/** 同一原结算的当前准备、ERP 结果及实际完成事实，不携带办理许可。 */
export interface SupplierSettlementNotificationTarget {
  messageId: string; settlementId: string; paymentId: string; requestId: string; applicationId: string; roundNo: number; accountingDate: string
  fact: 'PREPARATION_RETRY' | 'PREPARATION_BLOCKED' | 'PREPARATION_VOIDED' | 'EXECUTION_RETRY' | 'UNKNOWN' | 'NOT_FOUND' | 'RECONCILING' | 'REJECTED' | 'VOIDED' | 'ERP_SETTLED' | 'COMPLETED' | 'RETIRED'
  preparation: { version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'BLOCKED' | 'VOIDED'; issue: string | null; updatedAt: string }
  operation: { version: number; status: 'QUEUED' | 'CHECKING' | 'SETTLING' | 'UNKNOWN' | 'QUERYING' | 'SETTLED' | 'REJECTED' | 'NOT_FOUND' | 'RECONCILING' | 'VOIDED'; issue: string | null; updatedAt: string } | null
  retirement: { basis: 'NEVER_DISPATCHED' | 'CONFIRMED_REJECTED'; retiredAt: string } | null
  completion: { operationId: string; operationVersion: number; paymentId: string; completedAt: string } | null
}
/** 原供应商应付调整、ERP 结果与本地完成的只读摘要。 */
export interface SupplierAdjustmentNotificationTarget {
  messageId: string; adjustmentId: string; paymentId: string; requestId: string; applicationId: string; roundNo: number; accountingDate: string
  fact: 'PREPARATION_RETRY' | 'PREPARATION_BLOCKED' | 'PREPARATION_VOIDED' | 'EXECUTION_RETRY' | 'UNKNOWN' | 'NOT_FOUND' | 'RECONCILING' | 'REJECTED' | 'VOIDED' | 'ERP_ADJUSTED' | 'COMPLETED' | 'RETIRED'
  preparation: { version: number; status: 'QUEUED' | 'RUNNING' | 'READY' | 'BLOCKED' | 'VOIDED'; issue: string | null; updatedAt: string }
  operation: { version: number; status: 'QUEUED' | 'CHECKING' | 'ADJUSTING' | 'UNKNOWN' | 'QUERYING' | 'ADJUSTED' | 'REJECTED' | 'NOT_FOUND' | 'RECONCILING' | 'VOIDED'; issue: string | null; updatedAt: string } | null
  retirement: { basis: 'NEVER_DISPATCHED' | 'CONFIRMED_REJECTED'; retiredAt: string } | null
  completion: { adjustmentId: string; adjustmentVersion: number; paymentId: string; paymentVersion: number; returnVersion: number; completedAt: string } | null
}
/** 原供应商回款核对与其实际登记的只读摘要。 */
export interface SupplierReturnNotificationTarget {
  messageId: string; checkId: string; paymentId: string; requestId: string; applicationId: string; roundNo: number
  fact: 'UNAVAILABLE' | 'SOURCE_CHANGED' | 'UNRESOLVED' | 'RETURN_REVIEW' | 'RECORDED'
  version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RESOLVED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null
  observation: { outcome: 'UNRESOLVED' | 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'; revision: number; observedAt: string; validUntil: string } | null
  registration: { id: string; returnVersion: number; outcome: 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'; registeredAt: string } | null
}
/** 原报销退回核对与其实际登记的只读摘要。 */
export interface ExpenseReturnNotificationTarget {
  messageId: string; checkId: string; paymentId: string; reportId: string; applicationId: string; roundNo: number
  fact: 'UNAVAILABLE' | 'SOURCE_CHANGED' | 'UNRESOLVED' | 'RETURN_REVIEW' | 'RECORDED'
  version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RESOLVED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null
  observation: { outcome: 'UNRESOLVED' | 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'; revision: number; observedAt: string; validUntil: string } | null
  registration: { id: string; returnVersion: number; outcome: 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'; registeredAt: string } | null
}
/** 原借款放款退回核对与其实际登记的只读摘要。 */
export interface DisbursementReturnNotificationTarget {
  messageId: string; checkId: string; paymentId: string; advanceId: string; applicationId: string; roundNo: number
  fact: 'UNAVAILABLE' | 'SOURCE_CHANGED' | 'UNRESOLVED' | 'RETURN_REVIEW' | 'REVIEW_REQUIRED' | 'RESOLVED'
  version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RESOLVED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null
  observation: { outcome: 'UNRESOLVED' | 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'; revision: number; observedAt: string; validUntil: string } | null
  resolution: { id: string; advanceVersion: number; outcome: 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'; resolvedAt: string } | null
}
/** 原供应商复核与预留的只读消息摘要。 */
export interface SupplierPayableNotificationTarget {
  messageId: string; requestId: string; applicationId: string; roundNo: number; sourceType: 'REVIEW' | 'OPERATION'; sourceId: string
  fact: 'REVIEW_UNAVAILABLE' | 'REVIEW_BLOCKED' | 'REVIEW_SOURCE_CHANGED' | 'REVIEW_INTERRUPTED' | 'UNKNOWN' | 'NOT_FOUND' | 'REJECTED' | 'HELD' | 'RECONCILING' | 'EXPIRED' | 'VOIDED' | 'RETIRED'
  review: { id: string; version: number; status: keyof typeof import('./supplierFinance').reviewLabels; requestedAt: string; updatedAt: string; issue: string | null } | null
  operation: { id: string; version: number; status: keyof typeof import('./supplierFinance').holdLabels; createdAt: string; updatedAt: string; failure: string | null; observation: SupplierPayableNotificationObservation | null; conflictingObservation: SupplierPayableNotificationObservation | null } | null
  retirement: { operationId: string; operationVersion: number; basis: 'NEVER_DISPATCHED' | 'CONFIRMED_REJECTED'; retiredAt: string } | null
}
export interface SupplierPayableNotificationObservation { outcome: 'HELD' | 'REJECTED' | 'PENDING' | 'NOT_FOUND'; revision: number; observedAt: string; heldAt: string | null; rejection: string | null }
/** 原预算调整复核与执行事实的只读摘要。 */
export interface BudgetAdjustmentNotificationTarget {
  messageId: string; requestId: string; applicationId: string; roundNo: number; sourceType: 'REVIEW' | 'OPERATION'; sourceId: string
  fact: 'REVIEW_UNAVAILABLE' | 'REVIEW_BLOCKED' | 'REVIEW_SOURCE_CHANGED' | 'UNKNOWN' | 'NOT_FOUND' | 'REJECTED' | 'APPLIED' | 'RECONCILING' | 'EXPIRED' | 'VOIDED' | 'RETIRED'
  review: { id: string; version: number; status: keyof typeof import('./budgetFinance').budgetReviewLabels; requestedAt: string; updatedAt: string; issue: string } | null
  operation: { id: string; version: number; status: keyof typeof import('./budgetFinance').budgetOperationLabels; createdAt: string; updatedAt: string; failure: string | null; observation: BudgetAdjustmentNotificationObservation | null; conflictingObservation: BudgetAdjustmentNotificationObservation | null } | null
  retirement: { operationId: string; operationVersion: number; basis: 'NEVER_SENT' | 'REJECTED'; retiredAt: string } | null
}
export interface BudgetAdjustmentNotificationObservation { outcome: 'APPLIED' | 'REJECTED' | 'PENDING' | 'NOT_FOUND'; revision: number; observedAt: string; appliedAt: string | null; rejection: string | null }
export interface RepaymentReviewNotificationTarget {
  messageId: string; checkId: string; paymentId: string; advanceId: string; repaymentId: string; applicationId: string; roundNo: number
  fact: 'UNAVAILABLE' | 'SOURCE_CHANGED' | 'UNRESOLVED' | 'RETURN_REVIEW' | 'REVIEW_REQUIRED' | 'RESOLVED'
  version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RESOLVED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null
  observation: { outcome: 'UNRESOLVED' | 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'; revision: number; observedAt: string; validUntil: string } | null
  resolution: { id: string; advanceVersion: number; outcome: 'CONFIRMED' | 'PARTIALLY_RETURNED' | 'RETURNED'; resolvedAt: string } | null
}
/** 原还款查询、实际登记和本次触发的复核分别保留。 */
export interface RepaymentNotificationTarget {
  messageId: string; checkId: string; paymentId: string; advanceId: string; applicationId: string; roundNo: number
  fact: 'UNAVAILABLE' | 'SOURCE_CHANGED' | 'NOT_FOUND' | 'PENDING' | 'REVERSED' | 'REVIEW_REQUIRED' | 'RECORDED'
  version: number; status: 'QUEUED' | 'RUNNING' | 'CHECKED' | 'RECORDED' | 'UNAVAILABLE' | 'VOIDED'; requestedAt: string; updatedAt: string; issue: string | null
  reviewRepaymentId: string | null
  observation: { outcome: 'NOT_FOUND' | 'PENDING' | 'CONFIRMED' | 'REVERSED'; revision: number; observedAt: string; validUntil: string } | null
  record: { id: string; advanceVersion: number; recordedAt: string } | null
}
/** 已读筛选与稳定分页游标。@author owlzhangfq@gmail.com */
export interface InboxQuery { read?: 'all' | 'unread'; limit?: number; cursor?: string }

function historyQuery(query: object) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.size ? '?' + params.toString() : ''
}

async function request<T>(path: string, init: RequestInit = {}, format: 'json' | 'xlsx' | 'binary' | 'zip' = 'json'): Promise<T> {
  const headers = new Headers(init.headers)
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = localStorage.getItem('agentflow.token')
  if (token && authentication?.mode !== 'OIDC') headers.set('Authorization', `Bearer ${token}`)
  if (authentication?.mode === 'OIDC' && !['GET', 'HEAD', 'OPTIONS'].includes((init.method ?? 'GET').toUpperCase())) {
    if (!authentication.csrfToken || authentication.csrfHeader !== 'X-CSRF-TOKEN') {
      throw { status: 403, code: 'CSRF_INVALID', message: '会话验证信息已失效，请刷新登录状态后恢复原操作。' } satisfies ApiError
    }
    headers.set(authentication.csrfHeader, authentication.csrfToken)
  }
  if (authentication?.mode === 'OIDC' && requestActor && !['/auth/me', '/auth/options'].includes(path)) {
    headers.set('X-AgentFlow-Actor', encodeURIComponent(JSON.stringify([requestActor.tenantId, requestActor.userId])))
  }
  const businessWrite = headers.has('Idempotency-Key')
  let response: Response
  try { response = await fetch(`${API_BASE}${path}`, { ...init, headers }) }
  catch { throw { status: 0, code: 'NETWORK_ERROR', message: businessWrite ? '连接中断，操作结果尚未确认。请恢复上次操作。' : '无法连接服务，请稍后重试。' } satisfies ApiError }
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    let code = 'HTTP_ERROR'
    let details: ApiError['details']
    try { const body = await response.json() as { message?: string; code?: string; details?: ApiError['details'] }; message = body.message ?? message; code = body.code ?? code; details = body.details } catch { /* 已收到明确状态码，保留错误分类。 */ }
    const messages: Record<string, string> = {
      NOT_FOUND: '记录不存在或当前账号无权查看，请刷新列表或返回原入口。',
      ATTACHMENT_NOT_READY: '附件尚未上传完成，请恢复上传后再提交。',
      ATTACHMENT_TOO_LARGE: '文件超过当前部署的大小上限，请选择其他文件。',
      ATTACHMENT_QUOTA_EXCEEDED: '本申请累计上传已达到限制，请联系管理员核对存储配置。移除引用不会释放历史文件占用。',
      ATTACHMENT_CONTENT_MISMATCH: '文件内容与登记时不一致，请使用原文件重试，或另行添加新文件。',
      ATTACHMENT_STORAGE_UNAVAILABLE: '附件存储未配置或暂时不可用，请联系管理员。',
      ATTACHMENT_INTEGRITY_FAILED: '文件缺失或完整性校验失败，未提供下载。请联系管理员核对备份和存储。',
      INVALID_ATTACHMENT_REFERENCE: '附件不属于本申请的这个字段，请重新上传。',
      CSRF_INVALID: '会话验证信息已失效，请刷新登录状态后恢复原操作。',
      AUDIT_EXPORT_LIMIT_EXCEEDED: '匹配操作超过 10,000 条，请按操作日期、账号或关联申请缩小筛选后再导出。未生成截断文件。',
      AUDIT_EXPORT_BUSY: '服务正在生成另一份审计导出，请稍后重试。',
      AUDIT_EXPORT_FAILED: '审计文件生成失败，请重试。',
      INVALID_AUDIT_QUERY: '审计筛选条件无效，请重新查询后再导出。',
      APPLICATION_EXPORT_LIMIT_EXCEEDED: '匹配申请超过 10,000 份，请按创建日期、流程或申请人缩小筛选后再导出。未生成截断文件。',
      APPLICATION_EXPORT_BUSY: '服务正在生成另一份导出，请稍后重试。',
      APPLICATION_EXPORT_FAILED: '文件生成失败，请重试。',
      INVALID_APPLICATION_QUERY: '筛选条件无效，请重新查询后导出。',
      IDEMPOTENCY_KEY_REUSED: '上次请求键对应其他内容，本次未重新执行。请先查询当前业务状态。',
      IDEMPOTENCY_KEY_EXPIRED: '上次操作的恢复期限已过，未重新执行。请先查询当前业务状态。',
      INVALID_FIRST_WORKFLOW_QUERY: '流程进度筛选无效，请重新选择流程。',
      INVALID_CALENDAR_QUERY: '日历分页参数无效，请重新读取。',
      INVALID_CALENDAR: '请填写有效标识、名称和日历规则。',
      INVALID_CALENDAR_RULES: '请检查时区、重复日期和工作时段。时段须为 HH:mm 且不重叠，结束可为 24:00；至少保留一个工作时段。',
      INVALID_CALENDAR_CALCULATION: '请输入有效开始时间和 1 至 527040 个工作分钟。',
      CALENDAR_KEY_CONFLICT: '此日历标识已存在，请选择其他标识。',
      CALENDAR_NONEXISTENT_START: '此当地钟点因时区切换不存在，请选择其他开始时间。',
      CALENDAR_AMBIGUOUS_START: '此当地钟点出现两次，请选择第一次或第二次后试算。',
      CALENDAR_HORIZON_EXCEEDED: '未来 3660 天内没有足够工作时间，请核对规则或缩短时长。',
      INVALID_COMMENT_QUERY: '评论筛选或分页已失效，请重新查询。',
      COMMENT_MENTION_UNAVAILABLE: '提醒对象已不在本轮可提醒范围，请刷新名单后重新选择。',
  INVALID_OPERATIONS_QUERY: '统计筛选无效：请检查 UTC 日期范围、流程标识和版本，范围最多 366 天。',
      CONCURRENCY_CONFLICT: '数据已被其他操作更新，请重新加载并核对后再操作。',
      INVALID_NOTIFICATION_DELIVERY_QUERY: '投递筛选或分页已失效，请重新读取。',
      NOTIFICATION_DELIVERY_NOT_FOUND: '投递记录不存在或当前账号无权查看。',
      NOTIFICATION_DELIVERY_STATE_INVALID: '当前投递状态不允许人工重试，请重新读取。',
      NOTIFICATION_CONSENT_REVOKED: '当前收件资格或原通知偏好已失效，不能重试旧提醒。',
      NOTIFICATION_BINDING_UNAVAILABLE: '原收件绑定不可用或已经变更，不能把旧提醒改投新地址。',
      NOTIFICATION_DUPLICATE_ACK_REQUIRED: '接收方可能已经收到，请明确确认可能重复后再重试。',
      COUNTERSIGN_ASSIGNMENT_FIXED: '会签任务不能转交、释放或重新领取；可委派协助后回交，或使用独立加减签入口。',
      COUNTERSIGN_NO_MEMBERS: '会签节点当前没有有效审批人，本次操作未生效，请联系管理员补齐审批名单。',
      APPROVAL_RESPONSIBILITY_NO_MEMBERS: '按职责分离规则排除冲突人员后无人可办，本次操作未生效。请联系流程管理员核对审批名单。',
      APPROVAL_RESPONSIBILITY_CONFLICT: '所选人员与本节点的职责分离规则冲突，请重新选择。',
      APPROVAL_RESPONSIBILITY_SCOPE_INVALID: '当前审批职责记录无法核实，请刷新并联系管理员核对。',
      APPROVAL_RESPONSIBILITY_INVALID: '职责分离配置无效，请检查申请人限制和前序步骤引用。',
      TASK_DELEGATION_PENDING: '这项任务处于受托处理阶段，请填写意见并回交给原审批人。',
      COUNTERSIGN_STATE_INVALID: '当前会签事实无法核实，请刷新任务并核对流程状态。',
      COUNTERSIGN_MEMBER_UNAVAILABLE: '所选未决会签任务已变化，请重新读取名单。',
      COUNTERSIGN_MEMBER_LIMIT: '当前会签责任人数已达 100 人，不能继续增加。',
      COUNTERSIGN_MEMBER_EXISTS: '该人员已在当前节点承担审批责任，不能重复添加。',
      COUNTERSIGN_LAST_MEMBER: '最后一张必要审批任务不能移除。',
      COUNTERSIGN_SELF_REMOVAL: '不能移除自己的审批责任。',
      TASK_NOT_DELEGATED: '任务已不在待回交状态，请刷新后重新选择。',
      TASK_DELEGATION_OWNER_MISSING: '原委派责任人缺失，请联系流程管理员核对。',
      INVALID_TASK_QUERY: '待办筛选无效，请检查金额范围并重新查询。',
      INVALID_TASK_RECIPIENT: '接收人须为当前租户的其他有效审批账号，请重新选择。',
      FORBIDDEN: '当前账号没有执行此操作的权限，本次未重新执行。原操作结果请查询业务状态。',
      INVALID_INITIALIZATION_REQUEST: '初始化配置不完整，请核对组织、日历、通知及明确确认。',
      INVALID_INITIALIZATION_QUERY: '初始化状态不接受身份或筛选参数，请重新读取。',
      TENANT_ALREADY_INITIALIZED: '当前租户已完成初始化，请重新读取原记录，不能再次创建。',
      INITIALIZATION_PERSON_CHANGED: '已有管理员人员信息已变化或已停用，请重新读取并在组织与人员中核对。',
      INITIALIZATION_CHANNEL_CHANGED: '所选通知渠道的绑定已变化或不可用，请重新读取并核对通知选择。',
      UNAUTHENTICATED: '登录已失效，请重新登录。',
      FORM_VALIDATION_FAILED: '部分表单字段未通过校验，请按提示修改。',
      INVALID_RISK_POLICY: '风险规则配置无效，请核对唯一标识、公开说明、等级和条件。',
      INVALID_SUBMISSION_RISK: '风险规则的标识、公开说明或等级不合法。',
      INVALID_FORM_SCHEMA: '表单配置未通过校验，请检查字段标识、类型、选项与约束。',
      FORM_ASSIGNEE_VALUE_REQUIRED: '请先选择表单中指定的人员、部门或岗位。',
      FORM_ASSIGNEE_UNAVAILABLE: '本轮固定的审批人已失去审批资格，请联系组织管理员核对；本次操作未生效。',
      FORM_ASSIGNEE_SNAPSHOT_MISSING: '本轮缺少有效的表单选人记录，流程已阻止继续推进，请联系管理员核查。',
      WEBHOOK_DELIVERY_CONFLICT: '投递状态已变化，请刷新详情后再操作。',
      WEBHOOK_TARGET_UNAVAILABLE: '原目的地已停用、移除或改址，请联系部署管理员核对配置。',
      INVALID_WEBHOOK_QUERY: '投递筛选或分页位置已失效，请重新查询。',
      INVALID_PUBLICATION_NOTE: '请填写 1 至 2000 字的发布变更说明。',
      INVALID_AVAILABILITY_REASON: '请填写 1 至 2000 字的停用或恢复原因。',
      INITIATOR_APPOINTMENT_REQUIRED: '该流程需要选择本次发起任职，请选择后提交。',
      INITIATOR_APPOINTMENT_UNAVAILABLE: '所选任职不可用，请刷新并选择本人的有效任职。',
      ORGANIZATION_SUPERVISOR_CYCLE: '主管链中重复出现了同一人员或任职，请调整关系。',
      ORGANIZATION_NOT_INITIALIZED: '请先明确启用本地组织目录。',
  ORGANIZATION_ALREADY_INITIALIZED: '本地组织目录已经启用，请刷新状态。',
  ORGANIZATION_IDENTITY_CONFLICT: '该身份已绑定本租户人员，请修改原人员记录。',
  ORGANIZATION_APPOINTMENT_CONFLICT: '该任职关系已存在，请修改原任职的在用状态。',
  ORGANIZATION_DEPARTMENT_CYCLE: '部门层级形成了循环，请选择其他上级部门。',
  ORGANIZATION_RELATION_INVALID: '组织关系须在同一法人内；部门负责人须在该部门任职。',
  ORGANIZATION_RELATION_INACTIVE: '在用关系引用的法人、部门、岗位和人员必须处于在用状态。',
  ORGANIZATION_NO_APPROVERS: '当前组织规则已无人可审批，请联系管理员核对人员与任职。',
  INVALID_ORGANIZATION_UNIT: '请检查组织单元名称、类型和归属。',
  INVALID_ORGANIZATION_PERSON: '请填写稳定身份标识和有效的人员名称。',
  INVALID_ORGANIZATION_APPOINTMENT: '请选择人员、部门和岗位。',
  DEFINITION_DISABLED: '此流程版本已停用，无法新建申请或提交（包括重提）。请联系流程管理员恢复原版本后重试。',
      DEFINITION_AVAILABILITY_UNCHANGED: '版本状态已与本次操作相同，请刷新版本状态后核对记录。',
      COPY_RECIPIENT_UNAVAILABLE: '抄送名单已失效或超过 100 人，请检查组织目录后重试。',
      ESCALATION_RECIPIENT_UNAVAILABLE: '升级名单已失效或超过 100 人，请检查组织目录后重试。',
      ESCALATION_RULE_INVALID: '请配置有效的升级等待时长和明确收件对象。',
      INVALID_COPY_QUERY: '抄送轮次无效，请重新打开消息。',
      INVALID_TEMPLATE_COPY_REQUEST: '复制信息无效，请检查流程标识、名称和模板版本。',
      TEMPLATE_VERSION_CONFLICT: '模板版本已变化，请重新加载目录，核对后再复制。',
      DEFINITION_BINDING_AMBIGUOUS: '这份旧申请未保存原流程来源，当前存在同名版本。请保留原记录，核对流程后重新发起申请。'
    }
    if (authentication?.mode === 'OIDC' && (response.status === 401 || code === 'CSRF_INVALID') && typeof window !== 'undefined') {
      window.dispatchEvent(new Event('agentflow:authentication-required'))
    }
    throw { status: response.status, code, message: messages[code] ?? message, details } satisfies ApiError
  }
  try {
    if (format === 'zip') {
      if (response.headers.get('Content-Type')?.split(';')[0] !== 'application/zip') throw new Error('Unexpected archive content type')
      const blob = await response.blob()
      if (blob.size < 22) throw new Error('Incomplete archive')
      const head = new Uint8Array(await blob.slice(0, 4).arrayBuffer()), end = new Uint8Array(await blob.slice(-22).arrayBuffer())
      if (head[0] !== 80 || head[1] !== 75 || head[2] !== 3 || head[3] !== 4
          || end[0] !== 80 || end[1] !== 75 || end[2] !== 5 || end[3] !== 6 || end[20] !== 0 || end[21] !== 0) throw new Error('Incomplete archive directory')
      return blob as T
    }
    if (format === 'binary') {
      if (response.headers.get('Content-Type')?.split(';')[0] !== 'application/octet-stream') throw new Error('Unexpected attachment content type')
      return await response.blob() as T
    }
    if (format === 'xlsx') {
      if (response.headers.get('Content-Type')?.split(';')[0] !== workbookType) throw new Error('Unexpected workbook content type')
      const blob = await response.blob()
      if (!blob.size) throw new Error('Empty workbook')
      return blob as T
    }
    const text = await response.text()
    const value = text ? JSON.parse(text) : undefined
    if (businessWrite && (value === null || typeof value !== 'object' || Array.isArray(value))) throw new Error('Invalid write response')
    return value as T
  } catch { throw { status: 0, code: 'RESPONSE_UNREADABLE', message: businessWrite ? '操作响应未完整接收，请恢复上次操作确认结果。' : '服务响应无法读取，请重试。' } satisfies ApiError }
}

/** 关闭只在明确确认后发送，超时保留原请求，并在清除恢复槽前验证回执。 */
async function sendExpenseRequestClose(operation: WriteRequest, key: string): Promise<ExpenseRequestCloseReceipt> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      controller.abort()
      reject({ status: 0, code: 'REQUEST_TIMEOUT', message: '关闭结果尚未确认，请恢复上次操作。' } satisfies ApiError)
    }, 12_000)
  })
  try {
    const value = await Promise.race([request(operation.path, { method: operation.method, body: operation.body, headers: { 'Idempotency-Key': key }, signal: controller.signal }), timeout])
    return readExpenseRequestCloseReceipt(value, decodeURIComponent(operation.path.split('/')[2]!), JSON.parse(operation.body!) as ExpenseRequestCloseInput)
  } finally { clearTimeout(timer) }
}

/** 票据准备包含事务外原件读取；超时只结束等待，原请求继续留给本人恢复。 */
async function sendExtraction(operation: WriteRequest, key: string): Promise<ExtractionReceipt> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      controller.abort()
      reject({ status: 0, code: 'REQUEST_TIMEOUT', message: '提取操作结果尚未确认，请恢复上次操作。' } satisfies ApiError)
    }, 25_000)
  })
  try {
    const value = await Promise.race([request(operation.path, { method: operation.method, body: operation.body, headers: { 'Idempotency-Key': key }, signal: controller.signal }), timeout])
    return validateExtractionReceipt(value, operation.path, operation.body!)
  } finally { clearTimeout(timer) }
}

/** 配置写入超时保留原键，回执须符合原租户、原内容及版本后才能确认成功。 */
async function sendFinanceConfiguration(operation: WriteRequest, key: string,
    validate: (value: unknown, path: string, body: string, actor: Pick<Actor, 'tenantId' | 'userId'> | null, signal: AbortSignal) => unknown = validateConfigurationReceipt) {
  const actor = requestActor ? { ...requestActor } : null, controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([
      request(operation.path, { method: operation.method, body: operation.body, headers: { 'Idempotency-Key': key }, signal: controller.signal })
        .then(value => validate(value, operation.path, operation.body!, actor, controller.signal)),
      new Promise<never>((_, reject) => { timer = setTimeout(() => { controller.abort(); reject({ status: 0, code: 'REQUEST_TIMEOUT', message: '配置保存结果尚未确认，请恢复原操作。' } satisfies ApiError) }, 12_000) })
    ])
    return value
  } finally { clearTimeout(timer) }
}
/** 发布后的附加历史核对失败不能证明写入失败，保留原请求等待本人恢复。 */
async function validateMappingOperation(value: unknown, path: string, body: string, actor: Pick<Actor, 'tenantId' | 'userId'> | null, signal: AbortSignal) {
  const checked = validateMappingReceipt(value, path, body, actor)
  if (!path.endsWith('/publish')) return checked
  const revision = (JSON.parse(body) as MappingPublishInput).expectedDraftRevision
  let original: unknown
  try { original = await request(path.replace(/publish$/, 'draft/versions/' + revision), { signal, cache: 'no-store' }) }
  catch { throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '发布响应已返回，但原草稿证据尚未核对，请恢复原发布操作。' } satisfies ApiError }
  return validateMappingPublicationHistory(checked as MappingCurrent, original, revision)
}
/** 所有配置读取固定身份并禁止缓存；返回后仍由页面代次丢弃迟到响应。 */
function configurationRead<T>(path: string, signal: AbortSignal, read: (value: unknown, actor: Pick<Actor, 'tenantId' | 'userId'> | null) => T): Promise<T> {
  const actor = requestActor ? { ...requestActor } : null
  return request(path, { signal, cache: 'no-store' }).then(value => read(value, actor))
}

/** 签署准备可能读取原件；结束等待不代表授权失败，原键保留供本人恢复。 */
async function sendSignature(operation: WriteRequest, key: string): Promise<SignatureReceipt> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const result = await Promise.race([request(operation.path, { method: operation.method, body: operation.body, headers: { 'Idempotency-Key': key }, signal: controller.signal }),
      new Promise<never>((_, reject) => { timer = setTimeout(() => { controller.abort(); reject({ status: 0, code: 'REQUEST_TIMEOUT', message: '签署操作结果尚未确认，请恢复原操作。' } satisfies ApiError) }, 12_000) })])
    return validateSignatureReceipt(result, operation.path, operation.body!)
  } finally { clearTimeout(timer) }
}

export const writeRequests = new PendingWrites(async (operation, key) => {
  if (signatureWritePath.test(operation.path)) return sendSignature(operation, key)
  if (operation.path === '/admin/expense-categories' || /^\/admin\/expense-policies\/[^/?]+\/(draft|publish)$/.test(operation.path)) return sendFinanceConfiguration(operation, key)
  if (/^\/admin\/account-mappings\/[^/?]+\/(draft|publish)$/.test(operation.path)) return sendFinanceConfiguration(operation, key, validateMappingOperation)
  if (/^\/expense-requests\/[^/?]+\/close$/.test(operation.path)) return sendExpenseRequestClose(operation, key)
  if (/^\/invoices\/[^/?]+\/extraction-runs(?:\/[^/?]+\/review)?$/.test(operation.path)) return sendExtraction(operation, key)
  const actor = requestActor ? { ...requestActor } : null
  const result = await request(operation.path, { method: operation.method, body: operation.body, headers: { 'Idempotency-Key': key } })
  if (operation.path.startsWith(syncPath + '/')) validateSyncReceipt(result, operation.path, operation.body!)
  if (operation.path === approvalProxyPath || /^\/organization\/approval-proxies\/[^/?]+\/revoke$/.test(operation.path)) validateApprovalProxyReceipt(result, operation.path, operation.body!)
  if (operation.path === '/system/initialization') validateInitializationReceipt(result, JSON.parse(operation.body!) as InitializationRequest, actor)
  if (operation.path === '/notifications/preferences') validateNotificationPreferencesReceipt(result, JSON.parse(operation.body!) as NotificationPreferencesInput)
  const notificationRetry = /^\/notifications\/deliveries\/([^/?]+)\/retry$/.exec(operation.path)
  if (notificationRetry) validateDeliveryRetryReceipt(result, decodeURIComponent(notificationRetry[1]!), JSON.parse(operation.body!) as NotificationDeliveryRetryInput)
  const comment = /^\/applications\/([^/?]+)\/comments$/.exec(operation.path)
  if (comment) validateCommentReceipt(result, decodeURIComponent(comment[1]!), JSON.parse(operation.body!) as CommentDraft)
  const taskAction = /^\/tasks\/([^/?]+)\/actions$/.exec(operation.path)
  if (taskAction) validateTaskAssignmentReceipt(result, decodeURIComponent(taskAction[1]!), JSON.parse(operation.body!) as TaskActionInput)
  validateEventMutation(result, operation.path, operation.body ?? '{}')
  const membership = /^\/tasks\/([^/?]+)\/countersign-changes$/.exec(operation.path)
  if (membership) validateCountersignReceipt(result as CountersignReceipt, decodeURIComponent(membership[1]!), JSON.parse(operation.body!) as CountersignInput)
  const timer = /^\/applications\/([^/?]+)\/rounds\/([1-9][0-9]*)\/timers\/([^/?]+)\/retry$/.exec(operation.path)
  if (timer) validateTimerReceipt(result as TimerReceipt, decodeURIComponent(timer[1]!), Number(timer[2]), decodeURIComponent(timer[3]!), JSON.parse(operation.body!) as TimerRetryInput)
  const instance = /^\/applications\/([^/?]+)\/rounds\/([1-9][0-9]*)\/runtime\/(pause|resume|terminate)$/.exec(operation.path)
  if (instance) validateInstanceReceipt(result as InstanceControlView, decodeURIComponent(instance[1]!), Number(instance[2]), instance[3] as InstanceControlAction, JSON.parse(operation.body!) as InstanceControlInput)
  if (/^\/applications\/[^/?]+\/draft-assist-runs(?:\/[^/?]+\/review)?$/.test(operation.path)) validateDraftReceipt(result, operation.path, operation.body!)
  if (/^\/expense-reports\/[^/?]+\/precheck-explanations(?:\/[^/?]+\/review)?$/.test(operation.path)) validateExplanationReceipt(result, operation.path, operation.body!)
  if (/^\/expense-reports\/[^/?]+\/risk-explanations(?:\/[^/?]+\/review)?$/.test(operation.path)) validateRiskReceipt(result, operation.path, operation.body!)
  if (/^\/expense-reports\/[^/?]+\/draft-assists(?:\/[^/?]+\/(?:confirm|dismiss))?$/.test(operation.path)) validateExpenseAssistReceipt(result, operation.path, operation.body!)
  return result
})
function write<T>(path: string, method: WriteRequest['method'], label: string, body?: unknown) {
  return writeRequests.run<T>({ path, method, label, body: body === undefined ? undefined : JSON.stringify(body) })
}

export const api = {
  accountMappings: (filters: Partial<MappingScope>, afterKey: string | undefined, signal: AbortSignal) => configurationRead('/admin/account-mappings' + historyQuery({ ...filters, afterKey, limit: 25 }), signal, value => readMappingDirectory(value, filters, afterKey)),
  accountMappingCurrent: (scope: MappingScope, signal: AbortSignal) => configurationRead('/admin/account-mappings/current' + historyQuery({ legalEntityId: scope.legalEntityId, currency: scope.currency }), signal, (value, actor) => readMappingCurrent(value, actor, scope)),
  accountMappingDraft: (key: string, signal: AbortSignal) => configurationRead('/admin/account-mappings/' + encodeURIComponent(key) + '/draft', signal, (value, actor) => readMappingDraft(value, actor, key)),
  saveAccountMappingDraft: (key: string, body: MappingDraftInput) => write<MappingDraft>('/admin/account-mappings/' + encodeURIComponent(key) + '/draft', 'PUT', '保存科目映射草稿', body),
  publishAccountMapping: (key: string, body: MappingPublishInput) => write<MappingCurrent>('/admin/account-mappings/' + encodeURIComponent(key) + '/publish', 'POST', '发布科目映射并切换本范围生效版本', body),
  accountMappingVersions: (key: string, beforeVersion: number | undefined, signal: AbortSignal) => configurationRead('/admin/account-mappings/' + encodeURIComponent(key) + '/versions' + historyQuery({ beforeVersion, limit: 25 }), signal, value => readMappingVersions(value, beforeVersion)),
  accountMappingVersion: (key: string, version: number, signal: AbortSignal) => configurationRead('/admin/account-mappings/' + encodeURIComponent(key) + '/versions/' + version, signal, (value, actor) => readPublishedMapping(value, actor, key, version)),
  accountMappingDraftVersion: (key: string, mappingId: string, revision: number, signal: AbortSignal) => configurationRead('/admin/account-mappings/' + encodeURIComponent(key) + '/draft/versions/' + revision, signal, value => readMappingDraftRevision(value, mappingId, revision)),
  accountMappingActivations: (scope: MappingScope, beforeVersion: number | undefined, signal: AbortSignal) => configurationRead('/admin/account-mappings/activations' + historyQuery({ legalEntityId: scope.legalEntityId, currency: scope.currency, beforeVersion, limit: 25 }), signal, value => readMappingActivations(value, beforeVersion)),
  expenseConfiguration: (signal: AbortSignal) => configurationRead('/admin/expense-policies/current', signal, readExpenseConfiguration),
  expenseCategories: (signal: AbortSignal) => configurationRead('/admin/expense-categories', signal, readExpenseCategories),
  saveExpenseCategories: (body: CategoryInput) => write<ExpenseCategories>('/admin/expense-categories', 'PUT', '保存费用类别修订', body),
  expenseCategoryVersions: (beforeVersion: number | undefined, signal: AbortSignal) => configurationRead('/admin/expense-categories/versions' + historyQuery({ beforeVersion, limit: 25 }), signal, value => readCategoryHistory(value, beforeVersion)),
  expenseCategoryVersion: (version: number, signal: AbortSignal) => configurationRead('/admin/expense-categories/versions/' + version, signal, (value, actor) => readCategoryRevision(value, actor, version)),
  expensePolicies: (afterKey: string | undefined, signal: AbortSignal) => configurationRead('/admin/expense-policies' + historyQuery({ afterKey, limit: 25 }), signal, value => readPolicyDirectory(value, afterKey)),
  expensePolicyDraft: (key: string, signal: AbortSignal) => configurationRead('/admin/expense-policies/' + encodeURIComponent(key) + '/draft', signal, (value, actor) => readPolicyDraft(value, actor, key)),
  saveExpensePolicyDraft: (key: string, body: PolicyDraftInput) => write<ExpensePolicyDraft>('/admin/expense-policies/' + encodeURIComponent(key) + '/draft', 'PUT', '保存费用制度草稿', body),
  publishExpensePolicy: (key: string, body: PolicyPublishInput) => write<ExpenseConfigurationCurrent>('/admin/expense-policies/' + encodeURIComponent(key) + '/publish', 'POST', '发布费用制度并切换生效版本', body),
  expensePolicyVersions: (key: string, beforeVersion: number | undefined, signal: AbortSignal) => configurationRead('/admin/expense-policies/' + encodeURIComponent(key) + '/versions' + historyQuery({ beforeVersion, limit: 25 }), signal, value => readPolicyHistory(value, beforeVersion)),
  expensePolicyVersion: (key: string, version: number, signal: AbortSignal) => configurationRead('/admin/expense-policies/' + encodeURIComponent(key) + '/versions/' + version, signal, (value, actor) => readPublishedPolicy(value, actor, key, version)),
  expensePolicyDraftVersion: (key: string, policyId: string, version: number, signal: AbortSignal) => configurationRead('/admin/expense-policies/' + encodeURIComponent(key) + '/draft/versions/' + version, signal, value => readPolicyDraftRevision(value, policyId, version)),
  expensePolicyActivations: (beforeVersion: number | undefined, signal: AbortSignal) => configurationRead('/admin/expense-policies/activations' + historyQuery({ beforeVersion, limit: 25 }), signal, value => readActivationHistory(value, beforeVersion)),

  eventContracts: (afterKey: string | undefined, signal: AbortSignal) => request<EventDirectory>('/event-contracts' + historyQuery({ limit: 25, afterKey }), { signal, cache: 'no-store' }).then(value => readEventDirectory(value, afterKey)),
  eventContractVersions: (key: string, beforeVersion: number | undefined, signal: AbortSignal) => request<EventVersions>(`/event-contracts/${encodeURIComponent(key)}/versions` + historyQuery({ limit: 25, beforeVersion }), { signal, cache: 'no-store' }).then(value => readEventVersions(value, key, beforeVersion)),
  eventContract: (key: string, version: number, signal: AbortSignal) => request<EventContract>(`/event-contracts/${encodeURIComponent(key)}/versions/${version}`, { signal, cache: 'no-store' }).then(value => readEventContract(value, key, version)),
  eventContractHistory: (key: string, version: number, beforeRevision: number | undefined, signal: AbortSignal) => request<EventContractHistory>(`/event-contracts/${encodeURIComponent(key)}/versions/${version}/history` + historyQuery({ limit: 25, beforeRevision }), { signal, cache: 'no-store' }).then(value => readEventContractHistory(value, beforeRevision)),
  publishEventContract: (key: string, input: EventPublication) => write<EventContract>(`/event-contracts/${encodeURIComponent(key)}/versions`, 'POST', '发布事件契约版本', input),
  changeEventAvailability: (key: string, version: number, input: EventAvailabilityInput) => write<EventContract>(`/event-contracts/${encodeURIComponent(key)}/versions/${version}/availability`, 'POST', input.enabled ? '恢复原事件版本' : '停用原事件版本', input),
  serviceTaskOptions: (afterKey: string | undefined, signal: AbortSignal) => request<ServiceTaskDirectory>('/process-definitions/service-task-options' + historyQuery({ limit: 25, afterKey }), { signal, cache: 'no-store' }).then(value => readServiceTaskDirectory(value, afterKey)),
  serviceTaskVersions: (key: string, beforeVersion: string | undefined, signal: AbortSignal) => request<ServiceTaskVersions>(`/process-definitions/service-task-options/${encodeURIComponent(key)}/versions` + historyQuery({ limit: 25, beforeVersion }), { signal, cache: 'no-store' }).then(value => readServiceTaskVersions(value, key, beforeVersion)),
  serviceTaskOption: (key: string, version: string, signal: AbortSignal) => request<ServiceTaskOption>(`/process-definitions/service-task-options/${encodeURIComponent(key)}/versions/${encodeURIComponent(version)}`, { signal, cache: 'no-store' }).then(value => readServiceTaskOption(value, key, version)),
  eventContractOptions: (afterKey: string | undefined, signal: AbortSignal) => request<EventDirectory>('/process-definitions/event-contract-options' + historyQuery({ limit: 25, afterKey }), { signal, cache: 'no-store' }).then(value => readEventDirectory(value, afterKey)),
  eventContractOptionVersions: (key: string, beforeVersion: number | undefined, signal: AbortSignal) => request<EventVersions>(`/process-definitions/event-contract-options/${encodeURIComponent(key)}/versions` + historyQuery({ limit: 25, beforeVersion }), { signal, cache: 'no-store' }).then(value => readEventVersions(value, key, beforeVersion)),
  eventContractOption: (key: string, version: number, signal: AbortSignal) => request<EventOption>(`/process-definitions/event-contract-options/${encodeURIComponent(key)}/versions/${version}`, { signal, cache: 'no-store' }).then(value => readEventOption(value, key, version)),
  eventInbox: (beforeId: string | undefined, signal: AbortSignal) => request<EventInboxPage>('/integrations/events' + historyQuery({ limit: 25, beforeId }), { signal, cache: 'no-store' }).then(value => readEventInboxPage(value, beforeId)),
  eventInboxItem: (id: string, signal: AbortSignal) => request<EventInboxItem>(`/integrations/events/${encodeURIComponent(id)}`, { signal, cache: 'no-store' }).then(value => readEventInboxItem(value, id)),
  eventInboxHistory: (id: string, beforeVersion: number | undefined, signal: AbortSignal) => request<EventInboxHistory>(`/integrations/events/${encodeURIComponent(id)}/history` + historyQuery({ limit: 25, beforeVersion }), { signal, cache: 'no-store' }).then(value => readEventInboxHistory(value, id, beforeVersion)),
  retryEvent: (id: string, input: EventRetryInput) => write<EventInboxItem>(`/integrations/events/${encodeURIComponent(id)}/retry`, 'POST', '重新处理原事件', input),
  eventWaits: (id: string, round: number, signal: AbortSignal) => request<EventWaitView>(`/applications/${encodeURIComponent(id)}/rounds/${round}/event-waits`, { signal, cache: 'no-store' }).then(value => readEventWaits(value, id, round)),
  instanceControl: (id: string, round: number, signal: AbortSignal) => request<InstanceControlView>(`/applications/${encodeURIComponent(id)}/rounds/${round}/runtime`, { signal, cache: 'no-store' }),
  controlInstance: (id: string, round: number, action: InstanceControlAction, input: InstanceControlInput) => write<InstanceControlView>(`/applications/${encodeURIComponent(id)}/rounds/${round}/runtime/${action}`, 'POST', action === 'pause' ? '暂停本轮审批' : action === 'resume' ? '恢复本轮审批' : '终止本轮审批', input),
  timerWaits: (id: string, round: number, signal: AbortSignal) => request<TimerView>(`/applications/${encodeURIComponent(id)}/rounds/${round}/timers`, { signal, cache: 'no-store' }),
  retryTimer: (id: string, round: number, jobId: string, input: TimerRetryInput) => write<TimerReceipt>(`/applications/${encodeURIComponent(id)}/rounds/${round}/timers/${encodeURIComponent(jobId)}/retry`, 'POST', '重试原定时等待', input),
  vouchers: (id: string, roundNo: number, signal: AbortSignal) => request<VoucherView>(`/applications/${encodeURIComponent(id)}/vouchers` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  voucherAction: (id: string, input: VoucherActionInput) => write<VoucherReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/actions`, 'POST', '办理本轮凭证操作', input),
  paymentVouchers: (id: string, roundNo: number, signal: AbortSignal) => request<VoucherView>(`/applications/${encodeURIComponent(id)}/vouchers/payment` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  paymentVoucherAction: (id: string, input: VoucherActionInput) => write<VoucherReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/payment/actions`, 'POST', '办理本轮付款凭证操作', input),
  resolveVoucherDispute: (id: string, operationId: string, input: VoucherDisputeInput) => write<VoucherDisputeReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/dispute-resolutions`, 'POST', '确认原凭证对账结果', input),
  voucherReversal: (id: string, operationId: string, roundNo: number, signal: AbortSignal) => request<VoucherReversalView>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/reversal` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  queryVoucherReversal: (id: string, operationId: string, input: ReversalQueryInput) => write<ReversalActionReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/reversal/checks`, 'POST', '核验独立冲销凭证', input),
  recordVoucherReversal: (id: string, operationId: string, input: ReversalRecordInput) => write<ReversalActionReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/reversal/records`, 'POST', '登记独立冲销凭证', input),
  voucherReversalExecution: (id: string, operationId: string, roundNo: number, signal: AbortSignal) => request<VoucherReversalExecutionView>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/reversal-execution` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  prepareVoucherReversal: (id: string, operationId: string, input: ReversalPrepareInput) => write<ReversalExecutionReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/reversal-execution/preparations`, 'POST', '准备独立冲销', input),
  authorizeVoucherReversal: (id: string, operationId: string, input: ReversalAuthorizeInput) => write<ReversalExecutionReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/reversal-execution/authorizations`, 'POST', '授权执行独立冲销', input),
  retireVoucherReversal: (id: string, operationId: string, input: ReversalRetirementInput) => write<ReversalRetirementReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/reversal-execution/retirements`, 'POST', '安全结束本次冲销', input),
  voucherReversalOperationAction: (id: string, operationId: string, input: ReversalOperationInput) => write<ReversalExecutionReceipt>(`/applications/${encodeURIComponent(id)}/vouchers/${encodeURIComponent(operationId)}/reversal-execution/actions`, 'POST', '办理原冲销命令', input),
  expenseSettlement: (id: string, roundNo: number, signal: AbortSignal) => request<SettlementView>(`/expense-reports/${encodeURIComponent(id)}/settlement` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  expenseResourceAdjustment: (id: string, roundNo: number, signal: AbortSignal) => request<AdjustmentView>(`/expense-reports/${encodeURIComponent(id)}/resource-adjustment` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  expensePartialAdjustments: (id: string, roundNo: number, signal: AbortSignal) => request<PartialView>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  createExpensePartialAdjustment: (id: string, input: PartialCreateInput) => write<PartialReceipt>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments`, 'POST', '登记报销部分调整', input),
  queryExpensePartialOriginals: (id: string, input: PartialOriginalInput) => write<PartialReceipt>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments/original-queries`, 'POST', '查询部分调整原财务', input),
  prepareExpensePartialAdjustment: (id: string, input: PartialPrepareInput) => write<PartialReceipt>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments/preparations`, 'POST', '准备本侧部分调整', input),
  authorizeExpensePartialAdjustment: (id: string, input: PartialAuthorizeInput) => write<PartialReceipt>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments/authorizations`, 'POST', '授权本侧部分调整', input),
  actExpensePartialAdjustment: (id: string, input: PartialActionInput) => write<PartialReceipt>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments/actions`, 'POST', '办理原部分调整', input),
  queryExpensePartialSources: (id: string, input: PartialSourceInput) => write<PartialReceipt>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments/source-queries`, 'POST', '重查部分调整原件', input),
  retireExpensePartialAdjustment: (id: string, input: PartialRetireInput) => write<PartialReceipt>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments/retirements`, 'POST', '安全结束部分调整', input),
  resolveExpensePartialDispute: (id: string, input: PartialDisputeInput) => write<PartialReceipt>(`/expense-reports/${encodeURIComponent(id)}/partial-adjustments/disputes`, 'POST', '明确裁决本侧部分调整争议', input),
  prepareExpenseResourceAdjustment: (id: string, input: AdjustmentPrepareInput) => write<AdjustmentPreparationReceipt>(`/expense-reports/${encodeURIComponent(id)}/resource-adjustment/preparations`, 'POST', '准备报销独立调整', input),
  authorizeExpenseResourceAdjustment: (id: string, input: AdjustmentAuthorizeInput) => write<AdjustmentPreparationReceipt>(`/expense-reports/${encodeURIComponent(id)}/resource-adjustment/authorizations`, 'POST', '授权报销独立调整', input),
  actExpenseResourceAdjustment: (id: string, input: AdjustmentOperationInput) => write<AdjustmentActionReceipt>(`/expense-reports/${encodeURIComponent(id)}/resource-adjustment/actions`, 'POST', '办理原报销独立调整', input),
  retireExpenseResourceAdjustment: (id: string, input: AdjustmentRetireInput) => write<AdjustmentActionReceipt>(`/expense-reports/${encodeURIComponent(id)}/resource-adjustment/retirements`, 'POST', '安全结束报销独立调整', input),
  expensePaymentReturn: (id: string, roundNo: number, signal: AbortSignal) => request<ExpenseReturnView>(`/expense-reports/${encodeURIComponent(id)}/payment-return` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  queryExpensePaymentReturn: (id: string, input: ExpenseReturnQueryInput) => write<ExpenseReturnActionReceipt>(`/expense-reports/${encodeURIComponent(id)}/payment-return/checks`, 'POST', '查询原报销银行退回', input),
  registerExpensePaymentReturn: (id: string, input: ExpenseReturnRegisterInput) => write<ExpenseReturnActionReceipt>(`/expense-reports/${encodeURIComponent(id)}/payment-return/registrations`, 'POST', '登记报销银行退回', input),
  expenseArchive: (id: string, roundNo: number, signal: AbortSignal) => request<ExpenseArchiveView>(`/expense-reports/${encodeURIComponent(id)}/archive` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  downloadExpenseArchive: (id: string, roundNo: number, signal: AbortSignal) => request<Blob>(`/expense-reports/${encodeURIComponent(id)}/archive/content` + historyQuery({ roundNo }), { signal, cache: 'no-store' }, 'zip'),
  retryExpenseSettlement: (id: string, input: SettlementRetry) => write<SettlementReceipt>(`/expense-reports/${encodeURIComponent(id)}/settlement/retry`, 'POST', '重新办理报销核销', input),
  financeCatalog: (signal: AbortSignal) => request<FinanceCatalog>('/finance/catalog', { signal, cache: 'no-store' }),
  expensePolicyGuidance: (context: PolicyGuidanceContext, signal: AbortSignal) => request('/finance/expense-policy-guidance?' + policyGuidanceQuery(context), { signal, cache: 'no-store' }).then(value => readPolicyGuidance(value, context)),
  financePayment: (id: string, roundNo: number, signal: AbortSignal) => request<FinancePaymentView>(`/applications/${encodeURIComponent(id)}/payments` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  authorizePayment: (id: string, input: PaymentAuthorizationInput) => write<FinancePaymentReceipt>(`/applications/${encodeURIComponent(id)}/payments/authorizations`, 'POST', '财务授权付款', input),
  financePaymentAction: (id: string, input: FinancePaymentActionInput) => write<FinancePaymentReceipt>(`/payments/${encodeURIComponent(id)}/finance-actions`, 'POST', '财务核对付款授权', input),
  reviewPaymentPayee: (id: string, input: PayeeReviewInput) => write<PayeeReviewReceipt>(`/payments/${encodeURIComponent(id)}/payee-reviews`, 'POST', '重新核对本人账户', input),
  resolvePaymentDispute: (id: string, input: PaymentDisputeInput) => write<PaymentDisputeReceipt>(`/payments/${encodeURIComponent(id)}/dispute-resolutions`, 'POST', '确认原付款对账结果', input),
  paymentBatches: (beforeId: string | undefined, signal: AbortSignal) => request<PaymentBatchPage>('/payment-batches' + historyQuery({ limit: 25, beforeId }), { signal, cache: 'no-store' }),
  paymentBatch: (id: string, signal: AbortSignal) => request<PaymentBatchDetail>(`/payment-batches/${encodeURIComponent(id)}`, { signal, cache: 'no-store' }),
  submitPaymentBatch: (input: PaymentBatchInput) => write<PaymentBatchReceipt>('/payment-batches', 'POST', '登记批量付款', input),
  cashierPayments: (beforeId: string | undefined, signal: AbortSignal, filter: CashierPaymentFilter = {}) => request<CashierPaymentPage>('/cashier/payments' + historyQuery({ limit: 25, beforeId, legalEntityId: filter.legalEntityId, debitAccount: filter.debitAccount, dueFrom: filter.dueFrom, dueTo: filter.dueTo, undated: filter.undated ? 'true' : undefined, sort: filter.sort }), { signal, cache: 'no-store' }),
  cashierPaymentFilterOptions: (legalEntityId: string | undefined, afterAccountKey: string | undefined, signal: AbortSignal) => request<CashierFilterOptions>('/cashier/payments/filter-options' + historyQuery({ limit: 25, legalEntityId, afterAccountKey }), { signal, cache: 'no-store' }),
  cashierPayment: (id: string, signal: AbortSignal) => request<CashierPaymentView>(`/cashier/payments/${encodeURIComponent(id)}`, { signal, cache: 'no-store' }),
  paymentAccounts: (id: string, signal: AbortSignal) => request<PaymentAccounts>(`/cashier/payments/${encodeURIComponent(id)}/accounts`, { signal, cache: 'no-store' }),
  cashierPaymentAction: (id: string, input: CashierPaymentActionInput) => write<CashierPaymentReceipt>(`/cashier/payments/${encodeURIComponent(id)}/actions`, 'POST', '出纳办理原付款', input),
  supplierCashierPayments: (beforeId: string | undefined, signal: AbortSignal) => request<SupplierCashierPage>('/cashier/supplier-payments' + historyQuery({ beforeId, limit: 25 }), { signal, cache: 'no-store' }),
  supplierCashierPayment: (id: string, signal: AbortSignal) => request<SupplierCashierView>(`/cashier/supplier-payments/${encodeURIComponent(id)}`, { signal, cache: 'no-store' }),
  supplierCashierAccounts: (id: string, signal: AbortSignal) => request<SupplierCashierAccounts>(`/cashier/supplier-payments/${encodeURIComponent(id)}/accounts`, { signal, cache: 'no-store' }),
  supplierCashierAction: (id: string, input: SupplierCashierInput) => write<SupplierCashierReceipt>(`/cashier/supplier-payments/${encodeURIComponent(id)}/actions`, 'POST', '办理供应商原付款', input),
  supplierFinance: (id: string, roundNo: number, signal: AbortSignal) => request<SupplierFinanceView>(`/procurement-payments/${encodeURIComponent(id)}/supplier-payment` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  budgetFinance: (id: string, roundNo: number, operationId: string | undefined, signal: AbortSignal) => request<BudgetFinanceView>(`/budget-adjustments/${encodeURIComponent(id)}/execution` + historyQuery({ roundNo, operationId }), { signal, cache: 'no-store' }),
  budgetFinanceHistory: (id: string, roundNo: number, before: string | undefined, signal: AbortSignal) => request<BudgetFinancePage>(`/budget-adjustments/${encodeURIComponent(id)}/execution/history` + historyQuery({ roundNo, before, limit: 25 }), { signal, cache: 'no-store' }),
  reviewBudgetLedger: (id: string, input: BudgetFinanceReviewInput) => write<BudgetFinanceReceipt>(`/budget-adjustments/${encodeURIComponent(id)}/execution/reviews`, 'POST', '读取最新预算台账', input),
  authorizeBudgetAdjustment: (id: string, input: BudgetFinanceAuthorizeInput) => write<BudgetFinanceReceipt>(`/budget-adjustments/${encodeURIComponent(id)}/execution/authorizations`, 'POST', '确认预算调整授权', input),
  budgetOperationAction: (id: string, input: BudgetFinanceActionInput) => write<BudgetFinanceReceipt>(`/budget-adjustment-operations/${encodeURIComponent(id)}/actions`, 'POST', '办理原预算调整指令', input),
  reviewSupplierPayable: (id: string, input: SupplierReviewInput) => write<SupplierFinanceReceipt>(`/procurement-payments/${encodeURIComponent(id)}/supplier-payment/reviews`, 'POST', '复核供应商原应付', input),
  authorizeSupplierPayment: (id: string, input: SupplierAuthorizeInput) => write<SupplierFinanceReceipt>(`/procurement-payments/${encodeURIComponent(id)}/supplier-payment/authorizations`, 'POST', '确认供应商付款授权', input),
  supplierHoldAction: (id: string, input: SupplierHoldInput) => write<SupplierFinanceReceipt>(`/supplier-payments/${encodeURIComponent(id)}/finance-actions`, 'POST', '办理供应商原预留', input),
  supplierDispute: (id: string, signal: AbortSignal) => request<SupplierDisputeView>(`/supplier-payments/${encodeURIComponent(id)}/dispute`, { signal, cache: 'no-store' }),
  querySupplierDispute: (id: string, input: SupplierDisputeQueryInput) => write<SupplierDisputeReceipt>(`/supplier-payments/${encodeURIComponent(id)}/dispute/queries`, 'POST', '查询原供应商银行交易', input),
  resolveSupplierDispute: (id: string, input: SupplierDisputeResolveInput) => write<SupplierDisputeReceipt>(`/supplier-payments/${encodeURIComponent(id)}/dispute/resolutions`, 'POST', '确认原供应商付款裁决', input),
  supplierAdjustmentDispute: (id: string, signal: AbortSignal) => request<AdjustmentDisputeView>(`/supplier-adjustments/${encodeURIComponent(id)}/dispute`, { signal, cache: 'no-store' }),
  resolveSupplierAdjustmentDispute: (id: string, input: AdjustmentDisputeInput) => write<AdjustmentDisputeReceipt>(`/supplier-adjustments/${encodeURIComponent(id)}/dispute/resolutions`, 'POST', '确认原供应商调整裁决', input),
  supplierSettlementDispute: (id: string, signal: AbortSignal) => request<SettlementDisputeView>(`/supplier-settlements/${encodeURIComponent(id)}/dispute`, { signal, cache: 'no-store' }),
  resolveSupplierSettlementDispute: (id: string, input: SettlementDisputeInput) => write<SettlementDisputeReceipt>(`/supplier-settlements/${encodeURIComponent(id)}/dispute/resolutions`, 'POST', '确认原供应商核销裁决', input),
  supplierReturns: (id: string, beforeVersion: number | undefined, signal: AbortSignal) => request<SupplierReturnView>(`/supplier-payments/${encodeURIComponent(id)}/returns` + historyQuery({ beforeVersion, limit: 25 }), { signal, cache: 'no-store' }),
  querySupplierReturns: (id: string, input: SupplierReturnQueryInput) => write<SupplierReturnReceipt>(`/supplier-payments/${encodeURIComponent(id)}/returns/checks`, 'POST', '查询原供应商付款回款', input),
  registerSupplierReturns: (id: string, input: SupplierReturnRegisterInput) => write<SupplierReturnReceipt>(`/supplier-payments/${encodeURIComponent(id)}/returns/registrations`, 'POST', '登记原供应商实际回款', input),
  supplierAdjustments: (id: string, beforeId: string | undefined, signal: AbortSignal) => request<SupplierAdjustmentView>(`/supplier-payments/${encodeURIComponent(id)}/adjustments` + historyQuery({ beforeId, limit: 25 }), { signal, cache: 'no-store' }),
  prepareSupplierAdjustment: (id: string, input: SupplierAdjustmentPrepareInput) => write<SupplierAdjustmentReceipt>(`/supplier-payments/${encodeURIComponent(id)}/adjustment-preparations`, 'POST', '登记供应商回款独立调整', input),
  supplierAdjustmentAction: (id: string, input: SupplierAdjustmentActionInput) => write<SupplierAdjustmentReceipt>(`/supplier-adjustments/${encodeURIComponent(id)}/finance-actions`, 'POST', '办理供应商原调整', input),
  supplierSettlements: (id: string, beforeId: string | undefined, signal: AbortSignal) => request<SupplierSettlementView>(`/supplier-payments/${encodeURIComponent(id)}/settlements` + historyQuery({ beforeId, limit: 25 }), { signal, cache: 'no-store' }),
  prepareSupplierSettlement: (id: string, input: SupplierSettlementPrepareInput) => write<SupplierSettlementReceipt>(`/supplier-payments/${encodeURIComponent(id)}/settlement-preparations`, 'POST', '登记供应商应付结算', input),
  supplierSettlementAction: (id: string, input: SupplierSettlementActionInput) => write<SupplierSettlementReceipt>(`/supplier-settlements/${encodeURIComponent(id)}/finance-actions`, 'POST', '办理供应商原核销', input),
  procurementPayments: (filter: ExpenseFilter, signal: AbortSignal) => request<ExpensePage<ProcurementPaymentItem>>('/procurement-payments' + historyQuery(filter), { signal, cache: 'no-store' }),
  procurementPayment: (id: string, roundNo: number | undefined, signal: AbortSignal) => request<ProcurementDetail>(`/procurement-payments/${encodeURIComponent(id)}` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  createProcurementPayment: (input: ProcurementCreate) => write<ProcurementReceipt>('/procurement-payments', 'POST', '保存采购付款申请', input),
  reviseProcurementPayment: (id: string, input: ProcurementRevise) => write<ProcurementReceipt>(`/procurement-payments/${encodeURIComponent(id)}/revise`, 'POST', '保存采购付款申请修改', input),
  procurementCheckOptions: (id: string, signal: AbortSignal) => request<ProcurementCheckOptions>(`/procurement-payments/${encodeURIComponent(id)}/prechecks/options`, { signal, cache: 'no-store' }),
  queueProcurementCheck: (id: string, input: ProcurementCheckInput) => write<{ id: string }>(`/procurement-payments/${encodeURIComponent(id)}/prechecks`, 'POST', '查询采购付款申请财务依据', input),
  procurementCheck: (id: string, jobId: string, signal: AbortSignal) => request<ProcurementCheckView>(`/procurement-payments/${encodeURIComponent(id)}/prechecks/${encodeURIComponent(jobId)}`, { signal, cache: 'no-store' }),
  submitProcurementPayment: (id: string, input: ProcurementVersions & { precheckId: string }) => write<ProcurementReceipt>(`/procurement-payments/${encodeURIComponent(id)}/submit`, 'POST', '正式提交采购付款申请', input),
  withdrawProcurementPayment: (id: string, input: ProcurementVersions & { comment: string }) => write<ProcurementReceipt>(`/procurement-payments/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回采购付款申请', input),
  cancelProcurementPayment: (id: string, input: ProcurementVersions & { comment: string }) => write<ProcurementReceipt>(`/procurement-payments/${encodeURIComponent(id)}/cancel`, 'POST', '作废采购付款申请', input),
  budgetAdjustments: (filter: ExpenseFilter, signal: AbortSignal) => request<ExpensePage<BudgetAdjustmentItem>>('/budget-adjustments' + historyQuery(filter), { signal, cache: 'no-store' }),
  budgetAdjustment: (id: string, roundNo: number | undefined, signal: AbortSignal) => request<BudgetAdjustmentDetail>(`/budget-adjustments/${encodeURIComponent(id)}` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  createBudgetAdjustment: (input: BudgetAdjustmentCreate) => write<BudgetAdjustmentReceipt>('/budget-adjustments', 'POST', '保存预算调整申请', input),
  reviseBudgetAdjustment: (id: string, input: BudgetAdjustmentRevise) => write<BudgetAdjustmentReceipt>(`/budget-adjustments/${encodeURIComponent(id)}/revise`, 'POST', '保存预算调整申请修改', input),
  budgetAdjustmentCheckOptions: (id: string, signal: AbortSignal) => request<BudgetAdjustmentCheckOptions>(`/budget-adjustments/${encodeURIComponent(id)}/prechecks/options`, { signal, cache: 'no-store' }),
  queueBudgetAdjustmentCheck: (id: string, input: BudgetAdjustmentCheckInput) => write<{ id: string }>(`/budget-adjustments/${encodeURIComponent(id)}/prechecks`, 'POST', '查询预算调整申请财务依据', input),
  budgetAdjustmentCheck: (id: string, jobId: string, signal: AbortSignal) => request<BudgetAdjustmentCheckView>(`/budget-adjustments/${encodeURIComponent(id)}/prechecks/${encodeURIComponent(jobId)}`, { signal, cache: 'no-store' }),
  submitBudgetAdjustment: (id: string, input: BudgetAdjustmentVersions & { precheckId: string }) => write<BudgetAdjustmentReceipt>(`/budget-adjustments/${encodeURIComponent(id)}/submit`, 'POST', '正式提交预算调整申请', input),
  withdrawBudgetAdjustment: (id: string, input: BudgetAdjustmentVersions & { comment: string }) => write<BudgetAdjustmentReceipt>(`/budget-adjustments/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回预算调整申请', input),
  cancelBudgetAdjustment: (id: string, input: BudgetAdjustmentVersions & { comment: string }) => write<BudgetAdjustmentReceipt>(`/budget-adjustments/${encodeURIComponent(id)}/cancel`, 'POST', '作废预算调整申请', input),
  advanceRequests: (filter: ExpenseFilter, signal: AbortSignal) => request<ExpensePage<AdvanceRequestItem>>('/advance-requests' + historyQuery(filter), { signal, cache: 'no-store' }),
  advanceRequest: (id: string, roundNo: number | undefined, signal: AbortSignal) => request<AdvanceDetail>(`/advance-requests/${encodeURIComponent(id)}` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  advanceDisbursementReview: (id: string, roundNo: number, signal: AbortSignal) => request<DisbursementReturnView>(`/advance-requests/${encodeURIComponent(id)}/disbursement-review` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  queryAdvanceDisbursementReview: (id: string, input: DisbursementReturnQueryInput) => write<DisbursementReturnActionReceipt>(`/advance-requests/${encodeURIComponent(id)}/disbursement-review-checks`, 'POST', '查询原放款与银行退回', input),
  resolveAdvanceDisbursement: (id: string, input: DisbursementResolutionInput) => write<DisbursementReturnActionReceipt>(`/advance-requests/${encodeURIComponent(id)}/disbursement-resolutions`, 'POST', '确认原放款复核与退回', input),
  advanceRepaymentReview: (id: string, repaymentId: string, roundNo: number, signal: AbortSignal) => request<RepaymentReviewView>(`/advance-requests/${encodeURIComponent(id)}/repayments/${encodeURIComponent(repaymentId)}/review` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  queryAdvanceRepaymentReview: (id: string, repaymentId: string, input: RepaymentReviewQueryInput) => write<RepaymentReviewActionReceipt>(`/advance-requests/${encodeURIComponent(id)}/repayments/${encodeURIComponent(repaymentId)}/review-checks`, 'POST', '查询原还款复核依据', input),
  resolveAdvanceRepayment: (id: string, repaymentId: string, input: RepaymentResolutionInput) => write<RepaymentReviewActionReceipt>(`/advance-requests/${encodeURIComponent(id)}/repayments/${encodeURIComponent(repaymentId)}/resolutions`, 'POST', '确认原还款复核与退回', input),
  advanceRepayments: (id: string, roundNo: number, beforeId: string | undefined, signal: AbortSignal) => request<RepaymentView>(`/advance-requests/${encodeURIComponent(id)}/repayments` + historyQuery({ roundNo, beforeId }), { signal, cache: 'no-store' }),
  queryAdvanceRepayment: (id: string, input: RepaymentQueryInput) => write<RepaymentActionReceipt>(`/advance-requests/${encodeURIComponent(id)}/repayment-checks`, 'POST', '查询原还款凭据', input),
  recordAdvanceRepayment: (id: string, input: RepaymentRecordInput) => write<RepaymentActionReceipt>(`/advance-requests/${encodeURIComponent(id)}/repayments`, 'POST', '确认借款还款', input),
  createAdvanceRequest: (input: AdvanceCreate) => write<AdvanceReceipt>('/advance-requests', 'POST', '保存借款申请', input),
  reviseAdvanceRequest: (id: string, input: AdvanceRevise) => write<AdvanceReceipt>(`/advance-requests/${encodeURIComponent(id)}/revise`, 'POST', '保存借款申请修改', input),
  advanceCheckOptions: (id: string, signal: AbortSignal) => request<AdvanceCheckOptions>(`/advance-requests/${encodeURIComponent(id)}/prechecks/options`, { signal, cache: 'no-store' }),
  queueAdvanceCheck: (id: string, input: AdvanceCheckInput) => write<{ id: string }>(`/advance-requests/${encodeURIComponent(id)}/prechecks`, 'POST', '查询借款申请财务依据', input),
  advanceCheck: (id: string, jobId: string, signal: AbortSignal) => request<AdvanceCheckView>(`/advance-requests/${encodeURIComponent(id)}/prechecks/${encodeURIComponent(jobId)}`, { signal, cache: 'no-store' }),
  submitAdvanceRequest: (id: string, input: AdvanceVersions & { precheckId: string }) => write<AdvanceReceipt>(`/advance-requests/${encodeURIComponent(id)}/submit`, 'POST', '正式提交借款申请', input),
  withdrawAdvanceRequest: (id: string, input: AdvanceVersions & { comment: string }) => write<AdvanceReceipt>(`/advance-requests/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回借款申请', input),
  cancelAdvanceRequest: (id: string, input: AdvanceVersions & { comment: string }) => write<AdvanceReceipt>(`/advance-requests/${encodeURIComponent(id)}/cancel`, 'POST', '作废借款申请', input),
  expensePlans: (filter: ExpenseFilter, signal: AbortSignal) => request<ExpensePage<PlanItem>>('/expense-plans' + historyQuery(filter), { signal, cache: 'no-store' }),
  expensePlan: (id: string, roundNo: number | undefined, signal: AbortSignal) => request<PlanDetail>(`/expense-plans/${encodeURIComponent(id)}` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  createExpensePlan: (input: PlanCreate) => write<PlanDetail>('/expense-plans', 'POST', '保存事前申请', input),
  reviseExpensePlan: (id: string, input: PlanRevise) => write<PlanDetail>(`/expense-plans/${encodeURIComponent(id)}/revise`, 'POST', '保存事前计划修改', input),
  planCheckOptions: (id: string, signal: AbortSignal) => request<PlanCheckOptions>(`/expense-plans/${encodeURIComponent(id)}/prechecks/options`, { signal, cache: 'no-store' }),
  queuePlanCheck: (id: string, input: PlanCheckInput) => write<{ id: string }>(`/expense-plans/${encodeURIComponent(id)}/prechecks`, 'POST', '查询事前计划财务依据', input),
  planCheck: (id: string, jobId: string, signal: AbortSignal) => request<PlanCheckView>(`/expense-plans/${encodeURIComponent(id)}/prechecks/${encodeURIComponent(jobId)}`, { signal, cache: 'no-store' }),
  submitExpensePlan: (id: string, input: PlanVersions & { precheckId: string }) => write<PlanReceipt>(`/expense-plans/${encodeURIComponent(id)}/submit`, 'POST', '正式提交事前申请', input),
  withdrawExpensePlan: (id: string, input: PlanVersions & { comment: string }) => write<PlanReceipt>(`/expense-plans/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回事前申请', input),
  cancelExpensePlan: (id: string, input: PlanVersions & { comment: string }) => write<PlanReceipt>(`/expense-plans/${encodeURIComponent(id)}/cancel`, 'POST', '作废事前申请', input),
  createExpense: (input: ExpenseCreate) => write<ExpenseDetail>('/expense-reports', 'POST', '保存报销草稿', input),
  reviseExpense: (id: string, input: ExpenseRevise) => write<ExpenseDetail>(`/expense-reports/${encodeURIComponent(id)}/revise`, 'POST', '保存报销修改', input),
  expensePrecheckOptions: (id: string, signal: AbortSignal) => request<PrecheckOptions>(`/expense-reports/${encodeURIComponent(id)}/precheck-options`, { signal, cache: 'no-store' }),
  queueExpensePrecheck: (id: string, input: PrecheckInput) => write<{ id: string }>(`/expense-reports/${encodeURIComponent(id)}/precheck`, 'POST', '发起费用预检', input),
  expensePrecheck: (id: string, jobId: string, signal: AbortSignal) => request<PrecheckView>(`/expense-reports/${encodeURIComponent(id)}/prechecks/${encodeURIComponent(jobId)}`, { signal, cache: 'no-store' }).then(value => { if (value.priorControls != null) readPriorAssessments(value.priorControls); return value }),
  precheckExplanationInput: (id: string, precheckId: string, signal: AbortSignal) => request(explanationPath(id) + '/input?precheckId=' + encodeURIComponent(precheckId), { signal, cache: 'no-store' }).then(value => readExplanationInput(value, precheckId)),
  expensePriorControl: (id: string, roundNo: number, signal: AbortSignal) => request<unknown>(`/expense-reports/${encodeURIComponent(id)}/prior-control` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  expenseSplitRouting: (id: string, roundNo: number, signal: AbortSignal) => request<unknown>(`/expense-reports/${encodeURIComponent(id)}/split-routing` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  expenseRiskInput: (id: string, body: RiskRequest, signal: AbortSignal) => request(riskPath(id) + '/input', { method: 'POST', body: JSON.stringify(body), signal, cache: 'no-store' }).then(value => readRiskInput(value, body.scope)),
  expenseRiskCalendars: (id: string, roundNo: number, taskId: string, afterKey: string | undefined, signal: AbortSignal) => request(riskPath(id) + '/calendars' + historyQuery({ roundNo, taskId, afterKey }), { signal, cache: 'no-store' }).then(readRiskCalendars),
  expenseRiskRuns: (id: string, roundNo: number, page: number, signal: AbortSignal) => request(riskPath(id) + historyQuery({ roundNo, page, pageSize: 20 }), { signal, cache: 'no-store' }).then(value => readRiskPage(value, page)),
  expenseRiskRun: (id: string, runId: string, roundNo: number, signal: AbortSignal) => request(riskPath(id) + '/' + encodeURIComponent(runId), { signal, cache: 'no-store' }).then(value => readRiskDetail(value, runId, roundNo)),
  generateExpenseRisk: (id: string, body: RiskGenerate) => write<RiskReceipt>(riskPath(id), 'POST', '生成费用风险解释', body),
  reviewExpenseRisk: (id: string, runId: string, body: RiskReview) => write<RiskReceipt>(riskPath(id) + '/' + encodeURIComponent(runId) + '/review', 'POST', '记录费用风险复核', body),
  precheckExplanationRuns: (id: string, page: number, signal: AbortSignal) => request(explanationPath(id) + '?page=' + page + '&pageSize=20', { signal, cache: 'no-store' }).then(value => readExplanationPage(value, page)),
  precheckExplanationRun: (id: string, runId: string, signal: AbortSignal) => request(explanationPath(id) + '/' + encodeURIComponent(runId), { signal, cache: 'no-store' }).then(value => readExplanationDetail(value, runId)),
  generatePrecheckExplanation: (id: string, body: ExplanationGenerate) => write<ExplanationReceipt>(explanationPath(id), 'POST', '生成本人预检解释', body),
  reviewPrecheckExplanation: (id: string, runId: string, body: ExplanationReview) => write<ExplanationReceipt>(explanationPath(id) + '/' + encodeURIComponent(runId) + '/review', 'POST', '复核本人预检解释', body),
  expenseAssistPreview: (id: string, body: ExpenseAssistRequest, signal: AbortSignal) => request(expenseAssistPath(id) + '/preview', { method: 'POST', body: JSON.stringify(body), signal, cache: 'no-store' }).then(value => readExpenseAssistPreview(value, id, body)),
  expenseAssistRuns: (id: string, page: number, signal: AbortSignal) => request(expenseAssistPath(id) + '?page=' + page + '&pageSize=20', { signal, cache: 'no-store' }).then(value => readExpenseAssistPage(value, page)),
  expenseAssistRun: (id: string, runId: string, signal: AbortSignal) => request(expenseAssistPath(id) + '/' + encodeURIComponent(runId), { signal, cache: 'no-store' }).then(value => readExpenseAssistDetail(value, id, runId)),
  generateExpenseAssist: (id: string, body: ExpenseAssistGenerate) => write<ExpenseAssistReceipt>(expenseAssistPath(id), 'POST', '生成本人报销填报建议', body),
  confirmExpenseAssist: (id: string, runId: string, body: ExpenseAssistConfirm) => write<ExpenseAssistReceipt>(expenseAssistPath(id) + '/' + encodeURIComponent(runId) + '/confirm', 'POST', '逐项确认报销填报建议', body),
  dismissExpenseAssist: (id: string, runId: string, body: ExpenseAssistDismiss) => write<ExpenseAssistReceipt>(expenseAssistPath(id) + '/' + encodeURIComponent(runId) + '/dismiss', 'POST', '放弃报销填报建议', body),
  advanceOffsetSuggestion: (id: string, jobId: string, signal: AbortSignal) => request<AdvanceOffsetSuggestion>(`/expense-reports/${encodeURIComponent(id)}/prechecks/${encodeURIComponent(jobId)}/advance-offset-suggestion`, { signal, cache: 'no-store' }),
  submitExpense: (id: string, input: { applicationVersion: number; financialVersion: number; precheckId: string }) => write<ExpenseReceipt>(`/expense-reports/${encodeURIComponent(id)}/submit`, 'POST', '正式提交报销', input),
  invoices: (filter: ExpenseFilter, signal: AbortSignal) => request<ExpensePage<InvoiceItem>>('/invoices' + historyQuery(filter), { signal, cache: 'no-store' }),
  invoice: (id: string, signal: AbortSignal) => request<InvoiceItem>(`/invoices/${encodeURIComponent(id)}`, { signal, cache: 'no-store' }),
  invoiceWalletOptions: (signal: AbortSignal) => request<InvoiceWalletOptions>('/invoices/options', { signal, cache: 'no-store' }),
  invoiceExtractionInput: (id: string, signal: AbortSignal) => request(extractionPath(id) + '/input', { signal, cache: 'no-store' }).then(value => readExtractionOptions(value, id)),
  invoiceExtractionRuns: (id: string, page: number, signal: AbortSignal) => request(extractionPath(id) + '?page=' + page + '&pageSize=20', { signal, cache: 'no-store' }).then(value => readExtractionPage(value, page)),
  invoiceExtractionRun: (id: string, runId: string, signal: AbortSignal) => request(extractionPath(id) + '/' + encodeURIComponent(runId), { signal, cache: 'no-store' }).then(value => readExtractionDetail(value, id, runId)),
  generateInvoiceExtraction: (id: string, body: ExtractionGenerate) => write<ExtractionReceipt>(extractionPath(id), 'POST', '发起本人票据提取', body),
  reviewInvoiceExtraction: (id: string, runId: string, body: ExtractionReview) => write<ExtractionReceipt>(extractionPath(id) + '/' + encodeURIComponent(runId) + '/review', 'POST', '保存本人票面复核', body),
  reserveInvoice: (input: InvoiceUploadInput, key: string, signal: AbortSignal) => request<{ id: string }>('/invoices', { method: 'POST', body: JSON.stringify(input), headers: { 'Idempotency-Key': key }, signal }),
  uploadInvoice: (id: string, file: Blob, signal: AbortSignal) => request<InvoiceOriginal>(`/invoices/${encodeURIComponent(id)}/content`, { method: 'PUT', body: file, headers: { 'Content-Type': 'application/octet-stream' }, signal }),
  downloadInvoice: (id: string, signal: AbortSignal) => request<Blob>(`/invoices/${encodeURIComponent(id)}/content`, { signal, cache: 'no-store' }, 'binary'),
  invoiceVerificationOptions: (id: string, signal: AbortSignal) => request<InvoiceVerificationOptions>(`/invoices/${encodeURIComponent(id)}/verification-options`, { signal, cache: 'no-store' }),
  queueInvoiceVerification: (id: string, input: InvoiceVerificationInput) => write<{ id: string }>(`/invoices/${encodeURIComponent(id)}/verifications`, 'POST', '发起发票查验', input),
  invoiceVerification: (id: string, jobId: string, signal: AbortSignal) => request<InvoiceVerificationJob>(`/invoices/${encodeURIComponent(id)}/verifications/${encodeURIComponent(jobId)}`, { signal, cache: 'no-store' }),
  invoiceVerifications: (id: string, filter: ExpenseFilter, signal: AbortSignal) => request<ExpensePage<InvoiceVerificationJob>>(`/invoices/${encodeURIComponent(id)}/verifications` + historyQuery(filter), { signal, cache: 'no-store' }),
  expenseReports: (filter: ExpenseFilter, signal: AbortSignal) => request<ExpensePage<ExpenseItem>>('/expense-reports' + historyQuery(filter), { signal, cache: 'no-store' }),
  expenseRequests: (filter: ExpenseFilter, signal: AbortSignal) => request<unknown>('/expense-requests' + historyQuery(filter), { signal, cache: 'no-store' }).then(readPriorRequestPage),
  closeExpenseRequest: (id: string, input: ExpenseRequestCloseInput) => write<ExpenseRequestCloseReceipt>(`/expense-requests/${encodeURIComponent(id)}/close`, 'POST', '关闭事前费用额度', input),
  employeeAdvances: (filter: ExpenseFilter, signal: AbortSignal) => request<ExpensePage<AdvanceItem>>('/employee-advances' + historyQuery(filter), { signal, cache: 'no-store' }),
  expenseReport: (id: string, roundNo: number | undefined, signal: AbortSignal) => request<ExpenseDetail>(`/expense-reports/${encodeURIComponent(id)}` + historyQuery({ roundNo }), { signal, cache: 'no-store' }),
  expenseWorkflow: (id: string, taskId: string | undefined, signal: AbortSignal) => request<ExpenseWorkflow>(`/expense-reports/${encodeURIComponent(id)}/workflow` + historyQuery({ taskId }), { signal, cache: 'no-store' }),
  receiveExpense: (id: string, taskId: string, input: ExpenseTaskCommand) => write<ExpenseReceipt>(`/expense-reports/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/receive`, 'POST', '确认费用原件签收', input),
  reduceExpense: (id: string, taskId: string, input: ExpenseReduction) => write<ExpenseReceipt>(`/expense-reports/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/reduce`, 'POST', '确认财务核减', input),
  withdrawExpense: (id: string, input: ExpenseCommand) => write<ExpenseReceipt>(`/expense-reports/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回费用审批', input),
  cancelExpense: (id: string, input: ExpenseCommand) => write<ExpenseReceipt>(`/expense-reports/${encodeURIComponent(id)}/cancel`, 'POST', '作废费用单', input),
  definitionCopyRecipients: (signal?: AbortSignal) => request<AssigneeOption[]>('/process-definitions/copy-options', { signal }),
  copySnapshot: (applicationId: string, round: number, signal?: AbortSignal) => request<CopySnapshot>(`/copies/${applicationId}/rounds/${round}`, { signal }),
  copyAttachment: (applicationId: string, id: string, round: number, signal?: AbortSignal) => request<AttachmentMetadata>(`/copies/${applicationId}/rounds/${round}/attachments/${id}`, { signal }),
  downloadCopyAttachment: (applicationId: string, id: string, round: number, signal?: AbortSignal) => request<Blob>(`/copies/${applicationId}/rounds/${round}/attachments/${id}/content`, { signal }, 'binary'),
  signatureOptions: (signal: AbortSignal) => configurationRead('/signatures/options', signal, readSignatureOptions),
  signaturePage: (applicationId: string, roundNo: number, afterId: string | undefined, signal: AbortSignal) => configurationRead(signaturePath(applicationId) + historyQuery({ roundNo, afterId, limit: 25 }), signal, value => readSignaturePage(value, afterId)),
  signatureDetail: (applicationId: string, id: string, roundNo: number, signal: AbortSignal) => configurationRead(signaturePath(applicationId) + '/' + encodeURIComponent(id), signal, (value, actor) => readSignatureView(value, id, roundNo, actor?.userId ?? '')),
  createSignature: (applicationId: string, body: SignatureInput) => write<SignatureReceipt>(signaturePath(applicationId), 'POST', '授权签署所选原件', body),
  cancelSignature: (applicationId: string, id: string, expectedVersion: string) => write<SignatureReceipt>(signaturePath(applicationId) + '/' + encodeURIComponent(id) + '/cancel', 'POST', '取消尚未发送的签署', { expectedVersion }),
  downloadSignature: (applicationId: string, id: string, documentId: string, signal: AbortSignal) => request<Blob>(signaturePath(applicationId) + '/' + encodeURIComponent(id) + '/documents/' + encodeURIComponent(documentId) + '/content', { signal, cache: 'no-store' }, 'binary'),
  attachmentOptions: (signal?: AbortSignal) => request<AttachmentOptions>('/attachments/options', { signal }),
  reserveAttachment: (applicationId: string, input: AttachmentInput, key: string, signal?: AbortSignal) => request<AttachmentMetadata>(`/applications/${applicationId}/attachments`, { method: 'POST', body: JSON.stringify(input), headers: { 'Idempotency-Key': key }, signal }),
  uploadAttachment: (applicationId: string, id: string, expectedVersion: number, file: Blob, signal?: AbortSignal) => request<AttachmentMetadata>(`/applications/${applicationId}/attachments/${id}/content`, { method: 'PUT', body: file, headers: { 'Content-Type': 'application/octet-stream', 'X-Application-Version': String(expectedVersion) }, signal }),
  attachment: (applicationId: string, id: string, roundNo?: number, signal?: AbortSignal) => request<AttachmentMetadata>(`/applications/${applicationId}/attachments/${id}${historyQuery({ roundNo })}`, { signal }),
  downloadAttachment: (applicationId: string, id: string, roundNo?: number, signal?: AbortSignal) => request<Blob>(`/applications/${applicationId}/attachments/${id}/content${historyQuery({ roundNo })}`, { signal }, 'binary'),
  authOptions: async () => {
    const options = await request<AuthOptions>('/auth/options', { cache: 'no-store', signal: AbortSignal.timeout(AUTH_OPTIONS_TIMEOUT_MS) })
    if (!['DEMO', 'OIDC', 'UNCONFIGURED'].includes(options.mode)
        || options.mode === 'OIDC' && (options.loginUrl !== '/api/v1/auth/oidc/authorize/enterprise'
          || options.csrfHeader !== 'X-CSRF-TOKEN' || !options.csrfToken || API_BASE !== '/api/v1'
          || options.providerLogoutUrl != null && (options.providerLogoutUrl !== '/api/v1/auth/oidc/logout/enterprise'
            || options.csrfParameter !== '_csrf'))) {
      throw { status: 0, code: 'AUTH_CONFIGURATION_INVALID', message: '登录配置无效，企业登录需要使用同源入口，请联系管理员。' } satisfies ApiError
    }
    authentication = options
    return options
  },
  webhookTargets: (signal: AbortSignal) => request<WebhookTarget[]>('/integrations/webhooks', { signal }),
  paymentCallbacks: (beforeId: string | undefined, signal: AbortSignal) => request<PaymentCallbackPage>('/integrations/payment/callbacks' + historyQuery({ limit: 25, beforeId }), { signal, cache: 'no-store' }),
  paymentCallback: (id: string, signal: AbortSignal) => request<PaymentCallbackDetail>('/integrations/payment/callbacks/' + encodeURIComponent(id), { signal, cache: 'no-store' }),
  retryPaymentCallback: (id: string, input: { expectedVersion: number; reason: string }) => write<PaymentCallbackView>('/integrations/payment/callbacks/' + encodeURIComponent(id) + '/retry', 'POST', '重新处理原支付回调', input),
  webhookOverview: (filters: WebhookOverviewFilters, signal: AbortSignal) => request<WebhookOverview>('/integrations/webhooks/overview' + historyQuery(filters), { signal }),
  webhookDeliveries: (filters: WebhookFilters, signal: AbortSignal) => request<WebhookPage>('/integrations/webhooks/deliveries' + historyQuery(filters), { signal }),
  webhookDelivery: (id: string, signal: AbortSignal) => request<WebhookDetail>('/integrations/webhooks/deliveries/' + encodeURIComponent(id), { signal }),
  retryWebhook: (id: string, expectedVersion: number) => write<WebhookItem>('/integrations/webhooks/deliveries/' + encodeURIComponent(id) + '/retry', 'POST', '重新排队 Webhook 投递', { expectedVersion }),
  organizationStatus: (signal: AbortSignal) => request<{ initialized: boolean }>('/organization', { signal }),
  organizationSyncOverview: (signal: AbortSignal) => configurationRead(syncPath, signal, readSyncOverview),
  organizationSyncBatches: (page: number, signal: AbortSignal) => configurationRead(syncPath + '/batches' + historyQuery({ page, pageSize: 20 }), signal, value => readSyncBatches(value, page)),
  organizationSyncBatch: (id: string, signal: AbortSignal) => configurationRead(syncPath + '/batches/' + encodeURIComponent(id), signal, value => readSyncDetail(value, id)),
  organizationSyncTransitions: (id: string, signal: AbortSignal) => configurationRead(syncPath + '/batches/' + encodeURIComponent(id) + '/transitions', signal, readSyncTransitions),
  organizationSyncPlans: (id: string, page: number, signal: AbortSignal) => configurationRead(syncPath + '/batches/' + encodeURIComponent(id) + '/plans' + historyQuery({ page, pageSize: 20 }), signal, value => readSyncPlans(value, page)),
  organizationSyncPlan: (id: string, batchId: string, signal: AbortSignal) => configurationRead(syncPath + '/plans/' + encodeURIComponent(id), signal, (value, actor) => readSyncPlan(value, id, batchId, actor?.tenantId ?? '')),
  organizationSyncOptions: (section: import('./organization').OrganizationSection, afterId: string | undefined, signal: AbortSignal) => configurationRead('/organization/'
    + (section === 'PERSON' ? 'people' : section === 'APPOINTMENT' ? 'appointments' : 'units')
    + historyQuery({ kind: ['PERSON', 'APPOINTMENT'].includes(section) ? undefined : section, afterId, limit: 30 }), signal, value => readSyncLocalPage(value, section)),
  queueOrganizationSync: (body: { expectedSourceVersion: number; targetDigest: string }) => write<SyncReceipt>(syncPath + '/batches', 'POST', '读取可信组织来源', body),
  retryOrganizationSync: (id: string, body: { expectedVersion: number; expectedSourceVersion: number; targetDigest: string }) => write<SyncReceipt>(syncPath + '/batches/' + encodeURIComponent(id) + '/retry', 'POST', '重试原组织同步批次', body),
  cancelOrganizationSync: (id: string, body: { expectedVersion: number; comment?: string }) => write<SyncReceipt>(syncPath + '/batches/' + encodeURIComponent(id) + '/cancel', 'POST', '取消组织同步批次', body),
  preflightOrganizationSync: (id: string, body: { expectedVersion: number; selections: SyncSelection[] }) => write<SyncPlanReceipt>(syncPath + '/batches/' + encodeURIComponent(id) + '/preflight', 'POST', '保存组织同步核对计划', body),
  applyOrganizationSync: (id: string, body: { expectedVersion: number; planId: string; comment?: string }) => write<SyncReceipt>(syncPath + '/batches/' + encodeURIComponent(id) + '/apply', 'POST', '应用已核对的组织同步计划', body),
  approvalProxies: async (personId: string | undefined, afterId: string | undefined, signal: AbortSignal) => readApprovalProxyPage(await request(approvalProxyPath + historyQuery({ personId, afterId, limit: 30 }), { signal, cache: 'no-store' })),
  approvalProxy: async (id: string, signal: AbortSignal) => readApprovalProxy(await request(approvalProxyPath + '/' + encodeURIComponent(id), { signal, cache: 'no-store' }), id),
  createApprovalProxy: (body: ApprovalProxyInput) => write<ApprovalProxyReceipt>(approvalProxyPath, 'POST', '创建审批代理', body),
  revokeApprovalProxy: (id: string, body: { expectedRevision: number; reason: string }) => write<ApprovalProxyReceipt>(approvalProxyPath + '/' + encodeURIComponent(id) + '/revoke', 'POST', '撤销审批代理', body),
  initializeOrganization: () => write<{ initialized: boolean }>('/organization/initialize', 'POST', '启用本地组织目录'),
  organizationUnits: (kind: OrganizationUnit['kind'], afterId: string | undefined, signal: AbortSignal) => request<OrganizationPage<OrganizationUnit>>('/organization/units' + historyQuery({ kind, afterId, limit: 30 }), { signal }),
  organizationPeople: (afterId: string | undefined, signal: AbortSignal) => request<OrganizationPage<OrganizationPerson>>('/organization/people' + historyQuery({ afterId, limit: 30 }), { signal }),
  organizationAppointments: (afterId: string | undefined, signal: AbortSignal) => request<OrganizationPage<OrganizationAppointment>>('/organization/appointments' + historyQuery({ afterId, limit: 30 }), { signal }),
  organizationChanges: (beforeRevision: number | undefined, signal: AbortSignal) => request<{ items: OrganizationChange[]; nextBeforeRevision?: number | null }>('/organization/changes' + historyQuery({ beforeRevision, limit: 30 }), { signal }),
  saveOrganization: (path: string, editing: boolean, label: string, body: Record<string, unknown>) => write<OrganizationRecord>(path, editing ? 'PUT' : 'POST', label, body),
  calendars: (afterKey: string | undefined, signal: AbortSignal) => request<CalendarPage>('/business-calendars' + historyQuery({ afterKey, limit: 30 }), { signal }),
  definitionCalendars: (afterKey: string | undefined, signal: AbortSignal) => request<CalendarPage>('/process-definitions/calendar-options' + historyQuery({ afterKey, limit: 30 }), { signal }),
  definitionCalendarVersions: (id: string, beforeRevision: number | undefined, signal: AbortSignal) => request<CalendarVersionPage>(`/process-definitions/calendar-options/${encodeURIComponent(id)}/versions` + historyQuery({ beforeRevision, limit: 30 }), { signal }),
  definitionCalendarVersion: (id: string, revision: string, signal: AbortSignal) => request<CalendarSummary>(`/process-definitions/calendar-options/${encodeURIComponent(id)}/versions/${encodeURIComponent(revision)}`, { signal }),
  calendar: (id: string, signal: AbortSignal) => request<BusinessCalendar>(`/business-calendars/${encodeURIComponent(id)}`, { signal }),
  calendarVersions: (id: string, beforeRevision: number | undefined, signal: AbortSignal) => request<CalendarVersionPage>(`/business-calendars/${encodeURIComponent(id)}/versions` + historyQuery({ beforeRevision, limit: 30 }), { signal }),
  calendarVersion: (id: string, revision: number, signal: AbortSignal) => request<BusinessCalendar>(`/business-calendars/${encodeURIComponent(id)}/versions/${revision}`, { signal }),
  createCalendar: (body: CalendarInput) => write<BusinessCalendar>('/business-calendars', 'POST', '新建工作日历', body),
  updateCalendar: (id: string, body: CalendarUpdate) => write<BusinessCalendar>(`/business-calendars/${encodeURIComponent(id)}`, 'PUT', '保存工作日历修订', body),
  calculateCalendar: (id: string, body: CalendarCalculationInput, signal: AbortSignal) => request<CalendarCalculation>(`/business-calendars/${encodeURIComponent(id)}/calculate`, { method: 'POST', body: JSON.stringify(body), signal }),
  openApi: (signal: AbortSignal) => request<ApiDocument>('/openapi.json', { signal }),
  workspaceApplications: (query: WorkspaceQuery, signal: AbortSignal) => request<WorkspacePage<WorkspaceApplication>>('/workspace/applications' + historyQuery(query), { signal }),
  workspaceHandled: (query: WorkspaceQuery, signal: AbortSignal) => request<WorkspacePage<WorkspaceHandled>>('/workspace/handled' + historyQuery(query), { signal }),
  definitionPublication: (id: string, signal: AbortSignal) => request<PublicationResponse>(`/process-definitions/${encodeURIComponent(id)}/publication`, { signal }),
  definitionAvailabilityHistory: (id: string, beforeRevision: number | undefined, signal: AbortSignal) => request<DefinitionAvailabilityHistory>(`/process-definitions/${encodeURIComponent(id)}/availability-history?limit=30${beforeRevision === undefined ? '' : '&beforeRevision=' + beforeRevision}`, { signal }),
  changeDefinitionAvailability: (id: string, body: DefinitionAvailabilityInput) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}/availability`, 'POST', body.startEnabled ? '恢复流程版本' : '停用流程版本', body),
  compareDefinition: (baselineId: string, body: ComparisonInput, signal: AbortSignal) => request<ComparisonResult>('/process-definitions/' + encodeURIComponent(baselineId) + '/compare', { method: 'POST', body: JSON.stringify(body), signal }),
  simulateDesign: (body: SimulationInput, signal: AbortSignal) => request<SimulationResult>('/process-definitions/simulate', { method: 'POST', body: JSON.stringify(body), signal }),
  previewFields: (body: FieldPreviewInput, signal: AbortSignal) => request<FieldPreviewResult>('/process-definitions/field-preview', { method: 'POST', body: JSON.stringify(body), signal }),
  approvalOperations: (filter: OperationsFilter, signal: AbortSignal) => request<OperationsReport>('/operations/approvals' + historyQuery(filter), { signal }),
  firstWorkflow: (id: string, signal: AbortSignal) => request<FirstWorkflowReport>('/system/first-workflow' + (id ? '?definitionId=' + encodeURIComponent(id) : ''), { signal }),
  tenantInitialization: (signal: AbortSignal) => {
    const actor = requestActor ? { ...requestActor } : null
    return request('/system/initialization', { signal, cache: 'no-store' }).then(value => readInitializationState(value, actor))
  },
  initializeTenant: (input: InitializationRequest) => write<InitializationReceipt>('/system/initialization', 'POST', '初始化工作区', input),
  systemChecks: (signal: AbortSignal) => request<SystemCheckReport>('/system/checks', { signal }),
  applicationTimeline: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/timeline' + historyQuery(query)),
  serviceTaskRuntime: (id: string, round: number, afterId: string | undefined, signal: AbortSignal) => request<ServiceTaskRuntimeView>(`/applications/${encodeURIComponent(id)}/rounds/${round}/service-tasks` + historyQuery({ limit: 25, afterId }), { signal, cache: 'no-store' }).then(value => readServiceTaskRuntime(value, id, round, afterId)),
  draftAssistInput: (id: string, signal: AbortSignal) => request(draftAssistPath(id) + '/input', { signal, cache: 'no-store' }).then(readDraftInput),
  draftAssistRuns: (id: string, page: number, signal: AbortSignal) => request(draftAssistPath(id) + '?page=' + page + '&pageSize=20', { signal, cache: 'no-store' }).then(value => readDraftPage(value, page)),
  draftAssistRun: (id: string, runId: string, signal: AbortSignal) => request(draftAssistPath(id) + '/' + encodeURIComponent(runId), { signal, cache: 'no-store' }).then(value => readDraftDetail(value, runId)),
  generateDraftAssist: (id: string, body: GenerateDraftInput) => write<DraftAssistReceipt>(draftAssistPath(id), 'POST', '生成草稿字段建议', body),
  reviewDraftAssist: (id: string, runId: string, body: ReviewDraftInput) => write<DraftAssistReceipt>(draftAssistPath(id) + '/' + encodeURIComponent(runId) + '/review', 'POST', '确认草稿字段建议', body),
  assistRuns: (id: string, query: AssistRunFilter, signal: AbortSignal) => request<AssistRunPage>('/applications/' + encodeURIComponent(id) + '/assist-runs' + historyQuery(query), { signal }),
  assistRun: (id: string, runId: string, signal: AbortSignal) => request<AssistRunDetail>('/applications/' + encodeURIComponent(id) + '/assist-runs/' + encodeURIComponent(runId), { signal }),
  assistInput: (id: string, taskId: string, signal: AbortSignal) => request<AssistInputOptions>('/applications/' + encodeURIComponent(id) + '/assist-runs/input?taskId=' + encodeURIComponent(taskId), { signal }),
  generateAssist: (id: string, body: AssistGenerateRequest) => write<AssistReceipt>('/applications/' + encodeURIComponent(id) + '/assist-runs', 'POST', '生成 Agent 摘要', body),
  reviewAssist: (id: string, runId: string, body: AssistReviewRequest) => write<AssistReceipt>('/applications/' + encodeURIComponent(id) + '/assist-runs/' + encodeURIComponent(runId) + '/review', 'POST', '复核 Agent 摘要', body),
  applicationComments: (id: string, query: CommentQuery, signal: AbortSignal) => request<CommentPage>('/applications/' + encodeURIComponent(id) + '/comments' + historyQuery(query), { signal }),
  commentMentionOptions: (id: string, query: CommentMentionFilter, signal: AbortSignal) => request<CommentMentionPage>('/applications/' + encodeURIComponent(id) + '/comments/mention-options' + historyQuery(query), { signal, cache: 'no-store' }).then(value => readCommentMentionPage(value, id, query)),
  addApplicationComment: (id: string, body: CommentDraft) => write<ApplicationComment>('/applications/' + encodeURIComponent(id) + '/comments', 'POST', '追加申请评论', body),
  applicationAudit: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/audit' + historyQuery(query)),
  login: (body: { tenantId: string; username: string; password: string }) => request<{ token: string; user: Actor }>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  me: () => request<{ actor: Actor }>('/auth/me'),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
  prepareProviderLogout: async () => {
    const original = requestActor
    const options = await api.authOptions()
    const current = (await api.me()).actor
    if (!original || requestActor !== original || current.tenantId !== original.tenantId || current.userId !== original.userId) {
      throw { status: 409, code: 'LOGOUT_IDENTITY_CHANGED', message: '当前企业账号与本页不同，请恢复原账号后重试。' } satisfies ApiError
    }
    if (options.mode !== 'OIDC' || !options.providerLogoutUrl) {
      throw { status: 409, code: 'PROVIDER_LOGOUT_UNAVAILABLE', message: '企业退出入口不可用，请选择仅退出平台。' } satisfies ApiError
    }
    return { action: options.providerLogoutUrl, fields: { [options.csrfParameter!]: options.csrfToken!, actor: JSON.stringify([original.tenantId, original.userId]) } }
  },
  taskPage: (query: PendingTaskQuery, signal: AbortSignal) => request<PendingTaskPage>('/workspace/tasks' + historyQuery(query), { signal }),
  task: (id: string, signal: AbortSignal) => request<Task>(`/tasks/${encodeURIComponent(id)}`, { signal }),
  tasks: (signal?: AbortSignal) => request<Task[]>('/tasks', { signal }),
  inbox: (query: InboxQuery, signal: AbortSignal) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) if (value !== undefined) params.set(key, String(value))
    return request<InboxPage>(`/notifications?${params}`, { signal })
  },
  notificationPreferences: (signal: AbortSignal) => request<NotificationPreferences>('/notifications/preferences', { signal, cache: 'no-store' }).then(readNotificationPreferences),
  reviseNotificationPreferences: (input: NotificationPreferencesInput) => write<NotificationPreferences>('/notifications/preferences', 'PUT', '保存通知偏好', input),
  notificationDeliveries: (filters: NotificationDeliveryFilters, signal: AbortSignal) => {
    const query = new URLSearchParams(); Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') query.set(key, String(value)) })
    return request('/notifications/deliveries?' + query, { signal, cache: 'no-store' }).then(readDeliveryPage)
  },
  notificationDelivery: (id: string, signal: AbortSignal) => request('/notifications/deliveries/' + encodeURIComponent(id), { signal, cache: 'no-store' }).then(value => readDeliveryDetail(value, id)),
  notificationDeliveryHistory: (id: string, filters: Pick<NotificationDeliveryFilters, 'limit' | 'cursor'>, signal: AbortSignal) => {
    const query = new URLSearchParams(); Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') query.set(key, String(value)) })
    return request('/notifications/deliveries/' + encodeURIComponent(id) + '/history?' + query, { signal, cache: 'no-store' }).then(readDeliveryHistory)
  },
  retryNotificationDelivery: (id: string, input: NotificationDeliveryRetryInput) => write<NotificationDelivery>('/notifications/deliveries/' + encodeURIComponent(id) + '/retry', 'POST', '恢复外部通知投递', input),
  readNotification: (id: string) => write<InboxMessage>(`/notifications/${encodeURIComponent(id)}/read`, 'POST', '标记消息已读', {}),
  paymentNotificationTarget: (id: string, signal: AbortSignal) => request<PaymentNotificationTarget>(`/notifications/${encodeURIComponent(id)}/payment-target`, { signal, cache: 'no-store' }),
  supplierPaymentNotificationTarget: (id: string, signal: AbortSignal) => request<SupplierPaymentNotificationTarget>(`/notifications/${encodeURIComponent(id)}/supplier-payment-target`, { signal, cache: 'no-store' }),
  supplierReturnNotificationTarget: (id: string, signal?: AbortSignal) => request<SupplierReturnNotificationTarget>(`/notifications/${encodeURIComponent(id)}/supplier-return-target`, { cache: 'no-store', signal }),
  expenseReturnNotificationTarget: (id: string, signal?: AbortSignal) => request<ExpenseReturnNotificationTarget>(`/notifications/${encodeURIComponent(id)}/expense-return-target`, { cache: 'no-store', signal }),
  disbursementReturnNotificationTarget: (id: string, signal?: AbortSignal) => request<DisbursementReturnNotificationTarget>(`/notifications/${encodeURIComponent(id)}/disbursement-return-target`, { cache: 'no-store', signal }),
  repaymentReviewNotificationTarget: (id: string, signal?: AbortSignal) => request<RepaymentReviewNotificationTarget>(`/notifications/${encodeURIComponent(id)}/repayment-review-target`, { cache: 'no-store', signal }),
  budgetAdjustmentNotificationTarget: (id: string, signal?: AbortSignal) => request<BudgetAdjustmentNotificationTarget>(`/notifications/${encodeURIComponent(id)}/budget-adjustment-target`, { cache: 'no-store', signal }),
  supplierPayableNotificationTarget: (id: string, signal?: AbortSignal) => request<SupplierPayableNotificationTarget>(`/notifications/${encodeURIComponent(id)}/supplier-payable-target`, { cache: 'no-store', signal }),
  expenseAdjustmentNotificationTarget: (id: string, signal?: AbortSignal) => request<ExpenseAdjustmentNotificationTarget>(`/notifications/${encodeURIComponent(id)}/expense-adjustment-target`, { cache: 'no-store', signal }),
  expensePartialAdjustmentNotificationTarget: (id: string, signal?: AbortSignal) => request<ExpensePartialAdjustmentNotificationTarget>(`/notifications/${encodeURIComponent(id)}/expense-partial-adjustment-target`, { cache: 'no-store', signal }),
  repaymentNotificationTarget: (id: string, signal?: AbortSignal) => request<RepaymentNotificationTarget>(`/notifications/${encodeURIComponent(id)}/repayment-target`, { cache: 'no-store', signal }),
  supplierAdjustmentNotificationTarget: (id: string, signal?: AbortSignal) => request<SupplierAdjustmentNotificationTarget>(`/notifications/${encodeURIComponent(id)}/supplier-adjustment-target`, { cache: 'no-store', signal }),
  supplierSettlementNotificationTarget: (id: string, signal?: AbortSignal) => request<SupplierSettlementNotificationTarget>(`/notifications/${encodeURIComponent(id)}/supplier-settlement-target`, { cache: 'no-store', signal }),
  expenseSettlementNotificationTarget: (id: string, signal: AbortSignal) => request<ExpenseSettlementNotificationTarget>(`/notifications/${encodeURIComponent(id)}/expense-settlement-target`, { signal, cache: 'no-store' }),
  reversalCheckNotificationTarget: (id: string, signal: AbortSignal) => request<ReversalCheckNotificationTarget>(`/notifications/${encodeURIComponent(id)}/reversal-check-target`, { signal, cache: 'no-store' }),
  reversalNotificationTarget: (id: string, signal: AbortSignal) => request<ReversalNotificationTarget>(`/notifications/${encodeURIComponent(id)}/reversal-target`, { signal, cache: 'no-store' }),
  budgetNotificationTarget: (id: string, signal: AbortSignal) => request<BudgetNotificationTarget>(`/notifications/${encodeURIComponent(id)}/budget-target`, { signal, cache: 'no-store' }),
  voucherNotificationTarget: (id: string, signal: AbortSignal) => request<VoucherNotificationTarget>(`/notifications/${encodeURIComponent(id)}/voucher-target`, { signal, cache: 'no-store' }),
  taskRecipients: (taskId: string, signal: AbortSignal) => request<string[]>(`/tasks/${encodeURIComponent(taskId)}/recipients`, { signal }),
  taskCountersignMembers: (taskId: string, signal: AbortSignal) => request<CountersignView>(`/tasks/${encodeURIComponent(taskId)}/countersign-members`, { signal, cache: 'no-store' }),
  changeCountersignMembers: (taskId: string, body: CountersignInput) => write<CountersignReceipt>(`/tasks/${encodeURIComponent(taskId)}/countersign-changes`, 'POST', body.action === 'ADD' ? '增加必要会签人' : '移除未决会签任务', body),
  taskAction: (taskId: string, body: TaskActionInput) => write<{ taskId: string; action: string; applicationStatus: string; version: number }>(`/tasks/${encodeURIComponent(taskId)}/actions`, 'POST', '处理审批任务', body),
  searchAudit: (filters: AuditSearchFilters, signal: AbortSignal) => request<AuditSearchPage>('/operations/audit' + historyQuery(filters), { signal }),
  searchApplications: (filters: ApplicationSearchFilters, signal: AbortSignal) => request<ApplicationSearchPage>('/operations/applications' + historyQuery(filters), { signal }),
  searchVisibleApplications: (filters: ApplicationSearchFilters, signal: AbortSignal) => request<ApplicationSearchPage>('/applications/search' + historyQuery(filters), { signal }),
  exportAudit: (filters: AuditExportFilters, signal: AbortSignal) => request<Blob>('/operations/audit/export' + historyQuery(filters), { signal }, 'xlsx'),
  exportApplications: (filters: ApplicationExportFilters, signal: AbortSignal) => request<Blob>('/operations/applications/export' + historyQuery(filters), { signal }, 'xlsx'),
  applications: (signal?: AbortSignal) => request<Application[]>('/applications', { signal }),
  application: (id: string, signal?: AbortSignal) => request<Application>(`/applications/${encodeURIComponent(id)}`, { signal }),
  updateApplication: (id: string, body: { expectedVersion: number; title: string; payload: Record<string, unknown> }) => write<Application>(`/applications/${encodeURIComponent(id)}`, 'PUT', '保存申请修改', body),
  roundDiagram: (id: string, round: number, signal?: AbortSignal) => request<RoundDiagram>(`/applications/${encodeURIComponent(id)}/rounds/${round}/diagram`, { signal }),
  subprocessRelations: (id: string, round: number, afterId: string | undefined, signal: AbortSignal) => request<SubprocessRelationsPage>(`/applications/${encodeURIComponent(id)}/rounds/${round}/subprocesses?limit=30${afterId ? '&afterId=' + encodeURIComponent(afterId) : ''}`, { signal, cache: 'no-store' }),
  applicationRounds: (id: string, signal?: AbortSignal) => request<SubmissionRound[]>(`/applications/${encodeURIComponent(id)}/rounds`, { signal }),
  createApplication: (body: { businessNo: string; processKey: string; definitionVersion: number; title: string; payload: Record<string, unknown> }) => write<Application>('/applications', 'POST', '创建申请草稿', body),
  myAppointments: (afterId: string | undefined, signal: AbortSignal) => request<InitiatorAppointmentPage>('/organization/my-appointments?limit=30' + (afterId ? '&afterId=' + encodeURIComponent(afterId) : ''), { signal }),
  definitionInitiatorRequirements: (id: string, signal: AbortSignal) => request<InitiatorRequirementsView>(`/process-definitions/${encodeURIComponent(id)}/initiator-requirements`, { signal, cache: 'no-store' }),
  applicationInitiatorRequirements: (id: string, signal: AbortSignal) => request<InitiatorRequirementsView>(`/applications/${encodeURIComponent(id)}/initiator-requirements`, { signal, cache: 'no-store' }),
  submitApplication: (id: string, expectedVersion: number, initiatorAppointmentId?: string) => write<Application>(`/applications/${encodeURIComponent(id)}/submit`, 'POST', '提交申请', { expectedVersion, ...(initiatorAppointmentId ? { initiatorAppointmentId } : {}) }),
  withdrawApplication: (id: string, body: { expectedVersion: number; comment?: string }) => write<Application>(`/applications/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回申请', body),
  cancelApplication: (id: string, body: { expectedVersion: number; comment?: string }) => write<Application>(`/applications/${encodeURIComponent(id)}/cancel`, 'POST', '作废申请', body),
  definitionAssignees: (signal: AbortSignal) => request<AssigneeOption[]>('/process-definitions/assignee-options', { signal }),
  formAssigneeOptions: (signal: AbortSignal) => request<FormAssigneeOption[]>('/process-definitions/form-assignee-options', { signal, cache: 'no-store' }),
  searchDefinitions: (filters: DefinitionCatalogFilters, signal: AbortSignal) => request<DefinitionCatalogPage>('/process-definitions/search?' + new URLSearchParams(Object.entries(filters).filter(([, value]) => value !== undefined && value !== '').map(([key, value]) => [key, String(value)])), { signal }),
  getDefinition: (id: string, signal?: AbortSignal) => request<Definition>(`/process-definitions/${encodeURIComponent(id)}`, { signal }),
  templates: () => request<ProcessTemplate[]>('/process-templates'),
  financialTemplateExamples: (templateKey: string) => request<FinancialTemplateExamples>(`/process-templates/${encodeURIComponent(templateKey)}/financial-examples`),
  copyTemplate: (templateKey: string, body: TemplateCopyInput) => write<Definition>(`/process-templates/${encodeURIComponent(templateKey)}/copy`, 'POST', '复制流程模板为草稿', body),
  definition: (body: { key: string; name: string; graph: Graph; formSchema?: FormSchema | null; notificationTexts?: NotificationTexts }) => write<Definition>('/process-definitions', 'POST', '创建流程草稿', body),
  updateDefinition: (id: string, body: { name: string; graph: Graph; expectedRevision: number; formSchema?: FormSchema | null; notificationTexts?: NotificationTexts }) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}`, 'PUT', '保存流程草稿', body),
  upgradeConditions: (graph: Graph, signal?: AbortSignal) => request<Graph>('/process-definitions/upgrade-conditions', { method: 'POST', body: JSON.stringify({ graph }), signal }),
  validateDefinition: (graph: Graph, formSchema?: FormSchema | null, signal?: AbortSignal, key?: string) => request<ValidationResult>('/process-definitions/validate', { method: 'POST', body: JSON.stringify({ graph, formSchema, key }), signal }),
  publishDefinition: (id: string, revision: number, changeNote: string) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}/publish?expectedRevision=${revision}`, 'POST', '发布流程', { changeNote })
}
