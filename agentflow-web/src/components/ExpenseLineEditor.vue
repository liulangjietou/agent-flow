<script setup lang="ts">
import { computed } from 'vue'
import type { ExpenseLine } from '../expenses'
import { expenseUnits, type FinanceCatalog } from '../expenseDraft'
const line = defineModel<ExpenseLine>({ required: true })
const props = defineProps<{ catalog: FinanceCatalog; legalEntityId: string; locked: boolean }>()
const emit = defineEmits<{ remove: [] }>()
const centers = computed(() => props.catalog.costCenters.filter(value => value.legalEntityId === props.legalEntityId))
const projects = computed(() => props.catalog.projects.filter(value => value.legalEntityId === props.legalEntityId))
const units = computed(() => props.catalog.categories.find(value => value.code === line.value.categoryCode)?.units ?? [])
function addAllocation() {
  if (!props.locked && line.value.allocations.length < 50) line.value.allocations.push({ costCenter: '', projectCode: null, amount: { value: '', currency: line.value.claimedGross.currency } })
}
</script>

<template>
  <fieldset class="expense-line-editor" :disabled="locked">
    <legend>第 {{ line.lineNo }} 行费用</legend>
    <div class="line-toolbar"><span>填写原币金额；换算及标准由预检核对。</span><button type="button" class="quiet" @click="emit('remove')">移除此行</button></div>
    <div class="entry-grid">
      <label>费用类别<select v-model="line.categoryCode" required><option value="" disabled>选择类别</option><option v-if="line.categoryCode && !catalog.categories.some(value => value.code === line.categoryCode)" :value="line.categoryCode">{{ line.categoryCode }}（当前不可用）</option><option v-for="category in catalog.categories" :key="category.code" :value="category.code">{{ category.name }}</option></select></label>
      <label>发生城市<select v-model="line.cityCode" required><option value="" disabled>选择城市</option><option v-if="line.cityCode && !catalog.cities.some(value => value.code === line.cityCode)" :value="line.cityCode">{{ line.cityCode }}（当前不可用）</option><option v-for="city in catalog.cities" :key="city.code" :value="city.code">{{ city.name }}</option></select></label>
      <label>发生日期<input v-model="line.incurredOn" type="date" required /></label>
      <label>结束日期（选填）<input v-model="line.endedOn" type="date" :min="line.incurredOn" /></label>
      <label>数量<input v-model="line.quantity" inputmode="decimal" maxlength="11" required /></label>
      <label>计量单位<select v-model="line.unit" required><option v-if="!units.includes(line.unit)" :value="line.unit" disabled>请按类别选择</option><option v-for="unit in units" :key="unit" :value="unit">{{ expenseUnits[unit] }}</option></select></label>
      <label>原币币种<input v-model="line.claimedGross.currency" maxlength="3" pattern="[A-Z]{3}" required placeholder="例如 CNY" /></label>
      <label>含税金额<input v-model="line.claimedGross.value" inputmode="decimal" maxlength="18" required /></label>
      <label>可抵扣税额<input v-model="line.claimedTax.value" inputmode="decimal" maxlength="18" required /></label>
    </div>
    <label>费用说明<textarea v-model="line.description" rows="2" maxlength="2000" required /></label>
    <label>超标说明（适用时填写）<textarea v-model="line.exceptionReason" rows="2" maxlength="2000" /></label>
    <div class="allocation-heading"><h4>成本分摊 · {{ line.claimedGross.currency }}</h4><button type="button" class="quiet" :disabled="line.allocations.length >= 50" @click="addAllocation">＋ 添加分摊</button></div>
    <p class="entry-help">每笔金额须大于零，合计须等于本行含税金额。</p>
    <div v-for="(allocation, index) in line.allocations" :key="index" class="allocation-row">
      <label>成本中心<select v-model="allocation.costCenter" required><option value="" disabled>选择成本中心</option><option v-if="allocation.costCenter && !centers.some(value => value.code === allocation.costCenter)" :value="allocation.costCenter">{{ allocation.costCenter }}（当前不可用）</option><option v-for="center in centers" :key="center.code" :value="center.code">{{ center.name }}</option></select></label>
      <label>项目<select :value="allocation.projectCode ?? ''" @change="allocation.projectCode = ($event.target as HTMLSelectElement).value || null"><option value="">不指定项目</option><option v-if="allocation.projectCode && !projects.some(value => value.code === allocation.projectCode)" :value="allocation.projectCode">{{ allocation.projectCode }}（当前不可用）</option><option v-for="project in projects" :key="project.code" :value="project.code">{{ project.name }}</option></select></label>
      <label>分摊金额<input v-model="allocation.amount.value" inputmode="decimal" maxlength="18" required /></label>
      <button type="button" class="quiet" :disabled="line.allocations.length === 1" :aria-label="`移除第 ${line.lineNo} 行第 ${index + 1} 笔分摊`" @click="line.allocations.splice(index, 1)">移除</button>
    </div>
    <div v-if="line.invoiceIds.length || line.priorRequest" class="line-references">
      <p v-for="id in line.invoiceIds" :key="id">发票 {{ id }}<button type="button" class="quiet" @click="line.invoiceIds = line.invoiceIds.filter(value => value !== id)">移除发票</button></p>
      <p v-if="line.priorRequest">事前批准 {{ line.priorRequest.requestId }} · 第 {{ line.priorRequest.lineNo }} 行<button type="button" class="quiet" @click="line.priorRequest = null">移除引用</button></p>
    </div>
  </fieldset>
</template>

<style scoped>
.expense-line-editor{margin:20px 0;padding:20px;border:1px solid var(--line);border-radius:12px;min-width:0;background:#fff}.expense-line-editor legend{padding:0 9px;font-size:14px;font-weight:700}.line-toolbar,.allocation-heading{display:flex;align-items:center;gap:12px;justify-content:space-between}.line-toolbar span,.entry-help{font-size:11px;color:var(--muted);line-height:1.8}.line-toolbar button,.allocation-heading button{font-size:12px;flex-shrink:0}.entry-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 16px}.expense-line-editor label{display:grid;gap:8px;font-size:12px;margin:14px 0;min-width:0}.expense-line-editor input,.expense-line-editor select,.expense-line-editor textarea{width:100%;min-width:0;border:1px solid var(--line);border-radius:7px;background:white;padding:10px;font:inherit;color:var(--ink)}.expense-line-editor textarea{resize:vertical}.allocation-heading{margin-top:20px;border-top:1px solid var(--line);padding-top:16px}.allocation-heading h4{font-size:13px;margin:0}.allocation-row{display:grid;grid-template-columns:1fr 1fr 1fr auto;gap:12px;align-items:center}.allocation-row button{font-size:11px;margin-top:20px}.line-references{border-top:1px solid var(--line);margin-top:14px;padding-top:7px}.line-references p{font-size:11px;overflow-wrap:anywhere;line-height:1.8}.line-references button{margin-left:10px;color:var(--deep)}@media(max-width:650px){.entry-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.allocation-row{grid-template-columns:1fr 1fr}.allocation-row button{justify-self:end}.expense-line-editor{padding:14px}}@media(max-width:420px){.entry-grid{grid-template-columns:1fr}}
</style>
