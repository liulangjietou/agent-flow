<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type Task, type TaskAction, type TaskActionInput } from '../api'
import { needsComment, needsRecipient, taskActionInput, taskActionLabels, TaskRecipientsQuery } from '../taskActions'

const props = defineProps<{ task: Task; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ execute: [input: TaskActionInput] }>()
const pending = ref<TaskAction | null>(null), comment = ref(''), target = ref(''), error = ref('')
const recipients = reactive(new TaskRecipientsQuery(api.taskRecipients))
const actions = computed(() => props.task.allowedActions ?? [])
const delegated = computed(() => props.task.delegationState === 'PENDING')
function cancel() { pending.value = null; comment.value = ''; target.value = ''; error.value = ''; recipients.clear() }
function reloadRecipients() { target.value = ''; void recipients.load(props.scopeKey, props.task.taskId) }
function prepare(action: TaskAction) {
  if (props.locked) return
  cancel()
  if (['APPROVE', 'CLAIM', 'RELEASE'].includes(action)) { execute(action); return }
  pending.value = action
  if (needsRecipient(action)) reloadRecipients()
}
function execute(action: TaskAction) {
  if (props.locked || recipients.loading || recipients.error) return
  try { error.value = ''; emit('execute', taskActionInput(props.task, action, comment.value, target.value, recipients.users)) }
  catch (cause) { error.value = (cause as Error).message }
}
watch([() => props.scopeKey, () => props.task.taskId, () => props.task.version], cancel, { flush: 'sync' })
onUnmounted(cancel)
</script>

<template>
  <div class="task-actions">
    <div v-if="delegated" class="delegation-note"><strong>受托处理 · 回交给 {{ task.owner || '待核对的原审批人' }}</strong><p>{{ task.owner ? '填写处理意见并回交。申请继续保持审批中，由原审批人作最终决定。' : '原责任人缺失，请联系流程管理员核对后再处理。' }}</p></div>
    <div v-else-if="task.delegationState === 'RESOLVED'" class="delegation-note"><strong>受托处理已回交</strong><p>请在操作审计中查看受托人的意见，再继续审批。</p></div>
    <form v-if="pending" class="task-action-form" @submit.prevent="execute(pending)">
      <h4>{{ taskActionLabels[pending] }}</h4>
      <p v-if="pending === 'DELEGATE'" class="task-action-help">接收人处理后会回交给你，最终审批仍由你完成。</p>
      <p v-else-if="pending === 'TRANSFER'" class="task-action-help">将任务交给接收人，由其继续审批。</p>
      <p v-else-if="pending === 'REJECT'" class="task-action-help">驳回将终止本轮申请；需要申请人补充后重提，请使用退回。</p>
      <template v-if="needsRecipient(pending)">
        <p v-if="recipients.loading" role="status" class="task-action-help">正在读取可接收任务的账号…</p>
        <div v-else-if="recipients.error" role="alert" class="recipient-error"><p>{{ recipients.error }}</p><button type="button" class="secondary" :disabled="locked" @click="reloadRecipients">重新读取接收人</button></div>
        <p v-else-if="!recipients.users.length" class="task-action-help">当前没有其他可接收任务的审批账号。</p>
        <label v-else>接收人<select v-model="target" :disabled="locked" required><option value="" disabled>请选择审批账号</option><option v-for="user in recipients.users" :key="user" :value="user">{{ user }}</option></select></label>
      </template>
      <label>{{ pending === 'RETURN' ? '退回原因（必填）' : pending === 'REJECT' ? '驳回原因（必填）' : pending === 'RESOLVE' ? '处理意见（必填）' : '处理说明（选填）' }}<textarea v-model="comment" :disabled="locked" :required="needsComment(pending)" rows="3" /></label>
      <p v-if="error" role="alert" class="recipient-error">{{ error }}</p>
      <div class="form-actions"><button type="button" class="secondary" :disabled="locked" @click="cancel">取消</button><button class="primary" :disabled="locked || recipients.loading || !!recipients.error || (needsRecipient(pending) && !target)">{{ locked ? '提交中…' : pending === 'RESOLVE' ? '确认回交给 ' + task.owner : '确认' + taskActionLabels[pending] }}</button></div>
    </form>
    <div v-else class="action-bar"><button v-for="action in actions" :key="action" :class="action === 'APPROVE' || action === 'RESOLVE' ? 'primary' : action === 'RETURN' || action === 'REJECT' ? 'return' : 'secondary'" :disabled="locked" @click="prepare(action)">{{ taskActionLabels[action] }}</button></div>
    <p v-if="error && !pending" role="alert" class="recipient-error">{{ error }}</p>
  </div>
</template>

<style scoped>
.task-actions .action-bar{flex-wrap:wrap;justify-content:flex-start;gap:10px}.task-actions .action-bar .primary{order:1;margin-left:auto}.delegation-note{margin:18px 24px 0;padding:15px 17px;background:var(--soft);border:1px solid #d7e5e0;border-radius:8px;font-size:12px;color:var(--deep)}.delegation-note p,.task-action-help{font-size:12px;line-height:1.8;margin:8px 0 0;color:var(--muted)}.task-action-form .task-action-help{margin-bottom:18px}.recipient-error{color:var(--red);font-size:12px;line-height:1.8}.task-action-form select{width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:7px;background:#fff;color:var(--ink);font:inherit}.form-actions{flex-wrap:wrap}.form-actions .primary{white-space:normal;overflow-wrap:anywhere}
@media(max-width:650px){.delegation-note{margin:15px 15px 0}.task-actions .action-bar .primary{margin-left:0}.task-actions .action-bar{gap:8px}}
</style>
