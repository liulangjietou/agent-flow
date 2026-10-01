import type { ApiError, Definition } from './api'
import type { DefinitionCatalogFilters, DefinitionCatalogPage } from './definitionCatalog'

const DEFINITION_READ_TIMEOUT_MS = 12_000

/** 完整配置读取约束；恢复设计器时才允许失效偏好回退。@author owlzhangfq@gmail.com */
export interface DefinitionSelectionOptions { publishedOnly?: boolean; startEnabledOnly?: boolean; processKey?: string; version?: number; restoreMissing?: boolean }

/**
 * 列表只读摘要，选中后读取一份完整配置；旧账号或旧选择的结果不能回填。
 * @author owlzhangfq@gmail.com
 */
export class DefinitionSelection {
  definition: Definition | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null

  constructor(private search: (filters: DefinitionCatalogFilters, signal: AbortSignal) => Promise<DefinitionCatalogPage>,
    private get: (id: string, signal: AbortSignal) => Promise<Definition>) {}

  /** 关闭页面或切换身份时清除配置及未完成的读取。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.definition = null; this.loading = false; this.error = ''
  }

  /** 指定 ID 始终读取该版本；只有未指定时才从一条摘要选择初始配置。 */
  async load(scope: string, id = '', options: DefinitionSelectionOptions = {}): Promise<Definition | null> {
    this.clear()
    if (!scope) return null
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timer: ReturnType<typeof setTimeout> | undefined
    const latest = async () => {
      const page = await this.search({ limit: 1, ...(options.publishedOnly || options.startEnabledOnly ? { status: 'PUBLISHED' } : {}),
        ...(options.startEnabledOnly ? { startEnabled: true } : {}),
        ...(options.processKey ? { processKey: options.processKey } : {}), ...(options.version !== undefined ? { version: options.version } : {}) }, controller.signal)
      if (generation !== this.generation || controller.signal.aborted || !page.items.length) return null
      return this.get(page.items[0]!.id, controller.signal)
    }
    const read = async () => {
      if (!id) return latest()
      try { return await this.get(id, controller.signal) }
      catch (cause) {
        if (generation === this.generation && !controller.signal.aborted && options.restoreMissing && (cause as ApiError).status === 404) return latest()
        throw cause
      }
    }
    try {
      const definition = await Promise.race([read(), new Promise<never>((_, reject) => {
        timer = setTimeout(() => { controller.abort(); reject(new Error('读取流程配置超时，请重试。')) }, DEFINITION_READ_TIMEOUT_MS)
      })])
      if (generation !== this.generation) return null
      if (definition && (options.startEnabledOnly && !definition.startEnabled
        || (options.publishedOnly || options.startEnabledOnly) && definition.status !== 'PUBLISHED'
        || options.processKey && definition.key !== options.processKey
        || options.version !== undefined && definition.version !== options.version)) throw new Error('所选流程版本不符合当前用途，请重新选择。')
      this.definition = definition
      return definition
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { message?: string })?.message ?? '读取流程配置失败，请重试。'
      return null
    } finally {
      clearTimeout(timer)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
