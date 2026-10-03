package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 支付系统对原授权的版本化事实；已受理、已到账和已退回不能相互冒充。
 * @author owlzhangfq@gmail.com
 */
public record PaymentObservation(UUID authorizationId, String commandDigest, Status status, Long revision, Instant observedAt,
                                 String paymentReference, Money paidAmount, String accountDigest, Instant completedAt,
                                 String receiptReference, Failure failure) {
    /** 已到账和退回必须带完整金额及回单，其余状态不能夹带到账事实。 */
    public PaymentObservation {
        if (authorizationId == null || !validDigest(commandDigest) || status == null || revision == null || observedAt == null
                || (status == Status.NOT_FOUND ? revision != 0 || paymentReference != null : revision < 1 || invalidReference(paymentReference))) throw invalid();
        boolean completed = status == Status.SUCCEEDED || status == Status.REVERSED;
        if (completed) {
            if (paidAmount == null || paidAmount.value().signum() <= 0 || !validDigest(accountDigest) || completedAt == null
                    || completedAt.isAfter(observedAt) || invalidReference(receiptReference) || failure != null) throw invalid();
        } else if (paidAmount != null || accountDigest != null || completedAt != null || receiptReference != null
                || (status == Status.FAILED) != (failure != null)) throw invalid();
    }

    /** 每次读取复核原命令及实际付款；部分付款、错币种和错收款账户均进入不可用结果。 */
    public boolean matches(PaymentCommand command, boolean queried, Instant now) {
        return authorizationId.equals(command.id()) && commandDigest.equals(command.digest()) && !observedAt.isAfter(now)
                && !observedAt.isBefore(command.authorization().authorizedAt()) && (status != Status.NOT_FOUND || queried)
                && (status != Status.SUCCEEDED && status != Status.REVERSED || paidAmount.equals(command.amount())
                    && accountDigest.equals(command.payee().accountDigest()) && !completedAt.isBefore(command.authorization().authorizedAt()));
    }

    private static boolean validDigest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean invalidReference(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_OBSERVATION", "Payment evidence must identify the original authorization and a consistent outcome"); }

    /**
     * REVERSED 是新的对账事实，不允许据此静默删除已形成的借款或费用核销。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { PENDING, SUCCEEDED, FAILED, REVERSED, NOT_FOUND }

    /**
     * 确认没有支付的业务失败分类；网络超时属于未知结果，不能放入此枚举。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { ACCOUNT_UNAVAILABLE, DEBIT_ACCOUNT_UNAVAILABLE, INSUFFICIENT_FUNDS, AUTHORIZATION_EXPIRED,
        APPROVAL_CHANGED, ACCOUNT_CHANGED, VOUCHER_UNAVAILABLE, PAYMENT_REJECTED }
}
