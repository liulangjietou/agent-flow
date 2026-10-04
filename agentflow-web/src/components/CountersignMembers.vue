<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type Task } from '../api'
import { CountersignQuery, countersignInput, type CountersignInput, type CountersignView } from '../countersignMembership'

const props = defineProps<{ task: Task; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ execute: [input: CountersignInput, view: CountersignView]; close: []; refresh: [] }>()
const query = reactive(new CountersignQuery(api.taskCountersignMembers))
const action = ref<'ADD' | 'REMOVE' | null>(null), target = ref(''), reason = ref(''), error = ref('')
const removable = computed(() => query.view?.pending.filter(member => member.canRemove) ?? [])
function cancel() { action.value = null; target.value = ''; reason.value = ''; error.value = '' }
function prepare(value: 'ADD' | 'REMOVE') { if (!props.locked) { cancel(); action.value = value } }
function refresh() { cancel(); void query.load(props.scopeKey, props.task) }
function execute() {
  if (props.locked || query.loading || !query.view || !action.value) return
  try { error.value = ''; emit('execute', countersignInput(query.view, action.value, target.value, reason.value), query.view) }
  catch (cause) { error.value = (cause as Error).message }
}
watch([() => props.scopeKey, () => props.task.taskId, () => props.task.version], refresh, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="membership-panel" aria-label="会签人员" :aria-busy="query.loading">
    <header><div><h4>会签人员</h4><p v-if="query.view?.issue === 'EXPENSE_PROJECT_MEMBERS_FIXED'">本轮项目负责人必须全部办理，名单随提交固定。</p><p v-else>新增人员也须实际审批。减签保留取消记录和已有意见。</p></div><button type="button" class="secondary" :disabled="locked" @click="emit('close')">返回审批</button></header>
    <p v-if="query.loading" role="status">正在核对本轮会签人员…</p>
    <div v-else-if="query.error" role="alert"><p class="membership-error">{{ query.error }}</p><button type="button" class="secondary" :disabled="locked" @click="emit('refresh')">刷新当前任务</button></div>
    <template v-else-if="query.view">
      <p class="membership-count">第 {{ query.view.roundNo }} 轮 · 已同意 {{ query.view.completed }} / {{ query.view.total }} 人</p>
      <ul class="member-list"><li v-for="member in query.view.pending" :key="member.taskId"><strong>{{ member.user }}</strong><span>{{ member.taskId === task.taskId ? '当前任务' : '待审批' }}<template v-if="member.delegated"> · {{ member.assignee }} 受托处理中</template></span></li><li v-for="user in query.view.completedUsers" :key="'completed:' + user"><strong>{{ user }}</strong><span>已同意 · 意见保留</span></li></ul>
      <details><summary>节点开始时的名单</summary><p>{{ query.view.originalMembers.join('、') }}</p><p v-if="query.view.issue !== 'EXPENSE_PROJECT_MEMBERS_FIXED'">后续明确增减记录保留在操作审计中。</p></details>
      <p v-if="query.view.issue === 'EXPENSE_PROJECT_MEMBERS_FIXED'" class="membership-help">项目审批责任已固定，不能加减签或转交。委派协助结束后仍由原负责人决定；有效代理保留原责任。</p>
      <p v-else-if="!query.view.canChange" class="membership-help">受托处理中不能增减人员，请先回交给原责任人。</p>
      <form v-else-if="action" @submit.prevent="execute">
        <h4>{{ action === 'ADD' ? '增加必要会签人' : '移除未决会签任务' }}</h4>
        <p class="membership-help">{{ action === 'ADD' ? '确认后为所选人员创建必要审批任务，已有意见保持。' : '确认后取消所选人员的这张未决任务；你的审批责任继续保留。' }}</p>
        <label>{{ action === 'ADD' ? '新增审批人' : '移除的任务' }}<select v-model="target" :disabled="locked" required><option value="" disabled>请选择人员</option><template v-if="action === 'ADD'"><option v-for="user in query.view.additions" :key="user" :value="user">{{ user }}</option></template><template v-else><option v-for="member in removable" :key="member.taskId" :value="member.taskId">{{ member.user }} · 待审批</option></template></select></label>
        <label>变更原因<textarea v-model="reason" :disabled="locked" required maxlength="2000" rows="3" /></label>
        <p v-if="error" role="alert" class="membership-error">{{ error }}</p>
        <div class="member-actions"><button type="button" class="secondary" :disabled="locked" @click="cancel">取消变更</button><button class="primary" :disabled="locked || !target || !reason.trim()">{{ locked ? '正在处理…' : action === 'ADD' ? '确认加签' : '确认减签' }}</button></div>
      </form>
      <div v-else class="member-actions"><button type="button" class="secondary" :disabled="locked || !query.view.canAdd" @click="prepare('ADD')">增加会签人</button><button type="button" class="secondary" :disabled="locked || !removable.length" @click="prepare('REMOVE')">移除会签人</button><button type="button" class="secondary" :disabled="locked" @click="refresh">重新核对名单</button></div>
    </template>
  </section>
</template>

<style scoped>
.membership-panel{margin:18px 24px;padding:18px;border:1px solid var(--line);border-radius:10px;background:var(--paper);font-size:12px;line-height:1.7}.membership-panel header{display:flex;gap:16px;align-items:flex-start;justify-content:space-between}.membership-panel h4{margin:0 0 8px;color:var(--ink);font-size:14px}.membership-panel p{margin:8px 0;overflow-wrap:anywhere}.membership-panel header p,.membership-help,.membership-panel details{color:var(--muted)}.membership-count{font-weight:600;color:var(--deep)}.member-list{list-style:none;margin:14px 0;padding:0}.member-list li{display:flex;justify-content:space-between;gap:12px;padding:9px 0;border-bottom:1px solid var(--line);overflow-wrap:anywhere}.member-list strong,.member-list span{min-width:0}.member-list span{color:var(--muted);text-align:right}.membership-panel details{margin-bottom:14px}.membership-panel label{display:block;margin:14px 0 0}.membership-panel select,.membership-panel textarea{display:block;width:100%;min-width:0;margin-top:6px;padding:10px;border:1px solid var(--line);border-radius:7px;background:#fff;color:var(--ink);font:inherit}.member-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.membership-error{color:var(--red)}.membership-panel button{white-space:normal;overflow-wrap:anywhere}
@media(max-width:650px){.membership-panel{margin:15px;padding:14px}.membership-panel header{flex-wrap:wrap}.member-list li{flex-wrap:wrap}.member-list span{text-align:left}.member-actions button{flex:1 1 130px}}
</style>
