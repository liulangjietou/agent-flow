import type { InitiatorContext } from './initiatorContext'

export interface ExpenseProjectOwner { legalEntityId: string; code: string; name: string; ownerSubject: string }
export interface ExpenseProjectOwners { catalogVersion: string; legalEntityId: string; projects: ExpenseProjectOwner[] }
export interface ProjectResponsibility {
  nodeName: string; stage: 'PROJECT_REVIEW'; rule: 'expense:projectOwners'; directoryRevision: number
  originalSubjects: string[]; candidateSubjects: string[]
  escalation?: { supervisorAppointmentId: string; originalSubject: string; replacementSubject: string; directoryRevision: number } | null
}
export interface ProjectApprovalDetails {
  ruleVersion: 1; precheckId: string; precheckVersion: 3; applicationVersion: number; financialVersion: number
  definitionId: string; definitionVersion: number; nodeId: string | null; submittedAt: string
  source: ExpenseProjectOwners; initiator: InitiatorContext; responsibility: ProjectResponsibility | null
}
export interface ProjectApprovalView {
  reportId: string; applicationId: string; roundNo: number; status: 'NOT_RECORDED' | 'NO_PROJECT' | 'RECORDED'
  details: ProjectApprovalDetails | null
}
const MAX_PROJECTS = 2000
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) > 0
const nonBlankText = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const text = (value: unknown, limit = 128): value is string => nonBlankText(value) && value.length <= limit
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const own = (value: object, key: string) => Object.prototype.hasOwnProperty.call(value, key)
function keys(value: unknown, required: string[], optional: string[] = []): value is Record<string, unknown> {
  return object(value) && required.every(key => own(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key))
}
function requireValue(valid: unknown): asserts valid { if (!valid) throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '项目负责人依据不完整或与原轮次不符，请重新读取。' } }
const subjects = (value: unknown): value is string[] => Array.isArray(value) && value.length > 0 && value.length <= MAX_PROJECTS && value.every(item => text(item)) && new Set(value).size === value.length
const sameSubjects = (left: string[], right: string[]) => left.length === right.length && left.every(subject => right.includes(subject))

/** 预检与原轮次共用可信来源格式；项目代码唯一、负责人明确且全部属于同一法人。 */
export function readProjectOwners(value: unknown, legalEntityId?: string): ExpenseProjectOwners {
  requireValue(keys(value, ['catalogVersion', 'legalEntityId', 'projects']) && text(value.catalogVersion) && uuid(value.legalEntityId)
    && (legalEntityId === undefined || value.legalEntityId === legalEntityId) && Array.isArray(value.projects) && value.projects.length <= MAX_PROJECTS)
  const codes = new Set<string>()
  for (const project of value.projects) {
    requireValue(keys(project, ['legalEntityId', 'code', 'name', 'ownerSubject']) && project.legalEntityId === value.legalEntityId
      && text(project.code) && !codes.has(project.code) && text(project.name) && text(project.ownerSubject))
    codes.add(project.code)
  }
  return value as unknown as ExpenseProjectOwners
}

/** 原负责人、上溯依据及必要责任人数一起核对，未知历史不能显示成无需审批。 */
export function readProjectApprovalView(value: unknown, reportId: string, applicationId: string, roundNo: number): ProjectApprovalView {
  requireValue(keys(value, ['reportId', 'applicationId', 'roundNo', 'status', 'details']) && uuid(value.reportId) && value.reportId === reportId
    && uuid(value.applicationId) && value.applicationId === applicationId && positive(value.roundNo) && value.roundNo === roundNo)
  if (value.status === 'NOT_RECORDED') requireValue(value.details === null)
  else {
    const details = value.details
    requireValue((value.status === 'NO_PROJECT' || value.status === 'RECORDED') && keys(details,
      ['ruleVersion', 'precheckId', 'precheckVersion', 'applicationVersion', 'financialVersion', 'definitionId', 'definitionVersion', 'nodeId', 'submittedAt', 'source', 'initiator', 'responsibility'])
      && details.ruleVersion === 1 && uuid(details.precheckId) && details.precheckVersion === 3 && positive(details.applicationVersion) && details.applicationVersion >= 2
      && positive(details.financialVersion) && details.financialVersion >= 2 && uuid(details.definitionId) && positive(details.definitionVersion)
      && (details.nodeId === null || text(details.nodeId)) && typeof details.submittedAt === 'string' && Number.isFinite(Date.parse(details.submittedAt)))
    const source = readProjectOwners(details.source), initiator = details.initiator
    requireValue(keys(initiator, ['appointmentId', 'personId', 'subject', 'directoryRevision', 'legalEntityId', 'legalEntityName', 'departmentId', 'departmentName', 'positionId', 'positionName'])
      && uuid(initiator.appointmentId) && uuid(initiator.personId) && text(initiator.subject) && positive(initiator.directoryRevision)
      && initiator.legalEntityId === source.legalEntityId && uuid(initiator.departmentId) && uuid(initiator.positionId)
      && text(initiator.legalEntityName) && text(initiator.departmentName) && text(initiator.positionName))
    if (value.status === 'NO_PROJECT') requireValue(source.projects.length === 0 && details.responsibility === null)
    else {
      const responsibility = details.responsibility
      requireValue(source.projects.length > 0 && text(details.nodeId) && keys(responsibility,
        ['nodeName', 'stage', 'rule', 'directoryRevision', 'originalSubjects', 'candidateSubjects'], ['escalation'])
        && nonBlankText(responsibility.nodeName) && responsibility.stage === 'PROJECT_REVIEW' && responsibility.rule === 'expense:projectOwners'
        && positive(responsibility.directoryRevision) && subjects(responsibility.originalSubjects) && subjects(responsibility.candidateSubjects)
        && sameSubjects([...new Set(source.projects.map(project => project.ownerSubject))], responsibility.originalSubjects))
      const self = responsibility.originalSubjects.includes(initiator.subject), escalation = responsibility.escalation
      requireValue(self === (escalation != null))
      if (escalation != null) requireValue(keys(escalation, ['supervisorAppointmentId', 'originalSubject', 'replacementSubject', 'directoryRevision'])
        && uuid(escalation.supervisorAppointmentId) && escalation.originalSubject === initiator.subject && text(escalation.replacementSubject)
        && escalation.replacementSubject !== escalation.originalSubject && escalation.directoryRevision === responsibility.directoryRevision)
      const replacement = escalation as ProjectResponsibility['escalation']
      const expected = responsibility.originalSubjects.map(subject => subject === replacement?.originalSubject ? replacement.replacementSubject : subject)
      requireValue(sameSubjects([...new Set(expected)], responsibility.candidateSubjects))
    }
  }
  return value as unknown as ProjectApprovalView
}
