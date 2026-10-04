<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { operationsDuration } from '../approvalOperations'
import { ExpenseFinancialQuery, defaultFinancialFilter, financialAmount, financialFilter, financialPercent,
  type FinancialFilter, type FinancialGroup } from '../expenseFinancialReporting'

const props = defineProps<{ scopeKey: string; refreshVersion: number }>()
const query = reactive(new ExpenseFinancialQuery(api.expenseFinancialReport))
const filters = reactive<FinancialFilter>(defaultFinancialFilter())
const labels = reactive<Partial<Record<'legalEntityId' | 'departmentId' | 'categoryCode', string>>>({})
const inputError = ref('')
const report = computed(() => query.report)
const dimensions = { LEGAL_ENTITY: '原法人', DEPARTMENT: '原部门', CATEGORY: '原费用类别' } as const
const filterKeys = { LEGAL_ENTITY: 'legalEntityId', DEPARTMENT: 'departmentId', CATEGORY: 'categoryCode' } as const
const ageLabels = { NOT_DUE: '未逾期', DAYS_1_30: '逾期 1–30 天', DAYS_31_60: '逾期 31–60 天', DAYS_61_90: '逾期 61–90 天', OVER_90: '逾期超过 90 天', UNKNOWN: '到期日未知' }
const operationLabels: Record<string, string> = { QUEUED: '排队中', CHECKING: '检查中', SENDING: '发送中', POSTING: '制证中', QUERYING: '查询中', UNKNOWN: '结果未知', FAILED: '明确失败', NOT_FOUND: '外部未找到', EXPIRED: '已过期', VOIDED: '已作废', RECONCILING: '对账中', REVERSED: '已退回 / 冲销' }
const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
const cycles = computed(() => report.value ? [
  { name: '提交 → 批准', value: report.value.totals.approval },
  { name: '批准 → 首次到账', value: report.value.totals.payment },
  { name: '提交 → 首次到账', value: report.value.totals.endToEnd }
] : [])
const outcomes = computed(() => report.value ? [
  ['已批准', report.value.totals.approved], ['已退回', report.value.totals.returned], ['已驳回', report.value.totals.rejected],
  ['已撤回', report.value.totals.withdrawn], ['已取消', report.value.totals.cancelled], ['审批中', report.value.totals.inApproval]
] as const : [])
function refresh() {
  inputError.value = ''
  try { void query.load(props.scopeKey, financialFilter(filters)) }
  catch (cause) { query.clear(); inputError.value = (cause as Error).message }
}
function reset() {
  for (const key of Object.values(filterKeys)) { delete filters[key]; delete labels[key] }
  Object.assign(filters, defaultFinancialFilter()); refresh()
}
function chooseGroup(group: Pick<FinancialGroup, 'dimension' | 'code' | 'name'>) {
  if (group.code === null) return
  const key = filterKeys[group.dimension]; filters[key] = group.code; labels[key] = group.name ?? group.code; refresh()
}
function removeFilter(key: 'legalEntityId' | 'departmentId' | 'categoryCode') { delete filters[key]; delete labels[key]; refresh() }
watch(filters, () => { query.clear(); inputError.value = '' }, { flush: 'sync' })
watch(() => props.scopeKey, reset, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh, { flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="content financial-report" :aria-busy="query.loading">
    <div class="page-heading"><div><p class="eyebrow">FINANCE / REPORTS</p><h2>费用财务报表</h2><p class="subhead">核对费用流转、资金余额与待处理事项。</p></div><button type="button" class="secondary" :disabled="query.loading" @click="refresh">{{ query.loading ? '正在统计…' : '刷新报告' }}</button></div>
    <p class="scope-note">当前可读范围 · 仅汇总你能完整读取的原审批轮次。组织、类别使用提交时记录；管理员也遵守敏感字段权限。</p>
    <form class="panel financial-filters" aria-label="财务报告筛选" @submit.prevent="refresh">
      <label>提交开始日期（UTC）<input v-model="filters.from" type="date" required /></label>
      <label>提交结束日期（UTC）<input v-model="filters.to" type="date" required /></label>
      <div class="filter-actions"><button class="primary" :disabled="query.loading">查询报告</button><button type="button" class="quiet" @click="reset">重置近 30 天</button></div>
      <div class="filter-help"><span>点击下方原轮次分组，按法人、部门或费用类别筛选。</span><button v-for="(label, key) in labels" :key="key" class="filter-chip" type="button" :aria-label="'清除筛选：' + label" @click="removeFilter(key)">{{ label }} <span aria-hidden="true">×</span></button></div>
    </form>
    <p v-if="inputError" role="alert" class="report-error">{{ inputError }}</p>
    <div v-if="query.loading" class="panel report-empty" role="status"><strong>正在汇总可读财务事实</strong><p>筛选改变后，旧报告已清除。</p></div>
    <div v-else-if="query.error" class="panel report-empty" role="alert"><strong>未取得财务报告</strong><p>{{ query.error }}</p><button class="secondary" type="button" @click="refresh">重试查询</button></div>
    <template v-else-if="report">
      <div class="report-period"><div><span class="period-tag">提交期间</span><strong>{{ report.filters.from }} — {{ report.filters.to }}</strong><p>包含首尾日期 · UTC · 重提另计一轮</p></div><p>生成于 {{ time(report.generatedAt) }}</p></div>
      <div class="panel report-overview"><div class="round-count"><span>提交轮次</span><strong>{{ number(report.totals.submitted) }}</strong></div><dl class="round-outcomes"><div v-for="[label, value] in outcomes" :key="label"><dt>{{ label }}</dt><dd>{{ number(value) }}</dd></div></dl></div>
      <p v-if="!report.totals.submitted" class="report-note">所选期间没有可完整读取的提交轮次。可以调整日期或清除分组筛选；当前余额与积压仍按下方口径单独展示。</p>
      <section class="panel report-section" aria-labelledby="financial-cycles"><h3 id="financial-cycles">从提交到到账</h3><p class="report-help">P50 为中位用时，P90 为 90% 有效样本不超过的用时，按 nearest-rank 计算，包含周末和等待。首次确认到账保留原时刻，后续退回不覆盖。</p>
        <div class="report-scroll"><table><thead><tr><th scope="col">阶段</th><th scope="col">P50</th><th scope="col">P90</th><th scope="col">有效样本</th><th scope="col">未知</th><th scope="col">待完成</th><th scope="col">不适用</th></tr></thead><tbody><tr v-for="cycle in cycles" :key="cycle.name"><th scope="row">{{ cycle.name }}</th><td>{{ cycle.value.p50Seconds === null ? '无有效样本' : operationsDuration(cycle.value.p50Seconds) }}</td><td>{{ cycle.value.p90Seconds === null ? '无有效样本' : operationsDuration(cycle.value.p90Seconds) }}</td><td>{{ number(cycle.value.samples) }}</td><td>{{ number(cycle.value.unknown) }}</td><td>{{ number(cycle.value.pending) }}</td><td>{{ number(cycle.value.notApplicable) }}</td></tr></tbody></table></div>
        <p class="report-help">零应付的到账周期不适用；未知时间和待完成事项不会当作零秒。</p>
      </section>
      <section class="panel report-section" aria-labelledby="financial-amounts"><h3 id="financial-amounts">费用金额与调整</h3><p class="report-help">按原轮次本位币分别汇总。核减金额为原申报与当前核定金额之差；重提轮次分别计入，不能作为实际现金支出。</p>
        <div class="report-scroll"><table v-if="report.totals.amounts.length"><thead><tr><th scope="col">币种</th><th scope="col">申报</th><th scope="col">核定</th><th scope="col">核减</th></tr></thead><tbody><tr v-for="amount in report.totals.amounts" :key="amount.currency"><th scope="row">{{ amount.currency }}</th><td>{{ financialAmount(amount.claimed) }}</td><td>{{ financialAmount(amount.approved) }}</td><td>{{ financialAmount(amount.reduced) }}</td></tr></tbody></table></div>
        <p v-if="!report.totals.amounts.length" class="report-help">没有可汇总的金额。</p>
        <dl class="ratio-list"><div><dt>超标率</dt><dd>{{ financialPercent(report.totals.overLimitRate) }}</dd><small>{{ number(report.totals.overLimit) }} 个含制度例外的轮次 / {{ number(report.totals.submitted) }} 个提交轮次</small></div><div><dt>核减率</dt><dd>{{ financialPercent(report.totals.reductionRate) }}</dd><small>{{ number(report.totals.reduced) }} 个发生核减的轮次 / {{ number(report.totals.submitted) }} 个提交轮次</small></div><div><dt>退回率</dt><dd>{{ financialPercent(report.totals.returnRate) }}</dd><small>{{ number(report.totals.returned) }} 个退回轮次 / {{ number(report.totals.approved + report.totals.returned + report.totals.rejected) }} 个批准、退回或驳回结论</small></div></dl>
        <details><summary>查看原退回原因（{{ number(report.totals.returned) }} 轮）</summary><ul v-if="report.totals.returnReasons.length" class="reason-list"><li v-for="reason in report.totals.returnReasons" :key="reason.reason ?? 'unknown'"><span>{{ reason.reason ?? '原因未记录' }}</span><strong>{{ number(reason.count) }}</strong></li></ul><p v-else class="report-help">没有已记录的退回轮次。</p></details>
      </section>
      <section class="panel report-section" aria-labelledby="financial-groups"><h3 id="financial-groups">原轮次分组</h3><p class="report-help">点击名称缩小范围。类别间轮次数不能相加；同一原组织改名前后的快照分别保留。组织未记录时保持未知。</p>
        <div class="report-scroll"><table v-if="report.groups.length"><thead><tr><th scope="col">维度 / 名称</th><th scope="col">提交</th><th scope="col">批准</th><th scope="col">超标率</th><th scope="col">核减率</th><th scope="col">退回率</th><th scope="col">批准 P90</th><th scope="col">申报金额</th></tr></thead><tbody><tr v-for="group in report.groups" :key="JSON.stringify([group.dimension, group.code, group.name])"><th scope="row"><small>{{ dimensions[group.dimension] }}</small><button v-if="group.code !== null" type="button" class="report-link" @click="chooseGroup(group)">{{ group.name }}</button><span v-else>组织未记录</span></th><td>{{ number(group.metrics.submitted) }}</td><td>{{ number(group.metrics.approved) }}</td><td>{{ financialPercent(group.metrics.overLimitRate) }}</td><td>{{ financialPercent(group.metrics.reductionRate) }}</td><td>{{ financialPercent(group.metrics.returnRate) }}</td><td>{{ group.metrics.approval.p90Seconds === null ? '—' : operationsDuration(group.metrics.approval.p90Seconds) }}</td><td><span v-for="amount in group.metrics.amounts" :key="amount.currency" class="currency-line">{{ amount.currency }} {{ financialAmount(amount.claimed) }}</span></td></tr></tbody></table></div><p v-if="!report.groups.length" class="report-help">没有可筛选的原轮次分组。</p>
      </section>
      <section class="panel report-section" aria-labelledby="financial-checks"><h3 id="financial-checks">票据查验与重复拦截</h3><p class="report-help">只统计所选可读轮次引用的原件与对应轮次的实际尝试，尝试日期也在上述期间内。原件查验按任务去重，仅采用该原件最近可读提交之前已记录的结果。</p>
        <dl class="check-list"><div><dt>查验成功</dt><dd>{{ number(report.activity.verification.succeeded) }}</dd></div><div><dt>查验拒绝</dt><dd>{{ number(report.activity.verification.rejected) }}</dd></div><div><dt>服务不可用</dt><dd>{{ number(report.activity.verification.unavailable) }}</dd></div><div><dt>尚无结论</dt><dd>{{ number(report.activity.verification.pending) }}</dd></div><div><dt>查验失败率</dt><dd>{{ financialPercent(report.activity.verification.failureRate) }}</dd></div><div><dt>重复票据预检</dt><dd>{{ number(report.activity.duplicatePrechecks) }}</dd></div><div><dt>已记录提交拒绝</dt><dd>{{ number(report.activity.duplicateSubmissions.recorded) }}</dd></div></dl>
        <p class="report-help">查验失败率 = 拒绝 /（成功 + 拒绝），服务不可用和尚无结论不进入分母。相同身份、提交内容和原请求的重复拒绝只记一次。</p>
        <p class="report-warning">提交拒绝记录启用于 {{ time(report.activity.duplicateSubmissions.recordingStartedAt) }}。{{ report.activity.duplicateSubmissions.containsUnrecordedHistory ? '所选期间包含启用前的历史未知部分，不能据此认定历史零拦截。' : '此处展示已成功记录的拒绝尝试。' }}</p>
      </section>
      <div class="report-period current-period"><div><span class="period-tag">当前余额与积压</span><strong>{{ time(report.resources.asOf) }}</strong><p>不受提交日期窗口限制 · 沿用原法人、部门、类别筛选 · 非历史期末余额</p></div></div>
      <section class="panel report-section" aria-labelledby="financial-advances"><h3 id="financial-advances">员工借款未还余额与账龄</h3><p class="report-help">未还余额包含已预留但尚未核销的资金，扣除实际还款；待复核余额单列。到期日当天未逾期，按每笔借款的原法人时区计算账龄。仅纳入有原批准轮次读取依据的账户。</p>
        <p v-if="!report.resources.advanceCategoryApplicable" class="report-note">已选择费用类别，借款没有对应类别，余额与借款积压不适用。</p>
        <template v-else><div v-for="loan in report.resources.advances" :key="loan.currency" class="currency-block"><h4>{{ loan.currency }} <span>{{ number(loan.accounts) }} 个账户（含已结清）</span></h4><dl class="balance-line"><div><dt>未还余额</dt><dd>{{ financialAmount(loan.outstanding) }}</dd></div><div><dt>其中待复核</dt><dd>{{ financialAmount(loan.underReview) }}</dd></div></dl><div class="report-scroll"><table><thead><tr><th v-for="age in loan.ages" :key="age.band" scope="col">{{ ageLabels[age.band] }}</th></tr></thead><tbody><tr><td v-for="age in loan.ages" :key="age.band">{{ financialAmount(age.outstanding) }}<small>{{ number(age.accounts) }} 个未结清账户</small></td></tr></tbody></table></div></div><p v-if="!report.resources.advances.length" class="report-help">当前筛选下没有可读的平台借款余额。</p></template>
      </section>
      <section class="panel report-section" aria-labelledby="financial-prior"><h3 id="financial-prior">事前计划执行</h3><p class="report-help">执行率 = 实际消费 / 原批准额度，容差额度不进入分母，预留单列。已关闭计划仍保留实际消费；超额执行可超过 100%，零批准额度的执行率未知。</p>
        <div class="report-scroll"><table v-if="report.resources.priorRequests.length"><thead><tr><th scope="col">币种</th><th scope="col">原批准明细</th><th scope="col">原批准额度</th><th scope="col">实际消费</th><th scope="col">当前预留</th><th scope="col">执行率</th></tr></thead><tbody><tr v-for="plan in report.resources.priorRequests" :key="plan.currency"><th scope="row">{{ plan.currency }}</th><td>{{ number(plan.lines) }}</td><td>{{ financialAmount(plan.approved) }}</td><td>{{ financialAmount(plan.consumed) }}</td><td>{{ financialAmount(plan.reserved) }}</td><td>{{ financialPercent(plan.executionRate) }}</td></tr></tbody></table></div><p v-if="!report.resources.priorRequests.length" class="report-help">当前筛选下没有可读的平台事前计划额度。</p>
      </section>
      <section class="panel report-section" aria-labelledby="financial-backlog"><h3 id="financial-backlog">付款与凭证积压</h3><p class="report-help">截至 {{ time(report.backlog.asOf) }}，当前非成功操作按状态单列。结果未知、对账中和明确失败不能合并；已退回 / 冲销仍需关注。</p><div class="backlog-grid"><div v-for="(counts, kind) in { payments: report.backlog.payments, vouchers: report.backlog.vouchers }" :key="kind"><h4>{{ kind === 'payments' ? '付款操作' : '凭证操作' }}</h4><dl class="backlog-list"><div v-for="(count, status) in counts" :key="status" :class="{ attention: count > 0 && ['UNKNOWN', 'FAILED', 'REVERSED'].includes(status) }"><dt>{{ operationLabels[status] }}</dt><dd>{{ number(count) }}</dd></div></dl></div></div></section>
    </template>
    <div v-else-if="!inputError" class="panel report-empty"><strong>筛选已改变</strong><p>点击“查询报告”取得对应范围的数据。</p></div>
  </section>
</template>

<style scoped>
.financial-report{--report-blue:#476b8b}.scope-note,.report-help,.report-note{font-size:12px;line-height:1.9;color:var(--muted)}.scope-note{margin:0 0 20px}.financial-filters{display:flex;flex-wrap:wrap;align-items:end;gap:16px;padding:20px}.financial-filters label{display:grid;gap:8px;font-size:12px;color:var(--muted)}.financial-filters input{min-height:40px;border:1px solid var(--line);border-radius:6px;padding:8px;background:#fff;color:var(--ink)}.filter-actions{display:flex;gap:10px;align-items:center}.filter-help{flex-basis:100%;display:flex;flex-wrap:wrap;align-items:center;gap:8px;font-size:11px;color:var(--muted)}.filter-chip{background:var(--soft);border:1px solid #afd8d1;border-radius:5px;padding:6px 10px;color:var(--deep);max-width:100%;overflow-wrap:anywhere}.filter-chip span{margin-left:10px}.report-period{display:flex;justify-content:space-between;align-items:end;gap:12px;flex-wrap:wrap;margin:30px 0 14px}.period-tag{display:block;font-size:11px;color:var(--deep);font-weight:700;letter-spacing:.06em;margin-bottom:9px}.report-period strong{font-size:19px;font-weight:600}.report-period p{font-size:11px;color:var(--muted);margin:8px 0;line-height:1.8}.report-overview{display:flex;padding:23px;gap:30px;align-items:center;margin-bottom:20px}.round-count{min-width:120px;border-right:2px solid var(--teal);padding-right:30px}.round-count span{font-size:12px;color:var(--muted)}.round-count strong{display:block;font:600 38px/1.4 Manrope,sans-serif;color:var(--deep)}.round-outcomes{flex:1;display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:14px;margin:0}.financial-report dt{font-size:12px;color:var(--muted)}.financial-report dd{margin:7px 0 0;font-variant-numeric:tabular-nums;font-weight:600}.round-outcomes dd{font-size:20px}.report-section{padding:24px;margin-bottom:20px}.report-section h3{font-size:16px;margin:0 0 10px}.report-section h4{font-size:13px;margin:10px 0 15px}.report-section h4 span{font-size:11px;font-weight:400;color:var(--muted);margin-left:10px}.report-scroll{overflow:auto}.financial-report table{border-collapse:collapse;text-align:left;width:100%;font-size:12px}.financial-report th,.financial-report td{padding:14px 12px;border-bottom:1px solid var(--line);line-height:1.8;vertical-align:top}.financial-report th:first-child,.financial-report td:first-child{padding-left:0}.financial-report thead th{font-size:11px;font-weight:500;color:var(--muted);white-space:nowrap}.financial-report tbody th{font-weight:500;max-width:280px;overflow-wrap:anywhere}.financial-report td{font-family:'SFMono-Regular',Consolas,monospace;white-space:nowrap}.financial-report th small,.financial-report td small{display:block;color:var(--muted);font-size:10px;font-family:inherit;font-weight:400}.ratio-list{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px;padding:20px 0;margin:0}.ratio-list dd{font-size:24px;color:var(--deep)}.ratio-list small{display:block;margin-top:8px;color:var(--muted);font-size:11px;line-height:1.8}.financial-report summary{font-size:12px;cursor:pointer;color:var(--deep);padding:12px 0}.reason-list{list-style:none;padding:0;margin:0}.reason-list li{display:flex;justify-content:space-between;gap:20px;padding:12px 0;border-top:1px solid var(--line);font-size:12px;line-height:1.9}.reason-list span{white-space:pre-wrap;overflow-wrap:anywhere}.report-link{padding:0;color:var(--deep);text-align:left;font:inherit;white-space:pre-wrap;overflow-wrap:anywhere}.report-link:hover{text-decoration:underline}.currency-line{display:block}.check-list{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:22px;margin:23px 0}.check-list dd{font-size:22px}.report-warning{border-left:3px solid var(--amber);background:#fff9eb;padding:12px 15px;font-size:12px;color:#78622e;line-height:1.9}.current-period{margin-top:38px;border-top:2px solid var(--report-blue);padding-top:22px}.current-period .period-tag{color:var(--report-blue)}.balance-line{display:flex;flex-wrap:wrap;gap:30px}.balance-line dd{font-family:'SFMono-Regular',Consolas,monospace;font-size:20px}.currency-block+.currency-block{margin-top:26px}.backlog-grid{display:grid;grid-template-columns:1fr 1fr;gap:36px}.backlog-list{margin:0}.backlog-list>div{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--line);padding:10px 0}.backlog-list dd{margin:0}.backlog-list .attention dt,.backlog-list .attention dd{color:var(--red)}.report-empty{text-align:center;padding:40px 20px;margin-top:22px}.report-empty p{font-size:12px;color:var(--muted);line-height:1.9}.report-error{color:var(--red);font-size:12px;line-height:1.9}
@media(max-width:1050px){.round-outcomes{grid-template-columns:repeat(3,minmax(0,1fr))}.check-list{grid-template-columns:repeat(3,minmax(0,1fr))}}
@media(max-width:650px){.financial-report .page-heading{align-items:start;flex-direction:column;gap:15px}.financial-filters,.report-section{padding:17px 14px}.financial-filters{gap:12px}.financial-filters label{min-width:0;width:calc(50% - 6px)}.financial-filters input{width:100%;min-width:0}.filter-actions{flex-basis:100%}.report-overview{padding:18px 14px;align-items:start;gap:15px}.round-count{min-width:80px;padding-right:15px}.round-count strong{font-size:30px}.round-outcomes{gap:14px 8px}.round-outcomes dd{font-size:17px}.round-outcomes dt{font-size:10px}.ratio-list{grid-template-columns:1fr;gap:18px}.ratio-list>div{display:grid;grid-template-columns:1fr auto;align-items:center}.ratio-list small{grid-column:1/-1;margin-top:3px}.ratio-list dd{margin:0;font-size:20px}.financial-report table{min-width:570px}.check-list{grid-template-columns:repeat(2,minmax(0,1fr))}.backlog-grid{grid-template-columns:1fr;gap:22px}.report-period strong{font-size:17px}}
</style>
