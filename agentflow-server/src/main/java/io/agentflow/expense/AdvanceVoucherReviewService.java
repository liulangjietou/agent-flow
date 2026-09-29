package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.finance.JdbcPaymentAuthorizationRepository;
import io.agentflow.finance.JdbcVoucherOperationRepository;
import io.agentflow.finance.PaymentAuthorization;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.VoucherCommand;
import io.agentflow.finance.VoucherDisputeResolved;
import io.agentflow.finance.VoucherOperation;
import io.agentflow.finance.VoucherOperationChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原借款凭证变化与余额冻结同事务保存，资金和会计复核各自独立。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceVoucherReviewService {
    private static final String SYSTEM_ACTOR = "advance-voucher-review";
    private final AdvanceRequestRepository requests;
    private final EmployeeAdvanceRepository balances;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcVoucherOperationRepository vouchers;

    /** 原付款授权确定凭证归属，不以当前组织或任意同金额凭证替代历史依据。 */
    public AdvanceVoucherReviewService(AdvanceRequestRepository requests, EmployeeAdvanceRepository balances,
            JdbcPaymentAuthorizationRepository authorizations, JdbcVoucherOperationRepository vouchers) {
        this.requests = requests; this.balances = balances; this.authorizations = authorizations; this.vouchers = vouchers;
    }

    /** 普通查询可以追加冻结，不能解除任意原凭证、原付款或员工还款的冻结。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(VoucherOperationChanged event) {
        var voucher = event.current(); var command = voucher.input().command();
        if (!loanVoucher(command) || !requiresReview(voucher)) return;
        requests.lock(command.tenantId(), command.binding().businessId());
        var advance = balances.find(command.tenantId(), command.binding().businessId()).orElse(null);
        if (advance == null) return;
        authorizations.active(command.tenantId(), BusinessReference.Type.ADVANCE_REQUEST, advance.id())
                .filter(value -> original(command, advance, value.terms())).ifPresent(value -> freeze(advance, voucher));
    }

    /** 只有独立财务确认原有效过账才能解除该凭证的冻结，其余来源保持原状。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolved(VoucherDisputeResolved event) {
        var voucher = event.voucher(); var command = voucher.input().command();
        if (!loanVoucher(command) || !voucher.usablePosted()) return;
        requests.lock(command.tenantId(), command.binding().businessId());
        var advance = balances.find(command.tenantId(), command.binding().businessId()).orElse(null);
        if (advance == null || !advance.voucherReviews().contains(command.id())) return;
        authorizations.active(command.tenantId(), BusinessReference.Type.ADVANCE_REQUEST, advance.id())
                .filter(value -> original(command, advance, value.terms())).ifPresent(value -> {
                    long version = advance.version(); advance.resolveVoucherReview(version, command.id());
                    balances.update(advance, version, event.resolution().resolvedBy(), "VOUCHER_DISPUTE_RESOLVED");
                });
    }

    /** 放款已在途时原凭证可能先发生争议，新余额必须在同一事务继承尚未解除的冻结。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void captureCurrent(PaymentCommand payment) {
        var advance = balances.find(payment.tenantId(), payment.binding().businessId()).orElseThrow();
        var authorization = authorizations.find(payment.tenantId(), payment.id()).orElseThrow();
        for (var kind : new VoucherCommand.Kind[] {VoucherCommand.Kind.EMPLOYEE_ADVANCE, VoucherCommand.Kind.PAYMENT}) {
            vouchers.forRound(payment.tenantId(), payment.binding().applicationId(), payment.binding().roundNo(), kind)
                    .filter(value -> original(value.input().command(), advance, authorization.terms()))
                    .filter(value -> vouchers.requiresAdvanceReview(payment.tenantId(), value.input().command().id()))
                    .ifPresent(value -> freeze(advance, value));
        }
    }

    private void freeze(EmployeeAdvance advance, VoucherOperation voucher) {
        var id = voucher.input().command().id();
        if (advance.voucherReviews().contains(id)) return;
        long version = advance.version(); advance.requireVoucherReview(version, id);
        balances.update(advance, version, SYSTEM_ACTOR, "VOUCHER_REVIEW");
    }

    private static boolean original(VoucherCommand command, EmployeeAdvance advance, PaymentAuthorization.Terms terms) {
        return terms.purpose() == PaymentCommand.Purpose.EMPLOYEE_ADVANCE && terms.binding().businessId().equals(advance.id())
                && command.binding().businessId().equals(advance.id()) && command.tenantId().equals(advance.tenantId())
                && command.legalEntityId().equals(advance.legalEntityId()) && command.employeeId().equals(advance.employeeId())
                && command.totals().gross().equals(advance.balance().limit())
                && command.binding().applicationId().equals(terms.binding().applicationId()) && command.binding().roundNo() == terms.binding().roundNo()
                && (command.kind() == VoucherCommand.Kind.EMPLOYEE_ADVANCE
                    ? command.id().equals(terms.voucherOperationId()) && command.digest().equals(terms.voucherCommandDigest())
                    : command.payment().command().id().equals(terms.id()));
    }

    private static boolean loanVoucher(VoucherCommand command) {
        return command.kind() == VoucherCommand.Kind.EMPLOYEE_ADVANCE || command.kind() == VoucherCommand.Kind.PAYMENT
                && command.payment().command().purpose() == PaymentCommand.Purpose.EMPLOYEE_ADVANCE;
    }
    private static boolean requiresReview(VoucherOperation voucher) { return voucher.status() == VoucherOperation.Status.REVERSED || voucher.status() == VoucherOperation.Status.RECONCILING; }
}
