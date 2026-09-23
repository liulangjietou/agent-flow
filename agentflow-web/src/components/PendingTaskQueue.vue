<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api, type Definition, type PendingTaskItem, type PendingTaskQuery } from '../api'
import { PendingTaskQueueQuery } from '../pendingTaskQueue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean; selectedId?: string; definitions: Definition[] }>()
const emit = defineEmits<{ select: [item: PendingTaskItem]; clearSelection: [] }>()
const query = reactive(new PendingTaskQueueQuery(api.taskPage))
const filters = reactive({ q: '', processKey: '', applicant: '', assignment: 'all', minAmount: '', maxAmount: '' })
const applied = ref<PendingTaskQuery>({})
const expanded = ref(false)
const filterDirty = ref(false)
watch(filters, () => { filterDirty.value = true }, { flush: 'sync' })
function refresh() { void query.load(props.scopeKey, applied.value) }
function search() {
  applied.value = { ...filters, q: filters.q.trim(), applicant: filters.applicant.trim(), assignment: filters.assignment as PendingTaskQuery['assignment'] }
  filterDirty.value = false; emit('clearSelection'); refresh()
}
function reset() {
  Object.assign(filters, { q: '', processKey: '', applicant: '', assignment: 'all', minAmount: '', maxAmount: '' })
  search()
}
watch([() => props.scopeKey, () => props.refreshVersion], refresh, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>

<template>
  <section class="queue panel pending-queue" aria-labelledby="pending-queue-title">
    <div class="panel-head"><div><h3 id="pending-queue-title">待办队列 <span v-if="query.loaded" class="count">{{ query.total }}</span></h3><p>按进入待办的时间排列，较早任务优先</p></div><button class="quiet" :disabled="locked || query.loading" @click="refresh">刷新队列</button></div>
    <form class="task-filters" @submit.prevent="search">
      <fieldset :disabled="locked"><legend class="sr-only">筛选待办</legend>
        <div class="search-line"><label class="search-box"><span class="sr-only">搜索待办</span><input v-model="filters.q" type="search" maxlength="100" placeholder="单号、申请标题或任务名称" /></label><button class="secondary" type="submit">查询</button></div>
        <div class="filter-line"><label>办理范围<select v-model="filters.assignment"><option value="all">全部可办理</option><option value="assigned">指派给我</option><option value="unclaimed">待领取</option><option value="delegated">待我回交</option></select></label><button class="quiet" type="button" :aria-expanded="expanded" aria-controls="task-more-filters" @click="expanded = !expanded">{{ expanded ? '收起筛选 −' : '更多筛选 ＋' }}</button></div>
        <div v-show="expanded" id="task-more-filters" class="more-filters">
          <label>流程标识<input v-model="filters.processKey" list="pending-process-options" maxlength="128" placeholder="全部流程，可选择或输入" /><datalist id="pending-process-options"><option v-for="definition in definitions.filter((item, index, all) => all.findIndex(other => other.key === item.key) === index)" :key="definition.key" :value="definition.key">{{ definition.name }}</option></datalist></label>
          <label>申请人账号<input v-model="filters.applicant" maxlength="128" placeholder="输入完整账号" /></label>
          <div class="amount-range"><label>最低金额<input v-model="filters.minAmount" inputmode="decimal" maxlength="80" placeholder="不限" /></label><span aria-hidden="true">—</span><label>最高金额<input v-model="filters.maxAmount" inputmode="decimal" maxlength="80" placeholder="不限" /></label></div>
          <p>金额范围仅匹配可识别的数值金额；未提供金额的申请不会计入。</p>
        </div>
        <div class="filter-footer"><span v-if="filterDirty">筛选已修改，点击查询生效</span><span v-else>显示已应用的筛选结果</span><button type="button" class="quiet" @click="reset">清空筛选</button></div>
      </fieldset>
    </form>
    <div v-if="query.error" class="queue-error" role="alert"><p>{{ query.error }}</p><button class="secondary" :disabled="locked || query.loading" @click="query.loaded ? query.more() : refresh()">重新读取</button></div>
    <p v-if="query.loading && !query.loaded" class="queue-empty" role="status">正在读取待办…</p>
    <div v-else-if="query.loaded && !query.items.length" class="queue-empty"><strong>当前没有匹配任务</strong><p>清空筛选或刷新队列，查看当前可办理的申请。</p></div>
    <ol v-else class="pending-list" aria-label="待办任务">
      <li v-for="item in query.items" :key="item.taskId"><button type="button" class="pending-row" :class="{ chosen: selectedId === item.taskId }" :aria-pressed="selectedId === item.taskId" :disabled="locked" @click="emit('select', item)">
        <span class="pending-node">{{ item.taskName }}<span>{{ item.delegationState === 'PENDING' ? '待回交' : item.assignee ? '已指派' : '待领取' }}</span></span>
        <strong>{{ item.title }}</strong><span class="pending-number">{{ item.businessNo }}</span>
        <span class="pending-facts"><span>申请人 {{ item.applicant }}</span><span>{{ item.amount == null ? '无可用金额' : '金额 ' + item.amount }}</span></span>
        <span class="pending-process">{{ item.processKey }} · v{{ item.definitionVersion }} · 第 {{ item.roundNo }} 轮</span>
        <time :datetime="item.createdAt">{{ dateLabel(item.createdAt) }} 进入待办</time>
      </button></li>
    </ol>
    <div v-if="query.loaded && query.items.length" class="queue-footer"><span>已加载 {{ query.items.length }} 条 · 当前匹配 {{ query.total }} 条</span><button v-if="query.nextCursor" class="secondary" :disabled="locked || query.loading" @click="query.more">{{ query.loading ? '正在加载…' : '加载更多' }}</button><span v-else>已到末页</span></div>
  </section>
</template>

<style scoped>
.pending-queue{min-width:0;align-self:start}.panel-head{gap:12px}.panel-head .quiet{white-space:nowrap;font-size:11px}.task-filters{padding:0 20px 15px;border-bottom:1px solid var(--line)}fieldset{border:0;padding:0;margin:0;min-width:0}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.search-line,.filter-line,.filter-footer{display:flex;gap:10px;align-items:center;justify-content:space-between}.search-box{flex:1;min-width:0}.task-filters input,.task-filters select{width:100%;min-width:0;padding:9px 10px;border:1px solid var(--line);border-radius:7px;background:#fff;color:var(--ink);font-size:12px}.task-filters label{display:flex;flex-direction:column;gap:6px;font-size:11px;color:var(--muted);min-width:0}.filter-line{margin-top:14px;align-items:flex-end}.filter-line label{flex:1}.filter-line .quiet{white-space:nowrap;font-size:11px;padding:10px 0}.more-filters{display:grid;gap:12px;margin-top:14px;padding-top:14px;border-top:1px dashed var(--line)}.amount-range{display:grid;grid-template-columns:minmax(0,1fr) auto minmax(0,1fr);gap:8px;align-items:end}.amount-range>span{padding-bottom:10px;color:var(--muted)}.more-filters p{font-size:11px;color:var(--muted);line-height:1.7;margin:0}.filter-footer{margin-top:10px;flex-wrap:wrap;gap:4px;font-size:10px;color:var(--muted)}.filter-footer .quiet{font-size:11px;padding:5px 0}.pending-list{list-style:none;margin:0;padding:0;max-height:min(60vh,720px);overflow-y:auto;overscroll-behavior:contain}.pending-row{display:flex;flex-direction:column;text-align:left;gap:7px;width:100%;padding:20px;border-bottom:1px solid var(--line);border-left:3px solid transparent;background:#fff;overflow-wrap:anywhere}.pending-row:hover{background:#f7fbfa}.pending-row.chosen{background:var(--soft);border-left-color:var(--teal)}.pending-node{display:flex;justify-content:space-between;gap:8px;width:100%;font-size:11px;color:var(--deep)}.pending-node>span{background:var(--paper);padding:2px 7px;border-radius:4px;white-space:nowrap}.pending-row>strong{font-size:14px;line-height:1.6}.pending-number{font:10px 'DM Mono',monospace;color:var(--muted)}.pending-facts{display:flex;gap:10px;flex-wrap:wrap;justify-content:space-between;width:100%;font-size:12px;line-height:1.7}.pending-process,.pending-row time{font-size:10px;color:var(--muted);line-height:1.6}.queue-footer{padding:16px 20px;display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between;color:var(--muted);font-size:11px}.queue-error{padding:16px 20px;color:var(--red);font-size:12px}
@media(max-width:650px){.task-filters{padding-left:14px;padding-right:14px}.pending-row{padding:18px 14px}.queue-footer{padding:14px}.panel-head{padding-left:14px;padding-right:14px}.pending-facts{font-size:11px}}
</style>
