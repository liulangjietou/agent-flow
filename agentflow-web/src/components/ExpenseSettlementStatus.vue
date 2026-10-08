<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import ExpensePaymentReturn from './ExpensePaymentReturn.vue'
import ExpenseResourceAdjustment from './ExpenseResourceAdjustment.vue'
import ExpensePartialAdjustment from './ExpensePartialAdjustment.vue'
import { settlementLabels, settlementFundingLabels, settlementBudgetLabels, settlementIssue, settlementError, settlementRetry, validateSettlement, validateSettlementReceipt, type SettlementBinding, type SettlementView } from '../expenseSettlement'
const props = defineProps<{ reportId: string; applicationId: string; roundNo: number; applicationVersion: number; financialVersion: number; scopeKey: string; locked?: boolean; revoked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const returnBusy = ref(false), adjustmentBusy = ref(false), partialBusy = ref(false)
const view = ref<SettlementView | null>(null), loading = ref(false), saving = ref(false), requiresRefresh = ref(false), unconfirmed = ref(false), confirming = ref(false)
const error = ref(''), notice = ref(''), comment = ref(''), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const path = () => `/expense-reports/${encodeURIComponent(props.reportId)}/settlement/retry`
function syncPending() {
  const pending = writeRequests.pending().some(entry => entry.path === path())
  if (unconfirmed.value && !pending) requiresRefresh.value = true
  unconfirmed.value = pending
}
const unsubscribe = writeRequests.subscribe(syncPending)
const blocked = computed(() => !!props.locked || loading.value || saving.value || returnBusy.value || adjustmentBusy.value || partialBusy.value || requiresRefresh.value || unconfirmed.value)
function stop() { epoch++; controller?.abort(); controller = null }
/** 身份、轮次或版本切换后丢弃迟到状态；读取失败不能保留旧财务按钮。 */
async function load() {
  if (saving.value || returnBusy.value || adjustmentBusy.value || partialBusy.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  view.value = null; confirming.value = false; comment.value = ''; error.value = ''
  const binding: SettlementBinding = { reportId: props.reportId, applicationId: props.applicationId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, financialVersion: props.financialVersion }
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '结算状态读取超时，请重新查询。' } }, 12_000)
  try {
    const result = await api.expenseSettlement(binding.reportId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validateSettlement(result, binding); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) error.value = settlementError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function prepare() {
  if (blocked.value || !view.value?.canRetry) return
  confirming.value = true; comment.value = ''; notice.value = ''; error.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector('textarea')?.focus() })
}
/** 写入只在人工确认后发送，未知结果交给原幂等请求恢复。 */
async function execute() {
  const value = view.value; if (!value || !confirming.value || blocked.value) return
  let input
  try { input = settlementRetry(value, comment.value) } catch (cause) { error.value = settlementError(cause); return }
  const current = epoch; saving.value = true; emit('busy', true); error.value = ''
  try {
    const result = await api.retryExpenseSettlement(value.reportId, input)
    if (current !== epoch) return
    validateSettlementReceipt(result, value, input); saving.value = false; confirming.value = false; emit('busy', false)
    notice.value = '结算重试已登记，请核对后续资源和预算处理进度。'; await load()
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = settlementError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.reportId, props.applicationId, props.roundNo, props.applicationVersion, props.financialVersion]), () => {
  stop(); view.value = null; loading.value = false; saving.value = false; returnBusy.value = false; adjustmentBusy.value = false; partialBusy.value = false; confirming.value = false; error.value = ''; notice.value = ''; comment.value = ''; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="settlement" aria-label="本轮报销结算">
    <div class="settlement-heading"><h3>第 {{ roundNo }} 轮 · 报销结算</h3><button type="button" class="quiet" :disabled="loading || saving || returnBusy || adjustmentBusy || partialBusy || locked" @click="notice = ''; load()">刷新结算状态</button></div>
    <p class="settlement-help">原结算记录本轮发票、额度、借款冲销及预算实际占用。后续取消结果在独立调整中分别显示。</p>
    <p v-if="loading" class="settlement-help" role="status">正在核对本轮结算进度与权限…</p>
    <p v-if="error" class="settlement-error" role="alert">{{ error }}</p><p v-if="notice" class="settlement-help" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="settlement-error" role="alert">上次重试结果尚未确认，请在未确认操作中恢复原请求后刷新。</p>
    <p v-if="requiresRefresh && !unconfirmed && !error" class="settlement-help" role="status">请刷新结算状态，核对原请求恢复后的结果。</p>
    <template v-if="view">
      <p v-if="!view.settlement" class="settlement-help">{{ revoked ? '审批已撤销，本轮没有核销记录。原金融结果以实际记录为准。' : '尚未登记本轮核销，正在等待付款到账或零应付结算依据。' }}</p>
      <template v-else>
        <strong class="settlement-status" :class="{ complete: view.settlement.status === 'SETTLED' }">{{ settlementLabels[view.settlement.status] }}</strong>
        <div class="settlement-stages">
          <article><small>结算依据</small><p>{{ settlementFundingLabels[view.settlement.funding] }}</p></article>
          <article><small>发票、额度与借款</small><p>{{ view.settlement.resourcesConsumed ? '原核销记录已保留' : '本轮资源尚未核销' }}</p></article>
          <article><small>预算实际占用</small><p>{{ view.settlement.budgetStatus ? settlementBudgetLabels[view.settlement.budgetStatus] : '尚未登记本次预算核销' }}</p></article>
        </div>
        <p v-if="view.settlement.issue" class="settlement-error" role="alert">{{ settlementIssue(view.settlement.issue) }}</p>
        <p class="settlement-help">最近更新：{{ new Date(view.settlement.updatedAt).toLocaleString('zh-CN') }}</p>
      </template>
      <button v-if="view.canRetry && !confirming" type="button" class="primary" :disabled="blocked" @click="prepare">重新办理未完成的核销</button>
      <form v-if="confirming" ref="form" class="settlement-confirm" @submit.prevent="execute">
        <p>确认相关问题已处理。本次仅办理未完成部分，已核销的资源会保留原记录。</p>
        <label>处理说明<textarea v-model="comment" rows="3" required maxlength="2000" :disabled="saving" placeholder="说明已核对的依据及处理结果" /></label>
        <div><button class="primary" :disabled="blocked">{{ saving ? '正在登记…' : '确认重新办理' }}</button><button type="button" class="quiet" :disabled="saving" @click="confirming = false; comment = ''">取消</button></div>
      </form>
      <ExpensePaymentReturn v-if="view.settlement?.funding === 'PAYMENT'" :application-id="applicationId" :report-id="reportId" :round-no="roundNo" :scope-key="scopeKey" :locked="locked || saving || adjustmentBusy || partialBusy" @busy="returnBusy = $event; emit('busy', $event || adjustmentBusy || partialBusy)" @changed="emit('changed')" />
      <ExpenseResourceAdjustment v-if="view.settlement?.resourcesConsumed" :application-id="applicationId" :report-id="reportId" :round-no="roundNo" :application-version="applicationVersion" :financial-version="financialVersion" :scope-key="scopeKey" :locked="locked || saving || returnBusy || partialBusy" @busy="adjustmentBusy = $event; emit('busy', $event || returnBusy || partialBusy)" @changed="emit('changed')" />
      <ExpensePartialAdjustment v-if="view.settlement?.resourcesConsumed && view.settlement.funding !== 'ZERO_AMOUNT'" :application-id="applicationId" :report-id="reportId" :round-no="roundNo" :application-version="applicationVersion" :financial-version="financialVersion" :scope-key="scopeKey" :locked="locked || saving || returnBusy || adjustmentBusy" @busy="partialBusy = $event; emit('busy', $event || returnBusy || adjustmentBusy)" @changed="emit('changed')" />
    </template>
  </section>
</template>

<style scoped>
.settlement{margin-top:24px;padding-top:20px;border-top:1px solid var(--line)}.settlement-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.settlement-heading h3{font-size:15px;margin:0}.settlement-help,.settlement-error{font-size:12px;line-height:1.8}.settlement-help{color:var(--muted)}.settlement-error{color:#9d3d2b;background:#fff0ed;border-radius:8px;padding:12px}.settlement-status{display:block;font-size:14px;margin:16px 0}.settlement-status.complete{color:var(--teal)}.settlement-stages{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.settlement-stages article{border:1px solid var(--line);border-radius:9px;background:var(--paper);padding:14px}.settlement-stages small{font-size:11px;color:var(--muted)}.settlement-stages p{font-size:12px;line-height:1.7;margin:8px 0 0;overflow-wrap:anywhere}.settlement-confirm{background:var(--paper);border:1px solid var(--line);border-radius:10px;padding:16px;font-size:12px;line-height:1.8}.settlement-confirm label{display:grid;gap:8px}.settlement-confirm textarea{width:100%;padding:10px;border:1px solid var(--line);border-radius:7px;resize:vertical;font:inherit}.settlement-confirm div{display:flex;gap:10px;margin-top:12px;flex-wrap:wrap}.settlement button{min-height:40px}@media(max-width:600px){.settlement-stages{grid-template-columns:1fr}}
</style>
