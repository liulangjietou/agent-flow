import type { Graph, ValidationResult } from './api'
import type { FormSchema } from './formSchema'

/** 校验原始设计快照，不让迟到结果覆盖后续编辑、流程切换或账号切换。@author owlzhangfq@gmail.com */
export class DesignerValidation {
  result: ValidationResult | null = null
  error = ''
  loading = false
  private generation = 0
  private controller: AbortController | null = null

  /** 注入只读校验请求；超时同样使本次结果失效。 */
  constructor(private fetch: (graph: Graph, form: FormSchema | null, signal: AbortSignal, key?: string) => Promise<ValidationResult>, private timeoutMs = 12_000) {}

  /** 编辑、切换和卸载立即清除旧高亮，并取消正在执行的读取。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.result = null; this.error = ''; this.loading = false
  }

  /** 仅返回本次仍有效的结果；结果未知时不能允许发布。 */
  async run(graph: Graph, form: FormSchema | null, key?: string) {
    this.clear()
    const generation = this.generation, controller = new AbortController()
    const input = JSON.parse(JSON.stringify({ graph, form })) as { graph: Graph; form: FormSchema | null }
    this.controller = controller; this.loading = true
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const result = await Promise.race([
        this.fetch(input.graph, input.form, controller.signal, key),
        new Promise<never>((_, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('校验超时，请重试。')) }, this.timeoutMs) })
      ])
      if (generation !== this.generation) return
      this.result = result
      return result
    } catch (error) {
      if (generation === this.generation) this.error = (error as Error).message || '校验失败，请重试。'
    } finally {
      clearTimeout(timer)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
