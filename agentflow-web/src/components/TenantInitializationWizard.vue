<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { cloneCalendar, weekdays } from '../businessCalendars'
import { initiatorContextLabel, type InitiatorAppointmentPage, type InitiatorContext } from '../initiatorContext'
import { InitializationRead, initializationBaseline, initializationDrafts, initializationProblem, initializationRequest, newInitializationDraft,
  type InitializationDraft, type InitializationReceipt, type InitializationState } from '../tenantInitialization'
import InitializationCalendar from './InitializationCalendar.vue'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean; enterpriseAuth: boolean }>()
const emit = defineEmits<{ templates: []; organization: []; completed: [] }>()
const stateQuery = reactive(new InitializationRead<InitializationState>('初始化状态'))
const appointmentsQuery = reactive(new InitializationRead<InitiatorAppointmentPage>('本人任职'))
const draft = ref<InitializationDraft | null>(null), receipt = ref<InitializationReceipt | null>(null)
const appointments = ref<InitiatorContext[]>([]), nextAppointment = ref<string | null>(null)
const step = ref(0), confirmed = ref(false), saving = ref(false), calendarBusy = ref(false), requiresReview = ref(false)
const error = ref(''), notice = ref(''), heading = ref<HTMLElement | null>(null), formElement = ref<HTMLFormElement | null>(null)
const labels = ['身份与空间', '组织任职', '工作日历', '通知选择', '确认初始化']
const roleNames: Record<string, string> = { ADMIN: '平台管理员', PROCESS_ADMIN: '流程管理员', APPROVER: '审批人', EMPLOYEE: '员工', FINANCE: '财务', CASHIER: '出纳', MANAGER: '经理角色' }
const state = computed(() => stateQuery.value)
const stale = computed(() => !!draft.value && (requiresReview.value || !!state.value && initializationBaseline(draft.value.baseline) !== initializationBaseline(state.value)))
const disabled = computed(() => props.locked || saving.value || stateQuery.loading || !state.value || !!receipt.value)
const source = computed(() => draft.value?.baseline)
const person = computed(() => source.value?.currentAdministratorPerson)
const selectedRules = computed(() => draft.value?.form.calendarSource === 'CREATE' ? draft.value.form.newCalendar.rules : draft.value?.form.calendar?.rules)
let active = true, identityEpoch = 0
const when = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const channelNames = (email: boolean, im: boolean) => ['站内提醒', ...(email ? ['邮件'] : []), ...(im ? ['企业 IM'] : [])].join('、')
const channel = (name: string) => source.value?.channelBindings.find(item => item.channel === name)

/** 刷新只取得新事实，草稿基线必须由用户明确采用；失败不开放旧基线提交。 */
async function load() {
  if (!active || !props.scopeKey) return
  const result = await stateQuery.load(signal => api.tenantInitialization(signal))
  if (!result) { confirmed.value = false; return }
  receipt.value = result.initialization
  const stored = initializationDrafts.get(props.scopeKey)
  if (result.initialization) {
    draft.value = stored
    error.value = ''; requiresReview.value = false
  }
  else if (!draft.value) draft.value = stored ?? newInitializationDraft(result)
}
async function loadAppointments(more = false) {
  if (appointmentsQuery.loading || !state.value || disabled.value) return
  if (!more) { appointments.value = []; nextAppointment.value = null }
  const result = await appointmentsQuery.load(signal => api.myAppointments(more ? nextAppointment.value ?? undefined : undefined, signal))
  if (result) { appointments.value = more ? [...appointments.value, ...result.items] : result.items; nextAppointment.value = result.nextAfterId ?? null }
}
function chooseAppointment(id: string) {
  if (disabled.value || !draft.value) return
  const value = appointments.value.find(item => item.appointmentId === id)
  draft.value.form.appointment = value ? cloneCalendar(value) : null
}
function adoptLatest() {
  if (disabled.value || !state.value || !draft.value) return
  draft.value.baseline = cloneCalendar(state.value); draft.value.form.appointment = null
  requiresReview.value = false; confirmed.value = false; appointmentsQuery.clear(); appointments.value = []; nextAppointment.value = null
  notice.value = '已采用最新来源。请重新核对组织任职、工作日历和本人通知选择。'
  void go(1, false)
}
function discard() {
  if (props.locked || saving.value || stateQuery.loading) return
  initializationDrafts.discard(props.scopeKey)
  draft.value = state.value && !state.value.initialization ? newInitializationDraft(state.value) : null
  step.value = 0; confirmed.value = false; requiresReview.value = false; error.value = ''; notice.value = '本地未提交草稿已放弃。'
}
async function go(target: number, validate = true) {
  if (disabled.value || !draft.value || calendarBusy.value || appointmentsQuery.loading) return
  if (validate && target > step.value) {
    error.value = initializationProblem(draft.value, target - 1)
    if (error.value || formElement.value?.reportValidity() === false) return
  }
  confirmed.value = false; step.value = target; error.value = ''
  if (target === 1 && draft.value.form.organizationSource === 'EXISTING') void loadAppointments()
  await nextTick(); heading.value?.focus?.()
}
/** 只提交已确认的原选择；未知结果留给全局原键恢复，不创建第二个请求。 */
async function initialize() {
  if (disabled.value || stale.value || !draft.value || !confirmed.value || step.value !== 4 || calendarBusy.value) return
  error.value = initializationProblem(draft.value)
  if (error.value) return
  const input = initializationRequest(draft.value), sentBody = JSON.stringify(input), scope = props.scopeKey, epoch = identityEpoch
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const result = await api.initializeTenant(input)
    const acknowledged = initializationDrafts.acknowledge(scope, sentBody)
    if (!active || epoch !== identityEpoch || scope !== props.scopeKey) return
    receipt.value = result; if (acknowledged) draft.value = null
    confirmed.value = false; notice.value = '工作区初始化已确认。可以继续配置并运行第一条审批流程。'
    emit('completed'); await load()
  } catch (cause) {
    if (!active || epoch !== identityEpoch || scope !== props.scopeKey) return
    const failure = cause as ApiError
    confirmed.value = false
    if (failure.status === 409 || failure.code === 'INITIATOR_APPOINTMENT_UNAVAILABLE') requiresReview.value = true
    error.value = failure.message ?? '初始化结果尚未确认，请使用页面上方的原操作恢复入口。'
  } finally { if (active && epoch === identityEpoch) saving.value = false }
}
watch(draft, value => { if (value) initializationDrafts.put(props.scopeKey, value); confirmed.value = false }, { deep: true, flush: 'sync' })
watch(() => draft.value?.form.organizationSource, value => {
  appointmentsQuery.clear(); appointments.value = []; nextAppointment.value = null
  if (value === 'EXISTING' && step.value === 1) void loadAppointments()
}, { flush: 'sync' })
watch(() => props.scopeKey, () => {
  identityEpoch++; stateQuery.clear(); appointmentsQuery.clear(); draft.value = null; receipt.value = null
  appointments.value = []; nextAppointment.value = null; step.value = 0; saving.value = false; calendarBusy.value = false
  requiresReview.value = false; confirmed.value = false; error.value = ''; notice.value = ''
  if (props.scopeKey) void load()
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, () => { confirmed.value = false; void load() })
onUnmounted(() => { active = false; identityEpoch++; stateQuery.clear(); appointmentsQuery.clear() })
</script>

<template>
  <section class="panel tenant-initialization" aria-labelledby="tenant-initialization-title" :aria-busy="saving || stateQuery.loading">
    <header class="init-header"><div><p class="eyebrow">WORKSPACE SETUP</p><h3 id="tenant-initialization-title">{{ receipt ? '工作区初始化记录' : '先把工作区配置好' }}</h3><p>身份、组织、工作日历与通知，一次核对后保存。</p></div><button type="button" class="quiet" :disabled="locked || saving || stateQuery.loading" @click="load">重新读取状态</button></header>
    <p v-if="stateQuery.loading" class="init-message" role="status">正在读取当前租户与个人配置…</p>
    <p v-if="stateQuery.error" class="init-error" role="alert">{{ stateQuery.error }}<button type="button" class="quiet" :disabled="locked || saving || stateQuery.loading" @click="load">重试读取</button></p>
    <p v-if="error" class="init-error" role="alert">{{ error }}</p><p v-if="notice" class="init-message" role="status">{{ notice }}</p>
    <template v-if="receipt">
      <div class="init-completed"><span class="complete-mark" aria-hidden="true">✓</span><div><h4>{{ receipt.workspaceName }}</h4><p>{{ receipt.initializedBy }} · {{ when(receipt.initializedAt) }}</p></div></div>
      <dl class="init-receipt"><div><dt>原组织任职</dt><dd>{{ initiatorContextLabel(receipt.organization) }}</dd></div><div><dt>原工作日历</dt><dd>{{ receipt.calendar.name }} · v{{ receipt.calendar.revision }} · {{ receipt.calendar.rules.zoneId }}</dd></div><div><dt>初始化操作者当时的通知选择</dt><dd>{{ channelNames(receipt.notifications.emailEnabled, receipt.notifications.enterpriseImEnabled) }}</dd></div><div v-if="state"><dt>你的当前通知选择</dt><dd>{{ channelNames(state.currentNotifications.emailEnabled, state.currentNotifications.enterpriseImEnabled) }}</dd></div></dl>
      <p class="init-note">这里保留初始化当时的配置。后续人员、任职、日历及通知设置在各自管理页面维护；外部服务的实际接入还需单独验证。</p>
      <p v-if="draft" class="init-note">本地仍保留一份未确认的草稿。已有初始化记录，不能再次创建。<button type="button" class="quiet" :disabled="locked || saving || stateQuery.loading" @click="discard">放弃本地未提交草稿</button></p>
      <button type="button" class="primary" :disabled="locked || saving" @click="emit('templates')">继续配置第一条流程 →</button>
    </template>
    <template v-else-if="draft">
      <nav class="init-steps" aria-label="初始化步骤"><button v-for="(label,index) in labels" :key="label" type="button" :aria-current="step === index ? 'step' : undefined" :disabled="disabled || calendarBusy || appointmentsQuery.loading" @click="go(index)"><span>{{ index + 1 }}</span>{{ label }}</button></nav>
      <div v-if="stale" class="init-conflict" role="alert"><strong>原配置已变化，需要重新核对</strong><p>填写内容仍保留。先重新读取状态，再明确采用最新来源；最终确认也需要重新勾选。</p><button type="button" class="secondary" :disabled="disabled" @click="adoptLatest">采用最新状态并重新核对</button></div>
      <form ref="formElement" class="init-form" @submit.prevent="step < 4 ? go(step + 1) : initialize()">
        <h4 ref="heading" tabindex="-1">{{ labels[step] }}</h4>
        <fieldset v-if="step === 0" :disabled="disabled"><legend class="sr-only">身份与工作区</legend>
          <dl class="init-identity"><div><dt>当前租户</dt><dd>{{ source?.tenantId }}</dd></div><div><dt>管理员登录身份</dt><dd>{{ source?.currentSubject }}</dd></div><div><dt>已验证角色</dt><dd><span v-for="role in source?.currentRoles" :key="role" class="role-tag">{{ roleNames[role] ?? role }}</span></dd></div></dl>
          <p class="init-note">{{ enterpriseAuth ? '租户与角色由企业身份服务提供。' : '当前身份来自演示登录，仅用于本地验证。' }}初始化沿用已验证权限；人员的本地审批资格不能代替登录身份的审批权限。</p>
          <label class="init-field">工作区名称<input v-model="draft.form.workspaceName" required maxlength="128" placeholder="例如 总部审批工作区" /></label>
        </fieldset>
        <fieldset v-if="step === 1" :disabled="disabled"><legend class="sr-only">组织与管理员任职</legend>
          <div class="init-choices"><label><input v-model="draft.form.organizationSource" type="radio" value="CREATE" />新建组织和本人任职</label><label><input v-model="draft.form.organizationSource" type="radio" value="EXISTING" :disabled="!source?.organizationRevision" />采用本人已有任职</label></div>
          <div v-if="draft.form.organizationSource === 'CREATE'" class="init-fields"><label>法人名称<input v-model="draft.form.legalEntityName" required maxlength="128" placeholder="填写实际法人名称" /></label><label>部门名称<input v-model="draft.form.departmentName" required maxlength="128" /></label><label>岗位名称<input v-model="draft.form.positionName" required maxlength="128" /></label><label>管理员姓名<input v-if="person" :value="person.displayName" readonly /><input v-else v-model="draft.form.administratorName" required maxlength="128" /></label></div>
          <template v-else><label class="init-field">当前账号的有效任职<select :value="draft.form.appointment?.appointmentId ?? ''" :disabled="appointmentsQuery.loading" required @change="chooseAppointment(($event.target as HTMLSelectElement).value)"><option value="">请选择并核对本人任职</option><option v-if="draft.form.appointment && !appointments.some(item => item.appointmentId === draft?.form.appointment?.appointmentId)" :value="draft.form.appointment.appointmentId">{{ initiatorContextLabel(draft.form.appointment) }}（原选择）</option><option v-for="item in appointments" :key="item.appointmentId" :value="item.appointmentId">{{ initiatorContextLabel(item) }}</option></select></label>
            <p v-if="appointmentsQuery.loading" class="init-note" role="status">正在读取本人任职…</p><p v-if="appointmentsQuery.error" class="init-error" role="alert">{{ appointmentsQuery.error }}</p><p v-if="appointmentsQuery.value && !appointments.length" class="init-note">暂无有效任职。先在“组织与人员”维护当前账号的任职，或新建组织。</p>
            <div class="init-actions"><button type="button" class="quiet" :disabled="appointmentsQuery.loading" @click="loadAppointments()">刷新本人任职</button><button v-if="nextAppointment" type="button" class="quiet" :disabled="appointmentsQuery.loading" @click="loadAppointments(true)">更多任职</button><button type="button" class="quiet" @click="emit('organization')">打开组织与人员 →</button></div>
          </template>
          <p v-if="person && !person.active" class="init-error" role="alert">当前人员已停用，请先在组织与人员中核对。向导不会自动启用人员。</p>
          <p class="init-note">{{ source?.organizationRevision ? '已有组织保持。新建会增加一组法人、部门、岗位及本人任职；已有人员沿用原姓名与审批资格。' : '启用本地目录后，审批人以已维护的人员为准，演示名单不再作为候选。发布流程前，请补齐实际审批人员。' }}</p>
        </fieldset>
        <InitializationCalendar v-if="step === 2" :scope-key="scopeKey" v-model:source="draft.form.calendarSource" v-model:created="draft.form.newCalendar" v-model:selected="draft.form.calendar" :locked="disabled" @busy="calendarBusy = $event" />
        <fieldset v-if="step === 3" :disabled="disabled"><legend class="sr-only">本人的通知渠道</legend>
          <p class="init-note">只修改当前账号的通知偏好。站内提醒始终开启，外部渠道需要部署管理员完成绑定。</p>
          <div class="init-notifications"><div class="in-app"><strong>站内提醒</strong><span>始终开启</span></div><label><input v-model="draft.form.emailEnabled" type="checkbox" :disabled="!channel('EMAIL')?.configured && !draft.form.emailEnabled" /><span><strong>邮件提醒</strong><small>{{ channel('EMAIL')?.configured ? '已配置当前账号的受控绑定；递送结果另行核验。' : '当前账号未配置可用绑定，可暂不选择。' }}</small></span></label><label><input v-model="draft.form.enterpriseImEnabled" type="checkbox" :disabled="!channel('ENTERPRISE_IM')?.configured && !draft.form.enterpriseImEnabled" /><span><strong>企业 IM 提醒</strong><small>{{ channel('ENTERPRISE_IM')?.configured ? '已配置当前账号的受控绑定；递送结果另行核验。' : '当前账号未配置可用绑定，可暂不选择。' }}</small></span></label></div>
          <p class="init-note">开启不会补发旧通知。关闭后停止尚未开始发送的提醒；此步骤不会发送测试消息。</p>
        </fieldset>
        <div v-if="step === 4" class="init-review">
          <p class="init-note">确认下面的具体配置。保存后记录初始化事实；任何一步失败都不会留下部分配置。</p>
          <dl><div><dt>工作区与管理员</dt><dd><strong>{{ draft.form.workspaceName }}</strong><span>{{ source?.tenantId }} · {{ source?.currentSubject }}</span></dd></div><div><dt>{{ draft.form.organizationSource === 'CREATE' ? '创建组织任职' : '采用已有任职' }}</dt><dd><template v-if="draft.form.organizationSource === 'CREATE'"><strong>{{ draft.form.legalEntityName }}</strong><span>{{ draft.form.departmentName }} / {{ draft.form.positionName }} · {{ person?.displayName ?? draft.form.administratorName }}</span></template><template v-else>{{ draft.form.appointment ? initiatorContextLabel(draft.form.appointment) : '未选择任职' }}</template></dd></div><div><dt>{{ draft.form.calendarSource === 'CREATE' ? '新建工作日历' : '采用日历版本' }}</dt><dd><strong>{{ draft.form.calendarSource === 'CREATE' ? draft.form.newCalendar.name : draft.form.calendar?.name }}</strong><span>{{ draft.form.calendarSource === 'CREATE' ? draft.form.newCalendar.key + ' · v1' : 'v' + draft.form.calendar?.revision }} · {{ selectedRules?.zoneId }}</span><ul class="init-week"><li v-for="day in weekdays" :key="day.key">{{ day.label }}：{{ selectedRules?.weeklyHours[day.key]?.map(period => period.start + '—' + period.end).join('、') || '休息' }}</li></ul><span v-if="!selectedRules?.overrides.length">没有日期例外</span><ul v-else class="init-week"><li v-for="day in selectedRules.overrides" :key="day.date">{{ day.date }}：{{ day.periods.map(period => period.start + '—' + period.end).join('、') || '休息' }} {{ day.note }}</li></ul></dd></div><div><dt>本人的通知选择</dt><dd>{{ channelNames(draft.form.emailEnabled, draft.form.enterpriseImEnabled) }}</dd></div></dl>
          <p class="init-note">仅完成本地配置；企业身份账号、真实外部递送和企业上线验收仍各自核验。</p>
          <label class="init-confirm"><input v-model="confirmed" type="checkbox" :disabled="disabled || stale" /><span>我已核对当前身份、组织与工作日历，确认启用本地目录并按上述选择设置本人通知。</span></label>
        </div>
        <footer class="init-footer"><button v-if="step > 0" type="button" class="secondary" :disabled="disabled || calendarBusy || appointmentsQuery.loading" @click="go(step - 1)">上一步</button><button type="button" class="quiet" :disabled="locked || saving || stateQuery.loading" @click="discard">放弃本地草稿</button><button v-if="step < 4" type="submit" class="primary" :disabled="disabled || calendarBusy || appointmentsQuery.loading">{{ step === 3 ? '核对全部配置' : '下一步' }}</button><button v-else type="submit" class="primary" :disabled="disabled || stale || !confirmed">{{ saving ? '正在初始化…' : '确认并初始化工作区' }}</button></footer>
      </form>
    </template>
  </section>
</template>

<style scoped>
.tenant-initialization{margin-bottom:26px;padding:26px;min-width:0;font-size:13px;line-height:1.8}.init-header{height:auto;padding:0;border:0;background:transparent;display:flex;align-items:flex-start;justify-content:space-between;gap:15px}.init-header h3{font-size:23px;margin:4px 0 8px;color:var(--ink)}.init-header p{margin:0;color:var(--muted)}.init-header .eyebrow{font-size:10px;color:var(--deep)}.init-steps{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px;margin:25px 0}.init-steps button{display:flex;align-items:center;gap:8px;background:transparent;border:0;border-bottom:2px solid var(--line);text-align:left;padding:10px 0;font:inherit;color:var(--muted)}.init-steps span{font-size:11px;border:1px solid var(--line);border-radius:50%;height:24px;width:24px;display:grid;place-items:center;flex-shrink:0}.init-steps [aria-current=step]{border-color:var(--teal);color:var(--deep);font-weight:650}.init-steps [aria-current=step] span{color:#fff;background:var(--deep);border-color:var(--deep)}.init-form{max-width:880px}.init-form>h4{font-size:19px;margin:23px 0 17px}.init-form fieldset{min-width:0;border:0;margin:0;padding:0}.init-form legend{font-weight:650}.init-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:17px}.init-fields label,.init-field{display:grid;gap:6px;min-width:0}.init-field{margin:15px 0}.init-form input:not([type=radio]):not([type=checkbox]),.init-form select{box-sizing:border-box;width:100%;min-width:0;padding:11px 12px;border:1px solid var(--line);border-radius:6px;font:inherit;color:var(--ink);background:var(--paper)}.init-form input[readonly]{background:#f4f6f5}.init-identity,.init-receipt,.init-review dl{margin:0}.init-identity>div,.init-receipt>div,.init-review dl>div{display:grid;grid-template-columns:180px minmax(0,1fr);gap:18px;padding:14px 0;border-bottom:1px solid var(--line)}.init-identity dt,.init-receipt dt,.init-review dt{font-size:12px;color:var(--muted)}.init-identity dd,.init-receipt dd,.init-review dd{margin:0;overflow-wrap:anywhere}.role-tag{display:inline-block;margin:0 8px 5px 0;padding:2px 8px;background:#edf7f3;border-radius:4px;color:var(--deep);font-size:11px}.init-note{font-size:12px;color:var(--muted);line-height:1.9;margin:15px 0}.init-choices,.init-actions,.init-footer{display:flex;gap:15px;align-items:center;flex-wrap:wrap}.init-choices{margin:0 0 20px}.init-choices label{display:flex;gap:7px;align-items:center}.init-notifications{display:grid;gap:10px}.init-notifications>label,.init-notifications>.in-app{display:flex;gap:12px;padding:15px;border:1px solid var(--line);border-radius:7px;align-items:center}.init-notifications strong,.init-notifications small{display:block}.init-notifications small{font-size:12px;color:var(--muted)}.in-app span{margin-left:auto;font-size:12px;color:var(--deep)}.init-review dd>strong,.init-review dd>span{display:block}.init-review dd>span{color:var(--muted);font-size:12px}.init-week{margin:9px 0;padding-left:18px;font-size:12px}.init-confirm{display:flex;align-items:flex-start;gap:10px;padding:16px;background:#edf7f3;border:1px solid #c5dfd5;border-radius:7px}.init-confirm input{margin-top:6px}.init-footer{justify-content:flex-end;padding-top:22px;margin-top:22px;border-top:1px solid var(--line)}.init-error{padding:12px 14px;color:var(--red);background:#fff3f0;border-radius:6px;overflow-wrap:anywhere}.init-message{color:var(--deep)}.init-conflict{background:#fff9eb;border:1px solid #eadbb8;padding:14px;border-radius:7px;color:#78622e}.init-conflict p{margin:6px 0 10px}.init-completed{display:flex;gap:14px;align-items:center;margin:23px 0}.init-completed h4{font-size:21px;margin:0;overflow-wrap:anywhere}.init-completed p{font-size:12px;color:var(--muted);margin:3px 0;overflow-wrap:anywhere}.complete-mark{display:grid;place-items:center;width:36px;height:36px;flex-shrink:0;border-radius:50%;background:#edf7f3;color:var(--deep);font-size:20px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}button:focus-visible,input:focus-visible,select:focus-visible,h4:focus-visible{outline:2px solid var(--teal);outline-offset:3px}@media(max-width:800px){.init-steps{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:650px){.tenant-initialization{padding:20px 16px}.init-header{flex-direction:column}.init-steps{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.init-fields{grid-template-columns:minmax(0,1fr)}.init-identity>div,.init-receipt>div,.init-review dl>div{grid-template-columns:minmax(0,1fr);gap:4px}.init-choices{flex-direction:column;align-items:flex-start}.init-footer>button{flex:1 1 130px}.init-confirm{padding:12px}.init-actions{gap:8px}}
</style>
