package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 原 ERP 核销争议的具名财务决定，连续原修订保留冲突和采用的外部终态。
 * @author owlzhangfq@gmail.com
 */
public record SupplierSettlementDisputeResolution(UUID id, String tenantId, UUID settlementId, long disputedVersion, long resolvedVersion,
        SupplierPayableSettlementObservation observation, String resolvedBy, Instant resolvedAt, String evidenceReference, String reason) {
    /** 人工说明不能代替 ERP 近期终态，实际核销冲销属于独立调整。 */
    public SupplierSettlementDisputeResolution {
        if (id == null || invalidText(tenantId, 64) || settlementId == null || disputedVersion < 1 || resolvedVersion != Math.incrementExact(disputedVersion)
                || observation == null || !settlementId.equals(observation.operationId())
                || observation.status() != SupplierPayableSettlementObservation.Status.SETTLED && observation.status() != SupplierPayableSettlementObservation.Status.REJECTED
                || invalidText(resolvedBy, 128) || resolvedAt == null || resolvedAt.isBefore(observation.observedAt())
                || !resolvedAt.isBefore(observation.observedAt().plus(SupplierPayableSettlementOperation.DISPUTE_EVIDENCE_LIFETIME))
                || invalidText(evidenceReference, 128) || StringUtils.isBlank(reason) || reason.length() > 2000) throw invalid();
        reason = reason.trim();
    }

    /** 原申请人和原出纳不能自办；候选身份、业务版本及历史核销仍由实体判定。 */
    public SupplierPayableSettlementOperation resolve(SupplierPayableSettlementOperation before, SupplierPayableSettlementOperation.ResolutionHistory history) {
        var command = before.command(); var payment = command.payment();
        if (!tenantId.equals(command.tenantId()) || !settlementId.equals(command.id()) || before.version() != disputedVersion
                || !observation.equals(before.conflictingObservation()) || resolvedBy.equals(payment.cashier())
                || resolvedBy.equals(payment.holdCommand().authorization().source().reservation().source().employeeId())) throw invalid();
        return before.resolveDispute(observation.status(), history, resolvedAt);
    }

    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException invalid() {
        return new DomainException("INVALID_SUPPLIER_SETTLEMENT_DISPUTE_RESOLUTION", "Supplier settlement resolution requires original terminal evidence and an independent named finance reviewer");
    }
    /** 日志只显示决定及原核销编号，不展开对账原件或自由文本。 */
    @Override public String toString() { return "SupplierSettlementDisputeResolution[id=" + id + ", settlementId=" + settlementId + "]"; }
}
