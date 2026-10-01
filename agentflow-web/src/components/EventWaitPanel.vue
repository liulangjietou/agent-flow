<script setup lang="ts">
import { onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { EventRead, type EventWaitView } from '../events'
const props = defineProps<{ applicationId: string; roundNo: number; version: number; scopeKey: string }>()
const emit = defineEmits<{ changed: [] }>()
const query = reactive(new EventRead<EventWaitView>())
async function load(refresh = false) {
  const value = await query.load(props.scopeKey, signal => api.eventWaits(props.applicationId, props.roundNo, signal))
  if (refresh && value && value.applicationVersion !== props.version) emit('changed')
}
watch(() => [props.scopeKey, props.applicationId, props.roundNo, props.version], () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>
<template>
  <section class="event-waits" aria-label="本轮事件等待" :aria-busy="query.loading">
    <div class="event-wait-heading"><div><h4>事件等待</h4><p>收到与本次等待匹配的事件后继续流程，后续审批仍由审批人办理。</p></div><button type="button" class="secondary" :disabled="query.loading" @click="load(true)">刷新事件等待</button></div>
    <p v-if="query.loading" role="status">正在读取本轮事件等待…</p>
    <p v-else-if="query.error" role="alert" class="event-wait-error">{{ query.error }}</p>
    <template v-else-if="query.value"><p v-if="!query.value.items.length">本轮当前没有正在等待的事件。已经发生的推进可在审批轨迹中查看。</p>
      <ol v-else><li v-for="item in query.value.items" :key="item.waitId"><div class="event-wait-heading"><strong>{{ item.nodeName }}</strong><span>{{ item.suspended ? '本轮已暂停' : !item.contractEnabled ? '事件版本已停用' : '等待事件' }}</span></div>
        <p v-if="item.suspended">恢复本轮审批后，才会继续处理收到的事件。</p><p v-if="!item.contractEnabled">需要恢复引用的原事件版本，当前等待不会自动改用其他版本。</p>
        <dl><dt>引用事件</dt><dd>{{ item.contractKey }} · v{{ item.contractVersion }}</dd><dt>开始等待</dt><dd>{{ time(item.createdAt) }}</dd><dt>本次等待编号</dt><dd><code>{{ item.waitId }}</code></dd></dl>
      </li></ol>
    </template>
  </section>
</template>
<style scoped>
.event-waits{margin:16px 0;padding:18px;border:1px solid var(--line);border-left:3px solid var(--deep);border-radius:9px;background:var(--paper);font-size:12px;line-height:1.8;min-width:0}.event-wait-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.event-wait-heading h4{margin:0;font-size:14px}.event-waits p{margin:8px 0;color:var(--muted)}.event-waits ol{list-style:none;padding:0;margin:0}.event-waits li{padding:14px 0;border-top:1px solid var(--line)}.event-waits dl{display:grid;grid-template-columns:92px minmax(0,1fr);gap:7px 12px}.event-waits dt{color:var(--muted)}.event-waits dd{margin:0}.event-waits p,.event-waits dd,.event-waits strong{overflow-wrap:anywhere;min-width:0}.event-waits .event-wait-error{color:var(--red)}.event-waits code{user-select:all;font-size:11px}.event-waits button{white-space:normal}@media(max-width:650px){.event-wait-heading{flex-wrap:wrap}.event-waits dl{grid-template-columns:80px minmax(0,1fr)}}
</style>
