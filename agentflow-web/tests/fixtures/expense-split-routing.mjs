export const uuid = n => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
export const at = '2026-10-04T10:00:00.000001Z', from = '2026-09-27T10:00:00.000001Z'
export const money = value => ({ value, currency: 'CNY' })
export function document(n, status = 'DRAFT') { return { reportId: uuid(n), applicationId: uuid(n + 20), applicationVersion: 2, financialVersion: 2, roundNo: 1,
  scope: { tenantId: 'demo', employeeId: 'alice', legalEntityId: uuid(30), currency: 'CNY' }, submittedAt: at, status,
  lines: [{ lineNo: 1, categoryCode: 'OFFICE', approvedGross: money('4000.00') }] } }
export function view(status = 'SPLIT_SUSPECTED') {
  const value = { reportId: uuid(1), applicationId: uuid(21), roundNo: 1, status, sourcesReadable: true, details: null }
  if (['RESTRICTED', 'NOT_RECORDED'].includes(status)) return { ...value, sourcesReadable: false }
  const mode = ['UNCONFIGURED', 'DISABLED'].includes(status) ? status : 'ENABLED'
  value.details = { ruleVersion: 1, definitionId: uuid(40), processKey: 'expense-split', definitionVersion: 1,
    configuration: { mode, ...(mode === 'UNCONFIGURED' ? {} : { rule: { windowDays: 7, threshold: money('5000.00') } }), gatewayIds: mode === 'UNCONFIGURED' ? [] : ['gate'] }, primary: document(1) }
  if (mode === 'ENABLED') value.details.assessment = { windowFrom: from, assessedAt: at, ownAmount: money('4000.00'), routingAmount: money(status === 'CLEAR' ? '4000.00' : '8000.00'),
    categories: [{ categoryCode: 'OFFICE', total: money(status === 'CLEAR' ? '4000.00' : '8000.00'), reportCount: status === 'CLEAR' ? 1 : 2, triggered: status !== 'CLEAR' }],
    sources: status === 'CLEAR' ? [document(1)] : [document(1), document(2, 'IN_APPROVAL')] }
  return value
}
