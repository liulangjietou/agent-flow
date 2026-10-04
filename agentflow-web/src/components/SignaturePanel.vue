<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type Application } from '../api'
import { fileSize } from '../attachments'
import { SignatureRead, signatureStatuses, signatureSourceVersion, signatureChoices, signatureMessage, rememberSignatureOperation, recalledSignatureOperation,
  type SignatureSource, type SignatureChoice, type SignatureOptions, type SignatureReceipt, type SignatureView, type SignatureInput } from '../signatures'

const props = defineProps<{ application: Application; rounds: SignatureSource[]; scopeKey: string; userId: string; initialRoundNo?: number | null; locked: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; dirty: [value: boolean] }>()
interface Workspace { options: SignatureOptions; items: SignatureReceipt[]; nextAfterId?: string | null; choices: SignatureChoice[] }
type Confirmation = { kind: 'create'; body: SignatureInput; fingerprint: string } | { kind: 'cancel'; id: string; version: string }
const reader = reactive(new SignatureRead<Workspace>()), detailReader = reactive(new SignatureRead<SignatureView>()), downloadReader = reactive(new SignatureRead<Blob>())
const selectedRound = ref(0), profileSelection = ref(''), selectedDocuments = ref<string[]>([]), purpose = ref(''), minutes = ref(60)
const items = ref<SignatureReceipt[]>([]), nextAfterId = ref<string | null>(null), activeId = ref(''), running = ref(false)
const confirmation = ref<Confirmation | null>(null), accepted = ref(false), error = ref(''), notice = ref('')
const confirmBox = ref<HTMLInputElement | null>(null), reviewButton = ref<HTMLButtonElement | null>(null), panel = ref<HTMLElement | null>(null)
let generation = 0, alive = true
const urls = new Set<string>(), cursors = new Set<string>()
const source = computed(() => props.rounds.find(round => round.roundNo === selectedRound.value))
const options = computed(() => reader.value?.options ?? null), choices = computed(() => reader.value?.choices ?? [])
const profileToken = (profile: { key: string; version: string }) => JSON.stringify([profile.key, profile.version])
const profile = computed(() => options.value?.profiles.find(value => profileToken(value) === profileSelection.value))
const originals = computed(() => choices.value.filter(file => selectedDocuments.value.includes(file.id)))
const totalBytes = computed(() => originals.value.reduce((sum, file) => sum + file.size, 0))
const approved = computed(() => props.application.status === 'APPROVED' && props.application.roundNo === selectedRound.value && source.value?.status === 'APPROVED')
const activeOperation = computed(() => items.value.some(item => !['SIGNED', 'DECLINED', 'CANCELLED', 'EXPIRED'].includes(item.status)))
const canStart = computed(() => approved.value && !!signatureSourceVersion(props.application.version) && !!options.value?.enabled && !!choices.value.length && !activeOperation.value && !reader.loading)
const dirty = computed(() => !!profileSelection.value || !!selectedDocuments.value.length || !!purpose.value || !!confirmation.value)
const locked = computed(() => props.locked || running.value || reader.loading)
const detail = computed(() => detailReader.value)
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const fingerprint = () => JSON.stringify([props.scopeKey, props.application.id, props.application.version, selectedRound.value, profileSelection.value, selectedDocuments.value, purpose.value, minutes.value])
function clearInputs() { profileSelection.value = ''; selectedDocuments.value = []; purpose.value = ''; confirmation.value = null; accepted.value = false; minutes.value = 60 }
function releaseDownloads() { downloadReader.clear(); for (const url of urls) URL.revokeObjectURL(url); urls.clear() }
function clearContext() {
  generation++; reader.clear(); detailReader.clear(); releaseDownloads(); items.value = []; nextAfterId.value = null; activeId.value = ''
  running.value = false; error.value = ''; notice.value = ''; clearInputs(); cursors.clear()
}
async function load(more = false) {
  if (running.value || more && (!nextAfterId.value || reader.loading)) return
  const previous = more ? [...items.value] : [], after = more ? nextAfterId.value! : undefined
  const scope = props.scopeKey, applicationId = props.application.id, roundNo = selectedRound.value, round = source.value, showChoices = approved.value
  generation++; detailReader.clear(); releaseDownloads(); activeId.value = ''; confirmation.value = null; accepted.value = false; error.value = ''; notice.value = ''
  items.value = []; nextAfterId.value = null
  if (!more) { cursors.clear(); clearInputs() }
  if (!scope || !round) { reader.clear(); return }
  if (after) cursors.add(after)
  const value = await reader.load(scope, async signal => {
    const [options, page] = await Promise.all([api.signatureOptions(signal), api.signaturePage(applicationId, roundNo, after, signal)])
    const choices = showChoices && options.enabled ? await signatureChoices(round, id => api.attachment(applicationId, id, roundNo, signal), signal) : []
    return { options, ...page, choices }
  })
  if (!value) return
  if (value.items.some(item => previous.some(old => old.id === item.id)) || value.nextAfterId && cursors.has(value.nextAfterId)) {
    reader.clear(); reader.error = '签署列表已变化，请重新加载首页。'; return
  }
  items.value = [...previous, ...value.items]; nextAfterId.value = value.nextAfterId ?? null
}
async function openOperation(id: string) {
  if (running.value || dirty.value) return
  activeId.value = id; confirmation.value = null; accepted.value = false; error.value = ''; releaseDownloads()
  const scope = props.scopeKey, application = props.application.id, round = selectedRound.value
  const value = await detailReader.load(scope, signal => api.signatureDetail(application, id, round, signal))
  if (value) rememberSignatureOperation(scope, application, id, round)
}
async function reviewCreate() {
  error.value = ''
  if (locked.value || !canStart.value || !profile.value || !source.value || !options.value) return
  const expectedVersion = signatureSourceVersion(props.application.version)
  if (!expectedVersion || !originals.value.length || originals.value.length !== selectedDocuments.value.length || originals.value.length > options.value.maxDocuments
    || totalBytes.value > options.value.maxTotalBytes) { error.value = '请选择本轮可读原件，最多 10 份且合计不超过 32 MB。'; return }
  if (!purpose.value.trim() || purpose.value.length > 1000 || /[\u0000-\u001f\u007f-\u009f]/.test(purpose.value)) { error.value = '请填写本次签署用途，使用一行文字且不超过 1000 字。'; return }
  if (!Number.isSafeInteger(minutes.value) || minutes.value < 1 || minutes.value * 60 > options.value.maxAuthorizationSeconds) { error.value = '请选择 1 至 1440 分钟的发送授权期限。'; return }
  confirmation.value = { kind: 'create', fingerprint: fingerprint(), body: { roundNo: selectedRound.value, expectedVersion, profileKey: profile.value.key,
    profileVersion: profile.value.version, documentIds: [...selectedDocuments.value], purpose: purpose.value.trim(), validUntil: new Date(Date.now() + minutes.value * 60_000).toISOString() } }
  accepted.value = false; await nextTick(); confirmBox.value?.focus()
}
async function reviewCancel() {
  if (locked.value || !detail.value?.canCancel || dirty.value) return
  confirmation.value = { kind: 'cancel', id: detail.value.operation.id, version: detail.value.operation.version }; accepted.value = false; error.value = ''
  await nextTick(); confirmBox.value?.focus()
}
async function dismissConfirmation() { confirmation.value = null; accepted.value = false; await nextTick(); reviewButton.value?.focus() }
async function discard() { if (running.value) return; clearInputs(); error.value = ''; await nextTick(); panel.value?.focus() }
async function confirm() {
  const original = confirmation.value
  if (!original || !accepted.value || locked.value) return
  if (original.kind === 'create' && (!canStart.value || original.fingerprint !== fingerprint() || Date.parse(original.body.validUntil) <= Date.now())) { error.value = '授权内容或期限已变化，请重新核对。'; confirmation.value = null; return }
  if (original.kind === 'cancel' && (!detail.value?.canCancel || detail.value.operation.id !== original.id || detail.value.operation.version !== original.version)) { error.value = '签署状态已变化，请刷新核对。'; confirmation.value = null; return }
  const epoch = generation, scope = props.scopeKey, applicationId = props.application.id, roundNo = selectedRound.value
  const current = () => alive && epoch === generation && scope === props.scopeKey && applicationId === props.application.id
  running.value = true; error.value = ''; notice.value = ''
  try {
    const result = original.kind === 'create' ? await api.createSignature(applicationId, original.body) : await api.cancelSignature(applicationId, original.id, original.version)
    if (!current()) return
    running.value = false; clearInputs(); rememberSignatureOperation(scope, applicationId, result.id, roundNo)
    await load()
    if (!alive || scope !== props.scopeKey || applicationId !== props.application.id || roundNo !== selectedRound.value) return
    notice.value = original.kind === 'create' ? '本次签署授权已登记。请刷新原记录查看进度，原件会保留。' : '尚未发送的签署已取消，原件和历史记录保留。'
    await openOperation(result.id)
  } catch (cause) { if (current()) { error.value = signatureMessage(cause); accepted.value = false } }
  finally { if (current()) running.value = false }
}
async function download(documentId: string, signed: boolean) {
  if (!detail.value || downloadReader.loading) return
  const file = detail.value.documents.find(document => document.id === documentId), operation = detail.value.operation
  if (!file || signed && !file.downloadable) return
  const epoch = generation, scope = props.scopeKey, application = props.application.id, round = selectedRound.value
  const content = await downloadReader.load(scope, signal => signed ? api.downloadSignature(application, operation.id, documentId, signal) : api.downloadAttachment(application, documentId, round, signal), 120_000)
  if (!content || !alive || epoch !== generation || activeId.value !== operation.id) return
  if (content.size !== (signed ? file.signedBytes : file.originalBytes)) { downloadReader.clear(); downloadReader.error = '文件接收不完整，请重新下载。'; return }
  const url = URL.createObjectURL(content); urls.add(url); downloadReader.value = null
  const link = document.createElement('a'); link.href = url; link.download = (signed ? 'signed-' : '') + file.filename; link.click()
  setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url) }, 1000)
}
watch(() => [props.scopeKey, props.application.id], () => {
  clearContext(); const previous = recalledSignatureOperation(props.scopeKey, props.application.id)
  selectedRound.value = props.rounds.find(r => r.roundNo === previous?.roundNo)?.roundNo ?? props.rounds.find(r => r.roundNo === props.initialRoundNo)?.roundNo ?? props.application.roundNo
}, { immediate: true, flush: 'sync' })
watch(() => [props.scopeKey, props.application.id, props.application.version, props.application.status, props.rounds, selectedRound.value], async () => {
  clearContext(); const epoch = generation, previous = recalledSignatureOperation(props.scopeKey, props.application.id)
  await load()
  if (alive && epoch + 1 === generation && reader.value && previous?.roundNo === selectedRound.value) await openOperation(previous.id)
}, { immediate: true, flush: 'sync' })
watch(() => fingerprint(), () => { if (confirmation.value?.kind === 'create') { confirmation.value = null; accepted.value = false } }, { flush: 'sync' })
watch(running, value => emit('busy', value), { immediate: true, flush: 'sync' })
watch(dirty, value => emit('dirty', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; clearContext(); emit('busy', false); emit('dirty', false) })
</script>

<template>
  <section ref="panel" class="signature-panel" aria-label="电子签" tabindex="-1" :aria-busy="reader.loading || running">
    <header class="signature-heading"><div><h3>电子签</h3><p>对已批准的原件明确授权签署，原件与签署结果分别保留。</p></div><button type="button" class="secondary" :disabled="reader.loading || running || dirty" @click="load()">刷新签署记录</button></header>
    <label class="signature-round">审批轮次<select v-model.number="selectedRound" :disabled="running || dirty || !rounds.length"><option v-for="round in [...rounds].sort((a, b) => b.roundNo - a.roundNo)" :key="round.roundNo" :value="round.roundNo">第 {{ round.roundNo }} 轮</option></select></label>
    <p v-if="!rounds.length">尚未提交，没有可签署的审批原件。</p><p v-else-if="reader.loading" role="status">正在读取本轮签署资料…</p><p v-if="reader.error" class="signature-error" role="alert">{{ reader.error }}</p>
    <p v-if="error" class="signature-error" role="alert">{{ error }}</p><p v-if="notice" class="signature-notice" role="status">{{ notice }}</p>
    <p v-if="props.locked" class="signature-notice">存在未确认操作，请先使用上方“恢复上次操作”核对原结果。</p>
    <template v-if="reader.value">
      <section class="signature-grant" aria-label="本次签署授权">
        <h4>本次签署授权</h4>
        <p v-if="!approved">只能对当前已批准轮次的原件发起新的签署。本轮已有记录仍可查看。</p>
        <p v-else-if="!signatureSourceVersion(application.version)" class="signature-error">申请版本无法准确读取，请联系管理员核对后再授权。</p>
        <p v-else-if="!options?.enabled">当前账号暂无可用签署资料，或签署存储尚未就绪。已有记录仍可查看。</p>
        <p v-else-if="!choices.length">本轮没有当前可读的附件原件，不能发起签署。</p>
        <p v-else-if="activeOperation">本轮有尚未结束的签署，请先查看原记录及文件保存进度。</p>
        <form v-else @submit.prevent="reviewCreate">
          <fieldset :disabled="locked || !!confirmation"><legend>选择要发送签署的原件</legend>
            <label v-for="file in choices" :key="file.id" class="signature-file-choice"><input v-model="selectedDocuments" type="checkbox" :value="file.id" /><span><strong>{{ file.filename }}</strong><small>{{ file.label }} · {{ fileSize(file.size) }}</small></span></label>
            <p>已选 {{ originals.length }} / {{ options?.maxDocuments }} 份，合计 {{ fileSize(totalBytes) }}。只发送所选原件。</p>
            <label>签署资料<select v-model="profileSelection"><option value="">请选择本次使用的资料</option><option v-for="option in options?.profiles" :key="profileToken(option)" :value="profileToken(option)">{{ option.name }} · v{{ option.version }}</option></select></label>
            <label>本次签署用途<input v-model="purpose" maxlength="1000" placeholder="例如：签署已批准的采购合同" /></label>
            <label>首次发送授权有效期（分钟）<input v-model.number="minutes" type="number" min="1" :max="(options?.maxAuthorizationSeconds ?? 0) / 60" step="1" /></label>
            <p>过期后尚未发送的授权会失效；已发送的操作继续按原编号查询和保存结果。</p>
          </fieldset>
          <button ref="reviewButton" type="submit" :disabled="locked || !!confirmation || !profile || !originals.length || !purpose.trim()">核对本次签署</button>
        </form>
      </section>
      <section v-if="confirmation" class="signature-confirm" aria-label="确认签署操作">
        <template v-if="confirmation.kind === 'create'"><h4>确认授权签署这些原件</h4><p>{{ profile?.name }} · v{{ confirmation.body.profileVersion }} · 第 {{ confirmation.body.roundNo }} 轮</p><ul><li v-for="file in originals" :key="file.id">{{ file.filename }} · {{ fileSize(file.size) }}</li></ul><p>用途：{{ confirmation.body.purpose }}</p><p>首次发送截止：<time :datetime="confirmation.body.validUntil">{{ time(confirmation.body.validUntil) }}</time></p></template>
        <template v-else><h4>确认取消尚未发送的签署</h4><p>只有原授权尚未发送时才可取消。已经发送或结果未知的操作会继续查询原结果。</p></template>
        <label class="signature-ack"><input ref="confirmBox" v-model="accepted" type="checkbox" :disabled="locked" />{{ confirmation.kind === 'create' ? '我已核对所选原件、签署资料和用途，授权本次签署。' : '我确认取消这次尚未发送的授权。' }}</label>
        <div class="signature-actions"><button type="button" class="secondary" :disabled="running" @click="dismissConfirmation">返回核对</button><button type="button" :disabled="locked || !accepted" @click="confirm">{{ running ? '正在确认…' : confirmation.kind === 'create' ? '确认授权签署' : '确认取消签署' }}</button></div>
      </section>
      <button v-if="dirty" type="button" class="secondary" :disabled="running" @click="discard">放弃本次填写</button>
      <section class="signature-records" aria-label="签署记录"><h4>本轮签署记录</h4><p v-if="!items.length">本页没有当前可读的签署记录。</p><ul><li v-for="item in items" :key="item.id"><button type="button" class="signature-record-button" :disabled="running || dirty" :aria-pressed="activeId === item.id" @click="openOperation(item.id)"><strong>{{ signatureStatuses[item.status] }}</strong><small>操作编号 {{ item.id }}</small></button></li></ul><button v-if="nextAfterId" type="button" class="secondary" :disabled="reader.loading || running || dirty" @click="load(true)">加载更多签署记录</button></section>
    </template>
    <p v-if="detailReader.loading" role="status">正在复核原件权限并读取记录…</p><p v-if="detailReader.error" class="signature-error" role="alert">{{ detailReader.error }}</p>
    <section v-if="detail" class="signature-detail" aria-label="签署详情"><h4>{{ signatureStatuses[detail.operation.status] }}</h4>
      <p v-if="['COLLECTING', 'FETCHING_FILES'].includes(detail.operation.status)">服务方已确认签署，正在保存完整结果；全部文件保存后才可下载签署结果。</p>
      <p v-if="['UNKNOWN', 'QUERYING'].includes(detail.operation.status)">正在核对原操作结果，请勿重新授权或更换编号。</p>
      <p v-if="detail.failure" class="signature-notice">处理暂未完成，请刷新原记录。持续异常时可向管理员提供下方操作编号。</p>
      <dl><dt>授权人</dt><dd>{{ detail.authorizedBy }}</dd><dt>签署用途</dt><dd>{{ detail.purpose }}</dd><dt>签署资料</dt><dd>{{ detail.profileKey }} · v{{ detail.profileVersion }}</dd><dt>最近更新</dt><dd>{{ time(detail.updatedAt) }}</dd><dt>操作编号</dt><dd><code>{{ detail.operation.id }}</code></dd></dl>
      <ul class="signature-result-files"><li v-for="file in detail.documents" :key="file.id"><strong>{{ file.filename }}</strong><div class="signature-file-pair"><div><small>原件 · {{ fileSize(file.originalBytes) }}</small><button type="button" class="secondary" :disabled="downloadReader.loading" @click="download(file.id, false)">下载原件</button></div><div><small>{{ file.downloadable ? '签署结果 · ' + fileSize(file.signedBytes!) : '签署结果尚未就绪' }}</small><button v-if="file.downloadable" type="button" :disabled="downloadReader.loading" @click="download(file.id, true)">下载签署结果</button></div></div></li></ul>
      <p v-if="downloadReader.loading" role="status">正在读取并核对文件…</p><p v-if="downloadReader.error" class="signature-error" role="alert">{{ downloadReader.error }}</p>
      <button v-if="detail.canCancel" type="button" class="secondary" :disabled="locked || dirty" @click="reviewCancel">取消尚未发送的签署</button>
    </section>
  </section>
</template>

<style scoped>
.signature-panel{margin-top:16px;min-width:0;color:var(--ink);font-size:13px;line-height:1.8}.signature-heading,.signature-actions{display:flex;align-items:flex-start;justify-content:space-between;gap:14px}.signature-panel h3{margin:0;font-size:17px}.signature-panel h4{margin:0 0 10px;font-size:15px}.signature-panel p{margin:9px 0;color:var(--muted)}.signature-panel label{display:block;margin:12px 0}.signature-panel select,.signature-panel input:not([type=checkbox]){display:block;width:100%;min-height:38px;margin-top:5px;padding:8px 10px;border:1px solid var(--line);border-radius:6px;background:white;color:var(--ink)}.signature-round{max-width:220px}.signature-grant,.signature-detail,.signature-confirm{padding:18px;margin:18px 0;border:1px solid var(--line);border-radius:9px;background:var(--paper)}.signature-confirm{border-color:var(--deep);background:var(--soft)}.signature-grant fieldset{border:0;padding:0;margin:0;min-width:0}.signature-grant legend{font-weight:700}.signature-panel .signature-file-choice,.signature-panel .signature-ack{display:flex;align-items:flex-start;gap:10px}.signature-panel input[type=checkbox]{margin-top:6px;flex-shrink:0}.signature-file-choice span{min-width:0}.signature-panel small{display:block;color:var(--muted)}.signature-panel button{min-height:38px;white-space:normal;overflow-wrap:anywhere}.signature-panel button:not(.secondary):not(.signature-record-button){padding:8px 14px;color:white;background:var(--deep);border-radius:6px}.signature-panel button:disabled{opacity:.5;cursor:not-allowed}.signature-panel button:focus-visible,.signature-panel input:focus-visible,.signature-panel select:focus-visible{outline:3px solid var(--teal);outline-offset:3px}.signature-panel .signature-error{color:var(--red)}.signature-panel .signature-notice{color:var(--deep)}.signature-records{margin-top:22px}.signature-records ul,.signature-result-files{list-style:none;padding:0;margin:0}.signature-records li{border-top:1px solid var(--line)}.signature-records .signature-record-button{display:block;width:100%;padding:12px 0;text-align:left;background:transparent;color:var(--ink);border-radius:0}.signature-records .signature-record-button[aria-pressed=true]{box-shadow:inset 3px 0 var(--deep);padding-left:12px}.signature-detail dl{display:grid;grid-template-columns:80px minmax(0,1fr);gap:8px}.signature-detail dt{color:var(--muted)}.signature-detail dd{margin:0}.signature-detail code{font-family:'DM Mono',monospace;font-size:12px;user-select:all}.signature-result-files li{padding:14px 0;border-top:1px solid var(--line)}.signature-file-pair{display:grid;grid-template-columns:1fr 1fr;margin-top:8px;gap:16px}.signature-file-pair>div{display:flex;align-items:flex-start;flex-direction:column;gap:8px;min-width:0}.signature-file-pair>div+div{padding-left:16px;border-left:1px solid var(--line)}.signature-panel strong,.signature-panel p,.signature-panel dd,.signature-panel small,.signature-panel li{overflow-wrap:anywhere;min-width:0}@media(max-width:650px){.signature-heading,.signature-actions{flex-wrap:wrap}.signature-grant,.signature-detail,.signature-confirm{padding:13px}.signature-file-pair{gap:10px}.signature-file-pair>div+div{padding-left:10px}.signature-detail dl{grid-template-columns:64px minmax(0,1fr)}}
</style>
