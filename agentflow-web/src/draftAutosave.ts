import type { RequestFailure } from './pendingWrites.js'

export const AUTOSAVE_DELAY_MS = 2000

/** 当前编辑会话及完整内容，scope 在切换账号或设计时变化。@author owlzhangfq@gmail.com */
export interface DraftSaveState { scope: string; snapshot: string; eligible: boolean }

/** 只调度草稿保存；失败后暂停，绝不自行重试未确认请求或发布流程。@author owlzhangfq@gmail.com */
export class DraftAutosave {
  scheduled = false
  saving = false
  failure: RequestFailure | null = null
  savedAt: Date | null = null
  private timer: ReturnType<typeof setTimeout> | undefined
  private generation = 0
  private disposed = false

  constructor(private current: () => DraftSaveState, private save: () => Promise<void>) { }

  /** 每次内容或准入条件变化都重新计时，输入法组合与拖动由入口暂停。 */
  observe() {
    this.cancelTimer()
    const captured = this.current()
    if (this.disposed || this.saving || this.failure || !captured.eligible) return
    const generation = this.generation
    this.scheduled = true
    this.timer = setTimeout(() => {
      this.timer = undefined; this.scheduled = false
      const latest = this.current()
      if (this.disposed || generation !== this.generation || latest.scope !== captured.scope
          || latest.snapshot !== captured.snapshot || !latest.eligible) return
      void this.execute(generation, captured.scope)
    }, AUTOSAVE_DELAY_MS)
  }

  /** 手动或自动保存成功后更新状态；编辑内容是否全部保存由调用方的快照决定。 */
  saved() { this.failure = null; this.savedAt = new Date() }

  /** 网络不确定、冲突及校验失败均需人工恢复或修正后手动保存。 */
  pause(error: unknown) {
    this.cancelTimer()
    const failure = error as Partial<RequestFailure>
    this.failure = { status: failure.status ?? 0, code: failure.code ?? 'SAVE_FAILED', message: failure.message ?? '草稿保存失败，请检查后重试。' }
  }

  /** 新设计或新会话不继承旧错误，正在发送的旧请求仍由原调用链收尾。 */
  reset() { this.cancelTimer(); this.generation++; this.failure = null; this.savedAt = null }

  /** 离开组件后取消计时，迟到结果不能恢复调度。 */
  dispose() { this.disposed = true; this.reset() }

  private async execute(generation: number, scope: string) {
    this.saving = true
    try {
      await this.save()
      if (generation === this.generation && this.current().scope === scope) this.saved()
    } catch (error) {
      if (generation === this.generation && this.current().scope === scope) this.pause(error)
    } finally { this.saving = false; this.observe() }
  }

  private cancelTimer() { if (this.timer !== undefined) clearTimeout(this.timer); this.timer = undefined; this.scheduled = false }
}
