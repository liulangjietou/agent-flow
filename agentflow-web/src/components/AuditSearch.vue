<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { AuditSearchQuery, type AuditSearchFilters, type AuditExportFilters } from '../auditSearch'
import { WorkbookExportQuery } from '../workbookExport'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ open: [id: string] }>()
const emptyFilters = () => ({ q: '', actor: '', action: '', source: '', applicationId: '', from: '', to: '' })
const fields = reactive(emptyFilters())
const query = reactive(new AuditSearchQuery((filters, signal) => api.searchAudit(filters, signal)))
const exporter = reactive(new WorkbookExportQuery<AuditExportFilters>((filters, signal) => api.exportAudit(filters, signal)))
const appliedFilters = ref<AuditExportFilters>({}), downloadUrl = ref('')
const submitted = ref(''), validation = ref('')
const actions: Record<string, string> = { AUTO_PASSED_DUPLICATE: '相邻重复审批已自动通过', SELF_APPROVAL_ESCALATED: '自审批已上溯直属主管', INSTANCE_PAUSE: '暂停审批', INSTANCE_RESUME: '恢复审批', INSTANCE_TERMINATE: '终止审批', TIMER_ELAPSED: '定时等待已到期', TIMER_FAILED: '定时推进失败', TIMER_RETRY: '重试原定时等待', EVENT_RECEIVED: '事件已推进等待', SERVICE_TASK_COMPLETED: '服务任务已完成', SUBPROCESS_COMPLETED: '子审批完成并接续', SUBPROCESS_STOPPED: '父子审批停止联动', CREATE: '创建申请', EXPENSE_REDUCE: '费用核减', REVISE: '修改申请', SUBMIT: '提交审批', WITHDRAW: '撤回申请', CANCEL: '作废申请', CLAIM: '认领任务', RELEASE: '释放任务', TRANSFER: '转办', DELEGATE: '委派', RESOLVE: '完成委派', RETURN: '退回', REJECT: '驳回', APPROVE: '同意', ADD_SIGNER: '增加会签人', REMOVE_SIGNER: '移除会签人' }
actions.TENANT_INITIALIZE = '完成工作区初始化'
actions.INVOICE_EXTRACTION_QUEUE = '发起票据抽取'
actions.INVOICE_EXTRACTION_CONFIRM = '确认票据候选值'
actions.INVOICE_EXTRACTION_DISMISS = '放弃票据建议'
actions.EXPENSE_REQUEST_CLOSED = '关闭事前费用额度'
const sources: Record<string, string> = { Application: '申请操作', Task: '任务操作', TenantInitialization: '工作区初始化', InvoiceExtractionRun: '票据抽取', ExpenseRequest: '事前批准额度' }
const snapshot = () => JSON.stringify(fields)
const changed = computed(() => submitted.value !== snapshot())
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

function refresh() {
  exporter.clear()
  validation.value = ''
  const applicationId = fields.applicationId.trim()
  if (applicationId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(applicationId)) validation.value = '申请标识需要填写完整 UUID。'
  if (fields.from && fields.to && fields.from > fields.to) validation.value = '操作结束日期不能早于开始日期。'
  if (validation.value) { query.clear(); return }
  const filters: AuditSearchFilters = { q: fields.q.trim(), actor: fields.actor.trim(), action: fields.action, source: fields.source }
  if (applicationId) filters.applicationId = applicationId
  if (fields.from) filters.from = fields.from
  if (fields.to) filters.to = fields.to
  submitted.value = snapshot()
  appliedFilters.value = { ...filters }
  void query.load(props.scopeKey, filters)
}
function reset() { Object.assign(fields, emptyFilters()); refresh() }
function releaseDownload() { if (downloadUrl.value) URL.revokeObjectURL(downloadUrl.value); downloadUrl.value = '' }
watch(fields, () => exporter.clear(), { deep: true, flush: 'sync' })
watch(() => exporter.file, file => { releaseDownload(); if (file) downloadUrl.value = URL.createObjectURL(file) }, { flush: 'sync' })
watch(() => props.scopeKey, reset, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
onUnmounted(() => { query.clear(); exporter.clear(); releaseDownload() })
</script>

<template>
  <section class="content audit-search" :aria-busy="query.loading">
    <div class="page-heading"><div><p class="eyebrow">OPERATIONS / AUDIT</p><h2>操作审计</h2><p class="subhead">查找当前租户的申请、任务、初始化与票据抽取操作，追溯谁在何时作出了什么操作。</p></div><span class="admin-label">管理员视图</span></div>
    <form class="panel audit-filters" @submit.prevent="refresh">
      <label class="wide">申请当前标题或业务单号<input v-model="fields.q" type="search" maxlength="100" placeholder="输入关联申请的关键词" /></label>
      <label>操作人账号<input v-model="fields.actor" maxlength="128" placeholder="精确账号，如 alice" /></label>
      <label>操作动作<select v-model="fields.action"><option value="">全部动作</option><option v-for="(label, value) in actions" :key="value" :value="value">{{ label }}</option></select></label>
      <label>事件来源<select v-model="fields.source"><option value="">全部来源</option><option v-for="(label, value) in sources" :key="value" :value="value">{{ label }}</option></select></label>
      <label>申请标识<input v-model="fields.applicationId" maxlength="36" placeholder="完整申请 UUID" /></label>
      <label>操作开始日期（UTC）<input v-model="fields.from" type="date" min="0001-01-01" max="9999-12-31" /></label>
      <label>操作结束日期（UTC）<input v-model="fields.to" type="date" min="0001-01-01" max="9999-12-31" /></label>
      <div class="audit-actions"><button class="primary" :disabled="query.loading">查询审计</button><button type="button" class="secondary" @click="reset">重置筛选</button></div>
    </form>
    <p v-if="validation" class="audit-error" role="alert">{{ validation }}</p>
    <p v-if="changed && query.loaded" class="audit-pending" role="status">筛选已修改，下方仍是上次查询结果。点击“查询审计”后生效。</p>
    <div class="audit-summary"><span>操作时间倒序 · 日期筛选包含首尾两天 · 时间按当前设备时区显示</span><span v-if="query.loaded">已加载 {{ query.items.length }} 条操作</span></div>
    <p class="audit-note">这里只展示已记录的操作事实；任务“同意”不等于整单批准。旧记录缺失的操作人或动作显示“未记录”，无法关联申请的事件仍保留。申请标题为当前值，历史正文请进入申请详情查看。</p>
    <div class="panel audit-export" :aria-busy="exporter.loading">
      <div><strong>导出审计摘要</strong><p>按已查询条件导出全部匹配操作，每次最多 10,000 条。包含操作人、动作和关联申请标识，不含表单正文与审批意见。</p></div>
      <button class="secondary" :disabled="!query.loaded || query.loading || !!query.error || changed || !!validation || exporter.loading" @click="exporter.generate(scopeKey, appliedFilters)">{{ exporter.loading ? '正在生成…' : '生成 Excel' }}</button>
      <button v-if="exporter.loading" class="secondary" @click="exporter.clear()">取消等待</button>
      <p v-if="exporter.error" class="audit-error" role="alert">{{ exporter.error }}</p>
      <p v-if="downloadUrl" class="audit-download" role="status">文件已生成。<a :href="downloadUrl" download="agentflow-audit.xlsx">下载 Excel ↓</a> <span>新操作可能继续产生；修改筛选后需重新生成。</span></p>
    </div>
    <div v-if="query.error" class="panel audit-failure" role="alert"><div><strong>{{ query.items.length ? '后续记录加载失败' : '暂时无法读取审计' }}</strong><p>{{ query.error }}</p></div><button class="secondary" :disabled="query.loading || changed" @click="query.items.length ? query.more() : refresh()">重试</button></div>
    <div v-if="!query.items.length && query.loading" class="panel audit-empty" role="status">正在查询审计…</div>
    <div v-else-if="!query.items.length && query.loaded" class="panel audit-empty"><h3>没有符合条件的操作</h3><p>调整账号、动作或日期后重新查询。操作人筛选仅匹配已记录的账号。</p></div>
    <div v-if="query.items.length" class="panel audit-results">
      <article v-for="item in query.items" :key="item.id" class="audit-row">
        <div class="audit-event"><span class="audit-badge" :class="item.action?.toLowerCase()">{{ item.action ? actions[item.action] ?? item.action : '动作未记录' }}</span><strong>{{ item.actor || '操作人未记录' }}</strong><time>{{ dateLabel(item.occurredAt) }}</time><small>{{ sources[item.source] ?? item.source }} · 聚合版本 {{ item.aggregateVersion }}</small></div>
        <div class="audit-subject"><template v-if="item.applicationId"><small>关联申请 · 当前标题</small><h3>{{ item.currentTitle }}</h3><p>{{ item.businessNo }}</p></template><template v-else><h3>未关联申请</h3><p>此事件没有可确认的当前租户申请关联。</p></template><details><summary>查看事件标识</summary><dl><dt>事件 ID</dt><dd>{{ item.eventId }}</dd><dt>记录 ID</dt><dd>{{ item.id }}</dd><dt>来源对象</dt><dd>{{ item.aggregateId }}</dd><template v-if="item.applicationId"><dt>申请 ID</dt><dd>{{ item.applicationId }}</dd></template></dl></details></div>
        <button v-if="item.applicationId" class="secondary audit-open" :disabled="locked" @click="emit('open', item.applicationId)">查看申请 ↗</button>
      </article>
    </div>
    <div v-if="query.items.length" class="audit-pagination"><button v-if="query.nextCursor" class="secondary" :disabled="query.loading || changed" @click="query.more()">{{ query.loading ? '加载中…' : '加载更多操作' }}</button><span v-else>当前查询已无更多操作</span><small>新操作会继续产生，重新查询可查看最新记录。</small></div>
  </section>
</template>

<style scoped>
.audit-export{display:flex;align-items:center;gap:16px;flex-wrap:wrap;padding:18px 22px;margin-bottom:20px}.audit-export>div{flex:1;min-width:200px}.audit-export strong{font-size:12px;color:var(--deep)}.audit-export p{font-size:11px;line-height:1.8;margin:6px 0 0;color:var(--muted)}.audit-export>p{flex-basis:100%}.audit-export .audit-error{color:var(--red)}.audit-download a{color:var(--deep);font-weight:600;text-underline-offset:3px}.audit-download span{margin-left:8px}

.admin-label{font-size:11px;background:var(--soft);color:var(--deep);padding:8px 12px;border-radius:6px;white-space:nowrap}.audit-filters{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px;padding:24px}.audit-filters label{display:flex;flex-direction:column;gap:8px;color:var(--muted);font-size:11px;min-width:0}.audit-filters .wide{grid-column:span 2}.audit-filters input,.audit-filters select{width:100%;min-width:0;height:40px;border:1px solid var(--line);border-radius:7px;padding:8px 10px;background:white;font:inherit;color:var(--ink)}.audit-actions{display:flex;align-items:end;gap:10px;grid-column:span 2}.audit-summary{display:flex;justify-content:space-between;gap:18px;color:var(--muted);font-size:11px;line-height:1.7;margin:20px 0 10px}.audit-note{color:var(--muted);font-size:11px;line-height:1.9;margin:0 0 20px;max-width:900px}.audit-pending{font-size:12px;padding:12px 16px;background:#fff5e1;color:#866324;border-radius:6px}.audit-error{color:var(--red);font-size:12px}.audit-row{display:grid;grid-template-columns:185px minmax(0,1fr) auto;gap:24px;align-items:start;padding:24px}.audit-row+.audit-row{border-top:1px solid var(--line)}.audit-event{display:flex;flex-direction:column;align-items:start;gap:8px;min-width:0}.audit-event strong{font-size:12px;overflow-wrap:anywhere}.audit-event time,.audit-event small,.audit-subject small{font-size:10px;color:var(--muted)}.audit-subject{min-width:0;overflow-wrap:anywhere}.audit-subject h3{font-size:14px;margin:7px 0 8px}.audit-subject p{font-size:11px;margin:0 0 12px;color:var(--muted)}.audit-subject details{font-size:10px;color:var(--muted)}.audit-subject summary{cursor:pointer;color:var(--deep);padding:4px 0}.audit-subject dl{line-height:1.7;margin-bottom:0}.audit-subject dt{margin-top:8px}.audit-subject dd{margin:2px 0 0;font-family:monospace}.audit-open{white-space:nowrap;align-self:center}.audit-badge{border-radius:5px;padding:5px 8px;font-size:11px;background:#f0f3f2;color:#64746f}.audit-badge.approve{background:var(--soft);color:var(--deep)}.audit-badge.return,.audit-badge.withdraw{background:#fff4df;color:#8b682e}.audit-badge.reject{background:#fff0ed;color:var(--red)}.audit-empty{padding:48px 20px;text-align:center;color:var(--muted);font-size:13px}.audit-empty h3{font-size:16px;color:var(--ink)}.audit-pagination{text-align:center;padding:24px;font-size:11px;color:var(--muted)}.audit-pagination small{display:block;margin-top:12px}.audit-failure{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px;color:var(--red);font-size:12px;margin-bottom:18px}.audit-failure p{margin-bottom:0}
@media(max-width:1100px){.audit-filters{grid-template-columns:repeat(2,minmax(0,1fr))}.audit-row{grid-template-columns:155px minmax(0,1fr);gap:18px}.audit-open{grid-column:2;justify-self:start}}
@media(max-width:650px){.audit-search .page-heading{align-items:start;flex-direction:column;gap:16px}.audit-filters{padding:16px;gap:14px}.audit-actions{flex-wrap:wrap}.audit-summary{flex-direction:column;gap:5px}.audit-row{display:flex;flex-direction:column;padding:20px}.audit-event,.audit-subject{width:100%}.audit-event{display:grid;grid-template-columns:auto minmax(0,1fr);align-items:center}.audit-open{align-self:start}.audit-failure{align-items:start;flex-direction:column}}
</style>
