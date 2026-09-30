package io.agentflow.budget;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预算执行从真实审批和独立申请派生依据，外部或调用者声明不能代替最终批准。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApprovedBudgetAdjustmentSources {
    private final ApplicationRepository applications;
    private final BudgetAdjustmentRepository requests;
    /** 跨聚合来源核对属于应用层，领域仅处理已读取的不可变事实。 */
    public ApprovedBudgetAdjustmentSources(ApplicationRepository applications, BudgetAdjustmentRepository requests) {
        this.applications = applications; this.requests = requests;
    }
    /** 所有财务决定沿用审批申请优先的锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(ApprovedBudgetAdjustment source) { requests.lock(source.tenantId(), source.requestId()); }
    /** 读取实际最终批准版本、原预算轮次及与之对应的审批载荷。 */
    public ApprovedBudgetAdjustment derive(String tenant, UUID requestId) {
        var request = requests.find(tenant, requestId).orElseThrow(ApprovedBudgetAdjustmentSources::changed);
        var application = applications.findById(tenant, request.applicationId()).orElseThrow(ApprovedBudgetAdjustmentSources::changed);
        if (request.approval() == null || application.status() != ApplicationStatus.APPROVED || application.businessReference() == null
                || application.businessReference().type() != BusinessReference.Type.BUDGET_ADJUSTMENT || !application.businessReference().id().equals(request.id())
                || !application.createdBy().equals(request.employeeId()) || application.roundNo() != request.approval().roundNo()
                || application.version() != request.approval().applicationVersion()
                || !application.payload().equals(BudgetAdjustmentFormContract.submittedPayload(request.currentRound()))) throw changed();
        return ApprovedBudgetAdjustment.from(request);
    }
    /** 新副作用依赖当前批准；查询已经发送的命令时不调用本守卫。 */
    public void requireCurrent(ApprovedBudgetAdjustment source) {
        if (!source.equals(derive(source.tenantId(), source.requestId()))) throw changed();
    }
    private static DomainException changed() { return new DomainException("BUDGET_ADJUSTMENT_SOURCE_CHANGED", "Actual approved budget application or original round changed"); }
}
