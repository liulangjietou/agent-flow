/** 一天内的工作时段，结束可为 24:00。@author owlzhangfq@gmail.com */
export interface CalendarPeriod { start: string; end: string }
/** 日期例外覆盖当天全部周规则；空时段表示休息。@author owlzhangfq@gmail.com */
export interface CalendarOverride { date: string; periods: CalendarPeriod[]; note: string }
/** 与服务端版本绑定的规则。@author owlzhangfq@gmail.com */
export interface CalendarRules { zoneId: string; weeklyHours: Partial<Record<Weekday, CalendarPeriod[]>>; overrides: CalendarOverride[] }
export type Weekday = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
export const weekdays: Array<{ key: Weekday; label: string }> = [
  { key: 'MONDAY', label: '周一' }, { key: 'TUESDAY', label: '周二' }, { key: 'WEDNESDAY', label: '周三' },
  { key: 'THURSDAY', label: '周四' }, { key: 'FRIDAY', label: '周五' }, { key: 'SATURDAY', label: '周六' }, { key: 'SUNDAY', label: '周日' }
]
/** 当前或历史修订，元数据由服务器产生。@author owlzhangfq@gmail.com */
export interface BusinessCalendar { id: string; key: string; name: string; revision: number; rules: CalendarRules; updatedBy: string; updatedAt: string }
/** 分页目录不携带完整规则。@author owlzhangfq@gmail.com */
export interface CalendarSummary { id: string; key: string; name: string; zoneId: string; revision: number; updatedBy: string; updatedAt: string }
/** 目录的稳定键分页。@author owlzhangfq@gmail.com */
export interface CalendarPage { items: CalendarSummary[]; nextAfterKey: string | null }
/** 历史的版本倒序分页。@author owlzhangfq@gmail.com */
export interface CalendarVersionPage { items: CalendarSummary[]; nextBeforeRevision: number | null }
/** 保存的业务内容，不允许客户端指定租户或作者。@author owlzhangfq@gmail.com */
export interface CalendarInput { key: string; name: string; rules: CalendarRules }
/** 以明确版本为基础保存新修订。@author owlzhangfq@gmail.com */
export interface CalendarUpdate { name: string; rules: CalendarRules; expectedRevision: number }
/** 试算必须选择已保存版本；重复钟点由用户选择。@author owlzhangfq@gmail.com */
export interface CalendarCalculationInput { revision: number; startLocal: string; workingMinutes: number; overlapChoice?: 'EARLIER' | 'LATER' }
/** 服务端返回的真实时间点与计算依据。@author owlzhangfq@gmail.com */
export interface CalendarCalculation { calendarId: string; revision: number; deadline: { startAt: string; dueAt: string; zoneId: string; workingMinutes: number; usedPeriods: number } }
/** 跨页面保留草稿，基础版本不能被刷新隐式替换。@author owlzhangfq@gmail.com */
export interface CalendarDraft { baseline: BusinessCalendar | null; form: CalendarInput }
export function cloneCalendar<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
export function emptyCalendar(): CalendarInput { return { key: '', name: '', rules: { zoneId: '', weeklyHours: {}, overrides: [] } } }
export function calendarInput(value: BusinessCalendar): CalendarInput { return cloneCalendar({ key: value.key, name: value.name, rules: value.rules }) }
export function calendarDirty(value: CalendarDraft): boolean { return JSON.stringify(value.form) !== JSON.stringify(value.baseline ? calendarInput(value.baseline) : emptyCalendar()) }
export function calendarPayload(value: CalendarDraft): CalendarInput | CalendarUpdate {
  const { key, name, rules } = cloneCalendar(value.form)
  return value.baseline ? { name: name.trim(), rules, expectedRevision: value.baseline.revision } : { key: key.trim(), name: name.trim(), rules }
}
/** 仅保存于当前页面内存，账号隔离，恢复请求时核对原内容。@author owlzhangfq@gmail.com */
export class CalendarDrafts {
  private drafts = new Map<string, CalendarDraft>()
  get(scope: string) { const value = this.drafts.get(scope); return value ? cloneCalendar(value) : null }
  put(scope: string, value: CalendarDraft) { if (scope) this.drafts.set(scope, cloneCalendar(value)) }
  hasDrafts() { return [...this.drafts.values()].some(calendarDirty) }
  /** 确认保存只推进原请求对应的草稿，其他资源或发送后的编辑保持原样。 */
  acknowledge(scope: string, path: string, sentBody: string, saved: BusinessCalendar): boolean {
    const current = this.drafts.get(scope)
    if (!current) return false
    const expectedPath = current.baseline ? '/business-calendars/' + current.baseline.id : '/business-calendars'
    if (path !== expectedPath || JSON.stringify(calendarPayload(current)) !== sentBody) return false
    this.put(scope, { baseline: saved, form: calendarInput(saved) }); return true
  }
}
export const calendarDrafts = new CalendarDrafts()

/** 日历目录、修订和试算共用的最新请求控制，失权与切换时不保留旧结果。@author owlzhangfq@gmail.com */
export class CalendarRead<T> {
  value: T | null = null
  loading = false
  error = ''
  status = 0
  private generation = 0
  private controller: AbortController | null = null
  clear() { this.generation++; this.controller?.abort(); this.controller = null; this.value = null; this.loading = false; this.error = ''; this.status = 0 }
  async load(fetchValue: (signal: AbortSignal) => Promise<T>): Promise<T | null> {
    this.clear()
    const generation = ++this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout = false
    const timer = setTimeout(() => { timeout = true; controller.abort() }, 12_000)
    try {
      const value = await fetchValue(controller.signal)
      if (generation !== this.generation) return null
      if (timeout) { this.error = '日历查询超时，请重试。'; return null }
      this.value = value; return value
    } catch (cause) {
      if (generation === this.generation) { this.status = (cause as { status?: number }).status ?? 0; this.error = timeout ? '日历查询超时，请重试。' : (cause as { message?: string }).message ?? '日历暂时无法读取，请重试。' }
      return null
    } finally { clearTimeout(timer); if (generation === this.generation) { this.loading = false; this.controller = null } }
  }
}

/** 浏览器时区库不支持服务端合法别名时，仍展示明确的 UTC 时间。 */
export function calendarInstant(value: string, zone: string): string {
  try { return new Intl.DateTimeFormat('zh-CN', { timeZone: zone, dateStyle: 'medium', timeStyle: 'long', hourCycle: 'h23' }).format(new Date(value)) }
  catch { return `${value}（UTC；浏览器无法显示 ${zone}）` }
}
