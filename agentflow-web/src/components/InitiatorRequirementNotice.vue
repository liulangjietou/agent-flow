<script setup lang="ts">
defineProps<{ state: { required: boolean | null; loading: boolean; error: string }; disabled?: boolean }>()
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="initiator-requirement" aria-label="发起任职要求" aria-live="polite">
    <p v-if="state.loading" role="status">正在核对当前版本及子流程的发起任职要求…</p>
    <p v-else-if="state.error" role="alert">{{ state.error }} <button type="button" class="quiet" :disabled="disabled" @click="emit('retry')">重试读取要求</button></p>
    <p v-else-if="state.required === true">此流程或其子流程的审批／抄送使用动态选人，提交前必须明确选择本次发起任职。</p>
    <p v-else-if="state.required === false">该版本及其子流程的审批与抄送规则未要求发起任职。</p>
    <p v-else>尚未取得当前版本的发起任职要求。</p>
  </div>
</template>

<style scoped>
.initiator-requirement{font-size:12px;line-height:1.8;overflow-wrap:anywhere}.initiator-requirement p{margin:8px 0;color:var(--muted)}.initiator-requirement [role=alert]{color:var(--red)}.initiator-requirement button{margin:4px 0}
</style>
