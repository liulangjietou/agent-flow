<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import CashierPaymentDetail from './CashierPaymentDetail.vue'
import { authorizationLabels, paymentOperationLabels, paymentError, type CashierPaymentView, type CashierAccountOption } from '../payments'
import { cashierFilter, validateCashierFilterOptions, validateCashierPaymentPage } from '../cashierFilters'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean; initialPaymentId?: string }>()
const items = ref<CashierPaymentView[]>([]), nextBeforeId = ref<string | null>(null), totalCount = ref<number | null>(null)
const selected = ref(''), loading = ref(false), saving = ref(false), error = ref('')
const legalEntityId = ref(''), debitAccount = ref(''), legalEntities = ref<{ id: string; name: string }[]>([]), accountOptions = ref<CashierAccountOption[]>([])
const nextAccountKey = ref<string | null>(null), optionsLoading = ref(false), optionsError = ref('')
let epoch = 0, controller: AbortController | null = null, optionsEpoch = 0, optionsController: AbortController | null = null
function stop() { epoch++; controller?.abort(); controller = null }
function stopOptions() { optionsEpoch++; optionsController?.abort(); optionsController = null }
async function load(append = false) {
  if (!props.scopeKey || saving.value || append && (!nextBeforeId.value || loading.value)) return
  const filter = cashierFilter(legalEntityId.value, debitAccount.value)
  stop(); const current = epoch, request = new AbortController(); controller = request
  const before = append ? nextBeforeId.value! : undefined; loading.value = true; error.value = ''
  if (!append) { items.value = []; nextBeforeId.value = null; totalCount.value = null }
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; items.value = []; totalCount.value = null; nextBeforeId.value = null; selected.value = ''; error.value = '付款目录读取超时，请重试。' } }, 12_000)
  try {
    const result = await api.cashierPayments(before, request.signal, filter); if (current !== epoch) return
    const page = validateCashierPaymentPage(result, filter, before), merged = append ? [...items.value, ...page.items] : page.items
    if (new Set(merged.map(item => item.payment.id)).size !== merged.length || merged.length > page.totalCount) throw new Error('付款列表发生变化，请刷新后重新查看。')
    items.value = merged; nextBeforeId.value = page.nextBeforeId; totalCount.value = page.totalCount
  } catch (cause) { if (current === epoch) { error.value = paymentError(cause); selected.value = ''; items.value = []; nextBeforeId.value = null; totalCount.value = null } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null } }
}
async function loadOptions(append = false) {
  if (!props.scopeKey || saving.value || append && (!nextAccountKey.value || optionsLoading.value)) return
  stopOptions(); const current = optionsEpoch, request = new AbortController(), legal = legalEntityId.value
  optionsController = request; const after = append ? nextAccountKey.value! : undefined; optionsLoading.value = true; optionsError.value = ''
  if (!append) { legalEntities.value = []; accountOptions.value = []; nextAccountKey.value = null }
  const timeout = setTimeout(() => { if (current === optionsEpoch) { stopOptions(); optionsLoading.value = false; accountOptions.value = []; nextAccountKey.value = null; optionsError.value = '付款筛选选项读取超时，请重试。' } }, 12_000)
  try {
    const result = await api.cashierPaymentFilterOptions(legal || undefined, after, request.signal); if (current !== optionsEpoch) return
    const page = validateCashierFilterOptions(result, legal, after), merged = append ? [...accountOptions.value, ...page.accounts] : page.accounts
    if (new Set(merged.map(item => item.key)).size !== merged.length) throw new Error('付款账户选项发生变化，请重新读取。')
    legalEntities.value = page.legalEntities; accountOptions.value = merged; nextAccountKey.value = page.nextAfterAccountKey
  } catch (cause) { if (current === optionsEpoch) { optionsError.value = paymentError(cause); accountOptions.value = []; nextAccountKey.value = null } }
  finally { clearTimeout(timeout); if (current === optionsEpoch) { optionsLoading.value = false; optionsController = null } }
}
function changeLegalEntity(value: string) {
  if (saving.value || props.locked || value && !legalEntities.value.some(item => item.id === value)) return
  legalEntityId.value = value; debitAccount.value = ''; selected.value = ''; void load(); void loadOptions()
}
function changeAccount(value: string) {
  if (saving.value || props.locked || value && value !== 'UNASSIGNED' && !accountOptions.value.some(item => item.key === value)) return
  debitAccount.value = value; selected.value = ''; void load()
}
function clearFilters() {
  if (saving.value || props.locked) return
  legalEntityId.value = ''; debitAccount.value = ''; selected.value = ''; void load(); void loadOptions()
}
function entityName(id: string) { return legalEntities.value.find(item => item.id === id)?.name ?? '法人名称暂不可用' }
watch(() => JSON.stringify([props.scopeKey, props.refreshVersion]), () => {
  stop(); stopOptions(); items.value = []; nextBeforeId.value = null; totalCount.value = null; selected.value = ''; saving.value = false; loading.value = false; error.value = ''
  legalEntityId.value = ''; debitAccount.value = ''; legalEntities.value = []; accountOptions.value = []; nextAccountKey.value = null; optionsLoading.value = false; optionsError.value = ''
  if (props.scopeKey) { void load(); void loadOptions() }
}, { immediate: true, flush: 'sync' })
// 消息入口可定位第一页之外的原付款，详情仍通过原出纳接口复核当前范围。
watch(() => JSON.stringify([props.scopeKey, props.initialPaymentId]), () => { if (!saving.value) selected.value = props.scopeKey ? props.initialPaymentId ?? '' : '' }, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); stopOptions() })
</script>

<template>
  <section class="cashier-workspace">
    <div class="page-heading"><div><p class="eyebrow">TREASURY / PAYMENTS</p><h2>出纳付款</h2><p class="subhead">核对财务授权，追踪每一笔原交易。</p></div><button type="button" class="quiet" :disabled="loading || saving || locked" @click="selected = ''; load()">刷新目录</button></div>
    <div class="cashier-filters" aria-label="付款目录筛选">
      <label>法人<select aria-label="法人筛选" :value="legalEntityId" :disabled="saving || locked || !legalEntities.length" @change="changeLegalEntity(($event.target as HTMLSelectElement).value)"><option value="">全部任职法人</option><option v-for="entity in legalEntities" :key="entity.id" :value="entity.id">{{ entity.name }}</option></select></label>
      <label>出款账户<select aria-label="出款账户筛选" :value="debitAccount" :disabled="saving || locked" @change="changeAccount(($event.target as HTMLSelectElement).value)"><option value="">全部账户</option><option value="UNASSIGNED">未固定出款账户</option><option v-for="account in accountOptions" :key="account.key" :value="account.key">{{ entityName(account.legalEntityId) }} · {{ account.displayName }} {{ account.maskedAccount }} · {{ account.currency }}</option></select></label>
      <button type="button" class="quiet" :disabled="saving || locked || (!legalEntityId && !debitAccount)" @click="clearFilters">清除筛选</button>
      <button v-if="nextAccountKey" type="button" class="quiet" :disabled="optionsLoading || saving || locked" @click="loadOptions(true)">加载更多账户</button>
      <button v-if="optionsError" type="button" class="quiet" :disabled="optionsLoading || saving || locked" @click="clearFilters">重新读取筛选选项</button>
    </div>
    <p class="cashier-note">账户按历史已固定的付款命令筛选；正在复查的选择属于“未固定出款账户”。</p>
    <p v-if="optionsLoading" role="status" class="cashier-note">正在读取当前范围的筛选选项…</p>
    <p v-if="optionsError" role="alert" class="cashier-error">{{ optionsError }}</p>
    <p v-if="totalCount !== null" role="status" class="cashier-total">共 {{ totalCount }} 笔 · 已加载 {{ items.length }} 笔</p>
    <p v-if="error" role="alert" class="cashier-error">{{ error }}</p>
    <div class="cashier-layout"><section class="cashier-list" aria-label="当前法人付款目录"><p class="cashier-note">仅显示你当前任职法人内的付款。选择一笔后核对收款人、金额和期限。</p>
      <p v-if="!loading && !items.length && !error" class="cashier-empty">当前筛选下没有付款授权。</p>
      <button v-for="item in items" :key="item.payment.id" type="button" class="cashier-item" :class="{ selected: selected === item.payment.id }" :aria-pressed="selected === item.payment.id" :disabled="saving || locked" @click="selected = item.payment.id">
        <span>{{ item.payment.purpose === 'EMPLOYEE_ADVANCE' ? '员工借款' : '费用报销' }} · {{ item.payment.employeeId }}</span><strong>{{ item.payment.amount.currency }} {{ item.payment.amount.value }}</strong><small>{{ item.payment.operation ? paymentOperationLabels[item.payment.operation.status] : authorizationLabels[item.payment.status] }}</small><small>第 {{ item.payment.roundNo }} 轮 · {{ new Date(item.payment.authorizedAt).toLocaleString('zh-CN') }}</small><small>{{ entityName(item.payment.legalEntityId) }} · {{ item.debitAccount ? item.debitAccount.displayName + ' ' + item.debitAccount.maskedAccount : '未固定出款账户' }}</small>
      </button>
      <p v-if="loading" role="status" class="cashier-note">正在读取当前范围…</p><button v-if="nextBeforeId" type="button" class="quiet cashier-more" :disabled="loading || saving || locked" @click="load(true)">加载更多</button>
    </section>
      <CashierPaymentDetail v-if="selected" :authorization-id="selected" :scope-key="scopeKey" :locked="locked" @busy="saving = $event" @changed="load()" />
      <div v-else class="cashier-placeholder"><span aria-hidden="true">↗</span><h3>选择一笔付款开始核对</h3><p>登记操作后，请继续确认银行结果。结果不明确时查询原交易。</p></div>
    </div>
  </section>
</template>

<style scoped>
.cashier-filters{display:flex;gap:12px;align-items:end;flex-wrap:wrap;margin:20px 0 10px}.cashier-filters label{display:grid;gap:7px;flex:1 1 230px;min-width:0;font-size:12px;color:#526861}.cashier-filters select{width:100%;min-width:0;min-height:42px;border:1px solid var(--line);border-radius:8px;padding:8px 10px;background:white;color:#203d35}.cashier-filters button{min-height:42px}.cashier-total{font-size:13px;color:#315e51;font-variant-numeric:tabular-nums;margin:0 0 15px}

.cashier-workspace{padding:32px 40px}.cashier-layout{display:grid;grid-template-columns:minmax(260px,330px) minmax(0,1fr);gap:22px;align-items:start}.cashier-list{border:1px solid var(--line);border-radius:12px;background:white;padding:15px;min-width:0}.cashier-note,.cashier-empty{font-size:12px;line-height:1.8;color:#657974;margin:2px 2px 14px}.cashier-item{display:grid;gap:8px;width:100%;text-align:left;border:1px solid var(--line);border-radius:9px;padding:16px;margin-top:10px;background:#fafcfb}.cashier-item.selected{border-color:#1c8170;box-shadow:inset 3px 0 #1c8170;background:#f0f8f5}.cashier-item span{font-size:12px}.cashier-item strong{font-size:21px;font-variant-numeric:tabular-nums}.cashier-item small{font-size:11px;color:#657974;overflow-wrap:anywhere}.cashier-placeholder{padding:65px 28px;border:1px dashed #b6c8c2;border-radius:12px;text-align:center}.cashier-placeholder>span{display:block;font-size:32px;color:#3e8877}.cashier-placeholder h3{font-size:17px}.cashier-placeholder p{font-size:13px;line-height:1.8;color:#657974}.cashier-error{color:#a04432;font-size:13px}.cashier-more{margin-top:12px;min-height:40px}@media(max-width:1000px){.cashier-workspace{padding:24px}.cashier-layout{grid-template-columns:1fr}.cashier-list{max-height:400px;overflow:auto}.cashier-placeholder{padding:35px 20px}}@media(max-width:600px){.cashier-workspace{padding:16px}}
</style>
