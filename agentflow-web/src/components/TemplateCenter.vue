<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, type TemplateCopyInput } from '../api'
import { fieldErrorMessage, fieldTypes, ownValue } from '../formSchema'
import { TemplateCatalog, validateTemplateCopy } from '../templateCenter'
import FormFields from './FormFields.vue'

const props = defineProps<{ examplesOnly?: boolean; scopeKey: string; refreshVersion: number; locked: boolean; hasUnsavedDefinition: boolean }>()
const emit = defineEmits<{ copy: [templateKey: string, body: TemplateCopyInput]; open: [definitionId: string]; returnDesigner: []; import: [] }>()
const catalog = reactive(new TemplateCatalog(api.templates))
const search = ref('')
const selectedKey = ref('')
const targetKey = ref('')
const targetName = ref('')
const attempted = ref(false)
const selected = computed(() => catalog.templates.find(item => item.key === selectedKey.value) ?? null)
const visibleTemplates = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return catalog.templates.filter(item => `${item.name} ${item.category} ${item.description}`.toLocaleLowerCase().includes(query))
})
const copyInput = computed<TemplateCopyInput>(() => ({ key: targetKey.value.trim(), name: targetName.value.trim(), templateVersion: selected.value?.templateVersion ?? 0 }))
const copyErrors = computed(() => attempted.value ? validateTemplateCopy(copyInput.value) : {})
const businessLabels = { FORM: '表单审批', PROCUREMENT_PAYMENT: '采购付款', BUDGET_ADJUSTMENT: '预算调整' }
const statusLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }
const notificationLabels: Record<string, string> = { SUBMITTED: '提交后', RETURNED: '退回后', APPROVED: '批准后' }
const roleLabels: Record<string, string> = { MANAGER: '经理审批组（示例）', ADMIN: '额外复核组（示例）', FINANCE: '财务复核（复制后绑定实际人员或岗位）', ORG_SUPERVISOR_1: '本次任职直属主管' }
const nodeName = (id: string) => selected.value?.graph.nodes.find(node => node.id === id)?.name ?? id
const fieldName = (key: string) => selected.value?.formSchema.fields.find(field => field.key === key)?.label ?? key
const fieldTypeName = (type: string) => fieldTypes.find(item => item.value === type)?.label ?? type
const roleName = (rule: string) => ownValue(roleLabels, rule.replace(/^role:/, '')) ?? rule
const timeLabel = (value: string) => new Date(value).toLocaleString('zh-CN')

async function load() {
  selectedKey.value = ''; search.value = ''; targetKey.value = ''; targetName.value = ''; attempted.value = false
  await catalog.load(props.scopeKey)
  if (props.examplesOnly && catalog.templates.length) selectedKey.value = catalog.templates[0]!.key
}
function choose(key: string) {
  if (props.locked) return
  selectedKey.value = key
}
function copy() {
  if (props.examplesOnly || props.locked || !selected.value) return
  attempted.value = true
  if (Object.keys(copyErrors.value).length) return
  emit('copy', selected.value.key, { ...copyInput.value })
}
watch(selected, value => {
  targetKey.value = value?.key ?? ''; targetName.value = value?.name ?? ''; attempted.value = false
})
watch([() => props.scopeKey, () => props.refreshVersion], load, { immediate: true, flush: 'sync' })
onUnmounted(() => catalog.clear())
</script>

<template>
  <section class="content template-center">
    <div class="page-heading"><div><p class="eyebrow">PROCESS TEMPLATES</p><h2>{{ examplesOnly ? '先了解一条流程怎样运行。' : '从成熟的起点开始。' }}</h2><p class="subhead">{{ examplesOnly ? '只读预览样例输入、预期路径和校验错误；不会创建申请或执行审批。' : '选择模板，复制为当前租户的独立草稿，再按业务制度调整。' }}</p></div><button class="secondary" :disabled="locked || catalog.loading" @click="load">重新加载目录</button></div>
    <button v-if="!examplesOnly" class="secondary" :disabled="locked" @click="emit('import')">从文件导入模板</button>
    <div v-if="hasUnsavedDefinition" class="template-draft-note" role="status"><span>流程设计器中有未保存的修改，浏览模板不会覆盖内容。</span><button class="quiet" :disabled="locked" @click="emit('returnDesigner')">返回继续编辑 →</button></div>
    <p v-if="catalog.loading" class="unavailable" role="status">正在加载模板与当前租户副本…</p>
    <div v-else-if="catalog.error" class="panel template-error" role="alert"><p>{{ catalog.error }}</p><button class="secondary" :disabled="locked" @click="load">重试加载</button></div>
    <template v-else>
      <div class="template-search"><label for="template-search">查找模板</label><input id="template-search" v-model="search" type="search" placeholder="名称、分类或用途" :disabled="locked" /><span>{{ visibleTemplates.length }} 个模板</span></div>
      <div class="template-layout">
        <div class="template-catalog" aria-label="模板目录">
          <p v-if="!visibleTemplates.length" class="unavailable">{{ catalog.templates.length ? '没有匹配的模板，请调整搜索词。' : '当前没有可用模板。' }}</p>
          <button v-for="item in visibleTemplates" :key="item.key" class="template-card" :class="{ selected: selectedKey === item.key }" :aria-pressed="selectedKey === item.key" :disabled="locked" @click="choose(item.key)">
            <span class="template-card-meta">{{ item.category }} · {{ businessLabels[item.businessType] }} <span>V{{ item.templateVersion }}</span></span><strong>{{ item.name }}</strong><p>{{ item.description }}</p><span class="template-card-footer">{{ item.formSchema.fields.length }} 个字段 · {{ item.scenarios.length }} 个样例 <span>{{ item.copies.length ? `${item.copies.length} 份租户副本` : '查看详情 →' }}</span></span>
          </button>
        </div>
        <article v-if="selected" :key="selected.key" class="panel template-detail">
          <div class="template-detail-heading"><div><p class="eyebrow">{{ selected.key }} / V{{ selected.templateVersion }}</p><h3>{{ selected.name }}</h3><p>{{ selected.description }}</p></div><span class="status-chip">{{ businessLabels[selected.businessType] }}</span></div>
          <section class="template-section"><h4>适用范围</h4><p>{{ selected.scope }}</p></section>
          <form v-if="!examplesOnly" class="template-copy-form" novalidate @submit.prevent="copy">
            <h4>复制为我的流程草稿</h4><p>复制后进入设计器；审批角色与示例阈值需核对，发布由你决定。</p>
            <fieldset :disabled="locked"><label for="template-target-key">目标流程标识<input id="template-target-key" v-model="targetKey" aria-required="true" :aria-invalid="!!copyErrors.key" :aria-describedby="copyErrors.key ? 'template-key-error' : undefined" /><small v-if="copyErrors.key" id="template-key-error" class="inline-error">{{ copyErrors.key }}</small></label><label for="template-target-name">目标流程名称<input id="template-target-name" v-model="targetName" aria-required="true" :aria-invalid="!!copyErrors.name" :aria-describedby="copyErrors.name ? 'template-name-error' : undefined" /><small v-if="copyErrors.name" id="template-name-error" class="inline-error">{{ copyErrors.name }}</small></label></fieldset>
            <p v-if="copyErrors.templateVersion" class="inline-error" role="alert">{{ copyErrors.templateVersion }}</p><button class="primary" :disabled="locked">{{ locked ? '操作暂不可用' : '复制并编辑 ↗' }}</button>
          </form>
          <section class="template-section"><h4>表单字段 <span>{{ selected.formSchema.fields.length }}</span></h4><dl class="template-fields"><template v-for="field in selected.formSchema.fields" :key="field.key"><dt>{{ field.label }} <small>{{ fieldTypeName(field.type) }}{{ field.required ? ' · 必填' : ' · 选填' }}</small></dt><dd><code>{{ field.key }}</code><p>{{ ownValue(selected.fieldDescriptions, field.key) ?? field.helpText ?? '未附字段说明。' }}</p><p v-if="field.options">可选项：{{ field.options.map(option => option.label).join('、') }}</p></dd></template></dl></section>
          <section class="template-section"><h4>审批链与分支</h4><ol class="template-approval-chain"><li v-for="node in selected.graph.nodes.filter(item => item.type === 'USER_TASK')" :key="node.id"><strong>{{ node.name }}</strong><span>{{ roleName(node.properties.assigneeRule ?? '') }}</span></li></ol><ul class="template-branches"><li v-for="edge in selected.graph.edges" :key="edge.id">{{ nodeName(edge.source) }} → {{ nodeName(edge.target) }}<code v-if="edge.condition">{{ edge.condition }}</code><small v-else-if="edge.defaultBranch">默认分支</small></li></ul><p>默认角色：{{ selected.defaultRoles.map(role => roleName(role)).join('、') }}</p></section>
          <section class="template-section"><h4>依赖与使用风险</h4><ul><li v-for="dependency in selected.dependencies" :key="dependency">{{ dependency }}</li></ul><div class="template-risk"><strong>使用前核对</strong><ul><li v-for="risk in selected.risks" :key="risk">{{ risk }}</li></ul></div><h4>版本升级</h4><p>{{ selected.upgradePolicy }}</p></section>
          <section class="template-section"><h4>通知文案 <span class="template-unavailable">{{ selected.notificationsAvailable ? '站内消息可用' : '文案尚未启用' }}</span></h4><p>{{ selected.notificationsAvailable ? '复制后携带以下申请人通知文案，可在设计器调整，发布后生效。' : '以下文案仅供参考。' }}邮件和其他外部渠道尚未接入。</p><dl class="template-notifications"><template v-for="(text, key) in selected.notificationTexts" :key="key"><dt>{{ ownValue(notificationLabels, key) ?? key }}</dt><dd>{{ text }}</dd></template></dl></section>
          <section class="template-section"><h4>路径与校验样例 <span>{{ selected.scenarios.length }}</span></h4><p>以下是模板预期结果，未创建申请或执行审批。</p><details v-for="(scenario,index) in selected.scenarios" :key="scenario.id" :open="examplesOnly && index === 0" class="template-scenario"><summary>{{ scenario.name }}<span :class="{ failure: Object.keys(scenario.expectedFieldErrors).length }">{{ Object.keys(scenario.expectedFieldErrors).length ? '预期校验失败' : '预期通过' }}</span></summary><p>{{ scenario.description }}</p><FormFields :schema="selected.formSchema" :model-value="scenario.payload" readonly /><p v-if="scenario.expectedPath.length" class="template-expected-path"><strong>预期路径</strong>{{ scenario.expectedPath.map(nodeName).join(' → ') }}</p><ul v-if="Object.keys(scenario.expectedFieldErrors).length" class="template-scenario-errors"><li v-for="(code, key) in scenario.expectedFieldErrors" :key="key">{{ fieldName(key) }}：{{ fieldErrorMessage(code) }} <code>{{ code }}</code></li></ul></details></section>
          <section v-if="!examplesOnly" class="template-section"><h4>当前租户的副本 <span>{{ selected.copies.length }}</span></h4><p v-if="!selected.copies.length">还没有复制记录。复制后的草稿会显示在这里。</p><div v-for="item in selected.copies" :key="item.definitionId" class="template-copy-row"><div><strong>{{ item.name }}</strong><small>{{ item.processKey }} · {{ ownValue(statusLabels, item.status) ?? item.status }}{{ item.version ? ` v${item.version}` : '' }} · 修订 {{ item.revision }}</small><small>来自模板 V{{ item.templateVersion }} · {{ item.copiedBy }} · {{ timeLabel(item.copiedAt) }}</small></div><button class="secondary" :disabled="locked" @click="emit('open', item.definitionId)">{{ item.status === 'DRAFT' ? '打开草稿' : '查看流程' }}</button></div></section>
        </article>
        <div v-else class="panel template-empty"><div class="empty-icon">▤</div><h3>选择一份流程模板</h3><p>查看字段、审批链与样例，确认适用范围后复制。</p></div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.template-center{max-width:1600px}.template-center .page-heading{gap:20px}.template-center .page-heading>.secondary{flex-shrink:0}.template-layout{display:grid;grid-template-columns:minmax(230px,320px) minmax(0,1fr);gap:22px;align-items:start}.template-catalog{display:grid;gap:14px}.template-card{display:block;text-align:left;background:#fff;border:1px solid var(--line);border-radius:13px;padding:21px;color:var(--ink);width:100%;overflow-wrap:anywhere}.template-card.selected{border-color:var(--teal);box-shadow:inset 3px 0 0 var(--teal);background:#f7fcfa}.template-card-meta,.template-card-footer{display:flex;justify-content:space-between;gap:8px;color:var(--muted);font-size:10px;line-height:1.7}.template-card>strong{display:block;font-size:18px;margin-top:15px}.template-card p{font-size:12px;line-height:1.9;color:var(--muted);margin:10px 0 22px}.template-card-footer{border-top:1px solid var(--line);padding-top:12px}.template-card-footer>span{color:var(--deep)}.template-search{display:flex;align-items:center;gap:12px;margin:0 0 20px;font-size:12px;color:var(--muted)}.template-search input{width:min(380px,100%);min-width:0;border:1px solid var(--line);border-radius:8px;padding:10px 12px;background:#fff;color:var(--ink)}.template-search label,.template-search>span{flex-shrink:0}.template-detail{min-width:0;overflow:clip}.template-detail-heading{display:flex;justify-content:space-between;gap:12px;padding:25px 28px;border-bottom:1px solid var(--line)}.template-detail-heading h3{font-size:23px;margin:0 0 9px}.template-detail-heading .eyebrow{overflow-wrap:anywhere}.template-detail-heading p:last-child{font-size:12px;line-height:1.9;color:var(--muted);margin:0}.template-detail-heading .status-chip{height:fit-content;white-space:nowrap}.template-section{padding:22px 28px;border-bottom:1px solid var(--line);font-size:12px;line-height:1.9;overflow-wrap:anywhere}.template-section:last-child{border-bottom:0}.template-section h4,.template-copy-form h4{margin:0 0 12px;font-size:14px}.template-section h4>span{font-size:11px;font-weight:400;color:var(--muted);margin-left:8px}.template-section p{margin:8px 0;color:var(--muted)}.template-section ul{padding-left:19px;margin:10px 0}.template-section li+li{margin-top:5px}.template-section code{font-size:11px;white-space:pre-wrap}.template-copy-form{padding:22px 28px;background:var(--soft);border-block:1px solid var(--line)}.template-copy-form p{font-size:12px;line-height:1.9;color:var(--muted);margin:0 0 16px}.template-copy-form fieldset{display:grid;grid-template-columns:1fr 1fr;gap:14px}.template-copy-form label{font-size:11px;color:var(--muted)}.template-copy-form input{display:block;width:100%;border:1px solid #c9ded9;border-radius:8px;padding:10px 12px;margin:7px 0;background:#fff;color:var(--ink)}.template-copy-form input[aria-invalid=true]{border-color:var(--red)}.template-copy-form small{display:block;line-height:1.7}.template-copy-form .primary{margin-top:14px}.template-fields{display:grid;grid-template-columns:minmax(100px,28%) minmax(0,1fr);margin:0}.template-fields dt,.template-fields dd{border-top:1px solid var(--line);padding:12px 0;margin:0}.template-fields dt{font-weight:600;padding-right:15px}.template-fields dt small{display:block;font-size:10px;font-weight:400;color:var(--muted);margin-top:3px}.template-fields dd>code{color:var(--deep)}.template-approval-chain{display:flex;flex-wrap:wrap;gap:10px;list-style:none;padding:0}.template-approval-chain li{padding:12px 15px;border:1px solid var(--line);border-radius:8px;margin:0!important;background:#fafcfc}.template-approval-chain span{display:block;color:var(--muted);font-size:11px}.template-branches code,.template-branches small{margin-left:8px;color:var(--deep)}.template-risk{padding:14px 17px;margin:16px 0;background:#fff8e9;border:1px solid #efdfbb;border-radius:8px;color:#7d632d}.template-risk ul{margin-bottom:0}.template-notifications{display:grid;grid-template-columns:65px 1fr;gap:10px}.template-notifications dt{color:var(--muted)}.template-notifications dd{margin:0}.template-scenario{border:1px solid var(--line);border-radius:8px;margin-top:10px;padding:0 15px}.template-scenario summary{cursor:pointer;padding:13px 0;font-weight:600}.template-scenario summary>span{font-size:10px;color:var(--deep);font-weight:400;float:right;margin-left:10px}.template-scenario summary>span.failure{color:var(--red)}.template-scenario-errors{color:var(--red)}.template-expected-path{padding:12px;background:#f4f8f7;border-radius:7px}.template-expected-path strong{display:block;color:var(--ink)}.template-copy-row{display:flex;align-items:center;gap:12px;justify-content:space-between;padding:14px 0;border-top:1px solid var(--line)}.template-copy-row>div{min-width:0}.template-copy-row small{display:block;color:var(--muted);font-size:10px}.template-copy-row button{flex-shrink:0}.template-empty{min-height:330px;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:28px;text-align:center}.template-empty h3{font-size:17px;margin:12px 0}.template-empty p{color:var(--muted);font-size:12px;line-height:1.9}.template-draft-note{display:flex;align-items:center;justify-content:space-between;gap:12px;background:#fff8e9;border:1px solid #efdfbb;border-radius:8px;padding:12px 16px;margin-bottom:18px;font-size:12px;line-height:1.8}.template-draft-note button{color:var(--deep);white-space:nowrap}.template-error{padding:24px;color:var(--red);font-size:12px}
@media(max-width:1000px){.template-layout{grid-template-columns:1fr}.template-catalog{grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.template-card{padding:16px}.template-card-footer{flex-direction:column}.template-card p{margin-bottom:15px}.template-detail-heading{padding:22px}}
@media(max-width:650px){.template-catalog{grid-template-columns:1fr}.template-card{padding:17px}.template-card>strong{font-size:17px;margin-top:7px}.template-card p{margin:7px 0 10px}.template-card-footer{flex-direction:row}.template-search{flex-wrap:wrap;gap:8px}.template-search input{order:3;width:100%}.template-search>span{margin-left:auto}.template-detail-heading,.template-section,.template-copy-form{padding:20px 17px}.template-copy-form fieldset{grid-template-columns:1fr}.template-draft-note{align-items:start;flex-direction:column}.template-fields{grid-template-columns:1fr}.template-fields dt{padding-bottom:2px}.template-fields dd{border-top:0;padding-top:2px}.template-fields dt small{display:inline;margin-left:6px}.template-copy-row{align-items:start;flex-direction:column}.template-detail-heading .status-chip{display:none}.template-scenario summary>span{float:none;display:block;margin:2px 0 0}.template-section h4>.template-unavailable{display:block;margin-left:0}.template-center .page-heading>.secondary{margin-top:15px}}
</style>
