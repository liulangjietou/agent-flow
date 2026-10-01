<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { EventRead, eventKey, type EventBinding, type EventDirectory, type EventOption, type EventVersions } from '../events'
const props = defineProps<{ modelValue: EventBinding; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: EventBinding]; beforeChange: [] }>()
const directory = reactive(new EventRead<EventDirectory>()), versions = reactive(new EventRead<EventVersions>()), current = reactive(new EventRead<EventOption>())
const browsing = ref(''), after = ref<string>(), before = ref<number>()
function loadDirectory(cursor?: string) { after.value = cursor; browsing.value = ''; versions.clear(); void directory.load(props.scopeKey, signal => api.eventContractOptions(cursor, signal)) }
function browse(key: string, cursor?: number) {
  if (props.disabled) return
  browsing.value = key; before.value = cursor
  void versions.load(props.scopeKey, signal => api.eventContractOptionVersions(key, cursor, signal))
}
/** 只接受用户点选的确切发布版本；浏览、刷新和新版本出现都不改写节点。 */
function choose(option: EventOption) {
  if (props.disabled || !option.enabled || versions.loading || !versions.value?.items.some(item => item.key === option.key && item.version === option.version && item.enabled)) return
  emit('beforeChange'); emit('update:modelValue', { key: option.key, version: String(option.version) })
}
function clear() { if (!props.disabled) { emit('beforeChange'); emit('update:modelValue', {}) } }
function loadCurrent() {
  current.clear()
  const { key, version } = props.modelValue
  if (eventKey(key) && version && /^[1-9][0-9]*$/.test(version) && Number.isSafeInteger(Number(version))) void current.load(props.scopeKey, signal => api.eventContractOption(key, Number(version), signal))
}
watch(() => props.scopeKey, () => { directory.clear(); versions.clear(); current.clear(); browsing.value = ''; loadDirectory(); loadCurrent() }, { immediate: true, flush: 'sync' })
watch(() => [props.modelValue.key, props.modelValue.version], loadCurrent, { flush: 'sync' })
onUnmounted(() => { directory.clear(); versions.clear(); current.clear() })
</script>
<template>
  <section class="event-definition" aria-label="事件等待配置">
    <div class="event-reference"><strong>本节点引用</strong><p v-if="modelValue.key || modelValue.version">{{ modelValue.key || '未填写事件标识' }} · v{{ modelValue.version || '未选择版本' }}</p><p v-else>尚未选择事件。请从已发布的事件中明确选择一个版本。</p>
      <p v-if="current.value">{{ current.value.name }} · {{ current.value.enabled ? '已启用' : '已停用，发布和发起前须恢复原版本' }}<br />来源 {{ current.value.sourceKey }} · {{ current.value.eventType }}</p>
      <p v-if="current.error" role="alert">{{ current.error }} 原引用已保留，请重新核对。</p>
      <button v-if="modelValue.key || modelValue.version" type="button" class="quiet" :disabled="disabled" @click="clear">清除事件引用</button>
    </div>
    <p>选择后固定到此版本。新版本发布不会替换本节点，也不会替代后续人工审批。</p>
    <div class="event-picker-heading"><strong>已发布事件</strong><button type="button" class="quiet" :disabled="disabled || directory.loading" @click="loadDirectory()">{{ after ? '返回目录首页' : '刷新目录' }}</button></div>
    <p v-if="directory.loading" role="status">正在读取事件目录…</p><p v-else-if="directory.error" role="alert">{{ directory.error }}</p>
    <template v-else-if="directory.value"><p v-if="!directory.value.items.length">当前没有已发布事件，请管理员先发布事件契约。</p><ul><li v-for="item in directory.value.items" :key="item.key"><button type="button" :disabled="disabled || versions.loading" :aria-pressed="browsing === item.key" @click="browse(item.key)"><strong>{{ item.name }}</strong><span>{{ item.key }} · 最新 v{{ item.version }} · {{ item.enabled ? '已启用' : '已停用' }}</span></button></li></ul><button v-if="directory.value.nextAfterKey" type="button" class="quiet" :disabled="disabled" @click="loadDirectory(directory.value.nextAfterKey!)">下一页事件</button></template>
    <div v-if="browsing" class="event-version-picker"><strong>{{ browsing }} 的发布版本</strong><p v-if="versions.loading" role="status">正在读取发布版本…</p><p v-if="versions.error" role="alert">{{ versions.error }}</p>
      <ul v-if="versions.value"><li v-for="item in versions.value.items" :key="item.version"><button type="button" :disabled="disabled || !item.enabled" @click="choose(item)"><strong>选择 v{{ item.version }} · {{ item.name }}</strong><span>{{ item.sourceKey }} · {{ item.eventType }} · {{ item.enabled ? '已启用' : '已停用' }}</span></button></li></ul>
      <div class="event-picker-heading"><button v-if="before" type="button" class="quiet" :disabled="disabled || versions.loading" @click="browse(browsing)">返回最新版本</button><button v-if="versions.value?.nextBeforeVersion" type="button" class="quiet" :disabled="disabled || versions.loading" @click="browse(browsing, versions.value.nextBeforeVersion!)">更早版本</button></div>
    </div>
  </section>
</template>
<style scoped>
.event-definition{min-width:0;font-size:12px;line-height:1.8}.event-reference{padding:12px;border-left:3px solid var(--deep);background:var(--soft);border-radius:5px}.event-definition p{color:var(--muted);overflow-wrap:anywhere}.event-definition strong,.event-definition span{overflow-wrap:anywhere}.event-picker-heading{display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px;align-items:center;margin:14px 0 8px}.event-definition ul{padding:0;list-style:none;max-height:240px;overflow:auto;margin:8px 0}.event-definition li+li{margin-top:6px}.event-definition li button{display:flex;flex-direction:column;gap:4px;width:100%;min-width:0;white-space:normal;text-align:left;padding:10px;border:1px solid var(--line);border-radius:6px;background:var(--paper);color:var(--ink);font:inherit;cursor:pointer}.event-definition li button span{font-size:11px;color:var(--muted)}.event-definition button[aria-pressed=true]{border-color:var(--deep)}.event-definition button:disabled{opacity:.6;cursor:default}.event-definition button:focus-visible{outline:2px solid var(--deep);outline-offset:2px}.event-version-picker{padding-top:12px;border-top:1px solid var(--line)}.event-definition [role=alert]{color:var(--red)}
</style>
