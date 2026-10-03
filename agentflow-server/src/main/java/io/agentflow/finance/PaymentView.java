package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * 两种受控工作区共用最小付款事实投影，不公开账户引用、摘要、财务目标或完整表单。
 * @author owlzhangfq@gmail.com
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentView(UUID id, long version, PaymentAuthorization.Status status, PaymentCommand.Purpose purpose,
                          UUID applicationId, UUID businessId, int roundNo, long applicationVersion, long businessVersion,
                          UUID legalEntityId, String employeeId, Money amount, String maskedPayeeAccount,
                          String authorizedBy, Instant authorizedAt, Instant expiresAt, String executedBy,
                          Request request, Operation operation, Retirement retirement) {
    /** 出纳和申请人均不会经共享状态对象得到原命令或原始资金回执。 */
    public static PaymentView of(PaymentAuthorization value, PaymentExecutionRequest request, PaymentOperation operation) {
        var terms = value.terms(); var binding = terms.binding(); var decision = value.decision();
        return new PaymentView(terms.id(), value.version(), value.status(), terms.purpose(), binding.applicationId(), binding.businessId(), binding.roundNo(),
                binding.applicationVersion(), binding.businessVersion(), terms.payee().legalEntityId(), terms.payee().employeeId(), terms.amount(), terms.payee().maskedAccount(),
                decision.authorizedBy(), decision.authorizedAt(), decision.expiresAt(), value.execution() == null ? null : value.execution().command().authorization().executedBy(),
                request == null ? null : new Request(request.input().id(), request.version(), request.status(), request.input().cashier(), request.createdAt(), request.updatedAt(), request.failure() == null ? null : request.failure().name()),
                operation == null ? null : operation(operation), value.retirement() == null ? null : new Retirement(value.retirement().retiredBy(),
                        value.retirement().retiredAt(), value.retirement().operationVersion(), value.retirement().basis()));
    }
    /** 查询提示按领域允许条件生成，不将排队或零发送次数误当作可查询的银行交易。 */
    public static boolean queryable(PaymentAuthorization authorization, PaymentOperation operation) {
        return authorization != null && authorization.status() == PaymentAuthorization.Status.EXECUTION_REGISTERED
                && operation != null && !operation.running() && operation.status() != PaymentOperation.Status.QUEUED && operation.dispatches() > 0;
    }
    private static Operation operation(PaymentOperation value) {
        var observation = value.observation();
        return new Operation(value.version(), value.status(), value.updatedAt(), observation == null ? null : observation.status(),
                observation == null ? null : observation.paymentReference(), observation == null ? null : observation.receiptReference(),
                observation == null ? null : observation.completedAt(), value.conflictingObservation() != null,
                value.failure() != null ? value.failure().name() : observation != null && observation.failure() != null ? observation.failure().name() : null);
    }
    /**
     * READY 仅代表已生成原付款队列，不代表银行到账。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Request(UUID id, long version, PaymentExecutionRequest.Status status, String cashier, Instant createdAt, Instant updatedAt, String issue) { }
    /**
     * 银行状态与已知回执分别显示；冲突状态保留先前事实以便追踪。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(long version, PaymentOperation.Status status, Instant updatedAt, PaymentObservation.Status observedStatus,
                            String paymentReference, String receiptReference, Instant completedAt, boolean disputed, String issue) { }

    /**
     * 出纳可核对结束依据与版本，但不会经最小付款投影取得财务自由文本说明。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(String retiredBy, Instant retiredAt, long operationVersion, PaymentOperation.RetirementBasis basis) { }
}
