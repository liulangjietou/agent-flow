<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { WebhookQuery, WebhookRead, webhookStatuses, webhookEvents, webhookErrors, webhookRetryable, type WebhookTarget, type WebhookDetail, type WebhookFilters } from '../webhooks'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ open: [id: string] }>()
const fields = reactive({ target: '', status: '', applicationId: '' })
const query = reactive(new WebhookQuery((filters, signal) => api.webhookDeliveries(filters, signal)))
const targets = reactive(new WebhookRead<WebhookTarget[]>())
const detail = reactive(new WebhookRead<WebhookDetail>())
const selected = ref(''), confirmation = ref(false), sending = ref(false), writeError = ref(''), notice = ref(''), validation = ref(''), submitted = ref('')
const detailRegion = ref<HTMLElement | null>(null)
let epoch = 0
const changed = computed(() => submitted.value !== JSON.stringify(fields))
const targetLabel = (id: string) => targets.value?.find(target => target.id === id)?.label ?? id
const time = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
const errorLabel = (code?: string) => code ? webhookErrors[code] ?? (code.startsWith('HTTP_') ? '接收服务返回 ' + code.slice(5) : code) : ''
function refresh() {
  validation.value = ''; closeDetail()
  const app = fields.applicationId.trim()
  if (app && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(app)) { validation.value = '申请标识需要填写完整 UUID。'; query.clear(); return }
  const filters: WebhookFilters = { target: fields.target, status: fields.status }
  if (app) filters.applicationId = app
  submitted.value = JSON.stringify(fields)
  void targets.load(signal => api.webhookTargets(signal)); void query.load(props.scopeKey, filters)
}
function closeDetail() { epoch++; selected.value = ''; detail.clear(); confirmation.value = false; writeError.value = ''; notice.value = '' }
async function inspect(id: string) {
  closeDetail(); selected.value = id
  const generation = epoch
  void detail.load(signal => api.webhookDelivery(id, signal))
  await nextTick()
  if (generation === epoch) { detailRegion.value?.focus({ preventScroll: true }); detailRegion.value?.scrollIntoView({ block: 'start' }) }
}
async function retry() {
  const value = detail.value?.delivery
  if (!value || !confirmation.value || props.locked || sending.value || !webhookRetryable(value)) return
  const generation = epoch
  confirmation.value = false; sending.value = true; writeError.value = ''; notice.value = ''
  try {
    await api.retryWebhook(value.id, value.version)
    if (generation !== epoch) return
    notice.value = '已重新排队。发送结果请刷新查看。'
    await detail.load(signal => api.webhookDelivery(value.id, signal))
  } catch (cause) { if (generation === epoch) writeError.value = (cause as { message?: string })?.message ?? '操作结果未确认，请使用页面上方的恢复入口。' }
  finally { sending.value = false }
}
watch(() => props.scopeKey, () => { Object.assign(fields, { target: '', status: '', applicationId: '' }); refresh() }, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
onUnmounted(() => { closeDetail(); query.clear(); targets.clear() })
</script>

<template>
  <section class="content webhooks" :aria-busy="query.loading">
    <div class="page-heading"><div><p class="eyebrow">INTEGRATIONS / DELIVERY</p><h2>集成投递</h2><p class="subhead">查看审批事件的外部投递结果，定位失败并按需重试。</p></div><span class="admin-label">管理员视图</span></div>
    <div v-if="targets.error" class="feedback error" role="alert">目的地目录读取失败：{{ targets.error }}</div>
    <div v-else-if="targets.value && !targets.value.length" class="panel setup"><h3>尚未配置外部接收服务</h3><p>由部署管理员配置当前租户的接收地址与签名密钥。启用后仅投递新产生的审批事件，不补发历史记录。</p></div>
    <div v-else-if="targets.value" class="destinations"><span v-for="target in targets.value" :key="target.id"><i :class="{ enabled: target.enabled }"></i>{{ target.label }} <small>{{ target.enabled ? '已启用' : '已停用' }}</small></span></div>
    <form class="panel filters" @submit.prevent="refresh">
      <label>目的地标识<input v-model="fields.target" list="webhook-targets" maxlength="64" placeholder="全部目的地，包括历史配置" /><datalist id="webhook-targets"><option v-for="target in targets.value ?? []" :key="target.id" :value="target.id">{{ target.label }}</option></datalist></label>
      <label>投递状态<select v-model="fields.status"><option value="">全部状态</option><option v-for="(label, value) in webhookStatuses" :key="value" :value="value">{{ label }}</option></select></label>
      <label>申请标识<input v-model="fields.applicationId" maxlength="36" placeholder="完整申请 UUID" /></label>
      <button class="primary" :disabled="query.loading || sending">查询投递</button>
    </form>
    <p v-if="validation" class="feedback error" role="alert">{{ validation }}</p>
    <p v-if="changed && query.loaded" class="feedback" role="status">筛选已修改，下方仍显示上次查询结果。</p>
    <p class="help">自动重试最多 6 次尝试，失败不会改变审批结论。同一事件可能多次送达，接收服务须按事件 ID 去重；不保证事件到达顺序。“已送达”表示收到 HTTP 2xx，不代表外部业务处理完成。</p>
    <div v-if="query.error" class="panel failure" role="alert"><span>{{ query.error }}</span><button class="secondary" :disabled="query.loading || changed" @click="query.items.length ? query.more() : refresh()">重试查询</button></div>
    <p class="summary">事件时间倒序 · <span v-if="query.loaded">已加载 {{ query.items.length }} 条投递 · </span>时间按当前设备时区显示</p>
    <div v-if="query.loading && !query.items.length" class="panel empty" role="status">正在读取投递…</div>
    <div v-else-if="query.loaded && !query.items.length" class="panel empty"><h3>没有符合条件的投递</h3><p>可调整筛选，或在启用接收服务后提交新的审批。</p></div>
    <div v-if="query.items.length" class="panel results">
      <article v-for="item in query.items" :key="item.id" class="delivery-row" :class="{ selected: selected === item.id }">
        <div class="state"><span class="badge" :class="item.status.toLowerCase()">{{ webhookStatuses[item.status] ?? item.status }}</span><time>{{ time(item.occurredAt) }}</time><small>累计 {{ item.attempts }} 次尝试</small></div>
        <div class="subject"><h3>{{ webhookEvents[item.eventType] ?? item.eventType }} <span>→ {{ targetLabel(item.targetId) }}</span></h3><p>事件 {{ item.eventId }}</p><p v-if="item.errorCode" class="error">{{ errorLabel(item.errorCode) }}</p><p v-if="item.nextAttemptAt">下次尝试 {{ time(item.nextAttemptAt) }}</p></div>
        <button class="secondary" :disabled="sending" @click="inspect(item.id)">查看投递</button>
      </article>
    </div>
    <div v-if="query.items.length" class="pagination"><button v-if="query.nextCursor" class="secondary" :disabled="query.loading || changed" @click="query.more()">{{ query.loading ? '加载中…' : '加载更多投递' }}</button><span v-else>当前查询已无更多投递</span></div>
    <section v-if="selected" ref="detailRegion" tabindex="-1" class="panel detail" aria-label="投递详情" :aria-busy="detail.loading">
      <div class="detail-heading"><h3>投递详情</h3><button class="quiet" :disabled="sending" @click="closeDetail">关闭详情</button></div>
      <p v-if="detail.loading" role="status">正在读取详情…</p>
      <div v-else-if="detail.error" class="failure error" role="alert"><span>{{ detail.error }}</span><button class="secondary" @click="inspect(selected)">重试详情</button></div>
      <template v-if="detail.value">
        <dl><dt>事件 ID</dt><dd>{{ detail.value.delivery.eventId }}</dd><dt>目的地</dt><dd>{{ targetLabel(detail.value.delivery.targetId) }} · {{ detail.value.delivery.targetId }}</dd><dt>当前状态</dt><dd>{{ webhookStatuses[detail.value.delivery.status] }} · 版本 {{ detail.value.delivery.version }}</dd><dt>尝试次数</dt><dd>累计 {{ detail.value.delivery.attempts }} 次，本轮 {{ detail.value.delivery.cycleAttempts }} / 6 次</dd><dt>HTTP 状态</dt><dd>{{ detail.value.delivery.httpStatus ?? '未记录' }}</dd><dt>最后更新</dt><dd>{{ time(detail.value.delivery.updatedAt) }}</dd><dt>申请标识</dt><dd>{{ detail.value.delivery.applicationId }}</dd></dl>
        <p v-if="detail.value.delivery.errorCode" class="feedback error">{{ errorLabel(detail.value.delivery.errorCode) }}</p>
        <div class="actions"><button class="secondary" :disabled="locked || sending" @click="emit('open', detail.value.delivery.applicationId)">查看申请 ↗</button><button class="secondary" :disabled="sending" @click="inspect(selected)">刷新详情</button><button v-if="webhookRetryable(detail.value.delivery)" class="primary" :disabled="locked || sending || confirmation" @click="confirmation = true">重新排队</button></div>
        <div v-if="confirmation" class="confirm" role="group" aria-label="确认重新排队"><strong>重新发送这个事件？</strong><p>保留原事件 ID 和正文，并开始最多 6 次新的尝试。接收方可能已收到，请先核对外部处理结果。</p><div class="actions"><button class="secondary" @click="confirmation = false">取消重试</button><button class="primary" :disabled="locked || sending" @click="retry">确认重新排队</button></div></div>
        <p v-if="sending" role="status">正在重新排队…</p><p v-if="notice" class="feedback" role="status">{{ notice }}</p><p v-if="writeError" class="feedback error" role="alert">{{ writeError }}</p>
        <h4>最近 {{ detail.value.attempts.length }} 次尝试 <small>最多显示 50 次</small></h4>
        <p v-if="!detail.value.attempts.length" class="help">尚未尝试发送。</p>
        <ol class="attempts"><li v-for="attempt in detail.value.attempts" :key="attempt.attemptNo"><strong>#{{ attempt.attemptNo }} · {{ attempt.result === 'OUTCOME_UNKNOWN' ? '结果未知' : attempt.result === 'DELIVERED' ? '已送达' : attempt.result === 'IN_FLIGHT' ? '投递中' : '未成功确认' }}</strong><span>{{ time(attempt.startedAt) }} → {{ time(attempt.finishedAt) }}</span><small>{{ attempt.httpStatus ? 'HTTP ' + attempt.httpStatus : '' }} {{ errorLabel(attempt.errorCode) }}</small></li></ol>
        <h4>人工重试请求 <small>最近 20 条</small></h4><p v-if="!detail.value.retryRequests.length" class="help">没有人工重试记录。</p><ol class="attempts"><li v-for="(request, index) in detail.value.retryRequests" :key="index"><strong>{{ request.requestedBy }} · {{ time(request.requestedAt) }}</strong><span>原状态 {{ webhookStatuses[request.previousStatus] }} · 原版本 {{ request.previousVersion }}</span></li></ol>
      </template>
    </section>
  </section>
</template>

<style scoped>
.admin-label{font-size:11px;background:var(--soft);color:var(--deep);padding:8px 12px;border-radius:6px;white-space:nowrap}.setup{padding:22px;margin-bottom:20px}.setup h3{font-size:15px;margin:0 0 8px}.setup p,.help{font-size:12px;color:var(--muted);line-height:1.9}.destinations{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:20px}.destinations>span{padding:9px 12px;border:1px solid var(--line);border-radius:6px;font-size:12px}.destinations small{color:var(--muted);margin-left:6px}.destinations i{display:inline-block;width:6px;height:6px;background:#aaa;border-radius:50%;margin-right:8px}.destinations i.enabled{background:var(--deep)}.filters{display:grid;grid-template-columns:1fr 1fr 1.4fr auto;gap:16px;padding:22px;align-items:end}.filters label{min-width:0;display:flex;flex-direction:column;gap:8px;font-size:11px;color:var(--muted)}.filters input,.filters select{width:100%;min-width:0;height:40px;border:1px solid var(--line);border-radius:6px;padding:8px;background:white;color:var(--ink);font:inherit}.summary,.pagination{font-size:11px;color:var(--muted);margin:18px 0}.pagination{text-align:center}.delivery-row{display:grid;grid-template-columns:180px minmax(0,1fr) auto;gap:20px;padding:22px;align-items:center}.delivery-row+.delivery-row{border-top:1px solid var(--line)}.delivery-row.selected{background:var(--soft)}.state{display:flex;flex-direction:column;align-items:start;gap:8px}.state time,.state small,.subject p{font-size:11px;color:var(--muted);margin:0}.badge{font-size:11px;padding:5px 8px;border-radius:5px;background:#eef1f0;color:#52635b}.badge.delivered{color:var(--deep);background:#e6f3ec}.badge.failed{color:var(--red);background:#fff0ed}.badge.retry_wait{color:#8b682e;background:#fff4df}.subject{min-width:0;overflow-wrap:anywhere}.subject h3{font-size:13px;margin:0 0 10px}.subject h3 span{font-weight:400}.subject p+p{margin-top:8px}.empty{text-align:center;padding:38px;color:var(--muted);font-size:13px}.empty h3{color:var(--ink);font-size:15px}.failure{padding:18px;display:flex;gap:15px;align-items:center;justify-content:space-between;font-size:12px}.feedback,.confirm{font-size:12px;background:#f6f5ee;padding:14px;line-height:1.8;border-radius:6px}.error,.subject .error{color:var(--red)}.detail{padding:24px;margin-top:20px;overflow-wrap:anywhere}.detail-heading{display:flex;align-items:center;justify-content:space-between;gap:14px}.detail-heading h3{font-size:17px}.detail dl{display:grid;grid-template-columns:100px minmax(0,1fr);font-size:12px;line-height:2.2;gap:3px 15px}.detail dt{color:var(--muted)}.detail dd{margin:0}.actions{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}.detail h4{font-size:13px;margin-top:28px}.detail h4 small{font-size:10px;font-weight:400;color:var(--muted);margin-left:8px}.attempts{list-style:none;padding:0}.attempts li{display:flex;flex-direction:column;gap:7px;font-size:11px;padding:14px 0;border-top:1px solid var(--line)}.attempts span,.attempts small{color:var(--muted)}
@media(max-width:1100px){.filters{grid-template-columns:1fr 1fr}.delivery-row{grid-template-columns:150px minmax(0,1fr)}.delivery-row>button{grid-column:2;justify-self:start}}
@media(max-width:650px){.webhooks .page-heading{align-items:start;flex-direction:column;gap:16px}.filters{padding:16px}.delivery-row{display:flex;flex-direction:column;align-items:start;padding:18px}.detail{padding:18px}.detail dl{grid-template-columns:75px minmax(0,1fr)}.subject,.state{width:100%}.failure{align-items:start;flex-direction:column}}
</style>
