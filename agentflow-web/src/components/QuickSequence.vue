<script setup lang="ts">
import type { FormSchema } from '../formSchema'
import { describeBranch, branchTooltip } from '../conditionPresentation'
import type { Graph, GraphNode } from '../api'
import type { QuickCommand, QuickSequence } from '../quickDesigner'
import { assigneeLabel } from '../definitionAssignees'
const props = defineProps<{ sequence: QuickSequence; graph: Graph; formSchema: FormSchema | null; selectedNode: string; selectedEdge: string; locked: boolean; invalidNodes: string[]; simulatedNodes: string[]; simulatedEdges: string[] }>()
const emit = defineEmits<{ selectNode: [id: string]; selectEdge: [id: string]; command: [value: QuickCommand] }>()
const node = (id: string) => props.graph.nodes.find(node => node.id === id)!
const edge = (id: string) => props.graph.edges.find(edge => edge.id === id)!
function insert(event: Event, edgeId: string | null, beforeNodeId: string | undefined, type: 'USER_TASK' | 'EXCLUSIVE_GATEWAY') {
  // 插入后原节点会复用，需主动收起菜单，避免重复添加。
  const menu = (event.currentTarget as HTMLElement).closest('details')
  if (menu) menu.open = false
  emit('command', { kind: 'insert', ...(edgeId ? { edgeId } : { beforeNodeId }), type })
}
const rule = (node: GraphNode) => node.properties.approvalMode === 'ALL' ? `全员会签 · ${assigneeLabel(node.properties.assigneeRule ?? '')}` : assigneeLabel(node.properties.assigneeRule ?? '')
</script>
<template>
  <div class="quick-sequence">
    <template v-for="step in sequence.steps" :key="step.nodeId">
      <div v-if="node(step.nodeId).type !== 'START'" class="quick-connector" :class="{ simulated: step.beforeEdge && simulatedEdges.includes(step.beforeEdge) }">
        <details v-if="!locked" class="quick-insert"><summary :aria-label="`在${node(step.nodeId).name}前添加步骤`">＋</summary><div><button type="button" @click="insert($event, step.beforeEdge, step.nodeId, 'USER_TASK')">添加审批</button><button type="button" @click="insert($event, step.beforeEdge, step.nodeId, 'EXCLUSIVE_GATEWAY')">添加条件分支</button></div></details>
      </div>
      <button type="button" class="quick-card" :data-quick-node="step.nodeId" :class="[node(step.nodeId).type.toLowerCase(), { selected: selectedNode === step.nodeId, invalid: invalidNodes.includes(step.nodeId), simulated: simulatedNodes.includes(step.nodeId) }]" @click="emit('selectNode', step.nodeId)">
        <span class="quick-type">{{ node(step.nodeId).type === 'START' ? '开始' : node(step.nodeId).type === 'END' ? '结束' : node(step.nodeId).type === 'EXCLUSIVE_GATEWAY' ? '条件分支' : '审批步骤' }}</span>
        <strong>{{ node(step.nodeId).name }}</strong><small v-if="node(step.nodeId).type === 'USER_TASK'">{{ rule(node(step.nodeId)) }}</small>
      </button>
      <div v-if="step.branches" class="quick-branches">
        <section v-for="(branch, index) in step.branches" :key="branch.edgeId" class="quick-branch" :aria-label="edge(branch.edgeId).defaultBranch ? '其他情况' : `条件分支 ${index + 1}`">
          <button type="button" class="quick-branch-label" :class="{ selected: selectedEdge === branch.edgeId, simulated: simulatedEdges.includes(branch.edgeId) }" @click="emit('selectEdge', branch.edgeId)"><strong>{{ edge(branch.edgeId).defaultBranch ? '其他情况' : `条件 ${index + 1}` }}</strong><span :title="branchTooltip(edge(branch.edgeId), formSchema?.fields ?? [], graph.conditionLanguageVersion ?? 1)">{{ describeBranch(edge(branch.edgeId), formSchema?.fields ?? [], graph.conditionLanguageVersion ?? 1) || '点击配置条件' }}</span></button>
          <QuickSequence :sequence="branch.sequence" :graph="graph" :form-schema="formSchema" :selected-node="selectedNode" :selected-edge="selectedEdge" :locked="locked" :invalid-nodes="invalidNodes" :simulated-nodes="simulatedNodes" :simulated-edges="simulatedEdges" @select-node="emit('selectNode', $event)" @select-edge="emit('selectEdge', $event)" @command="emit('command', $event)" />
        </section>
      </div>
    </template>
    <div v-if="sequence.tailEdge" class="quick-connector quick-tail" :class="{ simulated: simulatedEdges.includes(sequence.tailEdge) }"><details v-if="!locked" class="quick-insert"><summary aria-label="在分支末尾添加步骤">＋</summary><div><button type="button" @click="insert($event, sequence.tailEdge, undefined, 'USER_TASK')">添加审批</button><button type="button" @click="insert($event, sequence.tailEdge, undefined, 'EXCLUSIVE_GATEWAY')">添加条件分支</button></div></details></div>
  </div>
</template>
<style scoped>
.quick-card.invalid{border:2px solid var(--red);box-shadow:0 0 0 3px #ba4d3b20}
.quick-sequence{display:flex;flex-direction:column;align-items:center;min-width:244px;flex:1}.quick-card{display:grid;gap:8px;width:218px;text-align:left;padding:16px;background:#fff;border:1px solid var(--line);border-radius:10px;box-shadow:0 3px 10px #15352b09;color:var(--ink);flex-shrink:0;position:relative;z-index:1}.quick-type{font-size:10px;letter-spacing:.04em;color:var(--muted)}.quick-card strong{font-size:13px;font-weight:600;overflow-wrap:anywhere}.quick-card small{font-size:11px;color:var(--muted);overflow-wrap:anywhere}.quick-card.user_task{border-top:3px solid var(--deep)}.quick-card.exclusive_gateway{border-top:3px solid #b48a46}.quick-card.start,.quick-card.end{border-radius:30px;max-width:170px;text-align:center;background:var(--soft)}.quick-card.start .quick-type,.quick-card.end .quick-type{display:none}.quick-card.selected,.quick-branch-label.selected{outline:2px solid var(--deep);outline-offset:2px}.quick-card.simulated,.quick-branch-label.simulated{background:#dff4e8}.quick-connector{height:56px;min-height:56px;width:2px;background:#b6c8c0;position:relative;display:flex;align-items:center;justify-content:center;z-index:2}.quick-connector.simulated{background:var(--deep)}.quick-insert{position:relative;flex-shrink:0}.quick-insert summary{list-style:none;display:grid;place-items:center;width:25px;height:25px;border:1px solid #c1d4cb;border-radius:50%;color:var(--deep);background:#fff;cursor:pointer;font-size:17px}.quick-insert summary::-webkit-details-marker{display:none}.quick-insert[open]{z-index:10}.quick-insert>div{position:absolute;left:30px;top:0;display:grid;gap:4px;min-width:142px;padding:8px;border:1px solid var(--line);border-radius:8px;background:white;box-shadow:0 6px 20px #173e3520}.quick-insert button{white-space:nowrap;text-align:left;border:0;background:var(--paper);color:var(--ink);padding:10px;border-radius:5px;font-size:11px}.quick-branches{display:flex;align-items:stretch;gap:24px;border-top:1px solid #b6c8c0;border-bottom:1px solid #b6c8c0;padding:0 12px;margin-top:30px;position:relative;min-width:max-content}.quick-branches::before{content:'';position:absolute;height:30px;width:1px;background:#b6c8c0;top:-30px;left:50%}.quick-branch{display:flex;flex-direction:column;align-items:center;min-width:244px}.quick-branch-label{margin-top:18px;width:218px;min-height:75px;padding:12px;border:1px solid var(--line);border-radius:9px;background:#fffdfa;text-align:left;display:grid;gap:7px;position:relative}.quick-branch-label::before{content:'';position:absolute;width:1px;height:18px;left:50%;top:-19px;background:#b6c8c0}.quick-branch-label strong{font-size:11px;color:#946622}.quick-branch-label span{font-size:11px;line-height:1.6;overflow-wrap:anywhere;color:var(--muted)}.quick-branch-label span{display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow:hidden}.quick-tail{flex:1;min-height:56px}.quick-insert summary:focus-visible,.quick-card:focus-visible,.quick-branch-label:focus-visible{outline:3px solid #20a18c60;outline-offset:3px}
</style>
