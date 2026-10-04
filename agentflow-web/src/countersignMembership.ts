import type { Task } from './api'

export interface CountersignMember { taskId: string; user: string; assignee: string; delegated: boolean; canRemove: boolean }
export interface CountersignBinding {
  taskId: string; applicationId: string; roundNo: number; applicationVersion: number
  processInstanceId: string; nodeId: string; executionId: string
}
export interface CountersignView extends CountersignBinding {
  originalMembers: string[]; total: number; completed: number; pending: CountersignMember[]; completedUsers: string[]
  canChange: boolean; issue?: 'TASK_DELEGATION_PENDING' | 'EXPENSE_PROJECT_MEMBERS_FIXED' | null; canAdd: boolean; additions: string[]
}
export type CountersignInput = { action: 'ADD'; targetUser: string; reason: string; expectedVersion: number }
  | { action: 'REMOVE'; targetTaskId: string; reason: string; expectedVersion: number }
export interface CountersignReceipt extends CountersignBinding {
  action: 'ADD' | 'REMOVE'; targetTaskId: string; targetUser: string
  totalBefore: number; totalAfter: number; completed: number; auditEventId: string
}
const text = (v: unknown): v is string => typeof v === 'string' && v.trim().length > 0
const integer = (v: unknown, min = 0): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min
const distinct = (v: unknown): v is string[] => Array.isArray(v) && v.every(text) && new Set(v).size === v.length
const binding = (v: CountersignBinding) => v && [v.taskId, v.applicationId, v.processInstanceId, v.nodeId, v.executionId].every(text)
  && integer(v.roundNo, 1) && integer(v.applicationVersion, 1)

/** 名单只适用于所选任务和版本；计数或授权投影不一致时不提供写入入口。 */
export function validateCountersignView(v: CountersignView, task: Task): CountersignView {
  const fail = () => { throw new Error('会签名单与当前任务不一致，请刷新当前任务后重新查看。') }
  if ((task.countersign?.mode ?? 'ALL') !== 'ALL' || !binding(v) || v.taskId !== task.taskId || v.applicationId !== task.applicationId || v.applicationVersion !== task.version
      || !integer(v.total, 1) || !integer(v.completed) || !distinct(v.originalMembers) || !v.originalMembers.length
      || !distinct(v.completedUsers) || !distinct(v.additions) || !Array.isArray(v.pending) || !v.pending.length
      || v.completed !== v.completedUsers.length || v.total !== v.completed + v.pending.length
      || typeof v.canChange !== 'boolean' || typeof v.canAdd !== 'boolean') return fail()
  const ids = new Set<string>(), users = new Set(v.completedUsers)
  for (const m of v.pending) {
    if (!m || !text(m.taskId) || !text(m.user) || !text(m.assignee) || typeof m.delegated !== 'boolean' || typeof m.canRemove !== 'boolean'
        || ids.has(m.taskId) || users.has(m.user) || (!m.delegated && m.assignee !== m.user)) return fail()
    ids.add(m.taskId); users.add(m.user)
    if (m.canRemove !== (v.canChange && v.pending.length > 1 && !m.delegated && m.taskId !== task.taskId)) return fail()
  }
  const source = v.pending.find(m => m.taskId === task.taskId)
  const projectFixed = v.issue === 'EXPENSE_PROJECT_MEMBERS_FIXED'
  if (!source || source.assignee !== task.assignee || source.delegated !== (task.delegationState === 'PENDING')
      || v.canChange !== (!projectFixed && !source.delegated)
      || (!projectFixed && (source.delegated ? v.issue !== 'TASK_DELEGATION_PENDING' : v.issue != null))
      || v.additions.some(user => users.has(user)) || ((!v.canChange || v.total >= 100) && v.additions.length > 0)
      || v.canAdd !== (v.canChange && v.total < 100 && v.additions.length > 0)
      || task.countersign?.total !== v.total || task.countersign?.completed !== v.completed) return fail()
  return v
}

/** 表单提交保留查看名单时的版本；减签目标必须是确切的未决任务编号。 */
export function countersignInput(v: CountersignView, action: 'ADD' | 'REMOVE', target: string, reason: string): CountersignInput {
  if (v.issue === 'EXPENSE_PROJECT_MEMBERS_FIXED') throw new Error('项目审批责任已按本轮提交固定，不能加减会签人员。')
  if (!v.canChange) throw new Error('当前任务不能增减会签人员，请先完成委派回交。')
  const comment = reason.trim()
  if (!comment || comment.length > 2000) throw new Error('请填写 1 至 2000 字的变更原因。')
  if (action === 'ADD') {
    if (!v.canAdd || !v.additions.includes(target)) throw new Error('请选择当前可加签的审批人。')
    return { action, targetUser: target, reason: comment, expectedVersion: v.applicationVersion }
  }
  if (!v.pending.some(m => m.taskId === target && m.canRemove)) throw new Error('请选择其他尚未处理且未委派的会签任务。')
  return { action, targetTaskId: target, reason: comment, expectedVersion: v.applicationVersion }
}

/** 在原请求恢复槽清除前校验真实回执，损坏或错配结果继续使用原幂等键恢复。 */
export function validateCountersignReceipt(r: CountersignReceipt, taskId: string, input: CountersignInput) {
  if (!binding(r) || r.taskId !== taskId || r.applicationVersion !== input.expectedVersion + 1 || r.action !== input.action
      || !text(r.targetTaskId) || !text(r.targetUser) || !text(r.auditEventId) || !integer(r.totalBefore, 1)
      || !integer(r.totalAfter, 1) || !integer(r.completed) || r.completed >= Math.min(r.totalBefore, r.totalAfter)
      || r.totalAfter !== r.totalBefore + (input.action === 'ADD' ? 1 : -1)
      || (input.action === 'ADD' ? r.targetUser !== input.targetUser : r.targetTaskId !== input.targetTaskId)) {
    throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '会签变更回执未能核实，请恢复上次操作确认结果。' }
  }
  return r
}

/** 当前面板读取可取消且有总时限，旧身份或旧版本的迟到响应不能重新填入。
 * @author owlzhangfq@gmail.com */
export class CountersignQuery {
  view: CountersignView | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetchView: (taskId: string, signal: AbortSignal) => Promise<CountersignView>) {}
  /** 关闭面板或切换任务立即撤销读取。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.view = null; this.loading = false; this.error = ''
  }
  /** 查询失败不沿用旧名单；刷新本方法不会产生任务变化。 */
  async load(scope: string, task: Task) {
    this.clear()
    if (!scope || !task.taskId) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const result = await Promise.race([
        this.fetchView(task.taskId, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject(new Error('会签名单读取超时，请重试。')) }, 12_000) })
      ])
      if (generation === this.generation) this.view = validateCountersignView(result, task)
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { message?: string })?.message || '无法读取会签名单，请刷新当前任务。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
