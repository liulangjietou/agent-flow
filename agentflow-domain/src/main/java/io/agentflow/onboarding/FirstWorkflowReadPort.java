package io.agentflow.onboarding;

import java.time.Instant;
import java.util.UUID;

/**
 * 首条流程引导只读取已发生的事实，不另存可被手工勾选的完成状态。
 * @author owlzhangfq@gmail.com
 */
public interface FirstWorkflowReadPort {
    /** 未指定定义时选择当前租户最近更新的一份，空租户返回无定义报告。 */
    Report read(String tenantId, UUID definitionId, Instant checkedAt);

    /**
     * 定义摘要不包含表单和流程正文；草稿版本为零，不能借用同标识旧版本的运行记录。
     * @author owlzhangfq@gmail.com
     */
    record Definition(UUID id, String key, String name, long version, String status) { }

    /**
     * 提交轮次证据，可通过既有申请详情查看轨迹与审计。
     * @author owlzhangfq@gmail.com
     */
    record Evidence(String applicationId, String businessNo, String title, int roundNo, String status,
                    Instant submittedAt, Instant completedAt) { }

    /**
     * 计数跨全部日期，但仅属于所选租户、流程和精确发布版本。
     * @author owlzhangfq@gmail.com
     */
    record Report(Instant checkedAt, Definition definition, long submittedRounds, long approvedRounds, long unrecordedHistoricalRounds,
                  Evidence latestSubmission, Evidence latestApproval) { }
}
