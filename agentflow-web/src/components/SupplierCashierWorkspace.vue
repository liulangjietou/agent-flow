<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import SupplierCashierDetail from './SupplierCashierDetail.vue'
import { paymentOperationLabels, supplierPreparationLabels, holdLabels, validateSupplierCashier, supplierCashierError, type SupplierCashierView } from '../supplierCashier'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean; initialPaymentId?: string }>()
const items = ref<SupplierCashierView[]>([]), nextBeforeId = ref<string | null>(null), selected = ref(''), loading = ref(false), saving = ref(false), error = ref('')
let epoch = 0, controller: AbortController | null = null
function stop() { epoch++; controller?.abort(); controller = null }
async function load(append = false) {
  if (!props.scopeKey || saving.value || append && (!nextBeforeId.value || loading.value)) return
  stop(); const current = epoch, request = new AbortController(); controller = request
  const before = append ? nextBeforeId.value! : undefined; loading.value = true; error.value = ''
  if (!append) { items.value = []; nextBeforeId.value = null }
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; error.value = '付款目录读取超时，请重试。' } }, 12_000)
  try {
    const page = await api.supplierCashierPayments(before, request.signal); if (current !== epoch) return
    if (!Array.isArray(page.items) || page.items.length > 25 || page.nextBeforeId !== null && (!page.items.length || page.nextBeforeId !== page.items[page.items.length - 1]?.authorizationId || page.nextBeforeId === before)) throw new Error('付款分页结果未通过校验。')
    const values = page.items.map(item => validateSupplierCashier(item)); const merged = append ? [...items.value, ...values] : values
    if (new Set(merged.map(item => item.authorizationId)).size !== merged.length) throw new Error('付款列表发生变化，请刷新后重新查看。')
    items.value = merged; nextBeforeId.value = page.nextBeforeId
  } catch (cause) { if (current === epoch) { error.value = supplierCashierError(cause); selected.value = ''; if (!append) items.value = [] } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
watch(() => [props.scopeKey, props.refreshVersion] as const, (current, previous) => { if (current[0] === previous?.[0] && saving.value) return; stop(); items.value = []; nextBeforeId.value = null; selected.value = ''; saving.value = false; loading.value = false; error.value = ''; if (props.scopeKey) void load() }, { immediate: true, flush: 'sync' })
// 原请求仍占有授权时才从消息进入，详情接口重新核对当前法人权限。
watch(() => JSON.stringify([props.scopeKey, props.initialPaymentId]), () => { if (!saving.value) selected.value = props.scopeKey ? props.initialPaymentId ?? '' : '' }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="cashier-workspace">
    <div class="page-heading"><div><p class="eyebrow">TREASURY / SUPPLIERS</p><h2>供应商付款</h2><p class="subhead">按原应付授权办理，核对每一笔供应商银行交易。</p></div><button type="button" class="quiet" :disabled="loading || saving || locked" @click="selected = ''; load()">刷新目录</button></div>
    <p v-if="error" role="alert" class="cashier-error">{{ error }}</p>
    <div class="cashier-layout"><section class="cashier-list" aria-label="当前法人供应商付款目录"><p class="cashier-note">仅显示你当前任职法人内的付款。选择一笔后核对收款人、金额和期限。</p>
      <p v-if="!loading && !items.length && !error" class="cashier-empty">当前范围内没有付款授权。</p>
      <button v-for="item in items" :key="item.authorizationId" type="button" class="cashier-item" :class="{ selected: selected === item.authorizationId }" :aria-pressed="selected === item.authorizationId" :disabled="saving || locked" @click="selected = item.authorizationId">
        <span>{{ item.supplierName }}</span><strong>{{ item.amount.currency }} {{ item.amount.value }}</strong><small>{{ item.operation ? paymentOperationLabels[item.operation.status] : item.preparation ? supplierPreparationLabels[item.preparation.status] : holdLabels[item.hold.status] }}</small><small>第 {{ item.roundNo }} 轮 · {{ new Date(item.authorizedAt).toLocaleString('zh-CN') }}</small>
      </button>
      <p v-if="loading" role="status" class="cashier-note">正在读取当前范围…</p><button v-if="nextBeforeId" type="button" class="quiet cashier-more" :disabled="loading || saving || locked" @click="load(true)">加载更多</button>
    </section>
      <SupplierCashierDetail v-if="selected" :authorization-id="selected" :scope-key="scopeKey" :locked="locked" @busy="saving = $event" @changed="load()" />
      <div v-else class="cashier-placeholder"><span aria-hidden="true">↗</span><h3>选择一笔付款开始核对</h3><p>登记操作后，请继续确认银行结果。结果不明确时查询原交易。</p></div>
    </div>
  </section>
</template>

<style scoped>
.cashier-workspace{padding:32px 40px}.cashier-layout{display:grid;grid-template-columns:minmax(260px,330px) minmax(0,1fr);gap:22px;align-items:start}.cashier-list{border:1px solid var(--line);border-radius:12px;background:white;padding:15px;min-width:0}.cashier-note,.cashier-empty{font-size:12px;line-height:1.8;color:#657974;margin:2px 2px 14px}.cashier-item{display:grid;gap:8px;width:100%;text-align:left;border:1px solid var(--line);border-radius:9px;padding:16px;margin-top:10px;background:#fafcfb}.cashier-item.selected{border-color:#1c8170;box-shadow:inset 3px 0 #1c8170;background:#f0f8f5}.cashier-item span{font-size:12px}.cashier-item strong{font-size:21px;font-variant-numeric:tabular-nums}.cashier-item small{font-size:11px;color:#657974;overflow-wrap:anywhere}.cashier-placeholder{padding:65px 28px;border:1px dashed #b6c8c2;border-radius:12px;text-align:center}.cashier-placeholder>span{display:block;font-size:32px;color:#3e8877}.cashier-placeholder h3{font-size:17px}.cashier-placeholder p{font-size:13px;line-height:1.8;color:#657974}.cashier-error{color:#a04432;font-size:13px}.cashier-more{margin-top:12px;min-height:40px}@media(max-width:1000px){.cashier-workspace{padding:24px}.cashier-layout{grid-template-columns:1fr}.cashier-list{max-height:400px;overflow:auto}.cashier-placeholder{padding:35px 20px}}@media(max-width:600px){.cashier-workspace{padding:16px}}
</style>
