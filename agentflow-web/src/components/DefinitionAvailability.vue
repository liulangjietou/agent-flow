<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api, type ApiError, type DefinitionAvailabilityChange, type DefinitionAvailabilityInput } from '../api'

const props = defineProps<{ definitionId: string; revision: number; startEnabled?: boolean; scopeKey: string;
  locked: boolean; error: string; refreshVersion?: number }>()
const emit = defineEmits<{ change: [input: DefinitionAvailabilityInput]; refresh: [] }>()
const reason = ref(''), intent = ref<boolean | null>(null), validation = ref('')
const actionButton = ref<HTMLButtonElement | null>(null), reasonInput = ref<HTMLTextAreaElement | null>(null)
const rows = ref<DefinitionAvailabilityChange[]>([]), nextBefore = ref<number | undefined>()
const loading = ref(false), loaded = ref(false), historyError = ref('')
let generation = 0, active: AbortController | null = null

/** 首次点击只打开说明表单，确认时发送当前修订和原操作意图。 */
async function prepare() {
  if (props.locked || props.startEnabled === undefined) return
  intent.value = !props.startEnabled; reason.value = ''; validation.value = ''
  await nextTick(); reasonInput.value?.focus()
}
function cancel() {
  if (props.locked) return
  intent.value = null; reason.value = ''; validation.value = ''
  void nextTick(() => actionButton.value?.focus())
}
function execute() {
  if (props.locked || props.startEnabled === undefined || intent.value === null) return
  const note = reason.value.trim()
  if (!note || note.length > 2000) { validation.value = '请填写 1 至 2000 字的操作原因。'; return }
  emit('change', { startEnabled: intent.value, expectedRevision: props.revision, reason: note })
}
async function load(more = false) {
  if (!props.scopeKey || !props.definitionId || more && (loading.value || nextBefore.value === undefined)) return
  active?.abort()
  const controller = new AbortController(), request = ++generation
  active = controller; loading.value = true; historyError.value = ''
  if (!more) { rows.value = []; nextBefore.value = undefined; loaded.value = false }
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const result = await Promise.race([
      api.definitionAvailabilityHistory(props.definitionId, more ? nextBefore.value : undefined, controller.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject(new Error('读取版本治理记录超时，请重试。')) }, 12_000) })
    ])
    if (generation !== request) return
    const revisions = new Set(rows.value.map(row => row.revision))
    rows.value = more ? [...rows.value, ...result.items.filter(row => !revisions.has(row.revision))] : result.items
    nextBefore.value = result.nextBeforeRevision; loaded.value = true
  } catch (cause) {
    if (generation === request) historyError.value = (cause as ApiError)?.message ?? '版本治理记录读取失败。'
  } finally { clearTimeout(timeout); if (generation === request) { loading.value = false; active = null } }
}
watch(() => [props.definitionId, props.scopeKey, props.revision, props.startEnabled], (current, previous) => {
  const returnFocus = intent.value !== null && previous && current[0] === previous[0] && current[1] === previous[1]
  intent.value = null; reason.value = ''; validation.value = ''; generation++; active?.abort()
  rows.value = []; loaded.value = false; nextBefore.value = undefined
  void load()
  if (returnFocus) void nextTick(() => {
    if (props.definitionId === current[0] && props.scopeKey === current[1]) actionButton.value?.focus()
  })
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, () => { void load() })
onBeforeUnmount(() => { generation++; active?.abort() })
</script>

<template>
  <section class="availability-panel panel" aria-label="版本使用与治理记录">
    <div class="availability-heading"><div><p class="eyebrow">VERSION AVAILABILITY</p><h3>版本使用</h3></div><strong :class="{ disabled: startEnabled === false }">{{ startEnabled === undefined ? '状态待刷新' : startEnabled ? '允许新发起' : '已停用' }}</strong></div>
    <p class="availability-help">停用后不能新建申请、首次提交或退回/撤回后重提，恢复原版本后才可继续提交。此开关只作用于当前发布版本，运行中的审批继续办理。</p>
    <p v-if="startEnabled === undefined" class="availability-help">原操作响应没有记录发起状态，请刷新版本状态后再操作。</p>
    <div class="availability-actions"><button v-if="startEnabled !== undefined" ref="actionButton" class="secondary" :disabled="locked || intent !== null" @click="prepare">{{ startEnabled ? '停用此版本' : '恢复此版本' }}</button><button class="quiet" :disabled="locked" @click="emit('refresh')">刷新版本状态</button></div>
    <form v-if="intent !== null" class="availability-form" @submit.prevent="execute">
      <h4>{{ intent ? '确认恢复此版本' : '确认停用此版本' }}</h4>
      <label>操作原因<textarea ref="reasonInput" v-model="reason" :disabled="locked" maxlength="2000" rows="3" placeholder="说明制度变化或恢复原因，供后续核对。" /></label>
      <p v-if="validation" class="availability-error" role="alert">{{ validation }}</p>
      <div class="availability-actions"><button type="button" class="secondary" :disabled="locked" @click="cancel">取消</button><button type="submit" class="primary" :disabled="locked || !reason.trim()">{{ intent ? '确认恢复' : '确认停用' }}</button></div>
    </form>
    <p v-if="error" class="availability-error" role="alert">{{ error }}</p>
    <div class="availability-history"><h4>停用与恢复记录</h4>
      <p v-if="historyError" class="availability-error" role="alert">{{ historyError }} <button class="quiet" :disabled="loading" @click="load(loaded)">重试读取</button></p>
      <p v-if="loading && !loaded" role="status">正在读取记录…</p>
      <p v-else-if="loaded && !rows.length" class="availability-help">尚无本功能记录的停用或恢复操作。</p>
      <ol v-if="rows.length"><li v-for="row in rows" :key="row.revision"><div><strong>{{ row.startEnabled ? '恢复版本' : '停用版本' }}</strong><span>{{ row.changedBy }} · {{ row.authorizedRole === 'ADMIN' ? '平台管理员' : '流程管理员' }}</span><time>{{ new Date(row.changedAt).toLocaleString('zh-CN') }}</time></div><p>{{ row.reason }}</p></li></ol>
      <button v-if="nextBefore !== undefined" class="secondary" :disabled="loading" @click="load(true)">{{ loading ? '正在加载…' : '加载更早记录' }}</button>
    </div>
  </section>
</template>

<style scoped>
.availability-panel{padding:22px;margin:18px 0}.availability-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.availability-heading h3{margin:6px 0;font-size:20px}.availability-heading>strong{font-size:12px;color:var(--deep);background:var(--soft);padding:7px 10px;border-radius:6px}.availability-heading>strong.disabled{color:#8b4b27;background:#fff0df}.availability-help{font-size:12px;color:var(--muted);line-height:1.9}.availability-actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}.availability-actions button{font-size:12px}.availability-form{margin-top:18px;padding:18px;background:var(--soft);border-radius:8px}.availability-form h4{margin:0 0 14px}.availability-form label{display:grid;gap:9px;font-size:12px}.availability-form textarea{width:100%;box-sizing:border-box;resize:vertical;line-height:1.8}.availability-error{color:var(--red);font-size:12px;line-height:1.8}.availability-history{border-top:1px solid var(--line);margin-top:22px;padding-top:4px}.availability-history h4{font-size:13px}.availability-history ol{list-style:none;padding:0;margin:0}.availability-history li{padding:14px 0;border-bottom:1px solid var(--line);font-size:12px}.availability-history li>div{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.availability-history span,.availability-history time{font-size:11px;color:var(--muted)}.availability-history li p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}.availability-history>button{margin-top:15px}@media(max-width:620px){.availability-panel{padding:16px}.availability-heading{align-items:start}.availability-form{padding:14px}.availability-heading>strong{white-space:nowrap}}
</style>
