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
     * 已校验的 UTC 提交日期范围与精确流程筛选。
     * @author owlzhangfq@gmail.com
     */
    record Query(LocalDate from, LocalDate to, String processKey, Long definitionVersion) {
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
            BigDecimal rate = decided == 0 ? null : BigDecimal.valueOf(returned).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(decided), 1, RoundingMode.HALF_UP);
            return new Metrics(submitted, applications, inApproval, approved, returned, rejected, withdrawn,
                    durationSamples, averageApprovalSeconds, decided, rate);
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
                       long tasks, Instant oldestCreatedAt, long oldestWaitSeconds) { }
    /**
     * 管理员可追溯到申请的待办摘要，不含审批正文。
     * @author owlzhangfq@gmail.com
     */
    record WaitingTask(String taskId, String taskName, String applicationId, String businessNo, String title,
                       String processKey, long definitionVersion, int roundNo, String assignee,
                       Instant createdAt, long waitingSeconds) { }
    /**
     * 日期窗口按提交时间划定；当前待办和缺失历史轮次只受流程筛选影响。
     * @author owlzhangfq@gmail.com
     */
    record Report(Instant generatedAt, LocalDate from, LocalDate to, String timeZone, String processKey,
                  Long definitionVersion, Metrics metrics, List<Daily> daily, List<ProcessSummary> processes,
                  boolean moreProcesses, long pendingTasks, List<WaitingNode> waitingNodes, boolean moreWaitingNodes,
                  List<WaitingTask> oldestTasks, boolean moreOldestTasks, long unrecordedHistoricalRounds) { }
}
