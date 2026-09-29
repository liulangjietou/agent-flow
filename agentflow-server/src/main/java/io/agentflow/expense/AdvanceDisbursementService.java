package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcPaymentOperationRepository;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.PaymentOperation;
import io.agentflow.finance.PaymentOperationChanged;
import io.agentflow.finance.PaymentPayeeEvidence;
import io.agentflow.finance.PaymentDisputeResolved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 成功回执与实际借款余额同事务建立，重复对账不会重新生成资金或清空已用余额。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceDisbursementService {
    private static final String SYSTEM_ACTOR = "payment-settlement";
    private final AdvanceRequestRepository requests;
    private final EmployeeAdvanceRepository balances;
    private final JdbcPaymentOperationRepository payments;
    private final PaymentPayeeEvidence payeeEvidence;
    private final JdbcDisbursementResolutionRepository returns;

    /** 本地结算只读取持久原付款，不调用外部资金或当前账户目录。 */
    public AdvanceDisbursementService(AdvanceRequestRepository requests, EmployeeAdvanceRepository balances, JdbcPaymentOperationRepository payments, PaymentPayeeEvidence payeeEvidence, JdbcDisbursementResolutionRepository returns) {
        this.requests = requests; this.balances = balances; this.payments = payments; this.payeeEvidence = payeeEvidence; this.returns = returns;
    }

    /** 同步消费资金状态，任何余额或审计失败均回滚这次资金确认。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(PaymentOperationChanged event) {
        var payment = event.current();
        if (payment.input().command().purpose() == PaymentCommand.Purpose.EMPLOYEE_ADVANCE) apply(payment);
    }

    /** 明确成功裁决才解除原放款冻结，普通查询及退回裁决均不能清除余额复核标记。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolved(PaymentDisputeResolved event) {
        var payment = event.payment(); var command = payment.input().command();
        if (!payment.settleable() || command.purpose() != PaymentCommand.Purpose.EMPLOYEE_ADVANCE) return;
        var existing = balances.find(command.tenantId(), command.binding().businessId()).orElse(null);
        if (existing == null || !existing.paymentReviewRequired() || !existing.disbursementReturns().isEmpty()) return;
        var request = requests.find(command.tenantId(), existing.id()).orElseThrow(AdvanceDisbursementService::mismatch);
        var expected = request.paidAdvance(payment, payeeEvidence.paymentAccount(payment, request.currentRound().account()));
        long version = existing.version(); existing.resolvePaymentReview(version, expected);
        balances.update(existing, version, event.resolution().resolvedBy(), "PAYMENT_DISPUTE_RESOLVED");
    }

    /** 恢复旧版本已经确认到账但尚未生成余额的交易；锁后重新读取原资金状态。 */
    @Transactional
    public void recover(String tenant, UUID paymentId) {
        var initial = payments.find(tenant, paymentId).orElseThrow(AdvanceDisbursementService::mismatch);
        if (initial.input().command().purpose() != PaymentCommand.Purpose.EMPLOYEE_ADVANCE) return;
        requests.lock(tenant, initial.input().command().binding().businessId());
        apply(payments.find(tenant, paymentId).orElseThrow(AdvanceDisbursementService::mismatch));
    }

    private void apply(PaymentOperation payment) {
        var command = payment.input().command(); var tenant = command.tenantId(); var id = command.binding().businessId();
        if (!payment.settleable() && payment.status() != PaymentOperation.Status.RECONCILING && payment.status() != PaymentOperation.Status.REVERSED) return;
        var existing = balances.find(tenant, id).orElse(null);
        if (payment.settleable()) {
            var request = requests.find(tenant, id).orElseThrow(AdvanceDisbursementService::mismatch);
            var expected = request.paidAdvance(payment, payeeEvidence.paymentAccount(payment, request.currentRound().account()));
            if (existing == null) balances.create(expected, SYSTEM_ACTOR);
            else if (!existing.sameDisbursement(expected)) throw mismatch();
        } else if (existing != null && !existing.paymentReviewRequired() && !acceptedReturn(payment, existing)) {
            long version = existing.version(); existing.requirePaymentReview(version);
            balances.update(existing, version, SYSTEM_ACTOR, "PAYMENT_REVIEW");
        }
    }

    private boolean acceptedReturn(PaymentOperation payment, EmployeeAdvance existing) {
        if (payment.status() != PaymentOperation.Status.REVERSED || existing.disbursementReturns().isEmpty()) return false;
        return returns.latest(existing.tenantId(), existing.id()).filter(decision -> decision.receipt().samePaymentFacts(payment.observation())
                && payment.observation().revision() >= decision.receipt().current().revision()).isPresent();
    }

    private static DomainException mismatch() { return new DomainException("ADVANCE_PAYMENT_MISMATCH", "Original successful advance payment and existing disbursement must match"); }
}
