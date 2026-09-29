package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 财务只决定短期付款授权、未执行作废和原交易复查，不能替出纳选择出款账户或执行付款。
 * @author owlzhangfq@gmail.com
 */
@Service
public class FinancePaymentActions {
    public static final int MIN_VALIDITY_SECONDS = 60;
    public static final int MAX_VALIDITY_SECONDS = 86_400;
    private final CurrentActor actors;
    private final PaymentAccess access;
    private final ApprovedVoucherSources voucherSources;
    private final ApprovedPaymentSources paymentSources;
    private final JdbcVoucherOperationRepository vouchers;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentOperationRepository operations;
    private final PaymentOperationService execution;
    private final PaymentAudit audit;
    /** 真实来源及审计在同一事务中处理，本服务没有外部 HTTP 依赖。 */
    public FinancePaymentActions(CurrentActor actors, PaymentAccess access, ApprovedVoucherSources voucherSources, ApprovedPaymentSources paymentSources,
                                  JdbcVoucherOperationRepository vouchers, JdbcPaymentAuthorizationRepository authorizations,
                                  JdbcPaymentOperationRepository operations, PaymentOperationService execution, PaymentAudit audit) {
        this.actors = actors; this.access = access; this.voucherSources = voucherSources; this.paymentSources = paymentSources;
        this.vouchers = vouchers; this.authorizations = authorizations; this.operations = operations; this.execution = execution; this.audit = audit;
    }
    /** 锁后从实际批准和挂账派生金额与账户，客户端只携带展示版本、期限和理由。 */
    @Transactional
    public Receipt authorize(UUID applicationId, Authorize input) {
        var initial = access.requireFinance(applicationId, input.roundNo()); voucherSources.lock(initial.source());
        var context = access.requireFinance(applicationId, input.roundNo()); var application = context.application(); var now = now();
        if (application.version() != input.applicationVersion() || context.businessVersion() != input.businessVersion()) throw conflict();
        if (application.status() != ApplicationStatus.APPROVED || application.roundNo() != input.roundNo()) throw changedSource();
        var voucher = vouchers.forRound(application.tenantId(), applicationId, input.roundNo(), context.kind()).orElseThrow(FinancePaymentActions::changedSource);
        if (!voucher.input().command().id().equals(input.voucherOperationId()) || voucher.version() != input.voucherVersion()) throw conflict();
        var payee = paymentSources.payee(voucher, now);
        var current = authorizations.active(application.tenantId(), application.businessReference().type(), application.businessReference().id()).orElse(null);
        if (current != null) {
            if (current.status() == PaymentAuthorization.Status.AUTHORIZED && !now.isBefore(current.decision().expiresAt())) authorizations.update(current.expire(now));
            else throw new DomainException("PAYMENT_AUTHORIZATION_EXISTS", "The financial business already has an active payment authorization or execution");
        }
        var authorization = PaymentAuthorization.issue(UUID.randomUUID(), voucher, payee, actors.actor().userId(), now, now.plusSeconds(input.validitySeconds()));
        authorizations.create(authorization);
        var event = audit.record(authorization, authorization.terms().id(), authorization.version(), "FINANCE", "PAYMENT_AUTHORIZE", null, authorization.status().name(), input.comment(), now);
        return receipt(authorization, "AUTHORIZE", null, event);
    }
    /** 原轮次权限每次重读；查询不要求原批准仍有效，作废不能覆盖已经登记的出纳执行。 */
    @Transactional
    public Receipt act(UUID authorizationId, Input input) {
        var initial = access.requireFinanceAuthorization(authorizationId); paymentSources.lock(initial);
        var authorization = access.requireFinanceAuthorization(authorizationId); if (authorization.version() != input.authorizationVersion()) throw conflict();
        var now = now(); String before, after; long version; Long operationVersion = null;
        if (input.action() == Action.VOID) {
            before = authorization.status().name(); authorization = authorization.voidBeforeExecution(actors.actor().userId(), input.comment(), now);
            authorizations.update(authorization); after = authorization.status().name(); version = authorization.version();
        } else {
            var operation = operations.find(authorization.terms().tenantId(), authorizationId).orElseThrow(FinancePaymentActions::notFound);
            before = operation.status().name(); var queried = execution.query(authorization.terms().tenantId(), authorizationId, input.operationVersion(), now);
            after = queried.status().name(); version = queried.version(); operationVersion = queried.version();
        }
        var event = audit.record(authorization, authorizationId, version, "FINANCE", "PAYMENT_" + input.action().name(), before, after, input.comment(), now);
        return receipt(authorization, input.action().name(), operationVersion, event);
    }
    private static Receipt receipt(PaymentAuthorization value, String action, Long operationVersion, UUID event) {
        var binding = value.terms().binding();
        return new Receipt(binding.applicationId(), binding.businessId(), binding.roundNo(), value.terms().id(), value.version(), action, operationVersion, value.decision().expiresAt(), event);
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed payment, voucher or financial version changed"); }
    private static DomainException changedSource() { return new DomainException("PAYMENT_SOURCE_CHANGED", "Current approved financial source and posted voucher are required"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original payment operation not found"); }
    /**
     * 财务动作不含出纳执行和换号重付。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { VOID, QUERY }
    /**
     * 客户端不提交金额、本人账户、财务目标或授权主体。
     * @author owlzhangfq@gmail.com
     */
    public record Authorize(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion,
                            @NotNull UUID voucherOperationId, @Positive long voucherVersion,
                            @Min(MIN_VALIDITY_SECONDS) @Max(MAX_VALIDITY_SECONDS) int validitySeconds, @NotBlank @Size(max = 2000) String comment) {
        /** 不静默忽略客户端夹带的财务事实。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown payment authorization field"); }
    }
    /**
     * 两类动作只接受相应版本，查询引用原付款操作。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull Action action, @Positive long authorizationVersion, @Positive Long operationVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 无意义的操作版本不能被忽略，以免页面恢复后误操作。 */
        public Input { if (action != null && (action == Action.VOID ? operationVersion != null : operationVersion == null)) throw new IllegalArgumentException("Payment action versions are inconsistent"); }
        /** 拒绝动作契约之外的字段。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown finance payment action field"); }
    }
    /**
     * 幂等回执仅定位原授权，不包含金额或账号；实时状态重新查询受控投影。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(UUID applicationId, UUID businessId, int roundNo, UUID authorizationId, long authorizationVersion, String action,
                          Long operationVersion, Instant expiresAt, UUID auditEventId) { }
}
