import { configurationError } from './expenseConfiguration.js'

/** 配置读取绑定当前页面代次；切换、超时或失败后不保留可操作的旧事实。 */
export class ConfigurationRead<T> {
  value: T | null = null; loading = false; error = ''; status = 0
  private generation = 0
  private controller: AbortController | null = null
  constructor(private describeError: (cause: unknown) => string = configurationError, private timeoutMessage = '费用配置读取超时，请重试。') { }
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = ''; this.status = 0 }
  async load(fetchValue: (signal: AbortSignal) => Promise<T>): Promise<T | null> {
    this.clear(); const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([fetchValue(controller.signal), new Promise<never>((_, reject) => {
        timer = setTimeout(() => { controller.abort(); reject({ message: this.timeoutMessage }) }, 12_000)
      })])
      if (generation !== this.generation) return null
      this.value = value; return value
    } catch (cause) {
      if (generation === this.generation) { this.error = this.describeError(cause); this.status = (cause as { status?: number }).status ?? 0 }
      return null
    } finally { clearTimeout(timer); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }
}
