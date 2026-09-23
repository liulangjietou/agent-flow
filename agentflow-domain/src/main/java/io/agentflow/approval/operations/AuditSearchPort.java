package io.agentflow.approval.operations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 当前租户的操作审计摘要查询，不重建或改变审批结论。
 * @author owlzhangfq@gmail.com
 */
public interface AuditSearchPort {
    /** 返回最多 limit + 1 条追加审计事实，供入口判断下一页。 */
    List<Item> search(String tenantId, Query query);

    /**
     * 入口已校验的条件，日期上界不包含，时间与记录标识共同定位。
     * @author owlzhangfq@gmail.com
     */
    record Query(String text, String actor, String action, String source, UUID applicationId,
                 Instant occurredFrom, Instant occurredBefore, int limit, Instant beforeTime, UUID beforeId) { }

    /**
     * 原始事件元数据与可关联申请的当前摘要；不返回表单正文或审批意见。
     * @author owlzhangfq@gmail.com
     */
    record Item(UUID id, String eventId, String source, String aggregateId, long aggregateVersion,
                String action, String actor, Instant occurredAt, UUID applicationId, String businessNo,
                String currentTitle) { }
}
