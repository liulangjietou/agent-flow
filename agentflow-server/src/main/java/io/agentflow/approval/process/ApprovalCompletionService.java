package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.budget.BudgetAdjustmentApprovalService;
import io.agentflow.expense.AdvanceRequestApprovalService;
import io.agentflow.expense.ExpenseApprovalService;
import io.agentflow.expense.ExpensePlanApprovalService;
import io.agentflow.finance.VoucherPreparationService;
import io.agentflow.procurement.ProcurementPaymentApprovalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * 人工任务和等待节点共享实际流程完成后的业务收尾，跨聚合编排不放进引擎或申请实体。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ApprovalCompletionService {
    private final ApplicationRepository applications;
    private final SubprocessExecutionLocks executionLocks;
    private final SubmissionRoundRepository rounds;
    private final ExpenseApprovalService expenses;
    private final ExpensePlanApprovalService plans;
    private final AdvanceRequestApprovalService advances;
    private final ProcurementPaymentApprovalService procurement;
    private final BudgetAdjustmentApprovalService budgets;
    private final VoucherPreparationService vouchers;
    private final ExpenseDuplicateApprovalProgress duplicateApprovals;

    /** 保留原财务控制与同事务准备入口，不在审批事务内调用外部财务系统。 */
    public ApprovalCompletionService(ApplicationRepository applications, SubmissionRoundRepository rounds,
            ExpenseApprovalService expenses, ExpensePlanApprovalService plans, AdvanceRequestApprovalService advances,
            ProcurementPaymentApprovalService procurement, BudgetAdjustmentApprovalService budgets, VoucherPreparationService vouchers,
            SubprocessExecutionLocks executionLocks, ExpenseDuplicateApprovalProgress duplicateApprovals) {
        this.applications = applications; this.rounds = rounds; this.expenses = expenses; this.plans = plans;
        this.advances = advances; this.procurement = procurement; this.budgets = budgets; this.vouchers = vouchers;
        this.executionLocks = executionLocks;
        this.duplicateApprovals = duplicateApprovals;
    }

    /** 申请优先，再锁关联业务；返回锁后事实，防止异步推进和人工动作交错覆盖。 */
    public Application lock(Application initial) {
        var path = lockForProgress(initial);
        path.requireActive();
        return path.application();
    }

    /** 后台按锁后事实返回暂停或过期结果，避免捕获事务代理异常后仍试图提交收件状态。 */
    public SubprocessExecutionLocks.LockedPath lockForProgress(Application initial) {
        var path = executionLocks.lockPath(initial);
        if (path.ancestors() == SubprocessExecutionLocks.AncestorState.ACTIVE) {
            for (var application : path.applications()) {
                expenses.lock(application); plans.lock(application); advances.lock(application);
                procurement.lock(application); budgets.lock(application);
            }
        }
        return path;
    }

    /** 引擎确认结束后才形成批准及财务依据，任何失败回滚引擎和全部业务事实。 */
    public void persistProgress(Application application, long expectedVersion, String instanceId, boolean ended,
                                String actor, String reason) {
        if (!ended) ended = duplicateApprovals.advance(application, instanceId);
        if (ended) {
            application.approve(application.version());
            rounds.complete(application.tenantId(), application.id(), application.roundNo(), instanceId,
                    SubmissionRound.Status.APPROVED, reason, actor, Instant.now());
        }
        applications.update(application, expectedVersion);
        plans.approved(application, actor); advances.approved(application, actor); procurement.approved(application, actor);
        budgets.approved(application, actor); vouchers.approved(application, actor);
    }
}
