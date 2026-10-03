<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { ExpensePageQuery } from '../expenses'
import type { FinanceCatalog } from '../expenseDraft'
import { invoiceError, invoiceIssue, verificationJobStatuses, type InvoiceItem, type InvoiceVerificationJob, type InvoiceVerificationOptions } from '../invoiceWallet'

const props = defineProps<{ item: InvoiceItem; scopeKey: string; refreshVersion: number; locked?: boolean }>()
const emit = defineEmits<{ changed: []; busy: [value: boolean] }>()
const options = ref<InvoiceVerificationOptions | null>(null), catalog = ref<FinanceCatalog | null>(null), legalEntityId = ref('')
const job = ref<InvoiceVerificationJob | null>(null), reading = ref(false), saving = ref(false), confirm = ref(false), error = ref(''), catalogError = ref(''), needsRefresh = ref(false)
const history = ref(new ExpensePageQuery<InvoiceVerificationJob>((filter, signal) => api.invoiceVerifications(props.item.id, filter, signal)))
const historyOpen = ref(false)
let epoch = 0, controller: AbortController | null = null, poll: ReturnType<typeof setTimeout> | undefined, pollUntil = 0
const active = computed(() => !!options.value?.activeVerificationId || job.value?.status === 'QUEUED' || job.value?.status === 'RUNNING')
const blocked = computed(() => !!props.locked || saving.value || reading.value || active.value || needsRefresh.value)
function stop() { epoch++; controller?.abort(); controller = null; clearTimeout(poll); poll = undefined }
/** 目标和当前活动任务来自本人选项接口；历史分页只作历史展示。 */
async function load(jobId?: string) {
  stop(); const version = epoch, request = new AbortController(); controller = request
  reading.value = true; options.value = null; catalog.value = null; job.value = null; error.value = ''; catalogError.value = ''; confirm.value = false
  const current = () => version === epoch && !request.signal.aborted
  const timeout = setTimeout(() => { if (current()) { error.value = '查验信息读取超时，请刷新。'; reading.value = false }; request.abort() }, 12_000)
  try {
    const [available, catalogResult] = await Promise.all([api.invoiceVerificationOptions(props.item.id, request.signal), api.financeCatalog(request.signal).then(value => ({ value }), () => ({ value: null }))])
    if (!current()) return
    const id = available.activeVerificationId ?? jobId
    const value = id ? await api.invoiceVerification(props.item.id, id, request.signal) : null
    if (!current()) return
    if (value && value.id !== id) throw new Error('Verification binding mismatch')
    options.value = available; catalog.value = catalogResult.value; job.value = value; needsRefresh.value = false
    if (!catalog.value) catalogError.value = '本人财务法人目录暂不可用，请刷新后再发起查验。'
    if (available.confirmedLegalEntityId) legalEntityId.value = available.confirmedLegalEntityId
    if (value && !['QUEUED', 'RUNNING'].includes(value.status)) {
      // 活动指针与详情读取之间可以完成；以任务终态显示结果，重新读取选项解除原活动键。
      const latestOptions = available.activeVerificationId === value.id ? await api.invoiceVerificationOptions(props.item.id, request.signal) : available
      if (!current()) return
      options.value = latestOptions
      emit('changed')
    }
    if (active.value && Date.now() < pollUntil) poll = setTimeout(() => void load(id ?? undefined), 2_000)
  } catch (cause) { if (current()) { options.value = null; catalog.value = null; job.value = null; error.value = invoiceError(cause) } }
  finally { clearTimeout(timeout); if (current()) { reading.value = false; controller = null } }
}
function canQueue() {
  const available = options.value, directory = catalog.value
  return !blocked.value && !!available?.enabled && !!available.targetDigest && available.invoiceVersion === props.item.version
    && props.item.original.status === 'READY' && !!directory && Date.parse(directory.validUntil) > Date.now()
    && directory.legalEntities.some(entity => entity.id === legalEntityId.value)
    && (!available.confirmedLegalEntityId || available.confirmedLegalEntityId === legalEntityId.value)
}
function prepare() { if (canQueue()) confirm.value = true; else error.value = '请选择本人有效法人，并刷新核对当前发票和查验配置。' }
async function queue() {
  if (!confirm.value || !canQueue() || !options.value?.targetDigest) return
  const version = epoch, id = props.item.id
  saving.value = true; emit('busy', true); error.value = ''
  try {
    const receipt = await api.queueInvoiceVerification(id, { expectedInvoiceVersion: options.value.invoiceVersion, legalEntityId: legalEntityId.value, targetDigest: options.value.targetDigest })
    if (version !== epoch) return
    confirm.value = false; saving.value = false; emit('busy', false); pollUntil = Date.now() + 90_000; await load(receipt.id)
    if (historyOpen.value) void history.value.load(props.scopeKey)
  } catch (cause) { if (version === epoch) { error.value = invoiceError(cause); confirm.value = false; needsRefresh.value = true } }
  finally { if (version === epoch) { saving.value = false; emit('busy', false) } }
}
function refresh() { pollUntil = Date.now() + 90_000; void load(job.value?.id); emit('changed'); if (historyOpen.value) void history.value.load(props.scopeKey) }
function toggleHistory() { historyOpen.value = !historyOpen.value; if (historyOpen.value) void history.value.load(props.scopeKey); else history.value.clear() }
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(legalEntityId, () => { confirm.value = false })
watch(() => JSON.stringify([props.scopeKey, props.item.id]), () => {
  stop(); history.value.clear(); historyOpen.value = false; legalEntityId.value = ''; job.value = null; saving.value = false; emit('busy', false); pollUntil = Date.now() + 90_000
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
onUnmounted(() => { stop(); history.value.clear(); emit('busy', false) })
</script>

<template>
  <section class="invoice-verification" aria-label="发票查验">
    <div class="verification-heading"><h3>核对法人，查验票面</h3><button type="button" class="quiet" :disabled="saving || reading" @click="refresh">刷新查验状态</button></div>
    <p class="verification-help">确认后将本份原件发送到所示财务查询目标。任务受理后仍需等待真实结论，上传成功不代表查验通过。</p>
    <p v-if="options?.destination" class="verification-destination">实际查询目标 <strong>{{ options.destination }}</strong></p>
    <label class="legal-picker">票面买方法人<select v-model="legalEntityId" :disabled="blocked || !!options?.confirmedLegalEntityId"><option value="">请选择本人可用法人</option><option v-if="options?.confirmedLegalEntityId && !catalog?.legalEntities.some(entity => entity.id === options?.confirmedLegalEntityId)" :value="options.confirmedLegalEntityId">已确认法人 {{ options.confirmedLegalEntityId }}</option><option v-for="entity in catalog?.legalEntities ?? []" :key="entity.id" :value="entity.id">{{ entity.name }}</option></select></label>
    <p v-if="options?.confirmedLegalEntityId" class="verification-help">已确认的票面法人固定，重新查验仍使用该法人。</p>
    <p v-if="reading" class="verification-help" role="status">正在读取查验信息…</p>
    <p v-if="catalogError" class="invoice-error" role="alert">{{ catalogError }}</p><p v-if="options && !options.enabled" class="invoice-error">{{ invoiceIssue(options.unavailableCode) }}</p><p v-if="error" class="invoice-error" role="alert">{{ error }}</p>
    <article v-if="job" class="verification-result" aria-label="本次发票查验结果"><strong>{{ verificationJobStatuses[job.status] }}</strong><p>发起于 {{ dateLabel(job.createdAt) }}<template v-if="job.completedAt"> · 完成于 {{ dateLabel(job.completedAt) }}</template></p><p v-if="job.failure || job.rejection">{{ invoiceIssue(job.failure ?? job.rejection) }}</p><small v-if="job.status === 'SUCCEEDED'">当前票面和占用请以上方刷新后的记录为准；历史成功不代表后续费用预检一定通过。</small></article>
    <div class="verification-actions"><button v-if="!confirm" type="button" class="primary" :disabled="blocked || !options?.enabled || !legalEntityId" @click="prepare">{{ item.checkedAt ? '核对并重新查验' : '核对并查验' }}</button><div v-else class="verification-confirm" role="group" aria-label="确认发送原件查验"><p>确认将「{{ item.original.filename }}」发送至 {{ options?.destination }}，按所选买方法人查验？</p><button type="button" class="secondary" :disabled="saving" @click="confirm = false">返回核对</button><button type="button" class="primary" :disabled="blocked" @click="queue">{{ saving ? '正在登记查验…' : '确认发送原件查验' }}</button></div></div>
    <button type="button" class="quiet history-toggle" :aria-expanded="historyOpen" @click="toggleHistory">{{ historyOpen ? '收起查验历史' : '查看查验历史' }}</button>
    <div v-if="historyOpen" class="verification-history"><p class="verification-help">逐条保留实际发起时间；分页顺序不代表时间先后。</p><article v-for="entry in history.items" :key="entry.id"><strong>{{ verificationJobStatuses[entry.status] }}</strong><time :datetime="entry.createdAt">{{ dateLabel(entry.createdAt) }}</time><p v-if="entry.failure || entry.rejection">{{ invoiceIssue(entry.failure ?? entry.rejection) }}</p><small>发票版本 {{ entry.invoiceVersion }}<template v-if="entry.resultingInvoiceVersion"> → {{ entry.resultingInvoiceVersion }}</template></small></article><p v-if="history.loading" class="verification-help">正在读取历史…</p><p v-else-if="!history.items.length && !history.error" class="verification-help">尚无查验记录。</p><p v-if="history.error" class="invoice-error">查验历史读取失败，请收起后重新读取。</p><button v-if="history.nextBeforeId" class="secondary" :disabled="history.loading" @click="history.load(scopeKey, undefined, true)">加载更多查验记录</button></div>
  </section>
</template>

<style scoped>
.invoice-verification{border-top:2px solid var(--teal);padding-top:20px;margin-top:24px}.verification-heading{display:flex;gap:15px;align-items:center;justify-content:space-between}.verification-heading h3{font-size:17px}.verification-help,.verification-destination{font-size:12px;color:var(--muted);line-height:1.9}.verification-destination strong{color:var(--deep);overflow-wrap:anywhere}.legal-picker{display:grid;gap:10px;font-size:12px;max-width:430px}.legal-picker select{width:100%;min-width:0;padding:11px;border:1px solid var(--line);border-radius:8px;background:white;font:inherit}.invoice-error{font-size:12px;color:var(--red);line-height:1.8}.verification-result{padding:18px;border:1px solid var(--line);border-radius:10px;background:var(--paper);font-size:12px;margin:20px 0;line-height:1.8}.verification-result strong{font-size:14px}.verification-result small{color:var(--muted)}.verification-actions{margin:18px 0}.verification-confirm{padding:18px;background:#f1faf7;border:1px solid var(--teal);border-radius:10px;font-size:12px;line-height:1.8}.verification-confirm button{margin:8px 10px 0 0}.history-toggle{margin-top:8px;font-size:12px}.verification-history article{display:grid;grid-template-columns:1fr auto;gap:8px;border-bottom:1px solid var(--line);padding:14px 0;font-size:12px}.verification-history time,.verification-history small{font-size:11px;color:var(--muted)}.verification-history p{grid-column:1/-1;margin:0;line-height:1.8}@media(max-width:600px){.verification-history article{grid-template-columns:1fr}.verification-heading{flex-wrap:wrap}}
</style>
