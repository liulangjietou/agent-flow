<script setup lang="ts">
import type { PendingWrite } from '../pendingWrites.js'

defineProps<{ pending: PendingWrite[]; error?: string }>()
const emit = defineEmits<{ recover: [id: string] }>()
</script>

<template>
  <section v-if="pending.length || error" class="request-recovery" aria-label="恢复待确认操作" aria-live="polite">
    <p v-if="error" role="alert">{{ error }}</p>
    <strong v-if="pending.length">上次操作的结果尚未确认</strong>
    <p v-if="pending.length">请先确认原操作，再继续修改。恢复只核对并重试原操作，不会自动继续提交或批准。</p>
    <div v-for="operation in pending" :key="operation.id" class="recovery-operation">
      <span>{{ operation.label }}</span><button type="button" class="secondary" :disabled="operation.sending" @click="emit('recover', operation.id)">{{ operation.sending ? '正在确认…' : '恢复上次操作' }}</button>
    </div>
    <small v-if="pending.length">恢复记录只保留在当前页面。刷新或关闭页面后，请先查询业务现态，确认后再发起新操作。</small>
  </section>
</template>

<style scoped>
.request-recovery{margin:16px 0;padding:15px 18px;border:1px solid #e9c890;border-radius:10px;background:#fff9ef;color:var(--ink);font-size:12px;line-height:1.7}
.request-recovery>strong{font-size:13px}.request-recovery p{margin:6px 0 10px}.request-recovery small{display:block;color:var(--muted);margin-top:10px}
.recovery-operation{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.recovery-operation+.recovery-operation{margin-top:8px}
@media(max-width:650px){.request-recovery{padding:12px}.recovery-operation button{width:100%}}
</style>
