/** 设计器与办理页共用审批方式名称；未知配置保留原值。@author owlzhangfq@gmail.com */
export const approvalModes = ['SINGLE', 'ALL', 'ANY', 'PERCENT'] as const

/** 比例只作展示，不在浏览器重新计算已冻结的实际门槛。 */
export function approvalPolicyLabel(mode = 'SINGLE', percentage?: string | number | null): string {
  if (mode === 'SINGLE') return '单人审批'
  if (mode === 'ALL') return '全员会签'
  if (mode === 'ANY') return '任一人通过'
  if (mode === 'PERCENT') return percentage === undefined || percentage === null || percentage === '' ? '按比例会签' : `${percentage}% 会签`
  return `未知方式：${mode}`
}

/** 有实际独立任务的方式才展示会签语义，单人候选池不误称会签。 */
export function isCountersignMode(mode?: string): boolean {
  return mode === 'ALL' || mode === 'ANY' || mode === 'PERCENT'
}
