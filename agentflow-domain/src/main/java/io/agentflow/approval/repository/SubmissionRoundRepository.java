package io.agentflow.approval.repository;

import io.agentflow.approval.model.SubmissionRound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 提交轮次仓储；只追加内容快照，已结束轮次不允许再次改写结论。
 * @author owlzhangfq@gmail.com
 */
public interface SubmissionRoundRepository {
    /** 追加本次提交快照，重复轮次必须失败，不能覆盖历史内容。 */
    void append(SubmissionRound round);

    /** 按提交顺序返回指定租户申请的轮次。 */
    List<SubmissionRound> findAll(String tenantId, UUID applicationId);

    /** 精确读取当前轮次，用于校验申请与流程实例的绑定。 */
    Optional<SubmissionRound> findByRound(String tenantId, UUID applicationId, int roundNo);

    /** 只更新当前实例对应的未结束轮次；兼容没有历史快照的旧申请，不补造旧数据。 */
    void complete(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                  SubmissionRound.Status status, String reason, String completedBy, Instant completedAt);
}
