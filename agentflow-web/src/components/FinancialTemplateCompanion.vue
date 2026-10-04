<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { api, type TemplateCompanionSummary } from '../api'
import { FinancialExamplePreview } from '../templateCenter'

const props = defineProps<{ templateKey: string; scopeKey: string; summary: TemplateCompanionSummary; locked: boolean }>()
const preview = reactive(new FinancialExamplePreview(api.financialTemplateExamples))
const downloadUrl = ref('')
const pretty = (value: unknown) => JSON.stringify(value, null, 2)
function releaseDownload() { if (downloadUrl.value) URL.revokeObjectURL(downloadUrl.value); downloadUrl.value = '' }
function load() { if (!props.locked) void preview.load(props.scopeKey, props.templateKey) }
watch(() => preview.value, value => {
  releaseDownload()
  if (value) downloadUrl.value = URL.createObjectURL(new Blob([pretty(value) + '\n'], { type: 'application/json;charset=utf-8' }))
}, { flush: 'sync' })
watch([() => props.scopeKey, () => props.templateKey], () => { preview.clear(); releaseDownload() }, { flush: 'sync' })
onUnmounted(() => { preview.clear(); releaseDownload() })
</script>

<template>
  <section class="financial-companion" aria-label="财务配套样例">
    <h4>{{ summary.name }} <span>V{{ summary.version }} · {{ summary.scenarioCount }} 个业务样例</span></h4>
    <p>{{ summary.description }}</p>
    <button class="secondary" :disabled="locked || preview.loading" @click="load">{{ preview.loading ? '正在读取…' : preview.value ? '重新读取配套样例' : '查看配置与业务样例' }}</button>
    <p v-if="preview.error" role="alert" class="companion-error">{{ preview.error }}</p>
    <div v-if="preview.value" class="companion-content">
      <p><a v-if="downloadUrl" :href="downloadUrl" :download="`${preview.value.key}-v${preview.value.version}.json`">下载完整样例（JSON）</a></p>
      <h5>准备顺序</h5><ol><li v-for="step in preview.value.setupSteps" :key="step">{{ step }}</li></ol>
      <details><summary>需要替换的编号与日期</summary><dl><template v-for="binding in preview.value.bindings" :key="binding.key"><dt>{{ binding.key }} <code>{{ binding.exampleValue }}</code></dt><dd>{{ binding.instruction }}</dd></template></dl></details>
      <details><summary>费用类别、差旅标准、科目映射与签收设置</summary><p>此处为配套输入，实际保存和发布仍在各自配置入口完成。</p><pre>{{ pretty(preview.value.configuration) }}</pre></details>
      <details v-for="scenario in preview.value.scenarios" :key="scenario.id" class="companion-scenario">
        <summary>{{ scenario.name }}</summary><p class="companion-meta">{{ scenario.templateKey }} · {{ scenario.id }}</p>
        <h5>办理步骤</h5><ol><li v-for="step in scenario.steps" :key="step">{{ step }}</li></ol>
        <h5>预期结果</h5><ul><li v-for="expected in scenario.expected" :key="expected">{{ expected }}</li></ul>
        <details><summary>专用业务入口的 content</summary><pre>{{ pretty(scenario.content) }}</pre></details>
        <details v-if="scenario.reductions"><summary>财务核减行（请求 lines）</summary><pre>{{ pretty(scenario.reductions) }}</pre></details>
      </details>
    </div>
  </section>
</template>

<style scoped>
.financial-companion{padding:22px 28px;border-bottom:1px solid var(--line);font-size:12px;line-height:1.9;overflow-wrap:anywhere}.financial-companion h4{margin:0 0 12px;font-size:14px}.financial-companion h4>span{font-size:11px;font-weight:400;color:var(--muted);margin-left:8px}.financial-companion p{color:var(--muted);margin:8px 0}.companion-content{margin-top:16px}.companion-content a{color:var(--deep);text-underline-offset:3px}.companion-content h5{font-size:12px;margin:14px 0 6px}.companion-content ol,.companion-content ul{padding-left:20px}.companion-content li+li{margin-top:5px}.companion-content details{padding:12px 14px;border:1px solid var(--line);border-radius:8px;margin-top:12px}.companion-content summary{cursor:pointer;font-weight:600}.companion-content pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px;line-height:1.7;padding:12px;background:#f4f8f7;border-radius:7px;max-height:420px;overflow:auto}.companion-content dt{font-weight:600;margin-top:12px}.companion-content code{display:block;font-weight:400;white-space:normal}.companion-content dd{margin:4px 0 12px;color:var(--muted)}.financial-companion .companion-error{color:var(--red)}.companion-meta{font-size:10px}
@media(max-width:650px){.financial-companion{padding:20px 17px}.financial-companion h4>span{display:block;margin-left:0}}
</style>
