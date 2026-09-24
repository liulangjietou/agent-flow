<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api, type ComparisonChange, type ComparisonInput } from '../api'
import DefinitionPicker from './DefinitionPicker.vue'
import { DefinitionSelection } from '../definitionSelection'
import { comparisonProperty, comparisonValue, DefinitionComparisonQuery } from '../definitionComparison'

const props = defineProps<{ input: ComparisonInput; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ locate: [change: ComparisonChange]; close: [] }>()
const query = reactive(new DefinitionComparisonQuery(api.compareDefinition))
const baselineId = ref('')
const selection = reactive(new DefinitionSelection(api.searchDefinitions, api.getDefinition))
const areas = [{ key: 'DEFINITION', label: '流程信息' }, { key: 'NODE', label: '节点与审批人' }, { key: 'EDGE', label: '连线与条件' },
  { key: 'ROUTING', label: '分支优先顺序' }, { key: 'FORM', label: '表单结构' }, { key: 'FIELD', label: '字段配置' }, { key: 'LAYOUT', label: '画布布局' }] as const
const groups = computed(() => areas.map(area => ({ ...area, changes: query.result?.changes.filter(change => change.area === area.key) ?? [] })).filter(group => group.changes.length))
const baseline = computed(() => selection.definition)
const kinds = { ADDED: '新增', REMOVED: '删除', MODIFIED: '修改' }

async function chooseBaseline(id: string) {
  baselineId.value = id; query.clear()
  await selection.load(props.scopeKey, id, { publishedOnly: true, processKey: props.input.key.trim() })
}
function canLocate(change: ComparisonChange) {
  if (change.area === 'FIELD') return props.input.formSchema?.fields.some(field => field.key === change.targetId) ?? false
  if (change.area === 'EDGE') return props.input.graph.edges.some(edge => edge.id === change.targetId)
  if (['NODE', 'ROUTING', 'LAYOUT'].includes(change.area)) return props.input.graph.nodes.some(node => node.id === change.targetId)
  return false
}
function changeTitle(change: ComparisonChange) {
  const graph = change.kind === 'REMOVED' ? baseline.value?.graph : props.input.graph
  const nodeLabel = (id: string) => graph?.nodes.find(node => node.id === id)?.name ?? id
  if (change.area === 'EDGE') {
    const edge = graph?.edges.find(item => item.id === change.targetId)
    if (edge) return `${nodeLabel(edge.source)} → ${nodeLabel(edge.target)}`
  }
  if (change.area === 'ROUTING') return nodeLabel(change.targetId)
  return change.label || areas.find(area => area.key === change.area)?.label || change.targetId
}
function valueText(change: ComparisonChange, side: 'before' | 'after') {
  const value = change[side]
  const graph = side === 'before' ? baseline.value?.graph : props.input.graph
  const schema = side === 'before' ? baseline.value?.formSchema : props.input.formSchema
  const nodeLabel = (id: string) => graph?.nodes.find(node => node.id === id)?.name ?? id
  if ((change.property === 'source' || change.property === 'target') && typeof value === 'string') return nodeLabel(value) + ' · ' + value
  if (change.property === 'branchOrder' && Array.isArray(value)) return value.map(id => {
    const edge = graph?.edges.find(item => item.id === id)
    return edge ? `${nodeLabel(edge.target)}（${id}）` : id
  }).join(' → ') || '无'
  if (change.property === 'fieldOrder' && Array.isArray(value)) return value.map(key => schema?.fields.find(field => field.key === key)?.label ?? key).join(' → ') || '无'
  return comparisonValue(value, change.property)
}
watch(() => props.scopeKey, () => { baselineId.value = ''; selection.clear(); query.clear() }, { immediate: true, flush: 'sync' })
watch([() => props.input, () => props.locked, baselineId], () => query.clear(), { deep: true, flush: 'sync' })
onBeforeUnmount(() => { query.clear(); selection.clear() })
</script>

<template>
  <section class="panel comparison-panel" aria-labelledby="comparison-title">
    <div class="comparison-heading"><div><p class="eyebrow">DESIGN / VERSIONS</p><h3 id="comparison-title">版本比较</h3><p>将当前设计与同一流程的已发布版本比较，包括尚未保存的修改。比较不会保存或发布流程。</p></div><button class="quiet" aria-label="关闭版本比较" @click="emit('close')">×</button></div>
    <p v-if="!input.key.trim()" class="comparison-empty">先填写流程标识，再选择同一流程的已发布版本。</p>
    <template v-else>
      <DefinitionPicker :scope-key="scopeKey" label="比较基线" published-only :process-key="input.key.trim()" :selected-id="baselineId" :selected-label="baseline ? baseline.name + ' · v' + baseline.version : ''" :locked="locked" @select="chooseBaseline($event.id)" />
      <p v-if="selection.loading" class="comparison-empty" role="status">正在读取所选基线配置…</p>
      <p v-if="selection.error" class="inline-error" role="alert">{{ selection.error }}<button type="button" class="quiet" @click="chooseBaseline(baselineId)">重试读取基线</button></p>
      <div class="comparison-controls"><div class="comparison-direction">→<span>当前设计<small>含未保存内容</small></span></div><button class="primary" :disabled="locked || query.loading || selection.loading || !baseline || !input.name.trim()" @click="query.run(baselineId,input)">{{ query.loading ? '正在比较…' : '比较当前内容' }}</button></div>
      <div aria-live="polite" :aria-busy="query.loading">
        <p v-if="query.error" role="alert" class="inline-error">{{ query.error }}</p>
        <p v-else-if="query.loading" class="comparison-empty">正在读取发布基线并计算差异…</p>
        <template v-else-if="query.result">
          <div class="comparison-summary"><strong>{{ query.result.changes.length ? `${query.result.changes.length} 处配置差异` : '与基线配置一致' }}</strong><span>基线 V{{ query.result.baseline.version }} · {{ query.result.baseline.name }}</span></div>
          <p v-if="!query.result.changes.length" class="comparison-help">当前名称、节点、连线、表单和已保存布局与此版本一致。</p>
          <details v-for="group in groups" :key="group.key" class="comparison-group" :open="group.key !== 'LAYOUT'"><summary>{{ group.label }}<span>{{ group.changes.length }}</span></summary><article v-for="(change,index) in group.changes" :key="index" class="comparison-change"><div class="comparison-change-title"><span class="comparison-kind" :class="change.kind.toLowerCase()">{{ kinds[change.kind] }}</span><strong>{{ changeTitle(change) }} · {{ comparisonProperty(change.property) }}</strong><button v-if="canLocate(change)" class="quiet" @click="emit('locate',change)">定位当前配置 ↗</button><small v-else-if="change.kind === 'REMOVED'">当前设计已删除</small></div><div class="comparison-values"><div><small>基线版本</small><pre>{{ valueText(change,'before') }}</pre></div><div><small>当前设计</small><pre>{{ valueText(change,'after') }}</pre></div></div></article></details>
          <p class="comparison-help">修改设计、切换基线或离开此页后，比较结果会清除。差异清单不替代发布校验；已有申请继续使用其绑定版本。</p>
        </template>
        <p v-else class="comparison-empty">选择基线后比较当前内容。审批人规则、条件优先级、表单约束和布局会分组展示。</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.comparison-panel{padding:24px;margin:22px 0}.comparison-heading{display:flex;align-items:start;justify-content:space-between;gap:18px}.comparison-heading h3{font-size:20px;margin:8px 0}.comparison-heading p:not(.eyebrow){font-size:12px;line-height:1.9;color:var(--muted)}.comparison-heading>button{font-size:24px}.comparison-controls{display:flex;align-items:end;gap:22px;padding:20px 0;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.comparison-direction{display:flex;align-items:center;gap:16px;font-size:14px;align-self:center}.comparison-direction small{display:block;font-size:10px;color:var(--muted);margin-top:5px}.comparison-summary{display:flex;flex-wrap:wrap;justify-content:space-between;gap:12px;margin:22px 0;font-size:14px}.comparison-summary>span{font-size:12px;color:var(--muted)}.comparison-group{border:1px solid var(--line);border-radius:10px;margin:14px 0;overflow:hidden}.comparison-group>summary{cursor:pointer;padding:15px;background:#f6f9f8;font-size:13px;font-weight:600}.comparison-group>summary>span{margin-left:10px;padding:2px 7px;border-radius:12px;background:#dcece7;font-size:10px}.comparison-change{padding:16px;border-top:1px solid var(--line)}.comparison-change-title{display:flex;align-items:center;gap:9px;flex-wrap:wrap;font-size:12px}.comparison-change-title strong{overflow-wrap:anywhere}.comparison-change-title button,.comparison-change-title small{margin-left:auto;font-size:10px}.comparison-kind{padding:3px 7px;border-radius:4px;background:#fff3d9;color:#815400;white-space:nowrap}.comparison-kind.added{background:#e4f4ed;color:#24684b}.comparison-kind.removed{background:#fff0ef;color:#9a3934}.comparison-values{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px;margin-top:12px}.comparison-values>div{background:#f9fafb;padding:12px;border-radius:6px;min-width:0}.comparison-values>div:last-child{background:#f3faf7}.comparison-values small{font-size:10px;color:var(--muted)}.comparison-values pre{font-family:inherit;font-size:12px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0 0;color:var(--ink)}.comparison-help,.comparison-empty{font-size:12px;color:var(--muted);line-height:1.9}.comparison-empty{padding:22px 0}.comparison-empty strong{color:var(--ink)}
@media(max-width:700px){.comparison-panel{padding:18px 15px}.comparison-controls{flex-wrap:wrap;gap:14px}.comparison-controls label{flex-basis:100%}.comparison-controls>.primary{margin-left:auto}.comparison-values{grid-template-columns:1fr}.comparison-summary{display:grid}.comparison-change{padding:12px}.comparison-change-title button{margin-left:0}}
</style>
