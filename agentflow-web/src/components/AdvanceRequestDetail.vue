<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { expenseError } from '../expenses'
import { type AdvanceDetail } from '../advanceRequest'
import AdvanceRequestEditor from './AdvanceRequestEditor.vue'
import AdvanceRequestTerms from './AdvanceRequestTerms.vue'
import VoucherStatus from './VoucherStatus.vue'
import FinancePaymentStatus from './FinancePaymentStatus.vue'

const props = defineProps<{ requestId: string; applicationId: string; scopeKey: string; version?: number; roundNo?: number; owner?: boolean; locked?: boolean }>()
const emit = defineEmits<{ changed: []; busy: [value: boolean] }>()
const detail = ref<AdvanceDetail | null>(null), loading = ref(false), saving = ref(false), editing = ref(false), restricted = ref(false)
const error = ref(''), pending = ref<'WITHDRAW' | 'CANCEL' | null>(null), comment = ref(''), requiresRefresh = ref(false)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => props.locked || saving.value || loading.value || requiresRefresh.value)
const canWithdraw = computed(() => props.owner && props.roundNo === undefined && detail.value?.status === 'IN_APPROVAL')
const canCancel = computed(() => props.owner && props.roundNo === undefined && !!detail.value && ['DRAFT', 'RETURNED', 'WITHDRAWN'].includes(detail.value.status))
function stop() { epoch++; controller?.abort(); controller = null }
/** 身份、申请与轮次共同限定查询；迟到结果不能重新显示旧身份的明细。 */
async function load() {
  if (saving.value) return
  stop(); const version = epoch, request = new AbortController(); controller = request
  detail.value = null; restricted.value = false; error.value = ''; pending.value = null; comment.value = ''; loading.value = true
  const timeout = setTimeout(() => { if (version === epoch) { stop(); loading.value = false; error.value = '借款明细读取超时，请重试。' } }, 12_000)
  try {
    const value = await api.advanceRequest(props.requestId, props.roundNo, request.signal)
    if (version !== epoch) return
    if (value.id !== props.requestId || value.applicationId !== props.applicationId || props.roundNo !== undefined && (value.roundNo !== props.roundNo || value.financialRound?.roundNo !== props.roundNo)) throw new Error('Advance binding mismatch')
    detail.value = value; requiresRefresh.value = false
  } catch (cause) {
    if (version !== epoch) return
    restricted.value = (cause as { status?: number }).status === 403
    error.value = restricted.value ? '当前字段权限不允许读取完整借款明细。' : expenseError(cause)
  } finally { clearTimeout(timeout); if (version === epoch) { loading.value = false; controller = null } }
}
function prepare(action: 'WITHDRAW' | 'CANCEL') {
  if (blocked.value || !(action === 'WITHDRAW' ? canWithdraw.value : canCancel.value)) return
  pending.value = action; comment.value = ''; error.value = ''
}
async function execute() {
  const action = pending.value, value = detail.value
  if (!action || !value || blocked.value || !(action === 'WITHDRAW' ? canWithdraw.value : canCancel.value)) return
  if (!comment.value.trim() || comment.value.length > 2000) { error.value = '请填写 2000 字以内的操作说明。'; return }
  const version = epoch, input = { applicationVersion: value.applicationVersion, requestVersion: value.requestVersion, comment: comment.value.trim() }
  saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = action === 'WITHDRAW' ? await api.withdrawAdvanceRequest(value.id, input) : await api.cancelAdvanceRequest(value.id, input)
    if (version !== epoch) return
    if (receipt.id !== value.id || receipt.applicationId !== value.applicationId) throw new Error('Advance receipt mismatch')
    pending.value = null; requiresRefresh.value = true; saving.value = false; emit('busy', false); emit('changed'); await load()
  } catch (cause) { if (version === epoch) { error.value = expenseError(cause); requiresRefresh.value = true } }
  finally { if (version === epoch) { saving.value = false; emit('busy', false) } }
}
async function changed() { editing.value = false; emit('changed'); await load() }
watch(() => JSON.stringify([props.scopeKey, props.requestId, props.applicationId, props.version, props.roundNo]), () => {
  stop(); detail.value = null; editing.value = false; saving.value = false; emit('busy', false)
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); emit('busy', false) })
</script>

<template>
  <section class="advance-detail" aria-label="借款申请明细">
    <div class="detail-heading"><h3>{{ roundNo ? `第 ${roundNo} 轮借款` : '借款申请明细' }}</h3><button type="button" class="quiet" :disabled="editing || loading || saving || locked" @click="load">刷新借款</button></div>
    <p v-if="error" class="advance-error" role="alert">{{ error }}</p><slot v-if="restricted" name="restricted" />
    <p v-if="loading" class="advance-help" role="status">正在核对借款内容与读取权限…</p>
    <template v-else-if="detail">
      <AdvanceRequestEditor v-if="editing && detail.editable && roundNo === undefined" :initial="detail" :scope-key="scopeKey" :locked="locked" @busy="emit('busy', $event)" @close="changed" @submitted="changed" />
      <template v-else>
        <button v-if="detail.editable && roundNo === undefined" type="button" class="primary" :disabled="blocked" @click="editing = true">填写并提交借款</button>
        <div class="advance-context"><strong>{{ detail.content.title }}</strong><span>{{ detail.businessNo }}</span></div>
        <p class="advance-help">{{ roundNo !== undefined ? '此处保留本轮借款约定。是否已付款，请以实际到账及结算记录为准。' : detail.approval ? '借款已批准，等待后续结算。实际付款成功后才会出现在已到账借款余额中。' : '提交及审批中的借款金额尚未形成可冲销余额。' }}</p>
        <AdvanceRequestTerms :content="detail.content" :financial="detail.financialRound" />
        <p v-if="detail.approval" class="advance-help">本轮批准：{{ detail.approval.approvedBy }} · {{ new Date(detail.approval.approvedAt).toLocaleString('zh-CN') }}</p>
        <VoucherStatus v-if="detail.approval" :application-id="detail.applicationId" :business-id="detail.id" business-type="ADVANCE_REQUEST" :round-no="detail.roundNo" :application-version="detail.applicationVersion" :business-version="detail.requestVersion" :scope-key="scopeKey" :locked="locked" @busy="emit('busy', $event)" />
        <FinancePaymentStatus v-if="detail.approval" :application-id="detail.applicationId" :business-id="detail.id" :round-no="detail.roundNo" :application-version="detail.applicationVersion" :business-version="detail.requestVersion" :scope-key="scopeKey" :locked="locked" @busy="emit('busy', $event)" />
        <VoucherStatus v-if="detail.approval" payment :application-id="detail.applicationId" :business-id="detail.id" business-type="ADVANCE_REQUEST" :round-no="detail.roundNo" :application-version="detail.applicationVersion" :business-version="detail.requestVersion" :scope-key="scopeKey" :locked="locked" @busy="emit('busy', $event)" />
        <div v-if="!pending" class="advance-actions"><button v-if="canWithdraw" type="button" class="secondary" :disabled="blocked" @click="prepare('WITHDRAW')">撤回借款审批</button><button v-if="canCancel" type="button" class="return" :disabled="blocked" @click="prepare('CANCEL')">作废借款申请</button></div>
        <form v-else class="advance-confirmation" @submit.prevent="execute"><h4>{{ pending === 'WITHDRAW' ? '确认撤回本轮借款' : '确认作废借款申请' }}</h4><p>{{ pending === 'WITHDRAW' ? '撤回将停止本轮待办。补正后重新提交会开始新一轮，原轮次和审批意见保留。' : '作废后不能恢复编辑或提交，原内容和历史审批记录保留。' }}</p><label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" :disabled="blocked" required /></label><div class="advance-actions"><button type="button" class="secondary" :disabled="saving" @click="pending = null">返回核对</button><button class="return" :disabled="blocked">{{ saving ? '正在处理…' : pending === 'WITHDRAW' ? '确认撤回借款' : '确认作废借款' }}</button></div></form>
      </template>
    </template>
  </section>
</template>

<style scoped>
.advance-detail{min-width:0;margin:18px 0}.detail-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}.detail-heading h3{font-size:17px;margin:0}.detail-heading button{font-size:12px}.advance-context{display:grid;gap:7px;margin:18px 0}.advance-context strong{font-size:15px;overflow-wrap:anywhere}.advance-context span,.advance-help{font-size:12px;color:var(--muted);line-height:1.9}.advance-error{font-size:12px;line-height:1.8;color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.advance-actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:20px}.advance-confirmation{margin-top:20px;padding:18px;background:var(--paper);border:1px solid var(--line);border-radius:10px;font-size:12px;line-height:1.8}.advance-confirmation label{display:grid;gap:8px}.advance-confirmation textarea{width:100%;padding:12px;resize:vertical;border:1px solid var(--line);border-radius:7px;font:inherit}.advance-confirmation h4{margin-top:0}
</style>
