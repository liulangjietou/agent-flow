<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import type { AssistInputOptions, AssistRunDetail } from '../assistRuns'

const props = defineProps<{ applicationId: string; scopeKey: string; taskId: string; version: number; detail: AssistRunDetail | null; locked: boolean }>()
const emit = defineEmits<{ changed: [id: string] }>()
const options = ref<AssistInputOptions | null>(null), selected = ref<string[]>([])
const loading = ref(false), saving = ref(false), error = ref(''), notice = ref('')
const acceptedText = ref(''), comment = ref('')
let generation = 0, controller: AbortController | null = null, active = true
const context = computed(() => JSON.stringify([props.scopeKey, props.applicationId, props.taskId, props.version]))
const ready = computed(() => options.value?.applicationVersion === props.version && !loading.value && !saving.value && !props.locked)
const reviewable = computed(() => ready.value && props.detail?.status === 'COMPLETED' && options.value)
const current = computed(() => props.detail?.inputCurrent && props.detail.applicationVersion === props.version)
const messages: Record<string, string> = {
  AGENT_MODEL_DISABLED: '模型服务尚未启用，请联系管理员配置后刷新。', AGENT_MODEL_UNCONFIGURED: '模型配置不可用，请联系管理员检查。',
  AGENT_INPUT_CHANGED: '申请版本已变化，请刷新待办并重新核对输入。', AGENT_RUN_ACTIVE: '这份申请已有正在执行的摘要，请刷新记录查看。',
  AGENT_TARGET_CHANGED: '模型目的地已变化，请刷新输入目录并重新选择。',
  FORBIDDEN: '当前待办或所选字段已无权限，请刷新申请。', INVALID_AGENT_INPUT: '请选择 1 到 64 项来源，合计内容不能超过 64 KiB。',
  CONCURRENCY_CONFLICT: '摘要已被其他人处理，请刷新记录。', AGENT_RUN_STATE_CONFLICT: '摘要状态已变化，请刷新记录。'
}
function message(cause: unknown) {
  const failure = cause as Partial<ApiError>
  return messages[failure.code ?? ''] ?? failure.message ?? '摘要操作未完成，请重试。'
}
async function load() {
  const currentGeneration = ++generation
  controller?.abort(); controller = new AbortController()
  options.value = null; selected.value = []; error.value = ''; notice.value = ''; loading.value = true
  const request = controller
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; request.abort(); if (generation === currentGeneration) { loading.value = false; error.value = '输入目录读取超时，请刷新。' } }, 12_000)
  try {
    const result = await api.assistInput(props.applicationId, props.taskId, request.signal)
    if (generation !== currentGeneration || timedOut) return
    options.value = result
  } catch (cause) { if (generation === currentGeneration) error.value = timedOut ? '输入目录读取超时，请刷新。' : message(cause) }
  finally { clearTimeout(timer); if (generation === currentGeneration) loading.value = false }
}
async function generate() {
  if (!ready.value || !options.value?.enabled || !options.value.targetDigest || !selected.value.length) return
  const original = context.value
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const result = await api.generateAssist(props.applicationId, { taskId: props.taskId, expectedVersion: props.version, targetDigest: options.value.targetDigest, sourceIds: [...selected.value] })
    if (!active || context.value !== original) return
    selected.value = []; notice.value = '摘要已排队，可刷新记录查看执行结果。'; emit('changed', result.id)
  } catch (cause) { if (active && context.value === original) error.value = message(cause) }
  finally { if (active && context.value === original) saving.value = false }
}
async function review(action: 'ADOPT' | 'DISMISS') {
  if (!reviewable.value || !props.detail || action === 'ADOPT' && (!current.value || !acceptedText.value.trim())) return
  const original = context.value, id = props.detail.id
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const result = await api.reviewAssist(props.applicationId, id, { taskId: props.taskId, expectedVersion: props.version,
      expectedRunVersion: props.detail.version, action, ...(action === 'ADOPT' ? { acceptedText: acceptedText.value } : {}), comment: comment.value })
    if (!active || context.value !== original) return
    notice.value = action === 'ADOPT' ? '人工修订稿已采纳。审批请在待办操作区继续办理。' : '已记录未采纳意见。'
    emit('changed', result.id)
  } catch (cause) { if (active && context.value === original) error.value = message(cause) }
  finally { if (active && context.value === original) saving.value = false }
}
watch(context, () => { saving.value = false; acceptedText.value = ''; comment.value = ''; void load() }, { immediate: true, flush: 'sync' })
watch(() => props.detail?.id, () => { acceptedText.value = props.detail?.suggestion?.claims.map(claim => claim.text).join('\n') ?? ''; comment.value = '' })
onUnmounted(() => { active = false; generation++; controller?.abort() })
</script>

<template>
  <section class="assist-actions" aria-label="生成与复核摘要">
    <header><strong>选择本次发送的内容</strong><button type="button" class="quiet" :disabled="loading || saving || locked" @click="load">刷新输入目录</button></header>
    <p>仅发送你勾选的本轮内容。敏感、隐藏、脱敏字段和附件不进入摘要输入。</p>
    <p v-if="loading" role="status">正在核对待办和字段权限…</p>
    <p v-if="error" role="alert" class="assist-error">{{ error }}</p>
    <p v-if="notice" role="status" class="assist-notice">{{ notice }}</p>
    <template v-if="options">
      <p v-if="!options.enabled" role="status">{{ messages[options.unavailableCode ?? ''] ?? '模型服务不可用。' }}</p>
      <p v-else class="destination">发送至 {{ options.providerId }} · {{ options.model }} · {{ options.destination }}</p>
      <fieldset :disabled="!ready || !options.enabled"><legend>可选输入 · 已选 {{ selected.length }} 项</legend>
        <label v-for="source in options.sources" :key="source.reference.sourceId" class="source">
          <input v-model="selected" type="checkbox" :value="source.reference.sourceId" :aria-label="`发送${source.label}`" />
          <span><strong>{{ source.label }}</strong><span class="source-content">{{ source.content }}</span></span>
        </label>
      </fieldset>
      <button type="button" class="secondary" :disabled="!ready || !options.enabled || !selected.length || selected.length > 64" @click="generate">{{ saving ? '正在保存…' : '生成所选内容摘要' }}</button>
    </template>
    <section v-if="detail?.status === 'COMPLETED' && options" class="review" aria-label="人工复核摘要">
      <h4>人工复核</h4><p>先核对下方模型原文及来源，再编辑人工修订稿。原模型文本会单独保留。</p>
      <p v-if="!current" class="assist-error">生成后申请或待办已变化，本次摘要不能采纳；可记录未采纳意见。</p>
      <label>人工修订稿<textarea v-model="acceptedText" :disabled="!reviewable || !current" maxlength="20020" rows="5" /></label>
      <label>复核说明<textarea v-model="comment" :disabled="!reviewable" maxlength="2000" rows="2" /></label>
      <div class="review-buttons"><button type="button" class="primary" :disabled="!reviewable || !current || !acceptedText.trim()" @click="review('ADOPT')">采纳修订稿</button><button type="button" class="secondary" :disabled="!reviewable" @click="review('DISMISS')">记录未采纳</button></div>
    </section>
  </section>
</template>

<style scoped>
.assist-actions{margin:16px 0 24px;padding:18px;background:var(--paper);border:1px solid var(--line);border-radius:12px;font-size:12px}.assist-actions header{display:flex;align-items:center;justify-content:space-between;gap:12px}.assist-actions p{color:var(--muted);line-height:1.8}.assist-actions fieldset{padding:0;border:0;margin:12px 0}.assist-actions legend{font-weight:600;padding:0 0 10px}.source{display:flex;align-items:flex-start;gap:10px;padding:10px 0;border-top:1px solid var(--line);cursor:pointer}.source input{width:16px;height:16px;margin:3px 0;flex:none}.source>span{display:grid;gap:5px;min-width:0}.source-content{white-space:pre-wrap;overflow-wrap:anywhere;max-height:130px;overflow:auto;color:var(--muted);line-height:1.7}.assist-actions .assist-error{color:var(--red)}.assist-actions .assist-notice{color:var(--deep)}.destination{overflow-wrap:anywhere}.review{border-top:1px solid var(--line);margin-top:18px;padding-top:12px}.review label{display:block;margin:12px 0;line-height:1.8}.review textarea{display:block;width:100%;padding:10px;margin-top:5px;resize:vertical;border:1px solid var(--line);border-radius:8px;font:inherit;line-height:1.8;background:white}.review-buttons{display:flex;flex-wrap:wrap;gap:10px}
</style>
