<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, type ApiError, type HistoryEvent, type HistoryQuery } from '../api'

const props = defineProps<{ applicationId: string; mode: 'timeline' | 'audit'; roundNoMax: number; version: number }>()
const events = ref<HistoryEvent[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
const selectedRound = ref('')
const selectedAction = ref('')
const from = ref('')
const to = ref('')
const applied = ref<HistoryQuery>({})
const historyRoot = ref<HTMLElement | null>(null)
const refreshButton = ref<HTMLButtonElement | null>(null)
let requestSequence = 0
const isAudit = computed(() => props.mode === 'audit')
const heading = computed(() => isAudit.value ? '操作审计' : '审批轨迹')
const actionOptions = [
  ['CREATE', '创建草稿'], ['EXPENSE_REDUCE', '费用核减'], ['REVISE', '修改申请'], ['SUBMIT', '提交申请'], ['WITHDRAW', '撤回申请'], ['CANCEL', '作废申请'],
  ['CLAIM', '领取任务'], ['RELEASE', '释放任务'], ['TRANSFER', '转交任务'], ['DELEGATE', '委派任务'], ['RESOLVE', '回交任务'],
  ['INSTANCE_PAUSE', '暂停审批'], ['INSTANCE_RESUME', '恢复审批'], ['INSTANCE_TERMINATE', '终止审批'],
  ['TIMER_ELAPSED', '定时等待已到期'], ['TIMER_FAILED', '定时推进失败'], ['TIMER_RETRY', '重试原定时等待'],
  ['EVENT_RECEIVED', '事件已推进等待'],
  ['SELF_APPROVAL_ESCALATED', '自审批已上溯直属主管'],
  ['SERVICE_TASK_COMPLETED', '服务任务已完成'],
  ['SUBPROCESS_COMPLETED', '子审批完成并接续'],
  ['SUBPROCESS_STOPPED', '父子审批停止联动'],
  ['ADD_SIGNER', '增加会签人'], ['REMOVE_SIGNER', '移除会签人'],
  ['RETURN', '退回申请'], ['REJECT', '驳回申请'], ['APPROVE', '审批通过']
]
const actionLabels: Record<string, string> = Object.fromEntries([
  ...actionOptions, ['NODE_STARTED', '进入节点'], ['NODE_ENDED', '节点结束'],
  ['ROUND_RETURNED', '本轮已退回'], ['ROUND_REJECTED', '本轮已驳回'],
  ['ROUND_APPROVED', '本轮已批准'], ['ROUND_WITHDRAWN', '本轮已撤回']
])
const sourceLabels: Record<string, string> = {
  APPLICATION_AUDIT: '申请操作', TASK_AUDIT: '审批操作',
  PROCESS_HISTORY: '流程节点', SUBMISSION_SNAPSHOT: '轮次记录'
}
const stateLabels: Record<string, string> = {
  DRAFT: '草稿', IN_APPROVAL: '审批中', RETURNED: '已退回', REJECTED: '已驳回',
  WITHDRAWN: '已撤回', APPROVED: '已批准', CANCELLED: '已作废', REVOKED: '已撤销'
}
const stateLabel = (value: string) => stateLabels[value] ?? value
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
const actionLabel = (event: HistoryEvent) => actionLabels[event.action] ?? event.action
const eventTitle = (event: HistoryEvent) => event.source === 'PROCESS_HISTORY' && event.nodeName
  ? event.nodeName + ' · ' + actionLabel(event) : actionLabel(event)
const sourceLabel = (event: HistoryEvent) => sourceLabels[event.source] ?? '历史记录'

async function load(more = false) {
  const focusBefore = historyRoot.value?.contains(document.activeElement) ? document.activeElement as HTMLElement : null
  const sequence = ++requestSequence
  loading.value = true; error.value = ''
  if (!more) { events.value = []; cursor.value = null }
  const query = { ...applied.value, limit: 50, ...(more && cursor.value ? { cursor: cursor.value } : {}) }
  try {
    const result = isAudit.value
      ? await api.applicationAudit(props.applicationId, query)
      : await api.applicationTimeline(props.applicationId, query)
    // 快速切换申请或视图时，旧请求不能覆盖当前申请的历史。
    if (sequence !== requestSequence) return
    events.value = more ? [...events.value, ...result.items] : result.items
    cursor.value = result.nextCursor ?? null
  } catch (cause) {
    if (sequence !== requestSequence) return
    const failure = cause as ApiError
    error.value = failure.status === 404 || failure.status === 403
      ? '当前申请的历史记录不可访问，请重新加载或稍后重试。'
      : failure.status === 401 ? '登录已失效，请重新登录后查看。'
        : failure.message || '历史记录加载失败，请重试。'
  } finally {
    if (sequence === requestSequence) {
      loading.value = false
      await nextTick()
      if (sequence === requestSequence && focusBefore && (document.activeElement === document.body || document.activeElement === focusBefore)) {
        (focusBefore.isConnected ? focusBefore : refreshButton.value)?.focus()
      }
    }
  }
}

function applyFilters() {
  const start = from.value ? new Date(from.value) : null
  const end = to.value ? new Date(to.value) : null
  if ((start && !Number.isFinite(start.getTime())) || (end && !Number.isFinite(end.getTime()))) {
    error.value = '请输入有效的起止时间。'; return
  }
  if (start && end && start > end) { error.value = '开始时间不能晚于截止时间。'; return }
  applied.value = {
    ...(selectedRound.value ? { roundNo: Number(selectedRound.value) } : {}),
    ...(isAudit.value && selectedAction.value ? { action: selectedAction.value } : {}),
    ...(isAudit.value && start ? { from: start.toISOString() } : {}),
    ...(isAudit.value && end ? { to: end.toISOString() } : {})
  }
  void load()
}

watch(() => [props.applicationId, props.mode, props.version], () => {
  selectedRound.value = ''; selectedAction.value = ''; from.value = ''; to.value = ''; applied.value = {}
  void load()
}, { immediate: true })
onUnmounted(() => { requestSequence++ })
</script>

<template>
  <section ref="historyRoot" class="application-history" :aria-label="heading" :aria-busy="loading">
    <div class="history-heading"><div><h3>{{ heading }}</h3><p>{{ isAudit ? '实际保存的操作记录，最新记录在前。' : '按发生时间查看流程节点与申请操作。' }}</p></div><button ref="refreshButton" type="button" class="secondary" :disabled="loading" @click="load()">刷新记录</button></div>
    <form class="history-filters" @submit.prevent="applyFilters">
      <fieldset :disabled="loading">
        <div class="history-filter-row">
          <label>审批轮次<select v-model="selectedRound"><option value="">全部轮次</option><option v-for="round in roundNoMax" :key="round" :value="String(round)">第 {{ round }} 轮</option></select></label>
          <label v-if="isAudit">操作类型<select v-model="selectedAction"><option value="">全部操作</option><option v-for="[value, label] in actionOptions" :key="value" :value="value">{{ label }}</option></select></label>
        </div>
        <div v-if="isAudit" class="history-filter-row"><label>开始时间<input v-model="from" type="datetime-local" /></label><label>截止时间<input v-model="to" type="datetime-local" /></label></div>
        <div class="history-filter-actions"><span v-if="isAudit">时间按当前设备时区输入</span><button type="submit" class="secondary">应用筛选</button></div>
      </fieldset>
    </form>
    <p v-if="error" class="history-error" role="alert">{{ error }}</p>
    <p v-if="loading && !events.length" class="history-empty" role="status">正在加载{{ heading }}…</p>
    <div v-else-if="!events.length && !error" class="history-empty"><strong>暂无符合条件的记录</strong><p>可调整筛选后重试。早期版本未记录的操作不会补造为历史。</p></div>
    <ol v-if="events.length" class="history-events">
      <li v-for="event in events" :key="event.id" :class="{ 'node-event': event.source === 'PROCESS_HISTORY' }">
        <span class="history-dot" aria-hidden="true">{{ event.source === 'PROCESS_HISTORY' ? '·' : '↳' }}</span>
        <article>
          <div class="history-event-heading"><strong>{{ eventTitle(event) }}</strong><span v-if="event.roundNo" class="history-round">第 {{ event.roundNo }} 轮</span></div>
          <p class="history-event-meta"><time :datetime="event.occurredAt">{{ timeLabel(event.occurredAt) }}</time><span>{{ sourceLabel(event) }}</span></p>
          <p v-if="event.actor || event.targetUser" class="history-actor"><template v-if="event.actor">操作人 {{ event.actor }}</template><template v-if="event.targetUser"> → {{ event.targetUser }}</template></p>
          <p v-if="event.proxyUse" class="history-actor">代理 {{ event.proxyUse.principal }} 办理 · 授权核对时间 {{ timeLabel(event.proxyUse.authorizedAt) }}</p>
          <p v-if="event.currentStatus && event.currentStatus !== event.previousStatus" class="history-transition"><template v-if="event.previousStatus">{{ stateLabel(event.previousStatus) }} → </template>{{ stateLabel(event.currentStatus) }}</p>
          <p v-if="event.comment" class="history-comment">{{ event.comment }}</p>
          <p v-if="event.membershipChange" class="history-transition">必要审批人数 {{ event.membershipChange.totalBefore }} → {{ event.membershipChange.totalAfter }}，已同意 {{ event.membershipChange.completed }} 人；本次操作不产生审批意见。</p>
          <p v-if="event.nodeName && event.source !== 'PROCESS_HISTORY'" class="history-node">{{ event.nodeName }}</p>
          <details v-if="isAudit" class="history-reference"><summary>查看记录标识</summary><dl><dt>记录标识</dt><dd>{{ event.id }}</dd><template v-if="event.aggregateVersion != null"><dt>申请版本</dt><dd>{{ event.aggregateVersion }}</dd></template><template v-if="event.taskId"><dt>任务标识</dt><dd>{{ event.taskId }}</dd></template><template v-if="event.proxyUse"><dt>代理标识</dt><dd>{{ event.proxyUse.proxyId }}</dd><dt>代理修订</dt><dd>{{ event.proxyUse.revision }}</dd></template></dl></details>
        </article>
      </li>
    </ol>
    <div class="history-footer"><span>已显示 {{ events.length }} 条</span><button v-if="cursor" type="button" class="secondary" :disabled="loading" @click="load(true)">{{ loading ? '加载中…' : '加载更多' }}</button><button v-else-if="error" type="button" class="secondary" :disabled="loading" @click="load()">重试加载</button></div>
    <p class="history-note">节点结束仅表示流程离开该节点；批准、退回等结论以操作审计和轮次记录为准。记录缺失时不推测历史操作人或接收人。</p>
  </section>
</template>

<style scoped>
.application-history{padding:20px 0;color:var(--ink)}
.history-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:14px}.history-heading h3{font-size:15px;margin:0 0 7px}.history-heading p{font-size:11px;line-height:1.7;color:var(--muted);margin:0}
.history-heading button{font-size:11px;flex-shrink:0}
.history-filters{margin:18px 0;background:var(--paper);border:1px solid var(--line);padding:14px;border-radius:10px}
.history-filter-row{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.history-filter-row label{font-size:11px;margin-bottom:12px}.history-filter-row input,.history-filter-row select{width:100%;min-width:0;font-size:12px}
.history-filter-actions{display:flex;justify-content:flex-end;align-items:center;gap:12px}.history-filter-actions span{font-size:10px;color:var(--muted);margin-right:auto}.history-filter-actions button{font-size:11px}
.history-error{padding:12px;border-radius:8px;background:#fff0ed;color:var(--red);font-size:12px;line-height:1.7}
.history-empty{padding:24px 10px;text-align:center;font-size:12px;color:var(--muted);line-height:1.8}.history-empty strong{color:var(--ink);font-weight:500}.history-empty p{margin:8px 0}
.history-events{list-style:none;padding:0;margin:24px 0}.history-events li{position:relative;display:flex;gap:14px;padding:0 0 22px}
.history-events li:not(:last-child)::before{content:'';position:absolute;left:11px;top:23px;bottom:0;width:1px;background:var(--line)}
.history-dot{width:23px;height:23px;flex:0 0 23px;display:grid;place-items:center;border-radius:50%;background:var(--soft);color:var(--deep);font-size:11px}.node-event .history-dot{background:var(--paper);border:1px solid var(--line);color:var(--muted)}
.history-events article{min-width:0;flex:1;padding-top:2px}.history-event-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}.history-event-heading strong{font-size:12px;font-weight:600;overflow-wrap:anywhere}.node-event .history-event-heading strong{font-weight:400;color:var(--muted)}
.history-round{font-size:10px;white-space:nowrap;color:var(--muted)}.history-event-meta{display:flex;flex-wrap:wrap;gap:5px 12px;font-size:10px;color:var(--muted);margin:8px 0}.history-actor,.history-node{font-size:11px;margin:8px 0;overflow-wrap:anywhere}.history-node{color:var(--muted)}
.history-comment{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--paper);border-radius:8px;padding:11px 13px;font-size:12px;line-height:1.8;margin:10px 0}
.history-transition{font-size:11px;color:var(--deep);margin:9px 0}
.history-reference{font-size:10px;color:var(--muted);margin-top:10px}.history-reference summary{cursor:pointer}.history-reference dl{display:grid;grid-template-columns:70px minmax(0,1fr);gap:7px}.history-reference dd{margin:0;overflow-wrap:anywhere;font-family:'DM Mono',monospace}
.history-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:11px;color:var(--muted)}.history-footer button{font-size:11px}
.history-note{border-top:1px solid var(--line);padding-top:14px;margin-top:18px;font-size:10px;line-height:1.8;color:var(--muted)}
summary:focus-visible{outline:3px solid rgba(33,173,159,.35);outline-offset:2px}
@media(max-width:650px){.history-filter-row{grid-template-columns:1fr;gap:0}.history-heading{gap:9px}.history-heading button{padding:8px 10px}.history-filter-actions{flex-wrap:wrap}.history-filter-actions span{width:100%}}
</style>
