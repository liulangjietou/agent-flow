package io.agentflow.finance;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequestRepository;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.JdbcExpenseSubmissionControlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 准备和实际发送共用真实批准来源，跨仓储规则集中在应用层而非 HTTP 回执中。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApprovedVoucherSources {
    private final ApplicationRepository applications;
    private final AdvanceRequestRepository advances;
    private final ExpenseReportRepository expenses;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final JdbcBudgetOccupationRepository budgets;
    /** 不缓存源事实，领取与落库均重新读取实际双版本。 */
    public ApprovedVoucherSources(ApplicationRepository applications, AdvanceRequestRepository advances, ExpenseReportRepository expenses,
                                  JdbcExpenseSubmissionControlRepository controls, JdbcBudgetOccupationRepository budgets) {
        this.applications = applications; this.advances = advances; this.expenses = expenses; this.controls = controls; this.budgets = budgets;
    }
    /** 最终审批已持有申请锁，用实际财务聚合版本建立来源，不接受客户端金额。 */
    public VoucherPreparation.Source reference(Application application) {
        var binding = application.businessReference();
        if (application.status() != ApplicationStatus.APPROVED || binding == null
                || binding.type() != BusinessReference.Type.ADVANCE_REQUEST && binding.type() != BusinessReference.Type.EXPENSE) throw changed();
        long version = binding.type() == BusinessReference.Type.ADVANCE_REQUEST
                ? advances.find(application.tenantId(), binding.id()).orElseThrow(ApprovedVoucherSources::changed).version()
                : expenses.find(application.tenantId(), binding.id()).orElseThrow(ApprovedVoucherSources::changed).version();
        return new VoucherPreparation.Source(application.tenantId(), binding.type(), binding.id(), application.id(), application.roundNo(), application.version(), version, application.createdBy());
    }
    /** 所有结算动作继续使用申请优先、财务聚合其次的锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(VoucherPreparation.Source source) {
        if (source.businessType() == BusinessReference.Type.ADVANCE_REQUEST) advances.lock(source.tenantId(), source.businessId());
        else expenses.lock(source.tenantId(), source.businessId());
    }
    /** 源金额及纸件和预算守卫只在这里读取；零金额也必须经过当前轮次控制。 */
    public VoucherSource.Plan derive(VoucherPreparation.Source source) {
        return derive(source, true);
    }
    /** 已消费资源的结算恢复仍核对批准和纸件；预算后续状态由原消费操作单独证明。 */
    public VoucherSource.Plan deriveAfterResourceConsumption(VoucherPreparation.Source source) {
        return derive(source, false);
    }
    private VoucherSource.Plan derive(VoucherPreparation.Source source, boolean requireFrozenBudget) {
        var application = applications.findById(source.tenantId(), source.applicationId()).orElseThrow(ApprovedVoucherSources::changed);
        if (!reference(application).equals(source)) throw changed();
        if (source.businessType() == BusinessReference.Type.ADVANCE_REQUEST) {
            return VoucherSource.advance(application, advances.find(source.tenantId(), source.businessId()).orElseThrow(ApprovedVoucherSources::changed));
        }
        var report = expenses.find(source.tenantId(), source.businessId()).orElseThrow(ApprovedVoucherSources::changed);
        var control = controls.find(source.tenantId(), report.id(), source.roundNo()).orElseThrow(ApprovedVoucherSources::changed);
        var budget = budgets.find(source.tenantId(), report.id()).orElseThrow(ApprovedVoucherSources::changed);
        if (requireFrozenBudget && !budget.frozenFor(BudgetPrecheckPort.Request.fromCurrent(report, control.input().accountingDate()))) {
            throw new DomainException("VOUCHER_BUDGET_NOT_FROZEN", "Current expense amount must retain its confirmed budget reservation");
        }
        return VoucherSource.expense(application, report, control);
    }
    /** 命令身份仅用于定位，真实字段由 derive 重新核验。 */
    public VoucherPreparation.Source reference(VoucherCommand command) {
        if (command.kind() == VoucherCommand.Kind.PAYMENT) throw new DomainException("VOUCHER_PAYMENT_SOURCE_REQUIRED", "A persisted successful payment is required before registering its voucher");
        var binding = command.binding();
        return new VoucherPreparation.Source(command.tenantId(), command.kind() == VoucherCommand.Kind.EMPLOYEE_ADVANCE ? BusinessReference.Type.ADVANCE_REQUEST : BusinessReference.Type.EXPENSE,
                binding.businessId(), binding.applicationId(), binding.roundNo(), binding.applicationVersion(), binding.businessVersion(), command.employeeId());
    }
    private static DomainException changed() { return new DomainException("VOUCHER_SOURCE_CHANGED", "Original approved financial source has changed or is unavailable"); }
}
