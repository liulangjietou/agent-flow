<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import PaymentBatchComposer from './PaymentBatchComposer.vue'
import CashierPaymentDetail from './CashierPaymentDetail.vue'
import { authorizationLabels, paymentOperationLabels, paymentRequestLabels, paymentError, validateCashierPayment, type CashierPaymentView } from '../payments'
import { MAX_BATCH_ITEMS, validateBatchDetail, validateBatchSelection, validateBatchSummary, type PaymentBatchDetail, type PaymentBatchSummary } from '../paymentBatches'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean }>()
const mode = ref<'create' | 'history'>('create'), payments = ref<CashierPaymentView[]>([]), batches = ref<PaymentBatchSummary[]>([]), checked = ref<string[]>([])
const nextBeforeId = ref<string | null>(null), detail = ref<PaymentBatchDetail | null>(null), selectedPayment = ref(''), loading = ref(false), saving = ref(false), error = ref(''), notice = ref('')
const selection = computed(() => checked.value.map(id => payments.value.find(item => item.payment.id === id)!))
let epoch = 0, controller: AbortController | null = null
function stop() { epoch++; controller?.abort(); controller = null }
function status(item: CashierPaymentView) { return item.payment.operation ? paymentOperationLabels[item.payment.operation.status] : item.payment.request ? paymentRequestLabels[item.payment.request.status] : authorizationLabels[item.payment.status] }
/** 刷新会清空旧选择；分页追加保持本页明确勾选，不自动勾选新到达授权。 */
async function load(append = false) {
  if (!props.scopeKey || saving.value || append && (loading.value || !nextBeforeId.value)) return
  stop(); const current = epoch, request = new AbortController(), pageMode = mode.value, before = append ? nextBeforeId.value! : undefined; controller = request
  loading.value = true; error.value = ''; detail.value = null; selectedPayment.value = ''
  if (!append) { payments.value = []; batches.value = []; checked.value = []; nextBeforeId.value = null }
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '付款目录读取超时，请重新刷新。' } }, 12_000)
  try {
    if (pageMode === 'create') {
      const page = await api.cashierPayments(before, request.signal); if (current !== epoch) return
      if (!Array.isArray(page.items) || page.items.length > 25 || page.nextBeforeId !== null && (!page.items.length || page.nextBeforeId !== page.items[page.items.length - 1]?.payment.id || page.nextBeforeId === before)) throw new Error('付款目录分页未通过校验。')
      const values = page.items.map(item => validateCashierPayment(item)), merged = append ? [...payments.value, ...values] : values
      if (new Set(merged.map(item => item.payment.id)).size !== merged.length) throw new Error('付款目录发生变化，请刷新后重新选择。')
      payments.value = merged; nextBeforeId.value = page.nextBeforeId
    } else {
      const page = await api.paymentBatches(before, request.signal); if (current !== epoch) return
      if (!Array.isArray(page.items) || page.items.length > 25 || page.nextBeforeId !== null && (!page.items.length || page.nextBeforeId !== page.items[page.items.length - 1]?.id || page.nextBeforeId === before)) throw new Error('批次目录分页未通过校验。')
      const values = page.items.map(validateBatchSummary), merged = append ? [...batches.value, ...values] : values
      if (new Set(merged.map(item => item.id)).size !== merged.length) throw new Error('批次目录发生变化，请刷新后重新查看。')
      batches.value = merged; nextBeforeId.value = page.nextBeforeId
    }
  } catch (cause) { if (current === epoch) error.value = paymentError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
function toggle(item: CashierPaymentView) {
  if (saving.value || props.locked || loading.value) return
  if (checked.value.includes(item.payment.id)) { checked.value = checked.value.filter(id => id !== item.payment.id); return }
  try { validateBatchSelection([...selection.value, item]); checked.value = [...checked.value, item.payment.id]; error.value = '' } catch (cause) { error.value = paymentError(cause) }
}
function switchMode(value: 'create' | 'history') { if (saving.value || props.locked || mode.value === value) return; mode.value = value; notice.value = ''; void load() }
async function open(id: string) {
  if (saving.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request
  detail.value = null; selectedPayment.value = ''; loading.value = true; error.value = ''
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '批次详情读取超时，请重试。' } }, 12_000)
  try { const value = await api.paymentBatch(id, request.signal); if (current === epoch) detail.value = validateBatchDetail(value, id) }
  catch (cause) { if (current === epoch) error.value = paymentError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
async function submitted(id: string) {
  saving.value = false; checked.value = []; mode.value = 'history'; notice.value = '批次已登记。请逐笔核对账户复核与银行结果。'
  const refresh = load(), current = epoch
  await refresh; if (current === epoch && mode.value === 'history') await open(id)
}
watch(() => JSON.stringify([props.scopeKey, props.refreshVersion]), () => {
  stop(); payments.value = []; batches.value = []; checked.value = []; detail.value = null; selectedPayment.value = ''; loading.value = false; saving.value = false; error.value = ''; notice.value = ''
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="batch-workspace">
    <div class="page-heading"><div><p class="eyebrow">TREASURY / BATCHES</p><h2>批量付款</h2><p class="subhead">统一登记借款与报销付款，逐笔核对结果。</p></div><button type="button" class="quiet" :disabled="loading || saving || locked" @click="load()">刷新批次工作台</button></div>
    <div class="batch-tabs" role="group" aria-label="批量付款视图"><button type="button" class="quiet" :aria-pressed="mode === 'create'" :disabled="saving || locked" @click="switchMode('create')">登记新批次</button><button type="button" class="quiet" :aria-pressed="mode === 'history'" :disabled="saving || locked" @click="switchMode('history')">已登记批次</button></div>
    <p v-if="error" role="alert" class="batch-error">{{ error }}</p><p v-if="notice" role="status" class="batch-note">{{ notice }}</p><p v-if="loading" role="status" class="batch-note">正在读取当前法人范围…</p>
    <div v-if="mode === 'create'" class="batch-layout">
      <section class="batch-list" aria-label="选择付款授权"><p class="batch-note">最多选择 {{ MAX_BATCH_ITEMS }} 笔。已登记、已失效的授权不能再次选择。</p>
        <p v-if="!payments.length && !loading" class="batch-note">当前没有付款授权。</p>
        <button v-for="item in payments" :key="item.payment.id" type="button" class="batch-choice" :aria-pressed="checked.includes(item.payment.id)" :disabled="saving || locked || loading || !item.actions.execute || (!!item.payment.request)" @click="toggle(item)">
          <span class="batch-checkbox" aria-hidden="true">{{ checked.includes(item.payment.id) ? '✓' : '' }}</span><span class="batch-choice-content"><strong>{{ item.payment.employeeId }} · {{ item.payment.purpose === 'EMPLOYEE_ADVANCE' ? '员工借款' : '费用报销' }}</strong><span class="batch-amount">{{ item.payment.amount.currency }} {{ item.payment.amount.value }}</span><small>{{ item.payment.maskedPayeeAccount }} · {{ status(item) }}</small><small>法人 {{ item.payment.legalEntityId }} · 第 {{ item.payment.roundNo }} 轮</small></span>
        </button>
        <button v-if="nextBeforeId" type="button" class="quiet batch-more" :disabled="loading || saving || locked" @click="load(true)">加载更多付款</button>
      </section>
      <PaymentBatchComposer :scope-key="scopeKey" :items="selection" :locked="locked || loading" @busy="saving = $event" @submitted="submitted" />
    </div>
    <div v-else class="batch-layout">
      <section class="batch-list" aria-label="已登记批次目录"><p v-if="!batches.length && !loading" class="batch-note">当前范围内没有已登记批次。</p>
        <button v-for="batch in batches" :key="batch.id" type="button" class="batch-choice" :aria-pressed="detail?.batch.id === batch.id" :disabled="saving || locked" @click="open(batch.id)"><span class="batch-choice-content"><strong>{{ batch.itemCount }} 笔 · {{ batch.cashier }}</strong><span class="batch-amount">{{ batch.currency }} {{ batch.total }}</span><small>{{ new Date(batch.createdAt).toLocaleString('zh-CN') }}</small><small>批次 {{ batch.id }}</small></span></button>
        <button v-if="nextBeforeId" type="button" class="quiet batch-more" :disabled="loading || saving || locked" @click="load(true)">加载更多批次</button>
      </section>
      <div v-if="detail" class="batch-details"><section class="batch-summary" aria-label="批次逐笔状态"><div class="batch-detail-heading"><h3>{{ detail.batch.itemCount }} 笔付款 · {{ detail.batch.currency }} {{ detail.batch.total }}</h3><button type="button" class="quiet" :disabled="loading || saving || locked" @click="open(detail.batch.id)">刷新逐笔状态</button></div><p class="batch-note">{{ detail.comment }}</p>
          <button v-for="item in detail.items" :key="item.requestId" type="button" class="batch-member" :aria-pressed="selectedPayment === item.current.payment.id" :disabled="saving || locked" @click="selectedPayment = item.current.payment.id"><span><strong>{{ item.current.payment.employeeId }}</strong><small>{{ item.current.payment.maskedPayeeAccount }}</small></span><span><strong>{{ item.current.payment.amount.value }}</strong><small>{{ status(item.current) }}</small></span><span aria-hidden="true">↗</span></button>
          <p class="batch-note">选择单笔可查看原交易、查询结果或处理明确查无。批次内每笔状态独立。</p></section>
        <CashierPaymentDetail v-if="selectedPayment" :authorization-id="selectedPayment" :scope-key="scopeKey" :locked="locked || loading" @busy="saving = $event" @changed="selectedPayment = ''; open(detail!.batch.id)" />
      </div>
      <div v-else class="batch-placeholder"><h3>选择一个批次查看逐笔结果</h3><p>账户复核失败、待确认和银行到账分别显示。</p></div>
    </div>
  </section>
</template>

<style scoped>
.batch-workspace{padding:32px 40px}.batch-tabs{display:flex;gap:10px;margin-bottom:20px}.batch-tabs button[aria-pressed="true"]{background:#e3f2eb;border-color:#3c8a74;color:#145d4c}.batch-layout{display:grid;grid-template-columns:minmax(260px,360px) minmax(0,1fr);gap:22px;align-items:start}.batch-list,.batch-summary{border:1px solid var(--line);border-radius:12px;background:white;padding:16px;min-width:0}.batch-list{max-height:690px;overflow:auto}.batch-note{font-size:12px;line-height:1.8;color:#5e7470;overflow-wrap:anywhere}.batch-error{font-size:13px;color:#a04432}.batch-choice{display:flex;align-items:flex-start;gap:12px;width:100%;text-align:left;margin-top:10px;padding:16px;border:1px solid var(--line);border-radius:9px;background:#fafcfb;min-width:0}.batch-choice[aria-pressed="true"],.batch-member[aria-pressed="true"]{background:#eef8f2;border-color:#3c8a74}.batch-choice:disabled{opacity:.6}.batch-checkbox{flex:0 0 18px;height:18px;border:1px solid #698379;border-radius:4px;text-align:center;font-size:14px;line-height:16px}.batch-choice-content{display:grid;gap:8px;min-width:0}.batch-choice-content strong{font-size:12px}.batch-choice-content small{font-size:11px;color:#657974;overflow-wrap:anywhere}.batch-amount{font-size:21px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.batch-more{margin-top:14px}.batch-details{display:grid;gap:20px;min-width:0}.batch-detail-heading{display:flex;gap:12px;justify-content:space-between;align-items:center;flex-wrap:wrap}.batch-detail-heading h3{font-size:17px;overflow-wrap:anywhere}.batch-member{display:flex;align-items:center;gap:14px;justify-content:space-between;width:100%;border:1px solid var(--line);background:#fafcfb;border-radius:8px;text-align:left;padding:14px;margin:10px 0}.batch-member span{display:grid;gap:7px;min-width:0}.batch-member strong{font-size:13px;overflow-wrap:anywhere}.batch-member small{font-size:11px;line-height:1.6;color:#5e7470;overflow-wrap:anywhere}.batch-placeholder{border:1px dashed #b6c8c2;border-radius:12px;text-align:center;padding:55px 20px}.batch-placeholder h3{font-size:17px}.batch-placeholder p{font-size:13px;line-height:1.8;color:#657974}.batch-workspace button{min-height:40px}@media(max-width:1000px){.batch-workspace{padding:24px}.batch-layout{grid-template-columns:1fr}.batch-list{max-height:390px}}@media(max-width:600px){.batch-workspace{padding:16px}.batch-choice{padding:12px}.batch-member{padding:10px;gap:8px}}
</style>
