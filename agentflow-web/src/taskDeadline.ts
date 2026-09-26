/** 以服务端记录的真实到期时刻判断状态；缺失值不推算或补造期限。 */
export function taskDeadlineState(dueAt: string | null | undefined, now: number) {
  if (dueAt == null) return 'unrecorded'
  const due = Date.parse(dueAt)
  if (!Number.isFinite(due)) return 'unknown'
  return due <= now ? 'overdue' : 'pending'
}
