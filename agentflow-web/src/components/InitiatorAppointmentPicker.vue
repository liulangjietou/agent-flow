<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { initiatorContextLabel, type InitiatorContext } from '../initiatorContext'

const props = defineProps<{ modelValue: string; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const choices = ref<InitiatorContext[]>([]), next = ref<string | null>(), loading = ref(false), error = ref('')
const READ_TIMEOUT_MS = 12_000
let generation = 0, controller: AbortController | null = null

async function load(more = false) {
  controller?.abort()
  if (!props.scopeKey) { generation++; choices.value = []; next.value = null; loading.value = false; error.value = ''; return }
  const version = ++generation, request = new AbortController()
  controller = request; loading.value = true; error.value = ''
  if (!more) { choices.value = []; next.value = null }
  const timeout = setTimeout(() => request.abort(), READ_TIMEOUT_MS)
  try {
    const page = await api.myAppointments(more ? next.value ?? undefined : undefined, request.signal)
    if (version !== generation) return
    choices.value = more ? [...choices.value, ...page.items] : page.items
    next.value = page.nextAfterId
  } catch (cause) {
    if (version === generation) error.value = (cause as ApiError).message ?? '任职读取失败，请重试。'
  } finally { clearTimeout(timeout); if (version === generation) loading.value = false }
}
watch(() => props.scopeKey, () => { emit('update:modelValue', ''); void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => { generation++; controller?.abort() })
</script>

<template>
  <div class="initiator-appointment">
    <label>本次发起任职
      <select :value="modelValue" :disabled="disabled || loading" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
        <option value="">请选择本次任职；静态选人流程可不选</option>
        <option v-if="modelValue && !choices.some(value => value.appointmentId === modelValue)" :value="modelValue">原选择尚未载入，请刷新核对</option>
        <option v-for="choice in choices" :key="choice.appointmentId" :value="choice.appointmentId">{{ initiatorContextLabel(choice) }}</option>
      </select>
    </label>
    <p v-if="loading" role="status">正在读取本人有效任职…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!choices.length">暂无有效任职。主管或部门负责人审批需先由管理员维护本人任职。</p>
    <p v-else>所选任职在提交时固定到本轮；退回或撤回后重提可以重新选择，历史记录保留。</p>
    <button type="button" class="secondary" :disabled="disabled || loading" @click="load()">{{ error ? '重试读取任职' : '刷新任职' }}</button>
    <button v-if="next" type="button" class="secondary" :disabled="disabled || loading" @click="load(true)">更多任职</button>
  </div>
</template>

<style scoped>
.initiator-appointment{margin:16px 0}.initiator-appointment label{display:grid;gap:8px}.initiator-appointment select{width:100%;min-width:0;padding:10px;border:1px solid var(--line);border-radius:6px;background:#fff;font:inherit}.initiator-appointment p{font-size:12px;line-height:1.7;color:var(--muted)}.initiator-appointment [role=alert]{color:var(--red)}.initiator-appointment button{margin-right:8px}
</style>
