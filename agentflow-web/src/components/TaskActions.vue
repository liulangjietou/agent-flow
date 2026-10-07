<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type Task, type TaskAction, type TaskActionInput } from '../api'
import { isApprovalDecision, needsComment, needsRecipient, taskActionInput, taskActionLabels, TaskRecipientsQuery, type TaskReturnDraft } from '../taskActions'
import CountersignMembers from './CountersignMembers.vue'
import type { CountersignInput, CountersignView } from '../countersignMembership'
import { approvalPolicyLabel } from '../approvalPolicy'

const props = defineProps<{ task: Task; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ execute: [input: TaskActionInput]; membership: [input: CountersignInput, view: CountersignView]; refresh: [] }>()
const membershipOpen = ref(false)
const pending = ref<TaskAction | null>(null), comment = ref(''), target = ref(''), error = ref('')
const selectedProxy = ref('')
const opinionInput = ref<HTMLTextAreaElement | null>(null)
const actionBar = ref<HTMLElement | null>(null)
const recipients = reactive(new TaskRecipientsQuery(api.taskRecipients))
const actions = computed(() => props.task.allowedActions ?? [])
const delegated = computed(() => props.task.delegationState === 'PENDING')
const allCountersign = computed(() => !!props.task.countersign && (props.task.countersign.mode ?? 'ALL') === 'ALL')
const membershipAllowed = computed(() => allCountersign.value && props.task.canActDirectly !== false)
const proxyOptions = computed(() => props.task.proxyOptions ?? [])
const proxy = computed(() => proxyOptions.value.find(option => option.proxyId === selectedProxy.value))
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
function cancel() { pending.value = null; membershipOpen.value = false; comment.value = ''; target.value = ''; selectedProxy.value = ''; error.value = ''; recipients.clear() }
/** 仅主动取消恢复按钮焦点；任务或账号变化时清理表单不抢焦点。 */
function cancelForm() {
  const action = pending.value
  cancel()
  void nextTick(() => actionBar.value?.querySelector<HTMLButtonElement>(`[data-action="${action}"]`)?.focus())
}
function reloadRecipients() { target.value = ''; void recipients.load(props.scopeKey, props.task.taskId) }
function prepare(action: TaskAction) {
  if (props.locked) return
  cancel()
  if (['CLAIM', 'RELEASE'].includes(action)) { execute(action); return }
  pending.value = action
  if (isApprovalDecision(action) && props.task.canActDirectly === false && proxyOptions.value.length === 1) selectedProxy.value = proxyOptions.value[0].proxyId
  if (needsRecipient(action)) reloadRecipients()
  else void nextTick(() => { if (pending.value === action && !props.locked) opinionInput.value?.focus() })
}
/** 外部预填仅属于本任务，不能覆盖正在填写的意见或直接发出审批命令。 */
function prepareReturn(draft: TaskReturnDraft): boolean {
  if (props.locked || pending.value || membershipOpen.value || !actions.value.includes('RETURN')
    || draft.scopeKey !== props.scopeKey || draft.taskId !== props.task.taskId || draft.expectedVersion !== props.task.version) return false
  prepare('RETURN'); comment.value = draft.comment
  return true
}
defineExpose({ prepareReturn })
function execute(action: TaskAction) {
  if (props.locked || recipients.loading || recipients.error) return
  try { error.value = ''; emit('execute', taskActionInput(props.task, action, comment.value, target.value, recipients.users, selectedProxy.value)) }
  catch (cause) { error.value = (cause as Error).message }
}
watch([() => props.scopeKey, () => props.task.taskId, () => props.task.version,
  () => JSON.stringify([props.task.canActDirectly, props.task.proxyOptions, props.task.allowedActions])], cancel, { flush: 'sync' })
onUnmounted(cancel)
</script>

<template>
  <div class="task-actions">
    <div v-if="task.countersign" class="delegation-note" role="status">
      <strong>{{ approvalPolicyLabel(task.countersign.mode ?? 'ALL', task.countersign.percentage) }} · 已同意 {{ task.countersign.completed }} / {{ task.countersign.total }} 人</strong>
      <p v-if="allCountersign">当前责任人全部同意才通过，任一驳回结束整轮。人员增减须明确原因，已有意见与最初名单保留；委派协助后仍由原责任人决定。</p>
      <p v-else>本节点需要 {{ task.countersign.required }} 人同意。达标后结束其余待办，不替未处理人员记录同意；达标前任一驳回结束整轮。名单和人数门槛已固定，委派协助后仍由原责任人决定。</p>
    </div>
    <div v-if="delegated" class="delegation-note"><strong>受托处理 · 回交给 {{ task.owner || '待核对的原审批人' }}</strong><p>{{ task.owner ? '填写处理意见并回交。申请继续保持审批中，由原审批人作最终决定。' : '原责任人缺失，请联系流程管理员核对后再处理。' }}</p></div>
    <div v-else-if="task.delegationState === 'RESOLVED'" class="delegation-note"><strong>受托处理已回交</strong><p>请在操作审计中查看受托人的意见，再继续审批。</p></div>
    <CountersignMembers v-if="membershipOpen && membershipAllowed" :task="task" :scope-key="scopeKey" :locked="locked" @execute="(input, view) => emit('membership', input, view)" @close="membershipOpen = false" @refresh="emit('refresh')" />
    <form v-else-if="pending" class="task-action-form" @submit.prevent="execute(pending)">
      <h4>{{ taskActionLabels[pending] }}</h4>
      <p v-if="pending === 'APPROVE'" class="task-action-help">确认后批准当前节点；申请是否完成取决于后续节点和会签进度。意见将保留在操作审计中。</p>
      <p v-else-if="pending === 'DELEGATE'" class="task-action-help">接收人处理后会回交给你，最终审批仍由你完成。</p>
      <p v-else-if="pending === 'TRANSFER'" class="task-action-help">将任务交给接收人，由其继续审批。</p>
      <p v-else-if="pending === 'REJECT'" class="task-action-help">驳回将终止本轮申请；需要申请人补充后重提，请使用退回。</p>
      <template v-if="isApprovalDecision(pending) && proxyOptions.length">
        <label>办理身份<select v-model="selectedProxy" :disabled="locked" :required="task.canActDirectly === false">
          <option v-if="task.canActDirectly !== false" value="">以本人审批职责办理</option>
          <option v-else value="" disabled>请选择本次代理的原审批人</option>
          <option v-for="option in proxyOptions" :key="option.proxyId" :value="option.proxyId">代理 {{ option.principal }} 办理</option>
        </select></label>
        <p v-if="proxy" class="task-action-help">代理有效至 {{ timeLabel(proxy.endsAt) }}。本次决定将同时记录你是实际办理人、{{ proxy.principal }} 是原审批人；提交时再次核对代理是否有效。</p>
      </template>
      <template v-if="needsRecipient(pending)">
        <p v-if="recipients.loading" role="status" class="task-action-help">正在读取可接收任务的账号…</p>
        <div v-else-if="recipients.error" role="alert" class="recipient-error"><p>{{ recipients.error }}</p><button type="button" class="secondary" :disabled="locked" @click="reloadRecipients">重新读取接收人</button></div>
        <p v-else-if="!recipients.users.length" class="task-action-help">当前没有其他可接收任务的审批账号。</p>
        <label v-else>接收人<select v-model="target" :disabled="locked" required><option value="" disabled>请选择审批账号</option><option v-for="user in recipients.users" :key="user" :value="user">{{ user }}</option></select></label>
      </template>
      <label>{{ pending === 'APPROVE' ? '批准意见（选填）' : pending === 'RETURN' ? '退回原因（必填）' : pending === 'REJECT' ? '驳回原因（必填）' : pending === 'RESOLVE' ? '处理意见（必填）' : '处理说明（选填）' }}<textarea ref="opinionInput" v-model="comment" :disabled="locked" :required="needsComment(pending)" rows="3" /></label>
      <p v-if="error" role="alert" class="recipient-error">{{ error }}</p>
      <div class="form-actions"><button type="button" class="secondary" :disabled="locked" @click="cancelForm">取消</button><button class="primary" :disabled="locked || recipients.loading || !!recipients.error || (needsRecipient(pending) && !target)">{{ locked ? '提交中…' : pending === 'RESOLVE' ? '确认回交给 ' + task.owner : '确认' + taskActionLabels[pending] }}</button></div>
    </form>
    <div v-else ref="actionBar" class="action-bar"><button v-if="membershipAllowed" type="button" class="secondary" :disabled="locked" @click="membershipOpen = true">查看与增减会签人</button><button v-for="action in actions" :key="action" :data-action="action" :class="action === 'APPROVE' || action === 'RESOLVE' ? 'primary' : action === 'RETURN' || action === 'REJECT' ? 'return' : 'secondary'" :disabled="locked" @click="prepare(action)">{{ taskActionLabels[action] }}</button></div>
    <p v-if="error && !pending" role="alert" class="recipient-error">{{ error }}</p>
  </div>
</template>

<style scoped>
.task-actions .action-bar{flex-wrap:wrap;justify-content:flex-start;gap:10px}.task-actions .action-bar .primary{order:1;margin-left:auto}.delegation-note{margin:18px 24px 0;padding:15px 17px;background:var(--soft);border:1px solid #d7e5e0;border-radius:8px;font-size:12px;color:var(--deep)}.delegation-note p,.task-action-help{font-size:12px;line-height:1.8;margin:8px 0 0;color:var(--muted)}.task-action-form .task-action-help{margin-bottom:18px}.recipient-error{color:var(--red);font-size:12px;line-height:1.8}.task-action-form select{width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:7px;background:#fff;color:var(--ink);font:inherit}.form-actions{flex-wrap:wrap}.form-actions .primary{white-space:normal;overflow-wrap:anywhere}
@media(max-width:650px){.delegation-note{margin:15px 15px 0}.task-actions .action-bar .primary{margin-left:0}.task-actions .action-bar{gap:8px}}
</style>
