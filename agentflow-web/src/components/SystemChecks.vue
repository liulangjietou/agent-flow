<script setup lang="ts">
import { computed, onUnmounted, reactive, watch } from 'vue'
import { api } from '../api'
import { SystemChecksQuery } from '../systemChecks'

const props = defineProps<{ scopeKey: string; refreshVersion: number }>()
const emit = defineEmits<{ templates: []; designer: [] }>()
const query = reactive(new SystemChecksQuery(api.systemChecks))
const coreIds = ['database', 'migrations', 'flowable', 'templates']
const core = computed(() => query.report?.checks.filter(check => coreIds.includes(check.id)) ?? [])
const connections = computed(() => query.report?.checks.filter(check => !coreIds.includes(check.id)) ?? [])
const passed = computed(() => core.value.filter(check => check.status === 'UP').length)
const labels: Record<string, string> = { database: '数据库', migrations: '数据库迁移', flowable: '流程引擎', templates: '官方流程模板', authentication: '身份认证', sessionStorage: '登录会话存储', objectStorage: '文件与对象存储', notifications: '审批通知', organization: '组织与人员同步', model: 'Agent 模型服务' }
const states = { UP: '检查通过', DOWN: '检查失败', UNKNOWN: '结果未确认', WARNING: '待完善', NOT_IMPLEMENTED: '尚未接入' }
const time = computed(() => query.report ? new Date(query.report.checkedAt).toLocaleString('zh-CN') : '')
function refresh() { void query.load(props.scopeKey) }
watch([() => props.scopeKey, () => props.refreshVersion], refresh, { immediate: true, flush: 'sync' })
onUnmounted(() => query.clear())
</script>

<template>
  <section class="content system-checks" :aria-busy="query.loading">
    <div class="page-heading"><div><p class="eyebrow">WORKSPACE / SYSTEM CHECKS</p><h2>系统自检</h2><p class="subhead">查看实际运行状态，再开始第一条审批。</p></div><button class="secondary" :disabled="query.loading" @click="refresh">{{ query.loading ? '正在检查…' : '重新检查' }}</button></div>
    <div class="system-scope-note"><strong>当前版本用于开发与演示</strong><span>检查通过仅表示已实现的基础服务可用。站内消息已可用；认证模式以下方检查结果为准。组织、邮件 / IM、附件和模型服务仍需接入。</span></div>
    <div v-if="query.loading" class="panel system-empty" role="status"><strong>正在检查依赖</strong><p>查询数据库、迁移记录与流程引擎，请稍候。</p></div>
    <div v-else-if="query.error" class="panel system-empty" role="alert"><strong>未取得自检结果</strong><p>{{ query.error }}</p><button class="secondary" @click="refresh">重试检查</button></div>
    <template v-else-if="query.report">
      <div class="system-overview"><div><strong>{{ passed }}<small> / {{ core.length }}</small></strong><span>基础检查通过</span></div><p>本次检查完成于 <time>{{ time }}</time><br /><span>状态为本次查询快照，可随时重新检查。</span></p></div>
      <div class="system-layout">
        <div>
          <section class="panel system-panel" aria-labelledby="system-core-title"><div class="system-panel-heading"><h3 id="system-core-title">运行基础</h3><span>真实依赖查询</span></div><article v-for="check in core" :key="check.id" class="system-check-row"><div><h4>{{ labels[check.id] ?? check.id }}</h4><p>{{ check.message }}</p><code v-if="check.status !== 'UP'">{{ check.code }}</code></div><span class="system-status" :class="check.status.toLowerCase()">{{ states[check.status] }}</span></article></section>
          <section class="panel system-panel" aria-labelledby="system-adapters-title"><div class="system-panel-heading"><h3 id="system-adapters-title">服务接入</h3><span>当前实现状态</span></div><article v-for="check in connections" :key="check.id" class="system-check-row"><div><h4>{{ labels[check.id] ?? check.id }}</h4><p>{{ check.message }}</p></div><span class="system-status" :class="check.status.toLowerCase()">{{ states[check.status] }}</span></article></section>
        </div>
        <aside class="panel system-start"><p class="eyebrow">FIRST WORKFLOW</p><h3>从一条真实审批开始</h3><p>按实际制度配置表单、审批组与分支，核对身份和审批人目录就绪后，再发布试用。</p><ol><li><strong>选择官方模板</strong><span>请假、用印、合同，复制后独立编辑。</span></li><li><strong>检查并发布流程</strong><span>在流程管理中调整字段和审批规则。</span></li><li><strong>提交与处理申请</strong><span>使用申请人与审批人账号完成流程，查看实际轨迹。</span></li></ol><button class="primary" @click="emit('templates')">从模板开始 ↗</button><button class="secondary" @click="emit('designer')">打开流程管理</button><p class="system-note">正式接入前需核对组织目录、审批人来源和权限。</p></aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
.system-scope-note{display:flex;gap:12px;flex-wrap:wrap;padding:15px 18px;border:1px solid #eadbb8;background:#fff9eb;border-radius:10px;font-size:12px;line-height:1.8;color:#78622e;margin-bottom:24px}
.system-overview{display:flex;align-items:center;justify-content:space-between;gap:20px;margin:0 0 24px}.system-overview>div{display:flex;gap:20px;align-items:center}.system-overview strong{font-size:38px;letter-spacing:-2px;color:var(--deep)}.system-overview strong small{font-size:22px;color:var(--muted);font-weight:400}.system-overview span,.system-overview p{font-size:12px;color:var(--muted);line-height:1.9}.system-overview time{color:var(--ink)}
.system-layout{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:24px;align-items:start}.system-panel{margin-bottom:20px;padding:0 24px}.system-panel-heading{display:flex;justify-content:space-between;gap:10px;align-items:center;padding:23px 0 18px}.system-panel-heading h3{margin:0;font-size:16px}.system-panel-heading>span{font-size:11px;color:var(--muted)}.system-check-row{display:flex;justify-content:space-between;gap:18px;padding:20px 0;border-top:1px solid var(--line)}.system-check-row h4{font-size:13px;margin:0 0 7px}.system-check-row p{font-size:12px;line-height:1.8;color:var(--muted);margin:0}.system-check-row code{display:block;font-size:10px;color:var(--red);margin-top:7px;overflow-wrap:anywhere}.system-status{flex-shrink:0;align-self:start;font-size:10px;line-height:1.5;padding:5px 8px;border-radius:5px;background:#f1f3f4;color:var(--muted)}.system-status.up{color:var(--deep);background:var(--soft)}.system-status.down{color:var(--red);background:#fff0ed}.system-status.unknown,.system-status.warning{color:#816425;background:#fff4d9}
.system-start{padding:25px}.system-start h3{font-size:19px;margin:14px 0}.system-start p{font-size:12px;line-height:1.9;color:var(--muted)}.system-start ol{padding-left:20px;margin:24px 0}.system-start li{margin:20px 0;padding-left:5px;color:var(--deep);font-size:12px}.system-start li strong{color:var(--ink);font-weight:600}.system-start li span{display:block;color:var(--muted);line-height:1.8;margin-top:6px}.system-start button{width:100%;margin-top:10px}.system-start .system-note{font-size:11px;margin:18px 0 0}.system-empty{padding:50px 24px;text-align:center}.system-empty p{color:var(--muted);font-size:12px;line-height:1.8}
@media(max-width:1150px){.system-layout{grid-template-columns:minmax(0,1fr)}.system-start{max-width:none}.system-start button{width:auto;margin-right:10px}.system-start ol{display:flex;gap:30px}.system-start li{flex:1}}
@media(max-width:650px){.system-checks .page-heading{flex-direction:column;align-items:start;gap:15px}.system-overview{align-items:start;flex-direction:column;gap:4px}.system-panel{padding:0 17px}.system-check-row{gap:12px}.system-panel-heading>span{display:none}.system-start ol{display:block}.system-start button{width:100%;margin-right:0}.system-start{padding:21px}.system-scope-note{gap:4px}}
</style>
