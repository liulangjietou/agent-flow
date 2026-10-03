package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 明确采用原交易对账终态的人工凭据，关联裁决前后修订而不覆盖原资金事实。
 * @author owlzhangfq@gmail.com
 */
public record PaymentDisputeResolution(UUID id, String tenantId, UUID paymentId, long disputedVersion, long resolvedVersion,
                                       PaymentObservation observation, String resolvedBy, Instant resolvedAt, String evidenceReference, String reason) {
    /** 裁决结果只能是资金终态；说明和外部对账凭据不能代替真实银行回执。 */
    public PaymentDisputeResolution {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || paymentId == null || disputedVersion < 1
                || resolvedVersion != Math.incrementExact(disputedVersion) || observation == null || !paymentId.equals(observation.authorizationId())
                || observation.status() != PaymentObservation.Status.SUCCEEDED && observation.status() != PaymentObservation.Status.FAILED && observation.status() != PaymentObservation.Status.REVERSED
                || StringUtils.isBlank(resolvedBy) || resolvedBy.length() > 128 || resolvedAt == null || resolvedAt.isBefore(observation.observedAt())
                || !resolvedAt.isBefore(observation.observedAt().plus(PaymentOperation.DISPUTE_EVIDENCE_LIFETIME))
                || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128 || evidenceReference.chars().anyMatch(Character::isISOControl)
                || StringUtils.isBlank(reason) || reason.length() > 2000) throw invalid();
        evidenceReference = evidenceReference.trim(); reason = reason.trim();
    }

    /** 裁决人不能是本人或原出纳；结果必须对应同一命令的精确连续修订。 */
    public boolean matches(PaymentOperation before, PaymentOperation after) {
        var command = before.input().command();
        return before.status() == PaymentOperation.Status.RECONCILING && before.version() == disputedVersion && after.version() == resolvedVersion
                && tenantId.equals(command.tenantId()) && paymentId.equals(command.id()) && before.input().equals(after.input())
                && observation.equals(before.conflictingObservation()) && observation.equals(after.observation()) && after.conflictingObservation() == null
                && after.status().name().equals(observation.status().name()) && after.updatedAt().equals(resolvedAt)
                && !resolvedBy.equals(command.payee().employeeId()) && !resolvedBy.equals(command.authorization().executedBy());
    }

    /** 日志只定位裁决记录，不展开自由文本或回单。 */
    @Override public String toString() { return "PaymentDisputeResolution[id=" + id + ", paymentId=" + paymentId + ", resolvedVersion=" + resolvedVersion + "]"; }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_DISPUTE_RESOLUTION", "Payment dispute decision must retain recent original evidence and a named financial reviewer"); }
}
