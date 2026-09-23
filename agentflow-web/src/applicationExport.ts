import type { ApplicationSearchFilters } from './applicationSearch'

export const workbookType = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
export type ApplicationExportFilters = Omit<ApplicationSearchFilters, 'cursor' | 'limit'>

/**
 * 导出只保留本次账号与筛选的完整文件；取消、切换和失败后不留下旧文件。
 * @author owlzhangfq@gmail.com
 */
export class ApplicationExportQuery {
  file: Blob | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null

  constructor(private fetchFile: (filters: ApplicationExportFilters, signal: AbortSignal) => Promise<Blob>) {}

  /** 编辑筛选、切换身份或离开页面时丢弃文件及迟到响应。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.file = null; this.loading = false; this.error = ''
  }

  /** 服务端一次生成完整匹配文件，不循环拼接变化中的列表分页。 */
  async generate(scope: string, filters: ApplicationExportFilters) {
    if (!scope || this.loading) return
    this.clear()
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const file = await Promise.race([
        this.fetchFile({ ...filters }, controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject({ message: '生成超时，请缩小筛选后重试。' }) }, 60_000) })
      ])
      if (generation === this.generation) this.file = file
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { message?: string })?.message ?? '导出失败，请重试。'
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
