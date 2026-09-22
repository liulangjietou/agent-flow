<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, type Actor, type ApiError, type Application, type Definition, type Graph, type GraphEdge, type Task } from './api'

type Page = 'workbench' | 'designer' | 'applications' | 'expense'
type NodeType = 'START' | 'USER_TASK' | 'EXCLUSIVE_GATEWAY' | 'END'
interface FlowNode { id: string; name: string; type: string; x: number; y: number; assigneeRule: string }
const page = ref<Page>('workbench')
const loggedIn = ref(false)
const username = ref('')
const password = ref('')
const tenantId = ref('demo')
const actor = ref<Actor | null>(null)
const notice = ref('')
const busy = ref(false)
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
const selectedDefinitionId = ref('')
const savedSnapshot = ref('')
const validationMessage = ref('尚未校验，发布前将运行服务端校验。')
const validationErrors = ref<string[]>([])
const newApplicationOpen = ref(false)
const applicationDefinitionId = ref('')
const applicationTitle = ref('')
const applicationBusinessNo = ref('')
const applicationAmount = ref('')
const applicationDescription = ref('')
const createdApplication = ref<Application | null>(null)
const nodes = ref<FlowNode[]>([])
const edges = ref<GraphEdge[]>([])
const selectedId = ref('')
const selectedEdgeId = ref('')
const dragging = ref<string | null>(null)
const history = ref<string[]>([])
const future = ref<string[]>([])
const connectionTarget = ref('')
const canvas = ref<HTMLElement | null>(null)
const palette: Array<{ type: NodeType; label: string; icon: string }> = [
  { type: 'USER_TASK', label: '人工审批', icon: '人' },
  { type: 'EXCLUSIVE_GATEWAY', label: '条件分支', icon: '◇' },
  { type: 'END', label: '结束节点', icon: '●' }
]
const roleOptions = [{ value: 'role:MANAGER', label: '部门审批组' }, { value: 'role:FINANCE', label: '财务审批组' }]
const selectedNode = computed(() => nodes.value.find(node => node.id === selectedId.value) ?? null)
const selectedEdge = computed(() => edges.value.find(edge => edge.id === selectedEdgeId.value) ?? null)
const publishedDefinitions = computed(() => definitions.value.filter(definition => definition.status === 'PUBLISHED'))
const canManageDefinitions = computed(() => actor.value?.roles.some(role => ['PROCESS_ADMIN', 'ADMIN'].includes(role)) ?? false)
const readonlyDefinition = computed(() => definitionStatus.value !== 'DRAFT')
const visibleTasks = computed(() => tasks.value.filter(task => task.taskName.toLowerCase().includes(taskSearch.value.trim().toLowerCase())))
const dirty = computed(() => snapshot() !== savedSnapshot.value)
const today = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full' }).format(new Date())
const statusLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已拒绝', APPROVED: '已批准', REVOKED: '已撤销', CANCELLED: '已作废' }
const statusLabel = (status: string) => statusLabels[status] ?? status
const errorMessage = (error: unknown) => (error as ApiError)?.message ?? '无法连接服务，请稍后重试'
const roleLabel = (rule: string) => roleOptions.find(option => option.value === rule)?.label ?? (rule ? rule : '待配置')
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
const payloadValue = (value: unknown) => typeof value === 'object' ? JSON.stringify(value) : String(value ?? '—')

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
function snapshot() { return JSON.stringify({ key: definitionKey.value, name: definitionName.value, nodes: nodes.value, edges: edges.value }) }
function remember() { history.value.push(snapshot()); if (history.value.length > 50) history.value.shift(); future.value = [] }
function restore(raw: string) { const value = JSON.parse(raw); definitionKey.value = value.key; definitionName.value = value.name; nodes.value = value.nodes; edges.value = value.edges }
function undo() { if (readonlyDefinition.value) return; const value = history.value.pop(); if (value) { future.value.push(snapshot()); restore(value) } }
function redo() { if (readonlyDefinition.value) return; const value = future.value.pop(); if (value) { history.value.push(snapshot()); restore(value) } }
function resetEditor() { history.value = []; future.value = []; selectedEdgeId.value = ''; connectionTarget.value = ''; validationErrors.value = []; validationMessage.value = '尚未校验，发布前将运行服务端校验。' }
function graphPayload(): Graph {
  return {
    nodes: nodes.value.map(node => ({ id: node.id, name: node.name, type: node.type, properties: { ...(node.type === 'USER_TASK' && node.assigneeRule ? { assigneeRule: node.assigneeRule } : {}), x: String(node.x), y: String(node.y) } })),
    edges: edges.value.map(edge => ({ ...edge, condition: edge.defaultBranch ? '' : edge.condition.trim() }))
  }
}
function applyDefinition(definition: Definition) {
  definitionId.value = definition.id; selectedDefinitionId.value = definition.id
  definitionKey.value = definition.key; definitionName.value = definition.name
  definitionRevision.value = definition.revision; definitionVersion.value = definition.version; definitionStatus.value = definition.status
  nodes.value = definition.graph.nodes.map((node, index) => ({ id: node.id, name: node.name, type: node.type, x: Number(node.properties.x ?? 40 + index * 180), y: Number(node.properties.y ?? 180), assigneeRule: node.properties.assigneeRule ?? '' }))
  edges.value = definition.graph.edges.map(edge => ({ ...edge, defaultBranch: edge.defaultBranch ?? false }))
  selectedId.value = nodes.value[0]?.id ?? ''; resetEditor(); savedSnapshot.value = snapshot()
  localStorage.setItem(`agentflow.definition.${tenantId.value}`, definition.id)
}
function chooseDefinition() { const definition = definitions.value.find(item => item.id === selectedDefinitionId.value); if (definition) applyDefinition(definition) }
function newDefinition(copy = false) {
  if (!canManageDefinitions.value) return
  if (!copy) { defaultGraph(); definitionKey.value = ''; definitionName.value = '新审批流程' }
  definitionId.value = ''; selectedDefinitionId.value = ''; definitionRevision.value = 0; definitionVersion.value = 0; definitionStatus.value = 'DRAFT'; resetEditor(); savedSnapshot.value = ''
  notice.value = copy ? '已复制为新草稿，保存后可继续编辑并发布新版本。' : '填写流程标识和名称，完成设计后保存草稿。'
}
async function loadDefinitions(restoreSelection = false) {
  definitions.value = await api.definitions()
  if (restoreSelection) {
    const remembered = localStorage.getItem(`agentflow.definition.${tenantId.value}`)
    const definition = definitions.value.find(item => item.id === remembered) ?? definitions.value[0]
    if (definition) applyDefinition(definition)
  }
}
async function loadTasks() { tasks.value = await api.tasks() }
async function loadApplications() { applications.value = await api.applications() }
async function refreshWorkspace(restoreSelection = false) {
  try { await Promise.all([loadTasks(), loadApplications(), loadDefinitions(restoreSelection)]); serverAvailable.value = true }
  catch (error) { serverAvailable.value = false; notice.value = errorMessage(error) }
}
async function login() {
  if (busy.value) return
  if (!username.value.trim() || !password.value) { notice.value = '请输入用户名和密码'; return }
  busy.value = true
  try { const result = await api.login({ tenantId: tenantId.value.trim(), username: username.value.trim(), password: password.value }); localStorage.setItem('agentflow.token', result.token); actor.value = result.user; loggedIn.value = true; password.value = ''; notice.value = '已进入工作空间'; await refreshWorkspace(true) }
  catch (error) { notice.value = errorMessage(error) }
  finally { busy.value = false }
}
async function logout() {
  try { await api.logout() } catch { /* 本地会话始终清除，失效令牌由服务端校验。 */ }
  localStorage.removeItem('agentflow.token'); loggedIn.value = false; actor.value = null; serverAvailable.value = false; tasks.value = []; applications.value = []; definitions.value = []; activeTask.value = null; activeApplication.value = null; notice.value = ''; page.value = 'workbench'
}
async function selectTask(task: Task) {
  activeTask.value = task; activeApplication.value = null; detailError.value = ''; taskTab.value = 'detail'; pendingAction.value = null; actionComment.value = ''; targetUser.value = ''
  try { const application = await api.application(task.applicationId); if (activeTask.value?.taskId === task.taskId) activeApplication.value = application }
  catch (error) { detailError.value = errorMessage(error) }
}
function prepareAction(action: 'RETURN' | 'TRANSFER') { pendingAction.value = action; actionComment.value = ''; targetUser.value = '' }
async function performAction(action: 'APPROVE' | 'RETURN' | 'TRANSFER') {
  if (!activeTask.value || busy.value) return
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
async function validateGraph() {
  const result = await api.validateDefinition(graphPayload()); validationErrors.value = result.errors
  validationMessage.value = result.errors.length ? `服务端校验发现 ${result.errors.length} 项问题。` : '服务端校验通过。'
  return result.errors.length === 0
}
async function validate() { if (busy.value) return; busy.value = true; try { await validateGraph() } catch (error) { validationMessage.value = '校验请求失败，请重试。'; notice.value = errorMessage(error) } finally { busy.value = false } }
async function persistDraft(): Promise<Definition> {
  if (readonlyDefinition.value) throw new Error('已发布定义只读，请先复制为新草稿')
  if (!definitionKey.value.trim() || !definitionName.value.trim()) throw new Error('请填写流程标识和名称')
  const definition = definitionId.value
    ? await api.updateDefinition(definitionId.value, { name: definitionName.value.trim(), graph: graphPayload(), expectedRevision: definitionRevision.value })
    : await api.definition({ key: definitionKey.value.trim(), name: definitionName.value.trim(), graph: graphPayload() })
  applyDefinition(definition); await loadDefinitions(); return definition
}
async function saveDraft() {
  if (busy.value) return; busy.value = true
  try { await persistDraft(); notice.value = '流程草稿已保存' } catch (error) { notice.value = errorMessage(error) } finally { busy.value = false }
}
async function publishDraft() {
  if (busy.value || readonlyDefinition.value) return; busy.value = true
  try {
    if (!await validateGraph()) { notice.value = '请先修复服务端校验问题'; return }
    const saved = await persistDraft()
    const published = await api.publishDefinition(saved.id, saved.revision)
    applyDefinition(published); await loadDefinitions(); notice.value = `流程${statusLabel(published.status)}，版本 v${published.version}`
  } catch (error) { notice.value = errorMessage(error) } finally { busy.value = false }
}
function selectNode(node: FlowNode) { selectedId.value = node.id; selectedEdgeId.value = ''; connectionTarget.value = '' }
function selectEdge(edge: GraphEdge) { selectedEdgeId.value = edge.id; selectedId.value = '' }
function moveNode(event: MouseEvent, node: FlowNode) {
  if (readonlyDefinition.value || event.button !== 0) return
  remember(); dragging.value = node.id; const startX = event.clientX; const startY = event.clientY; const x = node.x; const y = node.y
  const move = (next: MouseEvent) => { node.x = Math.max(12, Math.round((x + next.clientX - startX) / 9) * 9); node.y = Math.max(20, Math.round((y + next.clientY - startY) / 9) * 9) }
  const up = () => { dragging.value = null; window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up) }
  window.addEventListener('mousemove', move); window.addEventListener('mouseup', up)
}
function addNode(type: NodeType) {
  if (readonlyDefinition.value) return
  remember(); const base = selectedNode.value; const id = `${type.toLowerCase()}-${crypto.randomUUID()}`
  nodes.value.push({ id, name: palette.find(item => item.type === type)?.label ?? '节点', type, x: (base?.x ?? 160) + 180, y: (base?.y ?? 140) + 80, assigneeRule: '' })
  if (base && base.type !== 'END' && base.type !== 'EXCLUSIVE_GATEWAY') {
    const old = edges.value.find(edge => edge.source === base.id)
    if (old) old.source = id
    edges.value.push({ id: `edge-${crypto.randomUUID()}`, source: base.id, target: id, condition: '', defaultBranch: false })
  }
  selectedId.value = id; selectedEdgeId.value = ''
}
function connectNode() {
  const node = selectedNode.value
  if (readonlyDefinition.value || !node || node.type === 'END' || !connectionTarget.value || edges.value.some(edge => edge.source === node.id && edge.target === connectionTarget.value)) return
  remember()
  if (node.type !== 'EXCLUSIVE_GATEWAY') edges.value = edges.value.filter(edge => edge.source !== node.id)
  edges.value.push({ id: `edge-${crypto.randomUUID()}`, source: node.id, target: connectionTarget.value, condition: '', defaultBranch: false }); connectionTarget.value = ''
}
function toggleDefault(edge: GraphEdge) {
  if (readonlyDefinition.value) return
  remember(); const enable = !edge.defaultBranch
  edges.value.filter(item => item.source === edge.source).forEach(item => { item.defaultBranch = enable && item.id === edge.id })
  if (enable) edge.condition = ''
}
function deleteSelected() {
  if (readonlyDefinition.value) return
  if (selectedNode.value && selectedNode.value.type !== 'START') { remember(); const id = selectedNode.value.id; nodes.value = nodes.value.filter(node => node.id !== id); edges.value = edges.value.filter(edge => edge.source !== id && edge.target !== id); selectedId.value = '' }
  else if (selectedEdge.value) { remember(); edges.value = edges.value.filter(edge => edge.id !== selectedEdgeId.value); selectedEdgeId.value = '' }
}
function onDrop(event: DragEvent) { const type = event.dataTransfer?.getData('node-type') as NodeType; if (palette.some(item => item.type === type)) addNode(type) }
function edgePath(edge: GraphEdge) {
  const a = nodes.value.find(node => node.id === edge.source); const b = nodes.value.find(node => node.id === edge.target)
  if (!a || !b) return ''
  const small = ['START', 'END'].includes(a.type); const ax = a.x + (small ? 67 : 126); const ay = a.y + (small ? 22 : 32); const bx = b.x; const by = b.y + (['START', 'END'].includes(b.type) ? 22 : 32); const mx = (ax + bx) / 2
  return `M ${ax} ${ay} L ${mx} ${ay} L ${mx} ${by} L ${bx} ${by}`
}
function keyHandler(event: KeyboardEvent) {
  if (page.value !== 'designer' || readonlyDefinition.value || ['INPUT', 'TEXTAREA', 'SELECT'].includes((event.target as HTMLElement).tagName)) return
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'z') { event.preventDefault(); event.shiftKey ? redo() : undo() }
  if (['Delete', 'Backspace'].includes(event.key)) { event.preventDefault(); deleteSelected() }
}
async function openApplicationForm() {
  try { await loadDefinitions(); applicationDefinitionId.value = publishedDefinitions.value[0]?.id ?? ''; applicationTitle.value = ''; applicationBusinessNo.value = `APP-${Date.now()}`; applicationAmount.value = ''; applicationDescription.value = ''; createdApplication.value = null; newApplicationOpen.value = true }
  catch (error) { notice.value = errorMessage(error) }
}
async function createAndSubmitApplication() {
  if (busy.value) return
  const definition = publishedDefinitions.value.find(item => item.id === applicationDefinitionId.value)
  if (!definition || !applicationTitle.value.trim() || !applicationBusinessNo.value.trim()) { notice.value = '请选择已发布流程，并填写申请标题和业务单号'; return }
  if (!applicationAmount.value || !Number.isFinite(Number(applicationAmount.value)) || Number(applicationAmount.value) < 0) { notice.value = '请输入不小于零的申请金额'; return }
  busy.value = true
  try {
    if (!createdApplication.value) createdApplication.value = await api.createApplication({ businessNo: applicationBusinessNo.value.trim(), processKey: definition.key, definitionVersion: definition.version, title: applicationTitle.value.trim(), payload: { amount: Number(applicationAmount.value), description: applicationDescription.value.trim() } })
    const submitted = await api.submitApplication(createdApplication.value.id, createdApplication.value.version)
    newApplicationOpen.value = false; createdApplication.value = null; page.value = 'applications'; await refreshWorkspace(); notice.value = `申请 ${submitted.businessNo} 已提交，状态：${statusLabel(submitted.status)}`
  } catch (error) { notice.value = `${errorMessage(error)}${createdApplication.value ? '；草稿已保留，可重试提交。' : ''}` }
  finally { busy.value = false }
}
async function submitExisting(application: Application) {
  if (busy.value) return; busy.value = true
  try { const submitted = await api.submitApplication(application.id, application.version); await refreshWorkspace(); notice.value = `${submitted.businessNo} 已提交` } catch (error) { notice.value = errorMessage(error) } finally { busy.value = false }
}
watch([nodes, edges, definitionName], () => { validationErrors.value = []; validationMessage.value = '内容已修改，请重新校验。' }, { deep: true, flush: 'sync' })
defaultGraph(); savedSnapshot.value = snapshot()
onMounted(async () => {
  if (!localStorage.getItem('agentflow.token')) return
  try { const result = await api.me(); actor.value = result.actor; username.value = result.actor.userId; tenantId.value = result.actor.tenantId; loggedIn.value = true; await refreshWorkspace(true) }
  catch { localStorage.removeItem('agentflow.token') }
})
</script>

<template>
  <div class="app" @keydown="keyHandler">
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
          <button :class="{ active: page === 'applications' }" @click="page = 'applications'"><b>↗</b><span>申请记录</span></button>
          <div class="nav-divider"></div>
          <button :class="{ active: page === 'designer' }" @click="page = 'designer'"><b>⌘</b><span>流程管理</span></button>
          <button disabled title="Agent 证据服务尚未接入"><b>✦</b><span>Agent 助理</span><small>未接入</small></button>
          <div class="nav-divider"></div>
          <button :class="{ active: page === 'expense' }" @click="page = 'expense'"><b>▣</b><span>费用报销</span></button>
        </nav>
        <div class="sidebar-bottom"><div class="online-dot" :class="{ offline: !serverAvailable }"></div><span>{{ serverAvailable ? '服务连接正常' : '服务连接异常' }}</span><button title="退出登录" aria-label="退出登录" @click="logout">↪</button></div>
      </aside>
      <main class="main">
        <header><div class="crumb">当前空间 <strong>/</strong> {{ page === 'designer' ? '流程管理' : page === 'expense' ? '费用报销' : page === 'applications' ? '申请记录' : '审批工作台' }}</div><div class="header-actions"><button class="quiet" :disabled="busy" @click="refreshWorkspace()">刷新数据</button><div class="avatar">{{ username.slice(0, 1).toUpperCase() }}</div><span class="user-name">{{ username }}</span></div></header>
        <div v-if="notice" class="toast" role="status">{{ notice }}<button aria-label="关闭提示" @click="notice = ''">×</button></div>
        <section v-if="page === 'workbench'" class="content">
          <div class="page-heading"><div><p class="eyebrow">{{ today }}</p><h2>今天，先处理重要的事。</h2><p class="subhead">当前有 <strong>{{ tasks.length }}</strong> 项可处理的审批任务。</p></div><button class="primary" @click="openApplicationForm">＋ 发起申请</button></div>
          <div class="metrics"><div><span class="metric-icon teal">◎</span><small>待我审批</small><strong>{{ tasks.length }}</strong><em>服务端实时数据</em></div><div><span class="metric-icon blue">↗</span><small>可访问的申请</small><strong>{{ applications.length }}</strong><em>按当前权限返回</em></div><div><span class="metric-icon amber">⌘</span><small>已发布流程</small><strong>{{ publishedDefinitions.length }}</strong><em>当前租户</em></div><div><span class="metric-icon purple">✦</span><small>Agent 预检</small><strong>—</strong><em>尚未接入</em></div></div>
          <div class="work-grid">
            <div class="queue panel"><div class="panel-head"><div><h3>待办队列 <span class="count">{{ visibleTasks.length }}</span></h3><p>你被指派或所在审批组可处理的任务</p></div></div><div class="queue-search"><input v-model="taskSearch" aria-label="搜索任务名称" placeholder="搜索任务名称" /></div>
              <div v-if="!visibleTasks.length" class="queue-empty"><strong>当前没有匹配任务</strong><p>刷新数据，或从已发布流程发起一项申请。</p></div>
              <button v-for="task in visibleTasks" :key="task.taskId" class="task-row" :class="{ chosen: activeTask?.taskId === task.taskId }" @click="selectTask(task)"><span class="task-state teal-bg">◷</span><span class="task-main"><strong>{{ task.taskName }}</strong><small>{{ task.assignee ? `当前处理人：${task.assignee}` : '审批组待处理' }}</small></span><span class="task-side"><small>{{ dateLabel(task.createdAt) }}</small></span></button>
            </div>
            <div class="detail panel">
              <div v-if="activeTask" class="detail-body">
                <div class="detail-top"><div><span class="status-chip">● {{ activeApplication ? statusLabel(activeApplication.status) : '待处理' }}</span><h3>{{ activeApplication?.title ?? activeTask.taskName }}</h3><p>{{ activeApplication?.businessNo ?? activeTask.taskName }} · 任务创建于 {{ dateLabel(activeTask.createdAt) }}</p></div></div>
                <div class="tabs"><button v-for="tab in [{ key: 'detail', label: '申请详情' }, { key: 'timeline', label: '时间线' }, { key: 'audit', label: '审计记录' }]" :key="tab.key" :class="{ active: taskTab === tab.key }" @click="taskTab = tab.key as typeof taskTab">{{ tab.label }}</button></div>
                <div v-if="taskTab === 'detail'" class="detail-content">
                  <p v-if="detailError" class="inline-error">{{ detailError }}</p>
                  <template v-else-if="activeApplication"><div class="facts"><div><small>申请人</small><strong>{{ activeApplication.createdBy }}</strong></div><div><small>流程版本</small><strong>{{ activeApplication.processKey }} / v{{ activeApplication.definitionVersion }}</strong></div><div><small>当前任务</small><strong>{{ activeTask.taskName }}</strong></div><div><small>审批轮次</small><strong>第 {{ activeApplication.roundNo }} 轮</strong></div></div><dl class="payload-list"><template v-for="(value, key) in activeApplication.payload" :key="key"><dt>{{ key === 'amount' ? '申请金额' : key === 'description' ? '申请说明' : key }}</dt><dd>{{ payloadValue(value) }}</dd></template></dl></template>
                  <p v-else class="unavailable">正在加载申请详情…</p>
                  <div class="agent-note"><span>✦</span><div><strong>Agent 证据尚未接入</strong><p>当前审批请以申请内容及线下核实结果为依据。</p></div></div>
                </div>
                <div v-else-if="taskTab === 'timeline'" class="timeline-full"><p class="unavailable">完整审批时间线尚未接入。当前任务创建时间：{{ dateLabel(activeTask.createdAt) }}。</p></div>
                <div v-else class="audit-list"><p>审计查询尚未接入。</p><code>当前任务版本：{{ activeTask.version }}</code><code>任务标识：{{ activeTask.taskId }}</code></div>
                <form v-if="pendingAction" class="task-action-form" @submit.prevent="performAction(pendingAction)">
                  <h4>{{ pendingAction === 'RETURN' ? '退回申请' : '转交任务' }}</h4>
                  <label v-if="pendingAction === 'TRANSFER'">接收人账号<input v-model="targetUser" required placeholder="输入用户账号，如 finance" /></label>
                  <label>{{ pendingAction === 'RETURN' ? '退回原因（必填）' : '转交说明（选填）' }}<textarea v-model="actionComment" :required="pendingAction === 'RETURN'" rows="3" /></label>
                  <div class="form-actions"><button type="button" class="secondary" @click="pendingAction = null">取消</button><button class="primary" :disabled="busy">{{ busy ? '提交中…' : '确认提交' }}</button></div>
                </form>
                <div v-else class="action-bar"><button class="secondary" :disabled="busy" @click="prepareAction('TRANSFER')">转交</button><button class="return" :disabled="busy" @click="prepareAction('RETURN')">退回</button><button class="primary" :disabled="busy" @click="performAction('APPROVE')">批准申请 ↗</button></div>
              </div>
              <div v-else class="empty-detail"><div class="empty-icon">◎</div><h3>选择一项待办</h3><p>查看真实申请内容，完成批准、退回或转交。</p></div>
            </div>
          </div>
        </section>
        <section v-else-if="page === 'applications'" class="content">
          <div class="page-heading"><div><p class="eyebrow">APPLICATIONS</p><h2>申请记录</h2><p class="subhead">服务端按发起人、参与者与管理员权限返回申请。</p></div><button class="primary" @click="openApplicationForm">＋ 发起申请</button></div>
          <div class="panel"><div v-if="!applications.length" class="queue-empty"><strong>还没有可访问的申请</strong><p>选择已发布流程，填写申请并提交。</p></div><div v-for="application in applications" :key="application.id" class="expense-row"><span class="receipt-icon">▤</span><div><strong>{{ application.title }}</strong><small>{{ application.businessNo }} · {{ application.createdBy }} · {{ application.processKey }} v{{ application.definitionVersion }}</small></div><span class="status-chip">{{ statusLabel(application.status) }}</span><button v-if="application.createdBy === username && ['DRAFT', 'RETURNED', 'WITHDRAWN'].includes(application.status)" class="secondary" :disabled="busy" @click="submitExisting(application)">提交申请</button></div></div>
        </section>
        <section v-else-if="page === 'designer'" class="designer-page">
          <div class="designer-heading"><div><p class="eyebrow">PROCESS DEFINITION / {{ statusLabel(definitionStatus) }} {{ definitionVersion ? `V${definitionVersion}` : '' }}</p><h2>{{ definitionName }} <span v-if="dirty && !readonlyDefinition" class="draft-dot"></span></h2><p class="subhead">{{ readonlyDefinition ? '已发布定义只读；复制为新草稿后可继续编辑。' : dirty ? '有未保存的修改；发布时会先保存当前内容。' : '当前草稿已保存。' }}</p></div><div class="designer-actions"><button class="secondary" :disabled="busy || !history.length || readonlyDefinition" aria-label="撤销" @click="undo">↶</button><button class="secondary" :disabled="busy || !future.length || readonlyDefinition" aria-label="重做" @click="redo">↷</button><button class="secondary" :disabled="busy" @click="validate">校验流程</button><template v-if="canManageDefinitions"><button v-if="readonlyDefinition" class="primary" :disabled="busy" @click="newDefinition(true)">复制为新草稿</button><template v-else><button class="secondary" :disabled="busy" @click="saveDraft">保存草稿</button><button class="primary" :disabled="busy" @click="publishDraft">{{ busy ? '处理中…' : '保存并发布 ↗' }}</button></template></template></div></div>
          <div class="definition-switcher"><label>已保存流程<select v-model="selectedDefinitionId" @change="chooseDefinition"><option value="">未保存草稿</option><option v-for="definition in definitions" :key="definition.id" :value="definition.id">{{ definition.name }} · {{ statusLabel(definition.status) }}{{ definition.version ? ` v${definition.version}` : '' }} · {{ definition.key }}</option></select></label><button v-if="canManageDefinitions" class="secondary" :disabled="busy" @click="newDefinition()">＋ 新建流程</button></div>
          <div v-if="!canManageDefinitions" class="unavailable">当前账号只能查看流程。请使用流程管理员账号编辑和发布。</div>
          <fieldset class="definition-fields" :disabled="readonlyDefinition || !canManageDefinitions || busy"><label>流程标识<input v-model="definitionKey" :disabled="!!definitionId" placeholder="如 expense-reimbursement" /></label><label>流程名称<input v-model="definitionName" /></label></fieldset>
          <div class="designer-layout">
            <aside class="palette"><h4>节点</h4><p>点击添加，再配置连线</p><button v-for="item in palette" :key="item.type" :disabled="readonlyDefinition || !canManageDefinitions" :draggable="!readonlyDefinition && canManageDefinitions" @dragstart="event => event.dataTransfer?.setData('node-type', item.type)" @click="addNode(item.type)"><span>{{ item.icon }}</span>{{ item.label }}<b>＋</b></button><div class="palette-tip"><strong>设计器提示</strong><p>选中节点可拖动。右侧配置审批组和下一节点；选中连线可编辑条件或删除。</p><p>当前支持部门审批组和财务审批组，组织负责人解析尚未接入。</p></div></aside>
            <div class="canvas-wrap"><div class="canvas-toolbar"><span>{{ definitionName }}</span><span>滚动画布查看全部节点</span></div><div ref="canvas" class="canvas" tabindex="0" @dragover.prevent @drop="onDrop"><svg class="edges" viewBox="0 0 1600 900"><path v-for="edge in edges" :key="edge.id" :d="edgePath(edge)" :class="{ selected: selectedEdgeId === edge.id }" @click.stop="selectEdge(edge)" /><text v-for="edge in edges.filter(item => item.condition || item.defaultBranch)" :key="`${edge.id}-label`" :x="((nodes.find(node => node.id === edge.source)?.x ?? 0) + (nodes.find(node => node.id === edge.target)?.x ?? 0)) / 2 + 30" :y="(nodes.find(node => node.id === edge.target)?.y ?? 0) - 8" class="edge-label">{{ edge.defaultBranch ? '默认分支' : edge.condition }}</text></svg><button v-for="node in nodes" :key="node.id" class="flow-node" :class="[node.type === 'EXCLUSIVE_GATEWAY' ? 'condition' : node.type.toLowerCase(), { selected: selectedId === node.id, dragging: dragging === node.id }]" :style="{ left: `${node.x}px`, top: `${node.y}px` }" @mousedown="event => canManageDefinitions && moveNode(event, node)" @click.stop="selectNode(node)"><span class="node-icon">{{ node.type === 'EXCLUSIVE_GATEWAY' ? '◇' : node.type === 'START' ? '▶' : node.type === 'END' ? '●' : '人' }}</span><strong>{{ node.name }}</strong><small v-if="node.type === 'USER_TASK'">{{ roleLabel(node.assigneeRule) }}</small><i v-if="node.type !== 'END'" class="port"></i></button></div></div>
            <aside class="inspector"><fieldset :disabled="readonlyDefinition || !canManageDefinitions || busy">
              <template v-if="selectedNode"><div class="inspector-head"><div><p class="eyebrow">NODE PROPERTY</p><h3>{{ selectedNode.name }}</h3></div></div><label>节点名称<input v-model="selectedNode.name" @focus="remember" /></label><label>节点类型<input :value="selectedNode.type" disabled /></label><label v-if="selectedNode.type === 'USER_TASK'">审批组<select v-model="selectedNode.assigneeRule" @focus="remember"><option value="">请选择审批组</option><option v-for="role in roleOptions" :key="role.value" :value="role.value">{{ role.label }}</option><option v-if="selectedNode.assigneeRule && !roleOptions.some(role => role.value === selectedNode?.assigneeRule)" :value="selectedNode.assigneeRule">{{ selectedNode.assigneeRule }}（已有配置）</option></select></label>
                <div v-if="selectedNode.type === 'EXCLUSIVE_GATEWAY'" class="branch-editor"><strong>分支条件</strong><p class="field-help">例如 amount &gt; 5000。每个分支网关只有一条默认分支。</p><div v-for="edge in edges.filter(item => item.source === selectedNode?.id)" :key="edge.id" class="branch-item"><small>→ {{ nodes.find(node => node.id === edge.target)?.name }}</small><div class="branch"><input v-model="edge.condition" :disabled="edge.defaultBranch" :aria-label="`分支条件 ${edge.id}`" :placeholder="edge.defaultBranch ? '默认分支无需条件' : '如 amount > 5000'" @focus="remember" /><button :class="{ default: edge.defaultBranch }" type="button" @click="toggleDefault(edge)">{{ edge.defaultBranch ? '取消默认' : '设为默认' }}</button></div></div></div>
                <template v-if="selectedNode.type !== 'END'"><label>连线到<select v-model="connectionTarget"><option value="">选择下一节点</option><option v-for="node in nodes.filter(item => item.id !== selectedNode?.id && item.type !== 'START')" :key="node.id" :value="node.id">{{ node.name }}</option></select></label><button class="secondary connect-button" :disabled="!connectionTarget" @click="connectNode">添加连线</button></template><button class="delete-button" :disabled="selectedNode.type === 'START'" @click="deleteSelected">删除节点</button>
              </template>
              <template v-else-if="selectedEdge"><div class="inspector-head"><div><p class="eyebrow">EDGE PROPERTY</p><h3>连线条件</h3></div></div><label>条件<input v-model="selectedEdge.condition" :disabled="selectedEdge.defaultBranch" placeholder="如 amount > 5000" @focus="remember" /></label><button v-if="nodes.find(node => node.id === selectedEdge?.source)?.type === 'EXCLUSIVE_GATEWAY'" class="secondary" @click="toggleDefault(selectedEdge)">{{ selectedEdge.defaultBranch ? '取消默认分支' : '设为默认分支' }}</button><button class="delete-button" @click="deleteSelected">删除连线</button></template>
              <div v-else class="inspector-empty"><span>＋</span><h3>选择节点或连线</h3><p>在这里配置流程属性。</p></div>
            </fieldset></aside>
          </div>
          <div class="validation-strip" :class="{ invalid: validationErrors.length }"><span>●</span>{{ validationMessage }}<ul v-if="validationErrors.length"><li v-for="error in validationErrors" :key="error">{{ error }}</li></ul></div>
        </section>
        <section v-else class="content expense-page"><div class="page-heading"><div><p class="eyebrow">EXPENSE CONTROL</p><h2>费用报销</h2><p class="subhead">报销领域正在接入，当前可使用通用表单验证审批流程。</p></div><button class="primary" @click="openApplicationForm">＋ 发起表单审批</button></div><div class="expense-cards"><article v-for="item in [{ title: '报销填报', detail: '发票、费用明细和借款冲销尚未接入。' }, { title: '财务审核', detail: '费用标准、预算校验和核减尚未接入。' }, { title: '出纳付款', detail: '付款授权、银行回执和对账尚未接入。' }]" :key="item.title"><span class="card-kicker">{{ item.title }}</span><strong>待接入</strong><p>{{ item.detail }}</p></article></div><div class="panel queue-empty"><strong>暂无报销领域数据</strong><p>通用审批申请可在“申请记录”中查看；此处不展示演示单据或虚构金额。</p></div></section>
      </main>
      <div v-if="newApplicationOpen" class="modal-backdrop" @click.self="!busy && (newApplicationOpen = false)"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="application-form-title"><div class="modal-heading"><div><p class="eyebrow">NEW APPLICATION</p><h2 id="application-form-title">发起表单审批</h2></div><button aria-label="关闭申请表单" :disabled="busy" @click="newApplicationOpen = false">×</button></div><p v-if="!publishedDefinitions.length" class="unavailable">当前没有已发布流程，请先由流程管理员创建并发布。</p><form @submit.prevent="createAndSubmitApplication"><fieldset :disabled="busy || !!createdApplication"><label>已发布流程<select v-model="applicationDefinitionId" required><option value="">请选择流程</option><option v-for="definition in publishedDefinitions" :key="definition.id" :value="definition.id">{{ definition.name }} · v{{ definition.version }} · {{ definition.key }}</option></select></label><label>申请标题<input v-model="applicationTitle" required maxlength="200" /></label><label>业务单号<input v-model="applicationBusinessNo" required /></label><label>申请金额<input v-model="applicationAmount" type="number" min="0" step="0.01" required /></label><label>申请说明<textarea v-model="applicationDescription" rows="3" /></label></fieldset><p v-if="createdApplication" class="unavailable">草稿 {{ createdApplication.businessNo }} 已保留。重试只会提交这张草稿。</p><div class="form-actions"><button type="button" class="secondary" :disabled="busy" @click="newApplicationOpen = false">关闭</button><button class="primary" :disabled="busy || !publishedDefinitions.length">{{ busy ? '提交中…' : createdApplication ? '重试提交草稿' : '创建并提交' }}</button></div></form></section></div>
    </template>
  </div>
</template>
