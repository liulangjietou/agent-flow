<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import { commentDrafts, CommentsQuery } from '../applicationComments'

const props = defineProps<{ applicationId: string; scopeKey: string; version: number; status: string; roundNo: number; locked: boolean; refreshVersion: number }>()
const emit = defineEmits<{ posted: []; refreshApplication: [] }>()
const query = reactive(new CommentsQuery(api.applicationComments))
const content = ref(''), expectedVersion = ref(props.version), filterRound = ref(''), appliedRound = ref<number | undefined>()
const sending = ref(false), error = ref(''), notice = ref('')
let generation = 0
const canComment = computed(() => props.status === 'IN_APPROVAL')
const staleDraft = computed(() => Boolean(content.value.trim()) && expectedVersion.value !== props.version)
const states: Record<string, string> = { DRAFT: '草稿', IN_APPROVAL: '审批中', RETURNED: '已退回', WITHDRAWN: '已撤回', REJECTED: '已驳回', APPROVED: '已批准' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN')

function restoreDraft() {
  const draft = commentDrafts.get(props.scopeKey, props.applicationId)
  content.value = draft?.content ?? ''; expectedVersion.value = draft?.expectedVersion ?? props.version
}
function edit(event: Event) {
  content.value = (event.target as HTMLTextAreaElement).value
  commentDrafts.put(props.scopeKey, props.applicationId, content.value, props.version)
  expectedVersion.value = commentDrafts.get(props.scopeKey, props.applicationId)?.expectedVersion ?? props.version
  notice.value = ''
}
function discard() { commentDrafts.discard(props.scopeKey, props.applicationId); restoreDraft(); error.value = '' }
function adopt() { commentDrafts.adopt(props.scopeKey, props.applicationId, props.version); restoreDraft(); error.value = '' }
function refresh() { return query.load(props.scopeKey, props.applicationId, appliedRound.value) }
function applyFilter() {
  const value = filterRound.value === '' ? undefined : Number(filterRound.value)
  if (value !== undefined && (!Number.isInteger(value) || value < 1 || value > props.roundNo)) { error.value = '请输入已有的审批轮次。'; return }
  error.value = ''; appliedRound.value = value; void refresh()
}
async function submit() {
  if (!canComment.value || props.locked || sending.value || staleDraft.value) return
  const text = content.value.trim()
  if (!text || text.length > 4000) { error.value = '请填写 1 至 4000 字的评论。'; return }
  const scope = props.scopeKey, id = props.applicationId, current = generation
  const body = { content: text, expectedVersion: expectedVersion.value }
  sending.value = true; error.value = ''; notice.value = ''
  try {
    await api.addApplicationComment(id, body)
    commentDrafts.acknowledge(scope, id, body)
    if (generation !== current) return
    restoreDraft(); notice.value = '评论已追加，审批状态未改变。'; emit('posted')
  } catch (cause) {
    if (generation !== current) return
    const failure = cause as ApiError
    error.value = failure.code === 'CONCURRENCY_CONFLICT'
      ? '申请已更新，评论尚未发送。请重新加载申请，核对内容后再继续。' : failure.message ?? '评论未能发送，请重试。'
  } finally { if (generation === current) sending.value = false }
}
watch(() => [props.scopeKey, props.applicationId], () => {
  generation++; sending.value = false; error.value = ''; notice.value = ''; filterRound.value = ''; appliedRound.value = undefined
  restoreDraft(); void refresh()
}, { immediate: true })
watch(() => props.refreshVersion, () => { restoreDraft(); error.value = ''; void refresh() })
watch(() => props.version, () => { if (!content.value.trim()) expectedVersion.value = props.version })
onUnmounted(() => { generation++; query.clear() })
</script>

<template>
  <section class="comments-panel" aria-label="申请协作评论">
    <header class="comments-heading"><div><p class="eyebrow">CONVERSATION</p><h3>协作评论</h3></div><button type="button" class="secondary" :disabled="query.loading" @click="refresh">刷新评论</button></header>
    <p class="comments-hint">仅有这份申请查看权限的人可见。评论只追加留痕，不替代审批意见，也不改变审批结论。</p>
    <form v-if="canComment || content" class="comment-compose" @submit.prevent="submit">
      <label>补充说明<textarea :value="content" rows="3" maxlength="4000" :disabled="locked || sending || !canComment" placeholder="补充依据、说明情况，或留下需要共同核实的问题" @input="edit" /></label>
      <div class="comment-compose-meta"><span>当前第 {{ roundNo }} 轮 · {{ states[status] ?? status }}</span><span>{{ content.length }} / 4000</span></div>
      <p v-if="staleDraft" class="comments-alert">申请内容或审批状态已变化，草稿仍保留。请核对最新申请后更新评论上下文。</p>
      <p v-if="!canComment" class="comments-hint">当前申请只读，未发送的评论保留在本页。</p>
      <div class="comment-buttons"><button type="button" class="secondary" :disabled="locked || sending" @click="emit('refreshApplication')">重新加载申请</button><button v-if="staleDraft && canComment" type="button" class="secondary" :disabled="locked || sending" @click="adopt">已核对，使用最新版本</button><button v-if="content" type="button" class="secondary" :disabled="locked || sending" @click="discard">清空草稿</button><button v-if="canComment" type="submit" class="primary" :disabled="locked || sending || staleDraft || !content.trim()">{{ sending ? '正在发送…' : '追加评论' }}</button></div>
      <p class="comments-hint small">未发送正文仅保留在当前页面内；切换视图后可继续，刷新页面会丢失。暂不支持 @ 通知。</p>
    </form>
    <p v-else class="comments-hint">当前申请只读，可查看已有评论。</p>
    <p v-if="error" class="comments-alert" role="alert">{{ error }}</p><p v-if="notice" class="comments-notice" role="status">{{ notice }}</p>
    <form class="comments-filter" @submit.prevent="applyFilter"><label>记录时的轮次<input v-model="filterRound" type="number" min="1" :max="roundNo" placeholder="全部轮次" /></label><button type="submit" class="secondary" :disabled="query.loading">筛选</button><span>已加载 {{ query.items.length }} 条</span></form>
    <p v-if="query.error" class="comments-alert" role="alert">{{ query.error }}</p>
    <p v-if="query.loading && !query.items.length" class="comments-hint" role="status">正在读取评论…</p>
    <p v-else-if="!query.items.length && !query.error" class="comments-empty">暂无评论，审批意见仍在审批轨迹中保留。</p>
    <ol class="comments-list" aria-label="协作评论记录"><li v-for="item in query.items" :key="item.id"><div class="comment-meta"><strong>{{ item.author }}</strong><time :datetime="item.createdAt">{{ time(item.createdAt) }}</time></div><p class="comment-content">{{ item.content }}</p><small>记录时：第 {{ item.roundNo }} 轮 · {{ states[item.applicationStatus] ?? item.applicationStatus }}</small></li></ol>
    <button v-if="query.nextCursor" type="button" class="secondary comments-more" :disabled="query.loading" @click="query.load(scopeKey, applicationId, appliedRound, true)">{{ query.loading ? '正在加载…' : '加载更早评论' }}</button>
  </section>
</template>

<style scoped>
.comments-panel{padding:20px 0;min-width:0}.comments-heading,.comment-meta,.comment-compose-meta,.comments-filter,.comment-buttons{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.comments-heading{justify-content:space-between}.comments-heading h3{margin:3px 0;font-size:17px}.comments-heading .eyebrow{margin:0}.comments-hint,.comments-empty{font-size:12px;line-height:1.8;color:var(--muted)}.comment-compose{padding:16px;background:var(--paper);border:1px solid var(--line);border-radius:12px;margin:18px 0}.comment-compose label{display:grid;gap:8px;font-size:12px}.comment-compose textarea{width:100%;box-sizing:border-box;resize:vertical;min-height:90px}.comment-compose-meta{justify-content:space-between;font-size:11px;color:var(--muted);margin:9px 0 14px}.comment-buttons{justify-content:flex-end}.small{font-size:11px;margin-bottom:0}.comments-alert,.comments-notice{padding:11px 13px;border-radius:8px;font-size:12px;line-height:1.8}.comments-alert{background:#fff0ed;color:var(--red)}.comments-notice{background:var(--soft);color:var(--deep)}.comments-filter{margin:24px 0 12px}.comments-filter label{display:flex;gap:8px;align-items:center;font-size:12px;margin:0}.comments-filter input{width:100px}.comments-filter>span{font-size:11px;color:var(--muted);margin-left:auto}.comments-list{list-style:none;padding:0;margin:0}.comments-list li{border-bottom:1px solid var(--line);padding:17px 0}.comment-meta strong{font-size:13px}.comment-meta time,.comments-list small{font-size:11px;color:var(--muted)}.comment-meta time{margin-left:auto}.comment-content{font-size:13px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere;margin:12px 0}.comments-more{margin-top:15px;width:100%}.comments-empty{padding:22px;text-align:center;background:var(--paper);border-radius:10px}textarea:focus-visible{outline:3px solid rgba(33,173,159,.35);outline-offset:2px}@media(max-width:650px){.comment-compose{padding:12px}.comment-buttons button{flex:1}.comment-meta time{margin-left:0}.comments-filter>span{width:100%;margin:0}}
</style>
