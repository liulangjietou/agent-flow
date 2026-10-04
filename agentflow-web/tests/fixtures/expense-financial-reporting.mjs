export const uuid = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
export const voucherStates = ['QUEUED','POSTING','QUERYING','UNKNOWN','FAILED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING','REVERSED']
export const paymentStates = ['QUEUED','CHECKING','SENDING','QUERYING','UNKNOWN','FAILED','NOT_FOUND','EXPIRED','VOIDED','RECONCILING','REVERSED']
export const ageBands = ['NOT_DUE','DAYS_1_30','DAYS_31_60','DAYS_61_90','OVER_90','UNKNOWN']
export function report(filters = { from: '2026-09-05', to: '2026-10-04' }) {
  const cycle = () => ({ samples: 0, unknown: 0, pending: 0, notApplicable: 0, p50Seconds: null, p90Seconds: null })
  return {
    generatedAt: '2026-10-04T14:00:00.123456Z', timeZone: 'UTC', scope: 'CURRENT_ACTOR_READABLE',
    filters: { from: filters.from, to: filters.to, legalEntityId: filters.legalEntityId ?? null, departmentId: filters.departmentId ?? null, categoryCode: filters.categoryCode ?? null },
    totals: { submitted: 0, approved: 0, returned: 0, rejected: 0, withdrawn: 0, cancelled: 0, inApproval: 0, overLimit: 0, reduced: 0,
      overLimitRate: null, reductionRate: null, returnRate: null, approval: cycle(), payment: cycle(), endToEnd: cycle(), amounts: [], returnReasons: [] },
    groups: [], activity: { verification: { succeeded: 0, rejected: 0, unavailable: 0, pending: 0, failureRate: null }, duplicatePrechecks: 0,
      duplicateSubmissions: { recorded: 0, recordingStartedAt: '2026-10-04T12:00:00Z', containsUnrecordedHistory: true } },
    resources: { asOf: '2026-10-04T14:00:00.123456Z', advanceCategoryApplicable: !filters.categoryCode, advances: [], priorRequests: [] },
    backlog: { asOf: '2026-10-04T14:00:00.123456Z', vouchers: Object.fromEntries(voucherStates.map(s => [s,0])), payments: Object.fromEntries(paymentStates.map(s => [s,0])) }
  }
}
