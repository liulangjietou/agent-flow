package io.agentflow.finance;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequestService;
import io.agentflow.expense.ExpenseDraftService;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * 凭证沿用当轮完整财务明细的读取边界，财务角色和管理员都不获得字段旁路。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherAccess {
    private final CurrentActor actors;
    private final ApprovalApplicationFacade applications;
    private final ExpenseDraftService expenses;
    private final AdvanceRequestService advances;
    /** 直接复用两种业务已经验证的历史与敏感字段权限。 */
    public VoucherAccess(CurrentActor actors, ApprovalApplicationFacade applications, ExpenseDraftService expenses, AdvanceRequestService advances) {
        this.actors = actors; this.applications = applications; this.expenses = expenses; this.advances = advances;
    }
    /** 不可读轮次不能通过操作号或状态接口得知其财务事实。 */
    public Context read(UUID applicationId, Integer roundNo) {
        var application = applications.get(applicationId); var binding = application.businessReference();
        if (binding == null || binding.type() != BusinessReference.Type.EXPENSE && binding.type() != BusinessReference.Type.ADVANCE_REQUEST) throw notFound();
        int round = roundNo == null ? application.roundNo() : roundNo;
        long version;
        if (binding.type() == BusinessReference.Type.EXPENSE) {
            var detail = expenses.read(binding.id(), roundNo); round = detail.roundNo(); version = detail.financialVersion();
        } else {
            var detail = advances.read(binding.id(), roundNo); round = detail.roundNo(); version = detail.requestVersion();
        }
        var actor = actors.actor(); boolean finance = actor.hasRole("FINANCE") && !actor.userId().equals(application.createdBy());
        return new Context(application, round, version, finance);
    }
    /** 幂等回放之前也复核当前读取权限，职责分离禁止申请人操作自己的财务副作用。 */
    public Context requireFinance(UUID applicationId, int round) {
        var context = read(applicationId, round);
        if (!context.finance()) throw new DomainException("FORBIDDEN", "Finance role and separation from applicant are required");
        return context;
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Financial application not found"); }
    /**
     * 内部授权上下文，不作为 HTTP 响应返回原始申请。
     * @author owlzhangfq@gmail.com
     */
    public record Context(Application application, int roundNo, long businessVersion, boolean finance) {
        /** 凭证类型由服务端业务绑定确定，外部请求不能替换。 */
        public VoucherCommand.Kind kind() { return application.businessReference().type() == BusinessReference.Type.ADVANCE_REQUEST ? VoucherCommand.Kind.EMPLOYEE_ADVANCE : VoucherCommand.Kind.EXPENSE_ACCRUAL; }
        /** 锁身份取服务器业务绑定，金额和版本仍由写入服务复核。 */
        public VoucherPreparation.Source source() {
            return new VoucherPreparation.Source(application.tenantId(), application.businessReference().type(), application.businessReference().id(), application.id(), roundNo,
                    application.version(), businessVersion, application.createdBy());
        }
    }
}
