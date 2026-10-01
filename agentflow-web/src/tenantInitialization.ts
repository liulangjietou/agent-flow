import { cloneCalendar, emptyCalendar, type BusinessCalendar, type CalendarInput, type CalendarRules } from './businessCalendars.js'
import type { InitiatorContext } from './initiatorContext'
import type { OrganizationPerson } from './organization'

/** 只包含本人通知事实，不包含外部地址或凭据。 */
export interface InitializationNotifications {
  tenantId: string; recipient: string; emailEnabled: boolean; enterpriseImEnabled: boolean
  version: number; emailGeneration: number; enterpriseImGeneration: number; updatedAt?: string | null
}
export interface InitializationReceipt {
  id: string; tenantId: string; workspaceName: string; initializedBy: string; initializedAt: string
  administratorRoles: string[]; administratorName: string; organization: InitiatorContext
  calendar: BusinessCalendar & { tenantId: string }; notifications: InitializationNotifications
}
export interface InitializationState {
  tenantId: string; currentSubject: string; currentRoles: string[]; organizationRevision: number
  currentAdministratorPerson: OrganizationPerson | null; initialization: InitializationReceipt | null
  currentNotifications: InitializationNotifications
  channelBindings: Array<{ channel: 'EMAIL' | 'ENTERPRISE_IM'; configured: boolean; digest: string | null }>
}
export interface InitializationRequest {
  workspaceName: string; expectedOrganizationRevision: number; confirmLocalDirectory: true
  organization: { source: 'CREATE'; legalEntityName: string; departmentName: string; positionName: string; administratorName: string }
    | { source: 'EXISTING'; appointmentId: string }
  calendar: ({ source: 'CREATE' } & CalendarInput) | { source: 'EXISTING'; id: string; revision: number }
  notifications: { expectedVersion: number; emailEnabled: boolean; enterpriseImEnabled: boolean; emailBindingDigest?: string; enterpriseImBindingDigest?: string }
}
export interface InitializationForm {
  workspaceName: string; organizationSource: 'CREATE' | 'EXISTING'; legalEntityName: string; departmentName: string
  positionName: string; administratorName: string; appointment: InitiatorContext | null
  calendarSource: 'CREATE' | 'EXISTING'; newCalendar: CalendarInput; calendar: BusinessCalendar | null
  emailEnabled: boolean; enterpriseImEnabled: boolean
}
export interface InitializationDraft { baseline: InitializationState; form: InitializationForm }
type Identity = { tenantId: string; userId: string }
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value)
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0
const integer = (value: unknown, min = 0): value is number => Number.isSafeInteger(value) && Number(value) >= min
const date = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const roles = (value: unknown): value is string[] => Array.isArray(value) && value.every(text) && value.includes('ADMIN')
const digest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
function requireValue(valid: unknown): asserts valid { if (!valid) throw unreadable() }
function unreadable() { return { status: 0, code: 'RESPONSE_UNREADABLE', message: '初始化响应不完整或与当前身份不符；写入结果未知时请恢复原操作。' } }

function checkNotifications(value: InitializationNotifications, tenant: string, subject: string) {
  requireValue(object(value) && value.tenantId === tenant && value.recipient === subject
    && typeof value.emailEnabled === 'boolean' && typeof value.enterpriseImEnabled === 'boolean'
    && integer(value.version) && integer(value.emailGeneration) && integer(value.enterpriseImGeneration))
  requireValue(value.version === 0 ? !value.emailEnabled && !value.enterpriseImEnabled && value.emailGeneration === 0
    && value.enterpriseImGeneration === 0 && value.updatedAt == null : date(value.updatedAt))
}
function checkOrganization(value: InitiatorContext, subject: string) {
  requireValue(object(value) && uuid(value.appointmentId) && uuid(value.personId) && value.subject === subject
    && integer(value.directoryRevision, 1) && uuid(value.legalEntityId) && text(value.legalEntityName)
    && uuid(value.departmentId) && text(value.departmentName) && uuid(value.positionId) && text(value.positionName))
}
function checkCalendar(value: BusinessCalendar) {
  requireValue(object(value) && uuid(value.id) && text(value.key) && text(value.name) && integer(value.revision, 1)
    && text(value.updatedBy) && date(value.updatedAt) && object(value.rules) && text(value.rules.zoneId)
    && object(value.rules.weeklyHours) && Array.isArray(value.rules.overrides))
  const periods = (items: unknown) => Array.isArray(items) && items.every(item => object(item) && text(item.start) && text(item.end))
  requireValue(Object.values(value.rules.weeklyHours).every(periods)
    && value.rules.overrides.every(day => object(day) && text(day.date) && typeof day.note === 'string' && periods(day.periods)))
}
function checkReceipt(value: InitializationReceipt, tenant: string) {
  requireValue(object(value) && uuid(value.id) && value.tenantId === tenant && text(value.workspaceName)
    && text(value.initializedBy) && date(value.initializedAt) && roles(value.administratorRoles) && text(value.administratorName))
  checkOrganization(value.organization, value.initializedBy)
  checkCalendar(value.calendar)
  requireValue(value.calendar.tenantId === tenant)
  checkNotifications(value.notifications, tenant, value.initializedBy)
}

/** 采用已有日历时，必须实际读到所选的精确版本。 */
export function readInitializationCalendar(value: unknown, id: string, revision: number): BusinessCalendar {
  const calendar = value as BusinessCalendar
  checkCalendar(calendar)
  requireValue(calendar.id === id && calendar.revision === revision)
  return calendar
}

/** 缺失状态不能当成未初始化，也不能使用另一账号的配置。 */
export function readInitializationState(value: unknown, actor: Identity | null): InitializationState {
  const result = value as InitializationState
  requireValue(actor && object(result) && result.tenantId === actor.tenantId && result.currentSubject === actor.userId
    && roles(result.currentRoles) && integer(result.organizationRevision)
    && (result.currentAdministratorPerson === null || object(result.currentAdministratorPerson))
    && (result.initialization === null || object(result.initialization)))
  if (result.currentAdministratorPerson) {
    const person = result.currentAdministratorPerson
    requireValue(uuid(person.id) && person.subject === actor.userId && text(person.displayName) && integer(person.revision, 1)
      && typeof person.active === 'boolean' && typeof person.approvalEligible === 'boolean')
  }
  checkNotifications(result.currentNotifications, actor.tenantId, actor.userId)
  requireValue(Array.isArray(result.channelBindings) && result.channelBindings.length === 2
    && ['EMAIL', 'ENTERPRISE_IM'].every(channel => result.channelBindings.filter(item => item?.channel === channel).length === 1)
    && result.channelBindings.every(item => object(item) && typeof item.configured === 'boolean' && (item.configured ? digest(item.digest) : item.digest === null)))
  if (result.initialization) checkReceipt(result.initialization, actor.tenantId)
  return result
}

/** 成功回执必须属于原账号并匹配原选择，畸形成功仍保留原请求恢复槽。 */
export function validateInitializationReceipt(value: unknown, input: InitializationRequest, actor: Identity | null): InitializationReceipt {
  requireValue(actor)
  const result = value as InitializationReceipt
  checkReceipt(result, actor.tenantId)
  requireValue(result.initializedBy === actor.userId && result.workspaceName === input.workspaceName.trim())
  if (input.organization.source === 'EXISTING') requireValue(result.organization.appointmentId === input.organization.appointmentId)
  else requireValue(result.organization.legalEntityName === input.organization.legalEntityName.trim()
    && result.organization.departmentName === input.organization.departmentName.trim()
    && result.organization.positionName === input.organization.positionName.trim() && result.administratorName === input.organization.administratorName.trim())
  if (input.calendar.source === 'EXISTING') requireValue(result.calendar.id === input.calendar.id && result.calendar.revision === input.calendar.revision)
  else requireValue(result.calendar.key === input.calendar.key && result.calendar.name === input.calendar.name.trim()
    && result.calendar.revision === 1 && canonicalRules(result.calendar.rules) === canonicalRules(input.calendar.rules))
  requireValue(result.notifications.emailEnabled === input.notifications.emailEnabled
    && result.notifications.enterpriseImEnabled === input.notifications.enterpriseImEnabled
    && [input.notifications.expectedVersion, input.notifications.expectedVersion + 1].includes(result.notifications.version))
  return result
}

function canonicalRules(rules: CalendarRules) {
  const periods = (items: Array<{ start: string; end: string }>) => items.map(item => [item.start, item.end]).sort((a, b) => a[0]!.localeCompare(b[0]!))
  return JSON.stringify([rules.zoneId, Object.entries(rules.weeklyHours).filter(([, items]) => items?.length)
    .sort(([a], [b]) => a.localeCompare(b)).map(([day, items]) => [day, periods(items!)]),
  [...rules.overrides].sort((a, b) => a.date.localeCompare(b.date)).map(day => [day.date, periods(day.periods), day.note?.trim() ?? ''])])
}

/** 初始值只承接已有个人设置，不生成企业作息或外部订阅。 */
export function newInitializationDraft(state: InitializationState): InitializationDraft {
  return { baseline: cloneCalendar(state), form: { workspaceName: '', organizationSource: 'CREATE', legalEntityName: '', departmentName: '',
    positionName: '', administratorName: state.currentAdministratorPerson?.displayName ?? '', appointment: null,
    calendarSource: 'CREATE', newCalendar: emptyCalendar(), calendar: null,
    emailEnabled: state.currentNotifications.emailEnabled, enterpriseImEnabled: state.currentNotifications.enterpriseImEnabled } }
}

/** 比较影响提交的来源事实，查询时间或数组顺序不造成误报。 */
export function initializationBaseline(state: InitializationState) {
  return JSON.stringify([state.tenantId, state.currentSubject, [...state.currentRoles].sort(), state.organizationRevision,
    state.currentAdministratorPerson, state.currentNotifications,
    [...state.channelBindings].sort((a, b) => a.channel.localeCompare(b.channel))])
}

/** 只从已核对基线和显式选择构造请求；已有来源不夹带创建字段。 */
export function initializationRequest(draft: InitializationDraft): InitializationRequest {
  const { baseline, form } = draft
  const binding = (channel: string) => baseline.channelBindings.find(item => item.channel === channel)?.digest ?? undefined
  return { workspaceName: form.workspaceName.trim(), expectedOrganizationRevision: baseline.organizationRevision, confirmLocalDirectory: true,
    organization: form.organizationSource === 'EXISTING' ? { source: 'EXISTING', appointmentId: form.appointment?.appointmentId ?? '' }
      : { source: 'CREATE', legalEntityName: form.legalEntityName.trim(), departmentName: form.departmentName.trim(), positionName: form.positionName.trim(),
        administratorName: baseline.currentAdministratorPerson?.displayName ?? form.administratorName.trim() },
    calendar: form.calendarSource === 'EXISTING' ? { source: 'EXISTING', id: form.calendar?.id ?? '', revision: form.calendar?.revision ?? 0 }
      : { source: 'CREATE', key: form.newCalendar.key.trim(), name: form.newCalendar.name.trim(), rules: cloneCalendar(form.newCalendar.rules) },
    notifications: { expectedVersion: baseline.currentNotifications.version, emailEnabled: form.emailEnabled, enterpriseImEnabled: form.enterpriseImEnabled,
      ...(form.emailEnabled ? { emailBindingDigest: binding('EMAIL') } : {}), ...(form.enterpriseImEnabled ? { enterpriseImBindingDigest: binding('ENTERPRISE_IM') } : {}) } }
}

/** 页面级提示只负责定位缺失输入，提交时仍由服务端原领域执行完整校验。 */
export function initializationProblem(draft: InitializationDraft, throughStep = 4): string {
  const { baseline, form } = draft
  if (!form.workspaceName.trim() || form.workspaceName.length > 128) return '请填写 1 至 128 字的工作区名称。'
  if (throughStep < 1) return ''
  if (form.organizationSource === 'CREATE') {
    if (![form.legalEntityName, form.departmentName, form.positionName, baseline.currentAdministratorPerson?.displayName ?? form.administratorName]
      .every(value => value.trim() && value.length <= 128)) return '请填写法人、部门、岗位与管理员姓名。'
    if (baseline.currentAdministratorPerson && !baseline.currentAdministratorPerson.active) return '当前人员已停用，请先在组织与人员中核对并启用。'
  } else if (!form.appointment || form.appointment.subject !== baseline.currentSubject || form.appointment.directoryRevision !== baseline.organizationRevision) {
    return '请选择本人有效任职；目录有变化时，先重新读取并核对初始化状态。'
  }
  if (throughStep < 2) return ''
  if (form.calendarSource === 'CREATE') {
    const calendar = form.newCalendar
    if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(calendar.key.trim()) || !calendar.name.trim() || !calendar.rules.zoneId.trim()) return '请填写有效日历标识、名称和时区。'
    if (!Object.values(calendar.rules.weeklyHours).some(items => items?.length) && !calendar.rules.overrides.some(day => day.periods.length)) return '请按实际作息添加至少一个工作时段。'
  } else if (!form.calendar) return '请选择并读取要采用的日历版本。'
  if (throughStep < 3) return ''
  for (const [enabled, channel] of [[form.emailEnabled, 'EMAIL'], [form.enterpriseImEnabled, 'ENTERPRISE_IM']] as const) {
    if (enabled && !baseline.channelBindings.some(item => item.channel === channel && item.configured)) return '所选外部通知尚未绑定当前账号。请关闭该选择，或完成配置后重新核对。'
  }
  return ''
}

/** 草稿只驻留内存并按身份隔离；确认过的原请求才能清理对应内容。 */
export class InitializationDrafts {
  private drafts = new Map<string, InitializationDraft>()
  get(scope: string) { const value = this.drafts.get(scope); return value ? cloneCalendar(value) : null }
  put(scope: string, value: InitializationDraft) { if (scope) this.drafts.set(scope, cloneCalendar(value)) }
  discard(scope: string) { this.drafts.delete(scope) }
  hasDrafts() { return [...this.drafts.values()].some(value => JSON.stringify(value.form) !== JSON.stringify(newInitializationDraft(value.baseline).form)) }
  acknowledge(scope: string, body: string) {
    const current = this.drafts.get(scope)
    if (!current || JSON.stringify(initializationRequest(current)) !== body) return false
    this.discard(scope); return true
  }
}
export const initializationDrafts = new InitializationDrafts()

/** 状态、任职和日历读取共用取消及超时边界，失败不提供可写默认值。 */
export class InitializationRead<T> {
  value: T | null = null; loading = false; error = ''; status = 0
  private generation = 0
  private controller: AbortController | null = null
  constructor(private label: string) { }
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = ''; this.status = 0 }
  async load(fetchValue: (signal: AbortSignal) => Promise<T>): Promise<T | null> {
    this.clear(); const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([fetchValue(controller.signal), new Promise<never>((_, reject) => {
        timeout = setTimeout(() => { controller.abort(); reject({ message: `${this.label}读取超时，请重试。` }) }, 12_000)
      })])
      if (generation !== this.generation) return null
      this.value = value; return value
    } catch (cause) {
      if (generation === this.generation) { this.error = (cause as { message?: string }).message ?? `${this.label}读取失败，请重试。`; this.status = (cause as { status?: number }).status ?? 0 }
      return null
    } finally { clearTimeout(timeout); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }
}
