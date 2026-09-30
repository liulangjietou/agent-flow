<script setup lang="ts">
import { budgetAdjustmentTypes, type BudgetAdjustmentContent, type BudgetAdjustmentRound } from '../budgetAdjustment'
import { moneyLabel } from '../expenses'
defineProps<{ content: BudgetAdjustmentContent; financial?: BudgetAdjustmentRound | null }>()
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <div class="budget-terms" aria-label="预算调整依据">
    <dl class="terms-facts">
      <div><dt>调整类型</dt><dd>{{ budgetAdjustmentTypes[content.type] }}</dd></div>
      <div><dt>调整金额</dt><dd class="terms-amount">{{ moneyLabel(content.amount) }}</dd></div>
      <div><dt>调整日期</dt><dd>{{ content.accountingDate }}</dd></div>
      <div v-if="financial"><dt>预算法人</dt><dd>{{ financial.legalEntity.name }}</dd></div>
      <div v-if="content.sourceBudgetReference"><dt>调出预算编号</dt><dd>{{ content.sourceBudgetReference }}</dd></div>
      <div v-if="content.targetBudgetReference"><dt>{{ content.type === 'INCREASE' ? '追加预算编号' : '调入预算编号' }}</dt><dd>{{ content.targetBudgetReference }}</dd></div>
    </dl>
    <div class="terms-purpose"><h4>调整原因</h4><p>{{ content.purpose }}</p></div>
    <template v-if="financial">
      <section class="budget-positions" aria-label="原预算与拟调整额度">
        <h4>原预算与拟调整额度</h4>
        <article v-for="position in financial.positions" :key="position.budgetReference" class="budget-position">
          <div class="position-heading"><div><strong>{{ position.name }}</strong><small>{{ position.budgetReference }}</small></div><span>{{ position.budgetReference === content.sourceBudgetReference ? '调出' : '调入' }}</span></div>
          <dl class="limit-comparison">
            <div><dt>原预算额度</dt><dd>{{ moneyLabel(position.beforeLimit) }}</dd></div>
            <div><dt>拟调整后额度</dt><dd class="proposed">{{ moneyLabel(position.proposedLimit) }}</dd></div>
          </dl>
          <dl class="position-balances">
            <div><dt>已占用</dt><dd>{{ moneyLabel(position.committed) }}</dd></div>
            <div><dt>已使用</dt><dd>{{ moneyLabel(position.consumed) }}</dd></div>
            <div><dt>查询时可用</dt><dd>{{ moneyLabel(position.available) }}</dd></div>
          </dl>
          <p class="position-period">预算期间 {{ position.periodReference }} · {{ position.periodStart }} 至 {{ position.periodEnd }} · {{ position.periodStatus === 'OPEN' ? '开放' : '关闭' }}</p>
        </article>
      </section>
      <p class="terms-note">台账版本 {{ financial.ledgerVersion }} · 查询于 {{ dateLabel(financial.observedAt) }}。以上是本轮审批依据，拟调整额度尚未实际生效，执行前仍需重新核对台账。</p>
    </template>
  </div>
</template>

<style scoped>
.budget-terms{margin:18px 0;border:1px solid var(--line);border-radius:12px;overflow:hidden;min-width:0}.terms-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));margin:0;background:var(--paper)}.terms-facts>div{padding:18px;min-width:0}.budget-terms dt,.budget-terms h4{font-size:11px;color:var(--muted);font-weight:400;margin:0 0 9px}.budget-terms dd{font-size:13px;margin:0;line-height:1.8;overflow-wrap:anywhere}.terms-facts .terms-amount{font:500 18px 'DM Mono',monospace;color:var(--deep)}.terms-purpose,.budget-positions{padding:18px;border-top:1px solid var(--line)}.terms-purpose p{font-size:13px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere;margin:0}.budget-position{border:1px solid var(--line);border-radius:8px;overflow:hidden;margin-top:14px}.position-heading{display:flex;justify-content:space-between;gap:10px;padding:14px;background:var(--paper);font-size:12px;line-height:1.7}.position-heading>div{min-width:0;overflow-wrap:anywhere}.position-heading small{display:block;color:var(--muted);font-size:11px;margin-top:5px}.position-heading>span{flex-shrink:0;color:var(--deep)}.limit-comparison,.position-balances{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));margin:0;gap:16px;padding:18px}.limit-comparison{background:#f1faf7}.limit-comparison dd,.position-balances dd{font:12px 'DM Mono',monospace;line-height:1.8}.limit-comparison .proposed{color:var(--deep);font-weight:500}.position-balances{grid-template-columns:repeat(3,minmax(0,1fr))}.position-balances>div,.limit-comparison>div{min-width:0}.position-period,.terms-note{font-size:11px;line-height:1.9;color:var(--muted);margin:0;padding:0 18px 18px;overflow-wrap:anywhere}@media(max-width:550px){.terms-facts,.position-balances,.limit-comparison{grid-template-columns:1fr}.terms-facts>div{padding:14px 18px}}
</style>
