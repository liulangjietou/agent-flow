<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { calendarInstant, CalendarRead, calendarDrafts, calendarDirty, calendarInput, calendarPayload, emptyCalendar, weekdays, type BusinessCalendar, type CalendarDraft, type CalendarPage, type CalendarVersionPage, type CalendarCalculation, type CalendarUpdate } from '../businessCalendars'
import CalendarHours from './CalendarHours.vue'
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const directory = reactive(new CalendarRead<CalendarPage>())
const detail = reactive(new CalendarRead<BusinessCalendar>())
const history = reactive(new CalendarRead<CalendarVersionPage>())
const calculation = reactive(new CalendarRead<CalendarCalculation>())
const draft = ref<CalendarDraft>(calendarDrafts.get(props.scopeKey) ?? { baseline: null, form: emptyCalendar() })
const historical = ref<BusinessCalendar | null>(null)
const directoryCursors = ref<Array<string | undefined>>([undefined])
const historyCursors = ref<Array<number | undefined>>([undefined])
const authorizationFailed = ref(false)
const saving = ref(false), saveError = ref(''), message = ref('')
const input = reactive({ startLocal: '', workingMinutes: 480, overlapChoice: '' as '' | 'EARLIER' | 'LATER' })
let active = true
const dirty = computed(() => calendarDirty(draft.value))
const saved = computed(() => historical.value ?? draft.value.baseline)
const rules = computed(() => historical.value?.rules ?? draft.value.form.rules)
const accessDenied = computed(() => authorizationFailed.value || [directory.status, detail.status, history.status, calculation.status].some(status => [401, 403].includes(status)))
const locked = computed(() => props.locked || saving.value || detail.loading || accessDenied.value)
const time = (value: string) => new Date(value).toLocaleString('zh-CN')

function loadDirectory() { void directory.load(signal => api.calendars(directoryCursors.value[directoryCursors.value.length - 1], signal)) }
function loadHistory() {
  if (!draft.value.baseline) { history.clear(); return }
  const id = draft.value.baseline.id
  void history.load(signal => api.calendarVersions(id, historyCursors.value[historyCursors.value.length - 1], signal))
}
async function select(id: string) {
  if (dirty.value || locked.value) return
  historical.value = null; calculation.clear(); history.clear(); saveError.value = ''; message.value = ''
  const value = await detail.load(signal => api.calendar(id, signal))
  if (!value || !active) return
  draft.value = { baseline: value, form: calendarInput(value) }; historyCursors.value = [undefined]; loadHistory()
}
function create() {
  if (dirty.value || locked.value) return
  detail.clear(); history.clear(); calculation.clear(); historical.value = null
  draft.value = { baseline: null, form: emptyCalendar() }; saveError.value = ''; message.value = ''
}
function discard() {
  draft.value.form = draft.value.baseline ? calendarInput(draft.value.baseline) : emptyCalendar()
  saveError.value = ''; message.value = '已放弃本地修改。'
}
function example() {
  draft.value.form.rules.weeklyHours = Object.fromEntries(weekdays.map((day, index) => [day.key, index < 5 ? [{ start: '09:00', end: '12:00' }, { start: '13:00', end: '18:00' }] : []]))
}
async function save() {
  if (locked.value || !dirty.value || historical.value) return
  const scope = props.scopeKey, id = draft.value.baseline?.id, body = calendarPayload(draft.value)
  calendarDrafts.put(scope, draft.value); saving.value = true; saveError.value = ''; message.value = ''
  try {
    const value = id ? await api.updateCalendar(id, body as CalendarUpdate) : await api.createCalendar(body as ReturnType<typeof emptyCalendar>)
    calendarDrafts.acknowledge(scope, id ? '/business-calendars/' + id : '/business-calendars', JSON.stringify(body), value)
    if (!active || scope !== props.scopeKey) return
    draft.value = calendarDrafts.get(scope)!; message.value = `已保存修订 v${value.revision}，旧版本保持不变。`
    directoryCursors.value = [undefined]; historyCursors.value = [undefined]; loadDirectory(); loadHistory()
  } catch (cause) {
    if (active && scope === props.scopeKey) {
      saveError.value = (cause as ApiError).message
      authorizationFailed.value = [401, 403].includes((cause as ApiError).status)
    }
  }
  finally { if (active) saving.value = false }
}
async function viewVersion(revision: number) {
  if (!draft.value.baseline || dirty.value || locked.value) return
  calculation.clear(); historical.value = null
  const id = draft.value.baseline.id
  if (revision === draft.value.baseline.revision) { detail.clear(); return }
  historical.value = await detail.load(signal => api.calendarVersion(id, revision, signal))
}
function reuseVersion() {
  if (!historical.value || locked.value) return
  draft.value.form = calendarInput(historical.value); historical.value = null
  message.value = '已复制历史设置。保存后将新增修订，旧版本不会被覆盖。'
}
function calculate() {
  if (!saved.value || dirty.value || detail.loading || accessDenied.value) return
  const value = saved.value
  void calculation.load(signal => api.calculateCalendar(value.id, { revision: value.revision, startLocal: input.startLocal, workingMinutes: Number(input.workingMinutes), ...(input.overlapChoice ? { overlapChoice: input.overlapChoice } : {}) }, signal))
}
watch(draft, value => { calendarDrafts.put(props.scopeKey, value); calculation.clear() }, { deep: true, flush: 'sync' })
watch(input, () => calculation.clear(), { flush: 'sync' })
watch(historical, () => calculation.clear())
watch(() => props.refreshVersion, () => {
  const value = calendarDrafts.get(props.scopeKey)
  if (value) draft.value = value
  historical.value = null; saveError.value = ''; loadDirectory(); loadHistory()
})
loadDirectory(); loadHistory()
onUnmounted(() => { active = false; directory.clear(); detail.clear(); history.clear(); calculation.clear() })
</script>

<template>
  <section class="content business-calendars">
    <div class="page-heading"><div><p class="eyebrow">SETTINGS / BUSINESS CALENDARS</p><h2>工作日历</h2><p class="subhead">按当地作息计算工作时间，让每次试算都有可追溯的版本。</p></div><button class="primary" :disabled="dirty || locked" @click="create">＋ 新建日历</button></div>
    <div class="calendar-scope"><span>管理员配置</span><p>节假日与调休由管理员明确维护。流程节点可引用明确日历修订计算新任务期限；后续日历修改不会改变既有任务。</p></div>
    <p v-if="accessDenied" class="calendar-error" role="alert">当前账号已无法访问日历配置，请重新登录并核对管理员权限。</p>
    <div v-if="!accessDenied" class="calendar-layout">
      <aside class="panel calendar-directory" aria-label="日历目录">
        <div class="calendar-section-title"><h3>日历目录</h3><button class="quiet" :disabled="directory.loading" @click="loadDirectory">刷新</button></div>
        <p v-if="directory.loading" class="calendar-hint" role="status">正在读取目录…</p>
        <p v-if="directory.error" class="calendar-error" role="alert">{{ directory.error }}</p>
        <p v-if="directory.value && !directory.value.items.length" class="calendar-hint">暂无日历。先确定时区和工作时段，再保存第一版。</p>
        <button v-for="item in directory.value?.items ?? []" :key="item.id" class="calendar-item" :class="{ selected: draft.baseline?.id === item.id }" :disabled="dirty || locked" @click="select(item.id)"><strong>{{ item.name }}</strong><span>{{ item.key }} · v{{ item.revision }}</span><small>{{ item.zoneId }}</small></button>
        <div class="calendar-pagination"><button class="quiet" :disabled="directoryCursors.length === 1 || directory.loading || dirty || locked" @click="directoryCursors.pop(); loadDirectory()">上一页</button><button class="quiet" :disabled="!directory.value?.nextAfterKey || directory.loading || dirty || locked" @click="directoryCursors.push(directory.value?.nextAfterKey ?? undefined); loadDirectory()">下一页</button></div>
        <p v-if="dirty" class="calendar-hint">先保存或放弃本地修改，再切换日历。</p>
      </aside>
      <div class="calendar-main">
        <p v-if="detail.loading" class="calendar-hint" role="status">正在读取日历版本…</p>
        <p v-if="detail.error" class="calendar-error" role="alert">{{ detail.error }}请从目录或版本历史重新选择。</p>
        <form class="panel calendar-editor" aria-label="工作日历配置" @submit.prevent="save">
          <div class="calendar-section-title"><div><h3>{{ historical ? `历史修订 v${historical.revision}` : draft.baseline ? `编辑日历 · v${draft.baseline.revision}` : '建立第一份工作日历' }}</h3><p v-if="saved">{{ saved.updatedBy }} · {{ time(saved.updatedAt) }}</p><p v-else>没有预填的企业制度，请按实际工作安排设置。</p></div><span class="calendar-badge">{{ historical ? '只读历史' : dirty ? '有未保存修改' : draft.baseline ? '已保存' : '未保存' }}</span></div>
          <p v-if="historical" class="calendar-hint">当前展示旧版规则。<button type="button" class="quiet" :disabled="locked" @click="reuseVersion">使用此版创建新修订</button><button type="button" class="quiet" :disabled="locked" @click="historical = null">返回当前版本</button></p>
          <fieldset :disabled="locked || !!historical">
            <div class="calendar-fields"><label>日历标识<input v-model="draft.form.key" :disabled="!!draft.baseline" required maxlength="64" pattern="[A-Za-z][A-Za-z0-9_-]{0,63}" placeholder="如 headquarters" /></label><label>日历名称<input v-if="!historical" v-model="draft.form.name" required maxlength="128" placeholder="如 总部工作日历" /><input v-else :value="historical.name" /></label><label class="calendar-zone">日历时区<input v-if="!historical" v-model="draft.form.rules.zoneId" list="calendar-timezones" required placeholder="如 Asia/Shanghai" /><input v-else :value="historical.rules.zoneId" /><datalist id="calendar-timezones"><option value="Asia/Shanghai" /><option value="Asia/Hong_Kong" /><option value="Asia/Tokyo" /><option value="Europe/London" /><option value="America/New_York" /><option value="UTC" /></datalist></label></div>
            <div class="calendar-section-title calendar-subsection"><div><h4>每周工作时段</h4><p>格式 09:00；结束可填 24:00。同日最多 8 段，午休留空隙。</p></div><button v-if="!historical" type="button" class="secondary" @click="example">填入工作日示例</button></div>
            <div v-for="day in weekdays" :key="day.key" class="calendar-weekday"><strong>{{ day.label }}</strong><CalendarHours :periods="rules.weeklyHours[day.key] ?? []" :label="day.label" :readonly="!!historical" @change="draft.form.rules.weeklyHours[day.key] = $event" /></div>
            <div class="calendar-section-title calendar-subsection"><div><h4>节假日与调休</h4><p>例外覆盖当天全部时段；无时段表示休息。</p></div><button v-if="!historical" type="button" class="secondary" :disabled="rules.overrides.length >= 500" @click="draft.form.rules.overrides.push({ date: '', periods: [], note: '' })">＋ 日期例外</button></div>
            <p v-if="!rules.overrides.length" class="calendar-hint">尚无日期例外，全部按每周规则执行。</p>
            <article v-for="(day,index) in rules.overrides" :key="index" class="calendar-exception">
              <div class="exception-fields"><label>例外日期<input v-model="day.date" type="date" required :aria-label="`例外${index + 1}日期`" /></label><label>说明<input v-model="day.note" maxlength="200" :aria-label="`例外${index + 1}说明`" placeholder="如 调休上班" /></label><button v-if="!historical" type="button" class="quiet" :aria-label="`删除例外${index + 1}`" @click="draft.form.rules.overrides.splice(index,1)">删除</button></div><CalendarHours :periods="day.periods" :label="`例外${index + 1}`" :readonly="!!historical" @change="day.periods = $event" />
            </article>
          </fieldset>
          <p v-if="saveError" class="calendar-error" role="alert">{{ saveError }}</p><p v-if="message" class="calendar-message" role="status">{{ message }}</p>
          <div v-if="!historical" class="calendar-save"><button class="primary" :disabled="locked || !dirty">{{ saving ? '正在保存…' : draft.baseline ? '保存为新修订' : '保存日历' }}</button><button v-if="dirty" type="button" class="quiet" :disabled="locked" @click="discard">放弃本地修改</button><span>每次保存保留旧版规则与修改人。</span></div>
        </form>
        <section v-if="saved" class="panel calendar-calculator" aria-label="到期时间试算">
          <div class="calendar-section-title"><div><h3>到期时间试算</h3><p>使用已保存 v{{ saved.revision }} · {{ saved.rules.zoneId }}，仅累计工作时段内实际经过的分钟。</p></div></div>
          <p v-if="dirty" class="calendar-hint">请先保存或放弃本地修改，再试算。</p>
          <form @submit.prevent="calculate"><fieldset :disabled="dirty || detail.loading || calculation.loading"><div class="calendar-fields"><label>开始时间（{{ saved.rules.zoneId }}）<input v-model="input.startLocal" type="datetime-local" step="1" required /></label><label>工作分钟<input v-model="input.workingMinutes" type="number" min="1" max="527040" step="1" required /></label><label>时区回拨时的重复钟点<select v-model="input.overlapChoice"><option value="">未选择（常规时间无需选）</option><option value="EARLIER">第一次出现</option><option value="LATER">第二次出现</option></select></label></div><button class="secondary">{{ calculation.loading ? '正在试算…' : '计算到期时间' }}</button></fieldset></form>
          <p v-if="calculation.error" class="calendar-error" role="alert">{{ calculation.error }}</p>
          <div v-if="calculation.value" class="calendar-result" role="status"><span>预计到期 · v{{ calculation.value.revision }}</span><strong>{{ calendarInstant(calculation.value.deadline.dueAt, calculation.value.deadline.zoneId) }}</strong><p>{{ calculation.value.deadline.workingMinutes }} 个工作分钟 · {{ calculation.value.deadline.usedPeriods }} 段工作窗口</p><small>UTC：{{ calculation.value.deadline.dueAt }}</small></div>
          <p class="calendar-hint">不存在的当地钟点会要求重新选择；重复钟点须明确选择第一次或第二次。最长向后查找 3660 天。</p>
        </section>
        <section v-if="draft.baseline" class="panel calendar-history" aria-label="版本历史"><div class="calendar-section-title"><h3>版本历史</h3><button class="quiet" :disabled="history.loading" @click="loadHistory">刷新历史</button></div><p v-if="history.loading" class="calendar-hint">正在读取历史…</p><p v-if="history.error" class="calendar-error" role="alert">{{ history.error }}</p><article v-for="version in history.value?.items ?? []" :key="version.revision"><div><strong>v{{ version.revision }} · {{ version.name }}</strong><p>{{ version.updatedBy }} · {{ time(version.updatedAt) }} · {{ version.zoneId }}</p></div><button class="secondary" :disabled="dirty || locked" @click="viewVersion(version.revision)">查看 v{{ version.revision }}</button></article><div class="calendar-pagination"><button class="quiet" :disabled="historyCursors.length === 1 || history.loading" @click="historyCursors.pop(); loadHistory()">上一页</button><button class="quiet" :disabled="!history.value?.nextBeforeRevision || history.loading" @click="historyCursors.push(history.value?.nextBeforeRevision ?? undefined); loadHistory()">下一页</button></div></section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.calendar-scope{display:flex;gap:12px;align-items:center;padding:12px 16px;margin:0 0 22px;border:1px solid var(--line);border-radius:8px;background:#f3f7f5}.calendar-scope>span,.calendar-badge{font-size:11px;white-space:nowrap;color:var(--deep)}.calendar-scope p{margin:0;font-size:12px;color:var(--muted);line-height:1.8}.calendar-layout{display:grid;grid-template-columns:230px minmax(0,1fr);gap:22px;align-items:start}.calendar-directory{padding:18px}.calendar-section-title{display:flex;align-items:start;justify-content:space-between;gap:14px;margin-bottom:18px}.calendar-section-title h3{font-size:15px;margin:0}.calendar-section-title h4{font-size:13px;margin:0}.calendar-section-title p,.calendar-hint,.calendar-history p{font-size:11px;line-height:1.9;color:var(--muted);margin:7px 0}.calendar-section-title button{font-size:11px;white-space:nowrap}.calendar-item{display:flex;flex-direction:column;text-align:left;width:100%;padding:15px 10px;gap:8px;border:1px solid transparent;border-bottom-color:var(--line);background:transparent;border-radius:5px;overflow-wrap:anywhere}.calendar-item strong{font-size:13px;font-weight:500}.calendar-item span,.calendar-item small{font-size:10px;color:var(--muted)}.calendar-item.selected{background:#edf5f1;border-color:#c5dacf}.calendar-item:disabled{opacity:.65}.calendar-pagination{display:flex;justify-content:space-between;gap:10px;margin-top:18px}.calendar-pagination button{font-size:11px}.calendar-main{display:grid;gap:22px;min-width:0}.calendar-editor,.calendar-calculator,.calendar-history{padding:24px}.calendar-main fieldset{border:0;margin:0;padding:0;min-width:0}.calendar-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-bottom:18px}.calendar-fields label,.exception-fields label{display:grid;gap:8px;font-size:11px;color:var(--muted);min-width:0}.calendar-fields input,.calendar-fields select,.exception-fields input{width:100%;min-width:0;border:1px solid var(--line);border-radius:6px;padding:10px;font:inherit;color:var(--ink);background:#fff;box-sizing:border-box;height:39px}.calendar-zone{grid-column:1/-1;max-width:360px}.calendar-subsection{margin-top:28px;align-items:center}.calendar-weekday{display:flex;gap:22px;align-items:start;padding:11px 0;border-bottom:1px solid var(--line)}.calendar-weekday>strong{font-size:12px;font-weight:500;min-width:28px;padding-top:10px}.calendar-exception{padding:16px 0;border-bottom:1px solid var(--line)}.exception-fields{display:grid;grid-template-columns:155px minmax(0,1fr) auto;gap:12px;align-items:end;margin-bottom:12px}.exception-fields button{font-size:11px;padding-bottom:12px}.calendar-save{display:flex;align-items:center;flex-wrap:wrap;gap:14px;margin-top:24px}.calendar-save>span{font-size:11px;color:var(--muted)}.calendar-error{color:var(--red);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.calendar-message{font-size:12px;color:var(--deep);line-height:1.8}.calendar-result{padding:19px;background:#edf5f1;border-left:3px solid var(--deep);margin:22px 0 12px;overflow-wrap:anywhere}.calendar-result span{font-size:11px;color:var(--muted)}.calendar-result strong{display:block;font-size:23px;font-weight:500;line-height:1.6;margin:7px 0;color:var(--deep)}.calendar-result p,.calendar-result small{font-size:11px;color:var(--muted);line-height:1.8}.calendar-history article{display:flex;justify-content:space-between;align-items:center;gap:15px;padding:15px 0;border-top:1px solid var(--line)}.calendar-history article strong{font-size:12px;font-weight:500;overflow-wrap:anywhere}.calendar-history article button{font-size:11px;white-space:nowrap}.calendar-history article>div{min-width:0}.calendar-history p{overflow-wrap:anywhere}
@media(max-width:1100px){.calendar-layout{grid-template-columns:minmax(0,1fr)}.calendar-directory{display:flex;gap:10px;flex-wrap:wrap;align-items:start}.calendar-directory>.calendar-section-title{width:100%;margin-bottom:0}.calendar-item{width:220px}.calendar-directory>.calendar-pagination{width:100%}}
@media(max-width:650px){.business-calendars .page-heading{flex-direction:column;align-items:start;gap:16px}.calendar-scope{align-items:start;flex-direction:column;gap:4px}.calendar-editor,.calendar-calculator,.calendar-history{padding:18px 15px}.calendar-fields{grid-template-columns:minmax(0,1fr)}.calendar-item{width:100%}.calendar-section-title{flex-wrap:wrap;gap:10px}.calendar-weekday{gap:12px}.exception-fields{grid-template-columns:minmax(0,1fr)}.exception-fields button{text-align:left;padding:0}.calendar-result strong{font-size:19px}}
</style>
