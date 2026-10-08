<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { selectedApprovalProxy } from '../taskActions'
import { expenseError, moneyLabel, previewReduction, reductionReasons, type ExpenseDetail, type ExpenseWorkflow, type ExpenseReductionPreview, type ExpenseReturnRequest, type ReductionLine, type ReductionReason } from '../expenses'

const props = defineProps<{ detail: ExpenseDetail; workflow: ExpenseWorkflow; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ changed: []; busy: [value: boolean]; refresh: []; preview: [value: ExpenseReductionPreview | null]; returnMissing: [value: ExpenseReturnRequest] }>()
type Action = 'RECEIVE' | 'REDUCE' | 'WITHDRAW' | 'CANCEL' | 'REVOKE'
const labels: Record<Action, string> = { RECEIVE: '确认原件签收', REDUCE: '确认财务核减', WITHDRAW: '确认撤回审批', CANCEL: '确认作废费用单', REVOKE: '确认撤销已批准报销' }
const pending = ref<Action | null>(null), comment = ref(''), reason = ref<ReductionReason | ''>(''), inputs = ref<ReductionLine[]>([])
const selectedProxy = ref('')
const receivedOriginals = ref<string[]>([])
const originals = computed(() => [{ key: 'report', label: '报销单纸质材料' }, ...props.detail.content.lines.flatMap(line =>
  line.invoiceIds.map((id, index) => ({ key: id, label: `第 ${line.lineNo} 行第 ${index + 1} 份发票纸质材料` })))])
const missingOriginals = computed(() => originals.value.filter(item => !receivedOriginals.value.includes(item.key)))
const proxyOptions = computed(() => props.workflow.task?.proxyOptions ?? [])
const proxy = computed(() => proxyOptions.value.find(option => option.proxyId === selectedProxy.value))
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
const saving = ref(false), error = ref(''), requiresRefresh = ref(false)
const formElement = ref<HTMLFormElement | null>(null)
let epoch = 0
const allowed = computed(() => ({ RECEIVE: props.workflow.task?.canReceive === true, REDUCE: props.workflow.task?.canReduce === true && !!props.detail.financialRound,
  WITHDRAW: props.workflow.canWithdraw, CANCEL: props.workflow.canCancel, REVOKE: props.workflow.revocation?.allowed === true }))
const blocked = computed(() => props.locked || saving.value || requiresRefresh.value)
const reduction = computed(() => {
  if (pending.value !== 'REDUCE') return { preview: null, issue: '' }
  try {
    const preview = previewReduction(props.detail.financialRound!, inputs.value)
    return { preview, issue: preview.lines.length ? '' : '请至少减少一行的含税额或可抵扣税额。' }
  } catch (cause) { return { preview: null, issue: (cause as Error).message } }
})
const reductionIssue = computed(() => {
  if (reduction.value.issue) return reduction.value.issue
  const missing = reduction.value.preview?.lines.find(line => !line.reasonCode)
  return missing ? `请选择第 ${missing.lineNo} 行的核减原因。` : !comment.value.trim() ? '请填写本次操作说明。' : ''
})
// 统一填写只是逐行选择的快捷方式，随后允许单独更改任意行。
watch(reason, value => { if (value) for (const line of inputs.value) line.reasonCode = value }, { flush: 'sync' })
watch(() => reduction.value.preview, value => emit('preview', value), { flush: 'sync' })
// 打开确认表单即锁住同一任务的审批，表单自己的保存与取消仍由 blocked 控制。
watch(() => pending.value !== null || saving.value || requiresRefresh.value, value => emit('busy', value), { flush: 'sync' })
function reset() { pending.value = null; comment.value = ''; reason.value = ''; inputs.value = []; selectedProxy.value = ''; receivedOriginals.value = []; error.value = '' }
watch(() => [props.scopeKey, props.detail.id, props.detail.applicationVersion, props.detail.financialVersion, props.workflow.task?.taskId,
  JSON.stringify([props.workflow.task?.canActDirectly, props.workflow.task?.proxyOptions, allowed.value])], () => {
  epoch++; reset(); requiresRefresh.value = false; saving.value = false
}, { flush: 'sync' })
onUnmounted(() => { epoch++; emit('busy', false); emit('preview', null) })
function prepare(action: Action) {
  if (blocked.value || !allowed.value[action]) return
  reset(); pending.value = action
  if ((action === 'RECEIVE' || action === 'REDUCE') && props.workflow.task?.canActDirectly === false && proxyOptions.value.length === 1) selectedProxy.value = proxyOptions.value[0].proxyId
  if (action === 'REDUCE') inputs.value = props.detail.financialRound!.approvedLines.map(line => ({ lineNo: line.lineNo, approvedGross: line.gross.value, approvedTax: line.tax.value, reasonCode: '' }))
  const generation = epoch
  void nextTick(() => { if (generation === epoch && pending.value === action) formElement.value?.querySelector<HTMLElement>('select, input, textarea')?.focus() })
}
function cancel() { if (!saving.value) reset() }
/** 缺件只产生当前任务的退回草稿，不签收、不直接退回，也不将票据标识写入通用意见。 */
function returnMissing() {
  const task = props.workflow.task
  if (pending.value !== 'RECEIVE' || blocked.value || !allowed.value.RECEIVE || !task || !missingOriginals.value.length) return
  const request: ExpenseReturnRequest = { applicationId: props.detail.applicationId,
    draft: { scopeKey: props.scopeKey, taskId: task.taskId, expectedVersion: props.detail.applicationVersion,
      comment: `纸质材料缺失，请补齐后重新提交：${missingOriginals.value.map(item => item.label).join('；')}。${comment.value.trim() ? '\n' + comment.value.trim() : ''}` } }
  reset(); emit('returnMissing', request)
}
async function execute() {
  const action = pending.value
  if (!action || blocked.value || !allowed.value[action]) return
  error.value = ''
  if (action === 'RECEIVE' && missingOriginals.value.length) { error.value = '请逐项确认收到的纸质材料；存在缺失时请退回补齐。'; return }
  if (!comment.value.trim()) { error.value = '请填写本次操作说明。'; return }
  let lines: ReductionLine[] = []
  if (action === 'REDUCE') {
    if (reductionIssue.value) { error.value = reductionIssue.value; return }
    lines = reduction.value.preview!.lines
  }
  const reasonCode = new Set(lines.map(line => line.reasonCode)).size === 1 ? lines[0]!.reasonCode as ReductionReason : 'OTHER'
  const generation = epoch, id = props.detail.id, taskId = props.workflow.task?.taskId
  let proxyId: string | undefined
  if (action === 'RECEIVE' || action === 'REDUCE') {
    try { proxyId = selectedApprovalProxy(props.workflow.task!, selectedProxy.value)?.proxyId }
    catch (cause) { error.value = (cause as Error).message; return }
  }
  const input = { applicationVersion: props.detail.applicationVersion, financialVersion: props.detail.financialVersion,
    comment: comment.value.trim(), ...(proxyId ? { proxyId } : {}) }
  saving.value = true
  try {
    const result = action === 'RECEIVE' ? await api.receiveExpense(id, taskId!, input)
      : action === 'REDUCE' ? await api.reduceExpense(id, taskId!, { ...input, reasonCode, lines })
      : action === 'WITHDRAW' ? await api.withdrawExpense(id, input)
      : action === 'REVOKE' ? await api.revokeExpense(id, input) : await api.cancelExpense(id, input)
    if (generation !== epoch) return
    if (result.reportId !== id || result.applicationId !== props.detail.applicationId) throw new Error('Expense receipt mismatch')
    if (action === 'REVOKE' && (!('status' in result) || result.status !== 'REVOKED'
        || result.applicationVersion !== input.applicationVersion + 1 || result.financialVersion !== input.financialVersion)) throw new Error('Expense revocation receipt mismatch')
    requiresRefresh.value = true; reset(); saving.value = false; emit('changed')
  } catch (cause) {
    if (generation !== epoch) return
    error.value = expenseError(cause); requiresRefresh.value = true
  } finally { if (generation === epoch) saving.value = false }
}
</script>

<template>
  <section class="expense-actions" aria-label="费用操作" v-if="Object.values(allowed).some(Boolean) || workflow.revocation?.unavailable || error">
    <p v-if="error" class="expense-error" role="alert">{{ error }}</p>
    <p v-if="workflow.revocation?.unavailable" class="expense-error">{{ expenseError({ code: workflow.revocation.unavailable }) }}</p>
    <div v-if="!pending" class="action-row">
      <button v-if="allowed.RECEIVE" class="primary" :disabled="blocked" @click="prepare('RECEIVE')">确认原件签收</button>
      <button v-if="allowed.REDUCE" class="secondary" :disabled="blocked" @click="prepare('REDUCE')">核减费用</button>
      <button v-if="allowed.WITHDRAW" class="secondary" :disabled="blocked" @click="prepare('WITHDRAW')">撤回审批</button>
      <button v-if="allowed.CANCEL" class="return" :disabled="blocked" @click="prepare('CANCEL')">作废费用单</button>
      <button v-if="allowed.REVOKE" class="return" :disabled="blocked" @click="prepare('REVOKE')">撤销已批准报销</button>
    </div>
    <form v-else ref="formElement" @submit.prevent="execute">
      <h4>{{ labels[pending] }}</h4>
      <template v-if="pending === 'RECEIVE'">
        <p>逐项勾选本轮已收到的纸质材料。纸质签收不替代电子票据原文件；签收后仍需完成当前节点审批。</p>
        <fieldset class="receipt-checklist"><legend>已收到的纸质材料</legend>
          <label v-for="item in originals" :key="item.key"><input v-model="receivedOriginals" type="checkbox" :value="item.key" :disabled="blocked" />{{ item.label }}</label>
        </fieldset>
        <p v-if="missingOriginals.length" role="status">还有 {{ missingOriginals.length }} 项未确认收到，不能签收。缺失项会带入退回原因，仍需确认退回。</p>
        <button type="button" class="return" :disabled="blocked || !missingOriginals.length" @click="returnMissing">缺失并退回</button>
      </template>
      <p v-if="pending === 'WITHDRAW'">撤回会停止本轮待办，保留现有占用供补正。重新提交将开始新一轮审批。</p>
      <p v-if="pending === 'CANCEL'">作废后不能再编辑或提交。已预留资金将安排释放，原内容和历史记录保留。</p>
      <p v-if="pending === 'REVOKE'">确认不再报销后，此单进入已撤销状态，不能编辑或重提。发票、事前额度和借款预留释放；预算等待外部释放确认。原批准轮次及财务记录保留。</p>
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
          <div class="reduction-head"><span>费用行 / 当前核定</span><span>核减后含税额</span><span>核减后可抵扣税额</span><span>本行核减原因</span></div>
          <div v-for="(line, index) in inputs" :key="line.lineNo" class="reduction-row">
            <div><strong>第 {{ line.lineNo }} 行</strong><small>{{ moneyLabel(detail.financialRound!.approvedLines[index]!.gross) }}</small></div>
            <label><span class="mobile-label">核减后含税额</span><input v-model="line.approvedGross" :aria-label="`第 ${line.lineNo} 行核减后含税额`" inputmode="decimal" autocomplete="off" maxlength="18" :disabled="blocked" required /></label>
            <label><span class="mobile-label">核减后可抵扣税额</span><input v-model="line.approvedTax" :aria-label="`第 ${line.lineNo} 行核减后可抵扣税额`" inputmode="decimal" autocomplete="off" maxlength="18" :disabled="blocked" required /></label>
            <label><span class="mobile-label">本行核减原因</span><select v-model="line.reasonCode" :aria-label="`第 ${line.lineNo} 行核减原因`" :disabled="blocked"><option value="" disabled>请选择原因</option><option v-for="(text, value) in reductionReasons" :key="value" :value="value">{{ text }}</option></select></label>
          </div>
        </div>
        <label>统一填写原因（可逐行调整）<select v-model="reason" aria-label="统一填写核减原因" :disabled="blocked"><option value="" disabled>可选：为各行统一填写</option><option v-for="(text, value) in reductionReasons" :key="value" :value="value">{{ text }}</option></select></label>
        <div v-if="reduction.preview" class="reduction-preview" aria-label="尚未保存的核减预览" aria-live="polite">
          <strong>核减预览 · 尚未保存</strong>
          <dl><div><dt>原申报总额</dt><dd>{{ moneyLabel(reduction.preview.original) }}</dd></div><div><dt>核减后核定</dt><dd>{{ moneyLabel(reduction.preview.gross) }}</dd></div><div><dt>借款冲销</dt><dd>{{ moneyLabel(reduction.preview.offset) }}</dd></div><div><dt>应付余额</dt><dd>{{ moneyLabel(reduction.preview.payable) }}</dd></div></dl>
          <p>核减后可抵扣税额 {{ moneyLabel(reduction.preview.tax) }}。保存并确认预算调整后，再办理审批。</p>
        </div>
        <p v-if="reductionIssue" class="expense-error" role="status">{{ reductionIssue }}</p>
      </template>
      <label>操作说明<textarea v-model="comment" rows="3" maxlength="2000" :disabled="blocked" required /></label>
      <div class="action-row"><button type="button" class="secondary" :disabled="saving" @click="cancel">取消</button><button :class="pending === 'CANCEL' || pending === 'REVOKE' ? 'return' : 'primary'" :disabled="blocked || (pending === 'REDUCE' && !!reductionIssue) || (pending === 'RECEIVE' && !!missingOriginals.length)">{{ saving ? '正在提交…' : labels[pending] }}</button></div>
    </form>
    <button v-if="requiresRefresh" type="button" class="secondary" :disabled="saving || locked" @click="emit('refresh')">刷新费用状态</button>
  </section>
</template>

<style scoped>
.receipt-checklist{margin:14px 0;padding:8px 14px;border:1px solid var(--line);border-radius:8px}.receipt-checklist legend{font-size:12px}.expense-actions .receipt-checklist label{display:flex;align-items:center;gap:10px}.receipt-checklist input{flex-shrink:0;width:16px;height:16px}
.reduction-preview{padding:14px;border:1px solid var(--line);border-radius:8px}.reduction-preview>strong{font-size:12px}.reduction-preview dl{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:12px 0}.reduction-preview dt{font-size:11px;color:var(--muted)}.reduction-preview dd{margin:6px 0 0;font:12px 'DM Mono',monospace;overflow-wrap:anywhere}
.expense-actions{margin-top:22px;padding-top:18px;border-top:1px solid var(--line)}.action-row{display:flex;gap:10px;flex-wrap:wrap}.expense-actions h4{font-size:15px;margin:0 0 10px}.expense-actions p{font-size:12px;line-height:1.8;color:var(--muted)}.expense-actions .expense-error{color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.expense-actions form{background:var(--paper);padding:18px;border-radius:12px}.expense-actions label{display:grid;gap:8px;margin:14px 0;font-size:12px}.expense-actions textarea{font:inherit;resize:vertical;border:1px solid var(--line);border-radius:8px;padding:10px;max-width:100%}.expense-actions textarea:focus-visible{outline:3px solid var(--teal);outline-offset:2px}.reduction-head,.reduction-row{display:grid;grid-template-columns:1.2fr 1fr 1fr 1.4fr;gap:12px;align-items:center}.reduction-head{font-size:11px;color:var(--muted);padding:10px 0;border-bottom:1px solid var(--line)}.reduction-row{border-bottom:1px solid var(--line);padding:10px 0}.reduction-row small{display:block;font:10px 'DM Mono',monospace;margin-top:5px;overflow-wrap:anywhere}.reduction-row strong{font-size:12px}.reduction-row label{margin:0;min-width:0}.reduction-row select{width:100%;min-width:0;font-size:12px}.reduction-row input{width:100%;min-width:0;font:12px 'DM Mono',monospace}.mobile-label{display:none}@media(max-width:600px){.reduction-head{display:none}.reduction-row{grid-template-columns:1fr 1fr}.reduction-row>div{grid-column:1/-1}.mobile-label{display:block;font-size:10px}.expense-actions form{padding:12px}}
</style>
