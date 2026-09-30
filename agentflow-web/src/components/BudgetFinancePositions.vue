<script setup lang="ts">
import type { BudgetAdjustmentPosition } from '../budgetAdjustment'
defineProps<{ positions: BudgetAdjustmentPosition[]; label: string }>()
</script>

<template>
  <div class="budget-position-scroll" tabindex="0" :aria-label="label">
    <table><caption>{{ label }}</caption><thead><tr><th scope="col">原预算项</th><th scope="col">调整前额度</th><th scope="col">已占用</th><th scope="col">已使用</th><th scope="col">调整前可用</th><th scope="col">本次目标额度</th></tr></thead>
      <tbody><tr v-for="position in positions" :key="position.budgetReference"><th scope="row"><strong>{{ position.name }}</strong><span>{{ position.budgetReference }}</span><span>{{ position.periodReference }} · {{ position.beforeLimit.currency }}</span></th><td>{{ position.beforeLimit.value }}</td><td>{{ position.committed.value }}</td><td>{{ position.consumed.value }}</td><td>{{ position.available.value }}</td><td class="budget-target">{{ position.proposedLimit.value }}</td></tr></tbody>
    </table>
  </div>
</template>

<style scoped>
.budget-position-scroll{max-width:100%;overflow:auto;margin:12px 0;border:1px solid var(--line);border-radius:8px}table{width:100%;min-width:680px;border-collapse:collapse;font-size:12px}caption{text-align:left;padding:12px;color:var(--muted);font-size:11px}th,td{text-align:right;vertical-align:top;padding:12px;border-top:1px solid var(--line);font-variant-numeric:tabular-nums;white-space:nowrap}thead th{font-weight:500;color:var(--muted);font-size:11px}th:first-child{text-align:left;white-space:normal;min-width:160px;max-width:260px}tbody th{font-weight:400;overflow-wrap:anywhere}tbody th span{display:block;margin-top:5px;color:var(--muted);font-size:11px}.budget-target{font-weight:600;color:var(--ink)}
</style>
