import type { ExpenseDetail } from './expenses.js'
import type { InvoiceItem } from './invoiceWallet.js'
import type { PolicyGuidanceView } from './expensePolicyGuidance.js'
import type { PrecheckView } from './expenseDraft.js'

/** 办理记录只组织原业务事实，完成助手步骤不代表预检通过或审批通过。 */
export const handlingStatuses = { OPEN: '继续整理', WAITING: '等待原任务结果', NEEDS_INFORMATION: '需要补充材料', NEEDS_CONFIRMATION: '待本人核对建议', SUBMITTED: '已提交审批', CLOSED: '办理记录已结束', LIMIT_REACHED: '已达到步骤上限' } as const
export const handlingTools = { EXPENSE: '读取费用', INVOICE: '核对票据', POLICY: '查询制度', PRECHECK_RESULT: '读取预检', INVOICE_EXTRACTION: '票面提取与本人确认', DRAFT: '费用行建议', PRECHECK: '费用预检', EXPLANATION: '补正解释', EXPENSE_SAVED: '本人保存费用', CORRECTION: '本人确认补正', SUBMISSION: '本人提交审批' } as const
export type ReadTool = 'EXPENSE' | 'INVOICE' | 'POLICY' | 'PRECHECK_RESULT'
export interface HandlingStep { number: number; tool: keyof typeof handlingTools; referenceId: string; sourceVersion: number; outcome: string; applicationVersion: number; financialVersion: number; inputDigest: string; startedAt: string; updatedAt: string }
export interface HandlingTask { id: string; reportId: string; applicationId: string; goal: string; createdAt: string; version: number; status: keyof typeof handlingStatuses; applicationVersion: number; financialVersion: number; current: boolean; steps: HandlingStep[] }
export interface HandlingStart { applicationVersion: number; financialVersion: number; goal: string }
export interface HandlingInspect { expectedVersion: number; tool: ReadTool; referenceId?: string; lineNo?: number }
export interface HandlingResult { tool: ReadTool; expense?: ExpenseDetail; invoice?: InvoiceItem; policy?: PolicyGuidanceView; precheck?: PrecheckView }
export interface HandlingReceipt { task: HandlingTask; result: HandlingResult }
export const handlingPath = (id: string) => `/expense-reports/${encodeURIComponent(id)}/handling-tasks`
export const handlingActive = (task: HandlingTask) => !['CLOSED', 'SUBMITTED', 'LIMIT_REACHED'].includes(task.status)
export const stepOutcomes: Record<string, string> = { READ: '已读取', QUEUED: '等待执行', RUNNING: '执行中', COMPLETED: '待核对', FAILED: '执行未完成', BLOCKED: '预检未通过', UNAVAILABLE: '依据不可用', READY: '预检完成', ADOPTED: '已记录采纳', CONFIRMED: '已确认', DISMISSED: '已放弃', SAVED: '已保存', SUBMITTED: '已提交' }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const positive = (v: unknown) => Number.isSafeInteger(v) && (v as number) > 0
const nonnegative = (v: unknown) => Number.isSafeInteger(v) && (v as number) >= 0
const time = (v: unknown) => typeof v === 'string' && Number.isFinite(Date.parse(v))
const text = (v: unknown) => typeof v === 'string' && !!v.trim()
const has = (v: object, key: unknown) => typeof key === 'string' && Object.prototype.hasOwnProperty.call(v, key)
const unreadable = () => ({ status: 0, code: 'RESPONSE_UNREADABLE', message: '办理回执不完整，请先恢复原操作，再读取办理记录。' })

/** 跨单、乱序、缺失摘要及未知状态均不能被当作已完成步骤。 */
export function readHandlingTask(value: unknown, reportId: string): HandlingTask {
  if (!object(value) || !uuid(value.id) || value.reportId !== reportId || !uuid(value.applicationId) || !text(value.goal)
      || !time(value.createdAt) || !positive(value.version) || !positive(value.applicationVersion) || !positive(value.financialVersion)
      || !has(handlingStatuses, value.status) || typeof value.current !== 'boolean' || !Array.isArray(value.steps) || value.steps.length > 32
      || !value.steps.every((step, index) => object(step) && step.number === index + 1 && has(handlingTools, step.tool)
        && uuid(step.referenceId) && positive(step.sourceVersion) && has(stepOutcomes, step.outcome)
        && positive(step.applicationVersion) && positive(step.financialVersion) && typeof step.inputDigest === 'string'
        && /^[a-f0-9]{64}$/.test(step.inputDigest) && time(step.startedAt) && time(step.updatedAt))) throw unreadable()
  if (value.current && ['SUBMITTED', 'CLOSED', 'LIMIT_REACHED'].includes(String(value.status))) throw unreadable()
  return value as unknown as HandlingTask
}
/** 历史有界，重读不创建任务，也不重复发送模型材料。 */
export function readHandlingTasks(value: unknown, reportId: string): HandlingTask[] {
  if (!Array.isArray(value) || value.length > 20) throw unreadable()
  const tasks = value.map(item => readHandlingTask(item, reportId))
  if (new Set(tasks.map(task => task.id)).size !== tasks.length || tasks.filter(handlingActive).length > 1) throw unreadable()
  return tasks
}
/** 幂等队列在确认回执前绑定原请求；响应错误时保留原键供恢复。 */
export function validateHandlingReceipt(value: unknown, path: string, body: string): HandlingTask | HandlingReceipt {
  const match = /^\/expense-reports\/([^/?]+)\/handling-tasks(?:\/([^/?]+)\/(close|inspect))?$/.exec(path)
  if (!match) throw unreadable()
  const input = JSON.parse(body), reportId = decodeURIComponent(match[1]!)
  const task = readHandlingTask(match[3] === 'inspect' && object(value) ? value.task : value, reportId)
  if (match[2] && task.id !== decodeURIComponent(match[2])) throw unreadable()
  if (!match[3]) {
    if (task.version !== 1 || task.status !== 'OPEN' || task.steps.length || task.goal !== input.goal || task.applicationVersion !== input.applicationVersion || task.financialVersion !== input.financialVersion) throw unreadable()
  } else if (match[3] === 'close' ? task.version !== input.expectedVersion + 1 : task.version <= input.expectedVersion) throw unreadable()
  if (match[3] === 'close' && task.status !== 'CLOSED') throw unreadable()
  if (match[3] === 'inspect') {
    if (!object(value) || !object(value.result) || value.result.tool !== input.tool) throw unreadable()
    const result = value.result, step = task.steps[task.steps.length - 1], payload = ({ EXPENSE: 'expense', INVOICE: 'invoice', POLICY: 'policy', PRECHECK_RESULT: 'precheck' } as const)[input.tool as ReadTool]
    if (!payload || !object(result[payload]) || Object.keys(result).some(key => key !== 'tool' && key !== payload) || !step || step.tool !== input.tool || step.outcome !== 'READ') throw unreadable()
    const data = result[payload] as Record<string, unknown>
    if (input.tool === 'EXPENSE' && (data.id !== reportId || data.applicationId !== task.applicationId || data.applicationVersion !== task.applicationVersion || data.financialVersion !== task.financialVersion || !object(data.content) || !Array.isArray(data.content.lines))) throw unreadable()
    if (input.tool === 'INVOICE' && (data.id !== input.referenceId || step.referenceId !== input.referenceId || data.version !== step.sourceVersion || !object(data.original))) throw unreadable()
    if (input.tool === 'POLICY' && (!object(data.guidance) || data.guidance.policyId !== step.referenceId || data.guidance.policyVersion !== step.sourceVersion || !object(data.context))) throw unreadable()
    if (input.tool === 'PRECHECK_RESULT' && (!object(data.job) || data.job.id !== input.referenceId || data.job.version !== step.sourceVersion || data.job.applicationVersion !== task.applicationVersion || data.job.financialVersion !== task.financialVersion || !Array.isArray(data.findings))) throw unreadable()
    return value as unknown as HandlingReceipt
  }
  return task
}

export const usageKinds = { SUMMARY: '审批摘要', DRAFT: '表单草稿', INVOICE: '票据抽取', EXPENSE_DRAFT: '费用行建议', PRECHECK_EXPLANATION: '预检解释', EXPENSE_RISK: '费用风险解释', HANDLING: '报销办理决策' } as const
export interface AgentUsage { runId: string; kind: keyof typeof usageKinds; subjectId: string; queuedAt: string; startedAt: string; completedAt: string | null; queueMillis: number; executionMillis: number | null; outcome: string; providerId: string | null; modelVersion: string | null; promptVersion: string | null; usageStatus: 'REPORTED' | 'NOT_REPORTED' | 'INVALID'; inputTokens: number | null; outputTokens: number | null; totalTokens: number | null }
export const usageOutcomes: Record<string, string> = { IN_PROGRESS: '最终结果尚未记录', SUCCEEDED: '模型输出已通过格式检查', EXECUTION_FAILED: '执行异常', MODEL_TIMEOUT: '模型超时', MODEL_UNAVAILABLE: '模型不可用', INVALID_MODEL_OUTPUT: '输出校验失败', INPUT_UNAVAILABLE: '来源不可用' }
/** 明确报告的零有效；未报告、记录中断和无效用量不能补零。 */
export function readAgentUsage(value: unknown, subjectId?: string): AgentUsage[] {
  if (!Array.isArray(value) || value.length > 100 || !value.every(row => object(row) && uuid(row.runId) && uuid(row.subjectId)
      && (!subjectId || row.subjectId === subjectId) && has(usageKinds, row.kind) && has(usageOutcomes, row.outcome)
      && time(row.queuedAt) && time(row.startedAt) && nonnegative(row.queueMillis)
      && (row.outcome === 'IN_PROGRESS' ? row.completedAt === null && row.executionMillis === null : time(row.completedAt) && nonnegative(row.executionMillis))
      && ['providerId', 'modelVersion', 'promptVersion'].every(key => row[key] === null || text(row[key]))
      && (row.usageStatus === 'REPORTED' ? nonnegative(row.inputTokens) && nonnegative(row.outputTokens) && nonnegative(row.totalTokens)
        && Number(row.inputTokens) + Number(row.outputTokens) === row.totalTokens
        : ['NOT_REPORTED', 'INVALID'].includes(String(row.usageStatus)) && row.inputTokens === null && row.outputTokens === null && row.totalTokens === null))) throw unreadable()
  return value as AgentUsage[]
}
