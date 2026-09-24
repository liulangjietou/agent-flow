<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import ConditionRuleFields from './ConditionRuleFields.vue'
import ConditionGroupFields from './ConditionGroupFields.vue'
import { parseConditionGroup, serializeConditionGroup, editConditionGroup, type ConditionGroup, type ConditionGroupEdit } from '../conditionGroups'
import { describeCondition, insertConditionField } from '../conditionPresentation'
import type { FormSchema, FormField } from '../formSchema'
import { operatorsFor as conditionOperatorsFor, parseConditionRows as parseRows, serializeConditionRows, type ConditionRows, type ConditionRow } from '../conditionBuilder'
const props = defineProps<{ modelValue: string; formSchema: FormSchema | null; disabled: boolean; languageVersion?: 1 | 2 }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; beforeChange: [] }>()
const languageVersion = computed(() => props.languageVersion ?? 1)
const operatorsFor = (field?: FormField) => conditionOperatorsFor(field, languageVersion.value)
const parseConditionRows = (source: string, fields: FormField[]) => parseRows(source, fields, languageVersion.value)
const fields = computed(() => props.formSchema?.fields ?? [])
const group = ref<ConditionGroup | null>(null)
const rows = ref<ConditionRows>({ join: 'AND', rows: [] })
const mode = ref<'visual' | 'expression'>('visual')
const convertible = ref(false)
const error = ref('')
const expressionInput = ref<HTMLTextAreaElement | null>(null)
const description = computed(() => describeCondition(props.modelValue, fields.value, languageVersion.value))
let sent: string | null = null
let restoringFocus = false
watch(() => [props.modelValue, props.formSchema, props.languageVersion] as const, ([source]) => {
  if (source === sent) { sent = null; return }
  if (languageVersion.value === 2) {
    group.value = parseConditionGroup(source, fields.value, 2); convertible.value = !!group.value
  } else {
    const parsed = parseConditionRows(source, fields.value)
    convertible.value = !!parsed && fields.value.length > 0
    if (parsed) rows.value = parsed
    group.value = null
  }
  error.value = ''
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
function change(index: number, row: ConditionRow) {
  update({ ...rows.value, rows: rows.value.rows.map((previous, at) => at === index ? row : previous) })
}
function changeGroup(edit: ConditionGroupEdit) {
  if (props.disabled || !group.value) return
  try {
    const next = editConditionGroup(group.value, edit, fields.value)
    const expression = serializeConditionGroup(next, fields.value)
    if (expression !== props.modelValue) { emit('beforeChange'); sent = expression; emit('update:modelValue', expression) }
    group.value = next; error.value = ''; convertible.value = true
  } catch (cause) { error.value = (cause as Error).message }
}
function add() { update({ ...rows.value, rows: [...rows.value.rows, { field: fields.value[0]?.key ?? '', operator: operatorsFor(fields.value[0])[0]!.value, value: '' }] }) }
function remove(index: number) { update({ ...rows.value, rows: rows.value.rows.filter((_, current) => index !== current) }) }
function expression(event: Event) {
  if (props.disabled) return
  error.value = ''; emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
}
/** 点击或键盘选择字段都使用文本框保留的选区，并把焦点还给新插入位置。 */
async function insertField(field: FormField) {
  const input = expressionInput.value
  if (props.disabled || !input) return
  try {
    const inserted = insertConditionField(props.modelValue, field.key, input.selectionStart, input.selectionEnd, languageVersion.value)
    if (inserted.value !== props.modelValue) { emit('beforeChange'); emit('update:modelValue', inserted.value) }
    error.value = ''
    await nextTick()
    if (input.isConnected && !props.disabled && props.modelValue === inserted.value) {
      restoringFocus = true
      try { input.focus(); input.setSelectionRange(inserted.caret, inserted.caret) } finally { restoringFocus = false }
    }
  } catch (cause) { error.value = (cause as Error).message }
}
function showVisual() {
  if (languageVersion.value === 2) {
    const parsed = parseConditionGroup(props.modelValue, fields.value, 2)
    // 临时未填写项由当前编辑树保留，切换查看表达式不会清掉尚未填完的条件。
    if (parsed) group.value = parsed
    else if (!group.value || !convertible.value) return
  } else {
    const parsed = parseConditionRows(props.modelValue, fields.value)
    if (!parsed || !fields.value.length) return
    rows.value = parsed
  }
  mode.value = 'visual'; error.value = ''
}
</script>

<template>
  <section class="condition-editor" aria-label="条件配置">
    <div class="condition-modes"><button type="button" :aria-pressed="mode === 'visual'" :disabled="!convertible" @click="showVisual">按字段配置</button><button type="button" :aria-pressed="mode === 'expression'" @click="mode = 'expression'">表达式</button></div>
    <template v-if="mode === 'visual' && languageVersion === 2 && group">
      <div class="condition-groups-scroll"><ConditionGroupFields :group="group" :fields="fields" :disabled="disabled" @edit="changeGroup" @invalid="error = $event" /></div>
      <p class="condition-help">每组可选“全部满足”或“任一满足”，可继续添加子组。取反作用于整组或整个判断，可能包含未填写字段；可用“已填写”单独限制。金额保持十进制原值，发布前请校验和模拟。</p>
    </template>
    <template v-else-if="mode === 'visual'">
      <label>条件组合<select :value="rows.join" :disabled="disabled" @change="update({ ...rows, join: ($event.target as HTMLSelectElement).value as 'AND' | 'OR' })"><option value="AND">全部满足</option><option value="OR">任一满足</option></select></label>
      <fieldset v-for="(row, index) in rows.rows" :key="index" :disabled="disabled" class="condition-row">
        <legend>条件 {{ index + 1 }}</legend>
        <ConditionRuleFields :row="row" :fields="fields" :version="languageVersion" :disabled="disabled" @change="change(index, $event)" @invalid="error = $event" />
        <button type="button" class="condition-remove" :disabled="rows.rows.length < 2" @click="remove(index)">删除条件 {{ index + 1 }}</button>
      </fieldset>
      <button type="button" class="secondary" :disabled="disabled" @click="add">＋ 添加条件</button>
      <p class="condition-help">条件直接使用下方表单字段。金额按十进制原值保存，发布时由服务端校验。</p>
    </template>
    <template v-else><label>条件表达式<textarea ref="expressionInput" :value="modelValue" :disabled="disabled" rows="3" placeholder="例如 amount > 5000" @focus="!restoringFocus && emit('beforeChange')" @input="expression" /></label><div v-if="fields.length" class="condition-fields"><p>插入字段<span>插入到光标处，或替换选中文字</span></p><div role="group" aria-label="插入条件字段"><button v-for="field in fields" :key="field.key" type="button" :disabled="disabled" :aria-label="`插入字段 ${field.label}（${field.key}）`" :title="field.key" @mousedown.prevent @click="insertField(field)">{{ field.label }}<code>{{ field.key }}</code></button></div></div><p v-if="!convertible" class="condition-help">{{ fields.length ? '当前表达式或字段尚不支持可视化配置，原文已保留。可继续编辑表达式并校验流程。' : '请先配置表单字段，已有表达式保持原样。' }}</p></template>
    <p v-if="mode === 'expression' && languageVersion === 2" class="condition-help">支持 AND / OR、&amp;&amp; / ||、! 和括号。单选字段可用 category IN ["TRAVEL", "DAILY"]。文本请加引号；取反会包含原条件为假的未填写情况，需要排除时请同时判断 EXISTS。校验流程可定位语法错误。</p>
    <div v-if="modelValue.trim()" class="condition-description"><strong>条件说明</strong><p>{{ description }}</p><small>以“校验流程”的服务端结果为准。</small></div>
    <p v-if="error" class="condition-error" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
.condition-editor{min-width:0}.condition-groups-scroll{max-width:100%;overflow:auto}.condition-modes{display:flex;gap:5px;margin:8px 0 16px}.condition-modes button{font-size:11px;padding:7px 10px;border:1px solid var(--line);border-radius:7px;background:var(--paper);color:var(--muted)}.condition-modes button[aria-pressed=true]{background:var(--soft);color:var(--deep);border-color:var(--deep)}.condition-row{border:1px solid var(--line);padding:10px;border-radius:9px;margin:13px 0}.condition-row legend{font-size:10px;color:var(--muted);padding:0 5px}.condition-row label{font-size:11px;margin-bottom:8px}.condition-row input,.condition-row select,.condition-editor textarea{font-size:12px;width:100%;min-width:0}.condition-remove{border:0;background:transparent;color:var(--red);font-size:10px;padding:4px 0}.condition-help{font-size:11px;color:var(--muted);line-height:1.8}.condition-error{font-size:11px;color:var(--red);line-height:1.8}
.condition-fields{margin:12px 0}.condition-fields>p{font-size:11px;color:var(--ink);line-height:1.7}.condition-fields>p span{display:block;color:var(--muted);font-size:10px}.condition-fields>div{display:flex;gap:6px;flex-wrap:wrap;max-height:180px;overflow:auto}.condition-fields button{display:grid;gap:3px;max-width:100%;text-align:left;border:1px solid var(--line);border-radius:7px;padding:7px 9px;background:var(--paper);color:var(--deep);font-size:11px;overflow-wrap:anywhere}.condition-fields code{font-size:10px;color:var(--muted);white-space:normal}.condition-fields button:focus-visible{outline:2px solid var(--deep);outline-offset:2px}.condition-description{margin-top:14px;border-left:2px solid var(--deep);padding:10px 12px;background:var(--soft);overflow-wrap:anywhere}.condition-description strong{font-size:10px;color:var(--deep)}.condition-description p{font-size:12px;line-height:1.8;white-space:pre-wrap;margin:7px 0}.condition-description small{font-size:10px;color:var(--muted)}
</style>
