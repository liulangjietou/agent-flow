<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import DefinitionPicker from './DefinitionPicker.vue'
import { FirstWorkflowQuery, guideHidden, guideSelection, hideGuide, rememberGuideSelection, workflowSteps } from '../firstWorkflow'
import { SystemChecksQuery } from '../systemChecks'

const props = defineProps<{ scopeKey: string; refreshVersion: number; locked: boolean }>()
const emit = defineEmits<{ templates: []; import: []; examples: []; new: []; edit: [id: string]; apply: [id: string]; open: [id: string]; checks: []; workbench: [] }>()
const query = reactive(new FirstWorkflowQuery(api.firstWorkflow))
const diagnostics = reactive(new SystemChecksQuery(api.systemChecks))
const selectedId = ref('')
const hidden = ref(false)
const report = computed(() => query.report)
const steps = computed(() => report.value ? workflowSteps(report.value) : [false, false, false, false])
const coreIds = ['database', 'migrations', 'flowable', 'templates']
const coreChecks = computed(() => diagnostics.report?.checks.filter(check => coreIds.includes(check.id)) ?? [])
const coreReady = computed(() => coreIds.every(id => coreChecks.value.some(check => check.id === id && check.status === 'UP')))
const status = (value: string) => ({ DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档', IN_APPROVAL: '审批中', APPROVED: '已批准', REJECTED: '已驳回', RETURNED: '已退回', WITHDRAWN: '已撤回' }[value] ?? value)
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
const stepLabels = ['保存流程', '发布版本', '提交申请', '完成批准']
const evidence = computed(() => report.value?.latestApproval ?? report.value?.latestSubmission)
function refresh() { void query.load(props.scopeKey, selectedId.value) }
function select(id: string) {
  selectedId.value = id
  rememberGuideSelection(props.scopeKey, selectedId.value); refresh()
}
function updatePreference() { hideGuide(props.scopeKey, hidden.value) }
watch(() => props.scopeKey, () => {
  diagnostics.clear(); selectedId.value = guideSelection(props.scopeKey); hidden.value = guideHidden(props.scopeKey); refresh()
}, { immediate: true, flush: 'sync' })
watch(() => props.refreshVersion, refresh)
onUnmounted(() => { query.clear(); diagnostics.clear() })
</script>

<template>
  <section class="content first-workflow">
    <div class="page-heading">
      <div><p class="eyebrow">GETTING STARTED / FIRST WORKFLOW</p><h2>让第一条审批真正跑起来。</h2><p class="subhead">从模板到真实申请，每一步都有可以核对的记录。</p></div>
      <button class="secondary" @click="emit('workbench')">进入工作台 →</button>
    </div>
    <div class="guide-scope"><strong>当前为开发与演示环境</strong><span>租户、法人、企业身份和组织初始化尚未完成。下方引导帮助验证已有的流程能力，正式使用前需完成企业接入。</span></div>
    <div class="guide-paths">
      <article class="panel guide-path featured"><span class="guide-number">01 / RECOMMENDED</span><h3>从模板开始</h3><p>请假、用印、合同审批已有字段与路由样例，复制后按实际制度调整。</p><button class="primary" :disabled="locked" @click="emit('templates')">选择并复制模板 ↗</button><button class="quiet" :disabled="locked" @click="emit('import')">从文件导入模板 →</button></article>
      <article class="panel guide-path"><span class="guide-number">02 / DESIGN</span><h3>创建自己的流程</h3><p>打开可视化设计器，配置字段、审批人和条件，校验并模拟后发布。</p><button class="secondary" :disabled="locked" @click="emit('new')">新建审批流程</button></article>
      <article class="panel guide-path"><span class="guide-number">03 / EXPLORE</span><h3>先看示例数据</h3><p>预览表单样例、预期路径和校验错误，理解一条流程会怎样运行。</p><button class="secondary" :disabled="locked" @click="emit('examples')">只读查看样例</button></article>
    </div>
    <div class="guide-layout">
      <section class="panel guide-progress" :aria-busy="query.loading" aria-labelledby="guide-progress-title">
        <div class="guide-section-heading"><div><p class="eyebrow">YOUR WORKFLOW</p><h3 id="guide-progress-title">接着上一次继续</h3></div><button class="quiet" :disabled="query.loading" @click="refresh">刷新进度</button></div>
        <DefinitionPicker :scope-key="scopeKey" label="验证流程" :refresh-version="refreshVersion" :selected-id="selectedId || report?.definition?.id" :selected-label="report?.definition ? report.definition.name + (report.definition.version ? ' · v' + report.definition.version : ' · 草稿') : ''" :locked="locked" @select="select($event.id)" />
        <button type="button" class="quiet" :disabled="query.loading" @click="select('')">自动选择最近更新的流程</button>
        <p v-if="query.loading" class="guide-empty" role="status">正在读取当前租户的流程与轮次记录…</p>
        <div v-else-if="query.error" class="guide-empty" role="alert"><strong>未取得流程进度</strong><p>{{ query.error }}</p><button class="secondary" @click="refresh">重试读取</button><p>也可以在上方重新选择流程。</p></div>
        <template v-else-if="report">
          <p v-if="report.unrecordedHistoricalRounds" class="guide-scope" role="status">此版本有 {{ report.unrecordedHistoricalRounds }} 个历史轮次缺少快照，无法核对旧提交与结论。下方进度只依据可核对的记录。</p>
          <ol class="guide-steps" aria-label="流程验证进度">
            <li v-for="(label,index) in stepLabels" :key="label" :class="{ complete: steps[index] }"><span>{{ steps[index] ? '✓' : index + 1 }}</span><div><strong>{{ label }}</strong><small>{{ steps[index] ? '已有实际记录' : report.unrecordedHistoricalRounds && index > 1 ? '暂无可核对记录' : '待完成' }}</small></div></li>
          </ol>
          <div v-if="!report.definition" class="guide-next"><h4>还没有保存的流程</h4><p>从上方选择模板或新建流程。保存后，这里会显示该流程的实际进度。</p><button class="primary" :disabled="locked" @click="emit('templates')">选择第一份模板</button></div>
          <div v-else class="guide-next">
            <div class="guide-definition"><strong>{{ report.definition.name }}</strong><span>{{ report.definition.key }} · {{ status(report.definition.status) }}{{ report.definition.version ? ' v' + report.definition.version : '' }}</span></div>
            <template v-if="report.definition.status === 'DRAFT'"><h4>核对配置，再发布版本</h4><p>草稿已保存。继续检查表单字段、审批人和路由，运行模拟并填写发布说明。</p><button class="primary" :disabled="locked" @click="emit('edit', report.definition.id)">继续设计与发布</button></template>
            <template v-else-if="report.definition.status === 'ARCHIVED'"><h4>此版本已归档</h4><p>历史运行记录仍可查看，新申请需选择可用的已发布版本。</p><button class="secondary" :disabled="locked" @click="emit('edit', report.definition.id)">查看流程版本</button></template>
            <template v-else-if="!report.submittedRounds"><h4>版本已发布，提交一份验证申请</h4><p>使用该版本填写真实表单并提交，再由配置的审批账号办理。不会自动替你提交或批准。</p><button class="primary" :disabled="locked" @click="emit('apply', report.definition.id)">用此版本发起申请</button></template>
            <template v-else-if="!report.approvedRounds"><h4>已有 {{ report.submittedRounds }} 次提交，继续完成审批</h4><p>由流程中配置的审批账号登录并办理。退回后可修改重提，撤回和驳回不算作已批准。</p><button class="secondary" :disabled="locked" @click="emit('workbench')">查看当前账号待办</button><button v-if="report.latestSubmission" class="primary" @click="emit('open', report.latestSubmission.applicationId)">查看最近申请</button></template>
            <template v-else><h4>这个版本已经走完一次批准流程</h4><p>{{ report.submittedRounds }} 个提交轮次中，已有 {{ report.approvedRounds }} 轮批准。可以核对原始轨迹与审计，继续验证其他分支。</p><button v-if="report.latestApproval" class="primary" @click="emit('open', report.latestApproval.applicationId)">查看批准记录</button><button class="secondary" :disabled="locked" @click="emit('edit', report.definition.id)">查看发布版本</button></template>
            <div v-if="evidence" class="guide-evidence"><span>依据：{{ evidence.businessNo }} · 第 {{ evidence.roundNo }} 轮 · {{ status(evidence.status) }}</span><small>{{ time(evidence.submittedAt) }} 提交{{ evidence.completedAt ? '，' + time(evidence.completedAt) + ' 结束' : '' }}</small></div>
          </div>
          <p class="guide-footnote">查询于 {{ time(report.checkedAt) }}。只统计所选流程版本的真实轮次；旧版本、浏览样例和模拟结果不计入。</p>
        </template>
      </section>
      <aside class="panel guide-environment" aria-labelledby="guide-environment-title">
        <p class="eyebrow">ENVIRONMENT</p><h3 id="guide-environment-title">运行前，检查环境</h3><p>确认数据库、迁移、流程引擎和模板可用，再开始配置。</p>
        <p v-if="diagnostics.loading" role="status">正在执行实际依赖检查…</p>
        <div v-else-if="diagnostics.error" role="alert"><strong>未取得环境结果</strong><p>{{ diagnostics.error }}</p></div>
        <template v-else-if="diagnostics.report"><strong :class="coreReady ? 'guide-ready' : 'guide-caution'">{{ coreReady ? '四项运行基础检查通过' : '运行基础尚未全部确认' }}</strong><p>检查于 {{ time(diagnostics.report.checkedAt) }}。企业认证、组织、对象存储和模型仍有待接入项。</p></template>
        <p v-else class="guide-footnote">尚未执行检查，不能据此判断服务就绪。</p>
        <button class="secondary" :disabled="diagnostics.loading" @click="diagnostics.load(scopeKey)">{{ diagnostics.loading ? '正在检查…' : '检查运行环境' }}</button><button class="quiet" @click="emit('checks')">查看完整系统自检 →</button>
        <div class="guide-account-note"><strong>切换账号验证</strong><p>演示环境可用申请人 alice、审批人 manager / finance，以及 admin；具体处理人由你配置的节点规则决定。</p><p>从左下角退出，再以对应账号登录。引导不代办审批，也不更改角色。</p></div>
      </aside>
    </div>
    <label class="guide-preference"><input v-model="hidden" type="checkbox" @change="updatePreference" />以后登录直接进入工作台<span>随时可以从侧栏“开始使用”返回。</span></label>
  </section>
</template>

<style scoped>
.first-workflow{max-width:1600px}
.guide-scope{display:flex;gap:14px;flex-wrap:wrap;padding:14px 18px;margin:0 0 24px;background:#fff9eb;border:1px solid #eadbb8;border-radius:9px;color:#78622e;font-size:12px;line-height:1.8}
.guide-paths{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin-bottom:26px}
.guide-path{padding:25px;display:flex;flex-direction:column;align-items:start}.guide-path.featured{border-color:#b7dcd4;background:#f4faf8}
.guide-number{color:var(--deep);font-size:10px;letter-spacing:1px}.guide-path h3{font-size:20px;margin:21px 0 10px}.guide-path p{font-size:12px;line-height:1.9;color:var(--muted);margin:0 0 23px;flex:1}.guide-path button{font-size:12px}
.guide-layout{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:24px;align-items:start}.guide-progress{padding:26px;min-width:0}.guide-section-heading{display:flex;justify-content:space-between;gap:18px;align-items:center;margin-bottom:23px}.guide-section-heading h3{margin:10px 0 0;font-size:20px}.guide-section-heading button{font-size:12px;color:var(--deep);white-space:nowrap}
.guide-steps{list-style:none;padding:0;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:26px 0}.guide-steps li{display:flex;gap:9px;align-items:center;min-width:0}.guide-steps li>span{display:grid;place-items:center;flex-shrink:0;width:29px;height:29px;border-radius:50%;border:1px solid var(--line);color:var(--muted);font-size:11px}.guide-steps li.complete>span{background:var(--deep);border-color:var(--deep);color:white}.guide-steps strong{font-size:11px;font-weight:500}.guide-steps small{display:block;font-size:10px;color:var(--muted);margin-top:6px;line-height:1.6}
.guide-next{border:1px solid var(--line);background:#fafcfc;padding:23px;border-radius:9px}.guide-next h4{font-size:17px;margin:17px 0 11px}.guide-next p{font-size:12px;line-height:1.9;color:var(--muted)}.guide-next button{font-size:12px;margin:10px 10px 0 0}.guide-definition{display:grid;gap:7px;padding-bottom:15px;border-bottom:1px solid var(--line);overflow-wrap:anywhere}.guide-definition strong{font-size:13px}.guide-definition span{font-size:11px;color:var(--muted)}.guide-evidence{display:grid;gap:6px;font-size:11px;color:var(--deep);margin-top:20px;line-height:1.8;overflow-wrap:anywhere}.guide-evidence small{color:var(--muted)}.guide-footnote{font-size:11px;color:var(--muted);line-height:1.9;margin-top:17px}
.guide-environment{padding:25px}.guide-environment h3{font-size:18px;margin:16px 0}.guide-environment p{font-size:12px;color:var(--muted);line-height:1.9}.guide-environment button{display:block;width:100%;margin-top:13px;font-size:12px}.guide-ready,.guide-caution{font-size:13px;line-height:1.8}.guide-ready{color:var(--deep)}.guide-caution{color:#816425}.guide-account-note{border-top:1px solid var(--line);padding-top:22px;margin-top:24px}.guide-account-note strong{font-size:12px}.guide-account-note p{font-size:11px}
.guide-empty{padding:30px 5px;font-size:13px;line-height:1.9;color:var(--muted);text-align:center}.guide-empty button{font-size:12px;margin-top:8px}.guide-preference{display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:12px;margin:24px 0;color:var(--ink)}.guide-preference span{color:var(--muted);font-size:11px;margin-left:8px}
@media(max-width:1150px){.guide-layout{grid-template-columns:minmax(0,1fr)}.guide-account-note{margin-top:18px}.guide-environment button{width:auto;display:inline-block;margin-right:15px}}
@media(max-width:800px){.guide-paths{grid-template-columns:1fr}.guide-path{padding:22px}.guide-path h3{margin-top:12px}.guide-path p{margin-bottom:16px}}
@media(max-width:650px){.first-workflow .page-heading{flex-direction:column;align-items:start;gap:15px}.guide-progress,.guide-environment{padding:20px 16px}.guide-steps{grid-template-columns:repeat(2,minmax(0,1fr));gap:17px}.guide-next{padding:18px 15px}.guide-next button{width:100%;margin-right:0}.guide-scope{gap:4px}.guide-preference span{margin-left:25px}.guide-section-heading h3{font-size:18px}}
</style>
