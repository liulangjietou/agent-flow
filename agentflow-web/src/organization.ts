/** 本地组织结构由管理员维护，认证主体与系统角色仍由身份源提供。@author owlzhangfq@gmail.com */
export type OrganizationSection = 'LEGAL_ENTITY' | 'DEPARTMENT' | 'POSITION' | 'PERSON' | 'APPOINTMENT'
/** 组织单元类型决定适用的归属关系。@author owlzhangfq@gmail.com */
export interface OrganizationUnit { id: string; kind: 'LEGAL_ENTITY' | 'DEPARTMENT' | 'POSITION'; name: string; legalEntityId?: string | null; parentDepartmentId?: string | null; headAppointmentId?: string | null; active: boolean; revision: number }
/** 稳定主体与本地资格，不包含密码或系统角色。@author owlzhangfq@gmail.com */
export interface OrganizationPerson { id: string; subject: string; displayName: string; active: boolean; approvalEligible: boolean; revision: number }
/** 多任职通过多条不可改写归属的关系表达。@author owlzhangfq@gmail.com */
export interface OrganizationAppointment { id: string; personId: string; departmentId: string; positionId: string; supervisorAppointmentId?: string | null; active: boolean; revision: number }
/** 分页目录。@author owlzhangfq@gmail.com */
export interface OrganizationPage<T> { items: T[]; nextAfterId?: string | null }
/** 只读变更记录。@author owlzhangfq@gmail.com */
export interface OrganizationChange { revision: number; actor: string; kind: string; recordId: string; snapshotJson: string; occurredAt: string }
export type OrganizationRecord = OrganizationUnit | OrganizationPerson | OrganizationAppointment
/** 表单只包含当前类别使用的业务字段。@author owlzhangfq@gmail.com */
export interface OrganizationForm { name: string; subject: string; legalEntityId: string; parentDepartmentId: string; personId: string; departmentId: string; positionId: string; active: boolean; approvalEligible: boolean; relationshipAppointmentId: string }
/** 草稿按登录身份保存在页面内存，网络结果未知时不替换版本。@author owlzhangfq@gmail.com */
export interface OrganizationDraft { section: OrganizationSection; mode?: 'record' | 'relationship'; baseline: OrganizationRecord | null; form: OrganizationForm }
export const organizationLabels: Record<OrganizationSection, string> = { LEGAL_ENTITY: '法人', DEPARTMENT: '部门', POSITION: '岗位', PERSON: '人员', APPOINTMENT: '任职' }
export function organizationForm(record: OrganizationRecord | null): OrganizationForm {
  return { name: record && 'name' in record ? record.name : record && 'displayName' in record ? record.displayName : '', subject: record && 'subject' in record ? record.subject : '',
    legalEntityId: record && 'legalEntityId' in record ? record.legalEntityId ?? '' : '', parentDepartmentId: record && 'parentDepartmentId' in record ? record.parentDepartmentId ?? '' : '',
    personId: record && 'personId' in record ? record.personId : '', departmentId: record && 'departmentId' in record ? record.departmentId : '', positionId: record && 'positionId' in record ? record.positionId : '',
    relationshipAppointmentId: record && 'personId' in record ? record.supervisorAppointmentId ?? '' : record && 'kind' in record ? record.headAppointmentId ?? '' : '',
    active: record?.active ?? true, approvalEligible: record && 'approvalEligible' in record ? record.approvalEligible : false }
}
export function emptyOrganizationDraft(section: OrganizationSection = 'LEGAL_ENTITY'): OrganizationDraft { return { section, baseline: null, form: organizationForm(null) } }
export function organizationDirty(draft: OrganizationDraft): boolean { return JSON.stringify(draft.form) !== JSON.stringify(organizationForm(draft.baseline)) }
export function organizationPath(draft: OrganizationDraft): string {
  const collection = draft.section === 'PERSON' ? 'people' : draft.section === 'APPOINTMENT' ? 'appointments' : 'units'
  if (draft.mode === 'relationship' && draft.baseline) return '/organization/' + collection + '/' + encodeURIComponent(draft.baseline.id) + (draft.section === 'APPOINTMENT' ? '/supervisor' : '/head')
  return '/organization/' + collection + (draft.baseline ? '/' + encodeURIComponent(draft.baseline.id) : '')
}
/** 编辑既有实体时不发送身份或归属替换；新任职必须明确三个引用。 */
export function organizationPayload(draft: OrganizationDraft): Record<string, unknown> {
  const form = draft.form, revision = draft.baseline ? { expectedRevision: draft.baseline.revision } : {}
  if (draft.mode === 'relationship') return { appointmentId: form.relationshipAppointmentId || null, ...revision }
  if (draft.section === 'PERSON') return { ...(draft.baseline ? {} : { subject: form.subject }), displayName: form.name.trim(), active: form.active, approvalEligible: form.approvalEligible, ...revision }
  if (draft.section === 'APPOINTMENT') return { ...(draft.baseline ? {} : { personId: form.personId, departmentId: form.departmentId, positionId: form.positionId }), active: form.active, ...revision }
  return { ...(draft.baseline ? {} : { kind: draft.section, ...(draft.section === 'LEGAL_ENTITY' ? {} : { legalEntityId: form.legalEntityId }) }),
    name: form.name.trim(), ...(draft.section === 'DEPARTMENT' ? { parentDepartmentId: form.parentDepartmentId || null } : {}), active: form.active, ...revision }
}
function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
/** 跨页面恢复原组织写入，只有路径和原正文同时匹配才推进草稿基线。@author owlzhangfq@gmail.com */
export class OrganizationDrafts {
  private drafts = new Map<string, OrganizationDraft>()
  get(scope: string) { const value = this.drafts.get(scope); return value ? clone(value) : null }
  put(scope: string, draft: OrganizationDraft) { if (scope) this.drafts.set(scope, clone(draft)) }
  hasDrafts() { return [...this.drafts.values()].some(organizationDirty) }
  acknowledge(scope: string, path: string, body: string, result: OrganizationRecord) {
    const draft = this.drafts.get(scope)
    if (!draft || organizationPath(draft) !== path || JSON.stringify(organizationPayload(draft)) !== body) return false
    this.put(scope, { section: draft.section, mode: draft.mode, baseline: result, form: organizationForm(result) }); return true
  }
}
export const organizationDrafts = new OrganizationDrafts()
