<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ServiceTaskRead } from '../serviceTasks'
import { serviceTaskState, serviceTaskExplanation, type ServiceTaskRuntimeEntry, type ServiceTaskRuntimeView } from '../serviceTaskRuntime'

const props = defineProps<{ applicationId: string; version: number; scopeKey: string; rounds: { roundNo: number }[]; initialRoundNo?: number | null }>()
const emit = defineEmits<{ changed: [] }>()
const reader = reactive(new ServiceTaskRead<ServiceTaskRuntimeView>('服务运行记录暂时不可访问，请重新加载或重新登录后查看。'))
const selectedRound = ref(0), items = ref<ServiceTaskRuntimeEntry[]>([]), nextAfterId = ref<string | null>(null)
const roundStatus = ref(''), viewVersion = ref<number | null>(null)
const availableRounds = computed(() => [...props.rounds].sort((left, right) => right.roundNo - left.roundNo))
const initialRound = () => props.initialRoundNo && props.rounds.some(round => round.roundNo === props.initialRoundNo)
  ? props.initialRoundNo : availableRounds.value[0]?.roundNo ?? 0
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

async function load(more = false, refresh = false) {
  if (more && (!nextAfterId.value || reader.loading)) return
  const previous = more ? items.value : [], after = more ? nextAfterId.value! : undefined
  const previousVersion = viewVersion.value, previousRoundStatus = roundStatus.value
  items.value = []; nextAfterId.value = null; roundStatus.value = ''; viewVersion.value = null
  if (!selectedRound.value || !props.scopeKey) { reader.clear(); return }
  const page = await reader.load(props.scopeKey, signal => api.serviceTaskRuntime(props.applicationId, selectedRound.value, after, signal))
  if (!page) return
  // 翻页期间轮次结论改变时重读首页，避免把旧结果拼接到新状态。
  if (more && (page.applicationVersion !== previousVersion || page.roundStatus !== previousRoundStatus)) { await load(); return }
  if (page.items.some(item => previous.some(existing => existing.id === item.id))) {
    reader.clear(); reader.error = '服务运行记录已变化，请刷新后重新查看。'; return
  }
  items.value = [...previous, ...page.items]; nextAfterId.value = page.nextAfterId ?? null
  roundStatus.value = page.roundStatus; viewVersion.value = page.applicationVersion
  if (refresh && page.applicationVersion !== props.version) emit('changed')
}

watch(() => [props.scopeKey, props.applicationId], () => { selectedRound.value = initialRound() }, { immediate: true, flush: 'sync' })
watch(() => props.rounds, () => { if (!props.rounds.some(round => round.roundNo === selectedRound.value)) selectedRound.value = initialRound() }, { flush: 'sync' })
watch(() => [props.scopeKey, props.applicationId, props.version, selectedRound.value], () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => reader.clear())
</script>

<template>
  <section class="service-runtime" aria-label="服务运行记录" :aria-busy="reader.loading">
    <div class="service-runtime-heading"><div><h3>服务运行记录</h3><p>按审批轮次查看后台服务的执行结果和流程推进情况。</p></div><button type="button" class="secondary" :disabled="reader.loading || !selectedRound || !scopeKey" @click="load(false, true)">刷新运行记录</button></div>
    <label class="service-round">审批轮次<select v-model.number="selectedRound" :disabled="!availableRounds.length"><option v-for="round in availableRounds" :key="round.roundNo" :value="round.roundNo">第 {{ round.roundNo }} 轮</option></select></label>
    <p v-if="reader.loading" role="status">正在读取服务运行记录…</p>
    <p v-else-if="reader.error" class="service-runtime-error" role="alert">{{ reader.error }}</p>
    <p v-else-if="!scopeKey">请登录后查看服务运行记录。</p>
    <p v-else-if="!selectedRound">尚未提交，没有服务运行记录。</p>
    <p v-else-if="!items.length">本轮暂无已进入的服务节点。后续进入服务节点时会在此显示。</p>
    <ol v-else><li v-for="item in items" :key="item.id">
      <div class="service-runtime-heading"><strong>{{ item.nodeName }}</strong><span class="service-state" :class="{ attention: item.status === 'UNKNOWN' || item.status === 'REJECTED' }">{{ serviceTaskState(item, roundStatus) }}</span></div>
      <p>{{ serviceTaskExplanation(item, roundStatus) }}</p>
      <dl><dt>执行服务</dt><dd>{{ item.operationName }} · v{{ item.operationVersion }}</dd><dt>进入节点</dt><dd><time :datetime="item.createdAt">{{ time(item.createdAt) }}</time></dd><dt>状态更新</dt><dd><time :datetime="item.updatedAt">{{ time(item.updatedAt) }}</time></dd><template v-if="item.completedAt"><dt>服务完成</dt><dd><time :datetime="item.completedAt">{{ time(item.completedAt) }}</time></dd></template></dl>
      <details><summary>查看操作编号</summary><p><code>{{ item.id }}</code></p><p>联系流程管理员时可提供此编号，查询恢复会沿用同一编号。</p></details>
    </li></ol>
    <button v-if="nextAfterId" type="button" class="secondary service-more" :disabled="reader.loading" @click="load(true)">加载更多记录</button>
  </section>
</template>

<style scoped>
.service-runtime{margin-top:16px;padding:18px;border:1px solid var(--line);border-radius:9px;background:var(--paper);font-size:13px;line-height:1.8;min-width:0}.service-runtime-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.service-runtime h3{margin:0;font-size:16px}.service-runtime p{margin:8px 0;color:var(--muted)}.service-round{display:flex;align-items:center;gap:12px;margin:16px 0}.service-round select{min-height:36px;max-width:100%;padding:5px 10px;border:1px solid var(--line);border-radius:5px;background:var(--paper);color:inherit}.service-runtime ol{list-style:none;margin:0;padding:0}.service-runtime li{border-top:1px solid var(--line);padding:18px 0}.service-runtime dl{display:grid;grid-template-columns:90px minmax(0,1fr);gap:6px 12px}.service-runtime dt{color:var(--muted)}.service-runtime dd{margin:0}.service-state{color:var(--deep)}.service-state.attention,.service-runtime .service-runtime-error{color:var(--red)}.service-runtime p,.service-runtime dd,.service-runtime strong,.service-runtime code,.service-state{overflow-wrap:anywhere;min-width:0}.service-runtime code{user-select:all;font-size:12px}.service-runtime summary{cursor:pointer}.service-runtime button{white-space:normal}.service-more{margin-top:12px}@media(max-width:650px){.service-runtime-heading{flex-wrap:wrap}.service-runtime dl{grid-template-columns:76px minmax(0,1fr)}}
</style>
