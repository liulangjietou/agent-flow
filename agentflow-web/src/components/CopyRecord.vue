<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, type ApiError, type CopySnapshot } from '../api'
import FormFields from './FormFields.vue'
const props = defineProps<{ applicationId: string; roundNo: number; scopeKey: string }>()
const emit = defineEmits<{ close: [] }>()
const value = ref<CopySnapshot | null>(null)
const loading = ref(false)
const error = ref('')
const dialog = ref<HTMLElement | null>(null)
let generation = 0, controller: AbortController | null = null, returnFocus: HTMLElement | null = null
async function load() {
  const epoch = ++generation
  controller?.abort(); value.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const active = controller = new AbortController()
  const current = () => generation === epoch && !active.signal.aborted
  loading.value = true
  const timeout = setTimeout(() => {
    if (current()) { error.value = '抄送内容加载超时，请重试。'; loading.value = false }
    active.abort()
  }, 12_000)
  try {
    const snapshot = await api.copySnapshot(props.applicationId, props.roundNo, active.signal)
    if (current()) value.value = snapshot
  } catch (cause) { if (current()) error.value = (cause as ApiError).message ?? '当前无法读取这份抄送。' }
  finally { clearTimeout(timeout); if (current()) loading.value = false }
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); emit('close') }
  if (event.key !== 'Tab' || !dialog.value) return
  const items = [...dialog.value.querySelectorAll<HTMLElement>('button:not([disabled]), [tabindex="0"]')]
  const first = items[0], last = items[items.length - 1]
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch(() => [props.scopeKey, props.applicationId, props.roundNo], () => { void load() }, { immediate: true, flush: 'sync' })
onMounted(async () => { returnFocus = document.activeElement as HTMLElement; await nextTick(); dialog.value?.focus() })
onUnmounted(() => { generation++; controller?.abort(); if (returnFocus?.isConnected) returnFocus.focus() })
</script>
<template>
  <div class="copy-backdrop" @click.self="emit('close')">
    <section ref="dialog" class="copy-dialog" role="dialog" aria-modal="true" aria-labelledby="copy-title" tabindex="-1" @keydown="keydown">
      <header><div><p class="eyebrow">APPROVAL COPY</p><h2 id="copy-title">{{ value?.title ?? '审批抄送' }}</h2></div><button type="button" class="quiet" @click="emit('close')">关闭</button></header>
      <p class="copy-hint">这是第 {{ roundNo }} 轮提交时的只读快照。申请人之后修改的草稿不会显示在这里。</p>
      <p v-if="loading" role="status">正在读取抄送内容…</p>
      <div v-else-if="error" role="alert" class="copy-error"><p>{{ error }}</p><button type="button" class="secondary" @click="load">重新读取</button></div>
      <template v-else-if="value">
        <p class="copy-meta">{{ value.businessNo }} · {{ value.nodeNames.join('、') }} · {{ new Date(value.submittedAt).toLocaleString('zh-CN') }}</p>
        <FormFields :schema="value.formSchema" :model-value="value.payload" readonly :attachment-context="{ applicationId, roundNo, scopeKey, copy: true }" />
      </template>
    </section>
  </div>
</template>
<style scoped>
.copy-backdrop{position:fixed;inset:0;background:#122d2866;z-index:80;display:flex;align-items:center;justify-content:center;padding:24px}.copy-dialog{width:min(840px,100%);max-height:90vh;overflow:auto;background:var(--paper);padding:30px;border-radius:16px;box-shadow:0 24px 70px #09251f35}.copy-dialog header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.copy-dialog h2{margin:8px 0;font-size:24px;overflow-wrap:anywhere}.copy-hint,.copy-meta{font-size:12px;line-height:1.8;color:var(--muted)}.copy-hint{padding:14px;background:var(--soft);border-radius:8px}.copy-error{color:var(--red)}@media(max-width:600px){.copy-backdrop{padding:10px}.copy-dialog{padding:20px 16px;max-height:95vh}.copy-dialog h2{font-size:20px}}
</style>
