<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { TimerWaitQuery, timerRetryInput, type TimerEntry } from '../timerWaits'
const props = defineProps<{ applicationId: string; roundNo: number; version: number; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const query = reactive(new TimerWaitQuery(api.timerWaits))
const selected = ref(''), reason = ref(''), error = ref(''), busy = ref(false)
let generation = 0
const stateLabels = { WAITING: '等待到期', FAILED: '推进失败', SUSPENDED: '已暂停' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
function failure(entry: TimerEntry) {
  return ['COUNTERSIGN_NO_MEMBERS', 'ORGANIZATION_NO_APPROVERS'].includes(entry.errorCode ?? '') ? '后续节点没有可用审批人，请先修复人员配置。'
    : entry.errorCode === 'DEPENDENCY_UNAVAILABLE' ? '所需服务暂不可用，原等待和到期时间已保留。' : '后续节点未能继续，原等待已保留，请管理员核对原因。'
}
function cancel() { selected.value = ''; reason.value = ''; error.value = '' }
function prepare(entry: TimerEntry) { if (!props.locked && !busy.value && entry.canRetry) { cancel(); selected.value = entry.jobId } }
function refresh() { generation++; cancel(); void query.load(props.scopeKey, props.applicationId, props.roundNo) }
async function retry() {
  if (props.locked || busy.value || query.loading || !query.value || !selected.value) return
  const current = generation
  try {
    const input = timerRetryInput(query.value, selected.value, reason.value)
    busy.value = true; error.value = ''
    await api.retryTimer(props.applicationId, props.roundNo, selected.value, input)
    if (current !== generation) return
    cancel(); emit('changed'); await query.load(props.scopeKey, props.applicationId, props.roundNo)
  } catch (cause) { if (current === generation) error.value = (cause as ApiError)?.message || '重试未完成，请核对当前状态。' }
  finally { if (current === generation) busy.value = false }
}
watch([() => props.scopeKey, () => props.applicationId, () => props.roundNo, () => props.version], () => { busy.value = false; refresh() }, { immediate: true, flush: 'sync' })
onUnmounted(() => { generation++; query.clear() })
</script>

<template>
  <section class="timer-panel" aria-label="本轮定时等待" :aria-busy="query.loading || busy">
    <div class="timer-heading"><div><h4>定时等待</h4><p>按节点实际到达时间计时，到期后继续流程；等待不代替人工审批。</p></div><button type="button" class="secondary" :disabled="busy || locked || query.loading" @click="refresh">刷新等待状态</button></div>
    <p v-if="query.loading" role="status">正在读取本轮等待…</p>
    <p v-else-if="query.error" class="timer-error" role="alert">{{ query.error }}</p>
    <template v-else-if="query.value">
      <p v-if="!query.value.items.length">当前没有未结束的定时等待。已发生的推进可在审批轨迹和操作审计中查看。</p>
      <ul v-else><li v-for="entry in query.value.items" :key="entry.jobId">
        <div class="timer-fact"><strong>{{ entry.nodeName }}</strong><span>{{ stateLabels[entry.state] }}</span></div>
        <p>原定到期：{{ time(entry.dueAt) }}</p>
        <p v-if="entry.state === 'FAILED'">{{ failure(entry) }}</p>
        <p v-if="entry.state === 'SUSPENDED'">流程暂停期间不会推进。</p>
        <form v-if="selected === entry.jobId" @submit.prevent="retry">
          <p>确认后仅重试这次失败的等待，不改变到期时间，不代替后续人员审批。</p>
          <label>重试原因<textarea v-model="reason" required maxlength="2000" rows="3" :disabled="busy || locked" /></label>
          <p v-if="error" class="timer-error" role="alert">{{ error }}</p>
          <div class="timer-actions"><button type="button" class="secondary" :disabled="busy || locked" @click="cancel">取消重试</button><button type="submit" class="primary" :disabled="busy || locked || !reason.trim()">{{ busy ? '正在重试…' : '确认重试原等待' }}</button></div>
        </form>
        <button v-else-if="entry.canRetry" type="button" class="secondary" :disabled="busy || locked" @click="prepare(entry)">重试此等待</button>
      </li></ul>
    </template>
  </section>
</template>

<style scoped>
.timer-panel{margin:16px 0;padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--paper);font-size:12px;line-height:1.7;min-width:0}.timer-panel .timer-heading,.timer-fact{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.timer-panel h4{margin:0;font-size:14px}.timer-panel p{margin:8px 0;color:var(--muted);overflow-wrap:anywhere}.timer-panel ul{list-style:none;margin:0;padding:0}.timer-panel li{padding:14px 0;border-top:1px solid var(--line)}.timer-fact strong{overflow-wrap:anywhere;min-width:0}.timer-fact span{flex-shrink:0;color:var(--deep)}.timer-panel label{display:block}.timer-panel textarea{box-sizing:border-box;display:block;width:100%;min-width:0;margin:7px 0;padding:10px;border:1px solid var(--line);border-radius:6px;font:inherit}.timer-actions{display:flex;flex-wrap:wrap;gap:10px}.timer-panel .timer-error{color:var(--red)}.timer-panel button{white-space:normal;overflow-wrap:anywhere}@media(max-width:650px){.timer-panel .timer-heading{flex-wrap:wrap}.timer-actions button{flex:1 1 150px}}
</style>
