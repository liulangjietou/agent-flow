package io.agentflow.budget;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentPersonnel;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 财务办理沿用原预算轮次字段权限，管理员或财务角色本身都不能绕过敏感原文授权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentFinanceAccess {
    private final CurrentActor actors;
    private final BudgetAdjustmentService requests;
    private final BudgetAdjustmentRepository repository;
    private final JdbcBudgetAdjustmentOperationRepository operations;
    private final PaymentPersonnel personnel;
    /** 复用原申请读取边界，法人任职只限定动作范围，不增加原文权限。 */
    public BudgetAdjustmentFinanceAccess(CurrentActor actors, BudgetAdjustmentService requests, BudgetAdjustmentRepository repository,
            JdbcBudgetAdjustmentOperationRepository operations, PaymentPersonnel personnel) {
        this.actors = actors; this.requests = requests; this.repository = repository; this.operations = operations; this.personnel = personnel;
    }
    /** 可读原轮次的人能看执行事实，独立财务另需当前角色和法人任职。 */
    public Context read(UUID id, Integer roundNo) {
        var view = requests.read(id, roundNo); var actor = actors.actor();
        var request = repository.find(actor.tenantId(), id).orElseThrow(BudgetAdjustmentFinanceAccess::notFound);
        boolean finance = actor.hasRole("FINANCE") && !actor.userId().equals(request.employeeId())
                && personnel.eligible(actor.tenantId(), actor.userId(), view.content().legalEntityId());
        return new Context(view, finance);
    }
    /** 每次新动作和幂等重放都检查当前权限，申请人兼任财务也不能自办。 */
    public Context requireFinance(UUID id, int roundNo) {
        var context = read(id, roundNo);
        if (!context.finance()) throw new DomainException("FORBIDDEN", "Independent finance role and current appointment in the original legal entity are required");
        return context;
    }
    /** 原操作必须位于原租户并可读取原批准轮次，不能用外来编号绕过范围。 */
    public BudgetAdjustmentOperation requireOperation(UUID id) {
        var operation = operations.find(actors.actor().tenantId(), id).orElseThrow(BudgetAdjustmentFinanceAccess::notFound);
        var source = operation.command().source(); requireFinance(source.requestId(), source.round().roundNo()); return operation;
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Budget adjustment execution is unavailable in the current scope"); }
    /**
     * 内部权限上下文不包含完整财务指令。
     * @author owlzhangfq@gmail.com
     */
    public record Context(BudgetAdjustmentService.View view, boolean finance) { }
}
