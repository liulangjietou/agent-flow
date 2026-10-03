package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequestRepository;
import io.agentflow.expense.ExpenseReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * 付款只能引用实际批准的当前财务聚合、原轮次账户及可用挂账凭证；命令中的声明不是批准依据。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApprovedPaymentSources {
    private final ApprovedVoucherSources approved;
    private final JdbcVoucherOperationRepository vouchers;
    private final AdvanceRequestRepository advances;
    private final ExpenseReportRepository expenses;
    private final PaymentPayeeEvidence payeeEvidence;
    /** 复用凭证已经具备的批准、纸件和预算规则，只在付款层增加原收款账户关联。 */
    public ApprovedPaymentSources(ApprovedVoucherSources approved, JdbcVoucherOperationRepository vouchers, AdvanceRequestRepository advances, ExpenseReportRepository expenses,
                                  PaymentPayeeEvidence payeeEvidence) {
        this.approved = approved; this.vouchers = vouchers; this.advances = advances; this.expenses = expenses; this.payeeEvidence = payeeEvidence;
    }
    /** 始终按申请、财务聚合的既有顺序锁定，旧轮次也能锁住并继续资金查询。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(PaymentAuthorization authorization) {
        var terms = authorization.terms(); var binding = terms.binding();
        approved.lock(new VoucherPreparation.Source(terms.tenantId(), JdbcPaymentAuthorizationRepository.businessType(terms.purpose()), binding.businessId(), binding.applicationId(),
                binding.roundNo(), binding.applicationVersion(), binding.businessVersion(), terms.payee().employeeId()));
    }
    /** 授权前从实际业务轮次取得原账户，所有金额和分摊再次与已保存凭证核对。 */
    public EmployeeAccountSnapshot payee(VoucherOperation voucher, Instant now) {
        if (voucher == null || !voucher.usablePosted() || voucher.updatedAt().isAfter(now)) throw changed();
        var command = voucher.input().command();
        if (!approved.derive(approved.reference(command)).matches(command)) throw changed();
        return command.kind() == VoucherCommand.Kind.EMPLOYEE_ADVANCE
                ? advances.find(command.tenantId(), command.binding().businessId()).orElseThrow(ApprovedPaymentSources::changed).currentRound().account()
                : expenses.find(command.tenantId(), command.binding().businessId()).orElseThrow(ApprovedPaymentSources::changed).currentRound().account();
    }
    /** 新执行及显式重发必须重新读取全部来源；资金查询不调用此方法以免丢失撤销后的原交易。 */
    public VoucherOperation requireCurrent(PaymentAuthorization authorization, Instant now) {
        var voucher = vouchers.find(authorization.terms().tenantId(), authorization.terms().voucherOperationId()).orElseThrow(ApprovedPaymentSources::changed);
        var approvedPayee = payee(voucher, now);
        if (!authorization.matchesVoucher(voucher, now)) throw changed();
        payeeEvidence.requireAuthorizedAccount(authorization, approvedPayee);
        return voucher;
    }
    private static DomainException changed() { return new DomainException("PAYMENT_SOURCE_CHANGED", "Original approved payment source, account or accounting voucher changed"); }
}
