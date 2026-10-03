<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, type ApiError } from '../api'
import type { NotificationPreferences } from '../notificationPreferences'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const current = ref<NotificationPreferences | null>(null)
const email = ref(false), enterpriseIm = ref(false), loading = ref(false), saving = ref(false)
const error = ref(''), notice = ref(''), stale = ref(false)
const changed = computed(() => !!current.value && (email.value !== current.value.emailEnabled || enterpriseIm.value !== current.value.enterpriseImEnabled))
const disabled = computed(() => props.locked || loading.value || saving.value || !current.value || stale.value)
let generation = 0, controller: AbortController | null = null

/** 明确重新读取才替换未保存开关；失败保留原输入，不能使用过时版本继续写入。 */
async function load() {
  const scope = props.scopeKey, token = ++generation
  controller?.abort(); controller = new AbortController()
  const active = controller
  let timeout: ReturnType<typeof setTimeout> | undefined
  loading.value = true; stale.value = true; error.value = ''
  try {
    const value = await Promise.race([
      api.notificationPreferences(active.signal),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => { active.abort(); reject({ message: '读取通知偏好超时，请重试。' }) }, 12_000) })
    ])
    if (generation !== token || props.scopeKey !== scope) return
    current.value = value; email.value = value.emailEnabled; enterpriseIm.value = value.enterpriseImEnabled; stale.value = false
  } catch (cause) {
    if (generation === token && props.scopeKey === scope) error.value = (cause as ApiError).message ?? '无法读取通知偏好，请重试。'
  } finally {
    clearTimeout(timeout)
    if (generation === token) { loading.value = false; controller = null }
  }
}

/** 保存只确认个人选择，渠道接通与送达结果不由此按钮宣告。 */
async function save() {
  if (disabled.value || !changed.value || !current.value) return
  const scope = props.scopeKey, token = generation
  const input = { emailEnabled: email.value, enterpriseImEnabled: enterpriseIm.value, expectedVersion: current.value.version }
  saving.value = true; error.value = ''; notice.value = ''
  try {
    await api.reviseNotificationPreferences(input)
    if (generation !== token || props.scopeKey !== scope) return
    notice.value = '偏好保存已确认，当前设置以重新读取的结果为准。'
    saving.value = false
    await load()
  } catch (cause) {
    if (generation !== token || props.scopeKey !== scope) return
    const failure = cause as ApiError
    if (failure.code === 'CONCURRENCY_CONFLICT') {
      stale.value = true
      error.value = '设置已在其他页面更新。未保存的选择仍保留，请重新读取后再选择。'
    } else error.value = failure.message ?? '保存结果未确认，请使用页面上方的原操作恢复入口。'
  } finally { if (props.scopeKey === scope && (generation === token || !saving.value)) saving.value = false }
}

watch(() => props.scopeKey, () => {
  generation++; controller?.abort(); current.value = null; email.value = false; enterpriseIm.value = false
  saving.value = false; notice.value = ''; error.value = ''; void load()
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, () => { notice.value = ''; saving.value = false; void load() })
onUnmounted(() => { generation++; controller?.abort() })
</script>

<template>
  <details class="preference-panel">
    <summary>我的通知偏好<span>站内提醒始终开启</span></summary>
    <div class="preference-body">
      <p>设置只对当前账号生效。邮件和企业 IM 接通并绑定收件账号后，按此偏好发送新消息。</p>
      <p class="preference-note">开启不会补发旧消息；关闭后停止尚未开始发送的提醒。保存偏好不表示消息已经送达。</p>
      <p v-if="loading" role="status">正在读取当前设置…</p>
      <form @submit.prevent="save">
        <fieldset :disabled="disabled"><legend>外部提醒渠道</legend>
          <label><input v-model="email" type="checkbox" /><span><strong>邮件提醒</strong><small>接收工作邮件通知</small></span></label>
          <label><input v-model="enterpriseIm" type="checkbox" /><span><strong>企业 IM 提醒</strong><small>接收企业即时消息通知</small></span></label>
        </fieldset>
        <p v-if="current && !stale" class="preference-note">{{ current.updatedAt ? '上次保存：' + new Date(current.updatedAt).toLocaleString('zh-CN') : '上次读取时：尚未保存个人偏好，外部提醒默认关闭。' }}</p>
        <p v-if="error" class="preference-error" role="alert">{{ error }}</p>
        <p v-if="notice" class="preference-notice" role="status">{{ notice }}</p>
        <div class="preference-actions"><button type="button" class="secondary" :disabled="locked || loading || saving" @click="notice = ''; load()">{{ changed ? '放弃修改并重新读取' : '重新读取设置' }}</button><button type="submit" class="primary" :disabled="disabled || !changed">{{ saving ? '正在保存…' : '保存通知偏好' }}</button></div>
      </form>
    </div>
  </details>
</template>

<style scoped>
.preference-panel{margin:22px 0;border:1px solid var(--line);border-radius:10px;background:var(--paper);font-size:13px}.preference-panel summary{padding:16px;cursor:pointer;color:var(--deep);font-weight:600}.preference-panel summary span{margin-left:12px;color:var(--muted);font-size:11px;font-weight:400}.preference-body{padding:0 16px 18px;line-height:1.8}.preference-body>p{margin:0 0 10px}.preference-note{color:var(--muted);font-size:12px}.preference-body fieldset{border:0;margin:15px 0;padding:0;display:flex;flex-wrap:wrap;gap:12px;min-width:0}.preference-body legend{font-size:12px;margin-bottom:8px}.preference-body label{display:flex;align-items:center;gap:10px;flex:1 1 200px;padding:13px;border:1px solid var(--line);border-radius:8px;min-width:0}.preference-body input{width:17px;height:17px;accent-color:var(--teal);flex-shrink:0}.preference-body strong,.preference-body small{display:block}.preference-body small{color:var(--muted);font-size:11px;font-weight:400}.preference-actions{display:flex;justify-content:flex-end;flex-wrap:wrap;gap:10px;margin-top:14px}.preference-error{color:var(--red)}.preference-notice{color:var(--deep)}@media(max-width:650px){.preference-panel summary span{display:block;margin:5px 0 0 16px}.preference-actions button{flex:1}.preference-body label{flex-basis:100%}}
</style>
