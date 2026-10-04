<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type VoucherNotificationTarget } from '../api'
import { readVoucherNotificationTarget } from '../notificationInbox'
import { preparationLabels, operationLabels, voucherIssue } from '../vouchers'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: VoucherNotificationTarget] }>()
const detail = ref<VoucherNotificationTarget | null>(null), loading = ref(false), error = ref('')
const kindLabel = computed(() => detail.value?.kind === 'PAYMENT' ? '付款凭证' : detail.value?.kind === 'EMPLOYEE_ADVANCE' ? '借款挂账凭证' : '报销挂账凭证')
const operationLabel = computed(() => {
  const value = detail.value?.operation
  return !value ? '尚未登记过账命令' : value.status === 'UNKNOWN' && !value.issue && value.observedStatus === 'PENDING' ? 'ERP 正在处理原操作' : operationLabels[value.status]
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
    const value = await Promise.race([api.voucherNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原凭证读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readVoucherNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原凭证记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原凭证暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-voucher" aria-labelledby="notice-voucher-title">
    <div class="notice-voucher-heading"><h3 id="notice-voucher-title">消息对应的原凭证</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-voucher-help">消息保留发生时的提示，下方显示同一原记录的当前状态。读取不会重新准备凭证或发送过账。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原凭证…</p><p v-if="error" class="notice-voucher-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>{{ kindLabel }} · 第 {{ detail.roundNo }} 轮</p>
      <div class="notice-voucher-stages"><article><small>会计依据</small><strong>{{ detail.preparation ? preparationLabels[detail.preparation.status] : '原记录未保存准备过程' }}</strong><p v-if="detail.preparation">第 {{ detail.preparation.attempt }} 次准备 · {{ time(detail.preparation.createdAt) }}</p><p v-if="detail.preparation?.issue">{{ voucherIssue(detail.preparation.issue) }}</p></article>
        <article><small>ERP 过账</small><strong>{{ operationLabel }}</strong><p v-if="detail.operation">会计日期 {{ detail.operation.accountingDate }} · 已处理 {{ detail.operation.attempts }} 次</p><p v-if="detail.operation?.issue">{{ voucherIssue(detail.operation.issue) }}</p></article></div>
      <p v-if="detail.operation?.disputed" class="notice-voucher-error">原回执存在冲突，以下保留此前已接受的凭证事实，请按权限核对。</p>
      <p v-if="detail.reversalBound" class="notice-voucher-error">原凭证已绑定独立冲销，目前不能作为新的财务依据，请在原申请核对冲销进度。</p>
      <p v-if="detail.operation?.voucherReference" class="notice-voucher-help">{{ detail.operation.status === 'POSTED' ? '已过账凭证号' : '此前确认的凭证号' }}：{{ detail.operation.voucherReference }}<template v-if="detail.operation.postedAt"><br />ERP 过账时间：{{ time(detail.operation.postedAt) }}</template></p>
      <p class="notice-voucher-help">原记录编号：{{ detail.voucherId }}<br />资金到账、业务结算与资源恢复需分别核对。</p>
    </template>
    <div class="notice-voucher-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-voucher{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-voucher-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-voucher-heading h3{font-size:16px;margin:0}.notice-voucher-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-voucher-error{color:var(--red);font-size:13px}.notice-voucher-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-voucher-actions button{min-height:40px}.notice-voucher-stages{display:grid;grid-template-columns:1fr 1fr;gap:12px}.notice-voucher-stages article{background:var(--soft);padding:14px;border-radius:8px;min-width:0}.notice-voucher-stages small,.notice-voucher-stages strong{display:block}.notice-voucher-stages small{font-size:11px;color:var(--muted);margin-bottom:6px}.notice-voucher-stages strong{font-size:14px}.notice-voucher-stages p{font-size:12px;color:var(--muted);line-height:1.7;overflow-wrap:anywhere}@media(max-width:600px){.notice-voucher{padding:14px}.notice-voucher-stages{grid-template-columns:1fr}}
</style>
