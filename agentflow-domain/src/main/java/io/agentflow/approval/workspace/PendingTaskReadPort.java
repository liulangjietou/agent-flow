package io.agentflow.approval.workspace;

import io.agentflow.common.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 当前可办理任务的只读投影端口，不复制引擎任务状态。
 * @author owlzhangfq@gmail.com
 */
public interface PendingTaskReadPort {
    /** 在存储侧同时限定租户、处理人、筛选与分页位置。 */
    List<Item> list(Actor actor, Query query);
    /** 当前筛选匹配总量，不受翻页位置影响。 */
    long count(Actor actor, Query query);

    /**
     * 任务与申请摘要；金额使用十进制文本，避免前端浮点精度损失。
     * @author owlzhangfq@gmail.com
     */
    record Item(String taskId, String taskName, String applicationId, String businessNo, String title,
                String processKey, long definitionVersion, String applicant, String amount, int roundNo,
                String assignee, String owner, String delegationState, Instant createdAt) { }

    /**
     * 已通过入口校验的查询条件；按创建时间和任务标识升序，先处理较早任务。
     * @author owlzhangfq@gmail.com
     */
    record Query(String text, String processKey, String applicant, String assignment, BigDecimal minAmount,
                 BigDecimal maxAmount, int limit, Instant afterTime, String afterId) { }
}
