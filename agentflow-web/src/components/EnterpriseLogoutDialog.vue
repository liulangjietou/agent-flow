<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps<{ returnFocus: HTMLElement | null; fallbackFocus: HTMLElement | null }>()
const emit = defineEmits<{ choose: [scope: 'local' | 'provider']; close: [] }>()
const dialog = ref<HTMLElement | null>(null)
const cancelButton = ref<HTMLButtonElement | null>(null)
const titleId = 'enterprise-logout-title'
const descriptionId = 'enterprise-logout-description'
let previousOverflow = ''

function close() { emit('close') }
function keyHandler(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')]
  const current = controls.findIndex(control => control === document.activeElement)
  const next = event.shiftKey ? (current <= 0 ? controls.length - 1 : current - 1) : (current + 1) % controls.length
  event.preventDefault()
  controls[next]?.focus()
}
function containFocus(event: FocusEvent) {
  if (dialog.value && !dialog.value.contains(event.target as Node)) cancelButton.value?.focus()
}
onMounted(() => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  document.addEventListener('focusin', containFocus, true)
  cancelButton.value?.focus()
})
onBeforeUnmount(() => {
  document.removeEventListener('focusin', containFocus, true)
  document.body.style.overflow = previousOverflow
})
onUnmounted(async () => {
  await nextTick()
  const target = props.returnFocus
  if (target?.isConnected && !target.closest('[inert]') && !target.matches(':disabled')) target.focus()
  else if (props.fallbackFocus?.isConnected && !props.fallbackFocus.closest('[inert]')) props.fallbackFocus.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop confirmation-backdrop" @click.self="close()">
      <section ref="dialog" class="modal unsaved-confirmation" role="dialog" aria-modal="true" :aria-labelledby="titleId" :aria-describedby="descriptionId" tabindex="-1" @keydown.stop="keyHandler">
        <p class="eyebrow">SIGN OUT</p>
        <h2 :id="titleId">选择退出范围</h2>
        <p :id="descriptionId" class="confirmation-description">仅退出平台会保留企业账号的登录状态。同时退出企业账号可能影响其他企业应用，并跳转到身份服务完成退出。页面中未保存的内容会随退出丢失。</p>
        <div class="confirmation-actions">
          <button ref="cancelButton" class="secondary" type="button" @click="close()">取消</button>
          <button class="secondary" type="button" @click="emit('choose', 'local')">仅退出平台</button>
          <button class="primary" type="button" @click="emit('choose', 'provider')">同时退出企业账号</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.confirmation-backdrop { z-index: 40; }
.unsaved-confirmation { width: min(470px, 100%); padding: 28px; }
.unsaved-confirmation h2 { margin: 0; font-size: 23px; color: var(--ink); }
.confirmation-description { margin: 16px 0 24px; font-size: 13px; line-height: 1.9; color: var(--muted); }
.confirmation-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.confirmation-actions button { min-height: 42px; }
@media (max-width: 500px) {
  .unsaved-confirmation { padding: 23px 20px; }
  .unsaved-confirmation h2 { font-size: 21px; }
  .confirmation-actions { flex-direction: column; }
  .confirmation-actions button { width: 100%; }
}
</style>
