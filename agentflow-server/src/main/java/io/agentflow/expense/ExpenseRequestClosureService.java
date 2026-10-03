package io.agentflow.expense;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本人停止使用原批准额度的用例；关闭规则归聚合，身份、并发与审计由本层编排。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseRequestClosureService {
    private static final String CLOSE_OPERATION = "CLOSE";
    private final ExpenseRequestRepository requests;
    private final ExpenseRequestClosureAudit audit;
    private final CurrentActor actors;

    /** 复用原资金仓储和认证身份，不由请求指定额度所有者。 */
    public ExpenseRequestClosureService(ExpenseRequestRepository requests, ExpenseRequestClosureAudit audit, CurrentActor actors) {
        this.requests = requests; this.audit = audit; this.actors = actors;
    }

    /** 锁后重读保持与报销占用串行；已有预留和核销不因关闭减少或释放。 */
    @Transactional
    public Receipt close(UUID id, long expectedVersion, String comment) {
        var actor = actors.actor();
        owned(actor, id);
        requests.lock(actor.tenantId(), id);
        var request = owned(actor, id);
        request.close(expectedVersion);
        requests.update(request, expectedVersion, actor.userId(), CLOSE_OPERATION);
        UUID event = audit.record(request, actor.userId(), expectedVersion, comment, Instant.now());
        return new Receipt(request.id(), request.applicationId(), request.version(), request.closed(), event);
    }

    private ExpenseRequest owned(Actor actor, UUID id) {
        return requests.find(actor.tenantId(), id).filter(value -> value.employeeId().equals(actor.userId()))
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Owned prior request credit not found"));
    }

    /**
     * 原请求恢复仅返回已保存的关闭事实，不包含余额、原因或其他报销的占用。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID requestId, UUID applicationId, long version, boolean closed, UUID eventId) { }
}
