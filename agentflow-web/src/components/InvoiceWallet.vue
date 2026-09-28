<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { fileSize } from '../attachments'
import { ExpensePageQuery, moneyLabel } from '../expenses'
import { invoiceError, occupationStatuses, verificationStatuses, type InvoiceWalletOptions } from '../invoiceWallet'
import InvoiceUploader from './InvoiceUploader.vue'
import InvoiceDetail from './InvoiceDetail.vue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean }>()
const records = reactive(new ExpensePageQuery(api.invoices)), options = ref<InvoiceWalletOptions | null>(null), error = ref(''), selectedId = ref(''), busy = ref(false)
let epoch = 0, controller: AbortController | null = null
async function limits() {
  controller?.abort(); const request = new AbortController(), version = ++epoch; controller = request; options.value = null; error.value = ''
  const current = () => version === epoch && !request.signal.aborted
  const timeout = setTimeout(() => { if (current()) error.value = '票夹配置读取超时，请刷新。'; request.abort() }, 12_000)
  try { const result = await api.invoiceWalletOptions(request.signal); if (current()) options.value = result }
  catch (cause) { if (current()) error.value = invoiceError(cause) }
  finally { clearTimeout(timeout) }
}
function refresh() { void limits(); void records.load(props.scopeKey) }
function uploaded(id: string) { selectedId.value = id; void records.load(props.scopeKey) }
watch(() => props.scopeKey, () => { records.clear(); selectedId.value = ''; busy.value = false; refresh() }, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
onUnmounted(() => { epoch++; controller?.abort(); records.clear() })
</script>

<template>
  <div class="invoice-wallet">
    <div class="wallet-heading"><div><h3>个人票夹</h3><p>保留本人原件，查验票面，再用于费用报销。</p></div><button v-if="selectedId" class="secondary" :disabled="busy" @click="selectedId = ''; refresh()">返回票夹</button><button v-else class="secondary" :disabled="busy || records.loading" @click="refresh">刷新票夹</button></div>
    <p v-if="error" class="invoice-error" role="alert">{{ error }}</p>
    <InvoiceDetail v-if="selectedId" :key="selectedId" :invoice-id="selectedId" :scope-key="scopeKey" :refresh-version="refreshVersion" :options="options" :locked="locked" @uploaded="uploaded" @changed="records.load(scopeKey)" @busy="busy = $event" />
    <template v-else>
      <InvoiceUploader :scope-key="scopeKey" :options="options" :locked="locked" @uploaded="uploaded" @busy="busy = $event" />
      <p class="wallet-count">已加载 {{ records.items.length }} 份 · 原件、查验和占用分别记录</p>
      <p v-if="records.error" class="invoice-error" role="alert">票夹读取失败，请刷新后核对。</p>
      <div class="invoice-ledger"><article v-for="invoice in records.items" :key="invoice.id"><div class="invoice-name"><strong>{{ invoice.original.filename }}</strong><small>{{ invoice.original.format }} · {{ fileSize(invoice.original.size) }} · {{ invoice.original.status === 'READY' ? '原件已保存' : '原件待上传' }}</small></div><div class="invoice-status"><span>{{ verificationStatuses[invoice.verification] }}</span><small>{{ occupationStatuses[invoice.occupation] }}</small></div><strong class="invoice-amount">{{ invoice.facts ? moneyLabel(invoice.facts.gross) : '票面待查验' }}</strong><button type="button" class="secondary" :disabled="busy" :aria-label="`查看票据 ${invoice.original.filename}`" @click="selectedId = invoice.id">查看与查验</button></article></div>
      <p v-if="records.loading" class="wallet-empty" role="status">正在读取本人票夹…</p><p v-else-if="!records.error && !records.items.length" class="wallet-empty">票夹还没有原件，从上方添加第一份。</p>
      <button v-if="records.nextBeforeId" type="button" class="secondary" :disabled="records.loading" @click="records.load(scopeKey, undefined, true)">加载更多票据</button>
    </template>
  </div>
</template>

<style scoped>
.invoice-wallet{min-width:0}.wallet-heading{display:flex;justify-content:space-between;align-items:center;gap:20px}.wallet-heading h3{font-size:19px;margin:8px 0}.wallet-heading p,.wallet-count{font-size:12px;color:var(--muted);line-height:1.8}.wallet-count{margin:24px 0 12px}.invoice-error{font-size:12px;color:var(--red);line-height:1.8}.invoice-ledger{border-radius:12px;overflow:hidden}.invoice-ledger article{display:grid;grid-template-columns:minmax(0,2fr) 125px 135px 110px;gap:18px;align-items:center;padding:20px;background:white;border:1px solid var(--line);border-bottom:0}.invoice-ledger article:last-child{border-bottom:1px solid var(--line)}.invoice-name strong{font-size:13px;overflow-wrap:anywhere;line-height:1.7}.invoice-name small,.invoice-status small{display:block;font-size:11px;color:var(--muted);margin-top:8px}.invoice-status span{font-size:12px;color:var(--deep)}.invoice-amount{font:12px 'DM Mono',monospace;overflow-wrap:anywhere}.invoice-ledger button{font-size:11px}.wallet-empty{text-align:center;border:1px dashed var(--line);border-radius:12px;padding:40px 18px;font-size:12px;color:var(--muted)}@media(max-width:950px){.invoice-ledger article{grid-template-columns:minmax(0,1fr) auto;gap:16px}.invoice-amount{grid-column:1}.invoice-ledger button{grid-column:2}}@media(max-width:550px){.wallet-heading{align-items:flex-start;flex-wrap:wrap}.invoice-ledger article{padding:16px}}
</style>
