<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { taskDeadlineState } from '../taskDeadline'

const props = defineProps<{ dueAt?: string | null }>()
const now = ref(Date.now())
const REFRESH_INTERVAL_MS = 30_000
const state = computed(() => taskDeadlineState(props.dueAt, now.value))
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, REFRESH_INTERVAL_MS) })
onUnmounted(() => clearInterval(timer))
const dateLabel = computed(() => props.dueAt ? new Date(props.dueAt).toLocaleString('zh-CN', { hour12: false }) : '')
</script>

<template>
  <span class="task-deadline" :class="{ overdue: state === 'overdue' }">
    <template v-if="state === 'pending' || state === 'overdue'">
      <strong>{{ state === 'overdue' ? '已超时' : '处理期限' }}</strong>
      <time :datetime="dueAt!">{{ dateLabel }}</time>
    </template>
    <template v-else>{{ state === 'unrecorded' ? '未记录期限' : '期限状态待刷新' }}</template>
  </span>
</template>

<style scoped>
.task-deadline{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap;color:var(--muted);font-size:11px;line-height:1.7}
.task-deadline strong{font-weight:500}.task-deadline.overdue{color:#a34736}.task-deadline.overdue strong{font-weight:700}
</style>
