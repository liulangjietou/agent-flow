/** 申请轮次的只读引擎图，不包含表单值或审批人规则。@author owlzhangfq@gmail.com */
export interface RoundDiagram {
  applicationId: string; roundNo: number; definitionVersion: number; status: string; observedAt: string
  nodes: DiagramNode[]; edges: { id: string; source: string; target: string; defaultBranch: boolean }[]
}
/** 节点离开不表示审批通过，会签任务数量取自当前引擎。@author owlzhangfq@gmail.com */
export interface DiagramNode {
  id: string; name: string; type: string; state: 'NOT_REACHED' | 'ACTIVE' | 'LEFT'; activeTasks: number
  firstEnteredAt?: string | null; lastLeftAt?: string | null
}
/** 隔离账号和轮次请求，刷新失败时不显示过期运行状态。@author owlzhangfq@gmail.com */
export class RoundDiagramQuery {
  value: RoundDiagram | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private readonly fetchDiagram: (id: string, round: number, signal?: AbortSignal) => Promise<RoundDiagram>) {}
  /** 清除图并取消迟到的请求。 */
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = '' }
  /** 只请求真实已提交的轮次，旧请求的成功或失败都不能覆盖新上下文。 */
  async load(scope: string, id: string, round: number) {
    this.clear()
    if (!scope || !id || !Number.isInteger(round) || round < 1) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([
        this.fetchDiagram(id, round, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '流程图加载超时，请重试。' }) }, 12_000) })
      ])
      if (generation === this.generation) this.value = value
    } catch (cause) {
      if (generation !== this.generation) return
      const failure = cause as { status?: number; message?: string }
      this.error = failure.status === 404 || failure.status === 403
        ? '本轮流程图不可用或无权查看，请查看审批轨迹或稍后重试。'
        : failure.status === 401 ? '登录已失效，请重新登录。' : failure.message ?? '流程图加载失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
