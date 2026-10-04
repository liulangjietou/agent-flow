package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseResourceAdjustment;
import io.agentflow.expense.ExpenseResourceAdjustmentAccess;
import io.agentflow.expense.ExpenseResourceAdjustmentPreparation;
import io.agentflow.expense.ExpenseResourceAdjustmentRetirement;
import io.agentflow.expense.ExpenseSettlementAccess;
import io.agentflow.expense.JdbcExpenseResourceAdjustmentPreparationRepository;
import io.agentflow.expense.JdbcExpenseResourceAdjustmentRepository;
import io.agentflow.expense.JdbcExpenseSettlementRepository;
import io.agentflow.finance.BudgetConsumptionReversalObservation;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原结算、实际准备与完整调整修订证明消息来源，当前原轮次权限约束详情。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseAdjustmentNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final JdbcExpenseResourceAdjustmentPreparationRepository preparations;
    private final JdbcExpenseResourceAdjustmentRepository adjustments;
    private final JdbcBudgetConsumptionReversalRepository budgets;
    private final JdbcExpenseSettlementRepository settlements;
    private final ApplicationRepository applications;
    private final ExpenseSettlementAccess readAccess;
    private final ExpenseResourceAdjustmentAccess financeAccess;
    private final PaymentNotificationAccess personnel;

    /** 不重新读取外部财务依据，不调用授权、预算查询或资源恢复。 */
    public ExpenseAdjustmentNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, JdbcExpenseResourceAdjustmentPreparationRepository preparations,
            JdbcExpenseResourceAdjustmentRepository adjustments, JdbcBudgetConsumptionReversalRepository budgets, JdbcExpenseSettlementRepository settlements,
            ApplicationRepository applications, ExpenseSettlementAccess readAccess, ExpenseResourceAdjustmentAccess financeAccess, PaymentNotificationAccess personnel) {
        this.actors = actors; this.jdbc = jdbc; this.preparations = preparations; this.adjustments = adjustments; this.budgets = budgets; this.settlements = settlements;
        this.applications = applications; this.readAccess = readAccess; this.financeAccess = financeAccess; this.personnel = personnel;
    }
    /** 本人消息固定原调整，管理员身份不能绕过原财务字段权限。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID id) {
        var actor = actors.actor(); var row = row(actor.tenantId(), actor.userId(), id); var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null) throw new DomainException("NOT_FOUND", "Original expense adjustment notification is unavailable in the current scope");
        var input = source.preparation().input(); var original = input.basis().settlement().input().source();
        if (actor.userId().equals(original.employeeId())) readAccess.read(input.basis().reportId(), original.roundNo());
        else financeAccess.requireFinance(input.basis().reportId(), original.roundNo());
        return new Target(id, input.id(), input.basis().reportId(), original.applicationId(), original.roundNo(), ExpenseAdjustmentNotice.source(row.eventKey()).orElseThrow().notice(),
                Preparation.of(source.preparation()), source.budget() == null ? null : Budget.of(source.budget()),
                source.adjustment() == null ? null : Adjustment.of(source.adjustment()), source.completion() == null ? null : Completion.of(source.completion()),
                source.retirement() == null ? null : Retirement.of(source.retirement()));
    }
    /** 外发前重新检查当前人员资格及消息的实际原事实。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }
    Source original(String tenant, UUID id, ExpenseAdjustmentNotice fact) {
        var preparation = preparations.find(tenant, id).orElse(null); if (preparation == null) return null;
        var input = preparation.input(); var basis = input.basis(); var binding = basis.settlement().input().source();
        if (!settlements.revision(tenant, basis.reportId(), basis.settlement().version()).filter(basis.settlement()::equals).isPresent()) return null;
        var app = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (app == null || !app.createdBy().equals(binding.employeeId()) || app.businessReference() == null
                || app.businessReference().type() != BusinessReference.Type.EXPENSE || !app.businessReference().id().equals(basis.reportId())) return null;
        var adjustment = adjustments.find(tenant, id).orElse(null); var budget = budgets.find(tenant, id).orElse(null);
        List<ExpenseResourceAdjustment> resourceHistory = List.of(); List<BudgetConsumptionReversalOperation> budgetHistory = List.of();
        ExpenseResourceAdjustment completion = null; ExpenseResourceAdjustmentRetirement retirement = null;
        if (preparation.status() == ExpenseResourceAdjustmentPreparation.Status.AUTHORIZED) {
            if (adjustment == null || budget == null || !preparation.authorizedInput().equals(adjustment.input()) || !budget.input().equals(adjustment.input().budget())) return null;
            resourceHistory = adjustments.revisions(tenant, id); budgetHistory = budgets.revisions(tenant, id);
            if (resourceHistory.stream().anyMatch(value -> !value.input().equals(adjustment.input()))
                    || budgetHistory.stream().anyMatch(value -> !value.input().equals(budget.input()))) return null;
            completion = adjustments.completion(tenant, id).orElse(null); retirement = adjustments.retirement(tenant, id).orElse(null);
            if (adjustment.resourcesReversed() != (completion != null) || (adjustment.status() == ExpenseResourceAdjustment.Status.RETIRED) != (retirement != null)
                    || retirement != null && (!retirement.after().equals(adjustment) || !retirement.stoppedBudget().equals(budget))) return null;
        } else if (adjustment != null || budget != null) return null;
        var recipients = new ArrayList<>(List.of(binding.employeeId(), input.requestedBy()));
        if (fact == ExpenseAdjustmentNotice.RETIRED && retirement != null) recipients.add(retirement.retiredBy());
        var source = new Source(app, preparation, adjustment, budget, completion, retirement, resourceHistory, budgetHistory, recipients.stream().distinct().toList());
        return hasFact(source, fact, null) ? source : null;
    }
    boolean eligible(String tenant, String recipient, Source source) {
        var basis = source.preparation().input().basis();
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, basis.settlement().input().source().employeeId(), basis.legalEntityId());
    }
    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = ExpenseAdjustmentNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.adjustmentId(), key.notice());
        return source != null && source.application().id().equals(row.applicationId())
                && source.preparation().input().basis().settlement().input().source().roundNo() == row.roundNo()
                && hasFact(source, key.notice(), row.createdAt()) && eligible(tenant, recipient, source) ? source : null;
    }
    private static boolean hasFact(Source source, ExpenseAdjustmentNotice fact, Instant at) {
        if (fact == ExpenseAdjustmentNotice.RETIRED) return source.retirement() != null && timeMatches(source.retirement().retiredAt(), at);
        if (ExpenseAdjustmentNotice.from(source.preparation()).filter(value -> value == fact).isPresent()) return timeMatches(source.preparation().updatedAt(), at);
        if (fact == ExpenseAdjustmentNotice.COMPLETED && source.completion() == null) return false;
        return source.budgetHistory().stream().anyMatch(value -> timeMatches(value.updatedAt(), at) && ExpenseAdjustmentNotice.from(value).filter(notice -> notice == fact).isPresent())
                || source.resourceHistory().stream().anyMatch(value -> timeMatches(value.updatedAt(), at) && ExpenseAdjustmentNotice.from(value).filter(notice -> notice == fact).isPresent());
    }
    private static boolean timeMatches(Instant fact, Instant message) { return message == null || fact.equals(message); }
    private Row row(String tenant, String recipient, UUID id) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no,created_at FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('EXPENSE_ADJUSTMENT_RESULT','EXPENSE_ADJUSTMENT_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")), UUID.fromString(row.getString("application_id")), row.getInt("round_no"), row.getTimestamp("created_at").toInstant()),
                tenant, recipient, id.toString()).stream().findFirst().orElse(null);
    }
    /**
     * 完整原事实只用于内部核对，不返回外部账户或原财务快照。
     * @author owlzhangfq@gmail.com
     */
    record Source(Application application, ExpenseResourceAdjustmentPreparation preparation, ExpenseResourceAdjustment adjustment,
                  BudgetConsumptionReversalOperation budget, ExpenseResourceAdjustment completion, ExpenseResourceAdjustmentRetirement retirement,
                  List<ExpenseResourceAdjustment> resourceHistory, List<BudgetConsumptionReversalOperation> budgetHistory, List<String> recipients) { }
    /**
     * 消息发生时间必须对应准确历史修订。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo, Instant createdAt) { }
    /**
     * 预算结果、资源结果和实际安全结束独立展示。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(UUID messageId, UUID adjustmentId, UUID reportId, UUID applicationId, int roundNo, ExpenseAdjustmentNotice fact,
                         Preparation preparation, Budget budget, Adjustment adjustment, Completion completion, Retirement retirement) { }
    /**
     * 本次准备的最小事实。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(long version, ExpenseResourceAdjustmentPreparation.Status status, Instant updatedAt, String issue) {
        static Preparation of(ExpenseResourceAdjustmentPreparation value) { return new Preparation(value.version(), value.status(), value.updatedAt(), value.issue()); }
    }
    /**
     * 原预算回执和另一次矛盾回执均保留，不展示金额或外部引用。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Budget(long version, BudgetConsumptionReversalOperation.Status status, Instant updatedAt, BudgetConsumptionReversalOperation.Failure failure,
                         BudgetConsumptionReversalObservation.Status outcome, BudgetConsumptionReversalObservation.Status conflictingOutcome) {
        static Budget of(BudgetConsumptionReversalOperation value) { return new Budget(value.version(), value.status(), value.updatedAt(), value.failure(), value.observation() == null ? null : value.observation().status(), value.conflictingObservation() == null ? null : value.conflictingObservation().status()); }
    }
    /**
     * 已完成资源标志不会在预算重新核对时消失。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Adjustment(long version, ExpenseResourceAdjustment.Status status, Instant updatedAt, boolean resourcesReversed, String issue) {
        static Adjustment of(ExpenseResourceAdjustment value) { return new Adjustment(value.version(), value.status(), value.updatedAt(), value.resourcesReversed(), value.issue()); }
    }
    /**
     * 首次实际完成修订，不把较晚的重新确认当成再次资源冲回。
     * @author owlzhangfq@gmail.com
     */
    public record Completion(long adjustmentVersion, long budgetVersion, Instant completedAt) {
        static Completion of(ExpenseResourceAdjustment value) { return new Completion(value.version(), value.budgetReversalVersion(), value.updatedAt()); }
    }
    /**
     * 安全结束的原调整及已停止预算准确版本。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(long adjustmentVersion, long budgetVersion, Instant retiredAt) {
        static Retirement of(ExpenseResourceAdjustmentRetirement value) { return new Retirement(value.after().version(), value.stoppedBudget().version(), value.retiredAt()); }
    }
}
