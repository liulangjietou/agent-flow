<script setup lang="ts">
import { computed } from 'vue'
import { operatorsFor, safeConditionLiteral, type ConditionRow } from '../conditionBuilder'
import type { FormField } from '../formSchema'
const props = defineProps<{ row: ConditionRow; fields: FormField[]; version: number; disabled: boolean }>()
const emit = defineEmits<{ change: [row: ConditionRow]; invalid: [message: string] }>()
const field = computed(() => props.fields.find(field => field.key === props.row.field))
const safe = (value: string) => safeConditionLiteral(value, props.version)
function change(property: 'field' | 'operator' | 'value', event: Event) {
  if (props.disabled) return
  const input = event.target as HTMLInputElement
  if (property === 'value' && !safe(input.value)) { input.value = props.row.value; emit('invalid', '此值包含当前条件语法不支持的字符，已保留原条件。'); return }
  const next = { ...props.row, [property]: input.value }
  if (property === 'field') { next.operator = operatorsFor(props.fields.find(field => field.key === next.field), props.version)[0]!.value; next.value = ''; next.values = undefined }
  if (property === 'operator') next.values = next.operator === 'IN' ? [] : undefined
  emit('change', next)
}
function member(value: string, event: Event) {
  if (props.disabled) return
  const selected = new Set(props.row.values ?? [])
  if ((event.target as HTMLInputElement).checked) selected.add(value); else selected.delete(value)
  emit('change', { ...props.row, values: field.value?.options?.filter(option => selected.has(option.value)).map(option => option.value) ?? [] })
}
</script>
<template>
  <div class="condition-rule-fields">
    <label>字段<select :value="row.field" :disabled="disabled" @change="change('field', $event)"><option v-for="item in fields" :key="item.key" :value="item.key">{{ item.label }}</option></select></label>
    <label>判断<select :value="row.operator" :disabled="disabled" @change="change('operator', $event)"><option v-for="operator in operatorsFor(field, version)" :key="operator.value" :value="operator.value">{{ operator.label }}</option></select></label>
    <fieldset v-if="row.operator === 'IN'" class="condition-members" :disabled="disabled"><legend>取值（可多选）</legend><label v-for="option in field?.options" :key="option.value"><input type="checkbox" :checked="row.values?.includes(option.value)" :disabled="!safe(option.value)" @change="member(option.value, $event)" /><span>{{ option.label }}</span></label><p v-if="!row.values?.length">请至少选择一个选项。</p></fieldset>
    <template v-else-if="!['EXISTS', 'NOT_EXISTS'].includes(row.operator)">
      <label v-if="field?.type === 'SELECT'">取值<select :value="row.value" :disabled="disabled" @change="change('value', $event)"><option value="">请选择</option><option v-for="option in field.options" :key="option.value" :value="option.value" :disabled="!safe(option.value)">{{ option.label }}</option></select></label>
      <label v-else-if="field?.type === 'BOOLEAN'">取值<select :value="row.value" :disabled="disabled" @change="change('value', $event)"><option value="">请选择</option><option value="true">是</option><option value="false">否</option></select></label>
      <label v-else>取值<input :value="row.value" :disabled="disabled" :type="field?.type === 'DATE' ? 'date' : 'text'" :inputmode="field?.type === 'NUMBER' ? 'decimal' : 'text'" @input="change('value', $event)" /></label>
    </template>
  </div>
</template>
<style scoped>
.condition-rule-fields{min-width:0}.condition-rule-fields label{font-size:11px;margin-bottom:8px}.condition-rule-fields input,.condition-rule-fields select{font-size:12px;width:100%;min-width:0}.condition-members{margin:6px 0;padding:10px;border:1px solid var(--line);border-radius:7px}.condition-members legend{font-size:10px}.condition-members label{display:flex!important;align-items:center;gap:8px}.condition-members input{width:14px!important;margin:0!important;flex-shrink:0}.condition-members span{overflow-wrap:anywhere}.condition-members p{font-size:11px;color:var(--muted);line-height:1.8}
</style>
