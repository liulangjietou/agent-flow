import { readNotificationTexts, type NotificationTexts } from './notificationTexts.js'
import type { Graph } from './api'
import { validateFormSchema, type FormSchema } from './formSchema.js'

export const TEMPLATE_FILE_LIMIT = 1024 * 1024
export const TEMPLATE_FORMAT = 'agentflow-process-template'

/** 仅承载可编辑配置；不包含来源租户、实例、发布状态或身份凭证。@author owlzhangfq@gmail.com */
export interface PortableProcess { key: string; name: string; graph: Graph; formSchema: FormSchema | null; notificationTexts?: NotificationTexts }

function object(value: unknown, allowed: string[], required: string[], path: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${path} 必须是对象。`)
  const result = value as Record<string, unknown>
  if (Object.keys(result).some(key => !allowed.includes(key))) throw new Error(`${path} 含有不支持的属性，请使用当前平台导出的模板。`)
  if (required.some(key => !Object.prototype.hasOwnProperty.call(result, key))) throw new Error(`${path} 缺少必要属性。`)
  return result
}
function text(value: unknown, path: string, max: number, empty = false): asserts value is string {
  if (typeof value !== 'string' || (!empty && !value.trim()) || value.length > max) throw new Error(`${path} 不是有效文本（最多 ${max} 字符）。`)
}
function list(value: unknown, path: string, max: number): asserts value is unknown[] {
  if (!Array.isArray(value) || value.length > max) throw new Error(`${path} 必须是列表，最多 ${max} 项。`)
}

function readField(raw: unknown, column = false) {
      const f = object(raw, ['key', 'label', 'type', 'required', 'helpText', 'maxLength', 'minimum', 'maximum', 'options', 'columns', 'maxRows'], ['key', 'label', 'type', 'required'], '字段')
      text(f.key, '字段标识', 64); text(f.label, '字段名称', 128)
      if (typeof f.required !== 'boolean') throw new Error('字段必填配置必须是布尔值。')
      if (f.helpText != null) text(f.helpText, '填写提示', 1000, true)
      if (f.options != null) {
        list(f.options, '字段选项', 50)
        if (f.type !== 'SELECT' && f.options.length) throw new Error('只有单选字段可以配置选项。')
        for (const rawOption of f.options) {
          const option = object(rawOption, ['value', 'label'], ['value', 'label'], '选项')
          text(option.value, '选项保存值', 128); text(option.label, '选项名称', 128)
        }
      }
      if (column && (f.type === 'TABLE' || f.columns != null)) throw new Error('明细列不能嵌套明细。')
      if (f.columns != null) {
        list(f.columns, '明细列', 20)
        for (const child of f.columns) readField(child, true)
      }
    }

/** 文件边界只校验表示和白名单；图连通性、条件与审批人仍由现有服务端校验。 */
function process(value: unknown, version: 1 | 2): PortableProcess {
  const fields = ['key', 'name', 'graph', 'formSchema', ...(version === 2 ? ['notificationTexts'] : [])]
  const p = object(value, fields, fields, '流程')
  if (version === 2) p.notificationTexts = readNotificationTexts(p.notificationTexts)
  text(p.key, '来源流程标识', 128); text(p.name, '流程名称', 128)
  const g = object(p.graph, ['nodes', 'edges', 'conditionLanguageVersion'], ['nodes', 'edges'], '流程图')
  if (g.conditionLanguageVersion !== undefined && g.conditionLanguageVersion !== 1 && g.conditionLanguageVersion !== 2) throw new Error('条件语言版本不受支持。')
  list(g.nodes, '节点', 200); list(g.edges, '连线', 400)
  for (const raw of g.nodes) {
    const n = object(raw, ['id', 'name', 'type', 'properties'], ['id', 'name', 'type', 'properties'], '节点')
    text(n.id, '节点标识', 128); text(n.name, '节点名称', 256)
    if (!['START', 'END', 'USER_TASK', 'EXCLUSIVE_GATEWAY', 'PARALLEL_GATEWAY'].includes(n.type as string)) throw new Error('模板包含当前版本不支持的节点类型。')
    const properties = object(n.properties, ['x', 'y', 'assigneeRule', 'approvalMode',
      'deadlineCalendarId', 'deadlineCalendarRevision', 'deadlineWorkingMinutes'], [], '节点配置')
    for (const [key, value] of Object.entries(properties)) {
      text(value, `节点配置 ${key}`, 256)
      if ((key === 'x' || key === 'y') && (!Number.isFinite(Number(value)) || Number(value) < 0 || Number(value) > 1_000_000)) throw new Error('节点位置必须在 0 至 1000000 之间。')
    }
  }
  for (const raw of g.edges) {
    const e = object(raw, ['id', 'source', 'target', 'condition', 'defaultBranch'], ['id', 'source', 'target', 'condition', 'defaultBranch'], '连线')
    for (const key of ['id', 'source', 'target']) text(e[key], '连线标识', 128)
    text(e.condition, '分支条件', 4000, true)
    if (typeof e.defaultBranch !== 'boolean') throw new Error('默认分支必须是布尔值。')
  }
  if (p.formSchema !== null) {
    const schema = object(p.formSchema, ['schemaVersion', 'fields'], ['schemaVersion', 'fields'], '表单')
    list(schema.fields, '表单字段', 50)
    for (const raw of schema.fields) readField(raw)
    const errors = validateFormSchema(p.formSchema as FormSchema)
    const messages = [...errors.schema, ...errors.fields.flatMap(field => Object.values(field))]
    if (messages.length) throw new Error(`表单配置无效：${messages[0]}`)
  }
  return p as unknown as PortableProcess
}

/** 读取 UTF-8 JSON 模板；未知版本、未知配置和超限内容整份拒绝，不静默丢弃。 */
export function parsePortableTemplate(raw: string): PortableProcess {
  if (new TextEncoder().encode(raw).byteLength > TEMPLATE_FILE_LIMIT) throw new Error('模板文件不能超过 1 MiB。')
  let value: unknown
  try { value = JSON.parse(raw.replace(/^\uFEFF/, '')) } catch { throw new Error('无法读取 JSON，请选择平台导出的流程模板。') }
  const envelope = object(value, ['format', 'formatVersion', 'process'], ['format', 'formatVersion', 'process'], '模板文件')
  if (envelope.format !== TEMPLATE_FORMAT || (envelope.formatVersion !== 1 && envelope.formatVersion !== 2)) throw new Error('模板格式或版本不受支持，请使用当前平台导出的模板。')
  return process(envelope.process, envelope.formatVersion as 1 | 2)
}

/** 显式选择配置字段，导出当前设计而非定义响应、租户信息或业务数据。 */
export function serializePortableTemplate(input: PortableProcess): string {
  const raw = JSON.stringify({ format: TEMPLATE_FORMAT, formatVersion: input.notificationTexts === undefined ? 1 : 2, process: {
    key: input.key, name: input.name, graph: input.graph, formSchema: input.formSchema,
    ...(input.notificationTexts === undefined ? {} : { notificationTexts: input.notificationTexts })
  } }, null, 2) + '\n'
  parsePortableTemplate(raw)
  return raw
}

/** 文件读取和租户预检共用代次；换文件、离开页面或取消后丢弃迟到结果。@author owlzhangfq@gmail.com */
export class PortableTemplateReview {
  value: PortableProcess | null = null
  loading = false
  reviewed = false
  errors: string[] = []
  error = ''
  private generation = 0
  private controller: AbortController | null = null

  constructor(private validate: (graph: Graph, schema: FormSchema | null, signal: AbortSignal, key?: string) => Promise<{ errors: string[] }>) {}

  /** 清空文件及预检；不触及设计器内容和持久化状态。 */
  clear() {
    this.invalidateCheck()
    this.value = null
  }

  /** 目标流程标识改变后保留已读文件，但原检查结果与迟到响应不能继续放行。 */
  invalidateCheck() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.loading = false; this.reviewed = false; this.errors = []; this.error = ''
  }

  /** 文件选择并不会创建或覆盖草稿。 */
  async read(file: Pick<File, 'size' | 'text'>) {
    this.clear()
    const generation = this.generation
    this.loading = true
    try {
      if (file.size > TEMPLATE_FILE_LIMIT) throw new Error('模板文件不能超过 1 MiB。')
      const raw = await file.text()
      if (generation !== this.generation) return
      this.value = parsePortableTemplate(raw)
      return this.value
    } catch (cause) {
      if (generation === this.generation) this.error = cause instanceof Error ? cause.message : '读取文件失败，请重新选择。'
    } finally { if (generation === this.generation) this.loading = false }
  }

  /** 复用服务端结构校验与当前租户身份目录，不产生定义或引擎实例。 */
  async check(key?: string) {
    if (!this.value || this.loading) return
    const generation = this.generation, controller = new AbortController(), value = this.value
    this.controller = controller; this.loading = true; this.reviewed = false; this.errors = []; this.error = ''
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const result = await Promise.race([
        this.validate(value.graph, value.formSchema, controller.signal, key),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject(new Error('检查超时，请重试检查。')) }, 12_000) })
      ])
      if (generation === this.generation) { this.errors = result.errors; this.reviewed = true }
    } catch (cause) {
      if (generation === this.generation) this.error = (cause as { message?: string })?.message ?? '检查失败，请重试。'
    } finally { clearTimeout(timeout); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }

  /** 缺失租户引用或区间遗漏允许创建待配置草稿，发布仍须重新通过检查。 */
  get canImport() {
    const repairable = ['ASSIGNEE_NOT_AVAILABLE:', 'DEADLINE_CALENDAR_UNAVAILABLE:', 'BRANCH_COVERAGE_GAP:']
    return this.reviewed && !!this.value && !this.loading && this.errors.every(error => repairable.some(prefix => error.startsWith(prefix)))
  }
}
