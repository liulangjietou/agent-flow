<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpensePageQuery, expenseStatuses } from '../expenses'
import ExpensePlanEditor from './ExpensePlanEditor.vue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean }>()
const emit = defineEmits<{ open: [applicationId: string] }>()
const query = reactive(new ExpensePageQuery(api.expensePlans)), status = ref(''), editing = ref(false)
function load(more = false) { return query.load(props.scopeKey, status.value || undefined, more) }
watch(() => JSON.stringify([props.scopeKey, props.refreshVersion, status.value]), () => { query.clear(); void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="plan-workspace" aria-label="我的事前申请">
    <ExpensePlanEditor v-if="editing" :scope-key="scopeKey" :locked="locked" @close="editing = false; load()" @submitted="editing = false; load(); emit('open', $event)" />
    <template v-else>
      <div class="plan-toolbar"><label>计划状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, value) in expenseStatuses" :key="value" :value="value">{{ label }}</option></select></label><div><button class="secondary" :disabled="query.loading" @click="load()">刷新计划记录</button><button class="primary" :disabled="locked" @click="editing = true">＋ 填写事前申请</button></div></div>
      <p class="plan-help">先申请计划，审批通过后再引用批准额度填写报销。计划金额与实际费用分别保存。</p>
      <p v-if="query.error" class="plan-error" role="alert">{{ query.error }}</p>
      <div v-if="query.items.length" class="plan-ledger"><article v-for="plan in query.items" :key="plan.id" class="plan-row"><div><strong>{{ plan.title }}</strong><small>{{ plan.businessNo }}</small></div><div><span class="status-chip">{{ expenseStatuses[plan.status] ?? plan.status }}</span><small>第 {{ plan.roundNo }} 轮</small></div><time :datetime="plan.createdAt">{{ dateLabel(plan.createdAt) }}</time><button class="secondary" :disabled="locked" :aria-label="`查看事前申请 ${plan.businessNo}`" @click="emit('open', plan.applicationId)">查看</button></article></div>
      <p v-if="query.loading" class="plan-empty" role="status">正在读取本人事前申请…</p><div v-else-if="!query.error && !query.items.length" class="plan-empty"><strong>暂无符合条件的事前申请</strong><p>保存后的计划会显示在这里，可继续补充并提交审批。</p></div>
      <button v-if="query.nextBeforeId" class="secondary more-plans" :disabled="query.loading" @click="load(true)">加载更多计划</button>
    </template>
  </section>
</template>

<style scoped>
.plan-toolbar,.plan-toolbar>div{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.plan-toolbar{justify-content:space-between}.plan-toolbar label{display:flex;align-items:center;gap:10px;font-size:12px}.plan-toolbar select{padding:9px 12px;min-width:130px}.plan-help{font-size:12px;color:var(--muted);line-height:1.8}.plan-ledger{background:white;border:1px solid var(--line);border-radius:12px;overflow:hidden}.plan-row{display:grid;grid-template-columns:minmax(0,2fr) minmax(100px,1fr) 155px 70px;align-items:center;gap:16px;padding:20px;border-bottom:1px solid var(--line)}.plan-row:last-child{border-bottom:0}.plan-row strong{font-size:13px;overflow-wrap:anywhere;line-height:1.6}.plan-row small{display:block;font-size:10px;color:var(--muted);margin-top:7px;overflow-wrap:anywhere}.plan-row time{font-size:10px;color:var(--muted)}.plan-row button{font-size:11px}.plan-empty{padding:36px 20px;text-align:center;border:1px dashed var(--line);border-radius:12px;color:var(--muted);font-size:12px;line-height:1.8}.plan-empty strong{font-size:14px;color:var(--ink)}.plan-error{font-size:12px;line-height:1.8;color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.more-plans{margin-top:18px}@media(max-width:800px){.plan-row{grid-template-columns:minmax(0,1fr) auto}.plan-row time{grid-column:1}.plan-row button{grid-column:2}}@media(max-width:500px){.plan-row{padding:15px}}
</style>
