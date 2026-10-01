<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, writeRequests } from '../api'
import { moneyLabel } from '../expenses'
import { adjustmentPreparationLabels } from '../expenseResourceAdjustment'
import { partialSides, partialSideLabels, partialLabels, partialOperationLabels, partialIntentLabels, partialIssue, partialError, partialFactLabel, partialSourceLabel, validatePartial,
  partialOperation, partialPreparation, partialCanCreate, partialCanPrepare, partialNeedsAcknowledgement, partialPreview,
  partialCreateInput, partialOriginalInput, partialSourceInput, partialPrepareInput, partialAuthorizeInput, partialActionInput, partialRetireInput, partialDisputeInput, validatePartialReceipt,
  type PartialView, type PartialIntent, type PartialSide, type PartialLineInput, type PartialBudgetFact, type PartialAccrualFact } from '../expensePartialAdjustment'
const props = defineProps<{ applicationId: string; reportId: string; roundNo: number; applicationVersion: number; financialVersion: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const view = ref<PartialView | null>(null), loading = ref(false), saving = ref(false), pending = ref<PartialIntent | null>(null), selected = ref(''), side = ref<PartialSide>('BUDGET')
const lines = ref<PartialLineInput[]>([]), returnIds = ref<string[]>([]), accountingDate = ref(''), reference = ref(''), comment = ref(''), acknowledged = ref(false)
const error = ref(''), notice = ref(''), requiresRefresh = ref(false), unconfirmed = ref(false), form = ref<HTMLFormElement | null>(null)
let epoch = 0, controller: AbortController | null = null
const blocked = computed(() => !!props.locked || loading.value || saving.value || requiresRefresh.value || unconfirmed.value)
const selectedEntry = computed(() => view.value?.adjustments.find(entry => entry.id === selected.value))
const selectedOperation = computed(() => selectedEntry.value ? partialOperation(selectedEntry.value, side.value) : null)
const selectedPreparation = computed(() => selectedEntry.value ? partialPreparation(selectedEntry.value, side.value) : null)
const preview = computed(() => { if (!view.value || pending.value !== 'CREATE') return null; try { return { value: partialPreview(view.value, lines.value, returnIds.value), error: '' } } catch (cause) { return { value: null, error: partialError(cause) } } })
const needsReference = computed(() => pending.value && ['CREATE', 'PREPARE', 'RETIRE', 'DISPUTE'].includes(pending.value))
const needsAck = computed(() => pending.value && partialNeedsAcknowledgement(pending.value))
function stop() { epoch++; controller?.abort(); controller = null }
function cancel() { pending.value = null; selected.value = ''; side.value = 'BUDGET'; lines.value = []; returnIds.value = []; accountingDate.value = ''; reference.value = ''; comment.value = ''; acknowledged.value = false }
function clear() { view.value = null; cancel() }
function syncPending() {
  const path = `/expense-reports/${encodeURIComponent(props.reportId)}/partial-adjustments`
  const current = writeRequests.pending().some(entry => entry.path === path || entry.path.startsWith(path + '/'))
  if (unconfirmed.value && !current) requiresRefresh.value = true
  unconfirmed.value = current
}
const unsubscribe = writeRequests.subscribe(syncPending)
function signature(value: PartialView) { return JSON.stringify([value.settlementVersion, value.returnsVersion, value.previousId, value.previousVersion, value.adjustments.map(entry => [entry.id, entry.version, entry.budgetPreparation?.version, entry.accrualPreparation?.version])]) }
/** 初始读取没有财务副作用，身份或版本变化立即清除旧表单和迟到响应。 */
async function load() {
  if (saving.value || loading.value || !props.scopeKey) return
  const previous = view.value; stop(); const current = epoch, request = new AbortController(); controller = request
  const binding = { reportId: props.reportId, applicationId: props.applicationId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, businessVersion: props.financialVersion }
  loading.value = true; clear(); error.value = ''; let changed = false
  const timeout = setTimeout(() => { if (current === epoch) { stop(); loading.value = false; requiresRefresh.value = true; error.value = '部分调整读取超时，请刷新核对。' } }, 12_000)
  try {
    const result = await api.expensePartialAdjustments(binding.reportId, binding.roundNo, request.signal)
    if (current !== epoch) return
    view.value = validatePartial(result, binding); requiresRefresh.value = false; syncPending(); changed = !!previous && signature(previous) !== signature(view.value)
  } catch (cause) { if (current === epoch) { clear(); error.value = partialError(cause); requiresRefresh.value = true } }
  finally { clearTimeout(timeout); if (current === epoch) { loading.value = false; controller = null; if (changed) emit('changed') } }
}
function prepare(intent: PartialIntent, id = '', selectedSide: PartialSide = 'BUDGET') {
  const value = view.value; if (!value?.finance || blocked.value) return
  const entry = value.adjustments.find(item => item.id === id)
  const allowed = intent === 'CREATE' || intent === 'ORIGINAL_QUERY' ? partialCanCreate(value)
    : !entry ? false : intent === 'PREPARE' ? partialCanPrepare(value, entry, selectedSide)
      : intent === 'AUTHORIZE' ? partialPreparation(entry, selectedSide)?.canAuthorize
        : intent === 'DISPUTE' ? partialOperation(entry, selectedSide)?.canResolve
          : intent === 'SOURCE_QUERY' ? entry.canQueryOriginals : intent === 'RETIRE' ? entry.canRetire : entry.availableActions.includes(intent)
  if (!allowed) return
  cancel(); pending.value = intent; selected.value = id; side.value = selectedSide; error.value = ''; notice.value = ''
  if (intent === 'CREATE') lines.value = value.remaining.lines.map(line => ({ lineNo: line.lineNo, remainingGross: line.gross.value, remainingTax: line.tax.value }))
  const current = epoch; void nextTick(() => { if (current === epoch) form.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input:not([type=checkbox]),textarea')?.focus() })
}
/** 每次只办理一个已展示意图，未知响应继续使用原请求体与幂等键恢复。 */
async function execute() {
  const value = view.value, intent = pending.value
  if (!value || !intent || blocked.value || partialNeedsAcknowledgement(intent) && !acknowledged.value) return
  let submit: () => Promise<void>
  try {
    if (intent === 'CREATE') { const input = partialCreateInput(value, lines.value, returnIds.value, reference.value, comment.value); submit = async () => validatePartialReceipt(await api.createExpensePartialAdjustment(value.reportId, input), value, intent, input) }
    else if (intent === 'ORIGINAL_QUERY') { const input = partialOriginalInput(value, comment.value); submit = async () => validatePartialReceipt(await api.queryExpensePartialOriginals(value.reportId, input), value, intent, input) }
    else if (intent === 'SOURCE_QUERY') { const input = partialSourceInput(value, selected.value, comment.value); submit = async () => validatePartialReceipt(await api.queryExpensePartialSources(value.reportId, input), value, intent, input) }
    else if (intent === 'PREPARE') { const input = partialPrepareInput(value, selected.value, side.value, accountingDate.value, reference.value, comment.value); submit = async () => validatePartialReceipt(await api.prepareExpensePartialAdjustment(value.reportId, input), value, intent, input) }
    else if (intent === 'AUTHORIZE') { const input = partialAuthorizeInput(value, selected.value, side.value, comment.value); submit = async () => validatePartialReceipt(await api.authorizeExpensePartialAdjustment(value.reportId, input), value, intent, input) }
    else if (intent === 'RETIRE') { const input = partialRetireInput(value, selected.value, reference.value, comment.value); submit = async () => validatePartialReceipt(await api.retireExpensePartialAdjustment(value.reportId, input), value, intent, input) }
    else if (intent === 'DISPUTE') { const input = partialDisputeInput(value, selected.value, side.value, reference.value, comment.value); submit = async () => validatePartialReceipt(await api.resolveExpensePartialDispute(value.reportId, input), value, intent, input) }
    else { const input = partialActionInput(value, selected.value, intent, comment.value); submit = async () => validatePartialReceipt(await api.actExpensePartialAdjustment(value.reportId, input), value, intent, input) }
  } catch (cause) { error.value = partialError(cause); return }
  const current = epoch; saving.value = true; error.value = ''; emit('busy', true)
  try {
    await submit(); if (current !== epoch) return
    cancel(); saving.value = false; emit('busy', false)
    notice.value = intent === 'CREATE' ? '调整意图已登记，请分别准备并授权预算和 ERP。' : intent === 'PREPARE' ? '本侧原件读取已登记，就绪后仍需明确授权。' : intent === 'AUTHORIZE' ? '本侧调整已授权，请刷新核对两侧财务和本地完成结果。' : '本次办理已保存，请核对原调整的最新结果。'
    await load()
  } catch (cause) { if (current === epoch) { if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) clear(); error.value = partialError(cause); requiresRefresh.value = true } }
  finally { if (current === epoch) { saving.value = false; syncPending(); emit('busy', false) } }
}
function time(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '尚无记录' }
function factText(value: PartialBudgetFact | PartialAccrualFact) {
  if ('reference' in value) return value.reference ? `预算凭据 ${value.reference} · ${moneyLabel(value.reducedAmount!)}` : value.rejection ? `拒绝原因：${partialIssue(value.rejection)}` : '尚无终态凭据'
  return value.voucherReference ? `凭证 ${value.voucherReference} · 过账 ${value.postingReference} · 受理 ${value.acceptanceReference} · 修订 ${value.revision}` : `修订 ${value.revision}${value.acceptanceReference ? ' · 受理 ' + value.acceptanceReference : ''}${value.rejection ? ' · 失败原因：' + partialIssue(value.rejection) : ''}`
}
watch(() => JSON.stringify([props.scopeKey, props.reportId, props.applicationId, props.roundNo, props.applicationVersion, props.financialVersion]), () => {
  stop(); clear(); loading.value = false; saving.value = false; requiresRefresh.value = false; error.value = ''; notice.value = ''; syncPending(); emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); unsubscribe(); emit('busy', false) })
</script>

<template>
  <section class="partial-adjustment" aria-label="报销部分调整">
    <div class="heading"><h4>报销部分调整</h4><button type="button" class="quiet" :disabled="loading || saving || locked" @click="notice = ''; load()">刷新部分调整</button></div>
    <p>按原费用行填写本次剩余额，选用已登记的整笔回款。预算调减与 ERP 调整分别授权，两侧确认后再恢复相应资源。</p>
    <p v-if="loading" role="status">正在核对原批准、已完成剩余额和调整记录…</p><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unconfirmed && !saving" class="error" role="alert">上次请求结果尚未确认，请先在未确认操作中恢复原请求，再刷新核对。</p>
    <p v-else-if="requiresRefresh && !error" role="status">请刷新部分调整，核对恢复后的结果。</p>
    <template v-if="view">
      <div class="ledger" aria-label="原批准与已完成剩余额">
        <div class="ledger-row ledger-title"><span>本币金额</span><span>原批准</span><span>已完成剩余</span></div>
        <div v-for="field in (['gross', 'tax', 'offsets', 'payable'] as const)" :key="field" class="ledger-row"><strong>{{ { gross: '含税总额', tax: '税额', offsets: '借款抵扣', payable: '银行应付' }[field] }}</strong><span>{{ moneyLabel(view.original.amounts[field]) }}</span><span>{{ moneyLabel(view.remaining[field]) }}</span></div>
      </div>
      <p class="muted">待办意图不会改变上方剩余额。原批准、付款、凭证和归档记录继续保留。</p>
      <details><summary>原财务依据</summary><p v-for="item in [{ label: '原预算消费', value: view.original.budget }, { label: '原挂账', value: view.original.accrual }, { label: '原付款', value: view.original.payment }, { label: '原付款凭证', value: view.original.paymentVoucher }]" :key="item.label">{{ item.label }}：{{ item.value ? partialSourceLabel(item.value.status) + ' · ' + item.value.id : '本次没有该原件' }}<template v-if="item.value"><br />更新于 {{ time(item.value.updatedAt) }}<span v-if="item.value.issue"> · {{ partialIssue(item.value.issue) }}</span></template></p></details>
      <div v-if="partialCanCreate(view) && !pending" class="buttons"><button type="button" class="primary" :disabled="blocked" @click="prepare('CREATE')">登记部分调整</button><button type="button" class="quiet" :disabled="blocked" @click="prepare('ORIGINAL_QUERY')">查询原报销财务</button></div>
      <p v-if="!view.adjustments.length" class="muted">尚无部分调整记录。</p>
      <article v-for="entry in view.adjustments" :key="entry.id" class="entry">
        <div class="heading"><h5 :class="{ complete: entry.status === 'APPLIED' }">{{ partialLabels[entry.status] }}</h5><span class="muted">{{ time(entry.updatedAt) }}</span></div>
        <p>本次含税剩额：{{ moneyLabel(entry.before.gross) }} → {{ moneyLabel(entry.after.gross) }}<br />登记人 {{ entry.requestedBy }} · 材料 {{ entry.evidenceReference }} · {{ entry.reason }}</p>
        <p v-if="entry.issue" class="error">{{ partialIssue(entry.issue) }}</p>
        <div class="sides">
          <section v-for="currentSide in partialSides" :key="currentSide" class="side" :aria-label="partialSideLabels[currentSide]">
            <h5>{{ partialSideLabels[currentSide] }}</h5>
            <template v-if="partialOperation(entry, currentSide)">
              <strong>{{ partialOperationLabels[partialOperation(entry, currentSide)!.status] }}</strong>
              <p class="muted">授权人 {{ partialOperation(entry, currentSide)!.authorizedBy }}<br />记账日 {{ partialOperation(entry, currentSide)!.accountingDate }}</p>
              <template v-for="kind in (['accepted', 'conflicting'] as const)" :key="kind"><p v-if="partialOperation(entry, currentSide)![kind]" :class="{ candidate: kind === 'conflicting' }"><b>{{ kind === 'accepted' ? '已接受事实' : '待裁决候选' }} · {{ partialFactLabel(partialOperation(entry, currentSide)![kind]!.status) }}</b><br />{{ factText(partialOperation(entry, currentSide)![kind]!) }}<br />观察于 {{ time(partialOperation(entry, currentSide)![kind]!.observedAt) }}</p></template>
              <p v-if="partialOperation(entry, currentSide)!.issue" class="error">{{ partialIssue(partialOperation(entry, currentSide)!.issue!) }}</p>
              <p v-if="partialOperation(entry, currentSide)!.conflicting">候选有效至 {{ time(partialOperation(entry, currentSide)!.candidateValidUntil) }}<br /><span v-if="partialOperation(entry, currentSide)!.resolutionIssue">{{ partialIssue(partialOperation(entry, currentSide)!.resolutionIssue!) }}</span></p>
              <details><summary>原命令与裁决记录</summary><p>当前操作 {{ partialOperation(entry, currentSide)!.id }}<br />发送有效至 {{ time(partialOperation(entry, currentSide)!.expiresAt) }}；原号查询可继续办理。</p><p v-if="partialOperation(entry, currentSide)!.latestResolution">最近裁决：{{ partialFactLabel(partialOperation(entry, currentSide)!.latestResolution!.outcome) }}<br />所裁决操作 {{ partialOperation(entry, currentSide)!.latestResolution!.operationId }}<br />{{ partialOperation(entry, currentSide)!.latestResolution!.resolvedBy }} · {{ time(partialOperation(entry, currentSide)!.latestResolution!.resolvedAt) }}<br />材料 {{ partialOperation(entry, currentSide)!.latestResolution!.evidenceReference }} · {{ partialOperation(entry, currentSide)!.latestResolution!.reason }}<br /><span v-if="partialOperation(entry, currentSide)!.latestResolution!.operationId !== partialOperation(entry, currentSide)!.id">这是旧操作的裁决，当前操作须单独核对。</span></p></details>
              <button v-if="partialOperation(entry, currentSide)!.canResolve && !pending" type="button" class="quiet" :disabled="blocked" @click="prepare('DISPUTE', entry.id, currentSide)">核对并裁决本侧争议</button>
            </template>
            <p v-else class="muted">本侧尚未授权。</p>
            <div v-if="partialPreparation(entry, currentSide)" class="preparation">
              <strong>{{ adjustmentPreparationLabels[partialPreparation(entry, currentSide)!.status] }}</strong>
              <p>本人准备 · 记账日 {{ partialPreparation(entry, currentSide)!.accountingDate }}<br />材料 {{ partialPreparation(entry, currentSide)!.evidenceReference }} · {{ partialPreparation(entry, currentSide)!.reason }}</p>
              <p v-if="partialPreparation(entry, currentSide)!.periodReference">期间 {{ partialPreparation(entry, currentSide)!.periodReference }} · 有效至 {{ time(partialPreparation(entry, currentSide)!.expiresAt) }}</p>
              <p v-if="partialPreparation(entry, currentSide)!.issue || partialPreparation(entry, currentSide)!.authorizationIssue">{{ partialIssue((partialPreparation(entry, currentSide)!.issue || partialPreparation(entry, currentSide)!.authorizationIssue)!) }}</p>
              <button v-if="partialPreparation(entry, currentSide)!.canAuthorize && !pending" type="button" class="primary" :disabled="blocked" @click="prepare('AUTHORIZE', entry.id, currentSide)">核对并授权本侧调整</button>
            </div>
            <button v-if="partialCanPrepare(view, entry, currentSide) && !pending" type="button" class="quiet" :disabled="blocked" @click="prepare('PREPARE', entry.id, currentSide)">准备本侧原件与期间</button>
          </section>
        </div>
        <p v-if="entry.completion" class="result">本地资源已完成本次差额调整 · {{ time(entry.completion.at) }}<br />预算凭据 {{ entry.completion.budgetReference }} · ERP 凭证 {{ entry.completion.voucherReference }}<span v-if="entry.status === 'REVIEW_REQUIRED'"><br />原完成记录保留；当前复核通过前不能继续新调整。</span></p><p v-else-if="!entry.retirement" class="muted">本次本地资源尚未完成，以上已接受财务事实分别保留。</p>
        <p v-if="entry.retirement">结束人 {{ entry.retirement.actor }} · {{ time(entry.retirement.at) }}<br />材料 {{ entry.retirement.evidenceReference }} · {{ entry.retirement.reason }}</p>
        <details><summary>本次金额与回款明细</summary><p>调整编号 {{ entry.id }}</p><p v-for="(line, index) in entry.after.lines" :key="line.lineNo">第 {{ line.lineNo }} 行：含税 {{ moneyLabel(entry.before.lines[index]!.gross) }} → {{ moneyLabel(line.gross) }}；税额 {{ moneyLabel(entry.before.lines[index]!.tax) }} → {{ moneyLabel(line.tax) }}</p><p>采用的整笔回款：{{ entry.returnIds.length ? entry.returnIds.join('、') : '无需银行回款' }}</p></details>
        <div v-if="!pending" class="buttons"><button v-for="action in entry.availableActions" :key="action" type="button" class="quiet" :disabled="blocked" @click="prepare(action, entry.id)">{{ partialIntentLabels[action] }}</button><button v-if="entry.canQueryOriginals" type="button" class="quiet" :disabled="blocked" @click="prepare('SOURCE_QUERY', entry.id)">重查原报销财务</button><button v-if="entry.canRetire" type="button" class="quiet" :disabled="blocked" @click="prepare('RETIRE', entry.id)">安全结束本次调整</button></div>
      </article>
      <form v-if="pending" ref="form" @submit.prevent="execute">
        <h4>{{ partialIntentLabels[pending] }}<template v-if="['PREPARE', 'AUTHORIZE', 'DISPUTE'].includes(pending)"> · {{ partialSideLabels[side] }}</template></h4>
        <template v-if="pending === 'CREATE'">
          <p>填写调整后的剩余额，未减少的行保持原值。税额和不含税费用都只能减少。</p>
          <div v-for="(line, index) in lines" :key="line.lineNo" class="line-input"><strong>第 {{ line.lineNo }} 行</strong><span class="muted">当前含税 {{ moneyLabel(view.remaining.lines[index]!.gross) }} · 税额 {{ moneyLabel(view.remaining.lines[index]!.tax) }}</span><label>剩余含税额<input v-model="line.remainingGross" inputmode="decimal" required :disabled="saving || locked" maxlength="18" /></label><label>剩余税额<input v-model="line.remainingTax" inputmode="decimal" required :disabled="saving || locked" maxlength="18" /></label></div>
          <fieldset :disabled="saving || locked"><legend>选择本次采用的整笔回款</legend><p v-if="!view.returns.length" class="muted">尚无已登记回款。需要银行回款时，请先在原付款退回区域核验并登记。</p><label v-for="entry in view.returns" :key="entry.fundsIdentity" class="ack"><input v-model="returnIds" type="checkbox" :value="entry.fundsIdentity" :disabled="!entry.available" /><span>{{ moneyLabel(entry.amount) }} · {{ entry.fundsIdentity }}<br />{{ time(entry.receivedAt) }} · {{ entry.available ? '可选整笔入款' : '已被调整占用' }}</span></label></fieldset>
          <p v-if="preview?.error" class="muted">{{ preview.error }}</p><div v-if="preview?.value" class="preview" aria-live="polite"><p>本次减少 {{ moneyLabel(preview.value.reduction) }} · 恢复借款抵扣 {{ moneyLabel(preview.value.advanceRestored) }}</p><p :class="{ error: !preview.value.matched }">所需银行回款 {{ moneyLabel(preview.value.bankReturn) }} · 已选 {{ moneyLabel(preview.value.selectedReturn) }}</p></div>
        </template>
        <p v-else-if="pending === 'PREPARE'">选择本侧记账日期。本次读取原银行、凭证和开放期间，准备完成后仍需本人明确授权。</p>
        <p v-else-if="pending === 'AUTHORIZE'">确认本侧差额、原件和期间。授权后后台可发送该侧固定命令。另一侧成功事实和原报销保持。<br />记账日 {{ selectedPreparation?.accountingDate }} · 期间 {{ selectedPreparation?.periodReference }} · 有效至 {{ time(selectedPreparation?.expiresAt ?? null) }}</p>
        <template v-else-if="pending === 'DISPUTE'"><p>采用下列已保存的原号候选。裁决仅处理当前侧；来源复核和本地完成仍须分别确认。</p><p v-if="selectedOperation?.conflicting" class="candidate"><strong>拟采用结果：{{ partialFactLabel(selectedOperation.conflicting.status) }}</strong><br />{{ factText(selectedOperation.conflicting) }}<br />操作 {{ selectedOperation.id }}<br />有效至 {{ time(selectedOperation.candidateValidUntil) }}</p></template>
        <p v-else-if="pending === 'RETIRE'">确认两侧均没有实际效果。安全结束会释放本次回款占用，并保留原意图和结束证明。</p>
        <p v-else-if="pending === 'CONFIRM_CURRENT'">核对原来源与两侧成功事实。本次确认不会重新发送财务命令，已完成资源保留。</p>
        <p v-else-if="pending.startsWith('RESEND')">原命令已明确查无。本次沿用原编号、原金额与未过期授权重新发送。</p>
        <p v-else>登记原号只读查询，结果返回后刷新核对；查询不会替代授权或争议裁决。</p>
        <p v-if="selectedEntry" class="muted">本次调整 {{ selectedEntry.id }} · 含税剩余 {{ moneyLabel(selectedEntry.after.gross) }}</p>
        <label v-if="pending === 'PREPARE'">本侧记账日期<input v-model="accountingDate" type="date" required :disabled="saving || locked" /></label>
        <label v-if="needsReference">核验材料编号<input v-model="reference" maxlength="128" required :disabled="saving || locked" /></label>
        <label>办理说明<textarea v-model="comment" rows="3" maxlength="2000" required :disabled="saving || locked" /></label>
        <label v-if="needsAck" class="ack"><input v-model="acknowledged" type="checkbox" required :disabled="saving || locked" />已核对本次金额、所选回款与财务依据，确认执行所示操作</label>
        <div class="buttons"><button class="primary" :disabled="blocked || !!needsAck && !acknowledged || pending === 'CREATE' && !preview?.value?.matched">{{ saving ? '正在保存…' : '确认' + partialIntentLabels[pending] }}</button><button type="button" class="quiet" :disabled="saving" @click="cancel">取消</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.partial-adjustment{margin-top:18px;padding:16px;border:1px solid var(--line);border-radius:10px;background:var(--paper);min-width:0;overflow-wrap:anywhere}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h4,h5{margin:0;font-size:14px}h5{font-size:13px}p,label,summary,.ledger,fieldset{font-size:12px;line-height:1.8}.muted{color:var(--muted);font-size:12px}.error{color:var(--red)}.complete{color:var(--teal)}.ledger{margin:16px 0;border:1px solid var(--line);border-radius:8px;background:white;overflow:hidden}.ledger-row{display:grid;grid-template-columns:1fr 1.2fr 1.2fr;gap:12px;padding:10px 12px;border-bottom:1px solid var(--line);font-variant-numeric:tabular-nums}.ledger-row:last-child{border-bottom:0}.ledger-row span:not(:first-child){text-align:right}.ledger-title{background:var(--paper);color:var(--muted)}.entry,form{margin-top:18px;padding-top:18px;border-top:1px solid var(--line)}.sides{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.side{min-width:0;padding:14px;border:1px solid var(--line);border-radius:8px;background:white}.side h5{margin-bottom:10px}.side strong{font-size:12px}.preparation{margin-top:14px;padding-top:12px;border-top:1px solid var(--line)}.candidate{border-left:3px solid var(--red);padding:9px 12px;background:var(--paper)}.result{padding:12px;border-left:3px solid var(--teal);background:white}.buttons{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}button{min-height:40px}summary{cursor:pointer;padding:9px 0}label{display:grid;gap:7px;margin-top:12px}input,textarea{width:100%;min-width:0;padding:10px;border:1px solid var(--line);border-radius:7px;background:white;font:inherit}textarea{resize:vertical}.ack{display:flex;align-items:center;gap:9px}.ack input{width:18px;height:18px;flex-shrink:0}.line-input{display:grid;grid-template-columns:1fr 1fr;gap:0 12px;padding:14px 0;border-bottom:1px solid var(--line)}.line-input>strong,.line-input>.muted{grid-column:1/-1;font-size:12px}fieldset{margin:16px 0 0;padding:12px;border:1px solid var(--line);border-radius:8px;min-width:0}.preview{padding:4px 12px;margin-top:12px;background:white;border-radius:8px}@media(max-width:650px){.sides{grid-template-columns:1fr}.ledger-row{gap:6px;padding:10px 8px;font-size:11px}}@media(max-width:400px){.line-input{grid-template-columns:1fr}}
</style>
