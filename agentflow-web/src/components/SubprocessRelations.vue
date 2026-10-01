<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type SubmissionRound } from '../api'
import { SubprocessRelationsQuery, type RelatedRound } from '../subprocessRelations'

const props = defineProps<{ applicationId: string; scopeKey: string; version: number; rounds: SubmissionRound[]; initialRoundNo?: number | null; locked: boolean }>()
const emit = defineEmits<{ open: [target: RelatedRound] }>()
const query = reactive(new SubprocessRelationsQuery(api.subprocessRelations))
const selectedRound = ref(0)
const sorted = computed(() => [...props.rounds].sort((a, b) => b.roundNo - a.roundNo))
const labels: Record<string, string> = { IN_APPROVAL: '审批中', APPROVED: '已批准', RETURNED: '已退回', REJECTED: '已驳回', WITHDRAWN: '已撤回', CANCELLED: '已取消' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
function load() { return query.load(props.scopeKey, props.applicationId, selectedRound.value) }
/** 只导航当前已授权响应中的对象；未保存修改、未知写入及读取中均由上层阻断。 */
function open(target: RelatedRound | null) {
  if (!target || props.locked || query.loading || query.error || !query.value) return
  if (query.value.parent === target || query.value.children.some(child => child.target === target)) emit('open', target)
}
watch([() => props.applicationId, () => props.scopeKey, () => props.initialRoundNo], () => {
  selectedRound.value = props.rounds.find(round => round.roundNo === props.initialRoundNo)?.roundNo ?? sorted.value[0]?.roundNo ?? 0
}, { immediate: true, flush: 'sync' })
watch(() => props.rounds, () => {
  if (!props.rounds.some(round => round.roundNo === selectedRound.value)) selectedRound.value = sorted.value[0]?.roundNo ?? 0
}, { flush: 'sync' })
watch([() => props.applicationId, () => props.scopeKey, () => props.version, selectedRound], () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="subprocess-relations" aria-label="父子流程关系" :aria-busy="query.loading">
    <div class="relations-heading"><div><h3>父子流程</h3><p>查看本轮实际产生的调用。尚未激活的步骤不会显示为已创建的子申请。</p></div><button type="button" class="secondary" :disabled="query.loading || !selectedRound" @click="load">刷新父子流程</button></div>
    <p v-if="!rounds.length">尚未提交，没有父子调用记录。</p>
    <template v-else>
      <label class="relations-selector">关联流程轮次<select v-model.number="selectedRound"><option v-for="round in sorted" :key="round.roundNo" :value="round.roundNo">第 {{ round.roundNo }} 轮 · {{ labels[round.status] ?? round.status }}</option></select></label>
      <p v-if="query.loading" role="status">正在读取本轮父子流程…</p>
      <div v-if="query.error" role="alert"><p>{{ query.error }}</p><button type="button" @click="load">重试读取父子流程</button></div>
      <template v-else-if="query.value">
        <p class="relations-note">读取于 {{ time(query.value.observedAt) }}。只提供已授权申请的入口；打开后重新检查权限，表单和附件沿用各自轮次的权限。</p>
        <article v-if="query.value.childApplication" class="relation-card" aria-label="来源父流程">
          <h4>来源父流程</h4>
          <template v-if="query.value.parent"><strong>{{ query.value.parent.title }}</strong><p>{{ query.value.parent.businessNo }} · 第 {{ query.value.parent.roundNo }} 轮 · {{ labels[query.value.parent.status] ?? query.value.parent.status }}</p><p>{{ query.value.parent.processKey }} · v{{ query.value.parent.definitionVersion }}</p><button type="button" :disabled="locked || query.loading" :aria-label="`打开 ${query.value.parent.businessNo} 第 ${query.value.parent.roundNo} 轮`" @click="open(query.value.parent)">查看来源轮次</button></template>
          <p v-else>来源轮次不可用或当前无读取权限。</p>
        </article>
        <p v-else>本申请没有父调用记录。</p>
        <h4>本轮子调用 · 已加载 {{ query.value.children.length }} 条</h4>
        <p v-if="!query.value.children.length">本轮尚无子调用记录。</p>
        <article v-for="child in query.value.children" :key="child.id" class="relation-card" :aria-label="`子调用 ${child.nodeName}`">
          <h4>{{ child.nodeName }}</h4><p>激活于 {{ time(child.createdAt) }}</p>
          <template v-if="child.target"><strong>{{ child.target.title }}</strong><p>{{ child.target.businessNo }} · 第 {{ child.target.roundNo }} 轮 · {{ labels[child.target.status] ?? child.target.status }}</p><p>{{ child.target.processKey }} · v{{ child.target.definitionVersion }}</p><button type="button" :disabled="locked || query.loading" :aria-label="`打开 ${child.target.businessNo} 第 ${child.target.roundNo} 轮`" @click="open(child.target)">查看子流程轮次</button></template>
          <p v-else>子轮次不可用或当前无读取权限。</p>
        </article>
        <button v-if="query.value.nextAfterId" type="button" :disabled="query.loading" @click="query.more">加载更多子调用</button>
        <p v-if="locked" class="relations-note">请先完成当前编辑或操作，再打开关联申请。</p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.subprocess-relations{padding:20px 0;min-width:0;font-size:12px;line-height:1.8;overflow-wrap:anywhere}.relations-heading{display:flex;align-items:start;justify-content:space-between;gap:12px;flex-wrap:wrap}.relations-heading h3{margin:0}.subprocess-relations p{color:var(--muted)}.subprocess-relations .relations-selector{display:grid;gap:8px;margin:16px 0;max-width:320px}.relations-selector select{width:100%;min-width:0}.relation-card{border:1px solid var(--line);border-radius:8px;padding:14px;margin:12px 0;background:var(--paper)}.relation-card h4{margin:0 0 8px}.relation-card strong{display:block}.relation-card button{margin-top:6px}.relations-note{font-size:11px}.subprocess-relations [role=alert]{color:var(--red)}.subprocess-relations button:focus-visible,.relations-selector select:focus-visible{outline:2px solid var(--deep);outline-offset:2px}
</style>
