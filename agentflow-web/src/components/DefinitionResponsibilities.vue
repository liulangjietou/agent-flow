<script setup lang="ts">
import { computed } from 'vue'
import type { Graph } from '../api'
import { MAX_RESPONSIBILITY_REFERENCES, priorApprovalNodes, responsibilityReferenceIssue, type ApprovalResponsibilities } from '../approvalResponsibilities'

const props = defineProps<{ modelValue: ApprovalResponsibilities; graph: Graph; nodeId: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: ApprovalResponsibilities]; beforeChange: [] }>()
const choices = computed(() => priorApprovalNodes(props.graph, props.nodeId))
const selected = computed(() => props.modelValue.differentApproverFrom?.split(',') ?? [])
const referenceIssue = computed(() => responsibilityReferenceIssue(props.modelValue.differentApproverFrom))
const excludeValid = computed(() => props.modelValue.excludeApplicant === undefined || ['true', 'false'].includes(props.modelValue.excludeApplicant))
const unavailable = computed(() => selected.value.filter(id => !choices.value.some(node => node.id === id)).map(id => ({
  id, name: props.graph.nodes.find(node => node.id === id)?.name ?? '步骤不存在'
})))

/** 每个明确操作产生一次撤销快照；另一项规则及其他属性由父视图原样保留。 */
function update(value: ApprovalResponsibilities) {
  if (props.disabled || value.excludeApplicant === props.modelValue.excludeApplicant && value.differentApproverFrom === props.modelValue.differentApproverFrom) return
  emit('beforeChange'); emit('update:modelValue', value)
}
function chooseApplicant(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (!['', 'true', 'false'].includes(value)) return
  update({ ...props.modelValue, excludeApplicant: value || undefined })
}
/** 删除或移动造成的无效引用继续保存，只有明确取消该项才移除。 */
function toggleReference(id: string, checked: boolean) {
  if (props.disabled || referenceIssue.value || checked && (!choices.value.some(node => node.id === id) || selected.value.length >= MAX_RESPONSIBILITY_REFERENCES)) return
  if (selected.value.includes(id) === checked) return
  const next = checked ? [...selected.value, id] : selected.value.filter(value => value !== id)
  update({ ...props.modelValue, differentApproverFrom: next.length ? next.join(',') : undefined })
}
function clearReferences() { update({ ...props.modelValue, differentApproverFrom: undefined }) }
</script>

<template>
  <section class="responsibility-config" aria-label="职责分离配置">
    <h4>职责分离</h4>
    <label>申请人限制
      <select :value="modelValue.excludeApplicant ?? ''" :disabled="disabled" @change="chooseApplicant">
        <option value="">未设置 · 沿用原选人规则</option>
        <option value="true">禁止申请人办理</option>
        <option value="false">不额外排除申请人</option>
        <option v-if="!excludeValid" :value="modelValue.excludeApplicant">已有配置待修正：{{ modelValue.excludeApplicant }}</option>
      </select>
    </label>
    <p v-if="!excludeValid" class="responsibility-error" role="alert">申请人限制无效，请明确选择上面的规则后再发布。</p>
    <fieldset class="responsibility-references" :disabled="disabled">
      <legend>与哪些步骤的批准人分开</legend>
      <template v-if="referenceIssue">
        <p class="responsibility-error" role="alert">{{ referenceIssue }}</p>
        <p class="responsibility-raw">原配置：{{ modelValue.differentApproverFrom === '' ? '空值' : modelValue.differentApproverFrom }}</p>
        <button type="button" class="secondary" :disabled="disabled" @click="clearReferences">清除无效引用并重新选择</button>
      </template>
      <template v-else>
        <label v-for="node in choices" :key="node.id" class="responsibility-option">
          <input type="checkbox" :checked="selected.includes(node.id)" :disabled="disabled || !selected.includes(node.id) && selected.length >= MAX_RESPONSIBILITY_REFERENCES"
            @change="toggleReference(node.id, ($event.target as HTMLInputElement).checked)" />
          <span>{{ node.name }}<small>{{ node.id }}</small></span>
        </label>
        <p v-if="!choices.length">此步骤前面还没有可引用的人工审批。</p>
        <div v-if="unavailable.length" class="responsibility-error" role="alert">
          <p>以下引用已保留，但不再是前序人工审批。请恢复步骤顺序，或明确移除引用后再发布。</p>
          <div v-for="node in unavailable" :key="node.id" class="responsibility-missing">
            <span>{{ node.name }}（{{ node.id }}）</span>
            <button type="button" class="secondary" :disabled="disabled" :aria-label="'移除引用 ' + node.id" @click="toggleReference(node.id, false)">移除引用</button>
          </div>
        </div>
        <p v-if="selected.length">已选择 {{ selected.length }} / {{ MAX_RESPONSIBILITY_REFERENCES }} 个步骤。</p>
      </template>
    </fieldset>
    <p>排除本轮所选步骤的实际批准人；未执行的分支、取消的会签和委派协助不算批准。</p>
    <p class="responsibility-note">转交、委派和加签也受这些规则约束。会签按排除后的名单计算人数；无人可办时阻止推进。</p>
  </section>
</template>

<style scoped>
.responsibility-config{border-top:1px solid var(--line);padding-top:16px;margin:18px 0}.responsibility-config h4{font-size:13px;color:var(--ink);margin:0 0 12px}.responsibility-config p{font-size:11px;line-height:1.8;color:var(--muted);margin:8px 0;overflow-wrap:anywhere}.responsibility-config label{font-size:12px;display:block;line-height:1.7}.responsibility-config select{width:100%;min-width:0}.responsibility-references{border:0;padding:0;margin:16px 0 10px;min-width:0}.responsibility-references legend{font-size:12px;line-height:1.7;margin-bottom:9px;padding:0;color:var(--ink)}.responsibility-config .responsibility-option{display:flex;align-items:flex-start;gap:8px;margin-bottom:9px}.responsibility-config .responsibility-option input[type=checkbox]{display:inline-block;width:16px;height:16px;flex:0 0 16px;margin:3px 0 0;padding:0;accent-color:var(--deep)}.responsibility-option span{min-width:0;overflow-wrap:anywhere}.responsibility-option small{display:block;font-size:10px;color:var(--muted)}.responsibility-config .responsibility-error,.responsibility-config .responsibility-error p{color:var(--red)}.responsibility-missing{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:8px 0;font-size:11px;overflow-wrap:anywhere}.responsibility-config .secondary{font-size:11px;padding:6px 9px}.responsibility-config .responsibility-note{padding:9px 11px;border-left:2px solid var(--deep);background:var(--paper)}.responsibility-config .responsibility-raw{max-height:120px;overflow:auto}.responsibility-config input:focus-visible,.responsibility-config select:focus-visible,.responsibility-config button:focus-visible{outline:2px solid var(--deep);outline-offset:2px}
</style>
