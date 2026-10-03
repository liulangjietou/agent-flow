package io.agentflow.expense;

import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 实际借款放款、预留和核销的持久端口。
 * @author owlzhangfq@gmail.com
 */
public interface EmployeeAdvanceRepository {
    /** 已验证的外部放款只建立一笔借款。 */
    void create(EmployeeAdvance advance, String actor);
    /** 余额、轮次归属和财务版本记录共同提交。 */
    void update(EmployeeAdvance advance, long expectedVersion, String actor, String operation);
    /** 仅查询当前租户的借款。 */
    Optional<EmployeeAdvance> find(String tenantId, UUID id);
    /** 批量读取当前及前一轮引用，避免按发票逐条查询数据库。 */
    Map<UUID, EmployeeAdvance> findAll(String tenantId, Collection<UUID> ids);

    /** 按实际放款日及 UUID 文本升序遍历本人同法人、同币种借款，跨页保持稳定顺序。 */
    java.util.List<EmployeeAdvance> findOwnedByPaidOn(String tenantId, String employeeId, UUID legalEntityId,
            String currency, PaidCursor after, int limit);

    /**
     * 仓储内部游标只由上一页最后一笔借款生成，不接受客户端提供的身份或余额。
     * @author owlzhangfq@gmail.com
     */
    record PaidCursor(java.time.LocalDate paidOn, UUID id) { }
}
