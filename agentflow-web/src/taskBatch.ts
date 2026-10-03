import type { PendingTaskItem, Task, TaskActionInput } from './api'
import { isDefinitiveWriteFailure } from './pendingWrites.js'

export const MAX_TASK_BATCH_SIZE = 20
export type TaskBatchAction = 'CLAIM' | 'RELEASE'
export type TaskBatchState = 'READY' | 'UNAVAILABLE' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'SKIPPED'
export interface TaskBatchRow {
  taskId: string; applicationId: string; businessNo: string; title: string; taskName: string
  state: TaskBatchState; version?: number; message: string
}

/**
 * 只编排已存在的领取与释放用例；先读取逐笔快照，确认后固定版本发送。
 * 原请求的保存和恢复仍由 PendingWrites 负责，批次不自动继续或重试。
 * @author owlzhangfq@gmail.com
 */
export class TaskBatch {
  rows: TaskBatchRow[] = []
  action: TaskBatchAction = 'CLAIM'
  stage: 'EMPTY' | 'CHECKING' | 'PREVIEW' | 'EXECUTING' | 'DONE' = 'EMPTY'
  private generation = 0
  private reading: AbortController | null = null

  constructor(private read: (id: string, signal: AbortSignal) => Promise<Task>,
              private write: (id: string, input: TaskActionInput) => Promise<unknown>) { }

  /** 页面离开或身份切换时取消读取并停止后续发送，已发出的原请求仍可独立恢复。 */
  clear() {
    this.generation++
    this.reading?.abort(); this.reading = null
    this.rows = []; this.stage = 'EMPTY'
  }

  /** 列表只有摘要；逐项重读当前权限及版本，不用摘要直接构造写命令。 */
  async prepare(action: TaskBatchAction, items: PendingTaskItem[]) {
    if (this.stage === 'EXECUTING' || this.rows.some(row => row.state === 'UNKNOWN')) throw new Error('请先确认原操作结果。')
    if (!['CLAIM', 'RELEASE'].includes(action)) throw new Error('批量操作仅支持领取或释放任务。')
    if (!items.length || items.length > MAX_TASK_BATCH_SIZE || new Set(items.map(item => item.taskId)).size !== items.length) {
      throw new Error(`请选择 1 至 ${MAX_TASK_BATCH_SIZE} 项不同任务。`)
    }
    this.clear(); this.action = action; this.stage = 'CHECKING'
    const generation = this.generation
    this.rows = items.map(({ taskId, applicationId, businessNo, title, taskName }) =>
      ({ taskId, applicationId, businessNo, title, taskName, state: 'SKIPPED', message: '尚未核对' }))
    for (const row of this.rows) {
      const controller = new AbortController(); this.reading = controller
      let timeout: ReturnType<typeof setTimeout> | undefined
      try {
        const task = await Promise.race([
          this.read(row.taskId, controller.signal),
          new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject(new Error('读取任务超时，请重新选择。')) }, 12_000) })
        ])
        if (generation !== this.generation) return
        if (task.taskId !== row.taskId || task.applicationId !== row.applicationId || !Number.isSafeInteger(task.version)
            || task.version < 0 || !Array.isArray(task.allowedActions) || !task.allowedActions.includes(action)) {
          throw new Error('任务已变化或不允许此操作，请刷新后重新选择。')
        }
        row.version = task.version; row.taskName = task.taskName; row.state = 'READY'; row.message = '已核对，等待确认'
      } catch (error) {
        if (generation !== this.generation) return
        row.state = 'UNAVAILABLE'; row.message = this.message(error)
      } finally { clearTimeout(timeout) }
    }
    this.reading = null; this.stage = 'PREVIEW'
  }

  /** 每笔独立成功或失败；结果未知立即停发，不能把后续操作接到原请求恢复上。 */
  async execute(reason: string) {
    if (this.stage === 'EXECUTING') return
    if (this.stage !== 'PREVIEW' || !this.rows.some(row => row.state === 'READY')) throw new Error('请先核对可办理的任务。')
    const comment = reason.trim()
    if (!comment || comment.length > 500) throw new Error('请填写 1 至 500 字的办理说明。')
    const generation = this.generation, action = this.action
    this.stage = 'EXECUTING'
    for (const row of this.rows) {
      if (row.state !== 'READY') continue
      row.state = 'RUNNING'; row.message = '正在确认结果'
      try {
        await this.write(row.taskId, { action, expectedVersion: row.version!, comment })
        if (generation !== this.generation) return
        row.state = 'SUCCEEDED'; row.message = action === 'CLAIM' ? '已领取' : '已释放'
      } catch (error) {
        if (generation !== this.generation) return
        row.message = this.message(error)
        if (isDefinitiveWriteFailure(error)) { row.state = 'FAILED'; continue }
        row.state = 'UNKNOWN'
        for (const remaining of this.rows) if (remaining.state === 'READY') {
          remaining.state = 'SKIPPED'; remaining.message = '前一项结果待确认，未发送'
        }
        break
      }
    }
    this.stage = 'DONE'
  }

  private message(error: unknown) { return (error as { message?: string })?.message || '操作未完成，请重新核对。' }
}
