import type { SystemCheckReport } from './api'

/**
 * 检查结果仅属于当前页面与账号；刷新、卸载或切换账号时撤销前次查询。
 * @author owlzhangfq@gmail.com
 */
export class SystemChecksQuery {
  report: SystemCheckReport | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null

  constructor(private fetchReport: (signal: AbortSignal) => Promise<SystemCheckReport>) {}

  /** 立即清理旧结果，迟到响应即使没有响应 abort 也不能回填。 */
  clear() {
    this.generation++
    this.controller?.abort()
    this.controller = null
    this.report = null
    this.error = ''
    this.loading = false
  }

  /** 请求有总时限，不把上次成功结果继续显示为当前状态。 */
  async load(scope: string) {
    this.clear()
    if (!scope) return
    const generation = this.generation
    const controller = new AbortController()
    this.controller = controller
    this.loading = true
    let timedOut = false
    const timeout = setTimeout(() => { timedOut = true; controller.abort() }, 12_000)
    try {
      const report = await this.fetchReport(controller.signal)
      if (generation === this.generation && !timedOut) this.report = report
    } catch (error) {
      if (generation === this.generation) this.error = timedOut ? '自检请求超时，请检查服务后重试。' :
        (error as { message?: string })?.message ?? '自检请求失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
