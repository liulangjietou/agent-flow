import type { AssigneeOption } from './definitionAssignees'
import type { ApiDocument } from './apiReference'
import { PendingWrites, type WriteRequest } from './pendingWrites.js'
import type { FieldErrors, FormSchema } from './formSchema'
const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

export interface ApiError { status: number; code: string; message: string; details?: { fieldErrors?: FieldErrors; definitionErrors?: string[] } }
/** 当前设计的模拟输入。@author owlzhangfq@gmail.com */
export interface SimulationInput { graph: Graph; formSchema: FormSchema | null; values: Record<string, unknown> }
/** 与发布基线比较的完整当前配置。@author owlzhangfq@gmail.com */
export interface ComparisonInput { key: string; name: string; graph: Graph; formSchema: FormSchema | null }
/** 一个稳定对象的配置变化；缺少前后值表示该侧未配置。@author owlzhangfq@gmail.com */
export interface ComparisonChange {
  area: 'DEFINITION' | 'NODE' | 'EDGE' | 'ROUTING' | 'FORM' | 'FIELD' | 'LAYOUT'
  kind: 'ADDED' | 'REMOVED' | 'MODIFIED'; targetId: string; label: string; property: string; before?: unknown; after?: unknown
}
/** 服务端确认的基线和差异列表。@author owlzhangfq@gmail.com */
export interface ComparisonResult { baseline: { id: string; key: string; name: string; version: number }; changes: ComparisonChange[] }
/** 服务端保存的发布事实；旧版本可能缺少完整记录。@author owlzhangfq@gmail.com */
export interface PublicationResponse {
  recorded: boolean
  publication: null | { definitionId: string; definitionVersion: number; publishedBy: string; authorizedRole: string; publishedAt: string; changeNote: string; validation: { nodeCount: number; edgeCount: number; fieldCount: number; formBound: boolean; checks: string[] } }
}
/** 不包含原测试数据的路径与分支依据。@author owlzhangfq@gmail.com */
export interface SimulationResult {
  path: string[]; edgeIds: string[]
  decisions: Array<{ nodeId: string; selectedEdgeId: string; branches: Array<{ edgeId: string; targetNodeId: string; condition: string; outcome: 'MATCHED' | 'NOT_MATCHED' | 'SKIPPED' | 'DEFAULT_SELECTED' | 'DEFAULT_SKIPPED' }> }>
}
export interface Actor { tenantId: string; userId: string; roles: string[] }
export interface GraphNode { id: string; name: string; type: string; properties: Record<string, string> }
export interface GraphEdge { id: string; source: string; target: string; condition: string; defaultBranch: boolean }
export interface Graph { nodes: GraphNode[]; edges: GraphEdge[] }
export interface Definition { id: string; key: string; name: string; revision: number; version: number; status: string; graph: Graph; formSchema: FormSchema | null }
export interface TemplateScenario { id: string; name: string; description: string; payload: Record<string, unknown>; expectedPath: string[]; expectedFieldErrors: Record<string, string> }
export interface TemplateCopy { definitionId: string; processKey: string; name: string; status: string; version: number; revision: number; templateVersion: number; copiedBy: string; copiedAt: string }
export interface ProcessTemplate {
  key: string; templateVersion: number; name: string; category: string; description: string; scope: string; businessType: 'FORM'
  dependencies: string[]; defaultRoles: string[]; fieldDescriptions: Record<string, string>; risks: string[]; upgradePolicy: string
  notificationTexts: Record<string, string>; notificationsAvailable: false; graph: Graph; formSchema: FormSchema
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
export interface Task { taskId: string; taskName: string; assignee?: string; applicationId: string; createdAt: string; version: number; owner?: string; delegationState: 'NONE' | 'PENDING' | 'RESOLVED'; allowedActions: TaskAction[]; countersign?: { total: number; completed: number } }
/** 待办只读摘要不携带审批正文或可直接提交的动作版本。@author owlzhangfq@gmail.com */
export interface PendingTaskItem {
  taskId: string; taskName: string; applicationId: string; businessNo: string; title: string; processKey: string
  definitionVersion: number; applicant: string; amount: string | null; roundNo: number; assignee?: string
  owner?: string; delegationState: 'NONE' | 'PENDING' | 'RESOLVED'; createdAt: string
}
/** 服务端筛选与分页参数。@author owlzhangfq@gmail.com */
export interface PendingTaskQuery {
  q?: string; processKey?: string; applicant?: string; assignment?: 'all' | 'assigned' | 'unclaimed' | 'delegated'
  minAmount?: string; maxAmount?: string; limit?: number; cursor?: string
}
/** 当前筛选计数不会被已加载条数替代。@author owlzhangfq@gmail.com */
export interface PendingTaskPage { items: PendingTaskItem[]; nextCursor?: string | null; total: number }
/** 提交时保留任务快照版本，不在冲突后自动更新版本。@author owlzhangfq@gmail.com */
export interface TaskActionInput { action: TaskAction; comment?: string; targetUser?: string; expectedVersion: number }
export interface Application { id: string; businessNo: string; processKey: string; definitionVersion: number; createdBy: string; title: string; payload: Record<string, unknown>; formSchema: FormSchema | null; status: string; roundNo: number; version: number }
export interface SubmissionRound { roundNo: number; processInstanceId: string; definitionVersion: number; title: string; payload: Record<string, unknown>; formSchema: FormSchema | null; submittedBy: string; submittedAt: string; status: string; reason: string | null; completedBy: string | null; completedAt: string | null }
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
  kind: 'APPLICATION_SUBMITTED' | 'TASK_PENDING' | 'APPLICATION_RETURNED' | 'APPLICATION_REJECTED' | 'APPLICATION_APPROVED' | 'APPLICATION_WITHDRAWN' | 'TASK_TRANSFERRED' | 'TASK_DELEGATED' | 'TASK_RESOLVED'
  taskId?: string; nodeName?: string; createdAt: string; readAt?: string
}
/** 个人消息列表和未读总数。@author owlzhangfq@gmail.com */
export interface InboxPage { items: InboxMessage[]; nextCursor?: string | null; unreadCount: number }
/** 已读筛选与稳定分页游标。@author owlzhangfq@gmail.com */
export interface InboxQuery { read?: 'all' | 'unread'; limit?: number; cursor?: string }

function historyQuery(query: HistoryQuery) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.size ? '?' + params.toString() : ''
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Content-Type', 'application/json')
  const token = localStorage.getItem('agentflow.token')
  if (token) headers.set('Authorization', `Bearer ${token}`)
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
      IDEMPOTENCY_KEY_REUSED: '上次请求键对应其他内容，本次未重新执行。请先查询当前业务状态。',
      IDEMPOTENCY_KEY_EXPIRED: '上次操作的恢复期限已过，未重新执行。请先查询当前业务状态。',
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
      INVALID_PUBLICATION_NOTE: '请填写 1 至 2000 字的发布变更说明。',
      INVALID_TEMPLATE_COPY_REQUEST: '复制信息无效，请检查流程标识、名称和模板版本。',
      TEMPLATE_VERSION_CONFLICT: '模板版本已变化，请重新加载目录，核对后再复制。',
      DEFINITION_BINDING_AMBIGUOUS: '这份旧申请未保存原流程来源，当前存在同名版本。请保留原记录，核对流程后重新发起申请。'
    }
    throw { status: response.status, code, message: messages[code] ?? message, details } satisfies ApiError
  }
  try {
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
  openApi: (signal: AbortSignal) => request<ApiDocument>('/openapi.json', { signal }),
  workspaceApplications: (query: WorkspaceQuery, signal: AbortSignal) => request<WorkspacePage<WorkspaceApplication>>('/workspace/applications' + historyQuery(query), { signal }),
  workspaceHandled: (query: WorkspaceQuery, signal: AbortSignal) => request<WorkspacePage<WorkspaceHandled>>('/workspace/handled' + historyQuery(query), { signal }),
  definitionPublication: (id: string, signal: AbortSignal) => request<PublicationResponse>(`/process-definitions/${encodeURIComponent(id)}/publication`, { signal }),
  compareDefinition: (baselineId: string, body: ComparisonInput, signal: AbortSignal) => request<ComparisonResult>('/process-definitions/' + encodeURIComponent(baselineId) + '/compare', { method: 'POST', body: JSON.stringify(body), signal }),
  simulateDesign: (body: SimulationInput, signal: AbortSignal) => request<SimulationResult>('/process-definitions/simulate', { method: 'POST', body: JSON.stringify(body), signal }),
  systemChecks: (signal: AbortSignal) => request<SystemCheckReport>('/system/checks', { signal }),
  applicationTimeline: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/timeline' + historyQuery(query)),
  applicationAudit: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/audit' + historyQuery(query)),
  login: (body: { tenantId: string; username: string; password: string }) => request<{ token: string; user: Actor }>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  me: () => request<{ actor: Actor }>('/auth/me'),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
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
  applications: (signal?: AbortSignal) => request<Application[]>('/applications', { signal }),
  application: (id: string, signal?: AbortSignal) => request<Application>(`/applications/${encodeURIComponent(id)}`, { signal }),
  updateApplication: (id: string, body: { expectedVersion: number; title: string; payload: Record<string, unknown> }) => write<Application>(`/applications/${encodeURIComponent(id)}`, 'PUT', '保存申请修改', body),
  applicationRounds: (id: string) => request<SubmissionRound[]>(`/applications/${encodeURIComponent(id)}/rounds`),
  createApplication: (body: { businessNo: string; processKey: string; definitionVersion: number; title: string; payload: Record<string, unknown> }) => write<Application>('/applications', 'POST', '创建申请草稿', body),
  submitApplication: (id: string, expectedVersion: number) => write<Application>(`/applications/${encodeURIComponent(id)}/submit`, 'POST', '提交申请', { expectedVersion }),
  withdrawApplication: (id: string, body: { expectedVersion: number; comment?: string }) => write<Application>(`/applications/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回申请', body),
  definitionAssignees: (signal: AbortSignal) => request<AssigneeOption[]>('/process-definitions/assignee-options', { signal }),
  definitions: () => request<Definition[]>('/process-definitions'),
  getDefinition: (id: string) => request<Definition>(`/process-definitions/${encodeURIComponent(id)}`),
  templates: () => request<ProcessTemplate[]>('/process-templates'),
  copyTemplate: (templateKey: string, body: TemplateCopyInput) => write<Definition>(`/process-templates/${encodeURIComponent(templateKey)}/copy`, 'POST', '复制流程模板为草稿', body),
  definition: (body: { key: string; name: string; graph: Graph; formSchema?: FormSchema | null }) => write<Definition>('/process-definitions', 'POST', '创建流程草稿', body),
  updateDefinition: (id: string, body: { name: string; graph: Graph; expectedRevision: number; formSchema?: FormSchema | null }) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}`, 'PUT', '保存流程草稿', body),
  validateDefinition: (graph: Graph, formSchema?: FormSchema | null) => request<{ errors: string[] }>('/process-definitions/validate', { method: 'POST', body: JSON.stringify({ graph, formSchema }) }),
  publishDefinition: (id: string, revision: number, changeNote: string) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}/publish?expectedRevision=${revision}`, 'POST', '发布流程', { changeNote })
}
