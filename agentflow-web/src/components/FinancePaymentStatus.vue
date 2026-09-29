<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import PaymentFacts from './PaymentFacts.vue'
import { financePaymentInput, financePaymentLabels, financePaymentActionKeys, payeeReviewLabels, validateFinancePayment, validateFinancePaymentReceipt, validatePayeeReviewReceipt, paymentError, paymentIssue, type FinancePaymentAction, type FinancePaymentView, type PaymentBinding } from '../payments'
const props = defineProps<{ applicationId: string; businessId: string; roundNo: number; applicationVersion: number; businessVersion: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const view = ref<FinancePaymentView | null>(null), loading = ref(false), saving = ref(false), requiresRefresh = ref(false), unconfirmed = ref(false)
const error = ref(''), notice = ref(''), pending = ref<FinancePaymentAction | null>(null), comment = ref(''), validityMinutes = ref(15), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
function syncPending() {
  const current = writeRequests.pending().some(entry => entry.path === `/applications/${encodeURIComponent(props.applicationId)}/payments/authorizations` || entry.path.startsWith('/payments/'))
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
const blocked = computed(() => !!props.locked || loading.value || saving.value || requiresRefresh.value || unconfirmed.value)
function stop() { epoch++; controller?.abort(); controller = null }
/** 当前身份、轮次和双版本共同约束迟到响应，读取失败清空旧的付款动作。 */
async function load() {
  if (saving.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  view.value = null; pending.value = null; comment.value = ''; error.value = ''
  const binding: PaymentBinding = { applicationId: props.applicationId, businessId: props.businessId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, businessVersion: props.businessVersion }
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '付款状态读取超时，请重新查询。' } }, 12_000)
  try {
    const result = await api.financePayment(binding.applicationId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validateFinancePayment(result, binding); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) error.value = paymentError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function allowed(action: FinancePaymentAction) { return !!view.value?.actions[financePaymentActionKeys[action]] }
function prepare(action: FinancePaymentAction) {
  if (blocked.value || !allowed(action)) return
  pending.value = action; comment.value = ''; error.value = ''; notice.value = ''; const current = epoch
  void nextTick(() => { if (current === epoch) form.value?.querySelector('textarea')?.focus() })
}
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  let input
  try { input = financePaymentInput(value, action, comment.value, validityMinutes.value * 60) } catch (cause) { error.value = paymentError(cause); return }
  const current = epoch; saving.value = true; emit('busy', true); error.value = ''
  try {
    if ('roundNo' in input) {
      const result = await api.authorizePayment(value.applicationId, input); if (current !== epoch) return
      validateFinancePaymentReceipt(result, value, input)
    } else if ('action' in input) {
      const result = await api.financePaymentAction(value.payment!.id, input); if (current !== epoch) return
      validateFinancePaymentReceipt(result, value, input)
    } else {
      const result = await api.reviewPaymentPayee(value.payment!.id, input); if (current !== epoch) return
      validatePayeeReviewReceipt(result, value)
    }
    saving.value = false; pending.value = null; emit('busy', false)
    notice.value = action === 'REVIEW_ACCOUNT' ? '账户复核已登记，请刷新查看读取结果；核对后需要再次确认新授权。' : '财务决定已登记，请刷新核对实际付款进度。'; await load()
  } catch (cause) { if (current === epoch) { requiresRefresh.value = true; error.value = paymentError(cause) } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.applicationId, props.businessId, props.roundNo, props.applicationVersion, props.businessVersion]), () => {
  stop(); view.value = null; loading.value = false; saving.value = false; error.value = ''; notice.value = ''; pending.value = null; comment.value = ''; requiresRefresh.value = false; validityMinutes.value = 15
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="finance-payment" aria-label="本轮付款与授权">
    <div class="payment-heading"><h3>第 {{ roundNo }} 轮 · 付款</h3><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新付款状态</button></div>
    <p class="payment-help">财务授权后由独立出纳办理，到账以银行确认结果为准。</p>
    <p v-if="loading" role="status" class="payment-help">正在核对本轮付款权限与状态…</p>
    <p v-if="error" class="payment-error" role="alert">{{ error }}</p><p v-if="notice" class="payment-help" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="payment-error" role="alert">上次操作结果尚未确认，请在未确认操作中恢复原请求后刷新。</p>
    <p v-if="requiresRefresh && !unconfirmed && !error" class="payment-help" role="status">请刷新付款状态，核对恢复后的原操作。</p>
    <template v-if="view">
      <PaymentFacts v-if="view.payment" :payment="view.payment" />
      <p v-else class="payment-help">尚无本轮付款授权。<span v-if="view.payable">本次应付 {{ view.payable.currency }} {{ view.payable.value }}。</span></p>
      <div v-if="view.payeeReview" class="payee-review" aria-label="本人账户复核">
        <h4>本人账户复核 · {{ payeeReviewLabels[view.payeeReview.status] }}</h4>
        <p v-if="view.payeeReview.maskedAccount">本次读取账户 <strong>{{ view.payeeReview.maskedAccount }}</strong><br />证据有效至 {{ new Date(view.payeeReview.validUntil!).toLocaleString() }}</p>
        <p v-if="view.payeeReview.status === 'READY'">请核对本次读取的账户。确认新授权后，再由独立出纳办理付款。</p>
        <p v-if="view.payeeReview.issue">{{ paymentIssue(view.payeeReview.issue) }}</p>
      </div>
      <div v-if="!pending" class="payment-buttons"><button v-for="action in (['AUTHORIZE', 'REVIEW_ACCOUNT', 'AUTHORIZE_REVIEWED', 'VOID', 'QUERY', 'RETIRE'] as const)" v-show="allowed(action)" :key="action" type="button" :class="action === 'AUTHORIZE_REVIEWED' || action === 'AUTHORIZE' && !allowed('AUTHORIZE_REVIEWED') ? 'primary' : 'quiet'" :disabled="blocked" @click="prepare(action)">{{ financePaymentLabels[action] }}</button></div>
      <form v-else ref="form" class="payment-confirm" @submit.prevent="execute">
        <h4>{{ financePaymentLabels[pending] }}</h4>
        <p v-if="pending === 'AUTHORIZE'">确认应付 {{ view.payable?.currency }} {{ view.payable?.value }}，依据本轮已批准内容与已过账凭证授权。出纳将在授权期限内执行。</p>
        <p v-else-if="pending === 'AUTHORIZE_REVIEWED'">确认应付 {{ view.payable?.currency }} {{ view.payable?.value }}，使用本次复核账户 {{ view.payeeReview?.maskedAccount }} 创建新授权。原批准金额和原付款记录保留。</p>
        <p v-else-if="pending === 'REVIEW_ACCOUNT'">从原财务系统重新读取同一法人下的本人账户。读取完成后，请核对脱敏账号，再决定是否按复核账户授权。</p>
        <p v-else-if="pending === 'VOID'">停止这份尚未登记执行的授权。出纳已经登记执行的付款不能在这里取消。</p>
        <p v-else-if="pending === 'RETIRE'">确认原命令从未发送，或资金系统已确认终态失败。结束后保留原记录，重新付款需要财务再次授权和独立出纳确认；本次操作不会发起付款。</p>
        <p v-else>只查询原交易及回单，不发起新的付款。</p>
        <label v-if="pending === 'AUTHORIZE' || pending === 'AUTHORIZE_REVIEWED'">授权有效期（分钟）<input v-model.number="validityMinutes" type="number" min="1" max="1440" step="1" required :disabled="saving" /></label>
        <label>办理说明<textarea v-model="comment" maxlength="2000" required rows="3" :disabled="saving" placeholder="说明本次授权、停止或核对的依据" /></label>
        <div class="payment-buttons"><button class="primary" :disabled="blocked">{{ saving ? '正在登记…' : '确认' + financePaymentLabels[pending] }}</button><button type="button" class="quiet" :disabled="saving" @click="pending = null; comment = ''">取消</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.payee-review{margin-top:16px;padding:16px;border:1px solid #b9cdc5;border-radius:10px;background:#f5f9f7}.payee-review h4{font-size:13px;margin:0}.payee-review p{font-size:12px;line-height:1.7;margin:8px 0 0}.payee-review strong{font-variant-numeric:tabular-nums;letter-spacing:1px}
.finance-payment{margin-top:24px;padding-top:20px;border-top:1px solid var(--line)}.payment-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}.payment-heading h3{font-size:15px;margin:0}.payment-help,.payment-error{font-size:12px;line-height:1.7}.payment-help{color:#617571}.payment-error{color:#a04432}.payment-buttons{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}.payment-confirm{padding:18px;margin-top:15px;background:#f5f8f7;border:1px solid var(--line);border-radius:10px}.payment-confirm h4{margin:0}.payment-confirm p{font-size:12px;line-height:1.7}.payment-confirm label{display:grid;gap:7px;font-size:12px;margin-top:12px}.payment-confirm input,.payment-confirm textarea{padding:10px;border:1px solid var(--line);border-radius:7px;width:100%;background:white}.payment-confirm input{max-width:180px}.payment-confirm textarea{resize:vertical}.payment-heading button,.payment-buttons button{min-height:40px}
</style>
