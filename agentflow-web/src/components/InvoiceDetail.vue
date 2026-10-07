<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { fileDigest, fileSize } from '../attachments'
import { moneyLabel } from '../expenses'
import { invoiceError, invoiceIssue, invoiceOccupationLabel, occupationStatuses, verificationStatuses, type InvoiceItem, type InvoiceWalletOptions } from '../invoiceWallet'
import InvoiceUploader from './InvoiceUploader.vue'
import InvoiceVerification from './InvoiceVerification.vue'
import InvoiceExtraction from './InvoiceExtraction.vue'
const props = defineProps<{ invoiceId: string; scopeKey: string; refreshVersion: number; options: InvoiceWalletOptions | null; locked?: boolean }>()
const emit = defineEmits<{ uploaded: [invoiceId: string]; changed: []; busy: [value: boolean] }>()
const item = ref<InvoiceItem | null>(null), loading = ref(false), downloading = ref(false), error = ref(''), actionBusy = ref(false), verificationRefresh = ref(0)
const extractionBusy = ref(false), extractionDirty = ref(false)
const interactionsBusy = computed(() => actionBusy.value || extractionBusy.value || extractionDirty.value)
let epoch = 0, readSequence = 0, readController: AbortController | null = null, downloadController: AbortController | null = null
const urls = new Set<string>()
function stop() { epoch++; readSequence++; readController?.abort(); downloadController?.abort(); loading.value = false; downloading.value = false; for (const url of urls) URL.revokeObjectURL(url); urls.clear() }
async function load() {
  readController?.abort(); const request = new AbortController(), version = epoch, sequence = ++readSequence; readController = request
  loading.value = true; error.value = ''
  const current = () => version === epoch && sequence === readSequence && !request.signal.aborted
  const timeout = setTimeout(() => { if (current()) { item.value = null; loading.value = false; error.value = '票据读取超时，请刷新。' }; request.abort() }, 12_000)
  try { const value = await api.invoice(props.invoiceId, request.signal); if (current()) { if (value.id !== props.invoiceId) throw new Error('Invoice binding mismatch'); item.value = value } }
  catch (cause) { if (current()) { item.value = null; error.value = invoiceError(cause) } }
  finally { clearTimeout(timeout); if (current()) loading.value = false }
}
/** 下载前重新读取本人元数据，收齐字节并核对 SHA-256 后才保存文件。 */
async function download() {
  if (!item.value || downloading.value || loading.value || item.value.original.status !== 'READY') return
  const version = epoch, id = props.invoiceId, request = new AbortController(); downloadController = request
  const current = () => version === epoch && !request.signal.aborted
  downloading.value = true; error.value = ''
  const timeout = setTimeout(() => { if (current()) { error.value = '原件下载超时，请重试。'; downloading.value = false }; request.abort() }, 120_000)
  try {
    const latest = await api.invoice(id, request.signal)
    if (!current()) return
    const content = await api.downloadInvoice(id, request.signal)
    if (!current()) return
    const digest = await fileDigest(content)
    if (!current()) return
    if (latest.id !== id || latest.original.status !== 'READY' || content.size !== latest.original.size || digest !== latest.original.sha256) throw new Error('Original integrity mismatch')
    const url = URL.createObjectURL(content); urls.add(url)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = latest.original.filename; anchor.click()
    setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url) }, 1_000)
  } catch (cause) { if (current()) error.value = invoiceError(cause) }
  finally { clearTimeout(timeout); if (current()) downloading.value = false }
}
function refreshed() { void load(); emit('changed') }
watch(() => [props.scopeKey, props.invoiceId], () => { stop(); item.value = null; error.value = ''; if (props.scopeKey) void load() }, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, () => { void load(); verificationRefresh.value++ })
watch(interactionsBusy, value => emit('busy', value), { flush: 'sync' })
onUnmounted(() => { stop(); emit('busy', false) })
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="invoice-detail" aria-label="本人票据详情">
    <div class="detail-toolbar"><p class="eyebrow">INVOICE RECORD</p><button type="button" class="secondary" :disabled="loading || interactionsBusy || downloading" @click="load(); verificationRefresh++">刷新票据</button></div>
    <p v-if="loading" class="invoice-help" role="status">正在核对本人票据…</p><p v-if="error" class="invoice-error" role="alert">{{ error }}</p>
    <template v-if="item">
      <h3>{{ item.original.filename }}</h3><p class="invoice-help">{{ item.original.format }} · {{ fileSize(item.original.size) }} · {{ dateLabel(item.original.createdAt) }} 保存</p>
      <div class="invoice-states"><span>原件：{{ item.original.status === 'READY' ? '已保存' : item.original.status === 'FAILED' ? '待恢复上传' : '等待上传' }}</span><span>查验：{{ verificationStatuses[item.verification] }}</span><span>占用：{{ occupationStatuses[item.activeClaim?.status ?? item.occupation] }}</span></div>
      <button v-if="item.original.status === 'READY'" type="button" class="secondary" :disabled="loading || downloading" @click="download">{{ downloading ? '下载中…' : '下载发票原件' }}</button>
      <p v-if="item.failureCode" class="invoice-error">{{ invoiceIssue(item.failureCode) }}</p>
      <dl v-if="item.facts" class="invoice-facts"><div><dt>发票号码</dt><dd>{{ item.facts.key.number }}</dd></div><div><dt>发票代码</dt><dd>{{ item.facts.key.code ?? '数电票不适用' }}</dd></div><div><dt>含税金额</dt><dd>{{ moneyLabel(item.facts.gross) }}</dd></div><div><dt>票面税额</dt><dd>{{ moneyLabel(item.facts.tax) }}</dd></div><div><dt>开票日期</dt><dd>{{ item.facts.issueDate }}</dd></div><div><dt>查验事实有效至</dt><dd>{{ dateLabel(item.facts.validUntil) }}</dd></div></dl>
      <p v-if="item.facts && item.verification !== 'VERIFIED'" class="invoice-help">上方保留历史已确认票面；当前查验未通过，不能据此新增报销占用。</p>
      <p v-if="item.activeClaim" class="invoice-help">票号当前{{ invoiceOccupationLabel(item.activeClaim) }}</p>
      <p v-else-if="item.use" class="invoice-help">关联报销来源暂不可用 · 第 {{ item.use.roundNo }} 轮 · 第 {{ item.use.lineNo }} 行</p>
      <InvoiceUploader v-if="item.original.status !== 'READY'" :scope-key="scopeKey" :options="options" :restore-id="item.id" :locked="locked || loading" @uploaded="emit('uploaded', $event)" @busy="actionBusy = $event" />
      <template v-else>
        <InvoiceExtraction :item="item" :scope-key="scopeKey" :refresh-version="verificationRefresh" :locked="locked || loading || actionBusy" @busy="extractionBusy = $event" @dirty="extractionDirty = $event" />
        <InvoiceVerification :item="item" :scope-key="scopeKey" :refresh-version="verificationRefresh" :locked="locked || loading || extractionBusy || extractionDirty" @changed="refreshed" @busy="actionBusy = $event" />
      </template>
    </template>
  </section>
</template>

<style scoped>
.invoice-detail{border:1px solid var(--line);border-radius:12px;background:#fff;padding:26px;margin:20px 0;min-width:0}.detail-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px}.invoice-detail h3{font-size:20px;overflow-wrap:anywhere;margin:16px 0 8px}.invoice-help{font-size:12px;color:var(--muted);line-height:1.9;overflow-wrap:anywhere}.invoice-error{font-size:12px;color:var(--red);line-height:1.8}.invoice-states{display:flex;flex-wrap:wrap;gap:10px;margin:20px 0}.invoice-states span{background:var(--paper);border-radius:6px;padding:8px 10px;font-size:11px}.invoice-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:22px;padding:22px 0;margin:20px 0;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.invoice-facts dt{font-size:11px;color:var(--muted);margin-bottom:10px}.invoice-facts dd{margin:0;font:13px 'DM Mono',monospace;overflow-wrap:anywhere;line-height:1.7}@media(max-width:600px){.invoice-detail{padding:16px}.invoice-facts{grid-template-columns:1fr;gap:16px}}
</style>
