<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { compareSubmissionRounds, RoundComparisonQuery, selectRoundPair } from '../roundComparison'

const props = defineProps<{ applicationId: string; scopeKey: string; version: number }>()
const query = reactive(new RoundComparisonQuery(api.applicationRounds))
const earlier = ref(0), later = ref(0), changesOnly = ref(true)
const rounds = computed(() => query.rounds ?? [])
const before = computed(() => rounds.value.find(round => round.roundNo === earlier.value))
const after = computed(() => rounds.value.find(round => round.roundNo === later.value))
const result = computed(() => before.value && after.value ? compareSubmissionRounds(before.value, after.value) : null)
const shown = computed(() => result.value?.rows.filter(row => !changesOnly.value || row.changed || row.definitionChanged) ?? [])
const states: Record<string, string> = { IN_APPROVAL: '审批中', APPROVED: '已批准', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已驳回' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
function chooseLater() {
  const pair = selectRoundPair(rounds.value, earlier.value, later.value)
  earlier.value = pair.before; later.value = pair.after
}
function load() { void query.load(props.scopeKey, props.applicationId) }
watch(() => [props.scopeKey, props.applicationId], () => { earlier.value = 0; later.value = 0; changesOnly.value = true }, { flush: 'sync' })
watch(() => [props.scopeKey, props.applicationId, props.version], load, { immediate: true, flush: 'sync' })
watch(() => query.rounds, value => { if (value) chooseLater() }, { flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="round-comparison" aria-label="轮次内容对比" :aria-busy="query.loading">
    <div class="comparison-heading"><div><p class="eyebrow">SUBMISSION / CHANGES</p><h3>两轮之间，改了哪些内容</h3></div><button type="button" class="secondary" :disabled="query.loading" @click="load">刷新提交记录</button></div>
    <p class="comparison-note">仅比较提交时保留的内容，未提交的修改不在其中。按原值核对，数字写法和空值的变化都会保留。</p>
    <p v-if="query.loading" class="comparison-empty" role="status">正在读取有权查看的提交快照…</p>
    <div v-else-if="query.error" class="comparison-error" role="alert"><p>{{ query.error }}</p><button type="button" class="secondary" @click="load">重试读取提交记录</button></div>
    <template v-else-if="query.rounds">
      <p v-if="rounds.length < 2" class="comparison-empty">{{ rounds.length ? '当前仅有一轮提交快照，至少两轮才可比较。' : '暂无可用的提交快照，不能用当前内容补写历史。' }}</p>
      <template v-else-if="before && after && result">
        <div class="comparison-selectors">
          <label>较早轮次<select v-model.number="earlier"><option v-for="round in rounds.filter(round => round.roundNo < later)" :key="round.roundNo" :value="round.roundNo">第 {{ round.roundNo }} 轮 · {{ states[round.status] ?? round.status }}</option></select></label>
          <span class="comparison-arrow" aria-hidden="true">→</span>
          <label>较新轮次<select v-model.number="later" @change="chooseLater"><option v-for="round in rounds.filter(round => rounds.some(item => item.roundNo < round.roundNo))" :key="round.roundNo" :value="round.roundNo">第 {{ round.roundNo }} 轮 · {{ states[round.status] ?? round.status }}</option></select></label>
        </div>
        <div class="comparison-sources"><div v-for="round in [before, after]" :key="round.roundNo"><strong>第 {{ round.roundNo }} 轮 · v{{ round.definitionVersion }}</strong><span>{{ round.submittedBy }} · {{ time(round.submittedAt) }} 提交</span><p v-if="round.reason">{{ round.status === 'RETURNED' ? '退回原因' : round.status === 'WITHDRAWN' ? '撤回说明' : '处理意见' }}：{{ round.reason }}</p></div></div>
        <div class="comparison-summary" role="status"><strong>{{ result.valueChanges }} 项内容变化</strong><span v-if="result.schemaChanged">表单配置有变化{{ result.definitionChanges ? `，涉及 ${result.definitionChanges} 个字段` : '' }}</span><label><input v-model="changesOnly" type="checkbox" />仅看变化</label></div>
        <p v-if="!shown.length" class="comparison-empty">{{ result.schemaChanged ? '填写值未变化，表单配置存在差异，可展开下方配置查看。' : '这两轮的标题、流程版本和表单内容相同。' }}</p>
        <div class="comparison-rows">
          <article v-for="row in shown" :key="`${row.category}:${row.key}`" class="comparison-row" :class="{ changed: row.changed, configured: row.definitionChanged }">
            <div class="comparison-row-title"><strong>{{ row.afterLabel }}</strong><code v-if="row.category === 'field'">{{ row.key }}</code><span>{{ row.changed ? !row.before.present ? '新增值' : !row.after.present ? '移除值' : '已修改' : '值未变' }}</span><span v-if="row.definitionChanged">字段配置变化</span></div>
            <div class="comparison-values">
              <div class="comparison-before"><div><small>第 {{ before.roundNo }} 轮 · {{ row.beforeLabel }}</small><em>{{ row.before.type }}</em></div><pre>{{ row.before.text }}</pre></div>
              <div class="comparison-after"><div><small>第 {{ after.roundNo }} 轮 · {{ row.afterLabel }}</small><em>{{ row.after.type }}</em></div><pre>{{ row.after.text }}</pre></div>
            </div>
          </article>
        </div>
        <details v-if="result.schemaChanged" class="comparison-schema"><summary>查看两轮表单配置原文</summary><p>名称、类型、选项和校验配置均来自各自提交轮次；填写值相同也可能有配置差异。</p><div><section><strong>第 {{ before.roundNo }} 轮</strong><pre>{{ before.formSchema ? JSON.stringify(before.formSchema, null, 2) : '未绑定版本化表单' }}</pre></section><section><strong>第 {{ after.roundNo }} 轮</strong><pre>{{ after.formSchema ? JSON.stringify(after.formSchema, null, 2) : '未绑定版本化表单' }}</pre></section></div></details>
      </template>
    </template>
  </section>
</template>

<style scoped>
.round-comparison{padding:20px 0;min-width:0}.comparison-heading{display:flex;justify-content:space-between;align-items:start;gap:12px}.comparison-heading h3{font-size:16px;margin:0 0 12px}.comparison-heading .eyebrow{font-size:9px;margin-bottom:8px}.comparison-heading button{font-size:11px;flex-shrink:0}.comparison-note{font-size:11px;line-height:1.8;color:var(--muted);margin:0 0 20px}.comparison-selectors{display:grid;grid-template-columns:minmax(0,1fr) 22px minmax(0,1fr);gap:12px;align-items:end}.comparison-selectors label{display:block;font-size:11px;color:var(--muted);margin:0}.comparison-selectors select{display:block;width:100%;min-width:0;margin-top:7px;border:1px solid var(--line);background:white;border-radius:8px;padding:10px;font-size:12px;color:var(--ink)}.comparison-arrow{align-self:end;padding-bottom:12px;color:var(--muted);text-align:center}.comparison-sources{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:15px;margin:16px 0}.comparison-sources>div{min-width:0;font-size:11px;line-height:1.8}.comparison-sources strong{display:block;color:var(--deep)}.comparison-sources span{display:block;color:var(--muted)}.comparison-sources p{margin:7px 0 0;white-space:pre-wrap;overflow-wrap:anywhere}.comparison-summary{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:13px 0;border-top:1px solid var(--line);font-size:11px}.comparison-summary strong{font-size:12px}.comparison-summary>span{color:#a26d2b}.comparison-summary label{margin:0 0 0 auto;display:flex;align-items:center;gap:7px;font-size:11px}.comparison-summary input{width:auto;margin:0;accent-color:var(--deep)}.comparison-empty{padding:24px 12px;text-align:center;color:var(--muted);font-size:12px;line-height:1.8}.comparison-error{padding:15px;background:#fff0ed;border-radius:9px;color:var(--red);font-size:12px}.comparison-error p{line-height:1.8;margin-top:0}.comparison-row{border:1px solid var(--line);border-radius:10px;margin:0 0 12px;overflow:hidden}.comparison-row-title{display:flex;align-items:center;gap:7px;flex-wrap:wrap;padding:12px 14px;font-size:11px;background:var(--paper)}.comparison-row-title strong{font-size:12px;overflow-wrap:anywhere}.comparison-row-title code{font-size:10px;color:var(--muted);overflow-wrap:anywhere}.comparison-row-title span{font-size:10px;color:var(--muted);border-left:1px solid #cad6d1;padding-left:7px}.comparison-row.changed .comparison-row-title{box-shadow:inset 3px 0 #b58743}.comparison-values{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.comparison-values>div{padding:14px;min-width:0}.comparison-before{border-right:1px solid var(--line)}.comparison-row.changed .comparison-before{background:#fffbf5}.comparison-row.changed .comparison-after{background:#f0faf6}.comparison-values>div>div{display:flex;gap:8px;justify-content:space-between;flex-wrap:wrap;color:var(--muted);font-size:10px}.comparison-values small{font-size:10px}.comparison-values em{font-size:9px;font-style:normal}.comparison-values pre{white-space:pre-wrap;overflow-wrap:anywhere;margin:9px 0 0;color:var(--ink);font-family:inherit;font-size:12px;line-height:1.8}.comparison-schema{margin-top:16px;padding:14px;border:1px solid var(--line);border-radius:8px;font-size:11px;line-height:1.8}.comparison-schema summary{cursor:pointer;color:var(--deep)}.comparison-schema p{color:var(--muted)}.comparison-schema>div{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:16px}.comparison-schema section{min-width:0}.comparison-schema pre{max-height:260px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:10px}.comparison-selectors select:focus-visible,.comparison-summary input:focus-visible,.comparison-schema summary:focus-visible{outline:3px solid #20a18c60;outline-offset:2px}@media(max-width:650px){.comparison-heading{flex-wrap:wrap}.comparison-selectors{gap:6px;grid-template-columns:minmax(0,1fr) 14px minmax(0,1fr)}.comparison-values,.comparison-schema>div{grid-template-columns:minmax(0,1fr)}.comparison-before{border-right:0;border-bottom:1px solid var(--line)}.comparison-sources{gap:10px}.comparison-summary label{margin-left:0}}
</style>
