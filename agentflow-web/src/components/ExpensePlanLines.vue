<script setup lang="ts">
import { moneyLabel } from '../expenses'
import { priorControlLabel } from '../expensePriorControl'
import type { PlanContent, PlanRound } from '../expensePlan'
defineProps<{ content: PlanContent; financial?: PlanRound | null }>()
</script>

<template>
  <div class="plan-lines" aria-label="计划明细行">
    <p v-if="financial?.managedCategoryRevision != null" class="category-revision">本轮使用平台费用类别修订 {{ financial.managedCategoryRevision }}</p>
    <p v-if="!content.lines.length" class="empty-lines">尚未填写计划明细。</p>
    <article v-for="line in content.lines" :key="line.lineNo" class="plan-line">
      <div class="line-heading"><span class="line-number">{{ line.lineNo }}</span><div><h4>{{ line.description }}</h4><p>{{ line.categoryCode }} · {{ line.plannedOn }}<template v-if="line.endedOn"> 至 {{ line.endedOn }}</template> · {{ line.cityCode }}</p></div><strong>{{ moneyLabel(line.amount) }}</strong></div>
      <details class="line-evidence"><summary>查看成本归属与折算依据</summary><div class="evidence-body">
        <h5>原币成本分摊</h5><ul><li v-for="allocation in line.allocations" :key="JSON.stringify([allocation.costCenter, allocation.projectCode])">{{ allocation.costCenter }}<template v-if="allocation.projectCode"> / {{ allocation.projectCode }}</template> · {{ moneyLabel(allocation.amount) }}</li></ul>
        <template v-for="frozen in financial?.lines.filter(value => value.original.lineNo === line.lineNo) ?? []" :key="frozen.original.lineNo">
          <p>本轮事前控制：{{ priorControlLabel(frozen.priorControl?.control) }}<template v-if="frozen.priorControl"> · {{ frozen.priorControl.categoryCode }} · 类别修订 {{ frozen.priorControl.categoryRevision }}</template></p>
          <p>本轮折算金额 <strong>{{ moneyLabel(frozen.amount) }}</strong></p><p>汇率 {{ frozen.rate.fromCurrency }} → {{ frozen.rate.toCurrency }}：{{ frozen.rate.rate }} · {{ frozen.rate.rateDate }} · {{ frozen.rate.source }}</p>
          <h5>本位币成本分摊</h5><ul><li v-for="allocation in frozen.allocations" :key="JSON.stringify([allocation.costCenter, allocation.projectCode])">{{ allocation.costCenter }}<template v-if="allocation.projectCode"> / {{ allocation.projectCode }}</template> · {{ moneyLabel(allocation.amount) }}</li></ul>
        </template>
      </div></details>
    </article>
  </div>
</template>

<style scoped>
.category-revision{font-size:12px;line-height:1.8;color:var(--muted);overflow-wrap:anywhere}
.plan-line{padding:20px 0;border-bottom:1px solid var(--line)}.line-heading{display:flex;align-items:flex-start;gap:10px}.line-number{flex-shrink:0;width:25px;height:25px;border-radius:7px;display:grid;place-items:center;font:11px 'DM Mono',monospace;background:var(--paper);color:var(--muted)}.line-heading>div{min-width:0}.line-heading h4{margin:3px 0 7px;font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere}.line-heading p{margin:0;color:var(--muted);font-size:11px;line-height:1.7}.line-heading>strong{font:12px 'DM Mono',monospace;margin:5px 0 0 auto;white-space:nowrap}.line-evidence{font-size:11px;line-height:1.9;color:var(--muted);margin-top:14px}.line-evidence summary{cursor:pointer;color:var(--deep);padding:5px 0}.line-evidence summary:focus-visible{outline:3px solid var(--teal);outline-offset:2px}.evidence-body{padding:8px 14px;background:var(--paper);border-radius:8px}.line-evidence p,.line-evidence li{overflow-wrap:anywhere}.line-evidence h5{font-size:11px;margin-bottom:5px}.line-evidence ul{padding-left:18px}.empty-lines{font-size:12px;color:var(--muted)}@media(max-width:600px){.line-heading{flex-wrap:wrap}.line-heading>div{flex:1}.line-heading>strong{width:100%;margin-left:35px}}
</style>
