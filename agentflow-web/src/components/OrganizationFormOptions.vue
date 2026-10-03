<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { DefinitionAssigneesQuery } from '../definitionAssignees'
import type { FormAssigneeKind, FormAssigneeOption } from '../formAssignees'
import type { FormOption } from '../formSchema'

const props = defineProps<{ scopeKey: string; disabled: boolean; existing: FormOption[] }>()
const emit = defineEmits<{ add: [option: FormOption] }>()
const query = reactive(new DefinitionAssigneesQuery<FormAssigneeOption>(api.formAssigneeOptions, '组织'))
const opened = ref(false)
const kind = ref<FormAssigneeKind>('PERSON')
const options = computed(() => query.options.filter(option => option.kind === kind.value))
const atLimit = computed(() => props.existing.length >= 50)
function open() {
  if (props.disabled || !props.scopeKey) return
  opened.value = true
  void query.load(props.scopeKey)
}
function add(event: Event) {
  const target = event.target as HTMLSelectElement
  const option = options.value.find(option => option.id === target.value)
  target.value = ''
  if (props.disabled || atLimit.value || !query.loaded || query.loading || query.error || !option || props.existing.some(value => value.value === option.id)) return
  emit('add', { value: option.id, label: option.label })
}
watch(() => props.scopeKey, () => { opened.value = false; kind.value = 'PERSON'; query.clear() }, { flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="organization-form-options" aria-label="组织来源选项">
    <button v-if="!opened" type="button" class="secondary" :disabled="disabled || !scopeKey || atLimit" @click="open">从组织目录添加选项</button>
    <template v-else>
      <p>用于表单选人时，请将字段设为必填，仅保留同一类组织选项，再到审批节点选择对应关系。</p>
      <label>来源类型<select v-model="kind" :disabled="disabled"><option value="PERSON">人员</option><option value="DEPARTMENT">部门</option><option value="POSITION">岗位</option></select></label>
      <p v-if="query.loading" role="status">正在读取本租户组织目录…</p>
      <p v-else-if="query.error" role="alert">{{ query.error }}</p>
      <template v-else-if="query.loaded">
        <label>添加组织选项<select value="" :disabled="disabled || atLimit || !options.length" @change="add"><option value="">请选择{{ kind === 'PERSON' ? '人员' : kind === 'DEPARTMENT' ? '部门' : '岗位' }}</option><option v-for="option in options" :key="option.id" :value="option.id" :disabled="existing.some(value => value.value === option.id)">{{ option.label }}{{ kind === 'PERSON' ? '' : ` · ${option.memberCount} 人` }}{{ kind === 'DEPARTMENT' ? option.headAvailable ? ' · 已有负责人' : ' · 未配置有效负责人' : '' }}{{ existing.some(value => value.value === option.id) ? '（已添加）' : '' }}</option></select></label>
        <p v-if="!options.length">当前没有这一类组织来源，请先在组织目录维护。</p>
      </template>
      <p v-if="atLimit" role="status">已达到 50 个选项上限。</p>
      <button type="button" class="secondary" :disabled="disabled || query.loading" @click="open">{{ query.error ? '重试读取组织目录' : '刷新组织目录' }}</button>
    </template>
  </section>
</template>

<style scoped>
.organization-form-options{padding:10px 0}.organization-form-options p{font-size:12px;line-height:1.6;color:var(--muted)}.organization-form-options [role=alert]{color:var(--red)}.organization-form-options label{display:block;margin:8px 0}
</style>
