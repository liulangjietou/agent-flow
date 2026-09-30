<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpensePageQuery, expenseStatuses } from '../expenses'
import BudgetAdjustmentEditor from './BudgetAdjustmentEditor.vue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean }>()
const emit = defineEmits<{ open: [applicationId: string] }>()
const query = reactive(new ExpensePageQuery(api.budgetAdjustments)), status = ref(''), editing = ref(false)
function load(more = false) { return query.load(props.scopeKey, status.value || undefined, more) }
watch(() => JSON.stringify([props.scopeKey, props.refreshVersion, status.value]), () => { query.clear(); void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="budget-adjustment-workspace" aria-label="我的预算调整申请">
    <BudgetAdjustmentEditor v-if="editing" :scope-key="scopeKey" :locked="locked" @close="editing = false; load()" @submitted="editing = false; load(); emit('open', $event)" />
    <template v-else>
      <div class="budget-adjustment-toolbar"><label>预算调整状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, value) in expenseStatuses" :key="value" :value="value">{{ label }}</option></select></label><div><button class="secondary" :disabled="query.loading" @click="load()">刷新预算调整记录</button><button class="primary" :disabled="locked" @click="editing = true">＋ 填写预算调整申请</button></div></div>
      <p class="budget-adjustment-help">申请追加、调减或同期间调拨预算。核对原台账与拟调整额度后提交，批准与实际生效分别记录。</p>
      <p v-if="query.error" class="budget-adjustment-error" role="alert">{{ query.error }}</p>
      <div v-if="query.items.length" class="budget-adjustment-ledger"><article v-for="budgetAdjustment in query.items" :key="budgetAdjustment.id" class="budget-adjustment-row"><div><strong>{{ budgetAdjustment.title }}</strong><small>{{ budgetAdjustment.businessNo }}</small></div><div><span class="status-chip">{{ expenseStatuses[budgetAdjustment.status] ?? budgetAdjustment.status }}</span><small>第 {{ budgetAdjustment.roundNo }} 轮</small></div><time :datetime="budgetAdjustment.createdAt">{{ dateLabel(budgetAdjustment.createdAt) }}</time><button class="secondary" :disabled="locked" :aria-label="`查看预算调整申请 ${budgetAdjustment.businessNo}`" @click="emit('open', budgetAdjustment.applicationId)">查看</button></article></div>
      <p v-if="query.loading" class="budget-adjustment-empty" role="status">正在读取本人预算调整申请…</p><div v-else-if="!query.error && !query.items.length" class="budget-adjustment-empty"><strong>暂无符合条件的预算调整申请</strong><p>保存后的预算调整申请显示在这里，可继续核对原预算台账并提交审批。</p></div>
      <button v-if="query.nextBeforeId" class="secondary more-budget-adjustments" :disabled="query.loading" @click="load(true)">加载更多预算调整</button>
    </template>
  </section>
</template>

<style scoped>
.budget-adjustment-toolbar,.budget-adjustment-toolbar>div{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.budget-adjustment-toolbar{justify-content:space-between}.budget-adjustment-toolbar label{display:flex;align-items:center;gap:10px;font-size:12px}.budget-adjustment-toolbar select{padding:9px 12px;min-width:130px}.budget-adjustment-help{font-size:12px;color:var(--muted);line-height:1.8}.budget-adjustment-ledger{background:white;border:1px solid var(--line);border-radius:12px;overflow:hidden}.budget-adjustment-row{display:grid;grid-template-columns:minmax(0,2fr) minmax(100px,1fr) 155px 70px;align-items:center;gap:16px;padding:20px;border-bottom:1px solid var(--line)}.budget-adjustment-row:last-child{border-bottom:0}.budget-adjustment-row strong{font-size:13px;overflow-wrap:anywhere;line-height:1.6}.budget-adjustment-row small{display:block;font-size:10px;color:var(--muted);margin-top:7px;overflow-wrap:anywhere}.budget-adjustment-row time{font-size:10px;color:var(--muted)}.budget-adjustment-row button{font-size:11px}.budget-adjustment-empty{padding:36px 20px;text-align:center;border:1px dashed var(--line);border-radius:12px;color:var(--muted);font-size:12px;line-height:1.8}.budget-adjustment-empty strong{font-size:14px;color:var(--ink)}.budget-adjustment-error{font-size:12px;line-height:1.8;color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.more-budget-adjustments{margin-top:18px}@media(max-width:800px){.budget-adjustment-row{grid-template-columns:minmax(0,1fr) auto}.budget-adjustment-row time{grid-column:1}.budget-adjustment-row button{grid-column:2}}@media(max-width:500px){.budget-adjustment-row{padding:15px}}
</style>
