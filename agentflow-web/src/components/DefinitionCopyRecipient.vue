<script setup lang="ts">
import { computed, onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { DefinitionAssigneesQuery } from '../definitionAssignees'
const props = defineProps<{ modelValue: string; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [rule: string]; beforeChange: [] }>()
const query = reactive(new DefinitionAssigneesQuery(api.definitionCopyRecipients))
const selected = computed(() => query.options.find(option => option.rule === props.modelValue))
function choose(event: Event) {
  const rule = (event.target as HTMLSelectElement).value
  if (props.disabled || query.loading || !query.loaded || query.error || rule === props.modelValue) return
  if (rule && !query.options.some(option => option.rule === rule && (option.contextual || option.memberCount > 0 && option.memberCount <= 100))) return
  emit('beforeChange'); emit('update:modelValue', rule)
}
watch(() => props.scopeKey, scope => { void query.load(scope) }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>
<template>
  <section class="copy-recipient" aria-label="抄送收件人配置">
    <label>抄送给
      <select :value="modelValue" :disabled="disabled || query.loading || !query.loaded || !!query.error" @change="choose">
        <option value="">请选择收件人、部门或岗位</option>
        <option v-if="modelValue && !selected" :value="modelValue">已有配置（当前不可用）</option>
        <option v-for="option in query.options" :key="option.rule" :value="option.rule" :disabled="!option.contextual && (option.memberCount < 1 || option.memberCount > 100)">{{ option.label }} · {{ option.contextual ? '按本轮任职解析' : `${option.memberCount} 人` }}</option>
      </select>
    </label>
    <p v-if="query.loading" role="status">正在读取收件目录…</p>
    <p v-else-if="query.error" role="alert">{{ query.error }}</p>
    <p v-else-if="query.loaded && !query.options.length">当前没有有效收件人，请在组织目录维护人员。</p>
    <button v-if="scopeKey" type="button" class="secondary" :disabled="disabled || query.loading" @click="query.load(scopeKey)">刷新收件目录</button>
    <p>到达节点时固定实际收件人，只读本轮提交内容。收件人无需审批资格；字段隐藏、脱敏和附件下载继续受权限约束。</p>
  </section>
</template>
<style scoped>
.copy-recipient p{font-size:12px;line-height:1.8;color:var(--muted)}.copy-recipient [role=alert]{color:var(--red)}.copy-recipient .secondary{font-size:11px;padding:7px 10px}
</style>
