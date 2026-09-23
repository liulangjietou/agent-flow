export interface ConfirmationRequest { id: number; confirmLabel: string }

/**
 * 只保管一项待确认意图；取消、会话失效与卸载均不能放行后续操作。
 * @author owlzhangfq@gmail.com
 */
export class UnsavedConfirmation {
  active: ConfirmationRequest | null = null
  private pending: { id: number; resolve: (accepted: boolean) => void } | null = null
  private sequence = 0
  private disposed = false

  /** 重复点击直接拒绝，不替换原对话框，也不共享确认结果。 */
  async confirm(confirmLabel: string, allowed: () => boolean, required = true): Promise<boolean> {
    if (this.disposed || this.pending || !allowed()) return false
    const accepted = required ? await new Promise<boolean>(resolve => {
      const id = ++this.sequence
      this.pending = { id, resolve }
      this.active = { id, confirmLabel }
    }) : true
    return accepted && !this.disposed && allowed()
  }

  /** 答复绑定当前请求，忽略旧组件卸载或重复点击留下的延迟事件。 */
  answer(id: number, accepted: boolean) {
    if (this.pending?.id !== id) return
    const resolve = this.pending.resolve
    this.pending = null
    this.active = null
    resolve(accepted)
  }

  /** 会话变化时关闭对话框并取消原意图。 */
  cancel() {
    if (this.pending) this.answer(this.pending.id, false)
  }

  /** 页面卸载后连已答复但尚未恢复执行的 Promise 也必须失效。 */
  dispose() {
    this.disposed = true
    this.cancel()
  }
}
