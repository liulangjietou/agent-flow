<script setup lang="ts">
import { computed, onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { assigneeLabel, DefinitionAssigneesQuery } from '../definitionAssignees'

const props = defineProps<{ modelValue: string; approvalMode?: string; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [rule: string]; 'update:approvalMode': [mode: string]; beforeChange: [] }>()
const query = reactive(new DefinitionAssigneesQuery(api.definitionAssignees))
const users = computed(() => query.options.filter(option => option.rule.startsWith('user:')))
const roles = computed(() => query.options.filter(option => option.rule.startsWith('role:')))
const selected = computed(() => query.options.find(option => option.rule === props.modelValue))
function choose(event: Event) {
  const rule = (event.target as HTMLSelectElement).value
  if (props.disabled || query.loading || !query.loaded || query.error || rule === props.modelValue) return
  if (rule && !query.options.some(option => option.rule === rule && (option.memberCount > 0 || option.contextual))) return
  emit('beforeChange'); emit('update:modelValue', rule)
}
function chooseMode(event: Event) {
  const mode = (event.target as HTMLSelectElement).value
  if (props.disabled || !['SINGLE', 'ALL'].includes(mode) || mode === (props.approvalMode ?? 'SINGLE')) return
  emit('beforeChange'); emit('update:approvalMode', mode)
}
watch(() => props.scopeKey, scope => { void query.load(scope) }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="assignee-config" aria-label="审批人配置">
    <label>审批方式
      <select :value="approvalMode ?? 'SINGLE'" :disabled="disabled" @change="chooseMode">
        <option value="SINGLE">单人审批 · 一人处理即可</option>
        <option value="ALL">全员会签 · 全部同意才通过</option>
        <option v-if="approvalMode && !['SINGLE', 'ALL'].includes(approvalMode)" :value="approvalMode">未知方式：{{ approvalMode }}</option>
      </select>
    </label>
    <label>审批人
      <select :value="modelValue" :disabled="disabled || query.loading || !query.loaded || !!query.error || !query.options.length" @change="choose">
        <option value="">请选择审批人或角色</option>
        <option v-if="modelValue && !selected" :value="modelValue">{{ assigneeLabel(modelValue) }}（已有配置）</option>
        <optgroup label="指定审批人"><option v-for="option in users" :key="option.rule" :value="option.rule" :disabled="!option.memberCount && !option.contextual">{{ option.label }}</option></optgroup>
        <optgroup label="组织与审批角色"><option v-for="option in roles" :key="option.rule" :value="option.rule" :disabled="!option.memberCount && !option.contextual">{{ assigneeLabel(option.rule, option.label) }} · {{ option.contextual ? '按本轮任职解析' : `${option.memberCount} 人` }}</option></optgroup>
      </select>
    </label>
    <p v-if="query.loading" role="status">正在读取当前租户审批人…</p>
    <div v-else-if="query.error" role="alert" class="assignee-error"><p>{{ query.error }}</p><p>已有配置已保留。</p></div>
    <template v-else-if="query.loaded">
      <p v-if="!query.options.length" class="assignee-error">当前没有可用审批账号，请先配置身份源和审批角色。</p>
      <p v-else-if="modelValue && (!selected || (!selected.contextual && selected.memberCount < 1))" class="assignee-error" role="alert">已有配置当前匹配不到审批人，请重新选择后发布。</p>
      <p v-else-if="selected">{{ selected.contextual ? '进入节点时，从申请人本轮选择的任职解析。关系缺失或人员无效时阻止推进。' : approvalMode === 'ALL' ? `当前匹配 ${selected.memberCount} 人，每人收到一张待办。` : selected.rule.startsWith('user:') ? '任务直接交给该账号审批。' : `当前有 ${selected.memberCount} 人可审批，由其中一人处理。` }}</p>
    </template>
    <p v-if="approvalMode === 'ALL'" class="assignee-help">进入节点时固定审批名单；全部同意才流转，任一驳回结束整轮。支持委派后回交，不支持转交和释放。</p>
    <button v-if="scopeKey" type="button" class="secondary" :disabled="disabled || query.loading" @click="query.load(scopeKey)">{{ query.error ? '重试读取审批人' : '刷新审批人' }}</button>
    <p class="assignee-help">名单来自当前租户配置的选人目录，发布时重新核对；主管与部门负责人按本轮选择的任职解析，人员关系在组织目录维护。</p>
  </section>
</template>

<style scoped>
.assignee-config{margin-bottom:18px}.assignee-config p{font-size:12px;line-height:1.7;color:var(--muted);margin:8px 0;overflow-wrap:anywhere}.assignee-config .assignee-error,.assignee-error p{color:var(--red)}.assignee-config .secondary{font-size:11px;padding:6px 9px}.assignee-config .assignee-help{font-size:11px}.assignee-config label{margin-bottom:8px}
</style>
