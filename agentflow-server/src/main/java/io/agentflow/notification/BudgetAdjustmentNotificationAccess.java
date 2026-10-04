package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.budget.*;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原预算消息由真实批准修订、原财务事实和当前原轮次权限共同约束。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcBudgetAdjustmentReviewRepository reviews;
    private final JdbcBudgetAdjustmentOperationRepository operations;
    private final ApplicationRepository applications;
    private final BudgetAdjustmentFinanceAccess access;
    private final PaymentNotificationAccess personnel;

    /** 访问只读取已有来源，不触发台账读取、执行或原号恢复。 */
    public BudgetAdjustmentNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, JsonUtil json, JdbcBudgetAdjustmentReviewRepository reviews,
            JdbcBudgetAdjustmentOperationRepository operations, ApplicationRepository applications, BudgetAdjustmentFinanceAccess access, PaymentNotificationAccess personnel) {
        this.actors = actors; this.jdbc = jdbc; this.json = json; this.reviews = reviews; this.operations = operations;
        this.applications = applications; this.access = access; this.personnel = personnel;
    }
    /** 本人原消息固定原来源，财务读取不因管理员角色而获得额外字段权限。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID id) {
        var actor = actors.actor(); var row = row(actor.tenantId(), actor.userId(), id); var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null) throw new DomainException("NOT_FOUND", "Original budget adjustment notification is unavailable in the current scope");
        var original = source.approved(); var round = original.round().roundNo();
        if (actor.userId().equals(original.employeeId())) access.read(original.requestId(), round);
        else access.requireFinance(original.requestId(), round);
        var key = BudgetAdjustmentNotice.source(row.eventKey()).orElseThrow();
        return new Target(id, original.requestId(), original.applicationId(), round, key.type(), key.id(), key.notice(),
                source.review() == null ? null : Review.of(source.review()), source.operation() == null ? null : Operation.of(source.operation()),
                source.retirement() == null ? null : Retirement.of(source.retirement()));
    }
    /** 外发最小消息前重新检查原参与关系及当前法人内人员资格。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }
    Source original(String tenant, BudgetAdjustmentNotice.Source key) {
        BudgetAdjustmentReview review = null; BudgetAdjustmentOperation operation = null; BudgetAdjustmentRetirement retirement = null;
        List<BudgetAdjustmentOperation> history = List.of(); ApprovedBudgetAdjustment approved; String finance;
        if (key.type() == BudgetAdjustmentNotice.SourceType.REVIEW) {
            review = reviews.find(tenant, key.id()).orElse(null);
            if (review == null || BudgetAdjustmentNotice.from(review).filter(value -> value == key.notice()).isEmpty()) return null;
            approved = review.input().source(); finance = review.input().requestedBy();
        } else {
            operation = operations.find(tenant, key.id()).orElse(null); if (operation == null) return null;
            approved = operation.command().source(); finance = operation.command().authorizedBy(); history = operations.history(tenant, key.id());
            var command = operation.command(); if (history.stream().anyMatch(value -> !value.command().equals(command))) return null;
            retirement = operations.retirement(tenant, key.id()).orElse(null);
            if (key.notice() == BudgetAdjustmentNotice.RETIRED ? retirement == null
                    : history.stream().noneMatch(value -> BudgetAdjustmentNotice.from(value).filter(fact -> fact == key.notice()).isPresent())) return null;
        }
        // 历史批准修订证明原来源；后来的申请内容或最新指令不能替换它。
        var snapshots = jdbc.query("SELECT state_json FROM budget_adjustment_revision WHERE tenant_id=? AND request_id=? AND request_version=?",
                (row, index) -> BudgetAdjustmentRequest.restore(json.read(row.getString("state_json"), BudgetAdjustmentRequest.State.class)), tenant, approved.requestId().toString(), approved.approvedRequestVersion());
        if (snapshots.size() != 1 || !ApprovedBudgetAdjustment.from(snapshots.get(0)).equals(approved)) return null;
        var application = applications.findById(tenant, approved.applicationId()).orElse(null);
        if (application == null || !application.createdBy().equals(approved.employeeId()) || application.businessReference() == null
                || application.businessReference().type() != BusinessReference.Type.BUDGET_ADJUSTMENT || !application.businessReference().id().equals(approved.requestId())) return null;
        var recipients = new ArrayList<>(List.of(approved.employeeId(), finance));
        if (key.notice() == BudgetAdjustmentNotice.RETIRED) recipients.add(retirement.retiredBy());
        return new Source(application, approved, review, operation, retirement, history, recipients.stream().distinct().toList());
    }
    boolean eligible(String tenant, String recipient, Source source) {
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, source.approved().employeeId(), source.approved().round().content().legalEntityId());
    }
    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = BudgetAdjustmentNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key);
        return source != null && source.application().id().equals(row.applicationId()) && source.approved().round().roundNo() == row.roundNo()
                && recordedAt(source, key.notice(), row.createdAt()) && eligible(tenant, recipient, source) ? source : null;
    }
    private boolean recordedAt(Source source, BudgetAdjustmentNotice fact, Instant at) {
        if (source.review() != null) return source.review().updatedAt().equals(at);
        if (fact == BudgetAdjustmentNotice.RETIRED) return source.retirement() != null && source.retirement().retiredAt().equals(at);
        return source.history().stream().anyMatch(value -> value.updatedAt().equals(at) && BudgetAdjustmentNotice.from(value).filter(notice -> notice == fact).isPresent());
    }
    private Row row(String tenant, String recipient, UUID id) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no,created_at FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('BUDGET_ADJUSTMENT_RESULT','BUDGET_ADJUSTMENT_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")), UUID.fromString(row.getString("application_id")), row.getInt("round_no"), row.getTimestamp("created_at").toInstant()),
                tenant, recipient, id.toString()).stream().findFirst().orElse(null);
    }
    /**
     * 完整台账和人员只用于内部证明，不进入消息详情。
     * @author owlzhangfq@gmail.com
     */
    record Source(Application application, ApprovedBudgetAdjustment approved, BudgetAdjustmentReview review, BudgetAdjustmentOperation operation,
                  BudgetAdjustmentRetirement retirement, List<BudgetAdjustmentOperation> history, List<String> recipients) { }
    /**
     * 持久消息发生时刻必须能在原事实中找到，不能伪装成其他结果。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo, Instant createdAt) { }
    /**
     * 原复核与原指令只读投影，不包含预算明细或操作许可。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(UUID messageId, UUID requestId, UUID applicationId, int roundNo, BudgetAdjustmentNotice.SourceType sourceType, UUID sourceId,
                         BudgetAdjustmentNotice fact, Review review, Operation operation, Retirement retirement) { }
    /**
     * 失败或停止的原台账读取，不能关联后一次成功授权。
     * @author owlzhangfq@gmail.com
     */
    public record Review(UUID id, long version, BudgetAdjustmentReview.Status status, Instant requestedAt, Instant updatedAt, BudgetAdjustmentReview.Issue issue) {
        static Review of(BudgetAdjustmentReview value) { return new Review(value.input().id(), value.version(), value.status(), value.input().requestedAt(), value.updatedAt(), value.issue()); }
    }
    /**
     * 原指令的当前状态，原观察与冲突观察分别保留。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(UUID id, long version, BudgetAdjustmentOperation.Status status, Instant createdAt, Instant updatedAt, BudgetAdjustmentOperation.Failure failure,
                            Observation observation, Observation conflictingObservation) {
        static Operation of(BudgetAdjustmentOperation value) { return new Operation(value.command().id(), value.version(), value.status(), value.createdAt(), value.updatedAt(), value.failure(), Observation.of(value.observation()), Observation.of(value.conflictingObservation())); }
    }
    /**
     * 回执只保留结果及时间，不外发额度、台账版本标识或外部引用。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Observation(BudgetAdjustmentObservation.Status outcome, long revision, Instant observedAt, Instant appliedAt, BudgetAdjustmentObservation.Rejection rejection) {
        static Observation of(BudgetAdjustmentObservation value) { return value == null ? null : new Observation(value.status(), value.revision(), value.observedAt(), value.appliedAt(), value.rejection()); }
    }
    /**
     * 安全结束引用原指令精确修订，不等同于新的财务授权。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(UUID operationId, long operationVersion, BudgetAdjustmentRetirement.Basis basis, Instant retiredAt) {
        static Retirement of(BudgetAdjustmentRetirement value) { return new Retirement(value.operationId(), value.operationVersion(), value.basis(), value.retiredAt()); }
    }
}
