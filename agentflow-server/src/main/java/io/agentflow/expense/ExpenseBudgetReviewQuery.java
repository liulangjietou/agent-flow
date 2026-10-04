package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预算审批事实按原轮次读取，完整费用明细授权同样约束管理员。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseBudgetReviewQuery {
    private final ExpenseDraftService expenses;
    private final JdbcExpenseBudgetReviewRepository reviews;
    private final JdbcBudgetOperationRepository operations;
    private final CurrentActor actors;

    /** 查询层投影原操作和审批证据，不向页面暴露可重放的外部授权。 */
    public ExpenseBudgetReviewQuery(ExpenseDraftService expenses, JdbcExpenseBudgetReviewRepository reviews,
            JdbcBudgetOperationRepository operations, CurrentActor actors) {
        this.expenses = expenses; this.reviews = reviews; this.operations = operations; this.actors = actors;
    }

    /** 先检查原节点的敏感字段权限；缺失历史记录不能解释为预算已通过。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID reportId, int roundNo) {
        var expense = expenses.read(reportId, roundNo);
        var tenant = actors.actor().tenantId();
        var review = reviews.find(tenant, reportId, roundNo).orElse(null);
        if (review == null) return new View(reportId, expense.applicationId(), roundNo, Status.NOT_RECORDED, null);
        var input = review.input();
        if (!input.applicationId().equals(expense.applicationId())) throw new IllegalStateException("Budget review application binding is inconsistent");
        var original = operations.find(tenant, input.originalOperationId()).orElseThrow();
        var authorized = review.authorizedOperationId() == null ? null : operations.find(tenant, review.authorizedOperationId()).orElseThrow();
        var approval = review.approval();
        return new View(reportId, expense.applicationId(), roundNo, Status.RECORDED,
                new Details(review.version(), input.submittedFinancialVersion(), input.budgetNodeId(),
                        input.policy() == null ? null : input.policy().reference(), input.originalOperationId(), original.status(),
                        review.authorizedOperationId(), authorized == null ? null : authorized.status(), review.status(),
                        approval == null ? null : new Decision(approval.actorId(), approval.taskId(), approval.auditEventId(), approval.approvedAt()),
                        review.automaticPass(), review.closure(), review.submittedAt(), review.updatedAt()));
    }

    /**
     * 新旧轮次是否实际保存预算依据。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { NOT_RECORDED, RECORDED }

    /**
     * 固定轮次的响应外壳，历史空值必须显式保留。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, Status status, Details details) { }

    /**
     * 仅投影真实决策和操作状态，不包含外部例外令牌、目标摘要或账户信息。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Details(long version, long financialVersion, String budgetNodeId, String policyReference,
            UUID originalOperationId, BudgetOperation.Status originalOperationStatus, UUID authorizedOperationId,
            BudgetOperation.Status authorizedOperationStatus, ExpenseBudgetReview.Status status, Decision decision,
            ExpenseBudgetReview.AutomaticPass automaticPass, ExpenseBudgetReview.Closure closure, Instant submittedAt, Instant updatedAt) { }

    /**
     * 实际办理人及原生任务审计，人工授权与预算确认分别展示。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(String actorId, String taskId, UUID auditEventId, Instant approvedAt) { }
}
