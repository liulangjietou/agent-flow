<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { EventRead, eventStatuses, eventReasons, eventRetryInput, eventError, type EventInboxPage, type EventInboxItem, type EventInboxHistory } from '../events'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ open: [id: string] }>()
const page = reactive(new EventRead<EventInboxPage>()), detail = reactive(new EventRead<EventInboxItem>()), history = reactive(new EventRead<EventInboxHistory>())
const beforeId = ref<string>(), beforeVersion = ref<number>(), confirmation = ref(false), reason = ref(''), error = ref(''), notice = ref('')
const saving = ref(false), unconfirmed = ref(false), requiresRefresh = ref(false), region = ref<HTMLElement | null>(null)
let epoch = 0, scope = ''
const loading = computed(() => page.loading || detail.loading || history.loading)
const blocked = computed(() => props.locked || saving.value || loading.value || unconfirmed.value || requiresRefresh.value)
function syncPending() { const pending = writeRequests.pending().some(item => item.path.startsWith('/integrations/events/')); if (unconfirmed.value && !pending) requiresRefresh.value = true; unconfirmed.value = pending }
const unsubscribe = writeRequests.subscribe(syncPending)
function cancel() { confirmation.value = false; reason.value = ''; error.value = '' }
async function load(cursor?: string) {
  if (saving.value) return
  cancel(); detail.clear(); history.clear(); beforeId.value = cursor
  const value = await page.load(props.scopeKey, signal => api.eventInbox(cursor, signal))
  if (value) { requiresRefresh.value = false; syncPending() }
}
async function inspect(id: string) {
  if (saving.value) return
  cancel(); history.clear(); beforeVersion.value = undefined
  const value = await detail.load(props.scopeKey, signal => api.eventInboxItem(id, signal))
  if (!value) return
  requiresRefresh.value = false; syncPending(); void loadHistory()
  const current = epoch; await nextTick(); if (current === epoch) region.value?.focus()
}
function loadHistory(cursor?: number) { const value = detail.value; if (!value || saving.value) return; beforeVersion.value = cursor; return history.load(props.scopeKey, signal => api.eventInboxHistory(value.id, cursor, signal)) }
function prepareRetry() { if (!blocked.value && detail.value?.status === 'REVIEW_REQUIRED') { cancel(); confirmation.value = true } }
async function retry() {
  const original = detail.value; if (!original || !confirmation.value || blocked.value) return
  let input
  try { input = eventRetryInput(original, reason.value) } catch (cause) { error.value = eventError(cause); return }
  const current = epoch; saving.value = true; error.value = ''
  try {
    await api.retryEvent(original.id, input)
    if (current !== epoch) return
    saving.value = false; requiresRefresh.value = false; cancel(); notice.value = '原事件已重新排队。当前处理结果以刷新后的原记录为准。'
    // 列表刷新期间换账号或卸载后，不能在新身份下继续打开旧原件。
    await load(); if (current !== epoch) return
    await inspect(original.id)
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = eventError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending() } }
}
watch(() => [props.scopeKey, props.refreshVersion], () => {
  if (scope === props.scopeKey && saving.value) return
  epoch++; scope = props.scopeKey; saving.value = false; page.clear(); detail.clear(); history.clear(); cancel(); notice.value = ''; requiresRefresh.value = false; syncPending(); void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { epoch++; page.clear(); detail.clear(); history.clear(); unsubscribe() })
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>
<template>
  <section class="event-workspace" aria-label="流程事件收件箱"><div class="page-heading"><div><h2>事件收件箱</h2><p>核对已接收事件的处理进度，查看原等待与恢复记录。</p></div><button class="secondary" :disabled="loading || saving" @click="load()">{{ beforeId ? '返回最新收件' : '刷新事件收件' }}</button></div>
    <div class="panel event-help"><strong>接收事件 → 核对原等待 → 继续流程</strong><p>已接收表示事件已经入库。“已推进等待”仍由后续节点继续办理，不能当作审批通过。</p></div>
    <p v-if="loading" role="status">正在读取事件记录…</p><p v-if="error || page.error || detail.error" role="alert" class="event-error">{{ error || page.error || detail.error }}</p><p v-if="notice" role="status" class="event-message">{{ notice }}</p><p v-if="unconfirmed" role="alert" class="event-error">上次恢复结果未确认，请在“未确认操作”中恢复原请求。</p><p v-else-if="requiresRefresh" role="status" class="event-message">请刷新原记录，核对当前状态后再办理。</p>
    <p v-if="page.value && !page.value.items.length" class="panel event-empty">{{ beforeId ? '没有更早的事件收件。' : '尚未收到已验证的流程事件。来源接入并启用后，收件将在这里显示。' }}</p>
    <ol v-if="page.value?.items.length" class="panel event-list" aria-label="事件接收记录"><li v-for="item in page.value.items" :key="item.id"><div class="event-state"><strong :class="{ 'event-error': item.status === 'REVIEW_REQUIRED' }">{{ eventStatuses[item.status] }}</strong><time>{{ time(item.receivedAt) }}</time></div><div class="event-route"><span>{{ item.sourceKey }}</span><span aria-hidden="true">→</span><span>{{ item.contractKey }} · v{{ item.contractVersion }}</span><small>事件 {{ item.eventId }}</small><p v-if="item.reason">{{ eventReasons[item.reason] }}</p></div><button class="secondary" :disabled="loading || saving" @click="inspect(item.id)">查看事件</button></li></ol>
    <button v-if="page.value?.nextBeforeId" class="secondary" :disabled="loading || saving" @click="load(page.value.nextBeforeId!)">更早事件收件</button>
    <section v-if="detail.value" ref="region" tabindex="-1" class="panel event-detail" aria-label="原事件处理详情"><div class="event-heading"><h3>原事件处理详情</h3><button class="quiet" :disabled="saving" @click="detail.clear(); history.clear(); cancel()">关闭事件详情</button></div><dl><dt>当前状态</dt><dd>{{ eventStatuses[detail.value.status] }} · 修订 {{ detail.value.version }}</dd><dt>事件编号</dt><dd>{{ detail.value.eventId }}</dd><dt>来源</dt><dd>{{ detail.value.sourceKey }} · 信任修订 {{ detail.value.trustRevision }}</dd><dt>引用契约</dt><dd>{{ detail.value.contractKey }} · v{{ detail.value.contractVersion }} · {{ detail.value.eventType }}</dd><dt>申请轮次</dt><dd>第 {{ detail.value.roundNo }} 轮 · {{ detail.value.applicationId }}</dd><dt>原等待编号</dt><dd>{{ detail.value.waitId }}</dd><dt>最后处理</dt><dd>{{ time(detail.value.updatedAt) }}</dd><template v-if="detail.value.nextAttemptAt"><dt>下次尝试</dt><dd>{{ time(detail.value.nextAttemptAt) }}</dd></template><dt>连续失败</dt><dd>{{ detail.value.failures }} 次</dd></dl><p v-if="detail.value.reason" class="event-message">{{ eventReasons[detail.value.reason] }}</p>
      <div class="event-actions"><button class="secondary" :disabled="loading || saving" @click="inspect(detail.value.id)">刷新原事件</button><button class="secondary" :disabled="saving || locked" @click="emit('open', detail.value.applicationId)">查看关联申请</button><button v-if="detail.value.status === 'REVIEW_REQUIRED' && !confirmation" class="primary" :disabled="blocked" @click="prepareRetry">重新处理原事件</button></div>
      <form v-if="confirmation" class="event-confirm" @submit.prevent="retry"><strong>确认重新处理这条原事件</strong><p>请先核对原来源配置和服务故障。恢复保留原事件、契约与等待编号，不能改投其他轮次；来源不匹配时仍会停止处理。</p><label>核对与恢复原因<textarea v-model="reason" required maxlength="500" rows="3" :disabled="saving || locked" /></label><div class="event-actions"><button type="button" class="secondary" :disabled="saving" @click="cancel">取消恢复</button><button type="submit" class="primary" :disabled="blocked || !reason.trim()">{{ saving ? '正在登记…' : '确认恢复原事件' }}</button></div></form>
      <h4>原事件处理历史</h4><p v-if="history.loading" role="status">正在读取处理历史…</p><p v-if="history.error" class="event-error" role="alert">{{ history.error }}</p><ol v-if="history.value" class="event-history"><li v-for="item in history.value.items" :key="item.version"><strong>{{ eventStatuses[item.status] }} · 修订 {{ item.version }}</strong><time>{{ time(item.updatedAt) }}</time><p v-if="item.reason">{{ eventReasons[item.reason] }}</p><p v-if="item.requestedBy">恢复依据：{{ item.requestedBy }} · {{ item.requestReason }}</p></li></ol><div class="event-actions"><button v-if="beforeVersion" class="quiet" :disabled="loading || saving" @click="loadHistory()">返回最新处理历史</button><button v-if="history.value?.nextBeforeVersion" class="secondary" :disabled="loading || saving" @click="loadHistory(history.value.nextBeforeVersion!)">更早处理历史</button></div>
    </section>
  </section>
</template>
<style src="../eventWorkspace.css"></style>
