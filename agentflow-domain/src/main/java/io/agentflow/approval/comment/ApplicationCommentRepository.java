package io.agentflow.approval.comment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 评论存储独立于审批聚合的版本和引擎任务，没有更新或删除评论的入口。
 * @author owlzhangfq@gmail.com
 */
public interface ApplicationCommentRepository {
    /** 在已授权的单申请范围内按时间和标识倒序读取，额外一条用于判断后续页。 */
    List<ApplicationComment> list(String tenantId, UUID applicationId, Query query);

    /** 仅在申请上下文仍相同时追加，不能更新原评论或申请版本。 */
    void append(String tenantId, ApplicationComment comment);

    /**
     * 已在接口边界校验的轮次筛选和游标位置。
     * @author owlzhangfq@gmail.com
     */
    record Query(Integer roundNo, int limit, Instant beforeTime, UUID beforeId) { }
}
