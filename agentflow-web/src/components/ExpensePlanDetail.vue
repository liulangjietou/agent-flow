<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { expenseError, expenseTypes, moneyLabel } from '../expenses'
import { planTotal, type PlanDetail } from '../expensePlan'
import ExpensePlanEditor from './ExpensePlanEditor.vue'
import ExpensePlanLines from './ExpensePlanLines.vue'

const props = defineProps<{ planId: string; applicationId: string; scopeKey: string; version?: number; roundNo?: number; owner?: boolean; locked?: boolean }>()
const emit = defineEmits<{ changed: []; busy: [value: boolean] }>()
const detail = ref<PlanDetail | null>(null), loading = ref(false), saving = ref(false), editing = ref(false), restricted = ref(false)
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
  const timeout = setTimeout(() => { if (version === epoch) { stop(); loading.value = false; error.value = '计划明细读取超时，请重试。' } }, 12_000)
  try {
    const value = await api.expensePlan(props.planId, props.roundNo, request.signal)
    if (version !== epoch) return
    if (value.id !== props.planId || value.applicationId !== props.applicationId || props.roundNo !== undefined && (value.roundNo !== props.roundNo || value.financialRound?.roundNo !== props.roundNo)) throw new Error('Plan binding mismatch')
    detail.value = value; requiresRefresh.value = false
  } catch (cause) {
    if (version !== epoch) return
    restricted.value = (cause as { status?: number }).status === 403
    error.value = restricted.value ? '当前字段权限不允许读取完整计划明细。' : expenseError(cause)
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
  const version = epoch, input = { applicationVersion: value.applicationVersion, planVersion: value.planVersion, comment: comment.value.trim() }
  saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = action === 'WITHDRAW' ? await api.withdrawExpensePlan(value.id, input) : await api.cancelExpensePlan(value.id, input)
    if (version !== epoch) return
    if (receipt.id !== value.id || receipt.applicationId !== value.applicationId) throw new Error('Plan receipt mismatch')
    pending.value = null; requiresRefresh.value = true; saving.value = false; emit('busy', false); emit('changed'); await load()
  } catch (cause) { if (version === epoch) { error.value = expenseError(cause); requiresRefresh.value = true } }
  finally { if (version === epoch) { saving.value = false; emit('busy', false) } }
}
async function changed() { editing.value = false; emit('changed'); await load() }
watch(() => JSON.stringify([props.scopeKey, props.planId, props.applicationId, props.version, props.roundNo]), () => {
  stop(); detail.value = null; editing.value = false; saving.value = false; emit('busy', false)
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); emit('busy', false) })
</script>

<template>
  <section class="plan-detail" aria-label="事前申请明细">
    <div class="detail-heading"><h3>{{ roundNo ? `第 ${roundNo} 轮计划` : '事前申请明细' }}</h3><button type="button" class="quiet" :disabled="editing || loading || saving || locked" @click="load">刷新计划</button></div>
    <p v-if="error" class="plan-error" role="alert">{{ error }}</p><slot v-if="restricted" name="restricted" />
    <p v-if="loading" class="plan-help" role="status">正在核对计划内容与读取权限…</p>
    <template v-else-if="detail">
      <ExpensePlanEditor v-if="editing && detail.editable && roundNo === undefined" :initial="detail" :scope-key="scopeKey" :locked="locked" @busy="emit('busy', $event)" @close="changed" @submitted="changed" />
      <template v-else>
        <button v-if="detail.editable && roundNo === undefined" type="button" class="primary" :disabled="blocked" @click="editing = true">填写并提交计划</button>
        <div class="plan-context"><strong>{{ detail.content.title }}</strong><span>{{ expenseTypes[detail.content.type] }} · {{ detail.content.lines.length }} 行计划</span></div>
        <div v-if="detail.financialRound" class="plan-total"><span>本轮计划总额</span><strong>{{ moneyLabel(planTotal(detail.financialRound)) }}</strong></div>
        <p class="plan-help">{{ roundNo !== undefined ? '历史计划展示该轮提交时的金额，批准额度以最终批准轮次为准。' : detail.status === 'APPROVED' ? '本申请已批准。实际可用余额可在费用工作区「事前批准额度」查看，已预留和使用的金额会减少余额。' : '事前批准额度以最终批准轮次为准；当前计划金额不等于可用余额。' }}</p>
        <ExpensePlanLines :content="detail.content" :financial="detail.financialRound" />
        <div v-if="!pending" class="plan-actions"><button v-if="canWithdraw" type="button" class="secondary" :disabled="blocked" @click="prepare('WITHDRAW')">撤回计划审批</button><button v-if="canCancel" type="button" class="return" :disabled="blocked" @click="prepare('CANCEL')">作废事前申请</button></div>
        <form v-else class="plan-confirmation" @submit.prevent="execute"><h4>{{ pending === 'WITHDRAW' ? '确认撤回本轮计划' : '确认作废事前申请' }}</h4><p>{{ pending === 'WITHDRAW' ? '撤回将停止本轮待办。补正后重新提交会开始新一轮，原轮次和审批意见保留。' : '作废后不能恢复编辑或提交，原内容和历史审批记录保留。' }}</p><label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" :disabled="blocked" required /></label><div class="plan-actions"><button type="button" class="secondary" :disabled="saving" @click="pending = null">返回核对</button><button class="return" :disabled="blocked">{{ saving ? '正在处理…' : pending === 'WITHDRAW' ? '确认撤回计划' : '确认作废计划' }}</button></div></form>
      </template>
    </template>
  </section>
</template>

<style scoped>
.plan-detail{min-width:0;margin:18px 0}.detail-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}.detail-heading h3{font-size:17px;margin:0}.detail-heading button{font-size:12px}.plan-context{display:grid;gap:7px;margin:18px 0}.plan-context strong{font-size:15px;overflow-wrap:anywhere}.plan-context span,.plan-help{font-size:12px;color:var(--muted);line-height:1.9}.plan-total{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:20px 16px;background:var(--soft);border-radius:12px;font-size:12px}.plan-total strong{font:500 15px 'DM Mono',monospace;overflow-wrap:anywhere}.plan-error{font-size:12px;line-height:1.8;color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.plan-actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:20px}.plan-confirmation{margin-top:20px;padding:18px;background:var(--paper);border:1px solid var(--line);border-radius:10px;font-size:12px;line-height:1.8}.plan-confirmation label{display:grid;gap:8px}.plan-confirmation textarea{width:100%;padding:12px;resize:vertical;border:1px solid var(--line);border-radius:7px;font:inherit}.plan-confirmation h4{margin-top:0}
</style>
