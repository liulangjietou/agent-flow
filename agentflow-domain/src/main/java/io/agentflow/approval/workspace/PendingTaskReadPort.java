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
    /** 返回当前授权筛选下的一页及完整总数，总数不受游标位置影响。 */
    Result read(Actor actor, Query query);

    /**
     * 同一查询快照的一页（最多 limit + 1 条，供入口判断后续页）及完整匹配总数。
     * @author owlzhangfq@gmail.com
     */
    record Result(List<Item> items, long total) { }

    /**
     * 任务与申请摘要；金额使用十进制文本，组织名称仅取本轮快照，不补造缺失历史。
     * @author owlzhangfq@gmail.com
     */
    record Item(String taskId, String taskName, String applicationId, String businessNo, String title,
                String processKey, long definitionVersion, String applicant, String amount, int roundNo,
                String assignee, String owner, String delegationState, Instant createdAt, Instant dueAt,
                String legalEntityName, String departmentName, String positionName) { }

    /**
     * 仅筛选引擎记录的期限事实；到达截止时刻即超时，缺失期限不推定为未到期。
     * @author owlzhangfq@gmail.com
     */
    enum DeadlineFilter { ALL, OVERDUE, PENDING, UNRECORDED }

    /**
     * 已通过入口校验的查询条件；按创建时间和任务标识升序，先处理较早任务。
     * @author owlzhangfq@gmail.com
     */
    record Query(String text, String processKey, String applicant, String organization, String assignment, DeadlineFilter deadline,
                 Instant deadlineAt, BigDecimal minAmount, BigDecimal maxAmount, int limit, Instant afterTime, String afterId) { }
}
