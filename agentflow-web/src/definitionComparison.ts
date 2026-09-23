import type { ApiError, ComparisonInput, ComparisonResult, Definition } from './api'

/**
 * 版本比较属于当前设计快照；切换基线或编辑内容后不得展示旧差异。
 * @author owlzhangfq@gmail.com
 */
export class DefinitionComparisonQuery {
  result: ComparisonResult | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null

  constructor(private fetchComparison: (id: string, input: ComparisonInput, signal: AbortSignal) => Promise<ComparisonResult>) {}

  /** 取消旧请求并清除其结果。 */
  clear() {
    this.generation++
    this.controller?.abort()
    this.controller = null
    this.result = null
    this.error = ''
    this.loading = false
  }

  /** 仅接收属于当前基线和设计快照的比较响应。 */
  async run(id: string, input: ComparisonInput) {
    this.clear()
    const generation = this.generation
    const controller = new AbortController()
    this.controller = controller
    this.loading = true
    let timedOut = false
    const timeout = setTimeout(() => { timedOut = true; controller.abort() }, 15_000)
    try {
      const result = await this.fetchComparison(id, JSON.parse(JSON.stringify(input)) as ComparisonInput, controller.signal)
      if (generation === this.generation) {
        if (timedOut) this.error = '比较请求超时，请重试。'
        else this.result = result
      }
    } catch (error) {
      if (generation === this.generation) {
        const failure = error as ApiError
        const messages: Record<string, string> = {
          COMPARISON_KEY_MISMATCH: '当前流程标识与基线不同，请重新选择同一流程的版本。',
          COMPARISON_BASELINE_REQUIRED: '基线必须是已发布版本，请刷新流程列表。',
          COMPARISON_ID_AMBIGUOUS: '节点或连线标识重复，无法可靠比较，请先修正标识。'
        }
        this.error = timedOut ? '比较请求超时，请重试。' : own(messages, failure.code) ?? failure.message ?? '版本比较失败，请重试。'
      }
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}

/** 只提供当前 key 的不可变发布版本，不把其他流程或草稿作为基线。 */
export function comparisonBaselines(definitions: Definition[], key: string) {
  return definitions.filter(definition => definition.status === 'PUBLISHED' && definition.key === key.trim())
    .sort((a, b) => b.version - a.version)
}

const labels: Record<string, string> = {
  entity: '整体配置', name: '名称', type: '类型', label: '字段名称', key: '字段标识', id: '标识',
  source: '起点', target: '终点', condition: '条件', defaultBranch: '默认分支', branchOrder: '条件求值顺序',
  schemaBinding: '绑定申请表单', schemaVersion: '表单格式版本', fieldOrder: '字段填写顺序', required: '必填',
  helpText: '填写提示', maxLength: '最多字符数', minimum: '最小值', maximum: '最大值', options: '可选项',
  properties: '节点属性', assigneeRule: '审批人规则', 'properties.assigneeRule': '审批人规则',
  x: '水平位置', y: '垂直位置', 'properties.x': '水平位置', 'properties.y': '垂直位置', value: '保存值'
}
const types: Record<string, string> = { START: '开始', END: '结束', USER_TASK: '人工审批', EXCLUSIVE_GATEWAY: '条件分支',
  SERVICE_TASK: '服务任务', PARALLEL_GATEWAY: '并行网关', TEXT: '单行文本', TEXTAREA: '多行文本', NUMBER: '数字', DATE: '日期', SELECT: '单选', BOOLEAN: '是 / 否' }
const own = <T>(values: Record<string, T>, key: string): T | undefined => Object.prototype.hasOwnProperty.call(values, key) ? values[key] : undefined

/** 未知配置保留原键，避免静默隐藏新增属性。 */
export const comparisonProperty = (property: string) => own(labels, property) ?? property

/** 仅格式化文本供 Vue 转义渲染，不将版本配置解释为 HTML。 */
export function comparisonValue(value: unknown, property = ''): string {
  if (value == null) return '未设置'
  if (value === '') return '空值'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'string') {
    if (property === 'type') return own(types, value) ?? value
    if (property.endsWith('assigneeRule')) {
      const roles: Record<string, string> = { 'role:MANAGER': '部门审批组', 'role:FINANCE': '财务审批组', 'role:ADMIN': '额外复核组（示例）' }
      return own(roles, value) ?? (value.startsWith('user:') ? '指定用户：' + value.slice(5) : value)
    }
    return value
  }
  if (Array.isArray(value)) {
    if (!value.length) return '无'
    if (property === 'fieldOrder' || property === 'branchOrder') return value.join(' → ')
    return value.map(item => comparisonValue(item)).join('\n\n')
  }
  if (typeof value === 'object') return Object.entries(value).map(([key, item]) => `${comparisonProperty(key)}：${comparisonValue(item, key)}`).join('\n')
  return String(value)
}
