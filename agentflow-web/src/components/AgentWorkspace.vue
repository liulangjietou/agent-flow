<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api, type PendingTaskItem, type PendingTaskQuery } from '../api'
import { PendingTaskQueueQuery } from '../pendingTaskQueue'
import AgentUsagePanel from './AgentUsagePanel.vue'
import TaskDeadlineStatus from './TaskDeadlineStatus.vue'
import WorkspaceIcon from './WorkspaceIcon.vue'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ select: [task: PendingTaskItem]; navigate: [page: 'expense' | 'applications'] }>()
const query = reactive(new PendingTaskQueueQuery(api.taskPage))
const searchText = ref(''), assignment = ref<PendingTaskQuery['assignment']>('all')
const applied = ref<PendingTaskQuery>({})

/** 仅查询本人当前可办理的任务；打开后由工作台重新核验权限和版本。 */
function refresh() { return query.load(props.scopeKey, applied.value) }
function search() {
  if (props.locked) return
  applied.value = { q: searchText.value.trim(), assignment: assignment.value }
  void refresh()
}
function reset() { searchText.value = ''; assignment.value = 'all'; search() }
function open(task: PendingTaskItem) { if (!props.locked) emit('select', task) }
function navigate(page: 'expense' | 'applications') { if (!props.locked) emit('navigate', page) }
watch(() => [props.scopeKey, props.refreshVersion], refresh, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="content agent-workspace" aria-labelledby="agent-workspace-title">
    <div class="page-heading">
      <div><p class="eyebrow">ASSISTED WORKFLOW</p><h2 id="agent-workspace-title">Agent 助理</h2><p class="subhead">从具体单据开始，让助手整理依据，由你核对并完成下一步。</p></div>
      <button type="button" class="secondary" :disabled="locked" @click="navigate('applications')">查看申请与历史摘要<WorkspaceIcon name="arrow" /></button>
    </div>

    <ol class="review-path" aria-label="智能辅助的办理顺序">
      <li><span class="path-icon"><WorkspaceIcon name="records" /></span><div><strong>选择材料</strong><p>打开申请，勾选本次可读内容</p></div></li>
      <li><span class="path-icon"><WorkspaceIcon name="send" /></span><div><strong>确认发送</strong><p>核对模型目的地与授权范围</p></div></li>
      <li><span class="path-icon"><WorkspaceIcon name="check" /></span><div><strong>复核与办理</strong><p>核对来源，采纳后单独处理审批</p></div></li>
    </ol>

    <div class="assistant-layout">
      <section class="assistant-inbox panel" aria-labelledby="assistant-inbox-title" :aria-busy="query.loading">
        <div class="inbox-heading"><div><p class="eyebrow">从待办开始</p><h3 id="assistant-inbox-title">选择需要辅助的申请<span v-if="query.loaded" class="task-total">{{ query.total }} 项</span></h3><p>打开后进入该任务的 Agent 摘要，选择材料后再生成。</p></div><button type="button" class="quiet" :disabled="locked || query.loading" @click="refresh"><WorkspaceIcon name="refresh" />刷新</button></div>
        <form class="assistant-search" @submit.prevent="search">
          <fieldset :disabled="locked"><legend class="sr-only">筛选智能辅助待办</legend>
            <label class="search-field"><span class="sr-only">搜索智能辅助待办</span><input v-model="searchText" type="search" maxlength="100" placeholder="搜索单号、申请标题或任务名称" /></label>
            <label class="assignment-field"><span class="sr-only">智能辅助办理范围</span><select v-model="assignment"><option value="all">全部可办理</option><option value="assigned">指派给我</option><option value="unclaimed">待领取</option><option value="delegated">待我回交</option></select></label>
            <button type="submit" class="secondary" :disabled="query.loading">查询</button>
          </fieldset>
        </form>
        <div v-if="query.error" class="assistant-error" role="alert"><p>{{ query.error }}</p><button type="button" class="secondary" :disabled="locked || query.loading" @click="query.loaded ? query.more() : refresh()">重新读取</button></div>
        <p v-if="query.loading && !query.loaded" class="assistant-empty" role="status">正在读取本人可办理的申请…</p>
        <div v-else-if="query.loaded && !query.items.length" class="assistant-empty"><WorkspaceIcon name="inbox" /><h4>{{ applied.q || applied.assignment && applied.assignment !== 'all' ? '没有符合条件的待办' : '当前没有可办理的待办' }}</h4><p>已有摘要可从申请记录查看；准备报销材料可使用右侧费用助手入口。</p><button v-if="applied.q || applied.assignment && applied.assignment !== 'all'" type="button" class="secondary" :disabled="locked" @click="reset">清空筛选</button></div>
        <ol v-if="query.loaded && query.items.length" class="assistant-tasks" aria-label="可使用智能辅助的待办">
          <li v-for="task in query.items" :key="task.taskId" class="assistant-task">
            <div class="task-context"><span>{{ task.taskName }}</span><span class="assignment-chip">{{ task.delegationState === 'PENDING' ? '待回交' : task.assignee ? '已指派' : '待领取' }}</span></div>
            <h4>{{ task.title }}</h4>
            <div class="task-metadata"><span>{{ task.businessNo }}</span><span>申请人 {{ task.applicant }}</span><span>第 {{ task.roundNo }} 轮</span></div>
            <TaskDeadlineStatus :due-at="task.dueAt" />
            <button type="button" class="secondary task-open" :disabled="locked" :aria-label="`核对材料：${task.title}（${task.businessNo}）`" @click="open(task)">核对材料<WorkspaceIcon name="arrow" /></button>
          </li>
        </ol>
        <div v-if="query.loaded && query.items.length" class="assistant-pagination"><span>已加载 {{ query.items.length }} / {{ query.total }} 项</span><button v-if="query.nextCursor" type="button" class="secondary" :disabled="locked || query.loading" @click="query.more">{{ query.loading ? '正在加载…' : '加载更多待办' }}</button><span v-else>已显示全部匹配待办</span></div>
      </section>

      <aside class="assistant-context" aria-label="费用辅助与使用说明">
        <section class="expense-entry"><span class="entry-icon"><WorkspaceIcon name="wallet" /></span><p class="eyebrow">申请人使用</p><h3>准备一份报销</h3><p>在已保存的报销单中，按办理目标整理票据、查询制度并核对预检问题。</p><ul><li>票据整理后逐项确认</li><li>费用建议核对后保存</li><li>预检通过后确认提交</li></ul><button type="button" class="primary" :disabled="locked" @click="navigate('expense')">进入财务申请<WorkspaceIcon name="arrow" /></button></section>
        <section class="assistant-boundaries"><h3>每一步都有依据</h3><p>发送前展示所选内容与模型目的地。执行记录、原始依据和人工复核分别保留。</p><p>助手建议不会自动批准申请。模型服务是否可用，以具体任务中的检查结果为准。</p></section>
      </aside>
    </div>
    <div class="assistant-usage"><AgentUsagePanel :scope-key="scopeKey" :refresh-version="refreshVersion" /></div>
  </section>
</template>

<style scoped>
.agent-workspace {
  max-width:1560px;
  margin:0 auto;
}

.sr-only {
  position:absolute;
  width:1px;
  height:1px;
  padding:0;
  margin:-1px;
  overflow:hidden;
  clip:rect(0,0,0,0);
  white-space:nowrap;
  border:0;
}

.page-heading>.secondary,.inbox-heading>.quiet,.task-open,.expense-entry>.primary {
  display:inline-flex;
  align-items:center;
  justify-content:center;
  gap:9px;
}

.review-path {
  display:grid;
  grid-template-columns:repeat(3,minmax(0,1fr));
  list-style:none;
  margin:26px 0;
  padding:20px 24px;
  border:1px solid var(--line);
  border-radius:12px;
  background:white;
  gap:24px;
}

.review-path li {
  display:flex;
  gap:13px;
  align-items:center;
  min-width:0;
  position:relative;
}

.review-path li+li {
  border-left:1px solid var(--line);
  padding-left:24px;
}

.path-icon {
  display:flex;
  padding:10px;
  background:var(--soft);
  color:var(--deep);
  border-radius:9px;
  flex-shrink:0;
}

.review-path strong {
  font-size:14px;
}

.review-path p {
  font-size:12px;
  color:var(--muted);
  margin:6px 0 0;
  line-height:1.65;
}

.assistant-layout {
  display:grid;
  grid-template-columns:minmax(0,1fr) 280px;
  gap:24px;
  align-items:start;
}

.assistant-inbox {
  min-width:0;
  overflow:hidden;
}

.inbox-heading {
  padding:24px;
  display:flex;
  gap:16px;
  align-items:start;
  justify-content:space-between;
}

.inbox-heading h3 {
  font-size:18px;
  margin:8px 0 0;
  line-height:1.5;
}

.inbox-heading .eyebrow {
  color:var(--deep);
}

.inbox-heading p:not(.eyebrow) {
  font-size:13px;
  color:var(--muted);
  line-height:1.8;
  margin:8px 0 0;
}

.inbox-heading>.quiet {
  white-space:nowrap;
  font-size:13px;
}

.task-total {
  font-size:12px;
  font-weight:500;
  color:var(--muted);
  margin-left:12px;
  white-space:nowrap;
}

.assistant-search {
  padding:0 24px 20px;
  border-bottom:1px solid var(--line);
}

.assistant-search fieldset {
  display:flex;
  gap:10px;
  align-items:center;
  margin:0;
  padding:0;
  border:0;
  min-width:0;
}

.assistant-search label {
  margin:0;
  min-width:0;
}

.search-field {
  flex:1;
}

.assignment-field {
  flex:0 0 126px;
}

.assistant-search input,.assistant-search select {
  width:100%;
  min-width:0;
  font:inherit;
  font-size:13px;
  padding:10px;
  border:1px solid var(--line);
  border-radius:7px;
  background:white;
}

.assistant-search button {
  flex-shrink:0;
}

.assistant-tasks {
  list-style:none;
  padding:0;
  margin:0;
  max-height:640px;
  overflow:auto;
}

.assistant-task {
  position:relative;
  padding:22px 166px 22px 24px;
  border-bottom:1px solid var(--line);
  overflow-wrap:anywhere;
}

.assistant-task:last-child {
  border-bottom:0;
}

.assistant-task:hover {
  background:#f8fbfc;
}

.task-context {
  display:flex;
  align-items:center;
  gap:10px;
  font-size:12px;
  color:var(--deep);
  line-height:1.6;
}

.assignment-chip {
  font-size:11px;
  padding:2px 7px;
  border:1px solid var(--line);
  border-radius:5px;
  color:var(--muted);
  white-space:nowrap;
  background:white;
}

.assistant-task h4 {
  font-size:16px;
  line-height:1.6;
  margin:10px 0 8px;
}

.task-metadata {
  display:flex;
  flex-wrap:wrap;
  gap:5px 14px;
  font-size:12px;
  line-height:1.75;
  color:var(--muted);
}

.task-metadata>span:first-child {
  font-family:var(--font-mono);
}

.task-open {
  position:absolute;
  right:24px;
  top:50%;
  transform:translateY(-50%);
  font-size:13px;
}

.task-open:active {
  transform:translateY(-50%);
}

.assistant-empty {
  padding:42px 24px;
  text-align:center;
  font-size:13px;
  color:var(--muted);
  line-height:1.8;
  margin:0;
}

.assistant-empty>.workspace-icon {
  width:30px;
  height:30px;
  color:var(--deep);
}

.assistant-empty h4 {
  font-size:16px;
  color:var(--ink);
  margin:14px 0 8px;
}

.assistant-empty p {
  max-width:420px;
  margin:0 auto 16px;
}

.assistant-error {
  padding:18px 24px;
  background:#fff3ee;
  color:var(--red);
  font-size:13px;
  line-height:1.8;
}

.assistant-error p {
  margin:0 0 10px;
}

.assistant-pagination {
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:12px;
  border-top:1px solid var(--line);
  padding:16px 24px;
  font-size:12px;
  color:var(--muted);
}

.assistant-context {
  display:grid;
  gap:22px;
}

.expense-entry {
  padding:26px 24px;
  border:1px solid #c6dedb;
  border-radius:12px;
  background:#edf6f5;
}

.entry-icon {
  display:inline-flex;
  padding:11px;
  background:white;
  color:var(--deep);
  border:1px solid #c6dedb;
  border-radius:10px;
  margin-bottom:22px;
}

.expense-entry .eyebrow {
  color:var(--deep);
}

.expense-entry h3 {
  font-size:23px;
  line-height:1.4;
  letter-spacing:-.5px;
  margin:10px 0 14px;
}

.expense-entry p:not(.eyebrow) {
  font-size:13px;
  line-height:1.9;
  color:var(--muted);
  margin:0;
}

.expense-entry ul {
  list-style:disc;
  padding:16px 0 16px 16px;
  margin:12px 0 18px;
  border-top:1px solid #c6dedb;
  border-bottom:1px solid #c6dedb;
  font-size:13px;
  color:var(--deep);
}

.expense-entry li+li {
  margin-top:12px;
}

.expense-entry>.primary {
  width:100%;
  font-size:13px;
}

.assistant-boundaries {
  padding:0 6px;
}

.assistant-boundaries h3 {
  font-size:14px;
  margin:0 0 12px;
}

.assistant-boundaries p {
  color:var(--muted);
  font-size:12px;
  line-height:1.9;
  margin:8px 0;
}

.assistant-usage {
  margin-top:24px;
}

@media(min-width:901px) and (max-width:1250px) {
  .assistant-layout {
    grid-template-columns:minmax(0,1fr) 236px;
    gap:18px;
  }
  .review-path {
    padding:18px;
    gap:14px;
  }
  .review-path li+li {
    padding-left:14px;
  }
  .path-icon {
    padding:8px;
  }
  .inbox-heading {
    padding:20px;
  }
  .assistant-search {
    padding:0 20px 18px;
  }
  .assistant-search fieldset {
    flex-wrap:wrap;
  }
  .search-field {
    flex-basis:100%;
  }
  .assignment-field {
    flex:1;
  }
  .assistant-task {
    padding:20px;
  }
  .task-open {
    position:static;
    transform:none;
    margin-top:14px;
  }
  .task-open:active {
    transform:none;
  }
  .expense-entry {
    padding:22px 18px;
  }
  .expense-entry h3 {
    font-size:21px;
  }
}
</style>
