<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import FormFields from './FormFields.vue'
import { api, type ApiError, type FieldPreviewResult } from '../api'
import type { FormSchema } from '../formSchema'

const props = defineProps<{ schema: FormSchema | null; values: Record<string, unknown>; approvalNodes: Array<{ id: string; name: string }>; scopeKey: string; invalid: boolean }>()
const mode = ref<'admin' | 'nodes'>('admin'), selected = ref<string[]>([])
const result = ref<FieldPreviewResult | null>(null), loading = ref(false), error = ref('')
const READ_TIMEOUT_MS = 12_000
let generation = 0, controller: AbortController | null = null
const canPreview = computed(() => !!props.scopeKey && !!props.schema && !props.invalid
  && (mode.value === 'admin' || selected.value.length > 0 && selected.value.every(id => props.approvalNodes.some(node => node.id === id))))
function clear() {
  generation++; controller?.abort(); controller = null
  result.value = null; loading.value = false; error.value = ''
}
watch(() => props.scopeKey, () => { mode.value = 'admin'; selected.value = []; clear() }, { flush: 'sync' })
watch(() => props.approvalNodes.map(node => node.id).join('\n'), () => {
  selected.value = selected.value.filter(id => props.approvalNodes.some(node => node.id === id))
}, { flush: 'sync' })
// 只在有效输入变化时清除结果，父组件重绘生成等值数组不会取消当前请求。
watch(() => JSON.stringify([props.schema, props.values, props.approvalNodes, props.scopeKey, props.invalid, mode.value, selected.value]), clear, { flush: 'sync' })
onUnmounted(clear)
async function preview() {
  if (!canPreview.value || !props.schema) return
  clear()
  const version = generation, request = new AbortController()
  controller = request; loading.value = true
  const timeout = setTimeout(() => {
    request.abort()
    if (generation === version) { loading.value = false; error.value = '权限预览读取超时，请重试。' }
  }, READ_TIMEOUT_MS)
  try {
    const view = await api.previewFields({ formSchema: props.schema, values: props.values, nodeIds: mode.value === 'nodes' ? [...selected.value] : [] }, request.signal)
    if (version === generation && !request.signal.aborted) result.value = view
  } catch (cause) {
    if (version === generation) error.value = request.signal.aborted ? '权限预览读取超时，请重试。' : (cause as ApiError).message ?? '权限预览失败，请重试。'
  } finally { clearTimeout(timeout); if (version === generation) loading.value = false }
}
</script>

<template>
  <section class="permission-preview" aria-label="字段权限展示预览">
    <h4>权限展示预览</h4>
    <p>使用上方申请人测试填写内容，查看管理员或审批节点收到的字段。预览内容不会保存。</p>
    <label class="preview-role">查看身份<select v-model="mode"><option value="admin">管理员（没有节点身份）</option><option value="nodes">审批节点</option></select></label>
    <fieldset v-if="mode === 'nodes'"><legend>审批节点，可多选</legend>
      <label v-for="node in approvalNodes" :key="node.id"><input v-model="selected" type="checkbox" :value="node.id" />{{ node.name }}</label>
      <p v-if="!approvalNodes.length">当前没有人工审批节点。</p>
      <p v-else>同时参与多个节点时，按更严格的权限展示。</p>
    </fieldset>
    <button type="button" class="secondary" :disabled="!canPreview || loading" @click="preview">{{ loading ? '正在生成预览…' : '生成权限预览' }}</button>
    <p v-if="invalid">请先修正字段配置。</p>
    <p v-if="error" class="preview-error" role="alert">{{ error }}</p>
    <div v-if="result" class="preview-result" aria-label="权限预览结果">
      <p role="status">{{ result.restricted ? '已应用隐藏或脱敏规则。' : '所选视角可读取全部已配置字段。' }}</p>
      <FormFields :schema="result.schema" :model-value="result.payload" readonly />
    </div>
  </section>
</template>

<style scoped>
.permission-preview{margin-top:24px;padding-top:20px;border-top:1px solid var(--line);font-size:12px}.permission-preview h4{font-size:14px;margin:0 0 9px}.permission-preview p{font-size:11px;line-height:1.8;color:var(--muted)}.preview-role{display:grid;gap:8px;margin:14px 0}.preview-role select{width:100%;min-width:0;border:1px solid var(--line);border-radius:7px;padding:9px;background:white;font:inherit;color:var(--ink)}fieldset{padding:10px 12px;margin:14px 0;border:1px solid var(--line);border-radius:8px}fieldset label{display:flex;align-items:center;gap:8px;margin:10px 0;overflow-wrap:anywhere}fieldset input{accent-color:var(--deep);flex-shrink:0}.preview-result{margin-top:15px;padding:12px;background:var(--paper);border:1px solid var(--line);border-radius:8px;overflow-wrap:anywhere}.permission-preview .preview-error{color:var(--red)}
</style>
