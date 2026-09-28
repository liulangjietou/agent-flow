package io.agentflow.expense;

import java.util.Optional;
import java.util.UUID;

/**
 * 报销聚合持久端口，每次写入与追加财务版本证据共同提交。
 * @author owlzhangfq@gmail.com
 */
public interface ExpenseReportRepository {
    /** 只创建初始草稿，业务绑定必须已经存在。 */
    void create(ExpenseReport report, String actor);
    /** 根据财务版本更新，审批申请版本由上层同一事务核对。 */
    void update(ExpenseReport report, long expectedVersion, String actor, String operation);
    /** 租户内按业务标识查询，不提供跨租户恢复入口。 */
    Optional<ExpenseReport> find(String tenantId, UUID id);
    /** 根据审批申请查询同租户的一对一报销绑定。 */
    Optional<ExpenseReport> findByApplication(String tenantId, UUID applicationId);
}
