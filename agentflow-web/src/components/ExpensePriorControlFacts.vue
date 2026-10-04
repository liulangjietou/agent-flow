<script setup lang="ts">
import { moneyLabel } from '../expenses'
import { priorControlLabel, priorReviewRequired, type PriorAssessment } from '../expensePriorControl'
defineProps<{ assessments: PriorAssessment[] }>()
</script>

<template>
  <div class="prior-facts">
    <p v-if="!assessments.length">本轮没有使用事前批准额度。</p>
    <article v-for="line in assessments" :key="line.lineNo">
      <h5>费用第 {{ line.lineNo }} 行 · {{ priorControlLabel(line.source.control?.control) }}</h5>
      <p>来源 {{ line.requestId }} · 批准第 {{ line.source.lineNo }} 行 · 资源版本 {{ line.requestVersion }}</p>
      <p v-if="line.source.control">{{ line.source.control.categoryCode }} · 类别修订 {{ line.source.control.categoryRevision }}</p>
      <dl>
        <div><dt>{{ line.source.control?.control.mode === 'NONE' ? '参考金额' : '累计阈值' }}</dt><dd>{{ moneyLabel(line.threshold) }}</dd></div>
        <div><dt>已核销</dt><dd>{{ moneyLabel(line.consumed) }}</dd></div>
        <div><dt>其他有效占用</dt><dd>{{ moneyLabel(line.otherReserved) }}</dd></div>
        <div><dt>本轮合计占用</dt><dd>{{ moneyLabel(line.roundReserved) }}</dd></div>
        <div><dt>本行使用</dt><dd>{{ moneyLabel(line.lineAmount) }}</dd></div>
        <div><dt>提交后累计</dt><dd>{{ moneyLabel(line.totalExposure) }}</dd></div>
        <div><dt>超出金额</dt><dd>{{ moneyLabel(line.exceeded) }}</dd></div>
      </dl>
      <p v-if="priorReviewRequired(line)" class="prior-exception">本行需要超额说明，并须完成独立的事前额度例外审批。</p>
      <p v-else-if="line.source.control?.control.mode === 'NONE'">超过参考金额仍记录真实用量，不按额度阻断。</p>
    </article>
  </div>
</template>

<style scoped>
.prior-facts article{border:1px solid var(--line);border-radius:8px;padding:14px;margin:12px 0}.prior-facts h5{font-size:13px;margin:0 0 9px}.prior-facts p{font-size:12px;line-height:1.8;color:var(--muted);overflow-wrap:anywhere}.prior-facts dl{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;font-size:12px}.prior-facts dt{color:var(--muted);margin-bottom:5px}.prior-facts dd{margin:0;overflow-wrap:anywhere}.prior-facts .prior-exception{color:var(--red)}@media(max-width:650px){.prior-facts dl{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
