<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { DefinitionCatalogQuery } from '../definitionCatalog'
import { PortableTemplateReview, serializePortableTemplate, type PortableProcess } from '../portableTemplate'
import { validateTemplateCopy } from '../templateCenter'
import { simulationIssue } from '../definitionSimulation'
import { fieldTypes } from '../formSchema'
import { approvalPolicyLabel } from '../approvalPolicy'

const props = defineProps<{ current: PortableProcess; locked: boolean; scopeKey: string; hasUnsavedDefinition: boolean }>()
const emit = defineEmits<{ import: [value: PortableProcess]; back: [] }>()
const review = reactive(new PortableTemplateReview(api.validateDefinition))
const targetKey = ref(''), targetName = ref(''), fileName = ref(''), attempted = ref(false)
const exportUrl = ref(''), exportError = ref('')
const targetErrors = computed(() => validateTemplateCopy({ key: targetKey.value.trim(), name: targetName.value.trim(), templateVersion: 1 }))
const keyQuery = reactive(new DefinitionCatalogQuery(api.searchDefinitions))
const KEY_CHECK_DELAY_MS = 300
let keyTimer: ReturnType<typeof setTimeout> | undefined
const sameKey = computed(() => keyQuery.loaded && keyQuery.items.length > 0)
watch(() => [props.scopeKey, targetKey.value.trim()], () => {
  review.invalidateCheck()
  clearTimeout(keyTimer); keyQuery.clear()
  if (targetKey.value.trim() && !targetErrors.value.key) keyTimer = setTimeout(() => { void keyQuery.load(props.scopeKey, { processKey: targetKey.value.trim() }) }, KEY_CHECK_DELAY_MS)
}, { flush: 'sync' })
const issues = computed(() => review.errors.map(code => {
  const issue = simulationIssue(code)
  const nodeName = review.value?.graph.nodes.find(node => node.id === issue.target)?.name
  return `${issue.label}${issue.target ? ' · ' + (nodeName ?? issue.target) : ''}`
}))
const approvals = computed(() => review.value?.graph.nodes.filter(node => node.type === 'USER_TASK') ?? [])
function clearExport() { if (exportUrl.value) URL.revokeObjectURL(exportUrl.value); exportUrl.value = ''; exportError.value = '' }
function generate() {
  if (props.locked) return
  clearExport()
  try { exportUrl.value = URL.createObjectURL(new Blob([serializePortableTemplate(props.current)], { type: 'application/json;charset=utf-8' })) }
  catch (cause) { exportError.value = cause instanceof Error ? cause.message : '生成模板失败，请检查当前设计。' }
}
async function choose(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  input.value = ''
  if (!file || props.locked) return
  fileName.value = file.name; targetKey.value = ''; targetName.value = ''; attempted.value = false
  const value = await review.read(file)
  // 文件可能已切换或组件已卸载；只有当前文件才能填充目标名称。
  if (value && value === review.value) targetName.value = value.name
}
function create() {
  if (props.locked || !review.canImport || !review.value) return
  attempted.value = true
  if (Object.keys(targetErrors.value).length) return
  emit('import', { ...review.value, key: targetKey.value.trim(), name: targetName.value.trim() })
}
watch(() => props.current, clearExport, { deep: true })
onUnmounted(() => { clearTimeout(keyTimer); keyQuery.clear(); review.clear(); clearExport() })
</script>

<template>
  <section class="content portable-template">
    <div class="page-heading"><div><p class="eyebrow">PROCESS TEMPLATE / FILE</p><h2>让流程配置可以复用。</h2><p class="subhead">导出当前设计，或从模板文件创建独立草稿。导入后核对审批人和期限日历，再模拟和发布。</p></div><button class="secondary" :disabled="locked" @click="emit('back')">返回流程设计器</button></div>
    <p v-if="hasUnsavedDefinition" class="unsaved-note">设计器有未保存的修改。读取文件和检查模板不会替换内容；创建草稿前会再次确认。</p>
    <div class="transfer-layout">
      <article class="panel import-panel" aria-labelledby="template-import-heading">
        <p class="eyebrow">IMPORT</p><h3 id="template-import-heading">导入模板文件</h3>
        <p class="explanation">选择平台导出的 JSON 文件，最多 1 MiB。文件中的角色、账号和期限日历需在当前租户重新核对。</p>
        <label class="file-picker"><strong>{{ fileName ? '更换模板文件' : '选择流程模板文件' }}</strong><span>点击选择 JSON 文件</span><input type="file" accept=".json,application/json" :disabled="locked" aria-label="选择流程模板文件" @change="choose" /></label>
        <p v-if="fileName" class="file-name">{{ fileName }}</p>
        <p v-if="review.loading" role="status" class="explanation">正在读取或检查模板…</p>
        <p v-if="review.error" role="alert" class="transfer-error">{{ review.error }}</p>
        <template v-if="review.value">
          <div class="template-summary"><strong>{{ review.value.name }}</strong><span>来源标识 {{ review.value.key }}</span><p>{{ review.value.graph.nodes.length }} 个节点 · {{ review.value.graph.edges.length }} 条连线 · {{ review.value.formSchema?.fields.length ?? 0 }} 个字段</p></div>
          <details><summary>预览审批人和表单</summary><ul class="config-list"><li v-for="node in approvals" :key="node.id"><strong>{{ node.name }}</strong><span>{{ node.properties.assigneeRule || '未配置审批人' }} · {{ approvalPolicyLabel(node.properties.approvalMode, node.properties.approvalPercentage) }}</span></li></ul><ul class="config-list"><li v-for="field in review.value.formSchema?.fields ?? []" :key="field.key"><strong>{{ field.label }}</strong><span>{{ fieldTypes.find(type => type.value === field.type)?.label }} · {{ field.required ? '必填' : '选填' }}</span></li></ul><p v-if="!review.value.formSchema" class="explanation">来源流程未绑定版本化表单。</p></details>
          <div class="check-row"><button class="secondary" :disabled="locked || review.loading" @click="review.check(targetKey.trim())">{{ review.reviewed ? '重新检查模板' : '检查模板' }}</button><span v-if="review.reviewed && !review.errors.length" class="check-success" role="status">结构、表单、审批人与期限引用检查通过。</span></div>
          <ul v-if="issues.length" class="transfer-issues" role="status"><li v-for="(issue, index) in issues" :key="index">{{ issue }}</li></ul>
          <p v-if="review.canImport && review.errors.length" class="explanation">可先创建草稿，在设计器中修正审批人、期限日历或分支覆盖。发布前必须重新通过检查。</p>
          <p v-else-if="review.reviewed && review.errors.length" class="transfer-error">请按上方提示修正后重新检查。目标流程标识冲突可在下方修改；结构或条件问题需在来源设计器修正并重新导出。</p>
          <fieldset :disabled="locked || review.loading" class="target-fields"><legend>新草稿</legend><label>目标流程标识<input v-model="targetKey" maxlength="64" placeholder="例如 team-leave" :aria-invalid="attempted && !!targetErrors.key" /><small v-if="attempted && targetErrors.key" class="transfer-error">{{ targetErrors.key }}</small></label><label>流程名称<input v-model="targetName" maxlength="128" :aria-invalid="attempted && !!targetErrors.name" /><small v-if="attempted && targetErrors.name" class="transfer-error">{{ targetErrors.name }}</small></label></fieldset>
          <p v-if="keyQuery.error" class="unsaved-note">未能检查同名流程。导入始终创建独立草稿，相同标识以后发布为该流程的新版本。</p>
          <p v-if="sameKey" class="unsaved-note">此标识已有流程。将创建独立草稿；以后发布时会成为该流程的新版本，已有版本和申请保持不变。</p>
          <button class="primary" :disabled="locked || !review.canImport" @click="create">创建独立草稿</button><p class="explanation">创建后进入设计器，不会自动发布或发起申请。</p>
        </template>
      </article>
      <aside class="panel export-panel" aria-labelledby="template-export-heading"><p class="eyebrow">EXPORT</p><h3 id="template-export-heading">导出当前设计</h3><strong class="current-name">{{ current.name }}</strong><p class="explanation">包含当前画布、表单、审批人、期限规则和通知文案，包括尚未保存的修改。不会保存草稿或改变已发布版本。</p><button class="secondary" :disabled="locked" @click="generate">生成模板文件</button><a v-if="exportUrl" :href="exportUrl" download="agentflow-process-template.json" class="download-link">下载流程模板 JSON ↓</a><p v-if="exportError" class="transfer-error" role="alert">{{ exportError }}</p><div class="file-boundary"><strong>分享前核对</strong><p>文件包含流程名称、角色、指定账号、期限日历引用和通知文案。请确认这些配置适合接收方使用。</p><p>不包含申请正文、审批历史、发布记录、租户身份或登录凭证。</p></div></aside>
    </div>
  </section>
</template>

<style scoped>
.portable-template{max-width:1600px}.transfer-layout{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(260px,1fr);gap:24px;align-items:start}.import-panel,.export-panel{padding:27px;min-width:0}.transfer-layout h3{font-size:20px;margin:12px 0 17px}.explanation,.file-boundary p{font-size:12px;line-height:1.9;color:var(--muted)}.unsaved-note{padding:13px 16px;border:1px solid #eadbb8;background:#fff9eb;color:#78622e;border-radius:8px;font-size:12px;line-height:1.8;margin:0 0 20px}.file-picker{position:relative;display:grid;gap:12px;font-size:12px;padding:20px;border:1px dashed #9bbfb8;border-radius:8px;background:#f4faf8;margin:20px 0}.file-picker input{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}.file-picker:focus-within{outline:2px solid var(--deep);outline-offset:3px}.file-picker span{color:var(--muted);font-size:11px}.file-name,.template-summary span{font-size:11px;color:var(--muted);overflow-wrap:anywhere}.template-summary{padding:18px 0;border-bottom:1px solid var(--line);margin-bottom:19px;display:grid;gap:9px;overflow-wrap:anywhere}.template-summary strong{font-size:16px}.template-summary p{font-size:12px;color:var(--deep);margin:3px 0}.config-list{list-style:none;padding:0;display:grid;gap:10px}.config-list li{display:flex;justify-content:space-between;gap:15px;font-size:12px;overflow-wrap:anywhere}.config-list strong{font-weight:500}.config-list span{color:var(--muted)}summary{cursor:pointer;font-size:12px;color:var(--deep)}.check-row{display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin:22px 0}.check-success{font-size:12px;color:var(--deep)}.transfer-error,.transfer-issues{font-size:12px;line-height:1.8;color:#a34736;overflow-wrap:anywhere}.transfer-issues{padding-left:20px}.target-fields{border:0;border-top:1px solid var(--line);padding:20px 0;display:grid;grid-template-columns:1fr 1fr;gap:18px;min-width:0}.target-fields legend{font-size:12px;color:var(--muted);padding-right:10px}.target-fields label{display:grid;gap:9px;font-size:12px;min-width:0}.target-fields input{width:100%;min-width:0;border:1px solid var(--line);border-radius:7px;padding:11px;background:white;color:var(--ink)}.current-name{font-size:16px;display:block;overflow-wrap:anywhere}.download-link{display:block;margin-top:18px;color:var(--deep);font-size:12px}.file-boundary{border-top:1px solid var(--line);margin-top:27px;padding-top:22px}.file-boundary strong{font-size:12px}.file-boundary p{margin-bottom:0}@media(max-width:1050px){.transfer-layout{grid-template-columns:1fr}.export-panel{order:2}}@media(max-width:650px){.target-fields{grid-template-columns:1fr}.import-panel,.export-panel{padding:20px}.config-list li{flex-direction:column;gap:6px}}
</style>
