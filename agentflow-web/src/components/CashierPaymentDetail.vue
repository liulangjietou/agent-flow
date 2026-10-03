<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import PaymentFacts from './PaymentFacts.vue'
import { cashierPaymentInput, cashierPaymentLabels, validateCashierPayment, validateCashierPaymentReceipt, validatePaymentAccounts, paymentError, type CashierPaymentAction, type CashierPaymentView, type PaymentAccounts } from '../payments'
const props = defineProps<{ authorizationId: string; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<CashierPaymentView | null>(null), loading = ref(false), saving = ref(false), accountsLoading = ref(false), requiresRefresh = ref(false), unconfirmed = ref(false)
const options = ref<PaymentAccounts | null>(null), selected = ref(''), comment = ref(''), pending = ref<CashierPaymentAction | null>(null), error = ref(''), notice = ref(''), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
function stop() { epoch++; controller?.abort(); controller = null }
function syncPending() {
  const current = writeRequests.pending().some(entry => entry.path.startsWith('/cashier/payments/'))
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
const blocked = computed(() => !!props.locked || loading.value || saving.value || accountsLoading.value || requiresRefresh.value || unconfirmed.value)
function clearChoice() { options.value = null; selected.value = ''; comment.value = ''; pending.value = null }
/** 任何身份或付款切换都废弃旧账户选项和旧请求，迟到响应不能恢复它们。 */
async function load() {
  if (saving.value || !props.scopeKey) return
  stop(); const current = epoch, id = props.authorizationId, request = new AbortController(); controller = request
  view.value = null; clearChoice(); loading.value = true; accountsLoading.value = false; error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '付款详情读取超时，请重试。' } }, 12_000)
  try {
    const result = await api.cashierPayment(id, request.signal); if (current !== epoch) return
    view.value = validateCashierPayment(result, id); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (current === epoch) error.value = paymentError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
async function loadAccounts() {
  const value = view.value; if (!value || saving.value || loading.value || unconfirmed.value || requiresRefresh.value || props.locked || !value.actions.execute) return
  stop(); const current = epoch, request = new AbortController(); controller = request
  options.value = null; selected.value = ''; accountsLoading.value = true; error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); accountsLoading.value = false; error.value = '出款账户读取超时，请重新查询。' } }, 12_000)
  try {
    const result = await api.paymentAccounts(value.payment.id, request.signal); if (current !== epoch) return
    options.value = validatePaymentAccounts(result, value.payment)
  } catch (cause) { if (current === epoch) { error.value = paymentError(cause); options.value = null } }
  finally { clearTimeout(timeout); if (current === epoch) { accountsLoading.value = false; controller = null } }
}
function allowed(action: CashierPaymentAction) { return !!view.value?.actions[({ EXECUTE: 'execute', QUERY: 'query', RESEND_ORIGINAL: 'resendOriginal' } as const)[action]] }
async function prepare(action: CashierPaymentAction) {
  if (blocked.value || !allowed(action)) return
  clearChoice(); pending.value = action; error.value = ''; notice.value = ''
  if (action === 'EXECUTE') await loadAccounts()
  const current = epoch; await nextTick(); if (current === epoch && pending.value === action) form.value?.querySelector<HTMLSelectElement | HTMLTextAreaElement>('select,textarea')?.focus()
}
async function execute() {
  const value = view.value, action = pending.value; if (!value || !action || blocked.value) return
  let input
  try { input = cashierPaymentInput(value, action, comment.value, options.value, selected.value) } catch (cause) { error.value = paymentError(cause); return }
  const current = epoch; saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = await api.cashierPaymentAction(value.payment.id, input); if (current !== epoch) return
    validateCashierPaymentReceipt(receipt, value.payment.id, input); saving.value = false; clearChoice(); emit('busy', false)
    notice.value = '出纳操作已登记，银行结果请刷新后核对。'; emit('changed'); await load()
  } catch (cause) { if (current === epoch) { error.value = paymentError(cause); requiresRefresh.value = true } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.authorizationId]), () => {
  stop(); view.value = null; clearChoice(); error.value = ''; notice.value = ''; loading.value = false; saving.value = false; accountsLoading.value = false; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey && props.authorizationId) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="cashier-detail" aria-label="出纳付款详情">
    <div class="cashier-heading"><h3>核对本次付款</h3><button type="button" class="quiet" :disabled="saving || loading || locked" @click="notice = ''; load()">刷新付款详情</button></div>
    <p v-if="loading" role="status">正在核对当前法人权限与原交易…</p><p v-if="error" role="alert" class="payment-error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="payment-error">上次出纳操作结果尚未确认，请在未确认操作中恢复原请求后刷新。</p>
    <p v-if="requiresRefresh && !error && !unconfirmed" role="status">原请求已恢复，请刷新付款详情后再办理。</p>
    <template v-if="view">
      <PaymentFacts :payment="view.payment" />
      <div v-if="!pending" class="cashier-buttons"><button v-for="action in (['EXECUTE', 'QUERY', 'RESEND_ORIGINAL'] as const)" v-show="allowed(action)" :key="action" type="button" :class="action === 'EXECUTE' ? 'primary' : 'quiet'" :disabled="blocked" @click="prepare(action)">{{ cashierPaymentLabels[action] }}</button></div>
      <form v-else ref="form" class="cashier-confirm" @submit.prevent="execute">
        <h4>{{ cashierPaymentLabels[pending] }}</h4>
        <p>收款人 {{ view.payment.employeeId }} · {{ view.payment.maskedPayeeAccount }}<br />确认金额 <strong>{{ view.payment.amount.currency }} {{ view.payment.amount.value }}</strong></p>
        <template v-if="pending === 'EXECUTE'">
          <div class="cashier-heading"><span>请选择本次出款账户</span><button type="button" class="quiet" :disabled="accountsLoading || saving || locked" @click="loadAccounts">重新查询账户</button></div>
          <p v-if="accountsLoading" role="status">正在读取当前可用账户…</p><p v-else-if="options && !options.items.length" role="status">当前没有可用出款账户，请联系资金管理员。</p>
          <label v-if="options?.items.length">出款账户<select v-model="selected" required :disabled="blocked"><option value="" disabled>请选择账户，不会自动选中</option><option v-for="account in options.items" :key="account.reference" :value="account.reference">{{ account.displayName }} · {{ account.maskedAccount }} · {{ account.currency }}</option></select></label>
          <p class="cashier-note">登记后会再次核对两端账户。此选择固定至原授权，不能替换收款人或金额。</p>
        </template>
        <p v-else-if="pending === 'RESEND_ORIGINAL'" class="payment-error">资金系统已确认原交易不存在。本次仅重发原编号、原金额和原账户。</p>
        <p v-else>只查询原交易，不重新付款。</p>
        <label>办理说明<textarea v-model="comment" required maxlength="2000" rows="3" :disabled="saving" placeholder="说明核对情况或本次查询原因" /></label>
        <div class="cashier-buttons"><button class="primary" :disabled="blocked || (pending === 'EXECUTE' && (!options || !selected))">{{ saving ? '正在登记…' : '确认' + cashierPaymentLabels[pending] }}</button><button type="button" class="quiet" :disabled="saving" @click="stop(); accountsLoading = false; clearChoice()">取消</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.cashier-detail{padding:22px;background:white;border:1px solid var(--line);border-radius:12px;min-width:0}.cashier-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}.cashier-heading h3{font-size:17px;margin:0}.cashier-detail p,.cashier-heading span{font-size:12px;line-height:1.7;color:#5e7470}.cashier-detail .payment-error{color:#a04732}.cashier-buttons{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.cashier-detail button{min-height:40px}.cashier-confirm{padding:18px;border:1px solid var(--line);border-radius:10px;background:#f5f8f7;margin-top:18px}.cashier-confirm h4{margin:0}.cashier-confirm label{display:grid;gap:7px;font-size:12px;margin-top:14px}.cashier-confirm select,.cashier-confirm textarea{width:100%;max-width:100%;padding:10px;border:1px solid var(--line);border-radius:7px;background:white}.cashier-confirm textarea{resize:vertical}.cashier-confirm .cashier-note{font-size:11px}@media(max-width:600px){.cashier-detail{padding:15px}.cashier-confirm{padding:12px}}
</style>
