<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ownValue, type FormSchema } from '../formSchema'
import { ServiceTaskRead, serviceTaskKey, serviceTaskVersion, serviceTaskInputIssue, type ServiceTaskBinding, type ServiceTaskDirectory, type ServiceTaskOption, type ServiceTaskParameter, type ServiceTaskVersions } from '../serviceTasks'
const props = defineProps<{ modelValue: ServiceTaskBinding; nodeId: string; formSchema: FormSchema | null; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: ServiceTaskBinding]; beforeChange: [] }>()
const directory = reactive(new ServiceTaskRead<ServiceTaskDirectory>()), versions = reactive(new ServiceTaskRead<ServiceTaskVersions>()), current = reactive(new ServiceTaskRead<ServiceTaskOption>())
const browsing = ref(''), after = ref<string>(), before = ref<string>()
const fields = computed(() => props.formSchema?.fields ?? [])
const invalidReference = computed(() => (props.modelValue.key !== undefined || props.modelValue.version !== undefined || props.modelValue.digest !== undefined)
  && (!serviceTaskKey(props.modelValue.key) || !serviceTaskVersion(props.modelValue.version) || !/^[a-f0-9]{64}$/.test(props.modelValue.digest ?? '')))
const matched = computed(() => current.value && current.value.key === props.modelValue.key && current.value.version === props.modelValue.version && current.value.contractDigest === props.modelValue.digest ? current.value : null)
const unknownInputs = computed(() => matched.value ? Object.keys(props.modelValue.inputs).filter(name => !matched.value!.parameters.some(parameter => parameter.name === name)) : [])
const typeLabels = { TEXT: '文本', NUMBER: '数字', BOOLEAN: '是／否', DATE: '日期' }
function loadDirectory(cursor?: string) { after.value = cursor; browsing.value = ''; versions.clear(); void directory.load(props.scopeKey, signal => api.serviceTaskOptions(cursor, signal)) }
function browse(key: string, cursor?: string) {
  if (props.disabled) return
  browsing.value = key; before.value = cursor
  void versions.load(props.scopeKey, signal => api.serviceTaskVersions(key, cursor, signal))
}
/** 只响应目录内明确点选的版本，同一引用不清空既有映射。 */
function choose(option: ServiceTaskOption) {
  if (props.disabled || !option.enabled || versions.loading || !versions.value?.items.some(item => item.key === option.key && item.version === option.version && item.contractDigest === option.contractDigest && item.enabled)) return
  if (props.modelValue.key === option.key && props.modelValue.version === option.version && props.modelValue.digest === option.contractDigest) return
  emit('beforeChange'); emit('update:modelValue', { key: option.key, version: option.version, digest: option.contractDigest, inputs: {} })
}
function clear() { if (!props.disabled) { emit('beforeChange'); emit('update:modelValue', { inputs: {} }) } }
function sourceIssue(parameter: ServiceTaskParameter) {
  const key = ownValue(props.modelValue.inputs, parameter.name)
  return !key && !parameter.required ? '' : serviceTaskInputIssue(fields.value.find(field => field.key === key), parameter, props.nodeId)
}
function mapInput(name: string, source: string) {
  if (props.disabled || !matched.value) return
  const parameter = matched.value.parameters.find(value => value.name === name)
  if (source && (!parameter || serviceTaskInputIssue(fields.value.find(field => field.key === source), parameter, props.nodeId))) return
  const inputs = { ...props.modelValue.inputs }
  if (source) inputs[name] = source; else delete inputs[name]
  emit('beforeChange'); emit('update:modelValue', { ...props.modelValue, inputs })
}
function loadCurrent() {
  current.clear()
  const { key, version } = props.modelValue
  if (serviceTaskKey(key) && serviceTaskVersion(version)) void current.load(props.scopeKey, signal => api.serviceTaskOption(key, version, signal))
}
watch(() => props.scopeKey, () => { directory.clear(); versions.clear(); current.clear(); browsing.value = ''; loadDirectory(); loadCurrent() }, { immediate: true, flush: 'sync' })
watch(() => [props.modelValue.key, props.modelValue.version, props.modelValue.digest], loadCurrent, { flush: 'sync' })
onUnmounted(() => { directory.clear(); versions.clear(); current.clear() })
</script>
<template>
  <section class="service-definition" aria-label="服务任务配置">
    <div class="service-reference"><strong>本节点操作</strong>
      <p v-if="modelValue.key || modelValue.version">{{ modelValue.key || '未选择操作' }} · v{{ modelValue.version || '未选择版本' }}</p><p v-else>请从已安装的操作中明确选择一个版本。</p>
      <p v-if="matched">{{ matched.name }} · {{ matched.enabled ? '可用' : '暂不可用，发布和发起前须恢复原操作' }}</p>
      <p v-if="invalidReference" role="alert">原引用不完整或格式不正确，已保留原文，请明确重新选择操作版本。</p>
      <p v-if="current.loading" role="status">正在核对原操作…</p><p v-else-if="current.error" role="alert">{{ current.error }} 原配置已保留。</p>
      <p v-else-if="current.value && !matched" role="alert">原契约与目录不一致，请核对后明确重新选择版本。</p>
      <button v-if="modelValue.key || modelValue.version || Object.keys(modelValue.inputs).length" type="button" class="quiet" :disabled="disabled" @click="clear">清除操作及映射</button>
      <button v-if="modelValue.key" type="button" class="quiet" :disabled="current.loading" @click="loadCurrent">重新核对原操作</button>
    </div>
    <p>仅发送下方明确映射的字段。操作版本固定，模拟不会发起外部调用；服务结果也不能代替人工审批。</p>
    <div v-if="matched" class="service-inputs"><strong>发送字段</strong><p v-if="!matched.parameters.length">此操作不接收表单字段。</p>
      <label v-for="parameter in matched.parameters" :key="parameter.name">{{ parameter.name }} · {{ typeLabels[parameter.type] }}{{ parameter.required ? ' · 必填' : ' · 可选' }}{{ parameter.sensitive ? ' · 接收敏感字段' : '' }}
        <select :value="ownValue(modelValue.inputs, parameter.name) ?? ''" :aria-label="`参数 ${parameter.name} 来源字段`" :disabled="disabled" @change="mapInput(parameter.name, ($event.target as HTMLSelectElement).value)">
          <option value="">{{ parameter.required ? '请选择来源字段' : '不发送此参数' }}</option>
          <option v-if="modelValue.inputs[parameter.name] && !fields.some(field => field.key === modelValue.inputs[parameter.name])" :value="modelValue.inputs[parameter.name]" disabled>{{ modelValue.inputs[parameter.name] }}（字段已不存在）</option>
          <option v-for="field in fields" :key="field.key" :value="field.key" :disabled="!!serviceTaskInputIssue(field, parameter, nodeId)">{{ field.label }} · {{ field.key }}{{ serviceTaskInputIssue(field, parameter, nodeId) ? '（不可映射）' : '' }}</option>
        </select><span v-if="sourceIssue(parameter)" role="alert">{{ sourceIssue(parameter) }}</span>
      </label>
      <p v-for="name in unknownInputs" :key="name" role="alert">参数 {{ name }} 不在原契约中。<button type="button" class="quiet" :disabled="disabled" @click="mapInput(name, '')">移除此映射</button></p>
    </div>
    <div class="service-heading"><strong>已安装操作</strong><button type="button" class="quiet" :disabled="disabled || directory.loading" @click="loadDirectory()">{{ after ? '返回目录首页' : '刷新目录' }}</button></div>
    <p v-if="directory.loading" role="status">正在读取操作目录…</p><p v-else-if="directory.error" role="alert">{{ directory.error }}</p>
    <template v-else-if="directory.value"><p v-if="!directory.value.items.length">当前没有已安装操作，请管理员配置后刷新。</p>
      <ul><li v-for="item in directory.value.items" :key="item.key"><button type="button" :disabled="disabled || versions.loading" :aria-pressed="browsing === item.key" @click="browse(item.key)"><strong>{{ item.name }}</strong><span>{{ item.key }} · 最新 v{{ item.version }} · {{ item.enabled ? '可用' : '暂不可用' }}</span></button></li></ul>
      <button v-if="directory.value.nextAfterKey" type="button" class="quiet" :disabled="disabled" @click="loadDirectory(directory.value.nextAfterKey!)">下一页操作</button>
    </template>
    <div v-if="browsing" class="service-versions"><strong>{{ browsing }} 的版本</strong><p v-if="versions.loading" role="status">正在读取版本…</p><p v-if="versions.error" role="alert">{{ versions.error }}</p>
      <ul v-if="versions.value"><li v-for="item in versions.value.items" :key="item.version"><button type="button" :disabled="disabled || !item.enabled" @click="choose(item)"><strong>选择 v{{ item.version }} · {{ item.name }}</strong><span>{{ item.parameters.length }} 个参数 · {{ item.enabled ? '可用' : '暂不可用' }}</span></button></li></ul>
      <div class="service-heading"><button v-if="before" type="button" class="quiet" :disabled="disabled || versions.loading" @click="browse(browsing)">返回最新版本</button><button v-if="versions.value?.nextBeforeVersion" type="button" class="quiet" :disabled="disabled || versions.loading" @click="browse(browsing, versions.value.nextBeforeVersion!)">更早版本</button></div>
    </div>
  </section>
</template>
<style scoped>
.service-definition{min-width:0;font-size:12px;line-height:1.8}.service-reference{padding:12px;border-left:3px solid var(--deep);background:var(--soft);border-radius:5px}.service-definition p{color:var(--muted);overflow-wrap:anywhere}.service-definition strong,.service-definition span{overflow-wrap:anywhere}.service-heading{display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px;align-items:center;margin:14px 0 8px}.service-definition ul{padding:0;list-style:none;max-height:240px;overflow:auto;margin:8px 0}.service-definition li+li{margin-top:6px}.service-definition li button{display:flex;flex-direction:column;gap:4px;width:100%;min-width:0;white-space:normal;text-align:left;padding:10px;border:1px solid var(--line);border-radius:6px;background:var(--paper);color:var(--ink);font:inherit;cursor:pointer}.service-definition li button span{font-size:11px;color:var(--muted)}.service-definition button[aria-pressed=true]{border-color:var(--deep)}.service-definition button:disabled{opacity:.6;cursor:default}.service-definition button:focus-visible,.service-definition select:focus-visible{outline:2px solid var(--deep);outline-offset:2px}.service-versions,.service-inputs{padding-top:12px;border-top:1px solid var(--line)}.service-inputs label{display:grid;gap:5px;margin:10px 0}.service-inputs select{width:100%;min-width:0;font:inherit;padding:8px;border:1px solid var(--line);border-radius:5px;background:var(--paper);color:var(--ink)}.service-definition [role=alert]{color:var(--red)}
</style>
