package io.agentflow.notification;


import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.SupplierReturnNotificationAccessMapper;
import io.agentflow.procurement.JdbcSupplierPaymentReturnCheckRepository;
import io.agentflow.procurement.JdbcSupplierPaymentReturnRepository;
import io.agentflow.procurement.SupplierPaymentReturnCheck;
import io.agentflow.procurement.SupplierPaymentReturnPort;
import io.agentflow.procurement.SupplierPaymentReturnSources;
import io.agentflow.procurement.SupplierSettlementAccess;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 原回款消息只读精确查询和实际登记，当前权限与原字段保护独立复核。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierReturnNotificationAccess {
    private final CurrentActor actors;
    private final SupplierReturnNotificationAccessMapper sqlMapper;
    private final JdbcSupplierPaymentReturnCheckRepository checks;
    private final JdbcSupplierPaymentReturnRepository registrations;
    private final SupplierPaymentReturnSources sources;
    private final ApplicationRepository applications;
    private final SupplierSettlementAccess access;
    private final SupplierPaymentNotificationAccess personnel;

    /** 身份来自持久原银行及原查询，不根据最新登记或管理员角色推测。 */
    public SupplierReturnNotificationAccess(
            CurrentActor actors,
            SupplierReturnNotificationAccessMapper sqlMapper,
            JdbcSupplierPaymentReturnCheckRepository checks,
            JdbcSupplierPaymentReturnRepository registrations,
            SupplierPaymentReturnSources sources,
            ApplicationRepository applications,
            SupplierSettlementAccess access,
            SupplierPaymentNotificationAccess personnel) {
        this.actors = actors;
        this.sqlMapper = sqlMapper;
        this.checks = checks;
        this.registrations = registrations;
        this.sources = sources;
        this.applications = applications;
        this.access = access;
        this.personnel = personnel;
    }

    /** 只读取消息原来源；不查询银行、不续期证据、不登记资金或调整 ERP。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var row = row(actor.tenantId(), actor.userId(), messageId); var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null) throw new DomainException("NOT_FOUND", "Original supplier return notification is unavailable in the current scope");
        var check = source.check(); var input = check.input(); var payment = input.request().command(); var binding = payment.holdCommand().authorization().source().reservation().source();
        access.read(payment.id()); var observed = check.receipt(); var registration = source.registration();
        return new Target(messageId, input.id(), payment.id(), binding.requestId(), binding.applicationId(), binding.round().roundNo(),
                SupplierReturnNotice.source(row.eventKey()).orElseThrow().notice(), check.version(), check.status(), input.requestedAt(), check.updatedAt(), check.issue(),
                observed == null ? null : new Observation(observed.status(), observed.revision(), observed.observedAt(), observed.validUntil()),
                registration == null ? null : new Registration(registration.decision().id(), registration.returnVersion(), registration.decision().receipt().status(), registration.decision().registeredAt()));
    }

    /** 外发最小消息之前复核原参与关系与当前人员和法人资格。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }

    Source original(String tenant, UUID id) {
        var check = checks.find(tenant, id).orElse(null); if (check == null) return null;
        var input = check.input(); var payment = input.request().command(); var binding = payment.holdCommand().authorization().source().reservation().source();
        var bank = sources.find(tenant, payment.id()).orElse(null); var application = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (bank == null || !bank.request().equals(input.request()) || bank.paymentVersion() != input.paymentVersion() || application == null || !application.createdBy().equals(binding.employeeId())
                || application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.PROCUREMENT_PAYMENT
                || !application.businessReference().id().equals(binding.requestId())) return null;
        // 决定必须实际消费本次查询；同笔付款后来的登记不能挂到这条旧消息上。
        var registration = check.resolutionId() == null ? null : registrations.find(tenant, check.resolutionId()).orElse(null);
        if ((check.status() == SupplierPaymentReturnCheck.Status.RESOLVED) != (registration != null)) return null;
        if (registration != null) {
            var decision = registration.decision();
            if (!decision.checkId().equals(id) || !decision.id().equals(check.resolutionId()) || !decision.receipt().equals(check.receipt())
                    || !decision.registeredBy().equals(input.requestedBy()) || !decision.registeredAt().equals(check.updatedAt())) return null;
        }
        return new Source(application, check, registration, binding.round().content().legalEntityId(),
                List.of(binding.employeeId(), input.requestedBy()).stream().distinct().toList());
    }

    boolean eligible(String tenant, String recipient, Source source) {
        var applicant = source.check().input().request().command().holdCommand().authorization().source().reservation().source().employeeId();
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, applicant, source.entity());
    }

    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = SupplierReturnNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.checkId());
        return source != null && source.application().id().equals(row.applicationId())
                && source.check().input().request().command().holdCommand().authorization().source().reservation().source().round().roundNo() == row.roundNo()
                && key.notice().presentIn(source.check()) && eligible(tenant, recipient, source) ? source : null;
    }

    private Row row(String tenant, String recipient, UUID id) {
        return SqlRows.map(
                        sqlMapper.row(tenant, recipient, id.toString()),
                        row ->
                                new Row(
                                        row.getString("event_key"),
                                        InboxMessage.Kind.valueOf(row.getString("kind")),
                                        UUID.fromString(row.getString("application_id")),
                                        row.getInt("round_no")))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 真实参与人和原件仅用于服务端核对，不公开资金命令。
     *
     * @author owlzhangfq@gmail.com
     */
    record Source(
            Application application,
            SupplierPaymentReturnCheck check,
            JdbcSupplierPaymentReturnRepository.Registered registration,
            UUID entity,
            List<String> recipients) {}

    /**
     * 仅定位当前接收人的持久消息。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) {}

    /**
     * 原查询状态与真实登记分开，金额、公司账户、入款流水及办理许可均不返回。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(
            UUID messageId,
            UUID checkId,
            UUID paymentId,
            UUID requestId,
            UUID applicationId,
            int roundNo,
            SupplierReturnNotice fact,
            long version,
            SupplierPaymentReturnCheck.Status status,
            Instant requestedAt,
            Instant updatedAt,
            SupplierPaymentReturnCheck.Issue issue,
            Observation observation,
            Registration registration) {}

    /**
     * 原件版本和当时有效窗口只用于追溯，不提供新鲜性或登记许可。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Observation(
            SupplierPaymentReturnPort.Status outcome,
            long revision,
            Instant observedAt,
            Instant validUntil) {}

    /**
     * 本次查询实际消费的具名决定，不能替换成当前累计账本版本。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Registration(
            UUID id,
            long returnVersion,
            SupplierPaymentReturnPort.Status outcome,
            Instant registeredAt) {}
}
