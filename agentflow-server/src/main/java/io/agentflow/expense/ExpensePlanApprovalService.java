package io.agentflow.expense;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实际人工审批完成后同事务产生额度，额度写入失败必须回滚审批及引擎任务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePlanApprovalService {
    private final ExpensePlanRepository plans;
    private final ExpenseRequestRepository requests;

    /** 不依赖外部资源，批准阶段只使用已冻结的计划金额。 */
    public ExpensePlanApprovalService(ExpensePlanRepository plans, ExpenseRequestRepository requests) { this.plans = plans; this.requests = requests; }

    /** 所有计划任务动作复用申请、计划的固定锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Application application) {
        if (bound(application)) plans.lock(application.tenantId(), application.businessReference().id());
    }

    /** 仅由任务完成事务调用，不提供客户端“已批准”写入入口。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(Application application, String actor) {
        if (!bound(application) || application.status() != ApplicationStatus.APPROVED) return;
        var plan = plans.find(application.tenantId(), application.businessReference().id()).orElseThrow(
                () -> new DomainException("NOT_FOUND", "Approved expense plan not found"));
        if (!plan.applicationId().equals(application.id()) || !plan.employeeId().equals(application.createdBy())
                || !ExpensePlanFormContract.submittedPayload(plan.currentRound()).equals(application.payload())) {
            throw new DomainException("EXPENSE_PLAN_APPROVAL_MISMATCH", "Approval must match the frozen expense plan and applicant");
        }
        requests.create(plan.approvedRequest(application.roundNo()), actor);
    }

    private static boolean bound(Application application) {
        return application.businessReference() != null && application.businessReference().type() == BusinessReference.Type.EXPENSE_PLAN;
    }
}
