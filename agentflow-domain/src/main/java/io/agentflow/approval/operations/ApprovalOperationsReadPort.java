package io.agentflow.approval.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 租户审批运营读模型；轮次与任务事实保留各自口径，不复制运行状态。
 * @author owlzhangfq@gmail.com
 */
public interface ApprovalOperationsReadPort {
    /** 已授权的管理员读取租户范围内同一数据库快照。 */
    Report read(String tenantId, Query query, Instant generatedAt);

    /**
     * 已校验的 UTC 提交日期、精确流程和提交时组织名称筛选。
     * @author owlzhangfq@gmail.com
     */
    record Query(LocalDate from, LocalDate to, String processKey, Long definitionVersion, String organization) {
        /** 保留未设置组织条件的既有内部调用。 */
        public Query(LocalDate from, LocalDate to, String processKey, Long definitionVersion) {
            this(from, to, processKey, definitionVersion, "");
        }
        /** 起始日包含，结束日次日零点不包含。 */
        public Instant fromInclusive() { return from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
        /** 将用户看到的结束日期转换为半开区间上界。 */
        public Instant toExclusive() { return to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
    }

    /**
     * 已确认的退回率只使用有审批结论的轮次；无分母时没有比例值。
     * @author owlzhangfq@gmail.com
     */
    record Metrics(long submittedRounds, long applications, long inApproval, long approved, long returned,
                   long rejected, long withdrawn, long durationSamples, Long averageApprovalSeconds,
                   long decidedRounds, BigDecimal returnRatePercent) {
        /** 从可信聚合计数计算派生指标，撤回与在审不进入退回率分母。 */
        public static Metrics of(long submitted, long applications, long inApproval, long approved, long returned,
                                 long rejected, long withdrawn, long durationSamples, Long averageApprovalSeconds) {
            long decided = approved + returned + rejected;
            BigDecimal rate = percentage(returned, decided);
            return new Metrics(submitted, applications, inApproval, approved, returned, rejected, withdrawn,
                    durationSamples, averageApprovalSeconds, decided, rate);
        }
    }

    /** 无样本时省略比例，已确认的零样本结果不能伪装为零百分比。 */
    private static BigDecimal percentage(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    /**
     * 历史人工任务的实际决定与期限；取消和缺失事实不作为按时完成。
     * @author owlzhangfq@gmail.com
     */
    record SlaMetrics(long decidedTasks, long timedTasks, long violatedTasks, long withoutDeadlineTasks,
                      long invalidTimingTasks, long cancelledTasks, long unfinishedTasks, long unrecordedDecisionTasks, long unverifiedRounds,
                      BigDecimal violationRatePercent) {
        /** 只有具备有效时刻和期限的已办理任务进入违约率分母。 */
        public static SlaMetrics of(long onTime, long violated, long withoutDeadline, long invalidTiming,
                                    long cancelled, long unfinished, long unrecordedDecision, long unverifiedRounds) {
            long timed = onTime + violated;
            return new SlaMetrics(timed + withoutDeadline + invalidTiming, timed, violated, withoutDeadline,
                    invalidTiming, cancelled, unfinished, unrecordedDecision, unverifiedRounds, percentage(violated, timed));
        }
    }

    /**
     * 外部投递按原记录计数，受理不代表送达，曾失败依据追加历史去重。
     * @author owlzhangfq@gmail.com
     */
    record NotificationMetrics(long deliveries, long accepted, long failed, long retryWaiting, long unknown,
                               long suppressed, long pending, long inFlight, long previouslyFailed) { }

    /**
     * 人工采纳与模型生成分开，未复核运行和执行失败不进入采纳率分母。
     * @author owlzhangfq@gmail.com
     */
    record AgentMetrics(long runs, long queued, long running, long awaitingReview, long failed, long adopted,
                        long dismissed, long reviewedRuns, BigDecimal adoptionRatePercent) {
        /** 已采纳和已拒绝构成人工复核样本，采纳不产生审批结论。 */
        public static AgentMetrics of(long queued, long running, long awaitingReview, long failed, long adopted, long dismissed) {
            long reviewed = adopted + dismissed;
            return new AgentMetrics(queued + running + awaitingReview + failed + reviewed, queued, running,
                    awaitingReview, failed, adopted, dismissed, reviewed, percentage(adopted, reviewed));
        }
    }

    /**
     * UTC 日期的提交轮次，空日期由适配器补零。
     * @author owlzhangfq@gmail.com
     */
    record Daily(LocalDate date, long submittedRounds) { }
    /**
     * 流程版本各自汇总，版本之间不混并节点和结论。
     * @author owlzhangfq@gmail.com
     */
    record ProcessSummary(String processKey, long definitionVersion, Metrics metrics) { }
    /**
     * 当前等待最久的节点，人数按实际独立任务计数。
     * @author owlzhangfq@gmail.com
     */
    record WaitingNode(String processKey, long definitionVersion, String nodeId, String nodeName,
                       long tasks, Instant oldestCreatedAt, long oldestWaitSeconds, long overdueTasks) { }
    /**
     * 管理员可追溯到申请的待办摘要，不含审批正文。
     * @author owlzhangfq@gmail.com
     */
    record WaitingTask(String taskId, String taskName, String applicationId, String businessNo, String title,
                       String processKey, long definitionVersion, int roundNo, String assignee,
                       Instant createdAt, long waitingSeconds, Instant dueAt) { }
    /**
     * 历史指标按各自轮次的组织筛选，当前待办按在审轮次；缺失历史仍仅披露流程范围。
     * @author owlzhangfq@gmail.com
     */
    record Report(Instant generatedAt, LocalDate from, LocalDate to, String timeZone, String processKey,
                  Long definitionVersion, String organization, Metrics metrics, List<Daily> daily, List<ProcessSummary> processes,
                  boolean moreProcesses, long pendingTasks, long overdueTasks, List<WaitingNode> waitingNodes, boolean moreWaitingNodes,
                  List<WaitingTask> oldestTasks, boolean moreOldestTasks, long unrecordedHistoricalRounds,
                  SlaMetrics sla, NotificationMetrics notifications, AgentMetrics agent) { }
}
