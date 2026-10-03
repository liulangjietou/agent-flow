<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ExpensePageQuery, expenseStatuses } from '../expenses'
import AdvanceRequestEditor from './AdvanceRequestEditor.vue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked?: boolean }>()
const emit = defineEmits<{ open: [applicationId: string] }>()
const query = reactive(new ExpensePageQuery(api.advanceRequests)), status = ref(''), editing = ref(false)
function load(more = false) { return query.load(props.scopeKey, status.value || undefined, more) }
watch(() => JSON.stringify([props.scopeKey, props.refreshVersion, status.value]), () => { query.clear(); void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="advance-workspace" aria-label="我的借款申请">
    <AdvanceRequestEditor v-if="editing" :scope-key="scopeKey" :locked="locked" @close="editing = false; load()" @submitted="editing = false; load(); emit('open', $event)" />
    <template v-else>
      <div class="advance-toolbar"><label>借款状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, value) in expenseStatuses" :key="value" :value="value">{{ label }}</option></select></label><div><button class="secondary" :disabled="query.loading" @click="load()">刷新借款记录</button><button class="primary" :disabled="locked" @click="editing = true">＋ 填写借款申请</button></div></div>
      <p class="advance-help">填写借款用途、金额与归还日。审批通过后等待结算，实际付款成功后才形成可冲销余额。</p>
      <p v-if="query.error" class="advance-error" role="alert">{{ query.error }}</p>
      <div v-if="query.items.length" class="advance-ledger"><article v-for="advance in query.items" :key="advance.id" class="advance-row"><div><strong>{{ advance.title }}</strong><small>{{ advance.businessNo }}</small></div><div><span class="status-chip">{{ expenseStatuses[advance.status] ?? advance.status }}</span><small>第 {{ advance.roundNo }} 轮</small></div><time :datetime="advance.createdAt">{{ dateLabel(advance.createdAt) }}</time><button class="secondary" :disabled="locked" :aria-label="`查看借款申请 ${advance.businessNo}`" @click="emit('open', advance.applicationId)">查看</button></article></div>
      <p v-if="query.loading" class="advance-empty" role="status">正在读取本人借款申请…</p><div v-else-if="!query.error && !query.items.length" class="advance-empty"><strong>暂无符合条件的借款申请</strong><p>保存后的借款申请显示在这里，可继续核对收款账户并提交审批。</p></div>
      <button v-if="query.nextBeforeId" class="secondary more-advances" :disabled="query.loading" @click="load(true)">加载更多借款</button>
    </template>
  </section>
</template>

<style scoped>
.advance-toolbar,.advance-toolbar>div{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.advance-toolbar{justify-content:space-between}.advance-toolbar label{display:flex;align-items:center;gap:10px;font-size:12px}.advance-toolbar select{padding:9px 12px;min-width:130px}.advance-help{font-size:12px;color:var(--muted);line-height:1.8}.advance-ledger{background:white;border:1px solid var(--line);border-radius:12px;overflow:hidden}.advance-row{display:grid;grid-template-columns:minmax(0,2fr) minmax(100px,1fr) 155px 70px;align-items:center;gap:16px;padding:20px;border-bottom:1px solid var(--line)}.advance-row:last-child{border-bottom:0}.advance-row strong{font-size:13px;overflow-wrap:anywhere;line-height:1.6}.advance-row small{display:block;font-size:10px;color:var(--muted);margin-top:7px;overflow-wrap:anywhere}.advance-row time{font-size:10px;color:var(--muted)}.advance-row button{font-size:11px}.advance-empty{padding:36px 20px;text-align:center;border:1px dashed var(--line);border-radius:12px;color:var(--muted);font-size:12px;line-height:1.8}.advance-empty strong{font-size:14px;color:var(--ink)}.advance-error{font-size:12px;line-height:1.8;color:var(--red);background:#fff0ed;padding:12px;border-radius:8px}.more-advances{margin-top:18px}@media(max-width:800px){.advance-row{grid-template-columns:minmax(0,1fr) auto}.advance-row time{grid-column:1}.advance-row button{grid-column:2}}@media(max-width:500px){.advance-row{padding:15px}}
</style>
