package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 出纳只登记一次账户选择或跟踪原交易，HTTP 入口不等待资金系统，也不能创建财务授权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class CashierPaymentActions {
    private final CurrentActor actors;
    private final PaymentAccess access;
    private final ApprovedPaymentSources sources;
    private final PaymentExecutionRequestService requests;
    private final JdbcPaymentOperationRepository operations;
    private final PaymentOperationService execution;
    private final PaymentAudit audit;

    /** 原业务锁、执行登记和审计共同参加幂等事务。 */
    public CashierPaymentActions(CurrentActor actors, PaymentAccess access, ApprovedPaymentSources sources, PaymentExecutionRequestService requests,
                                 JdbcPaymentOperationRepository operations, PaymentOperationService execution, PaymentAudit audit) {
        this.actors = actors; this.access = access; this.sources = sources; this.requests = requests;
        this.operations = operations; this.execution = execution; this.audit = audit;
    }

    /** 幂等回放之前也复查岗位和职责分离；只有原出纳可以重发原交易。 */
    public PaymentAuthorization requireAccess(UUID id, Action action) {
        var authorization = action == Action.QUERY ? access.requireCashier(id) : access.requireExecution(id);
        if (action == Action.RESEND_ORIGINAL && (authorization.execution() == null
                || !authorization.execution().command().authorization().executedBy().equals(actors.actor().userId()))) {
            throw new DomainException("FORBIDDEN", "Only the original cashier may resend the original payment");
        }
        return authorization;
    }

    /** 账户引用和版本只记录为待复查意图，202 不表示账户有效或银行已付款。 */
    @Transactional
    public Receipt act(UUID id, Input input) {
        var initial = requireAccess(id, input.action()); sources.lock(initial);
        var authorization = requireAccess(id, input.action());
        if (authorization.version() != input.authorizationVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Displayed payment authorization changed");
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS); UUID requestId = null; Long operationVersion = null;
        String previous = null, current; long version; UUID aggregate;
        if (input.action() == Action.EXECUTE) {
            var request = requests.register(authorization, actors.actor().userId(), input.debitAccountReference(), input.debitAccountVersion(), now);
            requestId = request.input().id(); aggregate = requestId; version = request.version(); current = request.status().name();
        } else {
            var operation = operations.find(authorization.terms().tenantId(), id)
                    .orElseThrow(() -> new DomainException("NOT_FOUND", "Original payment operation not found"));
            previous = operation.status().name();
            var next = input.action() == Action.QUERY ? execution.query(authorization.terms().tenantId(), id, input.operationVersion(), now)
                    : execution.resend(authorization.terms().tenantId(), id, input.operationVersion(), now);
            aggregate = id; version = next.version(); operationVersion = version; current = next.status().name();
        }
        var event = audit.record(authorization, aggregate, version, PaymentAccess.CASHIER_ROLE, "PAYMENT_" + input.action(), previous, current, input.comment(), now);
        return new Receipt(id, authorization.version(), input.action(), requestId, operationVersion, event);
    }

    /**
     * 查询和明确查无后的原号重发均不接受新账户。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { EXECUTE, QUERY, RESEND_ORIGINAL }

    /**
     * 形状检查集中在入口，客户端不能夹带金额、收款人、授权人或新交易号。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull Action action, @Positive long authorizationVersion, @Positive Long operationVersion,
                        @Size(max = 128) String debitAccountReference, @Size(max = 128) String debitAccountVersion,
                        @NotBlank @Size(max = 2000) String comment) {
        /** 各动作必需字段严格互斥，不能默默忽略旧页面带来的账户选择。 */
        public Input {
            if (action != null && (action == Action.EXECUTE
                    ? operationVersion != null || StringUtils.isBlank(debitAccountReference) || StringUtils.isBlank(debitAccountVersion)
                    : operationVersion == null || debitAccountReference != null || debitAccountVersion != null)) {
                throw new IllegalArgumentException("Cashier action fields are inconsistent");
            }
        }
        /** 不接受契约外的资金事实。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown cashier payment action field"); }
    }

    /**
     * 回执只保存原操作标识和审计证据，账户与实时资金状态须重新授权查询。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(UUID authorizationId, long authorizationVersion, Action action, UUID requestId, Long operationVersion, UUID auditEventId) { }
}
