<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { usageKinds, usageOutcomes, type AgentUsage } from '../expenseHandling'
const props = defineProps<{ scopeKey: string; subjectId?: string; refreshVersion?: number }>()
const rows = ref<AgentUsage[]>([]), error = ref(''), loading = ref(false), opened = ref(false), loaded = ref(false)
let epoch = 0, controller: AbortController | null = null
function stop() { epoch++; controller?.abort(); controller = null; loading.value = false }
async function load() {
  stop(); rows.value = []; error.value = ''; loaded.value = false
  if (!props.scopeKey || !opened.value) return
  const current = epoch, request = new AbortController(); controller = request; loading.value = true
  const timeout = setTimeout(() => request.abort(), 12_000)
  try { const value = await api.agentUsage(props.subjectId, request.signal); if (current === epoch) { rows.value = value; loaded.value = true } }
  catch { if (current === epoch) error.value = '用量记录暂不可读，请重新查询。' }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function toggle(event: Event) { opened.value = (event.target as HTMLDetailsElement).open; if (opened.value) void load(); else stop() }
watch(() => [props.scopeKey, props.subjectId, props.refreshVersion], () => { loading.value = false; void load() })
onUnmounted(stop)
</script>
<template>
  <details class="usage" @toggle="toggle">
    <summary>本人助手执行与用量<span class="summary-note">最近 100 次</span></summary>
    <div class="usage-toolbar">
      <p>{{ subjectId ? '仅显示当前费用下，本人发起的最近 100 次执行。' : '仅显示本人发起的最近 100 次执行。' }}用量以服务商回执为准；缺少实际价格版本时不估算费用。</p>
      <button type="button" class="quiet" :disabled="loading" @click="load">{{ loading ? '正在读取…' : error ? '重试读取用量' : '刷新用量' }}</button>
    </div>
    <p v-if="error" class="usage-state error" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="usage-state" role="status">正在读取执行记录…</p>
    <p v-else-if="loaded && !rows.length" class="usage-state">当前没有可读取的执行记录。</p>
    <div v-else-if="loaded" class="usage-records">
      <article v-for="row in rows" :key="row.kind + row.runId">
        <div class="record-heading"><strong>{{ usageKinds[row.kind] }}</strong><span class="usage-outcome" :class="{ running: row.outcome === 'IN_PROGRESS' }">{{ usageOutcomes[row.outcome] }}</span><time :datetime="row.startedAt">{{ new Date(row.startedAt).toLocaleString('zh-CN') }}</time></div>
        <dl class="usage-facts"><div><dt>模型版本</dt><dd>{{ row.modelVersion ?? '未返回' }}</dd></div><div><dt>排队耗时</dt><dd>{{ row.queueMillis }} ms</dd></div><div><dt>执行耗时</dt><dd>{{ row.executionMillis === null ? '未知' : `${row.executionMillis} ms` }}</dd></div></dl>
        <p v-if="row.usageStatus === 'REPORTED'" class="token-receipt">输入 {{ row.inputTokens }} / 输出 {{ row.outputTokens }} / 总计 {{ row.totalTokens }} tokens</p>
        <p v-else class="token-receipt">用量未知{{ row.usageStatus === 'INVALID' ? '：供应商用量未通过校验' : '：未取得可用回执' }}</p>
        <p v-if="row.outcome === 'IN_PROGRESS'" class="running-note">尚未取得最终观测，可能仍在执行或记录中断。请查询原业务运行，不要据此重复发送。</p>
        <small>原运行 {{ row.runId }}</small>
      </article>
    </div>
  </details>
</template>
<style scoped>
.usage { margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--line); color: var(--muted); font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.usage summary { cursor: pointer; color: var(--deep); font-size: 14px; font-weight: 600; }
.summary-note { margin-left: 10px; font-size: 12px; font-weight: 400; color: var(--muted); }
.usage-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 12px 0; }
.usage-toolbar p { max-width: 760px; margin: 0; }
.usage-toolbar button { flex: 0 0 auto; }
.usage-state { padding: 18px; background: #f7f9fa; border: 1px solid var(--line); border-radius: 8px; }
.error { color: var(--red); }
.usage-records article { padding: 18px 0; border-bottom: 1px solid var(--line); }
.record-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; }
.record-heading strong { font-size: 14px; color: var(--ink); }
.record-heading time { margin-left: auto; font-size: 12px; }
.usage-outcome { padding: 2px 8px; border-radius: 5px; background: #edf2f5; color: #516471; font-size: 12px; }
.usage-outcome.running { background: #fff2d9; color: #805311; }
.usage-facts { display: flex; flex-wrap: wrap; gap: 12px 36px; margin: 13px 0; }
.usage-facts dt { font-size: 12px; }
.usage-facts dd { margin: 2px 0 0; color: var(--ink); }
.token-receipt { display: inline-block; padding: 4px 10px; border: 1px solid var(--line); border-radius: 5px; margin: 0 0 8px; font-size: 12px; }
.running-note { margin: 0 0 8px; color: #805311; }
.usage small { display: block; font-size: 12px; }
</style>
