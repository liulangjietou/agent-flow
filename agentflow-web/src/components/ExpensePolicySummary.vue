<script setup lang="ts">
import { expenseUnits, type ExpensePolicyDefinition } from '../expenseConfiguration'
defineProps<{ definition: ExpensePolicyDefinition }>()
</script>
<template>
  <section class="policy-summary" aria-label="制度正文">
    <h4>{{ definition.name }}</h4>
    <p v-if="!definition.rules.length">本修订没有规则。</p>
    <article v-for="(rule, index) in definition.rules" :key="rule.key">
      <h5>{{ index + 1 }}. {{ rule.name }} <small>{{ rule.key }}</small></h5>
      <dl>
        <div><dt>法人</dt><dd>{{ rule.match.legalEntityIds.join('、') || '不限' }}</dd></div>
        <div><dt>类别</dt><dd>{{ rule.match.categoryCodes.join('、') || '不限' }}</dd></div>
        <div><dt>城市等级</dt><dd>{{ rule.match.cityTiers.join('、') || '不限' }}</dd></div>
        <div><dt>员工职级</dt><dd>{{ rule.match.employeeGrades.join('、') || '不限' }}</dd></div>
        <div><dt>发生日期</dt><dd>{{ rule.match.fromDate || '无起始限制' }} 至 {{ rule.match.throughDate || '无截止限制' }}（含当天）</dd></div>
        <div><dt>原币币种</dt><dd>{{ rule.match.currency || '不限' }}</dd></div>
        <div><dt>结论</dt><dd>{{ rule.constraints.effect === 'DENY' ? '禁止报销' : '允许，按约束执行' }}</dd></div>
        <template v-if="rule.constraints.effect === 'ALLOW'">
          <div v-if="rule.constraints.fixedAllowance"><dt>定额补贴</dt><dd>{{ rule.constraints.fixedAllowance.dailyRate.currency }} {{ rule.constraints.fixedAllowance.dailyRate.value }} / 天；自然日含起止日，同日计一天；金额自动计算，不关联发票。</dd></div>
          <div><dt>单价限额</dt><dd>{{ rule.constraints.unitPriceLimit ? `${rule.constraints.unitPriceLimit.currency} ${rule.constraints.unitPriceLimit.value} / ${expenseUnits[rule.constraints.limitUnit!]}` : '未限制' }}</dd></div>
          <div><dt>票据时限</dt><dd>{{ rule.constraints.invoiceMaxAgeDays === null ? '未限制' : `${rule.constraints.invoiceMaxAgeDays} 天；超出时${rule.constraints.invoiceAgeAction === 'REJECT' ? '拒绝报销' : '要求例外说明'}` }}</dd></div>
          <div><dt>舱位或等级</dt><dd>{{ rule.constraints.allowedServiceLevels.join('、') || '不限' }}</dd></div>
          <div><dt>事前申请</dt><dd>{{ rule.constraints.priorRequestRequired ? '必须关联' : '本规则不要求' }}</dd></div>
        </template>
      </dl>
    </article>
  </section>
</template>
<style scoped>
.policy-summary h4{margin:14px 0;font-size:16px}.policy-summary article{border-top:1px solid var(--line);padding:12px 0}.policy-summary h5{margin:0 0 10px;font-size:13px}.policy-summary small{color:var(--muted);font-weight:400;margin-left:8px}.policy-summary dl{margin:0;display:grid;gap:6px;font-size:12px}.policy-summary dl>div{display:grid;grid-template-columns:105px minmax(0,1fr);gap:10px}.policy-summary dt{color:var(--muted)}.policy-summary dd{margin:0;overflow-wrap:anywhere}@media(max-width:500px){.policy-summary dl>div{grid-template-columns:85px minmax(0,1fr)}}
</style>
