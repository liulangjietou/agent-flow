<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { moneyLabel, type ExpenseDetail } from '../expenses'
import { precheckIssues, type PrecheckView } from '../expenseDraft'
import { requireAdvanceOffsetSuggestion, type AdvanceOffsetSuggestion } from '../advanceOffsetSuggestion'

const props = defineProps<{ detail: ExpenseDetail; precheck: PrecheckView; scopeKey: string; locked: boolean }>()
const emit = defineEmits<{ apply: [suggestion: AdvanceOffsetSuggestion] }>()
const suggestion = ref<AdvanceOffsetSuggestion | null>(null), reading = ref(false), confirming = ref(false), error = ref(''), notice = ref('')
let epoch = 0, controller: AbortController | null = null
const alreadyApplied = computed(() => !!suggestion.value && JSON.stringify(props.detail.content.advanceOffsets) === JSON.stringify(suggestion.value.items.map(row => ({ advanceId: row.advanceId, amount: row.amount }))))
const blocked = computed(() => props.locked || reading.value || !props.scopeKey || !props.precheck.usable)
function stop() { epoch++; controller?.abort(); controller = null }
/** 读取及采纳复核均有超时和身份隔离，切换单据后旧请求不能填入草稿。 */
async function load(applying = false) {
  if (blocked.value || applying && (!confirming.value || !suggestion.value || alreadyApplied.value)) return
  const reviewed = suggestion.value ? JSON.stringify(suggestion.value) : null
  stop(); const version = epoch, request = new AbortController(); controller = request
  reading.value = true; error.value = ''; notice.value = ''; confirming.value = false
  const timeout = setTimeout(() => {
    if (version !== epoch) return
    stop(); reading.value = false; suggestion.value = null; error.value = '借款建议读取超时，请重新读取。'
  }, 12_000)
  try {
    const result = await api.advanceOffsetSuggestion(props.detail.id, props.precheck.job.id, request.signal)
    if (version !== epoch) return
    const current = requireAdvanceOffsetSuggestion(result, props.detail, props.precheck)
    suggestion.value = current
    if (applying && !props.locked) {
      if (JSON.stringify(current) !== reviewed) notice.value = '借款余额或版本已变化，建议已更新，请重新核对并确认。'
      else emit('apply', current)
    }
  } catch (cause) {
    if (version === epoch) {
      suggestion.value = null
      error.value = precheckIssues[(cause as { code?: string }).code ?? ''] ?? '暂时无法获取有效借款建议，请刷新预检后重试。'
    }
  } finally { clearTimeout(timeout); if (version === epoch) { reading.value = false; controller = null } }
}
function prepare() {
  if (blocked.value || !suggestion.value || alreadyApplied.value) return
  try { requireAdvanceOffsetSuggestion(suggestion.value, props.detail, props.precheck); confirming.value = true }
  catch { suggestion.value = null; error.value = '借款建议已过期，请重新预检。' }
}
watch(() => [props.scopeKey, props.detail.id, props.detail.applicationVersion, props.detail.financialVersion, props.precheck.job.id, props.precheck.usable, props.precheck.validUntil], () => {
  stop(); reading.value = false; confirming.value = false; suggestion.value = null; error.value = ''; notice.value = ''
  if (props.scopeKey && props.precheck.usable) void load()
}, { immediate: true, flush: 'sync' })
watch(() => props.locked, () => { confirming.value = false })
onUnmounted(stop)
</script>

<template>
  <section class="offset-suggestion" aria-label="借款冲销建议">
    <h4>先冲销较早放款的借款</h4>
    <p>系统按放款日期从早到晚建议，同日按借款编号排序。金额包含本单保留的预留，已扣除其他单占用及待复核冻结；你仍可手动调整。</p>
    <p v-if="error" class="offset-error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="reading" role="status">正在读取本人借款并核对可用额度…</p>
    <template v-if="suggestion">
      <p v-if="!suggestion.items.length">当前没有可用于本次核定金额的借款。</p>
      <ol v-else><li v-for="row in suggestion.items" :key="row.advanceId"><span>{{ row.paidOn }} 放款 · {{ row.advanceId }}</span><span>可建议 {{ moneyLabel(row.capacity) }} · 建议冲销 <strong>{{ moneyLabel(row.amount) }}</strong></span></li></ol>
      <p>建议冲销 {{ moneyLabel(suggestion.offsetTotal) }}，冲销后应付 {{ moneyLabel(suggestion.payable) }}。</p>
      <p v-if="suggestion.selectionLimitReached">本次建议已达 50 笔上限，尚未覆盖全部核定金额，请核对应付余额。</p>
      <p v-if="alreadyApplied">当前草稿已采用这些冲销金额。</p>
      <template v-else>
        <button v-if="!confirming" type="button" class="secondary" :disabled="blocked" @click="prepare">核对并采用建议</button>
        <div v-else role="group" aria-label="确认采用冲销建议"><p>确认用以上建议替换当前 {{ detail.content.advanceOffsets.length }} 笔冲销？采纳后可调整，保存草稿并重新预检后才能提交。</p><button type="button" class="secondary" :disabled="blocked" @click="load(true)">确认替换草稿冲销</button><button type="button" class="quiet" :disabled="reading" @click="confirming = false">继续核对</button></div>
      </template>
    </template>
    <button type="button" class="quiet" :disabled="blocked" @click="load()">重新读取借款建议</button>
  </section>
</template>

<style scoped>
.offset-suggestion{border:1px solid var(--line);border-radius:10px;background:var(--paper);padding:16px;margin:18px 0;font-size:12px;line-height:1.9}.offset-suggestion h4{margin:0;font-size:14px}.offset-suggestion p{color:var(--muted)}.offset-suggestion ol{padding-left:20px;max-height:320px;overflow:auto}.offset-suggestion li{margin:10px 0;overflow-wrap:anywhere}.offset-suggestion li span{display:block}.offset-suggestion button{margin:4px 10px 4px 0}.offset-suggestion .offset-error{color:var(--red)}
</style>
