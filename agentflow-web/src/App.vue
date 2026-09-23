<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import ApplicationRecord from './components/ApplicationRecord.vue'
import WorkspaceRecords from './components/WorkspaceRecords.vue'
import ApplicationHistory from './components/ApplicationHistory.vue'
import RequestRecovery from './components/RequestRecovery.vue'
import FormFields from './components/FormFields.vue'
import FormSchemaEditor from './components/FormSchemaEditor.vue'
import TemplateCenter from './components/TemplateCenter.vue'
import SystemChecks from './components/SystemChecks.vue'
import DefinitionSimulation from './components/DefinitionSimulation.vue'
import DefinitionComparison from './components/DefinitionComparison.vue'
import DefinitionPublication from './components/DefinitionPublication.vue'
import PublicationDialog from './components/PublicationDialog.vue'
import { DraftAutosave } from './draftAutosave'
import { loadDesignerNodes, serializeDesignerNodes, type DesignerNode as FlowNode } from './designerGraph'
import { arrangeNodes, routeEdges, graphBounds, fittedViewport, clampZoom, zoomedScroll, draggedPosition, CANVAS_PADDING, MIN_ZOOM, MAX_ZOOM, ZOOM_STEP, type Point } from './designerLayout'
import UnsavedConfirmationDialog from './components/UnsavedConfirmationDialog.vue'
import { UnsavedConfirmation } from './unsavedConfirmation'
import { cloneSchema, defaultFormSchema, validatePayload, type FieldErrors, type FormSchema } from './formSchema'
import { api, writeRequests, type Actor, type ApiError, type Application, type Definition, type Graph, type GraphEdge, type Task, type TemplateCopyInput, type SimulationResult, type ComparisonChange } from './api'
import type { PendingWrite } from './pendingWrites.js'

type Page = 'started' | 'drafts' | 'handled' | 'workbench' | 'designer' | 'templates' | 'applications' | 'expense' | 'system'
type NodeType = 'START' | 'USER_TASK' | 'EXCLUSIVE_GATEWAY' | 'END'
const page = ref<Page>('workbench')
const comparisonOpen = ref(false)
const comparisonInput = computed(() => ({ key: definitionKey.value.trim(), name: definitionName.value.trim(), graph: simulationGraph.value, formSchema: definitionFormSchema.value }))
const simulationOpen = ref(false)
const simulationResult = ref<SimulationResult | null>(null)
const simulationGraph = computed(graphPayload)
const loggedIn = ref(false)
const username = ref('')
const password = ref('')
const tenantId = ref('demo')
const actor = ref<Actor | null>(null)
const actorScope = computed(() => actor.value ? JSON.stringify([actor.value.tenantId, actor.value.userId]) : '')
const confirmation = reactive(new UnsavedConfirmation())
const confirmationOpen = computed(() => confirmation.active !== null)
const confirmationReturnFocus = ref<HTMLElement | null>(null)
const publicationOpen = ref(false)
const publicationNote = ref('')
const publicationError = ref('')
const publicationReturnFocus = ref<HTMLElement | null>(null)
let viewActive = true
const templateRefresh = ref(0)
const notice = ref('')
const busy = ref(false)
const pendingWrites = ref<PendingWrite[]>([])
const writesBlocked = computed(() => pendingWrites.value.length > 0)
const recordRefresh = ref(0)
const recoveryError = ref('')
const workspace = ref<HTMLElement | null>(null)
const unsubscribeWrites = writeRequests.subscribe(() => { pendingWrites.value = writeRequests.pending() })
const serverAvailable = ref(false)
const tasks = ref<Task[]>([])
const applications = ref<Application[]>([])
const activeTask = ref<Task | null>(null)
const activeApplication = ref<Application | null>(null)
const detailError = ref('')
const taskTab = ref<'detail' | 'timeline' | 'audit'>('detail')
const pendingAction = ref<'RETURN' | 'TRANSFER' | null>(null)
const actionComment = ref('')
const targetUser = ref('')
const taskSearch = ref('')
const definitions = ref<Definition[]>([])
const definitionId = ref('')
const definitionRevision = ref(0)
const definitionVersion = ref(0)
const definitionStatus = ref('DRAFT')
const definitionKey = ref('expense-reimbursement')
const definitionName = ref('费用报销审批')
const definitionFormSchema = ref<FormSchema | null>(defaultFormSchema())
const selectedDefinitionId = ref('')
const savedSnapshot = ref('')
const editorSession = ref(0)
const autosaveEnabled = ref(true)
const composing = ref(false)
/** 保存检查点仅留在当前页面，用于确认原请求而不覆盖发送之后的新输入。@author owlzhangfq@gmail.com */
interface DraftCheckpoint { scope: string; snapshot: string; path: string }
let pendingDraftCheckpoint: DraftCheckpoint | null = null
const draftScope = computed(() => `${actorScope.value}:${editorSession.value}`)
const autosave = reactive(new DraftAutosave(
  () => ({ scope: draftScope.value, snapshot: snapshot(), eligible: viewActive && loggedIn.value && canManageDefinitions.value
    && page.value === 'designer' && autosaveEnabled.value && dirty.value && !readonlyDefinition.value
    && !busy.value && !writesBlocked.value && !confirmationOpen.value && !publicationOpen.value
    && !dragging.value && !composing.value && !!definitionKey.value.trim() && !!definitionName.value.trim() }),
  async () => { await persistDraft() }
))
const draftConflict = computed(() => ['DRAFT_VERSION_CONFLICT', 'CONCURRENCY_CONFLICT', 'DEFINITION_IMMUTABLE'].includes(autosave.failure?.code ?? ''))
const visiblePendingWrites = computed(() => pendingWrites.value.filter(operation => !(autosave.saving && operation.sending && operation.path === pendingDraftCheckpoint?.path)))
const autosaveLabel = computed(() => autosave.saving ? '正在保存草稿…' : autosave.failure ? '自动保存已暂停'
  : !autosaveEnabled.value ? '自动保存已关闭' : !definitionKey.value.trim() || !definitionName.value.trim() ? '填写流程标识和名称后自动保存'
  : !definitionId.value && !dirty.value ? '首次修改后自动保存'
  : autosave.scheduled || dirty.value ? '编辑停顿 2 秒后自动保存' : '草稿已保存')
const validationMessage = ref('尚未校验，发布前将运行服务端校验。')
const validationErrors = ref<string[]>([])
const newApplicationOpen = ref(false)
const recordApplicationId = ref('')
const applicationDefinitionId = ref('')
const applicationTitle = ref('')
const applicationBusinessNo = ref('')
const applicationAmount = ref('')
const applicationDescription = ref('')
const createdApplication = ref<Application | null>(null)
const applicationPayload = ref<Record<string, unknown>>({})
const applicationFieldErrors = ref<FieldErrors>({})
const applicationFormError = ref('')
const applicationFormSchema = computed(() => createdApplication.value ? createdApplication.value.formSchema ?? null : publishedDefinitions.value.find(item => item.id === applicationDefinitionId.value)?.formSchema ?? null)
const nodes = ref<FlowNode[]>([])
const edges = ref<GraphEdge[]>([])
const selectedId = ref('')
const selectedEdgeId = ref('')
const dragging = ref<string | null>(null)
const history = ref<string[]>([])
const future = ref<string[]>([])
const connectionTarget = ref('')
const canvas = ref<HTMLElement | null>(null)
const canvasZoom = ref(1)
const canvasSize = ref({ width: 0, height: 0 })
const canvasMessage = ref('缩放只改变视图，自动布局可撤销。')
const routedEdges = computed(() => routeEdges(nodes.value, edges.value))
const canvasBounds = computed(() => graphBounds(nodes.value, routedEdges.value))
const stageSize = computed(() => ({ width: Math.max(canvasSize.value.width / canvasZoom.value, canvasBounds.value.right + CANVAS_PADDING * 2),
  height: Math.max(canvasSize.value.height / canvasZoom.value, canvasBounds.value.bottom + CANVAS_PADDING * 2) }))
let stopNodeDrag: (() => void) | null = null
const palette: Array<{ type: NodeType; label: string; icon: string }> = [
  { type: 'USER_TASK', label: '人工审批', icon: '人' },
  { type: 'EXCLUSIVE_GATEWAY', label: '条件分支', icon: '◇' },
  { type: 'END', label: '结束节点', icon: '●' }
]
const roleOptions = [{ value: 'role:MANAGER', label: '部门审批组' }, { value: 'role:FINANCE', label: '财务审批组' }, { value: 'role:ADMIN', label: '额外复核组（示例）' }]
const selectedNode = computed(() => nodes.value.find(node => node.id === selectedId.value) ?? null)
const selectedEdge = computed(() => edges.value.find(edge => edge.id === selectedEdgeId.value) ?? null)
const publishedDefinitions = computed(() => definitions.value.filter(definition => definition.status === 'PUBLISHED'))
const canManageDefinitions = computed(() => actor.value?.roles.some(role => ['PROCESS_ADMIN', 'ADMIN'].includes(role)) ?? false)
const canInspectSystem = computed(() => actor.value?.roles.includes('ADMIN') ?? false)
const readonlyDefinition = computed(() => definitionStatus.value !== 'DRAFT')
const editorLocked = computed(() => readonlyDefinition.value || (writesBlocked.value && !autosave.saving) || busy.value || confirmationOpen.value || publicationOpen.value)
const visibleTasks = computed(() => tasks.value.filter(task => task.taskName.toLowerCase().includes(taskSearch.value.trim().toLowerCase())))
const dirty = computed(() => snapshot() !== savedSnapshot.value)
const today = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full' }).format(new Date())
const statusLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已拒绝', APPROVED: '已批准', REVOKED: '已撤销', CANCELLED: '已作废' }
const statusLabel = (status: string) => statusLabels[status] ?? status
const errorMessage = (error: unknown) => (error as ApiError)?.message ?? '无法连接服务，请稍后重试'
const roleLabel = (rule: string) => roleOptions.find(option => option.value === rule)?.label ?? (rule ? rule : '待配置')
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')

function defaultGraph() {
  nodes.value = [
    { id: 'start', name: '开始', type: 'START', x: 40, y: 180, assigneeRule: '' },
    { id: 'manager', name: '部门审批', type: 'USER_TASK', x: 190, y: 180, assigneeRule: 'role:MANAGER' },
    { id: 'amount', name: '金额判断', type: 'EXCLUSIVE_GATEWAY', x: 390, y: 180, assigneeRule: '' },
    { id: 'finance', name: '财务复核', type: 'USER_TASK', x: 590, y: 75, assigneeRule: 'role:FINANCE' },
    { id: 'end', name: '结束', type: 'END', x: 810, y: 180, assigneeRule: '' }
  ]
  edges.value = [
    { id: 'e1', source: 'start', target: 'manager', condition: '', defaultBranch: false },
    { id: 'e2', source: 'manager', target: 'amount', condition: '', defaultBranch: false },
    { id: 'e3', source: 'amount', target: 'end', condition: '', defaultBranch: true },
    { id: 'e4', source: 'amount', target: 'finance', condition: 'amount > 5000', defaultBranch: false },
    { id: 'e5', source: 'finance', target: 'end', condition: '', defaultBranch: false }
  ]
  selectedId.value = 'amount'
  selectedEdgeId.value = ''
}
function snapshot() { return JSON.stringify({ key: definitionKey.value, name: definitionName.value, nodes: nodes.value, edges: edges.value, formSchema: definitionFormSchema.value }) }
function remember() { history.value.push(snapshot()); if (history.value.length > 50) history.value.shift(); future.value = [] }
// 首次保存前的撤销快照可能尚无标识；已落库草稿的身份不能随内容撤销。
function restore(raw: string) { const value = JSON.parse(raw); if (!definitionId.value) definitionKey.value = value.key; definitionName.value = value.name; nodes.value = value.nodes; edges.value = value.edges; definitionFormSchema.value = cloneSchema(value.formSchema ?? null) }
function undo() { if (editorLocked.value) return; stopNodeDrag?.(); const value = history.value.pop(); if (value) { future.value.push(snapshot()); restore(value) } }
function redo() { if (editorLocked.value) return; stopNodeDrag?.(); const value = future.value.pop(); if (value) { history.value.push(snapshot()); restore(value) } }
function resetEditor() { history.value = []; future.value = []; selectedEdgeId.value = ''; connectionTarget.value = ''; validationErrors.value = []; validationMessage.value = '尚未校验，发布前将运行服务端校验。' }
function graphPayload(): Graph {
  return {
    nodes: serializeDesignerNodes(nodes.value),
    edges: edges.value.map(edge => ({ ...edge, condition: edge.defaultBranch ? '' : edge.condition.trim() }))
  }
}
function applyDefinition(definition: Definition) {
  editorSession.value++; autosave.reset(); pendingDraftCheckpoint = null; composing.value = false
  definitionId.value = definition.id; selectedDefinitionId.value = definition.id
  definitionKey.value = definition.key; definitionName.value = definition.name; definitionFormSchema.value = cloneSchema(definition.formSchema ?? null)
  definitionRevision.value = definition.revision; definitionVersion.value = definition.version; definitionStatus.value = definition.status
  nodes.value = loadDesignerNodes(definition.graph.nodes)
  edges.value = definition.graph.edges.map(edge => ({ ...edge, defaultBranch: edge.defaultBranch ?? false }))
  selectedId.value = nodes.value[0]?.id ?? ''; resetEditor(); savedSnapshot.value = snapshot()
  localStorage.setItem(`agentflow.definition.${tenantId.value}`, definition.id)
}
/** 应用层持有原操作意图，等待结束后复核会话与业务锁，再执行原动作。 */
async function confirmReplaceDefinition(confirmLabel: string, operation: () => void | Promise<void>,
  allowed = () => !busy.value && !writesBlocked.value, required = !readonlyDefinition.value && dirty.value) {
  const sessionActor = actor.value
  const scope = actorScope.value
  const current = () => viewActive && !!sessionActor && actor.value === sessionActor && actorScope.value === scope && allowed()
  if (!current() || confirmationOpen.value) return
  if (required) confirmationReturnFocus.value = document.activeElement as HTMLElement | null
  if (await confirmation.confirm(confirmLabel, current, required)) {
    // Promise 恢复与调用方之间仍可能发生状态变化；此处是实际执行前的最后一道准入检查。
    if (current()) await operation()
  }
}
async function chooseDefinition() {
  if (busy.value || writesBlocked.value) return
  const targetId = selectedDefinitionId.value
  selectedDefinitionId.value = definitionId.value
  if (!targetId || targetId === definitionId.value) return
  await confirmReplaceDefinition('放弃修改并切换', () => {
    const definition = definitions.value.find(item => item.id === targetId)
    if (definition) applyDefinition(definition)
    else notice.value = '该流程已不在当前列表，请刷新后重试。'
  })
}
async function newDefinition(copy = false) {
  await confirmReplaceDefinition('放弃修改并新建', () => {
    editorSession.value++; autosave.reset(); pendingDraftCheckpoint = null; composing.value = false
    if (!copy) { defaultGraph(); definitionKey.value = ''; definitionName.value = '新审批流程'; definitionFormSchema.value = defaultFormSchema() }
    else definitionFormSchema.value = cloneSchema(definitionFormSchema.value)
    definitionId.value = ''; selectedDefinitionId.value = ''; definitionRevision.value = 0; definitionVersion.value = 0; definitionStatus.value = 'DRAFT'; resetEditor(); savedSnapshot.value = ''
    notice.value = copy ? '已复制为新草稿，保存后可继续编辑并发布新版本。' : '填写流程标识和名称，完成设计后保存草稿。'
  }, () => canManageDefinitions.value && !busy.value && !writesBlocked.value, !copy && !readonlyDefinition.value && dirty.value)
}
async function loadDefinitions(restoreSelection = false) {
  const scope = actorScope.value
  const result = await api.definitions()
  if (!scope || actorScope.value !== scope) return
  definitions.value = result
  if (restoreSelection) {
    const remembered = localStorage.getItem(`agentflow.definition.${tenantId.value}`)
    const definition = definitions.value.find(item => item.id === remembered) ?? definitions.value[0]
    if (definition) applyDefinition(definition)
  }
}
async function loadTasks() { const scope = actorScope.value; const result = await api.tasks(); if (scope && actorScope.value === scope) tasks.value = result }
async function loadApplications() { const scope = actorScope.value; const result = await api.applications(); if (scope && actorScope.value === scope) applications.value = result }
async function refreshWorkspace(restoreSelection = false) {
  const scope = actorScope.value
  try { await Promise.all([loadTasks(), loadApplications(), loadDefinitions(restoreSelection)]); if (scope && actorScope.value === scope) serverAvailable.value = true }
  catch (error) { if (scope && actorScope.value === scope) { serverAvailable.value = false; notice.value = errorMessage(error) } }
}
function refreshPage() { templateRefresh.value++; void refreshWorkspace() }
async function copyTemplate(templateKey: string, body: TemplateCopyInput) {
  const originalBody = { ...body }
  await confirmReplaceDefinition('放弃修改并复制', async () => {
    busy.value = true
    try {
      const definition = await api.copyTemplate(templateKey, originalBody)
      applyDefinition(definition); page.value = 'designer'; templateRefresh.value++
      await loadDefinitions(); notice.value = '模板已复制为独立草稿，请核对字段、审批角色与分支后再发布。'
    } catch (error) { notice.value = errorMessage(error) }
    finally { busy.value = false }
  }, () => canManageDefinitions.value && !busy.value && !writesBlocked.value)
}
async function openTemplateCopy(id: string) {
  await confirmReplaceDefinition('放弃修改并打开', async () => {
    busy.value = true
    const scope = actorScope.value
    try {
      const definition = await api.getDefinition(id)
      if (actorScope.value !== scope) return
      applyDefinition(definition); page.value = 'designer'; notice.value = '已打开当前租户的模板副本。'
    } catch (error) { if (actorScope.value === scope) notice.value = errorMessage(error) }
    finally { busy.value = false }
  }, () => canManageDefinitions.value && !busy.value && !writesBlocked.value)
}
function clearDesigner() {
  editorSession.value++; autosave.reset(); pendingDraftCheckpoint = null; composing.value = false
  defaultGraph(); definitionId.value = ''; selectedDefinitionId.value = ''; definitionRevision.value = 0; definitionVersion.value = 0
  definitionStatus.value = 'DRAFT'; definitionKey.value = 'expense-reimbursement'; definitionName.value = '费用报销审批'; definitionFormSchema.value = defaultFormSchema()
  resetEditor(); savedSnapshot.value = snapshot()
}
async function login() {
  if (busy.value) return
  if (!username.value.trim() || !password.value) { notice.value = '请输入用户名和密码'; return }
  busy.value = true
  try { const result = await api.login({ tenantId: tenantId.value.trim(), username: username.value.trim(), password: password.value }); localStorage.setItem('agentflow.token', result.token); actor.value = result.user; writeRequests.setActor(result.user); loggedIn.value = true; password.value = ''; notice.value = '已进入工作空间'; await refreshWorkspace(true) }
  catch (error) { notice.value = errorMessage(error) }
  finally { busy.value = false }
}
async function logout() {
  await confirmReplaceDefinition('放弃修改并退出', async () => {
    busy.value = true
    try { await api.logout() } catch { /* 本地会话始终清除，失效令牌由服务端校验。 */ }
    writeRequests.setActor(null)
    localStorage.removeItem('agentflow.token'); loggedIn.value = false; actor.value = null; serverAvailable.value = false; tasks.value = []; applications.value = []; definitions.value = []; activeTask.value = null; activeApplication.value = null; notice.value = ''; page.value = 'workbench'
    clearDesigner(); templateRefresh.value++
    recordApplicationId.value = ''; recoveryError.value = ''; newApplicationOpen.value = false; createdApplication.value = null; busy.value = false
  }, () => !busy.value && !pendingWrites.value.some(operation => operation.sending))
}
async function selectTask(task: Task) {
  if (busy.value || writesBlocked.value) return
  activeTask.value = task; activeApplication.value = null; detailError.value = ''; taskTab.value = 'detail'; pendingAction.value = null; actionComment.value = ''; targetUser.value = ''
  try { const application = await api.application(task.applicationId); if (activeTask.value?.taskId === task.taskId) activeApplication.value = application }
  catch (error) { detailError.value = errorMessage(error) }
}
function prepareAction(action: 'RETURN' | 'TRANSFER') { pendingAction.value = action; actionComment.value = ''; targetUser.value = '' }
async function performAction(action: 'APPROVE' | 'RETURN' | 'TRANSFER') {
  if (!activeTask.value || busy.value || writesBlocked.value) return
  if (action === 'RETURN' && !actionComment.value.trim()) { notice.value = '请填写退回原因'; return }
  if (action === 'TRANSFER' && !targetUser.value.trim()) { notice.value = '请填写接收人的用户账号'; return }
  busy.value = true
  try {
    const result = await api.taskAction(activeTask.value.taskId, { action, expectedVersion: activeTask.value.version, comment: actionComment.value.trim() || undefined, targetUser: action === 'TRANSFER' ? targetUser.value.trim() : undefined })
    activeTask.value = null; activeApplication.value = null; pendingAction.value = null
    await refreshWorkspace(); notice.value = `${action === 'APPROVE' ? '批准' : action === 'RETURN' ? '退回' : '转交'}已完成，申请状态：${statusLabel(result.applicationStatus)}`
  } catch (error) { notice.value = errorMessage(error) }
  finally { busy.value = false }
}
async function openSimulation() {
  simulationOpen.value = !simulationOpen.value
  await nextTick()
  if (simulationOpen.value) document.getElementById('simulation-title')?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
async function openComparison() {
  comparisonOpen.value = !comparisonOpen.value
  await nextTick()
  if (comparisonOpen.value) document.getElementById('comparison-title')?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
async function locateComparisonChange(change: ComparisonChange) {
  if (change.area !== 'FIELD') { await locateDesignTarget(change.targetId); return }
  const index = definitionFormSchema.value?.fields.findIndex(field => field.key === change.targetId) ?? -1
  if (index < 0) return
  document.querySelector<HTMLElement>(`[aria-label="字段 ${index + 1} 名称"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
async function locateDesignTarget(id: string) {
  const node = nodes.value.find(item => item.id === id)
  if (node) selectNode(node)
  else { const edge = edges.value.find(item => item.id === id); if (!edge) return; selectEdge(edge) }
  await nextTick()
  const nodeId = node?.id ?? edges.value.find(item => item.id === id)?.source
  const target = Array.from(canvas.value?.querySelectorAll<HTMLElement>('[data-node-id]') ?? []).find(item => item.dataset.nodeId === nodeId)
  target?.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' })
}
async function validateGraph() {
  const result = await api.validateDefinition(graphPayload(), definitionFormSchema.value); validationErrors.value = result.errors
  validationMessage.value = result.errors.length ? `服务端校验发现 ${result.errors.length} 项问题。` : '服务端校验通过。'
  return result.errors.length === 0
}
async function validate() { if (busy.value) return; busy.value = true; try { await validateGraph() } catch (error) { validationMessage.value = '校验请求失败，请重试。'; notice.value = errorMessage(error) } finally { busy.value = false } }
/** 只确认本次发送的快照；新输入、选中项、焦点和撤销栈均留在编辑器。 */
function acknowledgeDraft(definition: Definition, checkpoint: DraftCheckpoint) {
  if (!viewActive || checkpoint.scope !== draftScope.value) return
  definitionId.value = definition.id; selectedDefinitionId.value = definition.id
  definitionKey.value = definition.key
  definitionRevision.value = definition.revision; definitionVersion.value = definition.version; definitionStatus.value = definition.status
  savedSnapshot.value = checkpoint.snapshot
  const index = definitions.value.findIndex(item => item.id === definition.id)
  if (index < 0) definitions.value.unshift(definition)
  else definitions.value[index] = definition
  localStorage.setItem(`agentflow.definition.${actor.value!.tenantId}`, definition.id)
}
async function persistDraft(): Promise<Definition> {
  if (readonlyDefinition.value) throw new Error('已发布定义只读，请先复制为新草稿')
  if (!definitionKey.value.trim() || !definitionName.value.trim()) throw new Error('请填写流程标识和名称')
  definitionKey.value = definitionKey.value.trim()
  const checkpoint: DraftCheckpoint = { scope: draftScope.value, snapshot: snapshot(), path: definitionId.value ? `/process-definitions/${encodeURIComponent(definitionId.value)}` : '/process-definitions' }
  pendingDraftCheckpoint = checkpoint
  try {
    const definition = definitionId.value
      ? await api.updateDefinition(definitionId.value, { name: definitionName.value.trim(), graph: graphPayload(), formSchema: definitionFormSchema.value, expectedRevision: definitionRevision.value })
      : await api.definition({ key: definitionKey.value.trim(), name: definitionName.value.trim(), graph: graphPayload(), formSchema: definitionFormSchema.value })
    acknowledgeDraft(definition, checkpoint)
    if (pendingDraftCheckpoint === checkpoint) pendingDraftCheckpoint = null
    return definition
  } catch (error) {
    if (!pendingWrites.value.some(operation => operation.path === checkpoint.path) && pendingDraftCheckpoint === checkpoint) pendingDraftCheckpoint = null
    throw error
  }
}
async function saveDraft() {
  if (busy.value || writesBlocked.value || draftConflict.value) return; busy.value = true
  try { await persistDraft(); autosave.saved(); notice.value = '流程草稿已保存' }
  catch (error) { autosave.pause(error); notice.value = errorMessage(error) } finally { busy.value = false }
}
/** 冲突不自动合并或覆盖；明确放弃本地修改后重新读取服务器最新版本。 */
async function reloadConflictingDraft() {
  const id = definitionId.value, scope = draftScope.value
  if (!id) return
  await confirmReplaceDefinition('放弃本地修改并加载', async () => {
    busy.value = true
    try { const definition = await api.getDefinition(id); if (draftScope.value === scope) { applyDefinition(definition); notice.value = '已加载服务端最新版本。' } }
    catch (error) { notice.value = errorMessage(error) } finally { busy.value = false }
  })
}
function openPublication() {
  if (editorLocked.value || draftConflict.value || !canManageDefinitions.value) return
  publicationReturnFocus.value = document.activeElement as HTMLElement | null
  publicationError.value = ''; publicationOpen.value = true
}
async function publishDraft() {
  if (!publicationOpen.value || !canManageDefinitions.value || busy.value || writesBlocked.value || readonlyDefinition.value) return
  const changeNote = publicationNote.value.trim()
  if (!changeNote || changeNote.length > 2000) { publicationError.value = '请填写 1 至 2000 字的发布变更说明。'; return }
  busy.value = true; publicationError.value = ''
  try {
    if (!await validateGraph()) { publicationError.value = '流程校验未通过，请返回编辑并修复下方校验问题。'; return }
    const saved = await persistDraft()
    autosave.saved()
    const published = await api.publishDefinition(saved.id, saved.revision, changeNote)
    applyDefinition(published); publicationOpen.value = false; publicationNote.value = ''
    await loadDefinitions(); notice.value = `流程${statusLabel(published.status)}，版本 v${published.version}，发布记录已保存。`
  } catch (error) { autosave.pause(error); publicationError.value = errorMessage(error); notice.value = publicationError.value } finally { busy.value = false }
}
function selectNode(node: FlowNode) { selectedId.value = node.id; selectedEdgeId.value = ''; connectionTarget.value = '' }
function selectEdge(edge: GraphEdge) { selectedEdgeId.value = edge.id; selectedId.value = '' }
function moveNode(event: PointerEvent, node: FlowNode) {
  if (editorLocked.value || event.button !== 0) return
  stopNodeDrag?.()
  const viewport = canvas.value
  if (!viewport) return
  const target = event.currentTarget as HTMLElement
  const start = { x: node.x, y: node.y }, pointer = { x: event.clientX, y: event.clientY }
  const scroll = { x: viewport.scrollLeft, y: viewport.scrollTop }, zoom = canvasZoom.value
  let changed = false
  target.setPointerCapture(event.pointerId)
  const move = (next: PointerEvent) => {
    if (next.pointerId !== event.pointerId || editorLocked.value) return
    const bounds = viewport.getBoundingClientRect()
    if (next.clientX > bounds.right - 24) viewport.scrollLeft += 14
    else if (next.clientX < bounds.left + 24) viewport.scrollLeft -= 14
    if (next.clientY > bounds.bottom - 24) viewport.scrollTop += 14
    else if (next.clientY < bounds.top + 24) viewport.scrollTop -= 14
    const position = draggedPosition(start, { x: next.clientX - pointer.x, y: next.clientY - pointer.y },
      { x: viewport.scrollLeft - scroll.x, y: viewport.scrollTop - scroll.y }, zoom)
    if (position.x === node.x && position.y === node.y) return
    if (!changed) { remember(); changed = true; dragging.value = node.id }
    node.x = position.x; node.y = position.y
  }
  const up = (next: PointerEvent) => { if (next.pointerId === event.pointerId) stopNodeDrag?.() }
  stopNodeDrag = () => {
    dragging.value = null; window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up)
    window.removeEventListener('pointercancel', up); target.removeEventListener('lostpointercapture', up)
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId)
    stopNodeDrag = null
  }
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', up)
  window.addEventListener('pointercancel', up); target.addEventListener('lostpointercapture', up)
}
function addNode(type: NodeType, position?: Point) {
  if (editorLocked.value) return
  remember(); const base = selectedNode.value; const id = `${type.toLowerCase()}-${crypto.randomUUID()}`
  nodes.value.push({ id, name: palette.find(item => item.type === type)?.label ?? '节点', type, x: position?.x ?? (base?.x ?? 160) + 180, y: position?.y ?? (base?.y ?? 140) + 80, assigneeRule: '' })
  if (base && base.type !== 'END' && base.type !== 'EXCLUSIVE_GATEWAY') {
    const old = edges.value.find(edge => edge.source === base.id)
    if (old) old.source = id
    edges.value.push({ id: `edge-${crypto.randomUUID()}`, source: base.id, target: id, condition: '', defaultBranch: false })
  }
  selectedId.value = id; selectedEdgeId.value = ''
}
function connectNode() {
  const node = selectedNode.value
  if (editorLocked.value || !node || node.type === 'END' || !connectionTarget.value || edges.value.some(edge => edge.source === node.id && edge.target === connectionTarget.value)) return
  remember()
  if (node.type !== 'EXCLUSIVE_GATEWAY') edges.value = edges.value.filter(edge => edge.source !== node.id)
  edges.value.push({ id: `edge-${crypto.randomUUID()}`, source: node.id, target: connectionTarget.value, condition: '', defaultBranch: false }); connectionTarget.value = ''
}
function toggleDefault(edge: GraphEdge) {
  if (editorLocked.value) return
  remember(); const enable = !edge.defaultBranch
  edges.value.filter(item => item.source === edge.source).forEach(item => { item.defaultBranch = enable && item.id === edge.id })
  if (enable) edge.condition = ''
}
function deleteSelected() {
  if (editorLocked.value) return
  if (selectedNode.value && selectedNode.value.type !== 'START') { remember(); const id = selectedNode.value.id; nodes.value = nodes.value.filter(node => node.id !== id); edges.value = edges.value.filter(edge => edge.source !== id && edge.target !== id); selectedId.value = '' }
  else if (selectedEdge.value) { remember(); edges.value = edges.value.filter(edge => edge.id !== selectedEdgeId.value); selectedEdgeId.value = '' }
}
function onDrop(event: DragEvent) {
  const type = event.dataTransfer?.getData('node-type') as NodeType
  const viewport = canvas.value
  if (!viewport || !palette.some(item => item.type === type)) return
  const bounds = viewport.getBoundingClientRect()
  const position = draggedPosition({ x: 0, y: 0 }, { x: event.clientX - bounds.left, y: event.clientY - bounds.top },
    { x: viewport.scrollLeft, y: viewport.scrollTop }, canvasZoom.value)
  addNode(type, position)
}
async function changeCanvasZoom(delta: number) {
  const viewport = canvas.value
  if (!viewport) return
  stopNodeDrag?.()
  const next = clampZoom(canvasZoom.value + delta)
  const position = zoomedScroll({ width: viewport.clientWidth, height: viewport.clientHeight, left: viewport.scrollLeft, top: viewport.scrollTop }, canvasZoom.value, next)
  canvasZoom.value = next
  await nextTick()
  viewport.scrollTo(position.x, position.y)
  canvasMessage.value = '缩放只改变视图，自动布局可撤销。'
}
async function fitCanvas(quiet = false) {
  await nextTick()
  const viewport = canvas.value
  if (!viewport) return
  stopNodeDrag?.()
  const fit = fittedViewport(canvasBounds.value, viewport.clientWidth, viewport.clientHeight)
  canvasZoom.value = fit.zoom
  await nextTick()
  viewport.scrollTo(fit.left, fit.top)
  if (!quiet) canvasMessage.value = fit.clipped ? '已缩小至 50%，流程较大，可继续滚动画布查看。' : '已适应画布，节点坐标保持不变。'
}
async function autoLayout() {
  if (editorLocked.value || !canManageDefinitions.value) return
  try {
    stopNodeDrag?.()
    const result = arrangeNodes(nodes.value, edges.value)
    if (result.nodes.some((node, index) => node.x !== nodes.value[index]?.x || node.y !== nodes.value[index]?.y)) { remember(); nodes.value = result.nodes }
    await fitCanvas(true)
    canvasMessage.value = result.hasCycle ? '已整理布局；草稿仍有回环，发布前需要修复。' : '已自动布局，可撤销；审批规则和分支顺序保持不变。'
  } catch (error) { canvasMessage.value = errorMessage(error) }
}
function keyHandler(event: KeyboardEvent) {
  if (page.value !== 'designer' || editorLocked.value || ['INPUT', 'TEXTAREA', 'SELECT'].includes((event.target as HTMLElement).tagName)) return
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'z') { event.preventDefault(); event.shiftKey ? redo() : undo() }
  if (['Delete', 'Backspace'].includes(event.key)) { event.preventDefault(); deleteSelected() }
}
async function openApplicationForm() {
  if (busy.value || writesBlocked.value) { notice.value = '请先恢复上次操作，再发起新申请。'; return }
  try {
    await loadDefinitions(); createdApplication.value = null
    applicationDefinitionId.value = publishedDefinitions.value[0]?.id ?? ''; applicationTitle.value = ''; applicationBusinessNo.value = `APP-${Date.now()}`
    applicationAmount.value = ''; applicationDescription.value = ''; applicationPayload.value = {}; applicationFieldErrors.value = {}; applicationFormError.value = ''; newApplicationOpen.value = true
  } catch (error) { notice.value = errorMessage(error) }
}
async function createAndSubmitApplication(submit = true) {
  if (busy.value || writesBlocked.value) return
  applicationFormError.value = ''; applicationFieldErrors.value = {}
  const definition = publishedDefinitions.value.find(item => item.id === applicationDefinitionId.value)
  if ((!definition && !createdApplication.value) || !applicationTitle.value.trim() || !applicationBusinessNo.value.trim()) { applicationFormError.value = '请选择已发布流程，并填写申请标题和业务单号。'; return }
  let payload: Record<string, unknown>
  if (createdApplication.value) payload = createdApplication.value.payload
  else if (applicationFormSchema.value) payload = applicationPayload.value
  else {
    if ((submit && !applicationAmount.value) || (applicationAmount.value !== '' && (!Number.isFinite(Number(applicationAmount.value)) || Number(applicationAmount.value) < 0))) { applicationFormError.value = '请输入不小于零的申请金额。'; return }
    payload = { ...(applicationAmount.value !== '' ? { amount: Number(applicationAmount.value) } : {}), description: applicationDescription.value.trim() }
  }
  applicationFieldErrors.value = validatePayload(applicationFormSchema.value, payload, submit)
  if (Object.keys(applicationFieldErrors.value).length) { applicationFormError.value = '请按字段提示修改后再操作。'; return }
  busy.value = true
  try {
    if (!createdApplication.value) createdApplication.value = await api.createApplication({ businessNo: applicationBusinessNo.value.trim(), processKey: definition!.key, definitionVersion: definition!.version, title: applicationTitle.value.trim(), payload })
    if (!submit) {
      const saved = createdApplication.value
      newApplicationOpen.value = false; createdApplication.value = null; page.value = 'drafts'; templateRefresh.value++; await refreshWorkspace(); notice.value = `草稿 ${saved.businessNo} 已保存，可在我的草稿中继续填写。`; return
    }
    const submitted = await api.submitApplication(createdApplication.value.id, createdApplication.value.version)
    newApplicationOpen.value = false; createdApplication.value = null; page.value = 'started'; templateRefresh.value++; await refreshWorkspace(); notice.value = `申请 ${submitted.businessNo} 已提交，状态：${statusLabel(submitted.status)}`
  } catch (error) {
    applicationFieldErrors.value = (error as ApiError).details?.fieldErrors ?? {}
    applicationFormError.value = `${errorMessage(error)}${createdApplication.value ? '；草稿已保留，可在申请记录中补充填写或重试提交。' : ''}`
  } finally { busy.value = false }
}
watch(applicationDefinitionId, () => {
  if (createdApplication.value) return
  applicationPayload.value = {}; applicationAmount.value = ''; applicationDescription.value = ''; applicationFieldErrors.value = {}; applicationFormError.value = ''
})

watch(actor, () => { editorSession.value++; autosave.reset(); pendingDraftCheckpoint = null; confirmation.cancel(); publicationOpen.value = false; publicationNote.value = ''; publicationError.value = '' }, { flush: 'sync' })
watch([actor, editorSession, page, editorLocked], () => stopNodeDrag?.(), { flush: 'sync' })
watch(editorSession, () => { canvasMessage.value = '缩放只改变视图，自动布局可撤销。'; void fitCanvas(true) })
watch(canvas, (element, _previous, onCleanup) => {
  if (!element) return
  const resize = () => { canvasSize.value = { width: element.clientWidth, height: element.clientHeight } }
  const observer = new ResizeObserver(resize)
  observer.observe(element); resize(); void fitCanvas(true)
  onCleanup(() => observer.disconnect())
})
watch([definitionId, definitionKey], () => { if (!publicationOpen.value) publicationNote.value = '' }, { flush: 'sync' })

watch([() => snapshot(), draftScope, page, loggedIn, canManageDefinitions, autosaveEnabled, readonlyDefinition,
  busy, writesBlocked, confirmationOpen, publicationOpen, dragging, composing, savedSnapshot], () => autosave.observe())

watch([nodes, edges, definitionName, definitionFormSchema], () => { validationErrors.value = []; validationMessage.value = '内容已修改，请重新校验。' }, { deep: true, flush: 'sync' })

/** 恢复结果始终更新原资源；恢复成功后由用户决定是否继续提交或发布。 */
async function recoverOperation(id: string) {
  const pending = pendingWrites.value.find(operation => operation.id === id)
  if (!pending) return
  const checkpoint = pendingDraftCheckpoint?.scope === draftScope.value && pendingDraftCheckpoint.path === pending.path ? pendingDraftCheckpoint : null
  const replacesDefinition = pending.path.startsWith('/process-definitions') || pending.path.startsWith('/process-templates/')
  await confirmReplaceDefinition('放弃修改并恢复', async () => {
    busy.value = true; recoveryError.value = ''
    try {
      const { request, result } = await writeRequests.recover(id)
      if (request.path.startsWith('/process-templates/') && request.path.endsWith('/copy')) {
        applyDefinition(result as Definition); page.value = 'designer'; templateRefresh.value++
        notice.value = '已确认原模板复制结果，已打开原草稿，请核对后再发布。'
      } else if (checkpoint && request.path === checkpoint.path && checkpoint.scope === draftScope.value) {
        acknowledgeDraft(result as Definition, checkpoint); pendingDraftCheckpoint = null
        if (dirty.value) autosave.pause({ code: 'RECOVERED_WITH_LOCAL_CHANGES', message: '原草稿已确认保存，后续修改仍保留。请点击保存草稿后继续自动保存。' })
        else autosave.saved()
        notice.value = '已确认原草稿保存结果，保留发送后的本地修改。'
      } else if (request.path.startsWith('/process-definitions')) {
        applyDefinition(result as Definition); page.value = 'designer'
        notice.value = request.path.includes('/publish?') ? '已确认原流程的发布结果。' : '已确认原流程草稿的保存结果，请核对后再发布。'
      } else if (request.path.startsWith('/applications')) {
        const value = result as Application
        if (request.path === '/applications') {
          createdApplication.value = value; applicationTitle.value = value.title; applicationBusinessNo.value = value.businessNo
          applicationPayload.value = { ...value.payload }; applicationFieldErrors.value = {}; applicationFormError.value = ''
          applicationAmount.value = String(value.payload.amount ?? ''); applicationDescription.value = String(value.payload.description ?? '')
          applicationDefinitionId.value = definitions.value.find(item => item.key === value.processKey && item.version === value.definitionVersion)?.id ?? ''
          newApplicationOpen.value = true
          notice.value = '原申请草稿已确认，请核对后再提交。'
        } else {
          if (createdApplication.value?.id === value.id) { createdApplication.value = null; newApplicationOpen.value = false }
          if (recordApplicationId.value === value.id) recordRefresh.value++
          notice.value = `已确认申请 ${value.businessNo} 的原操作，状态：${statusLabel(value.status)}。`
        }
      } else {
        const value = result as { taskId: string; applicationStatus: string }
        if (activeTask.value?.taskId === value.taskId) { activeTask.value = null; activeApplication.value = null; pendingAction.value = null }
        notice.value = `已确认原审批任务的处理结果，申请状态：${statusLabel(value.applicationStatus)}。`
      }
      await refreshWorkspace()
    } catch (error) {
      if (checkpoint?.scope === draftScope.value) {
        autosave.pause(error)
        if (!pendingWrites.value.some(operation => operation.id === id)) pendingDraftCheckpoint = null
      }
      recoveryError.value = errorMessage(error); notice.value = recoveryError.value
    }
    finally {
      busy.value = false
      await nextTick()
      const dialog = document.querySelector<HTMLElement>('[role="dialog"]')
      const recovery = (dialog ?? workspace.value)?.querySelector<HTMLElement>('.request-recovery button:not(:disabled)')
      ;(recovery ?? dialog ?? workspace.value)?.focus()
    }
  }, () => !busy.value && pendingWrites.value.some(operation => operation.id === id && !operation.sending),
  replacesDefinition && !checkpoint && !readonlyDefinition.value && dirty.value)
}
function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (writeRequests.hasUnconfirmed() || (!readonlyDefinition.value && (dirty.value || publicationNote.value.trim()))) { event.preventDefault(); event.returnValue = '' }
}
defaultGraph(); savedSnapshot.value = snapshot()
onMounted(async () => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  if (!localStorage.getItem('agentflow.token')) return
  try { const result = await api.me(); actor.value = result.actor; writeRequests.setActor(result.actor); username.value = result.actor.userId; tenantId.value = result.actor.tenantId; loggedIn.value = true; await refreshWorkspace(true) }
  catch { localStorage.removeItem('agentflow.token') }
})
onBeforeUnmount(() => { viewActive = false; autosave.dispose(); stopNodeDrag?.(); confirmation.dispose() })
onUnmounted(() => { unsubscribeWrites(); window.removeEventListener('beforeunload', warnBeforeUnload) })
</script>

<template>
  <div class="app" :inert="confirmationOpen || publicationOpen" @keydown="keyHandler">
    <section v-if="!loggedIn" class="login-screen">
      <form class="login-card" @submit.prevent="login">
        <div class="brand-mark">AF</div><p class="eyebrow">AGENTFLOW / WORKFLOW OS</p>
        <h1>让每一次审批<br /><em>都有依据。</em></h1><p class="login-copy">面向 OA、财务与业务团队的智能审批工作台。</p>
        <label>租户空间<input v-model="tenantId" autocomplete="organization" placeholder="demo" /></label>
        <label>用户名<input v-model="username" autocomplete="username" placeholder="admin" /></label>
        <label>密码<input v-model="password" autocomplete="current-password" type="password" placeholder="请输入密码" /></label>
        <button class="primary wide" :disabled="busy">{{ busy ? '正在登录…' : '进入工作台 ↗' }}</button>
        <small role="status">{{ notice || '演示租户 demo；账号 admin / manager / finance / alice，密码 demo' }}</small>
      </form>
    </section>
    <template v-else>
      <aside class="sidebar">
        <div class="brand"><div class="brand-mark">AF</div><div><strong>agentflow</strong><small>审批工作台</small></div></div>
        <div class="space-label">{{ tenantId }} WORKSPACE</div>
        <nav>
          <button :class="{ active: page === 'workbench' }" @click="page = 'workbench'"><b>◉</b><span>待我审批</span><i>{{ tasks.length }}</i></button>
          <button :class="{ active: page === 'started' }" @click="page = 'started'"><b>↗</b><span>我发起</span></button>
          <button :class="{ active: page === 'drafts' }" @click="page = 'drafts'"><b>▧</b><span>我的草稿</span></button>
          <button :class="{ active: page === 'handled' }" @click="page = 'handled'"><b>✓</b><span>已办记录</span></button>
          <button :class="{ active: page === 'applications' }" @click="page = 'applications'"><b>↗</b><span>申请记录</span></button>
          <div class="nav-divider"></div>
          <button :class="{ active: page === 'designer' }" @click="page = 'designer'"><b>⌘</b><span>流程管理</span></button>
          <button v-if="canManageDefinitions" :class="{ active: page === 'templates' }" @click="page = 'templates'"><b>▤</b><span>模板中心</span></button>
          <button disabled title="Agent 证据服务尚未接入"><b>✦</b><span>Agent 助理</span><small>未接入</small></button>
          <div class="nav-divider"></div>
          <button :class="{ active: page === 'expense' }" @click="page = 'expense'"><b>▣</b><span>费用报销</span></button>
          <button v-if="canInspectSystem" :class="{ active: page === 'system' }" @click="page = 'system'"><b>◈</b><span>系统自检</span></button>
        </nav>
        <div class="sidebar-bottom"><div class="online-dot" :class="{ offline: !serverAvailable }"></div><span>{{ serverAvailable ? '上次数据同步成功' : '上次数据同步失败' }}</span><button title="退出登录" aria-label="退出登录" :disabled="busy || pendingWrites.some(operation => operation.sending)" @click="logout">↪</button></div>
      </aside>
      <main ref="workspace" class="main" tabindex="-1">
        <header><div class="crumb">当前空间 <strong>/</strong> {{ page === 'started' ? '我发起' : page === 'drafts' ? '我的草稿' : page === 'handled' ? '已办记录' : page === 'system' ? '系统自检' : page === 'designer' ? '流程管理' : page === 'templates' ? '模板中心' : page === 'expense' ? '费用报销' : page === 'applications' ? '申请记录' : '审批工作台' }}</div><div class="header-actions"><button class="quiet" :disabled="busy" @click="refreshPage">刷新数据</button><div class="avatar">{{ username.slice(0, 1).toUpperCase() }}</div><span class="user-name">{{ username }}</span></div></header>
        <div v-if="notice" class="toast" role="status">{{ notice }}<button aria-label="关闭提示" @click="notice = ''">×</button></div>
        <div v-if="!newApplicationOpen && !recordApplicationId" class="recovery-container"><RequestRecovery :pending="visiblePendingWrites" :error="recoveryError" @recover="recoverOperation" /></div>
        <section v-if="page === 'workbench'" class="content">
          <div class="page-heading"><div><p class="eyebrow">{{ today }}</p><h2>今天，先处理重要的事。</h2><p class="subhead">当前有 <strong>{{ tasks.length }}</strong> 项可处理的审批任务。</p></div><button class="primary" @click="openApplicationForm">＋ 发起申请</button></div>
          <div class="metrics"><div><span class="metric-icon teal">◎</span><small>待我审批</small><strong>{{ tasks.length }}</strong><em>服务端实时数据</em></div><div><span class="metric-icon blue">↗</span><small>可访问的申请</small><strong>{{ applications.length }}</strong><em>按当前权限返回</em></div><div><span class="metric-icon amber">⌘</span><small>已发布流程</small><strong>{{ publishedDefinitions.length }}</strong><em>当前租户</em></div><div><span class="metric-icon purple">✦</span><small>Agent 预检</small><strong>—</strong><em>尚未接入</em></div></div>
          <div class="work-grid">
            <div class="queue panel"><div class="panel-head"><div><h3>待办队列 <span class="count">{{ visibleTasks.length }}</span></h3><p>你被指派或所在审批组可处理的任务</p></div></div><div class="queue-search"><input v-model="taskSearch" aria-label="搜索任务名称" placeholder="搜索任务名称" /></div>
              <div v-if="!visibleTasks.length" class="queue-empty"><strong>当前没有匹配任务</strong><p>刷新数据，或从已发布流程发起一项申请。</p></div>
              <button v-for="task in visibleTasks" :key="task.taskId" class="task-row" :disabled="busy || writesBlocked" :class="{ chosen: activeTask?.taskId === task.taskId }" @click="selectTask(task)"><span class="task-state teal-bg">◷</span><span class="task-main"><strong>{{ task.taskName }}</strong><small>{{ task.assignee ? `当前处理人：${task.assignee}` : '审批组待处理' }}</small></span><span class="task-side"><small>{{ dateLabel(task.createdAt) }}</small></span></button>
            </div>
            <div class="detail panel">
              <div v-if="activeTask" class="detail-body">
                <div class="detail-top"><div><span class="status-chip">● {{ activeApplication ? statusLabel(activeApplication.status) : '待处理' }}</span><h3>{{ activeApplication?.title ?? activeTask.taskName }}</h3><p>{{ activeApplication?.businessNo ?? activeTask.taskName }} · 任务创建于 {{ dateLabel(activeTask.createdAt) }}</p></div></div>
                <div class="tabs"><button v-for="tab in [{ key: 'detail', label: '申请详情' }, { key: 'timeline', label: '时间线' }, { key: 'audit', label: '审计记录' }]" :key="tab.key" :class="{ active: taskTab === tab.key }" @click="taskTab = tab.key as typeof taskTab">{{ tab.label }}</button></div>
                <div v-if="taskTab === 'detail'" class="detail-content">
                  <p v-if="detailError" class="inline-error">{{ detailError }}</p>
                  <template v-else-if="activeApplication"><div class="facts"><div><small>申请人</small><strong>{{ activeApplication.createdBy }}</strong></div><div><small>流程版本</small><strong>{{ activeApplication.processKey }} / v{{ activeApplication.definitionVersion }}</strong></div><div><small>当前任务</small><strong>{{ activeTask.taskName }}</strong></div><div><small>审批轮次</small><strong>第 {{ activeApplication.roundNo }} 轮</strong></div></div><FormFields :schema="activeApplication.formSchema" :model-value="activeApplication.payload" readonly /></template>
                  <p v-else class="unavailable">正在加载申请详情…</p>
                  <div class="agent-note"><span>✦</span><div><strong>Agent 证据尚未接入</strong><p>当前审批请以申请内容及线下核实结果为依据。</p></div></div>
                </div>
                <div v-else-if="taskTab === 'timeline'" class="timeline-full"><button class="secondary" @click="recordApplicationId = activeTask.applicationId">查看提交轮次与历史内容</button><ApplicationHistory :application-id="activeTask.applicationId" mode="timeline" :round-no-max="activeApplication?.roundNo ?? 1" :version="activeTask.version" /></div>
                <div v-else class="audit-list"><ApplicationHistory :application-id="activeTask.applicationId" mode="audit" :round-no-max="activeApplication?.roundNo ?? 1" :version="activeTask.version" /></div>
                <form v-if="pendingAction" class="task-action-form" @submit.prevent="performAction(pendingAction)">
                  <h4>{{ pendingAction === 'RETURN' ? '退回申请' : '转交任务' }}</h4>
                  <label v-if="pendingAction === 'TRANSFER'">接收人账号<input v-model="targetUser" :disabled="busy || writesBlocked" required placeholder="输入用户账号，如 finance" /></label>
                  <label>{{ pendingAction === 'RETURN' ? '退回原因（必填）' : '转交说明（选填）' }}<textarea v-model="actionComment" :disabled="busy || writesBlocked" :required="pendingAction === 'RETURN'" rows="3" /></label>
                  <div class="form-actions"><button type="button" class="secondary" :disabled="busy || writesBlocked" @click="pendingAction = null">取消</button><button class="primary" :disabled="busy || writesBlocked">{{ busy ? '提交中…' : '确认提交' }}</button></div>
                </form>
                <div v-else class="action-bar"><button class="secondary" :disabled="busy || writesBlocked" @click="prepareAction('TRANSFER')">转交</button><button class="return" :disabled="busy || writesBlocked" @click="prepareAction('RETURN')">退回</button><button class="primary" :disabled="busy || writesBlocked" @click="performAction('APPROVE')">批准申请 ↗</button></div>
              </div>
              <div v-else class="empty-detail"><div class="empty-icon">◎</div><h3>选择一项待办</h3><p>查看真实申请内容，完成批准、退回或转交。</p></div>
            </div>
          </div>
        </section>
        <section v-else-if="page === 'applications'" class="content">
          <div class="page-heading"><div><p class="eyebrow">APPLICATIONS</p><h2>申请记录</h2><p class="subhead">服务端按发起人、参与者与管理员权限返回申请。</p></div><button class="primary" @click="openApplicationForm">＋ 发起申请</button></div>
          <div class="panel"><div v-if="!applications.length" class="queue-empty"><strong>还没有可访问的申请</strong><p>选择已发布流程，填写申请并提交。</p></div><div v-for="application in applications" :key="application.id" class="expense-row"><span class="receipt-icon">▤</span><div><strong>{{ application.title }}</strong><small>{{ application.businessNo }} · {{ application.createdBy }} · {{ application.processKey }} v{{ application.definitionVersion }} · 第 {{ application.roundNo }} 轮</small></div><span class="status-chip">{{ statusLabel(application.status) }}</span><button class="secondary" :disabled="busy" @click="recordApplicationId = application.id">{{ application.createdBy === actor?.userId && ['DRAFT', 'RETURNED', 'WITHDRAWN'].includes(application.status) ? '查看并修改' : '查看详情' }}</button></div></div>
        </section>
        <WorkspaceRecords v-else-if="['started', 'drafts', 'handled'].includes(page)" :key="actorScope + ':' + page" :scope-key="actorScope" :mode="page === 'handled' ? 'handled' : page === 'drafts' ? 'drafts' : 'started'" :refresh-version="templateRefresh" :locked="busy || writesBlocked" @open="recordApplicationId = $event" @create="openApplicationForm" />
        <TemplateCenter v-else-if="page === 'templates' && canManageDefinitions" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" :locked="busy || writesBlocked" :has-unsaved-definition="!readonlyDefinition && dirty" @copy="copyTemplate" @open="openTemplateCopy" @return-designer="page = 'designer'" />
        <SystemChecks v-else-if="page === 'system' && canInspectSystem" :key="actorScope" :scope-key="actorScope" :refresh-version="templateRefresh" @templates="page = 'templates'" @designer="page = 'designer'" />
        <section v-else-if="page === 'designer'" class="designer-page" @compositionstart="composing = true" @compositionend="composing = false">
          <div class="designer-heading"><div><p class="eyebrow">PROCESS DEFINITION / {{ statusLabel(definitionStatus) }} {{ definitionVersion ? `V${definitionVersion}` : '' }}</p><h2>{{ definitionName }} <span v-if="dirty && !readonlyDefinition" class="draft-dot"></span></h2><p class="subhead">{{ readonlyDefinition ? '已发布定义只读；复制为新草稿后可继续编辑。' : !definitionId ? '尚未保存草稿。' : dirty ? '有未保存的修改；发布时会先保存当前内容。' : '当前草稿已保存。' }}</p></div></div><div class="designer-toolbar" aria-label="流程设计操作"><span class="toolbar-context">{{ readonlyDefinition ? '已发布版本' : !definitionId ? '尚未保存草稿' : dirty ? '有未保存修改' : '草稿已保存' }}</span><div class="designer-actions"><button class="secondary" :disabled="editorLocked || !history.length" aria-label="撤销" @click="undo">↶</button><button class="secondary" :disabled="editorLocked || !future.length" aria-label="重做" @click="redo">↷</button><button class="secondary" :disabled="busy || writesBlocked" @click="validate">校验流程</button><button v-if="canManageDefinitions" class="secondary" :disabled="busy || writesBlocked" :aria-expanded="simulationOpen" @click="openSimulation">{{ simulationOpen ? '收起模拟' : '模拟运行' }}</button><button v-if="canManageDefinitions" class="secondary" :disabled="busy || writesBlocked" :aria-expanded="comparisonOpen" @click="openComparison">{{ comparisonOpen ? '收起比较' : '版本比较' }}</button><template v-if="canManageDefinitions"><button v-if="readonlyDefinition" class="primary" :disabled="busy || writesBlocked" @click="newDefinition(true)">复制为新草稿</button><template v-else><button class="secondary" :disabled="busy || writesBlocked || draftConflict" @click="saveDraft">保存草稿</button><button class="primary" :disabled="busy || writesBlocked || draftConflict" @click="openPublication">{{ busy ? '处理中…' : '保存并发布 ↗' }}</button></template></template></div></div>
          <section v-if="canManageDefinitions && !readonlyDefinition" class="draft-save-status" aria-label="草稿保存状态">
            <label><input v-model="autosaveEnabled" type="checkbox" :disabled="busy || writesBlocked" />自动保存</label>
            <span role="status" aria-live="polite">{{ autosaveLabel }}<time v-if="autosave.savedAt && !dirty" :datetime="autosave.savedAt.toISOString()"> · {{ autosave.savedAt.toLocaleTimeString('zh-CN') }}</time></span>
            <div v-if="autosave.failure" class="draft-save-error" role="alert">
              <strong>{{ draftConflict ? '服务端版本已变化，本地修改已保留。' : '草稿未全部确认保存。' }}</strong>
              <p>{{ draftConflict ? '自动保存已暂停。可另存本地设计，或放弃本地修改并加载最新版本。' : autosave.failure.message }}</p>
              <div v-if="draftConflict" class="draft-conflict-actions"><button type="button" class="secondary" :disabled="busy || writesBlocked" @click="newDefinition(true)">另存为新草稿</button><button type="button" class="secondary" :disabled="busy || writesBlocked" @click="reloadConflictingDraft">加载服务端版本</button></div>
              <p v-else-if="!writesBlocked">修正后点击“保存草稿”，成功后恢复自动保存。</p>
            </div>
          </section>
          <div class="definition-switcher"><label>已保存流程<select v-model="selectedDefinitionId" :disabled="busy || writesBlocked" @change="chooseDefinition"><option value="">未保存草稿</option><option v-for="definition in definitions" :key="definition.id" :value="definition.id">{{ definition.name }} · {{ statusLabel(definition.status) }}{{ definition.version ? ` v${definition.version}` : '' }} · {{ definition.key }}</option></select></label><button v-if="canManageDefinitions" class="secondary" :disabled="busy || writesBlocked" @click="newDefinition()">＋ 新建流程</button></div>
          <div v-if="!canManageDefinitions" class="unavailable">当前账号只能查看流程。请使用流程管理员账号编辑和发布。</div>
          <fieldset class="definition-fields" :disabled="editorLocked || !canManageDefinitions"><label>流程标识<input v-model="definitionKey" :disabled="!!definitionId || autosave.saving" placeholder="如 expense-reimbursement" /></label><label>流程名称<input v-model="definitionName" /></label></fieldset>
          <div class="designer-layout">
            <aside class="palette"><h4>节点</h4><p>点击添加，再配置连线</p><button v-for="item in palette" :key="item.type" :disabled="editorLocked || !canManageDefinitions" :draggable="!editorLocked && canManageDefinitions" @dragstart="event => event.dataTransfer?.setData('node-type', item.type)" @click="addNode(item.type)"><span>{{ item.icon }}</span>{{ item.label }}<b>＋</b></button><div class="palette-tip"><strong>设计器提示</strong><p>选中节点可拖动。右侧配置审批组和下一节点；选中连线可编辑条件或删除。</p><p>支持角色审批组；额外复核组仅为模板示例，组织负责人解析尚未接入。</p></div></aside>
            <div class="canvas-wrap">
              <div class="canvas-toolbar"><span class="canvas-title" :title="definitionName">{{ definitionName }}</span><div class="canvas-tools" aria-label="画布视图操作">
                <button type="button" aria-label="缩小画布" title="缩小画布" :disabled="canvasZoom <= MIN_ZOOM" @click="changeCanvasZoom(-ZOOM_STEP)">−</button>
                <output aria-label="画布缩放比例">{{ Math.round(canvasZoom * 100) }}%</output>
                <button type="button" aria-label="放大画布" title="放大画布" :disabled="canvasZoom >= MAX_ZOOM" @click="changeCanvasZoom(ZOOM_STEP)">＋</button>
                <button type="button" @click="fitCanvas()">适应画布</button>
                <button v-if="canManageDefinitions" type="button" :disabled="editorLocked || !nodes.length" @click="autoLayout">自动布局</button>
              </div></div>
              <div ref="canvas" class="canvas" tabindex="0" aria-label="流程画布，可滚动查看节点与连线" @dragover.prevent @drop.prevent="onDrop">
                <div class="canvas-sizer" :style="{ width: `${stageSize.width * canvasZoom}px`, height: `${stageSize.height * canvasZoom}px` }">
                  <div class="canvas-stage" :style="{ width: `${stageSize.width}px`, height: `${stageSize.height}px`, transform: `scale(${canvasZoom})` }">
                    <svg class="edges" :viewBox="`0 0 ${stageSize.width} ${stageSize.height}`">
                      <defs><marker id="flow-arrow" markerWidth="7" markerHeight="7" refX="7" refY="3.5" orient="auto"><polygon points="0 0, 7 3.5, 0 7" fill="context-stroke" /></marker></defs>
                      <g v-for="route in routedEdges" :key="route.edge.id">
                        <path class="edge-hit" :d="route.path" @click.stop="selectEdge(route.edge)" />
                        <path :d="route.path" :data-edge-id="route.edge.id" marker-end="url(#flow-arrow)" :class="{ selected: selectedEdgeId === route.edge.id, simulated: simulationResult?.edgeIds.includes(route.edge.id) }" @click.stop="selectEdge(route.edge)" />
                        <text v-if="route.text" :x="route.label.x" :y="route.label.y" class="edge-label" @click.stop="selectEdge(route.edge)">{{ route.text }}<title>{{ route.fullText }}</title></text>
                      </g>
                    </svg>
                    <button v-for="node in nodes" :key="node.id" class="flow-node" :data-node-id="node.id" :class="[node.type === 'EXCLUSIVE_GATEWAY' ? 'condition' : node.type.toLowerCase(), { selected: selectedId === node.id, dragging: dragging === node.id, simulated: simulationResult?.path.includes(node.id) }]" :style="{ left: `${node.x}px`, top: `${node.y}px` }" @pointerdown="event => canManageDefinitions && moveNode(event, node)" @click.stop="selectNode(node)"><span class="node-icon">{{ node.type === 'EXCLUSIVE_GATEWAY' ? '◇' : node.type === 'START' ? '▶' : node.type === 'END' ? '●' : '人' }}</span><strong>{{ node.name }}</strong><small v-if="node.type === 'USER_TASK'">{{ roleLabel(node.assigneeRule) }}</small><i v-if="node.type !== 'END'" class="port"></i></button>
                  </div>
                </div>
              </div>
              <p class="canvas-hint" role="status">{{ routedEdges.some(route => route.obstructed) ? '部分节点或连线重叠，可手动调整节点或使用自动布局。' : canvasMessage }}</p>
            </div>
            <aside class="inspector"><fieldset :disabled="editorLocked || !canManageDefinitions">
              <template v-if="selectedNode"><div class="inspector-head"><div><p class="eyebrow">NODE PROPERTY</p><h3>{{ selectedNode.name }}</h3></div></div><label>节点名称<input v-model="selectedNode.name" @focus="remember" /></label><label>节点类型<input :value="selectedNode.type" disabled /></label><label v-if="selectedNode.type === 'USER_TASK'">审批组<select v-model="selectedNode.assigneeRule" @focus="remember"><option value="">请选择审批组</option><option v-for="role in roleOptions" :key="role.value" :value="role.value">{{ role.label }}</option><option v-if="selectedNode.assigneeRule && !roleOptions.some(role => role.value === selectedNode?.assigneeRule)" :value="selectedNode.assigneeRule">{{ selectedNode.assigneeRule }}（已有配置）</option></select></label>
                <div v-if="selectedNode.type === 'EXCLUSIVE_GATEWAY'" class="branch-editor"><strong>分支条件</strong><p class="field-help">例如 amount &gt; 5000。每个分支网关只有一条默认分支。</p><div v-for="edge in edges.filter(item => item.source === selectedNode?.id)" :key="edge.id" class="branch-item"><small>→ {{ nodes.find(node => node.id === edge.target)?.name }}</small><div class="branch"><input v-model="edge.condition" :disabled="edge.defaultBranch" :aria-label="`分支条件 ${edge.id}`" :placeholder="edge.defaultBranch ? '默认分支无需条件' : '如 amount > 5000'" @focus="remember" /><button :class="{ default: edge.defaultBranch }" type="button" @click="toggleDefault(edge)">{{ edge.defaultBranch ? '取消默认' : '设为默认' }}</button></div></div></div>
                <template v-if="selectedNode.type !== 'END'"><label>连线到<select v-model="connectionTarget"><option value="">选择下一节点</option><option v-for="node in nodes.filter(item => item.id !== selectedNode?.id && item.type !== 'START')" :key="node.id" :value="node.id">{{ node.name }}</option></select></label><button class="secondary connect-button" :disabled="!connectionTarget" @click="connectNode">添加连线</button></template><button class="delete-button" :disabled="selectedNode.type === 'START'" @click="deleteSelected">删除节点</button>
              </template>
              <template v-else-if="selectedEdge"><div class="inspector-head"><div><p class="eyebrow">EDGE PROPERTY</p><h3>连线条件</h3></div></div><label>条件<input v-model="selectedEdge.condition" :disabled="selectedEdge.defaultBranch" placeholder="如 amount > 5000" @focus="remember" /></label><button v-if="nodes.find(node => node.id === selectedEdge?.source)?.type === 'EXCLUSIVE_GATEWAY'" class="secondary" @click="toggleDefault(selectedEdge)">{{ selectedEdge.defaultBranch ? '取消默认分支' : '设为默认分支' }}</button><button class="delete-button" @click="deleteSelected">删除连线</button></template>
              <div v-else class="inspector-empty"><span>＋</span><h3>选择节点或连线</h3><p>在这里配置流程属性。</p></div>
            </fieldset></aside>
          </div>
          <DefinitionComparison v-if="comparisonOpen && canManageDefinitions" :input="comparisonInput" :definitions="definitions" :current-id="definitionId" :scope-key="actorScope + ':' + definitionId + ':' + definitionKey" :locked="busy || writesBlocked || confirmationOpen" @locate="locateComparisonChange" @close="comparisonOpen = false" />
          <DefinitionSimulation v-if="simulationOpen && canManageDefinitions" :graph="simulationGraph" :form-schema="definitionFormSchema" :scope-key="actorScope + ':' + definitionId + ':' + definitionKey" :locked="busy || writesBlocked || confirmationOpen" @result="simulationResult = $event" @locate="locateDesignTarget" @close="simulationOpen = false" />
          <DefinitionPublication v-if="readonlyDefinition && definitionId && canManageDefinitions" :definition-id="definitionId" :scope-key="actorScope" />
          <FormSchemaEditor v-model="definitionFormSchema" :disabled="editorLocked || !canManageDefinitions" @before-change="remember" />
          <div class="validation-strip" :class="{ invalid: validationErrors.length }"><span>●</span>{{ validationMessage }}<ul v-if="validationErrors.length"><li v-for="error in validationErrors" :key="error">{{ error }}</li></ul></div>
        </section>
        <section v-else class="content expense-page"><div class="page-heading"><div><p class="eyebrow">EXPENSE CONTROL</p><h2>费用报销</h2><p class="subhead">报销领域正在接入，当前可使用通用表单验证审批流程。</p></div><button class="primary" @click="openApplicationForm">＋ 发起表单审批</button></div><div class="expense-cards"><article v-for="item in [{ title: '报销填报', detail: '发票、费用明细和借款冲销尚未接入。' }, { title: '财务审核', detail: '费用标准、预算校验和核减尚未接入。' }, { title: '出纳付款', detail: '付款授权、银行回执和对账尚未接入。' }]" :key="item.title"><span class="card-kicker">{{ item.title }}</span><strong>待接入</strong><p>{{ item.detail }}</p></article></div><div class="panel queue-empty"><strong>暂无报销领域数据</strong><p>通用审批申请可在“申请记录”中查看；此处不展示演示单据或虚构金额。</p></div></section>
      </main>
      <ApplicationRecord v-if="recordApplicationId && actor" :key="recordApplicationId + ':' + recordRefresh" :application-id="recordApplicationId" :user-id="actor.userId" :pending-writes="pendingWrites" :recovery-error="recoveryError" @recover="recoverOperation" @close="recordApplicationId = ''" @changed="refreshPage()" />
      <div v-if="newApplicationOpen" class="modal-backdrop" @click.self="!busy && (newApplicationOpen = false)">
        <section class="modal" role="dialog" aria-modal="true" aria-labelledby="application-form-title" tabindex="-1">
          <div class="modal-heading"><div><p class="eyebrow">NEW APPLICATION</p><h2 id="application-form-title">发起表单审批</h2></div><button aria-label="关闭申请表单" :disabled="busy" @click="newApplicationOpen = false">×</button></div>
          <p v-if="!publishedDefinitions.length && !createdApplication" class="unavailable">当前没有已发布流程，请先由流程管理员创建并发布。</p>
          <RequestRecovery :pending="visiblePendingWrites" :error="recoveryError" @recover="recoverOperation" />
          <p v-if="applicationFormError" class="inline-error" role="alert">{{ applicationFormError }}</p>
          <form novalidate @submit.prevent="createAndSubmitApplication(true)">
            <fieldset :disabled="busy || writesBlocked || !!createdApplication">
              <label>已发布流程<select v-model="applicationDefinitionId"><option value="">请选择流程</option><option v-for="definition in publishedDefinitions" :key="definition.id" :value="definition.id">{{ definition.name }} · v{{ definition.version }} · {{ definition.key }}</option></select></label>
              <label>申请标题<input v-model="applicationTitle" maxlength="200" /></label><label>业务单号<input v-model="applicationBusinessNo" /></label>
              <FormFields v-if="applicationFormSchema" v-model="applicationPayload" :schema="applicationFormSchema" :disabled="busy || writesBlocked || !!createdApplication" :errors="applicationFieldErrors" />
              <template v-else><label>申请金额<input v-model="applicationAmount" type="number" min="0" step="0.01" /></label><label>申请说明<textarea v-model="applicationDescription" rows="3" /></label></template>
            </fieldset>
            <p v-if="createdApplication" class="unavailable">草稿 {{ createdApplication.businessNo }} 已保留。重试只会提交这张草稿；需要修改时请关闭后从申请记录打开。</p>
            <p v-else class="field-help">必填字段在提交时检查，未填完整也可先保存草稿。</p>
            <div class="form-actions"><button type="button" class="secondary" :disabled="busy" @click="newApplicationOpen = false">关闭</button><button v-if="!createdApplication" type="button" class="secondary" :disabled="busy || writesBlocked || !publishedDefinitions.length" @click="createAndSubmitApplication(false)">保存草稿</button><button class="primary" :disabled="busy || writesBlocked || (!createdApplication && !publishedDefinitions.length)">{{ busy ? '处理中…' : createdApplication ? '重试提交草稿' : '创建并提交' }}</button></div>
          </form>
        </section>
      </div>
    </template>
  </div>
  <UnsavedConfirmationDialog v-if="confirmation.active" :key="confirmation.active.id" :request="confirmation.active" :return-focus="confirmationReturnFocus" :fallback-focus="workspace" @answer="(id, accepted) => confirmation.answer(id, accepted)" />
  <PublicationDialog v-if="publicationOpen" v-model:note="publicationNote" :name="definitionName" :process-key="definitionKey" :busy="busy" :blocked="writesBlocked" :error="publicationError" :return-focus="publicationReturnFocus" :fallback-focus="workspace" @close="publicationOpen = false" @submit="publishDraft" />
</template>
