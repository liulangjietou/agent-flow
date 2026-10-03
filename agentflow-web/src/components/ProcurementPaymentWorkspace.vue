<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpensePageQuery, expenseStatuses } from '../expenses'
import ProcurementPaymentEditor from './ProcurementPaymentEditor.vue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean }>()
const emit = defineEmits<{ open: [applicationId: string] }>()
const query = reactive(new ExpensePageQuery(api.procurementPayments)), status = ref(''), editing = ref(false)
function load(more = false) { return query.load(props.scopeKey, status.value || undefined, more) }
watch(() => JSON.stringify([props.scopeKey, props.refreshVersion, status.value]), () => { query.clear(); void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="procurement-workspace" aria-label="我的采购付款申请">
    <ProcurementPaymentEditor v-if="editing" :scope-key="scopeKey" :locked="locked" @close="editing = false; load()" @submitted="editing = false; load(); emit('open', $event)" />
    <template v-else>
      <div class="procurement-toolbar"><label>采购付款状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, value) in expenseStatuses" :key="value" :value="value">{{ label }}</option></select></label><div><button class="secondary" :disabled="query.loading" @click="load()">刷新采购付款记录</button><button class="primary" :disabled="locked" @click="editing = true">＋ 填写采购付款申请</button></div></div>
      <p class="procurement-help">按已验收、开票并挂账的原应付申请付款。核对供应商、未结余额及三单匹配依据后提交审批。</p>
      <p v-if="query.error" class="procurement-error" role="alert">{{ query.error }}</p>
      <div v-if="query.items.length" class="procurement-ledger"><article v-for="procurement in query.items" :key="procurement.id" class="procurement-row"><div><strong>{{ procurement.title }}</strong><small>{{ procurement.businessNo }}</small></div><div><span class="status-chip">{{ expenseStatuses[procurement.status] ?? procurement.status }}</span><small>第 {{ procurement.roundNo }} 轮</small></div><time :datetime="procurement.createdAt">{{ dateLabel(procurement.createdAt) }}</time><button class="secondary" :disabled="locked" :aria-label="`查看采购付款申请 ${procurement.businessNo}`" @click="emit('open', procurement.applicationId)">查看</button></article></div>
      <p v-if="query.loading" class="procurement-empty" role="status">正在读取本人采购付款申请…</p><div v-else-if="!query.error && !query.items.length" class="procurement-empty"><strong>暂无符合条件的采购付款申请</strong><p>保存后的采购付款申请显示在这里，可继续核对收款账户并提交审批。</p></div>
      <button v-if="query.nextBeforeId" class="secondary more-procurements" :disabled="query.loading" @click="load(true)">加载更多采购付款</button>
    </template>
  </section>
</template>

<style scoped>
.procurement-toolbar,.procurement-toolbar>div{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.procurement-toolbar{justify-content:space-between}.procurement-toolbar label{display:flex;align-items:center;gap:10px;font-size:12px}.procurement-toolbar select{padding:9px 12px;min-width:130px}.procurement-help{font-size:12px;color:var(--muted);line-height:1.8}.procurement-ledger{background:white;border:1px solid var(--line);border-radius:12px;overflow:hidden}.procurement-row{display:grid;grid-template-columns:minmax(0,2fr) minmax(100px,1fr) 155px 70px;align-items:center;gap:16px;padding:20px;border-bottom:1px solid var(--line)}.procurement-row:last-child{border-bottom:0}.procurement-row strong{font-size:13px;overflow-wrap:anywhere;line-height:1.6}.procurement-row small{display:block;font-size:10px;color:var(--muted);margin-top:7px;overflow-wrap:anywhere}.procurement-row time{font-size:10px;color:var(--muted)}.procurement-row button{font-size:11px}.procurement-empty{padding:36px 20px;text-align:center;border:1px dashed var(--line);border-radius:12px;color:var(--muted);font-size:12px;line-height:1.8}.procurement-empty strong{font-size:14px;color:var(--ink)}.procurement-error{font-size:12px;line-height:1.8;color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.more-procurements{margin-top:18px}@media(max-width:800px){.procurement-row{grid-template-columns:minmax(0,1fr) auto}.procurement-row time{grid-column:1}.procurement-row button{grid-column:2}}@media(max-width:500px){.procurement-row{padding:15px}}
</style>
