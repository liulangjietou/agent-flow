<script setup lang="ts">
import { computed } from 'vue'
import type { RiskPolicy, RiskRule } from '../api'
import type { FormSchema } from '../formSchema'
import { copyRiskPolicy, MAX_RISK_RULES, riskConditionSchema } from '../submissionRisk'
import ConditionEditor from './ConditionEditor.vue'

const props = defineProps<{ modelValue: RiskPolicy | null; formSchema: FormSchema | null; languageVersion: 1 | 2; locked: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: RiskPolicy | null]; beforeChange: [] }>()
const rules = computed(() => props.modelValue?.rules ?? [])
const publicSchema = computed(() => riskConditionSchema(props.formSchema))

/** 所有编辑均先留撤销快照，发布版本由上层锁定。 */
function replace(next: RiskRule[]) {
  if (props.locked) return
  emit('beforeChange')
  emit('update:modelValue', next.length ? { rules: next } : null)
}
function add() {
  if (props.locked || rules.value.length >= MAX_RISK_RULES || !publicSchema.value?.fields.length) return
  let index = 1
  while (rules.value.some(rule => rule.id === `risk${index}`)) index++
  replace([...rules.value, { id: `risk${index}`, label: '', level: 'MEDIUM', condition: '' }])
}
function change(index: number, patch: Partial<RiskRule>) {
  const next = copyRiskPolicy(props.modelValue)?.rules
  if (!next?.[index]) return
  next[index] = { ...next[index]!, ...patch }
  replace(next)
}
function remove(index: number) { replace(rules.value.filter((_, at) => at !== index)) }
</script>

<template>
  <section class="risk-policy" aria-label="提交时风险规则">
    <div class="risk-heading"><div><h3>提交时风险规则</h3><p>随流程版本发布，提交时保存判断结果，用于待办筛选。</p></div><button type="button" class="secondary" :disabled="locked || rules.length >= MAX_RISK_RULES || !publicSchema?.fields.length" @click="add">＋ 添加风险规则</button></div>
    <p v-if="!rules.length" class="risk-help">未配置规则，新提交显示“未评估”。规则未命中也不会显示为低风险。</p>
    <p v-if="!publicSchema?.fields.length" class="risk-help">请先配置可公开读取的表单字段。敏感、隐藏、脱敏字段不参与风险规则。</p>
    <fieldset v-for="(rule, index) in rules" :key="index" :disabled="locked" class="risk-rule">
      <legend>风险规则 {{ index + 1 }}</legend>
      <div class="risk-inputs">
        <label>规则标识<input :value="rule.id" maxlength="64" placeholder="以字母开头，字母、数字、下划线或短横线" @input="change(index, { id: ($event.target as HTMLInputElement).value })" /></label>
        <label>公开说明<input :value="rule.label" maxlength="120" placeholder="审批人可见，请勿填写敏感信息" @input="change(index, { label: ($event.target as HTMLInputElement).value })" /></label>
        <label>命中等级<select :value="rule.level" @change="change(index, { level: ($event.target as HTMLSelectElement).value as RiskRule['level'] })"><option value="LOW">低风险</option><option value="MEDIUM">中风险</option><option value="HIGH">高风险</option></select></label>
      </div>
      <ConditionEditor :model-value="rule.condition" :form-schema="publicSchema" :language-version="languageVersion" :disabled="locked" @update:model-value="change(index, { condition: $event })" />
      <button type="button" class="quiet" :disabled="locked" @click="remove(index)">删除风险规则 {{ index + 1 }}</button>
    </fieldset>
    <p v-if="rules.length" class="risk-help">最多 {{ MAX_RISK_RULES }} 条；同时命中时保留全部说明，取最高等级。请填完整标识、公开说明和条件后保存。结果描述本轮提交时的状态，后续修改不会覆盖历史，也不会自动批准申请。</p>
  </section>
</template>

<style scoped>
.risk-policy{border:1px solid var(--line);border-radius:10px;padding:18px;margin:18px 0;min-width:0}.risk-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.risk-heading h3{font-size:14px;margin:0}.risk-heading p,.risk-help{font-size:12px;color:var(--muted);line-height:1.8}.risk-rule{border:1px solid var(--line);border-radius:8px;padding:14px;margin:18px 0;min-width:0}.risk-rule legend{font-size:12px;padding:0 6px}.risk-inputs{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,2fr) minmax(100px,.7fr);gap:12px}.risk-inputs label{display:flex;flex-direction:column;gap:6px;font-size:12px;min-width:0}.risk-inputs input,.risk-inputs select{width:100%;min-width:0;padding:9px;border:1px solid var(--line);border-radius:6px;background:white;color:var(--ink)}.risk-rule>.quiet{color:var(--red);font-size:12px}@media(max-width:700px){.risk-inputs{grid-template-columns:minmax(0,1fr)}.risk-policy{padding:12px}.risk-rule{padding:10px}}
</style>
