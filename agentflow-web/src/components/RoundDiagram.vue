<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, useId, watch } from 'vue'
import { api, type SubmissionRound } from '../api'
import TimerWaitPanel from './TimerWaitPanel.vue'
import EventWaitPanel from './EventWaitPanel.vue'
import { RoundDiagramQuery, traversalRecords } from '../roundDiagram'
import { arrangeNodes, clampZoom, fittedViewport, graphBounds, nodeRectangle, routeEdges } from '../designerLayout'

const props = defineProps<{ applicationId: string; rounds: SubmissionRound[]; scopeKey: string; version: number; locked?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const query = reactive(new RoundDiagramQuery(api.roundDiagram))
const selectedRound = ref(0)
const selectedNode = ref('')
const viewport = ref<HTMLElement | null>(null)
const zoom = ref(1)
const marker = 'round-arrow-' + useId()
const sortedRounds = computed(() => [...props.rounds].sort((a, b) => b.roundNo - a.roundNo))
const roundLabels: Record<string, string> = { IN_APPROVAL: '审批中', APPROVED: '已批准', RETURNED: '已退回', REJECTED: '已驳回', WITHDRAWN: '已撤回' }
const states = { NOT_REACHED: '未记录到达', ACTIVE: '当前节点', LEFT: '已离开' }
const typeNames: Record<string, string> = { START: '开始', END: '结束', USER_TASK: '审批', COPY: '抄送', TIMER_WAIT: '定时等待', SERVICE_TASK: '服务任务', EVENT_WAIT: '事件等待', SUB_PROCESS: '子流程', EXCLUSIVE_GATEWAY: '条件网关', PARALLEL_GATEWAY: '并行网关', OTHER: '流程节点' }
const edges = computed(() => (query.value?.edges ?? []).map(edge => ({ ...edge, condition: '' })))
const takenIds = computed(() => new Set(edges.value.filter(edge => edge.state === 'TAKEN').map(edge => edge.id)))
const records = computed(() => query.value ? traversalRecords(query.value) : [])
const layout = computed(() => arrangeNodes((query.value?.nodes ?? []).map(node => ({ ...node, x: 0, y: 0, assigneeRule: '' })), edges.value).nodes.map(position => ({ ...position, ...query.value!.nodes.find(node => node.id === position.id)! })))
const routes = computed(() => routeEdges(layout.value, edges.value))
const bounds = computed(() => graphBounds(layout.value, routes.value))
const width = computed(() => Math.max(480, bounds.value.right + 40))
const height = computed(() => Math.max(240, bounds.value.bottom + 40))
const detail = computed(() => query.value?.nodes.find(node => node.id === selectedNode.value))
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
function fit() {
  if (!viewport.value) return
  const result = fittedViewport(bounds.value, viewport.value.clientWidth, viewport.value.clientHeight)
  zoom.value = result.zoom
  void nextTick(() => { if (viewport.value) { viewport.value.scrollLeft = result.left; viewport.value.scrollTop = result.top } })
}
async function load() {
  selectedNode.value = ''
  await query.load(props.scopeKey, props.applicationId, selectedRound.value)
}
watch(() => [props.applicationId, props.scopeKey], () => { selectedRound.value = sortedRounds.value[0]?.roundNo ?? 0 }, { immediate: true, flush: 'sync' })
watch(() => [props.version, props.rounds], () => {
  if (!props.rounds.some(round => round.roundNo === selectedRound.value)) selectedRound.value = sortedRounds.value[0]?.roundNo ?? 0
  else void load()
}, { flush: 'sync' })
watch(() => [props.applicationId, props.scopeKey, selectedRound.value], () => { void load() }, { immediate: true, flush: 'sync' })
watch(() => query.value, async value => {
  if (!value) return
  selectedNode.value = value.nodes.find(node => node.state === 'ACTIVE')?.id ?? value.nodes[0]?.id ?? ''
  await nextTick(); fit()
})
onUnmounted(() => query.clear())
</script>

<template>
  <section class="round-diagram" aria-label="轮次流程图" :aria-busy="query.loading">
    <div class="diagram-heading"><div><h3>轮次流程图</h3><p>查看这一轮实际使用的流程和运行位置。</p></div><button type="button" class="secondary" :disabled="!selectedRound || query.loading" @click="load">刷新流程图</button></div>
    <p v-if="!rounds.length" class="diagram-empty">暂无提交轮次，提交申请后可查看流程图。</p>
    <template v-else>
      <label class="round-selector">查看轮次<select v-model.number="selectedRound"><option v-for="round in sortedRounds" :key="round.roundNo" :value="round.roundNo">第 {{ round.roundNo }} 轮 · {{ roundLabels[round.status] ?? round.status }} · v{{ round.definitionVersion }}</option></select></label>
      <p v-if="query.loading" class="diagram-empty" role="status">正在读取本轮流程…</p>
      <div v-else-if="query.error" class="diagram-error" role="alert"><p>{{ query.error }}</p><button type="button" class="secondary" @click="load">重试加载流程图</button></div>
      <template v-else-if="query.value">
        <EventWaitPanel v-if="query.value.nodes.some(node => node.type === 'EVENT_WAIT')" :application-id="applicationId" :round-no="selectedRound" :version="version" :scope-key="scopeKey" @changed="emit('changed'); load()" />
        <TimerWaitPanel v-if="query.value.nodes.some(node => node.type === 'TIMER_WAIT')" :application-id="applicationId" :round-no="selectedRound" :version="version" :scope-key="scopeKey" :locked="locked ?? false" @changed="emit('changed'); load()" />
        <div class="diagram-meta"><strong>第 {{ query.value.roundNo }} 轮 · {{ roundLabels[query.value.status] ?? query.value.status }}</strong><span>流程版本 v{{ query.value.definitionVersion }}</span><small>读取于 {{ time(query.value.observedAt) }}</small></div>
        <div class="diagram-toolbar"><div class="diagram-legend"><span class="ACTIVE">● 当前节点</span><span class="LEFT">● 已离开</span><span class="NOT_REACHED">○ 未记录到达</span><span class="TAKEN">━━ 已流转</span><span class="NOT_RECORDED">┄ 未记录流转</span></div><div class="diagram-zoom"><button type="button" aria-label="缩小流程图" :disabled="zoom <= 0.5" @click="zoom = clampZoom(zoom - 0.1)">−</button><span>{{ Math.round(zoom * 100) }}%</span><button type="button" aria-label="放大流程图" :disabled="zoom >= 1.6" @click="zoom = clampZoom(zoom + 0.1)">＋</button><button type="button" @click="fit">适应</button></div></div>
        <div ref="viewport" class="diagram-viewport" tabindex="0" aria-label="可滚动流程图，点击节点查看记录">
          <div :style="{ width: `${width * zoom}px`, height: `${height * zoom}px` }">
            <div class="diagram-canvas" :style="{ width: `${width}px`, height: `${height}px`, transform: `scale(${zoom})` }">
              <svg :width="width" :height="height" aria-hidden="true">
                <defs>
                  <marker :id="marker" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0 0 L7 3.5 L0 7 Z" fill="#99aaa7" /></marker>
                  <marker :id="`${marker}-taken`" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0 0 L7 3.5 L0 7 Z" fill="var(--deep)" /></marker>
                </defs>
                <g v-for="route in routes" :key="route.edge.id" :class="['diagram-edge', { taken: takenIds.has(route.edge.id) }]">
                  <path :d="route.path" fill="none" :stroke="takenIds.has(route.edge.id) ? 'var(--deep)' : '#99aaa7'" :stroke-width="takenIds.has(route.edge.id) ? 2.5 : 1.5" :stroke-dasharray="takenIds.has(route.edge.id) ? undefined : '5 4'" :marker-end="`url(#${marker}${takenIds.has(route.edge.id) ? '-taken' : ''})`" />
                  <text v-if="route.text" :x="route.label.x" :y="route.label.y" text-anchor="middle">{{ route.text }}</text>
                </g>
              </svg>
              <button v-for="node in layout" :key="node.id" type="button" class="diagram-node" :class="[node.state, { selected: selectedNode === node.id, compact: node.type === 'START' || node.type === 'END' }]" :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${nodeRectangle(node).width}px`, height: `${nodeRectangle(node).height}px` }" :title="`${node.name} · ${states[node.state]}${node.activeTasks ? ` · ${node.activeTasks} 张待办` : ''}`" :aria-label="`${node.name}，${states[node.state]}${node.activeTasks ? `，${node.activeTasks} 张待办` : ''}`" :aria-pressed="selectedNode === node.id" @click="selectedNode = node.id"><strong>{{ node.name }}</strong><small>{{ node.activeTasks ? `${node.activeTasks} 张待办` : states[node.state] }}</small></button>
            </div>
          </div>
        </div>
        <article v-if="detail" class="diagram-detail" aria-live="polite"><div><strong>{{ detail.name }}</strong><span>{{ typeNames[detail.type] ?? detail.type }} · {{ states[detail.state] }}</span></div><p v-if="detail.activeTasks">当前有 {{ detail.activeTasks }} 张待办。</p><dl><dt>首次进入</dt><dd>{{ detail.firstEnteredAt ? time(detail.firstEnteredAt) : '暂无进入记录' }}</dd><dt>最近结束记录</dt><dd>{{ detail.lastLeftAt ? time(detail.lastLeftAt) : '暂无结束记录' }}</dd></dl>
          <section v-if="detail.type === 'USER_TASK'" class="candidate-evidence" aria-label="节点初始候选账号">
            <h4>节点初始候选账号</h4>
            <template v-if="detail.candidateSnapshots?.length">
              <div v-for="snapshot in detail.candidateSnapshots" :key="snapshot.id">
                <p v-if="detail.candidateSnapshots.length > 1">组织修订 {{ snapshot.directoryRevision }}</p>
                <ul><li v-for="userId in snapshot.candidateUserIds" :key="userId">{{ userId }}</li></ul>
              </div>
              <p>这是节点创建时保存的候选名单，组织调整不会改写。当前办理人可能因领取、转交或委派而变化；实际办理及结论请查看审批轨迹。</p>
            </template>
            <p v-else>{{ detail.state === 'NOT_REACHED' ? '本轮尚无该节点的候选记录。' : '本轮未保存候选名单，不能用当前组织推断历史责任。' }}</p>
          </section>
        </article>
        <details class="traversal-records">
          <summary>流转记录 · {{ records.length }} 条已流转连线</summary>
          <p v-if="!records.length" class="diagram-note">本轮暂无连线流转记录，仍可查看流程结构和节点记录。</p>
          <ul v-else aria-label="本轮已流转连线">
            <li v-for="record in records" :key="record.id">
              <div><strong>{{ record.sourceName }} → {{ record.targetName }}</strong><span v-if="record.defaultBranch">默认分支</span><span>{{ record.traversalCount }} 次</span></div>
              <p>首次：{{ record.firstTakenAt ? time(record.firstTakenAt) : '时间未记录' }}<template v-if="record.traversalCount > 1"> · 最近：{{ record.lastTakenAt ? time(record.lastTakenAt) : '时间未记录' }}</template></p>
            </li>
          </ul>
        </details>
        <p class="diagram-note">实线表示本轮已记录的流转，虚线表示未记录流转；历史缺失时不推断路径。会签中的结束记录可能来自部分已办任务。节点“已离开”不等于审批通过，处理结论以本轮状态和操作审计为准。可用键盘滚动查看完整流程。</p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.round-diagram{padding:20px 0;min-width:0}.diagram-heading{display:flex;justify-content:space-between;gap:12px;align-items:start}.diagram-heading h3{margin:0 0 7px;font-size:15px}.diagram-heading p,.diagram-note{font-size:11px;color:var(--muted);line-height:1.8;margin:0}.diagram-heading button{font-size:11px;flex-shrink:0}.round-selector{max-width:320px;margin:20px 0;font-size:11px}.round-selector select{width:100%;font-size:12px}.diagram-empty{text-align:center;color:var(--muted);padding:30px 10px;font-size:12px}.diagram-error{padding:14px;background:#fff0ed;color:var(--red);border-radius:8px;font-size:12px}.diagram-meta{display:flex;align-items:center;gap:9px 18px;flex-wrap:wrap;font-size:11px;margin-bottom:16px}.diagram-meta strong{color:var(--deep)}.diagram-meta small{color:var(--muted);margin-left:auto}.diagram-toolbar{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px;padding:10px;border:1px solid var(--line);border-bottom:0;border-radius:10px 10px 0 0;background:var(--paper)}.diagram-legend{display:flex;gap:12px;font-size:10px}.diagram-legend .ACTIVE{color:var(--deep)}.diagram-legend .LEFT{color:#5c7974}.diagram-legend .NOT_REACHED{color:var(--muted)}.diagram-zoom{display:flex;gap:6px;align-items:center;font-size:10px}.diagram-zoom button{background:white;border:1px solid var(--line);border-radius:5px;padding:3px 7px;color:var(--ink)}.diagram-viewport{overflow:auto;width:100%;height:290px;border:1px solid var(--line);border-radius:0 0 10px 10px;background:radial-gradient(#d8e3df 1px,transparent 1px) 0 0/18px 18px}.diagram-canvas{position:relative;transform-origin:top left}.diagram-canvas svg{position:absolute;inset:0}.diagram-canvas text{font-size:10px;fill:var(--muted)}.diagram-node{position:absolute;display:flex;flex-direction:column;justify-content:center;gap:6px;padding:7px;border:1px solid #cbd7d3;border-radius:8px;background:white;color:var(--ink);box-shadow:0 2px 6px #193f3510}.diagram-node.compact{padding:4px;gap:3px;border-radius:18px}.diagram-node strong{line-height:1.2;font-size:11px;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;width:100%}.diagram-node small{line-height:1.2;font-size:9px;color:var(--muted)}.diagram-node.ACTIVE{border:2px solid var(--deep);background:var(--soft)}.diagram-node.ACTIVE small{color:var(--deep)}.diagram-node.LEFT{border-color:#85a69d;background:#f1f6f3}.diagram-node.NOT_REACHED{border-style:dashed}.diagram-node.selected{box-shadow:0 0 0 3px #20a18c24}.diagram-detail{margin:12px 0;padding:14px;background:var(--paper);border:1px solid var(--line);border-radius:9px;font-size:11px}.diagram-detail>div{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}.diagram-detail strong{overflow-wrap:anywhere}.diagram-detail span,.diagram-detail p,.diagram-detail dt{color:var(--muted)}.diagram-detail dl{display:grid;grid-template-columns:70px 1fr;gap:8px;margin-bottom:0}.diagram-detail dd{margin:0}.diagram-note{margin-top:14px}.diagram-viewport:focus-visible,.diagram-node:focus-visible{outline:3px solid #20a18c60;outline-offset:2px}@media(max-width:650px){.diagram-heading{flex-wrap:wrap}.diagram-meta small{width:100%;margin-left:0}.diagram-legend{flex-wrap:wrap}.diagram-toolbar{gap:12px}.diagram-viewport{height:260px}}
.diagram-legend{flex-wrap:wrap}.diagram-legend .TAKEN{color:var(--deep)}.diagram-legend .NOT_RECORDED{color:var(--muted)}.traversal-records{margin:12px 0;border:1px solid var(--line);border-radius:9px;font-size:11px;overflow:hidden}.traversal-records summary{padding:12px 14px;cursor:pointer;color:var(--deep);font-weight:600}.traversal-records summary:focus-visible{outline:3px solid #20a18c60;outline-offset:-3px}.traversal-records>p{padding:0 14px 12px}.traversal-records ul{margin:0;padding:0 14px 6px;list-style:none;max-height:240px;overflow:auto}.traversal-records li{padding:10px 0;border-top:1px solid var(--line)}.traversal-records li>div{display:flex;gap:8px;align-items:baseline;flex-wrap:wrap}.traversal-records strong{font-weight:500;overflow-wrap:anywhere}.traversal-records span,.traversal-records li p{color:var(--muted);font-size:10px}.traversal-records li p{margin:6px 0 0;line-height:1.6}
.candidate-evidence{margin-top:16px;border-top:1px solid var(--line);padding-top:12px}.candidate-evidence h4{margin:0 0 10px;font-size:12px}.candidate-evidence ul{display:flex;flex-wrap:wrap;gap:6px;list-style:none;padding:0;max-height:180px;overflow:auto}.candidate-evidence li{padding:5px 8px;border:1px solid var(--line);background:white;border-radius:5px;overflow-wrap:anywhere;max-width:100%}.candidate-evidence p{line-height:1.8}
</style>
