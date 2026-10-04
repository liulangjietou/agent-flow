import { amountMinor, expenseTypes, type ExpenseContent, type ExpenseLine, type ExpensePolicySelection, type ExpenseAllowanceBasis } from './expenses.js'
import { readPolicyConstraints, expenseUnits, type PolicyConstraints } from './expenseConfiguration.js'
import type { FinanceCatalog } from './expenseDraft'

export interface PolicyGuidanceContext {
  legalEntityId: string; reportType: ExpenseContent['type']; categoryCode: string; cityCode: string
  incurredOn: string; currency: string; unit: ExpenseLine['unit']
  endedOn?: string
}
export interface PolicyGuidance {
  policyId: string; policyVersion: number; policyName: string; ruleKey: string; ruleName: string
  constraints: PolicyConstraints; factSourceReference: string; validUntil: string; selection: ExpensePolicySelection | null
}
export interface PolicyGuidanceView { context: PolicyGuidanceContext; guidance: PolicyGuidance; allowance?: ExpenseAllowanceBasis | null }
const dimensions = ['legalEntityId', 'reportType', 'categoryCode', 'cityCode', 'incurredOn', 'currency', 'unit'] as const
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const positive = (value: unknown) => Number.isSafeInteger(value) && (value as number) > 0
const text = (value: unknown, max: number): value is string => typeof value === 'string' && !!value.trim() && value === value.trim() && value.length <= max && !/[\x00-\x1f\x7f]/.test(value)
const day = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value

/** 只在本人目录内的匹配条件完整后查询，金额和数量变化无需重复访问外部系统。 */
export function policyGuidanceContext(legalEntityId: string, reportType: ExpenseContent['type'], line: ExpenseLine, catalog: FinanceCatalog): PolicyGuidanceContext | null {
  if (!uuid(legalEntityId) || !catalog.legalEntities.some(entity => entity.id === legalEntityId) || !Object.prototype.hasOwnProperty.call(expenseTypes, reportType)
    || !catalog.categories.some(category => category.code === line.categoryCode && category.units.includes(line.unit))
    || !catalog.cities.some(city => city.code === line.cityCode) || !day(line.incurredOn) || !/^[A-Z]{3}$/.test(line.claimedGross.currency)
    || line.endedOn && (!day(line.endedOn) || line.endedOn < line.incurredOn)) return null
  return { legalEntityId, reportType, categoryCode: line.categoryCode, cityCode: line.cityCode, incurredOn: line.incurredOn, currency: line.claimedGross.currency, unit: line.unit,
    ...(line.endedOn ? { endedOn: line.endedOn } : {}) }
}

/** 查询只投影匹配维度和可选行程结束日，不接受员工、职级或制度覆盖。 */
export function policyGuidanceQuery(context: PolicyGuidanceContext): string {
  const query = new URLSearchParams(dimensions.map(key => [key, context[key]]))
  if (context.endedOn) query.set('endedOn', context.endedOn)
  return query.toString()
}

/** 响应必须绑定本次输入和仍有效的版本，畸形内容不能显示为可用制度。 */
export function readPolicyGuidance(value: unknown, expected: PolicyGuidanceContext, now = Date.now()): PolicyGuidanceView {
  const invalid = () => { throw { status: 0, code: 'RESPONSE_UNREADABLE', message: '制度提示响应不完整，请刷新重试。' } }
  if (!object(value) || !object(value.context) || !object(value.guidance)) return invalid()
  const context = value.context
  if (!dimensions.every(key => context[key] === expected[key]) || (context.endedOn ?? null) !== (expected.endedOn ?? null)) return invalid()
  const advice = value.guidance as unknown as PolicyGuidance
  if (!uuid(advice.policyId) || !positive(advice.policyVersion) || !text(advice.policyName, 128) || !text(advice.ruleKey, 64)
    || !text(advice.ruleName, 128) || !text(advice.factSourceReference, 128) || !(Date.parse(advice.validUntil) > now)) return invalid()
  const selection = advice.selection ?? null
  if (selection && (!object(selection) || selection.policyId !== advice.policyId || selection.policyVersion !== advice.policyVersion
    || !positive(selection.categoryRevision) || !positive(selection.activeRevision) || typeof selection.definitionDigest !== 'string'
    || !/^[a-f0-9]{64}$/.test(selection.definitionDigest))) return invalid()
  let constraints: PolicyConstraints
  try {
    constraints = readPolicyConstraints(advice.constraints, expected.currency)
    if (constraints.unitPriceLimit) amountMinor(constraints.unitPriceLimit.value)
  } catch { return invalid() }
  let allowance: ExpenseAllowanceBasis | undefined
  if (constraints.fixedAllowance) {
    if (!selection || expected.unit !== 'DAY') return invalid()
    if (expected.endedOn) {
      const basis = value.allowance as ExpenseAllowanceBasis
      if (!object(basis) || !object(basis.policy) || !object(basis.policy.selection) || !object(basis.calculation)) return invalid()
      const calc = basis.calculation, days = Math.round((Date.parse(expected.endedOn) - Date.parse(expected.incurredOn)) / 86_400_000) + 1
      if (!day(expected.endedOn) || !day(expected.incurredOn) || days < 1 || days > 1_000_000 || calc.days !== days
        || calc.startsOn !== expected.incurredOn || calc.endsOn !== expected.endedOn || !object(calc.rule) || !object(calc.rule.dailyRate)
        || !object(calc.gross) || calc.gross.currency !== expected.currency || calc.rule.dayCountBasis !== constraints.fixedAllowance.dayCountBasis
        || calc.rule.dailyRate.currency !== expected.currency || basis.policy.ruleKey !== advice.ruleKey || basis.policy.factSourceReference !== advice.factSourceReference
        || Object.entries(selection).some(([key, entry]) => (basis.policy.selection as unknown as Record<string, unknown>)[key] !== entry)) return invalid()
      try {
        if (amountMinor(calc.rule.dailyRate.value) !== amountMinor(constraints.fixedAllowance.dailyRate.value)
          || amountMinor(calc.gross.value) !== amountMinor(calc.rule.dailyRate.value) * BigInt(days)) return invalid()
      } catch { return invalid() }
      allowance = { policy: { selection: { ...selection }, ruleKey: basis.policy.ruleKey, factSourceReference: basis.policy.factSourceReference },
        calculation: { startsOn: calc.startsOn, endsOn: calc.endsOn, days, rule: { dailyRate: { ...calc.rule.dailyRate }, dayCountBasis: calc.rule.dayCountBasis }, gross: { ...calc.gross } } }
    } else if (value.allowance != null) return invalid()
  } else if (value.allowance != null) return invalid()
  return { ...(allowance ? { allowance } : {}), context: { ...expected }, guidance: { policyId: advice.policyId, policyVersion: advice.policyVersion, policyName: advice.policyName,
    ruleKey: advice.ruleKey, ruleName: advice.ruleName, constraints, factSourceReference: advice.factSourceReference,
    validUntil: advice.validUntil, selection: selection ? { ...selection } : null } }
}

export interface GuidanceMessage { code: string; warning: boolean; text: string }
/** 只比较用户正在输入的金额与规则；不生成任何税额、批准或票据查验事实。 */
export function policyGuidanceMessages(advice: PolicyGuidance, line: ExpenseLine): GuidanceMessage[] {
  const c = advice.constraints, messages: GuidanceMessage[] = []
  const add = (code: string, warning: boolean, text: string) => messages.push({ code, warning, text })
  if (c.effect === 'DENY') { add('DENIED', true, '该规则禁止此项报销，请核对费用内容或联系财务。'); return messages }
  if (c.fixedAllowance) {
    add('ALLOWANCE', false, `定额补贴 ${c.fixedAllowance.dailyRate.currency} ${c.fixedAllowance.dailyRate.value} / 天；按自然日计算，包含起止日，同日计一天。`)
    if (!line.endedOn) add('ALLOWANCE_DATES', true, '请填写行程结束日期，系统将自动计算天数和金额。')
    if (line.invoiceIds.length) add('ALLOWANCE_INVOICES', true, '补贴行不关联发票，请移除本行已选发票。')
  }
  if (c.unitPriceLimit) {
    const cap = c.unitPriceLimit
    add('CAP', false, `单价上限 ${cap.currency} ${cap.value} / ${expenseUnits[c.limitUnit!]}`)
    if (line.unit !== c.limitUnit || line.claimedGross.currency !== cap.currency) add('UNIT_MISMATCH', true, '本行单位或币种与上限不同，请核对后预检。')
    else {
      try {
        const inputQuantity = String(line.quantity)
        if (!/^\d{1,7}(?:\.\d{1,3})?$/.test(inputQuantity)) throw new Error('Invalid quantity')
        const [whole, fraction = ''] = inputQuantity.split('.'), quantity = BigInt(whole!) * 1000n + BigInt(fraction.padEnd(3, '0'))
        const gross = amountMinor(line.claimedGross.value)
        if (quantity <= 0n || quantity > 1_000_000_000n || gross <= 0n) throw new Error('Incomplete amounts')
        if (gross * 1000n > amountMinor(cap.value) * quantity) add('AMOUNT', true, '按当前数量，本行金额超过单价上限，请填写超标说明；是否可报由预检和审批确认。')
        else add('AMOUNT_INPUT', false, '当前输入未超过本规则的单价上限，票据、税额与其他条件仍需预检。')
      } catch { add('AMOUNT_INCOMPLETE', false, '填写有效金额和数量后，将在此比较单价上限。') }
    }
  }
  if (c.priorRequestRequired) add('PRIOR_REQUEST', !line.priorRequest, line.priorRequest
    ? '已选择事前申请，批准状态和可用额度仍需预检确认。' : '该规则要求事前申请，请选择对应的有效批准行。')
  if (c.invoiceMaxAgeDays !== null) add('INVOICE_AGE', false, `票据时限 ${c.invoiceMaxAgeDays} 天；${c.invoiceAgeAction === 'REJECT' ? '超期阻断' : '超期需说明'}，由查验票据事实确认。`)
  if (c.allowedServiceLevels.length) add('SERVICE_LEVEL', false, `允许等级：${c.allowedServiceLevels.join('、')}；具体票据等级由预检核对。`)
  if (!messages.length) add('RULE', false, '当前适用规则未配置金额上限；完整可报结论仍需预检。')
  return messages
}

/** 对不可用、换版和未匹配分别提示，不把查询失败显示为不限额。 */
export function policyGuidanceError(cause: unknown): string {
  const error = cause as { code?: string; message?: string }
  if (error?.code === 'POLICY_CONFIGURATION_CHANGED') return '费用制度已更新，请刷新本行提示。'
  if (error?.code === 'FINANCE_TARGET_CHANGED') return '财务连接已变化，请刷新财务目录后重试。'
  if (error?.code === 'EXPENSE_GUIDANCE_CONTEXT_UNAVAILABLE') return '本人可用目录已变化，请刷新财务目录。'
  if (error?.code === 'FINANCE_RULE_REJECTED' && error.message === 'POLICY_NOT_FOUND') return '当前条件没有匹配的费用制度，请核对输入或联系财务。'
  if (error?.code === 'RESPONSE_UNREADABLE') return '制度提示响应不完整，请刷新重试。'
  if (error?.code === 'GUIDANCE_TIMEOUT') return '制度提示读取超时，请刷新重试。'
  if (error?.code === 'INVALID_EXPENSE_GUIDANCE_QUERY') return '请核对日期、币种和单位；当前仅支持两位精度的有效币种。'
  if (error?.code === 'ALLOWANCE_POLICY_PERIOD_MISMATCH') return '行程跨越本规则的有效日期，请按制度有效期拆分行程。'
  if (error?.code === 'ALLOWANCE_ITINERARY_TOO_LONG') return '行程天数超过可填范围，请核对起止日期。'
  return '暂时无法读取费用标准，可继续填写；保存后仍须通过预检。'
}

/** 采纳服务端计算，只调整唯一分摊；多笔成本归属由申请人重新分配，保留已选票据供明确移除。 */
export function applyAllowancePreview(line: ExpenseLine, view: PolicyGuidanceView): ExpenseLine {
  const basis = view.allowance
  if (!basis) return line
  const calc = basis.calculation
  if (line.categoryCode !== view.context.categoryCode || line.cityCode !== view.context.cityCode || line.incurredOn !== calc.startsOn
    || line.endedOn !== calc.endsOn || line.unit !== 'DAY' || line.claimedGross.currency !== calc.gross.currency) throw new Error('费用条件已变化，请重新计算补贴。')
  const existing = line.allowance
  const sameBasis = existing && existing.policy.ruleKey === basis.policy.ruleKey
    && JSON.stringify(existing.policy.selection) === JSON.stringify(basis.policy.selection) && JSON.stringify(existing.calculation) === JSON.stringify(calc)
  return { ...line, quantity: Number(line.quantity) === calc.days ? line.quantity : String(calc.days), claimedGross: { ...calc.gross },
    claimedTax: { value: '0.00', currency: calc.gross.currency }, allowance: sameBasis ? existing : basis,
    allocations: line.allocations.length === 1 ? [{ ...line.allocations[0]!, amount: { ...calc.gross } }] : line.allocations }
}
