<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { archiveError, archiveIssue, archiveLabels, validateArchive, type ExpenseArchiveView } from '../expenseArchive'
const props = defineProps<{ reportId: string; applicationId: string; roundNo: number; applicationVersion: number; financialVersion: number; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const view = ref<ExpenseArchiveView | null>(null), loading = ref(false), downloading = ref(false), error = ref('')
let epoch = 0, controller: AbortController | null = null
const urls = new Set<string>()
function stop() { epoch++; controller?.abort(); controller = null; for (const url of urls) URL.revokeObjectURL(url); urls.clear() }
/** 每次刷新丢弃旧状态；切换身份、轮次或版本后忽略迟到请求。 */
async function load() {
  if (downloading.value || !props.scopeKey) return
  stop(); const version = epoch, request = new AbortController(); controller = request; loading.value = true; view.value = null; error.value = ''
  const binding = { reportId: props.reportId, applicationId: props.applicationId, roundNo: props.roundNo, applicationVersion: props.applicationVersion, financialVersion: props.financialVersion }
  const current = () => version === epoch && !request.signal.aborted
  const timeout = setTimeout(() => { if (current()) { error.value = '归档状态读取超时，请刷新。'; loading.value = false }; request.abort() }, 12_000)
  try { const result = await api.expenseArchive(binding.reportId, binding.roundNo, request.signal); if (current()) view.value = validateArchive(result, binding) }
  catch (cause) { if (current()) error.value = archiveError(cause) }
  finally { clearTimeout(timeout); if (current()) loading.value = false }
}
/** 下载前再次核对本轮清单，收齐 ZIP 后才触发保存；身份切换不落盘旧内容。 */
async function download() {
  const expected = view.value
  if (!expected?.canDownload || loading.value || downloading.value || props.locked) return
  const version = epoch, request = new AbortController(); controller = request; downloading.value = true; emit('busy', true); error.value = ''
  const current = () => version === epoch && !request.signal.aborted
  const timeout = setTimeout(() => { if (current()) { error.value = '档案下载超时，请重试。'; downloading.value = false; emit('busy', false) }; request.abort() }, 120_000)
  try {
    const latest = await api.expenseArchive(expected.reportId, expected.roundNo, request.signal)
    if (!current()) return
    validateArchive(latest, expected)
    if (!latest.canDownload || latest.manifestSha256 !== expected.manifestSha256) throw new Error('原归档清单已变化，请刷新核对。')
    view.value = latest
    const content = await api.downloadExpenseArchive(expected.reportId, expected.roundNo, request.signal)
    if (!current()) return
    const url = URL.createObjectURL(content); urls.add(url)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `expense-${expected.reportId}-round-${expected.roundNo}.zip`; anchor.click()
    setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url) }, 1_000)
  } catch (cause) { if (current()) { error.value = archiveError(cause); view.value = null } }
  finally { clearTimeout(timeout); if (current()) { downloading.value = false; emit('busy', false) } }
}
watch(() => JSON.stringify([props.scopeKey, props.reportId, props.applicationId, props.roundNo, props.applicationVersion, props.financialVersion]), () => {
  stop(); view.value = null; loading.value = false; downloading.value = false; error.value = ''; emit('busy', false); if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { stop(); emit('busy', false) })
</script>

<template>
  <section class="archive" aria-label="本轮报销归档">
    <div class="archive-heading"><h3>第 {{ roundNo }} 轮 · 电子档案</h3><button type="button" class="quiet" :disabled="loading || downloading || locked" @click="load">刷新归档状态</button></div>
    <p class="archive-help">本轮报销、审批轨迹、核减记录、会计凭证、付款回单及发票原文件一起保留。资料齐全后由后台完成归档。</p>
    <p v-if="loading" class="archive-help" role="status">正在读取本轮归档进度…</p><p v-if="error" class="archive-error" role="alert">{{ error }}</p>
    <template v-if="view">
      <strong class="archive-status" :class="{ complete: view.status === 'ARCHIVED' }">{{ archiveLabels[view.status] }}</strong>
      <p v-if="view.issue" :class="view.status === 'WAITING' ? 'archive-help' : 'archive-error'" role="status"><template v-if="view.status === 'ARCHIVED'">归档后的财务事实需要核对，原档案保持不变。<br></template>{{ archiveIssue(view.issue) }}</p>
      <template v-if="view.status === 'ARCHIVED'">
        <dl class="archive-facts"><div><dt>归档时间</dt><dd>{{ new Date(view.archivedAt!).toLocaleString('zh-CN') }}</dd></div><div><dt>资料数量</dt><dd>{{ view.originalCount }} 份原文件 · {{ view.voucherCount }} 份凭证</dd></div></dl>
        <p class="archive-help">下载包包含清单校验文件，可核对保存的原始资料。后续争议请结合当前财务状态处理。</p>
        <button class="primary" type="button" :disabled="loading || downloading || locked" @click="download">{{ downloading ? '正在下载档案…' : '下载本轮档案包' }}</button>
      </template>
    </template>
  </section>
</template>
<style scoped>
.archive{margin-top:24px;padding-top:20px;border-top:1px solid var(--line)}.archive-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.archive-heading h3{font-size:15px;margin:0}.archive-help,.archive-error{font-size:12px;line-height:1.8}.archive-help{color:var(--muted)}.archive-error{color:#9d3d2b;background:#fff0ed;border-radius:8px;padding:12px}.archive-status{display:block;font-size:14px;margin:16px 0}.archive-status.complete{color:var(--teal)}.archive-facts{display:grid;grid-template-columns:1fr 1fr;gap:10px}.archive-facts div{border:1px solid var(--line);border-radius:9px;background:var(--paper);padding:14px}.archive-facts dt{font-size:11px;color:var(--muted)}.archive-facts dd{font-size:12px;line-height:1.7;margin:8px 0 0;overflow-wrap:anywhere}.archive button{min-height:40px}@media(max-width:600px){.archive-facts{grid-template-columns:1fr}}
</style>
