package io.agentflow.expense.reporting;

import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequestService;
import io.agentflow.expense.ExpenseDraftService;
import io.agentflow.expense.ExpensePlanService;
import io.agentflow.expense.ExpenseResponse;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 三类真实财务来源共用现有完整字段读取边界，之后才使用原任职筛选。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseReportScope {
    private final ExpenseDraftService expenses;
    private final ExpensePlanService plans;
    private final AdvanceRequestService advances;
    private final SubmissionRoundRepository rounds;
    private final CurrentActor actors;

    /** 不创建报表专用授权，管理员也通过同一原轮次字段投影。 */
    public ExpenseReportScope(ExpenseDraftService expenses, ExpensePlanService plans, AdvanceRequestService advances,
                              SubmissionRoundRepository rounds, CurrentActor actors) {
        this.expenses = expenses; this.plans = plans; this.advances = advances; this.rounds = rounds; this.actors = actors;
    }
    /** 不可读费用轮次完全不进入统计；数据损坏和基础设施异常仍传播。 */
    public Optional<Expense> expense(UUID id, int number) {
        return readable(() -> expenses.read(id, number)).map(view -> new Expense(view, original(view.applicationId(), number)));
    }
    /** 事前余额授权依附原批准轮次，不借当前已关闭状态取消历史读取。 */
    public Optional<Plan> plan(UUID id, int number) {
        return readable(() -> plans.read(id, number)).map(view -> new Plan(view, original(view.applicationId(), number)));
    }
    /** 借款原轮次的全部敏感字段可读后，才允许汇总其已放款余额。 */
    public Optional<Advance> advance(UUID id, int number) {
        return readable(() -> advances.read(id, number)).map(view -> new Advance(view, original(view.applicationId(), number)));
    }
    private SubmissionRound original(UUID application, int number) {
        return rounds.findByRound(actors.actor().tenantId(), application, number).orElseThrow(ExpenseReportScope::unavailable);
    }
    private static <T> Optional<T> readable(Supplier<T> read) {
        try { return Optional.of(read.get()); }
        catch (DomainException failure) {
            if ("FORBIDDEN".equals(failure.code()) || "NOT_FOUND".equals(failure.code())) return Optional.empty();
            throw failure;
        }
    }
    /** 法人与部门都来自原任职，缺失历史组织不能匹配一个指定的组织。 */
    static boolean organization(ExpenseReportQueryParameters.Query query, SubmissionRound round) {
        var original = round.initiatorContext();
        return (query.legalEntityId() == null || original != null && query.legalEntityId().equals(original.legalEntityId()))
                && (query.departmentId() == null || original != null && query.departmentId().equals(original.departmentId()));
    }
    /** 不同来源绑定不一致时拒绝整次报告，不能以零值掩盖来源损坏。 */
    static DomainException unavailable() { return new DomainException("EXPENSE_REPORT_SOURCE_UNAVAILABLE", "Original financial report sources are missing or inconsistent"); }
    /**
     * 已授权报销与其独立审批原事实。
     * @author owlzhangfq@gmail.com
     */
    public record Expense(ExpenseResponse view, SubmissionRound original) {
        /** 类别只检查原明细，不使用现在的类别目录。 */
        boolean matches(ExpenseReportQueryParameters.Query query) {
            return organization(query, original) && (query.categoryCode() == null || view.financialRound().originalLines().stream()
                    .anyMatch(line -> query.categoryCode().equals(line.original().categoryCode())));
        }
    }
    /**
     * 已授权计划与原批准上下文。
     * @author owlzhangfq@gmail.com
     */
    public record Plan(ExpensePlanService.View view, SubmissionRound original) { }
    /**
     * 已授权借款与原放款审批上下文。
     * @author owlzhangfq@gmail.com
     */
    public record Advance(AdvanceRequestService.View view, SubmissionRound original) { }
}
