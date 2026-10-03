<script setup lang="ts">
import { expenseUnits, type ExpensePolicyDefinition, type ExpensePolicyRule, type PolicyMatch, type PolicyConstraints } from '../expenseConfiguration'
import { emptyPolicyRule } from '../expenseConfigurationDrafts'

const props = defineProps<{ definition: ExpensePolicyDefinition; disabled: boolean }>()
const lines = (value: string) => value.split('\n').map(item => item.trim()).filter(Boolean)
function selectors(rule: ExpensePolicyRule, field: 'legalEntityIds' | 'categoryCodes' | 'cityTiers' | 'employeeGrades', event: Event) {
  if (!props.disabled) rule.match[field] = lines((event.target as HTMLTextAreaElement).value)
}
function optional(rule: ExpensePolicyRule, field: 'fromDate' | 'throughDate' | 'currency', event: Event) {
  if (props.disabled) return
  const value = (event.target as HTMLInputElement).value
  rule.match[field] = value || null
  if (field === 'currency' && rule.constraints.unitPriceLimit) rule.constraints.unitPriceLimit.currency = value
}
function effect(rule: ExpensePolicyRule, event: Event) {
  if (props.disabled) return
  rule.constraints.effect = (event.target as HTMLSelectElement).value as PolicyConstraints['effect']
  if (rule.constraints.effect === 'DENY') Object.assign(rule.constraints, { unitPriceLimit: null, limitUnit: null, invoiceMaxAgeDays: null,
    invoiceAgeAction: null, allowedServiceLevels: [], priorRequestRequired: false })
}
function amount(rule: ExpensePolicyRule, event: Event) {
  if (props.disabled) return
  rule.constraints.unitPriceLimit = (event.target as HTMLInputElement).checked ? { value: '', currency: rule.match.currency ?? '' } : null
  rule.constraints.limitUnit = null
}
function age(rule: ExpensePolicyRule, event: Event) {
  if (props.disabled) return
  const value = (event.target as HTMLInputElement).value
  rule.constraints.invoiceMaxAgeDays = value === '' ? null : Number(value)
  if (value === '') rule.constraints.invoiceAgeAction = null
}
function move(index: number, offset: number) {
  if (props.disabled || index + offset < 0 || index + offset >= props.definition.rules.length) return
  const rules = props.definition.rules; [rules[index], rules[index + offset]] = [rules[index + offset]!, rules[index]!]
}
function add() { if (!props.disabled && props.definition.rules.length < 200) props.definition.rules.push(emptyPolicyRule()) }
function remove(index: number) { if (!props.disabled) props.definition.rules.splice(index, 1) }
const matchFields: Array<{ key: keyof Pick<PolicyMatch, 'legalEntityIds' | 'categoryCodes' | 'cityTiers' | 'employeeGrades'>; label: string; help: string }> = [
  { key: 'legalEntityIds', label: '法人编号', help: '使用组织目录中的法人 UUID' }, { key: 'categoryCodes', label: '类别代码', help: '使用当前启用的类别代码' },
  { key: 'cityTiers', label: '城市等级', help: '使用企业事实源的等级代码' }, { key: 'employeeGrades', label: '员工职级', help: '使用企业事实源的职级代码' }
]
</script>

<template>
  <section class="policy-rules" aria-label="制度规则编辑">
    <p class="help">规则从上到下匹配，首条符合全部条件的规则生效。条件列表每行一项，留空表示不限；未命中规则时不能报销。</p>
    <p v-if="!definition.rules.length" class="help">尚无规则，可以保存草稿；发布前至少添加一条完整规则。</p>
    <fieldset v-for="(rule, index) in definition.rules" :key="index" class="rule" :disabled="disabled">
      <legend>规则 {{ index + 1 }}{{ rule.name ? ' · ' + rule.name : '' }}</legend>
      <div class="rule-actions"><button type="button" :disabled="disabled || index === 0" :aria-label="'上移规则 ' + (index + 1)" @click="move(index, -1)">上移</button><button type="button" :disabled="disabled || index === definition.rules.length - 1" :aria-label="'下移规则 ' + (index + 1)" @click="move(index, 1)">下移</button><button type="button" :aria-label="'移除规则 ' + (index + 1)" @click="remove(index)">移除</button></div>
      <div class="rule-grid">
        <label>规则标识<input v-model.trim="rule.key" required maxlength="64" autocomplete="off" /></label>
        <label>规则名称<input v-model.trim="rule.name" required maxlength="128" /></label>
        <label v-for="field in matchFields" :key="field.key">{{ field.label }}<textarea :value="rule.match[field.key].join('\n')" rows="2" @change="selectors(rule, field.key, $event)" /><small>{{ field.help }}；每行一项</small></label>
        <label>起始日期<input type="date" :value="rule.match.fromDate ?? ''" @input="optional(rule, 'fromDate', $event)" /></label>
        <label>截止日期<input type="date" :value="rule.match.throughDate ?? ''" @input="optional(rule, 'throughDate', $event)" /></label>
        <label>原币币种<input :value="rule.match.currency ?? ''" maxlength="3" pattern="[A-Z]{3}" placeholder="留空表示不限" @input="optional(rule, 'currency', $event)" /><small>三位大写币种代码；配置限额时必须填写</small></label>
        <label>处理方式<select :value="rule.constraints.effect" @change="effect(rule, $event)"><option value="ALLOW">允许，按下方约束执行</option><option value="DENY">禁止报销</option></select><small>改为禁止会清空该规则的允许性约束</small></label>
      </div>
      <div v-if="rule.constraints.effect === 'ALLOW'" class="constraints">
        <label class="check"><input type="checkbox" :checked="!!rule.constraints.unitPriceLimit" @change="amount(rule, $event)" />设置单价限额</label>
        <div v-if="rule.constraints.unitPriceLimit" class="rule-grid">
          <label>单价限额<input v-model.trim="rule.constraints.unitPriceLimit.value" inputmode="decimal" required pattern="[0-9]+(\.[0-9]+)?" /><small>按上方原币计算，金额保留精确小数</small></label>
          <label>限额单位<select v-model="rule.constraints.limitUnit" required><option :value="null" disabled>请选择单位</option><option v-for="(label, value) in expenseUnits" :key="value" :value="value">{{ label }}</option></select></label>
        </div>
        <div class="rule-grid">
          <label>票据最长时限（天）<input type="number" min="0" max="36600" step="1" :value="rule.constraints.invoiceMaxAgeDays ?? ''" placeholder="留空表示不限制" @input="age(rule, $event)" /></label>
          <label v-if="rule.constraints.invoiceMaxAgeDays !== null">超出票据时限<select v-model="rule.constraints.invoiceAgeAction" required><option :value="null" disabled>请选择处理方式</option><option value="REJECT">拒绝报销</option><option value="REQUIRE_REASON">要求例外说明</option></select></label>
          <label>允许的舱位或等级<textarea :value="rule.constraints.allowedServiceLevels.join('\n')" rows="2" @change="!disabled && (rule.constraints.allowedServiceLevels = lines(($event.target as HTMLTextAreaElement).value))" /><small>企业事实源中的等级代码；每行一项，留空表示不限</small></label>
        </div>
        <label class="check"><input v-model="rule.constraints.priorRequestRequired" type="checkbox" />必须关联已批准的事前申请</label>
      </div>
      <p v-else class="help">命中此规则的费用禁止报销。</p>
    </fieldset>
    <button class="secondary" type="button" :disabled="disabled || definition.rules.length >= 200" @click="add">添加规则</button>
  </section>
</template>

<style scoped>
.help,small{color:var(--muted);font-size:12px;line-height:1.7}.rule{border:1px solid var(--line);border-radius:8px;padding:16px;margin:18px 0;min-width:0}.rule legend{font-size:14px;padding:0 6px;font-weight:600;overflow-wrap:anywhere}.rule-actions{display:flex;justify-content:flex-end;gap:8px;margin-bottom:12px}.rule-actions button{background:transparent;border:1px solid var(--line);padding:5px 10px;border-radius:5px;font-size:12px}.rule-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.rule-grid label{display:grid;gap:6px;font-size:12px;min-width:0}.rule-grid input,.rule-grid select,.rule-grid textarea{width:100%;box-sizing:border-box;border:1px solid var(--line);border-radius:5px;padding:8px;font:inherit;background:var(--surface,#fff);color:inherit}.rule-grid textarea{resize:vertical;min-height:64px}.constraints{border-top:1px solid var(--line);margin-top:18px;padding-top:14px}.check{display:flex;align-items:center;gap:8px;font-size:12px;margin:10px 0 14px}.check input{width:auto}button:disabled{opacity:.5;cursor:not-allowed}input:focus-visible,textarea:focus-visible,select:focus-visible,button:focus-visible{outline:2px solid var(--teal);outline-offset:2px}@media(max-width:650px){.rule-grid{grid-template-columns:minmax(0,1fr)}.rule{padding:12px}.rule-actions{justify-content:flex-start}}
</style>
