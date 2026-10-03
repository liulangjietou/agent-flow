package io.agentflow.budget;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 引擎最终批准和预算待结算依据同事务落库，写入失败回滚审批任务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentApprovalService {
    private final BudgetAdjustmentRepository requests;
    /** 批准封存本轮调整依据，实际额度变动由后续独立财务执行负责。 */
    public BudgetAdjustmentApprovalService(BudgetAdjustmentRepository requests) {
        this.requests = requests;
    }

    /** 所有任务动作均使用申请优先的锁顺序，与补正和提交一致。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Application application) {
        if (bound(application)) requests.lock(application.tenantId(), application.businessReference().id());
    }

    /** 仅真实最终批准可以记录依据，禁止通过客户端设置批准标记。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(Application application, String actor) {
        if (!bound(application) || application.status() != ApplicationStatus.APPROVED) return;
        var request = requests.find(application.tenantId(), application.businessReference().id()).orElseThrow(
                () -> new DomainException("NOT_FOUND", "Approved budget adjustment not found"));
        if (!request.applicationId().equals(application.id()) || !request.employeeId().equals(application.createdBy())
                || !BudgetAdjustmentFormContract.submittedPayload(request.currentRound()).equals(application.payload())) {
            throw new DomainException("BUDGET_ADJUSTMENT_APPROVAL_MISMATCH", "Approval must match the frozen adjustment terms and applicant");
        }
        long previous = request.version();
        request.approve(previous, application.roundNo(), application.version(), actor, Instant.now().truncatedTo(ChronoUnit.MICROS));
        requests.update(request, previous, actor, "APPROVE");
    }

    private static boolean bound(Application application) {
        return application.businessReference() != null && application.businessReference().type() == BusinessReference.Type.BUDGET_ADJUSTMENT;
    }
}
