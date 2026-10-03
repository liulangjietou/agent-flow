<script setup lang="ts">
import type { SubmissionRisk } from '../api'
import { riskLabels } from '../submissionRisk'
defineProps<{ risk?: SubmissionRisk | null; compact?: boolean }>()
</script>

<template>
  <span class="submission-risk" :class="[risk?.level?.toLowerCase() ?? 'unassessed', { compact }]">
    <strong>提交时风险：{{ riskLabels[risk?.level ?? 'UNASSESSED'] }}</strong>
    <span v-if="risk?.matches.length" class="risk-matches">{{ risk.matches.map(match => match.label).join('、') }}</span>
    <small v-if="!compact">{{ risk && risk.level !== 'UNASSESSED' ? `依据本轮流程 v${risk.definitionVersion} 的风险规则；后续修改不覆盖此结果。` : '未配置规则或本轮没有风险记录，不能据此判断为低风险。' }}</small>
  </span>
</template>

<style scoped>
.submission-risk{display:flex;flex-direction:column;gap:5px;font-size:12px;line-height:1.7;overflow-wrap:anywhere;color:var(--ink);padding:9px 12px;background:var(--paper);border-radius:6px}.submission-risk strong{font-weight:600}.submission-risk small{color:var(--muted);font-size:11px}.submission-risk.high>strong{color:var(--red)}.submission-risk.medium>strong{color:#8a570f}.submission-risk.low>strong{color:var(--deep)}.submission-risk.unassessed>strong,.submission-risk.unmatched>strong{color:var(--muted)}.submission-risk.compact{padding:0;background:transparent;font-size:11px}.risk-matches{font-weight:400;color:var(--muted)}
</style>
