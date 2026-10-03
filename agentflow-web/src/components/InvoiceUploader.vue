<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { fileDigest, fileSize } from '../attachments'
import { invoiceError, invoiceFileFormat, invoiceUploads, type InvoiceUploadAttempt, type InvoiceWalletOptions } from '../invoiceWallet'

const props = defineProps<{ scopeKey: string; options: InvoiceWalletOptions | null; restoreId?: string; locked?: boolean }>()
const emit = defineEmits<{ uploaded: [invoiceId: string]; busy: [value: boolean] }>()
const attempt = ref<InvoiceUploadAttempt | null>(null), uploading = ref(false), error = ref(''), phase = ref('')
let epoch = 0, controller: AbortController | null = null
function busy(value: boolean) { uploading.value = value; emit('busy', value) }
function stop() { epoch++; controller?.abort(); controller = null; busy(false) }
watch(() => props.scopeKey, () => { stop(); attempt.value = invoiceUploads.get(props.scopeKey); error.value = ''; phase.value = '' }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
function selected(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || uploading.value || props.locked || attempt.value || !props.scopeKey || !props.options) return
  try {
    invoiceFileFormat(file, props.options)
    const value: InvoiceUploadAttempt = { file, key: crypto.randomUUID(), registrationSent: false, ...(props.restoreId ? { invoiceId: props.restoreId } : {}) }
    invoiceUploads.set(props.scopeKey, value); attempt.value = value; error.value = ''; phase.value = ''
  } catch (cause) { error.value = (cause as Error).message }
}
function discard() {
  if (uploading.value || !attempt.value || attempt.value.registrationSent) return
  invoiceUploads.clear(props.scopeKey); attempt.value = null; error.value = ''; phase.value = ''
}
/** 中断时保留原文件、原登记键和已取得的发票身份；恢复只发送同一份内容。 */
async function upload() {
  const active = attempt.value, scope = props.scopeKey
  if (!active || !scope || uploading.value || props.locked || !props.options?.enabled) return
  const generation = epoch, request = new AbortController(); controller = request
  const current = () => generation === epoch && !request.signal.aborted
  const timeout = setTimeout(() => {
    if (current()) { error.value = '上传结果未确认，请继续原上传；原登记身份已保留。'; phase.value = ''; busy(false) }
    request.abort()
  }, 120_000)
  busy(true); error.value = ''
  try {
    phase.value = '正在计算原件摘要…'
    const sha256 = await fileDigest(active.file)
    if (!current()) return
    if (!active.invoiceId) {
      active.input ??= { filename: active.file.name, size: active.file.size, sha256, format: invoiceFileFormat(active.file, props.options) }
      active.registrationSent = true; phase.value = '正在登记原件…'
      const receipt = await api.reserveInvoice(active.input, active.key, request.signal)
      if (!current()) return
      active.invoiceId = receipt.id
    }
    const item = await api.invoice(active.invoiceId, request.signal)
    if (!current()) return
    if (item.id !== active.invoiceId || item.original.sha256 !== sha256 || item.original.size !== active.file.size) {
      error.value = '所选文件与原登记不同，请选择原文件恢复。'; return
    }
    if (item.original.status !== 'READY') {
      phase.value = '正在上传并核对原件…'
      const original = await api.uploadInvoice(active.invoiceId, active.file, request.signal)
      if (!current()) return
      if (original.id !== item.original.id || original.status !== 'READY' || original.sha256 !== sha256 || original.size !== active.file.size) throw new Error('Invalid original receipt')
    }
    const id = active.invoiceId
    invoiceUploads.clear(scope); attempt.value = null; phase.value = '原件已保存，可以选择法人发起查验。'; emit('uploaded', id)
  } catch (cause) {
    if (current()) {
      const failure = cause as { status?: number; code?: string }
      // 明确拒绝且尚未取得登记身份，才允许换文件；认证和未知结果继续原请求。
      if (!active.invoiceId && (failure.status ?? 0) >= 400 && (failure.status ?? 0) < 500 && failure.status !== 401 && !['CSRF_INVALID', 'IDEMPOTENCY_KEY_EXPIRED', 'IDEMPOTENCY_KEY_REUSED'].includes(failure.code ?? '')) active.registrationSent = false
      error.value = invoiceError(cause)
    }
  } finally { clearTimeout(timeout); if (current()) { busy(false); controller = null } }
}
</script>

<template>
  <section class="invoice-uploader" aria-label="发票原件上传" :aria-busy="uploading">
    <div><p class="eyebrow">ORIGINAL DOCUMENT</p><h3>{{ restoreId ? '恢复原件上传' : '添加一份发票原件' }}</h3></div>
    <p class="upload-help">{{ restoreId ? '选择原登记对应的同一份文件，继续使用已有发票记录。' : '保存原件后，再明确选择法人查验。查验通过的发票可在报销费用行中选择。' }}</p>
    <template v-if="options?.enabled">
      <label v-if="!attempt" class="file-picker">{{ restoreId ? '选择原文件' : '选择发票原件' }}<input type="file" accept=".pdf,.ofd,.png,.jpg,.jpeg,.xml" :disabled="locked || uploading" :aria-label="restoreId ? '选择原文件恢复票据' : '选择发票原件'" @change="selected" /></label>
      <div v-else class="upload-selection"><div><strong>{{ attempt.file.name }}</strong><small>{{ fileSize(attempt.file.size) }} · {{ attempt.invoiceId ? '继续原登记' : attempt.registrationSent ? '登记结果待确认' : '等待确认上传' }}</small></div><div class="upload-actions"><button class="primary" type="button" :disabled="locked || uploading" @click="upload">{{ uploading ? '上传中…' : attempt.registrationSent || attempt.invoiceId ? '继续原上传' : '确认上传原件' }}</button><button v-if="!attempt.registrationSent" class="quiet" type="button" :disabled="uploading" @click="discard">重新选择</button></div></div>
      <p class="upload-help">支持 {{ options.formats.join(' / ') }}；单份最多 {{ fileSize(options.maxFileBytes) }}。本人累计最多 {{ options.maxWalletUploads }} 次 / {{ fileSize(options.maxWalletBytes) }}，包含历史及未完成上传。</p>
    </template>
    <p v-else class="upload-help">{{ options ? '原件存储尚未就绪，请联系管理员配置持久目录。' : '正在读取原件上传配置…' }}</p>
    <p v-if="phase" class="upload-help" role="status">{{ phase }}</p><p v-if="error" class="invoice-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.invoice-uploader{border:1px solid var(--line);border-radius:12px;background:#fff;padding:22px;margin:20px 0}.invoice-uploader h3{font-size:17px;margin:7px 0}.upload-help{font-size:12px;color:var(--muted);line-height:1.8}.file-picker{display:grid;gap:12px;font-size:12px;padding:18px;background:var(--paper);border:1px dashed var(--line);border-radius:9px}.file-picker input{max-width:100%;font:inherit}.upload-selection{display:flex;align-items:center;justify-content:space-between;gap:18px;background:var(--paper);border-radius:9px;padding:16px}.upload-selection strong{display:block;font-size:13px;overflow-wrap:anywhere}.upload-selection small{display:block;font-size:11px;color:var(--muted);margin-top:8px}.upload-actions{display:flex;gap:10px;flex-wrap:wrap}.invoice-error{font-size:12px;color:var(--red);line-height:1.8}@media(max-width:650px){.invoice-uploader{padding:16px}.upload-selection{align-items:stretch;flex-direction:column}}
</style>
