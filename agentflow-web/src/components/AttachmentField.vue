<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api, type ApiError } from '../api'
import { attachmentIds, fileDigest, fileSize, type AttachmentContext, type AttachmentInput, type AttachmentMetadata, type AttachmentOptions } from '../attachments'

const props = defineProps<{ modelValue: unknown; fieldPath: string; context?: AttachmentContext; readonly?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [ids: string[]]; uploading: [busy: boolean] }>()
const ids = computed(() => attachmentIds(props.modelValue))
const metadata = ref<Record<string, AttachmentMetadata>>({})
const options = ref<AttachmentOptions | null>(null)
const error = ref('')
const loading = ref(false)
const uploading = ref(false)
const phase = ref('')
const downloading = ref('')
const picker = ref<HTMLInputElement | null>(null)
const retryTarget = ref<string | null>(null)
const attempt = shallowRef<{ file: File; key: string; id?: string; input?: AttachmentInput } | null>(null)
const contextKey = computed(() => JSON.stringify([props.context?.scopeKey, props.context?.applicationId, props.context?.roundNo, props.fieldPath, !!props.readonly]))
let generation = 0, readGeneration = 0
let readController: AbortController | null = null, uploadController: AbortController | null = null, downloadController: AbortController | null = null
const urls = new Set<string>()
const alive = (epoch: number) => epoch === generation
const message = (cause: unknown) => (cause as ApiError).message ?? '附件操作未完成，请重试。'
function busy(value: boolean) { uploading.value = value; emit('uploading', value) }
function cancelRequests() { readController?.abort(); uploadController?.abort(); downloadController?.abort() }

async function refresh() {
  readController?.abort()
  const context = props.context, epoch = generation, sequence = ++readGeneration
  loading.value = false; metadata.value = {}; options.value = null
  if (!context?.scopeKey) return
  const controller = readController = new AbortController()
  const current = () => alive(epoch) && sequence === readGeneration && !controller.signal.aborted
  const timeout = setTimeout(() => {
    if (current()) { error.value = '附件信息加载超时，请重试。'; loading.value = false }
    controller.abort()
  }, 12_000)
  loading.value = true; error.value = ''
  try {
    const [limits, values] = await Promise.all([
      props.readonly ? Promise.resolve(null) : api.attachmentOptions(controller.signal),
      Promise.all(ids.value.map(id => api.attachment(context.applicationId, id, context.roundNo, controller.signal)))
    ])
    if (current()) { options.value = limits; metadata.value = Object.fromEntries(values.map(file => [file.id, file])) }
  } catch (cause) { if (current()) error.value = controller.signal.aborted ? '附件信息加载超时，请重试。' : message(cause) }
  finally { clearTimeout(timeout); if (current()) loading.value = false }
}

watch(contextKey, () => {
  generation++; readGeneration++; cancelRequests(); busy(false)
  metadata.value = {}; options.value = null; error.value = ''; phase.value = ''; attempt.value = null; downloading.value = ''; retryTarget.value = null
  if (picker.value) picker.value.value = ''
  for (const url of urls) URL.revokeObjectURL(url)
  urls.clear(); void refresh()
}, { immediate: true, flush: 'sync' })
watch(() => ids.value.join(','), () => { void refresh() })
onUnmounted(() => { generation++; cancelRequests(); busy(false); for (const url of urls) URL.revokeObjectURL(url) })

function choose(target: string | null = null) {
  if (props.disabled || props.readonly || uploading.value || !props.context?.scopeKey || !options.value?.enabled) return
  retryTarget.value = target
  picker.value?.click()
}
async function selected(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  ;(event.target as HTMLInputElement).value = ''
  if (!file || props.disabled || props.readonly || uploading.value || !props.context?.scopeKey || !options.value) return
  if (file.size > options.value.maxFileBytes) { error.value = `单份文件不能超过 ${fileSize(options.value.maxFileBytes)}。`; return }
  if (!retryTarget.value && ids.value.length >= options.value.maxAttachmentsPerField) { error.value = '这个字段的附件数量已达上限。'; return }
  attempt.value = { file, key: crypto.randomUUID(), ...(retryTarget.value ? { id: retryTarget.value } : {}) }
  await upload()
}

async function upload() {
  const active = attempt.value, context = props.context
  if (!active || !context?.scopeKey || context.expectedVersion == null || props.disabled || props.readonly || uploading.value) return
  const epoch = generation, controller = uploadController = new AbortController()
  const current = () => alive(epoch) && !controller.signal.aborted
  const timeout = setTimeout(() => {
    if (current()) { error.value = '上传结果未确认，请重试原文件；已登记的文件不会另建一份。'; phase.value = ''; busy(false) }
    controller.abort()
  }, 120_000)
  busy(true); error.value = ''
  try {
    phase.value = '正在核对文件…'
    const sha256 = await fileDigest(active.file)
    if (!current()) return
    if (!active.id) {
      active.input ??= { fieldPath: props.fieldPath, expectedVersion: context.expectedVersion, filename: active.file.name, size: active.file.size, sha256 }
      phase.value = '正在登记上传…'
      const registered = await api.reserveAttachment(context.applicationId, active.input, active.key, controller.signal)
      if (!current()) return
      active.id = registered.id; metadata.value = { ...metadata.value, [registered.id]: registered }
      emit('update:modelValue', [...ids.value, registered.id])
    } else {
      const registered = await api.attachment(context.applicationId, active.id, undefined, controller.signal)
      if (!current()) return
      if (registered.sha256 !== sha256 || registered.size !== active.file.size) throw { message: '所选文件与原上传不同。请选回原文件，或移除引用后添加新文件。' }
    }
    phase.value = '正在上传并校验…'
    const uploaded = await api.uploadAttachment(context.applicationId, active.id, props.context!.expectedVersion!, active.file, controller.signal)
    if (!current()) return
    readGeneration++; readController?.abort(); loading.value = false
    metadata.value = { ...metadata.value, [uploaded.id]: uploaded }; attempt.value = null; phase.value = '上传完成，请保存申请内容。'
  } catch (cause) {
    if (current()) error.value = controller.signal.aborted ? '上传结果未确认，请重试原文件；已登记的文件不会另建一份。' : message(cause)
  } finally { clearTimeout(timeout); if (current()) busy(false) }
}

async function download(id: string) {
  if (!props.context?.scopeKey || downloading.value) return
  const context = props.context, epoch = generation, controller = downloadController = new AbortController()
  const current = () => alive(epoch) && !controller.signal.aborted
  const timeout = setTimeout(() => {
    if (current()) { error.value = '下载超时，请重试。'; downloading.value = '' }
    controller.abort()
  }, 120_000)
  downloading.value = id; error.value = ''
  try {
    const file = await api.attachment(context.applicationId, id, context.roundNo, controller.signal)
    if (!current()) return
    const content = await api.downloadAttachment(context.applicationId, id, context.roundNo, controller.signal)
    if (!current()) return
    if (content.size !== file.size) throw { message: '下载内容未完整接收，请重试。' }
    const url = URL.createObjectURL(content); urls.add(url)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = file.filename; anchor.click()
    setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url) }, 1000)
  } catch (cause) { if (current()) error.value = controller.signal.aborted ? '下载超时，请重试。' : message(cause) }
  finally { clearTimeout(timeout); if (current()) downloading.value = '' }
}
function remove(id: string) {
  if (props.disabled || props.readonly || uploading.value) return
  emit('update:modelValue', ids.value.filter(value => value !== id))
  if (attempt.value?.id === id) attempt.value = null
  phase.value = '已移除本次引用，保存后生效；历史轮次文件保留。'
}
</script>

<template>
  <div class="attachment-field" :aria-busy="loading || uploading">
    <p v-if="!context" class="attachment-help">{{ readonly ? `${ids.length} 份附件引用；在实际申请中可核对并下载。` : '先保存申请草稿，再打开草稿上传附件。' }}</p>
    <template v-else>
      <ul v-if="ids.length" class="attachment-list">
        <li v-for="id in ids" :key="id"><div><strong>{{ metadata[id]?.filename ?? '附件信息待加载' }}</strong><small v-if="metadata[id]">{{ fileSize(metadata[id].size) }} · {{ metadata[id].status === 'READY' ? '完整性已校验' : metadata[id].status === 'FAILED' ? '上传未完成，可重试' : '等待上传完成' }}</small></div>
          <div class="attachment-actions"><button v-if="metadata[id]?.status === 'READY'" type="button" :disabled="!!downloading" @click="download(id)">{{ downloading === id ? '下载中…' : '下载' }}</button><button v-else-if="!readonly" type="button" :disabled="disabled || uploading || !options?.enabled" @click="choose(id)">选择原文件恢复</button><button v-if="!readonly" type="button" :disabled="disabled || uploading" @click="remove(id)">移除引用</button></div>
        </li>
      </ul>
      <p v-else class="attachment-help">未上传附件。</p>
      <template v-if="!readonly">
        <input ref="picker" class="attachment-picker" type="file" tabindex="-1" aria-label="选择附件文件" @change="selected" />
        <button type="button" class="secondary" :disabled="disabled || uploading || !options?.enabled || ids.length >= (options?.maxAttachmentsPerField ?? 10)" @click="choose()">添加附件</button>
        <button v-if="attempt && !uploading" type="button" class="secondary" :disabled="disabled" @click="upload">重试本次上传</button>
        <p v-if="options?.enabled" class="attachment-help">单份最多 {{ fileSize(options.maxFileBytes) }}，每字段最多 {{ options.maxAttachmentsPerField }} 份。本申请累计最多 {{ options.maxApplicationUploads }} 次上传 / {{ fileSize(options.maxApplicationBytes) }}，包含历史及未完成上传。当前未接入内容扫描。</p>
        <p v-else-if="!loading" class="attachment-help">附件存储未就绪，请联系管理员配置持久目录。</p>
      </template>
      <p v-if="phase" class="attachment-help" role="status">{{ phase }}</p>
      <p v-if="error" class="attachment-error" role="alert">{{ error }} <button type="button" :disabled="loading || uploading" @click="error = ''; refresh()">刷新附件信息</button></p>
    </template>
  </div>
</template>

<style scoped>
.attachment-field{min-width:0}.attachment-list{list-style:none;margin:0 0 12px;padding:0}.attachment-list li{display:flex;align-items:center;justify-content:space-between;gap:12px;border:1px solid var(--line);border-radius:8px;padding:10px 12px;margin-bottom:8px}.attachment-list li>div:first-child{min-width:0}.attachment-list strong{display:block;font-size:12px;font-weight:500;overflow-wrap:anywhere}.attachment-list small,.attachment-help{font-size:11px;line-height:1.7;color:var(--muted)}.attachment-list small{display:block;margin-top:4px}.attachment-actions{display:flex;flex-wrap:wrap;gap:8px;flex-shrink:0}.attachment-actions button,.attachment-error button{font-size:11px;color:var(--deep);border:0;background:transparent;padding:4px}.attachment-picker{display:none!important}.attachment-error{font-size:12px;line-height:1.7;color:var(--red)}.attachment-field>.secondary{margin-right:8px}@media(max-width:600px){.attachment-list li{align-items:flex-start;flex-direction:column}}
</style>
