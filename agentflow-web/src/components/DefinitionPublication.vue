<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import BranchDiagnostics from './BranchDiagnostics.vue'
import { api, type ApiError, type PublicationResponse } from '../api'

const props = defineProps<{ definitionId: string; scopeKey: string }>()
const result = ref<PublicationResponse | null>(null)
const loading = ref(false)
const error = ref('')
let active: AbortController | null = null
const checkLabels: Record<string, string> = { GRAPH_STRUCTURE: '流程图结构', ASSIGNEE_SYNTAX: '审批人规则语法', RESTRICTED_CONDITIONS: '受限条件表达式', FORM_FIELD_TYPES: '表单字段与条件类型', BRANCH_COVERAGE: '分支覆盖检查' }

async function load() {
  active?.abort()
  const controller = new AbortController()
  active = controller
  result.value = null; error.value = ''; loading.value = true
  const timeout = window.setTimeout(() => controller.abort(), 15000)
  try {
    const response = await api.definitionPublication(props.definitionId, controller.signal)
    if (active === controller && !controller.signal.aborted) result.value = response
  } catch (cause) {
    if (active === controller) error.value = controller.signal.aborted ? '读取发布记录超时，请重试。' : (cause as ApiError).message ?? '发布记录读取失败。'
  } finally {
    window.clearTimeout(timeout)
    if (active === controller) loading.value = false
  }
}
watch(() => [props.definitionId, props.scopeKey], load, { immediate: true })
onBeforeUnmount(() => { const controller = active; active = null; controller?.abort() })
</script>

<template>
  <section class="publication-panel" aria-label="发布记录" :aria-busy="loading">
    <div class="publication-heading"><div><p class="eyebrow">PUBLICATION RECORD</p><h3>发布记录</h3></div><span v-if="result?.recorded" class="publication-badge">已留存发布依据</span></div>
    <p v-if="loading" role="status">正在读取发布记录…</p>
    <div v-else-if="error" class="publication-error" role="alert"><p>{{ error }}</p><button class="secondary" @click="load">重试读取</button></div>
    <p v-else-if="result && !result.recorded" class="publication-legacy">此历史版本没有完整发布记录，发布者、变更说明和校验摘要未记录。</p>
    <template v-else-if="result?.publication">
      <dl class="publication-meta"><div><dt>发布者</dt><dd>{{ result.publication.publishedBy }}</dd></div><div><dt>发布权限</dt><dd>{{ result.publication.authorizedRole === 'ADMIN' ? '平台管理员' : '流程管理员' }}</dd></div><div><dt>发布时间</dt><dd>{{ new Date(result.publication.publishedAt).toLocaleString('zh-CN') }}</dd></div></dl>
      <div class="publication-note"><h4>变更说明</h4><p>{{ result.publication.changeNote }}</p></div>
      <div class="publication-validation"><h4>发布时检查记录</h4><p>{{ result.publication.validation.nodeCount }} 个节点 · {{ result.publication.validation.edgeCount }} 条连线 · {{ result.publication.validation.formBound ? `${result.publication.validation.fieldCount} 个表单字段` : '未绑定表单' }}</p><ul><li v-for="check in result.publication.validation.checks" :key="check">{{ checkLabels[check] ?? check }}</li></ul><p class="publication-boundary">摘要只记录本次已执行的检查。分支重叠与未能证明覆盖的提醒仍需按业务制度核对。</p></div>
      <BranchDiagnostics :items="result.publication.validation.branchDiagnostics ?? []" />
    </template>
  </section>
</template>

<style scoped>
.publication-panel { border: 1px solid var(--line); border-radius: 14px; background: var(--panel, #fff); padding: 22px; margin: 18px 0; color: var(--ink); }
.publication-heading { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.publication-heading .eyebrow { margin-bottom: 5px; }
.publication-heading h3 { margin: 0; font-size: 19px; }
.publication-badge { font-size: 11px; color: #176450; background: #edf7f1; padding: 7px 10px; border-radius: 6px; }
.publication-meta { display: flex; gap: 24px; flex-wrap: wrap; margin: 22px 0; }
.publication-meta dt { color: var(--muted); font-size: 11px; margin-bottom: 6px; }
.publication-meta dd { font-size: 13px; margin: 0; overflow-wrap: anywhere; }
.publication-note { border-top: 1px solid var(--line); padding-top: 17px; }
.publication-panel h4 { margin: 0 0 9px; font-size: 13px; }
.publication-note p { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.8; margin: 0 0 20px; }
.publication-validation { padding: 15px; background: #f6f8f5; border-radius: 9px; }
.publication-validation p, .publication-legacy { font-size: 12px; line-height: 1.8; color: var(--muted); }
.publication-validation ul { display: flex; flex-wrap: wrap; gap: 7px 18px; list-style: none; padding: 0; font-size: 12px; }
.publication-validation li::before { content: '✓ '; color: #176450; }
.publication-validation .publication-boundary { margin-bottom: 0; }
.publication-error { color: #973c32; font-size: 13px; }
@media (max-width: 500px) { .publication-panel { padding: 17px; } .publication-heading { align-items: flex-start; flex-direction: column; } .publication-meta { gap: 17px; } }
</style>
