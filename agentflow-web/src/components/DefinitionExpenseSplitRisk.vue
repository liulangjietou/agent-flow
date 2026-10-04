<script setup lang="ts">
import { computed } from 'vue'
import type { Graph } from '../api'
import type { FormSchema } from '../formSchema'
import { expenseDefinition } from '../expenseDraft'
import { readSplitRule, splitGatewayIssue, splitRuleIssues, SPLIT_GATEWAY, type SplitRuleFields } from '../expenseSplitPolicy'
const props = defineProps<{ nodeId: string; graph: Graph; formSchema: FormSchema | null; disabled: boolean }>()
const emit = defineEmits<{ rule: [id: string, value: SplitRuleFields]; gateway: [id: string, value: string | undefined]; beforeChange: [] }>()
const selected = computed(() => props.graph.nodes.find(node => node.id === props.nodeId))
const expense = computed(() => expenseDefinition({ formSchema: props.formSchema }))
const rule = computed(() => readSplitRule((selected.value?.type === 'START' ? selected.value : props.graph.nodes.find(node => node.type === 'START'))?.properties ?? {}))
const gatewayValue = computed(() => selected.value?.properties.expenseSplitRouting)
const knownMode = computed(() => ['', 'ENABLED', 'DISABLED'].includes(rule.value.mode))
const knownGateway = computed(() => gatewayValue.value === undefined || gatewayValue.value === SPLIT_GATEWAY)
const visible = computed(() => selected.value && ((selected.value.type === 'START' && (expense.value || Object.values(rule.value).some(Boolean)))
  || (selected.value.type === 'EXCLUSIVE_GATEWAY' && expense.value) || gatewayValue.value !== undefined))
const issues = computed(() => splitRuleIssues(props.graph, rule.value))
const gatewayIssue = computed(() => splitGatewayIssue(props.graph, props.nodeId))
const gatewayReady = computed(() => expense.value && ['ENABLED', 'DISABLED'].includes(rule.value.mode) && !gatewayIssue.value)
function chooseMode(event: Event) {
  setMode((event.target as HTMLSelectElement).value)
}
function setMode(mode: string) {
  if (props.disabled || selected.value?.type !== 'START' || !['', 'ENABLED', 'DISABLED'].includes(mode) || mode && !expense.value) return
  const value = mode ? { ...rule.value, mode } : { mode: '', windowDays: '', threshold: '', currency: '' }
  if ((Object.keys(value) as Array<keyof SplitRuleFields>).every(key => value[key] === rule.value[key])) return
  emit('beforeChange'); emit('rule', props.nodeId, value)
}
function changeField(key: 'windowDays' | 'threshold' | 'currency', event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (props.disabled || !expense.value || selected.value?.type !== 'START' || !rule.value.mode || value === rule.value[key]) return
  emit('beforeChange'); emit('rule', props.nodeId, { ...rule.value, [key]: value })
}
function chooseGateway(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (props.disabled || !['', SPLIT_GATEWAY].includes(value) || value === (gatewayValue.value ?? '') || value && !gatewayReady.value) return
  emit('beforeChange'); emit('gateway', props.nodeId, value || undefined)
}
</script>
<template>
  <section v-if="visible" class="expense-split-risk" aria-label="跨单拆分风险">
    <template v-if="selected?.type === 'START'">
      <label>跨单拆分风险
        <select :value="rule.mode" :disabled="disabled" @change="chooseMode">
          <option value="">未配置</option>
          <option value="DISABLED" :disabled="!expense">明确关闭</option>
          <option value="ENABLED" :disabled="!expense">启用规则并使用指定业务网关</option>
          <option v-if="!knownMode" :value="rule.mode">已有配置待修正：{{ rule.mode }}</option>
        </select>
      </label>
      <template v-if="Object.values(rule).some(Boolean)">
        <label>滚动窗口（天）<input :value="rule.windowDays" inputmode="numeric" :disabled="disabled || !expense" @change="changeField('windowDays', $event)" /></label>
        <label>同类费用风险阈值<input :value="rule.threshold" inputmode="decimal" :disabled="disabled || !expense" @change="changeField('threshold', $event)" /></label>
        <label>本位币代码<input :value="rule.currency" placeholder="填写明确的三位大写代码" :disabled="disabled || !expense" @change="changeField('currency', $event)" /></label>
      </template>
      <p v-if="rule.mode === 'DISABLED'">本版本明确关闭跨单计算。参数可以全部留空，也可以保留完整规则；网关标记保留，但仍使用本单金额。</p>
      <p v-if="rule.mode === 'ENABLED'">同申请人、法人、本位币与费用类别在滚动窗口内的有效报销合计超过阈值且涉及至少两张单据时，指定业务网关使用冻结路由金额。请逐个选择需要应用规则的条件网关。</p>
      <p v-if="!knownMode || !expense" role="alert">已有配置已保留；仅完整费用表单支持此规则，请恢复表单或明确清除配置后再发布。</p>
      <button v-if="!rule.mode && Object.values(rule).some(Boolean)" type="button" class="secondary" :disabled="disabled" @click="setMode('')">清除残留跨单参数</button>
      <ul v-if="issues.length" role="status"><li v-for="issue in issues" :key="issue">{{ issue }}</li></ul>
    </template>
    <template v-if="selected?.type !== 'START' || gatewayValue !== undefined">
      <label>此网关的金额依据
        <select :value="gatewayValue ?? ''" :disabled="disabled" @change="chooseGateway">
          <option value="">本单金额</option>
          <option :value="SPLIT_GATEWAY" :disabled="!gatewayReady">使用冻结跨单路由金额</option>
          <option v-if="!knownGateway" :value="gatewayValue">已有配置待修正：{{ gatewayValue }}</option>
        </select>
      </label>
      <p v-if="gatewayIssue">{{ gatewayIssue }}</p>
      <p v-if="!expense || !['ENABLED', 'DISABLED'].includes(rule.mode)">须先在完整费用表单的开始节点明确配置规则状态。</p>
      <p v-if="gatewayValue !== undefined && (!gatewayReady || !knownGateway)" role="alert">已有标记已保留，请修正规则或明确选择“本单金额”清除标记。</p>
      <p v-if="gatewayValue === SPLIT_GATEWAY">仅在本流程版本启用跨单规则时使用提交时冻结的金额；财务审核及后续复核仍依据本单金额。</p>
    </template>
    <p>保存草稿不会改变已发布版本、原审批轮次或费用金额。</p>
  </section>
</template>
<style scoped>
.expense-split-risk{border-top:1px solid var(--line);margin:18px 0;padding-top:16px}.expense-split-risk p,.expense-split-risk li{font-size:12px;line-height:1.7;color:var(--muted);margin:8px 0}.expense-split-risk label{margin-bottom:8px}.expense-split-risk ul{padding-left:18px}
</style>
