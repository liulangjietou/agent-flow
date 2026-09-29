<script setup lang="ts">
import { authorizationLabels, paymentRequestLabels, paymentOperationLabels, retirementBasisLabels, paymentIssue, type PaymentView } from '../payments'
defineProps<{ payment: PaymentView }>()
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <div class="payment-facts">
    <div class="payment-amount"><small>{{ payment.purpose === 'EMPLOYEE_ADVANCE' ? '员工借款放款' : '费用报销付款' }} · 第 {{ payment.roundNo }} 轮</small><strong>{{ payment.amount.currency }} {{ payment.amount.value }}</strong><span>收款人 {{ payment.employeeId }} · {{ payment.maskedPayeeAccount }}</span></div>
    <ol class="payment-stages">
      <li><small>01 / 财务授权</small><strong>{{ payment.status === 'AUTHORIZED' && Date.parse(payment.expiresAt) <= Date.now() ? '授权已到期，待关闭' : authorizationLabels[payment.status] }}</strong><p>{{ payment.authorizedBy }} · {{ date(payment.authorizedAt) }}</p></li>
      <li><small>02 / 出纳登记</small><strong>{{ payment.status === 'RETIRED' ? '原登记已结束' : payment.request ? paymentRequestLabels[payment.request.status] : '等待出纳确认' }}</strong><p v-if="payment.request">{{ payment.request.cashier }} · {{ date(payment.request.updatedAt) }}</p><p v-if="payment.request?.issue">{{ paymentIssue(payment.request.issue) }}</p></li>
      <li :class="{ confirmed: payment.operation?.status === 'SUCCEEDED' }"><small>03 / 银行结果</small><strong>{{ payment.operation ? paymentOperationLabels[payment.operation.status] : '尚未发送付款' }}</strong><p v-if="payment.operation">{{ date(payment.operation.updatedAt) }}</p><p v-if="payment.operation?.issue">{{ paymentIssue(payment.operation.issue) }}</p></li>
    </ol>
    <p v-if="payment.operation?.disputed" class="payment-warning" role="alert">资金结果存在冲突。下方保留此前确认的回执，需完成对账后再处理。</p>
    <p v-if="payment.operation?.status === 'REVERSED'" class="payment-warning" role="alert">银行报告款项已退回，需要财务核对后续处理。</p>
    <p v-if="payment.retirement" class="payment-warning">原付款已安全结束：{{ retirementBasisLabels[payment.retirement.basis] }}。由 {{ payment.retirement.retiredBy }} 于 {{ date(payment.retirement.retiredAt) }} 确认，执行证据版本 {{ payment.retirement.operationVersion }}。重新付款须另行授权。</p>
    <dl><div><dt>授权到期</dt><dd>{{ date(payment.expiresAt) }}</dd></div><div><dt>原授权号</dt><dd>{{ payment.id }}</dd></div><div v-if="payment.operation?.paymentReference"><dt>资金交易号</dt><dd>{{ payment.operation.paymentReference }}</dd></div><div v-if="payment.operation?.receiptReference"><dt>{{ payment.operation.observedStatus === 'REVERSED' ? '退回回单' : '银行回单' }}</dt><dd>{{ payment.operation.receiptReference }}<span v-if="payment.operation.completedAt"> · {{ date(payment.operation.completedAt) }}</span></dd></div></dl>
  </div>
</template>

<style scoped>
.payment-amount{display:grid;gap:7px;padding:18px;background:#f0f6f4;border-left:3px solid #167a69}.payment-amount small,.payment-amount span{font-size:12px;color:#566968}.payment-amount strong{font-size:25px;font-variant-numeric:tabular-nums;color:#153e38}.payment-stages{list-style:none;padding:0;margin:15px 0;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));border:1px solid var(--line);border-radius:9px;overflow:hidden}.payment-stages li{padding:15px;background:#fafcfc}.payment-stages li+li{border-left:1px solid var(--line)}.payment-stages small,.payment-stages strong{display:block}.payment-stages small{font-size:10px;color:#617c7b;margin-bottom:8px}.payment-stages strong{font-size:13px}.payment-stages p{font-size:11px;line-height:1.6;color:#5d7170;margin:7px 0 0}.payment-stages .confirmed{background:#edf8f2}.payment-warning{color:#98442e;font-size:12px;line-height:1.7}dl{display:grid;gap:9px;margin:12px 0;font-size:12px}dl>div{display:grid;grid-template-columns:90px 1fr;gap:10px}dt{color:#6e8180}dd{margin:0;overflow-wrap:anywhere}@media(max-width:720px){.payment-stages{grid-template-columns:1fr}.payment-stages li+li{border-left:0;border-top:1px solid var(--line)}.payment-amount strong{font-size:22px}}
</style>
