<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { callbackStatuses, callbackReasons, validateCallbackPage, validateCallbackDetail, callbackRetryInput, validateCallbackRetry, callbackError, type PaymentCallbackPage, type PaymentCallbackDetail } from '../paymentCallbacks'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const page = ref<PaymentCallbackPage | null>(null), detail = ref<PaymentCallbackDetail | null>(null)
const loading = ref(false), saving = ref(false), unconfirmed = ref(false), requiresRefresh = ref(false)
const error = ref(''), notice = ref(''), reason = ref(''), confirmation = ref(false), beforeId = ref<string | undefined>()
const region = ref<HTMLElement | null>(null)
let epoch = 0, controller: AbortController | null = null, activeScope = ''
const blocked = computed(() => props.locked || loading.value || saving.value || unconfirmed.value || requiresRefresh.value)
function syncPending() {
  const active = writeRequests.pending().some(entry => entry.path.startsWith('/integrations/payment/callbacks/'))
  if (unconfirmed.value && !active) requiresRefresh.value = true
  unconfirmed.value = active
}
const unsubscribe = writeRequests.subscribe(syncPending)
function stop() { epoch++; controller?.abort(); controller = null }
function begin() {
  stop(); const request = new AbortController(); controller = request; const current = epoch; loading.value = true; error.value = ''
  const timer = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '回调记录读取超时，请重新查询。' } }, 12_000)
  return { current, request, timer }
}
/** 翻页替换整页并清除旧详情，身份变化后迟到响应不能重新打开恢复操作。 */
async function load(cursor?: string) {
  if (saving.value || !props.scopeKey) return
  page.value = null; detail.value = null; confirmation.value = false; reason.value = ''; beforeId.value = cursor
  const read = begin()
  try {
    const value = validateCallbackPage(await api.paymentCallbacks(cursor, read.request.signal), cursor)
    if (read.current === epoch) { page.value = value; requiresRefresh.value = false; syncPending() }
  } catch (cause) { if (read.current === epoch) error.value = callbackError(cause) }
  finally { clearTimeout(read.timer); if (read.current === epoch) { loading.value = false; controller = null } }
}
async function inspect(id: string) {
  if (saving.value || !props.scopeKey) return
  detail.value = null; confirmation.value = false; reason.value = ''; notice.value = ''; const read = begin()
  try {
    const value = validateCallbackDetail(await api.paymentCallback(id, read.request.signal), id)
    if (read.current !== epoch) return
    detail.value = value; requiresRefresh.value = false; syncPending()
    await nextTick(); if (read.current === epoch) region.value?.focus()
  } catch (cause) { if (read.current === epoch) error.value = callbackError(cause) }
  finally { clearTimeout(read.timer); if (read.current === epoch) { loading.value = false; controller = null } }
}
function prepareRetry() {
  if (blocked.value || detail.value?.callback.status !== 'REVIEW_REQUIRED') return
  confirmation.value = true; reason.value = ''; error.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) region.value?.querySelector('textarea')?.focus() })
}
async function retry() {
  const original = detail.value?.callback
  if (!original || !confirmation.value || blocked.value) return
  let input
  try { input = callbackRetryInput(original, reason.value) } catch (cause) { error.value = callbackError(cause); return }
  const current = epoch; saving.value = true; error.value = ''
  try {
    const result = await api.retryPaymentCallback(original.id, input)
    if (current !== epoch) return
    validateCallbackRetry(result, original, input.reason); saving.value = false; confirmation.value = false
    notice.value = '原回调已重新排队。刷新查看处理进度，银行结果请在财务办理页核对。'; await load()
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = callbackError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending() } }
}
watch(() => JSON.stringify([props.scopeKey, props.refreshVersion]), () => {
  if (activeScope === props.scopeKey && saving.value) return
  activeScope = props.scopeKey; saving.value = false
  stop(); page.value = null; detail.value = null; beforeId.value = undefined; reason.value = ''; confirmation.value = false; error.value = ''; notice.value = ''; loading.value = false; requiresRefresh.value = false
  syncPending(); if (props.scopeKey && !saving.value) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe() })
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>

<template>
  <section class="callback-inbox" aria-label="支付回调收件箱">
    <div class="page-heading"><div><div class="eyebrow">PAYMENT CALLBACKS</div><h2>支付回调</h2><p>查看可信通知的接收与处理进度，按原交易核对银行结果。</p></div><button class="secondary" :disabled="loading || saving || locked" @click="notice = ''; load()">{{ beforeId ? '返回最新回调' : '刷新回调记录' }}</button></div>
    <div class="panel callback-help"><strong>回调接收 → 原号查询 → 银行事实</strong><p>“已接收”和“已登记原号查询”都是处理状态。到账、退回或争议仍由原交易查询确认，可在财务办理页查看。</p></div>
    <p v-if="loading" role="status" class="callback-message">正在读取回调记录…</p>
    <p v-if="error" role="alert" class="callback-error">{{ error }}</p><p v-if="notice" role="status" class="callback-message">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="callback-error">上次恢复结果未确认，请在未确认操作中恢复原请求后刷新。</p>
    <p v-else-if="requiresRefresh" role="status" class="callback-message">请刷新原记录，核对当前状态后继续。</p>
    <div v-if="page && !page.items.length" class="panel callback-empty">{{ beforeId ? '没有更早的回调记录。' : '尚未收到已验证的支付回调。只有部署配置启用后才会接收。' }}</div>
    <ol v-if="page?.items.length" class="panel callback-list" aria-label="回调接收记录">
      <li v-for="item in page.items" :key="item.id"><div class="callback-state"><strong :class="{ attention: item.status === 'REVIEW_REQUIRED' }">{{ callbackStatuses[item.status] }}</strong><time>{{ time(item.receivedAt) }}</time></div><div class="callback-subject"><h3>{{ item.kind === 'SUPPLIER' ? '供应商付款' : '员工付款' }}</h3><p>事件 {{ item.eventId }}</p><p v-if="item.reason">{{ callbackReasons[item.reason] }}</p></div><button class="secondary" :disabled="loading || saving" @click="inspect(item.id)">查看回调</button></li>
    </ol>
    <div v-if="page?.nextBeforeId" class="callback-pagination"><button class="secondary" :disabled="loading || saving || locked" @click="load(page.nextBeforeId!)">查看更早回调</button></div>
    <section v-if="detail" ref="region" tabindex="-1" class="panel callback-detail" aria-label="支付回调处理详情">
      <div class="callback-heading"><h3>处理详情</h3><button class="quiet" :disabled="loading || saving" @click="detail = null; confirmation = false; reason = ''">关闭详情</button></div>
      <dl><dt>当前状态</dt><dd>{{ callbackStatuses[detail.callback.status] }}</dd><dt>事件编号</dt><dd>{{ detail.callback.eventId }}</dd><dt>原授权编号</dt><dd>{{ detail.callback.authorizationId }}</dd><dt>发送方版本</dt><dd>{{ detail.callback.sourceRevision }}</dd><dt>最后处理</dt><dd>{{ time(detail.callback.updatedAt) }}</dd><template v-if="detail.callback.queryVersion"><dt>已排队修订</dt><dd>{{ detail.callback.queryVersion }} · 原付款查询版本</dd></template></dl>
      <p v-if="detail.callback.reason" class="callback-message">{{ callbackReasons[detail.callback.reason] }}</p>
      <div class="callback-actions"><button class="secondary" :disabled="loading || saving || locked" @click="inspect(detail.callback.id)">刷新原记录</button><button v-if="detail.callback.status === 'REVIEW_REQUIRED' && !confirmation" class="primary" :disabled="blocked" @click="prepareRetry">重新处理原回调</button></div>
      <form v-if="confirmation" class="callback-confirm" @submit.prevent="retry"><label>核对与恢复原因<textarea v-model="reason" rows="3" maxlength="500" :disabled="saving" placeholder="说明已核对的原交易、配置或服务故障" /></label><p>恢复后继续查询原交易，不会创建或重发付款。</p><div class="callback-actions"><button class="secondary" type="button" :disabled="saving" @click="confirmation = false; reason = ''">取消</button><button class="primary" type="submit" :disabled="blocked">{{ saving ? '正在登记…' : '确认重新处理' }}</button></div></form>
      <h4>最近 {{ detail.history.length }} 次处理 <small>最多显示 50 次</small></h4><ol class="callback-history"><li v-for="item in detail.history" :key="item.version"><strong>{{ callbackStatuses[item.status] }} · 修订 {{ item.version }}</strong><time>{{ time(item.updatedAt) }}</time><p v-if="item.reason">{{ callbackReasons[item.reason] }}</p><p v-if="item.requestedBy">{{ item.requestedBy }} · {{ item.requestReason }}</p></li></ol>
    </section>
  </section>
</template>

<style scoped>
.callback-inbox{min-width:0}.callback-help,.callback-detail{padding:24px}.callback-help strong{font-size:14px}.callback-help p,.callback-message,.callback-confirm p{font-size:12px;color:var(--muted);line-height:1.9}.callback-error,.attention{color:var(--red)}.callback-error{font-size:12px;line-height:1.8}.callback-empty{padding:34px;text-align:center;color:var(--muted);font-size:13px}.callback-list{list-style:none;padding:0;margin:20px 0}.callback-list>li{display:grid;grid-template-columns:175px minmax(0,1fr) auto;gap:20px;padding:22px;align-items:center}.callback-list>li+li{border-top:1px solid var(--line)}.callback-state{display:flex;flex-direction:column;gap:10px;font-size:12px}.callback-state time,.callback-subject p{color:var(--muted);font-size:11px;line-height:1.8}.callback-subject{min-width:0;overflow-wrap:anywhere}.callback-subject h3{font-size:13px;margin:0}.callback-subject p{margin:8px 0 0}.callback-pagination{text-align:center;margin:18px}.callback-detail{margin-top:24px;overflow-wrap:anywhere}.callback-heading,.callback-actions{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.callback-heading{justify-content:space-between}.callback-heading h3{font-size:17px}.callback-actions{margin:16px 0}.callback-detail dl{display:grid;grid-template-columns:100px minmax(0,1fr);gap:10px 16px;font-size:12px;line-height:1.8}.callback-detail dt{color:var(--muted)}.callback-detail dd{margin:0}.callback-confirm{padding:18px;background:var(--soft);border-radius:8px}.callback-confirm label{display:flex;flex-direction:column;gap:10px;font-size:12px}.callback-confirm textarea{width:100%;resize:vertical;min-height:88px;box-sizing:border-box;padding:12px;background:white;border:1px solid var(--line);border-radius:6px;font:inherit;color:var(--ink)}.callback-history{list-style:none;padding:0}.callback-history li{display:flex;flex-direction:column;gap:8px;padding:16px 0;border-top:1px solid var(--line);font-size:12px}.callback-history time,.callback-history p{font-size:11px;color:var(--muted);margin:0;line-height:1.8}.callback-detail h4{font-size:13px;margin-top:26px}.callback-detail h4 small{font-weight:400;color:var(--muted);margin-left:8px}.callback-detail:focus-visible{outline:2px solid var(--deep);outline-offset:3px}
@media(max-width:750px){.callback-list>li{display:flex;flex-direction:column;align-items:flex-start;gap:14px}.callback-inbox .page-heading{align-items:flex-start;flex-direction:column;gap:16px}.callback-help,.callback-detail{padding:18px}.callback-detail dl{grid-template-columns:80px minmax(0,1fr)}.callback-subject,.callback-state{width:100%}}
</style>
