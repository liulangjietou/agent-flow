<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { moneyLabel, type ExpenseDetail } from '../expenses'
import type { ApplicationSearchItem, ApplicationSearchPage } from '../applicationSearch'
import { riskError, riskScope, type RiskCalendar, type RiskRequest } from '../expenseRisk'

const props = defineProps<{ report: ExpenseDetail; taskId: string; scopeKey: string; locked: boolean; resetVersion: number }>()
const emit = defineEmits<{ prepare: [request: RiskRequest]; change: []; dirty: [value: boolean]; busy: [value: boolean] }>()
const documents = ref<Array<{ report: ExpenseDetail; lineNos: number[] }>>([])
const query = ref(''), submittedQuery = ref(''), results = ref<ApplicationSearchPage | null>(null)
const calendars = ref<RiskCalendar[]>([]), calendarId = ref(''), nextCalendarKey = ref<string | null>(null)
const loading = ref(false), error = ref(''), applicant = ref('')
const context = computed(() => JSON.stringify([props.scopeKey, props.report.id, props.report.applicationVersion, props.report.financialVersion, props.report.roundNo, props.taskId, props.resetVersion]))
const selection = computed(() => JSON.stringify([documents.value.map(d => [d.report.id, d.report.roundNo, d.lineNos]), calendarId.value]))
const dirty = computed(() => documents.value.length > 1 || documents.value.some(d => d.lineNos.length > 0) || !!calendarId.value)
const blocked = computed(() => props.locked || loading.value)
let active = true, generation = 0, controller: AbortController | null = null

function reset() {
  generation++; controller?.abort(); loading.value = false; error.value = ''; applicant.value = ''
  documents.value = [{ report: props.report, lineNos: [] }]; query.value = ''; submittedQuery.value = ''; results.value = null
  calendars.value = []; calendarId.value = ''; nextCalendarKey.value = null
}
/** 选择目录有界读取；账号、轮次或任务变化后迟到结果不能恢复旧费用。 */
async function read<T>(fetch: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void) {
  if (blocked.value || !active) return
  const original = context.value, current = ++generation
  controller?.abort(); const request = new AbortController(); controller = request; loading.value = true; error.value = ''
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const result = await Promise.race([fetch(request.signal), new Promise<never>((_, reject) => {
      timer = setTimeout(() => { request.abort(); reject(new Error('来源查询超时，请重试。')) }, 12_000)
    })])
    if (active && current === generation && original === context.value) apply(result)
  } catch (cause) {
    if (active && current === generation && original === context.value) {
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) reset()
      error.value = riskError(cause)
    }
  } finally { clearTimeout(timer); if (active && current === generation && original === context.value) loading.value = false }
}
async function search(more = false) {
  if (!more) { results.value = null; submittedQuery.value = query.value.trim() }
  const cursor = more ? results.value?.nextCursor : undefined
  if (more && !cursor) return
  await read(async signal => {
    const primary = await api.application(props.report.applicationId, signal)
    if (signal.aborted) throw new Error('来源查询已结束。')
    const page = await api.searchVisibleApplications({ applicant: primary.createdBy, q: submittedQuery.value, limit: 30, ...(cursor ? { cursor } : {}) }, signal)
    return { page, applicant: primary.createdBy }
  }, value => {
    applicant.value = value.applicant
    const prior = more ? results.value?.items ?? [] : []
    results.value = { items: [...prior, ...value.page.items.filter(item => item.id !== props.report.applicationId && item.roundNo > 0 && !prior.some(p => p.id === item.id))], nextCursor: value.page.nextCursor }
  })
}
async function add(item: ApplicationSearchItem) {
  if (documents.value.length >= 20 || documents.value.some(d => d.report.applicationId === item.id)) return
  await read(async signal => {
    const application = await api.application(item.id, signal)
    if (application.createdBy !== applicant.value || application.businessReference?.type !== 'EXPENSE') throw new Error('请选择同一申请人的已提交报销单。')
    if (signal.aborted) throw new Error('来源查询已结束。')
    const report = await api.expenseReport(application.businessReference.id, item.roundNo, signal)
    if (report.applicationId !== item.id || report.roundNo !== item.roundNo || !report.financialRound
        || report.content.legalEntityId !== props.report.content.legalEntityId) throw new Error('对照单须与主单属于同一法人，并完整开放所选轮次的费用明细。')
    return report
  }, report => { documents.value.push({ report, lineNos: [] }); results.value = null })
}
async function loadCalendars(more = false) {
  if (more && !nextCalendarKey.value) return
  await read(signal => api.expenseRiskCalendars(props.report.id, props.report.roundNo, props.taskId, more ? nextCalendarKey.value! : undefined, signal), page => {
    const prior = more ? calendars.value : []
    calendars.value = [...prior, ...page.items.filter(c => !prior.some(p => p.id === c.id))]; nextCalendarKey.value = page.nextAfterKey
  })
}
function prepare() {
  if (blocked.value) return
  error.value = ''
  try { emit('prepare', { taskId: props.taskId, scope: riskScope(props.report.id, props.report.roundNo,
    documents.value.map(d => ({ reportId: d.report.id, roundNo: d.report.roundNo, lineNos: d.lineNos })), calendarId.value || null) }) }
  catch (cause) { error.value = riskError(cause) }
}
watch(context, reset, { immediate: true, flush: 'sync' })
watch(selection, () => emit('change'), { flush: 'sync' })
watch(dirty, value => emit('dirty', value), { immediate: true, flush: 'sync' })
watch(loading, value => emit('busy', value), { flush: 'sync' })
onUnmounted(() => { active = false; generation++; controller?.abort(); emit('dirty', false); emit('busy', false) })
</script>

<template>
  <section aria-label="风险解释的单据范围" class="risk-scope">
    <h4>1. 选择费用范围</h4>
    <p>明确勾选本单和对照单的费用行。最多 20 份单据、合计 200 行；对照范围限同一申请人、同一法人。</p>
    <p v-if="error" role="alert" class="risk-error">{{ error }}</p>
    <fieldset v-for="(entry, index) in documents" :key="entry.report.id" :disabled="blocked">
      <legend>单据 {{ index + 1 }} · {{ index === 0 ? '本次审批' : '对照单' }} · {{ entry.report.businessNo }} · 第 {{ entry.report.roundNo }} 轮</legend>
      <p>{{ entry.report.content.title }}</p>
      <label v-for="line in entry.report.content.lines" :key="line.lineNo"><input v-model="entry.lineNos" type="checkbox" :value="line.lineNo" />第 {{ line.lineNo }} 行 · {{ line.incurredOn }} · {{ line.categoryCode }} · {{ moneyLabel(line.claimedGross) }}</label>
      <button v-if="index > 0" type="button" class="quiet" @click="documents.splice(index, 1)">移除此对照单</button>
    </fieldset>
    <div class="risk-search"><label>查找可访问的对照申请<input v-model="query" maxlength="100" :disabled="blocked" placeholder="输入单号或标题" @keydown.enter.prevent="search()" /></label><button type="button" class="quiet" :disabled="blocked || documents.length >= 20" @click="search()">查找对照单</button></div>
    <div v-if="results"><p v-if="!results.items.length">本页没有可选申请，可调整关键词或加载下一页。</p><ul><li v-for="item in results.items" :key="item.id"><span>{{ item.businessNo }} · {{ item.title }} · 第 {{ item.roundNo }} 轮</span><button type="button" class="quiet" :disabled="blocked || documents.length >= 20 || documents.some(d => d.report.applicationId === item.id)" @click="add(item)">添加并核对明细权限</button></li></ul><button v-if="results.nextCursor" type="button" class="quiet" :disabled="blocked" @click="search(true)">更多可访问申请</button></div>
    <div class="risk-calendar"><label>工作日历（可选）<select v-model="calendarId" :disabled="blocked"><option value="">不判断非工作日</option><option v-for="calendar in calendars" :key="calendar.id" :value="calendar.id">{{ calendar.name }} · {{ calendar.zoneId }} · 修订 {{ calendar.revision }}</option></select></label><button type="button" class="quiet" :disabled="blocked" @click="loadCalendars()">读取可选日历</button><button v-if="nextCalendarKey" type="button" class="quiet" :disabled="blocked" @click="loadCalendars(true)">更多日历</button></div>
    <p v-if="loading" role="status">正在读取所选来源与权限…</p>
    <button type="button" class="secondary" :disabled="blocked" @click="prepare">预览本次发送内容</button>
  </section>
</template>

<style scoped>
.risk-scope fieldset { border: 1px solid var(--line); border-radius: 8px; margin: 12px 0; padding: 12px; min-width: 0; }
.risk-scope label { display: flex; align-items: start; gap: 8px; margin: 8px 0; overflow-wrap: anywhere; }
.risk-scope input[type=checkbox] { width: auto; flex-shrink: 0; }
.risk-search, .risk-calendar { display: flex; align-items: end; gap: 8px; flex-wrap: wrap; margin: 12px 0; }
.risk-search label, .risk-calendar label { display: grid; flex: 1; min-width: min(220px, 100%); }
.risk-scope input, .risk-scope select { font: inherit; min-width: 0; max-width: 100%; }
.risk-scope li { margin: 8px 0; overflow-wrap: anywhere; }.risk-error { color: var(--red); }
</style>
