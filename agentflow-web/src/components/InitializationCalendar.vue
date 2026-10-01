<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { cloneCalendar, weekdays, type BusinessCalendar, type CalendarInput, type CalendarPage, type CalendarVersionPage, type CalendarSummary, type CalendarOverride, type Weekday, type CalendarPeriod } from '../businessCalendars'
import { InitializationRead, readInitializationCalendar } from '../tenantInitialization'
import CalendarHours from './CalendarHours.vue'

const props = defineProps<{ scopeKey: string; source: 'CREATE' | 'EXISTING'; created: CalendarInput; selected: BusinessCalendar | null; locked: boolean }>()
const emit = defineEmits<{ 'update:source': [value: 'CREATE' | 'EXISTING']; 'update:created': [value: CalendarInput]; 'update:selected': [value: BusinessCalendar | null]; busy: [value: boolean] }>()
const directory = reactive(new InitializationRead<CalendarPage>('日历目录'))
const versions = reactive(new InitializationRead<CalendarVersionPage>('日历历史'))
const detail = reactive(new InitializationRead<BusinessCalendar>('日历版本'))
const items = ref<CalendarSummary[]>([]), history = ref<CalendarSummary[]>([])
const nextKey = ref<string | null>(null), nextRevision = ref<number | null>(null), historyId = ref('')
const busy = computed(() => directory.loading || versions.loading || detail.loading)
const shown = computed(() => props.source === 'CREATE' ? props.created.rules : props.selected?.rules)
let active = true
const input = (event: Event) => (event.target as HTMLInputElement).value

function patch(value: Partial<CalendarInput>) { emit('update:created', { ...cloneCalendar(props.created), ...value }) }
function setWeek(day: Weekday, periods: CalendarPeriod[]) { patch({ rules: { ...props.created.rules, weeklyHours: { ...props.created.rules.weeklyHours, [day]: periods } } }) }
function setOverride(index: number, value: Partial<CalendarOverride>) {
  patch({ rules: { ...props.created.rules, overrides: props.created.rules.overrides.map((day, i) => i === index ? { ...day, ...value } : day) } })
}
function removeOverride(index: number) { patch({ rules: { ...props.created.rules, overrides: props.created.rules.overrides.filter((_, i) => i !== index) } }) }
function addOverride() { patch({ rules: { ...props.created.rules, overrides: [...props.created.rules.overrides, { date: '', periods: [], note: '' }] } }) }
function example() {
  patch({ rules: { ...props.created.rules, weeklyHours: Object.fromEntries(weekdays.slice(0, 5).map(day => [day.key, [{ start: '09:00', end: '18:00' }]])) } })
}
async function loadDirectory(more = false) {
  if (!active || props.source !== 'EXISTING' || directory.loading) return
  if (!more) { items.value = []; nextKey.value = null }
  const result = await directory.load(signal => api.calendars(more ? nextKey.value ?? undefined : undefined, signal))
  if (result) { items.value = more ? [...items.value, ...result.items] : result.items; nextKey.value = result.nextAfterKey }
}
async function loadVersions(more = false) {
  if (!historyId.value || versions.loading) return
  const id = historyId.value
  if (!more) { history.value = []; nextRevision.value = null }
  const result = await versions.load(signal => api.calendarVersions(id, more ? nextRevision.value ?? undefined : undefined, signal))
  if (result) { history.value = more ? [...history.value, ...result.items] : result.items; nextRevision.value = result.nextBeforeRevision }
}
async function chooseVersion(id: string, revision: number) {
  if (props.locked) return
  emit('update:selected', null)
  const result = await detail.load(signal => api.calendarVersion(id, revision, signal).then(value => readInitializationCalendar(value, id, revision)))
  if (result && props.source === 'EXISTING') emit('update:selected', result)
}
async function chooseCalendar(value: CalendarSummary) {
  if (props.locked) return
  versions.clear(); historyId.value = value.id
  await Promise.all([loadVersions(), chooseVersion(value.id, value.revision)])
}
function clear() {
  directory.clear(); versions.clear(); detail.clear(); items.value = []; history.value = []
  nextKey.value = null; nextRevision.value = null; historyId.value = ''
}
watch(() => [props.scopeKey, props.source], () => { clear(); if (props.source === 'EXISTING') void loadDirectory() }, { immediate: true, flush: 'sync' })
watch(busy, value => emit('busy', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { active = false; clear(); emit('busy', false) })
</script>

<template>
  <div class="init-calendar">
    <div class="calendar-source"><label><input type="radio" :checked="source === 'CREATE'" :disabled="locked" @change="emit('update:source', 'CREATE')" />新建工作日历</label><label><input type="radio" :checked="source === 'EXISTING'" :disabled="locked" @change="emit('update:source', 'EXISTING')" />采用已有版本</label></div>
    <template v-if="source === 'EXISTING'">
      <p class="hint">选择后读取完整规则。后续日历更新不会改写本次初始化保存的版本。</p>
      <p v-if="directory.loading" role="status">正在读取日历目录…</p><p v-if="directory.error" role="alert">{{ directory.error }}</p>
      <p v-if="directory.value && !items.length" class="hint">尚无工作日历，可切换到“新建工作日历”。</p>
      <div class="calendar-options"><button v-for="item in items" :key="item.id" type="button" :disabled="locked || busy" :aria-pressed="historyId === item.id" @click="chooseCalendar(item)"><strong>{{ item.name }}</strong><span>{{ item.key }} · 当前 v{{ item.revision }} · {{ item.zoneId }}</span></button></div>
      <div class="calendar-controls"><button type="button" class="quiet" :disabled="locked || busy" @click="loadDirectory()">刷新日历目录</button><button v-if="nextKey" type="button" class="quiet" :disabled="locked || busy" @click="loadDirectory(true)">更多日历</button></div>
      <p v-if="versions.error" role="alert">{{ versions.error }}<button type="button" class="quiet" :disabled="locked || busy" @click="loadVersions()">重试版本历史</button></p>
      <div v-if="history.length" class="calendar-history"><span>选择精确版本</span><button v-for="item in history" :key="item.revision" type="button" class="secondary" :disabled="locked || busy" :aria-pressed="selected?.id === item.id && selected?.revision === item.revision" @click="chooseVersion(item.id, item.revision)">v{{ item.revision }} · {{ item.name }}</button><button v-if="nextRevision" type="button" class="quiet" :disabled="locked || busy" @click="loadVersions(true)">更早版本</button></div>
      <p v-if="detail.loading" role="status">正在读取所选版本…</p><p v-if="detail.error" role="alert">{{ detail.error }}请从目录或历史重新选择。</p>
      <p v-if="selected" class="selection">已选择：{{ selected.name }} · v{{ selected.revision }} · {{ selected.rules.zoneId }}<small>{{ selected.updatedBy }} 于 {{ new Date(selected.updatedAt).toLocaleString('zh-CN') }} 保存</small></p>
    </template>
    <fieldset v-if="source === 'CREATE'" :disabled="locked">
      <legend>按实际工作安排填写</legend>
      <div class="calendar-fields"><label>日历标识<input :value="created.key" required maxlength="64" pattern="[A-Za-z][A-Za-z0-9_-]{0,63}" placeholder="例如 headquarters" @input="patch({ key: input($event) })" /></label><label>日历名称<input :value="created.name" required maxlength="128" placeholder="例如 总部工作日历" @input="patch({ name: input($event) })" /></label><label>日历时区<input :value="created.rules.zoneId" required list="initialization-timezones" placeholder="例如 Asia/Shanghai" @input="patch({ rules: { ...created.rules, zoneId: input($event) } })" /><datalist id="initialization-timezones"><option value="Asia/Shanghai" /><option value="Asia/Hong_Kong" /><option value="Asia/Tokyo" /><option value="Europe/London" /><option value="America/New_York" /><option value="UTC" /></datalist></label></div>
    </fieldset>
    <fieldset v-if="shown" :disabled="locked"><legend>{{ source === 'CREATE' ? '每周工作时段' : '所选版本的工作规则' }}</legend>
      <p class="hint">同日最多 8 段；格式为 09:00，结束可填 24:00。无时段的日期休息。</p>
      <button v-if="source === 'CREATE'" type="button" class="quiet" @click="example">填入周一至周五 09:00—18:00 示例</button>
      <div v-for="day in weekdays" :key="day.key" class="calendar-day"><strong>{{ day.label }}</strong><CalendarHours :periods="shown.weeklyHours[day.key] ?? []" :label="'初始化' + day.label" :readonly="source === 'EXISTING'" @change="setWeek(day.key, $event)" /></div>
      <div class="calendar-controls"><strong>节假日与调休</strong><button v-if="source === 'CREATE'" type="button" class="quiet" :disabled="created.rules.overrides.length >= 500" @click="addOverride">＋ 日期例外</button></div>
      <p v-if="!shown.overrides.length" class="hint">未配置日期例外，按每周规则执行；示例不包含企业节假日。</p>
      <article v-for="(day, index) in shown.overrides" :key="index" class="calendar-exception">
        <template v-if="source === 'CREATE'"><label>例外日期<input :value="day.date" type="date" required min="0001-01-01" max="9999-12-31" :aria-label="`初始化例外${index + 1}日期`" @input="setOverride(index, { date: input($event) })" /></label><label>说明<input :value="day.note" maxlength="200" :aria-label="`初始化例外${index + 1}说明`" @input="setOverride(index, { note: input($event) })" /></label><button type="button" class="quiet" @click="removeOverride(index)">删除此日期例外</button></template><p v-else>{{ day.date }} · {{ day.note || '日期例外' }}</p>
        <CalendarHours :periods="day.periods" :label="`初始化例外${index + 1}`" :readonly="source === 'EXISTING'" @change="setOverride(index, { periods: $event })" />
      </article>
    </fieldset>
  </div>
</template>

<style scoped>
.init-calendar{font-size:13px;line-height:1.7;min-width:0}.calendar-source,.calendar-controls,.calendar-history{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.calendar-source{margin-bottom:12px}.calendar-source label{display:flex;gap:7px;align-items:center}.calendar-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.calendar-fields label,.calendar-exception label{display:grid;gap:6px;min-width:0}.init-calendar fieldset{border:0;padding:0;margin:20px 0;min-width:0}.init-calendar legend{font-weight:650;margin-bottom:12px}.init-calendar input:not([type=radio]){box-sizing:border-box;min-width:0;width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:6px;background:var(--paper);font:inherit;color:var(--ink)}.hint{font-size:12px;color:var(--muted)}.calendar-day{display:grid;grid-template-columns:42px minmax(0,1fr);gap:8px;align-items:center;padding:9px 0;border-bottom:1px solid var(--line)}.calendar-day>strong{font-size:12px}.calendar-controls{justify-content:space-between;margin:15px 0}.calendar-options{display:grid;gap:8px}.calendar-options>button{border:1px solid var(--line);border-radius:7px;background:var(--paper);padding:12px;text-align:left;color:var(--ink);font:inherit;min-width:0;overflow-wrap:anywhere}.calendar-options strong,.calendar-options span,.selection small{display:block}.calendar-options span,.selection small{font-size:11px;color:var(--muted)}[aria-pressed=true]{border-color:var(--teal)!important;background:#edf7f3!important}.calendar-history>span{width:100%;font-size:12px;color:var(--muted)}.calendar-history button{font-size:12px}.selection{padding:12px 14px;background:#edf7f3;border-radius:7px;overflow-wrap:anywhere}.calendar-exception{display:grid;gap:10px;margin:12px 0;padding:14px;border:1px solid var(--line);border-radius:7px}.init-calendar [role=alert]{color:var(--red)}@media(max-width:650px){.calendar-fields{grid-template-columns:minmax(0,1fr)}.calendar-source{align-items:flex-start;flex-direction:column}.calendar-history button{max-width:100%;white-space:normal;overflow-wrap:anywhere}}
</style>
