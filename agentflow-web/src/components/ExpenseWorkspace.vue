<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpensePageQuery, expenseStatuses, moneyLabel } from '../expenses'
import ExpenseEditor from './ExpenseEditor.vue'
import ExpensePlanWorkspace from './ExpensePlanWorkspace.vue'
import AdvanceRequestWorkspace from './AdvanceRequestWorkspace.vue'
import InvoiceWallet from './InvoiceWallet.vue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean }>()
const emit = defineEmits<{ open: [applicationId: string] }>()
const tab = ref<'reports' | 'requests' | 'advances' | 'invoices' | 'plans' | 'borrowings'>('reports'), status = ref('')
const editing = ref(false)
const reports = reactive(new ExpensePageQuery(api.expenseReports)), requests = reactive(new ExpensePageQuery(api.expenseRequests)), advances = reactive(new ExpensePageQuery(api.employeeAdvances))
const current = computed(() => tab.value === 'reports' ? reports : tab.value === 'requests' ? requests : advances)
const tabs = [{ id: 'borrowings' as const, label: '我的借款申请' }, { id: 'plans' as const, label: '我的事前申请' }, { id: 'reports' as const, label: '我的报销' }, { id: 'invoices' as const, label: '个人票夹' }, { id: 'requests' as const, label: '事前批准额度' }, { id: 'advances' as const, label: '已放款借款' }]
const advanceStatus: Record<string, string> = { PAID_OUT: '已放款', PARTIALLY_SETTLED: '部分冲销', SETTLED: '已结清', PAYMENT_REVIEW: '付款待核对，暂停使用' }
function clear() { reports.clear(); requests.clear(); advances.clear() }
function load(more = false) { if (!['invoices', 'plans', 'borrowings'].includes(tab.value)) return current.value.load(props.scopeKey, tab.value === 'reports' ? status.value || undefined : undefined, more) }
watch(() => [props.scopeKey, props.refreshVersion, tab.value, status.value], () => { clear(); void load() }, { immediate: true, flush: 'sync' })
onUnmounted(clear)
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
</script>

<template>
  <section class="content expense-workspace">
    <ExpenseEditor v-if="editing" :scope-key="scopeKey" :locked="locked" @close="editing = false; load()" @submitted="editing = false; load(); emit('open', $event)" />
    <template v-else>
    <div class="page-heading"><div><p class="eyebrow">EXPENSE CONTROL</p><h2>费用报销</h2><p class="subhead">查看报销进度，核对本人可用额度与借款余额。</p></div><div class="workspace-actions"><button v-if="!['invoices', 'plans', 'borrowings'].includes(tab)" class="secondary" :disabled="current.loading" @click="load()">刷新记录</button><button class="primary" :disabled="locked" @click="editing = true">＋ 填写报销</button></div></div>
    <div class="expense-navigation" role="group" aria-label="费用工作区视图"><button v-for="item in tabs" :key="item.id" :aria-pressed="tab === item.id" @click="tab = item.id">{{ item.label }}</button></div>
    <InvoiceWallet v-if="tab === 'invoices'" :scope-key="scopeKey" :refresh-version="refreshVersion" :locked="locked" />
    <ExpensePlanWorkspace v-else-if="tab === 'plans'" :scope-key="scopeKey" :refresh-version="refreshVersion" :locked="locked" @open="emit('open', $event)" />
    <AdvanceRequestWorkspace v-else-if="tab === 'borrowings'" :scope-key="scopeKey" :refresh-version="refreshVersion" :locked="locked" @open="emit('open', $event)" />
    <template v-else>
    <div class="ledger-toolbar"><label v-if="tab === 'reports'">报销状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, value) in expenseStatuses" :key="value" :value="value">{{ label }}</option></select></label><p v-else>{{ tab === 'requests' ? '批准额度包含当前预留；已关闭的额度不能增加占用。' : '只显示实际放款的借款，预留金额仍未完成冲销。' }}</p><span>已加载 {{ current.items.length }} 条</span></div>
    <p v-if="current.error" class="expense-error" role="alert">{{ current.error }}<button :disabled="current.loading" @click="load(!!current.nextBeforeId)">重新读取</button></p>
    <div v-if="tab === 'reports' && reports.items.length" class="report-ledger">
      <div class="ledger-head"><span>费用单</span><span>状态 / 轮次</span><span>创建时间</span><span></span></div>
      <article v-for="report in reports.items" :key="report.id" class="ledger-row"><div><strong>{{ report.title }}</strong><small>{{ report.businessNo }}</small></div><div><span class="status-chip">{{ expenseStatuses[report.status] ?? report.status }}</span><small>{{ report.roundNo ? `第 ${report.roundNo} 轮` : '尚未提交' }}</small></div><time :datetime="report.createdAt">{{ dateLabel(report.createdAt) }}</time><button type="button" class="secondary" :disabled="locked" :aria-label="`查看费用单 ${report.businessNo}`" @click="emit('open', report.applicationId)">查看</button></article>
    </div>
    <div v-if="tab === 'requests'" class="funds-ledger"><article v-for="request in requests.items" :key="request.id" class="fund-entry"><div class="fund-heading"><strong>批准额度</strong><span class="status-chip">{{ request.closed ? '已关闭' : '可用' }}</span><button class="quiet" :disabled="locked" @click="emit('open', request.applicationId)">查看来源申请</button></div><p class="reference">{{ request.id }} · 法人 {{ request.legalEntityId }}</p><div v-for="line in request.lines" :key="line.lineNo" class="balance-row"><strong>第 {{ line.lineNo }} 行</strong><dl><div><dt>批准额</dt><dd>{{ moneyLabel(line.approved) }}</dd></div><div><dt>当前额度</dt><dd>{{ moneyLabel(line.limit) }}</dd></div><div><dt>可用余额</dt><dd class="available">{{ moneyLabel(line.available) }}</dd></div><div><dt>预留 / 已使用</dt><dd>{{ moneyLabel(line.reserved) }} / {{ moneyLabel(line.consumed) }}</dd></div></dl></div></article></div>
    <div v-if="tab === 'advances'" class="funds-ledger"><article v-for="advance in advances.items" :key="advance.id" class="fund-entry"><div class="fund-heading"><strong>员工借款</strong><span class="status-chip">{{ advanceStatus[advance.status] ?? advance.status }}</span></div><p class="reference">{{ advance.id }} · 法人 {{ advance.legalEntityId }}</p><p class="advance-dates">{{ advance.paidOn }} 放款 · {{ advance.dueOn }} 到期</p><dl><div><dt>实际放款</dt><dd>{{ moneyLabel(advance.paid) }}</dd></div><div><dt>可用余额</dt><dd class="available">{{ moneyLabel(advance.available) }}</dd></div><div><dt>当前预留</dt><dd>{{ moneyLabel(advance.reserved) }}</dd></div><div><dt>已冲销</dt><dd>{{ moneyLabel(advance.settled) }}</dd></div></dl></article></div>
    <p v-if="current.loading" class="ledger-empty" role="status">正在读取本人费用记录…</p>
    <div v-else-if="!current.error && !current.items.length" class="ledger-empty"><strong>{{ tab === 'reports' ? '暂无符合条件的报销单' : tab === 'requests' ? '暂无本人事前批准额度' : '暂无本人已放款借款' }}</strong><p>{{ tab === 'reports' ? '已保存的费用单会显示在这里，可切换状态筛选。' : '此处根据实际批准或放款结果显示余额。' }}</p></div>
    <button v-if="current.nextBeforeId" class="secondary more-records" :disabled="current.loading" @click="load(true)">加载更多</button>
    </template>
    </template>
  </section>
</template>

<style scoped>
.workspace-actions{display:flex;gap:10px;flex-wrap:wrap}
.expense-workspace{min-width:0}.expense-navigation{display:flex;gap:8px;border-bottom:1px solid var(--line);margin:24px 0 18px;overflow-x:auto}.expense-navigation button{white-space:nowrap;padding:13px 17px;font-size:13px;border-bottom:2px solid transparent;color:var(--muted)}.expense-navigation button[aria-pressed="true"]{color:var(--deep);border-color:var(--teal);font-weight:700}.ledger-toolbar{display:flex;gap:18px;align-items:center;margin-bottom:18px}.ledger-toolbar label{display:flex;align-items:center;gap:12px;margin:0;font-size:12px;white-space:nowrap}.ledger-toolbar select{min-width:135px;padding:9px 12px}.ledger-toolbar>span{margin-left:auto;font-size:11px;color:var(--muted);white-space:nowrap}.ledger-toolbar p{font-size:12px;line-height:1.8;color:var(--muted);margin:0}.report-ledger{background:white;border:1px solid var(--line);border-radius:14px;overflow:hidden}.ledger-head,.ledger-row{display:grid;grid-template-columns:minmax(0,2fr) minmax(130px,1fr) 155px 70px;gap:18px;align-items:center;padding:16px 22px}.ledger-head{font-size:10px;color:var(--muted);background:var(--paper)}.ledger-row{border-top:1px solid var(--line)}.ledger-row strong{font-size:13px;line-height:1.6;overflow-wrap:anywhere}.ledger-row small{display:block;font-size:10px;color:var(--muted);margin-top:7px;overflow-wrap:anywhere}.ledger-row time{font:10px 'DM Mono',monospace;color:var(--muted)}.ledger-row button{font-size:11px}.funds-ledger{display:grid;gap:14px}.fund-entry{background:white;border:1px solid var(--line);border-radius:14px;padding:22px}.fund-heading{display:flex;align-items:center;gap:12px}.fund-heading strong{font-size:14px}.fund-heading button{margin-left:auto;font-size:12px}.reference{font:10px 'DM Mono',monospace;color:var(--muted);overflow-wrap:anywhere;line-height:1.8}.fund-entry dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px}.fund-entry dt{font-size:11px;color:var(--muted);margin-bottom:8px}.fund-entry dd{margin:0;font:12px 'DM Mono',monospace;line-height:1.8;overflow-wrap:anywhere}.available{color:var(--deep)}.balance-row{padding-top:15px;border-top:1px solid var(--line);margin-top:16px}.balance-row>strong{font-size:12px}.advance-dates{font-size:11px;color:var(--muted)}.ledger-empty{text-align:center;border:1px dashed var(--line);border-radius:12px;padding:44px 20px;color:var(--muted);font-size:12px;line-height:1.8}.ledger-empty strong{color:var(--ink);font-size:14px}.expense-error{font-size:12px;line-height:1.8;background:#fff0ed;color:var(--red);padding:14px;border-radius:10px}.expense-error button{margin-left:12px;text-decoration:underline}.more-records{margin-top:18px}@media(max-width:900px){.ledger-head{display:none}.ledger-row{grid-template-columns:minmax(0,1fr) auto}.ledger-row time{grid-column:1}.ledger-row button{grid-column:2}.fund-entry dl{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:550px){.ledger-toolbar{flex-wrap:wrap;gap:10px}.ledger-toolbar>span{margin-left:0}.expense-navigation button{padding:12px;font-size:12px}.fund-entry{padding:15px}.fund-heading{flex-wrap:wrap}.fund-entry dl{gap:12px}.ledger-row{padding:16px;gap:14px}}
</style>
