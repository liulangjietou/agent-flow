<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type SupplierSettlementNotificationTarget } from '../api'
import { readSupplierSettlementNotificationTarget } from '../supplierSettlementNotification'
import { supplierSettlementNoticeLabels } from '../supplierSettlementNotification'
import { supplierSettlementLabels, settlementPreparationLabels, settlementIssueLabels } from '../supplierSettlement'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: SupplierSettlementNotificationTarget] }>()
const detail = ref<SupplierSettlementNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.supplierSettlementNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原供应商结算读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readSupplierSettlementNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原供应商结算记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原供应商结算暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-settlement" aria-labelledby="notice-settlement-title">
    <div class="notice-settlement-heading"><h3 id="notice-settlement-title">消息对应的原供应商结算</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-settlement-help">消息固定原结算编号，下方读取该次准备和执行的当前状态；后续新准备不会替换原记录。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原结算…</p><p v-if="error" class="notice-settlement-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮 · 原记账日期 {{ detail.accountingDate }}</p>
      <div class="notice-settlement-fact"><h4>消息发生时</h4><p>{{ supplierSettlementNoticeLabels[detail.fact] }}</p></div>
      <div class="notice-settlement-fact"><h4>当前原结算状态</h4>
        <p>{{ settlementPreparationLabels[detail.preparation.status] }} · 准备版本 {{ detail.preparation.version }}</p>
        <p v-if="detail.preparation.issue" class="notice-settlement-help">{{ settlementIssueLabels[detail.preparation.issue] || '请在原申请核对准备原因。' }}</p>
        <template v-if="detail.operation"><p><strong>{{ supplierSettlementLabels[detail.operation.status] }}</strong> · ERP 版本 {{ detail.operation.version }}</p>
          <p v-if="detail.operation.issue" class="notice-settlement-help">{{ settlementIssueLabels[detail.operation.issue] || '请在原申请核对执行原因。' }}</p></template>
        <p v-else>本次尚未登记 ERP 核销指令。</p>
        <p v-if="detail.retirement">本次结算已安全结束 · {{ time(detail.retirement.retiredAt) }}</p>
        <p v-if="detail.completion">原本地占用已完成 · {{ time(detail.completion.completedAt) }}</p>
        <p v-else>本次尚无本地占用完成记录。</p>
      </div>
      <p class="notice-settlement-help">ERP 核销、本地占用完成和安全结束分别核对。原银行已付事实、归档及后续调整均独立保留。</p>
    </template>
    <div class="notice-settlement-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-settlement{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-settlement-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-settlement-heading h3{font-size:16px;margin:0}.notice-settlement-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-settlement-error{color:var(--red);font-size:13px}.notice-settlement-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-settlement-actions button{min-height:40px}@media(max-width:600px){.notice-settlement{padding:14px}}


.notice-settlement-fact{border-top:1px solid var(--line);padding-top:12px;margin-top:16px}.notice-settlement-fact h4{font-size:13px;margin:0}
</style>
