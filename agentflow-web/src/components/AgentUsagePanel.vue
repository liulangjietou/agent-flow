<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { usageKinds, usageOutcomes, type AgentUsage } from '../expenseHandling'
const props = defineProps<{ scopeKey: string; subjectId?: string; refreshVersion?: number }>()
const rows = ref<AgentUsage[]>([]), error = ref(''), loading = ref(false), opened = ref(false)
let epoch = 0, controller: AbortController | null = null
function stop() { epoch++; controller?.abort(); controller = null }
async function load() {
  stop(); rows.value = []; error.value = ''
  if (!props.scopeKey || !opened.value) return
  const current = epoch, request = new AbortController(); controller = request; loading.value = true
  const timeout = setTimeout(() => request.abort(), 12_000)
  try { const value = await api.agentUsage(props.subjectId, request.signal); if (current === epoch) rows.value = value }
  catch { if (current === epoch) error.value = '用量记录暂不可读，请重新查询。' }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function toggle(event: Event) { opened.value = (event.target as HTMLDetailsElement).open; if (opened.value) void load(); else stop() }
watch(() => [props.scopeKey, props.subjectId, props.refreshVersion], () => { loading.value = false; void load() })
onUnmounted(stop)
</script>
<template>
  <details class="usage" @toggle="toggle"><summary>本人助手执行与用量</summary><p>仅显示本人发起的最近 100 次执行。用量以服务商回执为准；缺少实际价格版本时不估算费用。</p><button type="button" class="quiet" :disabled="loading" @click="load">刷新用量</button><p v-if="error" role="alert">{{ error }}</p><p v-if="loading" role="status">正在读取执行记录…</p><p v-else-if="!rows.length">当前没有可读取的执行记录。</p><article v-for="row in rows" :key="row.kind + row.runId"><strong>{{ usageKinds[row.kind] }} · {{ usageOutcomes[row.outcome] }}</strong><p>{{ row.modelVersion ?? '模型版本未返回' }} · 排队 {{ row.queueMillis }} ms · 执行 {{ row.executionMillis === null ? '未知' : `${row.executionMillis} ms` }}</p><p v-if="row.usageStatus === 'REPORTED'">输入 {{ row.inputTokens }} / 输出 {{ row.outputTokens }} / 总计 {{ row.totalTokens }} tokens</p><p v-else>用量未知{{ row.usageStatus === 'INVALID' ? '：供应商用量未通过校验' : '：未取得可用回执' }}</p><p v-if="row.outcome === 'IN_PROGRESS'">尚未取得最终观测，可能仍在执行或记录中断。请查询原业务运行，不要据此重复发送。</p><small>原运行 {{ row.runId }} · {{ new Date(row.startedAt).toLocaleString('zh-CN') }}</small></article></details>
</template>
<style scoped>.usage{margin-top:18px;padding-top:14px;border-top:1px solid var(--line);font-size:12px;color:var(--muted);line-height:1.8;overflow-wrap:anywhere}.usage summary{cursor:pointer;color:var(--deep)}.usage article{padding:12px 0;border-bottom:1px solid var(--line)}.usage strong{color:var(--ink)}.usage p{margin:6px 0}</style>
