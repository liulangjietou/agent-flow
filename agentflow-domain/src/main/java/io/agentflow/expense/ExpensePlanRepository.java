package io.agentflow.expense;

import java.util.Optional;
import java.util.UUID;

/**
 * 计划持久端口，审批事务与计划修订共享同一申请优先的锁顺序。
 * @author owlzhangfq@gmail.com
 */
public interface ExpensePlanRepository {
    /** 先锁申请再锁计划，防止补正、预检和批准额度相互覆盖。 */
    void lock(String tenantId, UUID id);
    /** 只创建已有独立审批绑定的草稿。 */
    void create(ExpensePlan plan, String actor);
    /** 每次更新追加不可变计划版本证据。 */
    void update(ExpensePlan plan, long expectedVersion, String actor, String operation);
    /** 租户内读取完整计划历史。 */
    Optional<ExpensePlan> find(String tenantId, UUID id);
}
