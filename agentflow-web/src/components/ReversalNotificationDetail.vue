<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type ReversalNotificationTarget } from '../api'
import { readReversalNotificationTarget } from '../notificationInbox'
import { reversalPreparationLabels, reversalExecutionLabels, reversalExecutionIssue, reversalRetirementBasisLabels } from '../voucherReversalExecution'
import { operationLabels } from '../vouchers'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: ReversalNotificationTarget] }>()
const detail = ref<ReversalNotificationTarget | null>(null), loading = ref(false), error = ref('')
const statusLabel = computed(() => {
  const operation = detail.value?.operation
  if (!operation) return '尚未登记本次冲销命令'
  if (operation.status === 'UNKNOWN' && operation.observedStatus === 'PENDING' && !operation.issue) return 'ERP 正在处理原冲销'
  if (operation.issue === 'RECHECK_REQUESTED') return '正在按原编号复查冲销结果'
  return reversalExecutionLabels[operation.status]
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
    const value = await Promise.race([api.reversalNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原冲销读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readReversalNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原冲销记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原冲销暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-reversal" aria-labelledby="notice-reversal-title">
    <div class="notice-reversal-heading"><h3 id="notice-reversal-title">消息对应的原冲销操作</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-reversal-help">消息保留发生时的提示，下方读取同一原操作的当前结果。读取不会发送冲销命令。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原冲销…</p><p v-if="error" class="notice-reversal-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮 · {{ reversalPreparationLabels[detail.preparation.status] }}</p>
      <p class="notice-reversal-help">准备时间 {{ time(detail.preparation.requestedAt) }} · 冲销会计日期 {{ detail.preparation.accountingDate }}</p>
      <p v-if="detail.preparation.status === 'UNAVAILABLE'" class="notice-reversal-error">本次准备暂不可用，未登记冲销命令，请在原申请核对处理。</p>
      <p v-if="detail.preparation.status === 'VOIDED'" class="notice-reversal-error">本次准备依据变化，未登记冲销命令。</p>
      <strong>{{ statusLabel }}</strong>
      <template v-if="detail.operation">
        <p class="notice-reversal-help">执行更新时间 {{ time(detail.operation.updatedAt) }} · 已尝试发送 {{ detail.operation.attempts }} 次</p>
        <p v-if="detail.operation.issue" class="notice-reversal-help">{{ reversalExecutionIssue(detail.operation.issue) }}</p>
        <p v-if="detail.operation.status === 'UNKNOWN' && detail.operation.issue && detail.operation.issue !== 'RECHECK_REQUESTED'" class="notice-reversal-error">暂时无法确认原冲销结果，系统将按原编号查询。此时不能推定冲销成功或失败。</p>
        <p v-if="detail.operation.status === 'NOT_FOUND'" class="notice-reversal-help">ERP 权威查询确认原冲销查无，系统未自动重发；请由有权限的经办人在原申请核对处理。</p>
        <p v-if="detail.operation.disputed" class="notice-reversal-error">回执存在冲突，此前已接受的会计事实仍保留，请在原申请核对处理。</p>
        <p v-if="detail.operation.voucherReference" class="notice-reversal-help">此前确认的反向凭证：{{ detail.operation.voucherReference }}<br />过账时间：{{ time(detail.operation.postedAt!) }}</p>
      </template>
      <div v-if="detail.retirement">
        <strong>已安全结束本次冲销</strong><p class="notice-reversal-help">{{ reversalRetirementBasisLabels[detail.retirement.basis] }} · {{ time(detail.retirement.retiredAt) }}</p>
      </div>
      <p class="notice-reversal-help">原凭证当前状态：{{ operationLabels[detail.originalStatus] }}<br />{{ detail.originalHeld ? '原凭证当前仍绑定冲销，暂不可用于后续业务；绑定可能来自另一次冲销。' : '原凭证当前没有冲销绑定，能否继续办理仍以原业务校验为准。' }}</p>
      <p class="notice-reversal-help">本次冲销编号：{{ detail.reversalId }}<br />原凭证操作编号：{{ detail.operationId }}<br />冲销、原凭证恢复、预算及资金状态分别核对；本页结果不表示银行已退款。</p>
    </template>
    <div class="notice-reversal-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-reversal{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-reversal-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-reversal-heading h3{font-size:16px;margin:0}.notice-reversal-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-reversal-error{color:var(--red);font-size:13px}.notice-reversal-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-reversal-actions button{min-height:40px}@media(max-width:600px){.notice-reversal{padding:14px}}
</style>
