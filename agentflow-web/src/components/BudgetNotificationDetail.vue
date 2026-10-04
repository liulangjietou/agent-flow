<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type BudgetNotificationTarget } from '../api'
import { readBudgetNotificationTarget } from '../notificationInbox'
import { budgetActionLabels, budgetStatusLabels, budgetIssueLabel } from '../notificationInbox'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: BudgetNotificationTarget] }>()
const detail = ref<BudgetNotificationTarget | null>(null), loading = ref(false), error = ref('')
const statusLabel = computed(() => {
  const value = detail.value
  if (!value) return ''
  if (value.observedStatus === 'PENDING') return '预算系统正在处理原操作'
  if (value.observedStatus === 'NOT_FOUND') return '查询查无，原命令待重试'
  return budgetStatusLabels[value.status]
})
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.budgetNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原预算读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readBudgetNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原预算记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原预算暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-budget" aria-labelledby="notice-budget-title">
    <div class="notice-budget-heading"><h3 id="notice-budget-title">消息对应的原预算操作</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-budget-help">消息保留发生时的提示，下方读取同一原操作的当前结果。读取不会发送预算命令。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原预算…</p><p v-if="error" class="notice-budget-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>{{ budgetActionLabels[detail.action] }} · 第 {{ detail.roundNo }} 轮 · 财务版本 {{ detail.financialVersion }}</p>
      <strong>{{ statusLabel }}</strong><p class="notice-budget-help">更新时间 {{ time(detail.updatedAt) }} · 已处理 {{ detail.attempts }} 次</p>
      <p v-if="detail.issue" class="notice-budget-help">{{ budgetIssueLabel(detail.issue) }}</p>
      <p v-if="detail.status === 'UNKNOWN' && detail.issue" class="notice-budget-error">暂时无法确认原操作结果，系统将按原编号查询。这不表示预算系统已拒绝，也不表示已成功。</p>
      <p v-if="detail.status === 'REJECTED'" class="notice-budget-error">预算系统已明确拒绝这笔原操作，请在原申请核对处理。</p>
      <p v-if="detail.observedStatus === 'NOT_FOUND'" class="notice-budget-help">权威查询确认原操作查无，系统将保留同一命令和编号进行重试。</p>
      <p v-if="detail.reference" class="notice-budget-help">原操作回执：{{ detail.reference }}<br />原账版本：{{ detail.ledgerRevision }}<br />确认时间：{{ time(detail.appliedAt!) }}</p>
      <p class="notice-budget-help">原操作编号：{{ detail.operationId }}<br />这里显示原命令的处理结果；当前预算占用、付款与结算进度请在申请中分别核对。</p>
    </template>
    <div class="notice-budget-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-budget{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-budget-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-budget-heading h3{font-size:16px;margin:0}.notice-budget-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-budget-error{color:var(--red);font-size:13px}.notice-budget-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-budget-actions button{min-height:40px}@media(max-width:600px){.notice-budget{padding:14px}}
</style>
