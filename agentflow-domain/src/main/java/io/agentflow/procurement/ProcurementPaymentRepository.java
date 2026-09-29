package io.agentflow.procurement;

import java.util.Optional;
import java.util.UUID;

/**
 * 采购申请的持久端口，财务和审批事务使用相同申请优先锁顺序。
 * @author owlzhangfq@gmail.com
 */
public interface ProcurementPaymentRepository {
    /** 先锁审批，再锁采购申请，跨聚合编排不得交换锁顺序。 */
    void lock(String tenantId, UUID id);
    /** 创建必须对应已有的独立采购审批绑定。 */
    void create(ProcurementPaymentRequest request, String actor);
    /** 每次变化追加原始修订，不覆盖旧轮次和批准。 */
    void update(ProcurementPaymentRequest request, long expectedVersion, String actor, String operation);
    /** 完整快照仅用于受控应用编排，公开响应另外投影。 */
    Optional<ProcurementPaymentRequest> find(String tenantId, UUID id);
}
