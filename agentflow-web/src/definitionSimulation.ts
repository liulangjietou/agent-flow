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
          ? '没有匹配的路径，请调整测试数据或配置默认分支。' : failure.code === 'EXPENSE_SPLIT_SIMULATION_REQUIRED'
          ? '已启用跨单规则，请明确填写合成路由金额。' : failure.code === 'EXPENSE_SPLIT_SIMULATION_INVALID'
          ? '合成金额须与规则和本单测试币种一致，且不能低于本单金额。' : failure.code === 'EXPENSE_SPLIT_SIMULATION_UNEXPECTED'
          ? '当前规则未启用，请清除合成金额后重新运行。' : failure.message ?? '模拟失败，请重试。'
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
  EXPENSE_SPLIT_RISK_POLICY_INVALID: '跨单规则须明确配置有效状态、完整窗口、阈值和本位币；关闭时可清空全部参数',
  EXPENSE_SPLIT_RISK_REQUIRES_EXPENSE_FORM: '跨单规则需要完整的结构化费用表单',
  EXPENSE_SPLIT_ROUTING_REQUIRED: '启用跨单规则至少需要一个指定业务条件网关',
  EXPENSE_SPLIT_ROUTING_INVALID: '冻结跨单金额只能由明确标记的条件网关使用',
  EXPENSE_SPLIT_ROUTING_AMOUNT_REQUIRED: '指定业务网关至少一条出线条件须引用 amount',
  EXPENSE_SPLIT_ROUTING_AFTER_FINANCE: '财务审核与复核之后的网关必须使用本单金额',
  INVALID_SERVICE_TASK_POLICY: '请明确选择操作版本并配置字段映射；服务节点不接受地址、脚本或执行表达式',
  SERVICE_TASK_CONTRACT_UNAVAILABLE: '原操作暂不可用或契约不一致，请核对原版本后再发布或发起',
  SERVICE_TASK_CONTRACT_MISMATCH: '节点所选版本与契约不一致，请明确重新选择',
  SERVICE_TASK_INPUT_UNKNOWN: '输入映射引用了不存在的表单字段或服务参数',
  SERVICE_TASK_INPUT_TYPE_MISMATCH: '来源字段类型须与服务参数一致',
  SERVICE_TASK_INPUT_NOT_READABLE: '服务节点对来源字段须有只读权限；隐藏或脱敏字段不能外发',
  SERVICE_TASK_INPUT_SENSITIVITY_LOSS: '此参数未声明敏感性，不能接收受限制字段',
  SERVICE_TASK_REQUIRED_INPUT_MISSING: '请为服务任务的每个必填参数选择来源字段',
  INVALID_SERVICE_TASK_INPUTS: '映射值不符合参数类型、必填或大小限制，请检查测试数据或申请表单',
  SERVICE_REQUIRES_SERVICE_NODE: '仅服务任务节点可以配置操作引用和参数映射',
  SERVICE_REQUIRES_APPROVAL_PATH: '每条完成路径仍须有人工审批依据，服务结果不能代替审批',
  SUBPROCESS_REFERENCE_REQUIRED: '请选择子流程的明确发布版本',
  SUBPROCESS_REFERENCE_INVALID: '子流程须引用准确标识和有效发布版本，不能使用最新版本或表达式',
  SUBPROCESS_INPUT_MAPPING_INVALID: '子流程输入只能映射已声明的字段标识，最多 50 项',
  SUBPROCESS_INPUT_FIELD_UNKNOWN: '输入映射引用了父表单或子表单中不存在的字段',
  SUBPROCESS_REQUIRED_INPUT_MISSING: '请为子流程的必填字段及明细列配置来源',
  SUBPROCESS_INPUT_TYPE_MISMATCH: '输入映射的父子字段类型必须一致',
  SUBPROCESS_INPUT_NOT_READABLE: '调用节点对输入字段须有只读权限，隐藏或脱敏字段不能传入',
  SUBPROCESS_INPUT_SENSITIVITY_LOSS: '子表单字段须保留父输入字段的敏感标记',
  SUBPROCESS_DEFINITION_UNAVAILABLE: '当前租户找不到所引用的子流程发布版本，请核对原版本',
  SUBPROCESS_REQUIRES_APPROVAL_PATH: '每条完成路径须有人工审批或有效的子审批依据',
  SUBPROCESS_RECURSION_FORBIDDEN: '子流程不能再次调用同一祖先版本',
  SUBPROCESS_DEPTH_EXCEEDED: '子流程嵌套不能超过 16 层',
  SUBPROCESS_DEPENDENCY_LIMIT_EXCEEDED: '子流程依赖不能超过 256 个版本或 4096 个调用节点',
  SUBPROCESS_REQUIRES_CALL_NODE: '只有子流程节点可以配置调用引用和输入',
  SUBPROCESS_NODE_LIMIT_EXCEEDED: '子流程节点标识最多 128 字符，名称最多 200 字符',
  SUBPROCESS_RUNTIME_NOT_READY: '当前版本暂未开放子流程保存与发布',
  BUSINESS_ENDPOINT_REQUIRED: '此财务流程需要专用业务提交，不能作为普通子流程调用',
  DEFINITION_DISABLED: '所引用的流程版本已停用，请恢复原版本或明确重新选择',
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
  INVALID_NODE_ID: '节点标识不合法，请使用如 review_1 的编号，不能以数字开头或包含空白、冒号、斜杠',
  INVALID_EDGE_ID: '连线标识不合法，请使用如 route_1 的编号，不能以数字开头或包含空白、冒号、斜杠',
  DIAGRAM_NODE_POSITION_INVALID: '节点位置无效或超出画布范围，请移动该节点或重新自动布局',
  DIAGRAM_EDGE_WAYPOINTS_INVALID: '连线路径无效，请重新自动布局后保存',
  INVALID_PROCESS_KEY: '流程标识不合法，请使用如 expense_1 的编号，不能以数字开头或包含空白、冒号、斜杠',
  NODE_EDGE_ID_CONFLICT: '连线与节点标识重复，请在高级画布删除该连线后重新连接',
  PROCESS_KEY_CONFLICT: '流程标识与节点或连线标识重复，请更换流程标识',
  START_COUNT_MUST_BE_ONE: '流程必须只有一个开始节点', END_REQUIRED: '流程缺少结束节点',
  NODE_UNREACHABLE: '节点无法从开始节点到达', NODE_DEAD_END: '节点没有后续路径', GRAPH_LOOP: '流程存在循环，请移除回流连线',
  APPROVAL_MODE_INVALID: '审批方式不合法，请选择单人审批、全员会签、任一人通过或按比例会签',
  APPROVAL_RESPONSIBILITY_INVALID: '职责分离配置无效：申请人限制须明确选择，前序引用最多 20 个且不能重复或留空',
  APPROVAL_RESPONSIBILITY_REQUIRES_USER_TASK: '只有人工审批节点可以配置职责分离',
  APPROVAL_RESPONSIBILITY_REFERENCE_INVALID: '职责分离只能引用本流程的前序人工审批；请检查已删除、后续或并行同级步骤',
  APPROVAL_PERCENTAGE_REQUIRED: '按比例会签必须明确填写通过比例',
  APPROVAL_PERCENTAGE_INVALID: '通过比例必须为 1 至 100 的整数',
  APPROVAL_PERCENTAGE_UNEXPECTED: '只有按比例会签可以配置通过比例，请清除其他方式残留的比例',
  DEADLINE_REQUIRES_USER_TASK: '只有人工审批节点可以配置期限',
  ESCALATION_REQUIRES_USER_TASK: '只有人工审批节点可以配置超时升级',
  ESCALATION_REQUIRES_DEADLINE: '启用超时升级前须配置完整审批期限',
  ESCALATION_RULE_INVALID: '升级须配置 1 至 527040 个工作分钟和明确收件规则',
  ESCALATION_RECIPIENT_UNAVAILABLE: '升级收件名单不可用，或人数超过 100 人',
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
  FORM_ASSIGNEE_RULE_INVALID: '表单选人需要明确选择来源字段与组织关系',
  FORM_ASSIGNEE_FIELD_REQUIRED: '表单选人只能引用顶层必填单选字段',
  FORM_ASSIGNEE_OPTION_INVALID: '选人字段包含普通选项，请仅保留从组织目录添加的选项',
  FORM_ASSIGNEE_OPTION_UNAVAILABLE: '选人字段存在不属于本租户、类型不符或没有有效审批人的选项，请核对全部选项',
  ASSIGNEE_RULE_REQUIRED: '审批节点尚未配置审批人', ASSIGNEE_RULE_INVALID: '审批人配置不合法',
  RISK_FIELD_RESTRICTED: '风险规则不能引用敏感、隐藏或脱敏字段',
  RISK_REQUIRES_FORM_SCHEMA: '风险规则需要已声明的表单字段',
  INVALID_RISK_POLICY: '风险规则配置不合法',
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
  const risk = error.indexOf(':risk:')
  if (risk >= 0) {
    const code = error.slice(0, risk)
    return { label: `风险规则 ${error.slice(risk + 6)}：${ruleLabels[code] ?? '条件语法或引用字段不合法'}`, target: '' }
  }
  if (error.startsWith('INVALID_CONDITION_AT:')) {
    const last = error.lastIndexOf(':')
    return { label: `条件语法错误，第 ${error.slice(last + 1)} 个字符附近，请检查运算符、括号和取值`, target: error.slice('INVALID_CONDITION_AT:'.length, last) }
  }
  const separator = error.indexOf(':')
  const code = separator < 0 ? error : error.slice(0, separator)
  return { label: Object.prototype.hasOwnProperty.call(ruleLabels, code) ? ruleLabels[code] : error, target: separator < 0 ? '' : error.slice(separator + 1) }
}
