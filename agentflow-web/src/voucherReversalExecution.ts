import { amountMinor, expenseError } from './expenses.js'
import { operationLabels } from './vouchers.js'
import { validateReversePosting, type VoucherReversalBinding, type ReversalOriginal, type ReversalLine, type ReversePosting } from './voucherReversal.js'

/** 拟发送分录来自原命令，页面只能审阅，不能重新填写科目或金额。 */
export type ReversalCommandLine = Omit<ReversalLine, 'entryReference'>
export interface ReversalCandidate { createdAt: string; expiresAt: string; originalRevision: number; originalObservedAt: string; periodReference: string; periodSourceVersion: string; lines: ReversalCommandLine[] }
export interface ReversalPreparation {
  id: string; version: number; status: keyof typeof reversalPreparationLabels; requestedAt: string; updatedAt: string; issue: string | null
  accountingDate: string; evidenceReference: string; reason: string; candidate: ReversalCandidate | null; canAuthorize: boolean; authorizationIssue: string | null
}
export interface ReversalExecutionObservation { status: 'NOT_FOUND' | 'PENDING' | 'POSTED' | 'FAILED'; revision: number; observedAt: string; acceptanceReference: string | null; posting: ReversePosting | null; rejection: string | null }
export interface ReversalExecution {
  id: string; version: number; status: keyof typeof reversalExecutionLabels; attempts: number; highestRevision: number; failure: string | null
  createdAt: string; sendExpiresAt: string; updatedAt: string; nextAttemptAt: string | null; authorizedBy: string; accountingDate: string; evidenceReference: string; reason: string
  lines: ReversalCommandLine[]; observation: ReversalExecutionObservation | null; conflictingObservation: ReversalExecutionObservation | null; canQuery: boolean; canResendOriginal: boolean
}
export interface VoucherReversalExecutionView extends VoucherReversalBinding {
  originalStatus: keyof typeof operationLabels; originalHeld: boolean; original: ReversalOriginal; canPrepare: boolean; latestPreparation: ReversalPreparation | null; operation: ReversalExecution | null
}
interface ReversalVersions { roundNo: number; applicationVersion: number; businessVersion: number; operationVersion: number; comment: string }
export interface ReversalPrepareInput extends ReversalVersions { accountingDate: string; evidenceReference: string }
export interface ReversalAuthorizeInput extends ReversalVersions { preparationId: string; preparationVersion: number }
export interface ReversalOperationInput extends ReversalVersions { reversalId: string; reversalVersion: number; action: 'QUERY' | 'RESEND_ORIGINAL' }
export type ReversalExecutionInput = ReversalPrepareInput | ReversalAuthorizeInput | ReversalOperationInput
export interface ReversalExecutionReceipt { applicationId: string; operationId: string; roundNo: number; preparationId: string; preparationVersion: number; reversalId: string | null; reversalVersion: number | null; operationVersion: number; auditEventId: string }
export type ReversalExecutionAction = 'PREPARE' | 'AUTHORIZE' | 'QUERY' | 'RESEND_ORIGINAL'
export const reversalPreparationLabels = { QUEUED: '等待核对原凭证与期间', RUNNING: '正在读取会计依据', READY: '分录已准备，等待财务授权', AUTHORIZED: '财务已授权', UNAVAILABLE: '会计依据暂不可用', VOIDED: '原件或财务资格已变化' }
export const reversalExecutionLabels = { QUEUED: '已授权，等待发送 ERP', POSTING: '正在请求独立冲销', QUERYING: '正在核对原冲销结果', UNKNOWN: '冲销结果待确认', POSTED: '反向凭证已过账', FAILED: 'ERP 已明确拒绝冲销', NOT_FOUND: 'ERP 确认原冲销不存在', EXPIRED: '发送依据已过期', VOIDED: '原冲销发送已停止', RECONCILING: '冲销回执存在争议' }
export const reversalExecutionActions: Record<ReversalExecutionAction, string> = { PREPARE: '准备独立冲销', AUTHORIZE: '授权执行独立冲销', QUERY: '查询原冲销结果', RESEND_ORIGINAL: '按原冲销编号重发' }
const issues: Record<string, string> = {
  VOUCHER_REVERSAL_NOT_READY: '准备尚未就绪，暂不能授权执行。', VOUCHER_REVERSAL_PENDING: '准备正在处理，请刷新查看。',
  VOUCHER_REVERSAL_EXPIRED: '准备依据已过期，请重新选择日期并核对。', VOUCHER_REVERSAL_EVIDENCE_EXPIRED: '准备依据已过期，请重新选择日期并核对。',
  VOUCHER_REVERSAL_SOURCE_CHANGED: '原凭证或业务版本已变化，请刷新核对。', VOUCHER_REVERSAL_OPERATION_EXISTS: '原凭证已经有冲销命令，请核对原操作。',
  VOUCHER_REVERSAL_ALREADY_RECORDED: '原凭证已登记独立冲销，不能再次办理。', VOUCHER_REVERSAL_OPERATION_CONFLICT: '原冲销操作已变化，请刷新核对。',
  VOUCHER_REVERSAL_PREPARATION_CONFLICT: '准备版本已变化，请刷新核对。', CONCURRENCY_CONFLICT: '页面版本已变化，请刷新原凭证后核对。',
  EVIDENCE_EXPIRED: '发送依据已过期，原凭证继续停用。', SOURCE_CHANGED: '原件或财务资格已变化，原凭证继续停用。',
  TIMEOUT: 'ERP 读取超时，请刷新核对实际状态。', TARGET_CHANGED: '原 ERP 配置已变化，请联系财务管理员。',
  CONNECTION: '连接中断，实际结果待查询。', INVALID_RESPONSE: 'ERP 回执未通过核验，实际结果待查询。',
  STALE_OBSERVATION: '返回依据早于已知会计版本，需核对原件。', INCONSISTENT_OBSERVATION: 'ERP 回执与已知事实不一致，需核对原件。',
  ACCOUNTING_PERIOD_CLOSED: '所选日期的会计期间未开放。', ORIGINAL_NOT_POSTED: 'ERP 原凭证不处于有效过账状态。', ORIGINAL_CHANGED: 'ERP 原凭证已变化。',
  REVERSAL_ALREADY_EXISTS: 'ERP 已有对应冲销，请核对原编号。', AUTHORIZATION_REJECTED: 'ERP 未接受本次财务授权。',
  ACCOUNT_UNAVAILABLE: '原分录科目当前不可用于过账。', LEGAL_ENTITY_UNAVAILABLE: '当前法人暂不能办理会计过账。'
}
export const reversalExecutionIssue = (code: string) => issues[code] ?? '当前冲销条件未满足，请核对原凭证与会计依据。'
export const reversalExecutionError = (cause: unknown) => cause instanceof Error ? cause.message : issues[(cause as { code?: string })?.code ?? ''] ?? expenseError(cause)
function check(condition: unknown): asserts condition { if (!condition) throw new Error('冲销准备或执行状态未通过核对，请刷新原凭证。') }
function id(value: unknown): asserts value is string { check(typeof value === 'string' && !!value.trim() && value.length <= 128) }
function positive(value: number) { check(Number.isSafeInteger(value) && value > 0) }
function count(value: number) { check(Number.isSafeInteger(value) && value >= 0) }
function instant(value: string) { check(typeof value === 'string' && Number.isFinite(Date.parse(value))); return Date.parse(value) }
function date(value: string) { check(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value) }
function note(value: string) { if (typeof value !== 'string' || !value.trim() || value.length > 2000) throw new Error('请填写 2000 字以内的办理说明。'); return value.trim() }
function optionalCode(value: string | null) { check(value === null || typeof value === 'string' && !!value) }
function reference(value: string) { if (!value.trim() || value.length > 128 || /[\u0000-\u001f\u007f-\u009f]/u.test(value)) throw new Error('请填写 128 字以内、不含控制字符的核对材料编号。'); return value.trim() }
function lines(value: ReversalCommandLine[], original: ReversalOriginal) {
  check(Array.isArray(value) && value.length >= 2); let debit = 0n, credit = 0n
  value.forEach((line, index) => {
    check(line && line.originalLineNo === index + 1); id(line.accountCode); count(line.sourceLineNo)
    check(['DEBIT', 'CREDIT'].includes(line.side) && line.amount && line.amount.currency === original.total.currency)
    const amount = amountMinor(line.amount.value); check(amount > 0n)
    for (const dimension of [line.costCenter, line.projectCode, line.advanceId]) if (dimension != null) id(dimension)
    if (line.side === 'DEBIT') debit += amount; else credit += amount
  })
  check(debit === credit && debit === amountMinor(original.total.value))
}
function observation(value: ReversalExecutionObservation | null, operation: ReversalExecution, original: ReversalOriginal) {
  if (value === null) return
  check(value && ['NOT_FOUND', 'PENDING', 'POSTED', 'FAILED'].includes(value.status)); count(value.revision)
  check(value.revision <= operation.highestRevision && instant(value.observedAt) >= instant(operation.createdAt) && instant(value.observedAt) <= instant(operation.updatedAt))
  if (value.status === 'NOT_FOUND') check(value.revision === 0 && value.acceptanceReference === null && value.posting === null && value.rejection === null)
  else {
    positive(value.revision); id(value.acceptanceReference)
    if (value.status === 'FAILED') id(value.rejection); else check(value.rejection === null)
    if (value.status !== 'POSTED') check(value.posting === null)
    else {
      check(value.posting); validateReversePosting(value.posting, original, instant(value.observedAt))
      check(value.posting.accountingDate === operation.accountingDate && value.posting.lines.length === operation.lines.length && instant(value.posting.postedAt) >= instant(operation.createdAt))
      value.posting.lines.forEach((line, index) => {
        const expected = operation.lines[index]!
        check(line.accountCode === expected.accountCode && line.side === expected.side && amountMinor(line.amount.value) === amountMinor(expected.amount.value)
          && line.sourceLineNo === expected.sourceLineNo && (line.costCenter ?? null) === (expected.costCenter ?? null) && (line.projectCode ?? null) === (expected.projectCode ?? null) && (line.advanceId ?? null) === (expected.advanceId ?? null))
      })
    }
  }
}
/** 同时验证父页面版本、会计合计和确认能力，错轮或不完整回执不能成为操作依据。 */
export function validateReversalExecution(value: VoucherReversalExecutionView, expected: VoucherReversalBinding) {
  check(value && value.original && ['applicationId', 'operationId', 'roundNo', 'applicationVersion', 'businessVersion', 'operationVersion', 'kind'].every(key => value[key as keyof VoucherReversalBinding] === expected[key as keyof VoucherReversalBinding]))
  id(value.applicationId); id(value.operationId); [value.roundNo, value.applicationVersion, value.businessVersion, value.operationVersion].forEach(positive)
  check(['EMPLOYEE_ADVANCE', 'EXPENSE_ACCRUAL', 'PAYMENT'].includes(value.kind) && Object.prototype.hasOwnProperty.call(operationLabels, value.originalStatus) && typeof value.originalHeld === 'boolean' && typeof value.canPrepare === 'boolean')
  const original = value.original; id(original.postingReference); id(original.voucherReference); id(original.periodReference); date(original.accountingDate); instant(original.postedAt)
  check(original.total && /^[A-Z]{3}$/.test(original.total.currency) && amountMinor(original.total.value) > 0n)
  const prepared = value.latestPreparation, operation = value.operation
  check(value.originalHeld === (operation !== null))
  if (prepared !== null) {
    check(prepared && Object.prototype.hasOwnProperty.call(reversalPreparationLabels, prepared.status)); id(prepared.id); positive(prepared.version)
    check(instant(prepared.requestedAt) <= instant(prepared.updatedAt) && typeof prepared.canAuthorize === 'boolean'); date(prepared.accountingDate); check(prepared.accountingDate >= original.accountingDate)
    reference(prepared.evidenceReference); note(prepared.reason); optionalCode(prepared.issue); optionalCode(prepared.authorizationIssue)
    check(['READY', 'AUTHORIZED'].includes(prepared.status) ? prepared.candidate !== null && prepared.issue === null : prepared.candidate === null && !prepared.canAuthorize)
    if (prepared.candidate !== null) {
      const candidate = prepared.candidate; check(candidate); const created = instant(candidate.createdAt), until = instant(candidate.expiresAt), observed = instant(candidate.originalObservedAt)
      check(created >= instant(prepared.requestedAt) && created <= instant(prepared.updatedAt) && observed <= created && created - observed <= 300_000 && until > created && until - created <= 300_000)
      positive(candidate.originalRevision); id(candidate.periodReference); id(candidate.periodSourceVersion); lines(candidate.lines, original)
    }
    if (prepared.canAuthorize) check(prepared.status === 'READY' && prepared.authorizationIssue === null && !value.originalHeld && operation === null && value.originalStatus === 'POSTED')
    if (prepared.status === 'AUTHORIZED') check(operation && operation.id === prepared.id && !prepared.canAuthorize)
  }
  if (value.canPrepare) check(value.originalStatus === 'POSTED' && !value.originalHeld && operation === null && (!prepared || !['QUEUED', 'RUNNING', 'AUTHORIZED'].includes(prepared.status)))
  if (operation !== null) {
    check(operation && Object.prototype.hasOwnProperty.call(reversalExecutionLabels, operation.status)); id(operation.id); positive(operation.version); count(operation.attempts); count(operation.highestRevision); optionalCode(operation.failure)
    check(operation.id !== value.operationId && instant(operation.createdAt) <= instant(operation.updatedAt) && instant(operation.sendExpiresAt) > instant(operation.createdAt))
    id(operation.authorizedBy); date(operation.accountingDate); check(operation.accountingDate >= original.accountingDate); reference(operation.evidenceReference); note(operation.reason); lines(operation.lines, original)
    check(typeof operation.canQuery === 'boolean' && typeof operation.canResendOriginal === 'boolean')
    if (['QUEUED', 'UNKNOWN'].includes(operation.status)) check(operation.nextAttemptAt !== null && instant(operation.nextAttemptAt) >= instant(operation.updatedAt)); else check(operation.nextAttemptAt === null)
    observation(operation.observation, operation, original); observation(operation.conflictingObservation, operation, original)
    if (operation.highestRevision > 0) check(operation.observation !== null || operation.conflictingObservation !== null)
    if (['POSTED', 'FAILED', 'NOT_FOUND'].includes(operation.status)) check(operation.observation?.status === operation.status && operation.observation.revision === operation.highestRevision && operation.conflictingObservation === null && operation.failure === null)
    if (operation.status === 'RECONCILING') check(operation.conflictingObservation && operation.failure)
    if (operation.canQuery) check(!['QUEUED', 'POSTING', 'QUERYING'].includes(operation.status))
    if (operation.canResendOriginal) check(operation.status === 'NOT_FOUND' && operation.highestRevision === 0 && operation.conflictingObservation === null && operation.canQuery)
  }
  return value
}
function versions(value: VoucherReversalExecutionView, comment: string): ReversalVersions { return { roundNo: value.roundNo, applicationVersion: value.applicationVersion, businessVersion: value.businessVersion, operationVersion: value.operationVersion, comment: note(comment) } }
/** 只提交人工选择的日期与材料；金额、科目、目标由服务端固定。 */
export function reversalPrepareInput(value: VoucherReversalExecutionView, accountingDate: string, evidenceReference: string, comment: string): ReversalPrepareInput {
  validateReversalExecution(value, value); check(value.canPrepare); date(accountingDate)
  if (accountingDate < value.original.accountingDate) throw new Error('冲销日期不能早于原凭证日期。')
  return { ...versions(value, comment), accountingDate, evidenceReference: reference(evidenceReference) }
}
/** 明确确认时再次检查时效，不通过重新读取自动延长原授权。 */
export function reversalAuthorizeInput(value: VoucherReversalExecutionView, comment: string, now = Date.now()): ReversalAuthorizeInput {
  validateReversalExecution(value, value); const prepared = value.latestPreparation
  check(prepared?.canAuthorize && prepared.candidate && instant(prepared.candidate.createdAt) <= now && instant(prepared.candidate.expiresAt) > now)
  return { ...versions(value, comment), preparationId: prepared.id, preparationVersion: prepared.version }
}
/** 查询可跨发送期限继续；只有权威查无且原期限内才允许明确重发。 */
export function reversalOperationInput(value: VoucherReversalExecutionView, action: ReversalOperationInput['action'], comment: string, now = Date.now()): ReversalOperationInput {
  validateReversalExecution(value, value); const operation = value.operation; check(operation)
  check(action === 'QUERY' ? operation.canQuery : operation.canResendOriginal && instant(operation.sendExpiresAt) > now)
  return { ...versions(value, comment), reversalId: operation.id, reversalVersion: operation.version, action }
}
/** 授权会增加原凭证版本，父面板必须刷新；准备和查询都保持原凭证版本。 */
export function validateReversalExecutionReceipt(receipt: ReversalExecutionReceipt, value: VoucherReversalExecutionView, input: ReversalExecutionInput) {
  check(receipt && receipt.applicationId === value.applicationId && receipt.operationId === value.operationId && receipt.roundNo === input.roundNo)
  id(receipt.preparationId); positive(receipt.preparationVersion); id(receipt.auditEventId)
  if ('preparationId' in input) check(receipt.preparationId === input.preparationId && receipt.preparationVersion === input.preparationVersion + 1 && receipt.reversalId === input.preparationId && receipt.reversalVersion === 1 && receipt.operationVersion === input.operationVersion + 1)
  else if ('reversalId' in input) {
    check(receipt.preparationId === input.reversalId && receipt.reversalId === input.reversalId && receipt.reversalVersion === input.reversalVersion + 1 && receipt.operationVersion === input.operationVersion)
    if (value.latestPreparation?.id === input.reversalId) check(receipt.preparationVersion === value.latestPreparation.version)
  } else check(receipt.preparationVersion === 1 && receipt.reversalId === null && receipt.reversalVersion === null && receipt.operationVersion === input.operationVersion)
}
