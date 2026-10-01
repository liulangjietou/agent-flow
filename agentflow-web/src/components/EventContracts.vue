<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { EventRead, eventPublication, eventAvailabilityInput, eventError, type EventDirectory, type EventVersions, type EventContract, type EventContractHistory, type EventPublication, type EventOption } from '../events'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const directory = reactive(new EventRead<EventDirectory>()), versions = reactive(new EventRead<EventVersions>()), detail = reactive(new EventRead<EventContract>()), history = reactive(new EventRead<EventContractHistory>())
const selectedKey = ref(''), afterKey = ref<string>(), beforeVersion = ref<number>(), beforeRevision = ref<number>()
const editor = ref<(EventPublication & { key: string }) | null>(null), availability = ref<boolean | null>(null), explanation = ref('')
const saving = ref(false), unconfirmed = ref(false), requiresRefresh = ref(false), error = ref(''), notice = ref(''), region = ref<HTMLElement | null>(null)
let epoch = 0, scope = ''
const loading = computed(() => directory.loading || versions.loading || detail.loading || history.loading)
const blocked = computed(() => props.locked || saving.value || loading.value || unconfirmed.value || requiresRefresh.value)
function syncPending() { const pending = writeRequests.pending().some(item => item.path.startsWith('/event-contracts/')); if (unconfirmed.value && !pending) requiresRefresh.value = true; unconfirmed.value = pending }
const unsubscribe = writeRequests.subscribe(syncPending)
function closeEditor() { editor.value = null; availability.value = null; explanation.value = ''; error.value = '' }
function clearReads() { directory.clear(); versions.clear(); detail.clear(); history.clear() }
async function load(cursor?: string) {
  if (saving.value) return
  closeEditor(); versions.clear(); detail.clear(); history.clear(); selectedKey.value = ''; afterKey.value = cursor
  const value = await directory.load(props.scopeKey, signal => api.eventContracts(cursor, signal))
  if (value) { requiresRefresh.value = false; syncPending() }
}
async function selectKey(key: string, cursor?: number) {
  if (saving.value) return
  closeEditor(); detail.clear(); history.clear(); selectedKey.value = key; beforeVersion.value = cursor
  await versions.load(props.scopeKey, signal => api.eventContractVersions(key, cursor, signal))
}
async function inspect(option: EventOption) {
  if (saving.value) return
  closeEditor(); history.clear(); beforeRevision.value = undefined
  const value = await detail.load(props.scopeKey, signal => api.eventContract(option.key, option.version, signal))
  if (!value) return
  requiresRefresh.value = false; syncPending(); void loadHistory()
  const current = epoch; await nextTick(); if (current === epoch) region.value?.focus()
}
function loadHistory(cursor?: number) {
  const value = detail.value; if (!value || saving.value) return
  beforeRevision.value = cursor; return history.load(props.scopeKey, signal => api.eventContractHistory(value.key, value.version, cursor, signal))
}
/** 新版先读取最新发布事实；查看旧版不会误把旧版号当作下一版的基础。 */
async function preparePublication(option?: EventOption) {
  if (blocked.value) return
  closeEditor(); notice.value = ''; const current = epoch
  if (!option) { editor.value = { key: '', expectedVersion: 0, name: '', sourceKey: '', eventType: '', reason: '' }; return }
  const page = await versions.load(props.scopeKey, signal => api.eventContractVersions(option.key, undefined, signal))
  const latest = page?.items[0]; if (current !== epoch || !latest) return
  selectedKey.value = latest.key; beforeVersion.value = undefined
  editor.value = { key: latest.key, expectedVersion: latest.version, name: latest.name, sourceKey: latest.sourceKey, eventType: latest.eventType, reason: '' }
}
async function publish() {
  if (!editor.value || blocked.value) return
  const key = editor.value.key; let input: EventPublication
  try { input = eventPublication(key, editor.value) } catch (cause) { error.value = eventError(cause); return }
  const current = epoch; saving.value = true; error.value = ''
  try {
    const result = await api.publishEventContract(key, input)
    if (current !== epoch) return
    saving.value = false; requiresRefresh.value = false; closeEditor(); notice.value = `已发布 ${result.name} v${result.version}。设计器可明确选择此版本。`
    // 写入后的每一步刷新也属于原身份；中途换账号或卸载后停止接续。
    await load(); if (current !== epoch) return
    await selectKey(key); if (current !== epoch) return
    await inspect({ ...result, enabled: result.availability.enabled, availabilityRevision: result.availability.revision })
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = eventError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending() } }
}
function prepareAvailability() { if (!blocked.value && detail.value) { closeEditor(); availability.value = !detail.value.availability.enabled } }
async function changeAvailability() {
  const value = detail.value; if (!value || availability.value === null || blocked.value) return
  let input
  try { input = eventAvailabilityInput(value, availability.value, explanation.value) } catch (cause) { error.value = eventError(cause); return }
  const current = epoch; saving.value = true; error.value = ''
  try {
    const result = await api.changeEventAvailability(value.key, value.version, input)
    if (current !== epoch) return
    saving.value = false; requiresRefresh.value = false; closeEditor(); notice.value = `已${result.availability.enabled ? '恢复' : '停用'} ${result.name} v${result.version}。`
    await load(); if (current !== epoch) return
    await selectKey(value.key); if (current !== epoch) return
    await inspect({ ...result, enabled: result.availability.enabled, availabilityRevision: result.availability.revision })
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = eventError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending() } }
}
watch(() => [props.scopeKey, props.refreshVersion], () => {
  if (scope === props.scopeKey && saving.value) return
  epoch++; scope = props.scopeKey; saving.value = false; clearReads(); closeEditor(); notice.value = ''; requiresRefresh.value = false; syncPending(); void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { epoch++; clearReads(); unsubscribe() })
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>
<template>
  <section class="event-workspace" aria-label="事件契约管理">
    <div class="page-heading"><div><h2>事件契约</h2><p>明确哪些来源、哪类事件可以被流程引用。每次发布保留独立版本。</p></div><div class="event-actions"><button class="secondary" :disabled="loading || saving" @click="load()">{{ afterKey ? '返回目录首页' : '刷新事件目录' }}</button><button class="primary" :disabled="blocked" @click="preparePublication()">发布新事件</button></div></div>
    <p v-if="error || directory.error || versions.error || detail.error" role="alert" class="event-error">{{ error || directory.error || versions.error || detail.error }}</p><p v-if="notice" role="status" class="event-message">{{ notice }}</p>
    <p v-if="unconfirmed" role="alert" class="event-error">上次事件操作结果未确认，请先在“未确认操作”中恢复原请求。</p><p v-else-if="requiresRefresh" role="status" class="event-message">请刷新当前记录，核对版本后再办理。</p>
    <form v-if="editor" class="panel event-editor" aria-label="发布事件版本" @submit.prevent="publish"><h3>{{ editor.expectedVersion ? `发布 ${editor.key} 的 v${editor.expectedVersion + 1}` : '发布新事件的 v1' }}</h3><fieldset :disabled="saving || locked || unconfirmed || requiresRefresh"><div class="event-fields"><label>事件标识<input v-model="editor.key" required maxlength="64" :disabled="editor.expectedVersion > 0" placeholder="例如 goods-accepted" /></label><label>事件名称<input v-model="editor.name" required maxlength="200" /></label><label>来源标识<input v-model="editor.sourceKey" required maxlength="64" placeholder="与已配置来源保持一致" /></label><label>事件类型<input v-model="editor.eventType" required maxlength="128" placeholder="例如 GoodsAccepted" /></label></div><label>发布原因<textarea v-model="editor.reason" required maxlength="2000" rows="3" /></label><p>发布后正文保持不变。来源是否可接收事件仍由部署配置决定，已有流程继续引用原版本。</p></fieldset><div class="event-actions"><button type="button" class="secondary" :disabled="saving" @click="closeEditor">取消发布</button><button class="primary" type="submit" :disabled="blocked">{{ saving ? '正在发布…' : '确认发布此版本' }}</button></div></form>
    <p v-if="directory.loading" role="status">正在读取事件目录…</p><p v-if="directory.value && !directory.value.items.length" class="panel event-empty">当前没有已发布事件。填写明确的来源和事件类型后，发布第一个版本。</p>
    <ol v-if="directory.value?.items.length" class="panel event-list" aria-label="已发布事件目录"><li v-for="item in directory.value.items" :key="item.key"><div class="event-state"><strong>{{ item.name }}</strong><small>最新 v{{ item.version }} · {{ item.enabled ? '已启用' : '已停用' }}</small></div><div class="event-route"><span>{{ item.sourceKey }}</span><span aria-hidden="true">→</span><span>{{ item.eventType }}</span><small>{{ item.key }}</small></div><div class="event-actions"><button class="secondary" :disabled="loading || saving" @click="selectKey(item.key)">查看版本</button><button class="quiet" :disabled="blocked" @click="preparePublication(item)">发布新版本</button></div></li></ol>
    <button v-if="directory.value?.nextAfterKey" class="secondary" :disabled="loading || saving" @click="load(directory.value.nextAfterKey!)">下一页事件</button>
    <section v-if="selectedKey" class="panel event-detail" aria-label="事件发布版本"><h3>{{ selectedKey }} 的发布版本</h3><p v-if="versions.loading" role="status">正在读取发布版本…</p><div class="event-version-list"><button v-for="item in versions.value?.items ?? []" :key="item.version" class="secondary" :aria-pressed="detail.value?.version === item.version" :disabled="loading || saving" @click="inspect(item)">v{{ item.version }} · {{ item.name }} · {{ item.enabled ? '已启用' : '已停用' }}</button></div><div class="event-actions"><button v-if="beforeVersion" class="quiet" :disabled="loading || saving" @click="selectKey(selectedKey)">返回最新版本</button><button v-if="versions.value?.nextBeforeVersion" class="quiet" :disabled="loading || saving" @click="selectKey(selectedKey, versions.value.nextBeforeVersion!)">更早发布版本</button></div></section>
    <section v-if="detail.value" ref="region" tabindex="-1" class="panel event-detail" aria-label="事件版本详情"><h3>{{ detail.value.name }} · v{{ detail.value.version }}</h3><dl><dt>事件标识</dt><dd>{{ detail.value.key }}</dd><dt>来源和类型</dt><dd>{{ detail.value.sourceKey }} → {{ detail.value.eventType }}</dd><dt>发布记录</dt><dd>{{ detail.value.publishedBy }} · {{ time(detail.value.publishedAt) }}</dd><dt>发布原因</dt><dd>{{ detail.value.publicationReason }}</dd><dt>当前状态</dt><dd>{{ detail.value.availability.enabled ? '已启用' : '已停用' }} · 启停修订 {{ detail.value.availability.revision }}</dd></dl>
      <div class="event-actions"><button class="secondary" :disabled="loading || saving" @click="inspect({ ...detail.value, enabled: detail.value.availability.enabled, availabilityRevision: detail.value.availability.revision })">刷新此版本</button><button class="secondary" :disabled="blocked" @click="prepareAvailability">{{ detail.value.availability.enabled ? '停用此版本' : '恢复此版本' }}</button></div>
      <form v-if="availability !== null" class="event-confirm" @submit.prevent="changeAvailability"><strong>确认{{ availability ? '恢复' : '停用' }} {{ detail.value.key }} v{{ detail.value.version }}</strong><p>{{ availability ? '原本等待此版本的流程可以继续处理事件。' : '此版本不能再用于发布或发起；在审等待保留，恢复原版本后接续。' }}</p><label>操作原因<textarea v-model="explanation" required maxlength="2000" rows="3" :disabled="saving || locked" /></label><div class="event-actions"><button type="button" class="secondary" :disabled="saving" @click="closeEditor">取消操作</button><button type="submit" class="primary" :disabled="blocked || !explanation.trim()">确认{{ availability ? '恢复' : '停用' }}原版本</button></div></form>
      <h4>发布与启停历史</h4><p v-if="history.loading" role="status">正在读取历史…</p><p v-if="history.error" role="alert" class="event-error">{{ history.error }}</p><ol v-if="history.value" class="event-history"><li v-for="item in history.value.items" :key="item.revision"><strong>{{ item.enabled ? '启用' : '停用' }} · 修订 {{ item.revision }}</strong><time>{{ time(item.changedAt) }} · {{ item.changedBy }}</time><p>{{ item.reason }}</p></li></ol><div class="event-actions"><button v-if="beforeRevision" class="quiet" :disabled="loading || saving" @click="loadHistory()">返回最新历史</button><button v-if="history.value?.nextBeforeRevision" class="secondary" :disabled="loading || saving" @click="loadHistory(history.value.nextBeforeRevision!)">更早启停历史</button></div>
    </section>
  </section>
</template>
<style src="../eventWorkspace.css"></style>
