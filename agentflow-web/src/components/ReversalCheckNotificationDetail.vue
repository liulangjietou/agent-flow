<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type ReversalCheckNotificationTarget } from '../api'
import { readReversalCheckNotificationTarget } from '../notificationInbox'
import { reversalCheckLabels, reversalIssue } from '../voucherReversal'
import { operationLabels } from '../vouchers'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: ReversalCheckNotificationTarget] }>()
const detail = ref<ReversalCheckNotificationTarget | null>(null), loading = ref(false), error = ref('')
const statusLabel = computed(() => {
  const value = detail.value
  if (!value) return ''
  if (value.status === 'CHECKED' && value.observation?.status === 'UNRESOLVED') return '本次查询未核清外部反向凭证'
  if (value.status === 'CHECKED') return '本次查询已返回完整反向凭证，尚未登记'
  return reversalCheckLabels[value.status]
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
    const value = await Promise.race([api.reversalCheckNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原冲销核对读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readReversalCheckNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原冲销核对记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原冲销核对暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-reversal-check" aria-labelledby="notice-reversal-check-title">
    <div class="notice-reversal-check-heading"><h3 id="notice-reversal-check-title">消息对应的原外部冲销核对</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-reversal-check-help">消息保留发生时的提示，下方读取同一原核对的当前记录。读取不会查询 ERP、发送冲销或登记凭证。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原冲销核对…</p><p v-if="error" class="notice-reversal-check-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮 · <strong>{{ statusLabel }}</strong></p>
      <p class="notice-reversal-check-help">发起时间 {{ time(detail.requestedAt) }} · 更新时间 {{ time(detail.updatedAt) }}</p>
      <p v-if="detail.issue" class="notice-reversal-check-help">{{ reversalIssue(detail.issue) }}</p>
      <p v-if="!detail.record" class="notice-reversal-check-help">本次核对没有登记记录；其他核对的处理结果请在原申请分别查看。</p>
      <p v-if="detail.observation?.status === 'UNRESOLVED'" class="notice-reversal-check-error">未核清不表示未发生冲销，也不能作为登记或恢复业务的依据。</p>
      <template v-if="detail.observation">
        <p class="notice-reversal-check-help">本次观察时间 {{ time(detail.observation.observedAt) }} · 外部版本 {{ detail.observation.revision }}<br />原证据有效期至 {{ time(detail.observation.validUntil) }}，当前能否办理仍需在原申请重新校验。</p>
        <p v-if="detail.observation.voucherReference" class="notice-reversal-check-help">查询时核得的反向凭证：{{ detail.observation.voucherReference }}<br />会计日期 {{ detail.observation.accountingDate }} · 过账时间 {{ time(detail.observation.postedAt!) }}</p>
      </template>
      <p v-if="detail.record" class="notice-reversal-check-help">本次已明确登记 · {{ time(detail.record.recordedAt) }}<br />登记编号：{{ detail.record.id }}</p>
      <p class="notice-reversal-check-help">原凭证当前状态：{{ operationLabels[detail.originalStatus] }}<br />{{ detail.originalHeld ? '原凭证当前仍绑定独立冲销，相关业务状态需另行核对。' : '原凭证当前没有独立冲销绑定。' }}</p>
      <p class="notice-reversal-check-help">原核对编号：{{ detail.checkId }}<br />原凭证操作编号：{{ detail.operationId }}<br />核对与登记不产生银行退款，也不自动恢复预算、借款或费用资源。</p>
    </template>
    <div class="notice-reversal-check-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-reversal-check{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-reversal-check-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-reversal-check-heading h3{font-size:16px;margin:0}.notice-reversal-check-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-reversal-check-error{color:var(--red);font-size:13px}.notice-reversal-check-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-reversal-check-actions button{min-height:40px}@media(max-width:600px){.notice-reversal-check{padding:14px}}
</style>
