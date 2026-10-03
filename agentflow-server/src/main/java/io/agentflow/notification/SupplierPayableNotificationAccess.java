package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.procurement.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原应付消息沿精确历史批准与命令读取，复核材料不因新授权或管理员身份扩大范围。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPayableNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSupplierPayableReviewRepository reviews;
    private final JdbcSupplierPayableHoldRepository operations;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final ApplicationRepository applications;
    private final SupplierPaymentAccess access;
    private final SupplierPaymentNotificationAccess personnel;

    /** 原历史只供身份与事实核对，页面不接收 ERP 余额、账户、命令或参与人名单。 */
    public SupplierPayableNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, JsonUtil json, JdbcSupplierPayableReviewRepository reviews,
            JdbcSupplierPayableHoldRepository operations, JdbcSupplierPaymentAuthorizationRepository authorizations,
            ApplicationRepository applications, SupplierPaymentAccess access, SupplierPaymentNotificationAccess personnel) {
        this.actors = actors; this.jdbc = jdbc; this.json = json; this.reviews = reviews; this.operations = operations;
        this.authorizations = authorizations; this.applications = applications; this.access = access; this.personnel = personnel;
    }
    /** 本人消息固定原轮次，非申请人仍须通过当前独立财务和原文权限。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID id) {
        var actor = actors.actor(); var row = row(actor.tenantId(), actor.userId(), id); var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null) throw new DomainException("NOT_FOUND", "Original supplier payable notification is unavailable in the current scope");
        var original = source.approved().reservation().source(); var round = original.round().roundNo();
        if (actor.userId().equals(original.employeeId())) access.read(original.requestId(), round);
        else access.requireFinance(original.requestId(), round);
        var key = SupplierPayableNotice.source(row.eventKey()).orElseThrow();
        return new Target(id, original.requestId(), original.applicationId(), round, key.type(), key.id(), key.notice(),
                source.review() == null ? null : Review.of(source.review()), source.operation() == null ? null : Operation.of(source.operation()),
                source.retirement() == null ? null : Retirement.of(source.retirement()));
    }
    /** 发送最小提示前重新检查原参与关系和当前原法人有效人员，不授予明细读取权。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }
    Source original(String tenant, SupplierPayableNotice.Source key) {
        SupplierPayableReview review = null; SupplierPayableHoldOperation operation = null; SupplierAuthorizationRetirement retirement = null;
        List<SupplierPayableReview> reviewHistory = List.of(); List<SupplierPayableHoldOperation> history = List.of();
        ApprovedProcurementPayment approved; var recipients = new ArrayList<String>();
        if (key.type() == SupplierPayableNotice.SourceType.REVIEW) {
            review = reviews.find(tenant, key.id()).orElse(null); if (review == null) return null;
            var input = review.input(); approved = input.source(); recipients.add(input.requestedBy());
            reviewHistory = jdbc.query("SELECT state_json FROM supplier_payable_review_revision WHERE tenant_id=? AND review_id=? ORDER BY version",
                    (row, index) -> json.read(row.getString("state_json"), SupplierPayableReview.class), tenant, key.id().toString());
            if (reviewHistory.stream().anyMatch(value -> !value.input().equals(input))
                    || reviewHistory.stream().noneMatch(value -> SupplierPayableNotice.from(value).filter(fact -> fact == key.notice()).isPresent())) return null;
        } else {
            operation = operations.find(tenant, key.id()).orElse(null); if (operation == null) return null;
            var command = operation.command(); var authorization = authorizations.find(tenant, key.id()).orElse(null);
            if (!command.authorization().equals(authorization)) return null;
            approved = authorization.source(); recipients.add(approved.reservation().source().employeeId()); recipients.add(authorization.authorizedBy());
            history = jdbc.query("SELECT state_json FROM supplier_payable_hold_revision WHERE tenant_id=? AND operation_id=? ORDER BY version",
                    (row, index) -> json.read(row.getString("state_json"), SupplierPayableHoldOperation.class), tenant, key.id().toString());
            if (history.stream().anyMatch(value -> !value.command().equals(command))) return null;
            retirement = authorizations.retirement(tenant, key.id()).orElse(null);
            if (key.notice() == SupplierPayableNotice.RETIRED) {
                if (retirement == null) return null; recipients.add(retirement.retiredBy());
            } else if (history.stream().noneMatch(value -> SupplierPayableNotice.from(value).filter(fact -> fact == key.notice()).isPresent())) return null;
        }
        var source = approved.reservation().source();
        // 当前申请可以继续演进；原批准修订和原占用才证明这次通知的业务绑定。
        var snapshots = jdbc.query("SELECT state_json FROM procurement_payment_revision WHERE tenant_id=? AND request_id=? AND request_version=?",
                (row, index) -> ProcurementPaymentRequest.restore(json.read(row.getString("state_json"), ProcurementPaymentRequest.State.class)), tenant, source.requestId().toString(), approved.approvedRequestVersion());
        if (snapshots.size() != 1 || !ApprovedProcurementPayment.from(snapshots.get(0), approved.reservation()).equals(approved)) return null;
        var application = applications.findById(tenant, source.applicationId()).orElse(null);
        if (application == null || !application.createdBy().equals(source.employeeId()) || application.businessReference() == null
                || application.businessReference().type() != BusinessReference.Type.PROCUREMENT_PAYMENT || !application.businessReference().id().equals(source.requestId())) return null;
        return new Source(application, approved, review, operation, retirement, reviewHistory, history, recipients.stream().distinct().toList());
    }
    boolean eligible(String tenant, String recipient, Source source) {
        var original = source.approved().reservation().source();
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, original.employeeId(), original.round().content().legalEntityId());
    }
    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = SupplierPayableNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key); if (source == null) return null;
        var original = source.approved().reservation().source();
        return source.application().id().equals(row.applicationId()) && original.round().roundNo() == row.roundNo()
                && recordedAt(source, key.notice(), row.createdAt()) && eligible(tenant, recipient, source) ? source : null;
    }
    private boolean recordedAt(Source source, SupplierPayableNotice fact, Instant at) {
        if (source.review() != null) return source.reviewHistory().stream().anyMatch(value -> value.updatedAt().equals(at) && SupplierPayableNotice.from(value).filter(notice -> notice == fact).isPresent());
        if (fact == SupplierPayableNotice.RETIRED) return source.retirement() != null && source.retirement().retiredAt().equals(at);
        return source.history().stream().anyMatch(value -> value.updatedAt().equals(at) && SupplierPayableNotice.from(value).filter(notice -> notice == fact).isPresent());
    }
    private Row row(String tenant, String recipient, UUID id) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no,created_at FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('SUPPLIER_PAYABLE_RESULT','SUPPLIER_PAYABLE_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")), UUID.fromString(row.getString("application_id")), row.getInt("round_no"), row.getTimestamp("created_at").toInstant()),
                tenant, recipient, id.toString()).stream().findFirst().orElse(null);
    }
    /**
     * 来源及历史只在服务内证明事实，参与人和完整快照不序列化。
     * @author owlzhangfq@gmail.com
     */
    record Source(Application application, ApprovedProcurementPayment approved, SupplierPayableReview review, SupplierPayableHoldOperation operation,
                  SupplierAuthorizationRetirement retirement, List<SupplierPayableReview> reviewHistory, List<SupplierPayableHoldOperation> history, List<String> recipients) { }
    /**
     * 持久消息发生时刻必须来自原事实修订。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo, Instant createdAt) { }
    /**
     * 当前原记录只读摘要，不包含金额、账户、ERP 引用或办理动作。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(UUID messageId, UUID requestId, UUID applicationId, int roundNo, SupplierPayableNotice.SourceType sourceType, UUID sourceId,
                         SupplierPayableNotice fact, Review review, Operation operation, Retirement retirement) { }
    /**
     * 原请求在读取中断后可继续恢复，仍保留同一个复核编号。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Review(UUID id, long version, SupplierPayableReview.Status status, Instant requestedAt, Instant updatedAt, SupplierPayableReview.Issue issue) {
        static Review of(SupplierPayableReview value) { return new Review(value.input().id(), value.version(), value.status(), value.input().requestedAt(), value.updatedAt(), value.issue()); }
    }
    /**
     * 预留当前状态和相互矛盾的观察分别展示。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(UUID id, long version, SupplierPayableHoldOperation.Status status, Instant createdAt, Instant updatedAt, SupplierPayableHoldOperation.Failure failure,
                            Observation observation, Observation conflictingObservation) {
        static Operation of(SupplierPayableHoldOperation value) { return new Operation(value.command().id(), value.version(), value.status(), value.createdAt(), value.updatedAt(), value.failure(), Observation.of(value.observation()), Observation.of(value.conflictingObservation())); }
    }
    /**
     * 只展示观察结论和时间，预留号、余额及账户摘要保持在原财务存储。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Observation(SupplierPayableHoldObservation.Status outcome, long revision, Instant observedAt, Instant heldAt, SupplierPayableHoldObservation.Rejection rejection) {
        static Observation of(SupplierPayableHoldObservation value) { return value == null ? null : new Observation(value.status(), value.revision(), value.observedAt(), value.heldAt(), value.rejection()); }
    }
    /**
     * 实际结束绑定原预留修订，不代表外部预留已经释放或新授权已经签发。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(UUID operationId, long operationVersion, SupplierPayableHoldOperation.RetirementBasis basis, Instant retiredAt) {
        static Retirement of(SupplierAuthorizationRetirement value) { return new Retirement(value.authorizationId(), value.operationVersion(), value.basis(), value.retiredAt()); }
    }
}
