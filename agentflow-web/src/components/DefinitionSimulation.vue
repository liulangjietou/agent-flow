<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api, type Graph, type SimulationResult } from '../api'
import type { FormSchema } from '../formSchema'
import { SimulationPreview, parseSimulationValues, simulationIssue } from '../definitionSimulation'
import FormFields from './FormFields.vue'

const props = defineProps<{ graph: Graph; formSchema: FormSchema | null; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ result: [result: SimulationResult | null]; locate: [id: string]; close: [] }>()
const preview = reactive(new SimulationPreview(api.simulateDesign))
const values = ref<Record<string, unknown>>({})
const rawValues = ref('{}')
const inputError = ref('')
const nodeName = (id: string) => props.graph.nodes.find(node => node.id === id)?.name ?? id
const issues = computed(() => preview.definitionErrors.map(simulationIssue))
const outcomes = { MATCHED: '条件命中 · 已选择', NOT_MATCHED: '条件不匹配', SKIPPED: '前序已命中 · 未计算', DEFAULT_SELECTED: '使用默认分支', DEFAULT_SKIPPED: '默认分支未启用' }
function canLocate(id: string) { return props.graph.nodes.some(node => node.id === id) || props.graph.edges.some(edge => edge.id === id) }
function clearResult() { preview.clear(); inputError.value = '' }
async function run() {
  if (props.locked || preview.loading) return
  clearResult()
  let payload = values.value
  if (!props.formSchema) {
    try { payload = parseSimulationValues(rawValues.value) }
    catch (error) { inputError.value = (error as Error).message; return }
  }
  await preview.run({ graph: props.graph, formSchema: props.formSchema, values: payload })
}
watch([() => props.scopeKey, () => props.formSchema], () => { values.value = {}; rawValues.value = '{}'; clearResult() }, { deep: true, flush: 'sync' })
watch([() => props.graph, () => props.locked, values, rawValues], clearResult, { deep: true, flush: 'sync' })
watch(() => preview.result, value => emit('result', value), { flush: 'sync' })
onBeforeUnmount(() => { preview.clear(); emit('result', null) })
</script>

<template>
  <section class="panel simulation-panel" aria-labelledby="simulation-title">
    <div class="simulation-heading"><div><p class="eyebrow">DESIGN / SIMULATION</p><h3 id="simulation-title">模拟运行</h3><p>按当前画布和表单试算，包括未保存的修改。仅填写测试数据，不会创建申请或执行审批。</p></div><button class="quiet" aria-label="关闭模拟面板" @click="emit('close')">×</button></div>
    <div class="simulation-layout">
      <form class="simulation-input" @submit.prevent="run"><h4>测试数据</h4><FormFields v-if="formSchema" v-model="values" :schema="formSchema" :errors="preview.fieldErrors" :disabled="locked" /><template v-else><label for="simulation-json">此流程尚未绑定表单，请输入字段值</label><textarea id="simulation-json" v-model="rawValues" :disabled="locked" rows="6" spellcheck="false" placeholder='{"amount":"6000"}' /><p class="simulation-help">使用 JSON 对象；小数和大整数请写成字符串以保留精度，例如 {"amount":"6000.50"}。</p></template><p v-if="inputError" role="alert" class="inline-error">{{ inputError }}</p><button class="primary" :disabled="locked || preview.loading">{{ preview.loading ? '正在试算…' : '运行模拟' }}</button></form>
      <div class="simulation-output" aria-live="polite" :aria-busy="preview.loading">
        <div v-if="preview.loading" class="simulation-empty"><strong>正在计算路径</strong><p>修改设计或测试数据会取消本次结果。</p></div>
        <template v-else-if="preview.error"><strong class="inline-error">{{ preview.error }}</strong><ul v-if="issues.length" class="simulation-errors"><li v-for="(issue,index) in issues" :key="index"><button v-if="canLocate(issue.target)" type="button" @click="emit('locate',issue.target)">{{ issue.label }} · 定位 ↗</button><span v-else>{{ issue.label }}</span></li></ul><p class="simulation-help">修正后可重新运行，当前未生成模拟路径。</p></template>
        <template v-else-if="preview.result"><div class="simulation-result-heading"><h4>本次模拟路径</h4><button class="quiet" type="button" @click="clearResult">清除结果</button></div><ol class="simulation-path"><li v-for="(id,index) in preview.result.path" :key="id"><button type="button" @click="emit('locate',id)"><span>{{ index + 1 }}</span>{{ nodeName(id) }}<small>定位 ↗</small></button></li></ol><p class="simulation-help">画布已高亮经过的节点和连线。会签节点仅展示经过的路径，不模拟多人表决。审批人是否存在、组织权限及外部服务不在本次路径模拟范围内。</p><div v-for="decision in preview.result.decisions" :key="decision.nodeId" class="simulation-decision"><h4>{{ nodeName(decision.nodeId) }} · 分支依据</h4><div v-for="branch in decision.branches" :key="branch.edgeId" class="simulation-branch" :class="{ chosen: branch.edgeId === decision.selectedEdgeId }"><button type="button" @click="emit('locate',branch.edgeId)">→ {{ nodeName(branch.targetNodeId) }}</button><code>{{ branch.condition || '默认分支' }}</code><span>{{ outcomes[branch.outcome] }}</span></div></div><p v-if="!preview.result.decisions.length" class="simulation-help">此路径未经过条件网关。</p></template>
        <div v-else class="simulation-empty"><strong>先填写一组测试数据</strong><p>运行后查看节点路径、分支条件与选择依据。设计或数据改变时，旧结果会立即清除。</p></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.simulation-panel{margin:22px 0;padding:24px}.simulation-heading{display:flex;justify-content:space-between;gap:18px;border-bottom:1px solid var(--line);padding-bottom:18px}.simulation-heading h3{font-size:20px;margin:8px 0}.simulation-heading p:not(.eyebrow){font-size:12px;line-height:1.9;color:var(--muted);margin-bottom:0}.simulation-heading>button{font-size:24px;align-self:start}.simulation-layout{display:grid;grid-template-columns:minmax(230px,.8fr) minmax(0,1.2fr);gap:28px;padding-top:22px}.simulation-layout h4{font-size:13px;margin:0 0 18px}.simulation-input{padding-right:26px;border-right:1px solid var(--line)}.simulation-input>button{width:100%;margin-top:12px}.simulation-input label{display:block;font-size:12px;color:var(--muted);margin-bottom:9px}.simulation-input textarea{width:100%;font:12px monospace;padding:12px;border:1px solid var(--line);border-radius:8px;background:#fafcfc;color:var(--ink);resize:vertical}.simulation-help{font-size:11px;line-height:1.9;color:var(--muted);overflow-wrap:anywhere}.simulation-empty{padding:34px 14px;text-align:center;color:var(--muted);font-size:13px}.simulation-empty p{font-size:12px;line-height:1.9}.simulation-path{padding:0;list-style:none}.simulation-path li{margin:7px 0}.simulation-path button{display:flex;align-items:center;width:100%;gap:10px;text-align:left;padding:10px 12px;border:1px solid #c3e5df;border-radius:8px;background:#f3fbf8;font-size:12px;color:var(--deep)}.simulation-path button>span{display:grid;place-items:center;border-radius:50%;width:22px;height:22px;flex-shrink:0;background:#d9f0e9;font-size:10px}.simulation-path small{margin-left:auto;white-space:nowrap;font-size:10px}.simulation-result-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.simulation-result-heading h4{margin:0}.simulation-result-heading>button{font-size:11px}.simulation-decision{margin-top:22px}.simulation-branch{display:grid;gap:7px;padding:12px;margin-top:8px;border:1px solid var(--line);border-radius:8px;font-size:12px}.simulation-branch>button{text-align:left;padding:0;color:var(--ink)}.simulation-branch code{font-size:11px;color:var(--muted);overflow-wrap:anywhere;white-space:pre-wrap}.simulation-branch>span{font-size:10px;color:var(--muted)}.simulation-branch.chosen{border-color:#a3d7ca;background:#f5fbf8}.simulation-branch.chosen>span{color:var(--deep)}.simulation-errors{padding-left:18px;font-size:12px;line-height:2;color:var(--red)}.simulation-errors button{padding:0;text-align:left;color:var(--red)}
@media(max-width:850px){.simulation-layout{grid-template-columns:1fr}.simulation-input{padding-right:0;padding-bottom:22px;border-right:0;border-bottom:1px solid var(--line)}}
@media(max-width:650px){.simulation-panel{padding:18px 15px}.simulation-path button{flex-wrap:wrap}.simulation-path small{margin-left:32px}}
</style>
