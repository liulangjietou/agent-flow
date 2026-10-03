<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, type InboxMessage, type SupplierAdjustmentNotificationTarget } from '../api'
import { readSupplierAdjustmentNotificationTarget } from '../supplierAdjustmentNotification'
import { supplierAdjustmentNoticeLabels } from '../supplierAdjustmentNotification'
import { adjustmentLabels, adjustmentPreparationLabels, adjustmentIssueLabels } from '../supplierAdjustment'

const props = defineProps<{ message: InboxMessage; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ close: []; open: [target: SupplierAdjustmentNotificationTarget] }>()
const detail = ref<SupplierAdjustmentNotificationTarget | null>(null), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | null = null
function stop() { generation++; controller?.abort(); controller = null }
/** 每次读取原编号，权限变化、身份切换和超时都清除旧财务事实。 */
async function load() {
  stop(); detail.value = null; error.value = ''; loading.value = false
  if (!props.scopeKey) return
  const current = generation, message = props.message, request = new AbortController(); controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const value = await Promise.race([api.supplierAdjustmentNotificationTarget(message.id, request.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { request.abort(); reject(new Error('原供应商应付调整读取超时，请重试。')) }, 12_000) })])
    if (current === generation) detail.value = readSupplierAdjustmentNotificationTarget(value, message)
  } catch (cause) {
    if (current === generation) error.value = [401, 403, 404].includes((cause as { status?: number })?.status ?? 0)
      ? '当前账号已无法读取这条原供应商应付调整记录，请核对当前申请与财务字段权限。'
      : cause instanceof Error ? cause.message : '原供应商应付调整暂时无法读取，请重试。'
  } finally { clearTimeout(timeout); if (current === generation) { loading.value = false; controller = null } }
}
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
watch(() => JSON.stringify([props.scopeKey, props.message.id]), () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(stop)
</script>

<template>
  <section class="notice-adjustment" aria-labelledby="notice-adjustment-title">
    <div class="notice-adjustment-heading"><h3 id="notice-adjustment-title">消息对应的原供应商应付调整</h3><button type="button" class="quiet" :disabled="locked" @click="emit('close')">收起</button></div>
    <p class="notice-adjustment-help">消息固定原应付调整编号；ERP 调整先保存结果，本地账务完成需要另行核对银行原件。后续调整不会替换原记录。</p>
    <p v-if="loading" role="status">正在核对当前权限并读取原应付调整…</p><p v-if="error" class="notice-adjustment-error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <p>第 {{ detail.roundNo }} 轮 · 原记账日期 {{ detail.accountingDate }}</p>
      <div class="notice-adjustment-fact"><h4>消息发生时</h4><p>{{ supplierAdjustmentNoticeLabels[detail.fact] }}</p></div>
      <div class="notice-adjustment-fact"><h4>当前原应付调整状态</h4>
        <p>{{ adjustmentPreparationLabels[detail.preparation.status] }} · 准备版本 {{ detail.preparation.version }}</p>
        <p v-if="detail.preparation.issue" class="notice-adjustment-help">{{ adjustmentIssueLabels[detail.preparation.issue] || '请在原申请核对准备原因。' }}</p>
        <template v-if="detail.operation"><p><strong>{{ adjustmentLabels[detail.operation.status] }}</strong> · ERP 版本 {{ detail.operation.version }}</p>
          <p v-if="detail.operation.issue" class="notice-adjustment-help">{{ adjustmentIssueLabels[detail.operation.issue] || '请在原申请核对执行原因。' }}</p></template>
        <p v-else>本次尚未登记 ERP 调整指令。</p>
        <p v-if="detail.retirement">本次应付调整已安全结束 · {{ time(detail.retirement.retiredAt) }}</p>
        <p v-if="detail.completion">原本地账务已完成 · {{ time(detail.completion.completedAt) }}</p>
        <p v-else>本次尚无本地账务完成记录。</p>
      </div>
      <p class="notice-adjustment-help">ERP 调整、本地账务完成和安全结束分别核对。原银行已付事实、归档及后续调整均独立保留。</p>
    </template>
    <div class="notice-adjustment-actions"><button type="button" class="secondary" :disabled="locked || loading" @click="load">重新读取</button><button v-if="detail" type="button" class="secondary" :disabled="locked || loading" @click="emit('open', detail)">查看原申请轮次</button></div>
  </section>
</template>

<style scoped>
.notice-adjustment{border:1px solid var(--line);border-radius:10px;background:white;padding:20px;margin-top:20px;min-width:0}.notice-adjustment-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.notice-adjustment-heading h3{font-size:16px;margin:0}.notice-adjustment-help{color:var(--muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.notice-adjustment-error{color:var(--red);font-size:13px}.notice-adjustment-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.notice-adjustment-actions button{min-height:40px}@media(max-width:600px){.notice-adjustment{padding:14px}}


.notice-adjustment-fact{border-top:1px solid var(--line);padding-top:12px;margin-top:16px}.notice-adjustment-fact h4{font-size:13px;margin:0}
</style>
