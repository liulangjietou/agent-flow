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
}
