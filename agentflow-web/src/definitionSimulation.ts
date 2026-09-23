import type { ApiError, SimulationInput, SimulationResult } from './api'
import type { FieldErrors } from './formSchema'

/**
 * 每个结果只属于一次不可变的设计与数据快照，编辑、切换或卸载后立即失效。
 * @author owlzhangfq@gmail.com
 */
export class SimulationPreview {
  result: SimulationResult | null = null
  loading = false
  error = ''
  fieldErrors: FieldErrors = {}
  definitionErrors: string[] = []
  private generation = 0
  private controller: AbortController | null = null

  constructor(private fetchPreview: (input: SimulationInput, signal: AbortSignal) => Promise<SimulationResult>) {}

  /** 清除结果和旧错误，不保留可被误认为当前设计的高亮。 */
  clear() {
    this.generation++
    this.controller?.abort()
    this.controller = null
    this.result = null
    this.error = ''
    this.fieldErrors = {}
    this.definitionErrors = []
    this.loading = false
  }

  /** 发送当前快照，仅接收仍属于此视图的响应。 */
  async run(input: SimulationInput) {
    this.clear()
    const generation = this.generation
    const controller = new AbortController()
    this.controller = controller
    this.loading = true
    let timedOut = false
    const timeout = setTimeout(() => { timedOut = true; controller.abort() }, 15_000)
    try {
      const snapshot = JSON.parse(JSON.stringify(input)) as SimulationInput
      const result = await this.fetchPreview(snapshot, controller.signal)
      if (generation === this.generation) {
        if (timedOut) this.error = '模拟请求超时，请重新运行。'
        else this.result = result
      }
    } catch (error) {
      if (generation === this.generation) {
        const failure = error as ApiError
        this.error = timedOut ? '模拟请求超时，请重新运行。' : failure.code === 'INVALID_DEFINITION'
          ? '请先修正流程配置，再运行模拟。' : failure.code === 'NO_BRANCH_MATCHED'
          ? '没有匹配的路径，请调整测试数据或配置默认分支。' : failure.message ?? '模拟失败，请重试。'
        this.fieldErrors = failure.details?.fieldErrors ?? {}
        this.definitionErrors = failure.details?.definitionErrors ?? []
      }
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}

/** 无表单的历史流程使用有限 JSON 值，复杂数据和可能丢失精度的数字必须显式改正。 */
export function parseSimulationValues(raw: string): Record<string, unknown> {
  let value: unknown
  try { value = JSON.parse(raw) } catch { throw new Error('请输入有效的 JSON 对象。') }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('测试数据必须是 JSON 对象。')
  for (const item of Object.values(value)) {
    if (item === null || typeof item === 'string' || typeof item === 'boolean' || typeof item === 'number' && Number.isSafeInteger(item)) continue
    throw new Error('字段只支持文本、布尔值和空值；小数或大整数请加双引号，不支持嵌套对象。')
  }
  return value as Record<string, unknown>
}

const ruleLabels: Record<string, string> = {
  DUPLICATE_NODE: '节点标识重复', DUPLICATE_EDGE: '连线标识重复', EDGE_NODE_NOT_FOUND: '连线引用了不存在的节点',
  START_COUNT_MUST_BE_ONE: '流程必须只有一个开始节点', END_REQUIRED: '流程缺少结束节点',
  NODE_UNREACHABLE: '节点无法从开始节点到达', NODE_DEAD_END: '节点没有后续路径', GRAPH_LOOP: '流程存在循环，请移除回流连线',
  ASSIGNEE_RULE_REQUIRED: '审批节点尚未配置审批人', ASSIGNEE_RULE_INVALID: '审批人配置不合法',
  UNSUPPORTED_NODE_TYPE: '当前版本不支持此节点类型', INVALID_CONDITION: '分支条件或引用字段不合法',
  GATEWAY_BRANCH_REQUIRED: '条件网关至少需要两条出线', GATEWAY_BRANCH_CONDITION_REQUIRED: '分支缺少条件',
  MULTIPLE_DEFAULT_BRANCHES: '一个网关只能有一条默认分支', DEFAULT_BRANCH_MUST_HAVE_NO_CONDITION: '默认分支不能填写条件',
  DEFAULT_BRANCH_REQUIRES_GATEWAY: '默认分支只能从条件网关发出', SINGLE_OUTGOING_REQUIRED: '普通节点只能连接一个后续节点，请使用条件网关分支',
  START_MUST_HAVE_NO_INCOMING: '开始节点不能有入线', END_MUST_HAVE_NO_OUTGOING: '结束节点不能有出线',
  TASK_NAME_EXPRESSION_FORBIDDEN: '审批节点名称不能包含表达式'
}

/** 规则只解析第一个冒号，节点或连线标识中的后续字符保持原样。 */
export function simulationIssue(error: string) {
  const separator = error.indexOf(':')
  const code = separator < 0 ? error : error.slice(0, separator)
  return { label: Object.prototype.hasOwnProperty.call(ruleLabels, code) ? ruleLabels[code] : error, target: separator < 0 ? '' : error.slice(separator + 1) }
}
