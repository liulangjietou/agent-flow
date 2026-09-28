<script setup lang="ts">
import { computed, ref, watch, useId } from 'vue'
import FormFields from './FormFields.vue'
import FieldPermissionPreview from './FieldPermissionPreview.vue'
import { defaultFormSchema, fieldTypes, validatePayload, validateFormSchema, ownValue, type FieldErrors, type FieldVisibility, type FieldType, type FormField, type FormSchema } from '../formSchema'

const props = defineProps<{ modelValue: FormSchema | null; disabled: boolean; columnsOnly?: boolean; approvalNodes?: Array<{ id: string; name: string }>; scopeKey?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: FormSchema]; beforeChange: [] }>()
const headingId = useId()
const fieldLimit = computed(() => props.columnsOnly ? 20 : 50)
const availableTypes = computed(() => props.columnsOnly ? fieldTypes.filter(type => type.value !== 'TABLE') : fieldTypes)
const rows = ref<Array<{ id: string; field: FormField }>>([])
const preview = ref<Record<string, unknown>>({})
const previewErrors = ref<FieldErrors>({})
const previewChecked = ref(false)
watch(() => props.scopeKey, () => { preview.value = {}; previewErrors.value = {}; previewChecked.value = false }, { flush: 'sync' })
const configErrors = computed(() => validateFormSchema(props.modelValue, props.columnsOnly))
const configErrorCount = computed(() => configErrors.value.schema.length + configErrors.value.fields.reduce((count, fields) => count + Object.keys(fields).length, 0))
const configError = (index: number, property: string) => ownValue(configErrors.value.fields[index] ?? {}, property)
const configErrorId = (id: string, property: string) => `${id}-${property.replace(/\./g, '-')}-error`
function errorAttributes(index: number, property: string) {
  return { 'aria-invalid': !!configError(index, property), 'aria-describedby': configError(index, property) ? configErrorId(rows.value[index].id, property) : undefined }
}
let emittedSnapshot = ''
watch(() => props.modelValue, value => {
  const snapshot = JSON.stringify(value)
  if (snapshot === emittedSnapshot) return
  emittedSnapshot = snapshot
  rows.value = (value?.fields ?? []).map(field => ({ id: crypto.randomUUID(), field: JSON.parse(JSON.stringify(field)) as FormField }))
  preview.value = {}; previewErrors.value = {}; previewChecked.value = false
}, { immediate: true })
function permissionNodes(field: FormField) {
  const nodes = [...(props.approvalNodes ?? [])]
  for (const id of Object.keys(field.nodeAccess ?? {})) if (!nodes.some(node => node.id === id)) nodes.push({ id, name: `已删除节点 ${id}（请选择默认以清除）` })
  return nodes
}
function permission(field: FormField, id: string, event: Event) {
  if (props.disabled) return
  emit('beforeChange')
  const value = (event.target as HTMLSelectElement).value, access = { ...field.nodeAccess }
  if (value) access[id] = value as FieldVisibility
  else delete access[id]
  field.nodeAccess = Object.keys(access).length ? access : undefined
  publish()
}
function publish() {
  if (props.disabled) return
  const value: FormSchema = { schemaVersion: props.modelValue?.schemaVersion === 2 || rows.value.some(row => row.field.type === 'TABLE') ? 2 : 1, fields: rows.value.map(row => JSON.parse(JSON.stringify(row.field)) as FormField) }
  emittedSnapshot = JSON.stringify(value)
  previewErrors.value = {}; previewChecked.value = false
  emit('update:modelValue', value)
}
function enable() {
  if (props.disabled) return
  emit('beforeChange')
  rows.value = defaultFormSchema().fields.map(field => ({ id: crypto.randomUUID(), field }))
  publish()
}
function add() {
  if (props.disabled || rows.value.length >= fieldLimit.value) return
  emit('beforeChange')
  let number = rows.value.length + 1
  while (rows.value.some(row => row.field.key === `field${number}`)) number++
  rows.value.push({ id: crypto.randomUUID(), field: { key: `field${number}`, label: '新字段', type: 'TEXT', required: false } })
  publish()
}
function remove(index: number) { if (props.disabled) return; emit('beforeChange'); rows.value.splice(index, 1); publish() }
function move(index: number, offset: number) {
  if (props.disabled || index + offset < 0 || index + offset >= rows.value.length) return
  emit('beforeChange')
  const [row] = rows.value.splice(index, 1); rows.value.splice(index + offset, 0, row); publish()
}
function changeType(row: { field: FormField }, event: Event) {
  if (props.disabled) return
  const type = (event.target as HTMLSelectElement).value as FieldType
  const { key, label, required, helpText, sensitive, nodeAccess } = row.field
  row.field = { key, label, type, required, ...(sensitive != null ? { sensitive } : {}), ...(nodeAccess ? { nodeAccess } : {}), ...(helpText ? { helpText } : {}), ...(type === 'TABLE' ? { columns: [{ key: 'name', label: '名称', type: 'TEXT' as const, required: true }], maxRows: 50 } : {}), ...(type === 'SELECT' ? { options: [{ value: 'option1', label: '选项一' }] } : {}) }
  publish()
}
function maxLength(field: FormField, event: Event, property: 'maxLength' | 'maxRows' = 'maxLength') {
  const value = (event.target as HTMLInputElement).value
  if (value === '') delete field[property]
  else field[property] = Number(value)
  publish()
}
function optionalNumber(field: FormField, key: 'minimum' | 'maximum', event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (value === '') delete field[key]
  else field[key] = value
  publish()
}
function addOption(field: FormField) {
  if (props.disabled || (field.options?.length ?? 0) >= 50) return
  emit('beforeChange')
  const options = field.options ?? (field.options = [])
  let number = options.length + 1
  while (options.some(option => option.value === `option${number}`)) number++
  options.push({ value: `option${number}`, label: `选项 ${number}` }); publish()
}
function removeOption(field: FormField, index: number) { if (props.disabled) return; emit('beforeChange'); field.options?.splice(index, 1); publish() }
function checkPreview() { previewErrors.value = validatePayload(props.modelValue, preview.value, true); previewChecked.value = true }
</script>

<template>
  <section class="form-designer" :class="{ 'columns-editor': columnsOnly }" :aria-labelledby="headingId">
    <div class="schema-heading"><div><p v-if="!columnsOnly" class="eyebrow">APPLICATION FORM</p><h3 :id="headingId">{{ columnsOnly ? '明细列配置' : '申请表单' }}</h3><p>{{ columnsOnly ? '每行共用以下列配置，支持调整列顺序。' : '配置申请人填写的内容，随流程版本一起发布。' }}</p></div><span v-if="modelValue" class="field-count">{{ rows.length }} / {{ fieldLimit }} {{ columnsOnly ? '列' : '个字段' }}</span></div>
    <div v-if="modelValue === null" class="legacy-schema"><strong>此版本使用原通用表单</strong><p>已有申请继续保留金额、说明及其他原始内容。启用字段配置后，可为新版本定义申请表单。</p><button type="button" class="secondary" :disabled="disabled" @click="enable">启用字段配置</button></div>
    <div v-else class="schema-workspace">
      <div class="field-config"><p v-for="issue in configErrors.schema" :key="issue" class="config-error" role="alert">{{ issue }}</p><p v-if="configErrorCount" class="config-validation-summary" role="status">还有 {{ configErrorCount }} 项配置需要调整，请查看字段下方提示。</p><div class="config-caption"><strong>字段配置</strong><button type="button" class="secondary" :disabled="disabled || rows.length >= fieldLimit" @click="add">＋ 添加字段</button></div>
        <p v-if="!rows.length" class="unavailable">没有业务字段。申请人仍可填写标题和业务单号。</p>
        <fieldset v-for="(row, index) in rows" :key="row.id" class="field-card" :disabled="disabled">
          <div class="field-card-heading"><span class="field-order">{{ index + 1 }}</span><strong>{{ row.field.label || '未命名字段' }}</strong><div><button type="button" :disabled="disabled || index === 0" :aria-label="`上移字段 ${index + 1}`" @click="move(index, -1)">↑</button><button type="button" :disabled="disabled || index === rows.length - 1" :aria-label="`下移字段 ${index + 1}`" @click="move(index, 1)">↓</button><button type="button" class="field-remove" :aria-label="`删除字段 ${index + 1}`" @click="remove(index)">删除</button></div></div>
          <div class="field-config-grid">
            <label>字段名称<input v-model="row.field.label" v-bind="errorAttributes(index, 'label')" :aria-label="`字段 ${index + 1} 名称`" maxlength="128" @focus="emit('beforeChange')" @input="publish" /><span v-if="configError(index, 'label')" :id="configErrorId(row.id, 'label')" class="config-error">{{ configError(index, 'label') }}</span></label>
            <label>字段标识<input v-model="row.field.key" v-bind="errorAttributes(index, 'key')" :aria-label="`字段 ${index + 1} 标识`" maxlength="64" @focus="emit('beforeChange')" @input="publish" /><span v-if="configError(index, 'key')" :id="configErrorId(row.id, 'key')" class="config-error">{{ configError(index, 'key') }}</span></label>
            <label>填写类型<select :value="row.field.type" v-bind="errorAttributes(index, 'type')" :aria-label="`字段 ${index + 1} 类型`" @focus="emit('beforeChange')" @change="changeType(row, $event)"><option v-for="type in availableTypes" :key="type.value" :value="type.value">{{ type.label }}</option></select><span v-if="configError(index, 'type')" :id="configErrorId(row.id, 'type')" class="config-error">{{ configError(index, 'type') }}</span></label>
            <label class="required-toggle"><input v-model="row.field.required" type="checkbox" :aria-label="`字段 ${index + 1} 必填`" @focus="emit('beforeChange')" @change="publish" /> 提交时必填</label>
          </div>
          <div class="field-permissions">
            <label class="required-toggle"><input v-model="row.field.sensitive" type="checkbox" :aria-label="`字段 ${index + 1} 敏感`" @focus="emit('beforeChange')" @change="publish" /> 敏感字段：默认向非申请人脱敏</label>
            <details><summary>审批节点字段权限</summary><p>申请人仅在草稿、退回、撤回时编辑。审批节点始终只读；未处于相应节点的管理员同样受字段限制。同时处于多个节点时，按更严格的权限显示。</p>
              <label v-for="node in permissionNodes(row.field)" :key="node.id">{{ node.name }}<select :value="row.field.nodeAccess?.[node.id] ?? ''" @change="permission(row.field, node.id, $event)"><option value="">默认（敏感字段脱敏，其余只读）</option><option value="READ_ONLY">可见原值 · 只读</option><option value="MASKED">脱敏</option><option value="HIDDEN">隐藏</option></select></label>
              <p v-if="!permissionNodes(row.field).length">添加审批节点后即可配置。</p>
              <p v-if="configError(index, 'nodeAccess')" class="config-error">{{ configError(index, 'nodeAccess') }}</p>
            </details>
          </div>
          <label>填写提示<input v-model="row.field.helpText" v-bind="errorAttributes(index, 'helpText')" :aria-label="`字段 ${index + 1} 提示`" maxlength="1000" placeholder="选填，帮助申请人准确填写" @focus="emit('beforeChange')" @input="publish" /><span v-if="configError(index, 'helpText')" :id="configErrorId(row.id, 'helpText')" class="config-error">{{ configError(index, 'helpText') }}</span></label>
          <label v-if="['TEXT', 'TEXTAREA'].includes(row.field.type)">最多字符数<input :value="row.field.maxLength ?? ''" v-bind="errorAttributes(index, 'maxLength')" type="number" min="1" max="10000" :aria-label="`字段 ${index + 1} 最多字符数`" placeholder="不设置时最多 10000 字" @focus="emit('beforeChange')" @input="maxLength(row.field, $event)" /><span v-if="configError(index, 'maxLength')" :id="configErrorId(row.id, 'maxLength')" class="config-error">{{ configError(index, 'maxLength') }}</span></label>
          <div v-if="row.field.type === 'NUMBER'" class="field-config-grid">
            <label>最小值<input :value="row.field.minimum ?? ''" v-bind="errorAttributes(index, 'minimum')" inputmode="decimal" :aria-label="`字段 ${index + 1} 最小值`" placeholder="选填" @focus="emit('beforeChange')" @input="optionalNumber(row.field, 'minimum', $event)" /><span v-if="configError(index, 'minimum')" :id="configErrorId(row.id, 'minimum')" class="config-error">{{ configError(index, 'minimum') }}</span></label>
            <label>最大值<input :value="row.field.maximum ?? ''" v-bind="errorAttributes(index, 'maximum')" inputmode="decimal" :aria-label="`字段 ${index + 1} 最大值`" placeholder="选填" @focus="emit('beforeChange')" @input="optionalNumber(row.field, 'maximum', $event)" /><span v-if="configError(index, 'maximum')" :id="configErrorId(row.id, 'maximum')" class="config-error">{{ configError(index, 'maximum') }}</span></label>
          </div>
          <div v-if="row.field.type === 'SELECT'" class="option-config" role="group" :aria-label="`字段 ${index + 1} 选项配置`" v-bind="errorAttributes(index, 'options')">
            <div class="option-heading"><strong>选项</strong><button type="button" :disabled="disabled || (row.field.options?.length ?? 0) >= 50" @click="addOption(row.field)">＋ 添加选项</button></div>
            <p v-if="configError(index, 'options')" :id="configErrorId(row.id, 'options')" class="config-error" role="alert">{{ configError(index, 'options') }}</p>
            <div v-for="(option, optionIndex) in row.field.options" :key="optionIndex" class="option-row">
              <label>保存值<input v-model="option.value" v-bind="errorAttributes(index, `options.${optionIndex}.value`)" :aria-label="`字段 ${index + 1} 选项 ${optionIndex + 1} 保存值`" @focus="emit('beforeChange')" @input="publish" /><span v-if="configError(index, `options.${optionIndex}.value`)" :id="configErrorId(row.id, `options.${optionIndex}.value`)" class="config-error">{{ configError(index, `options.${optionIndex}.value`) }}</span></label>
              <label>显示名称<input v-model="option.label" v-bind="errorAttributes(index, `options.${optionIndex}.label`)" :aria-label="`字段 ${index + 1} 选项 ${optionIndex + 1} 名称`" @focus="emit('beforeChange')" @input="publish" /><span v-if="configError(index, `options.${optionIndex}.label`)" :id="configErrorId(row.id, `options.${optionIndex}.label`)" class="config-error">{{ configError(index, `options.${optionIndex}.label`) }}</span></label>
              <button type="button" :aria-label="`删除字段 ${index + 1} 选项 ${optionIndex + 1}`" @click="removeOption(row.field, optionIndex)">×</button>
            </div>
          </div>
          <template v-if="row.field.type === 'TABLE' && !columnsOnly">
            <label>最多明细行数<input :value="row.field.maxRows ?? ''" v-bind="errorAttributes(index, 'maxRows')" type="number" min="1" max="100" :aria-label="`字段 ${index + 1} 最多明细行数`" placeholder="不设置时最多 50 行" @focus="emit('beforeChange')" @input="maxLength(row.field, $event, 'maxRows')" /><span v-if="configError(index, 'maxRows')" :id="configErrorId(row.id, 'maxRows')" class="config-error">{{ configError(index, 'maxRows') }}</span></label>
            <p v-if="configError(index, 'columns')" class="config-error" role="alert">{{ configError(index, 'columns') }}</p>
            <FormSchemaEditor :model-value="{ schemaVersion: 1, fields: row.field.columns ?? [] }" :disabled="disabled" :approval-nodes="approvalNodes" columns-only @before-change="emit('beforeChange')" @update:model-value="value => { row.field.columns = value.fields; publish() }" />
          </template>
        </fieldset>
        <p class="config-footnote">字段标识以字母开头，只使用字母、数字与下划线。字段顺序决定填写顺序。</p>
      </div>
      <aside v-if="!columnsOnly" class="form-preview" aria-label="申请表单预览">
        <div class="preview-title"><span class="preview-dot"></span><strong>申请人填写预览</strong><small>预览内容不会保存</small></div>
        <FormFields v-model="preview" :schema="modelValue" :errors="previewErrors" @update:model-value="previewChecked = false; previewErrors = {}" />
        <p v-if="configErrorCount" class="config-error">请先修正字段配置，再检查预览填写。</p>
        <button type="button" class="secondary" :disabled="configErrorCount > 0" @click="checkPreview">检查预览填写</button>
        <p v-if="previewChecked && !Object.keys(previewErrors).length" class="preview-success" role="status">预览填写符合当前字段要求。</p>
        <FieldPermissionPreview :schema="modelValue" :values="preview" :approval-nodes="approvalNodes ?? []" :scope-key="scopeKey ?? ''" :invalid="configErrorCount > 0" />
      </aside>
    </div>
  </section>
</template>

<style scoped>
.field-permissions{border-top:1px solid var(--line);margin:14px 0;padding-top:10px}.field-permissions p{font-size:12px;line-height:1.7;color:var(--muted)}.field-permissions summary{cursor:pointer}

.form-designer{margin-top:24px;background:white;border:1px solid var(--line);border-radius:15px;overflow:clip}.schema-heading{display:flex;justify-content:space-between;align-items:center;padding:23px 25px;border-bottom:1px solid var(--line);gap:15px}.schema-heading h3{font-size:18px;margin:0 0 8px}.schema-heading p:not(.eyebrow){font-size:12px;color:var(--muted);margin:0;line-height:1.7}.field-count{font:11px 'DM Mono',monospace;color:var(--muted);white-space:nowrap}.schema-workspace{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(270px,.85fr)}.field-config{padding:22px 24px;border-right:1px solid var(--line);min-width:0}.config-caption,.field-card-heading,.option-heading{display:flex;align-items:center;justify-content:space-between;gap:10px}.config-caption{margin-bottom:18px;font-size:13px}.field-card{background:var(--paper);border:1px solid var(--line);border-radius:10px;padding:16px;margin-bottom:14px}.field-card-heading{font-size:12px;margin-bottom:16px}.field-order{background:var(--soft);color:var(--deep);width:23px;height:23px;border-radius:6px;display:grid;place-items:center;font:11px 'DM Mono',monospace}.field-card-heading>strong{margin-right:auto;overflow-wrap:anywhere;min-width:0}.field-card-heading>div{display:flex;gap:2px;flex-shrink:0}.field-card-heading button{padding:5px 7px;font-size:11px}.field-remove{color:var(--red)}.field-config-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.field-card label{display:block;font-size:11px;color:var(--muted);margin-bottom:12px}.field-card input,.field-card select{display:block;width:100%;min-width:0;border:1px solid var(--line);border-radius:7px;padding:9px 10px;font-family:inherit;font-size:12px;background:white;color:var(--ink);margin-top:6px}.field-card .required-toggle{display:flex;gap:8px;align-items:center;align-self:center;padding-top:11px}.required-toggle input{width:15px;height:15px;margin:0;accent-color:var(--deep)}.option-config{border-top:1px solid var(--line);padding-top:12px}.option-heading{font-size:11px;margin-bottom:9px}.option-heading button{color:var(--deep)}.option-row{display:grid;grid-template-columns:1fr 1fr 24px;gap:8px;align-items:center}.option-row>button{color:var(--red);font-size:20px}.config-footnote{font-size:10px;line-height:1.8;color:var(--muted)}.form-preview{padding:24px;position:sticky;top:100px;align-self:start;min-width:0}.preview-title{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:26px;font-size:13px}.preview-title small{font-size:10px;color:var(--muted);margin-left:auto}.preview-dot{width:7px;height:7px;border-radius:50%;background:var(--teal)}.preview-success{font-size:11px;color:var(--deep)}.legacy-schema{padding:24px;font-size:13px}.legacy-schema p{color:var(--muted);font-size:12px;line-height:1.8;max-width:700px}
.config-error{display:block;color:var(--red);font-size:10px;line-height:1.7;margin:6px 0 0}.config-validation-summary{color:var(--red);font-size:11px;line-height:1.7;background:#fff0ed;border-radius:8px;padding:10px 12px;margin:0 0 16px}.field-card [aria-invalid="true"]{border-color:var(--red)}
@media(max-width:900px){.schema-workspace{grid-template-columns:1fr}.field-config{border-right:0;border-bottom:1px solid var(--line)}.form-preview{position:static}.schema-heading,.field-config,.form-preview{padding:20px}}@media(max-width:650px){.schema-heading{align-items:flex-start;flex-direction:column}.field-config-grid{grid-template-columns:1fr;gap:0}.field-card{padding:13px}.field-card .required-toggle{padding:0}.option-row{grid-template-columns:1fr 1fr 24px}}
.columns-editor{margin-top:10px;border-radius:9px}.columns-editor>.schema-heading{padding:14px}.columns-editor>.schema-heading h3{font-size:14px}.columns-editor>.schema-workspace{display:block}.columns-editor>.schema-workspace>.field-config{padding:14px;border:0}.columns-editor>.schema-heading .field-count{white-space:normal}
</style>
