<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { expenseError, type ExpenseDetail } from '../expenses'
import { invoiceError, type InvoiceItem } from '../invoiceWallet'
import type { HandlingTask } from '../expenseHandling'
import { agentActions, agentStatuses, agentPath, type AgentPreview, type AgentScope, type ExpenseAgentView, type HandlingRead } from '../expenseAgent'
import InvoiceExtraction from './InvoiceExtraction.vue'

const props = defineProps<{ report: ExpenseDetail; task: HandlingTask; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ busy: [boolean]; draft: [{ taskId: string; brief: string; runId?: string }]; changed: [] }>()
const run = ref<ExpenseAgentView | null>(null), preview = ref<AgentPreview | null>(null), reads = ref<HandlingRead[]>([])
const wallet = ref<InvoiceItem[]>([]), invoice = ref<InvoiceItem | null>(null)
const walletNext = ref<string | null>(null), walletLoaded = ref(false), walletLoading = ref(false), walletError = ref('')
const lines = ref<number[]>([]), invoices = ref<string[]>([]), maxSteps = ref(8), consent = ref(false)
const answer = ref(''), acknowledge = ref(false), loading = ref(false), refreshing = ref(false), loaded = ref(false), saving = ref(false), childBusy = ref(false), childDirty = ref(false)
const error = ref(''), actionError = ref(''), pending = ref(false)
const blocked = computed(() => props.locked || loading.value || walletLoading.value || saving.value || childBusy.value || childDirty.value || pending.value)
const last = computed(() => run.value?.state.steps[run.value.state.steps.length - 1]?.decision)
const active = computed(() => run.value && !['COMPLETED', 'FAILED', 'CANCELLED', 'LIMIT_REACHED'].includes(run.value.state.status))
const invoiceAction = computed(() => last.value?.action === 'EXTRACT_INVOICE' && ['NEEDS_CONFIRMATION', 'WAITING_CHILD'].includes(run.value?.state.status ?? ''))
const canRetryRead = computed(() => run.value?.state.status === 'FAILED' && ['EXPENSE', 'INVOICE', 'POLICY', 'PRECHECK_RESULT'].includes(last.value?.action ?? ''))
const needsInput = computed(() => ['NEEDS_INFORMATION', 'INTERRUPTED'].includes(run.value?.state.status ?? ''))
const needsDraft = computed(() => last.value?.action === 'DRAFT' && ['NEEDS_CONFIRMATION', 'WAITING_CHILD'].includes(run.value?.state.status ?? ''))
const statusTone = computed(() => {
  const status = run.value?.state.status
  return status === 'COMPLETED' ? 'complete' : ['NEEDS_INFORMATION', 'NEEDS_CONFIRMATION', 'INTERRUPTED', 'FAILED', 'LIMIT_REACHED'].includes(status ?? '') ? 'attention' : 'neutral'
})
const nextAction = computed(() => {
  const status = run.value?.state.status
  if (!status) return props.task.current ? '选择本次授权范围，再核对发送内容。' : '当前记录对应的单据版本已变化，请先在上方读取当前费用。'
  if (status === 'NEEDS_INFORMATION') return '补充下方资料后，助手会继续原办理。'
  if (status === 'INTERRUPTED') return '核对原调用记录，并明确确认是否发起下一次决策。'
  if (status === 'NEEDS_CONFIRMATION') return last.value?.action === 'DRAFT' ? '打开费用草稿，核对行程、目录和发送范围。' : '核对票据原件及发送范围，再确认下一项操作。'
  if (status === 'WAITING_CHILD') return '查看下方绑定的原任务，等待结果并由本人核对。'
  if (canRetryRead.value) return '可按原步骤重试查询；不会重新创建本次办理。'
  if (status === 'COMPLETED') return '本次自动办理已结束。费用仍需在原表单核对、保存和预检。'
  if (status === 'LIMIT_REACHED') return '已达到本次授权步数，后续操作请从原费用表单继续。'
  if (status === 'FAILED' || status === 'CANCELLED') return '核对下方执行记录，再从原业务任务继续处理。'
  return '助手正在授权范围内处理，执行记录会自动刷新。'
})
const stepOutcomes: Record<string, string> = { MODEL_RUNNING: '模型调用已登记', TOOL_READY: '已安排查询', NEEDS_INFORMATION: '已请求补充', NEEDS_CONFIRMATION: '已请求确认', COMPLETED: '本次判断已结束', READ: '已读取结果', CONFIRMED: '本人已确认' }
function formatTime(value: string) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) }
function message(value?: string | null) {
  const messages: Record<string, string> = { UNAUTHENTICATED: '原登录已失效，自动办理已停止。', AGENT_INPUT_CHANGED: '单据或授权范围已变化，请重新核对当前费用。', AGENT_AUTHORIZATION_EXPIRED: '本次 30 分钟授权已到期。', AGENT_MODEL_DISABLED: '模型服务已停用。', MODEL_UNAVAILABLE: '模型服务暂时不可用。', MODEL_TIMEOUT: '模型执行超时，原调用结果尚未确认。', INVALID_MODEL_OUTPUT: '模型输出未通过白名单或来源校验。', AGENT_EXECUTION_FAILED: '本步骤未完成，请核对原执行记录。' }
  return value ? messages[value] ?? (/[\u4e00-\u9fff]/.test(value) ? value : '本步骤未完成，请核对原执行记录后重试。') : ''
}
let epoch = 0, controller: AbortController | null = null, timer: ReturnType<typeof setTimeout> | undefined
function stop() { epoch++; controller?.abort(); controller = null; clearTimeout(timer); loading.value = false; refreshing.value = false; walletLoading.value = false }
function syncPending() {
  const before = pending.value
  pending.value = writeRequests.pending().some(value => value.path.startsWith(agentPath(props.report.id, props.task.id)) || value.path.includes(`/${props.task.id}/reads/`))
  if (before && !pending.value && !saving.value) void load()
}
const unsubscribe = writeRequests.subscribe(syncPending)
/** 原运行由后台推进，刷新只读取历史及明确绑定的子任务。 */
async function load() {
  if (saving.value || childBusy.value || childDirty.value) { clearTimeout(timer); timer = setTimeout(() => void load(), 2000); return }
  stop(); const current = epoch, request = new AbortController(); controller = request; loading.value = !loaded.value; refreshing.value = loaded.value
  const timeout = setTimeout(() => request.abort(), 12_000)
  try {
    const [value, history] = await Promise.all([api.expenseAgent(props.report.id, props.task.id, request.signal), api.expenseHandlingReads(props.report.id, props.task.id, request.signal)])
    if (current !== epoch) return
    run.value = value; reads.value = history; error.value = ''
    if (!value && !walletLoaded.value) {
      try {
        const page = await api.invoices({ limit: 50 }, request.signal)
        if (current !== epoch) return
        wallet.value = page.items; walletNext.value = page.nextBeforeId ?? null; walletLoaded.value = true; walletError.value = ''
      } catch (cause) { if (current !== epoch) return; walletError.value = invoiceError(cause) }
    }
    if (invoiceAction.value && last.value?.referenceId && invoice.value?.id !== last.value.referenceId) {
      const item = await api.invoice(last.value.referenceId, request.signal)
      if (current !== epoch) return
      invoice.value = item
    }
    loaded.value = true
    if (active.value) timer = setTimeout(() => void load(), 2000)
  } catch (cause) { if (current === epoch) { error.value = expenseError(cause); if ([401, 403, 404].includes((cause as { status?: number }).status ?? 0)) { run.value = null; invoice.value = null; wallet.value = []; walletNext.value = null; walletLoaded.value = false; preview.value = null; reads.value = []; loaded.value = false } } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; refreshing.value = false; controller = null } }
}
/** 分页只扩展可选择的原票据，保留已选范围；失败时沿用原游标重试。 */
async function loadMoreInvoices() {
  if (blocked.value || run.value || walletLoaded.value && !walletNext.value) return
  stop(); const current = epoch, request = new AbortController(); controller = request; walletLoading.value = true; walletError.value = ''
  const timeout = setTimeout(() => request.abort(), 12_000)
  try {
    const page = await api.invoices({ limit: 50, ...(walletNext.value ? { beforeId: walletNext.value } : {}) }, request.signal)
    if (current !== epoch) return
    const known = new Set(wallet.value.map(item => item.id))
    wallet.value = [...wallet.value, ...page.items.filter(item => !known.has(item.id))]; walletNext.value = page.nextBeforeId ?? null; walletLoaded.value = true
  } catch (cause) { if (current === epoch) walletError.value = invoiceError(cause) }
  finally { clearTimeout(timeout); if (current === epoch) { walletLoading.value = false; controller = null } }
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
  // 人工动作开始时取消后台读取，迟到的旧版本不得覆盖操作后的状态。
  stop(); const current = epoch; saving.value = true; actionError.value = ''; error.value = ''
  try { await operation(); if (current === epoch) { consent.value = false; preview.value = null; answer.value = ''; acknowledge.value = false; emit('changed') } }
  catch (cause) { if (current === epoch) actionError.value = expenseError(cause) }
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
  stop(); loaded.value = false; run.value = null; invoice.value = null; wallet.value = []; walletNext.value = null; walletLoaded.value = false; walletError.value = ''; preview.value = null; consent.value = false; answer.value = ''; acknowledge.value = false; error.value = ''; actionError.value = ''
  lines.value = []; invoices.value = []; reads.value = []; saving.value = false; childBusy.value = false; childDirty.value = false; syncPending(); void load()
}, { immediate: true, flush: 'sync' })
watch([lines, invoices, maxSteps], () => { preview.value = null; consent.value = false }, { deep: true })
watch(() => childBusy.value || childDirty.value, value => {
  if (value) stop()
  else if (active.value && !saving.value) { clearTimeout(timer); timer = setTimeout(() => void load(), 2000) }
}, { flush: 'sync' })
watch(() => saving.value || childBusy.value || childDirty.value, value => emit('busy', value), { flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="agent-panel" aria-label="受控自动办理">
    <header class="agent-header">
      <div><span class="eyebrow">费用办理助手</span><h4>按目标自动办理</h4></div>
      <button type="button" class="quiet" :disabled="loading || refreshing || walletLoading || saving || childBusy || childDirty" @click="load">{{ refreshing ? '正在刷新…' : '刷新执行记录' }}</button>
    </header>
    <p class="help">助手会在授权范围内查询和判断；补充资料、票据处理与费用修改由你确认。</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
    <p v-if="pending" class="notice" role="status">原操作结果尚未确认，请先恢复页面中的原请求。</p>
    <p v-if="loading && !loaded" class="notice" role="status">正在读取原办理与授权范围…</p>

    <div v-if="loaded || run" class="agent-overview" :class="statusTone">
      <div class="goal"><span class="meta-label">本次目标</span><strong>{{ task.goal }}</strong></div>
      <div class="current-status">
        <span class="status-badge" role="status">{{ run ? agentStatuses[run.state.status] : task.current ? '等待授权' : '单据版本已变化' }}</span>
        <span v-if="run" class="step-count">已记录 {{ run.state.steps.length }} 步 · 授权上限 {{ run.scope.maxSteps }} 步</span>
      </div>
      <p class="next-action">{{ nextAction }}</p>
      <p v-if="run?.state.message && run.state.status !== 'NEEDS_INFORMATION'" class="preserved">{{ message(run.state.message) }}</p>
    </div>

    <template v-if="!run && task.current && loaded">
      <fieldset class="scope-fieldset" :disabled="blocked"><legend>本次允许读取和发送的范围</legend>
        <p class="scope-description">本单已保存的费用内容、办理目标，以及下方明确选择的查询结果会发送给模型。</p>
        <div class="scope-groups">
          <section class="scope-group" aria-label="费用制度授权">
            <div class="group-heading"><h5>费用制度</h5><span>已选 {{ lines.length }} / 20 行</span></div>
            <p class="help">仅查询所选费用行适用的制度。</p>
            <div class="choice-list">
              <label v-for="line in report.content.lines" :key="line.lineNo" class="choice"><input v-model="lines" type="checkbox" :value="line.lineNo" /><span>第 {{ line.lineNo }} 行<span class="choice-note">{{ line.description || '允许查询适用制度' }}</span></span></label>
              <p v-if="!report.content.lines.length" class="empty-hint">尚无已保存的费用行。</p>
            </div>
          </section>
          <section class="scope-group" aria-label="票据读取授权">
            <div class="group-heading"><h5>票据资料</h5><span>已选 {{ invoices.length }} / 20 张</span></div>
            <p class="help">允许读取及安排整理；原件外发另行确认。</p>
            <div class="choice-list">
              <label v-for="item in wallet" :key="item.id" class="choice"><input v-model="invoices" type="checkbox" :value="item.id" /><span>{{ item.original.filename }}</span></label>
              <p v-if="walletLoaded && !wallet.length" class="empty-hint">票据夹暂无可选择的票据。</p>
            </div>
            <p v-if="walletError" class="error" role="alert">{{ walletError }}</p>
            <button v-if="walletNext || !walletLoaded" type="button" class="quiet wallet-more" :disabled="walletLoading" @click="loadMoreInvoices">{{ walletLoading ? '正在读取票据…' : walletError ? '重试读取票据' : '加载更多票据' }}</button>
            <small v-if="wallet.length" class="help">已加载 {{ wallet.length }} 张票据{{ walletNext ? '，可继续加载' : '' }}</small>
          </section>
        </div>
        <div class="authorization-actions">
          <label class="step-limit">最多执行步数<input v-model.number="maxSteps" type="number" min="1" max="12" /></label>
          <button type="button" class="secondary" :disabled="lines.length > 20 || invoices.length > 20 || maxSteps < 1 || maxSteps > 12" @click="showPreview">核对发送内容与目的地</button>
        </div>
      </fieldset>
      <div v-if="preview" class="authorization">
        <h5>确认本次授权</h5>
        <dl class="destination"><div><dt>服务商 / 模型</dt><dd>{{ preview.providerId }} · {{ preview.model }}</dd></div><div><dt>发送目的地</dt><dd>{{ preview.destination }}</dd></div></dl>
        <p>授权最多 {{ preview.scope.maxSteps }} 步，有效 30 分钟。制度查询与返回的条款也在发送范围内；原件外发仍需单独确认。</p>
        <details><summary>查看本次完整费用、票据范围与预检资料</summary><pre>{{ JSON.stringify(preview.sendableData, null, 2) }}</pre></details>
        <label class="choice"><input v-model="consent" type="checkbox" :disabled="blocked" /><span>我已核对，授权上述范围内的自动查询、结果发送与下一步判断</span></label>
        <button type="button" class="primary" :disabled="blocked || !consent" @click="start">确认授权并开始</button>
      </div>
    </template>

    <template v-if="run">
      <div v-if="needsInput || canRetryRead || invoiceAction || needsDraft" class="human-action">
        <div class="group-heading"><h5>需要你处理</h5><span>确认后继续原办理</span></div>
        <p v-if="run.state.status === 'NEEDS_INFORMATION' && last?.message" class="question preserved">{{ last.message }}</p>
        <form v-if="needsInput" @submit.prevent="resume">
          <label>补充资料<textarea v-model="answer" maxlength="2000" :disabled="blocked" rows="4" placeholder="填写助手需要的事实或说明" /></label>
          <div class="answer-meta"><span>{{ answer.length }} / 2000</span><span v-if="refreshing">后台刷新不影响填写</span></div>
          <label v-if="run.state.status === 'INTERRUPTED'" class="choice"><input v-model="acknowledge" type="checkbox" :disabled="blocked" /><span>我已知晓原模型调用结果未知，同意保留原记录并发起下一次决策</span></label>
          <button type="submit" class="primary" :disabled="blocked || (run.state.status === 'INTERRUPTED' ? !acknowledge : !answer.trim())">{{ saving ? '正在提交…' : '继续原办理' }}</button>
        </form>
        <button v-if="canRetryRead" type="button" class="secondary" :disabled="blocked || !task.current" @click="resume">按原步骤重试查询并继续</button>
        <InvoiceExtraction v-if="invoiceAction && invoice" :item="invoice" :scope-key="scopeKey" :refresh-version="run.state.version" :locked="locked || saving || pending"
          :handling="{ reportId: report.id, taskId: task.id }" :focus-run-id="run.state.childId ?? undefined" :allow-generate="run.state.status === 'NEEDS_CONFIRMATION'" @busy="childBusy = $event" @dirty="childDirty = $event" />
        <button v-if="needsDraft" type="button" class="secondary" :disabled="blocked" @click="continueDraft">{{ run.state.childId ? '查看本次原费用草稿任务' : '继续整理费用草稿并核对发送范围' }}</button>
      </div>
      <div class="run-meta"><span>授权有效至 {{ formatTime(run.deadline) }}</span><span>更新于 {{ formatTime(run.state.updatedAt) }}</span><button v-if="active" type="button" class="quiet" :disabled="blocked" @click="cancel">停止后续自动办理</button></div>
      <section class="execution-history" aria-label="自动办理执行历史">
        <div class="group-heading"><h5>执行记录</h5><span>{{ run.state.steps.length }} 个已记录步骤</span></div>
        <p v-if="!run.state.steps.length" class="empty-hint">尚未形成执行步骤。</p>
        <ol v-else class="timeline"><li v-for="(step, index) in run.state.steps" :key="step.id">
          <span class="timeline-marker">{{ index + 1 }}</span>
          <div class="timeline-content"><div class="step-heading"><strong>{{ step.decision ? agentActions[step.decision.action] : '判断下一步' }}</strong><span class="outcome">{{ stepOutcomes[step.outcome] || step.outcome }}</span><time :datetime="step.createdAt">{{ formatTime(step.createdAt) }}</time></div>
            <p v-if="step.decision?.message" class="preserved">{{ step.decision.message }}</p>
            <details><summary>查看依据与实际结果</summary><small>原步骤 {{ step.id }}</small><pre>{{ step.observation || '尚未形成工具结果' }}</pre></details>
          </div>
        </li></ol>
      </section>
    </template>
    <details v-if="reads.length" class="read-history"><summary>查询执行过程 · {{ reads.length }} 条（含失败及中断）</summary><article v-for="read in reads" :key="read.id"><div class="step-heading"><strong>{{ agentActions[read.input.tool] }}</strong><span class="outcome">{{ ({ RUNNING: '执行中', PREPARED: '已读取，待登记', RECORDED: '已登记', FAILED: '读取失败' })[read.status] }}</span></div><p v-if="read.failureCode" class="error">{{ message(read.failureCode) }}</p><small>原步骤 {{ read.id }} · 输入摘要 {{ read.inputDigest }}</small><button v-if="read.status !== 'RECORDED'" type="button" class="quiet" :disabled="blocked || !task.current" @click="change(() => api.resumeHandlingRead(report.id, task.id, read.id, read.version))">按原输入恢复本步骤</button></article></details>
  </section>
</template>

<style scoped>
.agent-panel { margin: 22px 0; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }
.agent-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.agent-panel h4 { margin: 4px 0 0; font-size: 18px; letter-spacing: -.3px; }
.agent-panel h5 { margin: 0; font-size: 14px; }
.eyebrow, .meta-label { color: var(--muted); font-size: 12px; font-weight: 600; }
.help, .empty-hint { color: var(--muted); font-size: 13px; line-height: 1.7; }
.agent-panel > .help { margin: 10px 0 18px; }
.agent-overview { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: start; gap: 12px 20px; padding: 20px; background: #f0f7f6; border: 1px solid #cfe3df; border-left: 3px solid var(--deep); border-radius: 10px; margin-bottom: 18px; }
.agent-overview.attention { background: #fffaf0; border-color: #eadbb7; border-left-color: #9e6610; }
.goal { display: grid; gap: 7px; min-width: 0; }
.goal strong { line-height: 1.65; overflow-wrap: anywhere; font-size: 15px; }
.current-status { display: grid; justify-items: end; gap: 8px; }
.status-badge { border-radius: 6px; padding: 5px 9px; background: #dceee9; color: #165f52; font-size: 12px; font-weight: 600; line-height: 1.5; }
.attention .status-badge { background: #f7ebcf; color: #805311; }
.step-count, .run-meta, .answer-meta { color: var(--muted); font-size: 12px; }
.next-action { grid-column: 1 / -1; margin: 0; font-size: 14px; line-height: 1.7; }
.agent-overview > .preserved { grid-column: 1 / -1; margin: 0; line-height: 1.7; }
.notice { padding: 12px 14px; border: 1px solid var(--line); border-radius: 7px; line-height: 1.7; background: #fff; }
.error { color: var(--red); }
.scope-fieldset { min-width: 0; border: 1px solid var(--line); border-radius: 10px; padding: 18px; margin: 0; }
.scope-fieldset legend { padding: 0 7px; font-size: 14px; font-weight: 600; }
.scope-description { margin: 0 0 18px; color: var(--muted); font-size: 13px; line-height: 1.7; }
.scope-groups { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; }
.scope-group { min-width: 0; background: #f7f9fa; padding: 14px; border: 1px solid var(--line); border-radius: 8px; }
.group-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.group-heading > span { color: var(--muted); font-size: 12px; }
.scope-group > .help { margin: 8px 0; }
.choice-list { max-height: 252px; overflow: auto; overscroll-behavior: contain; }
.agent-panel label { display: grid; gap: 7px; margin: 14px 0; font-size: 13px; }
.agent-panel .choice { display: flex; align-items: start; gap: 9px; line-height: 1.65; }
.choice > span { min-width: 0; overflow-wrap: anywhere; }
.choice input { margin: 4px 0 0; width: 16px; height: 16px; flex: 0 0 auto; accent-color: var(--deep); }
.choice-note { display: block; color: var(--muted); font-size: 12px; }
.agent-panel textarea, .agent-panel input[type=number] { font: inherit; padding: 10px 12px; border: 1px solid var(--line); border-radius: 6px; background: #fff; }
.agent-panel textarea { width: 100%; box-sizing: border-box; resize: vertical; line-height: 1.7; }
.agent-panel input[type=number] { max-width: 90px; }
.authorization-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 14px; border-top: 1px solid var(--line); margin-top: 18px; padding-top: 14px; }
.agent-panel .step-limit { display: flex; align-items: center; margin: 0; }
.wallet-more { margin: 8px 0; }
.authorization { padding: 20px; margin-top: 18px; background: #f0f7f6; border: 1px solid #cfe3df; border-radius: 10px; font-size: 13px; line-height: 1.7; }
.destination { display: flex; flex-wrap: wrap; gap: 12px 28px; }
.destination dt { color: var(--muted); font-size: 12px; }
.destination dd { margin: 3px 0 0; color: var(--ink); font-weight: 600; overflow-wrap: anywhere; }
.human-action { margin: 18px 0; padding: 20px; background: #fff; border: 1px solid #cfe3df; border-radius: 10px; box-shadow: 0 3px 12px #183a3610; }
.human-action .question { padding: 12px 14px; background: #f7f9fa; border-left: 2px solid var(--line); font-size: 14px; line-height: 1.7; }
.human-action > button { margin-top: 16px; }
.answer-meta { display: flex; justify-content: space-between; gap: 12px; margin: -7px 0 14px; }
.run-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 20px; line-height: 1.7; margin: 12px 0 20px; }
.run-meta button { margin-left: auto; }
.execution-history { border-top: 1px solid var(--line); padding-top: 18px; }
.timeline { list-style: none; padding: 0; margin: 20px 0 0; }
.timeline li { position: relative; display: flex; gap: 14px; padding-bottom: 22px; }
.timeline li:not(:last-child)::after { content: ''; position: absolute; top: 28px; bottom: 0; left: 13px; width: 1px; background: var(--line); }
.timeline-marker { display: grid; place-items: center; width: 28px; height: 28px; flex: 0 0 auto; border-radius: 50%; background: #eaf0f3; color: var(--deep); font-size: 12px; font-weight: 600; }
.timeline-content { flex: 1; min-width: 0; padding-top: 2px; }
.step-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; font-size: 13px; }
.step-heading time { margin-left: auto; color: var(--muted); font-size: 12px; }
.outcome { padding: 2px 7px; background: #edf2f5; color: #516471; border-radius: 4px; font-size: 12px; line-height: 1.5; }
.timeline-content > p { margin: 9px 0; color: var(--muted); font-size: 13px; line-height: 1.7; }
.agent-panel details { font-size: 13px; }
.agent-panel summary { cursor: pointer; line-height: 1.7; color: var(--deep); }
.preserved, .agent-panel pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.agent-panel pre { font-size: 12px; line-height: 1.65; max-height: 360px; overflow: auto; background: #f7f9fa; padding: 12px; border-radius: 6px; }
.agent-panel small { display: block; overflow-wrap: anywhere; font-size: 12px; }
.timeline-content details small { margin-top: 10px; color: var(--muted); }
.read-history { border-top: 1px solid var(--line); padding-top: 16px; }
.read-history article { padding: 16px 0; border-bottom: 1px solid var(--line); }
.read-history article small { margin: 10px 0; color: var(--muted); }
</style>
