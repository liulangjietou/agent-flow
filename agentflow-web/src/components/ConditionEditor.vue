<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FormSchema } from '../formSchema'
import { operatorsFor, parseConditionRows, safeConditionLiteral, serializeConditionRows, type ConditionRows, type ConditionRow } from '../conditionBuilder'
const props = defineProps<{ modelValue: string; formSchema: FormSchema | null; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; beforeChange: [] }>()
const fields = computed(() => props.formSchema?.fields ?? [])
const rows = ref<ConditionRows>({ join: 'AND', rows: [] })
const mode = ref<'visual' | 'expression'>('visual')
const convertible = ref(false)
const error = ref('')
let sent: string | null = null
watch(() => [props.modelValue, props.formSchema] as const, ([source]) => {
  if (source === sent) { sent = null; return }
  const parsed = parseConditionRows(source, fields.value)
  convertible.value = !!parsed && fields.value.length > 0
  if (parsed) rows.value = parsed
  if (!convertible.value) mode.value = 'expression'
}, { immediate: true, deep: true })
function update(next: ConditionRows) {
  if (props.disabled) return
  try {
    const expression = serializeConditionRows(next, fields.value)
    if (expression !== props.modelValue) { emit('beforeChange'); sent = expression; emit('update:modelValue', expression) }
    rows.value = next; error.value = ''; convertible.value = fields.value.length > 0
  } catch (cause) { error.value = (cause as Error).message }
}
function change(index: number, property: keyof ConditionRow, event: Event) {
  const input = event.target as HTMLInputElement
  if (property === 'value' && !safeConditionLiteral(input.value)) { input.value = rows.value.rows[index]!.value; error.value = '不支持分号、括号、模板表达式或同时包含两种引号；此字符未写入条件。'; return }
  const next = { ...rows.value, rows: rows.value.rows.map(row => ({ ...row })) }, row = next.rows[index]!
  row[property] = input.value
  if (property === 'field') { row.operator = '=='; row.value = '' }
  update(next)
}
function add() { update({ ...rows.value, rows: [...rows.value.rows, { field: fields.value[0]?.key ?? '', operator: '==', value: '' }] }) }
function remove(index: number) { update({ ...rows.value, rows: rows.value.rows.filter((_, current) => index !== current) }) }
function expression(event: Event) {
  if (props.disabled) return
  error.value = ''; emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
}
function showVisual() {
  const parsed = parseConditionRows(props.modelValue, fields.value)
  if (!parsed || !fields.value.length) return
  rows.value = parsed; mode.value = 'visual'; error.value = ''
}
</script>

<template>
  <section class="condition-editor" aria-label="条件配置">
    <div class="condition-modes"><button type="button" :aria-pressed="mode === 'visual'" :disabled="!convertible" @click="showVisual">按字段配置</button><button type="button" :aria-pressed="mode === 'expression'" @click="mode = 'expression'">表达式</button></div>
    <template v-if="mode === 'visual'">
      <label>条件组合<select :value="rows.join" :disabled="disabled" @change="update({ ...rows, join: ($event.target as HTMLSelectElement).value as 'AND' | 'OR' })"><option value="AND">全部满足</option><option value="OR">任一满足</option></select></label>
      <fieldset v-for="(row, index) in rows.rows" :key="index" :disabled="disabled" class="condition-row">
        <legend>条件 {{ index + 1 }}</legend>
        <label>字段<select :value="row.field" @change="change(index, 'field', $event)"><option v-for="field in fields" :key="field.key" :value="field.key">{{ field.label }}</option></select></label>
        <label>判断<select :value="row.operator" @change="change(index, 'operator', $event)"><option v-for="operator in operatorsFor(fields.find(field => field.key === row.field))" :key="operator.value" :value="operator.value">{{ operator.label }}</option></select></label>
        <template v-if="!['EXISTS', 'NOT_EXISTS'].includes(row.operator)">
          <label v-if="fields.find(field => field.key === row.field)?.type === 'SELECT'">取值<select :value="row.value" @change="change(index, 'value', $event)"><option value="">请选择</option><option v-for="option in fields.find(field => field.key === row.field)?.options" :key="option.value" :value="option.value" :disabled="!safeConditionLiteral(option.value)">{{ option.label }}</option></select></label>
          <label v-else-if="fields.find(field => field.key === row.field)?.type === 'BOOLEAN'">取值<select :value="row.value" @change="change(index, 'value', $event)"><option value="">请选择</option><option value="true">是</option><option value="false">否</option></select></label>
          <label v-else>取值<input :value="row.value" :type="fields.find(field => field.key === row.field)?.type === 'DATE' ? 'date' : 'text'" :inputmode="fields.find(field => field.key === row.field)?.type === 'NUMBER' ? 'decimal' : 'text'" @input="change(index, 'value', $event)" /></label>
        </template>
        <button type="button" class="condition-remove" :disabled="rows.rows.length < 2" @click="remove(index)">删除条件 {{ index + 1 }}</button>
      </fieldset>
      <button type="button" class="secondary" :disabled="disabled" @click="add">＋ 添加条件</button>
      <p class="condition-help">条件直接使用下方表单字段。金额按十进制原值保存，发布时由服务端校验。</p>
    </template>
    <template v-else><label>条件表达式<textarea :value="modelValue" :disabled="disabled" rows="3" placeholder="例如 amount > 5000" @focus="emit('beforeChange')" @input="expression" /></label><p v-if="!convertible" class="condition-help">{{ fields.length ? '当前表达式不能无损转为单组条件，原文已保留。可继续编辑表达式。' : '请先配置表单字段，已有表达式保持原样。' }}</p></template>
    <p v-if="error" class="condition-error" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
.condition-editor{min-width:0}.condition-modes{display:flex;gap:5px;margin:8px 0 16px}.condition-modes button{font-size:11px;padding:7px 10px;border:1px solid var(--line);border-radius:7px;background:var(--paper);color:var(--muted)}.condition-modes button[aria-pressed=true]{background:var(--soft);color:var(--deep);border-color:var(--deep)}.condition-row{border:1px solid var(--line);padding:10px;border-radius:9px;margin:13px 0}.condition-row legend{font-size:10px;color:var(--muted);padding:0 5px}.condition-row label{font-size:11px;margin-bottom:8px}.condition-row input,.condition-row select,.condition-editor textarea{font-size:12px;width:100%;min-width:0}.condition-remove{border:0;background:transparent;color:var(--red);font-size:10px;padding:4px 0}.condition-help{font-size:11px;color:var(--muted);line-height:1.8}.condition-error{font-size:11px;color:var(--red);line-height:1.8}
</style>
