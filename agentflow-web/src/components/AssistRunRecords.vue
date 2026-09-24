<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { AssistRunsQuery, type AssistStatus } from '../assistRuns'

const props = defineProps<{ applicationId: string; scopeKey: string; version: number; roundNo: number }>()
const query = reactive(new AssistRunsQuery(api.assistRuns, api.assistRun))
const filterRound = ref(''), appliedRound = ref<number | undefined>(), filterError = ref('')
const labels: Record<AssistStatus, string> = { QUEUED: '等待执行', RUNNING: '正在生成', COMPLETED: '待人工复核', FAILED: '生成失败', ADOPTED: '已采纳摘要', DISMISSED: '未采纳摘要' }
const failureLabels = { MODEL_UNAVAILABLE: '模型服务不可用', MODEL_TIMEOUT: '模型响应超时', INVALID_MODEL_OUTPUT: '模型结果未通过校验', INPUT_UNAVAILABLE: '输入依据不可用' }
const stale = computed(() => query.detail && (!query.detail.inputCurrent || query.detail.applicationVersion !== props.version))
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
const confidence = computed(() => query.detail?.suggestion ? (query.detail.suggestion.confidence * 100).toFixed(1).replace(/\.0$/, '') + '%' : '')
function refresh() { return query.load(props.scopeKey, props.applicationId, appliedRound.value) }
function filter() {
  const round = filterRound.value === '' ? undefined : Number(filterRound.value)
  if (round !== undefined && (!Number.isInteger(round) || round < 1 || round > props.roundNo)) { filterError.value = '请输入已有的申请轮次。'; return }
  filterError.value = ''; appliedRound.value = round; void refresh()
}
watch(() => [props.scopeKey, props.applicationId, props.version], () => {
  filterRound.value = ''; appliedRound.value = undefined; filterError.value = ''; void refresh()
}, { immediate: true })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="assist-records" aria-label="Agent 摘要记录">
    <header class="assist-heading"><div><p class="eyebrow">ASSISTANT RECORDS</p><h3>Agent 摘要</h3></div><button type="button" class="secondary" :disabled="query.loading" @click="refresh">刷新记录</button></header>
    <p class="assist-intro">摘要提供核对线索，审批结论由审批人作出。当前尚未接入模型生成，可查看已有运行记录。</p>
    <form class="assist-filter" @submit.prevent="filter"><label>申请轮次<input v-model="filterRound" type="number" min="1" :max="roundNo" placeholder="全部轮次" /></label><button type="submit" class="secondary" :disabled="query.loading">筛选</button><span>已加载 {{ query.items.length }} 条</span></form>
    <p v-if="filterError" role="alert" class="assist-alert">{{ filterError }}</p>
    <p v-if="query.error" role="alert" class="assist-alert">{{ query.error }}</p>
    <p v-if="query.loading && !query.items.length" role="status" class="assist-empty">正在读取摘要记录…</p>
    <div v-else-if="!query.items.length && !query.error" class="assist-empty"><strong>这份申请暂无摘要记录</strong><p>请先核对申请内容、提交轮次与审批轨迹。模型服务启用后，新生成的摘要会在这里保留。</p></div>
    <ol class="assist-list" aria-label="摘要运行记录">
      <li v-for="item in query.items" :key="item.id"><button type="button" class="assist-run" :aria-pressed="query.selectedId === item.id" @click="query.select(item.id)"><span><strong>第 {{ item.roundNo }} 轮</strong><time :datetime="item.createdAt">{{ time(item.createdAt) }}</time></span><span class="assist-run-state" :class="{ failed: item.status === 'FAILED' }">{{ labels[item.status] }}</span><span aria-hidden="true">→</span></button></li>
    </ol>
    <button v-if="query.nextCursor" type="button" class="secondary assist-more" :disabled="query.loading" @click="query.load(scopeKey, applicationId, appliedRound, true)">{{ query.loading ? '正在加载…' : '加载更早记录' }}</button>
    <section v-if="query.selectedId" class="assist-detail" aria-label="摘要详情" aria-live="polite">
      <header class="assist-heading"><h4>摘要详情</h4><button type="button" class="quiet" @click="query.closeDetail">收起详情</button></header>
      <p v-if="query.detailLoading" class="assist-intro" role="status">正在核对访问权限并读取详情…</p>
      <div v-else-if="query.detailError"><p class="assist-alert" role="alert">{{ query.detailError }}</p><button type="button" class="secondary" @click="query.select(query.selectedId)">重试详情</button></div>
      <template v-else-if="query.detail">
        <div class="assist-context"><strong>{{ labels[query.detail.status] }}</strong><span>由 {{ query.detail.requestedBy }} 发起 · 第 {{ query.detail.roundNo }} 轮</span></div>
        <p v-if="stale" class="assist-stale">申请内容或审批状态已变化。这是历史摘要，请结合最新申请重新核对。</p>
        <p v-if="query.detail.failure" class="assist-alert">{{ failureLabels[query.detail.failure] }}。本次运行没有生成可复核的摘要。</p>
        <p v-if="query.detail.status === 'QUEUED' || query.detail.status === 'RUNNING'" class="assist-intro">本次运行尚无结果，可稍后刷新记录查看状态。</p>
        <template v-if="query.detail.suggestion">
          <h4>模型原文</h4><p class="assist-intro">模型自报置信度 {{ confidence }}，仍需人工核对。来源绑定并不代表陈述已经核实。</p>
          <ol class="assist-claims"><li v-for="(claim, index) in query.detail.suggestion.claims" :key="index"><p class="assist-text">{{ claim.text }}</p><details><summary>查看 {{ claim.evidence.length }} 个来源引用</summary><ul><li v-for="source in claim.evidence" :key="source.sourceId"><strong>{{ source.sourceId }}</strong><code>{{ source.contentDigest }}</code></li></ul></details></li></ol>
        </template>
        <section v-if="query.detail.review" class="assist-review" aria-label="人工复核记录"><h4>人工复核记录</h4><p class="assist-intro">{{ query.detail.review.reviewer }} · {{ time(query.detail.review.reviewedAt) }} · {{ labels[query.detail.status] }}</p><p v-if="query.detail.review.acceptedText" class="assist-text">{{ query.detail.review.acceptedText }}</p><p v-if="query.detail.review.comment" class="assist-text">复核说明：{{ query.detail.review.comment }}</p></section>
        <p class="assist-intro">当前页面仅供查看。来源引用只保留字段标识与内容指纹，原始证据内容尚未接入。</p>
        <details class="assist-metadata"><summary>运行信息</summary><dl><dt>运行编号</dt><dd>{{ query.detail.id }}</dd><dt>创建时间</dt><dd>{{ time(query.detail.createdAt) }}</dd><template v-if="query.detail.startedAt"><dt>开始时间</dt><dd>{{ time(query.detail.startedAt) }}</dd></template><template v-if="query.detail.completedAt"><dt>结束时间</dt><dd>{{ time(query.detail.completedAt) }}</dd></template><dt>申请版本</dt><dd>生成时 {{ query.detail.applicationVersion }} / 查询时 {{ query.detail.currentApplicationVersion }}</dd><dt>提示版本</dt><dd>{{ query.detail.promptVersion }}</dd><template v-if="query.detail.suggestion"><dt>模型来源</dt><dd>{{ query.detail.suggestion.providerId }}</dd><dt>模型版本</dt><dd>{{ query.detail.suggestion.modelVersion }}</dd></template></dl></details>
      </template>
    </section>
  </section>
</template>

<style scoped>
.assist-records{padding:20px 0;min-width:0}.assist-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.assist-heading h3{font-size:17px;margin:3px 0}.assist-heading .eyebrow{margin:0}.assist-intro,.assist-empty p{color:var(--muted);font-size:12px;line-height:1.8}.assist-filter{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:20px 0 12px}.assist-filter label{display:flex;align-items:center;gap:8px;margin:0;font-size:12px}.assist-filter input{width:105px}.assist-filter>span{margin-left:auto;color:var(--muted);font-size:11px}.assist-empty{background:var(--paper);border:1px dashed var(--line);padding:24px;border-radius:12px;font-size:13px}.assist-empty p{margin:8px 0 0}.assist-list{list-style:none;padding:0;margin:0}.assist-run{display:flex;align-items:center;gap:12px;width:100%;padding:15px 12px;text-align:left;border:0;border-bottom:1px solid var(--line);background:transparent;border-radius:0}.assist-run[aria-pressed=true]{background:var(--soft);box-shadow:inset 3px 0 var(--deep)}.assist-run>span:first-child{display:grid;gap:6px;min-width:0;flex:1}.assist-run strong{font-size:13px}.assist-run time{font-size:11px;color:var(--muted)}.assist-run-state{font-size:12px;color:var(--deep);white-space:nowrap}.assist-run-state.failed{color:var(--red)}.assist-more{width:100%;margin:12px 0}.assist-detail{margin-top:22px;border-top:2px solid var(--deep);padding-top:16px}.assist-detail h4{font-size:13px;margin:12px 0}.assist-context{display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:12px;margin:10px 0 18px}.assist-context>span{color:var(--muted)}.assist-alert,.assist-stale{padding:12px;border-radius:8px;font-size:12px;line-height:1.8}.assist-alert{background:#fff0ed;color:var(--red)}.assist-stale{background:#fff7e5;color:#815e19}.assist-claims{padding-left:23px}.assist-claims>li{padding:0 0 16px 5px;margin-bottom:12px;border-bottom:1px solid var(--line)}.assist-text{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.9;margin:8px 0 12px}.assist-claims details,.assist-metadata{font-size:11px;color:var(--muted)}summary{cursor:pointer;line-height:1.8}details ul{list-style:none;padding:0}details li{padding:8px 0}details code{display:block;font-size:10px;overflow-wrap:anywhere;margin-top:5px}.assist-review{padding:12px 16px;background:var(--paper);border-left:3px solid var(--line);margin:18px 0}.assist-metadata{margin-top:18px}.assist-metadata dl{display:grid;grid-template-columns:76px minmax(0,1fr);gap:8px;line-height:1.8}.assist-metadata dd{margin:0;overflow-wrap:anywhere}button:focus-visible,summary:focus-visible{outline:3px solid rgba(33,173,159,.35);outline-offset:2px}@media(max-width:650px){.assist-filter>span{width:100%;margin-left:0}.assist-empty{padding:18px}.assist-run{padding:13px 6px;gap:8px}.assist-context{gap:7px}.assist-review{padding:10px 12px}}
</style>
