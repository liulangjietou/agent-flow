/** 所选版本的真实轮次证据，不存放表单正文或审批令牌。@author owlzhangfq@gmail.com */
export interface FirstWorkflowEvidence {
  applicationId: string; businessNo: string; title: string; roundNo: number; status: string
  submittedAt: string; completedAt?: string
}
/** 首次使用进度取自服务端业务事实。@author owlzhangfq@gmail.com */
export interface FirstWorkflowReport {
  checkedAt: string
  definition?: { id: string; key: string; name: string; version: number; status: string }
  submittedRounds: number; approvedRounds: number; unrecordedHistoricalRounds: number
  latestSubmission?: FirstWorkflowEvidence; latestApproval?: FirstWorkflowEvidence
}

const preferenceKey = (scope: string, name: string) => `agentflow.firstWorkflow.${scope}.${name}`

/** 只保存界面偏好，失败不阻塞现有工作台；每个租户账号独立。 */
export function rememberGuideSelection(scope: string, id: string, storage?: Pick<Storage, 'setItem'>) {
  if (!scope) return
  try { (storage ?? localStorage).setItem(preferenceKey(scope, 'definition'), id) } catch { /* 禁用存储仍可继续操作。 */ }
}
export function guideSelection(scope: string, storage?: Pick<Storage, 'getItem'>): string {
  try { return (storage ?? localStorage).getItem(preferenceKey(scope, 'definition')) ?? '' } catch { return '' }
}
export function guideHidden(scope: string, storage?: Pick<Storage, 'getItem'>): boolean {
  try { return (storage ?? localStorage).getItem(preferenceKey(scope, 'hidden')) === 'true' } catch { return false }
}
export function hideGuide(scope: string, hidden: boolean, storage?: Pick<Storage, 'setItem'>) {
  if (!scope) return
  try { (storage ?? localStorage).setItem(preferenceKey(scope, 'hidden'), String(hidden)) } catch { /* 偏好保存失败不会改变业务状态。 */ }
}

/** 仅依据持久化事实计算步骤，模拟或浏览示例不能把运行步骤标为完成。 */
export function workflowSteps(report: FirstWorkflowReport) {
  return [Boolean(report.definition), Boolean(report.definition && report.definition.version > 0), report.submittedRounds > 0, report.approvedRounds > 0]
}

/**
 * 切换账号或流程即清理旧证据，失败不保留上次的成功进度。
 * @author owlzhangfq@gmail.com
 */
export class FirstWorkflowQuery {
  report: FirstWorkflowReport | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetchReport: (id: string, signal: AbortSignal) => Promise<FirstWorkflowReport>) {}

  /** 撤销当前查询及所有迟到响应。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.report = null; this.error = ''; this.loading = false
  }

  /** 查询目标保持固定，只有本次请求能更新当前页面。 */
  async load(scope: string, id: string) {
    this.clear()
    if (!scope) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timedOut = false
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, 12_000)
    try {
      const report = await this.fetchReport(id, controller.signal)
      if (generation === this.generation) {
        if (timedOut) this.error = '流程进度查询超时，请重试。'
        else this.report = report
      }
    } catch (cause) {
      if (generation === this.generation) this.error = timedOut ? '流程进度查询超时，请重试。'
        : (cause as { message?: string })?.message ?? '无法读取流程进度，请重试。'
    } finally {
      clearTimeout(timer)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
