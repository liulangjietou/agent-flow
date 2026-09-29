<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { workspaceMenu, type WorkspacePage } from '../workspaceNavigation'

const props = defineProps<{ page: WorkspacePage; tenantId: string; username: string; canInspect: boolean; canManage: boolean; canCashier?: boolean; taskCount: number | null; serverAvailable: boolean; logoutDisabled: boolean }>()
const emit = defineEmits<{ 'update:page': [page: WorkspacePage]; logout: [] }>()
const compact = ref(false)
const opened = ref(false)
const panel = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const groups = computed(() => workspaceMenu.map(group => ({ ...group, items: group.items.filter(item => !item.access || (item.access === 'inspect' ? props.canInspect : item.access === 'cashier' ? props.canCashier : props.canManage)) })))
let media: MediaQueryList | undefined
let previousOverflow: string | null = null

function unlockScroll() {
  if (previousOverflow !== null) { document.body.style.overflow = previousOverflow; previousOverflow = null }
}
function close() {
  if (panel.value instanceof HTMLDialogElement && panel.value.open) panel.value.close()
  opened.value = false
  unlockScroll()
}
function open() {
  if (!(panel.value instanceof HTMLDialogElement) || panel.value.open) return
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  panel.value.showModal()
  opened.value = true
  closeButton.value?.focus()
}
async function resize() {
  const wasOpen = opened.value
  close()
  compact.value = media!.matches
  // 横竖屏或窗口切换时释放模态状态，将焦点交回仍可见的当前页面入口。
  if (wasOpen && !compact.value) {
    await nextTick()
    panel.value?.querySelector<HTMLButtonElement>('[aria-current="page"]')?.focus()
  }
}
function select(page: WorkspacePage) { close(); emit('update:page', page) }
function logout() { close(); emit('logout') }
function containTab(event: KeyboardEvent) {
  if (!compact.value || !opened.value || event.key !== 'Tab') return
  const controls = panel.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
  const first = controls?.[0], last = controls?.[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
function backdrop(event: MouseEvent) {
  const element = panel.value
  if (!compact.value || !element || event.target !== element) return
  const bounds = element.getBoundingClientRect()
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) close()
}
onMounted(() => {
  media = window.matchMedia('(max-width: 650px)')
  compact.value = media.matches
  media.addEventListener('change', resize)
})
onBeforeUnmount(() => { close(); media?.removeEventListener('change', resize) })
</script>

<template>
  <button class="mobile-menu-trigger" type="button" aria-label="打开工作空间导航" aria-haspopup="dialog" aria-controls="workspace-navigation" :aria-expanded="opened" @click="open"><span aria-hidden="true">☰</span>菜单</button>
  <component :is="compact ? 'dialog' : 'aside'" id="workspace-navigation" ref="panel" class="sidebar workspace-navigation" :aria-label="compact ? '工作空间导航' : '工作空间侧栏'" @cancel.prevent="close" @click="backdrop" @keydown.stop="containTab">
    <div class="navigation-brand"><div class="brand"><div class="brand-mark" aria-hidden="true">AF</div><div><strong>agentflow</strong><small>审批工作台</small></div></div><button v-if="compact" ref="closeButton" type="button" class="navigation-close" aria-label="关闭工作空间导航" @click="close">×</button></div>
    <div class="space-label">{{ tenantId }} WORKSPACE</div>
    <nav aria-label="工作空间页面">
      <section v-for="group in groups" :key="group.label" class="navigation-group" :aria-label="group.label">
        <h2 class="navigation-group-label">{{ group.label }}</h2>
        <button v-for="item in group.items" :key="item.label" type="button" :class="{ active: page === item.page }" :aria-label="item.label" :aria-current="page === item.page ? 'page' : undefined" :title="item.page ? item.label : 'Agent 证据服务尚未接入'" :disabled="!item.page" @click="item.page && select(item.page)"><b aria-hidden="true">{{ item.icon }}</b><span>{{ item.label }}</span><i v-if="item.page === 'workbench'" aria-hidden="true">{{ taskCount ?? '—' }}</i><small v-if="!item.page">未接入</small></button>
      </section>
    </nav>
    <div class="sidebar-bottom"><div class="online-dot" :class="{ offline: !serverAvailable }" aria-hidden="true"></div><span>{{ username }}<small>{{ serverAvailable ? '上次数据同步成功' : '上次数据同步失败' }}</small></span><button type="button" title="退出登录" aria-label="退出登录" :disabled="logoutDisabled" @click="logout">↪<span class="navigation-logout-text">退出</span></button></div>
  </component>
</template>

<style scoped>
.mobile-menu-trigger,.navigation-logout-text{display:none}.navigation-brand{display:flex;align-items:flex-start;justify-content:space-between;flex-shrink:0}.navigation-brand .brand{min-width:0}.navigation-group+.navigation-group{border-top:1px solid #294049;margin-top:18px;padding-top:14px}.navigation-group-label{font-size:10px;font-weight:500;color:#789396;letter-spacing:.06em;margin:4px 12px 9px}.sidebar-bottom>span{min-width:0;overflow-wrap:anywhere}.sidebar-bottom small{display:block;font-size:9px;color:#789396;margin-top:4px}.sidebar-bottom button{min-width:36px;min-height:44px}.workspace-navigation button:focus-visible{outline-color:#74d9cf;outline-offset:-3px}
@media(max-width:1050px) and (min-width:651px){.navigation-group-label{display:none}.workspace-navigation .brand{padding:0 6px 30px}.sidebar-bottom button{min-width:26px}}
@media(max-width:650px){
  .mobile-menu-trigger{display:flex;align-items:center;gap:6px;flex-shrink:0;min-height:44px;padding:8px 10px;border:1px solid var(--line);border-radius:8px;background:white;font-size:12px;font-weight:700}.mobile-menu-trigger>span{font-size:16px}
  dialog.workspace-navigation{width:min(320px,88vw);max-width:none;height:100dvh;max-height:100dvh;inset:0 auto 0 0;margin:0;padding:20px 16px;border:0;border-radius:0 18px 18px 0;color:#adc1c4;box-shadow:var(--shadow);overscroll-behavior:contain}
  dialog.workspace-navigation:not([open]){display:none}dialog.workspace-navigation[open]{display:flex}dialog.workspace-navigation::backdrop{background:rgba(12,30,39,.55)}
  .workspace-navigation .brand{padding:0 4px 22px;gap:10px}.workspace-navigation .brand>div:not(.brand-mark),.workspace-navigation .space-label,.workspace-navigation nav button span,.workspace-navigation nav button i,.workspace-navigation nav button small,.workspace-navigation .sidebar-bottom span{display:block}
  .workspace-navigation .space-label{overflow-wrap:anywhere;line-height:1.6}.workspace-navigation .brand-mark{width:38px;height:38px;flex-shrink:0}.navigation-close{min-width:44px;min-height:44px;color:#adc1c4;font-size:28px;margin-top:-7px}
  .workspace-navigation nav button{justify-content:flex-start;padding:12px;min-height:46px;gap:12px;font-size:13px}.workspace-navigation nav button b{width:18px;flex-shrink:0}.navigation-group-label{font-size:11px}.workspace-navigation .sidebar-bottom{padding-top:14px;gap:10px}.workspace-navigation .sidebar-bottom button{min-width:64px;display:flex;align-items:center;justify-content:center;gap:6px}.workspace-navigation .sidebar-bottom .navigation-logout-text{display:inline;font-size:12px}
}
</style>
