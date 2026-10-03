<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { emptyOrganizationDraft, organizationDirty, organizationDrafts, organizationForm, organizationLabels, organizationPath, organizationPayload,
  type OrganizationSection, type OrganizationRecord, type OrganizationUnit, type OrganizationPerson, type OrganizationChange } from '../organization'

const READ_TIMEOUT_MS = 12_000
const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const draft = ref(organizationDrafts.get(props.scopeKey) ?? emptyOrganizationDraft())
const initialized = ref(false), loading = ref(false), saving = ref(false), denied = ref(false), error = ref(''), message = ref('')
const rows = ref<OrganizationRecord[]>([]), nextId = ref<string | undefined>(), history = ref<OrganizationChange[]>([]), before = ref<number | undefined>()
const showHistory = ref(false), initializeConfirmed = ref(false)
const options = reactive<Record<string, { items: OrganizationRecord[]; next?: string }>>({ LEGAL_ENTITY: { items: [] }, DEPARTMENT: { items: [] }, POSITION: { items: [] }, PERSON: { items: [] }, APPOINTMENT: { items: [] } })
let generation = 0, controller: AbortController | null = null, active = true
const dirty = computed(() => organizationDirty(draft.value))
const locked = computed(() => props.locked || saving.value || loading.value || denied.value)
const section = computed(() => draft.value.section)
const form = computed(() => draft.value.form)
const relationshipMode = computed(() => draft.value.mode === 'relationship')
const relationshipChoices = computed(() => options.APPOINTMENT.items.filter(value => 'personId' in value && value.active && value.id !== draft.value.baseline?.id && (section.value !== 'DEPARTMENT' || value.departmentId === draft.value.baseline?.id)))
const editing = computed(() => !!draft.value.baseline)
const departments = computed(() => options.DEPARTMENT.items as OrganizationUnit[])
const positions = computed(() => options.POSITION.items as OrganizationUnit[])
const selectedDepartment = computed(() => departments.value.find(value => value.id === form.value.departmentId))
const applicablePositions = computed(() => positions.value.filter(value => !selectedDepartment.value || value.legalEntityId === selectedDepartment.value.legalEntityId))
function label(record: OrganizationRecord): string { return 'displayName' in record ? record.displayName : 'name' in record ? record.name : reference('PERSON', record.personId) + ' · ' + reference('DEPARTMENT', record.departmentId) }
function reference(kind: string, id: string): string { const value = options[kind].items.find(item => item.id === id); return value ? label(value) : '未载入引用 ' + id.slice(0, 8) }
function known(kind: string, id: string) { return options[kind].items.some(item => item.id === id) }
function fail(cause: unknown) { error.value = (cause as ApiError).message ?? '组织目录暂时无法读取，请重试。'; denied.value = [401, 403].includes((cause as ApiError).status); if (denied.value) { rows.value = []; history.value = []; Object.values(options).forEach(value => { value.items = [] }) } }
async function references(signal: AbortSignal, version: number) {
  const values = await Promise.all([api.organizationUnits('LEGAL_ENTITY', undefined, signal), api.organizationUnits('DEPARTMENT', undefined, signal), api.organizationUnits('POSITION', undefined, signal), api.organizationPeople(undefined, signal), api.organizationAppointments(undefined, signal)])
  if (version !== generation) return
  ;['LEGAL_ENTITY', 'DEPARTMENT', 'POSITION', 'PERSON', 'APPOINTMENT'].forEach((key, index) => { options[key] = { items: values[index].items, next: values[index].nextAfterId ?? undefined } })
}
async function load(more = false) {
  controller?.abort(); const version = ++generation, scope = props.scopeKey, kind = section.value
  controller = new AbortController(); const requestController = controller, signal = requestController.signal
  loading.value = true; error.value = ''; denied.value = false
  const timer = setTimeout(() => requestController.abort(), READ_TIMEOUT_MS)
  try {
    const state = await api.organizationStatus(signal)
    if (version !== generation || scope !== props.scopeKey) return
    initialized.value = state.initialized
    if (!state.initialized) { rows.value = []; return }
    const page = kind === 'PERSON' ? await api.organizationPeople(more ? nextId.value : undefined, signal)
      : kind === 'APPOINTMENT' ? await api.organizationAppointments(more ? nextId.value : undefined, signal)
      : await api.organizationUnits(kind, more ? nextId.value : undefined, signal)
    if (version !== generation) return
    rows.value = more ? [...rows.value, ...page.items] : page.items; nextId.value = page.nextAfterId ?? undefined
    if (!more) await references(signal, version)
  } catch (cause) { if (version === generation) { rows.value = []; fail(cause) } }
  finally { clearTimeout(timer); if (version === generation) loading.value = false }
}
async function moreReference(kind: OrganizationSection) {
  if (locked.value || !options[kind].next) return
  controller?.abort(); controller = new AbortController()
  const version = generation, requestController = controller, signal = requestController.signal
  const timer = setTimeout(() => requestController.abort(), READ_TIMEOUT_MS)
  loading.value = true
  try {
    const page = kind === 'PERSON' ? await api.organizationPeople(options[kind].next, signal) : kind === 'APPOINTMENT' ? await api.organizationAppointments(options[kind].next, signal) : await api.organizationUnits(kind, options[kind].next, signal)
    if (active && version === generation) { options[kind].items.push(...page.items); options[kind].next = page.nextAfterId ?? undefined }
  } catch (cause) { if (active && version === generation) fail(cause) }
  finally { clearTimeout(timer); if (active && version === generation) loading.value = false }
}
function select(kind: OrganizationSection, record: OrganizationRecord | null = null) {
  if (locked.value || dirty.value) return
  const changed = kind !== section.value
  draft.value = { section: kind, baseline: record, form: organizationForm(record) }; error.value = ''; message.value = ''
  if (changed) { nextId.value = undefined; void load() }
}
function relationshipModeChange() {
  if (locked.value || dirty.value || !editing.value) return
  draft.value.mode = relationshipMode.value ? 'record' : 'relationship'
}
function discard() { draft.value.form = organizationForm(draft.value.baseline); error.value = ''; message.value = '已放弃本地修改。' }
async function initialize() {
  if (locked.value || !initializeConfirmed.value) return
  saving.value = true; error.value = ''; const scope = props.scopeKey
  try { await api.initializeOrganization(); if (active && scope === props.scopeKey) { message.value = '本地目录已启用，请配置组织和人员。'; await load() } }
  catch (cause) { if (active && scope === props.scopeKey) fail(cause) }
  finally { if (active && scope === props.scopeKey) saving.value = false }
}
async function save() {
  if (locked.value || !dirty.value) return
  const scope = props.scopeKey, path = organizationPath(draft.value), body = organizationPayload(draft.value)
  organizationDrafts.put(scope, draft.value); saving.value = true; error.value = ''; message.value = ''
  try {
    const value = await api.saveOrganization(path, editing.value, '保存' + organizationLabels[section.value], body)
    organizationDrafts.acknowledge(scope, path, JSON.stringify(body), value)
    if (!active || scope !== props.scopeKey) return
    draft.value = organizationDrafts.get(scope)!; message.value = '已保存修订 v' + value.revision + '，变更事实已保留。'; await load()
  } catch (cause) { if (active && scope === props.scopeKey) fail(cause) }
  finally { if (active && scope === props.scopeKey) saving.value = false }
}
async function loadHistory(more = false) {
  if (locked.value) return
  showHistory.value = true; controller?.abort(); controller = new AbortController()
  const scope = props.scopeKey, version = generation, requestController = controller, signal = requestController.signal
  const timer = setTimeout(() => requestController.abort(), READ_TIMEOUT_MS)
  loading.value = true
  try {
    const page = await api.organizationChanges(more ? before.value : undefined, signal)
    if (active && scope === props.scopeKey && version === generation) { history.value = more ? [...history.value, ...page.items] : page.items; before.value = page.nextBeforeRevision ?? undefined }
  } catch (cause) { if (active && version === generation) fail(cause) }
  finally { clearTimeout(timer); if (active && version === generation) loading.value = false }
}
watch(draft, value => organizationDrafts.put(props.scopeKey, value), { deep: true, flush: 'sync' })
watch(() => props.scopeKey, () => { controller?.abort(); generation++; initialized.value = false; rows.value = []; history.value = []; Object.values(options).forEach(value => { value.items = []; value.next = undefined }); draft.value = organizationDrafts.get(props.scopeKey) ?? emptyOrganizationDraft(); void load() }, { immediate: true })
watch(() => props.refreshVersion, () => { draft.value = organizationDrafts.get(props.scopeKey) ?? draft.value; void load() })
onUnmounted(() => { active = false; generation++; controller?.abort() })
</script>

<template>
  <section class="content organization" aria-label="组织与人员管理">
    <div class="page-heading"><div><p class="eyebrow">ORGANIZATION</p><h2>组织与人员</h2><p>维护企业组织、稳定身份与任职，让流程选到实际处理人。</p></div><button class="secondary" :disabled="locked" @click="load()">刷新目录</button></div>
    <p v-if="error" class="organization-error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="loading" role="status">正在读取组织目录…</p>
    <template v-if="!denied">
      <section v-if="!initialized && !loading" class="panel organization-setup"><h3>启用本地组织目录</h3><p>由本平台维护法人、部门、岗位、人员及任职。OIDC 继续负责登录与系统角色；本地审批资格不会授予管理员或审批角色。</p><p>启用后，本租户的选人目录不再使用演示名单。空目录需要先添加人员，才能发布包含本地审批人的流程。</p><label class="organization-check"><input v-model="initializeConfirmed" type="checkbox" :disabled="locked" />我已了解并采用本地组织目录</label><button class="primary" :disabled="locked || !initializeConfirmed" @click="initialize">启用本地目录</button></section>
      <template v-else-if="initialized">
        <nav class="organization-tabs" aria-label="组织类别"><button v-for="(text, kind) in organizationLabels" :key="kind" :aria-pressed="section === kind" :class="{ active: section === kind }" :disabled="locked || dirty" @click="select(kind)">{{ text }}</button><button class="secondary" :disabled="locked" @click="loadHistory()">查看变更记录</button></nav>
        <p class="organization-help">指定人员、部门、岗位、任职主管及部门负责人可用于审批选人。任职调整影响后续创建的任务，已创建任务保留原候选名单；人员停用或取消审批资格后不能继续办理。</p>
        <p v-if="dirty" class="organization-help">有未保存修改，请先保存或放弃后再切换记录。</p>
        <div class="organization-grid">
          <section class="panel organization-list" aria-label="组织记录列表"><div class="organization-toolbar"><h3>{{ organizationLabels[section] }}目录</h3><button class="secondary" :disabled="locked || dirty" @click="select(section)">新增{{ organizationLabels[section] }}</button></div>
            <button v-for="row in rows" :key="row.id" class="organization-row" :disabled="locked || dirty" :aria-pressed="draft.baseline?.id === row.id" @click="select(section, row)"><strong>{{ label(row) }}</strong><span>{{ row.active ? '在用' : '已停用' }} · v{{ row.revision }}</span><small v-if="'subject' in row">{{ row.subject }} · {{ row.approvalEligible ? '具备本地审批资格' : '无本地审批资格' }}</small><small v-if="'positionId' in row">{{ reference('POSITION', row.positionId) }}</small></button>
            <p v-if="!rows.length && !loading">暂无记录，请先新增{{ organizationLabels[section] }}。</p><button v-if="nextId" class="secondary" :disabled="locked" @click="load(true)">更多记录</button>
          </section>
          <form class="panel organization-editor" @submit.prevent="save"><h3>{{ relationshipMode ? '设置' : editing ? '修改' : '新增' }}{{ relationshipMode ? (section === 'APPOINTMENT' ? '直属主管' : '部门负责人') : organizationLabels[section] }}</h3>
            <button v-if="editing && ['DEPARTMENT','APPOINTMENT'].includes(section)" type="button" class="secondary" :disabled="locked || dirty" @click="relationshipModeChange">{{ relationshipMode ? '编辑基本信息' : section === 'APPOINTMENT' ? '设置直属主管' : '设置部门负责人' }}</button>
            <template v-if="!relationshipMode">
            <label v-if="section !== 'APPOINTMENT'">{{ section === 'PERSON' ? '人员名称' : '名称' }}<input v-model="form.name" required maxlength="128" :disabled="locked" /></label>
            <label v-if="section === 'PERSON'">身份源主体标识（sub）<input v-model="form.subject" required maxlength="128" :disabled="locked || editing" /><small>使用可信身份源的稳定 sub，保持原值；显示名称不能代替身份标识。</small></label>
            <label v-if="section === 'DEPARTMENT' || section === 'POSITION'">所属法人<select v-model="form.legalEntityId" required :disabled="locked || editing"><option value="">请选择法人</option><option v-if="form.legalEntityId && !known('LEGAL_ENTITY', form.legalEntityId)" :value="form.legalEntityId">当前引用 {{ form.legalEntityId }}</option><option v-for="item in options.LEGAL_ENTITY.items" :key="item.id" :value="item.id">{{ 'name' in item ? item.name : '' }}{{ item.active ? '' : '（已停用）' }}</option></select></label>
            <label v-if="section === 'DEPARTMENT'">上级部门<select v-model="form.parentDepartmentId" :disabled="locked"><option value="">法人下的一级部门</option><option v-if="form.parentDepartmentId && !known('DEPARTMENT', form.parentDepartmentId)" :value="form.parentDepartmentId">当前引用 {{ form.parentDepartmentId }}</option><option v-for="item in departments.filter(value => value.legalEntityId === form.legalEntityId && value.id !== draft.baseline?.id)" :key="item.id" :value="item.id">{{ item.name }}{{ item.active ? '' : '（已停用）' }}</option></select></label>
            <template v-if="section === 'APPOINTMENT'">
              <label>人员<select v-model="form.personId" required :disabled="locked || editing"><option value="">请选择人员</option><option v-if="form.personId && !known('PERSON', form.personId)" :value="form.personId">当前引用 {{ form.personId }}</option><option v-for="item in options.PERSON.items" :key="item.id" :value="item.id">{{ 'displayName' in item ? item.displayName : '' }}{{ item.active ? '' : '（已停用）' }}</option></select></label>
              <label>部门<select v-model="form.departmentId" required :disabled="locked || editing"><option value="">请选择部门</option><option v-if="form.departmentId && !known('DEPARTMENT', form.departmentId)" :value="form.departmentId">当前引用 {{ form.departmentId }}</option><option v-for="item in departments" :key="item.id" :value="item.id">{{ item.name }}{{ item.active ? '' : '（已停用）' }}</option></select></label>
              <label>岗位<select v-model="form.positionId" required :disabled="locked || editing"><option value="">请选择岗位</option><option v-if="form.positionId && !known('POSITION', form.positionId)" :value="form.positionId">当前引用 {{ form.positionId }}</option><option v-for="item in applicablePositions" :key="item.id" :value="item.id">{{ item.name }}{{ item.active ? '' : '（已停用）' }}</option></select></label>
              <p class="organization-help">调岗请停用旧任职并新增任职；同一人员可同时在多个部门或岗位任职。</p>
            </template>
            </template>
            <template v-else>
              <p>当前记录：{{ draft.baseline ? label(draft.baseline) : '' }}</p>
              <label>{{ section === 'APPOINTMENT' ? '直属主管的任职' : '负责人在本部门的任职' }}<select v-model="form.relationshipAppointmentId" :disabled="locked"><option value="">未设置／清除关系</option><option v-if="form.relationshipAppointmentId && !known('APPOINTMENT', form.relationshipAppointmentId)" :value="form.relationshipAppointmentId">当前任职尚未载入</option><option v-for="item in relationshipChoices" :key="item.id" :value="item.id">{{ label(item) }} · {{ 'positionId' in item ? reference('POSITION', item.positionId) : '' }}</option></select></label>
              <p class="organization-help">选择明确的人员任职。主管须属同一法人，部门负责人须在本部门任职；关系不能成环。变更影响后续激活的节点，历史候选保持不变。</p>
            </template>
            <div class="organization-reference-pages"><template v-for="kind in (['LEGAL_ENTITY','DEPARTMENT','POSITION','PERSON','APPOINTMENT'] as const)" :key="kind"><button v-if="options[kind].next" type="button" class="secondary" :disabled="locked" @click="moreReference(kind)">载入更多{{ organizationLabels[kind] }}选项</button></template></div>
            <label v-if="!relationshipMode" class="organization-check"><input v-model="form.active" type="checkbox" :disabled="locked" />在用</label>
            <label v-if="section === 'PERSON'" class="organization-check"><input v-model="form.approvalEligible" type="checkbox" :disabled="locked" />具备本地审批资格</label>
            <p v-if="section === 'PERSON'" class="organization-help">办理任务还要求身份源授予 APPROVER 角色。本页不会授予系统角色。</p>
            <div class="organization-toolbar"><button type="submit" class="primary" :disabled="locked || !dirty">{{ saving ? '正在保存…' : '保存' }}</button><button type="button" class="secondary" :disabled="locked || !dirty" @click="discard">放弃本地修改</button></div>
          </form>
        </div>
        <section v-if="showHistory" class="panel organization-history"><div class="organization-toolbar"><h3>组织变更记录</h3><button class="secondary" @click="showHistory = false">收起记录</button></div><details v-for="change in history" :key="change.revision"><summary>目录修订 {{ change.revision }} · {{ organizationLabels[change.kind as OrganizationSection] ?? change.kind }} · {{ change.actor }} · {{ new Date(change.occurredAt).toLocaleString('zh-CN') }}</summary><pre>{{ change.snapshotJson }}</pre></details><p v-if="!history.length">暂无变更记录。</p><button v-if="before" class="secondary" :disabled="locked" @click="loadHistory(true)">更早记录</button></section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.organization{font-size:13px}.organization .page-heading p,.organization-help,.organization-setup p{color:var(--muted);line-height:1.8}.organization-setup,.organization-editor,.organization-list,.organization-history{padding:22px}.organization-setup{max-width:760px}.organization-tabs{display:flex;gap:8px;flex-wrap:wrap;margin:20px 0}.organization-tabs button{width:auto;flex:none;background:#fff;border:1px solid var(--line);color:var(--ink);padding:10px 18px}.organization-tabs button.active{background:var(--deep);color:#fff}.organization-grid{display:grid;grid-template-columns:minmax(260px,1fr) minmax(340px,1.3fr);gap:22px}.organization-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}.organization-toolbar h3{margin:0}.organization-row{display:flex;flex-wrap:wrap;gap:8px;width:100%;border:0;border-top:1px solid var(--line);text-align:left;background:#fff;padding:16px 0;color:var(--ink)}.organization-row strong{flex:1;overflow-wrap:anywhere}.organization-row span,.organization-row small{color:var(--muted);font-size:11px}.organization-row small{flex-basis:100%;overflow-wrap:anywhere}.organization-row[aria-pressed=true]{color:var(--deep)}.organization-editor label{display:grid;gap:8px;margin:16px 0}.organization-editor input,.organization-editor select{width:100%;min-width:0;border:1px solid var(--line);border-radius:6px;padding:10px;background:#fff;font:inherit}.organization-editor small{line-height:1.7;color:var(--muted)}.organization .organization-check{display:flex;align-items:center;gap:10px;margin:18px 0}.organization-check input{width:16px;height:16px;margin:0;padding:0}.organization-error{color:var(--red);line-height:1.8}.organization-reference-pages{display:flex;flex-wrap:wrap;gap:6px}.organization-history{margin-top:22px}.organization-history summary{cursor:pointer;line-height:2}.organization-history pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;background:var(--paper);padding:14px}.organization button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:800px){.organization-grid{grid-template-columns:minmax(0,1fr)}.organization-tabs button{padding:9px 12px}.organization-toolbar{flex-wrap:wrap}.organization-setup,.organization-editor,.organization-list,.organization-history{padding:16px}}
</style>
