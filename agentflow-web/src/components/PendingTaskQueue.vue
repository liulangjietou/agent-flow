<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type PendingTaskItem, type PendingTaskQuery } from '../api'
import { PendingTaskQueueQuery } from '../pendingTaskQueue'
import DefinitionPicker from './DefinitionPicker.vue'
import PendingTaskCard from './PendingTaskCard.vue'
import TaskBatchPanel from './TaskBatchPanel.vue'
const props = defineProps<{ view: 'list' | 'board'; scopeKey: string; refreshVersion: number; locked: boolean; selectedId?: string }>()
const emit = defineEmits<{ select: [item: PendingTaskItem]; clearSelection: []; changed: []; 'update:view': [view: 'list' | 'board'] }>()
const query = reactive(new PendingTaskQueueQuery(api.taskPage))
const filters = reactive({ q: '', processKey: '', applicant: '', organization: '', assignment: 'all', deadline: 'all', risk: 'all', minAmount: '', maxAmount: '' })
const applied = ref<PendingTaskQuery>({})
const expanded = ref(false)
const batchOpen = ref(false)
const panel = ref<HTMLElement | null>(null)
// 分栏只投影已加载的真实任务，不作为权限判断或审批命令来源。
const lanes = computed(() => [
  { key: 'unclaimed', title: '待领取', hint: '领取后由你继续处理', items: query.items.filter(item => item.delegationState !== 'PENDING' && !item.assignee) },
  { key: 'assigned', title: '已指派', hint: '已分配给你的审批', items: query.items.filter(item => item.delegationState !== 'PENDING' && !!item.assignee) },
  { key: 'delegated', title: '待回交', hint: '填写核实意见，交回原审批人', items: query.items.filter(item => item.delegationState === 'PENDING') }
])
/** 从详情回到原卡片；任务已离开队列时回到队列标题。 */
function focusTask(id?: string) {
  const target = [...(panel.value?.querySelectorAll<HTMLButtonElement>('[data-task-id]') ?? [])].find(button => button.dataset.taskId === id) ?? panel.value
  target?.scrollIntoView({ block: 'center' }); target?.focus({ preventScroll: true })
}
defineExpose({ focusTask })
const filterDirty = ref(false)
watch(filters, () => { filterDirty.value = true }, { flush: 'sync' })
function refresh() { void query.load(props.scopeKey, applied.value) }
/** 打开批量清单前关闭旧任务详情，办理完成后刷新实际工作台。 */
function openBatch() { if (!props.locked && !query.loading && query.items.length) { emit('clearSelection'); batchOpen.value = true } }
function search() {
  applied.value = { ...filters, q: filters.q.trim(), applicant: filters.applicant.trim(), organization: filters.organization.trim(), assignment: filters.assignment as PendingTaskQuery['assignment'], deadline: filters.deadline as PendingTaskQuery['deadline'], risk: filters.risk as PendingTaskQuery['risk'] }
  filterDirty.value = false; emit('clearSelection'); refresh()
}
function reset() {
  Object.assign(filters, { q: '', processKey: '', applicant: '', organization: '', assignment: 'all', deadline: 'all', risk: 'all', minAmount: '', maxAmount: '' })
  search()
}
watch([() => props.scopeKey, () => props.refreshVersion], refresh, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section ref="panel" tabindex="-1" class="queue panel pending-queue" :class="{ 'board-view': view === 'board' }" aria-labelledby="pending-queue-title">
    <div class="panel-head"><div><h3 id="pending-queue-title">待办队列 <span v-if="query.loaded" class="count">{{ query.total }}</span></h3><p>按进入待办的时间排列，较早任务优先</p></div><button class="quiet" :disabled="locked || batchOpen || query.loading" @click="refresh">刷新队列</button></div>
    <div class="queue-view-bar"><div class="queue-view-switch" role="group" aria-label="待办展示方式"><button type="button" :disabled="locked || batchOpen" :aria-pressed="view === 'list'" @click="emit('update:view', 'list')">列表</button><button type="button" :disabled="locked || batchOpen" :aria-pressed="view === 'board'" @click="emit('update:view', 'board')">看板</button></div><span v-if="view === 'board'">按办理状态分栏，列内较早任务优先</span></div>
    <form class="task-filters" @submit.prevent="search">
      <fieldset :disabled="locked || batchOpen"><legend class="sr-only">筛选待办</legend>
        <div class="search-line"><label class="search-box"><span class="sr-only">搜索待办</span><input v-model="filters.q" type="search" maxlength="100" placeholder="单号、申请标题或任务名称" /></label><button class="secondary" type="submit">查询</button></div>
        <div class="filter-line"><label>办理范围<select v-model="filters.assignment"><option value="all">全部可办理</option><option value="assigned">指派给我</option><option value="unclaimed">待领取</option><option value="delegated">待我回交</option></select></label><button class="quiet" type="button" :aria-expanded="expanded" aria-controls="task-more-filters" @click="expanded = !expanded">{{ expanded ? '收起筛选 −' : '更多筛选 ＋' }}</button></div>
        <div v-show="expanded" id="task-more-filters" class="more-filters">
          <label>流程标识<input v-model="filters.processKey" maxlength="128" placeholder="全部流程，可输入准确标识" /></label>
          <DefinitionPicker :scope-key="scopeKey" label="待办流程" published-only :selected-label="filters.processKey || '全部流程'" :locked="locked" @select="filters.processKey = $event.key" />
          <label>申请人账号<input v-model="filters.applicant" maxlength="128" placeholder="输入完整账号" /></label>
          <label>本轮组织<input v-model="filters.organization" maxlength="128" placeholder="法人、部门或岗位名称" /></label>
          <label>处理期限<select v-model="filters.deadline"><option value="all">全部期限状态</option><option value="overdue">已超时</option><option value="pending">未到期</option><option value="unrecorded">未记录期限</option></select></label>
          <label>提交时风险<select v-model="filters.risk"><option value="all">全部风险状态</option><option value="high">高风险</option><option value="medium">中风险</option><option value="low">低风险</option><option value="unmatched">规则未命中</option><option value="unassessed">未评估</option></select></label>
          <p>风险依据本轮提交时的流程规则；未评估和规则未命中均不代表低风险。</p>
          <div class="amount-range"><label>最低金额<input v-model="filters.minAmount" inputmode="decimal" maxlength="80" placeholder="不限" /></label><span aria-hidden="true">—</span><label>最高金额<input v-model="filters.maxAmount" inputmode="decimal" maxlength="80" placeholder="不限" /></label></div>
          <p>金额范围仅匹配可识别的数值金额；未提供金额的申请不会计入。</p>
          <p>组织按本轮提交时的名称匹配；未记录任职的申请不参与组织筛选。</p>
          <p v-if="applied.deadline && applied.deadline !== 'all'">期限按查询时刻筛选；跨过截止时间后请刷新队列。</p>
        </div>
        <div class="filter-footer"><span v-if="filterDirty">筛选已修改，点击查询生效</span><span v-else>显示已应用的筛选结果</span><button type="button" class="quiet" @click="reset">清空筛选</button></div>
      </fieldset>
    </form>
    <div class="batch-entry"><button type="button" class="secondary" :disabled="locked || query.loading || !query.items.length || batchOpen" @click="openBatch">批量领取 / 释放</button></div>
    <TaskBatchPanel v-if="batchOpen" :items="query.items" :scope-key="scopeKey" :locked="locked" @close="batchOpen = false" @changed="emit('changed')" />
    <div v-if="query.error" class="queue-error" role="alert"><p>{{ query.error }}</p><button class="secondary" :disabled="locked || batchOpen || query.loading" @click="query.loaded ? query.more() : refresh()">重新读取</button></div>
    <p v-if="query.loading && !query.loaded" class="queue-empty" role="status">正在读取待办…</p>
    <div v-else-if="query.loaded && !query.items.length" class="queue-empty"><strong>当前没有匹配任务</strong><p>清空筛选或刷新队列，查看当前可办理的申请。</p></div>
    <template v-else-if="query.loaded && !batchOpen">
      <div v-if="view === 'board'" class="task-board" aria-label="待办看板">
        <p class="board-page-note">各列数字为已加载条数{{ query.nextCursor ? '，底部可继续加载更多任务' : '' }}。待回交任务从已指派中单独列出。</p>
        <div class="board-lanes">
          <section v-for="lane in lanes" :key="lane.key" class="board-lane" :class="lane.key" :aria-label="`${lane.title}任务列`">
            <div class="lane-heading"><h4>{{ lane.title }}<span>{{ lane.items.length }}</span></h4><p>{{ lane.hint }}</p></div>
            <ol v-if="lane.items.length" :aria-label="`${lane.title}任务`"><li v-for="item in lane.items" :key="item.taskId"><PendingTaskCard :item="item" :selected="selectedId === item.taskId" :locked="locked" @select="emit('select', $event)" /></li></ol>
            <p v-else class="lane-empty">已加载任务中暂无此类{{ query.nextCursor ? '，可继续加载或使用办理范围筛选。' : '。' }}</p>
          </section>
        </div>
      </div>
      <ol v-else class="pending-list" aria-label="待办任务"><li v-for="item in query.items" :key="item.taskId"><PendingTaskCard :item="item" :selected="selectedId === item.taskId" :locked="locked" @select="emit('select', $event)" /></li></ol>
    </template>
    <div v-if="query.loaded && query.items.length" class="queue-footer"><span>已加载 {{ query.items.length }} 条 · 当前匹配 {{ query.total }} 条</span><button v-if="query.nextCursor" class="secondary" :disabled="locked || batchOpen || query.loading" @click="query.more">{{ query.loading ? '正在加载…' : '加载更多' }}</button><span v-else>已到末页</span></div>
  </section>
</template>

<style scoped>
.batch-entry{padding:12px 20px}.pending-queue{min-width:0;align-self:start}.panel-head{gap:12px}.panel-head .quiet{white-space:nowrap;font-size:11px}.task-filters{padding:0 20px 15px;border-bottom:1px solid var(--line)}fieldset{border:0;padding:0;margin:0;min-width:0}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.search-line,.filter-line,.filter-footer{display:flex;gap:10px;align-items:center;justify-content:space-between}.search-box{flex:1;min-width:0}.task-filters input,.task-filters select{width:100%;min-width:0;padding:9px 10px;border:1px solid var(--line);border-radius:7px;background:#fff;color:var(--ink);font-size:12px}.task-filters label{display:flex;flex-direction:column;gap:6px;font-size:11px;color:var(--muted);min-width:0}.filter-line{margin-top:14px;align-items:flex-end}.filter-line label{flex:1}.filter-line .quiet{white-space:nowrap;font-size:11px;padding:10px 0}.more-filters{display:grid;gap:12px;margin-top:14px;padding-top:14px;border-top:1px dashed var(--line)}.amount-range{display:grid;grid-template-columns:minmax(0,1fr) auto minmax(0,1fr);gap:8px;align-items:end}.amount-range>span{padding-bottom:10px;color:var(--muted)}.more-filters p{font-size:11px;color:var(--muted);line-height:1.7;margin:0}.filter-footer{margin-top:10px;flex-wrap:wrap;gap:4px;font-size:10px;color:var(--muted)}.filter-footer .quiet{font-size:11px;padding:5px 0}.pending-list{list-style:none;margin:0;padding:0;max-height:min(60vh,720px);overflow-y:auto;overscroll-behavior:contain}.queue-footer{padding:16px 20px;display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between;color:var(--muted);font-size:11px}.queue-error{padding:16px 20px;color:var(--red);font-size:12px}
@media(max-width:650px){.task-filters{padding-left:14px;padding-right:14px}.queue-footer{padding:14px}.panel-head{padding-left:14px;padding-right:14px}}
.queue-view-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;padding:0 20px 16px}
.queue-view-bar>span{font-size:11px;color:var(--muted)}
.queue-view-switch{display:flex;padding:3px;border:1px solid var(--line);border-radius:8px;background:var(--paper);gap:3px}
.queue-view-switch button{padding:6px 17px;border-radius:5px;color:var(--muted);font-size:11px}
.queue-view-switch button[aria-pressed="true"]{background:white;color:var(--deep);box-shadow:0 1px 4px #17342a12;font-weight:700}
.queue-view-switch button:focus-visible{outline:3px solid #20a18c60;outline-offset:2px}
.board-view .task-filters fieldset{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(0,1fr);gap:10px 20px}
.board-view .filter-line{margin:0}.board-view .filter-footer,.board-view .more-filters{grid-column:1/-1}
.board-view .more-filters{grid-template-columns:minmax(0,1fr) minmax(0,1fr) minmax(0,1.4fr);align-items:end}
.board-view .more-filters p{grid-column:1/-1}
.task-board{padding:0 16px 16px}.board-page-note{font-size:11px;color:var(--muted);line-height:1.8;padding:0 4px}
.board-lanes{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;align-items:start}
.board-lane{min-width:0;border:1px solid var(--line);border-radius:10px;background:#f6f8f7;overflow:hidden;border-top:3px solid var(--amber)}
.board-lane.assigned{border-top-color:var(--teal)}.board-lane.delegated{border-top-color:var(--purple)}
.lane-heading{padding:16px 14px 13px}.lane-heading h4{font-size:13px;margin:0;display:flex;align-items:center;justify-content:space-between}
.lane-heading h4 span{font:12px 'DM Mono',monospace;color:var(--muted)}.lane-heading p{font-size:10px;color:var(--muted);margin:7px 0 0;line-height:1.6}
.board-lane ol{list-style:none;margin:0;padding:0 8px 8px;max-height:min(55vh,620px);overflow:auto;overscroll-behavior:contain}
.board-lane li+li{margin-top:8px}.board-lane :deep(.pending-row){padding:14px 12px;border:1px solid var(--line);border-left:3px solid transparent;border-radius:8px}
.board-lane :deep(.pending-row.chosen){border-color:#a8d9d1;border-left-color:var(--teal)}
.lane-empty{padding:24px 14px;margin:0;color:var(--muted);font-size:11px;line-height:1.8;min-height:112px}
@media(max-width:850px){.board-view .more-filters{grid-template-columns:minmax(0,1fr)}.board-lanes{grid-template-columns:minmax(0,1fr)}.board-lane ol{max-height:46vh}.board-lane .lane-empty{min-height:0;padding:0 14px 18px}}
@media(max-width:650px){.queue-view-bar{padding:0 14px 14px}.board-view .task-filters fieldset{grid-template-columns:minmax(0,1fr)}.task-board{padding:0 12px 12px}}
</style>
