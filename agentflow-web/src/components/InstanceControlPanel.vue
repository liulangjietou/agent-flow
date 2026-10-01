<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { InstanceControlQuery, instanceControlInput, type InstanceControlAction, type InstanceControlView } from '../instanceControl'
const props = defineProps<{ applicationId: string; roundNo: number; version: number; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ changed: []; state: [value: InstanceControlView | null]; busy: [value: boolean] }>()
const query = reactive(new InstanceControlQuery(api.instanceControl))
const selected = ref<InstanceControlAction | null>(null), reason = ref(''), error = ref(''), busy = ref(false)
const reasonInput = ref<HTMLTextAreaElement | null>(null), trigger = ref<HTMLButtonElement | null>(null)
const terminationTrigger = ref<HTMLButtonElement | null>(null)
const fresh = computed(() => query.value?.applicationVersion === props.version)
const blocked = computed(() => props.locked || busy.value || query.loading || !fresh.value)
const labels = { RUNNING: '正常办理', PAUSED: '审批已暂停', ENDED: '本轮已结束', UNAVAILABLE: '运行状态不可用' }
const actionLabel = computed(() => selected.value === 'pause' ? '暂停审批' : selected.value === 'resume' ? '恢复审批' : '终止审批')
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
let generation = 0

function cancel() {
  const action = selected.value
  selected.value = null; reason.value = ''; error.value = ''
  // 确认表单会卸载原按钮，必须在按钮重新挂载后读取引用。
  if (action) void nextTick(() => (action === 'terminate' ? terminationTrigger.value : trigger.value)?.focus())
}
async function prepare(action: InstanceControlAction) {
  if (blocked.value || !query.value || !(action === 'pause' ? query.value.canPause : action === 'resume' ? query.value.canResume : query.value.canTerminate)) return
  cancel(); selected.value = action
  await nextTick(); reasonInput.value?.focus()
}
function refresh() {
  if (busy.value) return
  generation++; cancel(); emit('state', null)
  void query.load(props.scopeKey, props.applicationId, props.roundNo)
}
async function execute() {
  if (blocked.value || !query.value || !selected.value) return
  const current = generation
  try {
    const input = instanceControlInput(query.value, props.version, selected.value, reason.value)
    busy.value = true; error.value = ''
    await api.controlInstance(props.applicationId, props.roundNo, selected.value, input)
    if (current !== generation) return
    cancel(); emit('changed')
  } catch (cause) {
    if (current === generation) error.value = (cause as ApiError)?.code === 'CONCURRENCY_CONFLICT'
      ? '运行状态已变化，请刷新申请详情后核对。' : (cause as ApiError)?.message || '操作未完成，请核对原请求结果。'
  } finally { if (current === generation) busy.value = false }
}
watch(() => query.value, value => emit('state', value), { flush: 'sync' })
watch(busy, value => emit('busy', value), { flush: 'sync' })
watch([() => props.scopeKey, () => props.applicationId, () => props.roundNo, () => props.version], () => {
  busy.value = false; refresh()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { generation++; query.clear(); emit('busy', false) })
</script>

<template>
  <section class="instance-control" :class="{ paused: query.value?.state === 'PAUSED' }" aria-label="本轮运行状态" :aria-busy="query.loading || busy">
    <div class="instance-heading"><div><h3>本轮运行状态</h3><strong v-if="query.value" class="instance-state">{{ labels[query.value.state] }}</strong></div><button type="button" class="secondary" :disabled="busy || query.loading || locked" @click="refresh">刷新运行状态</button></div>
    <p v-if="query.loading" role="status">正在读取本轮运行状态…</p>
    <p v-else-if="query.error" class="instance-error" role="alert">{{ query.error }}</p>
    <template v-else-if="query.value">
      <template v-if="query.value.state === 'PAUSED'"><p v-if="query.value.pausedAt">暂停于 {{ time(query.value.pausedAt) }}</p><p>原待办和审批意见已保留。请管理员恢复后继续办理或撤回。</p><p v-if="!query.value.pausedAt">当前缺少可恢复的暂停依据，请管理员核对原运行记录。</p></template>
      <p v-else-if="query.value.state === 'RUNNING'">当前待办按各自期限继续办理。</p>
      <p v-else-if="query.value.state === 'ENDED'">本轮已经结束，可查看审批轨迹与操作审计。</p>
      <p v-else>暂时不能确认本轮实例，请管理员核对运行记录。</p>
      <div v-if="!fresh" class="instance-stale" role="status"><p>申请已更新，请先读取最新详情。</p><button type="button" class="secondary" :disabled="busy || locked" @click="emit('changed')">刷新申请详情</button></div>
      <form v-else-if="selected" @submit.prevent="execute">
        <h4>确认{{ actionLabel }}</h4>
        <p v-if="selected === 'pause'">本轮审批与定时推进将暂停，人工任务保留剩余办理时长。原审批意见和财务占用保留。</p>
        <p v-else-if="selected === 'resume'">接续原任务与剩余工作时长，已经超时的任务仍保持超时。原定时等待若已到期，将继续推进。</p>
        <p v-else>本轮及未完成的子审批将取消，终止后不能恢复或重新提交。已完成的审批意见保留。相关财务占用按实际状态释放，外部结果未知时继续对账。</p>
        <label>操作原因<textarea ref="reasonInput" v-model="reason" required maxlength="2000" rows="3" :disabled="busy || locked" /></label>
        <p v-if="error" class="instance-error" role="alert">{{ error }}</p>
        <div class="instance-actions"><button type="button" class="secondary" :disabled="busy" @click="cancel">取消操作</button><button type="submit" class="primary" :disabled="blocked || !reason.trim()">{{ busy ? '正在处理…' : `确认${actionLabel}` }}</button></div>
      </form>
      <div v-else-if="query.value.canPause || query.value.canResume || query.value.canTerminate" class="instance-actions">
        <button v-if="query.value.canPause || query.value.canResume" ref="trigger" type="button" class="secondary" :disabled="blocked" @click="prepare(query.value.canPause ? 'pause' : 'resume')">{{ query.value.canPause ? '暂停本轮审批' : '恢复本轮审批' }}</button>
        <button v-if="query.value.canTerminate" ref="terminationTrigger" type="button" class="secondary" :disabled="blocked" @click="prepare('terminate')">终止本轮审批</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.instance-control{margin:16px 0;padding:16px 18px;border:1px solid var(--line);border-left:4px solid var(--deep);border-radius:10px;background:var(--paper);font-size:12px;line-height:1.7;min-width:0}.instance-control.paused{border-left-color:var(--amber)}.instance-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.instance-heading h3{margin:0;color:var(--muted);font-size:12px;font-weight:500}.instance-state{display:block;font-size:16px;color:var(--deep)}.paused .instance-state{color:var(--ink)}.instance-control p{margin:8px 0;color:var(--muted);overflow-wrap:anywhere}.instance-control form{margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}.instance-control h4{margin:0;font-size:14px}.instance-control label{display:block}.instance-control textarea{box-sizing:border-box;display:block;width:100%;min-width:0;margin:7px 0;padding:10px;border:1px solid var(--line);border-radius:6px;font:inherit}.instance-control textarea:focus-visible{outline:3px solid var(--teal);outline-offset:2px}.instance-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:12px}.instance-control .instance-error{color:var(--red)}.instance-control button{white-space:normal;overflow-wrap:anywhere}@media(max-width:650px){.instance-heading{flex-wrap:wrap}.instance-actions button{flex:1 1 150px}}
</style>
