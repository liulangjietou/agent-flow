<script setup lang="ts">
import { onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { ConfigurationRead } from '../expenseConfigurationRead'
import { readProjectApprovalView, type ProjectApprovalView } from '../expenseProjectApproval'
import { initiatorContextLabel } from '../initiatorContext'
import ExpenseProjectOwners from './ExpenseProjectOwners.vue'
const props = defineProps<{ reportId: string; applicationId: string; roundNo: number; scopeKey: string; version?: number; locked?: boolean }>()
const query = reactive(new ConfigurationRead<ProjectApprovalView>(cause => {
  const failure = cause as { status?: number; code?: string; message?: string }
  return failure.status === 403 || failure.status === 404 ? '当前无法读取这份原轮次项目依据。'
    : failure.code === 'RESPONSE_UNREADABLE' ? failure.message! : '项目依据读取失败，请刷新。'
}, '项目依据读取超时，请刷新。'))
function load() {
  const { reportId, applicationId, roundNo, scopeKey } = props
  if (!scopeKey || !reportId || !applicationId || !Number.isSafeInteger(roundNo) || roundNo < 1) { query.clear(); return }
  return query.load(async signal => readProjectApprovalView(await api.expenseProjectApproval(reportId, roundNo, signal), reportId, applicationId, roundNo))
}
watch(() => [props.scopeKey, props.reportId, props.applicationId, props.roundNo, props.version], () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="project-evidence" aria-label="项目负责人依据" :aria-busy="query.loading">
    <div class="project-heading"><h4>项目负责人依据</h4><button type="button" class="quiet" :disabled="locked || query.loading" @click="load">刷新依据</button></div>
    <p v-if="query.loading" role="status">正在读取原轮次项目依据…</p>
    <p v-else-if="query.error" role="alert">{{ query.error }}</p>
    <template v-else-if="query.value">
      <p v-if="query.value.status === 'NOT_RECORDED'">这份历史轮次未记录项目负责人依据。</p>
      <template v-else-if="query.value.details">
        <p>第 {{ query.value.roundNo }} 轮 · 流程发布版本 {{ query.value.details.definitionVersion }}</p>
        <p>提交任职：{{ initiatorContextLabel(query.value.details.initiator) }}。项目责任按本轮提交固定。</p>
        <ExpenseProjectOwners :source="query.value.details.source" :responsibility="query.value.details.responsibility" />
      </template>
    </template>
  </section>
</template>

<style scoped>
.project-evidence{border-top:1px solid var(--line);padding-top:16px;margin-top:20px}.project-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.project-heading h4{font-size:14px;margin:0}.project-evidence p{font-size:12px;color:var(--muted);line-height:1.8;overflow-wrap:anywhere}.project-evidence [role=alert]{color:var(--red)}
</style>
