package io.agentflow.finance;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequestRepository;
import io.agentflow.expense.ExpensePrecheckJob;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.JdbcExpensePrecheckRepository;
import io.agentflow.expense.JdbcExpenseSubmissionControlRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 付款凭证只使用已保存的原授权、成功修订与冻结时区；跨聚合核验不依赖当前审批结论。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentVoucherSources {
    private final ApplicationRepository applications;
    private final JdbcPaymentOperationRepository payments;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcVoucherOperationRepository vouchers;
    private final AdvanceRequestRepository advances;
    private final ExpenseReportRepository expenses;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final JdbcExpensePrecheckRepository prechecks;
    private final PaymentPayeeEvidence payeeEvidence;

    /** 会计日期来自原业务冻结事实，不能查询今天的法人目录重新解释历史到账日。 */
    public PaymentVoucherSources(ApplicationRepository applications, JdbcPaymentOperationRepository payments,
            JdbcPaymentAuthorizationRepository authorizations, JdbcVoucherOperationRepository vouchers, AdvanceRequestRepository advances,
            ExpenseReportRepository expenses, JdbcExpenseSubmissionControlRepository controls, JdbcExpensePrecheckRepository prechecks, PaymentPayeeEvidence payeeEvidence) {
        this.applications = applications; this.payments = payments; this.authorizations = authorizations; this.vouchers = vouchers;
        this.advances = advances; this.expenses = expenses; this.controls = controls; this.prechecks = prechecks; this.payeeEvidence = payeeEvidence;
    }

    /** 只定位成功的持久修订，当前有效性留到准备和发送前重新读取。 */
    public VoucherPreparation.Source reference(PaymentOperation payment) {
        if (!payment.settleable()) throw unconfirmed();
        var command = payment.input().command(); var binding = command.binding();
        return new VoucherPreparation.Source(command.tenantId(), JdbcPaymentAuthorizationRepository.businessType(command.purpose()), binding.businessId(),
                binding.applicationId(), binding.roundNo(), binding.applicationVersion(), binding.businessVersion(), command.payee().employeeId(), command.id(), payment.version());
    }

    /** 原修订保持完整回单；当前退票、争议或尚未核实的查询状态阻止新的会计发送。 */
    public VoucherSource.Plan derive(VoucherPreparation.Source source) {
        var original = payments.revision(source.tenantId(), source.paymentOperationId(), source.paymentVersion()).orElseThrow(PaymentVoucherSources::changed);
        if (!original.settleable() || !reference(original).equals(source)) throw changed();
        var current = payments.find(source.tenantId(), source.paymentOperationId()).orElseThrow(PaymentVoucherSources::changed);
        if (!current.settleable() || !current.input().equals(original.input()) || !sameSettlement(original.observation(), current.observation())) throw unconfirmed();
        var command = original.input().command();
        var authorization = authorizations.find(source.tenantId(), command.id()).orElseThrow(PaymentVoucherSources::changed);
        if (authorization.execution() == null || !authorization.execution().command().equals(command)) throw changed();
        var voucher = vouchers.find(source.tenantId(), authorization.terms().voucherOperationId()).orElseThrow(PaymentVoucherSources::changed);
        if (!authorization.matchesVoucher(voucher, Instant.now())) {
            throw new DomainException("PAYMENT_VOUCHER_ACCRUAL_UNCONFIRMED", "Original accrual voucher must remain confirmed before payment accounting");
        }
        var application = applications.findById(source.tenantId(), source.applicationId()).orElseThrow(PaymentVoucherSources::changed);
        return VoucherSource.payment(application, command, original.observation(), legalTimeZone(source, authorization, voucher.input().command()));
    }

    private ZoneId legalTimeZone(VoucherPreparation.Source source, PaymentAuthorization authorization, VoucherCommand accrual) {
        var payment = authorization.execution().command();
        if (payment.purpose() == PaymentCommand.Purpose.EMPLOYEE_ADVANCE) {
            var request = advances.find(source.tenantId(), source.businessId()).orElseThrow(PaymentVoucherSources::changed);
            var round = request.rounds().stream().filter(value -> value.roundNo() == source.roundNo()).findFirst().orElseThrow(PaymentVoucherSources::changed);
            if (!request.applicationId().equals(source.applicationId()) || !request.employeeId().equals(source.employeeId())
                    || !round.content().amount().equals(payment.amount())
                    || !round.legalEntity().id().equals(payment.payee().legalEntityId())) throw changed();
            payeeEvidence.requireAuthorizedAccount(authorization, round.account());
            return ZoneId.of(round.legalEntity().timeZone());
        }
        var report = expenses.find(source.tenantId(), source.businessId()).orElseThrow(PaymentVoucherSources::changed);
        var round = report.rounds().stream().filter(value -> value.roundNo() == source.roundNo()).findFirst().orElseThrow(PaymentVoucherSources::changed);
        var control = controls.find(source.tenantId(), report.id(), source.roundNo()).orElseThrow(PaymentVoucherSources::changed);
        var precheck = prechecks.find(source.tenantId(), control.input().precheckId()).orElseThrow(PaymentVoucherSources::changed);
        var input = precheck.input();
        if (!report.applicationId().equals(source.applicationId()) || !report.employeeId().equals(source.employeeId())
                || !round.payable().equals(payment.amount())
                || !round.approvedGross().equals(accrual.totals().gross()) || !round.offsetTotal().equals(accrual.totals().offset())
                || precheck.status() != ExpensePrecheckJob.Status.READY || !input.reportId().equals(source.businessId())
                || !input.applicationId().equals(source.applicationId()) || input.roundNo() != source.roundNo()
                || input.financialVersion() != round.submittedFinancialVersion()
                || !precheck.result().evidence().legalEntity().id().equals(payment.payee().legalEntityId())
                || !precheck.result().evidence().preview().account().equals(round.account())) throw changed();
        payeeEvidence.requireAuthorizedAccount(authorization, round.account());
        return ZoneId.of(precheck.result().evidence().legalEntity().timeZone());
    }

    private static boolean sameSettlement(PaymentObservation original, PaymentObservation current) {
        return current.revision() >= original.revision() && !current.observedAt().isBefore(original.observedAt())
                && current.paymentReference().equals(original.paymentReference()) && current.receiptReference().equals(original.receiptReference())
                && current.paidAmount().equals(original.paidAmount()) && current.accountDigest().equals(original.accountDigest())
                && current.completedAt().equals(original.completedAt());
    }
    private static DomainException changed() { return new DomainException("PAYMENT_VOUCHER_SOURCE_CHANGED", "Payment voucher must match its persisted original financial evidence"); }
    private static DomainException unconfirmed() { return new DomainException("PAYMENT_VOUCHER_PAYMENT_UNCONFIRMED", "Original payment must have a current undisputed successful receipt"); }
}
