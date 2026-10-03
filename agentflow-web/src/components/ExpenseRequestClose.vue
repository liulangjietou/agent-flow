<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import type { PriorRequestItem } from '../expenses'
import { expenseRequestCloseError } from '../expenseRequestClosure'
import { isDefinitiveWriteFailure } from '../pendingWrites'

const props = defineProps<{ item: PriorRequestItem; scopeKey: string; locked?: boolean }>()
const emit = defineEmits<{ changed: []; refresh: [] }>()
const confirming = ref(false), comment = ref(''), sending = ref(false), error = ref(''), refreshRequired = ref(false), completed = ref(false)
let generation = 0
const unavailable = computed(() => !props.scopeKey || props.locked || props.item.closed || sending.value || completed.value || refreshRequired.value)
function reset() {
  generation++; confirming.value = false; comment.value = ''; sending.value = false
  error.value = ''; refreshRequired.value = false; completed.value = false
}
watch(() => [props.scopeKey, props.item.id, props.item.version, props.item.closed], reset, { flush: 'sync' })
onUnmounted(reset)
function begin() { if (!unavailable.value) { confirming.value = true; error.value = '' } }
function cancel() { if (!sending.value && !refreshRequired.value) { confirming.value = false; comment.value = ''; error.value = '' } }

/** 确认前不产生写入；未知结果由全局原请求恢复处理，禁止改正文重发。 */
async function confirm() {
  if (unavailable.value || !confirming.value) return
  const reason = comment.value.trim()
  if (!reason || comment.value.length > 2000) { error.value = '请填写 1–2000 字的关闭原因。'; return }
  if (!Number.isSafeInteger(props.item.version) || props.item.version < 1) { error.value = '额度版本无效，请刷新记录。'; refreshRequired.value = true; return }
  const token = generation
  sending.value = true; error.value = ''
  try {
    await api.closeExpenseRequest(props.item.id, { expectedVersion: props.item.version, comment: reason })
    if (token !== generation) return
    completed.value = true; confirming.value = false; comment.value = ''; emit('changed')
  } catch (cause) {
    if (token !== generation) return
    error.value = expenseRequestCloseError(cause)
    refreshRequired.value = true
    // 明确失败可以重读，未知结果须先恢复原请求；锁定由工作台统一展示。
    if (!isDefinitiveWriteFailure(cause)) confirming.value = false
  } finally { if (token === generation) sending.value = false }
}
</script>

<template>
  <div class="credit-close">
    <p v-if="item.closed" class="closure-note">额度已关闭，已有预留仍可结算或释放。</p>
    <p v-else-if="completed" class="closure-note" role="status">额度已关闭，正在刷新余额。</p>
    <template v-else>
      <button v-if="!confirming && !refreshRequired" type="button" class="secondary" :disabled="unavailable" @click="begin">关闭剩余额度</button>
      <form v-if="confirming" class="closure-form" @submit.prevent="confirm">
        <p class="closure-note">关闭后不能新增或增加占用，已有预留仍可结算或释放。原申请的批准结果保留，此操作不可撤销。</p>
        <label :for="`credit-close-reason-${item.id}`">关闭原因</label>
        <textarea :id="`credit-close-reason-${item.id}`" v-model="comment" rows="3" maxlength="2000" required :disabled="unavailable" />
        <div class="closure-actions"><button type="submit" class="primary" :disabled="unavailable">{{ sending ? '正在关闭…' : '确认关闭额度' }}</button><button type="button" class="secondary" :disabled="sending || refreshRequired" @click="cancel">暂不关闭</button></div>
      </form>
      <p v-if="error" class="closure-error" role="alert">{{ error }}</p>
      <button v-if="refreshRequired" type="button" class="secondary" :disabled="locked || sending" @click="emit('refresh')">刷新本人额度</button>
    </template>
  </div>
</template>

<style scoped>
.credit-close{margin-top:16px;padding-top:14px;border-top:1px solid var(--line)}
.closure-note{color:var(--muted);font-size:12px;line-height:1.8;margin:0 0 12px}
.closure-form{max-width:640px}.closure-form label{display:block;font-size:12px;margin-bottom:8px}
.closure-form textarea{box-sizing:border-box;width:100%;resize:vertical;font:inherit;line-height:1.7;padding:10px;border:1px solid var(--line);border-radius:8px}
.closure-actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:10px}.closure-error{color:var(--red);font-size:12px;line-height:1.8}
</style>
