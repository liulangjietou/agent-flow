<script setup lang="ts">
import { computed, onUnmounted, ref, useId } from 'vue'
import AttachmentField from './AttachmentField.vue'
import type { AttachmentContext } from '../attachments'
import DetailTableValue from './DetailTableValue.vue'
import type { FieldErrors, FormField, FormSchema } from '../formSchema'
import { displayFields, fieldErrorMessage, rawValueLabel, updatePayloadField, ownValue, isDetailRow } from '../formSchema'

const props = withDefaults(defineProps<{ schema?: FormSchema | null; modelValue: Record<string, unknown>; readonly?: boolean; disabled?: boolean; errors?: FieldErrors; attachmentContext?: AttachmentContext; fieldPrefix?: string }>(), { schema: null, readonly: false, disabled: false, errors: () => ({}), fieldPrefix: '' })
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, unknown>]; uploading: [busy: boolean] }>()
const prefix = useId()
const activeUploads = ref(new Set<string>())
function uploadState(key: string, busy: boolean) { if (busy) activeUploads.value.add(key); else activeUploads.value.delete(key); emit('uploading', activeUploads.value.size > 0) }
onUnmounted(() => emit('uploading', false))
const attachmentField = (key: string) => props.schema?.fields.find(field => field.key === key && field.type === 'ATTACHMENT')
const entries = computed(() => displayFields(props.schema, props.modelValue))
const extras = computed(() => entries.value.filter(field => field.extra))
const errorCode = (key: string) => ownValue(props.errors, key)
const inputValue = (key: string) => { const value = ownValue(props.modelValue, key); return value == null ? '' : typeof value === 'object' ? rawValueLabel(value) : String(value) }
function update(field: FormField, event: Event) {
  const text = (event.target as HTMLInputElement).value
  const value = field.type === 'BOOLEAN' ? text === '' ? null : text === 'true' : text
  emit('update:modelValue', updatePayloadField(props.modelValue, field.key, value))
}
const tableField = (key: string) => props.schema?.fields.find(field => field.key === key && field.type === 'TABLE')
const detailRows = (field: FormField): unknown[] => Array.isArray(ownValue(props.modelValue, field.key)) ? ownValue(props.modelValue, field.key) as unknown[] : []
const columnSchema = (field: FormField): FormSchema => ({ schemaVersion: 1, fields: (field.columns ?? []).filter(column => column.type !== 'TABLE') })
const rowErrors = (field: FormField, index: number): FieldErrors => Object.fromEntries(Object.entries(props.errors)
  .filter(([key]) => key.startsWith(`${field.key}[${index}].`)).map(([key, code]) => [key.slice(`${field.key}[${index}].`.length), code]))
function changeRows(field: FormField, rows: unknown[]) {
  if (!props.disabled) emit('update:modelValue', updatePayloadField(props.modelValue, field.key, rows))
}
function moveRow(field: FormField, index: number, offset: number) {
  const rows = [...detailRows(field)]
  const [row] = rows.splice(index, 1); rows.splice(index + offset, 0, row); changeRows(field, rows)
}
</script>

<template>
  <div class="form-fields">
    <template v-if="readonly">
      <dl v-if="entries.length" class="payload-list"><template v-for="entry in entries" :key="entry.key"><dt>{{ entry.label }}<small v-if="entry.extra">其他已保存字段</small></dt><dd :class="{ 'detail-value-cell': tableField(entry.key) }"><DetailTableValue v-if="tableField(entry.key)" :field="tableField(entry.key)!" :value="ownValue(modelValue, entry.key)" :attachment-context="attachmentContext" /><AttachmentField v-else-if="attachmentField(entry.key)" :model-value="ownValue(modelValue, entry.key)" :field-path="fieldPrefix + entry.key" :context="attachmentContext" readonly /><template v-else>{{ entry.value }}</template></dd></template></dl>
      <p v-else class="unavailable">此表单没有业务字段。</p>
    </template>
    <template v-else-if="schema">
      <p v-if="!schema.fields.length" class="unavailable">此流程仅需填写申请标题和业务单号。</p>
      <div v-for="(field, index) in schema.fields" :key="index" class="dynamic-field">
        <label :for="`${prefix}-${index}`">{{ field.label }}<span v-if="field.required" class="required-mark" aria-label="必填"> *</span></label>
        <div v-if="field.type === 'TABLE'" :id="`${prefix}-${index}`" class="detail-table" role="group" :aria-label="field.label" :aria-describedby="`${prefix}-${index}-help`">
          <p class="detail-caption">{{ detailRows(field).length }} / {{ field.maxRows ?? 50 }} 行 · 每行单独填写一项</p>
          <p v-if="ownValue(modelValue, field.key) != null && !Array.isArray(ownValue(modelValue, field.key))" class="field-error">原明细格式不匹配：{{ rawValueLabel(ownValue(modelValue, field.key)) }} <button type="button" :disabled="disabled" @click="changeRows(field, [])">清空无效明细</button></p>
          <section v-for="(row, rowIndex) in detailRows(field)" :key="rowIndex" class="detail-row" :aria-label="`${field.label} 第 ${rowIndex + 1} 行`">
            <div class="detail-row-heading"><strong>第 {{ rowIndex + 1 }} 行</strong><div><button type="button" :disabled="disabled || activeUploads.size > 0 || rowIndex === 0" :aria-label="`上移${field.label}第 ${rowIndex + 1} 行`" @click="moveRow(field, rowIndex, -1)">↑</button><button type="button" :disabled="disabled || activeUploads.size > 0 || rowIndex === detailRows(field).length - 1" :aria-label="`下移${field.label}第 ${rowIndex + 1} 行`" @click="moveRow(field, rowIndex, 1)">↓</button><button type="button" :disabled="disabled || activeUploads.size > 0" :aria-label="`删除${field.label}第 ${rowIndex + 1} 行`" @click="changeRows(field, detailRows(field).filter((_, current) => current !== rowIndex))">删除</button></div></div>
            <FormFields v-if="isDetailRow(row)" :attachment-context="attachmentContext" :field-prefix="field.key + '.'" @uploading="uploadState(field.key + ':' + rowIndex, $event)" :schema="columnSchema(field)" :model-value="row" :errors="rowErrors(field, rowIndex)" :disabled="disabled" @update:model-value="value => changeRows(field, detailRows(field).map((saved, current) => current === rowIndex ? value : saved))" />
            <p v-else class="field-error">此行格式不匹配：{{ rawValueLabel(row) }}。请删除后重新添加。</p>
          </section>
          <button type="button" class="secondary" :disabled="disabled || activeUploads.size > 0 || detailRows(field).length >= (field.maxRows ?? 50) || (ownValue(modelValue, field.key) != null && !Array.isArray(ownValue(modelValue, field.key)))" @click="changeRows(field, [...detailRows(field), {}])">＋ 添加{{ field.label }}行</button>
        </div>
        <AttachmentField v-else-if="field.type === 'ATTACHMENT'" :model-value="ownValue(modelValue, field.key)" :field-path="fieldPrefix + field.key" :context="attachmentContext" :disabled="disabled" @update:model-value="emit('update:modelValue', updatePayloadField(modelValue, field.key, $event))" @uploading="uploadState(field.key, $event)" />
        <textarea v-else-if="field.type === 'TEXTAREA'" :id="`${prefix}-${index}`" :value="inputValue(field.key)" :disabled="disabled" rows="3" :aria-required="field.required" :aria-invalid="!!errorCode(field.key)" :aria-describedby="`${prefix}-${index}-help`" @input="update(field, $event)" />
        <select v-else-if="field.type === 'SELECT'" :id="`${prefix}-${index}`" :value="inputValue(field.key)" :disabled="disabled" :aria-required="field.required" :aria-invalid="!!errorCode(field.key)" :aria-describedby="`${prefix}-${index}-help`" @change="update(field, $event)">
          <option value="">请选择</option><option v-for="option in field.options" :key="option.value" :value="option.value">{{ option.label }}</option>
          <option v-if="inputValue(field.key) && !field.options?.some(option => option.value === inputValue(field.key))" :value="inputValue(field.key)">{{ inputValue(field.key) }}（已保存的其他值）</option>
        </select>
        <select v-else-if="field.type === 'BOOLEAN'" :id="`${prefix}-${index}`" :value="inputValue(field.key)" :disabled="disabled" :aria-required="field.required" :aria-invalid="!!errorCode(field.key)" :aria-describedby="`${prefix}-${index}-help`" @change="update(field, $event)"><option value="">未填写</option><option value="true">是</option><option value="false">否</option><option v-if="inputValue(field.key) && !['true', 'false'].includes(inputValue(field.key))" :value="inputValue(field.key)">{{ inputValue(field.key) }}（值类型不匹配）</option></select>
        <input v-else :id="`${prefix}-${index}`" :value="inputValue(field.key)" :type="field.type === 'DATE' ? 'date' : 'text'" :inputmode="field.type === 'NUMBER' ? 'decimal' : 'text'" :disabled="disabled" :aria-required="field.required" :aria-invalid="!!errorCode(field.key)" :aria-describedby="`${prefix}-${index}-help`" @input="update(field, $event)" />
        <div :id="`${prefix}-${index}-help`" class="field-guidance"><small v-if="field.helpText">{{ field.helpText }}</small><small v-if="field.maxLength != null">最多 {{ field.maxLength }} 字</small><small v-if="field.type === 'NUMBER' && (field.minimum != null || field.maximum != null)">{{ field.minimum != null ? `最小 ${field.minimum}` : '' }}{{ field.minimum != null && field.maximum != null ? ' · ' : '' }}{{ field.maximum != null ? `最大 ${field.maximum}` : '' }}</small><p v-if="errorCode(field.key)" class="field-error" role="alert">{{ fieldErrorMessage(errorCode(field.key)!) }}</p></div>
      </div>
      <div v-if="extras.length" class="saved-extra"><strong>其他已保存字段</strong><p>以下内容保持原样保存。</p><dl class="payload-list"><template v-for="entry in extras" :key="entry.key"><dt>{{ entry.label }}</dt><dd>{{ entry.value }}<p v-if="errorCode(entry.key)" class="field-error" role="alert">{{ fieldErrorMessage(errorCode(entry.key)!) }}</p></dd></template></dl></div>
    </template>
  </div>
</template>

<style scoped>
.dynamic-field{margin-bottom:18px}.dynamic-field>label{display:block;color:var(--ink);font-size:12px;margin:0 0 7px}.dynamic-field input,.dynamic-field textarea,.dynamic-field select{display:block;width:100%;border:1px solid var(--line);border-radius:8px;background:#fbfcfc;color:var(--ink);padding:10px 12px;font-family:inherit;font-size:12px;margin:0}.dynamic-field [aria-invalid="true"]{border-color:var(--red)}.required-mark{color:var(--red)}.field-guidance{font-size:11px;color:var(--muted);line-height:1.6}.field-guidance small{display:block;margin-top:5px}.field-error{color:var(--red);font-size:11px;margin:5px 0 0}.saved-extra{border-top:1px solid var(--line);padding-top:14px;font-size:12px}.saved-extra>p,.payload-list dt small{color:var(--muted);font-size:10px}.payload-list dt small{display:block;margin-top:5px}.dynamic-field textarea:focus-visible{outline:3px solid rgba(33,173,159,.35);outline-offset:2px}
.detail-caption{font-size:11px;color:var(--muted)}.detail-row{border:1px solid var(--line);border-radius:9px;padding:14px;margin:12px 0;background:var(--paper);min-width:0}.detail-row-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:14px;font-size:12px}.detail-row-heading button{font-size:11px;padding:5px 8px;color:var(--deep)}.detail-row-heading button:last-child{color:var(--red)}.detail-row>strong{display:block;font-size:12px;margin-bottom:12px}
.detail-value-cell{grid-column:1 / -1;min-width:0;margin:0 0 16px}
</style>
