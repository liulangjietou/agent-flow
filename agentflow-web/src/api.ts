const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

export interface ApiError { status: number; message: string }

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Content-Type', 'application/json')
  const token = localStorage.getItem('agentflow.token')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  if (!response.ok) { let message = `请求失败（${response.status}）`; try { const body = await response.json() as { message?: string }; message = body.message ?? message } catch { /* 保留状态码 */ }; throw { status: response.status, message } satisfies ApiError }
  return response.status === 204 ? undefined as T : await response.json() as T
}

export const api = {
  login: (body: { tenantId: string; username: string; password: string }) => request<{ token: string; actor: { userId: string } }>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  tasks: () => request<Array<{ taskId: string; taskName: string; assignee?: string; applicationId?: string; createdAt?: string; version?: number }>>('/tasks'),
  taskAction: (taskId: string, body: { action: string; comment?: string; targetUser?: string; expectedVersion?: number }) => request<{ taskId: string; action: string; applicationStatus: string; version?: number }>(`/tasks/${taskId}/actions`, { method: 'POST', body: JSON.stringify(body) }),
  definition: (body: unknown) => request<{ id: string; revision: number }>('/process-definitions', { method: 'POST', body: JSON.stringify(body) }),
  updateDefinition: (id: string, body: unknown) => request<{ id: string; revision: number }>(`/process-definitions/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  publishDefinition: (id: string, revision: number) => request(`/process-definitions/${id}/publish?expectedRevision=${revision}`, { method: 'POST' })
}
