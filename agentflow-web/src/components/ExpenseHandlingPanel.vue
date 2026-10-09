<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { expenseError, moneyLabel, type ExpenseDetail } from '../expenses'
import { handlingActive, handlingPath, handlingStatuses, handlingTools, stepOutcomes, type HandlingTask, type HandlingResult, type ReadTool } from '../expenseHandling'
import AgentUsagePanel from './AgentUsagePanel.vue'
import ExpenseAgentPanel from './ExpenseAgentPanel.vue'

const props = defineProps<{ report: ExpenseDetail; scopeKey: string; locked?: boolean; dirty?: boolean; refreshVersion?: number }>()
const emit = defineEmits<{ busy: [value: boolean]; navigate: [target: 'invoice' | 'draft' | 'check' | 'explanation']; draft: [value: { taskId: string; brief: string; runId?: string }] }>()
const tasks = ref<HandlingTask[]>([]), goal = ref('核对报销材料、制度依据并处理预检问题')
const loading = ref(false), saving = ref(false), error = ref(''), result = ref<HandlingResult | null>(null), pending = ref(false)
const lineNo = ref<number | ''>(''), invoiceId = ref(''), selected = ref(''), usageRefresh = ref(0)
const showAllSteps = ref(false), agentBusy = ref(false)
const active = computed(() => tasks.value.find(handlingActive))
const shown = computed(() => tasks.value.find(task => task.id === selected.value) ?? active.value ?? tasks.value[0])
const visibleSteps = computed(() => showAllSteps.value ? shown.value?.steps ?? [] : shown.value?.steps.slice(-4) ?? [])
const blocked = computed(() => !!props.locked || !!props.dirty || loading.value || saving.value || pending.value || agentBusy.value)
const canInspect = computed(() => !blocked.value && !!active.value?.current && active.value.steps.length < 32)
const invoiceIds = computed(() => [...new Set(props.report.content.lines.flatMap(line => line.invoiceIds))])
let epoch = 0, controller: AbortController | null = null, timer: ReturnType<typeof setTimeout> | undefined, polls = 0
function stop() { epoch++; controller?.abort(); controller = null; clearTimeout(timer) }
/** 未知写入只通过全局原键恢复；刷新不能偷偷重新发送业务命令。 */
function syncPending() {
  const was = pending.value
  pending.value = writeRequests.pending().some(entry => entry.path.startsWith(handlingPath(props.report.id)))
  if (was && !pending.value && !saving.value) void load()
}
const unsubscribe = writeRequests.subscribe(syncPending)
async function load() {
  if (saving.value || !props.scopeKey) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true; error.value = ''; result.value = null
  const timeout = setTimeout(() => request.abort(), 12_000)
  try {
    const values = await api.expenseHandlingTasks(props.report.id, request.signal)
    if (epoch !== current) return
    if (values.some(task => task.applicationId !== props.report.applicationId)) throw new Error('Handling application mismatch')
    tasks.value = values; usageRefresh.value++
    if (active.value?.status === 'WAITING' && polls++ < 60) timer = setTimeout(() => void load(), 2_000)
  } catch (cause) { if (epoch === current) { tasks.value = []; error.value = expenseError(cause) } }
  finally { clearTimeout(timeout); if (epoch === current) { loading.value = false; controller = null } }
}
async function change(action: () => Promise<unknown>) {
  if (blocked.value) return
  clearTimeout(timer); const current = epoch; saving.value = true; error.value = ''; result.value = null
  try { await action() }
  catch (cause) { if (epoch === current) error.value = expenseError(cause) }
  finally {
    if (epoch === current) { saving.value = false; syncPending(); if (!error.value && !pending.value) { polls = 0; await load() } }
  }
}
function start() {
  if (active.value || !goal.value.trim() || !props.report.editable) return
  return change(() => api.startExpenseHandling(props.report.id, { applicationVersion: props.report.applicationVersion, financialVersion: props.report.financialVersion, goal: goal.value.trim() }))
}
function closeTask() {
  const task = active.value
  if (task) return change(() => api.closeExpenseHandling(props.report.id, task.id, task.version))
}
/** 每次工具操作只读本人已保存材料；模型外发仍在原助手的来源确认中完成。 */
async function inspect(tool: ReadTool) {
  const task = active.value
  if (!canInspect.value || !task) return
  const input = { expectedVersion: task.version, tool, ...(tool === 'POLICY' ? { lineNo: Number(lineNo.value) } : {}), ...(tool === 'INVOICE' ? { referenceId: invoiceId.value } : {}) }
  const current = epoch; clearTimeout(timer); saving.value = true; result.value = null; error.value = ''
  try {
    const receipt = await api.inspectExpenseHandling(props.report.id, task.id, input)
    if (epoch !== current) return
    tasks.value = tasks.value.map(value => value.id === task.id ? receipt.task : value); result.value = receipt.result
  } catch (cause) { if (epoch === current) error.value = expenseError(cause) }
  finally { if (epoch === current) { saving.value = false; syncPending() } }
}
watch(() => [props.scopeKey, props.report.id, props.report.applicationVersion, props.report.financialVersion, props.refreshVersion], () => {
  stop(); tasks.value = []; result.value = null; selected.value = ''; invoiceId.value = ''; lineNo.value = ''; loading.value = false; saving.value = false; polls = 0; syncPending(); void load()
}, { immediate: true, flush: 'sync' })
watch(() => saving.value || agentBusy.value, value => emit('busy', value), { flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="handling" aria-label="报销办理助手">
    <header><div><p class="caption">本单办理记录</p><h4>报销办理助手</h4></div><button type="button" class="quiet" :disabled="saving || loading || locked" @click="polls = 0; load()">刷新记录</button></header>
    <p class="help">按原任务继续整理、核对和补正。助手建议由本人确认，预检通过后仍需确认提交审批。</p>
    <nav aria-label="报销办理步骤"><button v-for="(label, key) in { invoice: '1 · 整理票据', draft: '2 · 整理费用行', check: '3 · 预检与提交', explanation: '4 · 核对补正' }" :key="key" type="button" :disabled="saving || locked" @click="emit('navigate', key)">{{ label }}</button></nav>
    <p v-if="dirty" class="help">请先保存费用修改，再读取本单依据或开始办理记录。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="pending" role="status" class="help">原操作结果未确认。请在页面的待恢复操作中恢复原请求，再继续办理。</p>
    <form v-if="!active && report.editable" @submit.prevent="start"><label>本次办理目标<input v-model="goal" maxlength="1000" :disabled="blocked" /></label><button type="submit" class="secondary" :disabled="blocked || !goal.trim()">开始本单办理</button></form>
    <template v-if="shown">
      <select v-if="tasks.length > 1" v-model="selected" aria-label="选择办理历史"><option value="">当前办理</option><option v-for="task in tasks" :key="task.id" :value="task.id">{{ handlingStatuses[task.status] }} · {{ new Date(task.createdAt).toLocaleString('zh-CN') }}</option></select>
      <div class="state"><strong>{{ handlingStatuses[shown.status] }}</strong><span>{{ shown.steps.length }} / 32 步</span></div><p class="goal">{{ shown.goal }}</p>
      <p v-if="active && !active.current" class="help">记录对应的单据版本已变化，请重新读取费用。结束旧记录后，可为当前版本开始新办理。</p>
      <details v-if="active?.id === shown.id" class="tools"><summary>核对已保存的费用与依据</summary><div class="tool-row"><button type="button" class="secondary" :disabled="!canInspect" @click="inspect('EXPENSE')">读取当前费用</button></div><div class="tool-row"><label>费用行<select v-model="lineNo" aria-label="费用行" :disabled="!canInspect"><option value="">选择费用行</option><option v-for="line in report.content.lines" :key="line.lineNo" :value="line.lineNo">{{ line.lineNo }} · {{ line.description }}</option></select></label><button type="button" class="secondary" :disabled="!canInspect || !lineNo" @click="inspect('POLICY')">查询适用制度</button></div><div class="tool-row"><label>已关联票据<select v-model="invoiceId" :disabled="!canInspect"><option value="">选择本人票据</option><option v-for="id in invoiceIds" :key="id" :value="id">{{ id }}</option></select></label><button type="button" class="secondary" :disabled="!canInspect || !invoiceId" @click="inspect('INVOICE')">读取票据事实</button></div></details>
      <ExpenseAgentPanel v-if="active?.id === shown.id" :report="report" :task="active" :scope-key="scopeKey" :locked="!!locked || !!dirty || saving || pending" @busy="agentBusy = $event" @draft="emit('draft', $event)" @changed="polls = 0; load()" />
      <div v-if="result" class="result" role="status"><p v-if="result.expense">已读取 {{ result.expense.content.lines.length }} 行已保存费用，财务版本 {{ result.expense.financialVersion }}。</p><template v-if="result.policy"><strong>{{ result.policy.guidance.policyName }} · v{{ result.policy.guidance.policyVersion }}</strong><p>{{ result.policy.guidance.ruleName }} · 依据 {{ result.policy.guidance.factSourceReference }}</p><p>有效至 {{ new Date(result.policy.guidance.validUntil).toLocaleString('zh-CN') }}，保存与提交时会再次核对。</p></template><template v-if="result.invoice"><p>{{ result.invoice.original.filename }} · {{ ({ VERIFIED: '查验通过', PENDING: '待查验', FAILED: '查验未通过' })[result.invoice.verification] }}</p><p v-if="result.invoice.facts">查验金额 {{ moneyLabel(result.invoice.facts.gross) }} · 票据日期 {{ result.invoice.facts.issueDate }}</p></template></div>
      <ol class="steps"><li v-for="step in visibleSteps" :key="step.number"><span class="number">{{ step.number }}</span><div><strong>{{ handlingTools[step.tool] }}</strong><span>{{ stepOutcomes[step.outcome] }} · 财务版本 {{ step.financialVersion }}</span><details><summary>原始依据</summary><p>原记录 {{ step.referenceId }} · 来源版本 {{ step.sourceVersion }}</p><p>输入摘要 {{ step.inputDigest }}</p><p>{{ new Date(step.updatedAt).toLocaleString('zh-CN') }}</p></details></div></li></ol>
      <button v-if="shown.steps.length > 4" type="button" class="quiet" :aria-expanded="showAllSteps" @click="showAllSteps = !showAllSteps">{{ showAllSteps ? '只看最近 4 步' : `查看全部 ${shown.steps.length} 步` }}</button>
      <p v-if="!shown.steps.length" class="help">记录已建立。沿上方步骤办理，费用建议、预检和本人确认会保存到同一记录。</p>
      <details v-if="active?.id === shown.id"><summary>结束本次办理记录</summary><p class="help">只结束此记录，已授权的模型任务仍按原编号执行。</p><button type="button" class="quiet" :disabled="blocked" @click="closeTask">确认结束记录</button></details>
    </template>
    <AgentUsagePanel :scope-key="scopeKey" :subject-id="report.id" :refresh-version="usageRefresh" />
  </section>
</template>

<style scoped>
.handling nav{flex-direction:row}
.handling{margin:18px 0;padding:22px;background:var(--paper);border-left:3px solid var(--teal);font-size:12px;line-height:1.8;min-width:0}.handling header,.state{display:flex;justify-content:space-between;align-items:center;gap:14px}.handling h4{margin:0;font-size:19px}.caption{margin:0;color:var(--muted);font-size:11px}.help{color:var(--muted)}.handling nav{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}.handling nav button{width:auto;flex:1 1 auto;border:1px solid var(--line);background:white;padding:8px 10px;border-radius:6px;font:inherit;color:var(--deep)}.handling label{display:grid;gap:6px;flex:1;min-width:0}.handling input,.handling select{font:inherit;width:100%;min-width:0;padding:8px;border:1px solid var(--line);border-radius:5px;background:white}.handling form,.tool-row{display:flex;align-items:end;gap:10px;margin:14px 0}.state{border-top:1px solid var(--line);padding-top:16px;color:var(--deep)}.state span{font-family:'DM Mono',monospace}.goal,.result,.steps p{overflow-wrap:anywhere;white-space:pre-wrap}.steps{list-style:none;padding:0;margin:18px 0}.steps li{display:flex;gap:12px;border-bottom:1px solid var(--line);padding:12px 0}.steps li>div{min-width:0;flex:1}.steps strong,.steps span:not(.number){display:block}.steps span:not(.number){color:var(--muted)}.number{font-family:'DM Mono',monospace;color:var(--teal)}.handling summary{cursor:pointer;color:var(--deep)}.result{padding:14px;background:white;border:1px solid var(--line)}.error{color:var(--red)}@media(max-width:650px){.handling{padding:14px}.tool-row,.handling form{flex-direction:column;align-items:stretch}.handling header{align-items:start}.handling nav button{flex:1 1 40%}}
</style>
