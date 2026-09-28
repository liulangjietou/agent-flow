<script setup lang="ts">
import type { AdvanceContent, AdvanceRound } from '../advanceRequest'
import { moneyLabel } from '../expenses'
defineProps<{ content: AdvanceContent; financial?: AdvanceRound | null }>()
</script>

<template>
  <div class="advance-terms" aria-label="借款约定">
    <dl class="terms-facts">
      <div><dt>借款金额</dt><dd class="terms-amount">{{ moneyLabel(content.amount) }}</dd></div>
      <div><dt>归还日期</dt><dd>{{ content.dueOn }}</dd></div>
      <div v-if="financial"><dt>借款法人</dt><dd>{{ financial.legalEntity.name }}</dd></div>
      <div v-if="financial"><dt>本轮本人收款账户</dt><dd>{{ financial.maskedAccount }}</dd></div>
    </dl>
    <div class="terms-purpose"><h4>借款用途</h4><p>{{ content.purpose }}</p></div>
    <p v-if="financial" class="terms-note">归还日按 {{ financial.legalEntity.timeZone }} 核对。收款账户来自本人财务主数据；需要更换时，先维护账户，再补正并重新提交审批。</p>
  </div>
</template>

<style scoped>
.advance-terms{margin:18px 0;border:1px solid var(--line);border-radius:12px;overflow:hidden}.terms-facts{display:grid;grid-template-columns:1fr 1fr;margin:0;background:var(--paper)}.terms-facts>div{padding:18px;min-width:0}.terms-facts dt,.terms-purpose h4{font-size:11px;color:var(--muted);font-weight:400;margin:0 0 9px}.terms-facts dd{font-size:13px;margin:0;line-height:1.8;overflow-wrap:anywhere}.terms-facts .terms-amount{font:500 18px 'DM Mono',monospace;color:var(--deep)}.terms-purpose{padding:18px;border-top:1px solid var(--line)}.terms-purpose p{font-size:13px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere;margin:0}.terms-note{font-size:11px;line-height:1.9;color:var(--muted);margin:0;padding:0 18px 18px}@media(max-width:550px){.terms-facts{grid-template-columns:1fr}.terms-facts>div{padding:14px 18px}}
</style>
