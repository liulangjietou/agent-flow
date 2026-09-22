const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

export interface ApiError { status: number; message: string }
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
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try { const body = await response.json() as { message?: string }; message = body.message ?? message } catch { /* 保留状态码 */ }
    throw { status: response.status, message } satisfies ApiError
  }
  const text = await response.text()
  return text ? JSON.parse(text) as T : undefined as T
}

export const api = {
  applicationTimeline: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/timeline' + historyQuery(query)),
  applicationAudit: (id: string, query: HistoryQuery = {}) => request<HistoryPage>('/applications/' + encodeURIComponent(id) + '/audit' + historyQuery(query)),
  login: (body: { tenantId: string; username: string; password: string }) => request<{ token: string; user: Actor }>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  me: () => request<{ actor: Actor }>('/auth/me'),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
  tasks: () => request<Task[]>('/tasks'),
  taskAction: (taskId: string, body: { action: string; comment?: string; targetUser?: string; expectedVersion: number }) => request<{ taskId: string; action: string; applicationStatus: string; version: number }>(`/tasks/${encodeURIComponent(taskId)}/actions`, { method: 'POST', body: JSON.stringify(body) }),
  applications: () => request<Application[]>('/applications'),
  application: (id: string) => request<Application>(`/applications/${encodeURIComponent(id)}`),
  updateApplication: (id: string, body: { expectedVersion: number; title: string; payload: Record<string, unknown> }) => request<Application>(`/applications/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
  applicationRounds: (id: string) => request<SubmissionRound[]>(`/applications/${encodeURIComponent(id)}/rounds`),
  createApplication: (body: { businessNo: string; processKey: string; definitionVersion: number; title: string; payload: Record<string, unknown> }) => request<Application>('/applications', { method: 'POST', body: JSON.stringify(body) }),
  submitApplication: (id: string, expectedVersion: number) => request<Application>(`/applications/${encodeURIComponent(id)}/submit`, { method: 'POST', body: JSON.stringify({ expectedVersion }) }),
  withdrawApplication: (id: string, body: { expectedVersion: number; comment?: string }) => request<Application>(`/applications/${encodeURIComponent(id)}/withdraw`, { method: 'POST', body: JSON.stringify(body) }),
  definitions: () => request<Definition[]>('/process-definitions'),
  definition: (body: { key: string; name: string; graph: Graph }) => request<Definition>('/process-definitions', { method: 'POST', body: JSON.stringify(body) }),
  updateDefinition: (id: string, body: { name: string; graph: Graph; expectedRevision: number }) => request<Definition>(`/process-definitions/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
  validateDefinition: (graph: Graph) => request<{ errors: string[] }>('/process-definitions/validate', { method: 'POST', body: JSON.stringify({ graph }) }),
  publishDefinition: (id: string, revision: number) => request<Definition>(`/process-definitions/${encodeURIComponent(id)}/publish?expectedRevision=${revision}`, { method: 'POST' })
}
