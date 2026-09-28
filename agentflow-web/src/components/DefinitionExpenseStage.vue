<script setup lang="ts">
import { computed } from 'vue'
import type { FormSchema } from '../formSchema'
const props = defineProps<{ modelValue?: string; formSchema: FormSchema | null; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string | undefined]; beforeChange: [] }>()
const stages = [
  { value: 'BUSINESS', name: '普通业务审批' },
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
    <p v-if="selected === 'RECEIPT'">办理人需明确确认本轮纸件后才能同意；退回或撤回后重提，需要重新签收。</p>
    <p v-else-if="selected === 'FINANCE_REVIEW' || selected === 'FINANCE_RECHECK'">同意前检查本轮纸件要求及当前核定金额的预算冻结结果。</p>
    <p v-if="selected !== 'BUSINESS'">请在字段权限中将此节点的“费用明细”设为只读。每条结束路径都须经过财务审核；需纸件时先安排签收。</p>
  </section>
</template>

<style scoped>
.expense-stage-config{border-top:1px solid var(--line);margin:18px 0;padding-top:16px}.expense-stage-config p{font-size:12px;line-height:1.7;color:var(--muted);margin:8px 0}.expense-stage-config label{margin-bottom:8px}
</style>
