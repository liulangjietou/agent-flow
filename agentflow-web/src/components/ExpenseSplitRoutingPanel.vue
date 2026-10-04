<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { SplitRoutingQuery, splitStatuses } from '../expenseSplitRouting'
import { moneyLabel } from '../expenses'
const props = defineProps<{ reportId: string; applicationId: string; roundNo: number; scopeKey: string; version?: number; locked?: boolean }>()
const query = reactive(new SplitRoutingQuery(api.expenseSplitRouting))
const PAGE_SIZE = 10
const page = ref(0), expanded = ref('')
const detail = computed(() => query.view?.details)
const assessment = computed(() => detail.value?.assessment)
const sources = computed(() => assessment.value?.sources ?? [])
const shownSources = computed(() => sources.value.slice(page.value * PAGE_SIZE, (page.value + 1) * PAGE_SIZE))
const pageCount = computed(() => Math.max(1, Math.ceil(sources.value.length / PAGE_SIZE)))
const timestamp = (value: string) => value.replace('T', ' ').replace(/Z$/, ' UTC')
function load() { return query.load(props.scopeKey, props.reportId, props.applicationId, props.roundNo) }
function changePage(next: number) {
  if (!Number.isInteger(next) || next < 0 || next >= pageCount.value) return
  page.value = next; expanded.value = ''
}
function toggleSource(id: string) { expanded.value = expanded.value === id ? '' : id }
watch(() => [props.scopeKey, props.reportId, props.applicationId, props.roundNo, props.version], () => { void load() }, { immediate: true, flush: 'sync' })
watch(() => query.view, () => { page.value = 0; expanded.value = '' }, { flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="split-evidence" aria-label="跨单路由依据" :aria-busy="query.loading">
    <div class="split-heading"><h4>跨单路由依据</h4><button type="button" class="quiet" :disabled="locked || query.loading" @click="load">刷新依据</button></div>
    <p v-if="query.loading" role="status">正在读取本轮冻结依据…</p>
    <p v-else-if="query.error" class="inline-error" role="alert">{{ query.error }}</p>
    <template v-else-if="query.view">
      <p class="split-status" :class="{ suspected: query.view.status === 'SPLIT_SUSPECTED' }">{{ splitStatuses[query.view.status] }}</p>
      <p v-if="query.view.status === 'RESTRICTED'">当前权限不足，无法查看本轮完整风险结论和金额依据。</p>
      <p v-else-if="query.view.status === 'NOT_RECORDED'">这份原轮次没有保存跨单检查记录，不能据此判断是否存在拆单风险。</p>
      <p v-else-if="query.view.status === 'UNCONFIGURED'">本轮提交使用的流程未配置跨单规则，没有执行这项检查。</p>
      <p v-else-if="query.view.status === 'DISABLED'">本轮提交时明确关闭了跨单规则，没有执行这项检查。</p>
      <template v-if="detail">
        <dl class="split-facts">
          <div><dt>流程版本</dt><dd>{{ detail.processKey }} / v{{ detail.definitionVersion }}</dd></div>
          <div><dt>原提交轮次</dt><dd>第 {{ query.view.roundNo }} 轮 · 规则版本 {{ detail.ruleVersion }}</dd></div>
          <div><dt>依据冻结时刻</dt><dd><time :datetime="detail.primary.submittedAt">{{ timestamp(detail.primary.submittedAt) }}</time></dd></div>
        </dl>
        <template v-if="assessment && detail.configuration.rule">
          <dl class="split-facts">
            <div><dt>滚动窗口</dt><dd>{{ detail.configuration.rule.windowDays }} × 24 小时</dd></div>
            <div><dt>本轮阈值</dt><dd>{{ moneyLabel(detail.configuration.rule.threshold) }}</dd></div>
            <div><dt>本单冻结金额</dt><dd>{{ moneyLabel(assessment.ownAmount) }}</dd></div>
            <div><dt>业务审批路由金额</dt><dd>{{ moneyLabel(assessment.routingAmount) }}</dd></div>
          </dl>
          <p>金额和来源固定于本轮提交。后续核减或规则换版不会重写这份依据；财务审核及付款使用本单财务金额。</p>
          <p>窗口起点：{{ timestamp(assessment.windowFrom) }}（包含起点）。至少两张同类正额报销且合计严格超过阈值才命中。</p>
          <div v-if="assessment.categories.length" class="split-table"><table><caption>本轮费用类别合计</caption><thead><tr><th>费用类别</th><th>合计</th><th>单据数</th><th>结果</th></tr></thead><tbody><tr v-for="category in assessment.categories" :key="category.categoryCode"><td>{{ category.categoryCode }}</td><td>{{ moneyLabel(category.total) }}</td><td>{{ category.reportCount }}</td><td>{{ category.triggered ? '命中' : '未命中' }}</td></tr></tbody></table></div>
          <p v-else>本单没有参与跨单计算的正额费用类别。</p>
          <h5>来源单据 · {{ sources.length }} 份</h5>
          <ul class="split-sources"><li v-for="source in shownSources" :key="source.reportId">
            <button type="button" class="split-source" :aria-expanded="expanded === source.reportId" @click="toggleSource(source.reportId)"><strong>{{ source.reportId === reportId ? '本单' : '对照单' }} · 第 {{ source.roundNo }} 轮</strong><code>{{ source.reportId }}</code><span>财务版本 {{ source.financialVersion }} · {{ expanded === source.reportId ? '收起行明细' : '查看行明细' }}</span></button>
            <template v-if="expanded === source.reportId"><p>来源提交时刻：{{ timestamp(source.submittedAt) }}</p><div class="split-table"><table><caption>原财务版本中的核定金额</caption><thead><tr><th>原行号</th><th>费用类别</th><th>本位币含税金额</th></tr></thead><tbody><tr v-for="line in source.lines" :key="line.lineNo"><td>{{ line.lineNo }}</td><td>{{ line.categoryCode }}</td><td>{{ moneyLabel(line.approvedGross) }}</td></tr></tbody></table></div></template>
          </li></ul>
          <nav v-if="pageCount > 1" class="split-pages" aria-label="来源单据翻页"><button type="button" class="quiet" :disabled="page === 0" @click="changePage(page - 1)">上一页</button><span>第 {{ page + 1 }} / {{ pageCount }} 页</span><button type="button" class="quiet" :disabled="page + 1 >= pageCount" @click="changePage(page + 1)">下一页</button></nav>
        </template>
      </template>
    </template>
  </section>
</template>

<style scoped>
.split-evidence{border-top:1px solid var(--line);margin-top:20px;padding-top:16px}.split-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.split-heading h4{margin:0;font-size:14px}.split-heading button,.split-pages button{font-size:12px}.split-evidence p{font-size:12px;line-height:1.8;color:var(--muted);overflow-wrap:anywhere}.split-evidence .split-status{font-weight:600;color:var(--deep)}.split-evidence .suspected{color:var(--red)}.split-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:14px 0;font-size:12px}.split-facts dt{color:var(--muted);margin-bottom:5px}.split-facts dd{margin:0;overflow-wrap:anywhere;line-height:1.7}.split-table{overflow-x:auto;margin:12px 0}.split-table table{width:100%;border-collapse:collapse;font-size:12px}.split-table caption{text-align:left;padding:6px 0;color:var(--muted)}.split-table th,.split-table td{padding:9px 10px;text-align:left;border-bottom:1px solid var(--line);overflow-wrap:anywhere}.split-table th{white-space:nowrap}.split-sources{list-style:none;padding:0}.split-sources li{border:1px solid var(--line);border-radius:8px;padding:12px;margin-top:10px}.split-source{display:grid;width:100%;gap:7px;text-align:left;padding:0}.split-source strong{font-size:12px}.split-source code{font-size:11px;overflow-wrap:anywhere}.split-source span{font-size:11px;color:var(--muted)}.split-pages{display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:12px}.split-evidence h5{font-size:12px;margin:18px 0 8px}@media(max-width:650px){.split-facts{grid-template-columns:1fr}.split-table th,.split-table td{padding:8px 5px}}
</style>
