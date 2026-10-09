import { handlingPath, readHandlingTask, validateHandlingReceipt, type HandlingInspect, type HandlingReceipt } from './expenseHandling.js'

/** 自动办理协议只接受固定动作、原身份及有界步骤。 */
export const agentStatuses = { READY: '准备下一步', MODEL_RUNNING: '正在判断下一步', TOOL_READY: '准备查询', TOOL_RUNNING: '正在查询', NEEDS_INFORMATION: '请补充资料', NEEDS_CONFIRMATION: '请确认下一项操作', WAITING_CHILD: '等待原任务与本人确认', COMPLETED: '本次自动办理已结束', INTERRUPTED: '模型执行中断，待确认继续', FAILED: '本次执行已停止', CANCELLED: '已停止自动办理', LIMIT_REACHED: '已达到授权上限' } as const
export const agentActions = { EXPENSE: '读取费用', INVOICE: '读取票据事实', POLICY: '查询制度', PRECHECK_RESULT: '读取预检', EXTRACT_INVOICE: '整理票面并复核', DRAFT: '整理费用草稿', ASK_USER: '补充资料', FINISH: '完成本次目标' } as const
export interface AgentScope { policyLineNos: number[]; invoiceIds: string[]; precheckIds: string[]; maxSteps: number }
export interface AgentDecision { action: keyof typeof agentActions; referenceId?: string | null; lineNo?: number | null; message: string }
export interface AgentStep { id: string; createdAt: string; decision?: AgentDecision | null; outcome: string; observation?: string | null }
export interface ExpenseAgentView { id: string; taskId: string; reportId: string; applicationVersion: number; financialVersion: number; scope: AgentScope; deadline: string
  state: { version: number; status: keyof typeof agentStatuses; steps: AgentStep[]; answers: string[]; message?: string | null; leaseUntil?: string | null; childId?: string | null; childAction?: 'EXTRACT_INVOICE' | 'DRAFT' | null; updatedAt: string } }
export interface AgentPreview { goal: string; scope: AgentScope; applicationVersion: number; financialVersion: number; providerId: string; model: string; destination: string; targetDigest: string; consentDigest: string; sendableData: unknown }
export interface HandlingRead { id: string; input: HandlingInspect; inputDigest: string; version: number; status: 'RUNNING' | 'PREPARED' | 'RECORDED' | 'FAILED'; failureCode?: string | null }
export const agentPath = (id: string, task: string) => `${handlingPath(id)}/${encodeURIComponent(task)}/agent`
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) > 0
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim()
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
const digest = (v: unknown) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const own = (v: object, k: unknown) => typeof k === 'string' && Object.prototype.hasOwnProperty.call(v, k)
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '自动办理记录不完整，请恢复原操作后刷新。' })
function scope(v: unknown): v is AgentScope {
  return object(v) && positive(v.maxSteps) && v.maxSteps <= 12 && Array.isArray(v.policyLineNos) && v.policyLineNos.length <= 20
    && v.policyLineNos.every(n => positive(n) && n <= 200) && new Set(v.policyLineNos).size === v.policyLineNos.length
    && ['invoiceIds', 'precheckIds'].every(key => Array.isArray(v[key]) && (v[key] as unknown[]).length <= (key === 'invoiceIds' ? 20 : 10)
      && (v[key] as unknown[]).every(uuid) && new Set(v[key] as unknown[]).size === (v[key] as unknown[]).length)
}
/** 读取原运行时校验跨单身份、版本及所有动作参数，损坏响应不能开启确认。 */
export function readExpenseAgent(value: unknown, reportId: string, taskId: string): ExpenseAgentView | null {
  if (value == null || value === '') return null
  if (!object(value) || !uuid(value.id) || value.reportId !== reportId || value.taskId !== taskId || !positive(value.applicationVersion)
    || !positive(value.financialVersion) || !scope(value.scope) || !time(value.deadline) || !object(value.state)) throw unreadable()
  const s = value.state, allowed = value.scope
  if (!positive(s.version) || !own(agentStatuses, s.status) || !time(s.updatedAt) || !Array.isArray(s.steps) || s.steps.length > allowed.maxSteps
    || new Set(s.steps.map(step => step?.id)).size !== s.steps.length || !Array.isArray(s.answers) || s.answers.length > 12
    || !s.answers.every(answer => text(answer) && answer.length <= 2000) || s.message != null && !text(s.message)
    || s.childId != null && !uuid(s.childId) || s.childAction != null && !['EXTRACT_INVOICE', 'DRAFT'].includes(String(s.childAction))) throw unreadable()
  for (const step of s.steps) {
    if (!object(step) || !uuid(step.id) || !time(step.createdAt) || !text(step.outcome) || step.observation != null && typeof step.observation !== 'string') throw unreadable()
    if (step.decision != null) {
      const d = step.decision
      if (!object(d) || !own(agentActions, d.action) || !text(d.message) || d.message.length > 2000) throw unreadable()
      if (['INVOICE', 'EXTRACT_INVOICE'].includes(String(d.action)) ? !allowed.invoiceIds.includes(String(d.referenceId)) || d.lineNo != null
        : d.action === 'POLICY' ? !allowed.policyLineNos.includes(Number(d.lineNo)) || !positive(d.lineNo) || d.referenceId != null
        : d.action === 'PRECHECK_RESULT' ? !allowed.precheckIds.includes(String(d.referenceId)) || d.lineNo != null
        : d.referenceId != null || d.lineNo != null) throw unreadable()
    }
  }
  if (['NEEDS_CONFIRMATION', 'TOOL_READY', 'TOOL_RUNNING', 'WAITING_CHILD', 'NEEDS_INFORMATION'].includes(String(s.status)) && !s.steps[s.steps.length - 1]?.decision) throw unreadable()
  if (s.status === 'WAITING_CHILD' && (!uuid(s.childId) || s.childAction !== s.steps[s.steps.length - 1]?.decision?.action)) throw unreadable()
  return value as unknown as ExpenseAgentView
}
/** 预览绑定原选择，不能用另一次读取的目的地摘要授权。 */
export function readAgentPreview(value: unknown, selected: AgentScope): AgentPreview {
  if (!object(value) || !text(value.goal) || !scope(value.scope) || JSON.stringify(value.scope) !== JSON.stringify(selected)
    || !positive(value.applicationVersion) || !positive(value.financialVersion) || !text(value.providerId) || !text(value.model)
    || !text(value.destination) || !digest(value.targetDigest) || !digest(value.consentDigest) || !object(value.sendableData)) throw unreadable()
  return value as unknown as AgentPreview
}
/** 写入回执必须与本次办理及版本一致，未知回执继续保留原幂等键。 */
export function validateAgentMutation(value: unknown, path: string, body: string) {
  const route = /^\/expense-reports\/([^/?]+)\/handling-tasks\/([^/?]+)\/agent(?:\/(resume|cancel))?$/.exec(path)
  if (!route) throw unreadable()
  const result = readExpenseAgent(value, decodeURIComponent(route[1]!), decodeURIComponent(route[2]!)), input = JSON.parse(body)
  if (!result || result.state.version !== (route[3] ? input.expectedVersion + 1 : 1)
    || (route[3] === 'cancel' ? result.state.status !== 'CANCELLED' : route[3] === 'resume' ? !['READY', 'TOOL_READY'].includes(result.state.status) : result.state.status !== 'READY')
    || !route[3] && JSON.stringify(result.scope) !== JSON.stringify(input.scope)) throw unreadable()
  return result
}
/** 调用前步骤和失败也进入历史，恢复时由服务器保存的原输入决定行为。 */
export function readHandlingReads(value: unknown): HandlingRead[] {
  if (!Array.isArray(value) || value.length > 32 || new Set(value.map(v => v?.id)).size !== value.length
    || !value.every(v => object(v) && uuid(v.id) && positive(v.version) && digest(v.inputDigest)
      && ['RUNNING', 'PREPARED', 'RECORDED', 'FAILED'].includes(String(v.status)) && object(v.input) && positive(v.input.expectedVersion)
      && ['EXPENSE', 'INVOICE', 'POLICY', 'PRECHECK_RESULT'].includes(String(v.input.tool)))) throw unreadable()
  return value as HandlingRead[]
}
/** 恢复回执可保留历史版本，但不能返回其他办理或尚未登记的步骤。 */
export function validateReadRecovery(value: unknown, path: string): HandlingReceipt {
  const route = /^\/expense-reports\/([^/?]+)\/handling-tasks\/([^/?]+)\/reads\/[^/?]+\/resume$/.exec(path)
  if (!route || !object(value) || !object(value.result)) throw unreadable()
  const task = readHandlingTask(value.task, decodeURIComponent(route[1]!)), result = value.result
  if (task.id !== decodeURIComponent(route[2]!) || !task.steps.some(step => step.tool === result.tool && step.outcome === 'READ')) throw unreadable()
  return validateHandlingReceipt(value, `${handlingPath(task.reportId)}/${encodeURIComponent(task.id)}/inspect`,
    JSON.stringify({ expectedVersion: task.version - 1, tool: result.tool, referenceId: task.steps[task.steps.length - 1]?.referenceId })) as HandlingReceipt
}
