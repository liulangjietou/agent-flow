<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, onUnmounted, ref } from 'vue'
import type { ConfirmationRequest } from '../unsavedConfirmation'

const props = defineProps<{ request: ConfirmationRequest; returnFocus: HTMLElement | null; fallbackFocus: HTMLElement | null }>()
const emit = defineEmits<{ answer: [id: number, accepted: boolean] }>()
const dialog = ref<HTMLElement | null>(null)
const cancelButton = ref<HTMLButtonElement | null>(null)
const titleId = `unsaved-confirmation-${props.request.id}-title`
const descriptionId = `unsaved-confirmation-${props.request.id}-description`
let previousOverflow = ''

function answer(accepted: boolean) { emit('answer', props.request.id, accepted) }
function keyHandler(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); answer(false); return }
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
  // 外部移除组件时也不能留下永远等待的操作；已完成的答复由请求编号忽略。
  answer(false)
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
    <div class="modal-backdrop confirmation-backdrop" @click.self="answer(false)">
      <section ref="dialog" class="modal unsaved-confirmation" role="dialog" aria-modal="true" :aria-labelledby="titleId" :aria-describedby="descriptionId" tabindex="-1" @keydown.stop="keyHandler">
        <p class="eyebrow">UNSAVED CHANGES</p>
        <h2 :id="titleId">{{ request.title ?? '有未保存的流程修改' }}</h2>
        <p :id="descriptionId" class="confirmation-description">{{ request.description ?? '继续操作会放弃设计器中未保存的修改。选择“继续编辑”可保留当前内容。' }}</p>
        <div class="confirmation-actions">
          <button ref="cancelButton" class="secondary" type="button" @click="answer(false)">继续编辑</button>
          <button class="primary" type="button" @click="answer(true)">{{ request.confirmLabel }}</button>
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
