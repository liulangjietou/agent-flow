import { PendingWrites, type WriteRequest } from './pendingWrites.js'
import type { FieldErrors, FormSchema } from './formSchema'
const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

export interface ApiError { status: number; code: string; message: string; details?: { fieldErrors?: FieldErrors } }
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
export interface Task { taskId: string; taskName: string; assignee?: string; applicationId: string; createdAt: string; version: number }
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
      FORBIDDEN: '当前账号没有执行此操作的权限，本次未重新执行。原操作结果请查询业务状态。',
      UNAUTHENTICATED: '登录已失效，请重新登录。',
      FORM_VALIDATION_FAILED: '部分表单字段未通过校验，请按提示修改。',
      INVALID_FORM_SCHEMA: '表单配置未通过校验，请检查字段标识、类型、选项与约束。',
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
  systemChecks: (signal: AbortSignal) => request<SystemCheckReport>('/system/checks', { signal }),
  applicationTimeline: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/timeline' + historyQuery(query)),
  applicationAudit: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/audit' + historyQuery(query)),
  login: (body: { tenantId: string; username: string; password: string }) => request<{ token: string; user: Actor }>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  me: () => request<{ actor: Actor }>('/auth/me'),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
  tasks: () => request<Task[]>('/tasks'),
  taskAction: (taskId: string, body: { action: string; comment?: string; targetUser?: string; expectedVersion: number }) => write<{ taskId: string; action: string; applicationStatus: string; version: number }>(`/tasks/${encodeURIComponent(taskId)}/actions`, 'POST', '处理审批任务', body),
  applications: () => request<Application[]>('/applications'),
  application: (id: string) => request<Application>(`/applications/${encodeURIComponent(id)}`),
  updateApplication: (id: string, body: { expectedVersion: number; title: string; payload: Record<string, unknown> }) => write<Application>(`/applications/${encodeURIComponent(id)}`, 'PUT', '保存申请修改', body),
  applicationRounds: (id: string) => request<SubmissionRound[]>(`/applications/${encodeURIComponent(id)}/rounds`),
  createApplication: (body: { businessNo: string; processKey: string; definitionVersion: number; title: string; payload: Record<string, unknown> }) => write<Application>('/applications', 'POST', '创建申请草稿', body),
  submitApplication: (id: string, expectedVersion: number) => write<Application>(`/applications/${encodeURIComponent(id)}/submit`, 'POST', '提交申请', { expectedVersion }),
  withdrawApplication: (id: string, body: { expectedVersion: number; comment?: string }) => write<Application>(`/applications/${encodeURIComponent(id)}/withdraw`, 'POST', '撤回申请', body),
  definitions: () => request<Definition[]>('/process-definitions'),
  getDefinition: (id: string) => request<Definition>(`/process-definitions/${encodeURIComponent(id)}`),
  templates: () => request<ProcessTemplate[]>('/process-templates'),
  copyTemplate: (templateKey: string, body: TemplateCopyInput) => write<Definition>(`/process-templates/${encodeURIComponent(templateKey)}/copy`, 'POST', '复制流程模板为草稿', body),
  definition: (body: { key: string; name: string; graph: Graph; formSchema?: FormSchema | null }) => write<Definition>('/process-definitions', 'POST', '创建流程草稿', body),
  updateDefinition: (id: string, body: { name: string; graph: Graph; expectedRevision: number; formSchema?: FormSchema | null }) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}`, 'PUT', '保存流程草稿', body),
  validateDefinition: (graph: Graph, formSchema?: FormSchema | null) => request<{ errors: string[] }>('/process-definitions/validate', { method: 'POST', body: JSON.stringify({ graph, formSchema }) }),
  publishDefinition: (id: string, revision: number) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}/publish?expectedRevision=${revision}`, 'POST', '发布流程')
}
