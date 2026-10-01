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
  TIMER_DELAY_REQUIRED: '请明确填写定时等待时长',
  TIMER_DELAY_INVALID: '等待时长必须为 1 至 31536000 的整数秒，不支持表达式或小数',
  TIMER_REQUIRES_WAIT_NODE: '只有定时等待节点可以配置等待时长',
  TIMER_REQUIRES_APPROVAL_PATH: '此结束路径可能绕过全部人工审批，定时等待不能代替审批',
  PARALLEL_BRANCH_REQUIRED: '并行网关需要至少两条入线或两条出线',
  PARALLEL_CONDITION_FORBIDDEN: '并行出线全部执行，不能设置条件',
  PARALLEL_JOIN_MISMATCH: '并行汇合必须接齐同一拆分的全部分支，不能把互斥路径分别作为入口',
  PARALLEL_MERGE_REQUIRES_GATEWAY: '并行分支必须先经并行网关汇合，再进入共同的后续节点',
  PARALLEL_JOIN_REQUIRED: '并行分支结束前必须先汇合',
  PARALLEL_BRANCH_CONDITION_REQUIRES_GATEWAY: '并行区域内的条件判断必须通过条件网关，避免丢失待汇合分支',
  BRANCH_COVERAGE_GAP: '数字条件存在未覆盖输入，发布前请补齐条件或设置默认分支',
  CONDITION_MEMBERSHIP_REQUIRES_SCHEMA: '属于判断需要绑定包含单选字段的表单',
  DUPLICATE_NODE: '节点标识重复', DUPLICATE_EDGE: '连线标识重复', EDGE_NODE_NOT_FOUND: '连线引用了不存在的节点',
  NODE_EDGE_ID_CONFLICT: '连线与节点标识重复，请在高级画布删除该连线后重新连接',
  PROCESS_KEY_CONFLICT: '流程标识与节点或连线标识重复，请更换流程标识',
  START_COUNT_MUST_BE_ONE: '流程必须只有一个开始节点', END_REQUIRED: '流程缺少结束节点',
  NODE_UNREACHABLE: '节点无法从开始节点到达', NODE_DEAD_END: '节点没有后续路径', GRAPH_LOOP: '流程存在循环，请移除回流连线',
  APPROVAL_MODE_INVALID: '审批方式不合法，请选择单人审批、全员会签、任一人通过或按比例会签',
  APPROVAL_PERCENTAGE_REQUIRED: '按比例会签必须明确填写通过比例',
  APPROVAL_PERCENTAGE_INVALID: '通过比例必须为 1 至 100 的整数',
  APPROVAL_PERCENTAGE_UNEXPECTED: '只有按比例会签可以配置通过比例，请清除其他方式残留的比例',
  DEADLINE_REQUIRES_USER_TASK: '只有人工审批节点可以配置期限',
  DEADLINE_RULE_INVALID: '期限须选择工作日历、明确修订和 1 至 527040 个工作分钟',
  DEADLINE_CALENDAR_UNAVAILABLE: '期限引用的日历修订不可用，请重新选择',
  COPY_RECIPIENT_UNAVAILABLE: '抄送名单不可用，或收件人数超过 100 人',
  COPY_REQUIRES_APPROVAL_PATH: '抄送流程的每条结束路径都必须经过人工审批',
  COPY_NODE_LIMIT_EXCEEDED: '抄送节点标识或名称过长',
  APPROVAL_MODE_REQUIRES_USER_TASK: '只有人工审批节点可以配置审批方式',
  INVALID_EXPENSE_STAGE: '费用审批职责无效，请重新选择',
  EXPENSE_STAGE_REQUIRES_USER_TASK: '费用审批职责只能配置在人工审批节点',
  EXPENSE_STAGE_REQUIRES_EXPENSE_FORM: '费用审批职责需要结构化费用表单',
  ASSIGNEE_NOT_AVAILABLE: '该节点当前匹配不到有效审批人，请重新选择',
  ASSIGNEE_RULE_REQUIRED: '审批节点尚未配置审批人', ASSIGNEE_RULE_INVALID: '审批人配置不合法',
  UNSUPPORTED_NODE_TYPE: '当前版本不支持此节点类型', INVALID_CONDITION: '分支条件或引用字段不合法',
  GATEWAY_MERGE_EDGE_UNCONDITIONAL: '条件汇合的出线不能设置条件或默认分支',
  GATEWAY_BRANCH_REQUIRED: '条件网关需要多条出线，或作为多入单出的汇合节点', GATEWAY_BRANCH_CONDITION_REQUIRED: '分支缺少条件',
  MULTIPLE_DEFAULT_BRANCHES: '一个网关只能有一条默认分支', DEFAULT_BRANCH_MUST_HAVE_NO_CONDITION: '默认分支不能填写条件',
  DEFAULT_BRANCH_REQUIRES_GATEWAY: '默认分支只能从条件网关发出', SINGLE_OUTGOING_REQUIRED: '普通节点只能连接一个后续节点，请使用条件网关分支',
  START_MUST_HAVE_NO_INCOMING: '开始节点不能有入线', END_MUST_HAVE_NO_OUTGOING: '结束节点不能有出线',
  TASK_NAME_EXPRESSION_FORBIDDEN: '审批节点名称不能包含表达式'
}

/** 规则只解析第一个冒号，节点或连线标识中的后续字符保持原样。 */
export function simulationIssue(error: string) {
  if (error.startsWith('INVALID_CONDITION_AT:')) {
    const last = error.lastIndexOf(':')
    return { label: `条件语法错误，第 ${error.slice(last + 1)} 个字符附近，请检查运算符、括号和取值`, target: error.slice('INVALID_CONDITION_AT:'.length, last) }
  }
  const separator = error.indexOf(':')
  const code = separator < 0 ? error : error.slice(0, separator)
  return { label: Object.prototype.hasOwnProperty.call(ruleLabels, code) ? ruleLabels[code] : error, target: separator < 0 ? '' : error.slice(separator + 1) }
}
