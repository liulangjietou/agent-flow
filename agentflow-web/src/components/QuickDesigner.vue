<script setup lang="ts">
import { computed } from 'vue'
import type { Graph, GraphNode, GraphEdge } from '../api'
import type { FormSchema } from '../formSchema'
import { projectQuickGraph, quickNodeIds, quickStep, type QuickCommand } from '../quickDesigner'
import QuickSequence from './QuickSequence.vue'
import DefinitionAssignee from './DefinitionAssignee.vue'
import DefinitionCopyRecipient from './DefinitionCopyRecipient.vue'
import DefinitionDeadline from './DefinitionDeadline.vue'
import DefinitionTimerWait from './DefinitionTimerWait.vue'
import DefinitionEventWait from './DefinitionEventWait.vue'
import DefinitionSubprocess from './DefinitionSubprocess.vue'
import { readSubprocessBinding, type SubprocessBinding } from '../subprocessDesigner'
import type { EventBinding } from '../events'
import DefinitionExpenseStage from './DefinitionExpenseStage.vue'
import { readDesignerDeadline, type DesignerDeadline } from '../designerGraph'
import ConditionEditor from './ConditionEditor.vue'
import { describeBranch, branchTooltip } from '../conditionPresentation'
const props = defineProps<{ graph: Graph; formSchema: FormSchema | null; selectedNode: string; selectedEdge: string; locked: boolean; scopeKey: string; invalidNodes: string[]; simulatedNodes: string[]; simulatedEdges: string[] }>()
const emit = defineEmits<{ subprocess: [id: string, value: SubprocessBinding]; eventContract: [id: string, value: EventBinding]; policy: [id: string, mode: string, percentage: string | undefined]; expenseStage: [id: string, value: string | undefined]; command: [value: QuickCommand]; selectNode: [id: string]; selectEdge: [id: string]; advanced: []; beforeChange: []; node: [id: string, patch: Partial<GraphNode>]; deadline: [id: string, value: DesignerDeadline | undefined]; edge: [id: string, condition: string]; defaultBranch: [edge: GraphEdge] }>()
const projection = computed(() => projectQuickGraph(props.graph))
const selected = computed(() => props.graph.nodes.find(node => node.id === props.selectedNode))
const selectedDeadline = computed(() => selected.value ? readDesignerDeadline(selected.value.properties) : undefined)
const selectedLine = computed(() => props.graph.edges.find(edge => edge.id === props.selectedEdge))
const source = computed(() => props.graph.nodes.find(node => node.id === selectedLine.value?.source))
const isGateway = (node: GraphNode | undefined) => !!node && ['EXCLUSIVE_GATEWAY', 'PARALLEL_GATEWAY'].includes(node.type)
const branches = computed(() => props.graph.edges.filter(edge => edge.source === (isGateway(selected.value) ? selected.value!.id : source.value?.id)))
const ordered = computed(() => [...branches.value.filter(edge => !edge.defaultBranch), ...branches.value.filter(edge => edge.defaultBranch)])
const conditional = computed(() => branches.value.filter(edge => !edge.defaultBranch))
const gateway = computed(() => {
  const candidate = isGateway(selected.value) ? selected.value : isGateway(source.value) ? source.value : null
  return candidate && props.graph.edges.filter(edge => edge.source === candidate.id).length > 1 ? candidate : null
})
const parallel = computed(() => gateway.value?.type === 'PARALLEL_GATEWAY')
const step = computed(() => gateway.value && projection.value.sequence ? quickStep(projection.value.sequence, gateway.value.id) : null)
const ownedCount = computed(() => step.value?.branches?.flatMap(branch => quickNodeIds(branch.sequence)).length ?? 0)
const branchCount = computed(() => {
  const branch = step.value?.branches?.find(branch => branch.edgeId === selectedLine.value?.id)
  return branch ? quickNodeIds(branch.sequence).length : 0
})
const adjacent = computed(() => {
  if (!selected.value || !['USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SUB_PROCESS'].includes(selected.value.type)) return {}
  const incoming = props.graph.edges.filter(edge => edge.target === selected.value!.id)
  const before = incoming.length === 1 ? props.graph.nodes.find(node => node.id === incoming[0]!.source && ['USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SUB_PROCESS'].includes(node.type)) : null
  const target = props.graph.edges.find(edge => edge.source === selected.value!.id)?.target
  const after = props.graph.nodes.find(node => node.id === target && ['USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SUB_PROCESS'].includes(node.type))
  return { before: before?.id, after: after && props.graph.edges.filter(edge => edge.target === after.id).length === 1 ? after.id : undefined }
})
function properties(key: string, value: string) { if (selected.value) emit('node', selected.value.id, { properties: { ...selected.value.properties, [key]: value } }) }
function changeDeadline(value: DesignerDeadline | undefined) {
  if (!props.locked && selected.value?.type === 'USER_TASK') emit('deadline', selected.value.id, value)
}
function moveBranch(direction: -1 | 1) { if (gateway.value && selectedLine.value) emit('command', { kind: 'moveBranch', nodeId: gateway.value.id, edgeId: selectedLine.value.id, direction }) }
</script>
<template>
  <div class="quick-designer">
    <template v-if="projection.sequence">
      <section class="quick-stage" aria-label="快速流程步骤">
        <div class="quick-stage-heading"><strong>从上到下，安排每一步</strong><span>点击 ＋ 添加审批、抄送、等待、子流程或分支</span></div>
        <div class="quick-scroll" tabindex="0" aria-label="可滚动的快速流程图"><QuickSequence :sequence="projection.sequence" :graph="graph" :form-schema="formSchema" :selected-node="selectedNode" :selected-edge="selectedEdge" :locked="locked" :invalid-nodes="invalidNodes" :simulated-nodes="simulatedNodes" :simulated-edges="simulatedEdges" @select-node="emit('selectNode', $event)" @select-edge="emit('selectEdge', $event)" @command="emit('command', $event)" /></div>
      </section>
      <aside class="quick-inspector" aria-label="快速步骤配置"><fieldset :disabled="locked">
        <template v-if="selected">
          <p class="eyebrow">{{ isGateway(selected) ? 'BRANCH' : 'STEP' }}</p><h3>{{ selected.name }}</h3>
          <label>步骤名称<input :value="selected.name" @focus="emit('beforeChange')" @input="emit('node', selected.id, { name: ($event.target as HTMLInputElement).value })" /></label>
          <template v-if="['USER_TASK', 'COPY', 'TIMER_WAIT', 'EVENT_WAIT', 'SUB_PROCESS'].includes(selected.type)">
            <DefinitionSubprocess v-if="selected.type === 'SUB_PROCESS'" :key="selected.id" :node-id="selected.id" :form-schema="formSchema" :model-value="readSubprocessBinding(selected.properties) ?? { inputs: {} }" :scope-key="scopeKey" :disabled="locked" @before-change="emit('beforeChange')" @update:model-value="emit('subprocess', selected.id, $event)" />
            <DefinitionEventWait v-if="selected.type === 'EVENT_WAIT'" :key="selected.id" :model-value="{ key: selected.properties.eventContractKey, version: selected.properties.eventContractVersion }" :scope-key="scopeKey" :disabled="locked" @before-change="emit('beforeChange')" @update:model-value="emit('eventContract', selected.id, $event)" />
            <DefinitionTimerWait v-if="selected.type === 'TIMER_WAIT'" :key="selected.id" :model-value="selected.properties.timerDelaySeconds" :disabled="locked" @before-change="emit('beforeChange')" @update:model-value="properties('timerDelaySeconds', $event)" />
            <DefinitionCopyRecipient v-if="selected.type === 'COPY'" :key="selected.id" :model-value="selected.properties.recipientRule ?? ''" :scope-key="scopeKey" :disabled="locked" @before-change="emit('beforeChange')" @update:model-value="properties('recipientRule', $event)" />
            <DefinitionAssignee v-if="selected.type === 'USER_TASK'" :key="selected.id" :model-value="selected.properties.assigneeRule ?? ''" :approval-mode="selected.properties.approvalMode ?? 'SINGLE'" :approval-percentage="selected.properties.approvalPercentage" :scope-key="scopeKey" :disabled="locked" @before-change="emit('beforeChange')" @update:model-value="properties('assigneeRule', $event)" @policy="(mode, percentage) => emit('policy', selected!.id, mode, percentage)" />
            <DefinitionExpenseStage v-if="selected.type === 'USER_TASK'" :model-value="selected.properties.expenseStage" :form-schema="formSchema" :disabled="locked" @before-change="emit('beforeChange')" @update:model-value="emit('expenseStage', selected.id, $event)" />
            <DefinitionDeadline v-if="selected.type === 'USER_TASK'" :key="selected.id" :model-value="selectedDeadline" :scope-key="scopeKey" :disabled="locked" @before-change="emit('beforeChange')" @update:model-value="changeDeadline" />
            <div class="quick-move"><button type="button" class="secondary" :disabled="!adjacent.before" @click="emit('command', { kind: 'swapTasks', firstId: adjacent.before!, secondId: selected.id })">上移一步</button><button type="button" class="secondary" :disabled="!adjacent.after" @click="emit('command', { kind: 'swapTasks', firstId: selected.id, secondId: adjacent.after! })">下移一步</button></div>
            <button type="button" class="quick-delete" @click="emit('command', { kind: 'removeTask', nodeId: selected.id })">删除此步骤并接续流程</button>
          </template>
          <template v-else-if="gateway && step?.branches">
            <p v-if="parallel" class="quick-help">所有分支同时执行，全部完成后才继续；显示顺序不代表审批先后。</p>
            <p v-else class="quick-help">按列表顺序判断，先满足先走；“其他情况”在所有条件都不满足时进入。</p>
            <ol class="quick-branch-list"><li v-for="(branch, index) in ordered" :key="branch.id"><button type="button" @click="emit('selectEdge', branch.id)"><strong>{{ parallel ? `并行分支 ${index + 1}` : branch.defaultBranch ? '其他情况' : `条件 ${index + 1}` }}</strong><span v-if="parallel">{{ graph.nodes.find(node => node.id === branch.target)?.name }}</span><span v-else :title="branchTooltip(branch, formSchema?.fields ?? [], graph.conditionLanguageVersion ?? 1)">{{ describeBranch(branch, formSchema?.fields ?? [], graph.conditionLanguageVersion ?? 1) || '尚未配置' }}</span></button></li></ol>
            <button type="button" class="secondary" @click="emit('command', { kind: 'addBranch', nodeId: selected.id })">＋ 增加一条分支</button>
            <p class="quick-help">删除{{ parallel ? '并行' : '条件' }}块将一并删除其中 {{ ownedCount }} 个步骤，接续后续流程；共用汇合节点保留，可撤销。</p>
            <button type="button" class="quick-delete" @click="emit('command', { kind: 'removeGateway', nodeId: selected.id })">删除{{ parallel ? '并行' : '条件' }}块及全部分支</button>
          </template>
          <p v-else-if="isGateway(selected)" class="quick-help">{{ selected.type === 'PARALLEL_GATEWAY' ? '等待本组全部并行分支到达后继续。' : '选中的条件路径到达后直接继续，不等待未选中的路径。' }}可在汇合之后添加步骤；需要删除分支时，请选择对应的分支块。</p>
          <p v-else class="quick-help">通过相邻的 ＋ 添加审批、抄送、等待、子流程或分支。开始、结束节点在快速模式中保留。</p>
        </template>
        <template v-else-if="selectedLine && gateway">
          <p class="eyebrow">{{ parallel ? 'PARALLEL BRANCH' : 'BRANCH CONDITION' }}</p><h3>{{ parallel ? '并行分支' : selectedLine.defaultBranch ? '其他情况' : '分支条件' }}</h3>
          <p v-if="parallel" class="quick-help">本分支与其他分支同时执行，不配置条件或默认路径。点击分支内的审批步骤配置处理人。</p>
          <p v-else-if="selectedLine.defaultBranch" class="quick-help">其余条件都不满足时进入此分支，无需填写条件。</p>
          <ConditionEditor v-else :key="selectedLine.id" :model-value="selectedLine.condition" :language-version="graph.conditionLanguageVersion ?? 1" :form-schema="formSchema" :disabled="locked" @before-change="emit('beforeChange')" @update:model-value="emit('edge', selectedLine.id, $event)" />
          <div v-if="!selectedLine.defaultBranch" class="quick-move"><button type="button" class="secondary" :disabled="conditional[0]?.id === selectedLine.id" @click="moveBranch(-1)">{{ parallel ? '向左移动' : '优先判断' }}</button><button type="button" class="secondary" :disabled="conditional[conditional.length - 1]?.id === selectedLine.id" @click="moveBranch(1)">{{ parallel ? '向右移动' : '延后判断' }}</button></div>
          <button v-if="!parallel && !selectedLine.defaultBranch" type="button" class="secondary" @click="emit('defaultBranch', selectedLine)">设为其他情况</button>
          <p class="quick-help">{{ selectedLine.defaultBranch ? '默认分支不能直接删除，可先将另一分支设为默认。' : `删除此分支将同时移除其中 ${branchCount} 个步骤，可撤销。` }}</p>
          <button v-if="!selectedLine.defaultBranch" type="button" class="quick-delete" :disabled="branches.length < 3" @click="emit('command', { kind: 'removeBranch', nodeId: gateway.id, edgeId: selectedLine.id })">删除此分支及其中步骤</button>
          <p v-if="branches.length < 3" class="quick-help">分支块至少保留两条路径；不再需要分支时，可选中分支块整体删除。</p>
        </template>
        <p v-else class="quick-help">选择左侧步骤或分支。发布前请完成审批人和条件分支的配置。</p>
      </fieldset></aside>
    </template>
    <div v-else class="quick-unavailable" role="status"><strong>此流程需要在高级画布中编辑</strong><p>{{ projection.reason }}</p><button type="button" class="secondary" @click="emit('advanced')">打开高级画布</button><p>原节点、属性和连线均保留；切换模式不会修改流程。</p></div>
  </div>
</template>
<style scoped>
.quick-designer{display:grid;grid-template-columns:minmax(0,1fr) 292px;border:1px solid var(--line);border-radius:13px;overflow:hidden;background:var(--paper);margin-bottom:22px}.quick-stage{min-width:0}.quick-stage-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:17px 22px;background:white;border-bottom:1px solid var(--line);font-size:12px}.quick-stage-heading span{font-size:10px;color:var(--muted)}.quick-scroll{overflow:auto;max-height:750px;min-height:520px;padding:30px 24px 65px;display:flex;align-items:flex-start}.quick-scroll>.quick-sequence{margin:auto;min-width:max-content}.quick-inspector{border-left:1px solid var(--line);padding:24px;background:white;min-width:0}.quick-inspector h3{font-size:16px;margin:8px 0 22px;overflow-wrap:anywhere}.quick-inspector :deep(label){display:block;font-size:12px;margin-bottom:14px;line-height:1.7}.quick-inspector :deep(input),.quick-inspector :deep(select),.quick-inspector :deep(textarea){display:block;width:100%;min-width:0;margin-top:6px;padding:10px 11px;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:var(--ink);font:inherit;font-size:12px}.quick-inspector :deep(textarea){resize:vertical;line-height:1.7}.quick-help{font-size:11px;line-height:1.85;color:var(--muted);margin:12px 0}.quick-move{display:flex;gap:8px;margin:15px 0;flex-wrap:wrap}.quick-inspector .secondary{font-size:11px;padding:8px 10px}.quick-delete{background:transparent;border:0;color:var(--red);font-size:11px;padding:12px 0;text-align:left;line-height:1.6}.quick-branch-list{padding:0;list-style:none;margin:15px 0}.quick-branch-list button{display:grid;gap:6px;width:100%;text-align:left;padding:10px;border:1px solid var(--line);border-radius:8px;background:var(--paper);margin-bottom:8px}.quick-branch-list strong{font-size:11px}.quick-branch-list span{font-size:10px;line-height:1.8;overflow-wrap:anywhere;color:var(--muted)}.quick-unavailable{grid-column:1/-1;padding:45px 25px;text-align:center;font-size:13px;line-height:1.8}.quick-unavailable p{font-size:12px;color:var(--muted)}.quick-scroll:focus-visible{outline:3px solid #20a18c60;outline-offset:-3px}@media(max-width:1000px){.quick-designer{grid-template-columns:minmax(0,1fr)}.quick-inspector{border-left:0;border-top:1px solid var(--line)}.quick-stage-heading{flex-wrap:wrap}.quick-scroll{max-height:550px}}
</style>
