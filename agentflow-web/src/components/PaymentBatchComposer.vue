<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { paymentError, type CashierPaymentView, type DebitAccount, type PaymentAccounts } from '../payments'
import { batchTotal, commonBatchAccounts, paymentBatchInput, validateBatchReceipt, validateBatchSelection } from '../paymentBatches'
const props = defineProps<{ scopeKey: string; items: CashierPaymentView[]; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; submitted: [id: string] }>()
const pending = ref(false), loading = ref(false), saving = ref(false), unconfirmed = ref(false), requiresRefresh = ref(false)
const directories = ref<PaymentAccounts[]>([]), accounts = ref<DebitAccount[]>([]), selected = ref(''), comment = ref(''), error = ref('')
let epoch = 0, controller: AbortController | null = null
function stop() { epoch++; controller?.abort(); controller = null }
function syncPending() {
  const current = writeRequests.pending().some(entry => entry.path === '/payment-batches' || entry.path.startsWith('/cashier/payments/'))
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
const blocked = computed(() => !!props.locked || loading.value || saving.value || unconfirmed.value || requiresRefresh.value)
const total = computed(() => batchTotal(props.items))
function clear() { stop(); pending.value = false; loading.value = false; directories.value = []; accounts.value = []; selected.value = ''; comment.value = '' }
/** 账户读取与登记分开；取消、选择变化和身份变化均废弃迟到目录。 */
async function prepare() {
  if (blocked.value || !props.scopeKey) return
  error.value = ''
  try { validateBatchSelection(props.items) } catch (cause) { error.value = paymentError(cause); return }
  stop(); const current = epoch, values = props.items, request = new AbortController(); controller = request
  pending.value = true; loading.value = true; directories.value = []; accounts.value = []; selected.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '批次账户读取超时，请重新查询。' } }, 12_000)
  try {
    const result = await Promise.all(values.map(item => api.paymentAccounts(item.payment.id, request.signal)))
    if (current !== epoch) return
    accounts.value = commonBatchAccounts(values, result); directories.value = result
  } catch (cause) { if (current === epoch) error.value = paymentError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
async function execute() {
  if (!pending.value || blocked.value) return
  let input
  try { input = paymentBatchInput(props.items, directories.value, selected.value, comment.value) } catch (cause) { error.value = paymentError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    const receipt = await api.submitPaymentBatch(input); if (current !== epoch) return
    validateBatchReceipt(receipt, input); saving.value = false; emit('busy', false); clear(); emit('submitted', receipt.batchId)
  } catch (cause) { if (current === epoch) { error.value = paymentError(cause); requiresRefresh.value = true } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.items]), () => {
  clear(); saving.value = false; error.value = ''; requiresRefresh.value = false; syncPending(); emit('busy', false)
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="batch-composer" aria-label="批次付款确认">
    <div class="batch-total"><span>已选 {{ items.length }} 笔</span><strong>{{ items[0]?.payment.amount.currency }} {{ total }}</strong></div>
    <p>一次登记同法人、同币种的借款和报销付款，最多 25 笔。登记后每笔分别复核账户并执行。</p>
    <p v-if="error" role="alert" class="batch-error">{{ error }}</p>
    <p v-if="unconfirmed && !saving" role="alert" class="batch-error">上次付款结果尚未确认，请先在未确认操作中恢复原请求。</p>
    <p v-else-if="requiresRefresh" role="status">请刷新付款目录，重新核对剩余授权。</p>
    <button v-if="!pending" type="button" class="primary" :disabled="blocked || !items.length" @click="prepare">核对选中付款</button>
    <form v-else @submit.prevent="execute">
      <h3>确认本批付款</h3>
      <ul class="batch-recipients"><li v-for="item in items" :key="item.payment.id"><span>{{ item.payment.employeeId }} · {{ item.payment.maskedPayeeAccount }}</span><strong>{{ item.payment.amount.value }}</strong></li></ul>
      <p v-if="loading" role="status">正在逐笔核对可用账户…</p>
      <p v-else-if="!accounts.length" role="status">尚未取得所有付款共同可用的出款账户，请重新查询。</p>
      <label v-if="accounts.length">共同出款账户<select v-model="selected" required :disabled="blocked"><option value="" disabled>请选择账户</option><option v-for="account in accounts" :key="account.reference" :value="account.reference">{{ account.displayName }} · {{ account.maskedAccount }} · {{ account.currency }}</option></select></label>
      <label>批次说明<textarea v-model="comment" required maxlength="2000" rows="3" :disabled="saving || locked" placeholder="说明本批付款的核对依据" /></label>
      <p>任一授权在登记时失效，整批不登记；登记后的账户复核和银行结果按单笔处理。</p>
      <div class="batch-buttons"><button class="primary" :disabled="blocked || !selected">{{ saving ? '正在登记…' : '确认登记 ' + items.length + ' 笔付款' }}</button><button type="button" class="quiet" :disabled="blocked" @click="prepare">重新查询账户</button><button type="button" class="quiet" :disabled="saving || locked" @click="clear">取消确认</button></div>
    </form>
  </section>
</template>

<style scoped>
.batch-composer{min-width:0;border:1px solid var(--line);border-radius:12px;background:#f3f8f5;padding:22px}.batch-total{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.batch-total span{font-size:13px}.batch-total strong{font-size:24px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.batch-composer p{font-size:12px;color:#536e64;line-height:1.8}.batch-composer .batch-error{color:#a04432}.batch-composer h3{font-size:16px}.batch-composer label{display:grid;gap:7px;font-size:12px;margin-top:16px}.batch-composer select,.batch-composer textarea{width:100%;max-width:100%;border:1px solid var(--line);padding:10px;border-radius:6px;background:white}.batch-composer textarea{resize:vertical}.batch-buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}.batch-composer button{min-height:40px}.batch-recipients{padding:0;list-style:none;max-height:240px;overflow:auto}.batch-recipients li{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid var(--line);font-size:12px}.batch-recipients span,.batch-recipients strong{overflow-wrap:anywhere}@media(max-width:600px){.batch-composer{padding:16px}}
</style>
