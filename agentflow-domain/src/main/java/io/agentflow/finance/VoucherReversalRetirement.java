package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 原冲销安全结束事实绑定停止前后修订与解除停用时的原件观察，历史命令不删除。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalRetirement(UUID id, String tenantId, UUID operationId, UUID reversalId,
        long originalVersion, long releasedVersion, long reversalVersion, long stoppedVersion,
        VoucherReversalOperation.RetirementBasis basis, VoucherObservation original,
        String retiredBy, String evidenceReference, String comment, Instant retiredAt) {
    /** 恢复历史记录时，原件必须是当时仍有效的新鲜过账事实。 */
    public VoucherReversalRetirement {
        if (id == null || StringUtils.isBlank(tenantId) || operationId == null || reversalId == null || operationId.equals(reversalId)
                || originalVersion < 1 || releasedVersion != originalVersion + 1 || reversalVersion < 1 || stoppedVersion < reversalVersion || stoppedVersion > reversalVersion + 1
                || basis == null || original == null || !operationId.equals(original.operationId()) || original.status() != VoucherObservation.Status.POSTED
                || StringUtils.isBlank(retiredBy) || retiredBy.length() > 128 || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128
                || evidenceReference.chars().anyMatch(Character::isISOControl) || StringUtils.isBlank(comment) || comment.length() > 2000 || retiredAt == null
                || original.observedAt().isAfter(retiredAt) || !retiredAt.isBefore(original.observedAt().plus(VoucherOperation.DISPUTE_EVIDENCE_LIFETIME))) throw invalid();
    }
    /** 结束资格来自原执行状态与随后复核的同一有效原件，当前财务任职由应用入口核对。 */
    public static VoucherReversalOperation.RetirementBasis requireSource(VoucherReversalOperation reversal, VoucherOperation original, String actor, Instant now) {
        var command = reversal.input().command(); var basis = reversal.retirementBasis();
        if (basis == null) throw new DomainException("VOUCHER_REVERSAL_RETIREMENT_UNSAFE", "Unknown, accepted, conflicting or posted reversal cannot be retired");
        if (now.isBefore(reversal.updatedAt()) || !command.source().command().equals(original.input().command()) || !command.id().equals(original.reversalId())
                || !VoucherDisputeResolution.independent(command.source().command(), actor)) throw invalid();
        var observation = original.observation();
        if (original.status() != VoucherOperation.Status.POSTED || observation == null || observation.observedAt().isBefore(reversal.updatedAt())
                || observation.revision() < command.verifiedOriginal().revision() || !command.source().matchesOriginal(observation)) {
            throw new DomainException("VOUCHER_REVERSAL_ORIGINAL_RECHECK_REQUIRED", "Original voucher must remain posted and be rechecked after the reversal stopped or failed");
        }
        original.releaseReversal(command.id(), now); return basis;
    }
    /** 创建完整结束依据，停止前后的精确版本分别引用，不能用新版本覆盖旧失败。 */
    public static VoucherReversalRetirement create(VoucherReversalOperation reversal, VoucherOperation original, String actor, String reference, String comment, Instant now) {
        var basis = requireSource(reversal, original, actor, now); var command = reversal.input().command();
        return new VoucherReversalRetirement(UUID.randomUUID(), command.source().command().tenantId(), original.input().command().id(), command.id(),
                original.version(), original.version() + 1, reversal.version(), reversal.stopForRetirement(now).version(), basis, original.observation(), actor, reference, comment, now);
    }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_RETIREMENT", "Reversal retirement must preserve its original identity, independent finance decision and fresh posted evidence"); }
}
