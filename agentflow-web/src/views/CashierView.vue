<script setup lang="ts">
import CashierWorkspace from '../components/CashierWorkspace.vue'
import PaymentBatchWorkspace from '../components/PaymentBatchWorkspace.vue'
import SupplierCashierWorkspace from '../components/SupplierCashierWorkspace.vue'

/**
 * 出纳付款页：在员工借款报销、供应商付款与批量付款之间切换；通知跳转带来的付款单只预选一次。
 * @author owlzhangfq@gmail.com
 */
defineProps<{ initialPaymentId: string; scopeKey: string; refreshVersion: number; locked: boolean }>()
const kind = defineModel<'employee' | 'supplier' | 'batches'>('kind', { required: true })
</script>

<template>
  <section>
    <div class="cashier-kind" role="group" aria-label="付款业务"><button type="button" class="quiet" :aria-pressed="kind === 'employee'" :disabled="locked" @click="kind = 'employee'">借款与报销</button><button type="button" class="quiet" :aria-pressed="kind === 'supplier'" :disabled="locked" @click="kind = 'supplier'">供应商付款</button><button type="button" class="quiet" :aria-pressed="kind === 'batches'" :disabled="locked" @click="kind = 'batches'">批量付款</button></div>
    <CashierWorkspace v-if="kind === 'employee'" :initial-payment-id="initialPaymentId" :scope-key="scopeKey" :refresh-version="refreshVersion" :locked="locked" />
    <PaymentBatchWorkspace v-else-if="kind === 'batches'" :scope-key="scopeKey" :refresh-version="refreshVersion" :locked="locked" />
    <SupplierCashierWorkspace v-else :initial-payment-id="initialPaymentId" :scope-key="scopeKey" :refresh-version="refreshVersion" :locked="locked" />
  </section>
</template>

<style scoped>
.cashier-kind{display:flex;flex-wrap:wrap;gap:10px;padding:24px 40px 0}.cashier-kind button[aria-pressed="true"]{background:#e3f2eb;border-color:#3c8a74;color:#145d4c}.cashier-kind button{min-height:40px}@media(max-width:600px){.cashier-kind{padding:16px 16px 0}}
</style>
