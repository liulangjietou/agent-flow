<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ExpenseLine, ExpenseContent } from '../expenses'
import { amountMinor } from '../expenses'
import { expenseUnits, type FinanceCatalog } from '../expenseDraft'
import { policyGuidanceContext, policyGuidanceQuery, applyAllowancePreview, type PolicyGuidanceView } from '../expensePolicyGuidance'
import ExpensePolicyGuidance from './ExpensePolicyGuidance.vue'
const line = defineModel<ExpenseLine>({ required: true })
const props = defineProps<{ catalog: FinanceCatalog; legalEntityId: string; reportType: ExpenseContent['type']; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ remove: [] }>()
const guidanceContext = computed(() => policyGuidanceContext(props.legalEntityId, props.reportType, line.value, props.catalog))
const centers = computed(() => props.catalog.costCenters.filter(value => value.legalEntityId === props.legalEntityId))
const projects = computed(() => props.catalog.projects.filter(value => value.legalEntityId === props.legalEntityId))
const units = computed(() => props.catalog.categories.find(value => value.code === line.value.categoryCode)?.units ?? [])
const fixed = ref(false)
const isAllowance = computed(() => fixed.value || !!line.value.allowance)
const lineElement = ref<HTMLFieldSetElement | null>(null), reasonInput = ref<HTMLTextAreaElement | null>(null), removeButton = ref<HTMLButtonElement | null>(null)
const allocationInputs = ref<HTMLInputElement[]>([])
/** 分摊反馈只解释当前草稿，不改写金额；完整保存仍走原有统一校验。 */
const allocationFeedback = computed(() => {
  const value = line.value, currency = value.claimedGross.currency
  if (!value.claimedGross.value || !value.allocations.length || value.allocations.some(item => !item.amount.value)) return { balanced: false, message: '填写完整金额后显示分摊差额。' }
  if (!/^[A-Z]{3}$/.test(currency) || value.allocations.some(item => item.amount.currency !== currency)) return { balanced: false, message: '请核对本行和各笔分摊的币种。' }
  try {
    const gross = amountMinor(value.claimedGross.value), amounts = value.allocations.map(item => amountMinor(item.amount.value))
    if (gross <= 0n || amounts.some(amount => amount <= 0n)) return { balanced: false, message: '含税金额和每笔分摊金额须大于零。' }
    const total = amounts.reduce((sum, amount) => sum + amount, 0n), difference = total - gross
    const display = (amount: bigint) => `${currency} ${amount / 100n}.${(amount % 100n).toString().padStart(2, '0')}`
    return { balanced: difference === 0n, message: `已分摊 ${display(total)} · ${difference === 0n ? '分摊已配平' : difference > 0n ? `超出 ${display(difference)}` : `还差 ${display(-difference)}`}` }
  } catch { return { balanced: false, message: '金额请填写普通十进制，最多两位小数。' } }
})
/** 仅移动焦点，不执行移除、保存或提交；锁定时不干扰正在进行的操作。 */
function focusFinding(code: string) {
  if (props.locked) return
  const target = code === 'EXPENSE_POLICY_DENIED' ? removeButton.value
    : ['EXPENSE_EXCEPTION_REASON_REQUIRED', 'EXPENSE_EXCEPTION_REQUIRED', 'PRIOR_REQUEST_EXCEPTION_REASON_REQUIRED'].includes(code) ? reasonInput.value
    : code === 'ALLOCATION_UNBALANCED' ? allocationInputs.value[0] : lineElement.value
  lineElement.value?.scrollIntoView({ block: 'center', behavior: 'auto' })
  target?.focus({ preventScroll: true })
}
defineExpose({ lineNo: computed(() => line.value.lineNo), focusFinding })
function clearAllowance(categoryChanged = false) {
  if (props.locked) return
  const previous = isAllowance.value
  line.value.allowance = null
  fixed.value = categoryChanged ? false : previous
  if (previous) { line.value.quantity = ''; line.value.claimedGross.value = ''; line.value.claimedTax.value = '0.00' }
}
function acceptGuidance(view: PolicyGuidanceView) {
  if (props.locked || !guidanceContext.value || policyGuidanceQuery(view.context) !== policyGuidanceQuery(guidanceContext.value)) return
  fixed.value = !!view.guidance.constraints.fixedAllowance
  if (view.allowance) line.value = applyAllowancePreview(line.value, view)
  else if (fixed.value) clearAllowance()
  else if (line.value.allowance) line.value.allowance = null
}
watch(() => [props.legalEntityId, props.reportType, props.scopeKey], () => clearAllowance(true))
function addAllocation() {
  if (!props.locked && line.value.allocations.length < 50) line.value.allocations.push({ costCenter: '', projectCode: null, amount: { value: '', currency: line.value.claimedGross.currency } })
}
</script>

<template>
  <fieldset ref="lineElement" class="expense-line-editor" :disabled="locked" tabindex="-1">
    <legend>第 {{ line.lineNo }} 行费用</legend>
    <div class="line-toolbar"><span>填写原币金额；换算及标准由预检核对。</span><button ref="removeButton" type="button" class="quiet" @click="emit('remove')">移除此行</button></div>
    <div class="entry-grid">
      <label>费用类别<select v-model="line.categoryCode" required @change="clearAllowance(true)"><option value="" disabled>选择类别</option><option v-if="line.categoryCode && !catalog.categories.some(value => value.code === line.categoryCode)" :value="line.categoryCode">{{ line.categoryCode }}（当前不可用）</option><option v-for="category in catalog.categories" :key="category.code" :value="category.code">{{ category.name }}</option></select></label>
      <label>发生城市<select v-model="line.cityCode" required @change="clearAllowance()"><option value="" disabled>选择城市</option><option v-if="line.cityCode && !catalog.cities.some(value => value.code === line.cityCode)" :value="line.cityCode">{{ line.cityCode }}（当前不可用）</option><option v-for="city in catalog.cities" :key="city.code" :value="city.code">{{ city.name }}</option></select></label>
      <label>{{ isAllowance ? '行程开始日期' : '发生日期' }}<input v-model="line.incurredOn" type="date" required @input="clearAllowance()" /></label>
      <label>{{ isAllowance ? '行程结束日期' : '结束日期（选填）' }}<input v-model="line.endedOn" type="date" :min="line.incurredOn" :required="isAllowance" @input="clearAllowance()" /></label>
      <label>{{ isAllowance ? '补贴天数（自动）' : '数量' }}<input v-model="line.quantity" inputmode="decimal" maxlength="11" required :readonly="isAllowance" /></label>
      <label>计量单位<select v-model="line.unit" required :disabled="isAllowance" @change="clearAllowance()"><option v-if="!units.includes(line.unit)" :value="line.unit" disabled>请按类别选择</option><option v-for="unit in units" :key="unit" :value="unit">{{ expenseUnits[unit] }}</option></select></label>
      <label>原币币种<input v-model="line.claimedGross.currency" maxlength="3" pattern="[A-Z]{3}" required placeholder="例如 CNY" @input="clearAllowance()" /></label>
      <label>{{ isAllowance ? '补贴金额（自动）' : '含税金额' }}<input v-model="line.claimedGross.value" inputmode="decimal" maxlength="18" required :readonly="isAllowance" /></label>
      <label>可抵扣税额<input v-model="line.claimedTax.value" inputmode="decimal" maxlength="18" required :readonly="isAllowance" /></label>
    </div>
    <p v-if="line.allowance" class="allowance-basis" role="status">补贴计算：{{ line.allowance.calculation.rule.dailyRate.currency }} {{ line.allowance.calculation.rule.dailyRate.value }} / 天 × {{ line.allowance.calculation.days }} 天 = {{ line.allowance.calculation.gross.value }}。包含起止日；制度 v{{ line.allowance.policy.selection.policyVersion }}。调整行程后自动重新计算。</p>
    <p v-else-if="isAllowance" class="entry-help" role="status">请填写完整行程并等待计算；补贴金额、天数和税额由系统确定。</p>
    <ExpensePolicyGuidance :context="guidanceContext" :line="line" :scope-key="scopeKey" :disabled="locked" @resolved="acceptGuidance" />
    <label>费用说明<textarea v-model="line.description" rows="2" maxlength="2000" required /></label>
    <label>制度或事前额度超额说明（适用时填写）<textarea ref="reasonInput" v-model="line.exceptionReason" rows="2" maxlength="2000" /></label>
    <div class="allocation-heading"><h4>成本分摊 · {{ line.claimedGross.currency }}</h4><button type="button" class="quiet" :disabled="line.allocations.length >= 50" @click="addAllocation">＋ 添加分摊</button></div>
    <p class="entry-help">每笔金额须大于零，合计须等于本行含税金额。</p>
    <p class="allocation-feedback" :class="{ balanced: allocationFeedback.balanced }" role="status">{{ allocationFeedback.message }}</p>
    <div v-for="(allocation, index) in line.allocations" :key="index" class="allocation-row">
      <label>成本中心<select v-model="allocation.costCenter" required><option value="" disabled>选择成本中心</option><option v-if="allocation.costCenter && !centers.some(value => value.code === allocation.costCenter)" :value="allocation.costCenter">{{ allocation.costCenter }}（当前不可用）</option><option v-for="center in centers" :key="center.code" :value="center.code">{{ center.name }}</option></select></label>
      <label>项目<select :value="allocation.projectCode ?? ''" @change="allocation.projectCode = ($event.target as HTMLSelectElement).value || null"><option value="">不指定项目</option><option v-if="allocation.projectCode && !projects.some(value => value.code === allocation.projectCode)" :value="allocation.projectCode">{{ allocation.projectCode }}（当前不可用）</option><option v-for="project in projects" :key="project.code" :value="project.code">{{ project.name }}</option></select></label>
      <label>分摊金额<input ref="allocationInputs" v-model="allocation.amount.value" inputmode="decimal" maxlength="18" required /></label>
      <button type="button" class="quiet" :disabled="line.allocations.length === 1" :aria-label="`移除第 ${line.lineNo} 行第 ${index + 1} 笔分摊`" @click="line.allocations.splice(index, 1)">移除</button>
    </div>
    <div v-if="line.invoiceIds.length || line.priorRequest" class="line-references">
      <p v-for="id in line.invoiceIds" :key="id">发票 {{ id }}<button type="button" class="quiet" @click="line.invoiceIds = line.invoiceIds.filter(value => value !== id)">移除发票</button></p>
      <p v-if="line.priorRequest">事前批准 {{ line.priorRequest.requestId }} · 第 {{ line.priorRequest.lineNo }} 行<button type="button" class="quiet" @click="line.priorRequest = null">移除引用</button></p>
    </div>
  </fieldset>
</template>

<style scoped>
.allocation-feedback{font-size:12px;line-height:1.8;color:var(--red);overflow-wrap:anywhere}.allocation-feedback.balanced{color:var(--deep)}.expense-line-editor:focus-visible{outline:3px solid var(--teal);outline-offset:3px}
.allowance-basis{padding:12px;border-left:3px solid var(--teal);background:var(--paper);font-size:12px;line-height:1.8;overflow-wrap:anywhere}input:read-only{background:var(--paper);color:var(--deep)}
.expense-line-editor{margin:20px 0;padding:20px;border:1px solid var(--line);border-radius:12px;min-width:0;background:#fff}.expense-line-editor legend{padding:0 9px;font-size:14px;font-weight:700}.line-toolbar,.allocation-heading{display:flex;align-items:center;gap:12px;justify-content:space-between}.line-toolbar span,.entry-help{font-size:11px;color:var(--muted);line-height:1.8}.line-toolbar button,.allocation-heading button{font-size:12px;flex-shrink:0}.entry-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 16px}.expense-line-editor label{display:grid;gap:8px;font-size:12px;margin:14px 0;min-width:0}.expense-line-editor input,.expense-line-editor select,.expense-line-editor textarea{width:100%;min-width:0;border:1px solid var(--line);border-radius:7px;background:white;padding:10px;font:inherit;color:var(--ink)}.expense-line-editor textarea{resize:vertical}.allocation-heading{margin-top:20px;border-top:1px solid var(--line);padding-top:16px}.allocation-heading h4{font-size:13px;margin:0}.allocation-row{display:grid;grid-template-columns:1fr 1fr 1fr auto;gap:12px;align-items:center}.allocation-row button{font-size:11px;margin-top:20px}.line-references{border-top:1px solid var(--line);margin-top:14px;padding-top:7px}.line-references p{font-size:11px;overflow-wrap:anywhere;line-height:1.8}.line-references button{margin-left:10px;color:var(--deep)}@media(max-width:650px){.entry-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.allocation-row{grid-template-columns:1fr 1fr}.allocation-row button{justify-self:end}.expense-line-editor{padding:14px}}@media(max-width:420px){.entry-grid{grid-template-columns:1fr}}
</style>
