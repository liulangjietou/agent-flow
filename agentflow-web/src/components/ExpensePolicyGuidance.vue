<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import type { ExpenseLine } from '../expenses'
import { policyGuidanceError, policyGuidanceMessages, policyGuidanceQuery, type PolicyGuidanceContext, type PolicyGuidance, type PolicyGuidanceView } from '../expensePolicyGuidance'

const props = defineProps<{ context: PolicyGuidanceContext | null; line: ExpenseLine; scopeKey: string; disabled: boolean }>()
const emit = defineEmits<{ resolved: [view: PolicyGuidanceView] }>()
const advice = ref<PolicyGuidance | null>(null), loading = ref(false), error = ref('')
const messages = computed(() => advice.value ? policyGuidanceMessages(advice.value, props.line) : [])
const DEBOUNCE_MS = 450, TIMEOUT_MS = 12_000
let epoch = 0, controller: AbortController | null = null
let debounce: ReturnType<typeof setTimeout> | undefined, expiry: ReturnType<typeof setTimeout> | undefined
function clear() {
  epoch++; controller?.abort(); controller = null; clearTimeout(debounce); clearTimeout(expiry)
  advice.value = null; loading.value = false; error.value = ''
}
async function refresh() {
  clear()
  if (!props.scopeKey || props.disabled || !props.context) return
  const context = { ...props.context }, version = epoch, request = new AbortController()
  controller = request; loading.value = true
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const result = await Promise.race([api.expensePolicyGuidance(context, request.signal), new Promise<never>((_, reject) => {
      timeout = setTimeout(() => { request.abort(); reject({ code: 'GUIDANCE_TIMEOUT' }) }, TIMEOUT_MS)
    })])
    if (version !== epoch) return
    advice.value = result.guidance
    emit('resolved', result)
    expiry = setTimeout(() => {
      if (version !== epoch) return
      clear(); error.value = '本行制度提示已过期，请刷新。'
    }, Math.max(0, Date.parse(result.guidance.validUntil) - Date.now()))
  } catch (cause) { if (version === epoch) error.value = policyGuidanceError(cause) }
  finally { clearTimeout(timeout); if (version === epoch) { controller = null; loading.value = false } }
}
function schedule() {
  clear()
  if (props.scopeKey && !props.disabled && props.context) debounce = setTimeout(() => { void refresh() }, DEBOUNCE_MS)
}
watch(() => [props.scopeKey, props.disabled, props.context ? policyGuidanceQuery(props.context) : ''], schedule, { immediate: true })
onUnmounted(clear)
</script>

<template>
  <section class="policy-guidance" :aria-label="`第 ${line.lineNo} 行费用标准提示`" aria-live="polite">
    <div class="guidance-heading"><h4>费用标准提示</h4><button v-if="context" type="button" class="quiet" :disabled="disabled || loading" @click="refresh">{{ loading ? '查询中…' : '刷新标准' }}</button></div>
    <p v-if="!context" class="guidance-note">选好法人、类别、城市、日期、币种和单位后，将查询本行适用标准。</p>
    <p v-else-if="loading" class="guidance-note">正在查询本人适用的费用规则…</p>
    <p v-else-if="error" class="guidance-error">{{ error }}</p>
    <template v-if="advice">
      <p class="guidance-rule">{{ advice.policyName }} · v{{ advice.policyVersion }} · {{ advice.ruleName }}</p>
      <ul><li v-for="message in messages" :key="message.code" :class="{ warning: message.warning }">{{ message.text }}</li></ul>
      <details><summary>查看规则版本与来源</summary><p>制度 {{ advice.policyId }} · 规则 {{ advice.ruleKey }}</p><p v-if="advice.selection">类别修订 {{ advice.selection.categoryRevision }} · 制度生效修订 {{ advice.selection.activeRevision }}</p><p>匹配事实来源：{{ advice.factSourceReference }}</p><p>本次提示有效至 {{ new Date(advice.validUntil).toLocaleString('zh-CN') }}</p></details>
      <p class="guidance-note">这是填报提示。保存后的预检会重新核对有效制度、票据和其他财务事实，最终以预检及审批结果为准。</p>
    </template>
  </section>
</template>

<style scoped>
.policy-guidance{margin:16px 0;padding:14px 16px;border:1px solid var(--line);border-radius:9px;background:var(--paper);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.guidance-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}.guidance-heading h4{font-size:12px;margin:0}.guidance-heading button{font-size:11px;flex-shrink:0}.guidance-rule{font-weight:600;color:var(--deep)}.policy-guidance ul{padding-left:18px;margin:10px 0}.policy-guidance li+li{margin-top:5px}.guidance-note,.policy-guidance details{font-size:11px;color:var(--muted)}.guidance-error,.warning{color:var(--red)}.policy-guidance summary{cursor:pointer;color:var(--deep)}.policy-guidance summary:focus-visible{outline:3px solid var(--teal);outline-offset:2px}
</style>
