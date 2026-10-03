<script setup lang="ts">
import { computed, onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { assigneeLabel, DefinitionAssigneesQuery } from '../definitionAssignees'
import { approvalModes, isCountersignMode } from '../approvalPolicy'
import { formAssigneeParts, formAssigneeFields, formAssigneeRelations } from '../formAssignees'
import type { FormSchema } from '../formSchema'

const props = defineProps<{ modelValue: string; approvalMode?: string; approvalPercentage?: string; scopeKey: string; disabled: boolean; formSchema?: FormSchema | null }>()
const emit = defineEmits<{ 'update:modelValue': [rule: string]; policy: [mode: string, percentage: string | undefined]; beforeChange: [] }>()
const query = reactive(new DefinitionAssigneesQuery(api.definitionAssignees))
const users = computed(() => query.options.filter(option => option.rule.startsWith('user:')))
const roles = computed(() => query.options.filter(option => option.rule.startsWith('role:')))
const selected = computed(() => query.options.find(option => option.rule === props.modelValue))
const fromField = computed(() => props.modelValue.startsWith('field:'))
const fieldSelection = computed(() => formAssigneeParts(props.modelValue))
const fields = computed(() => formAssigneeFields(props.formSchema))
function chooseSource(event: Event) {
  const source = (event.target as HTMLSelectElement).value
  if (props.disabled || !['directory', 'field'].includes(source) || (source === 'field') === fromField.value) return
  emit('beforeChange'); emit('update:modelValue', source === 'field' ? 'field::PERSON' : '')
}
function chooseField(event: Event) {
  const field = (event.target as HTMLSelectElement).value
  if (props.disabled || !fromField.value || !fields.value.some(value => value.key === field)) return
  emit('beforeChange'); emit('update:modelValue', `field:${field}:${fieldSelection.value.relation}`)
}
function chooseRelation(event: Event) {
  const relation = (event.target as HTMLSelectElement).value
  if (props.disabled || !fromField.value || !formAssigneeRelations.some(value => value.value === relation)) return
  emit('beforeChange'); emit('update:modelValue', `field:${fieldSelection.value.fieldKey}:${relation}`)
}
function choose(event: Event) {
  const rule = (event.target as HTMLSelectElement).value
  if (props.disabled || query.loading || !query.loaded || query.error || rule === props.modelValue) return
  if (rule && !query.options.some(option => option.rule === rule && (option.memberCount > 0 || option.contextual))) return
  emit('beforeChange'); emit('update:modelValue', rule)
}
function chooseMode(event: Event) {
  const mode = (event.target as HTMLSelectElement).value
  if (props.disabled || !approvalModes.some(value => value === mode) || mode === (props.approvalMode ?? 'SINGLE')) return
  emit('beforeChange'); emit('policy', mode, mode === 'PERCENT' ? props.approvalPercentage : undefined)
}
/** 保留用户原始输入，由发布校验报告非法比例；不擅自四舍五入或补默认值。 */
function choosePercentage(event: Event) {
  if (!props.disabled && props.approvalMode === 'PERCENT') emit('policy', 'PERCENT', (event.target as HTMLInputElement).value)
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
        <option value="ANY">任一人通过 · 一人同意即通过</option>
        <option value="PERCENT">按比例会签 · 达到比例才通过</option>
        <option v-if="approvalMode && !approvalModes.some(mode => mode === approvalMode)" :value="approvalMode">未知方式：{{ approvalMode }}</option>
      </select>
    </label>
    <label v-if="approvalMode === 'PERCENT'">通过比例（%）
      <input :value="approvalPercentage ?? ''" inputmode="numeric" placeholder="填写 1 至 100 的整数" :disabled="disabled" @focus="emit('beforeChange')" @input="choosePercentage" />
    </label>
    <label>选人来源<select :value="fromField ? 'field' : 'directory'" :disabled="disabled" @change="chooseSource"><option value="directory">指定人员、组织或任职关系</option><option value="field">申请表单中的选择</option></select></label>
    <template v-if="fromField">
      <label>来源字段<select :value="fieldSelection.fieldKey" :disabled="disabled || !fields.length" @change="chooseField"><option value="">请选择必填单选字段</option><option v-if="fieldSelection.fieldKey && !fields.some(field => field.key === fieldSelection.fieldKey)" :value="fieldSelection.fieldKey">{{ fieldSelection.fieldKey }}（来源已失效）</option><option v-for="field in fields" :key="field.key" :value="field.key">{{ field.label }} · {{ field.key }}</option></select></label>
      <label>组织关系<select :value="fieldSelection.relation" :disabled="disabled" @change="chooseRelation"><option v-if="!formAssigneeRelations.some(value => value.value === fieldSelection.relation)" :value="fieldSelection.relation">待修复关系：{{ fieldSelection.relation || '未选择' }}</option><option v-for="relation in formAssigneeRelations" :key="relation.value" :value="relation.value">{{ relation.label }}</option></select></label>
      <p v-if="!fields.length" role="alert" class="assignee-error">请先添加顶层必填单选字段，并从组织目录添加选项。</p>
      <p v-else-if="!fields.some(field => field.key === fieldSelection.fieldKey)" role="alert" class="assignee-error">请选择有效来源字段；已有配置不会自动替换。</p>
      <p>提交时根据所选组织固定本轮名单，退回或撤回后重提会重新解析。组织关系缺失、没有审批人或冻结人员失去审批资格时阻止推进。</p>
    </template>
    <label v-else>审批人
      <select :value="modelValue" :disabled="disabled || query.loading || !query.loaded || !!query.error || !query.options.length" @change="choose">
        <option value="">请选择审批人或角色</option>
        <option v-if="modelValue && !selected" :value="modelValue">{{ assigneeLabel(modelValue) }}（已有配置）</option>
        <optgroup label="指定审批人"><option v-for="option in users" :key="option.rule" :value="option.rule" :disabled="!option.memberCount && !option.contextual">{{ option.label }}</option></optgroup>
        <optgroup label="组织与审批角色"><option v-for="option in roles" :key="option.rule" :value="option.rule" :disabled="!option.memberCount && !option.contextual">{{ assigneeLabel(option.rule, option.label) }} · {{ option.contextual ? '按本轮任职解析' : `${option.memberCount} 人` }}</option></optgroup>
      </select>
    </label>
    <template v-if="!fromField">
    <p v-if="query.loading" role="status">正在读取当前租户审批人…</p>
    <div v-else-if="query.error" role="alert" class="assignee-error"><p>{{ query.error }}</p><p>已有配置已保留。</p></div>
    <template v-else-if="query.loaded">
      <p v-if="!query.options.length" class="assignee-error">当前没有可用审批账号，请先配置身份源和审批角色。</p>
      <p v-else-if="modelValue && (!selected || (!selected.contextual && selected.memberCount < 1))" class="assignee-error" role="alert">已有配置当前匹配不到审批人，请重新选择后发布。</p>
      <p v-else-if="selected">{{ selected.contextual ? '进入节点时，从申请人本轮选择的任职解析。关系缺失或人员无效时阻止推进。' : isCountersignMode(approvalMode) ? `目录当前匹配 ${selected.memberCount} 人，实际名单在进入节点时确定。` : selected.rule.startsWith('user:') ? '由该账号办理；若配置职责分离，仍需满足对应约束。' : `目录当前匹配 ${selected.memberCount} 人，由进入节点后保留的候选人办理。` }}</p>
    </template>
    </template>
    <p v-if="approvalMode === 'ALL'" class="assignee-help">{{ fromField ? '使用提交时的名单，进入节点后应用职责分离约束。' : '进入节点时固定审批名单。' }}全部同意才流转，任一驳回结束整轮。支持委派后回交，不支持转交和释放。</p>
    <p v-else-if="approvalMode === 'ANY' || approvalMode === 'PERCENT'" class="assignee-help">{{ fromField ? '使用提交时的名单，进入节点后应用职责分离约束并固定所需同意人数。' : '进入节点时固定名单和所需同意人数。' }}{{ approvalMode === 'ANY' ? '任一责任人同意即通过。' : '人数按比例向上取整，例如 3 人按 50% 需要 2 人同意。' }}达标后结束其余待办，保留实际意见；达标前任一驳回结束整轮。支持委派协助，不支持增减人员、转交和释放。</p>
    <button v-if="scopeKey && !fromField" type="button" class="secondary" :disabled="disabled || query.loading" @click="query.load(scopeKey)">{{ query.error ? '重试读取审批人' : '刷新审批人' }}</button>
    <p class="assignee-help">{{ fromField ? '发布时核对来源字段的全部选项，要求与关系类型一致且均能匹配有效审批人。' : '名单来自当前租户配置的选人目录，发布时重新核对；主管与部门负责人按本轮选择的任职解析，人员关系在组织目录维护。' }}</p>
  </section>
</template>

<style scoped>
.assignee-config{margin-bottom:18px}.assignee-config p{font-size:12px;line-height:1.7;color:var(--muted);margin:8px 0;overflow-wrap:anywhere}.assignee-config .assignee-error,.assignee-error p{color:var(--red)}.assignee-config .secondary{font-size:11px;padding:6px 9px}.assignee-config .assignee-help{font-size:11px}.assignee-config label{margin-bottom:8px}
</style>
