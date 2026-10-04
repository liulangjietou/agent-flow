<script setup lang="ts">
import { computed } from 'vue'
import type { FormSchema } from '../formSchema'
const props = defineProps<{ modelValue?: string; selfApproval?: string; formSchema: FormSchema | null; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string | undefined]; beforeChange: [] }>()
const supported = 'AUTO_PASS_ADJACENT'
const expense = computed(() => props.formSchema?.fields.some(field => field.key === 'expenseDetails') ?? false)
const ready = computed(() => expense.value && props.selfApproval === 'ESCALATE_SUPERVISOR')
const visible = computed(() => expense.value || props.modelValue !== undefined)
const known = computed(() => props.modelValue === undefined || props.modelValue === supported)
function choose(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (props.disabled || !['', supported].includes(value) || value === (props.modelValue ?? '') || value && !ready.value) return
  emit('beforeChange'); emit('update:modelValue', value || undefined)
}
</script>

<template>
  <section v-if="visible" class="expense-duplicate-approval" aria-label="相邻重复审批">
    <label>相邻重复审批
      <select :value="modelValue ?? ''" :disabled="disabled" @change="choose">
        <option value="">未启用 · 每个节点人工办理</option>
        <option :value="supported" :disabled="!ready">自动通过相邻同人业务审批</option>
        <option v-if="!known" :value="modelValue">已有配置待修正：{{ modelValue }}</option>
      </select>
    </label>
    <p v-if="!ready">仅适用于费用报销，须先启用“申请人转本次任职的直属主管”，以固定本轮候选并执行职责分离。</p>
    <p v-if="modelValue === supported">按实际经过的路径识别相邻业务审批，包括中间的金额判断。前后实际审批人相同且后一个任务只有一名有效审批人时，自动通过后一个节点，并记录来源任务和规则版本。</p>
    <p v-if="modelValue === supported">非相邻重复、多人候选、多人会签和有多个不同审批前驱的汇合保留人工办理。财务签收、审核与复核始终人工办理。</p>
    <p v-if="modelValue !== undefined && (!ready || !known)" role="alert">已有配置已保留；请恢复所需费用策略或明确清除相邻重复审批配置，再校验并发布。</p>
    <p>保存草稿不会改变已发布版本或原审批轮次。</p>
  </section>
</template>

<style scoped>
.expense-duplicate-approval{border-top:1px solid var(--line);margin:18px 0;padding-top:16px}.expense-duplicate-approval p{font-size:12px;line-height:1.7;color:var(--muted);margin:8px 0}.expense-duplicate-approval label{margin-bottom:8px}
</style>
