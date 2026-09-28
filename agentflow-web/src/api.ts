import type { OrganizationUnit, OrganizationPerson, OrganizationAppointment, OrganizationRecord, OrganizationPage, OrganizationChange } from './organization'
import type { InitiatorContext, InitiatorAppointmentPage } from './initiatorContext'
import type { NotificationTexts } from './notificationTexts'
import type { AssistRunDetail, AssistRunFilter, AssistRunPage } from './assistRuns'
import type { WebhookFilters, WebhookPage, WebhookTarget, WebhookDetail, WebhookItem, WebhookOverview, WebhookOverviewFilters } from './webhooks'
import type { RoundDiagram } from './roundDiagram'
import type { AuditSearchFilters, AuditSearchPage } from './auditSearch'
import type { ApplicationSearchFilters, ApplicationSearchPage } from './applicationSearch'
import type { DefinitionCatalogFilters, DefinitionCatalogPage } from './definitionCatalog'
import { workbookType, type ApplicationExportFilters } from './applicationExport.js'
import type { AuditExportFilters } from './auditSearch'
import type { BusinessCalendar, CalendarInput, CalendarUpdate, CalendarPage, CalendarVersionPage, CalendarCalculationInput, CalendarCalculation, CalendarSummary } from './businessCalendars'
import type { FirstWorkflowReport } from './firstWorkflow'
import type { ApplicationComment, CommentDraft, CommentPage, CommentQuery } from './applicationComments'
import type { OperationsFilter, OperationsReport } from './approvalOperations'
import type { AssigneeOption } from './definitionAssignees'
import type { ApiDocument } from './apiReference'
import { PendingWrites, type WriteRequest } from './pendingWrites.js'
import type { FieldErrors, FormSchema } from './formSchema'
import type { AttachmentInput, AttachmentMetadata, AttachmentOptions } from './attachments'
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
export interface SimulationInput { graph: Graph; formSchema: FormSchema | null; values: Record<string, unknown> }
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
export interface Graph { nodes: GraphNode[]; edges: GraphEdge[]; conditionLanguageVersion?: 1 | 2 }
export interface Definition { id: string; key: string; name: string; revision: number; version: number; status: string; graph: Graph; formSchema: FormSchema | null; startEnabled?: boolean; notificationTexts?: NotificationTexts }
/** 版本停用恢复的实际操作依据。@author owlzhangfq@gmail.com */
export interface DefinitionAvailabilityChange { tenantId: string; definitionId: string; revision: number; previousEnabled: boolean; startEnabled: boolean; changedBy: string; authorizedRole: string; changedAt: string; reason: string }
/** 按修订降序的操作记录页。@author owlzhangfq@gmail.com */
export interface DefinitionAvailabilityHistory { items: DefinitionAvailabilityChange[]; nextBeforeRevision?: number }
/** 身份由服务端确定的治理操作。@author owlzhangfq@gmail.com */
export interface DefinitionAvailabilityInput { startEnabled: boolean; expectedRevision: number; reason: string }
export interface TemplateScenario { id: string; name: string; description: string; payload: Record<string, unknown>; expectedPath: string[]; expectedFieldErrors: Record<string, string> }
export interface TemplateCopy { definitionId: string; processKey: string; name: string; status: string; version: number; revision: number; templateVersion: number; copiedBy: string; copiedAt: string }
export interface ProcessTemplate {
  key: string; templateVersion: number; name: string; category: string; description: string; scope: string; businessType: 'FORM'
  dependencies: string[]; defaultRoles: string[]; fieldDescriptions: Record<string, string>; risks: string[]; upgradePolicy: string
  notificationTexts: Record<string, string>; notificationsAvailable: boolean; graph: Graph; formSchema: FormSchema
  scenarios: TemplateScenario[]; copies: TemplateCopy[]
}
export interface TemplateCopyInput { key: string; name: string; templateVersion: number }
/** 自检只读快照。@author owlzhangfq@gmail.com */
export interface SystemCheckReport {
  checkedAt: string
  checks: Array<{ id: string; status: 'UP' | 'DOWN' | 'UNKNOWN' | 'WARNING' | 'NOT_IMPLEMENTED'; code: string; message: string }>
}
export type TaskAction = 'CLAIM' | 'RELEASE' | 'TRANSFER' | 'DELEGATE' | 'RESOLVE' | 'RETURN' | 'REJECT' | 'APPROVE'
/** 当前可操作的任务快照；动作由服务端按委派状态限制。@author owlzhangfq@gmail.com */
export interface Task { taskId: string; taskName: string; assignee?: string; applicationId: string; createdAt: string; version: number; owner?: string; delegationState: 'NONE' | 'PENDING' | 'RESOLVED'; allowedActions: TaskAction[]; countersign?: { total: number; completed: number }; dueAt?: string | null }
/** 待办只读摘要不携带审批正文或可直接提交的动作版本。@author owlzhangfq@gmail.com */
export interface PendingTaskItem {
  taskId: string; taskName: string; applicationId: string; businessNo: string; title: string; processKey: string
  definitionVersion: number; applicant: string; amount: string | null; roundNo: number; assignee?: string
  owner?: string; delegationState: 'NONE' | 'PENDING' | 'RESOLVED'; createdAt: string; dueAt?: string | null
  legalEntityName?: string | null; departmentName?: string | null; positionName?: string | null
}
/** 服务端筛选与分页参数。@author owlzhangfq@gmail.com */
export interface PendingTaskQuery {
  q?: string; processKey?: string; applicant?: string; organization?: string; assignment?: 'all' | 'assigned' | 'unclaimed' | 'delegated'
  deadline?: 'all' | 'overdue' | 'pending' | 'unrecorded'
  minAmount?: string; maxAmount?: string; limit?: number; cursor?: string
}
/** 当前筛选计数不会被已加载条数替代。@author owlzhangfq@gmail.com */
export interface PendingTaskPage { items: PendingTaskItem[]; nextCursor?: string | null; total: number }
/** 提交时保留任务快照版本，不在冲突后自动更新版本。@author owlzhangfq@gmail.com */
export interface TaskActionInput { action: TaskAction; comment?: string; targetUser?: string; expectedVersion: number }
export interface Application { id: string; businessNo: string; processKey: string; definitionVersion: number; createdBy: string; title: string; payload: Record<string, unknown>; formSchema: FormSchema | null; status: string; roundNo: number; version: number }
export interface SubmissionRound { roundNo: number; processInstanceId: string; definitionVersion: number; title: string; payload: Record<string, unknown>; formSchema: FormSchema | null; submittedBy: string; submittedAt: string; status: string; reason: string | null; completedBy: string | null; completedAt: string | null; initiatorContext?: InitiatorContext | null }
export interface HistoryEvent {
  id: string; sequence: number; occurredAt: string; source: string; action: string
  aggregateVersion?: number; roundNo?: number; actor?: string; targetUser?: string; comment?: string
  nodeId?: string; nodeName?: string; nodeType?: string; taskId?: string; processInstanceId?: string; definitionVersion?: number
  previousStatus?: string; currentStatus?: string
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
  kind: 'APPLICATION_SUBMITTED' | 'TASK_PENDING' | 'APPLICATION_RETURNED' | 'APPLICATION_REJECTED' | 'APPLICATION_APPROVED' | 'APPLICATION_WITHDRAWN' | 'TASK_TRANSFERRED' | 'TASK_DELEGATED' | 'TASK_RESOLVED' | 'TASK_OVERDUE'
  taskId?: string; nodeName?: string; createdAt: string; readAt?: string; content?: string | null
}
/** 个人消息列表和未读总数。@author owlzhangfq@gmail.com */
export interface InboxPage { items: InboxMessage[]; nextCursor?: string | null; unreadCount: number }
/** 已读筛选与稳定分页游标。@author owlzhangfq@gmail.com */
export interface InboxQuery { read?: 'all' | 'unread'; limit?: number; cursor?: string }

function historyQuery(query: object) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.size ? '?' + params.toString() : ''
}

async function request<T>(path: string, init: RequestInit = {}, format: 'json' | 'xlsx' | 'binary' = 'json'): Promise<T> {
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
  INVALID_OPERATIONS_QUERY: '统计筛选无效：请检查 UTC 日期范围、流程标识和版本，范围最多 366 天。',
      CONCURRENCY_CONFLICT: '数据已被其他操作更新，请重新加载并核对后再操作。',
      COUNTERSIGN_ASSIGNMENT_FIXED: '会签名单已固定，不能转交、释放或重新领取；可委派协助后回交。',
      COUNTERSIGN_NO_MEMBERS: '会签节点当前没有有效审批人，本次操作未生效，请联系管理员补齐审批名单。',
      COUNTERSIGN_STATE_INVALID: '会签执行状态异常，本次操作未生效，请联系管理员核对。',
      TASK_DELEGATION_PENDING: '这项任务处于受托处理阶段，请填写意见并回交给原审批人。',
      TASK_NOT_DELEGATED: '任务已不在待回交状态，请刷新后重新选择。',
      TASK_DELEGATION_OWNER_MISSING: '原委派责任人缺失，请联系流程管理员核对。',
      INVALID_TASK_QUERY: '待办筛选无效，请检查金额范围并重新查询。',
      INVALID_TASK_RECIPIENT: '接收人须为当前租户的其他有效审批账号，请重新选择。',
      FORBIDDEN: '当前账号没有执行此操作的权限，本次未重新执行。原操作结果请查询业务状态。',
      UNAUTHENTICATED: '登录已失效，请重新登录。',
      FORM_VALIDATION_FAILED: '部分表单字段未通过校验，请按提示修改。',
      INVALID_FORM_SCHEMA: '表单配置未通过校验，请检查字段标识、类型、选项与约束。',
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

export const writeRequests = new PendingWrites((operation, key) => request(operation.path, {
  method: operation.method, body: operation.body, headers: { 'Idempotency-Key': key }
}))
function write<T>(path: string, method: WriteRequest['method'], label: string, body?: unknown) {
  return writeRequests.run<T>({ path, method, label, body: body === undefined ? undefined : JSON.stringify(body) })
}

export const api = {
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
  webhookOverview: (filters: WebhookOverviewFilters, signal: AbortSignal) => request<WebhookOverview>('/integrations/webhooks/overview' + historyQuery(filters), { signal }),
  webhookDeliveries: (filters: WebhookFilters, signal: AbortSignal) => request<WebhookPage>('/integrations/webhooks/deliveries' + historyQuery(filters), { signal }),
  webhookDelivery: (id: string, signal: AbortSignal) => request<WebhookDetail>('/integrations/webhooks/deliveries/' + encodeURIComponent(id), { signal }),
  retryWebhook: (id: string, expectedVersion: number) => write<WebhookItem>('/integrations/webhooks/deliveries/' + encodeURIComponent(id) + '/retry', 'POST', '重新排队 Webhook 投递', { expectedVersion }),
  organizationStatus: (signal: AbortSignal) => request<{ initialized: boolean }>('/organization', { signal }),
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
  systemChecks: (signal: AbortSignal) => request<SystemCheckReport>('/system/checks', { signal }),
  applicationTimeline: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/timeline' + historyQuery(query)),
  assistRuns: (id: string, query: AssistRunFilter, signal: AbortSignal) => request<AssistRunPage>('/applications/' + encodeURIComponent(id) + '/assist-runs' + historyQuery(query), { signal }),
  assistRun: (id: string, runId: string, signal: AbortSignal) => request<AssistRunDetail>('/applications/' + encodeURIComponent(id) + '/assist-runs/' + encodeURIComponent(runId), { signal }),
  applicationComments: (id: string, query: CommentQuery, signal: AbortSignal) => request<CommentPage>('/applications/' + encodeURIComponent(id) + '/comments' + historyQuery(query), { signal }),
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
  readNotification: (id: string) => write<InboxMessage>(`/notifications/${encodeURIComponent(id)}/read`, 'POST', '标记消息已读', {}),
  taskRecipients: (taskId: string, signal: AbortSignal) => request<string[]>(`/tasks/${encodeURIComponent(taskId)}/recipients`, { signal }),
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
  applicationRounds: (id: string, signal?: AbortSignal) => request<SubmissionRound[]>(`/applications/${encodeURIComponent(id)}/rounds`, { signal }),
  createApplication: (body: { businessNo: string; processKey: string; definitionVersion: number; title: string; payload: Record<string, unknown> }) => write<Application>('/applications', 'POST', '创建申请草稿', body),
  myAppointments: (afterId: string | undefined, signal: AbortSignal) => request<InitiatorAppointmentPage>('/organization/my-appointments?limit=30' + (afterId ? '&afterId=' + encodeURIComponent(afterId) : ''), { signal }),
  submitApplication: (id: string, expectedVersion: number, initiatorAppointmentId?: string) => write<Application>(`/applications/${encodeURIComponent(id)}/submit`, 'POST', '提交申请', { expectedVersion, ...(initiatorAppointmentId ? { initiatorAppointmentId } : {}) }),
  withdrawApplication: (id: string, body: { expectedVersion: number; comment?: string }) => write<Application>(`/applications/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回申请', body),
  cancelApplication: (id: string, body: { expectedVersion: number; comment?: string }) => write<Application>(`/applications/${encodeURIComponent(id)}/cancel`, 'POST', '作废申请', body),
  definitionAssignees: (signal: AbortSignal) => request<AssigneeOption[]>('/process-definitions/assignee-options', { signal }),
  searchDefinitions: (filters: DefinitionCatalogFilters, signal: AbortSignal) => request<DefinitionCatalogPage>('/process-definitions/search?' + new URLSearchParams(Object.entries(filters).filter(([, value]) => value !== undefined && value !== '').map(([key, value]) => [key, String(value)])), { signal }),
  getDefinition: (id: string, signal?: AbortSignal) => request<Definition>(`/process-definitions/${encodeURIComponent(id)}`, { signal }),
  templates: () => request<ProcessTemplate[]>('/process-templates'),
  copyTemplate: (templateKey: string, body: TemplateCopyInput) => write<Definition>(`/process-templates/${encodeURIComponent(templateKey)}/copy`, 'POST', '复制流程模板为草稿', body),
  definition: (body: { key: string; name: string; graph: Graph; formSchema?: FormSchema | null; notificationTexts?: NotificationTexts }) => write<Definition>('/process-definitions', 'POST', '创建流程草稿', body),
  updateDefinition: (id: string, body: { name: string; graph: Graph; expectedRevision: number; formSchema?: FormSchema | null; notificationTexts?: NotificationTexts }) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}`, 'PUT', '保存流程草稿', body),
  upgradeConditions: (graph: Graph, signal?: AbortSignal) => request<Graph>('/process-definitions/upgrade-conditions', { method: 'POST', body: JSON.stringify({ graph }), signal }),
  validateDefinition: (graph: Graph, formSchema?: FormSchema | null, signal?: AbortSignal, key?: string) => request<ValidationResult>('/process-definitions/validate', { method: 'POST', body: JSON.stringify({ graph, formSchema, key }), signal }),
  publishDefinition: (id: string, revision: number, changeNote: string) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}/publish?expectedRevision=${revision}`, 'POST', '发布流程', { changeNote })
}
