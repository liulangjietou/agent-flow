<script setup lang="ts">
import { type ExpensePartialAdjustmentNotificationTarget } from '../api'
import { isExpensePartialAdjustmentNotification } from '../expensePartialAdjustmentNotification'
import ExpensePartialAdjustmentNotificationDetail from './ExpensePartialAdjustmentNotificationDetail.vue'
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api, type InboxMessage, type FinancialNotificationTarget, type VoucherNotificationTarget, type BudgetNotificationTarget, type ReversalNotificationTarget, type ReversalCheckNotificationTarget, type DisbursementReturnNotificationTarget, type RepaymentReviewNotificationTarget, type ExpenseAdjustmentNotificationTarget, type BudgetAdjustmentNotificationTarget, type RepaymentNotificationTarget, type ExpenseReturnNotificationTarget, type SupplierReturnNotificationTarget, type SupplierAdjustmentNotificationTarget, type SupplierSettlementNotificationTarget, type ExpenseSettlementNotificationTarget } from '../api'
import { isTaskNotification, isPaymentNotification, isVoucherNotification, isBudgetNotification, isReversalNotification, isReversalCheckNotification, isSettlementNotification, notificationLabels, NotificationInboxQuery } from '../notificationInbox'
import PaymentNotificationDetail from './PaymentNotificationDetail.vue'
import VoucherNotificationDetail from './VoucherNotificationDetail.vue'
import BudgetNotificationDetail from './BudgetNotificationDetail.vue'
import ReversalCheckNotificationDetail from './ReversalCheckNotificationDetail.vue'
import { isSupplierReturnNotification } from '../supplierReturnNotification'
import { isExpenseReturnNotification } from '../expenseReturnNotification'
import { isDisbursementReturnNotification } from '../disbursementReturnNotification'
import { isRepaymentReviewNotification } from '../repaymentReviewNotification'
import { isExpenseAdjustmentNotification } from '../expenseAdjustmentNotification'
import ExpenseAdjustmentNotificationDetail from './ExpenseAdjustmentNotificationDetail.vue'
import { isBudgetAdjustmentNotification } from '../budgetAdjustmentNotification'
import { isRepaymentNotification } from '../repaymentNotification'
import SupplierReturnNotificationDetail from './SupplierReturnNotificationDetail.vue'
import ExpenseReturnNotificationDetail from './ExpenseReturnNotificationDetail.vue'
import DisbursementReturnNotificationDetail from './DisbursementReturnNotificationDetail.vue'
import RepaymentReviewNotificationDetail from './RepaymentReviewNotificationDetail.vue'
import BudgetAdjustmentNotificationDetail from './BudgetAdjustmentNotificationDetail.vue'
import RepaymentNotificationDetail from './RepaymentNotificationDetail.vue'
import { isSupplierAdjustmentNotification } from '../supplierAdjustmentNotification'
import SupplierAdjustmentNotificationDetail from './SupplierAdjustmentNotificationDetail.vue'
import { isSupplierSettlementNotification } from '../supplierSettlementNotification'
import SupplierSettlementNotificationDetail from './SupplierSettlementNotificationDetail.vue'
import ExpenseSettlementNotificationDetail from './ExpenseSettlementNotificationDetail.vue'
import ReversalNotificationDetail from './ReversalNotificationDetail.vue'
import NotificationPreferencesPanel from './NotificationPreferencesPanel.vue'
import NotificationDeliveriesPanel from './NotificationDeliveriesPanel.vue'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ read: [message: InboxMessage]; open: [message: InboxMessage]; paymentOpen: [target: FinancialNotificationTarget]; voucherOpen: [target: VoucherNotificationTarget]; budgetOpen: [target: BudgetNotificationTarget]; reversalOpen: [target: ReversalNotificationTarget]; reversalCheckOpen: [target: ReversalCheckNotificationTarget]; supplierReturnOpen: [target: SupplierReturnNotificationTarget]; expenseReturnOpen: [target: ExpenseReturnNotificationTarget]; disbursementReturnOpen: [target: DisbursementReturnNotificationTarget]; repaymentReviewOpen: [target: RepaymentReviewNotificationTarget]; expensePartialAdjustmentOpen: [target: ExpensePartialAdjustmentNotificationTarget]; expenseAdjustmentOpen: [target: ExpenseAdjustmentNotificationTarget]; budgetAdjustmentOpen: [target: BudgetAdjustmentNotificationTarget]; repaymentOpen: [target: RepaymentNotificationTarget]; supplierAdjustmentOpen: [target: SupplierAdjustmentNotificationTarget]; supplierSettlementOpen: [target: SupplierSettlementNotificationTarget]; settlementOpen: [target: ExpenseSettlementNotificationTarget] }>()
const selectedSupplierReturn = ref<InboxMessage | null>(null)
const selectedExpenseReturn = ref<InboxMessage | null>(null)
const selectedDisbursementReturn = ref<InboxMessage | null>(null)
const selectedRepaymentReview = ref<InboxMessage | null>(null)
const selectedExpensePartialAdjustment = ref<InboxMessage | null>(null)
const selectedExpenseAdjustment = ref<InboxMessage | null>(null)
const selectedBudgetAdjustment = ref<InboxMessage | null>(null)
const selectedRepayment = ref<InboxMessage | null>(null)
const selectedSupplierAdjustment = ref<InboxMessage | null>(null)
const selectedSupplierSettlement = ref<InboxMessage | null>(null)
const selectedSettlement = ref<InboxMessage | null>(null)
const selectedPayment = ref<InboxMessage | null>(null)
const selectedReversalCheck = ref<InboxMessage | null>(null)
const selectedReversal = ref<InboxMessage | null>(null)
const selectedBudget = ref<InboxMessage | null>(null)
const selectedVoucher = ref<InboxMessage | null>(null)
function open(item: InboxMessage) {
  selectedPayment.value = null; selectedVoucher.value = null; selectedBudget.value = null; selectedReversal.value = null; selectedReversalCheck.value = null; selectedSettlement.value = null; selectedSupplierAdjustment.value = null; selectedSupplierSettlement.value = null; selectedSupplierReturn.value = null; selectedExpenseReturn.value = null; selectedDisbursementReturn.value = null; selectedRepaymentReview.value = null; selectedExpensePartialAdjustment.value = null; selectedExpenseAdjustment.value = null; selectedBudgetAdjustment.value = null; selectedRepayment.value = null
  if (isSupplierAdjustmentNotification(item)) selectedSupplierAdjustment.value = item
  else if (isDisbursementReturnNotification(item)) selectedDisbursementReturn.value = item
  else if (isExpensePartialAdjustmentNotification(item)) selectedExpensePartialAdjustment.value = item
  else if (isExpenseAdjustmentNotification(item)) selectedExpenseAdjustment.value = item
  else if (isBudgetAdjustmentNotification(item)) selectedBudgetAdjustment.value = item
  else if (isRepaymentReviewNotification(item)) selectedRepaymentReview.value = item
  else if (isRepaymentNotification(item)) selectedRepayment.value = item
  else if (isExpenseReturnNotification(item)) selectedExpenseReturn.value = item
  else if (isSupplierReturnNotification(item)) selectedSupplierReturn.value = item
  else if (isSupplierSettlementNotification(item)) selectedSupplierSettlement.value = item
  else if (isSettlementNotification(item)) selectedSettlement.value = item
  else if (isPaymentNotification(item)) selectedPayment.value = item
  else if (isVoucherNotification(item)) selectedVoucher.value = item
  else if (isBudgetNotification(item)) selectedBudget.value = item
  else if (isReversalCheckNotification(item)) selectedReversalCheck.value = item
  else if (isReversalNotification(item)) selectedReversal.value = item
  else emit('open', item)
}
const readFilter = ref<'all' | 'unread'>('all')
const query = reactive(new NotificationInboxQuery(api.inbox))
function refresh() { void query.load(props.scopeKey, readFilter.value) }
watch([() => props.scopeKey, () => props.refreshVersion, readFilter], refresh, { immediate: true, flush: 'sync' })
watch(() => props.scopeKey, () => { selectedPayment.value = null; selectedVoucher.value = null; selectedBudget.value = null; selectedReversal.value = null; selectedReversalCheck.value = null; selectedSettlement.value = null; selectedSupplierAdjustment.value = null; selectedSupplierSettlement.value = null; selectedSupplierReturn.value = null; selectedExpenseReturn.value = null; selectedDisbursementReturn.value = null; selectedRepaymentReview.value = null; selectedExpensePartialAdjustment.value = null; selectedExpenseAdjustment.value = null; selectedBudgetAdjustment.value = null; selectedRepayment.value = null }, { flush: 'sync' })
onUnmounted(() => query.clear())
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>

<template>
  <section class="content inbox-page" aria-labelledby="inbox-title">
    <div class="inbox-heading"><div><p class="eyebrow">NOTIFICATIONS</p><h2 id="inbox-title">消息中心</h2><p class="inbox-intro">跟进审批与任务流转，回到申请继续处理。</p></div><span v-if="query.loaded" class="unread-total" role="status">{{ query.unreadCount }} 条未读</span></div>
    <NotificationPreferencesPanel :scope-key="scopeKey" :refresh-version="refreshVersion" :locked="locked" />
    <NotificationDeliveriesPanel :scope-key="scopeKey" :refresh-version="refreshVersion" :locked="locked" />
    <PaymentNotificationDetail v-if="selectedPayment" :message="selectedPayment" :scope-key="scopeKey" :locked="locked" @close="selectedPayment = null" @open="emit('paymentOpen', $event)" />
    <SupplierReturnNotificationDetail v-if="selectedSupplierReturn" :message="selectedSupplierReturn" :scope-key="scopeKey" :locked="locked" @close="selectedSupplierReturn = null" @open="emit('supplierReturnOpen', $event)" />
    <ExpenseReturnNotificationDetail v-if="selectedExpenseReturn" :message="selectedExpenseReturn" :scope-key="scopeKey" :locked="locked" @close="selectedExpenseReturn = null" @open="emit('expenseReturnOpen', $event)" />
    <DisbursementReturnNotificationDetail v-if="selectedDisbursementReturn" :message="selectedDisbursementReturn" :scope-key="scopeKey" :locked="locked" @close="selectedDisbursementReturn = null" @open="emit('disbursementReturnOpen', $event)" />
    <RepaymentReviewNotificationDetail v-if="selectedRepaymentReview" :message="selectedRepaymentReview" :scope-key="scopeKey" :locked="locked" @close="selectedRepaymentReview = null" @open="emit('repaymentReviewOpen', $event)" />
    <ExpenseAdjustmentNotificationDetail v-if="selectedExpenseAdjustment" :message="selectedExpenseAdjustment" :scope-key="scopeKey" :locked="locked" @close="selectedExpenseAdjustment = null" @open="emit('expenseAdjustmentOpen', $event)" />
    <ExpensePartialAdjustmentNotificationDetail v-if="selectedExpensePartialAdjustment" :message="selectedExpensePartialAdjustment" :scope-key="scopeKey" :locked="locked" @close="selectedExpensePartialAdjustment = null" @open="emit('expensePartialAdjustmentOpen', $event)" />
    <BudgetAdjustmentNotificationDetail v-if="selectedBudgetAdjustment" :message="selectedBudgetAdjustment" :scope-key="scopeKey" :locked="locked" @close="selectedBudgetAdjustment = null" @open="emit('budgetAdjustmentOpen', $event)" />
    <RepaymentNotificationDetail v-if="selectedRepayment" :message="selectedRepayment" :scope-key="scopeKey" :locked="locked" @close="selectedRepayment = null" @open="emit('repaymentOpen', $event)" />
    <SupplierAdjustmentNotificationDetail v-if="selectedSupplierAdjustment" :message="selectedSupplierAdjustment" :scope-key="scopeKey" :locked="locked" @close="selectedSupplierAdjustment = null" @open="emit('supplierAdjustmentOpen', $event)" />
    <SupplierSettlementNotificationDetail v-if="selectedSupplierSettlement" :message="selectedSupplierSettlement" :scope-key="scopeKey" :locked="locked" @close="selectedSupplierSettlement = null" @open="emit('supplierSettlementOpen', $event)" />
    <ExpenseSettlementNotificationDetail v-if="selectedSettlement" :message="selectedSettlement" :scope-key="scopeKey" :locked="locked" @close="selectedSettlement = null" @open="emit('settlementOpen', $event)" />
    <ReversalCheckNotificationDetail v-if="selectedReversalCheck" :message="selectedReversalCheck" :scope-key="scopeKey" :locked="locked" @close="selectedReversalCheck = null" @open="emit('reversalCheckOpen', $event)" />
    <ReversalNotificationDetail v-if="selectedReversal" :message="selectedReversal" :scope-key="scopeKey" :locked="locked" @close="selectedReversal = null" @open="emit('reversalOpen', $event)" />
    <BudgetNotificationDetail v-if="selectedBudget" :message="selectedBudget" :scope-key="scopeKey" :locked="locked" @close="selectedBudget = null" @open="emit('budgetOpen', $event)" />
    <VoucherNotificationDetail v-if="selectedVoucher" :message="selectedVoucher" :scope-key="scopeKey" :locked="locked" @close="selectedVoucher = null" @open="emit('voucherOpen', $event)" />
    <div class="inbox-toolbar"><div role="group" aria-label="消息筛选"><button :class="{ selected: readFilter === 'all' }" :aria-pressed="readFilter === 'all'" :disabled="locked" @click="readFilter = 'all'">全部消息</button><button :class="{ selected: readFilter === 'unread' }" :aria-pressed="readFilter === 'unread'" :disabled="locked" @click="readFilter = 'unread'">只看未读</button></div><button class="secondary" :disabled="query.loading || locked" @click="refresh">刷新消息</button></div>
    <p class="inbox-hint">消息保留发生时的进展，最新状态请查看申请。已读不会改变审批状态。</p>
    <div v-if="query.error" class="inbox-error" role="alert"><p>{{ query.error }}</p><button class="secondary" :disabled="locked || query.loading" @click="query.loaded ? query.more() : refresh()">重新读取</button></div>
    <p v-if="query.loading && !query.loaded" role="status" class="inbox-empty">正在读取消息…</p>
    <div v-else-if="query.loaded && !query.items.length" class="inbox-empty"><span aria-hidden="true">◌</span><h3>{{ readFilter === 'unread' ? '没有未读消息' : '还没有站内消息' }}</h3><p>{{ readFilter === 'unread' ? '切换到全部消息，可以回看之前的进展。' : '申请提交、审批结果和任务交接后，相关消息会出现在这里。' }}</p></div>
    <ol v-else class="inbox-list" aria-label="站内消息">
      <li v-for="item in query.items" :key="item.id" :class="{ unread: !item.readAt }">
        <div class="message-state"><i aria-hidden="true" /><span>{{ item.readAt ? '已读' : '未读' }}</span></div>
        <div class="message-body"><div class="message-meta"><strong>{{ notificationLabels[item.kind] }}</strong><time :datetime="item.createdAt">{{ time(item.createdAt) }}</time></div><h3>{{ item.title }}</h3><p v-if="item.content" class="message-content">{{ item.content }}</p><p>{{ item.businessNo }} · 第 {{ item.roundNo }} 轮<span v-if="item.nodeName"> · {{ item.nodeName }}</span></p><p class="message-actor">操作人 {{ item.actor }}<span v-if="item.readAt"> · {{ time(item.readAt) }} 已读</span></p></div>
        <div class="message-actions"><button class="secondary" :disabled="locked" @click="open(item)">{{ isSupplierAdjustmentNotification(item) ? '查看供应商应付调整' : isDisbursementReturnNotification(item) ? '查看借款放款退回' : isExpensePartialAdjustmentNotification(item) ? '查看报销部分调整' : isExpenseAdjustmentNotification(item) ? '查看报销资源调整' : isBudgetAdjustmentNotification(item) ? '查看预算调整' : isRepaymentReviewNotification(item) ? '查看还款复核' : isRepaymentNotification(item) ? '查看借款还款' : isExpenseReturnNotification(item) ? '查看报销退回' : isSupplierReturnNotification(item) ? '查看供应商回款' : isSupplierSettlementNotification(item) ? '查看供应商结算' : isSettlementNotification(item) ? '查看原结算' : isReversalCheckNotification(item) ? '查看原核对' : isReversalNotification(item) ? '查看原冲销' : isBudgetNotification(item) ? '查看原预算' : isVoucherNotification(item) ? '查看原凭证' : isPaymentNotification(item) ? '查看原付款' : item.kind === 'APPLICATION_COPIED' ? '查看抄送' : isTaskNotification(item) ? '查看待办' : '查看申请' }} ↗</button><button v-if="!item.readAt" class="quiet" :disabled="locked" @click="emit('read', item)">标为已读</button></div>
      </li>
    </ol>
    <div v-if="query.loaded && query.items.length" class="inbox-footer"><span>已加载 {{ query.items.length }} 条消息</span><button v-if="query.nextCursor" class="secondary" :disabled="query.loading || locked" @click="query.more">{{ query.loading ? '正在加载…' : '加载更多' }}</button><span v-else>已加载全部匹配消息</span></div>
  </section>
</template>

<style scoped>
.inbox-heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.inbox-heading h2{font-size:28px;margin:6px 0}.inbox-intro,.inbox-hint{font-size:13px;color:var(--muted);line-height:1.8}.unread-total{border:1px solid var(--line);padding:10px 14px;border-radius:8px;color:var(--deep);font-size:13px;white-space:nowrap}.inbox-toolbar{display:flex;justify-content:space-between;gap:15px;margin-top:26px}.inbox-toolbar [role=group]{display:flex;gap:4px;background:var(--soft);padding:4px;border-radius:8px}.inbox-toolbar [role=group] button{border:0;background:transparent;padding:9px 14px;color:var(--muted);border-radius:5px;font:inherit;font-size:13px;cursor:pointer}.inbox-toolbar [role=group] .selected{background:#fff;color:var(--deep);box-shadow:0 1px 3px #173e3520}.inbox-list{list-style:none;margin:20px 0 0;padding:0;border-top:1px solid var(--line)}.inbox-list li{display:grid;grid-template-columns:40px minmax(0,1fr) auto;gap:20px;padding:24px 8px;border-bottom:1px solid var(--line);align-items:center}.inbox-list li.unread{background:linear-gradient(90deg,var(--soft),transparent 35%)}.message-state{font-size:11px;color:var(--muted);display:flex;flex-direction:column;align-items:center;gap:8px}.message-state i{width:7px;height:7px;border-radius:50%;background:var(--line)}.unread .message-state i{background:var(--teal)}.message-meta{display:flex;gap:12px;align-items:center;justify-content:space-between;font-size:12px;color:var(--deep)}.message-meta time{font-size:11px;color:var(--muted);white-space:nowrap}.message-body h3{font-size:15px;line-height:1.7;margin:9px 0 3px;overflow-wrap:anywhere}.message-body p{font-size:12px;color:var(--muted);line-height:1.8;margin:0;overflow-wrap:anywhere}.message-body .message-content{white-space:pre-wrap;color:var(--ink);margin:7px 0}.message-body .message-actor{font-size:11px;margin-top:7px}.message-actions{display:flex;flex-direction:column;gap:8px}.message-actions button{white-space:nowrap}.inbox-footer{display:flex;justify-content:space-between;align-items:center;gap:15px;margin:20px 0;color:var(--muted);font-size:12px}.inbox-empty{text-align:center;padding:55px 20px;color:var(--muted);font-size:13px}.inbox-empty>span{display:block;font-size:35px;color:var(--teal)}.inbox-empty h3{color:var(--ink);font-size:17px}.inbox-error{color:var(--red);padding:18px 0;font-size:13px}
@media(max-width:650px){.inbox-heading{align-items:flex-start;gap:8px}.inbox-heading h2{font-size:24px}.inbox-intro{max-width:230px}.unread-total{padding:8px;font-size:12px}.inbox-toolbar{gap:8px}.inbox-toolbar [role=group] button{padding:9px}.inbox-list li{grid-template-columns:28px minmax(0,1fr);gap:12px;padding:20px 0}.message-meta{align-items:flex-start;flex-direction:column;gap:5px}.message-actions{grid-column:2;flex-direction:row;flex-wrap:wrap}.inbox-footer{flex-wrap:wrap}}
</style>
