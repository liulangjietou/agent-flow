<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { WorkspaceRecordsQuery, type WorkspaceMode } from '../workspaceRecords'

const props = defineProps<{ scopeKey: string; mode: WorkspaceMode; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ open: [id: string]; create: [] }>()
const query = reactive(new WorkspaceRecordsQuery((mode, filters, signal) => mode === 'handled' ? api.workspaceHandled(filters, signal) : api.workspaceApplications(filters, signal)))
const text = ref(''), filter = ref('')
const statusLabels: Record<string, string> = { DRAFT: '草稿', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已驳回', APPROVED: '已批准', CANCELLED: '已作废', REVOKED: '已撤销' }
const actions: Record<string, string> = { APPROVE: '批准', RETURN: '退回', REJECT: '驳回', TRANSFER: '转交', DELEGATE: '委派', RESOLVE: '回交' }
const heading = computed(() => ({ started: '我发起', drafts: '我的草稿', handled: '已办记录' })[props.mode])
const description = computed(() => ({ started: '跟进本人发起的每一份申请，查看进度或继续修改。', drafts: '继续填写尚未提交的申请。已退回、已撤回的申请请到“我发起”查看。', handled: '回看本人每次办理的动作与意见，跟进申请当前进度。' })[props.mode])
const empty = computed(() => ({ started: '还没有发起申请', drafts: '没有待填写的草稿', handled: '还没有办理记录' })[props.mode])
const stateLabel = (status: string | null) => status ? statusLabels[status] ?? status : '未记录'
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
function refresh() { void query.load(props.scopeKey, props.mode, text.value, filter.value) }
watch([() => props.scopeKey, () => props.mode], () => { text.value = ''; filter.value = ''; refresh() }, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
onUnmounted(() => query.clear())
</script>

<template>
  <section class="content workspace-records" :aria-busy="query.loading">
    <div class="page-heading"><div><p class="eyebrow">PERSONAL WORKSPACE</p><h2>{{ heading }}</h2><p class="subhead">{{ description }}</p></div><button v-if="mode !== 'handled'" class="primary" :disabled="locked" @click="emit('create')">＋ 发起申请</button></div>
    <form class="panel workspace-filters" @submit.prevent="refresh">
      <label class="workspace-search">搜索申请<input v-model="text" maxlength="100" placeholder="标题、单号或流程标识" type="search" /></label>
      <label v-if="mode !== 'drafts'">{{ mode === 'handled' ? '办理动作' : '申请状态' }}<select v-model="filter" @change="refresh"><option value="">全部{{ mode === 'handled' ? '动作' : '状态' }}</option><option v-for="(label, value) in mode === 'handled' ? actions : statusLabels" :key="value" :value="value">{{ label }}</option></select></label>
      <button class="secondary" :disabled="query.loading">查询</button>
    </form>
    <div class="workspace-summary"><span>{{ mode === 'handled' ? '按办理时间倒序 · 同一申请可有多次办理' : '按创建时间倒序' }}</span><span v-if="query.loaded">已加载 {{ query.items.length }} 条{{ mode === 'handled' ? '办理记录' : '申请' }}</span></div>
    <div v-if="query.error" class="workspace-error panel" role="alert"><div><strong>{{ query.items.length ? '后续记录加载失败' : '暂时无法读取记录' }}</strong><p>{{ query.error }}</p></div><button class="secondary" :disabled="query.loading" @click="query.items.length ? query.more() : refresh()">重试</button></div>
    <div v-if="!query.items.length && query.loading" class="panel workspace-empty" role="status"><strong>正在读取{{ heading }}…</strong></div>
    <div v-else-if="!query.items.length && query.loaded" class="panel workspace-empty"><span aria-hidden="true">▤</span><h3>{{ text.trim() || filter ? '没有符合条件的记录' : empty }}</h3><p>{{ text.trim() || filter ? '调整搜索内容或筛选条件后重新查询。' : mode === 'handled' ? '完成批准、退回、驳回、转交、委派或回交后，可在这里回看。' : '选择已发布流程，填写第一份申请。' }}</p></div>
    <div v-if="query.items.length" class="panel workspace-list">
      <article v-for="item in query.items" :key="item.id" class="workspace-row">
        <div class="workspace-main"><div class="workspace-row-heading"><span class="workspace-icon" aria-hidden="true">{{ 'action' in item ? '✓' : '↗' }}</span><div><h3>{{ item.title }}</h3><p class="workspace-business">{{ item.businessNo }}</p></div></div>
          <p class="workspace-meta">{{ item.processKey }} · v{{ item.definitionVersion }}<template v-if="item.roundNo"> · 第 {{ item.roundNo }} 轮</template><template v-if="'action' in item && item.nodeName"> · {{ item.nodeName }}</template></p>
          <template v-if="'action' in item"><div class="workspace-decision"><strong>{{ actions[item.action] ?? item.action }}</strong><span v-if="item.targetUser">交给 {{ item.targetUser }}</span><span>办理后：{{ stateLabel(item.handledStatus) }}</span></div><p v-if="item.comment" class="workspace-comment">{{ item.comment }}</p></template>
        </div>
        <div class="workspace-state"><small>申请当前状态</small><span class="workspace-badge" :class="('action' in item ? item.applicationStatus : item.status).toLowerCase()">{{ stateLabel('action' in item ? item.applicationStatus : item.status) }}</span><time>{{ dateLabel('action' in item ? item.handledAt : item.createdAt) }}</time><small>{{ 'action' in item ? '办理时间' : '创建时间' }}</small></div>
        <button class="secondary workspace-open" :disabled="locked" @click="emit('open', 'action' in item ? item.applicationId : item.id)">{{ !('action' in item) && ['DRAFT', 'RETURNED', 'WITHDRAWN'].includes(item.status) ? '继续填写' : '查看详情' }} ↗</button>
      </article>
    </div>
    <div v-if="query.items.length" class="workspace-pagination"><button v-if="query.nextCursor" class="secondary" :disabled="query.loading" @click="query.more()">{{ query.loading ? '加载中…' : '加载更多' }}</button><span v-else>已加载全部匹配记录</span></div>
  </section>
</template>

<style scoped>
.workspace-filters{display:flex;align-items:end;gap:16px;padding:20px;margin-bottom:18px}.workspace-filters label{display:flex;flex-direction:column;gap:9px;font-size:12px;color:var(--muted);min-width:140px}.workspace-search{flex:1}.workspace-filters input,.workspace-filters select{width:100%;min-height:39px;padding:9px 12px;border:1px solid var(--line);border-radius:6px;background:white;color:var(--ink);font:inherit}.workspace-summary{display:flex;justify-content:space-between;gap:15px;margin:18px 0;font-size:11px;color:var(--muted)}.workspace-row{display:grid;grid-template-columns:minmax(0,1fr) 165px 112px;gap:24px;padding:25px 26px;align-items:center}.workspace-row+.workspace-row{border-top:1px solid var(--line)}.workspace-row-heading{display:flex;gap:12px;align-items:center}.workspace-icon{display:grid;place-items:center;width:36px;height:36px;background:var(--soft);border-radius:8px;color:var(--deep);flex-shrink:0}.workspace-row h3{font-size:15px;margin:0 0 6px;line-height:1.5;overflow-wrap:anywhere}.workspace-business{font-size:11px;color:var(--muted);margin:0;overflow-wrap:anywhere}.workspace-meta{font-size:11px;color:var(--muted);line-height:1.8;margin:12px 0 0;overflow-wrap:anywhere}.workspace-decision{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-top:14px;font-size:11px;color:var(--muted)}.workspace-decision strong{color:var(--deep);font-size:12px}.workspace-comment{border-left:2px solid #d7e5e0;padding:5px 0 5px 12px;margin:12px 0 0;font-size:12px;color:#536763;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.workspace-state{display:flex;align-items:start;flex-direction:column;gap:7px}.workspace-state small{font-size:10px;color:var(--muted)}.workspace-state time{font-size:11px;color:var(--muted);margin-top:6px}.workspace-badge{font-size:11px;padding:5px 8px;border-radius:5px;background:#f1f4f3;color:#64746f}.workspace-badge.in_approval{background:#eef4ff;color:#456788}.workspace-badge.approved{background:var(--soft);color:var(--deep)}.workspace-badge.returned,.workspace-badge.withdrawn{background:#fff4df;color:#8b682e}.workspace-badge.rejected{background:#fff0ed;color:var(--red)}.workspace-open{white-space:nowrap}.workspace-pagination{text-align:center;padding:24px;font-size:11px;color:var(--muted)}.workspace-empty{padding:52px 24px;text-align:center}.workspace-empty>span{font-size:32px;color:var(--deep)}.workspace-empty h3{font-size:16px;margin:16px 0 10px}.workspace-empty p{font-size:12px;color:var(--muted);line-height:1.8}.workspace-error{display:flex;gap:20px;justify-content:space-between;align-items:center;padding:20px;margin-bottom:18px;border-color:#ead6ca;color:var(--red);font-size:13px}.workspace-error p{font-size:12px;line-height:1.7;margin-bottom:0}
@media(max-width:1100px){.workspace-row{grid-template-columns:minmax(0,1fr) 145px;gap:16px}.workspace-open{grid-column:2;justify-self:start}.workspace-main{grid-row:span 2}}
@media(max-width:650px){.workspace-records .page-heading{flex-direction:column;gap:16px;align-items:start}.workspace-filters{flex-wrap:wrap;gap:12px;padding:16px}.workspace-filters .workspace-search{flex:0 0 100%}.workspace-filters label:not(.workspace-search){flex:1}.workspace-summary{line-height:1.7;flex-direction:column;gap:4px}.workspace-row{display:flex;flex-wrap:wrap;padding:20px;gap:18px}.workspace-main{width:100%}.workspace-state{flex:1}.workspace-open{align-self:end}.workspace-error{align-items:start;flex-direction:column}}
</style>
