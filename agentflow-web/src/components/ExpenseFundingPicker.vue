<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpensePageQuery, moneyLabel, type ExpenseContent } from '../expenses'
import { invoiceSelectable, type InvoiceItem } from '../expenseDraft'
const content = defineModel<ExpenseContent>({ required: true })
const props = defineProps<{ scopeKey: string; reportId?: string; baseCurrency: string; locked: boolean }>()
const open = ref(false), kind = ref<'invoices' | 'requests' | 'advances'>('invoices'), lineNo = ref(0)
const invoices = reactive(new ExpensePageQuery(api.invoices)), requests = reactive(new ExpensePageQuery(api.expenseRequests)), advances = reactive(new ExpensePageQuery(api.employeeAdvances))
const current = computed(() => kind.value === 'invoices' ? invoices : kind.value === 'requests' ? requests : advances)
const selectedLine = computed(() => content.value.lines.find(line => line.lineNo === lineNo.value))
const availableInvoices = computed(() => invoices.items.filter(item => invoiceSelectable(item, content.value.legalEntityId, props.reportId)))
const legalRequests = computed(() => requests.items.filter(item => item.legalEntityId === content.value.legalEntityId))
const legalAdvances = computed(() => advances.items.filter(item => item.legalEntityId === content.value.legalEntityId && item.paid.currency === props.baseCurrency && ['PAID_OUT', 'PARTIALLY_SETTLED'].includes(item.status)))
function clear() { invoices.clear(); requests.clear(); advances.clear() }
function load(more = false) { return current.value.load(props.scopeKey, undefined, more) }
function addInvoice(item: InvoiceItem) {
  if (props.locked || !selectedLine.value || selectedLine.value.allowance || !invoiceSelectable(item, content.value.legalEntityId, props.reportId) || content.value.lines.some(line => line.invoiceIds.includes(item.id)) || selectedLine.value.invoiceIds.length >= 50) return
  selectedLine.value.invoiceIds.push(item.id)
}
function addAdvance(id: string) {
  const advance = legalAdvances.value.find(item => item.id === id)
  if (props.locked || !advance || content.value.advanceOffsets.some(item => item.advanceId === id) || content.value.advanceOffsets.length >= 50) return
  content.value.advanceOffsets.push({ advanceId: id, amount: { value: '', currency: advance.paid.currency } })
}
function addPrior(id: string, number: number) {
  const request = legalRequests.value.find(item => item.id === id)
  if (props.locked || !selectedLine.value || !request || request.closed || !request.lines.some(line => line.lineNo === number)) return
  selectedLine.value.priorRequest = { requestId: id, lineNo: number }
}
watch(() => [props.scopeKey, content.value.legalEntityId], () => { clear(); open.value = false; lineNo.value = 0 }, { flush: 'sync' })
watch(() => [open.value, kind.value], () => { clear(); if (open.value) void load() })
onUnmounted(clear)
</script>

<template>
  <section class="expense-funding" aria-label="费用原件与资金引用">
    <button type="button" class="secondary" :disabled="locked || !content.legalEntityId" :aria-expanded="open" @click="open = !open">{{ open ? '收起原件与资金选择' : '选择发票、事前额度或借款' }}</button>
    <div v-if="open" class="funding-browser">
      <div class="funding-tabs" role="group" aria-label="引用类型"><button v-for="tab in [{ id: 'invoices' as const, name: '本人已验发票' }, { id: 'requests' as const, name: '事前批准额度' }, { id: 'advances' as const, name: '已放款借款' }]" :key="tab.id" type="button" :aria-pressed="kind === tab.id" @click="kind = tab.id">{{ tab.name }}</button></div>
      <label v-if="kind !== 'advances'">归属费用行<select v-model="lineNo" :disabled="locked"><option :value="0">请选择费用行</option><option v-for="line in content.lines" :key="line.lineNo" :value="line.lineNo">第 {{ line.lineNo }} 行 · {{ line.description || '尚未填写说明' }}</option></select></label>
      <p class="funding-help">这里只显示本人当前所选法人的资源。选择不占用余额，正式提交时再次核验；已有占用是否可保留由预检确认。</p>
      <p v-if="current.error" class="funding-error" role="alert">{{ current.error }}</p>
      <p v-if="kind === 'invoices' && selectedLine?.allowance">补贴行不关联发票，请选择其他费用行。</p>
      <ul v-if="kind === 'invoices'"><li v-for="invoice in availableInvoices" :key="invoice.id"><div><strong>{{ invoice.original.filename }}</strong><small>{{ invoice.facts!.issueDate }} · {{ moneyLabel(invoice.facts!.gross) }}</small></div><button type="button" class="secondary" :disabled="locked || !selectedLine || !!selectedLine.allowance || selectedLine.invoiceIds.length >= 50 || content.lines.some(line => line.invoiceIds.includes(invoice.id))" @click="addInvoice(invoice)">{{ content.lines.some(line => line.invoiceIds.includes(invoice.id)) ? '已选择' : '引用发票' }}</button></li></ul>
      <ul v-if="kind === 'requests'"><template v-for="request in legalRequests" :key="request.id"><li v-for="line in request.lines" :key="line.lineNo"><div><strong>{{ request.id }} · 第 {{ line.lineNo }} 行</strong><small>可用 {{ moneyLabel(line.available) }} · {{ request.closed ? '已关闭，仅可保留原引用' : '已批准' }}</small></div><button type="button" class="secondary" :disabled="locked || !selectedLine || request.closed" @click="addPrior(request.id, line.lineNo)">引用额度</button></li></template></ul>
      <ul v-if="kind === 'advances'"><li v-for="advance in legalAdvances" :key="advance.id"><div><strong>{{ advance.id }}</strong><small>{{ advance.paidOn }} 放款 · 可用 {{ moneyLabel(advance.available) }}</small></div><button type="button" class="secondary" :disabled="locked || content.advanceOffsets.length >= 50 || content.advanceOffsets.some(item => item.advanceId === advance.id)" @click="addAdvance(advance.id)">{{ content.advanceOffsets.some(item => item.advanceId === advance.id) ? '已选择' : '选择借款' }}</button></li></ul>
      <p v-if="current.loading" class="funding-help" role="status">正在读取本人资源…</p>
      <p v-else-if="!(kind === 'invoices' ? availableInvoices.length : kind === 'requests' ? legalRequests.length : legalAdvances.length)" class="funding-help">当前已载入页没有可选资源。{{ current.nextBeforeId ? '可以继续加载下一页。' : '原件需先完成上传和查验；额度及借款需有实际批准或放款记录。' }}</p>
      <div class="funding-toolbar"><button type="button" class="quiet" :disabled="current.loading" @click="load()">刷新资源</button><button v-if="current.nextBeforeId" type="button" class="secondary" :disabled="current.loading" @click="load(true)">加载更多</button></div>
    </div>
    <div v-if="content.advanceOffsets.length" class="selected-advances"><h4>本次借款抵扣</h4><div v-for="(offset, index) in content.advanceOffsets" :key="offset.advanceId" class="advance-selection"><label>{{ offset.advanceId }} · {{ offset.amount.currency }}<input v-model="offset.amount.value" :disabled="locked" inputmode="decimal" maxlength="18" required /></label><button type="button" class="quiet" :disabled="locked" @click="content.advanceOffsets.splice(index, 1)">移除借款</button></div></div>
  </section>
</template>

<style scoped>
.expense-funding{margin:22px 0}.funding-browser{background:var(--paper);padding:18px;border:1px solid var(--line);border-radius:10px;margin-top:14px}.funding-tabs{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:18px}.funding-tabs button{font-size:12px;padding:8px 10px;border-bottom:2px solid transparent}.funding-tabs button[aria-pressed=true]{border-color:var(--teal);color:var(--deep)}.expense-funding label{display:grid;gap:8px;font-size:12px;min-width:0;overflow-wrap:anywhere}.expense-funding select,.expense-funding input{width:100%;min-width:0;font:inherit;padding:10px;border:1px solid var(--line);border-radius:7px;background:white}.funding-help{font-size:11px;line-height:1.8;color:var(--muted)}.funding-error{font-size:12px;color:var(--red)}.funding-browser ul{padding:0;list-style:none;max-height:380px;overflow:auto}.funding-browser li{display:flex;align-items:center;gap:16px;padding:14px 0;border-bottom:1px solid var(--line)}.funding-browser li>div{flex:1;min-width:0}.funding-browser strong,.funding-browser small{display:block;font-size:11px;line-height:1.8;overflow-wrap:anywhere}.funding-browser small{color:var(--muted)}.funding-browser li button{font-size:11px;white-space:nowrap;flex-shrink:0}.funding-toolbar{display:flex;justify-content:space-between;gap:16px;font-size:12px}.selected-advances h4{font-size:13px}.advance-selection{display:flex;gap:12px;align-items:end;margin-top:14px}.advance-selection label{flex:1}.advance-selection button{font-size:11px;flex-shrink:0;padding:12px 0}
</style>
