package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.JdbcPaymentAuthorizationRepository;
import io.agentflow.finance.JdbcPaymentOperationRepository;
import io.agentflow.finance.PaymentAuthorization;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.PaymentOperation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 还款始终引用实际原放款，不能以当前批准状态或新的账户快照代替已经发生的资金事实。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRepaymentSources {
    private final AdvanceRequestRepository requests;
    private final EmployeeAdvanceRepository balances;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentOperationRepository payments;
    /** 实际余额、原授权及原资金修订共同确定收款查询身份。 */
    public AdvanceRepaymentSources(AdvanceRequestRepository requests, EmployeeAdvanceRepository balances, JdbcPaymentAuthorizationRepository authorizations, JdbcPaymentOperationRepository payments) {
        this.requests = requests; this.balances = balances; this.authorizations = authorizations; this.payments = payments;
    }
    /** 与原付款和借款操作使用相同的申请、借款锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Source locked(String tenant, UUID advanceId) { requests.lock(tenant, advanceId); return find(tenant, advanceId); }
    /** 仅加载已实际产生的借款，不通过请求虚构可还金额。 */
    public Source find(String tenant, UUID advanceId) {
        var advance = balances.find(tenant, advanceId).orElseThrow(AdvanceRepaymentSources::changed);
        var authorization = authorizations.active(tenant, BusinessReference.Type.ADVANCE_REQUEST, advanceId).orElseThrow(AdvanceRepaymentSources::changed);
        var payment = payments.find(tenant, authorization.terms().id()).orElseThrow(AdvanceRepaymentSources::changed);
        var command = payment.input().command();
        if (command.purpose() != PaymentCommand.Purpose.EMPLOYEE_ADVANCE || !command.binding().businessId().equals(advanceId)
                || !command.tenantId().equals(tenant) || !command.payee().employeeId().equals(advance.employeeId())
                || !command.payee().legalEntityId().equals(advance.legalEntityId()) || !command.amount().equals(advance.balance().limit())
                || payment.observation() == null || !advance.paymentReference().equals(payment.observation().paymentReference())) throw changed();
        return new Source(advance, authorization, payment);
    }
    /** 队列恢复核对原成功修订及目标，后续原交易查询可以增加当前版本。 */
    public void requireCheck(AdvanceRepaymentCheck check, Source current) {
        var input = check.input(); var proof = payments.revision(input.tenantId(), input.paymentId(), input.paymentVersion()).orElseThrow(AdvanceRepaymentSources::changed);
        if (!proof.settleable() || !proof.input().equals(current.payment().input()) || !input.paymentId().equals(current.authorization().terms().id())
                || !input.targetDigest().equals(current.authorization().terms().targetDigest()) || !input.request().equals(current.request(input.request().receiptReference()))) throw changed();
    }
    private static DomainException changed() { return new DomainException("ADVANCE_REPAYMENT_SOURCE_CHANGED", "The actual original advance disbursement is unavailable or inconsistent"); }
    /**
     * 原放款与当前余额是不同事实，查询不修改二者。
     * @author owlzhangfq@gmail.com
     */
    public record Source(EmployeeAdvance advance, PaymentAuthorization authorization, PaymentOperation payment) {
        /** 新还款需要当前无争议的原放款；已还款异常另行冻结。 */
        public void requireConfirmedPayment() { if (!payment.settleable() || advance.paymentReviewRequired()) throw changed(); }
        /** 收款凭据查询只接受编号，其余身份来自原实际借款。 */
        public AdvanceRepaymentPort.Request request(String reference) { return new AdvanceRepaymentPort.Request(advance.id(), advance.legalEntityId(), advance.employeeId(), advance.paymentReference(), advance.balance().limit().currency(), reference); }
    }
}
