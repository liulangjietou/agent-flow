<script setup lang="ts">
import { computed, onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import type { DefinitionCatalogItem } from '../definitionCatalog'
import { DefinitionSelection } from '../definitionSelection'
import { fieldTypes, ownValue, type FormField, type FormSchema } from '../formSchema'
import { subprocessInputIssue, subprocessVersion, type SubprocessBinding } from '../subprocessDesigner'
import DefinitionPicker from './DefinitionPicker.vue'

const props = defineProps<{ modelValue: SubprocessBinding; nodeId: string; formSchema: FormSchema | null; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: SubprocessBinding]; beforeChange: [] }>()
const current = reactive(new DefinitionSelection(api.searchDefinitions, api.getDefinition))
const choice = reactive(new DefinitionSelection(api.searchDefinitions, api.getDefinition))
const version = computed(() => subprocessVersion(props.modelValue))
const referenced = computed(() => props.modelValue.key !== undefined || props.modelValue.version !== undefined || Object.keys(props.modelValue.inputs).length > 0)
const targetFields = computed(() => current.definition?.formSchema?.fields ?? [])
const sourceFields = computed(() => props.formSchema?.fields ?? [])
const removedTargets = computed(() => Object.keys(props.modelValue.inputs).filter(key => !targetFields.value.some(field => field.key === key)))
const editingDisabled = computed(() => props.disabled || current.loading || choice.loading || !current.definition)
const typeLabel = (field: FormField) => fieldTypes.find(type => type.value === field.type)?.label ?? field.type
const input = (target: string) => ownValue(props.modelValue.inputs, target) ?? ''
const issue = (target: FormField, source: string) => subprocessInputIssue(sourceFields.value.find(field => field.key === source), target, props.nodeId)

function loadCurrent() {
  current.clear()
  if (version.value !== undefined) void current.load(props.scopeKey, '', { publishedOnly: true, processKey: props.modelValue.key, version: version.value })
}
/** 用户明确点选且完整配置复核通过后才替换；更换版本清空旧映射，读取本身不修改节点。 */
async function choose(item: DefinitionCatalogItem) {
  if (props.disabled || choice.loading || item.status !== 'PUBLISHED' || !item.startEnabled) return
  const selected = await choice.load(props.scopeKey, item.id, { publishedOnly: true, startEnabledOnly: true, processKey: item.key, version: item.version })
  if (!selected || props.disabled) return
  if (selected.key === props.modelValue.key && String(selected.version) === props.modelValue.version) { loadCurrent(); return }
  emit('beforeChange'); emit('update:modelValue', { key: selected.key, version: String(selected.version), inputs: {} })
}
function clear() {
  if (props.disabled) return
  choice.clear(); emit('beforeChange'); emit('update:modelValue', { inputs: {} })
}
/** 移除失效项必须显式操作；权限或字段变化不会悄悄改写原映射。 */
function changeInput(targetKey: string, sourceKey: string) {
  if (editingDisabled.value) return
  const target = targetFields.value.find(field => field.key === targetKey)
  if (sourceKey && (!target || issue(target, sourceKey))) return
  const inputs = { ...props.modelValue.inputs }
  if (sourceKey) inputs[targetKey] = sourceKey
  else delete inputs[targetKey]
  emit('beforeChange'); emit('update:modelValue', { ...props.modelValue, inputs })
}
watch([() => props.scopeKey, () => props.modelValue.key, () => props.modelValue.version], () => { choice.clear(); loadCurrent() }, { immediate: true, flush: 'sync' })
watch(() => props.disabled, disabled => { if (disabled) choice.clear() }, { flush: 'sync' })
onUnmounted(() => { current.clear(); choice.clear() })
</script>

<template>
  <section class="subprocess-definition" aria-label="子流程配置">
    <DefinitionPicker label="子流程发布版本" :scope-key="scopeKey" :selected-id="current.definition?.id" :selected-label="referenced ? `${modelValue.key || '未选择流程'} · v${modelValue.version || '未选择版本'}` : ''" published-only start-enabled-only :locked="disabled || choice.loading" @select="choose" />
    <p>固定使用所选发布版本。明确选择其他版本后，需重新配置输入；刷新目录不会替换引用。</p>
    <p v-if="choice.loading" role="status">正在核对所选版本…</p>
    <p v-if="choice.error" role="alert">{{ choice.error }} 原引用和映射已保留。</p>
    <div v-if="referenced" class="subprocess-current">
      <p v-if="current.loading" role="status">正在读取原发布版本…</p>
      <p v-else-if="version === undefined" role="alert">原引用不完整或版本格式无效，请明确选择发布版本。原配置已保留。</p>
      <p v-else-if="current.error || !current.definition" role="alert">{{ current.error || '当前租户找不到原发布版本。' }} 原引用和映射已保留，请核对后重新选择。</p>
      <template v-else>
        <strong>{{ current.definition.name }}</strong>
        <p v-if="!current.definition.startEnabled" role="alert">此版本已停用。发布和发起前须恢复原版本，或明确选择其他版本。</p>
        <div class="subprocess-mappings" role="group" aria-label="子流程输入映射">
          <label v-for="field in targetFields" :key="field.key">
            <span>{{ field.label }}{{ field.required ? '（必填）' : '' }} · {{ typeLabel(field) }}{{ field.sensitive ? ' · 敏感' : '' }}</span>
            <small>子字段 {{ field.key }}</small>
            <select :aria-label="`子字段 ${field.label} 的父表单来源`" :value="input(field.key)" :disabled="editingDisabled" @change="changeInput(field.key, ($event.target as HTMLSelectElement).value)">
              <option value="">{{ field.required ? '请选择父表单字段' : '不传入此字段' }}</option>
              <option v-if="input(field.key) && !sourceFields.some(source => source.key === input(field.key))" :value="input(field.key)" disabled>原来源已移除：{{ input(field.key) }}</option>
              <option v-for="source in sourceFields" :key="source.key" :value="source.key" :disabled="!!subprocessInputIssue(source, field, nodeId)">{{ source.label }} · {{ typeLabel(source) }}{{ subprocessInputIssue(source, field, nodeId) ? ' · ' + subprocessInputIssue(source, field, nodeId) : '' }}</option>
            </select>
            <small v-if="input(field.key) && issue(field, input(field.key))" role="alert">{{ issue(field, input(field.key)) }}；原映射仍保留。</small>
            <small v-else-if="field.required && !input(field.key)" role="status">此必填子字段尚未配置输入。</small>
          </label>
          <p v-if="!targetFields.length">此子版本没有声明表单输入。</p>
          <div v-for="key in removedTargets" :key="key" class="subprocess-stale"><span>子字段 {{ key }} 已不存在，原来源 {{ input(key) }} 仍保留。</span><button type="button" :disabled="editingDisabled" @click="changeInput(key, '')">移除此映射</button></div>
        </div>
        <p>输入在子流程激活时固定，不回写父表单。明细按同名列映射，附件建立子申请自己的读取权限。</p>
      </template>
      <div class="subprocess-actions"><button type="button" class="quiet" :disabled="current.loading || choice.loading" @click="loadCurrent">重新读取原版本</button><button type="button" class="quiet" :disabled="disabled" @click="clear">清除子流程与映射</button></div>
    </div>
    <p>子流程沿用根申请发起时固定的任职。审批路径需要动态主管或负责人时，根申请须选择发起任职。</p>
  </section>
</template>

<style scoped>
.subprocess-definition{min-width:0;font-size:12px;line-height:1.8}.subprocess-definition p{color:var(--muted);overflow-wrap:anywhere}.subprocess-current{margin-top:12px;padding-top:12px;border-top:1px solid var(--line)}.subprocess-mappings{display:grid;gap:14px;margin:14px 0}.subprocess-definition .subprocess-mappings label{display:grid;gap:5px;min-width:0}.subprocess-mappings span,.subprocess-mappings small,.subprocess-current strong{overflow-wrap:anywhere}.subprocess-mappings small{color:var(--muted);font-size:11px}.subprocess-mappings select{width:100%;min-width:0;padding:9px;border:1px solid var(--line);border-radius:6px;background:var(--paper);color:var(--ink);font:inherit}.subprocess-actions,.subprocess-stale{display:flex;flex-wrap:wrap;align-items:center;gap:10px}.subprocess-stale{padding:10px;background:var(--soft);border-radius:6px}.subprocess-definition [role=alert]{color:var(--red)}.subprocess-definition select:focus-visible,.subprocess-definition button:focus-visible{outline:2px solid var(--deep);outline-offset:2px}
</style>
