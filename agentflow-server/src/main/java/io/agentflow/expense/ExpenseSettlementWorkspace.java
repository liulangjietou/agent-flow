package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 最小结算投影分别展示资源核销和预算实际占用，不把银行成功或本地排队称作归档。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementWorkspace {
    private final ExpenseSettlementAccess access;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcBudgetOperationRepository budgets;
    /** 本接口不返回账户、原命令、目标摘要或发票原文。 */
    public ExpenseSettlementWorkspace(ExpenseSettlementAccess access, JdbcExpenseSettlementRepository settlements, JdbcBudgetOperationRepository budgets) {
        this.access = access; this.settlements = settlements; this.budgets = budgets;
    }
    /** 历史轮次只读取其对应账本，不能串到后续核定版本。 */
    public View get(UUID reportId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid(); Integer round = null;
        if (parameters.containsKey("roundNo")) {
            try { String value = parameters.get("roundNo"); if (!value.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(value); }
            catch (NumberFormatException malformed) { throw invalid(); }
        }
        var context = access.read(reportId, round); var app = context.application();
        var current = settlements.find(app.tenantId(), reportId).filter(value -> value.input().source().roundNo() == context.roundNo()).orElse(null);
        State state = null; boolean retry = false;
        if (current != null) {
            var input = current.input();
            var operation = current.budgetOperationId() == null ? null : budgets.find(app.tenantId(), current.budgetOperationId()).orElseThrow();
            state = new State(current.version(), current.status(), current.resourcesConsumed(), operation == null ? null : operation.status(), current.issue(),
                    input.payment() != null ? Funding.PAYMENT : input.voucherOperationId() != null ? Funding.FULL_OFFSET : Funding.ZERO_AMOUNT,
                    input.fundingConfirmedAt(), current.updatedAt());
            retry = (current.status() == ExpenseSettlement.Status.BLOCKED || current.status() == ExpenseSettlement.Status.BUDGET_REJECTED)
                    && app.status() == ApplicationStatus.APPROVED && app.version() == input.source().applicationVersion()
                    && context.businessVersion() == input.source().businessVersion() && access.canManage(context, reportId);
        }
        return new View(reportId, app.id(), context.roundNo(), app.version(), context.businessVersion(), state, retry);
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_QUERY", "Settlement query only accepts a positive roundNo"); }

    /**
     * 零金额与全额冲销不是银行付款成功。
     * @author owlzhangfq@gmail.com
     */
    public enum Funding { PAYMENT, FULL_OFFSET, ZERO_AMOUNT }
    /**
     * 缺省账本保持显式 null，前端不会误用旧轮次状态。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, long applicationVersion, long financialVersion, State settlement, boolean canRetry) { }
    /**
     * 只暴露稳定错误码，未知外部状态通过原预算查询继续恢复。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record State(long version, ExpenseSettlement.Status status, boolean resourcesConsumed, BudgetOperation.Status budgetStatus, String issue,
                        Funding funding, Instant fundingConfirmedAt, Instant updatedAt) { }
}
