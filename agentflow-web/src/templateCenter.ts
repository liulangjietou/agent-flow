import type { ProcessTemplate, TemplateCopyInput } from './api'

/**
 * 目录重载时先清空租户副本，只有当前账号的最新请求可以更新页面。
 * @author owlzhangfq@gmail.com
 */
export class TemplateCatalog {
  templates: ProcessTemplate[] = []
  loading = false
  error = ''
  private generation = 0
  private scope = ''

  constructor(private fetchTemplates: () => Promise<ProcessTemplate[]>) {}

  /** 卸载或账号变化后，迟到响应不能恢复旧目录。 */
  clear() {
    this.generation++
    this.scope = ''
    this.templates = []
    this.loading = false
    this.error = ''
  }

  /** 不保留重载前的副本列表，失败时展示明确的重试入口。 */
  async load(scope: string) {
    this.clear()
    if (!scope) return
    this.scope = scope
    this.loading = true
    const generation = this.generation
    const current = () => this.generation === generation && this.scope === scope
    try {
      const templates = await this.fetchTemplates()
      if (current()) this.templates = templates
    } catch (error) {
      if (current()) this.error = (error as { message?: string })?.message ?? '模板目录加载失败，请重试。'
    } finally {
      if (current()) this.loading = false
    }
  }
}

/** 复制目标的就地提示；模板内容与最终有效性仍由服务端校验。 */
export function validateTemplateCopy(input: TemplateCopyInput): Partial<Record<keyof TemplateCopyInput, string>> {
  const errors: Partial<Record<keyof TemplateCopyInput, string>> = {}
  if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(input.key)) errors.key = '以英文字母开头，仅含字母、数字、下划线或短横线，最多 64 个字符。'
  if (!input.name.trim() || input.name.length > 128) errors.name = '请填写流程名称，最多 128 个字符。'
  if (!Number.isInteger(input.templateVersion) || input.templateVersion < 1) errors.templateVersion = '模板版本无效，请重新加载目录。'
  return errors
}
