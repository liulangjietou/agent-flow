<script setup lang="ts">
import { computed, useId } from 'vue'
import type { FieldErrors, FormField, FormSchema } from '../formSchema'
import { displayFields, fieldErrorMessage, rawValueLabel, updatePayloadField, ownValue } from '../formSchema'

const props = withDefaults(defineProps<{ schema?: FormSchema | null; modelValue: Record<string, unknown>; readonly?: boolean; disabled?: boolean; errors?: FieldErrors }>(), { schema: null, readonly: false, disabled: false, errors: () => ({}) })
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, unknown>] }>()
const prefix = useId()
const entries = computed(() => displayFields(props.schema, props.modelValue))
const extras = computed(() => entries.value.filter(field => field.extra))
const errorCode = (key: string) => ownValue(props.errors, key)
const inputValue = (key: string) => { const value = ownValue(props.modelValue, key); return value == null ? '' : typeof value === 'object' ? rawValueLabel(value) : String(value) }
function update(field: FormField, event: Event) {
  const text = (event.target as HTMLInputElement).value
  const value = field.type === 'BOOLEAN' ? text === '' ? null : text === 'true' : text
  emit('update:modelValue', updatePayloadField(props.modelValue, field.key, value))
}
</script>

<template>
  <div class="form-fields">
    <template v-if="readonly">
      <dl v-if="entries.length" class="payload-list"><template v-for="entry in entries" :key="entry.key"><dt>{{ entry.label }}<small v-if="entry.extra">其他已保存字段</small></dt><dd>{{ entry.value }}</dd></template></dl>
      <p v-else class="unavailable">此表单没有业务字段。</p>
    </template>
    <template v-else-if="schema">
      <p v-if="!schema.fields.length" class="unavailable">此流程仅需填写申请标题和业务单号。</p>
      <div v-for="(field, index) in schema.fields" :key="index" class="dynamic-field">
        <label :for="`${prefix}-${index}`">{{ field.label }}<span v-if="field.required" class="required-mark" aria-label="必填"> *</span></label>
        <textarea v-if="field.type === 'TEXTAREA'" :id="`${prefix}-${index}`" :value="inputValue(field.key)" :disabled="disabled" rows="3" :aria-required="field.required" :aria-invalid="!!errorCode(field.key)" :aria-describedby="`${prefix}-${index}-help`" @input="update(field, $event)" />
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
</style>
