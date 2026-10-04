package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRepaymentSources;
import io.agentflow.expense.JdbcAdvanceRepaymentCheckRepository;
import io.agentflow.expense.JdbcAdvanceRepaymentRepository;
import io.agentflow.expense.AdvanceRepaymentCheck;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.VoucherAccess;
import io.agentflow.finance.PaymentAuthorization;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原还款消息只读精确查询和实际登记，当前权限与原字段保护独立复核。
 * @author owlzhangfq@gmail.com
 */
@Service
public class RepaymentNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final JdbcAdvanceRepaymentCheckRepository checks;
    private final JdbcAdvanceRepaymentRepository repayments;
    private final AdvanceRepaymentSources sources;
    private final ApplicationRepository applications;
    private final VoucherAccess access;
    private final PaymentNotificationAccess personnel;
    /** 身份来自持久原放款及原查询，不根据最新登记或管理员角色推测。 */
    public RepaymentNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, JdbcAdvanceRepaymentCheckRepository checks,
            JdbcAdvanceRepaymentRepository repayments, AdvanceRepaymentSources sources, ApplicationRepository applications,
            VoucherAccess access, PaymentNotificationAccess personnel) {
        this.actors = actors; this.jdbc = jdbc; this.checks = checks; this.repayments = repayments; this.sources = sources;
        this.applications = applications; this.access = access; this.personnel = personnel;
    }
    /** 只读取消息原来源；不查询银行、不续期证据、不登记还款或修改借款。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var row = row(actor.tenantId(), actor.userId(), messageId); var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null) throw new DomainException("NOT_FOUND", "Original repayment notification is unavailable in the current scope");
        var check = source.check(); var input = check.input(); var binding = source.authorization().terms().binding();
        if (actor.userId().equals(input.request().employeeId())) access.read(binding.applicationId(), binding.roundNo());
        else access.requireFinance(binding.applicationId(), binding.roundNo());
        var observed = check.receipt(); var recorded = source.recorded();
        return new Target(messageId, input.id(), input.paymentId(), input.request().advanceId(), binding.applicationId(), binding.roundNo(),
                RepaymentNotice.source(row.eventKey()).orElseThrow().notice(), check.version(), check.status(), input.requestedAt(), check.updatedAt(), check.issue(), check.reviewRepaymentId(),
                observed == null ? null : new Observation(observed.status(), observed.revision(), observed.observedAt(), observed.validUntil()),
                recorded == null ? null : new Registration(recorded.repayment().id(), recorded.advanceVersion(), recorded.repayment().recordedAt()));
    }
    /** 外发最小消息之前复核原参与关系与当前人员和法人资格。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }
    Source original(String tenant, UUID id) {
        var check = checks.find(tenant, id).orElse(null); if (check == null) return null;
        var input = check.input(); var request = input.request(); AdvanceRepaymentSources.Source bank;
        try { bank = sources.find(tenant, request.advanceId()); sources.requireCheck(check, bank); } catch (DomainException changed) { return null; }
        var binding = bank.authorization().terms().binding(); var application = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (application == null || !application.createdBy().equals(request.employeeId()) || application.businessReference() == null
                || application.businessReference().type() != BusinessReference.Type.ADVANCE_REQUEST || !application.businessReference().id().equals(request.advanceId())) return null;
        var recorded = check.repaymentId() == null ? null : repayments.findRecorded(tenant, check.repaymentId()).orElse(null);
        if ((check.status() == AdvanceRepaymentCheck.Status.RECORDED) != (recorded != null)) return null;
        if (recorded != null) {
            var value = recorded.repayment();
            if (!value.checkId().equals(id) || !value.id().equals(check.repaymentId()) || !value.receipt().equals(check.receipt())
                    || !value.recordedBy().equals(input.requestedBy()) || !value.recordedAt().equals(check.updatedAt())) return null;
        }
        // 触发复核的是既有原还款，历史消息不会根据当前冻结或最新裁决推测关联。
        if (check.reviewRepaymentId() != null) {
            var original = repayments.findRecorded(tenant, check.reviewRepaymentId()).orElse(null);
            if (original == null || !original.repayment().receipt().request().equals(request) || original.repayment().recordedAt().isAfter(check.updatedAt())) return null;
        }
        return new Source(application, check, recorded, bank.authorization(), request.legalEntityId(), List.of(request.employeeId(), input.requestedBy()).stream().distinct().toList());
    }
    boolean eligible(String tenant, String recipient, Source source) {
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, source.check().input().request().employeeId(), source.entity());
    }
    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = RepaymentNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.checkId());
        return source != null && source.application().id().equals(row.applicationId())
                && source.authorization().terms().binding().roundNo() == row.roundNo()
                && key.notice().presentIn(source.check()) && eligible(tenant, recipient, source) ? source : null;
    }
    private Row row(String tenant, String recipient, UUID id) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('REPAYMENT_RESULT','REPAYMENT_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")), UUID.fromString(row.getString("application_id")), row.getInt("round_no")),
                tenant, recipient, id.toString()).stream().findFirst().orElse(null);
    }
    /**
     * 真实参与人和原件仅用于服务端核对，不公开资金命令。
     * @author owlzhangfq@gmail.com
     */
    record Source(Application application, AdvanceRepaymentCheck check, JdbcAdvanceRepaymentRepository.Recorded recorded, PaymentAuthorization authorization, UUID entity, List<String> recipients) { }
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
    public record Target(UUID messageId, UUID checkId, UUID paymentId, UUID advanceId, UUID applicationId, int roundNo, RepaymentNotice fact,
                         long version, AdvanceRepaymentCheck.Status status, Instant requestedAt, Instant updatedAt, AdvanceRepaymentCheck.Issue issue, UUID reviewRepaymentId,
                         Observation observation, Registration record) { }
    /**
     * 原件版本和当时有效窗口只用于追溯，不提供新鲜性或登记许可。
     * @author owlzhangfq@gmail.com
     */
    public record Observation(AdvanceRepaymentPort.Status outcome, long revision, Instant observedAt, Instant validUntil) { }
    /**
     * 本次查询实际消费的还款登记，不能替换成当前累计账本版本。
     * @author owlzhangfq@gmail.com
     */
    public record Registration(UUID id, long advanceVersion, Instant recordedAt) { }
}
