package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.*;
import io.agentflow.finance.BudgetConsumptionReductionOperation;
import io.agentflow.finance.ExpenseAccrualReductionOperation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原结算、连续修订和规范化命令登记共同证明来源；旧消息读取同一原操作的最后记录。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final JdbcExpensePartialPreparationRepository preparations;
    private final JdbcExpensePartialDisputeRepository disputes;
    private final JdbcExpenseSettlementRepository settlements;
    private final ApplicationRepository applications;
    private final ExpenseSettlementAccess readAccess;
    private final ExpenseResourceAdjustmentAccess financeAccess;
    private final PaymentNotificationAccess personnel;
    /** 读取只依赖已经保存的原件，不发起外部查询、授权或调整。 */
    public ExpensePartialAdjustmentNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, JdbcExpensePartialAdjustmentRepository adjustments,
            JdbcExpensePartialPreparationRepository preparations, JdbcExpensePartialDisputeRepository disputes, JdbcExpenseSettlementRepository settlements,
            ApplicationRepository applications, ExpenseSettlementAccess readAccess, ExpenseResourceAdjustmentAccess financeAccess, PaymentNotificationAccess personnel) {
        this.actors = actors; this.jdbc = jdbc; this.adjustments = adjustments; this.preparations = preparations; this.disputes = disputes; this.settlements = settlements;
        this.applications = applications; this.readAccess = readAccess; this.financeAccess = financeAccess; this.personnel = personnel;
    }
    /** 本人消息与当前原轮次字段权限同时成立，管理员身份不能绕过财务边界。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID id) {
        var actor = actors.actor(); var row = row(actor.tenantId(), actor.userId(), id); var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null) throw new DomainException("NOT_FOUND", "Original partial expense adjustment notification is unavailable in the current scope");
        var binding = source.current().input().basis().funding().financial().settlement().input().source(); var report = source.current().input().basis().reportId();
        if (actor.userId().equals(binding.employeeId())) readAccess.read(report, binding.roundNo()); else financeAccess.requireFinance(report, binding.roundNo());
        var value = source.view(); var key = source.key();
        return new Target(id, key.adjustmentId(), report, binding.applicationId(), binding.roundNo(), key.sourceType(), key.sourceId(), key.notice(),
                source.preparation() == null ? null : Preparation.of(source.preparation()), value == null ? null : Operation.of(value.budget()),
                value == null ? null : Operation.of(value.accrual()), value == null ? null : Adjustment.of(value),
                value == null || value.completion() == null ? null : Completion.of(value.completion()),
                value == null || value.retirement() == null ? null : new Retirement(value.retirement().at()),
                source.decision() == null ? null : Resolution.of(source.decision()));
    }
    /** 实际外发前复核当前人员资格与原事实。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }
    Source original(String tenant, ExpensePartialAdjustmentNotice.Source key) {
        var current = adjustments.find(tenant, key.adjustmentId()).orElse(null); if (current == null) return null;
        var basis = current.input().basis(); var settlement = basis.funding().financial().settlement(); var binding = settlement.input().source();
        if (!settlements.revision(tenant, basis.reportId(), settlement.version()).filter(settlement::equals).isPresent()) return null;
        var app = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (app == null || !app.createdBy().equals(binding.employeeId()) || app.businessReference() == null
                || app.businessReference().type() != BusinessReference.Type.EXPENSE || !app.businessReference().id().equals(basis.reportId())) return null;
        var history = disputes.revisions(current); ExpensePartialAdjustment view = current;
        ExpensePartialAdjustmentPreparation preparation = null; ExpensePartialDisputeResolution decision = null;
        var recipients = new ArrayList<>(List.of(binding.employeeId(), current.input().requestedBy()));
        if (key.sourceType() == ExpensePartialAdjustmentNotice.SourceType.PREPARATION) {
            preparation = preparations.find(tenant, key.sourceId()).orElse(null);
            if (preparation == null || !history.contains(preparation.input().adjustment())) return null;
            recipients.add(preparation.input().requestedBy()); view = null;
        } else {
            if (key.sourceType() == ExpensePartialAdjustmentNotice.SourceType.DISPUTE) {
                decision = disputes.recorded(current).stream().filter(value -> value.id().equals(key.sourceId())).findFirst().orElse(null);
                if (decision == null) return null; recipients.add(decision.resolvedBy());
            }
            var side = decision != null ? decision.side().name() : key.sourceType().name();
            var operation = decision != null ? decision.operationId() : key.sourceId();
            if (side.equals("BUDGET") || side.equals("ACCRUAL")) {
                view = null;
                for (var value : history) if (matchesOperation(value, side, operation)) view = value;
                if (view == null) return null;
            }
            // 被替换的原操作仍核验原规范化登记与实际完成证明，不仅相信历史 JSON。
            if (!adjustments.revision(tenant, current.id(), view.version()).filter(view::equals).isPresent()) return null;
            if (view.budget() != null) recipients.add(view.budget().input().command().authorizedBy());
            if (view.accrual() != null) recipients.add(view.accrual().input().command().authorizedBy());
            if (key.notice() == ExpensePartialAdjustmentNotice.RETIRED && view.retirement() != null) recipients.add(view.retirement().actor());
        }
        var source = new Source(key, app, current, view, preparation, decision, history, recipients.stream().distinct().toList());
        return hasFact(source, null) ? source : null;
    }
    boolean eligible(String tenant, String recipient, Source source) {
        var financial = source.current().input().basis().funding().financial();
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, financial.settlement().input().source().employeeId(), financial.accrual().input().command().legalEntityId());
    }
    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = ExpensePartialAdjustmentNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key);
        return source != null && source.application().id().equals(row.applicationId())
                && source.current().input().basis().funding().financial().settlement().input().source().roundNo() == row.roundNo()
                && hasFact(source, row.createdAt()) && eligible(tenant, recipient, source) ? source : null;
    }
    private static boolean hasFact(Source source, Instant at) {
        var key = source.key(); var fact = key.notice();
        return switch (key.sourceType()) {
            case PREPARATION -> ExpensePartialAdjustmentNotice.from(source.preparation()).filter(value -> value == fact).isPresent() && time(source.preparation().updatedAt(), at);
            case DISPUTE -> source.decision() != null && time(source.decision().resolvedAt(), at);
            case ADJUSTMENT -> fact == ExpensePartialAdjustmentNotice.RETIRED
                    ? source.current().retirement() != null && time(source.current().retirement().at(), at)
                    : source.history().stream().anyMatch(value -> time(value.updatedAt(), at) && ExpensePartialAdjustmentNotice.from(value).filter(found -> found == fact).isPresent());
            case BUDGET -> source.history().stream().anyMatch(value -> matchesOperation(value, "BUDGET", key.sourceId()) && time(value.budget().updatedAt(), at)
                    && ExpensePartialAdjustmentNotice.from(value.budget()).filter(found -> found == fact).isPresent());
            case ACCRUAL -> source.history().stream().anyMatch(value -> matchesOperation(value, "ACCRUAL", key.sourceId()) && time(value.accrual().updatedAt(), at)
                    && ExpensePartialAdjustmentNotice.from(value.accrual()).filter(found -> found == fact).isPresent());
        };
    }
    private static boolean matchesOperation(ExpensePartialAdjustment value, String side, UUID operation) {
        return side.equals("BUDGET") ? value.budget() != null && value.budget().input().command().id().equals(operation)
                : value.accrual() != null && value.accrual().input().command().id().equals(operation);
    }
    private static boolean time(Instant fact, Instant at) { return at == null || fact.equals(at); }
    private Row row(String tenant, String recipient, UUID id) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no,created_at FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('EXPENSE_PARTIAL_ADJUSTMENT_RESULT','EXPENSE_PARTIAL_ADJUSTMENT_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")), UUID.fromString(row.getString("application_id")), row.getInt("round_no"), row.getTimestamp("created_at").toInstant()),
                tenant, recipient, id.toString()).stream().findFirst().orElse(null);
    }
    /**
     * 内部原件只用于核对，不向消息泄漏财务快照。
     * @author owlzhangfq@gmail.com
     */
    record Source(ExpensePartialAdjustmentNotice.Source key, Application application, ExpensePartialAdjustment current, ExpensePartialAdjustment view,
                  ExpensePartialAdjustmentPreparation preparation, ExpensePartialDisputeResolution decision, List<ExpensePartialAdjustment> history, List<String> recipients) { }
    /**
     * 消息时间必须对应原事实。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo, Instant createdAt) { }
    /**
     * 原操作摘要，不含金额、账户、外部引用或办理许可。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(UUID messageId, UUID adjustmentId, UUID reportId, UUID applicationId, int roundNo,
                         ExpensePartialAdjustmentNotice.SourceType sourceType, UUID sourceId, ExpensePartialAdjustmentNotice fact,
                         Preparation preparation, Operation budget, Operation accrual, Adjustment adjustment, Completion completion, Retirement retirement, Resolution resolution) { }
    /**
     * 本次单侧准备。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(ExpensePartialAdjustmentPreparation.Side side, long version, ExpensePartialAdjustmentPreparation.Status status, Instant updatedAt, String issue) {
        static Preparation of(ExpensePartialAdjustmentPreparation v) { return new Preparation(v.input().side(), v.version(), v.status(), v.updatedAt(), v.issue()); }
    }
    /**
     * 两类操作只共享最小展示结构，原编号和状态分别保留。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(UUID id, long version, String status, Instant updatedAt, String failure, String outcome, String conflictingOutcome) {
        static Operation of(BudgetConsumptionReductionOperation v) { return v == null ? null : new Operation(v.input().command().id(), v.version(), v.status().name(), v.updatedAt(), v.failure() == null ? null : v.failure().name(), v.observation() == null ? null : v.observation().status().name(), v.conflictingObservation() == null ? null : v.conflictingObservation().status().name()); }
        static Operation of(ExpenseAccrualReductionOperation v) { return v == null ? null : new Operation(v.input().command().id(), v.version(), v.status().name(), v.updatedAt(), v.failure() == null ? null : v.failure().name(), v.observation() == null ? null : v.observation().status().name(), v.conflictingObservation() == null ? null : v.conflictingObservation().status().name()); }
    }
    /**
     * 原操作最后所在修订，不冒充替代操作的当前状态。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Adjustment(long version, ExpensePartialAdjustment.Status status, Instant updatedAt, boolean resourcesCompleted, String issue) {
        static Adjustment of(ExpensePartialAdjustment v) { return new Adjustment(v.version(), v.status(), v.updatedAt(), v.completion() != null, v.issue()); }
    }
    /**
     * 不可覆盖的实际完成事实。
     * @author owlzhangfq@gmail.com
     */
    public record Completion(long budgetVersion, long accrualVersion, Instant completedAt) {
        static Completion of(ExpensePartialAdjustment.Completion v) { return new Completion(v.budgetVersion(), v.accrualVersion(), v.at()); }
    }
    /**
     * 实际安全结束时间。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(Instant retiredAt) { }
    /**
     * 实际具名裁决的最小关联，不携带人工证据正文。
     * @author owlzhangfq@gmail.com
     */
    public record Resolution(UUID id, ExpensePartialAdjustmentPreparation.Side side, UUID operationId, long beforeVersion, long afterVersion, ExpensePartialDisputeResolution.Outcome outcome, Instant resolvedAt) {
        static Resolution of(ExpensePartialDisputeResolution v) { return new Resolution(v.id(), v.side(), v.operationId(), v.beforeVersion(), v.afterVersion(), v.outcome(), v.resolvedAt()); }
    }
}
