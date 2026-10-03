package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRepaymentSources;
import io.agentflow.expense.JdbcRepaymentReviewCheckRepository;
import io.agentflow.expense.JdbcRepaymentResolutionRepository;
import io.agentflow.expense.AdvanceRepaymentReviewCheck;
import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import io.agentflow.finance.VoucherAccess;
import io.agentflow.finance.PaymentAuthorization;
import io.agentflow.expense.JdbcAdvanceRepaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原还款复核消息只读精确查询和实际裁决，当前权限与原字段保护独立复核。
 * @author owlzhangfq@gmail.com
 */
@Service
public class RepaymentReviewNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final JdbcRepaymentReviewCheckRepository checks;
    private final JdbcRepaymentResolutionRepository resolutions;
    private final AdvanceRepaymentSources sources;
    private final JdbcAdvanceRepaymentRepository repayments;
    private final ApplicationRepository applications;
    private final VoucherAccess access;
    private final PaymentNotificationAccess personnel;
    /** 身份来自原放款、已登记还款及原复核查询，不根据最新登记或管理员角色推测。 */
    public RepaymentReviewNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, JdbcRepaymentReviewCheckRepository checks,
            JdbcRepaymentResolutionRepository resolutions, AdvanceRepaymentSources sources, JdbcAdvanceRepaymentRepository repayments, ApplicationRepository applications,
            VoucherAccess access, PaymentNotificationAccess personnel) {
        this.actors = actors; this.jdbc = jdbc; this.checks = checks; this.resolutions = resolutions; this.sources = sources;
        this.repayments = repayments; this.applications = applications; this.access = access; this.personnel = personnel;
    }
    /** 只读取消息原来源；不查询银行、不续期证据、不登记资金或修改借款。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var row = row(actor.tenantId(), actor.userId(), messageId); var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null) throw new DomainException("NOT_FOUND", "Original repayment review notification is unavailable in the current scope");
        var check = source.check(); var input = check.input(); var original = input.request().original().request(); var binding = source.authorization().terms().binding();
        if (actor.userId().equals(original.employeeId())) access.read(binding.applicationId(), binding.roundNo());
        else access.requireFinance(binding.applicationId(), binding.roundNo());
        var observed = check.receipt(); var resolution = source.resolution();
        return new Target(messageId, input.id(), source.authorization().terms().id(), original.advanceId(), input.request().repaymentId(), binding.applicationId(), binding.roundNo(),
                RepaymentReviewNotice.source(row.eventKey()).orElseThrow().notice(), check.version(), check.status(), input.requestedAt(), check.updatedAt(), check.issue(),
                observed == null ? null : new Observation(observed.status(), observed.revision(), observed.observedAt(), observed.validUntil()),
                resolution == null ? null : new Resolution(resolution.decision().id(), resolution.advanceVersion(), resolution.decision().receipt().status(), resolution.decision().resolvedAt()));
    }
    /** 外发最小消息之前复核原参与关系与当前人员和法人资格。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }
    Source original(String tenant, UUID id) {
        var check = checks.find(tenant, id).orElse(null); if (check == null) return null;
        var input = check.input(); var request = input.request().original().request(); AdvanceRepaymentSources.Source bank;
        try { bank = sources.find(tenant, request.advanceId()); } catch (DomainException changed) { return null; }
        var registered = repayments.findRecorded(tenant, input.request().repaymentId()).orElse(null);
        var binding = bank.authorization().terms().binding(); var application = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (registered == null || !registered.repayment().receipt().equals(input.request().original()) || !registered.repayment().belongsTo(bank.advance())
                || !bank.authorization().terms().targetDigest().equals(input.targetDigest()) || application == null || !application.createdBy().equals(request.employeeId())
                || application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.ADVANCE_REQUEST
                || !application.businessReference().id().equals(request.advanceId())) return null;
        // 决定必须实际消费本次查询；同笔还款后来的决定不能挂到这条旧消息上。
        var resolution = check.resolutionId() == null ? null : resolutions.find(tenant, check.resolutionId()).orElse(null);
        if ((check.status() == AdvanceRepaymentReviewCheck.Status.RESOLVED) != (resolution != null)) return null;
        if (resolution != null) {
            var decision = resolution.decision();
            if (!decision.checkId().equals(id) || !decision.id().equals(check.resolutionId()) || !decision.receipt().equals(check.receipt())
                    || !decision.resolvedBy().equals(input.requestedBy()) || !decision.resolvedAt().equals(check.updatedAt())) return null;
        }
        return new Source(application, check, resolution, bank.authorization(), request.legalEntityId(),
                List.of(request.employeeId(), input.requestedBy()).stream().distinct().toList());
    }
    boolean eligible(String tenant, String recipient, Source source) {
        var applicant = source.check().input().request().original().request().employeeId();
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, applicant, source.entity());
    }
    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = RepaymentReviewNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.checkId());
        return source != null && source.application().id().equals(row.applicationId())
                && source.authorization().terms().binding().roundNo() == row.roundNo()
                && key.notice().presentIn(source.check()) && eligible(tenant, recipient, source) ? source : null;
    }
    private Row row(String tenant, String recipient, UUID id) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('REPAYMENT_REVIEW_RESULT','REPAYMENT_REVIEW_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")), UUID.fromString(row.getString("application_id")), row.getInt("round_no")),
                tenant, recipient, id.toString()).stream().findFirst().orElse(null);
    }
    /**
     * 真实参与人和原件仅用于服务端核对，不公开资金命令。
     * @author owlzhangfq@gmail.com
     */
    record Source(Application application, AdvanceRepaymentReviewCheck check, JdbcRepaymentResolutionRepository.Resolved resolution, PaymentAuthorization authorization, UUID entity, List<String> recipients) { }
    /**
     * 仅定位当前接收人的持久消息。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) { }
    /**
     * 原查询状态与真实登记分开，金额、公司账户、入款流水及办理许可均不返回。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(UUID messageId, UUID checkId, UUID paymentId, UUID advanceId, UUID repaymentId, UUID applicationId, int roundNo, RepaymentReviewNotice fact,
                         long version, AdvanceRepaymentReviewCheck.Status status, Instant requestedAt, Instant updatedAt, AdvanceRepaymentReviewCheck.Issue issue,
                         Observation observation, Resolution resolution) { }
    /**
     * 原件版本和当时有效窗口只用于追溯，不提供新鲜性或登记许可。
     * @author owlzhangfq@gmail.com
     */
    public record Observation(AdvanceRepaymentAdjustmentPort.Status outcome, long revision, Instant observedAt, Instant validUntil) { }
    /**
     * 本次查询实际消费的具名决定，不能替换成当前累计账本版本。
     * @author owlzhangfq@gmail.com
     */
    public record Resolution(UUID id, long advanceVersion, AdvanceRepaymentAdjustmentPort.Status outcome, Instant resolvedAt) { }
}
