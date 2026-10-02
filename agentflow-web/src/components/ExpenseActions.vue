<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { selectedApprovalProxy } from '../taskActions'
import { changedReductions, expenseError, moneyLabel, reductionReasons, type ExpenseDetail, type ExpenseWorkflow, type ReductionLine, type ReductionReason } from '../expenses'

const props = defineProps<{ detail: ExpenseDetail; workflow: ExpenseWorkflow; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ changed: []; busy: [value: boolean]; refresh: [] }>()
type Action = 'RECEIVE' | 'REDUCE' | 'WITHDRAW' | 'CANCEL'
const labels: Record<Action, string> = { RECEIVE: '确认原件签收', REDUCE: '确认财务核减', WITHDRAW: '确认撤回审批', CANCEL: '确认作废费用单' }
const pending = ref<Action | null>(null), comment = ref(''), reason = ref<ReductionReason | ''>(''), inputs = ref<ReductionLine[]>([])
const selectedProxy = ref('')
const proxyOptions = computed(() => props.workflow.task?.proxyOptions ?? [])
const proxy = computed(() => proxyOptions.value.find(option => option.proxyId === selectedProxy.value))
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
const saving = ref(false), error = ref(''), requiresRefresh = ref(false)
const formElement = ref<HTMLFormElement | null>(null)
let epoch = 0
const allowed = computed(() => ({ RECEIVE: props.workflow.task?.canReceive === true, REDUCE: props.workflow.task?.canReduce === true && !!props.detail.financialRound,
  WITHDRAW: props.workflow.canWithdraw, CANCEL: props.workflow.canCancel }))
const blocked = computed(() => props.locked || saving.value || requiresRefresh.value)
function reset() { pending.value = null; comment.value = ''; reason.value = ''; inputs.value = []; selectedProxy.value = ''; error.value = '' }
watch(() => [props.scopeKey, props.detail.id, props.detail.applicationVersion, props.detail.financialVersion, props.workflow.task?.taskId,
  JSON.stringify([props.workflow.task?.canActDirectly, props.workflow.task?.proxyOptions, allowed.value])], () => {
  epoch++; reset(); requiresRefresh.value = false; saving.value = false; emit('busy', false)
}, { flush: 'sync' })
onUnmounted(() => { epoch++; emit('busy', false) })
function prepare(action: Action) {
  if (blocked.value || !allowed.value[action]) return
  reset(); pending.value = action
  if ((action === 'RECEIVE' || action === 'REDUCE') && props.workflow.task?.canActDirectly === false && proxyOptions.value.length === 1) selectedProxy.value = proxyOptions.value[0].proxyId
  if (action === 'REDUCE') inputs.value = props.detail.financialRound!.approvedLines.map(line => ({ lineNo: line.lineNo, approvedGross: line.gross.value, approvedTax: line.tax.value }))
  const generation = epoch
  void nextTick(() => { if (generation === epoch && pending.value === action) formElement.value?.querySelector<HTMLElement>('select, input, textarea')?.focus() })
}
function cancel() { if (!saving.value) reset() }
async function execute() {
  const action = pending.value
  if (!action || blocked.value || !allowed.value[action]) return
  error.value = ''
  if (!comment.value.trim()) { error.value = '请填写本次操作说明。'; return }
  if (action === 'REDUCE' && !reason.value) { error.value = '请选择核减原因。'; return }
  let lines: ReductionLine[] = []
  if (action === 'REDUCE') {
    try { lines = changedReductions(props.detail.financialRound!.approvedLines, inputs.value) }
    catch (cause) { error.value = (cause as Error).message; return }
  }
  const generation = epoch, id = props.detail.id, taskId = props.workflow.task?.taskId
  let proxyId: string | undefined
  if (action === 'RECEIVE' || action === 'REDUCE') {
    try { proxyId = selectedApprovalProxy(props.workflow.task!, selectedProxy.value)?.proxyId }
    catch (cause) { error.value = (cause as Error).message; return }
  }
  const input = { applicationVersion: props.detail.applicationVersion, financialVersion: props.detail.financialVersion,
    comment: comment.value.trim(), ...(proxyId ? { proxyId } : {}) }
  saving.value = true; emit('busy', true)
  try {
    const result = action === 'RECEIVE' ? await api.receiveExpense(id, taskId!, input)
      : action === 'REDUCE' ? await api.reduceExpense(id, taskId!, { ...input, reasonCode: reason.value as ReductionReason, lines })
      : action === 'WITHDRAW' ? await api.withdrawExpense(id, input) : await api.cancelExpense(id, input)
    if (generation !== epoch) return
    if (result.reportId !== id || result.applicationId !== props.detail.applicationId) throw new Error('Expense receipt mismatch')
    reset(); requiresRefresh.value = true; saving.value = false; emit('busy', false); emit('changed')
  } catch (cause) {
    if (generation !== epoch) return
    error.value = expenseError(cause); requiresRefresh.value = true
  } finally { if (generation === epoch) { saving.value = false; emit('busy', false) } }
}
</script>

<template>
  <section class="expense-actions" aria-label="费用操作" v-if="Object.values(allowed).some(Boolean) || error">
    <p v-if="error" class="expense-error" role="alert">{{ error }}</p>
    <div v-if="!pending" class="action-row">
      <button v-if="allowed.RECEIVE" class="primary" :disabled="blocked" @click="prepare('RECEIVE')">确认原件签收</button>
      <button v-if="allowed.REDUCE" class="secondary" :disabled="blocked" @click="prepare('REDUCE')">核减费用</button>
      <button v-if="allowed.WITHDRAW" class="secondary" :disabled="blocked" @click="prepare('WITHDRAW')">撤回审批</button>
      <button v-if="allowed.CANCEL" class="return" :disabled="blocked" @click="prepare('CANCEL')">作废费用单</button>
    </div>
    <form v-else ref="formElement" @submit.prevent="execute">
      <h4>{{ labels[pending] }}</h4>
      <p v-if="pending === 'RECEIVE'">请核对本轮纸质原件与费用明细。签收会记录办理人和时间，随后仍需完成当前节点审批。</p>
      <p v-if="pending === 'WITHDRAW'">撤回会停止本轮待办，保留现有占用供补正。重新提交将开始新一轮审批。</p>
      <p v-if="pending === 'CANCEL'">作废后不能再编辑或提交。已预留资金将安排释放，原内容和历史记录保留。</p>
      <template v-if="(pending === 'RECEIVE' || pending === 'REDUCE') && proxyOptions.length">
        <label>办理身份<select v-model="selectedProxy" :disabled="blocked" :required="workflow.task?.canActDirectly === false">
          <option v-if="workflow.task?.canActDirectly !== false" value="">以本人审批职责办理</option>
          <option v-else value="" disabled>请选择本次代理的原审批人</option>
          <option v-for="option in proxyOptions" :key="option.proxyId" :value="option.proxyId">代理 {{ option.principal }} 办理</option>
        </select></label>
        <p v-if="proxy">代理有效至 {{ timeLabel(proxy.endsAt) }}。本次操作保留实际办理人和原审批依据，当前节点仍需另行审批。</p>
      </template>
      <template v-if="pending === 'REDUCE'">
        <p>只填写核减后的金额。原始提交内容保留；借款抵扣随总额减少，预算调整确认前不能批准本节点。</p>
        <div class="reduction-table" role="group" aria-label="核减后金额">
          <div class="reduction-head"><span>费用行 / 当前核定</span><span>核减后含税额</span><span>核减后可抵扣税额</span></div>
          <div v-for="(line, index) in inputs" :key="line.lineNo" class="reduction-row">
            <div><strong>第 {{ line.lineNo }} 行</strong><small>{{ moneyLabel(detail.financialRound!.approvedLines[index]!.gross) }}</small></div>
            <label><span class="mobile-label">核减后含税额</span><input v-model="line.approvedGross" :aria-label="`第 ${line.lineNo} 行核减后含税额`" inputmode="decimal" autocomplete="off" maxlength="18" :disabled="blocked" required /></label>
            <label><span class="mobile-label">核减后可抵扣税额</span><input v-model="line.approvedTax" :aria-label="`第 ${line.lineNo} 行核减后可抵扣税额`" inputmode="decimal" autocomplete="off" maxlength="18" :disabled="blocked" required /></label>
          </div>
        </div>
        <label>核减原因<select v-model="reason" :disabled="blocked" required><option value="" disabled>请选择原因</option><option v-for="(text, value) in reductionReasons" :key="value" :value="value">{{ text }}</option></select></label>
      </template>
      <label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" :disabled="blocked" required /></label>
      <div class="action-row"><button type="button" class="secondary" :disabled="saving" @click="cancel">取消</button><button :class="pending === 'CANCEL' ? 'return' : 'primary'" :disabled="blocked">{{ saving ? '正在提交…' : labels[pending] }}</button></div>
    </form>
    <button v-if="requiresRefresh" type="button" class="secondary" :disabled="saving || locked" @click="emit('refresh')">刷新费用状态</button>
  </section>
</template>

<style scoped>
.expense-actions{margin-top:22px;padding-top:18px;border-top:1px solid var(--line)}.action-row{display:flex;gap:10px;flex-wrap:wrap}.expense-actions h4{font-size:15px;margin:0 0 10px}.expense-actions p{font-size:12px;line-height:1.8;color:var(--muted)}.expense-actions .expense-error{color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.expense-actions form{background:var(--paper);padding:18px;border-radius:12px}.expense-actions label{display:grid;gap:8px;margin:14px 0;font-size:12px}.expense-actions textarea{font:inherit;resize:vertical;border:1px solid var(--line);border-radius:8px;padding:10px;max-width:100%}.expense-actions textarea:focus-visible{outline:3px solid var(--teal);outline-offset:2px}.reduction-head,.reduction-row{display:grid;grid-template-columns:1.2fr 1fr 1fr;gap:12px;align-items:center}.reduction-head{font-size:11px;color:var(--muted);padding:10px 0;border-bottom:1px solid var(--line)}.reduction-row{border-bottom:1px solid var(--line);padding:10px 0}.reduction-row small{display:block;font:10px 'DM Mono',monospace;margin-top:5px;overflow-wrap:anywhere}.reduction-row strong{font-size:12px}.reduction-row label{margin:0;min-width:0}.reduction-row input{width:100%;min-width:0;font:12px 'DM Mono',monospace}.mobile-label{display:none}@media(max-width:600px){.reduction-head{display:none}.reduction-row{grid-template-columns:1fr 1fr}.reduction-row>div{grid-column:1/-1}.mobile-label{display:block;font-size:10px}.expense-actions form{padding:12px}}
</style>
