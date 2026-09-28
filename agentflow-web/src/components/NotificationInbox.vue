<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api, type InboxMessage } from '../api'
import { isTaskNotification, notificationLabels, NotificationInboxQuery } from '../notificationInbox'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ read: [message: InboxMessage]; open: [message: InboxMessage] }>()
const readFilter = ref<'all' | 'unread'>('all')
const query = reactive(new NotificationInboxQuery(api.inbox))
function refresh() { void query.load(props.scopeKey, readFilter.value) }
watch([() => props.scopeKey, () => props.refreshVersion, readFilter], refresh, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>

<template>
  <section class="content inbox-page" aria-labelledby="inbox-title">
    <div class="inbox-heading"><div><p class="eyebrow">NOTIFICATIONS</p><h2 id="inbox-title">消息中心</h2><p class="inbox-intro">跟进审批与任务流转，回到申请继续处理。</p></div><span v-if="query.loaded" class="unread-total" role="status">{{ query.unreadCount }} 条未读</span></div>
    <div class="inbox-toolbar"><div role="group" aria-label="消息筛选"><button :class="{ selected: readFilter === 'all' }" :aria-pressed="readFilter === 'all'" :disabled="locked" @click="readFilter = 'all'">全部消息</button><button :class="{ selected: readFilter === 'unread' }" :aria-pressed="readFilter === 'unread'" :disabled="locked" @click="readFilter = 'unread'">只看未读</button></div><button class="secondary" :disabled="query.loading || locked" @click="refresh">刷新消息</button></div>
    <p class="inbox-hint">消息保留发生时的进展，最新状态请查看申请。已读不会改变审批状态。</p>
    <div v-if="query.error" class="inbox-error" role="alert"><p>{{ query.error }}</p><button class="secondary" :disabled="locked || query.loading" @click="query.loaded ? query.more() : refresh()">重新读取</button></div>
    <p v-if="query.loading && !query.loaded" role="status" class="inbox-empty">正在读取消息…</p>
    <div v-else-if="query.loaded && !query.items.length" class="inbox-empty"><span aria-hidden="true">◌</span><h3>{{ readFilter === 'unread' ? '没有未读消息' : '还没有站内消息' }}</h3><p>{{ readFilter === 'unread' ? '切换到全部消息，可以回看之前的进展。' : '申请提交、审批结果和任务交接后，相关消息会出现在这里。' }}</p></div>
    <ol v-else class="inbox-list" aria-label="站内消息">
      <li v-for="item in query.items" :key="item.id" :class="{ unread: !item.readAt }">
        <div class="message-state"><i aria-hidden="true" /><span>{{ item.readAt ? '已读' : '未读' }}</span></div>
        <div class="message-body"><div class="message-meta"><strong>{{ notificationLabels[item.kind] }}</strong><time :datetime="item.createdAt">{{ time(item.createdAt) }}</time></div><h3>{{ item.title }}</h3><p v-if="item.content" class="message-content">{{ item.content }}</p><p>{{ item.businessNo }} · 第 {{ item.roundNo }} 轮<span v-if="item.nodeName"> · {{ item.nodeName }}</span></p><p class="message-actor">操作人 {{ item.actor }}<span v-if="item.readAt"> · {{ time(item.readAt) }} 已读</span></p></div>
        <div class="message-actions"><button class="secondary" :disabled="locked" @click="emit('open', item)">{{ item.kind === 'APPLICATION_COPIED' ? '查看抄送' : isTaskNotification(item) ? '查看待办' : '查看申请' }} ↗</button><button v-if="!item.readAt" class="quiet" :disabled="locked" @click="emit('read', item)">标为已读</button></div>
      </li>
    </ol>
    <div v-if="query.loaded && query.items.length" class="inbox-footer"><span>已加载 {{ query.items.length }} 条消息</span><button v-if="query.nextCursor" class="secondary" :disabled="query.loading || locked" @click="query.more">{{ query.loading ? '正在加载…' : '加载更多' }}</button><span v-else>已加载全部匹配消息</span></div>
  </section>
</template>

<style scoped>
.inbox-heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.inbox-heading h2{font-size:28px;margin:6px 0}.inbox-intro,.inbox-hint{font-size:13px;color:var(--muted);line-height:1.8}.unread-total{border:1px solid var(--line);padding:10px 14px;border-radius:8px;color:var(--deep);font-size:13px;white-space:nowrap}.inbox-toolbar{display:flex;justify-content:space-between;gap:15px;margin-top:26px}.inbox-toolbar [role=group]{display:flex;gap:4px;background:var(--soft);padding:4px;border-radius:8px}.inbox-toolbar [role=group] button{border:0;background:transparent;padding:9px 14px;color:var(--muted);border-radius:5px;font:inherit;font-size:13px;cursor:pointer}.inbox-toolbar [role=group] .selected{background:#fff;color:var(--deep);box-shadow:0 1px 3px #173e3520}.inbox-list{list-style:none;margin:20px 0 0;padding:0;border-top:1px solid var(--line)}.inbox-list li{display:grid;grid-template-columns:40px minmax(0,1fr) auto;gap:20px;padding:24px 8px;border-bottom:1px solid var(--line);align-items:center}.inbox-list li.unread{background:linear-gradient(90deg,var(--soft),transparent 35%)}.message-state{font-size:11px;color:var(--muted);display:flex;flex-direction:column;align-items:center;gap:8px}.message-state i{width:7px;height:7px;border-radius:50%;background:var(--line)}.unread .message-state i{background:var(--teal)}.message-meta{display:flex;gap:12px;align-items:center;justify-content:space-between;font-size:12px;color:var(--deep)}.message-meta time{font-size:11px;color:var(--muted);white-space:nowrap}.message-body h3{font-size:15px;line-height:1.7;margin:9px 0 3px;overflow-wrap:anywhere}.message-body p{font-size:12px;color:var(--muted);line-height:1.8;margin:0;overflow-wrap:anywhere}.message-body .message-content{white-space:pre-wrap;color:var(--ink);margin:7px 0}.message-body .message-actor{font-size:11px;margin-top:7px}.message-actions{display:flex;flex-direction:column;gap:8px}.message-actions button{white-space:nowrap}.inbox-footer{display:flex;justify-content:space-between;align-items:center;gap:15px;margin:20px 0;color:var(--muted);font-size:12px}.inbox-empty{text-align:center;padding:55px 20px;color:var(--muted);font-size:13px}.inbox-empty>span{display:block;font-size:35px;color:var(--teal)}.inbox-empty h3{color:var(--ink);font-size:17px}.inbox-error{color:var(--red);padding:18px 0;font-size:13px}
@media(max-width:650px){.inbox-heading{align-items:flex-start;gap:8px}.inbox-heading h2{font-size:24px}.inbox-intro{max-width:230px}.unread-total{padding:8px;font-size:12px}.inbox-toolbar{gap:8px}.inbox-toolbar [role=group] button{padding:9px}.inbox-list li{grid-template-columns:28px minmax(0,1fr);gap:12px;padding:20px 0}.message-meta{align-items:flex-start;flex-direction:column;gap:5px}.message-actions{grid-column:2;flex-direction:row;flex-wrap:wrap}.inbox-footer{flex-wrap:wrap}}
</style>
