<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { operationLabels, preparationLabels, validateVoucherReceipt, validateVoucherView, voucherActionInput, voucherActionLabels, voucherError, voucherIssue, type VoucherAction, type VoucherBinding, type VoucherView } from '../vouchers'

const props = defineProps<{ applicationId: string; businessId: string; businessType: 'EXPENSE' | 'ADVANCE_REQUEST'; roundNo: number; applicationVersion: number; businessVersion: number; scopeKey: string; locked?: boolean; payment?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const view = ref<VoucherView | null>(null), loading = ref(false), saving = ref(false), requiresRefresh = ref(false), unconfirmed = ref(false)
const error = ref(''), notice = ref(''), pending = ref<VoucherAction | null>(null), comment = ref(''), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const path = () => `/applications/${encodeURIComponent(props.applicationId)}/vouchers${props.payment ? '/payment' : ''}/actions`
const title = computed(() => props.payment ? '付款凭证' : '挂账凭证')
function syncPending() {
  const current = writeRequests.pending().some(entry => entry.path === path())
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
const blocked = computed(() => !!props.locked || saving.value || loading.value || requiresRefresh.value || unconfirmed.value)
function stop() { epoch++; controller?.abort(); controller = null }
/** 切换身份、轮次和版本后清空旧状态，查询超时或迟到也不能恢复旧内容。 */
async function load() {
  if (saving.value || !props.scopeKey) return
  stop(); const version = epoch, request = new AbortController(); controller = request
  view.value = null; pending.value = null; comment.value = ''; error.value = ''; loading.value = true
  const binding: VoucherBinding = { applicationId: props.applicationId, businessId: props.businessId, businessType: props.businessType, roundNo: props.roundNo, applicationVersion: props.applicationVersion, businessVersion: props.businessVersion,
    kind: props.payment ? 'PAYMENT' : props.businessType === 'EXPENSE' ? 'EXPENSE_ACCRUAL' : 'EMPLOYEE_ADVANCE' }
  const timeout = setTimeout(() => { if (version === epoch) { stop(); loading.value = false; error.value = '凭证状态读取超时，请重试。' } }, 12_000)
  try {
    const result = await (binding.kind === 'PAYMENT' ? api.paymentVouchers : api.vouchers)(binding.applicationId, binding.roundNo, request.signal)
    if (version !== epoch) return
    view.value = validateVoucherView(result, binding); requiresRefresh.value = false; syncPending()
  } catch (cause) { if (version === epoch) error.value = voucherError(cause) }
  finally { clearTimeout(timeout); if (version === epoch) { loading.value = false; controller = null } }
}
function allowed(action: VoucherAction) { return !!view.value?.actions[({ PREPARE: 'prepare', QUERY: 'query', RESEND_ORIGINAL: 'resendOriginal' } as const)[action]] }
function prepare(action: VoucherAction) {
  if (blocked.value || !allowed(action)) return
  pending.value = action; comment.value = ''; error.value = ''; notice.value = ''
  const version = epoch
  void nextTick(() => { if (version === epoch && pending.value === action) form.value?.querySelector('textarea')?.focus() })
}
/** 写入不自动重发；同一请求的未知结果统一交给全局原请求恢复。 */
async function execute() {
  const value = view.value, action = pending.value
  if (!value || !action || blocked.value || !allowed(action)) return
  let input
  try { input = voucherActionInput(value, action, comment.value) } catch (cause) { error.value = voucherError(cause); return }
  const version = epoch
  saving.value = true; error.value = ''; emit('busy', true)
  try {
    const receipt = await (value.kind === 'PAYMENT' ? api.paymentVoucherAction : api.voucherAction)(value.applicationId, input)
    if (version !== epoch) return
    validateVoucherReceipt(receipt, value, input)
    pending.value = null; saving.value = false; emit('busy', false)
    notice.value = '操作已登记，请刷新查看实际处理结果。'; await load()
  } catch (cause) { if (version === epoch) { error.value = voucherError(cause); requiresRefresh.value = true } }
  finally { if (version === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.applicationId, props.businessId, props.businessType, props.roundNo, props.applicationVersion, props.businessVersion, props.payment]), () => {
  stop(); view.value = null; pending.value = null; comment.value = ''; error.value = ''; notice.value = ''; saving.value = false; loading.value = false; requiresRefresh.value = false
  syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="voucher-status" :aria-label="`本轮${title}`">
    <div class="voucher-heading"><h3>第 {{ roundNo }} 轮 · {{ title }}</h3><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新{{ title }}状态</button></div>
    <p class="voucher-help">{{ payment ? '根据原支付命令和成功回单办理会计入账。付款凭证的准备、查询及重发均不会再次付款。' : '挂账凭证记录本轮已批准的费用或借款。已过账后仍须办理实际付款与结算。' }}</p>
    <p v-if="loading" class="voucher-help" role="status">正在核对本轮凭证与读取权限…</p>
    <p v-if="error" class="voucher-error" role="alert">{{ error }}</p><p v-if="notice" class="voucher-help" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="voucher-error" role="alert">上次操作结果尚未确认，请在未确认操作中恢复后刷新状态。</p>
    <p v-if="requiresRefresh && !error && !unconfirmed && !loading" class="voucher-help" role="status">上次操作已恢复，请刷新凭证状态后再办理。</p>
    <template v-if="view">
      <p v-if="payment && !view.preparation && !view.operation" class="voucher-help">成功付款后生成付款凭证；全额冲销或零金额结算不生成付款凭证。</p>
      <div class="voucher-stages"><article><small>会计依据</small><strong>{{ view.preparation ? preparationLabels[view.preparation.status] : '尚无凭证准备记录' }}</strong><p v-if="view.preparation">第 {{ view.preparation.attempt }} 次准备 · {{ timeLabel(view.preparation.createdAt) }}</p><p v-if="view.preparation?.issue">{{ voucherIssue(view.preparation.issue) }}</p></article>
        <article :class="{ posted: view.operation?.status === 'POSTED' }"><small>ERP 过账</small><strong>{{ view.operation ? operationLabels[view.operation.status] : '尚无过账记录' }}</strong><p v-if="view.operation">会计日期 {{ view.operation.accountingDate }} · 尝试处理 {{ view.operation.attempts }} 次</p><p v-if="view.operation?.issue">{{ voucherIssue(view.operation.issue) }}</p></article></div>
      <p v-if="view.operation?.disputed" class="voucher-error">{{ payment ? '付款凭证结果存在冲突，需要核对原会计记录。以下保留的是此前已接受的凭证信息。' : '外部结果存在冲突，当前凭证不能作为后续付款依据。以下保留的是此前已接受的凭证信息。' }}</p>
      <dl v-if="view.operation?.voucherReference" class="voucher-reference"><div><dt>{{ view.operation.status === 'POSTED' ? '已过账凭证号' : '此前确认的凭证号' }}</dt><dd>{{ view.operation.voucherReference }}</dd></div><div v-if="view.operation.postedAt"><dt>ERP 过账时间</dt><dd>{{ timeLabel(view.operation.postedAt) }}</dd></div></dl>
      <div v-if="!pending" class="voucher-actions"><button v-if="allowed('PREPARE')" type="button" class="primary" :disabled="blocked" @click="prepare('PREPARE')">重新准备凭证</button><button v-if="allowed('QUERY')" type="button" class="secondary" :disabled="blocked" @click="prepare('QUERY')">查询 ERP 结果</button><button v-if="allowed('RESEND_ORIGINAL')" type="button" class="secondary" :disabled="blocked" @click="prepare('RESEND_ORIGINAL')">按原编号重发</button></div>
      <form v-else ref="form" class="voucher-confirm" @submit.prevent="execute"><h4>{{ voucherActionLabels[pending] }}</h4>
        <p v-if="pending === 'PREPARE'">{{ payment ? '沿用原支付命令、成功回单和会计日期重新查询会计期间及科目映射，准备完成后办理过账。' : '根据本轮已批准内容重新查询会计期间和科目映射，准备完成后再办理过账。' }}</p>
        <p v-else-if="pending === 'QUERY'">核对原凭证在 ERP 中的实际结果，查询本身不会发起新过账。</p>
        <p v-else>ERP 已明确确认原操作不存在。本次仍使用原编号、原金额和原会计日期，发送期限为 {{ timeLabel(view.operation!.sendExpiresAt) }}。</p>
        <label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="blocked" /></label><div class="voucher-actions"><button type="button" class="secondary" :disabled="saving" @click="pending = null">返回核对</button><button class="primary" :disabled="blocked">{{ saving ? '正在登记…' : `确认${voucherActionLabels[pending]}` }}</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.voucher-status{margin-top:26px;padding-top:20px;border-top:2px solid var(--teal)}.voucher-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.voucher-heading h3{font-size:16px;margin:0}.voucher-heading button{font-size:12px}.voucher-help{font-size:12px;line-height:1.9;color:var(--muted)}.voucher-stages{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:18px}.voucher-stages article{border:1px solid var(--line);border-radius:10px;background:var(--paper);padding:16px;min-width:0}.voucher-stages .posted{background:var(--soft);border-color:var(--teal)}.voucher-stages small{display:block;font-size:11px;color:var(--muted);margin-bottom:10px}.voucher-stages strong{font-size:14px;line-height:1.6}.voucher-stages p{font-size:11px;line-height:1.8;color:var(--muted);margin:9px 0 0}.voucher-error{font-size:12px;line-height:1.8;color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.voucher-reference{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:18px 0}.voucher-reference dt{font-size:11px;color:var(--muted);margin-bottom:8px}.voucher-reference dd{margin:0;font-size:12px;overflow-wrap:anywhere}.voucher-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.voucher-confirm{margin-top:18px;padding:18px;background:var(--paper);border:1px solid var(--line);border-radius:10px;font-size:12px;line-height:1.8}.voucher-confirm h4{font-size:14px;margin:0}.voucher-confirm label{display:grid;gap:8px}.voucher-confirm textarea{font:inherit;width:100%;padding:10px;border:1px solid var(--line);border-radius:7px;resize:vertical}@media(max-width:600px){.voucher-heading{flex-wrap:wrap}.voucher-stages,.voucher-reference{grid-template-columns:1fr}}
</style>
