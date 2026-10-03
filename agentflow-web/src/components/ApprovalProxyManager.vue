<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { approvalProxyDrafts, approvalProxyPath, emptyProxyForm, proxyCreateInput, proxyLocalTime, proxyStatuses, unreadableProxy,
  type ApprovalProxyForm, type ApprovalProxyView } from '../approvalProxies'
import type { OrganizationPerson } from '../organization'
import type { DefinitionCatalogItem } from '../definitionCatalog'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
type Slot = 'list' | 'detail' | 'options'
const loading = reactive<Record<Slot, boolean>>({ list: false, detail: false, options: false })
const errors = reactive<Record<Slot, string>>({ list: '', detail: '', options: '' })
const rows = ref<ApprovalProxyView[]>([]), nextId = ref<string | null>(null), observedAt = ref('')
const people = ref<OrganizationPerson[]>([]), peopleNext = ref<string | null>(null)
const definitions = ref<DefinitionCatalogItem[]>([]), definitionsNext = ref<string | null>(null)
const filterPerson = ref(''), appliedPerson = ref(''), definitionSearch = ref(''), appliedSearch = ref('')
const selectedId = ref(''), detail = ref<ApprovalProxyView | null>(null), stale = ref(true)
const form = ref<ApprovalProxyForm>(emptyProxyForm()), formOpen = ref(false), confirmed = ref(false)
const revokeReason = ref(''), revokeConfirmed = ref(false), sending = ref(false), denied = ref(false)
const writeError = ref(''), notice = ref('')
const controllers = new Map<Slot, AbortController>(), versions = { list: 0, detail: 0, options: 0 }
let epoch = 0, active = true, hydrating = false
const locked = computed(() => props.locked || sending.value || denied.value)
const eligiblePeople = computed(() => people.value.filter(person => person.active && person.approvalEligible))
const inputError = computed(() => {
  try { proxyCreateInput(form.value); return '' } catch (cause) { return (cause as Error).message }
})
const selectionKnown = computed(() => definitions.value.some(value => value.id === form.value.definitionId && value.status === 'PUBLISHED')
  && eligiblePeople.value.some(value => value.id === form.value.principalId) && eligiblePeople.value.some(value => value.id === form.value.substituteId))
const canCreate = computed(() => !locked.value && !loading.options && confirmed.value && !inputError.value && selectionKnown.value)
const canRevoke = computed(() => !locked.value && !loading.detail && !stale.value && detail.value?.proxy.revocation === null
  && revokeConfirmed.value && !!revokeReason.value.trim() && revokeReason.value.length <= 1000 && !/[\u0000-\u001f\u007f-\u009f]/.test(revokeReason.value))
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
function time(value: string) {
  const date = new Date(value)
  const offset = new Intl.DateTimeFormat('en', { timeZoneName: 'shortOffset' }).formatToParts(date).find(part => part.type === 'timeZoneName')!.value
  return `${date.toLocaleString('zh-CN', { hour12: false })} (${offset})`
}
const previewTime = (value: string) => { const instant = proxyLocalTime(value); return instant ? time(instant) : '尚未填写有效时间' }

function cancelReads() {
  epoch++; controllers.forEach(controller => controller.abort()); controllers.clear()
  for (const slot of ['list', 'detail', 'options'] as const) { versions[slot]++; loading[slot] = false; errors[slot] = '' }
}
function clearVisible() {
  rows.value = []; nextId.value = null; observedAt.value = ''; people.value = []; peopleNext.value = null
  definitions.value = []; definitionsNext.value = null; detail.value = null; selectedId.value = ''; stale.value = true
  confirmed.value = false; revokeConfirmed.value = false; revokeReason.value = ''; notice.value = ''; writeError.value = ''
}
function fail(cause: unknown) {
  const failure = cause as Partial<ApiError>
  if ([401, 403].includes(failure.status ?? 0)) {
    cancelReads(); clearVisible(); denied.value = true; sending.value = false
    approvalProxyDrafts.forget(props.scopeKey); hydrating = true; form.value = emptyProxyForm(); hydrating = false
  }
  return failure.message ?? '暂时无法读取审批代理，请重试。'
}
/** 列表、详情和选项各自取消旧请求；账号变化或权限拒绝使全部旧响应失效。 */
async function read<T>(slot: Slot, fetch: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void) {
  controllers.get(slot)?.abort()
  const version = ++versions[slot], currentEpoch = epoch, controller = new AbortController()
  controllers.set(slot, controller); loading[slot] = true; errors[slot] = ''
  let timer: ReturnType<typeof setTimeout> | undefined
  const current = () => active && currentEpoch === epoch && version === versions[slot]
  try {
    const result = await Promise.race([fetch(controller.signal), new Promise<never>((_, reject) => {
      timer = setTimeout(() => { controller.abort(); reject(new Error('读取超时，请重新读取；原操作结果未知时先恢复上次操作。')) }, 12_000)
    })])
    if (current()) apply(result)
  } catch (cause) { if (current()) errors[slot] = fail(cause) }
  finally { clearTimeout(timer); if (current()) { loading[slot] = false; controllers.delete(slot) } }
}
async function loadList(more = false) {
  if (!props.scopeKey || more && (loading.list || !nextId.value)) return
  const after = more ? nextId.value : null, personId = appliedPerson.value
  if (!more) { rows.value = []; nextId.value = null; observedAt.value = '' }
  await read('list', signal => api.approvalProxies(personId || undefined, after ?? undefined, signal), page => {
    const ids = new Set(rows.value.map(value => value.proxy.id))
    if (page.nextAfterId && page.nextAfterId === after || more && page.items.some(value => ids.has(value.proxy.id))) throw unreadableProxy()
    rows.value = more ? [...rows.value, ...page.items] : page.items; nextId.value = page.nextAfterId; observedAt.value = page.observedAt
  })
}
function applyFilter() { if (sending.value) return; appliedPerson.value = filterPerson.value; void loadList() }
async function loadOptions() {
  await read('options', signal => Promise.all([api.organizationPeople(undefined, signal), api.searchDefinitions({ status: 'PUBLISHED', q: appliedSearch.value || undefined, limit: 30 }, signal)]), ([persons, versions]) => {
    people.value = persons.items; peopleNext.value = persons.nextAfterId ?? null
    definitions.value = versions.items; definitionsNext.value = versions.nextCursor ?? null
  })
}
async function morePeople() {
  if (loading.options || !peopleNext.value) return
  const after = peopleNext.value
  await read('options', signal => api.organizationPeople(after, signal), page => {
    if (page.nextAfterId === after || page.items.some(value => people.value.some(old => old.id === value.id))) throw unreadableProxy()
    people.value.push(...page.items); peopleNext.value = page.nextAfterId ?? null
  })
}
async function searchDefinitions(more = false) {
  if (loading.options || more && !definitionsNext.value) return
  if (!more) { appliedSearch.value = definitionSearch.value.trim(); definitions.value = []; definitionsNext.value = null; confirmed.value = false }
  const cursor = more ? definitionsNext.value : null, q = appliedSearch.value
  await read('options', signal => api.searchDefinitions({ status: 'PUBLISHED', q: q || undefined, limit: 30, cursor: cursor ?? undefined }, signal), page => {
    if (page.nextCursor && page.nextCursor === cursor || more && page.items.some(value => definitions.value.some(old => old.id === value.id))) throw unreadableProxy()
    definitions.value = more ? [...definitions.value, ...page.items] : page.items; definitionsNext.value = page.nextCursor ?? null
  })
}
async function readDetail(id: string) {
  selectedId.value = id; detail.value = null; stale.value = true; revokeConfirmed.value = false; revokeReason.value = ''
  await read('detail', signal => api.approvalProxy(id, signal), value => { detail.value = value; stale.value = false })
}
function inspect(id: string) { if (sending.value) return; formOpen.value = false; writeError.value = ''; notice.value = ''; void readDetail(id) }
function openCreate() { if (locked.value) return; formOpen.value = true; confirmed.value = false; writeError.value = ''; notice.value = '' }
function discard() { if (locked.value) return; form.value = emptyProxyForm(); confirmed.value = false; notice.value = '已放弃本地创建草稿。' }
async function create() {
  if (!canCreate.value) return
  let body
  try { body = proxyCreateInput(form.value) } catch (cause) { writeError.value = (cause as Error).message; confirmed.value = false; return }
  const scope = props.scopeKey, currentEpoch = epoch
  approvalProxyDrafts.put(scope, form.value); sending.value = true; writeError.value = ''; notice.value = ''
  try {
    const receipt = await api.createApprovalProxy(body)
    approvalProxyDrafts.acknowledge(scope, approvalProxyPath, JSON.stringify(body), receipt)
    if (!active || epoch !== currentEpoch) return
    form.value = approvalProxyDrafts.get(scope).form; formOpen.value = false; confirmed.value = false
    notice.value = '创建已确认，正在按原编号读取当前状态。'
    await Promise.all([readDetail(receipt.proxyId), loadList()])
    if (active && epoch === currentEpoch) notice.value = errors.detail ? '代理已创建，详情读取失败；请按原编号重新读取。' : '代理已创建，当前状态已重新读取。'
  } catch (cause) { if (active && epoch === currentEpoch) { writeError.value = fail(cause); confirmed.value = false } }
  finally { if (active && epoch === currentEpoch) sending.value = false }
}
async function revoke() {
  if (!canRevoke.value || !detail.value) return
  const value = detail.value, scope = props.scopeKey, currentEpoch = epoch
  const body = { expectedRevision: value.proxy.revision, reason: revokeReason.value.trim() }
  sending.value = true; writeError.value = ''; notice.value = ''; stale.value = true; revokeConfirmed.value = false
  try {
    const receipt = await api.revokeApprovalProxy(value.proxy.id, body)
    approvalProxyDrafts.acknowledge(scope, `${approvalProxyPath}/${value.proxy.id}/revoke`, JSON.stringify(body), receipt)
    if (!active || currentEpoch !== epoch) return
    notice.value = '撤销已确认，正在重新读取原记录。'
    await Promise.all([readDetail(receipt.proxyId), loadList()])
    if (active && epoch === currentEpoch) notice.value = errors.detail ? '撤销已确认，详情读取失败；请按原编号重新读取。' : '代理已撤销，原记录已重新读取。'
  } catch (cause) { if (active && currentEpoch === epoch) writeError.value = fail(cause) }
  finally { if (active && currentEpoch === epoch) sending.value = false }
}
function refresh() {
  if (!props.scopeKey || sending.value) return
  denied.value = false; writeError.value = ''; confirmed.value = false; revokeConfirmed.value = false
  const saved = approvalProxyDrafts.get(props.scopeKey)
  hydrating = true; form.value = saved.form; hydrating = false
  void loadList(); void loadOptions()
  const id = saved.lastProxyId || selectedId.value
  if (id) { formOpen.value = false; void readDetail(id) }
}
watch(form, value => { confirmed.value = false; if (!hydrating && !denied.value) approvalProxyDrafts.put(props.scopeKey, value) }, { deep: true, flush: 'sync' })
watch(revokeReason, () => { revokeConfirmed.value = false }, { flush: 'sync' })
watch(() => props.scopeKey, () => {
  cancelReads(); clearVisible(); sending.value = false; denied.value = false; formOpen.value = false
  filterPerson.value = ''; appliedPerson.value = ''; definitionSearch.value = ''; appliedSearch.value = ''
  hydrating = true; form.value = approvalProxyDrafts.get(props.scopeKey).form; hydrating = false
  if (props.scopeKey) refresh()
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
onUnmounted(() => { active = false; cancelReads() })
</script>

<template>
  <section class="content proxy-manager" aria-label="审批代理管理">
    <div class="page-heading"><div><small class="eyebrow">组织与人员</small><h1>审批代理</h1><p>为一个已发布流程版本指定代理人，保留原审批责任和有效期限。</p></div><button class="primary" :disabled="locked" @click="openCreate">新建代理</button></div>
    <p class="proxy-note">代理人仍需自己的审批角色和当前任务权限。原审批人、代理范围及起止时间保存后保持不变；提前结束请撤销。</p>
    <p v-if="denied" class="proxy-error" role="alert">当前账号不能管理审批代理，已清除页面中的人员与授权详情。</p>
    <div class="proxy-tools">
      <form class="proxy-filter" @submit.prevent="applyFilter"><label>按参与人员筛选<select v-model="filterPerson" :disabled="loading.options || sending || denied"><option value="">全部人员</option><option v-for="person in people" :key="person.id" :value="person.id">{{ person.displayName }} · {{ person.subject }}</option></select></label><button class="secondary" :disabled="loading.list || sending || denied">查询记录</button></form>
      <button class="secondary" :disabled="sending" @click="refresh">刷新当前状态</button>
      <button v-if="peopleNext" class="quiet" :disabled="loading.options || sending" @click="morePeople">加载更多人员选项</button>
    </div>
    <p v-if="errors.options" class="proxy-error" role="alert">{{ errors.options }} <button class="quiet" :disabled="loading.options" @click="loadOptions">重新读取选项</button></p>
    <div v-if="!denied" class="proxy-grid">
      <section class="panel proxy-list" aria-label="代理记录">
        <h2>授权记录 <small v-if="observedAt">最近一页读取于 {{ time(observedAt) }}</small></h2>
        <p v-if="loading.list" role="status">正在读取代理记录…</p><p v-if="errors.list" class="proxy-error" role="alert">{{ errors.list }}</p>
        <button v-for="row in rows" :key="row.proxy.id" class="proxy-row" :aria-pressed="selectedId === row.proxy.id && !formOpen" :disabled="sending" @click="inspect(row.proxy.id)">
          <span class="proxy-people"><strong>{{ row.principal.displayName }}</strong><span aria-hidden="true">→</span><strong>{{ row.substitute.displayName }}</strong></span>
          <span>{{ row.definitionName }} · v{{ row.definitionVersion }}</span><span class="proxy-row-period">{{ time(row.proxy.startsAt) }}<br />至 {{ time(row.proxy.endsAt) }}</span>
          <span class="proxy-status" :class="row.status.toLowerCase()">{{ proxyStatuses[row.status] }} · {{ time(row.observedAt) }} 读取</span>
        </button>
        <p v-if="!rows.length && !loading.list && !errors.list" class="proxy-note">{{ appliedPerson ? '该人员暂无代理记录，可更换筛选条件。' : '暂无代理记录。请先准备双方人员和已发布版本，再新建代理。' }}</p>
        <button v-if="nextId" class="secondary" :disabled="loading.list || sending" @click="loadList(true)">加载更多记录</button>
      </section>
      <section class="panel proxy-editor" :aria-label="formOpen ? '新建代理' : '代理详情'">
        <template v-if="formOpen">
          <h2>新建代理</h2>
          <div class="proxy-definition-search"><label>查找已发布流程<input v-model="definitionSearch" :disabled="locked || loading.options" placeholder="流程名称或标识" @keydown.enter.prevent="searchDefinitions()" /></label><button class="secondary" :disabled="locked || loading.options" @click="searchDefinitions()">查找版本</button></div>
          <form class="proxy-form" @submit.prevent="create">
            <label>流程版本<select v-model="form.definitionId" :disabled="locked || loading.options" required><option value="">请选择一个已发布版本</option><option v-if="form.definitionId && !definitions.some(value => value.id === form.definitionId)" :value="form.definitionId" disabled>原选择尚未载入，请重新核对</option><option v-for="definition in definitions" :key="definition.id" :value="definition.id">{{ definition.name }} · {{ definition.key }} · v{{ definition.version }}{{ definition.startEnabled ? '' : '（已停用新发起）' }}</option></select></label>
            <button v-if="definitionsNext" type="button" class="quiet" :disabled="locked || loading.options" @click="searchDefinitions(true)">加载更多流程版本</button>
            <div class="proxy-pair"><label>原审批人<select v-model="form.principalId" :disabled="locked || loading.options" required><option value="">请选择原审批人</option><option v-for="person in eligiblePeople" :key="person.id" :value="person.id">{{ person.displayName }} · {{ person.subject }}</option></select></label><label>代理人<select v-model="form.substituteId" :disabled="locked || loading.options" required><option value="">请选择代理人</option><option v-for="person in eligiblePeople" :key="person.id" :value="person.id" :disabled="person.id === form.principalId">{{ person.displayName }} · {{ person.subject }}</option></select></label></div>
            <button v-if="peopleNext" type="button" class="quiet" :disabled="locked || loading.options" @click="morePeople">加载更多人员选项</button>
            <p class="proxy-note">只列出在用且具备本地审批资格的人员；身份源角色仍需在实际办理时核对。</p>
            <div class="proxy-pair"><label>开始时间<input v-model="form.startsLocal" type="datetime-local" step="60" :disabled="locked" required /></label><label>结束时间<input v-model="form.endsLocal" type="datetime-local" step="60" :disabled="locked" required /></label></div>
            <div class="proxy-period"><strong>本次有效期 · {{ zone }}</strong><span>{{ previewTime(form.startsLocal) }}</span><span>至 {{ previewTime(form.endsLocal) }}</span><small>包含开始时刻，到达结束时刻停止。早于创建时间的部分不会追溯授权。</small></div>
            <label>授权原因<input v-model="form.reason" maxlength="1000" :disabled="locked" placeholder="说明临时代理的业务原因" required /></label>
            <p v-if="inputError" class="proxy-note">{{ inputError }}</p><p v-else-if="!selectionKnown" class="proxy-note">请重新载入并核对所选人员和版本。</p>
            <label class="proxy-check"><input v-model="confirmed" type="checkbox" :disabled="locked || !!inputError || !selectionKnown" />已核对双方人员、流程版本与有效期，确认创建这份代理。</label>
            <div class="proxy-actions"><button class="primary" type="submit" :disabled="!canCreate">{{ sending ? '正在创建…' : '确认创建代理' }}</button><button class="secondary" type="button" :disabled="locked" @click="discard">放弃本地草稿</button></div>
          </form>
        </template>
        <template v-else-if="selectedId">
          <div class="proxy-detail-heading"><h2>代理详情</h2><button class="secondary" :disabled="sending || loading.detail" @click="readDetail(selectedId)">重新读取</button></div>
          <p class="proxy-id">编号 {{ selectedId }}</p><p v-if="loading.detail" role="status">正在按原编号读取当前状态…</p>
          <p v-if="errors.detail" class="proxy-error" role="alert">{{ errors.detail }}</p>
          <template v-if="detail">
            <div class="proxy-identity"><div><small>原审批人</small><strong>{{ detail.principal.displayName }}</strong><span>{{ detail.principal.subject }}</span></div><span aria-hidden="true">→</span><div><small>代理人</small><strong>{{ detail.substitute.displayName }}</strong><span>{{ detail.substitute.subject }}</span></div></div>
            <p><strong>{{ detail.definitionName }} · v{{ detail.definitionVersion }}</strong><br /><span class="proxy-note">{{ detail.processKey }}</span></p>
            <div class="proxy-period"><strong>{{ proxyStatuses[detail.status] }} · 读取于 {{ time(detail.observedAt) }}</strong><span>{{ time(detail.proxy.startsAt) }}</span><span>至 {{ time(detail.proxy.endsAt) }}</span></div>
            <p v-if="!detail.principal.approvalEligible || !detail.substitute.approvalEligible" class="proxy-warning">至少一方当前不具备本地审批资格，不能依此代理办理任务。</p>
            <p class="proxy-note">期限内仍需核对当前任务、职责分离、会签独立性和本人权限。</p>
            <dl class="proxy-facts"><dt>授权原因</dt><dd>{{ detail.proxy.reason }}</dd><dt>创建人</dt><dd>{{ detail.proxy.createdBy }} · {{ time(detail.proxy.createdAt) }}</dd><dt>当前修订</dt><dd>v{{ detail.proxy.revision }}</dd></dl>
            <div v-if="detail.proxy.revocation" class="proxy-revoked"><h3>撤销记录</h3><p>{{ detail.proxy.revocation.actor }} · {{ time(detail.proxy.revocation.at) }}</p><p>{{ detail.proxy.revocation.reason }}</p></div>
            <form v-else class="proxy-form proxy-revoke" @submit.prevent="revoke"><h3>撤销这份代理</h3><p class="proxy-note">原授权和已发生的办理记录会保留。撤销后如需再次授权，请创建新代理。</p><label>撤销原因<input v-model="revokeReason" maxlength="1000" :disabled="locked || stale || loading.detail" required /></label><label class="proxy-check"><input v-model="revokeConfirmed" type="checkbox" :disabled="locked || stale || loading.detail" />确认撤销上方这份代理。</label><button type="submit" class="secondary" :disabled="!canRevoke">{{ sending ? '正在撤销…' : '确认撤销代理' }}</button></form>
            <p v-if="stale" class="proxy-warning" role="status">此前读取的状态已不能用于新操作，请重新读取；结果未知时先恢复上次操作。</p>
          </template>
        </template>
        <template v-else><h2>选择一份授权</h2><p class="proxy-note">查看双方人员、原期限、当前资格及撤销记录，或新建一份临时代理。</p></template>
        <p v-if="writeError" class="proxy-error" role="alert">{{ writeError }}</p><p v-if="notice" class="proxy-notice" role="status">{{ notice }}</p>
      </section>
    </div>
    <p v-if="denied && (writeError || errors.detail || errors.list || errors.options)" class="proxy-error" role="alert">{{ writeError || errors.detail || errors.list || errors.options }}</p>
  </section>
</template>

<style scoped>
.proxy-manager{font-size:13px;line-height:1.75}.proxy-manager .page-heading{align-items:flex-start;gap:18px}.proxy-manager .page-heading p,.proxy-note{color:var(--muted)}.proxy-tools,.proxy-filter,.proxy-actions,.proxy-detail-heading,.proxy-definition-search{display:flex;align-items:end;gap:12px;flex-wrap:wrap}.proxy-tools{margin:22px 0}.proxy-filter{flex:1}.proxy-filter label{flex:1;max-width:400px}.proxy-grid{display:grid;grid-template-columns:minmax(250px,.85fr) minmax(0,1.3fr);gap:22px;align-items:start}.proxy-list,.proxy-editor{padding:22px;min-width:0}.proxy-manager h2{font-size:18px;margin:0 0 16px}.proxy-manager h2 small{display:block;font-size:11px;font-weight:400;color:var(--muted);margin-top:6px}.proxy-row{display:flex;flex-direction:column;align-items:flex-start;gap:7px;width:100%;text-align:left;padding:18px 3px;border-top:1px solid var(--line);overflow-wrap:anywhere}.proxy-row[aria-pressed=true]{border-left:3px solid var(--deep);padding-left:12px;background:var(--soft)}.proxy-row>span:not(.proxy-people){font-size:12px}.proxy-people{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.proxy-row-period{color:var(--muted)}.proxy-status{background:var(--paper);border-radius:4px;padding:2px 8px;color:var(--muted)}.proxy-status.active{color:var(--deep);background:var(--soft)}.proxy-manager label{display:flex;flex-direction:column;gap:7px}.proxy-manager input,.proxy-manager select{width:100%;min-width:0;max-width:100%;padding:10px;border:1px solid var(--line);border-radius:6px;background:#fff;color:var(--ink);font:inherit}.proxy-form{display:flex;flex-direction:column;gap:17px}.proxy-pair{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.proxy-definition-search{margin-bottom:18px}.proxy-definition-search label{flex:1;min-width:150px}.proxy-period{display:flex;flex-direction:column;padding:14px 16px;border-left:3px solid var(--deep);background:var(--soft);gap:4px;overflow-wrap:anywhere}.proxy-period strong{font-size:12px}.proxy-period small{color:var(--muted);margin-top:6px}.proxy-form .proxy-check{flex-direction:row;align-items:flex-start;gap:9px}.proxy-check input{width:16px;height:16px;flex-shrink:0;margin-top:4px;accent-color:var(--deep)}.proxy-identity{display:grid;grid-template-columns:minmax(0,1fr) auto minmax(0,1fr);gap:18px;align-items:center;margin:22px 0}.proxy-identity>div{display:flex;flex-direction:column;min-width:0;overflow-wrap:anywhere}.proxy-identity strong{font-size:20px}.proxy-identity small,.proxy-identity span,.proxy-id{color:var(--muted);font-size:12px}.proxy-id{font-family:'DM Mono',monospace;overflow-wrap:anywhere}.proxy-detail-heading{justify-content:space-between;align-items:center}.proxy-detail-heading h2{margin:0}.proxy-facts{display:grid;grid-template-columns:78px minmax(0,1fr);gap:8px;margin:20px 0}.proxy-facts dt{color:var(--muted)}.proxy-facts dd{margin:0;overflow-wrap:anywhere}.proxy-revoke,.proxy-revoked{border-top:1px solid var(--line);padding-top:18px;margin-top:20px}.proxy-manager h3{font-size:14px;margin:0}.proxy-revoke p{margin:0}.proxy-revoke>button{align-self:flex-start}.proxy-error{color:var(--red);overflow-wrap:anywhere}.proxy-warning{color:#865c16;background:#fbf5e8;padding:12px;border-radius:6px}.proxy-notice{color:var(--deep)}.proxy-manager button:disabled{opacity:.5;cursor:not-allowed}.proxy-revoked p{overflow-wrap:anywhere}.proxy-manager .quiet{padding:5px 0;text-decoration:underline;text-underline-offset:3px;text-align:left}
@media(max-width:950px){.proxy-grid{grid-template-columns:minmax(0,1fr)}.proxy-pair{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:550px){.proxy-manager .page-heading{flex-direction:column;align-items:stretch}.proxy-list,.proxy-editor{padding:16px}.proxy-pair{grid-template-columns:minmax(0,1fr)}.proxy-tools{align-items:stretch}.proxy-filter{flex-basis:100%}.proxy-definition-search{align-items:stretch}.proxy-identity{gap:10px}.proxy-identity strong{font-size:17px}.proxy-actions{align-items:stretch;flex-direction:column}.proxy-period{padding:12px}.proxy-detail-heading{gap:8px}}
</style>
