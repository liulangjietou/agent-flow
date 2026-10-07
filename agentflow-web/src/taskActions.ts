import type { Task, TaskAction, TaskActionInput } from './api'
/** 外部业务面板仅预填退回意见，仍由当前待办明确确认。 */
export interface TaskReturnDraft { scopeKey: string; taskId: string; expectedVersion: number; comment: string }

export const taskActionLabels: Record<TaskAction, string> = {
  APPROVE: '批准申请', RETURN: '退回申请', REJECT: '驳回申请', TRANSFER: '转交任务',
  DELEGATE: '委派任务', RESOLVE: '填写意见并回交', CLAIM: '领取任务', RELEASE: '释放任务'
}
export const needsRecipient = (action: TaskAction) => action === 'TRANSFER' || action === 'DELEGATE'
export const needsComment = (action: TaskAction) => ['RETURN', 'REJECT', 'RESOLVE'].includes(action)
export const isApprovalDecision = (action: TaskAction) => ['APPROVE', 'RETURN', 'REJECT'].includes(action)

/** 通用决定与财务专用操作共用身份选择，显式失效依据不能回退为本人权限。 */
export function selectedApprovalProxy(authority: Pick<Task, 'canActDirectly' | 'proxyOptions'>, proxyId = '') {
  const options = authority.proxyOptions ?? []
  let selected = proxyId
  if (!selected && authority.canActDirectly === false) {
    if (!options.length) throw new Error('当前代理已不可用，请刷新任务。')
    if (options.length > 1) throw new Error('请选择本次代理的原审批人。')
    selected = options[0].proxyId
  }
  if (!selected) return undefined
  const option = options.find(value => value.proxyId === selected), now = Date.now()
  // 页面停留期间也可能到期；此提示不能替代服务器锁内授权。
  if (!option || !(Date.parse(option.startsAt) <= now && now < Date.parse(option.endsAt))) {
    throw new Error('所选代理已失效或当前不可用，请刷新任务。')
  }
  return option
}

/** 领取与释放只增加一个版本；回执无法绑定原命令时保留原幂等键供用户恢复。 */
export function validateTaskAssignmentReceipt(value: unknown, taskId: string, input: TaskActionInput) {
  if (input.action !== 'CLAIM' && input.action !== 'RELEASE') return
  const receipt = value as { taskId?: string; action?: string; applicationStatus?: string; version?: number } | null
  if (!receipt || receipt.taskId !== taskId || receipt.action !== input.action
      || receipt.applicationStatus !== 'IN_APPROVAL' || receipt.version !== input.expectedVersion + 1) {
    throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '任务办理回执未能核实，请恢复上次操作确认结果。' }
  }
}

/** 只按当前任务快照提交，回交接收人由服务端解析，不能通过表单伪造。 */
export function taskActionInput(task: Task, action: TaskAction, comment: string, target: string, recipients: string[], proxyId = ''): TaskActionInput {
  if (!task.allowedActions?.includes(action)) throw new Error('任务当前不允许此操作，请刷新后重新选择。')
  if (needsComment(action) && !comment.trim()) throw new Error('请填写处理意见。')
  if (needsRecipient(action) && (!target || !recipients.includes(target))) throw new Error('请选择可用的接收人。')
  const selected = selectedApprovalProxy(task, proxyId)
  if (selected && !isApprovalDecision(action)) throw new Error('代理不支持领取、转交或委派，请选择审批决定。')
  return { action, expectedVersion: task.version, comment: comment.trim() || undefined,
    targetUser: needsRecipient(action) ? target : undefined, ...(selected ? { proxyId: selected.proxyId } : {}) }
}

/**
 * 人员列表只属于当前任务操作面板，切换任务、账号和取消操作时撤销请求。
 * @author owlzhangfq@gmail.com
 */
export class TaskRecipientsQuery {
  users: string[] = []
  error = ''
  loading = false
  private generation = 0
  private controller: AbortController | null = null

  constructor(private fetchUsers: (taskId: string, signal: AbortSignal) => Promise<string[]>) {}

  /** 清除名单，避免其他账号或任务的名单被误用。 */
  clear() {
    this.generation++
    this.controller?.abort(); this.controller = null
    this.users = []; this.error = ''; this.loading = false
  }

  /** 执行带取消和总时限的目录查询。 */
  async load(scope: string, taskId: string) {
    this.clear()
    if (!scope || !taskId) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const users = await Promise.race([
        this.fetchUsers(taskId, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '接收人查询超时，请重试。' }) }, 12_000) })
      ])
      if (generation === this.generation) this.users = users
    } catch (error) {
      if (generation === this.generation) this.error = (error as { message?: string })?.message ?? '无法读取接收人，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.controller = null; this.loading = false }
    }
  }
}
