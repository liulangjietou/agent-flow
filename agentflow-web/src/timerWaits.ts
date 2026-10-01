/** 当前轮次的原生等待事实，不携带表单或引擎变量。@author owlzhangfq@gmail.com */
export interface TimerEntry { jobId: string; nodeId: string; nodeName: string; executionId: string; dueAt: string; state: 'WAITING' | 'FAILED' | 'SUSPENDED'; errorCode?: string | null; canRetry: boolean }
/** @author owlzhangfq@gmail.com */
export interface TimerView { applicationId: string; roundNo: number; applicationVersion: number; items: TimerEntry[] }
/** @author owlzhangfq@gmail.com */
export interface TimerRetryInput { expectedVersion: number; reason: string }
/** @author owlzhangfq@gmail.com */
export interface TimerReceipt { applicationId: string; roundNo: number; applicationVersion: number; jobId: string; nodeId: string; applicationStatus: 'IN_APPROVAL' | 'APPROVED' }
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0

/** 确认时绑定当前读取版本和失败任务，不允许页面传入新到期时间。 */
export function timerRetryInput(view: TimerView, jobId: string, reason: string): TimerRetryInput {
  const entry = view.items.find(item => item.jobId === jobId)
  if (!positive(view.applicationVersion) || !entry || entry.state !== 'FAILED' || !entry.canRetry) throw new Error('该等待当前不能重试，请刷新后核对。')
  const comment = reason.trim()
  if (!comment || comment.length > 2000) throw new Error('请填写 1 至 2000 字的重试原因。')
  return { expectedVersion: view.applicationVersion, reason: comment }
}

/** 写回执损坏或串单时保留原请求恢复槽，不能按成功结果丢弃原幂等键。 */
export function validateTimerReceipt(value: TimerReceipt, applicationId: string, roundNo: number, jobId: string, input: TimerRetryInput) {
  const increment = value?.applicationStatus === 'APPROVED' ? 2 : value?.applicationStatus === 'IN_APPROVAL' ? 1 : 0
  if (!value || !increment || value.applicationId !== applicationId || value.roundNo !== roundNo || value.jobId !== jobId
      || !text(value.nodeId) || !positive(value.applicationVersion) || value.applicationVersion !== input.expectedVersion + increment) {
    throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '重试回执未能核实，请恢复上次操作确认结果。' }
  }
  return value
}

/** 账号、申请和轮次变化后撤销旧读取，不展示过期的管理员恢复权限。@author owlzhangfq@gmail.com */
export class TimerWaitQuery {
  value: TimerView | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetchView: (id: string, round: number, signal: AbortSignal) => Promise<TimerView>) {}
  /** 清除状态和旧读取，适用于关闭面板和切换身份。 */
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = '' }
  /** 返回最新绑定的等待事实，读取失败时不保留旧操作入口。 */
  async load(scope: string, id: string, round: number) {
    this.clear()
    if (!scope || !id || !positive(round)) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([this.fetchView(id, round, controller.signal), new Promise<never>((_, reject) => {
        timeout = setTimeout(() => { controller.abort(); reject(new Error('等待状态读取超时，请重试。')) }, 10_000)
      })])
      if (generation !== this.generation) return
      if (!value || value.applicationId !== id || value.roundNo !== round || !positive(value.applicationVersion) || !Array.isArray(value.items)
          || new Set(value.items.map(item => item.jobId)).size !== value.items.length || value.items.some(item =>
            !text(item.jobId) || !text(item.executionId) || !text(item.nodeId) || !text(item.nodeName) || !text(item.dueAt)
            || !Number.isFinite(Date.parse(item.dueAt)) || !['WAITING', 'FAILED', 'SUSPENDED'].includes(item.state)
            || typeof item.canRetry !== 'boolean' || item.canRetry && item.state !== 'FAILED')) throw new Error('等待状态与当前轮次不一致，请重新读取。')
      this.value = value
    } catch (failure) {
      if (generation === this.generation) this.error = (failure as Error)?.message || '等待状态读取失败，请重试。'
    } finally {
      if (timeout) clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
