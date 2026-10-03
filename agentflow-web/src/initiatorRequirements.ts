/** 原发布版本的选人要求；只读结果不代表依赖当前可启动或已经获得提交授权。@author owlzhangfq@gmail.com */
export interface InitiatorRequirementsView { processKey: string; definitionVersion: number; appointmentRequired: boolean }
export interface InitiatorRequirementsTarget { kind: 'definition' | 'application'; id: string; processKey: string; definitionVersion: number }
type ReadRequirements = (id: string, signal: AbortSignal) => Promise<unknown>
const READ_TIMEOUT_MS = 12_000

/** 新发起和原申请重提共用读取状态；未知、失败和旧身份结果不能被解释为任职可选。 */
export class InitiatorRequirements {
  required: boolean | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null

  constructor(private definition: ReadRequirements, private application: ReadRequirements) {}

  /** 切换来源、关闭或退出账号立即清除旧提示并取消读取。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.required = null; this.loading = false; this.error = ''
  }

  /** 查询当前选中来源，复核响应的原流程标识和版本，读取不携带表单或任职资料。 */
  async load(scope: string, target?: InitiatorRequirementsTarget | null) {
    this.clear()
    if (!scope || !target) return
    const generation = this.generation, request = new AbortController(), expected = { ...target }
    this.controller = request; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const read = expected.kind === 'application' ? this.application : this.definition
      const value = await Promise.race([read(expected.id, request.signal), new Promise<never>((_, reject) => {
        timeout = setTimeout(() => { request.abort(); reject(new Error('读取发起任职要求超时，请重试。')) }, READ_TIMEOUT_MS)
      })]) as Partial<InitiatorRequirementsView> | null
      if (generation !== this.generation) return
      if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== 3
        || value.processKey !== expected.processKey || value.definitionVersion !== expected.definitionVersion
        || typeof value.appointmentRequired !== 'boolean') throw new Error('发起任职要求与当前版本不一致，请重新读取。')
      this.required = value.appointmentRequired
    } catch {
      if (generation === this.generation) this.error = '无法确认当前版本及子流程的发起任职要求，请重试读取；仍可保存草稿。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }

  /** 提交前单点检查；保存草稿不受任职要求影响，真正提交继续由后台校验。 */
  submissionError(appointmentId: string): string {
    if (this.loading) return '正在核对发起任职要求，请稍后提交。'
    if (this.required === null) return this.error || '请先读取当前版本的发起任职要求。'
    return this.required && !appointmentId ? '此流程或其子流程需要发起任职，请明确选择本次任职。' : ''
  }
}
