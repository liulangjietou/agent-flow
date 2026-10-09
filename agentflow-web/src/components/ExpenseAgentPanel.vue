<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { expenseError, type ExpenseDetail } from '../expenses'
import type { InvoiceItem } from '../invoiceWallet'
import type { HandlingTask } from '../expenseHandling'
import { agentActions, agentStatuses, agentPath, type AgentPreview, type AgentScope, type ExpenseAgentView, type HandlingRead } from '../expenseAgent'
import InvoiceExtraction from './InvoiceExtraction.vue'

const props = defineProps<{ report: ExpenseDetail; task: HandlingTask; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ busy: [boolean]; draft: [{ taskId: string; brief: string; runId?: string }]; changed: [] }>()
const run = ref<ExpenseAgentView | null>(null), preview = ref<AgentPreview | null>(null), reads = ref<HandlingRead[]>([])
const wallet = ref<InvoiceItem[]>([]), invoice = ref<InvoiceItem | null>(null)
const lines = ref<number[]>([]), invoices = ref<string[]>([]), maxSteps = ref(8), consent = ref(false)
const answer = ref(''), acknowledge = ref(false), loading = ref(false), saving = ref(false), childBusy = ref(false), childDirty = ref(false)
const error = ref(''), pending = ref(false)
const blocked = computed(() => props.locked || loading.value || saving.value || childBusy.value || pending.value)
const last = computed(() => run.value?.state.steps[run.value.state.steps.length - 1]?.decision)
const active = computed(() => run.value && !['COMPLETED', 'FAILED', 'CANCELLED', 'LIMIT_REACHED'].includes(run.value.state.status))
const invoiceAction = computed(() => last.value?.action === 'EXTRACT_INVOICE' && ['NEEDS_CONFIRMATION', 'WAITING_CHILD'].includes(run.value?.state.status ?? ''))
const canRetryRead = computed(() => run.value?.state.status === 'FAILED' && ['EXPENSE', 'INVOICE', 'POLICY', 'PRECHECK_RESULT'].includes(last.value?.action ?? ''))
function message(value?: string | null) {
  const messages: Record<string, string> = { UNAUTHENTICATED: '原登录已失效，自动办理已停止。', AGENT_INPUT_CHANGED: '单据或授权范围已变化，请重新核对当前费用。', AGENT_AUTHORIZATION_EXPIRED: '本次 30 分钟授权已到期。', AGENT_MODEL_DISABLED: '模型服务已停用。', MODEL_UNAVAILABLE: '模型服务暂时不可用。', MODEL_TIMEOUT: '模型执行超时，原调用结果尚未确认。', INVALID_MODEL_OUTPUT: '模型输出未通过白名单或来源校验。', AGENT_EXECUTION_FAILED: '本步骤未完成，请核对原执行记录。' }
  return value ? messages[value] ?? (/[\u4e00-\u9fff]/.test(value) ? value : '本步骤未完成，请核对原执行记录后重试。') : ''
}
let epoch = 0, controller: AbortController | null = null, timer: ReturnType<typeof setTimeout> | undefined
function stop() { epoch++; controller?.abort(); clearTimeout(timer) }
function syncPending() {
  const before = pending.value
  pending.value = writeRequests.pending().some(value => value.path.startsWith(agentPath(props.report.id, props.task.id)) || value.path.includes(`/${props.task.id}/reads/`))
  if (before && !pending.value && !saving.value) void load()
}
const unsubscribe = writeRequests.subscribe(syncPending)
/** 原运行由后台推进，刷新只读取历史及明确绑定的子任务。 */
async function load() {
  if (saving.value || childBusy.value || childDirty.value) { timer = setTimeout(() => void load(), 2000); return }
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true
  const timeout = setTimeout(() => request.abort(), 12_000)
  try {
    const [value, history] = await Promise.all([api.expenseAgent(props.report.id, props.task.id, request.signal), api.expenseHandlingReads(props.report.id, props.task.id, request.signal)])
    if (current !== epoch) return
    run.value = value; reads.value = history
    if (!value && !wallet.value.length) {
      const page = await api.invoices({ limit: 50 }, request.signal)
      if (current !== epoch) return
      wallet.value = page.items
    }
    if (invoiceAction.value && last.value?.referenceId && invoice.value?.id !== last.value.referenceId) {
      const item = await api.invoice(last.value.referenceId, request.signal)
      if (current !== epoch) return
      invoice.value = item
    }
    if (active.value) timer = setTimeout(() => void load(), 2000)
  } catch (cause) { if (current === epoch) { error.value = expenseError(cause); if ([401, 403, 404].includes((cause as { status?: number }).status ?? 0)) { run.value = null; invoice.value = null; wallet.value = []; preview.value = null; reads.value = [] } } }
  finally { clearTimeout(timeout); if (current === epoch) loading.value = false }
}
async function showPreview() {
  if (blocked.value || !props.task.current) return
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = true; error.value = ''; consent.value = false; preview.value = null
  const scope: AgentScope = { policyLineNos: [...lines.value], invoiceIds: [...invoices.value], precheckIds: props.task.steps.filter(s => s.tool === 'PRECHECK' && ['READY', 'BLOCKED', 'UNAVAILABLE'].includes(s.outcome) && s.applicationVersion === props.report.applicationVersion && s.financialVersion === props.report.financialVersion).slice(-1).map(s => s.referenceId), maxSteps: maxSteps.value }
  const timeout = setTimeout(() => request.abort(), 12_000)
  try { const value = await api.previewExpenseAgent(props.report.id, props.task.id, scope, request.signal); if (current === epoch) preview.value = value }
  catch (cause) { if (current === epoch) error.value = expenseError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) loading.value = false }
}
async function change(operation: () => Promise<unknown>) {
  if (blocked.value) return
  clearTimeout(timer); const current = epoch; saving.value = true; error.value = ''
  try { await operation(); if (current === epoch) { consent.value = false; preview.value = null; answer.value = ''; acknowledge.value = false; emit('changed') } }
  catch (cause) { if (current === epoch) error.value = expenseError(cause) }
  finally { if (current === epoch) { saving.value = false; syncPending(); if (!pending.value) await load() } }
}
function start() { const value = preview.value; if (value && consent.value) return change(() => api.startExpenseAgent(props.report.id, props.task.id, value)) }
function resume() { const value = run.value; if (value) return change(() => api.resumeExpenseAgent(props.report.id, props.task.id, value.state.version, answer.value, acknowledge.value)) }
function cancel() { const value = run.value; if (value) return change(() => api.cancelExpenseAgent(props.report.id, props.task.id, value.state.version)) }
function continueDraft() {
  if (blocked.value || !run.value || last.value?.action !== 'DRAFT') return
  const confirmed = run.value.state.steps.filter(step => step.decision?.action === 'EXTRACT_INVOICE' && step.outcome === 'CONFIRMED').map(step => step.observation).filter(Boolean)
  emit('draft', { taskId: props.task.id, brief: `${props.task.goal}\n${confirmed.length ? '本人确认的票据候选字段（不代表查验通过）：\n' + confirmed.join('\n') : ''}`.slice(0, 8000), ...(run.value.state.childId ? { runId: run.value.state.childId } : {}) })
}
watch(() => [props.scopeKey, props.task.id, props.report.applicationVersion, props.report.financialVersion], () => {
  stop(); run.value = null; invoice.value = null; wallet.value = []; preview.value = null; consent.value = false; answer.value = ''; acknowledge.value = false; error.value = ''
  lines.value = []; invoices.value = []; reads.value = []; saving.value = false; childBusy.value = false; childDirty.value = false; syncPending(); void load()
}, { immediate: true, flush: 'sync' })
watch([lines, invoices, maxSteps], () => { preview.value = null; consent.value = false }, { deep: true })
watch(() => saving.value || childBusy.value || childDirty.value, value => emit('busy', value), { flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="agent-panel" aria-label="受控自动办理">
    <header><h4>按目标自动办理</h4><button type="button" class="quiet" :disabled="loading || saving || childBusy || childDirty" @click="load">刷新执行记录</button></header>
    <p class="help">授权后，助手会选择查询工具、读取结果并继续判断。缺资料会提问，票据处理及费用修改会停下来请你确认。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="pending" role="status">原操作结果尚未确认，请先恢复页面中的原请求。</p>
    <template v-if="!run && task.current">
      <fieldset :disabled="blocked"><legend>本次允许读取和发送的范围</legend>
        <p>本单已保存的费用内容、办理目标，以及下方明确选择的查询结果会发送给模型。</p>
        <label v-for="line in report.content.lines" :key="line.lineNo" class="choice"><input v-model="lines" type="checkbox" :value="line.lineNo" />第 {{ line.lineNo }} 行：允许查询适用制度</label>
        <label v-for="item in wallet" :key="item.id" class="choice"><input v-model="invoices" type="checkbox" :value="item.id" />允许读取及安排整理票据：{{ item.original.filename }}</label>
        <label>最多执行步数<input v-model.number="maxSteps" type="number" min="1" max="12" /></label>
        <button type="button" class="secondary" :disabled="lines.length > 20 || invoices.length > 20 || maxSteps < 1 || maxSteps > 12" @click="showPreview">核对发送内容与目的地</button>
      </fieldset>
      <div v-if="preview" class="authorization"><strong>{{ preview.providerId }} · {{ preview.model }} · {{ preview.destination }}</strong>
        <p>授权最多 {{ preview.scope.maxSteps }} 步，有效 30 分钟。制度查询与返回的条款也在发送范围内；原件外发仍需单独确认。</p>
        <details><summary>查看本次完整费用、票据范围与预检资料</summary><pre>{{ JSON.stringify(preview.sendableData, null, 2) }}</pre></details>
        <label class="choice"><input v-model="consent" type="checkbox" :disabled="blocked" />我已核对，授权上述范围内的自动查询、结果发送与下一步判断</label>
        <button type="button" class="primary" :disabled="blocked || !consent" @click="start">确认授权并开始</button>
      </div>
    </template>
    <template v-if="run">
      <p role="status"><strong>{{ agentStatuses[run.state.status] }}</strong> · {{ run.state.steps.length }} / {{ run.scope.maxSteps }} 步</p>
      <p class="preserved">{{ message(run.state.message) }}</p>
      <ol><li v-for="step in run.state.steps" :key="step.id"><strong>{{ step.decision ? agentActions[step.decision.action] : '判断下一步' }}</strong><p class="preserved">{{ step.decision?.message }}</p><details><summary>本步骤的依据与结果</summary><p>原步骤 {{ step.id }}</p><pre>{{ step.observation || '尚未形成工具结果' }}</pre></details></li></ol>
      <form v-if="run.state.status === 'NEEDS_INFORMATION' || run.state.status === 'INTERRUPTED'" @submit.prevent="resume">
        <label>补充资料<textarea v-model="answer" maxlength="2000" :disabled="blocked" rows="3" /></label>
        <label v-if="run.state.status === 'INTERRUPTED'" class="choice"><input v-model="acknowledge" type="checkbox" :disabled="blocked" />我已知晓原模型调用结果未知，同意保留原记录并发起下一次决策</label>
        <button type="submit" class="secondary" :disabled="blocked || (run.state.status === 'INTERRUPTED' ? !acknowledge : !answer.trim())">继续原办理</button>
      </form>
      <button v-if="canRetryRead" type="button" class="secondary" :disabled="blocked || !task.current" @click="resume">按原步骤重试查询并继续</button>
      <InvoiceExtraction v-if="invoiceAction && invoice" :item="invoice" :scope-key="scopeKey" :refresh-version="run.state.version" :locked="locked || saving || pending"
        :handling="{ reportId: report.id, taskId: task.id }" :focus-run-id="run.state.childId ?? undefined" :allow-generate="run.state.status === 'NEEDS_CONFIRMATION'" @busy="childBusy = $event" @dirty="childDirty = $event" />
      <button v-if="last?.action === 'DRAFT' && ['NEEDS_CONFIRMATION', 'WAITING_CHILD'].includes(run.state.status)" type="button" class="secondary" :disabled="blocked" @click="continueDraft">{{ run.state.childId ? '查看本次原费用草稿任务' : '继续整理费用草稿并核对发送范围' }}</button>
      <button v-if="active" type="button" class="quiet" :disabled="blocked" @click="cancel">停止后续自动办理</button>
    </template>
    <details v-if="reads.length"><summary>查询执行过程（含失败及中断）</summary><article v-for="read in reads" :key="read.id"><p>{{ agentActions[read.input.tool] }} · {{ ({ RUNNING: '执行中', PREPARED: '已读取，待登记', RECORDED: '已登记', FAILED: '读取失败' })[read.status] }}</p><p v-if="read.failureCode">{{ read.failureCode }}</p><small>原步骤 {{ read.id }} · 输入摘要 {{ read.inputDigest }}</small><button v-if="read.status !== 'RECORDED'" type="button" class="quiet" :disabled="blocked || !task.current" @click="change(() => api.resumeHandlingRead(report.id, task.id, read.id, read.version))">按原输入恢复本步骤</button></article></details>
  </section>
</template>

<style scoped>
.agent-panel{border-top:1px solid var(--line);margin:18px 0;padding-top:18px}.agent-panel header{display:flex;align-items:center;justify-content:space-between;gap:12px}.agent-panel h4{margin:0}.help{color:var(--muted)}fieldset{border:1px solid var(--line);border-radius:8px;padding:14px}label{display:grid;gap:6px;margin:12px 0}.choice{display:flex;align-items:start;gap:8px}.choice input{margin-top:5px;width:auto;flex:0 0 auto}textarea,input[type=number]{font:inherit;padding:8px;border:1px solid var(--line);border-radius:5px}input[type=number]{max-width:110px}.authorization{padding:16px;margin-top:14px;background:#edf9f5;border:1px solid var(--line);border-radius:8px}.preserved,pre{white-space:pre-wrap;overflow-wrap:anywhere}pre{font-size:11px;max-height:360px;overflow:auto}.agent-panel li,.agent-panel article{margin:12px 0;padding:10px 0;border-bottom:1px solid var(--line)}small{display:block;overflow-wrap:anywhere}.quiet{margin:8px}summary{cursor:pointer}.agent-panel [role=alert]{color:var(--red)}
</style>
