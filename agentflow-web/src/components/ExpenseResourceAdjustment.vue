<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { moneyLabel } from '../expenses'
import { adjustmentLabels, adjustmentBudgetLabels, adjustmentPreparationLabels, adjustmentIntentLabels, adjustmentIssue, adjustmentError, validateAdjustment,
  adjustmentPrepareInput, adjustmentAuthorizeInput, adjustmentOperationInput, adjustmentRetireInput, validateAdjustmentPreparationReceipt, validateAdjustmentActionReceipt,
  type AdjustmentView, type AdjustmentIntent } from '../expenseResourceAdjustment'
const props = defineProps<{ applicationId: string; reportId: string; roundNo: number; applicationVersion: number; financialVersion: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<AdjustmentView | null>(null), loading = ref(false), saving = ref(false), pending = ref<AdjustmentIntent | null>(null), selected = ref('')
const accountingDate = ref(''), reference = ref(''), comment = ref(''), acknowledged = ref(false), error = ref(''), notice = ref(''), requiresRefresh = ref(false), unconfirmed = ref(false), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || requiresRefresh.value || unconfirmed.value)
const selectedEntry = computed(() => view.value?.adjustments.find(item => item.id === selected.value))
function stop() { epoch++; controller?.abort(); controller = null }
function cancel() { pending.value = null; selected.value = ''; accountingDate.value = ''; reference.value = ''; comment.value = ''; acknowledged.value = false }
function clear() { view.value = null; cancel() }
function syncPending() {
  const prefix = `/expense-reports/${encodeURIComponent(props.reportId)}/resource-adjustment/`
  const current = writeRequests.pending().some(entry => entry.path.startsWith(prefix))
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
function signature(value: AdjustmentView) { return JSON.stringify(value.adjustments.map(item => [item.id, item.version, item.resourcesReversed, item.budget.version])) }
/** 来源变化仍显示已授权历史，身份切换或失权则清除敏感内容和迟到响应。 */
async function load() {
  if (saving.value || loading.value || !props.scopeKey) return
  const previous = view.value; stop(); const current = epoch, request = new AbortController(); controller = request
  const binding = { reportId: props.reportId, applicationId: props.applicationId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, businessVersion: props.financialVersion }
  loading.value = true; clear(); error.value = ''; let changed = false
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; requiresRefresh.value = true; error.value = '独立调整读取超时，请刷新核对。' } }, 12_000)
  try {
    const result = await api.expenseResourceAdjustment(binding.reportId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validateAdjustment(result, binding); requiresRefresh.value = false; syncPending()
    changed = !!previous && signature(previous) !== signature(view.value)
  } catch (cause) { if (current === epoch) { clear(); error.value = adjustmentError(cause); requiresRefresh.value = true } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null; if (changed) emit('changed') } }
}
function prepare(intent: AdjustmentIntent, id = '') {
  const value = view.value; if (!value || blocked.value) return
  const entry = value.adjustments.find(item => item.id === id)
  const allowed = intent === 'PREPARE' ? value.canPrepare : intent === 'AUTHORIZE' ? value.latestPreparation?.canAuthorize : intent === 'RETIRE' ? entry?.canRetire : entry?.availableActions.includes(intent)
  if (!value.finance || !allowed) return
  cancel(); pending.value = intent; selected.value = id; error.value = ''; notice.value = ''
  if (intent === 'PREPARE') {
    const now = new Date(); accountingDate.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  }
  const current = epoch; void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')?.focus() })
}
/** 所有动作都先展示意图，授权、重发、恢复与结束须明确勾选，未知请求沿原幂等键恢复。 */
async function execute() {
  const value = view.value, intent = pending.value; if (!value || !intent || blocked.value) return
  if (intent !== 'PREPARE' && intent !== 'QUERY' && !acknowledged.value) { error.value = '请确认已核对原财务依据和本次操作。'; return }
  let submit: () => Promise<void>
  try {
    if (intent === 'PREPARE') {
      const input = adjustmentPrepareInput(value, accountingDate.value, reference.value, comment.value)
      submit = async () => validateAdjustmentPreparationReceipt(await api.prepareExpenseResourceAdjustment(value.reportId, input), value, input)
    } else if (intent === 'AUTHORIZE') {
      const input = adjustmentAuthorizeInput(value, comment.value)
      submit = async () => validateAdjustmentPreparationReceipt(await api.authorizeExpenseResourceAdjustment(value.reportId, input), value, input)
    } else if (intent === 'RETIRE') {
      const input = adjustmentRetireInput(value, selected.value, reference.value, comment.value)
      submit = async () => validateAdjustmentActionReceipt(await api.retireExpenseResourceAdjustment(value.reportId, input), value, input)
    } else {
      const input = adjustmentOperationInput(value, selected.value, intent, comment.value)
      submit = async () => validateAdjustmentActionReceipt(await api.actExpenseResourceAdjustment(value.reportId, input), value, input)
    }
  } catch (cause) { error.value = adjustmentError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    await submit(); if (current !== epoch) return
    cancel(); saving.value = false; emit('busy', false)
    notice.value = intent === 'PREPARE' ? '期间读取已登记，准备就绪后仍需明确授权。' : intent === 'AUTHORIZE' ? '独立调整已授权，请刷新核对预算和资源两个结果。' : '本次办理已保存，请刷新核对原调整结果。'
    await load()
  } catch (cause) {
    if (current === epoch) { if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) clear(); error.value = adjustmentError(cause); requiresRefresh.value = true }
  } finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.reportId, props.applicationId, props.roundNo, props.applicationVersion, props.financialVersion]), () => {
  stop(); clear(); loading.value = false; saving.value = false; error.value = ''; notice.value = ''; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="resource-adjustment" aria-label="报销独立资源调整">
    <div class="heading"><h4>报销独立资源调整</h4><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新独立调整</button></div>
    <p>整笔报销取消须先核清挂账冲销与全部实际退回，再独立冲正预算并冲回资源。原批准、付款、核销及归档记录保留。</p>
    <p v-if="loading" role="status">正在核对原报销与独立调整…</p><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="error" role="alert">上次调整请求尚未确认，请先恢复原操作，再刷新核对。</p>
    <p v-else-if="requiresRefresh && !error" role="status">请刷新独立调整，核对原请求恢复后的结果。</p>
    <template v-if="view">
      <div class="amounts"><article><small>原批准报销总额</small><strong>{{ moneyLabel(view.original.gross) }}</strong></article><article><small>原借款冲销</small><strong>{{ moneyLabel(view.original.offsets) }}</strong></article><article><small>原应付金额</small><strong>{{ moneyLabel(view.original.payable) }}</strong></article></div>
      <p v-if="!view.adjustments.length">尚无独立调整记录。</p>
      <article v-for="entry in view.adjustments" :key="entry.id" class="entry">
        <div class="heading"><strong :class="{ complete: entry.status === 'APPLIED' }">{{ adjustmentLabels[entry.status] }}</strong><small>{{ new Date(entry.updatedAt).toLocaleString() }}</small></div>
        <dl><div><dt>预算冲正</dt><dd>{{ adjustmentBudgetLabels[entry.budget.status] }}</dd></div><div><dt>本地资源</dt><dd>{{ entry.resourcesReversed ? '已冲回，原核销明细保留' : '尚未冲回' }}</dd></div><div><dt>本次记账日期</dt><dd>{{ entry.accountingDate }}</dd></div><div><dt>授权人</dt><dd>{{ entry.authorizedBy }}</dd></div></dl>
        <p v-if="entry.budget.acceptedReference">已接受的预算回执：{{ entry.budget.acceptedReference }} · {{ new Date(entry.budget.acceptedAt!).toLocaleString() }}</p>
        <p v-if="entry.resourcesReversed" class="result">本次涉及的资源已按原核销明细冲回。发票再次使用前需重新查验；事前申请的关闭状态和借款的独立冻结保留。</p>
        <p v-if="entry.issue" class="error" role="status">{{ adjustmentIssue(entry.issue) }}</p><p v-if="entry.budget.issue">{{ adjustmentIssue(entry.budget.issue) }}</p>
        <p>材料 {{ entry.evidenceReference }} · {{ entry.reason }}</p>
        <p v-if="entry.retirement">结束人 {{ entry.retirement.retiredBy }} · 材料 {{ entry.retirement.evidenceReference }}<br />{{ entry.retirement.reason }}</p>
        <details><summary>查看本次调整编号和授权期限</summary><p>{{ entry.id }}<br />授权于 {{ new Date(entry.authorizedAt).toLocaleString() }}<br />原命令发送有效至 {{ new Date(entry.budget.expiresAt).toLocaleString() }}；查询原结果不受此期限限制。</p></details>
        <div v-if="!pending" class="buttons"><button v-for="action in entry.availableActions" :key="action" type="button" class="quiet" :disabled="blocked" @click="prepare(action, entry.id)">{{ adjustmentIntentLabels[action] }}</button><button v-if="entry.canRetire" type="button" class="quiet" :disabled="blocked" @click="prepare('RETIRE', entry.id)">安全结束本次调整</button></div>
      </article>
      <article v-if="view.latestPreparation" class="preparation">
        <strong>{{ adjustmentPreparationLabels[view.latestPreparation.status] }}</strong>
        <p>记账日期 {{ view.latestPreparation.accountingDate }} · 材料 {{ view.latestPreparation.evidenceReference }}<br />{{ view.latestPreparation.reason }}</p>
        <p v-if="view.latestPreparation.periodReference">开放期间 {{ view.latestPreparation.periodReference }} · 有效至 {{ new Date(view.latestPreparation.expiresAt!).toLocaleString() }}</p>
        <p v-if="view.latestPreparation.issue">{{ adjustmentIssue(view.latestPreparation.issue) }}</p>
        <p v-else-if="view.latestPreparation.status === 'READY' && view.latestPreparation.authorizationIssue">{{ adjustmentIssue(view.latestPreparation.authorizationIssue) }}</p>
        <button v-if="view.latestPreparation.canAuthorize && !pending" type="button" class="primary" :disabled="blocked" @click="prepare('AUTHORIZE')">核对并授权独立调整</button>
      </article>
      <p v-if="view.finance && !view.canPrepare && view.preparationIssue && view.adjustments.length === 0">{{ adjustmentIssue(view.preparationIssue) }}</p>
      <div v-if="view.canPrepare && !pending" class="buttons"><button type="button" class="quiet" :disabled="blocked" @click="prepare('PREPARE')">准备全额取消依据</button></div>
      <form v-if="pending" ref="form" @submit.prevent="execute">
        <h4>{{ adjustmentIntentLabels[pending] }}</h4>
        <p v-if="pending === 'PREPARE'">选择本次记账日期并登记完整取消原因。此步骤只读取开放会计期间。</p>
        <p v-else-if="pending === 'AUTHORIZE'">确认原挂账已完整冲销、正额付款已全部退回。授权后将冲正原预算实际占用，确认成功后再冲回发票、事前额度及借款核销。</p>
        <p v-else-if="pending === 'QUERY'">只查询原预算命令。已接受的预算及资源事实保留，复核后仍需明确确认。</p>
        <p v-else-if="pending === 'RESEND_ORIGINAL'">外部系统已明确查无。此次使用同一个命令和原有效期限再次发送，不生成新编号。</p>
        <p v-else-if="pending === 'RETRY_RESOURCES'">原预算已确认成功，此次只恢复尚未完成的本地资源冲回。</p>
        <p v-else-if="pending === 'CONFIRM_COMPLETED'">确认原预算成功仍然有效，原资源冲回明细保持，不再次冲回。</p>
        <p v-else>确认本次预算命令从未发送或已明确拒绝且没有副作用。原授权及结束证明保留，结束后才可重新准备。</p>
        <p v-if="selectedEntry">办理调整 {{ selectedEntry.id }}</p>
        <label v-if="pending === 'PREPARE'">本次记账日期<input v-model="accountingDate" type="date" required :disabled="saving" /></label>
        <label v-if="pending === 'PREPARE' || pending === 'RETIRE'">核验材料编号<input v-model="reference" maxlength="128" required :disabled="saving" /></label>
        <label>办理说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <label v-if="pending !== 'PREPARE' && pending !== 'QUERY'" class="ack"><input v-model="acknowledged" type="checkbox" required :disabled="saving" />已核对原财务依据，并确认本次操作</label>
        <div class="buttons"><button class="primary" :disabled="blocked || pending !== 'PREPARE' && pending !== 'QUERY' && !acknowledged">{{ saving ? '正在保存…' : pending === 'CONFIRM_COMPLETED' ? adjustmentIntentLabels[pending] : '确认' + adjustmentIntentLabels[pending] }}</button><button type="button" class="quiet" :disabled="saving" @click="cancel">取消</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.resource-adjustment{margin-top:18px;padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--paper);min-width:0;overflow-wrap:anywhere}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h4{font-size:13px;margin:0}p{font-size:12px;line-height:1.8}.heading small,dt{font-size:11px;color:var(--muted)}.error{color:var(--red)}.complete{color:var(--teal)}.result{padding:10px 12px;border-left:3px solid var(--teal);background:white}.amounts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:16px 0}.amounts article{padding:12px;border:1px solid var(--line);border-radius:8px;background:white}.amounts small{display:block;font-size:11px;color:var(--muted)}.amounts strong{display:block;margin-top:8px;font-size:15px;font-variant-numeric:tabular-nums}.entry,.preparation,form{margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}strong{font-size:13px}dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}dt{margin-bottom:6px}dd{margin:0;font-size:12px;font-variant-numeric:tabular-nums}label{display:grid;gap:7px;font-size:12px;margin-top:12px}input,textarea{width:100%;min-width:0;padding:10px;border:1px solid var(--line);border-radius:7px;background:white;font:inherit}textarea{resize:vertical}.ack{display:flex;align-items:center;gap:9px}.ack input{width:18px;height:18px;flex-shrink:0}.buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}button{min-height:40px}summary{font-size:12px;cursor:pointer;padding:9px 0}@media(max-width:650px){dl,.amounts{grid-template-columns:1fr}}
</style>
