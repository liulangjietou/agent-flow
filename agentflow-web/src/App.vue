<script setup lang="ts">
import type { ExpenseTaskActivity } from './expenses'
import WorkspaceTabs from './components/WorkspaceTabs.vue'
import AccountMappingManager from './components/AccountMappingManager.vue'
import { mappingDrafts } from './accountMappingDrafts'
import ExpenseConfigurationManager from './components/ExpenseConfigurationManager.vue'
import { configurationDrafts } from './expenseConfigurationDrafts'
import DefinitionNotificationTexts from './components/DefinitionNotificationTexts.vue'
import { copyNotificationTexts } from './notificationTexts'
import { computed, nextTick, onBeforeUnmount, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import ApplicationRecord from './components/ApplicationRecord.vue'
import type { RelatedRound } from './subprocessRelations'
import WorkspaceNavigation from './components/WorkspaceNavigation.vue'
import type { WorkspacePage as Page } from './workspaceNavigation'
import BranchDiagnostics from './components/BranchDiagnostics.vue'
import { DesignerValidation } from './designerValidation'
import ApplicationSearch from './components/ApplicationSearch.vue'
import IntegrationWorkspace from './components/IntegrationWorkspace.vue'
import AuditSearch from './components/AuditSearch.vue'
import TaskActions from './components/TaskActions.vue'
import type { CountersignInput, CountersignView } from './countersignMembership'
import ExpenseWorkspace from './components/ExpenseWorkspace.vue'
import CashierWorkspace from './components/CashierWorkspace.vue'
import PaymentBatchWorkspace from './components/PaymentBatchWorkspace.vue'
import SupplierCashierWorkspace from './components/SupplierCashierWorkspace.vue'
import ExpenseDetail from './components/ExpenseDetail.vue'
import ExpensePlanDetail from './components/ExpensePlanDetail.vue'
import AdvanceRequestDetail from './components/AdvanceRequestDetail.vue'
import ProcurementPaymentDetail from './components/ProcurementPaymentDetail.vue'
import BudgetAdjustmentDetail from './components/BudgetAdjustmentDetail.vue'
import { procurementDrafts, type ProcurementReceipt } from './procurementPayment'
import { budgetAdjustmentDrafts, type BudgetAdjustmentReceipt } from './budgetAdjustment'
import { advanceDrafts, type AdvanceReceipt } from './advanceRequest'
import { planDrafts, type PlanDetail as PlanDetailData } from './expensePlan'
import { expenseDrafts } from './expenseDraft'
import { invoiceUploads } from './invoiceWallet'
import type { ExpenseDetail as ExpenseDetailData } from './expenses'
import TaskDeadlineStatus from './components/TaskDeadlineStatus.vue'
import PendingTaskQueue from './components/PendingTaskQueue.vue'
import NotificationInbox from './components/NotificationInbox.vue'
import CopyRecord from './components/CopyRecord.vue'
import DefinitionCopyRecipient from './components/DefinitionCopyRecipient.vue'
import { isTaskNotification } from './notificationInbox'
import { taskActionLabels } from './taskActions'
import WorkspaceRecords from './components/WorkspaceRecords.vue'
import ApplicationHistory from './components/ApplicationHistory.vue'
import RoundComparison from './components/RoundComparison.vue'
import ApplicationComments from './components/ApplicationComments.vue'
import AssistRunRecords from './components/AssistRunRecords.vue'
import { commentDrafts, type CommentDraft, type ApplicationComment } from './applicationComments'
import RequestRecovery from './components/RequestRecovery.vue'
import FormFields from './components/FormFields.vue'
import InitiatorAppointmentPicker from './components/InitiatorAppointmentPicker.vue'
import InitiatorRequirementNotice from './components/InitiatorRequirementNotice.vue'
import { InitiatorRequirements } from './initiatorRequirements'
import FormSchemaEditor from './components/FormSchemaEditor.vue'
import TemplateCenter from './components/TemplateCenter.vue'
import PortableTemplate from './components/PortableTemplate.vue'
import type { PortableProcess } from './portableTemplate'
import SystemChecks from './components/SystemChecks.vue'
import FirstWorkflow from './components/FirstWorkflow.vue'
import { guideHidden, rememberGuideSelection } from './firstWorkflow'
import OrganizationDirectory from './components/OrganizationDirectory.vue'
import ApprovalProxyManager from './components/ApprovalProxyManager.vue'
import { approvalProxyDrafts, type ApprovalProxyReceipt } from './approvalProxies'
import { organizationDrafts, type OrganizationRecord } from './organization'
import { organizationSyncDrafts, syncPath, type SyncReceipt, type SyncPlanReceipt } from './organizationSync'
import { rememberSignatureOperation } from './signatures'
import { initializationDrafts } from './tenantInitialization'
import BusinessCalendars from './components/BusinessCalendars.vue'
import { calendarDrafts, type BusinessCalendar } from './businessCalendars'
import ApprovalOperations from './components/ApprovalOperations.vue'
import ExpenseFinancialReporting from './components/ExpenseFinancialReporting.vue'
import ApiReference from './components/ApiReference.vue'
import DefinitionSimulation from './components/DefinitionSimulation.vue'
import DefinitionComparison from './components/DefinitionComparison.vue'
import DefinitionCatalog from './components/DefinitionCatalog.vue'
import DefinitionPicker from './components/DefinitionPicker.vue'
import { DefinitionSelection } from './definitionSelection'
import DefinitionAssignee from './components/DefinitionAssignee.vue'
import DefinitionResponsibilities from './components/DefinitionResponsibilities.vue'
import { readResponsibilities, writeResponsibilities, type ApprovalResponsibilities } from './approvalResponsibilities'
import DefinitionDeadline from './components/DefinitionDeadline.vue'
import DefinitionTimerWait from './components/DefinitionTimerWait.vue'
import DefinitionServiceTask from './components/DefinitionServiceTask.vue'
import type { ServiceTaskBinding } from './serviceTasks'
import DefinitionEventWait from './components/DefinitionEventWait.vue'
import DefinitionSubprocess from './components/DefinitionSubprocess.vue'
import type { SubprocessBinding } from './subprocessDesigner'
import type { EventBinding } from './events'
import DefinitionExpenseStage from './components/DefinitionExpenseStage.vue'
import DefinitionExpenseSelfApproval from './components/DefinitionExpenseSelfApproval.vue'
import DefinitionExpenseDuplicateApproval from './components/DefinitionExpenseDuplicateApproval.vue'
import DefinitionExpenseSplitRisk from './components/DefinitionExpenseSplitRisk.vue'
import { writeSplitRule, writeSplitGateway, type SplitRuleFields } from './expenseSplitPolicy'
import { assigneeLabel } from './definitionAssignees'
import { approvalPolicyLabel, isCountersignMode } from './approvalPolicy'
import { simulationIssue } from './definitionSimulation'
import DefinitionPublication from './components/DefinitionPublication.vue'
import DefinitionAvailability from './components/DefinitionAvailability.vue'
import type { DefinitionAvailabilityInput } from './api'
import PublicationDialog from './components/PublicationDialog.vue'
import { DraftAutosave } from './draftAutosave'
import type { GraphNode } from './api'
import QuickDesigner from './components/QuickDesigner.vue'
import ConditionEditor from './components/ConditionEditor.vue'
import DefinitionRiskPolicy from './components/DefinitionRiskPolicy.vue'
import { copyRiskPolicy } from './submissionRisk'
import type { RiskPolicy } from './api'
import { describeBranch, branchTooltip } from './conditionPresentation'
import { editQuickGraph, type QuickCommand } from './quickDesigner'
import { loadDesignerNodes, serializeDesignerNodes, type DesignerNode as FlowNode, type DesignerDeadline } from './designerGraph'
import { connectCanvasGraph, insertCanvasGraph, deleteCanvasNode, orderCanvasBranches, CANVAS_KEY_STEP, CANVAS_LARGE_KEY_STEP } from './designerEditing'
import { arrangeNodes, routeEdges, serializeDesignerEdges, graphBounds, fittedViewport, clampZoom, zoomedScroll, draggedPosition, nodeRectangle, CANVAS_PADDING, MIN_ZOOM, MAX_ZOOM, ZOOM_STEP, type Point } from './designerLayout'
import UnsavedConfirmationDialog from './components/UnsavedConfirmationDialog.vue'
import EnterpriseLogoutDialog from './components/EnterpriseLogoutDialog.vue'
import { UnsavedConfirmation } from './unsavedConfirmation'
import { cloneSchema, defaultFormSchema, validatePayload, type FieldErrors, type FormSchema } from './formSchema'
import { api, bindAuthenticationActor, writeRequests, type AuthOptions, type Actor, type ApiError, type Application, type Definition, type Graph, type GraphEdge, type Task, type TaskActionInput, type TemplateCopyInput, type SimulationResult, type ComparisonChange, type InboxMessage, type FinancialNotificationTarget, type VoucherNotificationTarget, type BudgetNotificationTarget, type ReversalNotificationTarget, type DisbursementReturnNotificationTarget, type RepaymentReviewNotificationTarget, type ExpensePartialAdjustmentNotificationTarget, type ExpenseAdjustmentNotificationTarget, type SupplierPayableNotificationTarget, type BudgetAdjustmentNotificationTarget, type RepaymentNotificationTarget, type ExpenseReturnNotificationTarget, type SupplierReturnNotificationTarget, type SupplierAdjustmentNotificationTarget, type SupplierSettlementNotificationTarget, type ExpenseSettlementNotificationTarget, type ReversalCheckNotificationTarget } from './api'
import type { PendingWrite } from './pendingWrites.js'
import { rememberDraftRun, type DraftAssistReceipt } from './draftAssist'
import { acknowledgeExplanation, type ExplanationReceipt } from './precheckExplanation'
import { acknowledgeRisk, type RiskReceipt } from './expenseRisk'
import { acknowledgeExpenseAssist, type ExpenseAssistReceipt } from './expenseDraftAssist'
import { acknowledgeExtraction, extractionDrafts, type ExtractionReceipt } from './invoiceExtraction'

type NodeType = 'START' | 'COPY' | 'TIMER_WAIT' | 'EVENT_WAIT' | 'SERVICE_TASK' | 'SUB_PROCESS' | 'USER_TASK' | 'EXCLUSIVE_GATEWAY' | 'PARALLEL_GATEWAY' | 'END'
const page = ref<Page>('workbench')
const comparisonOpen = ref(false)
const comparisonInput = computed(() => ({ key: definitionKey.value.trim(), name: definitionName.value.trim(), graph: simulationGraph.value, formSchema: definitionFormSchema.value, notificationTexts: definitionNotificationTexts.value }))
const simulationOpen = ref(false)
const simulationResult = ref<SimulationResult | null>(null)
const simulationGraph = computed(graphPayload)
const loggedIn = ref(false)
const authOptions = ref<AuthOptions | null>(null)
const authLoading = ref(true)
const sessionExpired = ref(false)
const logoutOpen = ref(false)
const logoutReturnFocus = ref<HTMLElement | null>(null)
let logoutActorScope = ''
let providerNavigation = false
const username = ref('')
const password = ref('')
const tenantId = ref('demo')
const actor = ref<Actor | null>(null)
const actorScope = computed(() => actor.value ? JSON.stringify([actor.value.tenantId, actor.value.userId]) : '')
const confirmation = reactive(new UnsavedConfirmation())
const confirmationOpen = computed(() => confirmation.active !== null)
const confirmationReturnFocus = ref<HTMLElement | null>(null)
const publicationOpen = ref(false)
const publicationNote = ref('')
const publicationError = ref('')
const publicationReturnFocus = ref<HTMLElement | null>(null)
let viewActive = true
const templateRefresh = ref(0)
const notice = ref('')
const busy = ref(false)
const pendingWrites = ref<PendingWrite[]>([])
const writesBlocked = computed(() => sessionExpired.value || pendingWrites.value.length > 0)
const recordRefresh = ref(0)
const recoveryError = ref('')
const workspace = ref<HTMLElement | null>(null)
const unsubscribeWrites = writeRequests.subscribe(() => { pendingWrites.value = writeRequests.pending() })
const serverAvailable = ref(false)
const taskCount = ref<number | null>(null)
const taskRefresh = ref(0)
const taskQueueView = ref<'list' | 'board'>('list')
const cashierKind = ref<'employee' | 'supplier' | 'batches'>('employee')
const notificationPaymentId = ref('')
watch(page, value => { if (value !== 'cashier') notificationPaymentId.value = '' })
const taskQueuePanel = ref<InstanceType<typeof PendingTaskQueue> | null>(null)
watch(actorScope, () => { taskQueueView.value = 'list'; cashierKind.value = 'employee'; notificationPaymentId.value = '' }, { flush: 'sync' })
let workspaceRefreshGeneration = 0
let taskCountRequest: AbortController | null = null
let taskDetailRequest: AbortController | null = null
const activeTask = ref<Task | null>(null)
const activeApplication = ref<Application | null>(null)
const expenseTaskBusy = ref(false)
watch(() => [actorScope.value, activeTask.value?.taskId, activeApplication.value?.id, activeApplication.value?.version], () => { expenseTaskBusy.value = false }, { flush: 'sync' })
/** 只接收当前申请版本的费用操作状态，旧任务的取消或卸载不能解除新任务的锁。 */
function expenseTaskActivity(value: ExpenseTaskActivity) {
  if (value.scopeKey !== actorScope.value || value.taskId !== activeTask.value?.taskId
    || value.applicationId !== activeApplication.value?.id || value.applicationVersion !== activeApplication.value?.version) return
  expenseTaskBusy.value = value.busy
}
const detailError = ref('')
const detailLoading = ref(false)
const taskDetailPanel = ref<HTMLElement | null>(null)
const operationStatus = ref<HTMLElement | null>(null)
const assistRefresh = ref(0)
const taskTab = ref<'detail' | 'compare' | 'timeline' | 'audit' | 'comments' | 'assist'>('detail')
const commentRefresh = ref(0)
const restoredDefinition = new DefinitionSelection(api.searchDefinitions, api.getDefinition)
const applicationSelection = reactive(new DefinitionSelection(api.searchDefinitions, api.getDefinition))
const requestedApplicationDefinition = ref('')
const definitionId = ref('')
const definitionRevision = ref(0)
const definitionVersion = ref(0)
const definitionStatus = ref('DRAFT')
const definitionStartEnabled = ref<boolean | undefined>(true)
const availabilityError = ref('')
const definitionKey = ref('expense-reimbursement')
const definitionName = ref('费用报销审批')
const definitionFormSchema = ref<FormSchema | null>(defaultFormSchema())
const definitionNotificationTexts = ref(copyNotificationTexts())
const catalogOpen = ref(false)
const savedSnapshot = ref('')
const editorSession = ref(0)
const autosaveEnabled = ref(true)
const composing = ref(false)
/** 保存检查点仅留在当前页面，用于确认原请求而不覆盖发送之后的新输入。@author owlzhangfq@gmail.com */
interface DraftCheckpoint { scope: string; snapshot: string; path: string }
let pendingDraftCheckpoint: DraftCheckpoint | null = null
const draftScope = computed(() => `${actorScope.value}:${editorSession.value}`)
const autosave = reactive(new DraftAutosave(
  () => ({ scope: draftScope.value, snapshot: snapshot(), eligible: viewActive && loggedIn.value && canManageDefinitions.value
    && page.value === 'designer' && autosaveEnabled.value && dirty.value && !readonlyDefinition.value
    && !busy.value && !writesBlocked.value && !confirmationOpen.value && !publicationOpen.value && !logoutOpen.value
    && !dragging.value && !composing.value && !!definitionKey.value.trim() && !!definitionName.value.trim() }),
  async () => { await persistDraft() }
))
const draftConflict = computed(() => ['DRAFT_VERSION_CONFLICT', 'CONCURRENCY_CONFLICT', 'DEFINITION_IMMUTABLE'].includes(autosave.failure?.code ?? ''))
const visiblePendingWrites = computed(() => pendingWrites.value.filter(operation => !(autosave.saving && operation.sending && operation.path === pendingDraftCheckpoint?.path)))
const autosaveLabel = computed(() => autosave.saving ? '正在保存草稿…' : autosave.failure ? '自动保存已暂停'
  : !autosaveEnabled.value ? '自动保存已关闭' : !definitionKey.value.trim() || !definitionName.value.trim() ? '填写流程标识和名称后自动保存'
  : !definitionId.value && !dirty.value ? '首次修改后自动保存'
  : autosave.scheduled || dirty.value ? '编辑停顿 2 秒后自动保存' : '草稿已保存')
const validationOpened = ref(false)
const validation = reactive(new DesignerValidation(api.validateDefinition))
let validationTimer: ReturnType<typeof setTimeout> | undefined
const validationErrors = computed(() => validation.result?.errors ?? [])
const branchDiagnostics = computed(() => validation.result?.branchDiagnostics ?? [])
const validationOtherErrors = computed(() => validationErrors.value.filter(error => !error.startsWith('BRANCH_COVERAGE_GAP:')))
const validationNodeIds = computed(() => [...new Set(validationErrors.value.map(error => {
  const target = simulationIssue(error).target
  return nodes.value.some(node => node.id === target) ? target : edges.value.find(edge => edge.id === target)?.source ?? ''
}).filter(Boolean))])
const validationMessage = computed(() => validation.loading ? '正在检查最新设计…' : validation.error
  || (validation.result ? validationErrors.value.length ? `发现 ${validationErrors.value.length} 项阻断问题。`
    : branchDiagnostics.value.length ? `未发现阻断错误，有 ${branchDiagnostics.value.length} 项提醒，请核对业务规则。` : '服务端校验通过。'
    : validationOpened.value ? '内容已修改，正在等待重新校验。' : '尚未校验，发布前将运行服务端校验。'))
const newApplicationOpen = ref(false)
const recordApplicationId = ref('')
const recordInitialRoundNo = ref<number | null>(null)
watch(recordApplicationId, () => { recordInitialRoundNo.value = null }, { flush: 'sync' })
/** 从已授权关联进入固定轮次；目标详情仍重新检查自身权限。 */
function openRelatedRound(target: RelatedRound) { recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo }
const selectedCopy = ref<{ applicationId: string; roundNo: number } | null>(null)
watch(actorScope, () => { selectedCopy.value = null }, { flush: 'sync' })
const applicationDefinitionId = computed(() => applicationSelection.definition?.id ?? '')
const applicationTitle = ref('')
const initiatorAppointmentId = ref('')
const applicationRequirements = reactive(new InitiatorRequirements(api.definitionInitiatorRequirements, api.applicationInitiatorRequirements))
const applicationBusinessNo = ref('')
const applicationAmount = ref('')
const applicationDescription = ref('')
const createdApplication = ref<Application | null>(null)
const applicationPayload = ref<Record<string, unknown>>({})
const applicationFieldErrors = ref<FieldErrors>({})
const applicationFormError = ref('')
const applicationFormSchema = computed(() => createdApplication.value ? createdApplication.value.formSchema ?? null : applicationSelection.definition?.formSchema ?? null)
const conditionLanguageVersion = ref<1 | 2>(2)
const definitionRiskPolicy = ref<RiskPolicy | null>(null)
const designerMode = ref<'quick' | 'advanced'>('quick')
// 编辑视图保留输入中的空格，提交时才调用 graphPayload 规范化。
const quickGraph = computed<Graph>(() => ({ conditionLanguageVersion: conditionLanguageVersion.value, riskPolicy: copyRiskPolicy(definitionRiskPolicy.value), nodes: serializeDesignerNodes(nodes.value), edges: edges.value.map(edge => ({ ...edge })) }))
const nodes = ref<FlowNode[]>([])
const edges = ref<GraphEdge[]>([])
const selectedId = ref('')
const selectedEdgeId = ref('')
const dragging = ref<string | null>(null)
const history = ref<string[]>([])
const future = ref<string[]>([])
const connectionTarget = ref('')
const canvas = ref<HTMLElement | null>(null)
const canvasZoom = ref(1)
const canvasSize = ref({ width: 0, height: 0 })
const canvasMessage = ref('缩放只改变视图，自动布局可撤销。')
const branchDescription = (edge: GraphEdge) => describeBranch(edge, definitionFormSchema.value?.fields ?? [], conditionLanguageVersion.value)
const branchTitle = (edge: GraphEdge) => branchTooltip(edge, definitionFormSchema.value?.fields ?? [], conditionLanguageVersion.value)
const routedEdges = computed(() => routeEdges(nodes.value, edges.value, branchDescription))
const canvasBounds = computed(() => graphBounds(nodes.value, routedEdges.value))
const stageSize = computed(() => ({ width: Math.max(canvasSize.value.width / canvasZoom.value, canvasBounds.value.right + CANVAS_PADDING * 2),
  height: Math.max(canvasSize.value.height / canvasZoom.value, canvasBounds.value.bottom + CANVAS_PADDING * 2) }))
let stopNodeDrag: (() => void) | null = null
let stopConnectionDrag: (() => void) | null = null
let canvasNudge: { nodeId: string; key: string; shift: boolean } | null = null
const connectionPreview = ref<{ sourceId: string; x1: number; y1: number; x2: number; y2: number } | null>(null)
const palette: Array<{ type: NodeType; label: string; icon: string }> = [
  { type: 'USER_TASK', label: '人工审批', icon: '人' },
  { type: 'COPY', label: '抄送', icon: '抄' },
  { type: 'TIMER_WAIT', label: '定时等待', icon: '时' },
  { type: 'EVENT_WAIT', label: '事件等待', icon: '事' },
  { type: 'SERVICE_TASK', label: '服务任务', icon: '服' },
  { type: 'SUB_PROCESS', label: '子流程', icon: '子' },
  { type: 'EXCLUSIVE_GATEWAY', label: '条件分支', icon: '◇' },
  { type: 'PARALLEL_GATEWAY', label: '并行网关', icon: '＋' },
  { type: 'END', label: '结束节点', icon: '●' }
]
const selectedNode = computed(() => nodes.value.find(node => node.id === selectedId.value) ?? null)
const selectedEdge = computed(() => edges.value.find(edge => edge.id === selectedEdgeId.value) ?? null)
const canManageDefinitions = computed(() => actor.value?.roles.some(role => ['PROCESS_ADMIN', 'ADMIN'].includes(role)) ?? false)
const canReadFinancialReports = computed(() => actor.value?.roles.includes('FINANCE') ?? false)
const financialReportScope = computed(() => actor.value ? JSON.stringify([actorScope.value, [...actor.value.roles].sort()]) : '')
const canConfigureFinance = computed(() => actor.value?.roles.includes('FINANCE_CONFIG_ADMIN') ?? false)
const canCashier = computed(() => actor.value?.roles.includes('CASHIER') ?? false)
const canInspectSystem = computed(() => actor.value?.roles.includes('ADMIN') ?? false)
const readonlyDefinition = computed(() => definitionStatus.value !== 'DRAFT')
const editorLocked = computed(() => readonlyDefinition.value || (writesBlocked.value && !autosave.saving) || busy.value || confirmationOpen.value || publicationOpen.value || logoutOpen.value)
const dirty = computed(() => snapshot() !== savedSnapshot.value)
const today = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full' }).format(new Date())
const statusLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已拒绝', APPROVED: '已批准', REVOKED: '已撤销', CANCELLED: '已作废' }
const statusLabel = (status: string) => statusLabels[status] ?? status
const errorMessage = (error: unknown) => (error as ApiError)?.message ?? '无法连接服务，请稍后重试'
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')

function defaultGraph() {
  conditionLanguageVersion.value = 2
  definitionRiskPolicy.value = null
  nodes.value = [
    { id: 'start', name: '开始', type: 'START', x: 40, y: 180, assigneeRule: '' },
    { id: 'manager', name: '部门审批', type: 'USER_TASK', x: 190, y: 180, assigneeRule: 'role:MANAGER' },
    { id: 'amount', name: '金额判断', type: 'EXCLUSIVE_GATEWAY', x: 390, y: 180, assigneeRule: '' },
    { id: 'finance', name: '财务复核', type: 'USER_TASK', x: 590, y: 75, assigneeRule: 'role:FINANCE' },
    { id: 'end', name: '结束', type: 'END', x: 810, y: 180, assigneeRule: '' }
  ]
  edges.value = [
    { id: 'e1', source: 'start', target: 'manager', condition: '', defaultBranch: false },
    { id: 'e2', source: 'manager', target: 'amount', condition: '', defaultBranch: false },
    { id: 'e3', source: 'amount', target: 'end', condition: '', defaultBranch: true },
    { id: 'e4', source: 'amount', target: 'finance', condition: 'amount > 5000', defaultBranch: false },
    { id: 'e5', source: 'finance', target: 'end', condition: '', defaultBranch: false }
  ]
  selectedId.value = 'amount'
  selectedEdgeId.value = ''
}
function snapshot() { return JSON.stringify({ key: definitionKey.value, name: definitionName.value, riskPolicy: copyRiskPolicy(definitionRiskPolicy.value), conditionLanguageVersion: conditionLanguageVersion.value, nodes: nodes.value, edges: edges.value, formSchema: definitionFormSchema.value, notificationTexts: definitionNotificationTexts.value }) }
function remember() { history.value.push(snapshot()); if (history.value.length > 50) history.value.shift(); future.value = [] }
// 首次保存前的撤销快照可能尚无标识；已落库草稿的身份不能随内容撤销。
function restore(raw: string) { const value = JSON.parse(raw); definitionRiskPolicy.value = copyRiskPolicy(value.riskPolicy); conditionLanguageVersion.value = value.conditionLanguageVersion ?? 1; if (!definitionId.value) definitionKey.value = value.key; definitionName.value = value.name; nodes.value = value.nodes; edges.value = value.edges; definitionFormSchema.value = cloneSchema(value.formSchema ?? null); definitionNotificationTexts.value = copyNotificationTexts(value.notificationTexts) }
function undo() { if (editorLocked.value) return; cancelCanvasInteraction(); const value = history.value.pop(); if (value) { future.value.push(snapshot()); restore(value) } }
function redo() { if (editorLocked.value) return; cancelCanvasInteraction(); const value = future.value.pop(); if (value) { history.value.push(snapshot()); restore(value) } }
function resetEditor() { history.value = []; future.value = []; selectedEdgeId.value = ''; connectionTarget.value = ''; clearValidation(true) }
function graphPayload(): Graph {
  return {
    conditionLanguageVersion: conditionLanguageVersion.value,
    riskPolicy: copyRiskPolicy(definitionRiskPolicy.value),
    nodes: serializeDesignerNodes(nodes.value),
    edges: serializeDesignerEdges(nodes.value, edges.value).map(edge => ({ ...edge, condition: edge.defaultBranch ? '' : edge.condition.trim() }))
  }
}
function applyDefinition(definition: Definition) {
  editorSession.value++; autosave.reset(); pendingDraftCheckpoint = null; composing.value = false
  definitionId.value = definition.id
  definitionKey.value = definition.key; definitionName.value = definition.name; definitionFormSchema.value = cloneSchema(definition.formSchema ?? null); definitionNotificationTexts.value = copyNotificationTexts(definition.notificationTexts)
  definitionRevision.value = definition.revision; definitionVersion.value = definition.version; definitionStatus.value = definition.status
  definitionStartEnabled.value = definition.startEnabled; availabilityError.value = ''
  conditionLanguageVersion.value = definition.graph.conditionLanguageVersion ?? 1
  definitionRiskPolicy.value = copyRiskPolicy(definition.graph.riskPolicy)
  nodes.value = loadDesignerNodes(definition.graph.nodes)
  edges.value = definition.graph.edges.map(edge => ({ ...edge, defaultBranch: edge.defaultBranch ?? false }))
  selectedId.value = nodes.value[0]?.id ?? ''; resetEditor(); savedSnapshot.value = snapshot()
  localStorage.setItem(`agentflow.definition.${tenantId.value}`, definition.id)
}
/** 应用层持有原操作意图，等待结束后复核会话与业务锁，再执行原动作。 */
async function confirmReplaceDefinition(confirmLabel: string, operation: () => void | Promise<void>,
  allowed = () => !busy.value && !writesBlocked.value, required = !readonlyDefinition.value && dirty.value) {
  const sessionActor = actor.value
  const scope = actorScope.value
  const current = () => viewActive && !!sessionActor && actor.value === sessionActor && actorScope.value === scope && allowed()
  if (!current() || confirmationOpen.value) return
  if (required) confirmationReturnFocus.value = document.activeElement as HTMLElement | null
  if (await confirmation.confirm(confirmLabel, current, required)) {
    // Promise 恢复与调用方之间仍可能发生状态变化；此处是实际执行前的最后一道准入检查。
    if (current()) await operation()
  }
}
async function newDefinition(copy = false) {
  await confirmReplaceDefinition('放弃修改并新建', () => {
    editorSession.value++; autosave.reset(); pendingDraftCheckpoint = null; composing.value = false
    page.value = 'designer'; catalogOpen.value = false
    if (!copy) { defaultGraph(); definitionKey.value = ''; definitionName.value = '新审批流程'; definitionFormSchema.value = defaultFormSchema(); definitionNotificationTexts.value = copyNotificationTexts() }
    else definitionFormSchema.value = cloneSchema(definitionFormSchema.value)
    definitionId.value = ''; definitionRevision.value = 0; definitionVersion.value = 0; definitionStatus.value = 'DRAFT'; definitionStartEnabled.value = true; availabilityError.value = ''; resetEditor(); savedSnapshot.value = ''
    notice.value = copy ? '已复制为新草稿，保存后可继续编辑并发布新版本。' : '填写流程标识和名称，完成设计后保存草稿。'
  }, () => canManageDefinitions.value && !busy.value && !writesBlocked.value, !copy && !readonlyDefinition.value && dirty.value)
}

/** 治理操作绑定当前版本和身份；结果未知时交给原请求恢复，不自动生成新请求。 */
async function changeDefinitionAvailability(input: DefinitionAvailabilityInput) {
  if (!canManageDefinitions.value || !readonlyDefinition.value || busy.value || writesBlocked.value) return
  const scope = actorScope.value, id = definitionId.value
  busy.value = true; availabilityError.value = ''
  try {
    const definition = await api.changeDefinitionAvailability(id, input)
    if (actorScope.value !== scope || definitionId.value !== id) return
    applyDefinition(definition); templateRefresh.value++
    notice.value = definition.startEnabled ? '此版本已恢复，可新建申请和提交（包括重提）。' : '此版本已停用，首次提交和重提暂停，运行中的审批继续办理。'
  } catch (cause) {
    if (actorScope.value === scope && definitionId.value === id) availabilityError.value = errorMessage(cause)
  } finally { busy.value = false }
}

/** 冲突后重新读取当前状态，保留原发布内容及历史依据。 */
async function refreshDefinitionAvailability() {
  if (busy.value || writesBlocked.value || !canManageDefinitions.value) return
  const scope = actorScope.value, id = definitionId.value
  busy.value = true; availabilityError.value = ''
  try {
    const definition = await api.getDefinition(id)
    if (actorScope.value === scope && definitionId.value === id) { applyDefinition(definition); templateRefresh.value++ }
  } catch (cause) {
    if (actorScope.value === scope && definitionId.value === id) availabilityError.value = errorMessage(cause)
  } finally { busy.value = false }
}
/** 恢复一份已授权的配置，不再读取当前租户的全部流程图和表单。 */
async function restoreDesigner() {
  const scope = actorScope.value, session = editorSession.value
  const remembered = localStorage.getItem(`agentflow.definition.${tenantId.value}`) ?? ''
  const definition = await restoredDefinition.load(scope, remembered, { restoreMissing: true })
  if (!viewActive || actorScope.value !== scope || editorSession.value !== session) return
  if (restoredDefinition.error) throw new Error(restoredDefinition.error)
  if (definition) applyDefinition(definition)
}
async function loadTasks() {
  const scope = actorScope.value, controller = new AbortController()
  taskCountRequest?.abort(); taskCountRequest = controller; taskCount.value = null
  const timeout = setTimeout(() => controller.abort(), 12_000)
  try { const result = await api.taskPage({ limit: 1 }, controller.signal); if (scope && actorScope.value === scope && taskCountRequest === controller) taskCount.value = result.total }
  finally { clearTimeout(timeout) }
}
async function refreshWorkspace(restoreSelection = false) {
  taskRefresh.value++
  const generation = ++workspaceRefreshGeneration
  const scope = actorScope.value
  try { await Promise.all([loadTasks(), ...(restoreSelection ? [restoreDesigner()] : [])]); if (scope && actorScope.value === scope && generation === workspaceRefreshGeneration) serverAvailable.value = true }
  catch (error) { if (scope && actorScope.value === scope && generation === workspaceRefreshGeneration) { serverAvailable.value = false; notice.value = errorMessage(error) } }
}
async function refreshPage() {
  if (authOptions.value?.mode === 'OIDC' && !await restoreEnterpriseSession()) return
  templateRefresh.value++; void refreshWorkspace()
}
/** 申请变更后清除同一申请的旧办理面板，暂停、恢复及其他变更都重新读取队列。 */
async function applicationRecordChanged() {
  if (activeTask.value?.applicationId === recordApplicationId.value) clearTaskSelection()
  await refreshPage()
}
async function copyTemplate(templateKey: string, body: TemplateCopyInput) {
  const originalBody = { ...body }
  await confirmReplaceDefinition('放弃修改并复制', async () => {
    busy.value = true
    try {
      const definition = await api.copyTemplate(templateKey, originalBody)
      applyDefinition(definition); rememberGuideSelection(actorScope.value, definition.id); page.value = 'designer'; templateRefresh.value++
      notice.value = '模板已复制为独立草稿，请核对字段、审批角色与分支后再发布。'
    } catch (error) { notice.value = errorMessage(error) }
    finally { busy.value = false }
  }, () => canManageDefinitions.value && !busy.value && !writesBlocked.value)
}
/** 导入只创建新聚合；与手工新建共用服务端校验、授权、幂等和恢复链路。 */
async function importTemplate(input: PortableProcess) {
  const body: PortableProcess = JSON.parse(JSON.stringify(input))
  await confirmReplaceDefinition('放弃修改并导入', async () => {
    busy.value = true
    const scope = actorScope.value
    try {
      const definition = await api.definition(body)
      if (!viewActive || actorScope.value !== scope) return
      applyDefinition(definition); rememberGuideSelection(scope, definition.id); page.value = 'designer'; templateRefresh.value++
      notice.value = '模板已导入为独立草稿，请核对审批人、运行模拟后再发布。'
    } catch (error) { if (actorScope.value === scope) notice.value = errorMessage(error) }
    finally { busy.value = false }
  }, () => canManageDefinitions.value && !busy.value && !writesBlocked.value)
}
/** 目录和模板记录共用读取入口；写权限仍由编辑器与服务端单独判断。 */
async function openSavedDefinition(id: string) {
  await confirmReplaceDefinition('放弃修改并打开', async () => {
    busy.value = true
    const scope = actorScope.value, controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 12_000)
    try {
      const definition = await api.getDefinition(id, controller.signal)
      if (!viewActive || actorScope.value !== scope) return
      applyDefinition(definition); rememberGuideSelection(actorScope.value, definition.id); page.value = 'designer'; catalogOpen.value = false; notice.value = '已打开当前租户的流程版本。'
    } catch (error) { if (actorScope.value === scope) notice.value = controller.signal.aborted ? '读取流程超时，当前设计已保留，请重新打开。' : errorMessage(error) }
    finally { clearTimeout(timeout); busy.value = false }
  })
}
function clearDesigner() {
  editorSession.value++; autosave.reset(); pendingDraftCheckpoint = null; composing.value = false
  defaultGraph(); definitionId.value = ''; definitionRevision.value = 0; definitionVersion.value = 0
  definitionStatus.value = 'DRAFT'; definitionKey.value = 'expense-reimbursement'; definitionName.value = '费用报销审批'; definitionFormSchema.value = defaultFormSchema(); definitionNotificationTexts.value = copyNotificationTexts()
  resetEditor(); savedSnapshot.value = snapshot()
}
async function login() {
  if (busy.value || authOptions.value?.mode !== 'DEMO') return
  if (!username.value.trim() || !password.value) { notice.value = '请输入用户名和密码'; return }
  busy.value = true
  try { const result = await api.login({ tenantId: tenantId.value.trim(), username: username.value.trim(), password: password.value }); localStorage.setItem('agentflow.token', result.token); actor.value = result.user; bindAuthenticationActor(result.user); loggedIn.value = true; password.value = ''; notice.value = '已进入工作空间'; page.value = canInspectSystem.value && !guideHidden(actorScope.value) ? 'guide' : 'workbench'; await refreshWorkspace(true) }
  catch (error) { notice.value = errorMessage(error) }
  finally { busy.value = false }
}
function requestLogout() {
  if (busy.value || pendingWrites.value.some(operation => operation.sending)) return
  if (!authOptions.value?.providerLogoutUrl) { void logout(); return }
  logoutActorScope = actorScope.value
  logoutReturnFocus.value = document.activeElement as HTMLElement | null
  logoutOpen.value = true
}
async function chooseLogout(scope: 'local' | 'provider') {
  if (busy.value || logoutActorScope !== actorScope.value || pendingWrites.value.some(operation => operation.sending)) return
  logoutOpen.value = false
  await nextTick()
  await logout(scope)
}
async function logout(scope: 'local' | 'provider' = 'local') {
  await confirmReplaceDefinition('放弃修改并退出', async () => {
    busy.value = true
    try {
      if (scope === 'provider') {
        const navigation = await api.prepareProviderLogout()
        const form = document.createElement('form')
        form.method = 'POST'; form.action = navigation.action; form.hidden = true
        for (const [name, value] of Object.entries(navigation.fields)) {
          const input = document.createElement('input')
          input.type = 'hidden'; input.name = name; input.value = value; form.append(input)
        }
        document.body.append(form)
        // 用户已确认退出范围和丢弃内容；身份令牌由服务端协议页处理，不进入业务脚本。
        providerNavigation = true
        form.submit(); form.remove()
        setTimeout(() => { providerNavigation = false; busy.value = false }, 0)
        return
      }
      if (authOptions.value?.mode === 'OIDC') await api.authOptions()
      await api.logout()
    }
    catch (error) {
      // Cookie 会话只能由服务端退出；断网不能显示虚假的退出成功。
      if (authOptions.value?.mode === 'OIDC') { notice.value = '退出未完成：' + errorMessage(error); busy.value = false; return }
    }
    bindAuthenticationActor(null)
    localStorage.removeItem('agentflow.token'); loggedIn.value = false; actor.value = null; serverAvailable.value = false; taskCountRequest?.abort(); taskDetailRequest?.abort(); taskCount.value = null; restoredDefinition.clear(); applicationSelection.clear(); activeTask.value = null; activeApplication.value = null; notice.value = ''; page.value = 'workbench'
    clearDesigner(); templateRefresh.value++
    recordApplicationId.value = ''; recoveryError.value = ''; newApplicationOpen.value = false; createdApplication.value = null; busy.value = false
    sessionExpired.value = false
    await loadAuthentication(false)
  }, () => !busy.value && !pendingWrites.value.some(operation => operation.sending))
}
/** 打开摘要时取得最新任务版本，旧列表不能直接产生审批命令。 */
async function selectTask(item: { taskId: string }) {
  if (busy.value || writesBlocked.value) return
  const scope = actorScope.value, controller = new AbortController()
  taskDetailRequest?.abort(); taskDetailRequest = controller; detailLoading.value = true
  activeTask.value = null; activeApplication.value = null; detailError.value = ''; taskTab.value = 'detail'
  const timeout = setTimeout(() => controller.abort(), 12_000)
  try {
    const task = await api.task(item.taskId, controller.signal)
    if (actorScope.value !== scope || taskDetailRequest !== controller) return
    activeTask.value = task
    const application = await api.application(task.applicationId, controller.signal)
    if (!controller.signal.aborted && actorScope.value === scope && taskDetailRequest === controller) {
      activeApplication.value = application
      await nextTick()
      if (actorScope.value === scope && taskDetailRequest === controller && page.value === 'workbench') {
        taskDetailPanel.value?.scrollIntoView({ block: 'start' })
        taskDetailPanel.value?.focus({ preventScroll: true })
      }
    }
  } catch (error) {
    if (actorScope.value !== scope || taskDetailRequest !== controller) return
    detailError.value = controller.signal.aborted ? '读取待办超时，请重新选择。' : errorMessage(error)
    notice.value = detailError.value
    if ([403, 404].includes((error as ApiError).status)) { clearTaskSelection(); void refreshWorkspace() }
  } finally { clearTimeout(timeout); if (taskDetailRequest === controller) detailLoading.value = false }
}
function clearTaskSelection() { taskDetailRequest?.abort(); taskDetailRequest = null; detailLoading.value = false; activeTask.value = null; activeApplication.value = null }
/** 财务变更同时影响详情、待办摘要金额及领取状态，必须刷新同一工作区。 */
function expenseTaskChanged() {
  const task = activeTask.value
  if (!task) return
  void refreshWorkspace()
  void selectTask(task)
}
async function performAction(input: TaskActionInput) {
  if (!activeTask.value || busy.value || writesBlocked.value || expenseTaskBusy.value) return
  const scope = actorScope.value, taskId = activeTask.value.taskId
  let showResult = false
  busy.value = true
  try {
    const result = await api.taskAction(taskId, input)
    if (actorScope.value !== scope || activeTask.value?.taskId !== taskId) return
    activeTask.value = null; activeApplication.value = null
    await refreshWorkspace()
    if (actorScope.value !== scope || activeTask.value) return
    notice.value = `${taskActionLabels[input.action]}已完成，申请状态：${statusLabel(result.applicationStatus)}`
    showResult = true
  } catch (error) {
    if (actorScope.value === scope && activeTask.value?.taskId === taskId) { notice.value = errorMessage(error); showResult = true }
  } finally {
    busy.value = false
    await nextTick()
    if (showResult && actorScope.value === scope && page.value === 'workbench'
      && (!activeTask.value || activeTask.value.taskId === taskId)) operationStatus.value?.focus({ preventScroll: true })
  }
}
/** 人员变更后重读原任务版本，不能沿旧名单继续批准或自动重提。 */
async function performMembershipChange(input: CountersignInput, view: CountersignView) {
  const task = activeTask.value, scope = actorScope.value
  if (!task || busy.value || writesBlocked.value || expenseTaskBusy.value || task.taskId !== view.taskId || task.version !== input.expectedVersion) return
  busy.value = true
  let changed = false
  try {
    const result = await api.changeCountersignMembers(task.taskId, input)
    if (scope !== actorScope.value) return
    changed = true
    clearTaskSelection()
    await refreshWorkspace()
    notice.value = `${input.action === 'ADD' ? '已增加会签人' : '已移除未决任务'} ${result.targetUser}，当前必要审批人数 ${result.totalAfter}，已有 ${result.completed} 人同意。`
  } catch (cause) { if (scope === actorScope.value) notice.value = errorMessage(cause) }
  finally { busy.value = false }
  // 详情读取遵守已有 busy 保护，必须等写入阶段结束，旧表单已在成功时清除。
  if (changed && scope === actorScope.value) await selectTask(task)
}
/** 标记已读只更新消息；结果不确定时由原请求恢复入口处理。 */
async function readNotification(message: InboxMessage) {
  if (busy.value || writesBlocked.value) return
  busy.value = true
  try { await api.readNotification(message.id); templateRefresh.value++; notice.value = '消息已标为已读。' }
  catch (error) { notice.value = errorMessage(error) }
  finally { busy.value = false }
}
/** 已在消息详情核验的原付款只定位既有工作区，打开时业务接口仍复核当前权限。 */
function openPaymentNotification(target: FinancialNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  if (target.view === 'CASHIER_PAYMENT') {
    if (!canCashier.value) { notice.value = '当前账号没有出纳工作区权限，请重新登录后核对。'; return }
    if ('executionRequestId' in target && !target.canOpenCashier) { notice.value = '原出纳选择已停止，请保留原记录核对。'; return }
    notificationPaymentId.value = target.paymentId; cashierKind.value = 'executionRequestId' in target ? 'supplier' : 'employee'; page.value = 'cashier'
  } else { recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo }
}
/** 原凭证详情通过权限后，仍沿原轮次打开业务申请。 */
function openVoucherNotification(target: VoucherNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 原预算消息打开固定申请轮次，业务页面仍重新核验当前权限。 */
function openBudgetNotification(target: BudgetNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 原冲销详情仅定位原申请轮次，办理仍由业务入口重新授权。 */
function openReversalNotification(target: ReversalNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
function openReversalCheckNotification(target: ReversalCheckNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
function openExpenseSettlementNotification(target: ExpenseSettlementNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 固定原采购申请轮次，办理能力由原页面再次核验。 */
function openSupplierSettlementNotification(target: SupplierSettlementNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 消息固定原应付调整轮次，不复用通知作为财务办理授权。 */
function openSupplierAdjustmentNotification(target: SupplierAdjustmentNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 回款消息回到原采购轮次，当前办理资格由原入口核对。 */
function openSupplierReturnNotification(target: SupplierReturnNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 退回消息回到原报销轮次，当前办理资格由原入口核对。 */
function openExpenseReturnNotification(target: ExpenseReturnNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 退回消息回到原借款轮次，当前办理资格由原入口核对。 */
function openDisbursementReturnNotification(target: DisbursementReturnNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 还款复核通知固定原申请轮次，后续办理重新核验。 */
function openRepaymentReviewNotification(target: RepaymentReviewNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 应付消息返回原采购轮次，原财务入口再次校验字段与任职权限。 */
function openSupplierPayableNotification(target: SupplierPayableNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
function openBudgetAdjustmentNotification(target: BudgetAdjustmentNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 报销调整通知返回原申请轮次，办理仍需独立授权。 */
function openExpenseAdjustmentNotification(target: ExpenseAdjustmentNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
function openExpensePartialAdjustmentNotification(target: ExpensePartialAdjustmentNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 还款通知回到原申请轮次，财务操作重新校验。 */
function openRepaymentNotification(target: RepaymentNotificationTarget) {
  if (busy.value || writesBlocked.value) return
  recordApplicationId.value = target.applicationId; recordInitialRoundNo.value = target.roundNo
}
/** 旧消息按单项任务实时复核；已结束或转交的任务回到申请权限查询。 */
async function openNotification(message: InboxMessage) {
  if (busy.value || writesBlocked.value) return
  if (message.kind === 'APPLICATION_COPIED') { selectedCopy.value = { applicationId: message.applicationId, roundNo: message.roundNo }; return }
  if (!isTaskNotification(message)) { recordApplicationId.value = message.applicationId; return }
  const scope = actorScope.value, controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 12_000)
  busy.value = true
  let task: Task | undefined
  try {
    const current = await api.task(message.taskId!, controller.signal)
    if (actorScope.value !== scope) return
    if (current.applicationId === message.applicationId) task = current
  } catch (error) {
    if (actorScope.value !== scope) return
    if (![403, 404].includes((error as ApiError).status)) { notice.value = controller.signal.aborted ? '读取待办超时，请重试。' : errorMessage(error); return }
  } finally { clearTimeout(timeout); busy.value = false }
  if (actorScope.value !== scope) return
  if (task) { page.value = 'workbench'; await selectTask(task) }
  else { recordApplicationId.value = message.applicationId; notice.value = '该待办已发生变化，请核对申请当前状态。' }
}
async function openSimulation() {
  simulationOpen.value = !simulationOpen.value
  await nextTick()
  if (simulationOpen.value) document.getElementById('simulation-title')?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
async function openComparison() {
  comparisonOpen.value = !comparisonOpen.value
  await nextTick()
  if (comparisonOpen.value) document.getElementById('comparison-title')?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
async function locateComparisonChange(change: ComparisonChange) {
  if (change.area !== 'FIELD') { await locateDesignTarget(change.targetId); return }
  const index = definitionFormSchema.value?.fields.findIndex(field => field.key === change.targetId) ?? -1
  if (index < 0) return
  document.querySelector<HTMLElement>(`[aria-label="字段 ${index + 1} 名称"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
async function locateDesignTarget(id: string) {
  const node = nodes.value.find(item => item.id === id)
  if (node) selectNode(node)
  else { const edge = edges.value.find(item => item.id === id); if (!edge) return; selectEdge(edge) }
  await nextTick()
  const nodeId = node?.id ?? edges.value.find(item => item.id === id)?.source
  const target = designerMode.value === 'quick'
    ? Array.from(document.querySelectorAll<HTMLElement>('[data-quick-node]')).find(item => item.dataset.quickNode === nodeId)
    : Array.from(canvas.value?.querySelectorAll<HTMLElement>('[data-node-id]') ?? []).find(item => item.dataset.nodeId === nodeId)
  target?.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' })
}
async function upgradeConditions() {
  if (editorLocked.value || !canManageDefinitions.value || conditionLanguageVersion.value !== 1) return
  const scope = draftScope.value, before = snapshot(), controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 12_000)
  busy.value = true
  try {
    const upgraded = await api.upgradeConditions(graphPayload(), controller.signal)
    if (!viewActive || scope !== draftScope.value || before !== snapshot()) return
    remember(); conditionLanguageVersion.value = upgraded.conditionLanguageVersion ?? 1
    definitionRiskPolicy.value = copyRiskPolicy(upgraded.riskPolicy)
    edges.value = upgraded.edges.map(edge => ({ ...edge }))
    notice.value = '已启用组合条件，原条件含义保留。可撤销此次更改。'
  } catch (error) { if (viewActive && scope === draftScope.value) notice.value = controller.signal.aborted ? '条件升级超时，请重试。' : errorMessage(error) }
  finally { clearTimeout(timeout); busy.value = false }
}

/** 关闭或编辑立即取消旧校验，不能保留过期的通过结论。 */
function clearValidation(close = false) {
  clearTimeout(validationTimer); validation.clear()
  if (close) validationOpened.value = false
}
async function validateGraph() {
  clearTimeout(validationTimer); validationOpened.value = true
  const result = await validation.run(graphPayload(), definitionFormSchema.value, definitionKey.value.trim())
  return !!result && result.errors.length === 0
}
async function validate() { if (!busy.value) await validateGraph() }
/** 只确认本次发送的快照；新输入、选中项、焦点和撤销栈均留在编辑器。 */
function acknowledgeDraft(definition: Definition, checkpoint: DraftCheckpoint) {
  if (!viewActive || checkpoint.scope !== draftScope.value) return
  rememberGuideSelection(actorScope.value, definition.id)
  definitionId.value = definition.id
  definitionKey.value = definition.key
  definitionRevision.value = definition.revision; definitionVersion.value = definition.version; definitionStatus.value = definition.status
  savedSnapshot.value = checkpoint.snapshot
  templateRefresh.value++
  localStorage.setItem(`agentflow.definition.${actor.value!.tenantId}`, definition.id)
}
async function persistDraft(): Promise<Definition> {
  if (readonlyDefinition.value) throw new Error('已发布定义只读，请先复制为新草稿')
  if (!definitionKey.value.trim() || !definitionName.value.trim()) throw new Error('请填写流程标识和名称')
  definitionKey.value = definitionKey.value.trim()
  const checkpoint: DraftCheckpoint = { scope: draftScope.value, snapshot: snapshot(), path: definitionId.value ? `/process-definitions/${encodeURIComponent(definitionId.value)}` : '/process-definitions' }
  pendingDraftCheckpoint = checkpoint
  try {
    const definition = definitionId.value
      ? await api.updateDefinition(definitionId.value, { name: definitionName.value.trim(), graph: graphPayload(), formSchema: definitionFormSchema.value, notificationTexts: definitionNotificationTexts.value, expectedRevision: definitionRevision.value })
      : await api.definition({ key: definitionKey.value.trim(), name: definitionName.value.trim(), graph: graphPayload(), formSchema: definitionFormSchema.value, notificationTexts: definitionNotificationTexts.value })
    acknowledgeDraft(definition, checkpoint)
    if (pendingDraftCheckpoint === checkpoint) pendingDraftCheckpoint = null
    return definition
  } catch (error) {
    if (!pendingWrites.value.some(operation => operation.path === checkpoint.path) && pendingDraftCheckpoint === checkpoint) pendingDraftCheckpoint = null
    throw error
  }
}
async function saveDraft() {
  if (busy.value || writesBlocked.value || draftConflict.value) return; busy.value = true
  try { await persistDraft(); autosave.saved(); notice.value = '流程草稿已保存' }
  catch (error) { autosave.pause(error); notice.value = errorMessage(error) } finally { busy.value = false }
}
/** 冲突不自动合并或覆盖；明确放弃本地修改后重新读取服务器最新版本。 */
async function reloadConflictingDraft() {
  const id = definitionId.value, scope = draftScope.value
  if (!id) return
  await confirmReplaceDefinition('放弃本地修改并加载', async () => {
    busy.value = true
    try { const definition = await api.getDefinition(id); if (draftScope.value === scope) { applyDefinition(definition); notice.value = '已加载服务端最新版本。' } }
    catch (error) { notice.value = errorMessage(error) } finally { busy.value = false }
  })
}
function openPublication() {
  if (editorLocked.value || draftConflict.value || !canManageDefinitions.value) return
  publicationReturnFocus.value = document.activeElement as HTMLElement | null
  publicationError.value = ''; publicationOpen.value = true
  void validateGraph()
}
async function publishDraft() {
  if (!publicationOpen.value || !canManageDefinitions.value || busy.value || writesBlocked.value || readonlyDefinition.value) return
  const changeNote = publicationNote.value.trim()
  if (!changeNote || changeNote.length > 2000) { publicationError.value = '请填写 1 至 2000 字的发布变更说明。'; return }
  busy.value = true; publicationError.value = ''
  try {
    if (!await validateGraph()) { publicationError.value = '流程校验未通过，请返回编辑并修复下方校验问题。'; return }
    const saved = await persistDraft()
    autosave.saved()
    const published = await api.publishDefinition(saved.id, saved.revision, changeNote)
    applyDefinition(published); publicationOpen.value = false; publicationNote.value = ''
    notice.value = `流程${statusLabel(published.status)}，版本 v${published.version}，发布记录已保存。`
  } catch (error) { autosave.pause(error); publicationError.value = errorMessage(error); notice.value = publicationError.value } finally { busy.value = false }
}
function selectNode(node: FlowNode) { if (selectedId.value !== node.id) endCanvasNudge(); selectedId.value = node.id; selectedEdgeId.value = ''; connectionTarget.value = '' }
function selectEdge(edge: GraphEdge) { endCanvasNudge(); selectedEdgeId.value = edge.id; selectedId.value = ''; canvas.value?.focus({ preventScroll: true }) }
function moveNode(event: PointerEvent, node: FlowNode) {
  if (editorLocked.value || !canManageDefinitions.value || event.button !== 0) return
  cancelCanvasInteraction(); selectNode(node)
  const viewport = canvas.value
  if (!viewport) return
  const target = event.currentTarget as HTMLElement
  const start = { x: node.x, y: node.y }, pointer = { x: event.clientX, y: event.clientY }
  const scroll = { x: viewport.scrollLeft, y: viewport.scrollTop }, zoom = canvasZoom.value
  let changed = false
  target.setPointerCapture(event.pointerId)
  const move = (next: PointerEvent) => {
    if (next.pointerId !== event.pointerId || editorLocked.value) return
    const bounds = viewport.getBoundingClientRect()
    if (next.clientX > bounds.right - 24) viewport.scrollLeft += 14
    else if (next.clientX < bounds.left + 24) viewport.scrollLeft -= 14
    if (next.clientY > bounds.bottom - 24) viewport.scrollTop += 14
    else if (next.clientY < bounds.top + 24) viewport.scrollTop -= 14
    const position = draggedPosition(start, { x: next.clientX - pointer.x, y: next.clientY - pointer.y },
      { x: viewport.scrollLeft - scroll.x, y: viewport.scrollTop - scroll.y }, zoom)
    if (position.x === node.x && position.y === node.y) return
    if (!changed) { remember(); changed = true; dragging.value = node.id }
    node.x = position.x; node.y = position.y
  }
  const up = (next: PointerEvent) => { if (next.pointerId === event.pointerId) stopNodeDrag?.() }
  stopNodeDrag = () => {
    dragging.value = null; window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up)
    window.removeEventListener('pointercancel', up); target.removeEventListener('lostpointercapture', up)
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId)
    stopNodeDrag = null
  }
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', up)
  window.addEventListener('pointercancel', up); target.addEventListener('lostpointercapture', up)
}
/** 面板点击与拖放共用草稿编辑，只有点击插入才重接原后继。 */
function addNode(type: NodeType, position?: Point) {
  if (editorLocked.value || !canManageDefinitions.value) return
  cancelCanvasInteraction()
  const base = selectedNode.value, id = `${type.toLowerCase()}-${crypto.randomUUID()}`
  const added = { id, name: palette.find(item => item.type === type)?.label ?? '节点', type,
    x: position?.x ?? (base?.x ?? 160) + 180, y: position?.y ?? (base?.y ?? 140), assigneeRule: '' }
  if (!position && base && ['EXCLUSIVE_GATEWAY', 'PARALLEL_GATEWAY'].includes(base.type)) {
    added.y += edges.value.filter(edge => edge.source === base.id).length * 90
  }
  const result = insertCanvasGraph(nodes.value, edges.value, base?.id ?? '', added, `edge-${crypto.randomUUID()}`, !!position)
  remember(); nodes.value = result.nodes; edges.value = result.edges
  selectedId.value = id; selectedEdgeId.value = ''
}
/** 快速模式只提交同一图的编辑结果，不持有另一份流程或绕过发布校验。 */
function editQuick(command: QuickCommand) {
  if (editorLocked.value || !canManageDefinitions.value) return
  try {
    const result = editQuickGraph(quickGraph.value, command)
    const previous = new Map(nodes.value.map(node => [node.id, node]))
    remember()
    nodes.value = loadDesignerNodes(result.nodes).map(node => {
      const old = previous.get(node.id)
      return old ? { ...node, x: old.x, y: old.y, loadedPosition: old.loadedPosition } : node
    })
    edges.value = result.edges
    const added = nodes.value.find(node => !previous.has(node.id))
    selectedEdgeId.value = !added && edges.value.some(edge => edge.id === selectedEdgeId.value) ? selectedEdgeId.value : ''
    selectedId.value = selectedEdgeId.value ? '' : added?.id
      ?? nodes.value.find(node => node.id === selectedId.value)?.id ?? nodes.value[0]?.id ?? ''
    connectionTarget.value = ''
    notice.value = command.kind === 'insert' || command.kind === 'addBranch'
      ? '步骤已添加，请配置节点规则；完成后保存并校验。' : '流程已更新，可通过撤销恢复。'
  } catch (error) { notice.value = errorMessage(error) }
}
/** 服务引用、契约摘要与输入整体更新，两种视图及撤销共用同一快照。 */
function patchServiceTask(id: string, value: ServiceTaskBinding) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type === 'SERVICE_TASK') node.serviceTask = { ...value, inputs: { ...value.inputs } }
}
/** 子引用与输入整体更新。 */
function patchSubprocess(id: string, value: SubprocessBinding) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type === 'SUB_PROCESS') node.subprocess = { ...value, inputs: { ...value.inputs } }
}
/** 两个事件引用字段一起更新，清除引用不会残留旧版本。 */
function patchEventContract(id: string, value: EventBinding) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type !== 'EVENT_WAIT') return
  node.eventContractKey = value.key; node.eventContractVersion = value.version
}
function patchQuickNode(id: string, patch: Partial<GraphNode>) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (!node) return
  if (patch.name !== undefined) node.name = patch.name
  if (patch.properties) {
    node.originalProperties = { ...node.originalProperties, ...patch.properties }
    node.assigneeRule = patch.properties.assigneeRule ?? node.assigneeRule
    node.recipientRule = patch.properties.recipientRule ?? node.recipientRule
    node.timerDelaySeconds = patch.properties.timerDelaySeconds ?? node.timerDelaySeconds
    node.approvalMode = patch.properties.approvalMode ?? node.approvalMode
  }
}
/** 方式与比例作为一次编辑，切换方式不会遗留上一种策略的比例。 */
function patchApprovalPolicy(id: string, mode: string, percentage: string | undefined) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type !== 'USER_TASK') return
  node.approvalMode = mode
  node.approvalPercentage = percentage
}
/** 两种设计视图共用原属性快照，明确清除规则时不影响其他节点配置。 */
function patchResponsibilities(id: string, value: ApprovalResponsibilities) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type === 'USER_TASK') node.originalProperties = writeResponsibilities(node.originalProperties ?? {}, value)
}
/** 财务职责写入已有节点属性，清除后恢复普通业务审批，撤销保留完整配置。 */
function patchExpenseStage(id: string, value: string | undefined) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type !== 'USER_TASK') return
  const properties = { ...node.originalProperties }
  if (value === undefined) delete properties.expenseStage
  else properties.expenseStage = value
  node.originalProperties = properties
}
/** 开始节点保存整轮费用策略，两种视图与撤销复用同一份属性。 */
function patchExpenseSelfApproval(id: string, value: string | undefined) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type !== 'START') return
  const properties = { ...node.originalProperties }
  if (value === undefined) delete properties.expenseSelfApproval
  else properties.expenseSelfApproval = value
  node.originalProperties = properties
}
/** 自动通过策略独立保存，依赖失效时保留原配置交由发布校验明确拒绝。 */
function patchExpenseDuplicateApproval(id: string, value: string | undefined) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type !== 'START') return
  const properties = { ...node.originalProperties }
  if (value === undefined) delete properties.expenseDuplicateApproval
  else properties.expenseDuplicateApproval = value
  node.originalProperties = properties
}
/** 开始节点规则统一写入原属性，未编辑的网关保持不变。 */
function patchExpenseSplitRule(id: string, value: SplitRuleFields) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type === 'START') node.originalProperties = writeSplitRule(node.originalProperties ?? {}, value)
}
/** 网关只切换金额依据；已有错误位置的标记也可以明确清除。 */
function patchExpenseSplitGateway(id: string, value: string | undefined) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node && (node.type === 'EXCLUSIVE_GATEWAY' || value === undefined)) node.originalProperties = writeSplitGateway(node.originalProperties ?? {}, value)
}
/** 两种设计视图共用期限输入，清除时保留节点其他配置。 */
function patchQuickDeadline(id: string, deadline: DesignerDeadline | undefined) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const node = nodes.value.find(node => node.id === id)
  if (node?.type === 'USER_TASK') node.deadline = deadline ? { ...deadline } : undefined
}
function patchQuickEdge(id: string, condition: string) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const edge = edges.value.find(edge => edge.id === id)
  if (edge && !edge.defaultBranch) edge.condition = condition
}
/** 条件汇合只保留一条无条件出线，与需要判断条件的拆分分开显示。 */
function isExclusiveMerge(id: string | undefined) {
  return nodes.value.find(node => node.id === id)?.type === 'EXCLUSIVE_GATEWAY'
    && edges.value.filter(edge => edge.source === id).length === 1
    && edges.value.filter(edge => edge.target === id).length > 1
}
/** 显式清除误配规则，避免导入或画布改线后遗留无效条件。 */
function clearMergeEdge() {
  if (editorLocked.value || !canManageDefinitions.value || !selectedEdge.value) return
  remember(); selectedEdge.value.condition = ''; selectedEdge.value.defaultBranch = false
}
/** 属性和指针连线统一进入此处，一次成功只保存一份撤销快照。 */
function connectCanvasNodes(source: string, target: string) {
  if (editorLocked.value || !canManageDefinitions.value) return false
  const result = connectCanvasGraph(nodes.value, edges.value, source, target, `edge-${crypto.randomUUID()}`)
  if (!result) return false
  endCanvasNudge(); remember(); edges.value = result; connectionTarget.value = ''
  return true
}
function connectNode() {
  if (selectedNode.value) connectCanvasNodes(selectedNode.value.id, connectionTarget.value)
}
function toggleDefault(edge: GraphEdge) {
  if (editorLocked.value || !canManageDefinitions.value) return
  remember(); const enable = !edge.defaultBranch
  edges.value.filter(item => item.source === edge.source).forEach(item => { item.defaultBranch = enable && item.id === edge.id })
  if (enable) edge.condition = ''
  const branches = edges.value.filter(item => item.source === edge.source)
  edges.value = orderCanvasBranches(edges.value, edge.source, [...branches.filter(item => !item.defaultBranch), ...branches.filter(item => item.defaultBranch)])
}
/** 默认分支仅作兜底，其余分支可明确调整求值先后。 */
function moveCanvasBranch(id: string, direction: -1 | 1) {
  if (editorLocked.value || !canManageDefinitions.value) return
  const edge = edges.value.find(item => item.id === id)
  if (!edge || edge.defaultBranch) return
  const branches = edges.value.filter(item => item.source === edge.source && !item.defaultBranch)
  const index = branches.findIndex(item => item.id === id), other = index + direction
  if (other < 0 || other >= branches.length) return
  ;[branches[index], branches[other]] = [branches[other]!, branches[index]!]
  remember(); edges.value = orderCanvasBranches(edges.value, edge.source, [...branches, ...edges.value.filter(item => item.source === edge.source && item.defaultBranch)])
}
function deleteSelected() {
  if (editorLocked.value || !canManageDefinitions.value) return
  cancelCanvasInteraction()
  if (selectedNode.value && selectedNode.value.type !== 'START') {
    const result = deleteCanvasNode(nodes.value, edges.value, selectedNode.value.id)
    remember(); nodes.value = result.nodes; edges.value = result.edges; selectedId.value = ''
  } else if (selectedEdge.value) {
    remember(); edges.value = edges.value.filter(edge => edge.id !== selectedEdgeId.value); selectedEdgeId.value = ''
  }
}
/** 鼠标与触屏共用指针捕获；取消、失焦或草稿切换均不提交半条连线。 */
function beginConnection(event: PointerEvent, node: FlowNode) {
  if (editorLocked.value || !canManageDefinitions.value || event.button !== 0 || node.type === 'END') return
  cancelCanvasInteraction()
  const viewport = canvas.value
  if (!viewport) return
  selectNode(node)
  const target = event.currentTarget as HTMLElement, scope = definitionId.value, graph = nodes.value
  const rect = nodeRectangle(node), x = rect.x + rect.width, y = rect.y + rect.height / 2
  connectionPreview.value = { sourceId: node.id, x1: x, y1: y, x2: x, y2: y }
  const current = () => !editorLocked.value && canManageDefinitions.value && nodes.value === graph && definitionId.value === scope && page.value === 'designer' && designerMode.value === 'advanced'
  const move = (next: PointerEvent) => {
    if (next.pointerId !== event.pointerId) return
    if (!current()) { stopConnectionDrag?.(); return }
    const bounds = viewport.getBoundingClientRect()
    if (next.clientX > bounds.right - 24) viewport.scrollLeft += 14
    else if (next.clientX < bounds.left + 24) viewport.scrollLeft -= 14
    if (next.clientY > bounds.bottom - 24) viewport.scrollTop += 14
    else if (next.clientY < bounds.top + 24) viewport.scrollTop -= 14
    connectionPreview.value = { sourceId: node.id, x1: x, y1: y,
      x2: (next.clientX - bounds.left + viewport.scrollLeft) / canvasZoom.value,
      y2: (next.clientY - bounds.top + viewport.scrollTop) / canvasZoom.value }
  }
  const up = (next: PointerEvent) => {
    if (next.pointerId !== event.pointerId) return
    const candidate = document.elementFromPoint(next.clientX, next.clientY)?.closest<HTMLElement>('.flow-node[data-node-id]')
    const id = candidate && viewport.contains(candidate) ? candidate.dataset.nodeId : undefined
    const allowed = current()
    stopConnectionDrag?.()
    if (allowed && id) connectCanvasNodes(node.id, id)
  }
  const cancel = (next: PointerEvent) => { if (next.pointerId === event.pointerId) stopConnectionDrag?.() }
  const blur = () => stopConnectionDrag?.()
  stopConnectionDrag = () => {
    connectionPreview.value = null; window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up)
    window.removeEventListener('blur', blur)
    window.removeEventListener('pointercancel', cancel); target.removeEventListener('lostpointercapture', cancel)
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId)
    stopConnectionDrag = null
  }
  target.setPointerCapture(event.pointerId)
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', up)
  window.addEventListener('blur', blur)
  window.addEventListener('pointercancel', cancel); target.addEventListener('lostpointercapture', cancel)
}
/** 松键或焦点改变结束连续微调，不生成额外撤销记录。 */
function endCanvasNudge() { canvasNudge = null }
function cancelCanvasInteraction() { stopNodeDrag?.(); stopConnectionDrag?.(); endCanvasNudge() }
function onDrop(event: DragEvent) {
  const type = event.dataTransfer?.getData('node-type') as NodeType
  const viewport = canvas.value
  if (!viewport || !palette.some(item => item.type === type)) return
  const bounds = viewport.getBoundingClientRect()
  const position = draggedPosition({ x: 0, y: 0 }, { x: event.clientX - bounds.left, y: event.clientY - bounds.top },
    { x: viewport.scrollLeft, y: viewport.scrollTop }, canvasZoom.value)
  addNode(type, position)
}
async function changeCanvasZoom(delta: number) {
  const viewport = canvas.value
  if (!viewport) return
  cancelCanvasInteraction()
  const next = clampZoom(canvasZoom.value + delta)
  const position = zoomedScroll({ width: viewport.clientWidth, height: viewport.clientHeight, left: viewport.scrollLeft, top: viewport.scrollTop }, canvasZoom.value, next)
  canvasZoom.value = next
  await nextTick()
  viewport.scrollTo(position.x, position.y)
  canvasMessage.value = '缩放只改变视图，自动布局可撤销。'
}
async function fitCanvas(quiet = false) {
  await nextTick()
  const viewport = canvas.value
  if (!viewport) return
  cancelCanvasInteraction()
  const fit = fittedViewport(canvasBounds.value, viewport.clientWidth, viewport.clientHeight)
  canvasZoom.value = fit.zoom
  await nextTick()
  viewport.scrollTo(fit.left, fit.top)
  if (!quiet) canvasMessage.value = fit.clipped ? '已缩小至 50%，流程较大，可继续滚动画布查看。' : '已适应画布，节点坐标保持不变。'
}
async function autoLayout() {
  if (editorLocked.value || !canManageDefinitions.value) return
  try {
    cancelCanvasInteraction()
    const result = arrangeNodes(nodes.value, edges.value, branchDescription)
    if (result.nodes.some((node, index) => node.x !== nodes.value[index]?.x || node.y !== nodes.value[index]?.y)) { remember(); nodes.value = result.nodes }
    await fitCanvas(true)
    canvasMessage.value = result.hasCycle ? '已整理布局；草稿仍有回环，发布前需要修复。' : '已自动布局，可撤销；审批规则和分支顺序保持不变。'
  } catch (error) { canvasMessage.value = errorMessage(error) }
}
function keyHandler(event: KeyboardEvent) {
  if (event.key === 'Escape') { cancelCanvasInteraction(); return }
  const target = event.target as HTMLElement
  if (page.value !== 'designer' || editorLocked.value || !canManageDefinitions.value || event.isComposing
    || target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
  if (event.metaKey || event.ctrlKey) {
    if (event.key.toLowerCase() === 'z' || (!event.metaKey && event.key.toLowerCase() === 'y')) {
      event.preventDefault(); cancelCanvasInteraction()
      event.key.toLowerCase() === 'y' || event.shiftKey ? redo() : undo()
    }
    return
  }
  if (designerMode.value !== 'advanced' || !canvas.value?.contains(target) || event.altKey) return
  if (['Delete', 'Backspace'].includes(event.key)) { event.preventDefault(); deleteSelected(); return }
  const direction: Record<string, Point> = { ArrowLeft: { x: -1, y: 0 }, ArrowRight: { x: 1, y: 0 }, ArrowUp: { x: 0, y: -1 }, ArrowDown: { x: 0, y: 1 } }
  const delta = direction[event.key], node = selectedNode.value
  if (!delta || !node) return
  event.preventDefault()
  const step = event.shiftKey ? CANVAS_LARGE_KEY_STEP : CANVAS_KEY_STEP
  const position = { x: Math.max(12, node.x + delta.x * step), y: Math.max(20, node.y + delta.y * step) }
  if (node.x === position.x && node.y === position.y) return
  if (!canvasNudge || !event.repeat || canvasNudge.nodeId !== node.id || canvasNudge.key !== event.key || canvasNudge.shift !== event.shiftKey) {
    remember(); canvasNudge = { nodeId: node.id, key: event.key, shift: event.shiftKey }
  }
  node.x = position.x; node.y = position.y
}
/** 表单只依赖当前选中的完整发布配置；切换选择立即取消旧读取。 */
async function selectApplicationDefinition(id: string) {
  requestedApplicationDefinition.value = id
  initiatorAppointmentId.value = ''
  applicationPayload.value = {}; applicationAmount.value = ''; applicationDescription.value = ''; applicationFieldErrors.value = {}; applicationFormError.value = ''
  await applicationSelection.load(actorScope.value, id, { publishedOnly: true, startEnabledOnly: true })
}
async function prepareApplication(id = '') {
  if (busy.value || writesBlocked.value) { notice.value = '请先恢复上次操作，再发起新申请。'; return }
  createdApplication.value = null; applicationTitle.value = ''; applicationBusinessNo.value = `APP-${Date.now()}`
  newApplicationOpen.value = true
  await selectApplicationDefinition(id)
}
async function openApplicationForm() { await prepareApplication() }
/** 引导始终读取指定版本，目标失效时不能静默切换到另一流程。 */
async function startGuidedApplication(id: string) { await prepareApplication(id) }
async function createAndSubmitApplication(submit = true) {
  if (busy.value || writesBlocked.value || applicationSelection.loading) return
  applicationFormError.value = ''; applicationFieldErrors.value = {}
  if (submit && (applicationFormError.value = applicationRequirements.submissionError(initiatorAppointmentId.value))) return
  const definition = applicationSelection.definition
  if ((!definition && !createdApplication.value) || !applicationTitle.value.trim() || !applicationBusinessNo.value.trim()) { applicationFormError.value = '请选择已发布流程，并填写申请标题和业务单号。'; return }
  let payload: Record<string, unknown>
  if (createdApplication.value) payload = createdApplication.value.payload
  else if (applicationFormSchema.value) payload = applicationPayload.value
  else {
    if ((submit && !applicationAmount.value) || (applicationAmount.value !== '' && (!Number.isFinite(Number(applicationAmount.value)) || Number(applicationAmount.value) < 0))) { applicationFormError.value = '请输入不小于零的申请金额。'; return }
    payload = { ...(applicationAmount.value !== '' ? { amount: Number(applicationAmount.value) } : {}), description: applicationDescription.value.trim() }
  }
  applicationFieldErrors.value = validatePayload(applicationFormSchema.value, payload, submit)
  if (Object.keys(applicationFieldErrors.value).length) { applicationFormError.value = '请按字段提示修改后再操作。'; return }
  busy.value = true
  try {
    if (!createdApplication.value) createdApplication.value = await api.createApplication({ businessNo: applicationBusinessNo.value.trim(), processKey: definition!.key, definitionVersion: definition!.version, title: applicationTitle.value.trim(), payload })
    if (!submit) {
      const saved = createdApplication.value
      newApplicationOpen.value = false; createdApplication.value = null; page.value = 'drafts'; templateRefresh.value++; await refreshWorkspace(); notice.value = `草稿 ${saved.businessNo} 已保存，可在我的草稿中继续填写。`; if (saved.formSchema?.fields.some(field => field.type === 'ATTACHMENT' || field.columns?.some(column => column.type === 'ATTACHMENT'))) recordApplicationId.value = saved.id; return
    }
    const submitted = await api.submitApplication(createdApplication.value.id, createdApplication.value.version, initiatorAppointmentId.value)
    newApplicationOpen.value = false; createdApplication.value = null; page.value = 'started'; templateRefresh.value++; await refreshWorkspace(); notice.value = `申请 ${submitted.businessNo} 已提交，状态：${statusLabel(submitted.status)}`
  } catch (error) {
    applicationFieldErrors.value = (error as ApiError).details?.fieldErrors ?? {}
    applicationFormError.value = `${errorMessage(error)}${createdApplication.value ? '；草稿已保留，可在申请记录中补充填写或重试提交。' : ''}`
  } finally { busy.value = false }
}
/** 新申请读取点选版本；创建后的重试只读取已保存申请的原绑定。 */
function loadApplicationRequirements() {
  const source = createdApplication.value, definition = applicationSelection.definition
  void applicationRequirements.load(newApplicationOpen.value ? actorScope.value : '', source
    ? { kind: 'application', id: source.id, processKey: source.processKey, definitionVersion: source.definitionVersion }
    : definition ? { kind: 'definition', id: definition.id, processKey: definition.key, definitionVersion: definition.version } : null)
}
watch([actorScope, newApplicationOpen, () => createdApplication.value?.id, () => applicationSelection.definition?.id], loadApplicationRequirements, { flush: 'sync' })
onUnmounted(() => applicationRequirements.clear())
watch(newApplicationOpen, open => { if (!open) applicationSelection.clear() }, { flush: 'sync' })
watch(actorScope, () => { restoredDefinition.clear(); applicationSelection.clear(); newApplicationOpen.value = false }, { flush: 'sync' })

watch([actorScope, page], () => { catalogOpen.value = false }, { flush: 'sync' })
watch(actor, () => { editorSession.value++; autosave.reset(); pendingDraftCheckpoint = null; confirmation.cancel(); publicationOpen.value = false; publicationNote.value = ''; publicationError.value = '' }, { flush: 'sync' })
watch([actor, editorLocked, canManageDefinitions, editorSession, designerMode, page], () => cancelCanvasInteraction(), { flush: 'sync' })
watch(editorSession, () => { canvasMessage.value = '缩放只改变视图，自动布局可撤销。'; void fitCanvas(true) })
watch(canvas, (element, _previous, onCleanup) => {
  if (!element) return
  const resize = () => { canvasSize.value = { width: element.clientWidth, height: element.clientHeight } }
  const observer = new ResizeObserver(resize)
  observer.observe(element); resize(); void fitCanvas(true)
  onCleanup(() => observer.disconnect())
})
watch([definitionId, definitionKey], () => { if (!publicationOpen.value) publicationNote.value = '' }, { flush: 'sync' })

watch([() => snapshot(), draftScope, page, loggedIn, canManageDefinitions, autosaveEnabled, readonlyDefinition,
  busy, writesBlocked, confirmationOpen, publicationOpen, logoutOpen, dragging, composing, savedSnapshot], () => autosave.observe())

watch([nodes, edges, definitionKey, definitionName, definitionFormSchema, conditionLanguageVersion, definitionRiskPolicy], () => {
  clearValidation()
  if (validationOpened.value && page.value === 'designer' && loggedIn.value) {
    validationTimer = setTimeout(() => { void validateGraph() }, 450)
  }
}, { deep: true, flush: 'sync' })
watch([draftScope, page], () => clearValidation(true), { flush: 'sync' })

/** 恢复结果始终更新原资源；恢复成功后由用户决定是否继续提交或发布。 */
async function recoverOperation(id: string) {
  const pending = pendingWrites.value.find(operation => operation.id === id)
  if (!pending) return
  const checkpoint = pendingDraftCheckpoint?.scope === draftScope.value && pendingDraftCheckpoint.path === pending.path ? pendingDraftCheckpoint : null
  const replacesDefinition = pending.path.startsWith('/process-definitions') || pending.path.startsWith('/process-templates/')
  await confirmReplaceDefinition('放弃修改并恢复', async () => {
    busy.value = true; recoveryError.value = ''
    try {
      const { request, result } = await writeRequests.recover(id)
      if (request.path.startsWith('/process-templates/') && request.path.endsWith('/copy')) {
        applyDefinition(result as Definition); page.value = 'designer'; templateRefresh.value++
        notice.value = '已确认原模板复制结果，已打开原草稿，请核对后再发布。'
      } else if (checkpoint && request.path === checkpoint.path && checkpoint.scope === draftScope.value) {
        acknowledgeDraft(result as Definition, checkpoint); pendingDraftCheckpoint = null
        if (dirty.value) autosave.pause({ code: 'RECOVERED_WITH_LOCAL_CHANGES', message: '原草稿已确认保存，后续修改仍保留。请点击保存草稿后继续自动保存。' })
        else autosave.saved()
        notice.value = '已确认原草稿保存结果，保留发送后的本地修改。'
      } else if (request.path.startsWith('/process-definitions')) {
        applyDefinition(result as Definition); page.value = 'designer'; templateRefresh.value++
        notice.value = request.path.endsWith('/availability') ? '已确认原版本治理操作，请刷新版本状态核对当前结果。'
          : request.path.includes('/publish?') ? '已确认原流程的发布结果。' : '已确认原流程草稿的保存结果，请核对后再发布。'
      } else if (request.path.startsWith('/event-contracts/') || request.path.startsWith('/integrations/events/')) {
        templateRefresh.value++
        notice.value = '已确认原事件操作，请刷新原记录核对当前版本和处理状态。'
      } else if (request.path.startsWith('/integrations/payment/callbacks/')) {
        templateRefresh.value++
        notice.value = '已确认原支付回调恢复请求，请刷新回调处理状态。'
      } else if (request.path.startsWith('/integrations/webhooks/deliveries/')) {
        templateRefresh.value++
        notice.value = '已确认原投递重试请求，请刷新投递状态查看发送结果。'
      } else if (request.path === '/notifications/preferences') {
        templateRefresh.value++
        notice.value = '已确认原通知偏好保存结果，请重新读取当前设置。'
      } else if (/^\/notifications\/deliveries\/[^/]+\/retry$/.test(request.path)) {
        templateRefresh.value++
        notice.value = '已确认原通知重试请求，请重新读取当前投递状态。'
      } else if (request.path.startsWith('/notifications/') && request.path.endsWith('/read')) {
        templateRefresh.value++
        notice.value = '已确认消息的已读状态。'
      } else if (/^\/applications\/[^/]+\/comments$/.test(request.path)) {
        const value = result as ApplicationComment
        if (request.body) commentDrafts.acknowledge(actorScope.value, value.applicationId, JSON.parse(request.body) as CommentDraft)
        commentRefresh.value++
        notice.value = '已确认原评论追加成功，审批状态未改变。'
      } else if (request.path.startsWith('/admin/account-mappings/')) {
        if (request.body) mappingDrafts.acknowledge(actorScope.value, request.path, request.body)
        templateRefresh.value++
        notice.value = '已确认原科目配置操作。请在科目映射页面核对版本和历史。'
      } else if (request.path === '/admin/expense-categories' || request.path.startsWith('/admin/expense-policies/')) {
        if (request.body) configurationDrafts.acknowledge(actorScope.value, request.path, request.body)
        templateRefresh.value++
        notice.value = '已确认原费用配置操作。请在费用制度页面重新核对当前版本和历史记录。'
      } else if (request.path === '/system/initialization') {
        if (request.body) initializationDrafts.acknowledge(actorScope.value, request.body)
        templateRefresh.value++
        notice.value = '已确认原工作区初始化结果，请在开始使用页面核对记录；当前设置会重新读取。'
      } else if (request.path === '/organization/approval-proxies' || /^\/organization\/approval-proxies\/[^/?]+\/revoke$/.test(request.path)) {
        approvalProxyDrafts.acknowledge(actorScope.value, request.path, request.body!, result as ApprovalProxyReceipt)
        templateRefresh.value++
        notice.value = '已确认原代理操作，请在审批代理页面按原编号核对当前状态。'
      } else if (request.path.startsWith(syncPath + '/')) {
        organizationSyncDrafts.acknowledge(actorScope.value, request.path, request.body!, result as SyncReceipt | SyncPlanReceipt)
        templateRefresh.value++
        notice.value = '已确认原组织同步操作，请回到外部同步核对原批次和计划；后续应用仍需明确操作。'
      } else if (request.path.startsWith('/organization')) {
        if (request.body) organizationDrafts.acknowledge(actorScope.value, request.path, request.body, result as OrganizationRecord)
        templateRefresh.value++
        notice.value = '已确认原组织保存结果，请核对目录。'
      } else if (request.path.startsWith('/business-calendars')) {
        if (request.body) calendarDrafts.acknowledge(actorScope.value, request.path, request.body, result as BusinessCalendar)
        templateRefresh.value++
        notice.value = '已确认原日历保存结果，旧版本保持不变。'
      } else if (/^\/applications\/[^/?]+\/signatures(?:\/[^/?]+\/cancel)?$/.test(request.path)) {
        const applicationId = decodeURIComponent(request.path.split('/')[2]!)
        rememberSignatureOperation(actorScope.value, applicationId, (result as { id: string }).id, (JSON.parse(request.body!) as { roundNo?: number }).roundNo)
        if (recordApplicationId.value === applicationId) recordRefresh.value++
        notice.value = '原签署操作已确认，请打开电子签页核对原记录和文件保存进度。'
      } else if (/^\/applications\/[^/?]+\/draft-assist-runs(?:\/[^/?]+\/review)?$/.test(request.path)) {
        const applicationId = decodeURIComponent(request.path.split('/')[2]!)
        rememberDraftRun(actorScope.value, applicationId, (result as DraftAssistReceipt).id)
        if (recordApplicationId.value === applicationId) recordRefresh.value++
        notice.value = '原草稿建议操作已确认，请核对这条建议和申请的最新内容；尚未自动提交审批。'
      } else if (/^\/applications\/[^/]+\/assist-runs(?:\/[^/]+\/review)?$/.test(request.path)) {
        assistRefresh.value++
        notice.value = '原摘要操作已确认，请刷新记录核对执行或复核结果。'
      } else if (/^\/invoices\/[^/?]+\/extraction-runs(?:\/[^/?]+\/review)?$/.test(request.path)) {
        acknowledgeExtraction(actorScope.value, request.path, request.body!, result as ExtractionReceipt)
        templateRefresh.value++
        notice.value = '原票面提取或复核操作已确认，请打开原票据核对这条记录；查验与财务状态保持不变。'
      } else if (/^\/invoices\/[^/]+\/verifications$/.test(request.path)) {
        templateRefresh.value++
        notice.value = '原验票任务已确认受理，请打开原票据并刷新查验状态。'
      } else if (request.path === '/payment-batches') {
        templateRefresh.value++
        notice.value = '原批次登记已确认，请查看已登记批次并逐笔核对付款结果。'
      } else if (request.path.startsWith('/cashier/payments/') || request.path.startsWith('/cashier/supplier-payments/') || request.path.startsWith('/payments/') || /\/applications\/[^/]+\/payments\/authorizations$/.test(request.path)) {
        notice.value = '原付款操作已确认，请刷新付款详情，核对最新授权与银行状态。'
      } else if (request.path.startsWith('/procurement-payments')) {
        if (request.body && (request.path === '/procurement-payments' || request.path.endsWith('/revise'))) {
          const value = result as ProcurementReceipt
          const restored = procurementDrafts.acknowledge(actorScope.value, request.path, request.body, value)
          if (!restored && value.applicationId) recordApplicationId.value = value.applicationId
          notice.value = '原采购付款保存结果已确认，请读取已保存内容后继续预检。'
        } else notice.value = '原采购付款操作已确认，请刷新采购付款详情或预检结果核对当前状态。'
        templateRefresh.value++
      } else if (request.path.startsWith('/budget-adjustments')) {
        if (request.body && (request.path === '/budget-adjustments' || request.path.endsWith('/revise'))) {
          const value = result as BudgetAdjustmentReceipt
          const restored = budgetAdjustmentDrafts.acknowledge(actorScope.value, request.path, request.body, value)
          if (!restored && value.applicationId) recordApplicationId.value = value.applicationId
          notice.value = '原预算调整保存结果已确认，请读取已保存内容后继续预检。'
        } else notice.value = '原预算调整操作已确认，请刷新预算调整详情或预检结果核对当前状态。'
        templateRefresh.value++
      } else if (request.path.startsWith('/advance-requests')) {
        if (request.body && (request.path === '/advance-requests' || request.path.endsWith('/revise'))) {
          const value = result as AdvanceReceipt
          const restored = advanceDrafts.acknowledge(actorScope.value, request.path, request.body, value)
          if (!restored && value.applicationId) recordApplicationId.value = value.applicationId
          notice.value = '原借款保存结果已确认，请读取已保存内容后继续预检。'
        } else notice.value = '原借款操作已确认，请刷新借款详情或预检结果核对当前状态。'
        templateRefresh.value++
      } else if (request.path.startsWith('/expense-plans')) {
        if (request.body && (request.path === '/expense-plans' || request.path.endsWith('/revise'))) {
          const value = result as PlanDetailData
          const restored = planDrafts.acknowledge(actorScope.value, request.path, request.body, value)
          if (!restored && value.applicationId) recordApplicationId.value = value.applicationId
          notice.value = '原计划保存结果已确认，请核对原单据后继续预检。'
        } else notice.value = '原计划操作已确认，请刷新计划详情或预检结果核对当前状态。'
        templateRefresh.value++
      } else if (/^\/expense-requests\/[^/?]+\/close$/.test(request.path)) {
        notice.value = '原额度关闭结果已确认，请核对最新额度状态。'
        templateRefresh.value++
      } else if (/^\/expense-reports\/[^/?]+\/draft-assists(?:\/[^/?]+\/(?:confirm|dismiss))?$/.test(request.path)) {
        acknowledgeExpenseAssist(actorScope.value, request.path, result as ExpenseAssistReceipt)
        notice.value = '原填报建议操作已确认，请查看同一条记录，再明确选择填入草稿。'
      } else if (/^\/expense-reports\/[^/?]+\/precheck-explanations(?:\/[^/?]+\/review)?$/.test(request.path)) {
        acknowledgeExplanation(actorScope.value, request.path, result as ExplanationReceipt)
        notice.value = '原预检解释操作已确认，请核对同一条记录；费用金额、检查结论和审批状态保持不变。'
      } else if (/^\/expense-reports\/[^/?]+\/risk-explanations(?:\/[^/?]+\/review)?$/.test(request.path)) {
        acknowledgeRisk(actorScope.value, request.path, request.body!, result as RiskReceipt)
        notice.value = '原风险复核操作已确认，请核对同一条记录。'
      } else if (request.path.startsWith('/expense-reports')) {
        if (request.body && (request.path === '/expense-reports' || request.path.endsWith('/revise'))) {
          const value = result as ExpenseDetailData
          const restored = expenseDrafts.acknowledge(actorScope.value, request.path, request.body, value)
          if (!restored && value.applicationId) recordApplicationId.value = value.applicationId
          notice.value = '原费用保存结果已确认，请核对原单据后继续预检。'
        } else notice.value = '原费用操作已确认，请刷新费用详情或预检结果核对当前状态。'
        templateRefresh.value++
      } else if (/^\/applications\/[^/?]+\//.test(request.path) && !/^\/applications\/[^/?]+\/(submit|withdraw|cancel)$/.test(request.path)) {
        // 子资源回执不具备完整申请字段；统一按原请求目标重读，完整申请仅来自明确的聚合操作。
        const applicationId = decodeURIComponent(request.path.split('/')[2]!)
        if (recordApplicationId.value === applicationId) recordRefresh.value++
        if (activeTask.value?.applicationId === applicationId) clearTaskSelection()
        notice.value = '已确认本轮原操作，请查看申请的最新状态。'
      } else if (request.path.startsWith('/applications')) {
        templateRefresh.value++
        const value = result as Application
        if (request.path === '/applications') {
          createdApplication.value = value; applicationTitle.value = value.title; applicationBusinessNo.value = value.businessNo
          applicationPayload.value = { ...value.payload }; applicationFieldErrors.value = {}; applicationFormError.value = ''
          applicationAmount.value = String(value.payload.amount ?? ''); applicationDescription.value = String(value.payload.description ?? '')
          applicationSelection.clear(); requestedApplicationDefinition.value = ''
          newApplicationOpen.value = true
          notice.value = '原申请草稿已确认，请核对后再提交。'
        } else {
          if (createdApplication.value?.id === value.id) { createdApplication.value = null; newApplicationOpen.value = false }
          if (recordApplicationId.value === value.id) recordRefresh.value++
          notice.value = `已确认申请 ${value.businessNo} 的原操作，状态：${statusLabel(value.status)}。`
        }
      } else {
        const value = result as { taskId: string; applicationStatus: string }
        if (activeTask.value?.taskId === value.taskId) { activeTask.value = null; activeApplication.value = null }
        notice.value = `已确认原审批任务的处理结果，申请状态：${statusLabel(value.applicationStatus)}。`
      }
      await refreshWorkspace()
    } catch (error) {
      if (checkpoint?.scope === draftScope.value) {
        autosave.pause(error)
        if (!pendingWrites.value.some(operation => operation.id === id)) pendingDraftCheckpoint = null
      }
      recoveryError.value = errorMessage(error); notice.value = recoveryError.value
    }
    finally {
      busy.value = false
      await nextTick()
      const dialog = document.querySelector<HTMLElement>('[role="dialog"]')
      const recovery = (dialog ?? workspace.value)?.querySelector<HTMLElement>('.request-recovery button:not(:disabled)')
      ;(recovery ?? dialog ?? workspace.value)?.focus()
    }
  }, () => !busy.value && pendingWrites.value.some(operation => operation.id === id && !operation.sending),
  replacesDefinition && !checkpoint && !readonlyDefinition.value && dirty.value)
}
function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (providerNavigation) return
  if (writeRequests.hasUnconfirmed() || configurationDrafts.hasDrafts() || mappingDrafts.hasDrafts() || extractionDrafts.hasDrafts() || initializationDrafts.hasDrafts() || commentDrafts.hasDrafts() || calendarDrafts.hasDrafts() || organizationDrafts.hasDrafts() || organizationSyncDrafts.hasDrafts() || approvalProxyDrafts.hasDrafts() || expenseDrafts.hasDrafts() || planDrafts.hasDrafts() || advanceDrafts.hasDrafts() || procurementDrafts.hasDrafts() || budgetAdjustmentDrafts.hasDrafts() || invoiceUploads.hasPending() || (!readonlyDefinition.value && (dirty.value || publicationNote.value.trim()))) { event.preventDefault(); event.returnValue = '' }
}
defaultGraph(); savedSnapshot.value = snapshot()
/** 企业身份仅从服务端会话恢复，前端不读取或保存 OIDC 令牌。 */
async function loadAuthentication(restore = true) {
  authLoading.value = true
  try {
    authOptions.value = await api.authOptions()
    if (authOptions.value.mode !== 'DEMO') localStorage.removeItem('agentflow.token')
    if (!restore || (authOptions.value.mode !== 'OIDC' && !localStorage.getItem('agentflow.token'))) return
    const result = await api.me()
    actor.value = result.actor; bindAuthenticationActor(result.actor)
    username.value = result.actor.userId; tenantId.value = result.actor.tenantId; loggedIn.value = true
    page.value = canInspectSystem.value && !guideHidden(actorScope.value) ? 'guide' : 'workbench'
    await refreshWorkspace(true)
  } catch (error) {
    if ((error as ApiError).status === 401) localStorage.removeItem('agentflow.token')
    else notice.value = errorMessage(error)
  } finally { authLoading.value = false }
}
function enterpriseLogin() {
  if (authOptions.value?.mode === 'OIDC' && !busy.value) window.location.assign(authOptions.value.loginUrl!)
}
function authenticationRequired() { if (loggedIn.value) sessionExpired.value = true }
function reopenEnterpriseLogin() {
  if (authOptions.value?.mode === 'OIDC') window.open(authOptions.value.loginUrl!, '_blank', 'noopener,noreferrer')
}
async function restoreEnterpriseSession() {
  if (pendingWrites.value.some(operation => operation.sending)) { notice.value = '请等待当前请求结束后恢复会话。'; return false }
  try {
    authOptions.value = await api.authOptions()
    const result = await api.me()
    if (actor.value?.tenantId !== result.actor.tenantId || actor.value?.userId !== result.actor.userId) {
      sessionExpired.value = true
      notice.value = '当前企业账号与本页不同，请在登录窗口恢复原账号后重试；原页面和未确认操作已保留。'
      return false
    }
    actor.value = result.actor; bindAuthenticationActor(result.actor); sessionExpired.value = false
    notice.value = '会话已恢复，可以继续核对并恢复原操作。'
    return true
  } catch (error) { sessionExpired.value = true; notice.value = errorMessage(error); return false }
}
onMounted(async () => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  window.addEventListener('agentflow:authentication-required', authenticationRequired)
  const url = new URL(window.location.href)
  if (url.searchParams.get('authError') === 'oidc') {
    notice.value = '企业登录未完成，可能是登录已过期或账号尚未分配平台权限。请重试或联系管理员。'
    url.searchParams.delete('authError'); window.history.replaceState(null, '', url)
  }
  await loadAuthentication()
})
onBeforeUnmount(() => { clearValidation(true); taskCountRequest?.abort(); taskDetailRequest?.abort(); viewActive = false; autosave.dispose(); cancelCanvasInteraction(); confirmation.dispose() })
onUnmounted(() => { restoredDefinition.clear(); applicationSelection.clear(); unsubscribeWrites(); window.removeEventListener('beforeunload', warnBeforeUnload); window.removeEventListener('agentflow:authentication-required', authenticationRequired) })
</script>

<template>
  <div class="app" :inert="confirmationOpen || publicationOpen || logoutOpen" @keydown="keyHandler">
    <section v-if="!loggedIn" class="login-screen">
      <form class="login-card" @submit.prevent="login">
        <div class="brand-mark">AF</div><p class="eyebrow">AGENTFLOW / WORKFLOW OS</p>
        <h1>让每一次审批<br /><em>都有依据。</em></h1><p class="login-copy">面向 OA、财务与业务团队的智能审批工作台。</p>
        <p v-if="authLoading" role="status">正在读取登录配置…</p>
        <template v-else-if="authOptions?.mode === 'DEMO'">
          <label>租户空间<input v-model="tenantId" autocomplete="organization" placeholder="demo" /></label>
          <label>用户名<input v-model="username" autocomplete="username" placeholder="admin" /></label>
          <label>密码<input v-model="password" autocomplete="current-password" type="password" placeholder="请输入密码" /></label>
          <button class="primary wide" :disabled="busy">{{ busy ? '正在登录…' : '进入工作台 ↗' }}</button>
          <small>演示租户 demo；账号 admin / manager / finance / cashier / alice，密码 demo</small>
        </template>
        <template v-else-if="authOptions?.mode === 'OIDC'">
          <p class="login-copy">使用企业账号登录，租户空间和权限由管理员分配。</p>
          <button type="button" class="primary wide" :disabled="busy" @click="enterpriseLogin">使用企业账号登录 ↗</button>
        </template>
        <template v-else>
          <p role="status">{{ authOptions?.mode === 'UNCONFIGURED' ? '尚未配置登录服务，请联系管理员。' : '无法读取登录配置，请检查服务连接后重试。' }}</p>
          <button type="button" class="primary wide" @click="loadAuthentication()">重新检查</button>
        </template>
        <small v-if="notice" role="status">{{ notice }}</small>
      </form>
    </section>
    <template v-else>
      <main ref="workspace" class="main" tabindex="-1">
        <header><div class="header-location"><WorkspaceNavigation v-model:page="page" :tenant-id="tenantId" :username="username" :can-inspect="canInspectSystem" :can-manage="canManageDefinitions" :can-cashier="canCashier" :can-configure-finance="canConfigureFinance" :can-read-financial-reports="canReadFinancialReports" :task-count="taskCount" :server-available="serverAvailable" :logout-disabled="busy || pendingWrites.some(operation => operation.sending)" @logout="requestLogout" /><div class="crumb">当前空间 <strong>/</strong> {{ page === 'expense-reports' ? '费用财务报表' : page === 'account-mappings' ? '科目映射' : page === 'expense-configuration' ? '费用制度' : page === 'webhooks' ? '集成投递' : page === 'audit' ? '操作审计' : page === 'transfer' ? '模板文件' : page === 'guide' ? '开始使用' : page === 'examples' ? '示例数据' : page === 'operations' ? '审批运营' : page === 'api' ? '接口文档' : page === 'notifications' ? '消息中心' : page === 'started' ? '我发起' : page === 'drafts' ? '我的草稿' : page === 'handled' ? '已办记录' : page === 'organization' ? '组织与人员' : page === 'proxies' ? '审批代理' : page === 'calendars' ? '工作日历' : page === 'system' ? '系统自检' : page === 'designer' ? '流程管理' : page === 'templates' ? '模板中心' : page === 'assist' ? 'Agent 助理' : page === 'cashier' ? '出纳付款' : page === 'expense' ? '财务申请' : page === 'applications' ? '申请记录' : '审批工作台' }}</div></div><div class="header-actions"><button class="quiet" :disabled="busy" @click="refreshPage">刷新数据</button><div class="avatar">{{ username.slice(0, 1).toUpperCase() }}</div><span class="user-name">{{ username }}</span></div></header>
        <div v-if="sessionExpired" class="session-notice" role="alert"><div><strong>需要恢复企业会话</strong><p>会话需要恢复。请在新窗口登录原账号，再恢复当前会话；本页的草稿和未确认操作会保留。</p><p v-if="notice">{{ notice }}</p></div><button class="secondary" @click="reopenEnterpriseLogin">重新登录</button><button class="secondary" :disabled="busy" @click="restoreEnterpriseSession">恢复当前会话</button></div>
        <div v-if="notice && !sessionExpired" ref="operationStatus" class="toast" role="status" tabindex="-1">{{ notice }}<button aria-label="关闭提示" @click="notice = ''">×</button></div>
        <div v-if="!newApplicationOpen && !recordApplicationId" class="recovery-container"><RequestRecovery :pending="visiblePendingWrites" :error="recoveryError" @recover="recoverOperation" /></div>
        <section v-if="page === 'workbench'" class="content">
          <div class="page-heading"><div><p class="eyebrow">{{ today }}</p><h2>今天，先处理重要的事。</h2><p class="subhead">当前有 <strong>{{ taskCount ?? '—' }}</strong> 项可处理的审批任务。</p></div><button class="primary" @click="openApplicationForm">＋ 发起申请</button></div>

          <div class="work-grid" :class="{ 'board-work-grid': taskQueueView === 'board' }">
            <PendingTaskQueue ref="taskQueuePanel" v-model:view="taskQueueView" :scope-key="actorScope" :refresh-version="taskRefresh" :locked="busy || writesBlocked" :selected-id="activeTask?.taskId" @select="selectTask" @clear-selection="clearTaskSelection" @changed="refreshWorkspace" />
            <div ref="taskDetailPanel" class="detail panel" tabindex="-1" aria-label="当前待办详情">
              <div v-if="taskQueueView === 'board'" class="board-return"><button type="button" class="quiet" @click="taskQueuePanel?.focusTask(activeTask?.taskId)">↑ 返回待办看板</button></div>
              <div v-if="activeTask" class="detail-body">
                <div class="detail-top"><div><span class="status-chip">● {{ activeApplication ? statusLabel(activeApplication.status) : '待处理' }}</span><h3>{{ activeApplication?.title ?? activeTask.taskName }}</h3><p>{{ activeApplication?.businessNo ?? activeTask.taskName }} · 任务创建于 {{ dateLabel(activeTask.createdAt) }}</p></div></div>
                <WorkspaceTabs :id-base="`task-${activeTask.taskId}`" label="待办详情" variant="task" :disabled="expenseTaskBusy" :model-value="taskTab" :tabs="[{ key: 'detail', label: '申请详情' }, { key: 'compare', label: '内容对比' }, { key: 'timeline', label: '时间线' }, { key: 'audit', label: '审计记录' }, { key: 'comments', label: '协作评论' }, { key: 'assist', label: 'Agent 摘要' }]" @update:model-value="taskTab = $event as typeof taskTab">
                <div v-if="taskTab === 'detail'" class="detail-content">
                  <p v-if="detailError" class="inline-error">{{ detailError }}</p>
                  <template v-else-if="activeApplication"><div class="facts"><div><small>申请人</small><strong>{{ activeApplication.createdBy }}</strong></div><div><small>流程版本</small><strong>{{ activeApplication.processKey }} / v{{ activeApplication.definitionVersion }}</strong></div><div><small>当前任务</small><strong>{{ activeTask.taskName }}</strong></div><div><small>审批轮次</small><strong>第 {{ activeApplication.roundNo }} 轮</strong></div></div><ExpenseDetail v-if="activeApplication.businessReference?.type === 'EXPENSE'" :key="actorScope + ':' + activeTask.taskId + ':' + activeApplication.id + ':' + activeApplication.version" @task-activity="expenseTaskActivity" :report-id="activeApplication.businessReference.id" :application-id="activeApplication.id" :scope-key="actorScope" :version="activeApplication.version" :task-id="activeTask.taskId" :locked="busy || writesBlocked" @changed="expenseTaskChanged"><template #restricted><FormFields :schema="activeApplication.formSchema" :model-value="activeApplication.payload" :attachment-context="{ applicationId: activeApplication.id, scopeKey: actorScope }" readonly /></template></ExpenseDetail><ExpensePlanDetail v-else-if="activeApplication.businessReference?.type === 'EXPENSE_PLAN'" :plan-id="activeApplication.businessReference.id" :application-id="activeApplication.id" :scope-key="actorScope" :version="activeApplication.version" :locked="busy || writesBlocked" @changed="expenseTaskChanged"><template #restricted><FormFields :schema="activeApplication.formSchema" :model-value="activeApplication.payload" :attachment-context="{ applicationId: activeApplication.id, scopeKey: actorScope }" readonly /></template></ExpensePlanDetail><AdvanceRequestDetail v-else-if="activeApplication.businessReference?.type === 'ADVANCE_REQUEST'" :request-id="activeApplication.businessReference.id" :application-id="activeApplication.id" :scope-key="actorScope" :version="activeApplication.version" :locked="busy || writesBlocked" @changed="expenseTaskChanged"><template #restricted><FormFields :schema="activeApplication.formSchema" :model-value="activeApplication.payload" :attachment-context="{ applicationId: activeApplication.id, scopeKey: actorScope }" readonly /></template></AdvanceRequestDetail><ProcurementPaymentDetail v-else-if="activeApplication.businessReference?.type === 'PROCUREMENT_PAYMENT'" :request-id="activeApplication.businessReference.id" :application-id="activeApplication.id" :scope-key="actorScope" :version="activeApplication.version" :locked="busy || writesBlocked" @changed="expenseTaskChanged"><template #restricted><FormFields :schema="activeApplication.formSchema" :model-value="activeApplication.payload" :attachment-context="{ applicationId: activeApplication.id, scopeKey: actorScope }" readonly /></template></ProcurementPaymentDetail><BudgetAdjustmentDetail v-else-if="activeApplication.businessReference?.type === 'BUDGET_ADJUSTMENT'" :request-id="activeApplication.businessReference.id" :application-id="activeApplication.id" :scope-key="actorScope" :version="activeApplication.version" :locked="busy || writesBlocked" @changed="expenseTaskChanged"><template #restricted><FormFields :schema="activeApplication.formSchema" :model-value="activeApplication.payload" :attachment-context="{ applicationId: activeApplication.id, scopeKey: actorScope }" readonly /></template></BudgetAdjustmentDetail><FormFields v-else :schema="activeApplication.formSchema" :model-value="activeApplication.payload" :attachment-context="{ applicationId: activeApplication.id, scopeKey: actorScope }" readonly /></template>
                  <p v-else class="unavailable">正在加载申请详情…</p>
                  <div class="agent-note"><span>✦</span><div><strong>Agent 摘要</strong><p>在「Agent 摘要」中选择本轮可读字段，生成后核对来源并保存人工修订。审批以申请内容及核实结果为依据。</p></div></div>
                </div>
                <div v-else-if="taskTab === 'compare'" class="timeline-full"><RoundComparison v-if="activeApplication" :application-id="activeApplication.id" :scope-key="actorScope" :version="activeApplication.version" /><p v-else class="unavailable">请先刷新并加载当前申请。</p></div>
                <div v-else-if="taskTab === 'timeline'" class="timeline-full"><button class="secondary" @click="recordApplicationId = activeTask.applicationId">查看提交轮次与历史内容</button><ApplicationHistory :application-id="activeTask.applicationId" mode="timeline" :round-no-max="activeApplication?.roundNo ?? 1" :version="activeTask.version" /></div>
                <div v-else-if="taskTab === 'audit'" class="audit-list"><ApplicationHistory :application-id="activeTask.applicationId" mode="audit" :round-no-max="activeApplication?.roundNo ?? 1" :version="activeTask.version" /></div>
                <div v-else-if="taskTab === 'assist'" class="timeline-full"><AssistRunRecords v-if="activeApplication" :application-id="activeApplication.id" :scope-key="actorScope" :version="activeApplication.version" :round-no="activeApplication.roundNo" :task-id="activeTask.taskId" :locked="busy || writesBlocked" :refresh-version="assistRefresh" /><p v-else class="unavailable">请先刷新并加载当前申请。</p></div>
                <ApplicationComments v-else-if="activeApplication" :application-id="activeApplication.id" :scope-key="actorScope" :version="activeApplication.version" :status="activeApplication.status" :round-no="activeApplication.roundNo" :locked="busy || writesBlocked || !!detailError" :refresh-version="commentRefresh" @posted="commentRefresh++" @refresh-application="selectTask(activeTask)" />
                </WorkspaceTabs>
                <TaskDeadlineStatus :due-at="activeTask.dueAt" />
                <p v-if="expenseTaskBusy" class="inline-error" role="status">费用操作尚未结束。请先保存或取消当前表单；结果待核对时请刷新费用状态。</p>
                <TaskActions :key="actorScope + ':' + activeTask.taskId" :task="activeTask" :scope-key="actorScope" :locked="busy || writesBlocked || expenseTaskBusy || !activeApplication || !!detailError" @execute="performAction" @membership="performMembershipChange" @refresh="expenseTaskChanged" />
              </div>
              <div v-else class="empty-detail"><div class="empty-icon">◎</div><h3>{{ detailLoading ? '正在读取待办…' : '选择一项待办' }}</h3><p>{{ detailLoading ? '正在核对当前处理权限与申请版本。' : '查看真实申请内容，完成批准、退回或转交。' }}</p></div>
            </div>
          </div>
        </section>
        <section v-else-if="page === 'assist'" class="content">
          <div class="page-heading"><div><p class="eyebrow">APPROVAL ASSISTANT</p><h2>Agent 助理</h2><p class="subhead">从当前待办选择材料，生成摘要后逐条核对来源。</p></div><button class="primary" @click="page = 'workbench'; taskTab = 'assist'">打开待办摘要</button></div>
          <div class="panel queue-empty"><strong>由你决定发送哪些内容</strong><p>在待办的“Agent 摘要”中勾选可读字段，确认模型目的地后生成。敏感字段和附件不发送。服务未配置时，输入面板会显示具体原因。</p><p>生成结果可修改后采纳，也可记录未采纳意见。复核记录与原文分别保留，审批仍需单独办理。</p><button class="secondary" @click="page = 'applications'">查看申请中的历史摘要</button></div>
        </section>
        <IntegrationWorkspace v-else-if="page === 'webhooks' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" @open="recordApplicationId = $event" />
        <AuditSearch v-else-if="page === 'audit' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" @open="recordApplicationId = $event" />
        <ApplicationSearch v-else-if="page === 'applications'" :key="actorScope" :scope-key="actorScope" :administrator="canInspectSystem" :user-id="actor?.userId ?? ''" :refresh-version="templateRefresh" :locked="busy || writesBlocked" @open="recordApplicationId = $event" @create="openApplicationForm" />
        <NotificationInbox v-else-if="page === 'notifications'" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" @read="readNotification" @open="openNotification" @payment-open="openPaymentNotification" @voucher-open="openVoucherNotification" @budget-open="openBudgetNotification" @reversal-open="openReversalNotification" @reversal-check-open="openReversalCheckNotification" @settlement-open="openExpenseSettlementNotification" @supplier-adjustment-open="openSupplierAdjustmentNotification" @supplier-settlement-open="openSupplierSettlementNotification" @supplier-return-open="openSupplierReturnNotification" @expense-return-open="openExpenseReturnNotification" @disbursement-return-open="openDisbursementReturnNotification" @repayment-review-open="openRepaymentReviewNotification" @expense-partial-adjustment-open="openExpensePartialAdjustmentNotification" @expense-adjustment-open="openExpenseAdjustmentNotification" @supplier-payable-open="openSupplierPayableNotification" @budget-adjustment-open="openBudgetAdjustmentNotification" @repayment-open="openRepaymentNotification" />
        <WorkspaceRecords v-else-if="['started', 'drafts', 'handled'].includes(page)" :key="actorScope + ':' + page" :scope-key="actorScope" :mode="page === 'handled' ? 'handled' : page === 'drafts' ? 'drafts' : 'started'" :refresh-version="templateRefresh" :locked="busy || writesBlocked" @open="recordApplicationId = $event" @create="openApplicationForm" />
        <TemplateCenter v-else-if="(page === 'templates' || page === 'examples') && canManageDefinitions" :key="actorScope + ':' + page" :examples-only="page === 'examples'" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" :has-unsaved-definition="!readonlyDefinition && dirty" @copy="copyTemplate" @open="openSavedDefinition" @return-designer="page = 'designer'" @import="page = 'transfer'" />
        <ApiReference v-else-if="page === 'api'" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" />
        <PortableTemplate v-else-if="page === 'transfer' && canManageDefinitions" :key="actorScope" :current="comparisonInput" :locked="busy || writesBlocked || confirmationOpen" :scope-key="actorScope" :has-unsaved-definition="!readonlyDefinition && dirty" @import="importTemplate" @back="page = 'designer'" />
        <FirstWorkflow :enterprise-auth="authOptions?.mode === 'OIDC'" v-else-if="page === 'guide' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" @templates="page = 'templates'" @import="page = 'transfer'" @examples="page = 'examples'" @new="newDefinition()" @edit="openSavedDefinition" @apply="startGuidedApplication" @open="recordApplicationId = $event" @checks="page = 'system'" @workbench="page = 'workbench'" @organization="page = 'organization'" />
        <ExpenseFinancialReporting v-else-if="page === 'expense-reports' && canReadFinancialReports" :key="financialReportScope" :scope-key="financialReportScope" :refresh-version="templateRefresh" />
        <ApprovalOperations v-else-if="page === 'operations' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" @open="recordApplicationId = $event" />
        <AccountMappingManager v-else-if="page === 'account-mappings' && canConfigureFinance" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" />
        <ExpenseConfigurationManager v-else-if="page === 'expense-configuration' && canConfigureFinance" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" />
        <OrganizationDirectory v-else-if="page === 'organization' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" />
        <ApprovalProxyManager v-else-if="page === 'proxies' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" />
        <BusinessCalendars v-else-if="page === 'calendars' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" />
        <SystemChecks v-else-if="page === 'system' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" @templates="page = 'templates'" @import="page = 'transfer'" @designer="page = 'designer'" />
        <section v-else-if="page === 'designer'" class="designer-page" @compositionstart="composing = true" @compositionend="composing = false">
          <div class="designer-heading"><div><p class="eyebrow">PROCESS DEFINITION / {{ statusLabel(definitionStatus) }} {{ definitionVersion ? `V${definitionVersion}` : '' }}</p><h2>{{ definitionName }} <span v-if="dirty && !readonlyDefinition" class="draft-dot"></span></h2><p class="subhead">{{ readonlyDefinition ? '已发布定义只读；复制为新草稿后可继续编辑。' : !definitionId ? '尚未保存草稿。' : dirty ? '有未保存的修改；发布时会先保存当前内容。' : '当前草稿已保存。' }}</p></div></div><div class="designer-toolbar" aria-label="流程设计操作"><span class="toolbar-context">{{ readonlyDefinition ? '已发布版本' + (definitionStartEnabled === false ? ' · 已停用' : definitionStartEnabled === undefined ? ' · 发起状态待刷新' : ' · 允许新发起') : !definitionId ? '尚未保存草稿' : dirty ? '有未保存修改' : '草稿已保存' }}</span><div class="designer-actions"><button class="secondary" :disabled="editorLocked || !history.length" aria-label="撤销" @click="undo">↶</button><button class="secondary" :disabled="editorLocked || !future.length" aria-label="重做" @click="redo">↷</button><button class="secondary" :disabled="busy || writesBlocked" @click="validate">校验流程</button><button v-if="canManageDefinitions" class="secondary" :disabled="busy || writesBlocked" :aria-expanded="simulationOpen" @click="openSimulation">{{ simulationOpen ? '收起模拟' : '模拟运行' }}</button><button v-if="canManageDefinitions" class="secondary" :disabled="busy || writesBlocked" :aria-expanded="comparisonOpen" @click="openComparison">{{ comparisonOpen ? '收起比较' : '版本比较' }}</button><template v-if="canManageDefinitions"><button v-if="readonlyDefinition" class="primary" :disabled="busy || writesBlocked" @click="newDefinition(true)">复制为新草稿</button><template v-else><button class="secondary" :disabled="busy || writesBlocked || draftConflict" @click="saveDraft">保存草稿</button><button class="primary" :disabled="busy || writesBlocked || draftConflict" @click="openPublication">{{ busy ? '处理中…' : '保存并发布 ↗' }}</button></template></template></div></div>
          <section v-if="canManageDefinitions && !readonlyDefinition" class="draft-save-status" aria-label="草稿保存状态">
            <label><input v-model="autosaveEnabled" type="checkbox" :disabled="busy || writesBlocked" />自动保存</label>
            <span role="status" aria-live="polite">{{ autosaveLabel }}<time v-if="autosave.savedAt && !dirty" :datetime="autosave.savedAt.toISOString()"> · {{ autosave.savedAt.toLocaleTimeString('zh-CN') }}</time></span>
            <div v-if="autosave.failure" class="draft-save-error" role="alert">
              <strong>{{ draftConflict ? '服务端版本已变化，本地修改已保留。' : '草稿未全部确认保存。' }}</strong>
              <p>{{ draftConflict ? '自动保存已暂停。可另存本地设计，或放弃本地修改并加载最新版本。' : autosave.failure.message }}</p>
              <div v-if="draftConflict" class="draft-conflict-actions"><button type="button" class="secondary" :disabled="busy || writesBlocked" @click="newDefinition(true)">另存为新草稿</button><button type="button" class="secondary" :disabled="busy || writesBlocked" @click="reloadConflictingDraft">加载服务端版本</button></div>
              <p v-else-if="!writesBlocked">修正后点击“保存草稿”，成功后恢复自动保存。</p>
            </div>
          </section>
          <div class="definition-switcher"><button class="secondary" :disabled="busy || writesBlocked" :aria-expanded="catalogOpen" @click="catalogOpen = !catalogOpen">流程目录</button><span>{{ definitionId ? '当前：' + definitionName + (definitionVersion ? ' · v' + definitionVersion : ' · 草稿') : '当前：未保存草稿' }}</span><button v-if="canManageDefinitions" class="secondary" :disabled="busy || writesBlocked" @click="newDefinition()">＋ 新建流程</button><button v-if="canManageDefinitions" class="secondary" :disabled="busy || writesBlocked" @click="page = 'transfer'">导入 / 导出模板</button></div>
          <DefinitionCatalog v-if="catalogOpen" :manage-definitions="canManageDefinitions" :scope-key="actorScope" :current-id="definitionId" :locked="busy || writesBlocked || confirmationOpen" :refresh-version="templateRefresh" @open="openSavedDefinition" @close="catalogOpen = false" />
          <div v-if="!canManageDefinitions" class="unavailable">当前账号只能查看流程。请使用流程管理员账号编辑和发布。</div>
          <fieldset class="definition-fields" :disabled="editorLocked || !canManageDefinitions"><label>流程标识<input v-model="definitionKey" :disabled="!!definitionId || autosave.saving" placeholder="如 expense-reimbursement" /></label><label>流程名称<input v-model="definitionName" /></label></fieldset>
          <p v-if="conditionLanguageVersion === 1" class="field-help">此流程使用旧版条件。启用组合条件后，可配置枚举多选、括号与取反；现有条件会转换为等价表达式。<button v-if="!readonlyDefinition && canManageDefinitions" type="button" class="secondary" :disabled="editorLocked" @click="upgradeConditions">启用组合条件</button></p>
          <DefinitionRiskPolicy v-model="definitionRiskPolicy" :form-schema="definitionFormSchema" :language-version="conditionLanguageVersion" :locked="editorLocked || !canManageDefinitions" @before-change="remember" />
          <div class="designer-mode-switch" role="group" aria-label="设计模式"><button type="button" :aria-pressed="designerMode === 'quick'" @click="designerMode = 'quick'">快速步骤</button><button type="button" :aria-pressed="designerMode === 'advanced'" @click="designerMode = 'advanced'">高级画布</button><span>两种视图编辑同一流程，切换不会更改规则。</span></div>
          <QuickDesigner v-if="designerMode === 'quick'" :graph="quickGraph" :form-schema="definitionFormSchema" :selected-node="selectedId" :selected-edge="selectedEdgeId" :locked="editorLocked || !canManageDefinitions" :scope-key="canManageDefinitions ? draftScope : ''" :invalid-nodes="validationNodeIds" :simulated-nodes="simulationResult?.path ?? []" :simulated-edges="simulationResult?.edgeIds ?? []" @command="editQuick" @select-node="id => { const node = nodes.find(item => item.id === id); if (node) selectNode(node) }" @select-edge="id => { const edge = edges.find(item => item.id === id); if (edge) selectEdge(edge) }" @before-change="remember" @node="patchQuickNode" @responsibilities="patchResponsibilities" @policy="patchApprovalPolicy" @service-task="patchServiceTask" @event-contract="patchEventContract" @subprocess="patchSubprocess" @deadline="patchQuickDeadline" @expense-stage="patchExpenseStage" @expense-self-approval="patchExpenseSelfApproval" @expense-duplicate-approval="patchExpenseDuplicateApproval" @expense-split-rule="patchExpenseSplitRule" @expense-split-gateway="patchExpenseSplitGateway" @edge="patchQuickEdge" @default-branch="toggleDefault" @advanced="designerMode = 'advanced'" />
          <div v-else class="designer-layout">
            <aside class="palette"><h4>节点</h4><p>点击插入或拖到画布</p><button v-for="item in palette" :key="item.type" :disabled="editorLocked || !canManageDefinitions" :draggable="!editorLocked && canManageDefinitions" @dragstart="event => event.dataTransfer?.setData('node-type', item.type)" @click="addNode(item.type)"><span>{{ item.icon }}</span>{{ item.label }}<b>＋</b></button><div class="palette-tip"><strong>设计器提示</strong><p>拖动端口连接下一节点，也可在右侧选择。画布内方向键移动，Shift 加大步长，Delete 删除，Esc 取消连线。</p><p>支持指定账号或角色审批。发布前会检查当前身份源中是否有可审批人员。</p></div></aside>
            <div class="canvas-wrap">
              <div class="canvas-toolbar"><span class="canvas-title" :title="definitionName">{{ definitionName }}</span><div class="canvas-tools" aria-label="画布视图操作">
                <button type="button" aria-label="缩小画布" title="缩小画布" :disabled="canvasZoom <= MIN_ZOOM" @click="changeCanvasZoom(-ZOOM_STEP)">−</button>
                <output aria-label="画布缩放比例">{{ Math.round(canvasZoom * 100) }}%</output>
                <button type="button" aria-label="放大画布" title="放大画布" :disabled="canvasZoom >= MAX_ZOOM" @click="changeCanvasZoom(ZOOM_STEP)">＋</button>
                <button type="button" @click="fitCanvas()">适应画布</button>
                <button v-if="canManageDefinitions" type="button" :disabled="editorLocked || !nodes.length" @click="autoLayout">自动布局</button>
              </div></div>
              <div ref="canvas" class="canvas" tabindex="0" aria-label="流程画布，可滚动查看节点与连线" @keyup="endCanvasNudge" @focusout="endCanvasNudge" @dragover.prevent @drop.prevent="onDrop">
                <div class="canvas-sizer" :style="{ width: `${stageSize.width * canvasZoom}px`, height: `${stageSize.height * canvasZoom}px` }">
                  <div class="canvas-stage" :style="{ width: `${stageSize.width}px`, height: `${stageSize.height}px`, transform: `scale(${canvasZoom})` }">
                    <svg class="edges" :viewBox="`0 0 ${stageSize.width} ${stageSize.height}`">
                      <defs><marker id="flow-arrow" markerWidth="7" markerHeight="7" refX="7" refY="3.5" orient="auto"><polygon points="0 0, 7 3.5, 0 7" fill="context-stroke" /></marker></defs>
                      <path v-if="connectionPreview" class="connection-preview" :d="`M${connectionPreview.x1},${connectionPreview.y1} L${connectionPreview.x2},${connectionPreview.y2}`" />
                      <g v-for="route in routedEdges" :key="route.edge.id">
                        <path class="edge-hit" :d="route.path" @click.stop="selectEdge(route.edge)" />
                        <path :d="route.path" :data-edge-id="route.edge.id" marker-end="url(#flow-arrow)" :class="{ selected: selectedEdgeId === route.edge.id, simulated: simulationResult?.edgeIds.includes(route.edge.id) }" @click.stop="selectEdge(route.edge)" />
                        <text v-if="route.text" :x="route.label.x" :y="route.label.y" class="edge-label" @click.stop="selectEdge(route.edge)">{{ route.text }}<title>{{ branchTitle(route.edge) }}</title></text>
                      </g>
                    </svg>
                    <button v-for="node in nodes" :key="node.id" class="flow-node" :data-node-id="node.id" :class="[node.type === 'EXCLUSIVE_GATEWAY' ? 'condition' : node.type.toLowerCase(), { selected: selectedId === node.id, dragging: dragging === node.id, invalid: validationNodeIds.includes(node.id), simulated: simulationResult?.path.includes(node.id) }]" :style="{ left: `${node.x}px`, top: `${node.y}px` }" @pointerdown="event => canManageDefinitions && moveNode(event, node)" @focus="selectNode(node)" @click.stop="selectNode(node)"><span class="node-icon">{{ node.type === 'SERVICE_TASK' ? '服' : node.type === 'SUB_PROCESS' ? '子' : node.type === 'EVENT_WAIT' ? '事' : node.type === 'TIMER_WAIT' ? '时' : node.type === 'COPY' ? '抄' : node.type === 'PARALLEL_GATEWAY' ? '＋' : node.type === 'EXCLUSIVE_GATEWAY' ? '◇' : node.type === 'START' ? '▶' : node.type === 'END' ? '●' : '人' }}</span><strong>{{ node.name }}</strong><small v-if="node.type === 'USER_TASK'">{{ isCountersignMode(node.approvalMode) ? approvalPolicyLabel(node.approvalMode, node.approvalPercentage) + ' · ' : '' }}{{ assigneeLabel(node.assigneeRule) }}</small><i v-if="node.type !== 'END'" class="port" aria-hidden="true" @pointerdown.stop.prevent="event => beginConnection(event, node)" @click.stop></i></button>
                  </div>
                </div>
              </div>
              <p class="canvas-hint" role="status">{{ routedEdges.some(route => route.obstructed) ? '部分节点或连线重叠，可手动调整节点或使用自动布局。' : canvasMessage }}</p>
            </div>
            <aside class="inspector"><fieldset :disabled="editorLocked || !canManageDefinitions">
              <template v-if="selectedNode"><div class="inspector-head"><div><p class="eyebrow">NODE PROPERTY</p><h3>{{ selectedNode.name }}</h3></div></div><label>节点名称<input v-model="selectedNode.name" @focus="remember" /></label><label>节点类型<input :value="selectedNode.type" disabled /></label><DefinitionAssignee v-if="selectedNode.type === 'USER_TASK'" :key="selectedNode.id" v-model="selectedNode.assigneeRule" :form-schema="definitionFormSchema" :approval-mode="selectedNode.approvalMode" :approval-percentage="selectedNode.approvalPercentage" @policy="(mode, percentage) => patchApprovalPolicy(selectedNode!.id, mode, percentage)" :scope-key="canManageDefinitions ? draftScope : ''" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" />
                <DefinitionSubprocess v-if="selectedNode.type === 'SUB_PROCESS'" :key="selectedNode.id" :node-id="selectedNode.id" :form-schema="definitionFormSchema" :model-value="selectedNode.subprocess ?? { inputs: {} }" :scope-key="canManageDefinitions ? draftScope : ''" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="patchSubprocess(selectedNode.id, $event)" />
                <DefinitionServiceTask v-if="selectedNode.type === 'SERVICE_TASK'" :key="selectedNode.id" :node-id="selectedNode.id" :form-schema="definitionFormSchema" :model-value="selectedNode.serviceTask ?? { inputs: {} }" :scope-key="canManageDefinitions ? draftScope : ''" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="patchServiceTask(selectedNode.id, $event)" />
                <DefinitionEventWait v-if="selectedNode.type === 'EVENT_WAIT'" :key="selectedNode.id" :model-value="{ key: selectedNode.eventContractKey, version: selectedNode.eventContractVersion }" :scope-key="actorScope" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="patchEventContract(selectedNode.id, $event)" />
                <DefinitionTimerWait v-if="selectedNode.type === 'TIMER_WAIT'" :key="selectedNode.id" v-model="selectedNode.timerDelaySeconds" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" />
                <DefinitionCopyRecipient v-if="selectedNode.type === 'COPY'" :key="selectedNode.id" :model-value="selectedNode.recipientRule ?? ''" :scope-key="canManageDefinitions ? draftScope : ''" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="selectedNode.recipientRule = $event" />
                <DefinitionResponsibilities v-if="selectedNode.type === 'USER_TASK'" :key="selectedNode.id" :node-id="selectedNode.id" :graph="quickGraph" :expense-policy-enabled="nodes.some(item => item.type === 'START' && item.originalProperties?.expenseSelfApproval === 'ESCALATE_SUPERVISOR')" :model-value="readResponsibilities(selectedNode.originalProperties ?? {})" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="value => patchResponsibilities(selectedNode!.id, value)" />
              <DefinitionExpenseSelfApproval v-if="selectedNode.type === 'START'" :model-value="selectedNode.originalProperties?.expenseSelfApproval" :form-schema="definitionFormSchema" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="patchExpenseSelfApproval(selectedNode.id, $event)" />
              <DefinitionExpenseDuplicateApproval v-if="selectedNode.type === 'START'" :model-value="selectedNode.originalProperties?.expenseDuplicateApproval" :self-approval="selectedNode.originalProperties?.expenseSelfApproval" :form-schema="definitionFormSchema" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="patchExpenseDuplicateApproval(selectedNode.id, $event)" />
              <DefinitionExpenseSplitRisk :node-id="selectedNode.id" :graph="quickGraph" :form-schema="definitionFormSchema" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @rule="patchExpenseSplitRule" @gateway="patchExpenseSplitGateway" />
              <DefinitionExpenseStage v-if="selectedNode.type === 'USER_TASK'" :model-value="selectedNode.originalProperties?.expenseStage" :form-schema="definitionFormSchema" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="patchExpenseStage(selectedNode.id, $event)" />
                <DefinitionDeadline v-if="selectedNode.type === 'USER_TASK'" :key="selectedNode.id" v-model="selectedNode.deadline" :scope-key="canManageDefinitions ? draftScope : ''" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" />
                <p v-if="selectedNode.type === 'PARALLEL_GATEWAY'" class="field-help">并行拆分会同时进入所有出线，汇合等待全部入线到达。请用并行网关成对连接，可嵌套；分支条件请另加条件网关。</p><p v-if="isExclusiveMerge(selectedNode.id)" class="field-help">条件汇合：选中的路径到达后直接继续，不等待未选中的路径。</p><div v-if="selectedNode.type === 'EXCLUSIVE_GATEWAY' && !isExclusiveMerge(selectedNode.id)" class="branch-editor"><strong>分支条件</strong><p class="field-help">例如 amount &gt; 5000。每个分支网关只有一条默认分支。</p><div v-for="edge in edges.filter(item => item.source === selectedNode?.id)" :key="edge.id" class="branch-item"><small>→ {{ nodes.find(node => node.id === edge.target)?.name }}</small><p class="field-help" :title="branchTitle(edge)">{{ branchDescription(edge) || '尚未配置条件' }}</p><div class="branch-order"><button type="button" :disabled="edge.defaultBranch || edges.filter(item => item.source === edge.source && !item.defaultBranch)[0]?.id === edge.id" :aria-label="`上移分支 ${edge.id}`" @click="moveCanvasBranch(edge.id, -1)">上移</button><button type="button" :disabled="edge.defaultBranch || edges.filter(item => item.source === edge.source && !item.defaultBranch).slice(-1)[0]?.id === edge.id" :aria-label="`下移分支 ${edge.id}`" @click="moveCanvasBranch(edge.id, 1)">下移</button></div><div class="branch"><input v-model="edge.condition" :disabled="edge.defaultBranch" :aria-label="`分支条件 ${edge.id}`" :placeholder="edge.defaultBranch ? '默认分支无需条件' : '如 amount > 5000'" @focus="remember" /><button :class="{ default: edge.defaultBranch }" type="button" @click="toggleDefault(edge)">{{ edge.defaultBranch ? '取消默认' : '设为默认' }}</button></div></div></div>
                <template v-if="selectedNode.type !== 'END'"><label>连线到<select v-model="connectionTarget"><option value="">选择下一节点</option><option v-for="node in nodes.filter(item => item.id !== selectedNode?.id && item.type !== 'START')" :key="node.id" :value="node.id">{{ node.name }}</option></select></label><button class="secondary connect-button" :disabled="!connectionTarget" @click="connectNode">添加连线</button></template><button class="delete-button" :disabled="selectedNode.type === 'START'" @click="deleteSelected">删除节点</button>
              </template>
              <template v-else-if="selectedEdge"><div class="inspector-head"><div><p class="eyebrow">EDGE PROPERTY</p><h3>连线条件</h3></div></div><template v-if="nodes.find(node => node.id === selectedEdge?.source)?.type === 'PARALLEL_GATEWAY' || isExclusiveMerge(selectedEdge.source)"><p class="field-help">{{ isExclusiveMerge(selectedEdge.source) ? '条件汇合后直接继续' : '并行连线全部执行' }}，不设置条件或默认分支。</p><button v-if="selectedEdge.condition || selectedEdge.defaultBranch" class="secondary" @click="clearMergeEdge">清除条件及默认设置</button></template><ConditionEditor v-else-if="!selectedEdge.defaultBranch" :key="selectedEdge.id" :model-value="selectedEdge.condition" :language-version="conditionLanguageVersion" :form-schema="definitionFormSchema" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" @update:model-value="patchQuickEdge(selectedEdge.id, $event)" /><p v-else class="field-help">其他条件均不满足时进入默认分支。</p><button v-if="nodes.find(node => node.id === selectedEdge?.source)?.type === 'EXCLUSIVE_GATEWAY' && !isExclusiveMerge(selectedEdge.source)" class="secondary" @click="toggleDefault(selectedEdge)">{{ selectedEdge.defaultBranch ? '取消默认分支' : '设为默认分支' }}</button><button class="delete-button" @click="deleteSelected">删除连线</button></template>
              <div v-else class="inspector-empty"><span>＋</span><h3>选择节点或连线</h3><p>在这里配置流程属性。</p></div>
            </fieldset></aside>
          </div>
          <DefinitionComparison v-if="comparisonOpen && canManageDefinitions" :input="comparisonInput" :scope-key="actorScope + ':' + definitionId + ':' + definitionKey" :locked="busy || writesBlocked || confirmationOpen" @locate="locateComparisonChange" @close="comparisonOpen = false" />
          <DefinitionSimulation v-if="simulationOpen && canManageDefinitions" :graph="simulationGraph" :form-schema="definitionFormSchema" :scope-key="actorScope + ':' + definitionId + ':' + definitionKey" :locked="busy || writesBlocked || confirmationOpen" @result="simulationResult = $event" @locate="locateDesignTarget" @close="simulationOpen = false" />
          <DefinitionPublication v-if="readonlyDefinition && definitionId && canManageDefinitions" :definition-id="definitionId" :scope-key="actorScope" />
          <DefinitionNotificationTexts v-model="definitionNotificationTexts" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" />
          <DefinitionAvailability v-if="definitionStatus === 'PUBLISHED' && definitionId && canManageDefinitions" :definition-id="definitionId" :revision="definitionRevision" :start-enabled="definitionStartEnabled" :scope-key="actorScope" :locked="busy || writesBlocked || confirmationOpen" :error="availabilityError" :refresh-version="templateRefresh" @change="changeDefinitionAvailability" @refresh="refreshDefinitionAvailability" />
          <FormSchemaEditor v-model="definitionFormSchema" :scope-key="canManageDefinitions ? actorScope + ':' + definitionId + ':' + definitionKey : ''" :approval-nodes="nodes.filter(node => ['USER_TASK', 'COPY', 'SUB_PROCESS', 'SERVICE_TASK'].includes(node.type))" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" />
          <section class="designer-validation" aria-label="流程校验" :aria-busy="validation.loading">
            <div class="validation-strip" :class="{ invalid: validationErrors.length || validation.error }"><span>●</span><span role="status">{{ validationMessage }}</span><button v-if="validationOpened" type="button" class="secondary" @click="clearValidation(true)">收起校验</button></div>
            <template v-if="validationOpened"><p class="validation-live-help">修改后自动重新检查。提醒不阻止发布，分支执行顺序保持不变。</p><ul v-if="validationOtherErrors.length"><li v-for="error in validationOtherErrors" :key="error">{{ simulationIssue(error).label }}<button v-if="simulationIssue(error).target" type="button" class="secondary" @click="locateDesignTarget(simulationIssue(error).target)">定位 {{ simulationIssue(error).target }}</button></li></ul>
              <BranchDiagnostics :items="branchDiagnostics" :graph="quickGraph" :form-schema="definitionFormSchema" locatable @locate="locateDesignTarget" />
            </template>
          </section>
        </section>
        <section v-else-if="page === 'cashier' && canCashier" :key="actorScope">
          <div class="cashier-kind" role="group" aria-label="付款业务"><button type="button" class="quiet" :aria-pressed="cashierKind === 'employee'" :disabled="busy || writesBlocked" @click="cashierKind = 'employee'">借款与报销</button><button type="button" class="quiet" :aria-pressed="cashierKind === 'supplier'" :disabled="busy || writesBlocked" @click="cashierKind = 'supplier'">供应商付款</button><button type="button" class="quiet" :aria-pressed="cashierKind === 'batches'" :disabled="busy || writesBlocked" @click="cashierKind = 'batches'">批量付款</button></div>
          <CashierWorkspace v-if="cashierKind === 'employee'" :initial-payment-id="notificationPaymentId" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" />
          <PaymentBatchWorkspace v-else-if="cashierKind === 'batches'" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" />
          <SupplierCashierWorkspace v-else :initial-payment-id="notificationPaymentId" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" />
        </section>
        <ExpenseWorkspace v-else :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" @open="recordApplicationId = $event" />
      </main>
      <CopyRecord v-if="selectedCopy && actor" :key="actorScope + selectedCopy.applicationId + selectedCopy.roundNo" :application-id="selectedCopy.applicationId" :round-no="selectedCopy.roundNo" :scope-key="actorScope" @close="selectedCopy = null" />
      <ApplicationRecord v-if="recordApplicationId && actor" :key="recordApplicationId + ':' + recordRefresh" :application-id="recordApplicationId" :initial-round-no="recordInitialRoundNo" @open-related="openRelatedRound" :user-id="actor.userId" :scope-key="actorScope" :comment-refresh-version="commentRefresh" @comment-posted="commentRefresh++" :pending-writes="pendingWrites" :recovery-error="recoveryError" @recover="recoverOperation" @close="recordApplicationId = ''" @changed="applicationRecordChanged" />
      <div v-if="newApplicationOpen" class="modal-backdrop" @click.self="!busy && (newApplicationOpen = false)">
        <section class="modal" role="dialog" aria-modal="true" aria-labelledby="application-form-title" tabindex="-1">
          <div class="modal-heading"><div><p class="eyebrow">NEW APPLICATION</p><h2 id="application-form-title">发起表单审批</h2></div><button aria-label="关闭申请表单" :disabled="busy" @click="newApplicationOpen = false">×</button></div>
          <DefinitionPicker v-if="!createdApplication" :scope-key="actorScope" label="申请流程" published-only start-enabled-only :selected-id="applicationDefinitionId" :selected-label="applicationSelection.definition ? applicationSelection.definition.name + ' · v' + applicationSelection.definition.version : ''" :locked="busy || writesBlocked" @select="selectApplicationDefinition($event.id)" />
          <p v-if="applicationSelection.loading" role="status" class="unavailable">正在读取所选流程的表单配置…</p>
          <p v-else-if="applicationSelection.error" role="alert" class="inline-error">{{ applicationSelection.error }}<button type="button" class="quiet" @click="selectApplicationDefinition(requestedApplicationDefinition)">重试读取表单</button></p>
          <p v-else-if="!applicationSelection.definition && !createdApplication" class="unavailable">尚未取得可发起的发布版本，请选择流程；没有已发布流程时需先由流程管理员发布。</p>
          <p v-if="createdApplication" class="field-help">已绑定流程 {{ createdApplication.processKey }} · v{{ createdApplication.definitionVersion }}，表单取自这份申请的保存快照。</p>
          <RequestRecovery :pending="visiblePendingWrites" :error="recoveryError" @recover="recoverOperation" />
          <p v-if="applicationFormError" class="inline-error" role="alert">{{ applicationFormError }}</p>
          <form v-if="applicationSelection.definition || createdApplication" novalidate @submit.prevent="createAndSubmitApplication(true)">
            <fieldset :disabled="busy || writesBlocked || !!createdApplication">

              <label>申请标题<input v-model="applicationTitle" maxlength="200" /></label><label>业务单号<input v-model="applicationBusinessNo" /></label>
              <FormFields v-if="applicationFormSchema" v-model="applicationPayload" :schema="applicationFormSchema" :disabled="busy || writesBlocked || !!createdApplication" :errors="applicationFieldErrors" @update:model-value="applicationFieldErrors = {}" />
              <template v-else><label>申请金额<input v-model="applicationAmount" type="number" min="0" step="0.01" /></label><label>申请说明<textarea v-model="applicationDescription" rows="3" /></label></template>
            </fieldset>
            <InitiatorRequirementNotice :state="applicationRequirements" :disabled="busy || writesBlocked" @retry="loadApplicationRequirements" />
            <InitiatorAppointmentPicker v-model="initiatorAppointmentId" :scope-key="actorScope" :required="applicationRequirements.required === true" :disabled="busy || writesBlocked" />
            <p v-if="createdApplication" class="unavailable">草稿 {{ createdApplication.businessNo }} 已保留。重试只会提交这张草稿；需要修改时请关闭后从申请记录打开。</p>
            <p v-else class="field-help">必填字段在提交时检查，未填完整也可先保存草稿。</p>
            <div class="form-actions"><button type="button" class="secondary" :disabled="busy" @click="newApplicationOpen = false">关闭</button><button v-if="!createdApplication" type="button" class="secondary" :disabled="busy || writesBlocked || !applicationSelection.definition" @click="createAndSubmitApplication(false)">保存草稿</button><button class="primary" :disabled="busy || writesBlocked || (!createdApplication && !applicationSelection.definition)">{{ busy ? '处理中…' : createdApplication ? '重试提交草稿' : '创建并提交' }}</button></div>
          </form>
        </section>
      </div>
    </template>
  </div>
  <EnterpriseLogoutDialog v-if="logoutOpen" :return-focus="logoutReturnFocus" :fallback-focus="workspace" @close="logoutOpen = false" @choose="chooseLogout" />
  <UnsavedConfirmationDialog v-if="confirmation.active" :key="confirmation.active.id" :request="confirmation.active" :return-focus="confirmationReturnFocus" :fallback-focus="workspace" @answer="(id, accepted) => confirmation.answer(id, accepted)" />
  <PublicationDialog v-if="publicationOpen" :validation="validation.result" :checking="validation.loading" :validation-error="validation.error" :graph="quickGraph" :form-schema="definitionFormSchema" v-model:note="publicationNote" :name="definitionName" :process-key="definitionKey" :busy="busy" :blocked="writesBlocked" :error="publicationError" :return-focus="publicationReturnFocus" :fallback-focus="workspace" @close="publicationOpen = false" @submit="publishDraft" />
</template>

<style scoped>
.cashier-kind{display:flex;flex-wrap:wrap;gap:10px;padding:24px 40px 0}.cashier-kind button[aria-pressed="true"]{background:#e3f2eb;border-color:#3c8a74;color:#145d4c}.cashier-kind button{min-height:40px}@media(max-width:600px){.cashier-kind{padding:16px 16px 0}}

.session-notice{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin:22px 30px;padding:18px 22px;border:1px solid #dfcfac;border-radius:12px;background:#fff8e9;color:var(--ink);font-size:13px;line-height:1.7}
.session-notice>div{flex:1 1 340px;min-width:0;overflow-wrap:anywhere}.session-notice p{margin:6px 0 0}.session-notice button{flex-shrink:0}
@media(max-width:650px){.session-notice{margin:16px;padding:16px}.session-notice>div{flex-basis:100%}}

.designer-validation{margin-top:16px;min-width:0}.validation-live-help{font-size:11px;color:var(--muted);line-height:1.8}.designer-validation>ul{padding-left:22px;font-size:12px;color:var(--red)}.designer-validation>ul li{margin:8px 0}.flow-node.invalid{border:2px solid var(--red);box-shadow:0 0 0 3px #ba4d3b20}.validation-strip .secondary{margin-left:auto;font-size:11px}
.work-grid.board-work-grid{grid-template-columns:minmax(0,1fr)}
.board-return{padding:16px 22px 0}
</style>
