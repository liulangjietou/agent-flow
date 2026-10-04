<script setup lang="ts">
import { holdLabels, supplierPreparationLabels, paymentOperationLabels, supplierBankIssues, type SupplierCashierView } from '../supplierCashier'
defineProps<{ view: SupplierCashierView }>()
</script>

<template>
  <section aria-label="供应商原付款事实">
      <div class="supplier-payee"><div><span>供应商</span><h4>{{ view.supplierName }}</h4><p>收款账户 {{ view.maskedPayeeAccount }}</p></div><strong>{{ view.amount.currency }} {{ view.amount.value }}</strong></div>
      <dl class="supplier-stages"><div><dt>原应付预留</dt><dd>{{ holdLabels[view.hold.status] }}</dd></div><div><dt>出纳复核</dt><dd>{{ view.preparation ? supplierPreparationLabels[view.preparation.status] : '等待选择账户' }}</dd></div><div><dt>银行结果</dt><dd>{{ view.operation ? paymentOperationLabels[view.operation.status] : '尚未登记银行指令' }}</dd></div></dl>
      <dl class="supplier-facts"><div><dt>申请人 / 财务授权人</dt><dd>{{ view.employeeId }} / {{ view.authorizedBy }}</dd></div><div><dt>批准轮次</dt><dd>第 {{ view.roundNo }} 轮</dd></div><div><dt>授权有效期至</dt><dd>{{ new Date(view.expiresAt).toLocaleString('zh-CN') }}</dd></div><div v-if="view.retiredAt"><dt>授权已结束</dt><dd>{{ new Date(view.retiredAt).toLocaleString('zh-CN') }}</dd></div><div v-if="view.operation?.paymentReference"><dt>原银行交易号</dt><dd>{{ view.operation.paymentReference }}</dd></div><div v-if="view.operation?.receiptReference"><dt>银行回单</dt><dd>{{ view.operation.receiptReference }}</dd></div></dl>
      <p v-if="view.operation?.issue || view.preparation?.issue" role="status">{{ supplierBankIssues[view.operation?.issue || view.preparation?.issue || ''] }}</p>
      <p v-if="view.operation?.disputed" role="alert" class="payment-error">银行结果存在冲突，请核对原交易。当前不能新建付款或改选账户。</p>
      <p v-if="view.operation?.status === 'SUCCEEDED'" class="cashier-note">银行已确认到账。原应付结算状态仍需由财务继续核对。</p>
    <p v-if="view.preparation" class="cashier-note">原出纳：{{ view.preparation.cashier }}</p>
  </section>
</template>

<style scoped>
.supplier-payee{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:18px;background:#f0f7f4;border-radius:10px}.supplier-payee span{font-size:11px;color:#56716a}.supplier-payee h4{font-size:19px;margin:6px 0}.supplier-payee strong{font-size:24px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.supplier-payee p{margin:0}.supplier-stages{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:20px 0}.supplier-stages>div{padding:12px;border:1px solid var(--line);border-radius:8px}.supplier-stages dt,.supplier-facts dt{font-size:11px;color:#627770}.supplier-stages dd,.supplier-facts dd{margin:7px 0 0;font-size:12px;line-height:1.6;overflow-wrap:anywhere}.supplier-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin:18px 0}.payment-error{color:#a04732}.cashier-note,p{font-size:12px;line-height:1.7;color:#5e7470}@media(max-width:600px){.supplier-payee{align-items:flex-start;flex-direction:column}.supplier-stages,.supplier-facts{grid-template-columns:1fr}}
</style>
