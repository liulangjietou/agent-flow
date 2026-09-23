import type { Task, TaskAction, TaskActionInput } from './api'

export const taskActionLabels: Record<TaskAction, string> = {
  APPROVE: '批准申请', RETURN: '退回申请', REJECT: '驳回申请', TRANSFER: '转交任务',
  DELEGATE: '委派任务', RESOLVE: '填写意见并回交', CLAIM: '领取任务', RELEASE: '释放任务'
}
export const needsRecipient = (action: TaskAction) => action === 'TRANSFER' || action === 'DELEGATE'
export const needsComment = (action: TaskAction) => ['RETURN', 'REJECT', 'RESOLVE'].includes(action)

/** 只按当前任务快照提交，回交接收人由服务端解析，不能通过表单伪造。 */
export function taskActionInput(task: Task, action: TaskAction, comment: string, target: string, recipients: string[]): TaskActionInput {
  if (!task.allowedActions?.includes(action)) throw new Error('任务当前不允许此操作，请刷新后重新选择。')
  if (needsComment(action) && !comment.trim()) throw new Error('请填写处理意见。')
  if (needsRecipient(action) && (!target || !recipients.includes(target))) throw new Error('请选择可用的接收人。')
  return { action, expectedVersion: task.version, comment: comment.trim() || undefined, targetUser: needsRecipient(action) ? target : undefined }
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
