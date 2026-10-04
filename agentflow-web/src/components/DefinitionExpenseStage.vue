<script setup lang="ts">
import { computed } from 'vue'
import type { FormSchema } from '../formSchema'
const props = defineProps<{ modelValue?: string; formSchema: FormSchema | null; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string | undefined]; beforeChange: [] }>()
const stages = [
  { value: 'BUSINESS', name: '普通业务审批' },
  { value: 'PRIOR_REQUEST_REVIEW', name: '事前额度例外审批' },
  { value: 'BUDGET_REVIEW', name: '预算负责人审批' },
  { value: 'RECEIPT', name: '纸质原件签收' },
  { value: 'FINANCE_REVIEW', name: '财务审核' },
  { value: 'FINANCE_RECHECK', name: '财务复核' },
]
const visible = computed(() => props.modelValue !== undefined || props.formSchema?.fields.some(field => field.key === 'expenseDetails'))
const selected = computed(() => props.modelValue ?? 'BUSINESS')
const known = computed(() => stages.some(stage => stage.value === selected.value))
function choose(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (props.disabled || !stages.some(stage => stage.value === value) || value === selected.value) return
  emit('beforeChange'); emit('update:modelValue', value === 'BUSINESS' ? undefined : value)
}
</script>

<template>
  <section v-if="visible" class="expense-stage-config" aria-label="费用审批职责">
    <label>费用审批职责
      <select :value="selected" :disabled="disabled" @change="choose">
        <option v-if="!known" :value="selected">{{ selected }}（待修正）</option>
        <option v-for="stage in stages" :key="stage.value" :value="stage.value">{{ stage.name }}</option>
      </select>
    </label>
    <p v-if="selected === 'PRIOR_REQUEST_REVIEW'">服务端累计超容差时必须经过本节点，位于财务审核之前；不能通过相邻同人规则自动跳过。流程表单需有布尔字段 priorRequestOverTolerance，值由提交时的额度依据产生。</p>
    <p v-if="selected === 'BUDGET_REVIEW'">预算节点须单人决策，并位于所有财务审核路径之前。等待真实预算结果：原预算确认后系统通过，明确需要例外时由预算负责人审批；同意后仍须等待预算确认，不能由相邻同人规则跳过。</p>
    <p v-if="selected === 'RECEIPT'">办理人需明确确认本轮纸件后才能同意；退回或撤回后重提，需要重新签收。</p>
    <p v-else-if="selected === 'FINANCE_REVIEW' || selected === 'FINANCE_RECHECK'">同意前检查本轮纸件要求及当前核定金额的预算冻结结果。</p>
    <p v-if="selected !== 'BUSINESS'">请在字段权限中将此节点的“费用明细”设为只读。每条结束路径都须经过财务审核；需纸件时先安排签收。</p>
  </section>
</template>

<style scoped>
.expense-stage-config{border-top:1px solid var(--line);margin:18px 0;padding-top:16px}.expense-stage-config p{font-size:12px;line-height:1.7;color:var(--muted);margin:8px 0}.expense-stage-config label{margin-bottom:8px}
</style>
