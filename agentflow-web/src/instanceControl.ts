/** 本轮实例状态与当前身份的操作能力，不承载表单或引擎变量。@author owlzhangfq@gmail.com */
export interface InstanceControlView {
  applicationId: string; roundNo: number; applicationVersion: number
  state: 'RUNNING' | 'PAUSED' | 'ENDED' | 'UNAVAILABLE'
  pausedAt?: string | null; canPause: boolean; canResume: boolean; canTerminate: boolean
}
/** @author owlzhangfq@gmail.com */
export type InstanceControlAction = 'pause' | 'resume' | 'terminate'
/** @author owlzhangfq@gmail.com */
export interface InstanceControlInput { expectedVersion: number; reason: string }
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const time = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && Number.isFinite(Date.parse(value))

/** 读取必须精确绑定申请、轮次与一致的操作能力，异常投影不能开放操作。 */
export function validateInstanceView(value: InstanceControlView, id: string, round: number) {
  if (!value || value.applicationId !== id || value.roundNo !== round || !positive(value.applicationVersion)
      || !['RUNNING', 'PAUSED', 'ENDED', 'UNAVAILABLE'].includes(value.state)
      || typeof value.canPause !== 'boolean' || typeof value.canResume !== 'boolean' || typeof value.canTerminate !== 'boolean'
      || value.canTerminate && !['RUNNING', 'PAUSED'].includes(value.state)
      || value.canPause && value.state !== 'RUNNING' || value.canResume && (value.state !== 'PAUSED' || !time(value.pausedAt))
      || value.pausedAt != null && (value.state !== 'PAUSED' || !time(value.pausedAt))) {
    throw new Error('运行状态与当前轮次不一致，请重新读取。')
  }
  return value
}

/** 确认必须使用当前详情版本、当前能力与明确原因，不能传入新期限或审批结论。 */
export function instanceControlInput(view: InstanceControlView, version: number, action: InstanceControlAction, reason: string): InstanceControlInput {
  if (!view || view.applicationVersion !== version || !positive(version)
      || !(action === 'pause' ? view.state === 'RUNNING' && view.canPause
        : action === 'resume' ? view.state === 'PAUSED' && view.canResume
        : action === 'terminate' && ['RUNNING', 'PAUSED'].includes(view.state) && view.canTerminate)) {
    throw new Error('当前状态已变化，请刷新申请详情后核对。')
  }
  const comment = reason.trim()
  if (!comment || comment.length > 2000) throw new Error('请填写 1 至 2000 字的操作原因。')
  return { expectedVersion: version, reason: comment }
}

/** 在原请求恢复槽清除前校验回执；成功响应损坏时仍保留原幂等键。 */
export function validateInstanceReceipt(value: InstanceControlView, id: string, round: number, action: InstanceControlAction, input: InstanceControlInput) {
  try {
    validateInstanceView(value, id, round)
    if (value.applicationVersion !== input.expectedVersion + 1 || (action === 'pause'
      ? value.state !== 'PAUSED' || !value.canResume || !time(value.pausedAt)
      : action === 'resume' ? value.state !== 'RUNNING' || !value.canPause
      : action !== 'terminate' || value.state !== 'ENDED')) throw new Error('Invalid instance receipt')
    return value
  } catch {
    throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '运行操作回执未能核实，请恢复上次操作确认结果。' }
  }
}

/** 按身份、申请和轮次读取状态，切换或失败后不沿用旧操作权限。@author owlzhangfq@gmail.com */
export class InstanceControlQuery {
  value: InstanceControlView | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetchView: (id: string, round: number, signal: AbortSignal) => Promise<InstanceControlView>) {}
  /** 关闭面板或上下文变化时取消旧读取。 */
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = '' }
  /** 获取原绑定实例；读取失败时保留刷新入口，不能推断为运行中。 */
  async load(scope: string, id: string, round: number) {
    this.clear()
    if (!scope || !id || !positive(round)) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([this.fetchView(id, round, controller.signal), new Promise<never>((_, reject) => {
        timeout = setTimeout(() => { controller.abort(); reject(new Error('运行状态读取超时，请重试。')) }, 10_000)
      })])
      if (generation !== this.generation) return
      this.value = validateInstanceView(value, id, round)
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as Error)?.message || '运行状态读取失败，请刷新后核对。'
    } finally {
      if (timeout) clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
