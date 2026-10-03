<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { DeliveryPageQuery, deliveryChannels, deliveryStatuses, deliveryErrors, type NotificationDelivery, type NotificationDeliveryDetail, type NotificationDeliveryEvent } from '../notificationDeliveries'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const opened = ref(false), channel = ref(''), status = ref('')
let applied = { channel: '', status: '' }
const query = reactive(new DeliveryPageQuery<NotificationDelivery>((cursor, signal) => api.notificationDeliveries({ ...applied, limit: 20, ...(cursor ? { cursor } : {}) }, signal), item => item.id))
const selected = ref(''), detail = ref<NotificationDeliveryDetail | null>(null), loading = ref(false), stale = ref(true)
const history = reactive(new DeliveryPageQuery<NotificationDeliveryEvent>((cursor, signal) => api.notificationDeliveryHistory(selected.value, { limit: 20, ...(cursor ? { cursor } : {}) }, signal), item => item.version))
const detailError = ref(''), writeError = ref(''), notice = ref(''), reason = ref(''), confirmed = ref(false), acknowledgeDuplicate = ref(false), sending = ref(false)
const region = ref<HTMLElement | null>(null)
let generation = 0, writeGeneration = 0, controller: AbortController | null = null
const writeBusy = computed(() => props.locked || sending.value)
const canRetry = computed(() => !writeBusy.value && !loading.value && !stale.value && detail.value?.retry.allowed
  && reason.value.trim().length > 0 && reason.value.length <= 1000 && confirmed.value
  && (!detail.value.retry.requiresDuplicateAcknowledgement || acknowledgeDuplicate.value))
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
const blockedText = computed(() => detail.value?.retry.blockedCode === 'STATE_NOT_RETRYABLE' ? '当前状态不允许人工重试。'
  : detail.value?.retry.blockedCode ? deliveryErrors[detail.value.retry.blockedCode] : '')

function closeDetail() {
  generation++; controller?.abort(); controller = null; selected.value = ''; detail.value = null; loading.value = false; stale.value = true
  history.clear(); detailError.value = ''; writeError.value = ''; notice.value = ''; reason.value = ''; confirmed.value = false; acknowledgeDuplicate.value = false
}
function refresh() {
  closeDetail(); applied = { channel: channel.value, status: status.value }
  void query.load(props.scopeKey)
}
function toggle(event: Event) {
  opened.value = (event.target as HTMLDetailsElement).open
  if (opened.value) { if (!query.loaded && !query.loading) refresh() }
  else { closeDetail(); query.clear() }
}

/** 迟到详情、历史和焦点更新均绑定原身份与原投递。 */
async function inspect(id: string, preserveNotice = false) {
  const previousNotice = preserveNotice ? notice.value : ''
  closeDetail(); notice.value = previousNotice; selected.value = id
  const token = generation, scope = props.scopeKey, active = new AbortController()
  controller = active; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.notificationDelivery(id, active.signal), new Promise<never>((_, reject) => {
      timeout = setTimeout(() => { active.abort(); reject({ message: '读取投递详情超时，请重试。' }) }, 12_000)
    })])
    if (generation !== token || props.scopeKey !== scope) return
    detail.value = value; stale.value = false; history.seed(scope + '\n' + id, value.history)
    await nextTick()
    if (generation === token) { region.value?.focus({ preventScroll: true }); region.value?.scrollIntoView({ block: 'nearest' }) }
  } catch (cause) { if (generation === token && props.scopeKey === scope) detailError.value = (cause as ApiError)?.message ?? '无法读取投递详情，请重试。' }
  finally { clearTimeout(timeout); if (generation === token) { loading.value = false; controller = null } }
}

/** 未确认写入保持原输入，不自动生成另一个请求；成功后独立读取当前状态。 */
async function retry() {
  if (!canRetry.value || !detail.value) return
  const value = detail.value.delivery, token = generation, scope = props.scopeKey
  const writeToken = ++writeGeneration
  const input = { expectedVersion: value.version, reason: reason.value.trim(), acknowledgePossibleDuplicate: acknowledgeDuplicate.value }
  sending.value = true; confirmed.value = false; writeError.value = ''; notice.value = ''
  try {
    await api.retryNotificationDelivery(value.id, input)
    if (generation !== token || props.scopeKey !== scope) return
    notice.value = '已确认原重试请求，当前发送状态以重新读取的结果为准。'
    sending.value = false
    await Promise.all([query.load(scope), inspect(value.id, true)])
  } catch (cause) {
    if (generation !== token || props.scopeKey !== scope) return
    stale.value = true
    const failure = cause as ApiError
    writeError.value = failure.code === 'CONCURRENCY_CONFLICT' ? '投递已被其他操作更新，原输入仍保留。请重新读取后再决定。'
      : failure.message ?? '重试结果未确认，请使用页面上方的原操作恢复入口。'
  } finally { if (writeGeneration === writeToken && props.scopeKey === scope) sending.value = false }
}
watch(() => props.scopeKey, () => { writeGeneration++; closeDetail(); query.clear(); sending.value = false; channel.value = ''; status.value = ''; if (opened.value) refresh() }, { flush: 'sync' })
watch(() => props.refreshVersion, () => { if (opened.value) refresh(); else { closeDetail(); query.clear() } })
onUnmounted(() => { writeGeneration++; closeDetail(); query.clear() })
</script>

<template>
  <details class="delivery-panel" @toggle="toggle">
    <summary>我的外部投递<span>查看发送结果与恢复记录</span></summary>
    <div v-if="opened" class="delivery-body">
      <p class="delivery-note">这里只展示当前账号的外部提醒。“服务器已受理”不代表最终送达；站内消息始终保留。</p>
      <div class="delivery-filters">
        <label>渠道<select v-model="channel" :disabled="writeBusy" @change="refresh"><option value="">全部渠道</option><option v-for="(label, value) in deliveryChannels" :key="value" :value="value">{{ label }}</option></select></label>
        <label>状态<select v-model="status" :disabled="writeBusy" @change="refresh"><option value="">全部状态</option><option v-for="(label, value) in deliveryStatuses" :key="value" :value="value">{{ label }}</option></select></label>
        <button class="secondary" :disabled="query.loading || sending" @click="refresh">刷新记录</button>
      </div>
      <div v-if="query.error" class="delivery-error" role="alert"><p>{{ query.error }}</p><button class="secondary" :disabled="query.loading" @click="query.loaded ? query.more() : refresh()">重新读取列表</button></div>
      <p v-if="query.loading && !query.loaded" role="status">正在读取投递记录…</p>
      <p v-else-if="query.loaded && !query.items.length" class="delivery-empty">没有匹配的投递记录。开启偏好并接通渠道后，新消息才会产生外部提醒。</p>
      <ol class="delivery-list" aria-label="我的外部投递记录">
        <li v-for="item in query.items" :key="item.id" :class="{ selected: item.id === selected }">
          <div><strong>{{ deliveryChannels[item.channel] }} · {{ deliveryStatuses[item.status] }}</strong><p>创建于 {{ time(item.createdAt) }} · 已开始 {{ item.attempts }} 次发送</p><p v-if="item.errorCode" :class="{ warning: item.status === 'UNKNOWN' }">{{ deliveryErrors[item.errorCode] }}</p></div>
          <button class="secondary" :disabled="sending" @click="inspect(item.id)">查看投递详情</button>
        </li>
      </ol>
      <div v-if="query.loaded && query.items.length" class="delivery-footer"><span>已加载 {{ query.items.length }} 条</span><button v-if="query.nextCursor" class="secondary" :disabled="query.loading || sending" @click="query.more">{{ query.loading ? '正在加载…' : '加载更多投递' }}</button></div>
      <section v-if="selected" ref="region" class="delivery-detail" tabindex="-1" aria-labelledby="delivery-detail-title">
        <div class="delivery-detail-heading"><h3 id="delivery-detail-title">投递详情</h3><button class="quiet" :disabled="sending" @click="closeDetail">收起详情</button></div>
        <p v-if="loading" role="status">正在读取当前状态…</p>
        <p v-if="detailError" class="delivery-error" role="alert">{{ detailError }}</p>
        <template v-if="detail">
          <p class="delivery-current"><strong>{{ deliveryChannels[detail.delivery.channel] }} · {{ deliveryStatuses[detail.delivery.status] }}</strong><span>第 {{ detail.delivery.version }} 版 · {{ time(detail.delivery.updatedAt) }}</span></p>
          <p v-if="detail.delivery.status === 'ACCEPTED'" class="delivery-note">发送服务已经受理，最终递送结果还需通过对应渠道核实。</p>
          <p v-if="detail.delivery.errorCode" :class="detail.delivery.status === 'UNKNOWN' ? 'delivery-warning' : 'delivery-note'">{{ deliveryErrors[detail.delivery.errorCode] }}</p>
          <p v-if="detail.delivery.nextAttemptAt" class="delivery-note">下次尝试不早于 {{ time(detail.delivery.nextAttemptAt) }}。</p>
          <p v-if="stale" class="delivery-warning" role="status">上方是此前读取的状态，当前结果尚未确认。请重新读取；原写入结果未知时先恢复原操作。</p>
          <p v-if="!detail.retry.allowed" class="delivery-note">{{ blockedText }}</p>
          <form v-else class="delivery-retry" @submit.prevent="retry">
            <label>重试原因<textarea v-model="reason" maxlength="1000" rows="2" :disabled="writeBusy || stale || loading" placeholder="说明已经核实的失败或未知结果" /></label>
            <label class="delivery-check"><input v-model="confirmed" type="checkbox" :disabled="writeBusy || stale || loading" />我已核实原投递，确认重试这条提醒。</label>
            <label v-if="detail.retry.requiresDuplicateAcknowledgement" class="delivery-check delivery-warning"><input v-model="acknowledgeDuplicate" type="checkbox" :disabled="writeBusy || stale || loading" />接收方可能已经收到，我仍接受重复提醒并确认重试。</label>
            <button class="primary" type="submit" :disabled="!canRetry">{{ sending ? '正在提交…' : '确认重试' }}</button>
          </form>
          <h4>投递历史</h4>
          <ol class="delivery-history" aria-label="投递完整历史"><li v-for="item in history.items" :key="item.version"><strong>第 {{ item.version }} 版 · {{ deliveryStatuses[item.status] }}</strong><time :datetime="item.occurredAt">{{ time(item.occurredAt) }}</time><p v-if="item.errorCode">{{ deliveryErrors[item.errorCode] }}</p><p v-if="item.actor">{{ item.actor }}：{{ item.reason }}</p></li></ol>
          <p v-if="history.error" class="delivery-error" role="alert">{{ history.error }}</p>
          <button v-if="history.nextCursor" class="secondary" :disabled="history.loading || sending" @click="history.more">{{ history.loading ? '正在读取历史…' : '加载更早历史' }}</button>
          <p v-else class="delivery-note">已加载全部历史。</p>
        </template>
        <p v-if="writeError" class="delivery-error" role="alert">{{ writeError }}</p>
        <p v-if="notice" class="delivery-notice" role="status">{{ notice }}</p>
        <button class="secondary delivery-reload" :disabled="loading || sending" @click="inspect(selected)">重新读取当前投递</button>
      </section>
    </div>
  </details>
</template>

<style scoped>
.delivery-panel{margin:16px 0;border:1px solid var(--line);border-radius:10px;background:var(--paper);font-size:13px}.delivery-panel summary{padding:16px;cursor:pointer;color:var(--deep);font-weight:600}.delivery-panel summary span{margin-left:12px;font-size:11px;font-weight:400;color:var(--muted)}.delivery-body{padding:0 16px 18px;line-height:1.8}.delivery-note,.delivery-empty,.delivery-footer{color:var(--muted);font-size:12px}.delivery-filters{display:flex;flex-wrap:wrap;align-items:end;gap:12px;margin:15px 0}.delivery-filters label{display:flex;flex-direction:column;gap:5px;flex:1;min-width:130px}.delivery-filters select,.delivery-retry textarea{border:1px solid var(--line);border-radius:6px;padding:10px;background:var(--paper);color:var(--ink);font:inherit;max-width:100%;box-sizing:border-box}.delivery-list,.delivery-history{list-style:none;padding:0;margin:14px 0}.delivery-list li{display:flex;justify-content:space-between;align-items:center;gap:15px;border-top:1px solid var(--line);padding:16px 4px}.delivery-list li.selected{background:var(--soft)}.delivery-list li>div{min-width:0}.delivery-list strong{font-size:13px;color:var(--deep)}.delivery-list p{margin:5px 0 0;font-size:12px;color:var(--muted);overflow-wrap:anywhere}.delivery-list button{flex-shrink:0}.delivery-footer,.delivery-detail-heading{display:flex;justify-content:space-between;align-items:center;gap:10px}.delivery-detail{margin-top:20px;border:1px solid var(--line);border-radius:8px;padding:18px;overflow-wrap:anywhere}.delivery-detail h3{font-size:16px;margin:0}.delivery-detail h4{font-size:13px;margin:25px 0 10px}.delivery-current{display:flex;flex-wrap:wrap;gap:10px;align-items:center}.delivery-current span{font-size:11px;color:var(--muted)}.delivery-warning,.delivery-list p.warning{color:#865c16;background:#fbf5e8;padding:10px;border-radius:6px}.delivery-error{color:var(--red)}.delivery-notice{color:var(--deep)}.delivery-retry{display:flex;flex-direction:column;gap:12px;margin:16px 0}.delivery-retry>label:first-child{display:flex;flex-direction:column;gap:6px}.delivery-retry textarea{width:100%;resize:vertical}.delivery-check{display:flex;gap:8px;align-items:flex-start;font-size:12px}.delivery-check input{margin-top:5px;flex-shrink:0;accent-color:var(--teal)}.delivery-retry button{align-self:flex-start}.delivery-history li{padding:12px 0;border-top:1px solid var(--line);font-size:12px}.delivery-history time{display:block;color:var(--muted);font-size:11px}.delivery-history p{white-space:pre-wrap;overflow-wrap:anywhere;margin:5px 0;color:var(--muted)}.delivery-reload{margin-top:15px}
@media(max-width:650px){.delivery-panel summary span{display:block;margin:4px 0 0}.delivery-body{padding:0 12px 15px}.delivery-list li{align-items:flex-start;flex-direction:column;gap:10px}.delivery-detail{padding:13px}.delivery-filters label{min-width:110px}.delivery-detail-heading{align-items:flex-start}.delivery-current{display:block}.delivery-current span{display:block;margin-top:4px}}
</style>
