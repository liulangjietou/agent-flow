<script setup lang="ts">
import type { OperationsAgent, OperationsNotifications, OperationsSla } from '../approvalOperations'
defineProps<{ sla: OperationsSla; notifications: OperationsNotifications; agent: OperationsAgent }>()
const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const percent = (value?: number) => value == null ? '—' : value.toFixed(1) + '%'
</script>

<template>
  <section class="outcome-metrics" aria-labelledby="outcome-metrics-title">
    <h3 id="outcome-metrics-title">这些提交轮次的办理结果</h3>
    <p class="scope">沿用上方提交日期、流程版本及提交时组织；显示截至本次查询的结果，结果可以在提交窗口之后产生。</p>
    <div class="cards">
      <article aria-labelledby="sla-metric-title">
        <h4 id="sla-metric-title">历史 SLA 违约率</h4>
        <strong>{{ percent(sla.violationRatePercent) }}</strong>
        <p>{{ number(sla.violatedTasks) }} 项超期办理 / {{ number(sla.timedTasks) }} 项有效期限样本</p>
        <dl>
          <div><dt>实际办理</dt><dd>{{ number(sla.decidedTasks) }}</dd></div>
          <div><dt>未记录期限</dt><dd>{{ number(sla.withoutDeadlineTasks) }}</dd></div>
          <div><dt>时间异常</dt><dd>{{ number(sla.invalidTimingTasks) }}</dd></div>
          <div><dt>取消任务</dt><dd>{{ number(sla.cancelledTasks) }}</dd></div>
          <div><dt>尚未完成</dt><dd>{{ number(sla.unfinishedTasks) }}</dd></div>
          <div><dt>缺少办理记录</dt><dd>{{ number(sla.unrecordedDecisionTasks) }}</dd></div>
        </dl>
        <p v-if="sla.unverifiedRounds" class="missing">{{ number(sla.unverifiedRounds) }} 个轮次无法核实引擎历史，未纳入以上样本。</p>
        <p class="boundary">仅统计真实批准、退回和驳回；使用暂停恢复后的实际期限。取消、缺失与异常样本不进入分母，到期瞬间完成不算违约。</p>
      </article>
      <article aria-labelledby="notification-metric-title">
        <h4 id="notification-metric-title">外部通知当前失败</h4>
        <strong>{{ number(notifications.failed) }}<small> / {{ number(notifications.deliveries) }} 条投递</small></strong>
        <p>曾明确失败 {{ number(notifications.previouslyFailed) }} 条，恢复成功仍保留此历史计数</p>
        <dl>
          <div><dt>渠道已受理</dt><dd>{{ number(notifications.accepted) }}</dd></div>
          <div><dt>等待重试</dt><dd>{{ number(notifications.retryWaiting) }}</dd></div>
          <div><dt>结果未知</dt><dd>{{ number(notifications.unknown) }}</dd></div>
          <div><dt>已抑制</dt><dd>{{ number(notifications.suppressed) }}</dd></div>
          <div><dt>排队中</dt><dd>{{ number(notifications.pending) }}</dd></div>
          <div><dt>发送中</dt><dd>{{ number(notifications.inFlight) }}</dd></div>
        </dl>
        <p class="boundary">每条收件人／渠道投递计一次，重试不新增样本。未知不计为明确失败，渠道受理不代表最终送达。</p>
      </article>
      <article aria-labelledby="agent-metric-title">
        <h4 id="agent-metric-title">审批摘要人工采纳率</h4>
        <strong>{{ percent(agent.adoptionRatePercent) }}</strong>
        <p>{{ number(agent.adopted) }} 次采纳 / {{ number(agent.reviewedRuns) }} 次人工复核</p>
        <dl>
          <div><dt>运行总数</dt><dd>{{ number(agent.runs) }}</dd></div>
          <div><dt>已拒绝建议</dt><dd>{{ number(agent.dismissed) }}</dd></div>
          <div><dt>等待复核</dt><dd>{{ number(agent.awaitingReview) }}</dd></div>
          <div><dt>执行失败</dt><dd>{{ number(agent.failed) }}</dd></div>
          <div><dt>排队中</dt><dd>{{ number(agent.queued) }}</dd></div>
          <div><dt>执行中</dt><dd>{{ number(agent.running) }}</dd></div>
        </dl>
        <p class="boundary">仅统计所选审批轮次的摘要，不含申请草稿建议。采纳包含人工修改后采纳，不等于审批通过。未复核和执行失败不进入分母；无有效样本时比例显示“—”。</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.missing{color:var(--red,#a94843)}.outcome-metrics{margin:26px 0}.outcome-metrics h3{font-size:16px;margin:0 0 8px}.scope,.boundary{color:var(--muted);font-size:11px;line-height:1.8}.scope{margin-bottom:16px}.cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.cards article{background:white;border:1px solid var(--line);border-radius:10px;padding:22px;min-width:0}.cards h4{font-size:12px;font-weight:500;color:var(--muted);margin:0 0 12px}.cards strong{font-size:30px;color:var(--deep);font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.cards strong small{font-size:12px;font-weight:400}.cards p{font-size:11px;line-height:1.8}.cards dl{margin:18px 0}.cards dl>div{display:flex;justify-content:space-between;gap:12px;border-bottom:1px solid var(--line);padding:7px 0;font-size:11px}.cards dt{color:var(--muted)}.cards dd{margin:0;font-variant-numeric:tabular-nums}
@media(max-width:1100px){.cards{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){.cards{grid-template-columns:minmax(0,1fr)}.cards article{padding:18px}}
</style>
