<script setup lang="ts">
import { computed, onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { DefinitionAssigneesQuery, assigneeLabel } from '../definitionAssignees'

const props = defineProps<{ modelValue?: { workingMinutes?: string; recipientRule?: string }; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: { workingMinutes?: string; recipientRule?: string } | undefined]; beforeChange: [] }>()
const MAX_RECIPIENTS = 100
const enabled = computed(() => props.modelValue !== undefined)
const query = reactive(new DefinitionAssigneesQuery(api.definitionCopyRecipients))
const selected = computed(() => query.options.find(option => option.rule === props.modelValue?.recipientRule))
function toggle(event: Event) {
  if (props.disabled) return
  emit('beforeChange'); emit('update:modelValue', (event.target as HTMLInputElement).checked ? { workingMinutes: '', recipientRule: '' } : undefined)
}
function rememberMinutes() { if (!props.disabled) emit('beforeChange') }
function changeMinutes(event: Event) {
  if (!props.disabled) emit('update:modelValue', { ...props.modelValue, workingMinutes: (event.target as HTMLInputElement).value })
}
function choose(event: Event) {
  if (props.disabled || query.loading || query.error || !query.loaded) return
  const rule = (event.target as HTMLSelectElement).value
  if (rule && !query.options.some(item => item.rule === rule && (item.contextual || item.memberCount > 0 && item.memberCount <= MAX_RECIPIENTS))) return
  emit('beforeChange'); emit('update:modelValue', { ...props.modelValue, recipientRule: rule })
}
watch([() => props.scopeKey, enabled], ([scope, active]) => { if (scope && active) void query.load(scope); else query.clear() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="escalation-config" aria-label="超时升级配置">
    <label class="escalation-toggle"><input type="checkbox" :checked="enabled" :disabled="disabled" @change="toggle" />启用超时升级提醒</label>
    <template v-if="enabled">
      <label>超时后额外等待的工作分钟<input type="number" min="1" max="527040" step="1" :value="modelValue?.workingMinutes ?? ''" :disabled="disabled" placeholder="填写明确的等待时长" @focus="rememberMinutes" @input="changeMinutes" /></label>
      <label>升级提醒对象<select :value="modelValue?.recipientRule ?? ''" :disabled="disabled || query.loading || !query.loaded || !!query.error" @change="choose">
        <option value="">请选择接收人、组织或主管</option>
        <option v-if="modelValue?.recipientRule && !selected" :value="modelValue.recipientRule">{{ assigneeLabel(modelValue.recipientRule) }}（已有配置）</option>
        <option v-for="item in query.options" :key="item.rule" :value="item.rule" :disabled="!item.contextual && (item.memberCount < 1 || item.memberCount > MAX_RECIPIENTS)">{{ item.label }} · {{ item.contextual ? '按本轮任职解析' : `${item.memberCount} 人` }}</option>
      </select></label>
      <p v-if="query.loading" role="status">正在读取升级收件目录…</p>
      <p v-else-if="query.error" class="escalation-error" role="alert">{{ query.error }} 已有升级配置保持不变。</p>
      <p v-else-if="query.loaded && !query.options.length" class="escalation-error">当前没有有效收件人，请先维护组织目录。</p>
      <button v-if="scopeKey" type="button" class="secondary" :disabled="disabled || query.loading" @click="query.load(scopeKey)">刷新升级收件目录</button>
      <p>从原审批期限继续累计同一日历的工作时长，暂停时冻结剩余时间。每张任务创建时固定名单，只升级一次；转交、委派不会重选名单或重新计时。</p>
      <p>升级对象收到申请名称、编号和协调提醒；查看申请仍需原有权限。升级不改变审批人，也不会自动批准或驳回。</p>
    </template>
  </section>
</template>

<style scoped>
.escalation-config{border-top:1px dashed var(--line);margin-top:16px;padding-top:14px}.escalation-config label{margin-bottom:8px}.escalation-config p{font-size:11px;line-height:1.8;color:var(--muted);overflow-wrap:anywhere}.escalation-config .escalation-error{color:var(--red)}.escalation-config .escalation-toggle{display:flex;align-items:center;gap:8px}.escalation-toggle input{flex:0 0 15px;width:15px;min-width:15px;height:15px;margin:0;padding:0}.escalation-config .secondary{font-size:11px;padding:6px 9px}
</style>
