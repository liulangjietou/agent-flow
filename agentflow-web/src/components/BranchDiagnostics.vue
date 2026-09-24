<script setup lang="ts">
import type { BranchDiagnostic, Graph } from '../api'
import type { FormSchema } from '../formSchema'
const props = defineProps<{ items: BranchDiagnostic[]; graph?: Graph; formSchema?: FormSchema | null; locatable?: boolean }>()
const emit = defineEmits<{ locate: [id: string] }>()
const fieldLabel = (key: string) => props.formSchema?.fields.find(field => field.key === key)?.label ?? key
const nodeLabel = (id: string) => props.graph?.nodes.find(node => node.id === id)?.name ?? id
const titles = { BRANCH_COVERAGE_GAP: '有输入无法进入任何分支', BRANCH_OVERLAP: '多个分支同时满足', BRANCH_COVERAGE_UNPROVEN: '需要核对分支覆盖' }
</script>
<template>
  <ul v-if="items.length" class="branch-diagnostics" aria-label="分支检查结果">
    <li v-for="(item, index) in items" :key="index" :class="item.severity.toLowerCase()">
      <div class="diagnostic-heading"><span>{{ item.severity === 'ERROR' ? '阻止发布' : '提醒' }}</span><strong>{{ nodeLabel(item.gatewayId) }} · {{ titles[item.code] }}</strong></div>
      <p v-if="item.code === 'BRANCH_COVERAGE_UNPROVEN'">当前条件无法自动证明覆盖所有输入。建议设置“其他情况”分支，并使用流程模拟核对业务场景。</p>
      <p v-else>例如：<strong>{{ fieldLabel(item.field) }}</strong> {{ item.missingValue ? '未填写' : `= ${item.sampleValue}` }}。{{ item.code === 'BRANCH_COVERAGE_GAP' ? '请补齐条件或设置默认分支。' : '执行时仍按分支顺序取第一个匹配项，请确认优先级符合业务规则。' }}</p>
      <p v-if="item.edgeIds.length" class="matched-branches">同时满足：{{ item.edgeIds.join(' → ') }}</p>
      <div v-if="locatable" class="diagnostic-actions"><button type="button" class="secondary" @click="emit('locate', item.gatewayId)">定位条件节点</button><button v-for="edge in item.edgeIds" :key="edge" type="button" class="secondary" @click="emit('locate', edge)">定位分支 {{ edge }}</button></div>
    </li>
  </ul>
</template>
<style scoped>
.branch-diagnostics{list-style:none;margin:14px 0 0;padding:0;display:grid;gap:10px;min-width:0}.branch-diagnostics>li{padding:14px;border:1px solid #decbaa;border-left:3px solid #b38435;border-radius:8px;background:#fffbf3;color:var(--ink)}.branch-diagnostics>li.error{border-color:#e4bbb5;border-left-color:var(--red);background:#fff8f6}.diagnostic-heading{display:flex;align-items:flex-start;gap:9px;flex-wrap:wrap}.diagnostic-heading>span{font-size:10px;font-weight:600;color:#90651f}.error .diagnostic-heading>span{color:var(--red)}.diagnostic-heading strong{font-size:12px;overflow-wrap:anywhere}.branch-diagnostics p{font-size:12px;line-height:1.8;margin:8px 0;overflow-wrap:anywhere}.matched-branches{color:var(--muted)}.diagnostic-actions{display:flex;flex-wrap:wrap;gap:6px}.diagnostic-actions button{font-size:11px;white-space:normal;overflow-wrap:anywhere;max-width:100%}
</style>
