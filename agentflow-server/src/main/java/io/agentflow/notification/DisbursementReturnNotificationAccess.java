package io.agentflow.notification;


import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceDisbursementReturnCheck;
import io.agentflow.expense.AdvanceDisbursementReturnSources;
import io.agentflow.expense.JdbcDisbursementResolutionRepository;
import io.agentflow.expense.JdbcDisbursementReturnCheckRepository;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import io.agentflow.finance.VoucherAccess;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.DisbursementReturnNotificationAccessMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 原退回消息只读精确查询和实际登记，当前权限与原字段保护独立复核。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class DisbursementReturnNotificationAccess {
    private final CurrentActor actors;
    private final DisbursementReturnNotificationAccessMapper sqlMapper;
    private final JdbcDisbursementReturnCheckRepository checks;
    private final JdbcDisbursementResolutionRepository resolutions;
    private final AdvanceDisbursementReturnSources sources;
    private final ApplicationRepository applications;
    private final VoucherAccess access;
    private final PaymentNotificationAccess personnel;

    /** 身份来自持久原银行及原查询，不根据最新登记或管理员角色推测。 */
    public DisbursementReturnNotificationAccess(
            CurrentActor actors,
            DisbursementReturnNotificationAccessMapper sqlMapper,
            JdbcDisbursementReturnCheckRepository checks,
            JdbcDisbursementResolutionRepository resolutions,
            AdvanceDisbursementReturnSources sources,
            ApplicationRepository applications,
            VoucherAccess access,
            PaymentNotificationAccess personnel) {
        this.actors = actors;
        this.sqlMapper = sqlMapper;
        this.checks = checks;
        this.resolutions = resolutions;
        this.sources = sources;
        this.applications = applications;
        this.access = access;
        this.personnel = personnel;
    }

    /** 只读取消息原来源；不查询银行、不续期证据、不登记资金或修改借款。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor();
        var row = row(actor.tenantId(), actor.userId(), messageId);
        var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null)
            throw new DomainException(
                    "NOT_FOUND",
                    "Original disbursement return notification is unavailable in the current"
                        + " scope");
        var check = source.check();
        var input = check.input();
        var payment = input.request().command();
        var binding = payment.binding();
        if (actor.userId().equals(payment.payee().employeeId()))
            access.read(binding.applicationId(), binding.roundNo());
        else access.requireFinance(binding.applicationId(), binding.roundNo());
        var observed = check.receipt();
        var resolution = source.resolution();
        return new Target(
                messageId,
                input.id(),
                payment.id(),
                binding.businessId(),
                binding.applicationId(),
                binding.roundNo(),
                DisbursementReturnNotice.source(row.eventKey()).orElseThrow().notice(),
                check.version(),
                check.status(),
                input.requestedAt(),
                check.updatedAt(),
                check.issue(),
                observed == null
                        ? null
                        : new Observation(
                                observed.status(),
                                observed.revision(),
                                observed.observedAt(),
                                observed.validUntil()),
                resolution == null
                        ? null
                        : new Resolution(
                                resolution.decision().id(),
                                resolution.advanceVersion(),
                                resolution.decision().receipt().status(),
                                resolution.decision().resolvedAt()));
    }

    /** 外发最小消息之前复核原参与关系与当前人员和法人资格。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }

    Source original(String tenant, UUID id) {
        var check = checks.find(tenant, id).orElse(null); if (check == null) return null;
        var input = check.input(); var payment = input.request().command(); var binding = payment.binding();
        AdvanceDisbursementReturnSources.Source bank;
        try { bank = sources.find(tenant, binding.businessId()); } catch (DomainException changed) { return null; }
        var application = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (!bank.request().equals(input.request()) || bank.paymentVersion() != input.paymentVersion() || !bank.funding().authorization().terms().targetDigest().equals(input.targetDigest()) || application == null || !application.createdBy().equals(payment.payee().employeeId())
                || application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.ADVANCE_REQUEST
                || !application.businessReference().id().equals(binding.businessId())) return null;
        // 决定必须实际消费本次查询；同笔付款后来的登记不能挂到这条旧消息上。
        var resolution = check.resolutionId() == null ? null : resolutions.find(tenant, check.resolutionId()).orElse(null);
        if ((check.status() == AdvanceDisbursementReturnCheck.Status.RESOLVED) != (resolution != null)) return null;
        if (resolution != null) {
            var decision = resolution.decision();
            if (!decision.checkId().equals(id) || !decision.id().equals(check.resolutionId()) || !decision.receipt().equals(check.receipt())
                    || !decision.resolvedBy().equals(input.requestedBy()) || !decision.resolvedAt().equals(check.updatedAt())) return null;
        }
        return new Source(application, check, resolution, payment.payee().legalEntityId(),
                List.of(payment.payee().employeeId(), input.requestedBy()).stream().distinct().toList());
    }

    boolean eligible(String tenant, String recipient, Source source) {
        var applicant = source.check().input().request().command().payee().employeeId();
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, applicant, source.entity());
    }

    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = DisbursementReturnNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.checkId());
        return source != null && source.application().id().equals(row.applicationId())
                && source.check().input().request().command().binding().roundNo() == row.roundNo()
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
            AdvanceDisbursementReturnCheck check,
            JdbcDisbursementResolutionRepository.Resolved resolution,
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
            UUID advanceId,
            UUID applicationId,
            int roundNo,
            DisbursementReturnNotice fact,
            long version,
            AdvanceDisbursementReturnCheck.Status status,
            Instant requestedAt,
            Instant updatedAt,
            AdvanceDisbursementReturnCheck.Issue issue,
            Observation observation,
            Resolution resolution) {}

    /**
     * 原件版本和当时有效窗口只用于追溯，不提供新鲜性或登记许可。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Observation(
            AdvanceDisbursementReturnPort.Status outcome,
            long revision,
            Instant observedAt,
            Instant validUntil) {}

    /**
     * 本次查询实际消费的具名决定，不能替换成当前累计账本版本。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Resolution(
            UUID id,
            long advanceVersion,
            AdvanceDisbursementReturnPort.Status outcome,
            Instant resolvedAt) {}
}
