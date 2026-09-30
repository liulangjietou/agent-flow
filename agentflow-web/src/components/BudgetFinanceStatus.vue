<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import type { BudgetAdjustmentContent } from '../budgetAdjustment'
import BudgetFinancePositions from './BudgetFinancePositions.vue'
import { budgetActionLabels, budgetActionAllowed, budgetFinanceInput, validateBudgetFinance, validateBudgetFinancePage, validateBudgetFinanceReceipt, budgetFinanceError, budgetIssueLabels, budgetReviewLabels, budgetOperationLabels, budgetResultLabels, type BudgetFinanceAction, type BudgetFinanceView, type BudgetFinanceOperation } from '../budgetFinance'

const props = defineProps<{ requestId: string; applicationId: string; roundNo: number; applicationVersion: number; requestVersion: number; content: BudgetAdjustmentContent; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const view = ref<BudgetFinanceView | null>(null), loading = ref(false), saving = ref(false), requiresRefresh = ref(false), unconfirmed = ref(false)
const error = ref(''), notice = ref(''), pending = ref<BudgetFinanceAction | null>(null), comment = ref(''), form = ref<HTMLFormElement | null>(null)
const history = ref<BudgetFinanceOperation[]>([]), historyLoaded = ref(false), historyLoading = ref(false), historyError = ref(''), nextBefore = ref<string | null>(null)
const selected = ref<string | undefined>()
let epoch = 0, controller: AbortController | null = null, historyController: AbortController | null = null
const now = ref(Date.now()), ticker = setInterval(() => { now.value = Date.now() }, 1000)
function syncPending() {
  const active = writeRequests.pending().some(entry => entry.path.startsWith(`/budget-adjustments/${encodeURIComponent(props.requestId)}/execution/`) || entry.path.startsWith('/budget-adjustment-operations/'))
  if (unconfirmed.value && !active) requiresRefresh.value = true
  unconfirmed.value = active
}
const unsubscribe = writeRequests.subscribe(syncPending)
const blocked = computed(() => !!props.locked || loading.value || historyLoading.value || saving.value || requiresRefresh.value || unconfirmed.value)
function stop() { epoch++; controller?.abort(); historyController?.abort(); controller = historyController = null; historyLoading.value = false }
/** 任一接口确认范围已失效后，旧台账、历史和确认输入必须一起撤下。 */
function clearDeniedScope(cause: unknown) {
  if (![401, 403, 404].includes((cause as { status?: number } | null)?.status ?? 0)) return
  stop(); view.value = null; history.value = []; historyLoaded.value = false; nextBefore.value = null
  pending.value = null; comment.value = ''; requiresRefresh.value = true; loading.value = false; saving.value = false; emit('busy', false)
}
/** 原身份、批准版本和所选指令变化时，立即清除旧额度和操作按钮。 */
async function load(operationId?: string) {
  if (saving.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  view.value = null; pending.value = null; comment.value = ''; error.value = ''; selected.value = operationId
  const binding = { requestId: props.requestId, applicationId: props.applicationId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, requestVersion: props.requestVersion }
  const content = JSON.parse(JSON.stringify(props.content)) as BudgetAdjustmentContent
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '预算办理状态读取超时，请重新查询。' } }, 12_000)
  try {
    const result = await api.budgetFinance(binding.requestId, binding.roundNo, operationId, request.signal)
    if (current !== epoch) return
    view.value = validateBudgetFinance(result, binding, content, operationId); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) { clearDeniedScope(cause); error.value = budgetFinanceError(cause) } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
/** 历史只按当前申请与轮次翻页，任何迟到响应都不能跨身份继续追加。 */
async function loadHistory(more = false) {
  if (historyLoading.value || loading.value || saving.value || !props.scopeKey || more && !nextBefore.value) return
  const current = epoch, request = new AbortController(); historyController?.abort(); historyController = request; historyLoading.value = true; historyError.value = ''
  const cursor = more ? nextBefore.value! : undefined
  const timeout = setTimeout(() => { if (current === epoch && historyController === request) { request.abort(); historyController = null; historyLoading.value = false; historyError.value = '预算执行历史读取超时，请重试。' } }, 12_000)
  try {
    const result = await api.budgetFinanceHistory(props.requestId, props.roundNo, cursor, request.signal)
    if (current !== epoch || historyController !== request) return
    validateBudgetFinancePage(result, props.content)
    const items = more ? [...history.value, ...result.items] : result.items
    if (new Set(items.map(value => value.id)).size !== items.length || result.nextBefore === cursor) throw new Error('预算执行历史游标重复，请重新读取。')
    history.value = items; nextBefore.value = result.nextBefore; historyLoaded.value = true
  } catch (cause) { if (current === epoch && historyController === request) { clearDeniedScope(cause); historyError.value = budgetFinanceError(cause) } }
  finally { clearTimeout(timeout); if (current === epoch && historyController === request) { historyLoading.value = false; historyController = null } }
}
function allowed(action: BudgetFinanceAction) { return budgetActionAllowed(view.value, action, now.value) }
function prepare(action: BudgetFinanceAction) {
  if (blocked.value || !allowed(action)) return
  pending.value = action; comment.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector('textarea')?.focus() })
}
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  let input
  try { input = budgetFinanceInput(value, props.content, action, comment.value) } catch (cause) { error.value = budgetFinanceError(cause); return }
  const current = epoch; saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = 'reviewId' in input ? await api.authorizeBudgetAdjustment(value.requestId, input) : 'roundNo' in input ? await api.reviewBudgetLedger(value.requestId, input) : await api.budgetOperationAction(value.operation!.id, input)
    if (current !== epoch) return
    validateBudgetFinanceReceipt(receipt, value, action)
    saving.value = false; pending.value = null; emit('busy', false)
    notice.value = action === 'REVIEW' ? '台账读取已登记，刷新后核对两端额度，再明确授权。' : action === 'AUTHORIZE' ? '本次预算调整授权已保存，请刷新核对实际执行结果。' : action === 'RETIRE' ? '原指令已安全结束，另行授权前需要重新读取台账。' : '原指令处理已登记，请刷新核对结果。'
    history.value = []; historyLoaded.value = false; nextBefore.value = null; await load()
  } catch (cause) { if (current === epoch) { clearDeniedScope(cause); requiresRefresh.value = true; error.value = budgetFinanceError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.requestId, props.applicationId, props.roundNo, props.applicationVersion, props.requestVersion, props.content]), () => {
  stop(); view.value = null; loading.value = false; saving.value = false; requiresRefresh.value = false; pending.value = null; comment.value = ''; error.value = ''; notice.value = ''; selected.value = undefined
  history.value = []; historyLoaded.value = false; historyError.value = ''; nextBefore.value = null
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); clearInterval(ticker); unsubscribe(); emit('busy', false) })
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="budget-finance" aria-label="预算调整财务办理">
    <div class="budget-finance-heading"><div><p class="budget-finance-eyebrow">第 {{ roundNo }} 轮批准 · 财务办理</p><h3>预算调整执行</h3></div><button class="quiet" type="button" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新最新办理状态</button></div>
    <p class="budget-finance-help">批准后重新核对最新台账，再确认额度调整。调拨两端共同成功才记为已生效。</p>
    <p v-if="loading" role="status" class="budget-finance-help">正在核对本轮权限与原指令状态…</p>
    <p v-if="error" role="alert" class="budget-finance-error">{{ error }}</p><p v-if="notice" role="status" class="budget-finance-help">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="budget-finance-error">预算办理有尚未确认的操作，请在未确认操作中恢复原请求后刷新。</p>
    <p v-if="requiresRefresh && !unconfirmed && !error" role="status" class="budget-finance-help">原记录已变化，请刷新核对办理状态。</p>
    <template v-if="view">
      <div class="budget-finance-overview"><div><span>本轮批准调整金额</span><strong>{{ content.amount.currency }} {{ content.amount.value }}</strong></div><div><span>{{ selected ? '所选原指令' : '最新原指令' }}</span><strong>{{ view.operation ? budgetOperationLabels[view.operation.status] : '尚未授权执行' }}</strong></div></div>
      <p v-if="!view.destinationReady" class="budget-finance-help">本轮批准对应的财务目标暂不可用，请联系管理员核对。</p>
      <article v-if="view.review" class="budget-finance-evidence" aria-label="本人最新预算台账复核">
        <h4>本人最新台账 · {{ budgetReviewLabels[view.review.status] }}</h4>
        <BudgetFinancePositions v-if="view.review.positions.length" :positions="view.review.positions" label="本次复核的原额度与目标额度" />
        <p v-if="view.review.validUntil" class="budget-finance-help">本次依据有效至 {{ date(view.review.validUntil) }}<span v-if="view.review.status === 'READY' && Date.parse(view.review.validUntil) <= now"> · 已到期，请重新读取</span></p>
        <p v-if="view.review.issue" class="budget-finance-help">{{ budgetIssueLabels[view.review.issue] }}</p>
      </article>
      <article v-if="view.operation" class="budget-finance-evidence" aria-label="原预算调整授权与结果">
        <h4>{{ view.operation.retirement ? '已结束的原授权' : '原调整授权' }}</h4>
        <p class="budget-finance-help">{{ view.operation.authorizedBy }} · {{ date(view.operation.authorizedAt) }}<br />发送有效至 {{ date(view.operation.expiresAt) }}<br />原指令 {{ view.operation.id }}</p>
        <p class="budget-finance-help">授权说明：{{ view.operation.reason }}</p>
        <BudgetFinancePositions :positions="view.operation.positions" label="原授权依据，保留当时额度" />
        <p v-if="view.operation.retirement" class="budget-finance-help">{{ date(view.operation.retirement.retiredAt) }} · {{ view.operation.retirement.retiredBy }} · {{ view.operation.retirement.basis === 'NEVER_SENT' ? '原指令从未发送，已安全结束' : '原系统明确拒绝，已安全结束' }}</p>
        <p v-if="view.operation.observation" class="budget-finance-help">已记录的原系统结果：{{ budgetResultLabels[view.operation.observation.status] }} · {{ date(view.operation.observation.observedAt) }}<span v-if="view.operation.observation.appliedAt"><br />生效时间 {{ date(view.operation.observation.appliedAt) }}</span><span v-if="view.operation.observation.reference"><br />原系统凭据 {{ view.operation.observation.reference }}</span><span v-if="view.operation.observation.rejection"><br />{{ budgetIssueLabels[view.operation.observation.rejection] }}</span></p>
        <p v-if="view.operation.conflictingObservation" class="budget-finance-error" role="alert">另一次查询返回“{{ budgetResultLabels[view.operation.conflictingObservation.status] }}”，与已保存结果矛盾。请核对原系统，当前不能另行授权。</p>
        <p v-if="view.operation.failure" class="budget-finance-help">{{ budgetIssueLabels[view.operation.failure] }}</p>
      </article>
      <div v-if="!pending" class="budget-finance-buttons"><button v-for="action in (['REVIEW', 'AUTHORIZE', 'QUERY', 'RETRY', 'RETIRE'] as const)" v-show="allowed(action)" :key="action" type="button" :class="action === 'AUTHORIZE' ? 'primary' : 'quiet'" :disabled="blocked" @click="prepare(action)">{{ budgetActionLabels[action] }}</button></div>
      <form v-else ref="form" class="budget-finance-confirm" @submit.prevent="execute">
        <h4>{{ budgetActionLabels[pending] }}</h4>
        <p v-if="pending === 'REVIEW'">读取本轮批准对应的最新预算台账。读取完成后，核对已占用、已使用和本次目标额度，再确认授权。</p>
        <p v-else-if="pending === 'AUTHORIZE'">确认本次调整 {{ content.amount.currency }} {{ content.amount.value }}，按上方本人最新台账所列目标额度执行。必须在本次依据有效期内发送，调拨两端共同办理。</p>
        <p v-else-if="pending === 'RETIRE'">依据原指令从未发送或原系统明确拒绝的记录，安全结束本次执行。历史记录保留，再次授权需要重新读取台账。</p>
        <p v-else-if="pending === 'RETRY'">已查询且暂未查到原指令。确认使用同一原编号、同一额度与版本重发，原授权有效期保持不变。</p>
        <p v-else>查询同一原指令，核对实际结果。查询不会产生新的预算调整。</p>
        <label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving" /></label>
        <div class="budget-finance-buttons"><button type="button" class="quiet" :disabled="saving" @click="pending = null">返回核对</button><button type="submit" class="primary" :disabled="blocked || !allowed(pending)">{{ saving ? '正在保存…' : '确认并提交' }}</button></div>
      </form>
    </template>
    <div class="budget-finance-history"><button type="button" class="quiet" :disabled="loading || saving || historyLoading || locked" @click="loadHistory()">{{ historyLoading ? '正在读取历史…' : '查看本轮执行历史' }}</button>
      <p v-if="historyError" class="budget-finance-error" role="alert">{{ historyError }}</p><p v-if="historyLoaded && !history.length" class="budget-finance-help">本轮尚无预算执行记录。</p>
      <ul v-if="history.length"><li v-for="operation in history" :key="operation.id"><button type="button" class="budget-history-record" :aria-pressed="selected === operation.id" :disabled="loading || saving || locked" @click="load(operation.id)"><strong>{{ operation.retirement ? '已安全结束' : budgetOperationLabels[operation.status] }}</strong><span>{{ date(operation.authorizedAt) }} · {{ operation.authorizedBy }}</span><span>{{ operation.id }}</span></button></li></ul>
      <button v-if="nextBefore" type="button" class="quiet" :disabled="historyLoading || loading || saving || locked" @click="loadHistory(true)">继续读取历史</button>
    </div>
  </section>
</template>

<style scoped>
.budget-finance{margin-top:26px;padding:22px;border:1px solid var(--line);border-radius:12px;background:var(--paper);min-width:0}.budget-finance-heading{display:flex;justify-content:space-between;gap:14px;align-items:center}.budget-finance-heading h3{font-size:17px;margin:4px 0}.budget-finance-eyebrow{color:var(--muted);font-size:11px;margin:0}.budget-finance-help{font-size:12px;color:var(--muted);line-height:1.9;overflow-wrap:anywhere}.budget-finance-overview{display:grid;grid-template-columns:1fr 1fr;gap:20px;border-block:1px solid var(--line);padding:20px 0;margin-top:18px}.budget-finance-overview div{display:grid;gap:8px}.budget-finance-overview span{font-size:11px;color:var(--muted)}.budget-finance-overview strong{font-size:17px;font-variant-numeric:tabular-nums}.budget-finance-evidence{padding:16px 0;border-bottom:1px solid var(--line)}.budget-finance-evidence h4,.budget-finance-confirm h4{font-size:13px;margin:0 0 8px}.budget-finance-error{padding:12px;background:#fff0ed;color:var(--red);font-size:12px;line-height:1.8;border-radius:7px}.budget-finance-buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}.budget-finance-confirm{margin-top:18px;padding:16px;border:1px solid var(--line);border-radius:8px;font-size:12px;line-height:1.8}.budget-finance-confirm label{display:grid;gap:8px}.budget-finance-confirm textarea{width:100%;padding:12px;border:1px solid var(--line);border-radius:7px;resize:vertical;font:inherit}.budget-finance-history{margin-top:22px}.budget-finance-history ul{list-style:none;padding:0;display:grid;gap:8px}.budget-history-record{width:100%;display:grid;gap:6px;text-align:left;padding:12px;border:1px solid var(--line);border-radius:7px;background:transparent;overflow-wrap:anywhere;font-size:12px}.budget-history-record span{font-size:11px;color:var(--muted)}.budget-history-record[aria-pressed=true]{border-color:var(--ink)}@media(max-width:600px){.budget-finance{padding:16px}.budget-finance-heading{align-items:flex-start}.budget-finance-overview{grid-template-columns:1fr}}
</style>
