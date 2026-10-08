package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcPaymentAuthorizationRepository;
import io.agentflow.finance.JdbcVoucherOperationRepository;
import io.agentflow.finance.VoucherCommand;
import io.agentflow.finance.VoucherOperationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * 已批准未外发报销的财务撤销，申请、原命令停用、资源释放与审计共用原业务锁和事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseRevocationService {
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final ExpenseSettlementAccess access;
    private final JdbcVoucherOperationRepository vouchers;
    private final VoucherOperationService voucherExecution;
    private final JdbcPaymentAuthorizationRepository payments;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseReleaseService releases;

    /** 复用独立财务任职和原轮次字段授权，不接受管理员或历史参与关系代替。 */
    public ExpenseRevocationService(CurrentActor actors, ExpenseReportRepository reports, ApprovalApplicationFacade applications,
            ExpenseSettlementAccess access, JdbcVoucherOperationRepository vouchers, VoucherOperationService voucherExecution,
            JdbcPaymentAuthorizationRepository payments, JdbcExpenseSettlementRepository settlements, ExpenseReleaseService releases) {
        this.actors = actors; this.reports = reports; this.applications = applications; this.access = access;
        this.vouchers = vouchers; this.voucherExecution = voucherExecution; this.payments = payments;
        this.settlements = settlements; this.releases = releases;
    }

    /** 锁定后复核权限和外发事实；网络调用由原 outbox 执行，撤销回执不代表预算已释放。 */
    @Transactional
    public ExpenseLifecycleService.Receipt revoke(UUID reportId, ExpenseLifecycleService.Input input) {
        var actor = actors.actor(); reports.lock(actor.tenantId(), reportId);
        var report = reports.find(actor.tenantId(), reportId).orElseThrow();
        var context = access.requireFinance(reportId, report.currentRound().roundNo());
        if (report.version() != input.financialVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Expense financial version changed");
        var issue = issue(report, context.application());
        if (issue != null) throw new DomainException(issue.name(), "Original voucher, payment or settlement requires reconciliation before revocation");
        var application = applications.revokeBusiness(report.applicationId(), input.applicationVersion(), input.comment(), context.application().businessReference());
        var now = Instant.now();
        vouchers.forRound(actor.tenantId(), application.id(), application.roundNo(), VoucherCommand.Kind.EXPENSE_ACCRUAL)
                .ifPresent(operation -> voucherExecution.voidUnsent(operation, now));
        releases.release(application, actor.userId(), now);
        return new ExpenseLifecycleService.Receipt(reportId, application.id(), application.version(), report.version(), application.status().name());
    }

    /** 仅在原明细已授权的查询中展示当前能力；此投影不能代替写入时重新取锁授权。 */
    public Availability availability(Application application, ExpenseReport report) {
        if (application.status() != ApplicationStatus.APPROVED) return null;
        var context = access.read(report.id(), application.roundNo());
        if (!access.canManage(context, report.id())) return null;
        var issue = issue(report, application);
        return new Availability(issue == null, issue);
    }

    private Issue issue(ExpenseReport report, Application application) {
        if (settlements.find(report.tenantId(), report.id()).isPresent()
                || payments.latest(report.tenantId(), application.id(), application.roundNo()).isPresent()) {
            return Issue.EXPENSE_REVOCATION_SETTLEMENT_STARTED;
        }
        var voucher = vouchers.forRound(report.tenantId(), application.id(), application.roundNo(), VoucherCommand.Kind.EXPENSE_ACCRUAL).orElse(null);
        return voucher != null && !voucher.neverSent() ? Issue.EXPENSE_REVOCATION_VOUCHER_STARTED : null;
    }

    /** 只公开稳定阻断分类，不向页面返回金融命令、目标或外部凭据。 */
    public record Availability(boolean allowed, Issue unavailable) { }
    /** P0 自动撤销止于任何发送尝试，失败和查无都保留给财务原操作核对。 */
    public enum Issue { EXPENSE_REVOCATION_VOUCHER_STARTED, EXPENSE_REVOCATION_SETTLEMENT_STARTED }
}
