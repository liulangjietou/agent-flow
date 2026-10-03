<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type RepaymentReviewNotificationTarget } from '../api'
import { readRepaymentReviewNotificationTarget } from '../repaymentReviewNotification'
import { repaymentReviewNoticeLabels, repaymentReviewObservationLabels } from '../repaymentReviewNotification'
import { repaymentReviewCheckLabels } from '../repaymentReview'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: RepaymentReviewNotificationTarget] }>()
const detail = ref<RepaymentReviewNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.repaymentReviewNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原还款复核读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readRepaymentReviewNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原还款复核记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原还款复核暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-settlement" aria-labelledby="notice-settlement-title">
    <div class="notice-settlement-heading"><h3 id="notice-settlement-title">消息对应的原还款复核</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-settlement-help">消息固定本次复核查询及其明确裁决。后续还款、退款和其他复核分别保留，不替换这条原记录。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原复核记录…</p><p v-if="error" class="notice-settlement-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮 · 原查询版本 {{ detail.version }}</p>
      <div class="notice-settlement-fact"><h4>消息发生时</h4><p>{{ repaymentReviewNoticeLabels[detail.fact] }}</p></div>
      <div class="notice-settlement-fact"><h4>当前原查询状态</h4><p><strong>{{ repaymentReviewCheckLabels[detail.status] }}</strong></p>
        <p v-if="detail.issue" class="notice-settlement-help">{{ detail.issue === 'SOURCE_CHANGED' ? '请在原申请核对当前来源与人员资格。' : '本次未取得可用原件，请在原申请核对后处理。' }}</p>
        <p class="notice-settlement-help">发起 {{ time(detail.requestedAt) }} · 更新 {{ time(detail.updatedAt) }}</p>
        <template v-if="detail.observation"><p>{{ repaymentReviewObservationLabels[detail.observation.outcome] }} · 原件版本 {{ detail.observation.revision }}</p>
          <p class="notice-settlement-help">观察 {{ time(detail.observation.observedAt) }} · 当时证据到期 {{ time(detail.observation.validUntil) }}；读取不会续期。</p></template>
        <p v-else>本次没有可展示的还款复核原件。</p>
        <p v-if="detail.resolution">本次依据已明确裁决 · 借款版本 {{ detail.resolution.advanceVersion }} · {{ time(detail.resolution.resolvedAt) }}</p>
        <p v-else>本次查询尚未裁决；查询原件不等于借款余额已经调整。</p>
      </div>
      <p class="notice-settlement-help">原放款、还款登记、还款退回和其他占用分别核对；读取不产生财务办理许可。</p>
    </template>
    <div class="notice-settlement-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-settlement{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-settlement-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-settlement-heading h3{font-size:16px;margin:0}.notice-settlement-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-settlement-error{color:var(--red);font-size:13px}.notice-settlement-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-settlement-actions button{min-height:40px}@media(max-width:600px){.notice-settlement{padding:14px}}


.notice-settlement-fact{border-top:1px solid var(--line);padding-top:12px;margin-top:16px}.notice-settlement-fact h4{font-size:13px;margin:0}
</style>
