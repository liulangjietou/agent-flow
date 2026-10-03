package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * ERP 对原预留命令的版本化证据；预留不等于银行成功或应付已结算。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableHoldObservation(UUID authorizationId, String commandDigest, Status status, Long revision,
        Instant observedAt, String holdReference, String ledgerVersion, Money heldAmount, String accountDigest, Instant heldAt, Rejection rejection) {
    /** 已预留必须有准确的正额、账户及账本依据；其余状态不能携带预留成功资料。 */
    public SupplierPayableHoldObservation {
        if (authorizationId == null || !validDigest(commandDigest) || status == null || revision == null || observedAt == null
                || (status == Status.NOT_FOUND ? revision != 0 : revision < 1)) throw invalid();
        if (status == Status.HELD) {
            if (invalidReference(holdReference) || invalidReference(ledgerVersion) || heldAmount == null || heldAmount.value().signum() <= 0
                    || !validDigest(accountDigest) || heldAt == null || heldAt.isAfter(observedAt) || rejection != null) throw invalid();
        } else if (holdReference != null || ledgerVersion != null || heldAmount != null || accountDigest != null || heldAt != null
                || (status == Status.REJECTED) != (rejection != null)) throw invalid();
    }

    /** 查询可以在授权过期后找回窗口内的原预留，窗口外新形成的预留及错误金额不得采用。 */
    public boolean matches(SupplierPayableHoldCommand command, boolean queried, Instant now) {
        return authorizationId.equals(command.id()) && commandDigest.equals(command.digest()) && now != null && !observedAt.isAfter(now)
                && !observedAt.isBefore(command.authorization().authorizedAt()) && (status != Status.NOT_FOUND || queried)
                && (status != Status.HELD || heldAmount.equals(command.authorization().source().amount())
                    && accountDigest.equals(command.authorization().payable().account().accountDigest())
                    && !heldAt.isBefore(command.authorization().authorizedAt()) && heldAt.isBefore(command.sendDeadline()));
    }

    private static boolean validDigest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean invalidReference(String value) {
        return StringUtils.isBlank(value) || value.length() > 128 || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYABLE_HOLD_OBSERVATION", "Original payable hold evidence requires a consistent outcome for the exact command"); }

    /** 不在日志展开预留号或账户摘要。 */
    @Override public String toString() { return "SupplierPayableHoldObservation[authorizationId=" + authorizationId + ", status=" + status + ", revision=" + revision + "]"; }

    /**
     * HELD 在外部持续占用，必须通过另行绑定原交易的结算或安全释放命令结束，不能自动到期。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { PENDING, HELD, REJECTED, NOT_FOUND }

    /**
     * 仅表示 ERP 已确认本命令没有形成预留；超时、未知和格式错误不能当作拒绝。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { PAYABLE_VERSION_CONFLICT, PAYABLE_INSUFFICIENT, PAYABLE_UNAVAILABLE, SUPPLIER_UNAVAILABLE,
        ACCOUNT_CHANGED, MATCHING_CHANGED, AUTHORIZATION_EXPIRED, APPROVAL_CHANGED }
}
