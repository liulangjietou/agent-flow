<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { CalendarRead, type CalendarPage, type CalendarSummary, type CalendarVersionPage } from '../businessCalendars'
import type { DesignerDeadline } from '../designerGraph'

const props = defineProps<{ modelValue?: DesignerDeadline; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: DesignerDeadline | undefined]; beforeChange: [] }>()
const directory = reactive(new CalendarRead<CalendarPage>())
const history = reactive(new CalendarRead<CalendarVersionPage>())
const selected = reactive(new CalendarRead<CalendarSummary>())
const calendars = ref<CalendarSummary[]>([]), versions = ref<CalendarSummary[]>([])
const nextKey = ref<string | null>(null), nextRevision = ref<number | null>(null)
const enabled = computed(() => props.modelValue !== undefined)
const calendarId = computed(() => props.modelValue?.calendarId ?? '')
const revision = computed(() => props.modelValue?.calendarRevision ?? '')
const knownCalendar = computed(() => calendars.value.some(value => value.id === calendarId.value))
const knownRevision = computed(() => versions.value.some(value => String(value.revision) === revision.value))

function change(value: DesignerDeadline | undefined) {
  if (props.disabled) return
  emit('beforeChange'); emit('update:modelValue', value)
}
function toggle(event: Event) {
  change((event.target as HTMLInputElement).checked ? { calendarId: '', calendarRevision: '', workingMinutes: '' } : undefined)
}
function chooseCalendar(event: Event) {
  const value = calendars.value.find(item => item.id === (event.target as HTMLSelectElement).value)
  if (!value || directory.loading || directory.error) return
  // 明确选择日历时使用页面所见修订；后台刷新和查询结果从不改写节点。
  change({ ...props.modelValue, calendarId: value.id, calendarRevision: String(value.revision) })
}
function chooseRevision(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (history.loading || history.error || !versions.value.some(item => String(item.revision) === value)) return
  change({ ...props.modelValue, calendarRevision: value })
}
function rememberMinutes() { if (!props.disabled) emit('beforeChange') }
function changeMinutes(event: Event) {
  if (!props.disabled) emit('update:modelValue', { ...props.modelValue, workingMinutes: (event.target as HTMLInputElement).value })
}
async function loadDirectory(more = false) {
  if (!props.scopeKey) return
  const value = await directory.load(signal => api.definitionCalendars(more ? nextKey.value ?? undefined : undefined, signal))
  if (value) { calendars.value = more ? [...calendars.value, ...value.items] : value.items; nextKey.value = value.nextAfterKey }
}
async function loadVersions(more = false) {
  if (!props.scopeKey || !calendarId.value) return
  const value = await history.load(signal => api.definitionCalendarVersions(calendarId.value, more ? nextRevision.value ?? undefined : undefined, signal))
  if (value) { versions.value = more ? [...versions.value, ...value.items] : value.items; nextRevision.value = value.nextBeforeRevision }
}
function loadSelected() {
  if (!props.scopeKey || !calendarId.value || !revision.value) { selected.clear(); return }
  void selected.load(signal => api.definitionCalendarVersion(calendarId.value, revision.value, signal))
}
watch(() => props.scopeKey, () => {
  directory.clear(); calendars.value = []; nextKey.value = null
  if (props.scopeKey) void loadDirectory()
}, { immediate: true, flush: 'sync' })
watch([() => props.scopeKey, calendarId], () => {
  history.clear(); versions.value = []; nextRevision.value = null
  if (props.scopeKey && calendarId.value) void loadVersions()
}, { immediate: true, flush: 'sync' })
watch([() => props.scopeKey, calendarId, revision], loadSelected, { immediate: true, flush: 'sync' })
onUnmounted(() => { directory.clear(); history.clear(); selected.clear() })
</script>

<template>
  <section class="deadline-config" aria-label="审批期限配置">
    <p class="deadline-help">新任务从创建时按固定日历修订计时；转交、委派和回交不重置期限。到期后站内提醒当前处理人，审批结论仍由人工决定。</p>
    <label class="deadline-toggle"><input type="checkbox" :checked="enabled" :disabled="disabled" @change="toggle" />设置审批期限</label>
    <template v-if="enabled">
      <label>期限工作日历
        <select :value="calendarId" :disabled="disabled || directory.loading || !!directory.error" @change="chooseCalendar">
          <option value="">请选择工作日历</option>
          <option v-if="calendarId && !knownCalendar" :value="calendarId">{{ selected.value?.name ?? calendarId }}（已有配置）</option>
          <option v-for="value in calendars" :key="value.id" :value="value.id">{{ value.name }} · {{ value.key }}</option>
        </select>
      </label>
      <p v-if="directory.loading" role="status">正在读取工作日历…</p>
      <p v-else-if="directory.error" class="deadline-error" role="alert">{{ directory.error }} 已有期限配置已保留。</p>
      <p v-else-if="!calendars.length">暂无工作日历，请联系管理员先配置企业作息。</p>
      <div class="deadline-actions">
        <button type="button" class="secondary" :disabled="disabled || directory.loading" @click="loadDirectory()">{{ directory.error ? '重试读取日历' : '刷新日历列表' }}</button>
        <button v-if="nextKey" type="button" class="secondary" :disabled="disabled || directory.loading || !!directory.error" @click="loadDirectory(true)">更多日历</button>
      </div>
      <label>期限日历修订
        <select :value="revision" :disabled="disabled || !calendarId || history.loading || !!history.error" @change="chooseRevision">
          <option value="">请选择明确修订</option>
          <option v-if="revision && !knownRevision" :value="revision">V{{ revision }}（已有配置）</option>
          <option v-for="value in versions" :key="value.revision" :value="String(value.revision)">V{{ value.revision }} · {{ value.name }}</option>
        </select>
      </label>
      <p v-if="history.loading" role="status">正在读取日历修订…</p>
      <p v-else-if="history.error" class="deadline-error" role="alert">{{ history.error }} 已有修订号已保留。</p>
      <div v-if="calendarId" class="deadline-actions">
        <button type="button" class="secondary" :disabled="disabled || history.loading" @click="loadVersions()">{{ history.error ? '重试读取修订' : '刷新修订列表' }}</button>
        <button v-if="nextRevision" type="button" class="secondary" :disabled="disabled || history.loading || !!history.error" @click="loadVersions(true)">更早修订</button>
      </div>
      <label>期限工作分钟<input type="number" min="1" max="527040" step="1" :value="modelValue?.workingMinutes ?? ''" :disabled="disabled" placeholder="例如 480" @focus="rememberMinutes" @input="changeMinutes" /></label>
      <p v-if="selected.loading" role="status">正在核对引用修订…</p>
      <div v-else-if="selected.error" role="alert"><p class="deadline-error">引用修订暂不可用：{{ selected.error }}</p><button type="button" class="secondary" :disabled="disabled" @click="loadSelected">重试核对引用</button></div>
      <p v-else-if="selected.value">已引用 {{ selected.value.name }} / V{{ selected.value.revision }}，时区 {{ selected.value.zoneId }}。</p>
      <p class="deadline-help">仅累计所选修订的工作时段，休息和日期例外按该修订执行。日历后续修改不会自动改变此节点引用。</p>
    </template>
    <p v-else>未设置期限。</p>
  </section>
</template>

<style scoped>
.deadline-config{border-top:1px solid var(--line);margin:18px 0;padding-top:16px}.deadline-config p{font-size:12px;line-height:1.7;color:var(--muted);overflow-wrap:anywhere;margin:8px 0}.deadline-config .deadline-error{color:var(--red)}.deadline-config .deadline-help{font-size:11px}.deadline-config label{margin-bottom:8px}.deadline-config .deadline-toggle{display:flex;align-items:center;gap:8px}.deadline-config .deadline-toggle input{flex:0 0 15px;width:15px;min-width:15px;height:15px;margin:0;padding:0}.deadline-actions{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:10px}.deadline-config .secondary{font-size:11px;padding:6px 9px}
</style>
