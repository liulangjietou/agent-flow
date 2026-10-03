/** 运营查询按 UTC 提交日期、精确流程版本和提交时组织名称筛选。@author owlzhangfq@gmail.com */
export interface OperationsFilter { from: string; to: string; processKey?: string; definitionVersion?: number; organization?: string }
/** 无有效样本时比例与平均时长缺省，不用零代替未知。@author owlzhangfq@gmail.com */
export interface OperationsMetrics {
  submittedRounds: number; applications: number; inApproval: number; approved: number; returned: number
  rejected: number; withdrawn: number; durationSamples: number; averageApprovalSeconds?: number
  decidedRounds: number; returnRatePercent?: number
}
/** 一个流程版本的提交队列口径。@author owlzhangfq@gmail.com */
export interface OperationsProcess { processKey: string; definitionVersion: number; metrics: OperationsMetrics }
/** 当前积压任务摘要，管理员从此打开申请原有详情。@author owlzhangfq@gmail.com */
export interface OperationsTask {
  taskId: string; taskName: string; applicationId: string; businessNo: string; title: string; processKey: string
  definitionVersion: number; roundNo: number; assignee?: string; createdAt: string; waitingSeconds: number; dueAt?: string | null
}
/** 已办理任务的历史期限事实，缺失和取消不会记作按时完成。@author owlzhangfq@gmail.com */
export interface OperationsSla {
  decidedTasks: number; timedTasks: number; violatedTasks: number; withoutDeadlineTasks: number
  invalidTimingTasks: number; cancelledTasks: number; unfinishedTasks: number; unrecordedDecisionTasks: number; unverifiedRounds: number
  violationRatePercent?: number
}
/** 按投递记录及追加历史去重汇总，不展示收件人和正文。@author owlzhangfq@gmail.com */
export interface OperationsNotifications {
  deliveries: number; accepted: number; failed: number; retryWaiting: number; unknown: number
  suppressed: number; pending: number; inFlight: number; previouslyFailed: number
}
/** 人工采纳率只使用已经复核的运行。@author owlzhangfq@gmail.com */
export interface OperationsAgent {
  runs: number; queued: number; running: number; awaitingReview: number; failed: number
  adopted: number; dismissed: number; reviewedRuns: number; adoptionRatePercent?: number
}
/** 提交窗口与当前积压来自同一查询快照，但日期口径分别标明。@author owlzhangfq@gmail.com */
export interface OperationsReport extends OperationsFilter {
  generatedAt: string; timeZone: 'UTC'; metrics: OperationsMetrics
  sla: OperationsSla; notifications: OperationsNotifications; agent: OperationsAgent
  daily: Array<{ date: string; submittedRounds: number }>
  processes: OperationsProcess[]; moreProcesses: boolean; pendingTasks: number; overdueTasks: number
  waitingNodes: Array<{ processKey: string; definitionVersion: number; nodeId: string; nodeName: string; tasks: number; oldestCreatedAt: string; oldestWaitSeconds: number; overdueTasks: number }>
  moreWaitingNodes: boolean; oldestTasks: OperationsTask[]; moreOldestTasks: boolean; unrecordedHistoricalRounds: number
}

const DAY_MILLIS = 86_400_000
const MAX_DAYS = 366

/** 初始范围为包含当前 UTC 日期的近三十天。 */
export function defaultOperationsFilter(now = new Date()): OperationsFilter {
  const to = now.toISOString().slice(0, 10)
  const from = new Date(Date.parse(to) - 29 * DAY_MILLIS).toISOString().slice(0, 10)
  return { from, to, processKey: '', organization: '' }
}

/** 在发出查询前给出可修正的表单错误，权威校验仍在服务端入口。 */
export function operationsFilter(from: string, to: string, processKey: string, version: string, organization = '', now = new Date()): OperationsFilter {
  const validDate = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= '0001-01-01'
    && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value
  if (!validDate(from) || !validDate(to) || from > to || to > now.toISOString().slice(0, 10)
      || (Date.parse(to) - Date.parse(from)) / DAY_MILLIS >= MAX_DAYS) {
    throw new Error('请选择有效的 UTC 日期，结束日期不晚于今天，范围最多 366 天。')
  }
  const key = processKey.trim()
  const organizationName = organization.trim()
  if (organizationName.length > 128 || /[\x00-\x1f\x7f-\x9f]/.test(organizationName)) {
    throw new Error('组织名称最多 128 字，不能包含控制字符。')
  }
  if (version && (!key || !/^[1-9]\d*$/.test(version) || Number(version) > 2_147_483_647)) {
    throw new Error('请先选择流程，再输入有效的正整数版本。')
  }
  return { from, to, processKey: key, organization: organizationName, ...(version ? { definitionVersion: Number(version) } : {}) }
}

/** 用实际秒数显示经过时长；此值未套用工作日历。 */
export function operationsDuration(seconds?: number): string {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟`
  if (seconds < DAY_MILLIS / 1000) return `${Math.floor(seconds / 3600)} 小时 ${Math.floor(seconds % 3600 / 60)} 分`
  return `${Math.floor(seconds / (DAY_MILLIS / 1000))} 天 ${Math.floor(seconds % (DAY_MILLIS / 1000) / 3600)} 小时`
}

/**
 * 账号、筛选或页面改变即取消旧报告；超时和失败都不能继续显示陈旧指标。
 * @author owlzhangfq@gmail.com
 */
export class ApprovalOperationsQuery {
  report: OperationsReport | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetchReport: (filter: OperationsFilter, signal: AbortSignal) => Promise<OperationsReport>) {}

  /** 清理当前快照并使迟到结果失效。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.report = null; this.loading = false; this.error = ''
  }

  /** 查询使用提交时的筛选副本，结果只属于该账号和该次请求。 */
  async load(scope: string, filter: OperationsFilter) {
    this.clear()
    if (!scope) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timedOut = false
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, 15_000)
    try {
      const report = await this.fetchReport({ ...filter }, controller.signal)
      if (generation === this.generation) {
        if (timedOut) this.error = '统计查询超时，请缩小日期范围后重试。'
        else this.report = report
      }
    } catch (cause) {
      if (generation === this.generation) this.error = timedOut ? '统计查询超时，请缩小日期范围后重试。'
        : (cause as { message?: string }).message ?? '统计查询失败，请重试。'
    } finally {
      clearTimeout(timer)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
