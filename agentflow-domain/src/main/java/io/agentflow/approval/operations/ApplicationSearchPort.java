package io.agentflow.approval.operations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理员的租户申请摘要检索端口，不读取正文或改变审批状态。
 * @author owlzhangfq@gmail.com
 */
public interface ApplicationSearchPort {
    /** 返回最多 limit + 1 条摘要供入口生成下一页，不计算全库总数。 */
    List<Item> search(String tenantId, Query query);

    /**
     * 入口已校验的精确筛选和创建时间游标；日期上界为不包含。
     * @author owlzhangfq@gmail.com
     */
    record Query(String text, String status, String processKey, Long definitionVersion, String applicant,
                 Instant createdFrom, Instant createdBefore, int limit, Instant beforeTime, UUID beforeId) { }

    /**
     * 仅包含定位申请所需的摘要，正文由原申请详情接口独立授权。
     * @author owlzhangfq@gmail.com
     */
    record Item(UUID id, String businessNo, String title, String processKey, long definitionVersion,
                String createdBy, String status, int roundNo, Instant createdAt, Instant updatedAt) { }
}
