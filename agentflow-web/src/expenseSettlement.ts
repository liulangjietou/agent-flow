export const settlementLabels = { QUEUED: '等待核销', BLOCKED: '核销需要处理', BUDGET_PENDING: '等待预算实际占用', BUDGET_REJECTED: '预算核销未通过', SETTLED: '核销已完成', REVIEW_REQUIRED: '资金或凭证需要人工核对' }
export const settlementFundingLabels = { PAYMENT: '银行已确认本次应付到账', FULL_OFFSET: '借款全额冲销，挂账已确认', ZERO_AMOUNT: '本次核定金额为零' }
export const settlementBudgetLabels = { QUEUED: '等待处理', EXECUTING: '处理中', UNKNOWN: '结果待确认', QUERYING: '正在核对原操作', APPLIED: '实际占用已确认', REJECTED: '未通过' }
export interface SettlementBinding { reportId: string; applicationId: string; roundNo: number; applicationVersion: number; financialVersion: number }
export interface SettlementState { version: number; status: keyof typeof settlementLabels; resourcesConsumed: boolean; budgetStatus: keyof typeof settlementBudgetLabels | null; issue: string | null; funding: keyof typeof settlementFundingLabels; fundingConfirmedAt: string; updatedAt: string }
export interface SettlementView extends SettlementBinding { settlement: SettlementState | null; canRetry: boolean }
export interface SettlementRetry { roundNo: number; applicationVersion: number; financialVersion: number; settlementVersion: number; comment: string }
export interface SettlementReceipt { reportId: string; applicationId: string; roundNo: number; settlementVersion: number; auditEventId: string }
const known = (values: object, key: string) => Object.prototype.hasOwnProperty.call(values, key)
const positive = (value: number) => Number.isSafeInteger(value) && value > 0
const validTime = (value: string) => typeof value === 'string' && Number.isFinite(Date.parse(value))

/** 轮次和三项版本绑定当前明细，预算排队、拒绝或争议不能显示为核销完成。 */
export function validateSettlement(value: SettlementView, binding: SettlementBinding): SettlementView {
  if (!value || !(['reportId', 'applicationId', 'roundNo', 'applicationVersion', 'financialVersion'] as const).every(key => value[key] === binding[key])
      || !value.reportId || !value.applicationId || ![value.roundNo, value.applicationVersion, value.financialVersion].every(positive)
      || typeof value.canRetry !== 'boolean' || value.settlement === undefined) throw new Error('结算轮次或版本已变化，请刷新业务明细。')
  const current = value.settlement
  if (current) {
    const budgetStage = ['BUDGET_PENDING', 'BUDGET_REJECTED', 'SETTLED'].includes(current.status)
    const problem = ['BLOCKED', 'BUDGET_REJECTED', 'REVIEW_REQUIRED'].includes(current.status)
    if (!positive(current.version) || !known(settlementLabels, current.status) || !known(settlementFundingLabels, current.funding)
        || typeof current.resourcesConsumed !== 'boolean' || !validTime(current.fundingConfirmedAt) || !validTime(current.updatedAt) || Date.parse(current.updatedAt) < Date.parse(current.fundingConfirmedAt)
        || (current.budgetStatus !== null && !known(settlementBudgetLabels, current.budgetStatus))
        || (budgetStage && (!current.resourcesConsumed || !current.budgetStatus)) || (!current.resourcesConsumed && current.budgetStatus !== null)
        || (['QUEUED', 'BLOCKED'].includes(current.status) && current.budgetStatus !== null)
        || (current.status === 'SETTLED' && current.budgetStatus !== 'APPLIED') || (current.status === 'BUDGET_REJECTED' && current.budgetStatus !== 'REJECTED')
        || (current.status === 'BUDGET_PENDING' && ['APPLIED', 'REJECTED'].includes(current.budgetStatus!))
        || (problem ? typeof current.issue !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(current.issue) : current.issue !== null)) throw new Error('结算凭据或处理进度不一致，请刷新核对。')
  }
  if (value.canRetry && (!current || !['BLOCKED', 'BUDGET_REJECTED'].includes(current.status))) throw new Error('当前结算不允许重新办理。')
  return value
}

/** 只提交原展示版本和人工说明，已消费资源的标记不能由客户端重置。 */
export function settlementRetry(value: SettlementView, comment: string): SettlementRetry {
  validateSettlement(value, value)
  if (!value.canRetry || !value.settlement) throw new Error('当前身份或结算状态不允许重试，请刷新核对。')
  if (!comment.trim() || comment.length > 2000) throw new Error('请填写 2000 字以内的处理说明。')
  return { roundNo: value.roundNo, applicationVersion: value.applicationVersion, financialVersion: value.financialVersion, settlementVersion: value.settlement.version, comment: comment.trim() }
}
/** 排队回执不代表结算完成，回放后仍需读取最新状态。 */
export function validateSettlementReceipt(receipt: SettlementReceipt, value: SettlementView, input: SettlementRetry) {
  if (!receipt || receipt.reportId !== value.reportId || receipt.applicationId !== value.applicationId || receipt.roundNo !== input.roundNo
      || receipt.settlementVersion !== input.settlementVersion + 1 || !receipt.auditEventId) throw new Error('结算重试回执不匹配，请刷新核对原操作。')
}
const issues: Record<string, string> = {
  ADVANCE_PAYMENT_REVIEW_REQUIRED: '本次冲销的借款付款存在争议，请由财务先核对原借款。', INVOICE_VERIFICATION_REQUIRED: '发票存在新的未完成或未通过查验，请重新取得成功验票结果。', INVOICE_VERIFICATION_EXPIRED: '发票查验已过期，请重新验票。',
  VOUCHER_SOURCE_CHANGED: '原批准或财务版本已变化，需要核对本次结算依据。', VOUCHER_BUDGET_NOT_FROZEN: '原核定金额的预算冻结尚未确认。', EXPENSE_RESERVATION_CHANGED: '本轮发票、额度或借款预留发生变化，需要核对原占用。',
  EXPENSE_PAYMENT_NOT_CONFIRMED: '原付款正在核对，请先确认银行结果。', EXPENSE_VOUCHER_NOT_CONFIRMED: '原挂账凭证正在核对，请先确认 ERP 结果。', EXPENSE_PAYMENT_REVIEW: '原付款发生退回或结果冲突，已暂停结算自动处理。', EXPENSE_VOUCHER_REVIEW: '原挂账凭证发生冲回或结果冲突，已暂停结算自动处理。',
  BUDGET_ACCOUNTING_PERIOD_CLOSED: '预算会计期间已关闭，请由财务核对后重试。', BUDGET_LEDGER_VERSION_CONFLICT: '外部预算版本不一致，请先核对预算台账。', EXPENSE_FUNDING_CONFLICT: '结算资金依据与已登记事实不一致，需要人工核对。'
}
export function settlementIssue(code: string | null) { return code ? issues[code] ?? '结算依据需要核对，请联系财务处理后再决定是否重试。' : '' }
export function settlementError(cause: unknown) {
  const error = cause as { status?: number; code?: string }
  if (error.status === 403 || error.status === 404) return '当前身份无权查看或办理这份报销结算。'
  if (error.status === 409) return '结算或业务版本已变化，请刷新后重新核对。'
  if (error.status === 0 || error.code === 'PENDING_REQUEST_CHANGED') return '结果尚未确认，请恢复原请求后刷新结算状态。'
  return cause instanceof Error ? cause.message : '结算操作未完成，请刷新核对。'
}
