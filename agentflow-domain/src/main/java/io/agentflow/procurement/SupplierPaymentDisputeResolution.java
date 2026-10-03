package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentObservation;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 供应商原付款的独立财务裁决，原冲突与结果分别引用连续不可变修订。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentDisputeResolution(UUID id, String tenantId, UUID paymentId, long disputedVersion, long resolvedVersion,
        PaymentObservation observation, String resolvedBy, Instant resolvedAt, String evidenceReference, String reason) {
    /** 人工说明只能补充外部凭据，不能代替原银行的近期终态。 */
    public SupplierPaymentDisputeResolution {
        if (id == null || invalidText(tenantId, 64) || paymentId == null || disputedVersion < 1 || resolvedVersion != Math.incrementExact(disputedVersion)
                || observation == null || !paymentId.equals(observation.authorizationId())
                || observation.status() != PaymentObservation.Status.SUCCEEDED && observation.status() != PaymentObservation.Status.FAILED && observation.status() != PaymentObservation.Status.REVERSED
                || invalidText(resolvedBy, 128) || resolvedAt == null || resolvedAt.isBefore(observation.observedAt())
                || !resolvedAt.isBefore(observation.observedAt().plus(SupplierPaymentOperation.DISPUTE_EVIDENCE_LIFETIME))
                || invalidText(evidenceReference, 128) || StringUtils.isBlank(reason) || reason.length() > 2000) throw invalid();
        reason = reason.trim();
    }

    /** 原申请人及原出纳不能自办；原命令和历史资金边界由所属付款实体判定。 */
    public SupplierPaymentOperation resolve(SupplierPaymentOperation before, SupplierPaymentOperation.ResolutionHistory history) {
        var command = before.command(); var source = command.holdCommand().authorization().source().reservation().source();
        if (!tenantId.equals(command.tenantId()) || !paymentId.equals(command.id()) || before.version() != disputedVersion
                || !observation.equals(before.conflictingObservation()) || resolvedBy.equals(source.employeeId()) || resolvedBy.equals(command.cashier())) throw invalid();
        return before.resolveDispute(observation.status(), history, resolvedAt);
    }

    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException invalid() {
        return new DomainException("INVALID_SUPPLIER_PAYMENT_DISPUTE_RESOLUTION", "Supplier payment resolution requires original terminal evidence and an independent named finance reviewer");
    }
    /** 日志不展开对账凭据或自由文本。 */
    @Override public String toString() { return "SupplierPaymentDisputeResolution[id=" + id + ", paymentId=" + paymentId + "]"; }
}
