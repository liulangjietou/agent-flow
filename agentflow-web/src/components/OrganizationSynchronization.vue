<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { isDefinitiveWriteFailure } from '../pendingWrites'
import { organizationLabels, type OrganizationRecord } from '../organization'
import { emptySyncDraft, organizationSyncDrafts, sameSyncSelections, syncChanges, syncConflictMessage, syncError, syncFacts, syncFailures,
  syncKey, syncPath, syncPlanSelections, syncStatuses, type SyncDetail, type SyncFact, type SyncOverview, type SyncPage,
  type SyncPlanReceipt, type SyncPlanSummary, type SyncReceipt, type SyncSavedPlan, type SyncSummary, type SyncTransition } from '../organizationSync'
import OrganizationSyncValue from './OrganizationSyncValue.vue'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean; accessDenied: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; applied: [] }>()
const draft = ref(organizationSyncDrafts.get(props.scopeKey))
const overview = ref<SyncOverview | null>(null), batches = ref<SyncPage<SyncSummary> | null>(null), detail = ref<SyncDetail | null>(null)
const plans = ref<SyncPage<SyncPlanSummary> | null>(null), plan = ref<SyncSavedPlan | null>(null), transitions = ref<SyncTransition[]>([])
const options = ref<OrganizationRecord[]>([]), nextOption = ref<string | undefined>(), selectedFactKey = ref(''), selectedLocalId = ref('')
const sending = ref(false), unknown = ref(false), denied = ref(false), error = ref(''), notice = ref(''), accepted = ref(false)
const factPage = ref(0), changePage = ref(0), conflictPage = ref(0)
const loading = reactive({ overview: false, batches: false, detail: false, plans: false, plan: false, options: false })
const errors = reactive({ overview: '', batches: '', detail: '', plans: '', plan: '', options: '' })
type Resource = keyof typeof loading
type Action = 'queue' | 'retry' | 'cancel' | 'apply'
const confirmation = ref<{ action: Action; stamp: string } | null>(null)
const slots = Object.fromEntries(Object.keys(loading).map(key => [key, { generation: 0, cancel: null as (() => void) | null }])) as Record<Resource, { generation: number; cancel: (() => void) | null }>
let active = true, session = 0
const busy = computed(() => sending.value || Object.values(loading).some(Boolean))
const locked = computed(() => props.locked || busy.value || unknown.value || denied.value)
const controlsLocked = computed(() => locked.value || !!confirmation.value)
const selectionDirty = computed(() => !sameSyncSelections(draft.value.selections, draft.value.plannedSelections))
const reviewDirty = computed(() => selectionDirty.value || !!draft.value.comment.trim())
const facts = computed(() => syncFacts(detail.value?.state.delta))
const selectedFact = computed(() => facts.value.find(value => syncKey(value.key) === selectedFactKey.value))
const selectedLocal = computed(() => options.value.find(value => value.id === selectedLocalId.value))
const changes = computed(() => plan.value ? syncChanges(plan.value.plan) : [])
const configured = computed(() => !!overview.value?.initialized && overview.value.configured
  && (!overview.value.registeredSourceKey || overview.value.registeredSourceKey === overview.value.sourceKey))
const canQueue = computed(() => !locked.value && configured.value && !overview.value?.activeBatchId && !reviewDirty.value)
const canRetry = computed(() => !locked.value && configured.value && !overview.value?.activeBatchId && !!detail.value
  && ['FAILED', 'CANCELLED'].includes(detail.value.state.status) && detail.value.request.sourceKey === overview.value?.sourceKey && !reviewDirty.value)
const canCancel = computed(() => !locked.value && !!detail.value && ['QUEUED', 'FETCHING', 'RECEIVED'].includes(detail.value.state.status))
const canPreflight = computed(() => !locked.value && detail.value?.reviewable === true && detail.value.state.status === 'RECEIVED')
const canApply = computed(() => canPreflight.value && !!plan.value && plan.value.plan.batchId === detail.value?.request.id
  && plan.value.plan.conflicts.length === 0 && sameSyncSelections(draft.value.selections, syncPlanSelections(plan.value.plan))
  && configured.value && plan.value.plan.sourceVersion === overview.value?.sourceVersion && detail.value?.request.afterRevision === overview.value?.appliedRevision)
const changedCount = computed(() => changes.value.filter(value => !value.before || value.before.revision !== value.after.revision).length)
const stamp = computed(() => JSON.stringify([props.scopeKey, overview.value?.sourceVersion, overview.value?.sourceKey, overview.value?.targetDigest,
  overview.value?.appliedRevision, overview.value?.activeBatchId, detail.value?.request.id, detail.value?.state.version, plan.value?.plan.id,
  plan.value?.digest, draft.value.selections, draft.value.comment]))
const actionAvailable = (action: Action) => ({ queue: canQueue.value, retry: canRetry.value, cancel: canCancel.value, apply: canApply.value })[action]
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
const factLabel = (value: SyncFact) => 'name' in value ? value.name : 'displayName' in value ? value.displayName : `任职 · ${value.key.externalId}`
const recordLabel = (value: OrganizationRecord) => 'name' in value ? value.name : 'displayName' in value ? value.displayName : `人员 ${value.personId} · 部门 ${value.departmentId}`
function abort(name: Resource) { const slot = slots[name]; slot.generation++; slot.cancel?.(); slot.cancel = null; loading[name] = false }
function clearReads() { for (const name of Object.keys(slots) as Resource[]) { abort(name); errors[name] = '' } }
function clearDetail() { detail.value = null; plans.value = null; plan.value = null; transitions.value = []; options.value = []; selectedFactKey.value = ''; selectedLocalId.value = ''; nextOption.value = undefined; factPage.value = 0; changePage.value = 0; conflictPage.value = 0 }
function deny() {
  session++; clearReads(); overview.value = null; batches.value = null; clearDetail(); organizationSyncDrafts.clear(props.scopeKey)
  draft.value = emptySyncDraft(); confirmation.value = null; denied.value = true; error.value = '当前账号已不能读取这些组织记录，请重新核对权限。'
}
/** 所有资源具有独立代次和总时限；账号切换、卸载或失权后不回填旧正文。 */
async function read<T>(name: Resource, fetch: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void) {
  if (!active || denied.value || !props.scopeKey) return false
  abort(name); const slot = slots[name], generation = slot.generation, scope = props.scopeKey, epoch = session, controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const valid = () => active && !denied.value && scope === props.scopeKey && epoch === session && generation === slot.generation
  const bounded = new Promise<never>((_, reject) => {
    slot.cancel = () => { controller.abort(); reject(new Error('读取已取消。')) }
    timer = setTimeout(() => { controller.abort(); reject(new Error('读取超时，请刷新重试。')) }, 12_000)
  })
  loading[name] = true; errors[name] = ''
  try { const value = await Promise.race([fetch(controller.signal), bounded]); if (valid()) { apply(value); return true } }
  catch (cause) { if (valid()) { if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) deny(); else errors[name] = syncError(cause) } }
  finally { clearTimeout(timer); if (valid()) { loading[name] = false; slot.cancel = null } }
  return false
}
async function loadOverview() { overview.value = null; await read('overview', api.organizationSyncOverview, value => { overview.value = value }) }
async function loadBatches(page = 0) { await read('batches', signal => api.organizationSyncBatches(page, signal), value => { batches.value = value }) }
async function loadPlans(page = 0) { const id = detail.value?.request.id; if (id) await read('plans', signal => api.organizationSyncPlans(id, page, signal), value => { if (detail.value?.request.id === id) plans.value = value }) }
async function loadPlan(id: string, adoptChoices = false) {
  const batchId = detail.value?.request.id; if (!batchId) return
  plan.value = null; changePage.value = 0; conflictPage.value = 0; confirmation.value = null
  await read('plan', signal => api.organizationSyncPlan(id, batchId, signal), value => {
    if (detail.value?.request.id !== batchId) return
    plan.value = value; draft.value.planId = id
    if (adoptChoices) { draft.value.selections = syncPlanSelections(value.plan); draft.value.plannedSelections = syncPlanSelections(value.plan) }
  })
}
async function loadDetail(id: string) {
  for (const name of ['plans', 'plan', 'options'] as Resource[]) abort(name)
  clearDetail(); const readId = id
  const success = await read('detail', async signal => {
    const value = await api.organizationSyncBatch(readId, signal)
    const history = await api.organizationSyncTransitions(readId, signal)
    const latest = history[history.length - 1]
    if (latest?.version !== value.state.version || latest?.status !== value.state.status) throw new Error('批次状态在读取期间已变化，请刷新后核对。')
    return { value, history }
  }, result => { detail.value = result.value; transitions.value = result.history })
  if (success && active && detail.value?.request.id === id) {
    const planId = draft.value.planId || detail.value.appliedPlanId
    await Promise.all([loadPlans(), planId ? loadPlan(planId) : Promise.resolve()])
  }
}
async function refresh() {
  confirmation.value = null; accepted.value = false; error.value = ''; clearReads()
  await Promise.all([loadOverview(), loadBatches(batches.value?.page ?? 0), draft.value.batchId ? loadDetail(draft.value.batchId) : Promise.resolve()])
}
async function showBatch(id: string) {
  if (controlsLocked.value || reviewDirty.value) return
  draft.value = { ...emptySyncDraft(), batchId: id }; notice.value = ''; confirmation.value = null; await loadDetail(id)
}
async function chooseFact(value: SyncFact) {
  if (controlsLocked.value || !canPreflight.value) return
  selectedFactKey.value = syncKey(value.key); selectedLocalId.value = ''; options.value = []; nextOption.value = undefined
  await loadOptions()
}
async function loadOptions(more = false) {
  const fact = selectedFact.value; if (!fact) return
  const original = selectedFactKey.value, cursor = more ? nextOption.value : undefined
  await read('options', signal => api.organizationSyncOptions(fact.key.kind, cursor, signal), value => {
    if (original !== selectedFactKey.value) return
    options.value = more ? [...options.value, ...value.items.filter(item => !options.value.some(old => old.id === item.id))] : value.items
    nextOption.value = value.nextAfterId ?? undefined
  })
}
function adopt() {
  if (controlsLocked.value || !canPreflight.value || !selectedFact.value || !selectedLocal.value) return
  const key = selectedFact.value.key, local = selectedLocal.value
  draft.value.selections = [...draft.value.selections.filter(item => syncKey(item) !== syncKey(key)), { ...key, localId: local.id, expectedRevision: local.revision }]
  selectedFactKey.value = ''; selectedLocalId.value = ''; options.value = []; confirmation.value = null
}
function removeSelection(key: string) { if (!controlsLocked.value) { draft.value.selections = draft.value.selections.filter(value => syncKey(value) !== key); confirmation.value = null } }
function discardReview() {
  if (controlsLocked.value) return
  draft.value.selections = [...draft.value.plannedSelections]; draft.value.comment = ''; selectedFactKey.value = ''; options.value = []
}
function begin(action: Action) { if (!controlsLocked.value && actionAvailable(action)) { confirmation.value = { action, stamp: stamp.value }; accepted.value = false; error.value = '' } }
/** 写入只发送已确认的原上下文；回执恢复只读取状态，不触发下一步业务写入。 */
async function write(path: string, body: object, send: () => Promise<SyncReceipt | SyncPlanReceipt>, applied = false) {
  const scope = props.scopeKey, epoch = session; sending.value = true; error.value = ''; notice.value = ''; confirmation.value = null
  const valid = () => active && scope === props.scopeKey && epoch === session
  try {
    const result = await send()
    // 正常离开页面仍保留原账号回执；失权或在当前页面切换账号后不恢复旧选择。
    if (!denied.value && (valid() || !active)) organizationSyncDrafts.acknowledge(scope, path, JSON.stringify(body), result)
    if (!valid()) return
    draft.value = organizationSyncDrafts.get(scope); unknown.value = false
    notice.value = applied ? '组织变更已应用，来源游标与审计已保存。' : '本次操作已确认，请核对批次状态和计划；组织变更需要单独确认应用。'
    await refresh(); if (valid() && applied) emit('applied')
  } catch (cause) { if (valid()) { unknown.value = !isDefinitiveWriteFailure(cause); if ([401, 403].includes((cause as { status?: number })?.status ?? 0)) deny(); else error.value = syncError(cause) } }
  finally { if (active && scope === props.scopeKey) sending.value = false }
}
async function preflight() {
  if (controlsLocked.value || !canPreflight.value || !detail.value) return
  const id = detail.value.request.id, body = { expectedVersion: detail.value.state.version, selections: draft.value.selections.map(value => ({ ...value })) }
  await write(syncPath + '/batches/' + id + '/preflight', body, () => api.preflightOrganizationSync(id, body))
}
async function confirm() {
  const intent = confirmation.value
  if (!intent || !accepted.value || intent.stamp !== stamp.value || !actionAvailable(intent.action)) return
  if (intent.action === 'queue') {
    const body = { expectedSourceVersion: overview.value!.sourceVersion, targetDigest: overview.value!.targetDigest! }
    await write(syncPath + '/batches', body, () => api.queueOrganizationSync(body)); return
  }
  const id = detail.value!.request.id, expectedVersion = detail.value!.state.version
  if (intent.action === 'retry') {
    const body = { expectedVersion, expectedSourceVersion: overview.value!.sourceVersion, targetDigest: overview.value!.targetDigest! }
    await write(syncPath + '/batches/' + id + '/retry', body, () => api.retryOrganizationSync(id, body)); return
  }
  const comment = draft.value.comment ? { comment: draft.value.comment } : {}
  if (intent.action === 'cancel') {
    const body = { expectedVersion, ...comment }; await write(syncPath + '/batches/' + id + '/cancel', body, () => api.cancelOrganizationSync(id, body))
  } else {
    const body = { expectedVersion, planId: plan.value!.plan.id, ...comment }
    await write(syncPath + '/batches/' + id + '/apply', body, () => api.applyOrganizationSync(id, body), true)
  }
}
watch(draft, value => organizationSyncDrafts.put(props.scopeKey, value), { deep: true, flush: 'sync' })
watch(busy, value => emit('busy', value), { flush: 'sync' })
watch(() => props.scopeKey, () => {
  session++; clearReads(); overview.value = null; batches.value = null; clearDetail(); confirmation.value = null
  draft.value = organizationSyncDrafts.get(props.scopeKey); denied.value = false; sending.value = false; unknown.value = writeRequests.pending().some(value => value.path.startsWith(syncPath + '/'))
  notice.value = ''; error.value = ''
  if (props.accessDenied) deny(); else if (props.scopeKey) void refresh()
}, { immediate: true, flush: 'sync' })
// 目录和同步页共享管理员权限；父级已确认失权时同步清除子页正文。
watch(() => props.accessDenied, value => { if (value) deny() }, { flush: 'sync' })
watch(() => props.refreshVersion, () => {
  draft.value = organizationSyncDrafts.get(props.scopeKey); unknown.value = writeRequests.pending().some(value => value.path.startsWith(syncPath + '/')); void refresh()
})
onUnmounted(() => { active = false; session++; clearReads(); emit('busy', false) })
</script>

<template>
  <section class="sync-workspace" aria-label="外部组织同步" :aria-busy="busy">
    <div class="sync-heading"><div><h3>外部组织同步</h3><p>读取可信来源，核对变更后再应用。人员主体和任职身份保持稳定。</p></div><button class="secondary" :disabled="busy || denied" @click="refresh">刷新状态</button></div>
    <p v-if="error" class="sync-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unknown" class="sync-warning" role="alert">上次操作结果尚未确认。请使用页面上方的“恢复原操作”，保持原请求；当前不会继续应用或重新读取来源。</p>
    <p v-for="(text, name) in errors" v-show="text" :key="name" class="sync-error" role="alert">{{ text }}</p>
    <template v-if="!denied">
      <section v-if="overview" class="panel sync-overview">
        <div><span>本租户来源</span><strong>{{ overview.sourceKey ?? '未配置' }}</strong><small>已登记：{{ overview.registeredSourceKey ?? '尚未登记' }}</small></div>
        <div><span>已应用来源版本</span><strong>{{ overview.appliedRevision }}</strong><small>收到数据后仍需明确应用</small></div>
        <div><span>读取后台</span><strong>{{ overview.workerEnabled ? '已启用' : '未启用' }}</strong><small>配置状态不代表来源连接已验收</small></div>
        <button class="primary" :disabled="!canQueue || !!confirmation" @click="begin('queue')">读取来源</button>
        <p v-if="!overview.initialized" class="sync-wide">请先在“本地维护”中启用组织目录。</p>
        <p v-else-if="!overview.configured" class="sync-wide">部署管理员需先为本租户配置可信来源；历史记录仍可读取。</p>
        <p v-else-if="overview.registeredSourceKey && overview.registeredSourceKey !== overview.sourceKey" class="sync-wide sync-error">部署来源与已登记来源不同，请先核对配置。</p>
        <p v-else-if="overview.activeBatchId" class="sync-wide">已有待处理批次。<button class="quiet" :disabled="controlsLocked || reviewDirty" @click="showBatch(overview.activeBatchId)">打开当前批次</button></p>
      </section>
      <section v-if="confirmation" class="panel sync-confirm" aria-label="确认组织同步操作">
        <h4>{{ { queue: '确认读取来源', retry: '确认创建重试批次', cancel: '确认取消本批次', apply: '确认应用组织变更' }[confirmation.action] }}</h4>
        <p v-if="confirmation.action === 'queue' || confirmation.action === 'retry'">来源 {{ overview?.sourceKey }}，从已应用版本 {{ overview?.appliedRevision }} 读取。返回的数据不会自动修改组织。</p>
        <p v-if="(confirmation.action === 'queue' || confirmation.action === 'retry') && !overview?.workerEnabled" class="sync-warning">后台当前未启用，本次只排队；恢复后台后才会读取。</p>
        <p v-if="confirmation.action === 'retry'">新批次将关联原失败或取消记录，原轨迹保持不变。</p>
        <p v-if="confirmation.action === 'cancel'">批次 {{ detail?.request.id }} 将停止后续接收或应用；已有事实、计划和人工意见继续保留。</p>
        <p v-if="confirmation.action === 'apply'">将应用计划 {{ plan?.plan.id }}，包含 {{ changedCount }} 条组织实体变更。来源版本推进到 {{ detail?.state.delta?.revision }}，旧审批轮次保留原依据。</p>
        <p v-if="draft.comment">本次意见：{{ draft.comment }}</p>
        <label class="sync-check"><input v-model="accepted" type="checkbox" :disabled="locked" />{{ confirmation.action === 'apply' ? '我已核对全部前后值和人工选择，确认应用本计划' : '我已核对当前来源、批次及操作影响' }}</label>
        <div class="sync-actions"><button class="primary" :disabled="locked || !accepted || confirmation.stamp !== stamp || !actionAvailable(confirmation.action)" @click="confirm">确认{{ confirmation.action === 'apply' ? '应用' : confirmation.action === 'cancel' ? '取消' : '读取' }}</button><button class="secondary" :disabled="sending" @click="confirmation = null">返回核对</button></div>
      </section>
      <div class="sync-layout">
        <section class="panel sync-batches" aria-label="组织同步批次列表"><h4>批次记录</h4>
          <p v-if="!batches && loading.batches">正在读取批次…</p><p v-else-if="!batches?.items.length">暂无同步批次。</p>
          <button v-for="item in batches?.items" :key="item.id" class="sync-batch" :disabled="controlsLocked || reviewDirty" :aria-pressed="draft.batchId === item.id" @click="showBatch(item.id)"><strong>{{ syncStatuses[item.status] }}</strong><span>{{ item.sourceKey }} · {{ item.id.slice(0, 8) }}</span><small>{{ time(item.createdAt) }} · {{ item.requestedBy }}</small><small>来源 {{ item.afterRevision }} → {{ item.receivedRevision ?? '尚未收到' }}</small></button>
          <div v-if="batches" class="sync-pages"><button :disabled="controlsLocked || reviewDirty || batches.page === 0" @click="loadBatches(batches.page - 1)">上一页</button><span>第 {{ batches.page + 1 }} 页 · 共 {{ batches.total }} 批</span><button :disabled="controlsLocked || reviewDirty || (batches.page + 1) * batches.pageSize >= batches.total" @click="loadBatches(batches.page + 1)">下一页</button></div>
        </section>
        <section class="panel sync-detail" aria-label="组织同步批次核对">
          <p v-if="loading.detail">正在读取原批次与轨迹…</p><p v-else-if="!detail">选择一个批次，核对来源事实、计划和人工决定。</p>
          <template v-if="detail">
            <div class="sync-heading"><h4>{{ syncStatuses[detail.state.status] }}</h4><span>批次 v{{ detail.state.version }}</span></div>
            <p class="sync-id">{{ detail.request.id }}</p><p>{{ detail.request.requestedBy }} 于 {{ time(detail.request.createdAt) }} 发起 · 来源 {{ detail.request.sourceKey }}</p>
            <p v-if="detail.request.retryOf">重试自 <button class="quiet" :disabled="controlsLocked || reviewDirty" @click="showBatch(detail.request.retryOf)">{{ detail.request.retryOf }}</button></p>
            <p v-if="detail.state.failure" class="sync-warning">{{ syncFailures[detail.state.failure] }}</p>
            <p v-if="['QUEUED','FETCHING'].includes(detail.state.status)">批次仍在处理中，可刷新状态；不会自动推进来源游标。</p>
            <p v-if="detail.state.status === 'RECEIVED' && !detail.reviewable" class="sync-warning">{{ syncConflictMessage(detail.unavailableReason ?? '') }}</p>
            <div class="sync-actions"><button class="primary" :disabled="controlsLocked || !canPreflight" @click="preflight">{{ selectionDirty ? '按新选择重新生成计划' : '生成核对计划' }}</button><button class="secondary" :disabled="controlsLocked || !canRetry" @click="begin('retry')">创建重试批次</button><button class="secondary" :disabled="controlsLocked || !canCancel" @click="begin('cancel')">取消批次</button></div>
            <details class="sync-history"><summary>查看 {{ transitions.length }} 条状态与人工决定</summary><ol><li v-for="item in transitions" :key="item.version"><strong>{{ syncStatuses[item.status] }}</strong> · {{ time(item.occurredAt) }}<p v-if="item.failure">{{ syncFailures[item.failure] }}</p><p v-if="item.decision">{{ item.decision.actor }}：{{ item.decision.comment || '未填写意见' }}</p></li></ol></details>
            <section v-if="detail.state.delta" aria-label="来源事实"><h4>已接收的来源事实 · {{ facts.length }} 条</h4><p>来源版本 {{ detail.state.delta.afterRevision }} → {{ detail.state.delta.revision }}。缺失记录表示未变，停用须显式提供。</p>
              <details v-for="fact in facts.slice(factPage * 20, (factPage + 1) * 20)" :key="syncKey(fact.key)" class="sync-fact"><summary>{{ organizationLabels[fact.key.kind] }} · {{ factLabel(fact) }} · {{ fact.key.externalId }}</summary><OrganizationSyncValue :value="fact" /><button class="secondary" :disabled="controlsLocked || !canPreflight" @click="chooseFact(fact)">明确选择本地对应记录</button></details>
              <div v-if="facts.length > 20" class="sync-pages"><button :disabled="factPage === 0" @click="factPage--">上一页事实</button><span>{{ factPage + 1 }} / {{ Math.ceil(facts.length / 20) }}</span><button :disabled="(factPage + 1) * 20 >= facts.length" @click="factPage++">下一页事实</button></div>
            </section>
            <section v-if="selectedFact" class="sync-adoption" aria-label="明确采用本地记录"><h4>选择 {{ organizationLabels[selectedFact.key.kind] }}：{{ selectedFact.key.externalId }}</h4>
              <p>明确将本地记录与此来源身份关联，并采用来源值。稳定主体和任职归属不能替换。</p>
              <label>本地对应记录<select v-model="selectedLocalId" :disabled="controlsLocked"><option value="">请选择，系统不会按姓名猜测</option><option v-for="item in options" :key="item.id" :value="item.id">{{ recordLabel(item) }} · v{{ item.revision }} · {{ item.active ? '在用' : '已停用' }} · {{ item.id }}</option></select></label>
              <button v-if="nextOption" class="secondary" :disabled="controlsLocked" @click="loadOptions(true)">载入更多本地记录</button>
              <OrganizationSyncValue v-if="selectedLocal" :value="selectedLocal" />
              <div class="sync-actions"><button class="primary" :disabled="controlsLocked || !selectedLocal" @click="adopt">采用此记录的当前修订</button><button class="secondary" :disabled="controlsLocked" @click="selectedFactKey = ''">关闭选择</button></div>
            </section>
            <section v-if="draft.selections.length" class="sync-choices"><h4>人工选择 · {{ draft.selections.length }} 条</h4><p>选择后重新生成计划，核对完整前后值；当前尚未修改组织。</p><ul><li v-for="item in draft.selections" :key="syncKey(item)">{{ organizationLabels[item.kind] }} {{ item.externalId }} → {{ item.localId }} · v{{ item.expectedRevision }} <button class="quiet" :disabled="controlsLocked" @click="removeSelection(syncKey(item))">移除选择</button></li></ul></section>
            <section aria-label="历史核对计划"><h4>核对计划</h4><p v-if="!plans?.items.length">尚无保存的核对计划。</p>
              <button v-for="item in plans?.items" :key="item.id" class="sync-plan-link" :aria-pressed="plan?.plan.id === item.id" :disabled="controlsLocked || reviewDirty" @click="loadPlan(item.id, true)">{{ item.ready ? '预检无冲突' : '存在待处理冲突' }} · {{ item.preparedBy }} · {{ time(item.preparedAt) }} · {{ item.id.slice(0, 8) }}</button>
              <div v-if="plans && plans.total > plans.pageSize" class="sync-pages"><button :disabled="controlsLocked || reviewDirty || plans.page === 0" @click="loadPlans(plans.page - 1)">上一页计划</button><span>{{ plans.page + 1 }} 页 / {{ plans.total }} 份</span><button :disabled="controlsLocked || reviewDirty || (plans.page + 1) * plans.pageSize >= plans.total" @click="loadPlans(plans.page + 1)">下一页计划</button></div>
            </section>
            <section v-if="plan" class="sync-plan" aria-label="核对计划前后值"><h4>{{ plan.plan.conflicts.length ? '存在阻断，不能应用' : '计划预检无冲突' }}</h4><p class="sync-id">计划 {{ plan.plan.id }}</p><p>{{ plan.plan.preparedBy }} 于 {{ time(plan.plan.preparedAt) }} 核对，目录修订 {{ plan.plan.directoryRevision }}。{{ changes.length }} 条记录中 {{ changedCount }} 条发生组织变更。</p>
              <p v-if="selectionDirty" class="sync-warning">人工选择已变化，需重新生成核对计划。</p>
              <article v-for="item in plan.plan.conflicts.slice(conflictPage * 20, (conflictPage + 1) * 20)" :key="syncKey(item.key)" class="sync-conflict"><strong>{{ organizationLabels[item.key.kind] }} · {{ item.key.externalId }}</strong><p>{{ syncConflictMessage(item.code) }}</p><small v-if="item.localId">当前本地记录：{{ item.localId }}</small><button v-if="facts.find(value => syncKey(value.key) === syncKey(item.key))" class="quiet" :disabled="controlsLocked || !canPreflight" @click="chooseFact(facts.find(value => syncKey(value.key) === syncKey(item.key))!)">核对对应记录</button></article>
              <div v-if="plan.plan.conflicts.length > 20" class="sync-pages"><button :disabled="conflictPage === 0" @click="conflictPage--">上一页冲突</button><span>{{ conflictPage + 1 }} / {{ Math.ceil(plan.plan.conflicts.length / 20) }}</span><button :disabled="(conflictPage + 1) * 20 >= plan.plan.conflicts.length" @click="conflictPage++">下一页冲突</button></div>
              <details v-for="item in changes.slice(changePage * 20, (changePage + 1) * 20)" :key="syncKey(item.key)" class="sync-change"><summary>{{ organizationLabels[item.key.kind] }} · {{ item.key.externalId }} · {{ !item.before ? '新增' : item.before.revision === item.after.revision ? '原值确认' : '修改' }}{{ item.explicitlySelected ? ' · 人工明确采用' : '' }}</summary><div class="sync-comparison"><section><h5>核对时的本地值</h5><OrganizationSyncValue :value="item.before" /></section><section><h5>应用后的值</h5><OrganizationSyncValue :value="item.after" /></section></div></details>
              <div v-if="changes.length > 20" class="sync-pages"><button :disabled="changePage === 0" @click="changePage--">上一页变更</button><span>{{ changePage + 1 }} / {{ Math.ceil(changes.length / 20) }}</span><button :disabled="(changePage + 1) * 20 >= changes.length" @click="changePage++">下一页变更</button></div>
              <p>计划生成后，目录、来源或批次发生变化会拒绝应用，需要重新预检。</p>
            </section>
            <label v-if="canCancel || detail.state.status === 'RECEIVED'">本次应用或取消意见（可选）<textarea v-model="draft.comment" maxlength="2000" rows="3" :disabled="controlsLocked" /></label>
            <div class="sync-actions"><button v-if="plan" class="primary" :disabled="controlsLocked || !canApply" @click="begin('apply')">核对并应用此计划</button><button v-if="reviewDirty" class="secondary" :disabled="controlsLocked" @click="discardReview">放弃未保存选择和意见</button></div>
            <p v-if="detail.appliedPlanId">本批次实际采用计划：<button class="quiet" :disabled="controlsLocked || reviewDirty" @click="loadPlan(detail.appliedPlanId, true)">{{ detail.appliedPlanId }}</button></p>
          </template>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.sync-workspace{font-size:13px;line-height:1.8}.sync-heading{display:flex;justify-content:space-between;align-items:start;gap:16px}.sync-heading h3,.sync-heading h4{margin-top:0}.sync-heading p,.sync-workspace small{color:var(--muted)}.sync-overview{padding:20px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr)) auto;gap:18px;margin:18px 0}.sync-overview span,.sync-overview strong,.sync-overview small{display:block}.sync-overview strong{font-size:18px;overflow-wrap:anywhere}.sync-overview button{align-self:center}.sync-wide{grid-column:1/-1;margin:0}.sync-layout{display:grid;grid-template-columns:280px minmax(0,1fr);gap:20px;align-items:start}.sync-batches,.sync-detail{padding:20px;min-width:0}.sync-batch{display:block;width:100%;background:transparent;border:1px solid var(--line);border-radius:7px;padding:12px;text-align:left;margin:9px 0;line-height:1.8}.sync-batch[aria-pressed=true]{border-color:var(--deep);background:var(--soft)}.sync-batch span,.sync-batch small{display:block}.sync-pages,.sync-actions{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin:16px 0}.sync-pages button{border:1px solid var(--line);background:white;border-radius:5px;padding:5px 8px}.sync-pages span{font-size:11px;color:var(--muted)}.sync-id{font-size:11px;overflow-wrap:anywhere;color:var(--muted)}.sync-error{color:var(--red)}.sync-warning,.sync-conflict{background:#fff7e7;border:1px solid #eadbb8;padding:12px;border-radius:7px;color:#765e28}.sync-conflict{margin:12px 0}.sync-conflict p{margin:5px 0}.sync-conflict small{display:block;overflow-wrap:anywhere}.sync-history,.sync-fact,.sync-change{border:1px solid var(--line);padding:13px;border-radius:7px;margin:12px 0}.sync-history summary,.sync-fact summary,.sync-change summary{cursor:pointer;overflow-wrap:anywhere}.sync-fact[open] summary,.sync-change[open] summary{margin-bottom:15px}.sync-fact button{margin-top:12px}.sync-detail label{display:grid;gap:8px;margin:14px 0}.sync-detail select,.sync-detail textarea{width:100%;box-sizing:border-box;padding:10px;border:1px solid var(--line);border-radius:6px;background:white;color:var(--ink);font:inherit}.sync-adoption{padding:18px;border:1px solid var(--deep);border-radius:8px;margin:18px 0}.sync-adoption select{max-width:100%}.sync-choices li{overflow-wrap:anywhere;margin:9px 0}.sync-plan-link{display:block;max-width:100%;border:0;background:transparent;color:var(--deep);text-align:left;padding:6px 0;overflow-wrap:anywhere}.sync-plan-link[aria-pressed=true]{font-weight:700;text-decoration:underline}.sync-plan{margin-top:16px;border-top:1px solid var(--line)}.sync-comparison{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.sync-comparison h5{font-size:12px;margin:0 0 12px;color:var(--deep)}.sync-confirm{padding:22px;border:1px solid var(--deep);margin:18px 0}.sync-confirm h4{margin-top:0}.sync-check{display:flex;gap:8px;align-items:start}.sync-check input{margin-top:6px}.quiet{overflow-wrap:anywhere;white-space:normal}button:disabled{opacity:.55;cursor:not-allowed}
@media(max-width:1050px){.sync-layout{grid-template-columns:1fr}.sync-overview{grid-template-columns:repeat(2,minmax(0,1fr))}.sync-batches{max-height:400px;overflow:auto}}
@media(max-width:650px){.sync-heading{flex-direction:column}.sync-overview,.sync-comparison{grid-template-columns:1fr}.sync-batches,.sync-detail,.sync-confirm{padding:15px}.sync-actions button{max-width:100%}}
</style>
