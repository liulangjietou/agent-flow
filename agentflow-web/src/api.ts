import { PendingWrites, type WriteRequest } from './pendingWrites.js'
const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

export interface ApiError { status: number; code: string; message: string }
export interface Actor { tenantId: string; userId: string; roles: string[] }
export interface GraphNode { id: string; name: string; type: string; properties: Record<string, string> }
export interface GraphEdge { id: string; source: string; target: string; condition: string; defaultBranch: boolean }
export interface Graph { nodes: GraphNode[]; edges: GraphEdge[] }
export interface Definition { id: string; key: string; name: string; revision: number; version: number; status: string; graph: Graph }
export interface Task { taskId: string; taskName: string; assignee?: string; applicationId: string; createdAt: string; version: number }
export interface Application { id: string; businessNo: string; processKey: string; definitionVersion: number; createdBy: string; title: string; payload: Record<string, unknown>; status: string; roundNo: number; version: number }
export interface SubmissionRound { roundNo: number; processInstanceId: string; definitionVersion: number; title: string; payload: Record<string, unknown>; submittedBy: string; submittedAt: string; status: string; reason: string | null; completedBy: string | null; completedAt: string | null }
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
    try { const body = await response.json() as { message?: string; code?: string }; message = body.message ?? message; code = body.code ?? code } catch { /* 已收到明确状态码，保留错误分类。 */ }
    const messages: Record<string, string> = {
      IDEMPOTENCY_KEY_REUSED: '上次请求键对应其他内容，本次未重新执行。请先查询当前业务状态。',
      IDEMPOTENCY_KEY_EXPIRED: '上次操作的恢复期限已过，未重新执行。请先查询当前业务状态。',
      CONCURRENCY_CONFLICT: '数据已被其他操作更新，请重新加载并核对后再操作。',
      FORBIDDEN: '当前账号没有执行此操作的权限，本次未重新执行。原操作结果请查询业务状态。',
      UNAUTHENTICATED: '登录已失效，请重新登录。'
    }
    throw { status: response.status, code, message: messages[code] ?? message } satisfies ApiError
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
  definition: (body: { key: string; name: string; graph: Graph }) => write<Definition>('/process-definitions', 'POST', '创建流程草稿', body),
  updateDefinition: (id: string, body: { name: string; graph: Graph; expectedRevision: number }) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}`, 'PUT', '保存流程草稿', body),
  validateDefinition: (graph: Graph) => request<{ errors: string[] }>('/process-definitions/validate', { method: 'POST', body: JSON.stringify({ graph }) }),
  publishDefinition: (id: string, revision: number) => write<Definition>(`/process-definitions/${encodeURIComponent(id)}/publish?expectedRevision=${revision}`, 'POST', '发布流程')
}
