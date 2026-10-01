<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { commentDrafts, CommentsQuery } from '../applicationComments'
import { CommentMentionQuery, MAX_COMMENT_MENTIONS } from '../commentMentions'

const props = defineProps<{ applicationId: string; scopeKey: string; version: number; status: string; roundNo: number; locked: boolean; refreshVersion: number }>()
const emit = defineEmits<{ posted: []; refreshApplication: [] }>()
const query = reactive(new CommentsQuery(api.applicationComments))
const mentionQuery = reactive(new CommentMentionQuery(api.commentMentionOptions))
const mentions = ref<string[]>([]), mentionsOpen = ref(false), mentionSearch = ref(''), appliedMentionSearch = ref('')
const content = ref(''), expectedVersion = ref(props.version), filterRound = ref(''), appliedRound = ref<number | undefined>()
const sending = ref(false), error = ref(''), notice = ref('')
let generation = 0
const canComment = computed(() => props.status === 'IN_APPROVAL')
const staleDraft = computed(() => Boolean(content.value.trim() || mentions.value.length) && expectedVersion.value !== props.version)
const mentionsVerified = computed(() => !mentions.value.length || mentionQuery.applicationVersion === expectedVersion.value
  && !mentionQuery.loading && !mentionQuery.error && mentions.value.every(user => mentionQuery.verified.has(user)))
const states: Record<string, string> = { DRAFT: '草稿', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已驳回', APPROVED: '已批准' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN')

function restoreDraft() {
  const draft = commentDrafts.get(props.scopeKey, props.applicationId)
  content.value = draft?.content ?? ''; expectedVersion.value = draft?.expectedVersion ?? props.version
  mentions.value = draft?.mentions ?? []
}
function edit(event: Event) {
  content.value = (event.target as HTMLTextAreaElement).value
  commentDrafts.put(props.scopeKey, props.applicationId, content.value, props.version, mentions.value)
  expectedVersion.value = commentDrafts.get(props.scopeKey, props.applicationId)?.expectedVersion ?? props.version
  notice.value = ''
}
function discard() { commentDrafts.discard(props.scopeKey, props.applicationId); restoreDraft(); mentionQuery.clear(); error.value = '' }
function adopt() { commentDrafts.adopt(props.scopeKey, props.applicationId, props.version); restoreDraft(); mentionQuery.clear(); error.value = ''; notice.value = '已更新评论上下文，请重新选择本轮提醒对象。' }
/** 名单查询不发送提醒；旧草稿中的账号要在当前上下文重新核对。 */
function loadMentions(more = false) {
  if (props.locked || sending.value || !canComment.value || staleDraft.value) return
  mentionsOpen.value = true
  if (!more) appliedMentionSearch.value = mentionSearch.value.trim()
  return mentionQuery.load(props.scopeKey, props.applicationId, expectedVersion.value, appliedMentionSearch.value, more)
}
function selectMention(user: string, selected: boolean) {
  if (props.locked || sending.value || !canComment.value || staleDraft.value) return
  if (selected && (!mentionQuery.verified.has(user) || mentions.value.length >= MAX_COMMENT_MENTIONS)) return
  mentions.value = selected ? [...new Set([...mentions.value, user])] : mentions.value.filter(value => value !== user)
  commentDrafts.put(props.scopeKey, props.applicationId, content.value, props.version, mentions.value)
  expectedVersion.value = commentDrafts.get(props.scopeKey, props.applicationId)?.expectedVersion ?? props.version
}
function refresh() { return query.load(props.scopeKey, props.applicationId, appliedRound.value) }
function applyFilter() {
  const value = filterRound.value === '' ? undefined : Number(filterRound.value)
  if (value !== undefined && (!Number.isInteger(value) || value < 1 || value > props.roundNo)) { error.value = '请输入已有的审批轮次。'; return }
  error.value = ''; appliedRound.value = value; void refresh()
}
async function submit() {
  if (!canComment.value || props.locked || sending.value || staleDraft.value || !mentionsVerified.value) return
  const text = content.value.trim()
  if (!text || text.length > 4000) { error.value = '请填写 1 至 4000 字的评论。'; return }
  const scope = props.scopeKey, id = props.applicationId, current = generation
  const body = { content: text, expectedVersion: expectedVersion.value, ...(mentions.value.length ? { mentions: [...mentions.value] } : {}) }
  sending.value = true; error.value = ''; notice.value = ''
  try {
    await api.addApplicationComment(id, body)
    commentDrafts.acknowledge(scope, id, body)
    if (generation !== current) return
    restoreDraft(); mentionQuery.clear(); notice.value = body.mentions?.length ? '评论与提醒已保存，审批状态未改变。' : '评论已追加，审批状态未改变。'; emit('posted')
  } catch (cause) {
    if (generation !== current) return
    const failure = cause as ApiError
    error.value = failure.code === 'CONCURRENCY_CONFLICT'
      ? '申请已更新，评论尚未发送。请重新加载申请，核对内容后再继续。' : failure.message ?? '评论未能发送，请重试。'
    if (failure.code === 'COMMENT_MENTION_UNAVAILABLE') mentionQuery.clear()
  } finally { if (generation === current) sending.value = false }
}
watch(() => [props.scopeKey, props.applicationId], () => {
  generation++; sending.value = false; error.value = ''; notice.value = ''; filterRound.value = ''; appliedRound.value = undefined
  mentionQuery.clear(); mentionsOpen.value = false; mentionSearch.value = ''; appliedMentionSearch.value = ''
  restoreDraft(); void refresh()
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, () => { restoreDraft(); error.value = ''; void refresh() })
watch(() => props.version, () => { mentionQuery.clear(); if (!content.value.trim() && !mentions.value.length) expectedVersion.value = props.version }, { flush: 'sync' })
onUnmounted(() => { generation++; query.clear(); mentionQuery.clear() })
</script>

<template>
  <section class="comments-panel" aria-label="申请协作评论">
    <header class="comments-heading"><div><p class="eyebrow">CONVERSATION</p><h3>协作评论</h3></div><button type="button" class="secondary" :disabled="query.loading" @click="refresh">刷新评论</button></header>
    <p class="comments-hint">仅有这份申请查看权限的人可见。评论只追加留痕，不替代审批意见，也不改变审批结论。</p>
    <form v-if="canComment || content || mentions.length" class="comment-compose" @submit.prevent="submit">
      <label>补充说明<textarea :value="content" rows="3" maxlength="4000" :disabled="locked || sending || !canComment" placeholder="补充依据、说明情况，或留下需要共同核实的问题" @input="edit" /></label>
      <div class="comment-compose-meta"><span>当前第 {{ roundNo }} 轮 · {{ states[status] ?? status }}</span><span>{{ content.length }} / 4000</span></div>
      <fieldset class="comment-mentions" :disabled="locked || sending || !canComment || staleDraft"><legend>@ 提醒对象（可选，最多 {{ MAX_COMMENT_MENTIONS }} 人）</legend>
        <button type="button" class="secondary" :disabled="mentionQuery.loading" @click="loadMentions()">{{ mentionQuery.loading ? '正在核对名单…' : '@ 选择或核对提醒对象' }}</button>
        <ul v-if="mentions.length" class="mention-selected" aria-label="已选提醒对象"><li v-for="user in mentions" :key="user">@{{ user }} <button type="button" class="quiet" :aria-label="`移除提醒 ${user}`" @click="selectMention(user, false)">移除</button></li></ul>
        <div v-if="mentionsOpen" class="mention-directory"><label>搜索可提醒账号<input v-model="mentionSearch" maxlength="128" type="search" @keydown.enter.prevent="loadMentions()" /></label><button type="button" class="secondary" :disabled="mentionQuery.loading" @click="loadMentions()">查询提醒对象</button>
          <ul v-if="mentionQuery.items.length" aria-label="可提醒账号"><li v-for="user in mentionQuery.items" :key="user"><label><input type="checkbox" :checked="mentions.includes(user)" :disabled="mentionQuery.loading || (mentions.length >= MAX_COMMENT_MENTIONS && !mentions.includes(user))" @change="selectMention(user, ($event.target as HTMLInputElement).checked)" />{{ user }}</label></li></ul>
          <p v-else-if="!mentionQuery.loading && !mentionQuery.error" class="comments-hint">当前没有匹配的可提醒账号。</p>
          <button v-if="mentionQuery.nextAfter" type="button" class="secondary" :disabled="mentionQuery.loading" @click="loadMentions(true)">更多提醒对象</button>
        </div>
        <p v-if="mentionQuery.error" class="comments-alert" role="alert">{{ mentionQuery.error }}</p>
        <p v-if="mentions.length && !mentionsVerified" class="comments-hint">已选账号需要在当前申请版本重新核对后才能发送。</p>
        <p class="comments-hint small">只提醒本轮已有读取权限的人；正文中的 @ 文字不会自动发送。提醒不包含评论正文。</p>
      </fieldset>
      <p v-if="staleDraft" class="comments-alert">申请内容或审批状态已变化，草稿仍保留。请核对最新申请后更新评论上下文。</p>
      <p v-if="!canComment" class="comments-hint">当前申请只读，未发送的评论保留在本页。</p>
      <div class="comment-buttons"><button type="button" class="secondary" :disabled="locked || sending" @click="emit('refreshApplication')">重新加载申请</button><button v-if="staleDraft && canComment" type="button" class="secondary" :disabled="locked || sending" @click="adopt">已核对，使用最新版本</button><button v-if="content || mentions.length" type="button" class="secondary" :disabled="locked || sending" @click="discard">清空草稿</button><button v-if="canComment" type="submit" class="primary" :disabled="locked || sending || staleDraft || !mentionsVerified || !content.trim()">{{ sending ? '正在发送…' : '追加评论' }}</button></div>
      <p class="comments-hint small">未发送正文与提醒选择仅保留在当前页面内；切换视图后可继续，刷新页面会丢失。</p>
    </form>
    <p v-else class="comments-hint">当前申请只读，可查看已有评论。</p>
    <p v-if="error" class="comments-alert" role="alert">{{ error }}</p><p v-if="notice" class="comments-notice" role="status">{{ notice }}</p>
    <form class="comments-filter" @submit.prevent="applyFilter"><label>记录时的轮次<input v-model="filterRound" type="number" min="1" :max="roundNo" placeholder="全部轮次" /></label><button type="submit" class="secondary" :disabled="query.loading">筛选</button><span>已加载 {{ query.items.length }} 条</span></form>
    <p v-if="query.error" class="comments-alert" role="alert">{{ query.error }}</p>
    <p v-if="query.loading && !query.items.length" class="comments-hint" role="status">正在读取评论…</p>
    <p v-else-if="!query.items.length && !query.error" class="comments-empty">暂无评论，审批意见仍在审批轨迹中保留。</p>
    <ol class="comments-list" aria-label="协作评论记录"><li v-for="item in query.items" :key="item.id"><div class="comment-meta"><strong>{{ item.author }}</strong><time :datetime="item.createdAt">{{ time(item.createdAt) }}</time></div><p class="comment-content">{{ item.content }}</p><p v-if="item.mentions?.length" class="comment-mentioned">已提醒：{{ item.mentions.map(user => '@' + user).join('、') }}</p><small>记录时：第 {{ item.roundNo }} 轮 · {{ states[item.applicationStatus] ?? item.applicationStatus }}</small></li></ol>
    <button v-if="query.nextCursor" type="button" class="secondary comments-more" :disabled="query.loading" @click="query.load(scopeKey, applicationId, appliedRound, true)">{{ query.loading ? '正在加载…' : '加载更早评论' }}</button>
  </section>
</template>

<style scoped>
.comment-mentions{min-width:0;border:1px solid var(--line);border-radius:8px;margin:14px 0;padding:12px}.comment-mentions legend{font-size:12px;padding:0 5px}.mention-selected,.mention-directory ul{list-style:none;padding:0;margin:10px 0;display:flex;flex-wrap:wrap;gap:8px}.mention-selected li{max-width:100%;overflow-wrap:anywhere;font-size:12px}.mention-directory{margin-top:12px}.mention-directory>button{margin:8px 0}.mention-directory ul{max-height:220px;overflow:auto}.mention-directory li{flex:1 1 160px;min-width:0}.mention-directory li label{display:flex;align-items:center;gap:8px;overflow-wrap:anywhere}.mention-directory input[type=checkbox]{width:auto;flex:0 0 auto}.mention-directory input[type=search]{width:100%;box-sizing:border-box}.comment-mentioned{font-size:12px;color:var(--muted);overflow-wrap:anywhere}

.comments-panel{padding:20px 0;min-width:0}.comments-heading,.comment-meta,.comment-compose-meta,.comments-filter,.comment-buttons{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.comments-heading{justify-content:space-between}.comments-heading h3{margin:3px 0;font-size:17px}.comments-heading .eyebrow{margin:0}.comments-hint,.comments-empty{font-size:12px;line-height:1.8;color:var(--muted)}.comment-compose{padding:16px;background:var(--paper);border:1px solid var(--line);border-radius:12px;margin:18px 0}.comment-compose label{display:grid;gap:8px;font-size:12px}.comment-compose textarea{width:100%;box-sizing:border-box;resize:vertical;min-height:90px}.comment-compose-meta{justify-content:space-between;font-size:11px;color:var(--muted);margin:9px 0 14px}.comment-buttons{justify-content:flex-end}.small{font-size:11px;margin-bottom:0}.comments-alert,.comments-notice{padding:11px 13px;border-radius:8px;font-size:12px;line-height:1.8}.comments-alert{background:#fff0ed;color:var(--red)}.comments-notice{background:var(--soft);color:var(--deep)}.comments-filter{margin:24px 0 12px}.comments-filter label{display:flex;gap:8px;align-items:center;font-size:12px;margin:0}.comments-filter input{width:100px}.comments-filter>span{font-size:11px;color:var(--muted);margin-left:auto}.comments-list{list-style:none;padding:0;margin:0}.comments-list li{border-bottom:1px solid var(--line);padding:17px 0}.comment-meta strong{font-size:13px}.comment-meta time,.comments-list small{font-size:11px;color:var(--muted)}.comment-meta time{margin-left:auto}.comment-content{font-size:13px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere;margin:12px 0}.comments-more{margin-top:15px;width:100%}.comments-empty{padding:22px;text-align:center;background:var(--paper);border-radius:10px}textarea:focus-visible{outline:3px solid rgba(33,173,159,.35);outline-offset:2px}@media(max-width:650px){.comment-compose{padding:12px}.comment-buttons button{flex:1}.comment-meta time{margin-left:0}.comments-filter>span{width:100%;margin:0}}
</style>
