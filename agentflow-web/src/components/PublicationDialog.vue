<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps<{ name: string; processKey: string; note: string; busy: boolean; blocked: boolean; error: string; returnFocus: HTMLElement | null; fallbackFocus: HTMLElement | null }>()
const emit = defineEmits<{ 'update:note': [value: string]; submit: []; close: [] }>()
const dialog = ref<HTMLElement | null>(null)
const noteInput = ref<HTMLTextAreaElement | null>(null)
let previousOverflow = ''
function close() { if (!props.busy) emit('close') }
function keyHandler(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), textarea:not(:disabled)')]
  const current = controls.findIndex(control => control === document.activeElement)
  const next = event.shiftKey ? (current <= 0 ? controls.length - 1 : current - 1) : (current + 1) % controls.length
  event.preventDefault()
  ;(controls[next] ?? dialog.value).focus()
}
function containFocus(event: FocusEvent) {
  if (dialog.value && !dialog.value.contains(event.target as Node)) {
    if (!props.busy && !props.blocked) noteInput.value?.focus()
    else dialog.value.focus()
  }
}
onMounted(() => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  document.addEventListener('focusin', containFocus, true)
  noteInput.value?.focus()
})
onBeforeUnmount(() => { document.removeEventListener('focusin', containFocus, true); document.body.style.overflow = previousOverflow })
onUnmounted(async () => {
  await nextTick()
  const target = props.returnFocus
  if (target?.isConnected && !target.closest('[inert]') && !target.matches(':disabled')) target.focus()
  else if (props.fallbackFocus?.isConnected && !props.fallbackFocus.closest('[inert]')) props.fallbackFocus.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop publication-backdrop" @click.self="close">
      <section ref="dialog" class="modal publication-dialog" role="dialog" aria-modal="true" aria-labelledby="publication-title" aria-describedby="publication-description" :aria-busy="busy" tabindex="-1" @keydown.stop="keyHandler">
        <p class="eyebrow">PUBLISH PROCESS</p><h2 id="publication-title">发布流程</h2>
        <p class="publication-process">{{ name }}<small>{{ processKey || '请先填写流程标识' }}</small></p>
        <p id="publication-description" class="publication-description">发布前会保存并校验当前设计。新版本发布后只读，已有审批实例继续使用原版本。</p>
        <form @submit.prevent="emit('submit')">
          <label for="publication-note">变更说明 <span>必填</span></label>
          <textarea id="publication-note" ref="noteInput" :value="note" :disabled="busy || blocked" maxlength="2000" required rows="5" placeholder="说明此次发布的目的、审批规则或表单变化，便于后续核对。" @input="emit('update:note', ($event.target as HTMLTextAreaElement).value)" />
          <p class="publication-count">{{ note.length }} / 2000</p>
          <p v-if="error" class="publication-error" role="alert">{{ error }}</p>
          <p v-if="blocked && !busy" class="publication-error">上次操作结果待确认。关闭此窗口后，请通过“恢复上次操作”查询结果，原发布说明会随请求保留。</p>
          <div class="publication-actions"><button type="button" class="secondary" :disabled="busy" @click="close">{{ blocked ? '关闭并恢复操作' : '返回编辑' }}</button><button type="submit" class="primary" :disabled="busy || blocked || !note.trim()">{{ busy ? '正在保存并发布…' : '确认发布' }}</button></div>
        </form>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.publication-backdrop { z-index: 40; }
.publication-dialog { width: min(550px, 100%); padding: 28px; }
.publication-dialog h2 { font-size: 24px; margin: 0; }
.publication-process { font-size: 15px; font-weight: 600; overflow-wrap: anywhere; }
.publication-process small { display: block; color: var(--muted); font-size: 11px; margin-top: 6px; font-weight: 400; }
.publication-description { color: var(--muted); font-size: 13px; line-height: 1.9; }
.publication-dialog label { display: flex; justify-content: space-between; margin-top: 22px; font-size: 13px; }
.publication-dialog label span, .publication-count { color: var(--muted); font-size: 11px; }
.publication-dialog textarea { margin-top: 10px; resize: vertical; width: 100%; line-height: 1.7; }
.publication-count { text-align: right; margin: 5px 0 20px; }
.publication-error { font-size: 13px; line-height: 1.8; color: #973c32; }
.publication-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 10px; }
@media (max-width: 500px) { .publication-dialog { padding: 23px 20px; } .publication-actions { flex-direction: column; } .publication-actions button { width: 100%; } }
</style>
