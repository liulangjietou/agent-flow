<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FormSchema, FormField } from '../formSchema'
import { operatorsFor as conditionOperatorsFor, parseConditionRows as parseRows, safeConditionLiteral as safeLiteral, serializeConditionRows, type ConditionRows, type ConditionRow } from '../conditionBuilder'
const props = defineProps<{ modelValue: string; formSchema: FormSchema | null; disabled: boolean; languageVersion?: 1 | 2 }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; beforeChange: [] }>()
const languageVersion = computed(() => props.languageVersion ?? 1)
const operatorsFor = (field?: FormField) => conditionOperatorsFor(field, languageVersion.value)
const safeConditionLiteral = (value: string) => safeLiteral(value, languageVersion.value)
const parseConditionRows = (source: string, fields: FormField[]) => parseRows(source, fields, languageVersion.value)
const fields = computed(() => props.formSchema?.fields ?? [])
const rows = ref<ConditionRows>({ join: 'AND', rows: [] })
const mode = ref<'visual' | 'expression'>('visual')
const convertible = ref(false)
const error = ref('')
let sent: string | null = null
watch(() => [props.modelValue, props.formSchema, props.languageVersion] as const, ([source]) => {
  if (source === sent) { sent = null; return }
  const parsed = parseConditionRows(source, fields.value)
  convertible.value = !!parsed && fields.value.length > 0
  if (parsed) rows.value = parsed
  if (!convertible.value) mode.value = 'expression'
}, { immediate: true, deep: true })
function update(next: ConditionRows) {
  if (props.disabled) return
  try {
    const expression = serializeConditionRows(next, fields.value, languageVersion.value)
    if (expression !== props.modelValue) { emit('beforeChange'); sent = expression; emit('update:modelValue', expression) }
    rows.value = next; error.value = ''; convertible.value = fields.value.length > 0
  } catch (cause) { error.value = (cause as Error).message }
}
function change(index: number, property: 'field' | 'operator' | 'value', event: Event) {
  const input = event.target as HTMLInputElement
  if (property === 'value' && !safeConditionLiteral(input.value)) { input.value = rows.value.rows[index]!.value; error.value = '此值包含当前条件语法不支持的字符，已保留原条件。'; return }
  const next = { ...rows.value, rows: rows.value.rows.map(row => ({ ...row })) }, row = next.rows[index]!
  row[property] = input.value
  if (property === 'field') { row.operator = operatorsFor(fields.value.find(field => field.key === row.field))[0]!.value; row.value = '' }
  if (property === 'operator') row.values = row.operator === 'IN' ? [] : undefined
  update(next)
}
function changeMembers(index: number, value: string, event: Event) {
  const current = rows.value.rows[index]!
  const selected = new Set(current.values ?? [])
  if ((event.target as HTMLInputElement).checked) selected.add(value); else selected.delete(value)
  const field = fields.value.find(field => field.key === current.field)
  update({ ...rows.value, rows: rows.value.rows.map((row, at) => at === index
    ? { ...row, values: field?.options?.filter(option => selected.has(option.value)).map(option => option.value) ?? [] } : row) })
}
function add() { update({ ...rows.value, rows: [...rows.value.rows, { field: fields.value[0]?.key ?? '', operator: operatorsFor(fields.value[0])[0]!.value, value: '' }] }) }
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
        <fieldset v-if="row.operator === 'IN'" class="condition-members"><legend>取值（可多选）</legend><label v-for="option in fields.find(field => field.key === row.field)?.options" :key="option.value"><input type="checkbox" :checked="row.values?.includes(option.value)" :disabled="!safeConditionLiteral(option.value)" @change="changeMembers(index, option.value, $event)" /><span>{{ option.label }}</span></label><p v-if="!row.values?.length" class="condition-help">请至少选择一个选项。</p></fieldset>
        <template v-else-if="!['EXISTS', 'NOT_EXISTS'].includes(row.operator)">
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
    <p v-if="mode === 'expression' && languageVersion === 2" class="condition-help">支持 AND / OR、&amp;&amp; / ||、! 和括号。单选字段可用 category IN ["TRAVEL", "DAILY"]。文本请加引号；取反会包含原条件为假的未填写情况，需要排除时请同时判断 EXISTS。校验流程可定位语法错误。</p>
    <p v-if="error" class="condition-error" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
.condition-editor{min-width:0}.condition-modes{display:flex;gap:5px;margin:8px 0 16px}.condition-modes button{font-size:11px;padding:7px 10px;border:1px solid var(--line);border-radius:7px;background:var(--paper);color:var(--muted)}.condition-modes button[aria-pressed=true]{background:var(--soft);color:var(--deep);border-color:var(--deep)}.condition-row{border:1px solid var(--line);padding:10px;border-radius:9px;margin:13px 0}.condition-row legend{font-size:10px;color:var(--muted);padding:0 5px}.condition-row label{font-size:11px;margin-bottom:8px}.condition-row input,.condition-row select,.condition-editor textarea{font-size:12px;width:100%;min-width:0}.condition-remove{border:0;background:transparent;color:var(--red);font-size:10px;padding:4px 0}.condition-help{font-size:11px;color:var(--muted);line-height:1.8}.condition-error{font-size:11px;color:var(--red);line-height:1.8}
.condition-members{margin:6px 0;padding:10px;border:1px solid var(--line);border-radius:7px}.condition-members label{display:flex!important;align-items:center;gap:8px}.condition-members input{width:14px!important;margin:0!important;flex-shrink:0}.condition-members span{overflow-wrap:anywhere}
</style>
