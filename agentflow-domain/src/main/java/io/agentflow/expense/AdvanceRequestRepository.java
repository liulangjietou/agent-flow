package io.agentflow.expense;

import java.util.Optional;
import java.util.UUID;

/**
 * 借款申请持久端口，审批事务与借款申请修订共享同一申请优先的锁顺序。
 * @author owlzhangfq@gmail.com
 */
public interface AdvanceRequestRepository {
    /** 先锁申请再锁借款申请，防止补正、预检和批准依据相互覆盖。 */
    void lock(String tenantId, UUID id);
    /** 只创建已有独立审批绑定的草稿。 */
    void create(AdvanceRequest request, String actor);
    /** 每次更新追加不可变借款申请版本证据。 */
    void update(AdvanceRequest request, long expectedVersion, String actor, String operation);
    /** 租户内读取完整借款申请历史。 */
    Optional<AdvanceRequest> find(String tenantId, UUID id);
}
