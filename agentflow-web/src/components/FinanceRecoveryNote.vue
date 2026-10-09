<script setup lang="ts">
import type { FinanceRecoveryAdvice } from '../financeRecovery'
defineProps<{ advice: FinanceRecoveryAdvice | null }>()
</script>
<template><aside v-if="advice" class="recovery" aria-label="财务恢复建议"><strong>按原记录继续处理</strong><p>{{ advice.reason }}</p><p>{{ advice.next }}</p><small v-if="advice.originalId">原记录 {{ advice.originalId }}</small></aside></template>
<style scoped>.recovery{padding:14px 16px;margin:12px 0;background:var(--paper);border-left:3px solid var(--amber);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.recovery p{margin:6px 0}.recovery small{color:var(--muted)}</style>
