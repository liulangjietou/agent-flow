package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.UUID;

/**
 * 执行、到账结算和会计凭证共用持久账户授权证据，不能将今天的账户读数替换历史授权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentPayeeEvidence {
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentPayeeReviewRepository reviews;
    /** 多次重新授权沿已消费复核回溯到原批准账户，不依赖证据今天是否过期。 */
    public PaymentPayeeEvidence(JdbcPaymentAuthorizationRepository authorizations, JdbcPaymentPayeeReviewRepository reviews) {
        this.authorizations = authorizations; this.reviews = reviews;
    }

    /** 换账户链上的每次人工授权必须保存完整复核，最终来源仍是原批准账户。 */
    public void requireAuthorizedAccount(PaymentAuthorization authorization, EmployeeAccountSnapshot approvedAccount) {
        var current = authorization; var visited = new HashSet<UUID>();
        while (visited.add(current.terms().id())) {
            var review = reviews.forAuthorization(current.terms().tenantId(), current.terms().id()).orElse(null);
            if (review == null) {
                if (!current.terms().payee().equals(approvedAccount)) throw changed();
                return;
            }
            if (!review.supports(current)) throw changed();
            var original = authorizations.find(current.terms().tenantId(), review.input().original().id()).orElseThrow(PaymentPayeeEvidence::changed);
            if (!PaymentPayeeReview.ended(original) || original.version() != review.input().authorizationVersion()
                    || !original.terms().equals(review.input().original()) || original.updatedAt().isAfter(review.input().requestedAt())) throw changed();
            current = original;
        }
        throw changed();
    }

    /** 结算只能采用原执行命令已获授权的账户，跨聚合证据核对后再交给业务实体入账。 */
    public EmployeeAccountSnapshot paymentAccount(PaymentOperation payment, EmployeeAccountSnapshot approvedAccount) {
        var command = payment.input().command();
        var authorization = authorizations.find(command.tenantId(), command.id()).orElseThrow(PaymentPayeeEvidence::changed);
        if (authorization.execution() == null || !authorization.execution().command().equals(command)
                || !authorization.terms().targetDigest().equals(payment.input().targetDigest())
                || !authorization.execution().debitAccount().equals(payment.input().debitAccount())) throw changed();
        requireAuthorizedAccount(authorization, approvedAccount); return authorization.terms().payee();
    }
    private static DomainException changed() { return new DomainException("PAYMENT_PAYEE_EVIDENCE_CHANGED", "Payment account must have intact authorization evidence from the original approved account"); }
}
