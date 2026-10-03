package io.agentflow.onboarding;

import java.util.Optional;

/**
 * 每租户至多一条初始化事实，保存与统一审计属于同一事务。
 * @author owlzhangfq@gmail.com
 */
public interface TenantInitializationRepository {
    /** 读取原始快照，不用当前组织与日历补写历史。 */
    Optional<TenantInitialization> find(String tenantId);
    /** 在已持有组织目录锁的事务中追加初始化与审计。 */
    void append(TenantInitialization value);
}
