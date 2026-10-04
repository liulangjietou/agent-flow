<script setup lang="ts">
import { onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { ConfigurationRead } from '../expenseConfigurationRead'
import { readPriorControlView, type PriorControlView } from '../expensePriorControl'
import ExpensePriorControlFacts from './ExpensePriorControlFacts.vue'
const props = defineProps<{ reportId: string; applicationId: string; roundNo: number; scopeKey: string; version?: number; locked?: boolean }>()
const query = reactive(new ConfigurationRead<PriorControlView>(cause => {
  const failure = cause as { status?: number; code?: string; message?: string }
  return failure.status === 403 || failure.status === 404 ? '当前无法读取这份原轮次额度依据。'
    : failure.code === 'RESPONSE_UNREADABLE' ? failure.message! : '额度依据读取失败，请刷新。'
}, '额度依据读取超时，请刷新。'))
function load() {
  const { reportId, applicationId, roundNo, scopeKey } = props
  if (!scopeKey || !reportId || !applicationId || !Number.isSafeInteger(roundNo) || roundNo < 1) { query.clear(); return }
  return query.load(async signal => readPriorControlView(await api.expensePriorControl(reportId, roundNo, signal), reportId, applicationId, roundNo))
}
watch(() => [props.scopeKey, props.reportId, props.applicationId, props.roundNo, props.version], () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="prior-evidence" aria-label="事前额度依据" :aria-busy="query.loading">
    <div class="prior-heading"><h4>事前额度依据</h4><button type="button" class="quiet" :disabled="locked || query.loading" @click="load">刷新依据</button></div>
    <p v-if="query.loading" role="status">正在读取原轮次额度依据…</p>
    <p v-else-if="query.error" role="alert">{{ query.error }}</p>
    <template v-else-if="query.value">
      <p v-if="query.value.status === 'NOT_RECORDED'">这份历史轮次未保存额度控制依据，不能据此判断是否超过容差。</p>
      <template v-else-if="query.value.details">
        <p>第 {{ query.value.roundNo }} 轮 · {{ query.value.requiresApproval ? '需要独立额度例外审批' : '无需追加额度例外审批' }}</p>
        <p>依据固定于本轮提交；后续核减、其他单据使用或类别改版不会重写本轮判断。</p>
        <ExpensePriorControlFacts :assessments="query.value.details.assessments" />
      </template>
    </template>
  </section>
</template>

<style scoped>
.prior-evidence{border-top:1px solid var(--line);padding-top:16px;margin-top:20px}.prior-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.prior-heading h4{font-size:14px;margin:0}.prior-evidence p{font-size:12px;color:var(--muted);line-height:1.8;overflow-wrap:anywhere}.prior-evidence [role=alert]{color:var(--red)}
</style>
