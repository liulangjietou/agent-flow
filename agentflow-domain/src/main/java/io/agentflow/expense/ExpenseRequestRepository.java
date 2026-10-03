package io.agentflow.expense;

import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 事前申请批准额度与预留的持久端口。
 * @author owlzhangfq@gmail.com
 */
public interface ExpenseRequestRepository {
    /** 每份批准申请只能形成一个可核销额度聚合。 */
    void create(ExpenseRequest request, String actor);
    /** 保存余额或关闭状态并追加版本证据。 */
    void update(ExpenseRequest request, long expectedVersion, String actor, String operation);
    /** 与报销提交使用同一资源行锁，关闭后必须重新读取已提交的最新额度。 */
    void lock(String tenantId, UUID id);
    /** 仅查询当前租户的事前申请。 */
    Optional<ExpenseRequest> find(String tenantId, UUID id);
    /** 批量读取当前及前一轮引用，避免按发票逐条查询数据库。 */
    Map<UUID, ExpenseRequest> findAll(String tenantId, Collection<UUID> ids);
}
