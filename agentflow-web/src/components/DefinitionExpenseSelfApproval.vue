<script setup lang="ts">
import { computed } from 'vue'
import type { FormSchema } from '../formSchema'
const props = defineProps<{ modelValue?: string; formSchema: FormSchema | null; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string | undefined]; beforeChange: [] }>()
const supported = 'ESCALATE_SUPERVISOR'
const expense = computed(() => props.formSchema?.fields.some(field => field.key === 'expenseDetails') ?? false)
const visible = computed(() => expense.value || props.modelValue !== undefined)
const known = computed(() => props.modelValue === undefined || props.modelValue === supported)
function choose(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (props.disabled || !['', supported].includes(value) || value === (props.modelValue ?? '') || value && !expense.value) return
  emit('beforeChange'); emit('update:modelValue', value || undefined)
}
</script>

<template>
  <section v-if="visible" class="expense-self-approval" aria-label="费用自审批处理">
    <label>费用自审批处理
      <select :value="modelValue ?? ''" :disabled="disabled" @change="choose">
        <option value="">未启用 · 沿用原选人规则</option>
        <option :value="supported" :disabled="!expense">申请人转本次任职的直属主管</option>
        <option v-if="!known" :value="modelValue">已有配置待修正：{{ modelValue }}</option>
      </select>
    </label>
    <p v-if="modelValue === supported">正式提交时固定各节点候选及本次任职依据。业务候选包含申请人时上溯一级，无有效主管则阻断提交。组织变更在重新提交的新轮次生效。</p>
    <p v-if="modelValue === supported">财务签收、审核和复核保留人工办理，并强制排除申请人及本单实际业务审批人。此规则优先于单个节点的申请人限制配置。</p>
    <p v-if="!expense || !known" role="alert">此策略只适用于费用报销表单，需修正配置后重新发布。</p>
    <p>保存草稿不会改变已发布版本或原审批轮次。</p>
  </section>
</template>

<style scoped>
.expense-self-approval{border-top:1px solid var(--line);margin:18px 0;padding-top:16px}.expense-self-approval p{font-size:12px;line-height:1.7;color:var(--muted);margin:8px 0}.expense-self-approval label{margin-bottom:8px}
</style>
