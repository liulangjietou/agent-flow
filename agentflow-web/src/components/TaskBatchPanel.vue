<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type PendingTaskItem } from '../api'
import { MAX_TASK_BATCH_SIZE, TaskBatch, type TaskBatchAction } from '../taskBatch'

const props = defineProps<{ items: PendingTaskItem[]; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ close: []; changed: [] }>()
// 名单只取打开时已加载的摘要；翻页或刷新不能悄悄把新任务加入本次选择。
const candidates = props.items.map(item => ({ ...item }))
const batch = reactive(new TaskBatch(api.task, api.taskAction))
const action = ref<TaskBatchAction>('CLAIM'), selected = ref<string[]>([]), reason = ref(''), error = ref('')
const checking = computed(() => batch.stage === 'CHECKING')
const executing = computed(() => batch.stage === 'EXECUTING')
const readyCount = computed(() => batch.rows.filter(row => row.state === 'READY').length)
const succeededCount = computed(() => batch.rows.filter(row => row.state === 'SUCCEEDED').length)
const unknown = computed(() => batch.rows.some(row => row.state === 'UNKNOWN'))
const label = computed(() => action.value === 'CLAIM' ? '领取' : '释放')
const selectable = (item: PendingTaskItem) => item.delegationState !== 'PENDING' && (action.value === 'CLAIM' ? !item.assignee : !!item.assignee)
watch(action, () => { batch.clear(); selected.value = []; reason.value = ''; error.value = '' }, { flush: 'sync' })
watch(() => props.scopeKey, () => { batch.clear(); emit('close') }, { flush: 'sync' })
onUnmounted(() => batch.clear())

/** 选择清单只触发读取；确认按钮才产生领取或释放操作。 */
async function prepare() {
  if (props.locked || checking.value || executing.value) return
  error.value = ''
  try { await batch.prepare(action.value, candidates.filter(item => selected.value.includes(item.taskId))) }
  catch (cause) { error.value = (cause as Error).message }
}
/** 结束后刷新实际队列；恢复原请求由工作台负责，不自动发送剩余任务。 */
async function execute() {
  if (props.locked || executing.value) return
  error.value = ''
  const scope = props.scopeKey
  try { await batch.execute(reason.value); if (scope === props.scopeKey && batch.stage === 'DONE') emit('changed') }
  catch (cause) { if (scope === props.scopeKey) error.value = (cause as Error).message }
}
function close() { if (!executing.value) { batch.clear(); emit('close') } }
</script>

<template>
  <section class="task-batch" aria-labelledby="task-batch-title">
    <div class="batch-heading"><h4 id="task-batch-title">批量领取与释放</h4><button type="button" class="quiet" :disabled="executing" @click="close">关闭批量面板</button></div>
    <p>从已加载的待办中选择，最多 {{ MAX_TASK_BATCH_SIZE }} 项。每项单独核对权限并记录结果，领取或释放不会形成审批结论。</p>
    <form v-if="batch.stage === 'EMPTY' || checking" @submit.prevent="prepare">
      <fieldset :disabled="locked || checking"><legend class="sr-only">选择批量操作与任务</legend>
        <label class="batch-action">本次操作<select v-model="action"><option value="CLAIM">领取任务</option><option value="RELEASE">释放任务</option></select></label>
        <ul class="batch-list"><li v-for="item in candidates" :key="item.taskId"><label class="batch-choice"><input v-model="selected" type="checkbox" :value="item.taskId" :disabled="!selectable(item) || (selected.length >= MAX_TASK_BATCH_SIZE && !selected.includes(item.taskId))" :aria-label="`选择 ${item.businessNo} ${item.taskName}`" /><span><strong>{{ item.title }}</strong><small>{{ item.businessNo }} · {{ item.taskName }}</small><small>{{ selectable(item) ? '可选择，确认前重新核对' : '列表状态不适用本次操作' }}</small></span></label></li></ul>
        <button type="submit" class="secondary" :disabled="!selected.length">{{ checking ? '正在核对…' : `核对已选 ${selected.length} 项` }}</button>
      </fieldset>
    </form>
    <template v-else>
      <p v-if="batch.stage === 'PREVIEW'">可{{ label }} {{ readyCount }} 项。不能办理的任务会跳过；以下清单确认后不会自动增加或更换。</p>
      <p v-else role="status">已{{ label }} {{ succeededCount }} / {{ batch.rows.length }} 项；其余结果逐项列出。</p>
      <ul class="batch-list"><li v-for="row in batch.rows" :key="row.taskId" :data-state="row.state"><strong>{{ row.title }}</strong><small>{{ row.businessNo }} · {{ row.taskName }}</small><span>{{ row.message }}</span></li></ul>
      <form v-if="batch.stage === 'PREVIEW' || executing" @submit.prevent="execute"><label class="batch-reason">办理说明<textarea v-model="reason" maxlength="500" rows="3" :disabled="locked || executing" /></label><button type="submit" class="primary" :disabled="locked || executing || !readyCount || !reason.trim()">{{ executing ? '正在逐项确认…' : `确认${label} ${readyCount} 项` }}</button></form>
      <p v-if="unknown" role="alert">有一项结果尚未确认，后续任务未发送。关闭此面板后，使用“恢复待确认操作”核对原请求；完成后重新选择其余任务。</p>
      <p v-if="batch.stage === 'DONE'">此处保留本次办理结果。需继续处理时，关闭面板并从刷新后的待办重新选择。</p>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.task-batch{padding:18px 20px;border-top:1px solid var(--line);border-bottom:1px solid var(--line);background:var(--paper);font-size:12px;line-height:1.7;min-width:0}.batch-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.batch-heading h4{margin:0;font-size:14px}.task-batch p{color:var(--muted);margin:10px 0}.task-batch fieldset{border:0;padding:0;margin:0;min-width:0}.batch-action,.batch-reason{display:grid;gap:6px;margin:12px 0}.task-batch select,.task-batch textarea{width:100%;padding:9px;border:1px solid var(--line);border-radius:7px;background:#fff;font:inherit;color:var(--ink);box-sizing:border-box}.batch-list{list-style:none;max-height:380px;overflow:auto;padding:0;margin:12px 0;border:1px solid var(--line);border-radius:8px;background:#fff}.batch-list li{display:flex;flex-direction:column;gap:4px;padding:12px;border-bottom:1px solid var(--line);overflow-wrap:anywhere}.batch-list li:last-child{border-bottom:0}.batch-list small{display:block;color:var(--muted);font-size:11px}.batch-choice{display:flex;gap:10px;align-items:flex-start}.batch-choice input{margin-top:5px;flex:0 0 auto}.batch-list [data-state="SUCCEEDED"]>span{color:var(--teal)}.batch-list [data-state="FAILED"]>span,.batch-list [data-state="UNKNOWN"]>span,.task-batch [role="alert"]{color:var(--red)}.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@media(max-width:650px){.task-batch{padding:14px}.batch-heading{align-items:flex-start}.batch-heading button{max-width:50%}}
</style>
