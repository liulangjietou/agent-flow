import type { FormSchema } from './formSchema'

export type FormAssigneeKind = 'PERSON' | 'DEPARTMENT' | 'POSITION'
export interface FormAssigneeOption { id: string; label: string; kind: FormAssigneeKind; memberCount: number; headAvailable: boolean }
export const formAssigneeRelations = [
  { value: 'PERSON', label: '所选人员', kind: 'PERSON' },
  { value: 'DEPARTMENT_HEAD', label: '所选部门负责人', kind: 'DEPARTMENT' },
  { value: 'DEPARTMENT_MEMBERS', label: '所选部门成员', kind: 'DEPARTMENT' },
  { value: 'POSITION_MEMBERS', label: '所选岗位成员', kind: 'POSITION' }
] as const
export type FormAssigneeRelation = typeof formAssigneeRelations[number]['value']

/** 保留未完成或已失效的配置，不能静默替换字段或关系。 */
export function formAssigneeParts(rule: string) {
  const parts = rule.startsWith('field:') ? rule.slice(6).split(':') : []
  return { fieldKey: parts[0] ?? '', relation: parts.slice(1).join(':') }
}

/** 表单来源只允许顶层必填单选；保存值在发布时由服务端核对所属租户和组织类型。 */
export function formAssigneeFields(schema?: FormSchema | null) {
  return (schema?.fields ?? []).filter(field => field.type === 'SELECT' && field.required)
}

/** 展示固定业务关系，不把规则语法作为用户名称。 */
export function formAssigneeLabel(rule: string) {
  const selected = formAssigneeParts(rule)
  const label = formAssigneeRelations.find(relation => relation.value === selected.relation)?.label ?? '待配置关系'
  return `表单选人 · ${selected.fieldKey || '待选择字段'} · ${label}`
}
