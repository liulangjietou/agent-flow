package io.agentflow.expense;

import java.util.Optional;
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
    /** 仅查询当前租户的事前申请。 */
    Optional<ExpenseRequest> find(String tenantId, UUID id);
}
