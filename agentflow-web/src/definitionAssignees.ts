import { formAssigneeLabel } from './formAssignees.js'

/**
 * 当前租户身份源中实际可选择的审批规则。
 * @author owlzhangfq@gmail.com
 */
export interface AssigneeOption { rule: string; label: string; memberCount: number; contextual?: boolean }

const roleLabels: Record<string, string> = {
  MANAGER: '部门审批组', FINANCE: '财务审批组', ADMIN: '管理员组',
  PROCESS_ADMIN: '流程管理员组', APPROVER: '全部审批账号', EMPLOYEE: '员工组'
}

/** 已保存规则始终可读，不因目录暂时不可用而变为空白。 */
export function assigneeLabel(rule: string, directoryLabel?: string): string {
  if (rule.startsWith('field:')) return formAssigneeLabel(rule)
  if (rule === 'role:ORG_DEPARTMENT_HEAD') return directoryLabel || '本次任职部门负责人'
  if (rule.startsWith('role:ORG_SUPERVISOR_')) return directoryLabel || `本次任职 · 第 ${rule.slice('role:ORG_SUPERVISOR_'.length)} 级主管`
  if (rule.startsWith('role:ORG_PERSON_')) return directoryLabel || '本地指定人员'
  if (rule.startsWith('role:ORG_UNIT_')) return directoryLabel || '本地组织成员'
  if (rule.startsWith('user:')) return `指定账号 · ${rule.slice(5)}`
  if (rule.startsWith('role:')) return roleLabels[rule.slice(5)] ?? `角色 · ${rule.slice(5)}`
  return rule || '待配置'
}

/**
 * 目录只用于当前编辑会话；切换账号、节点或离开页面后旧响应不得回填。
 * @author owlzhangfq@gmail.com
 */
export class DefinitionAssigneesQuery<T = AssigneeOption> {
  options: T[] = []
  loading = false
  loaded = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null

  constructor(private fetchOptions: (signal: AbortSignal) => Promise<T[]>, private directoryName = '审批人') {}

  /** 清除当前会话的目录及未完成请求。 */
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.options = []; this.loading = false; this.loaded = false; this.error = ''
  }

  /** 读取名单并限制等待时长；失败时不保留过期候选人。 */
  async load(scope: string) {
    this.clear()
    if (!scope) return
    const generation = this.generation, controller = new AbortController()
    this.controller = controller; this.loading = true
    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const options = await Promise.race([
        this.fetchOptions(controller.signal),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject(new Error(`${this.directoryName}目录读取超时，请重试。`)) }, 12_000) })
      ])
      if (generation === this.generation) { this.options = options; this.loaded = true }
    } catch (error) {
      if (generation === this.generation) this.error = (error as { message?: string })?.message ?? `无法读取${this.directoryName}目录，请重试。`
    } finally {
      clearTimeout(timeout)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
