package io.agentflow.notification;


import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcVoucherReversalOperationRepository;
import io.agentflow.finance.JdbcVoucherReversalPreparationRepository;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.finance.VoucherAccess;
import io.agentflow.finance.VoucherCommand;
import io.agentflow.finance.VoucherOperation;
import io.agentflow.finance.VoucherReversalObservation;
import io.agentflow.finance.VoucherReversalOperation;
import io.agentflow.finance.VoucherReversalPreparation;
import io.agentflow.finance.VoucherReversalRetirement;
import io.agentflow.finance.VoucherReversalSources;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.ReversalNotificationAccessMapper;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 原冲销消息固定准备、命令和安全结束身份；当前原凭证权限决定能否读取摘要。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ReversalNotificationAccess {
    private final CurrentActor actors;
    private final ReversalNotificationAccessMapper sqlMapper;
    private final VoucherNotificationAccess vouchers;
    private final VoucherAccess access;
    private final VoucherReversalSources sources;
    private final JdbcVoucherReversalPreparationRepository preparations;
    private final JdbcVoucherReversalOperationRepository operations;
    private final OrganizationRepository organization;
    private final PaymentPersonnel personnel;

    /** 复用原凭证的不可变业务映射及当前字段读取入口，通知不授予办理权限。 */
    public ReversalNotificationAccess(
            CurrentActor actors,
            ReversalNotificationAccessMapper sqlMapper,
            VoucherNotificationAccess vouchers,
            VoucherAccess access,
            VoucherReversalSources sources,
            JdbcVoucherReversalPreparationRepository preparations,
            JdbcVoucherReversalOperationRepository operations,
            OrganizationRepository organization,
            PaymentPersonnel personnel) {
        this.actors = actors;
        this.sqlMapper = sqlMapper;
        this.vouchers = vouchers;
        this.access = access;
        this.sources = sources;
        this.preparations = preparations;
        this.operations = operations;
        this.organization = organization;
        this.personnel = personnel;
    }

    /** 新准备或新命令不能替换旧消息；读取不消费准备、发送 ERP 或结束冲销。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var source = allowed(actor.tenantId(), actor.userId(), row(actor.tenantId(), actor.userId(), messageId));
        if (source == null) throw new DomainException("NOT_FOUND", "Reversal notification is unavailable in the current scope");
        var original = source.original(); access.read(original.application().id(), original.roundNo());
        var prepared = source.preparation(); var input = prepared.input(); var ended = source.retirement();
        return new Target(messageId, input.id(), original.id(), original.application().id(), original.application().businessReference().id(), original.roundNo(),
                original.kind(), original.operation().status(), original.operation().reversalId() != null,
                new Preparation(input.id(), prepared.version(), prepared.status(), input.requestedAt(), prepared.updatedAt(), input.accountingDate(), prepared.issue()),
                operation(source.operation()), ended == null ? null : new Retirement(ended.id(), ended.retiredAt(), ended.basis()));
    }

    /** 外发前复核原消息与参与关系，原财务参与人还须保留原法人有效任职。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }

    Source original(String tenant, UUID id) {
        var prepared = preparations.find(tenant, id).orElse(null); if (prepared == null) return null;
        var input = prepared.input(); var originalCommand = input.source().command(); var original = vouchers.original(tenant, originalCommand.id());
        if (original == null || original.operation() == null || !original.operation().input().command().equals(originalCommand)) return null;
        var accounting = sources.find(tenant, originalCommand.id());
        if (accounting.originalVersion() != input.originalVersion() || !accounting.request().equals(input.source())) return null;
        var operation = operations.find(tenant, id).orElse(null); var retired = operations.retirement(tenant, id).orElse(null);
        if ((prepared.status() == VoucherReversalPreparation.Status.AUTHORIZED) != (operation != null)
                || operation != null && (!operation.input().command().equals(prepared.command()) || !operation.input().targetDigest().equals(input.targetDigest())
                    || operation.input().originalVersion() != input.operationVersion())
                || retired != null && (operation == null || !retired.operationId().equals(original.id()) || retired.stoppedVersion() != operation.version())) return null;
        return new Source(original, prepared, operation, retired);
    }

    boolean eligible(String tenant, String recipient, Source source, ReversalNotice notice) {
        return source.recipients(notice).contains(recipient) && organization.personBySubject(tenant, recipient).map(OrganizationPerson::active).orElse(false)
                && (recipient.equals(source.original().employee()) || personnel.eligible(tenant, recipient, source.original().entity()));
    }

    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = ReversalNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.reversalId());
        return source != null && source.original().application().id().equals(row.applicationId()) && source.original().roundNo() == row.roundNo()
                && (key.notice() != ReversalNotice.RETIRED || source.retirement() != null) && eligible(tenant, recipient, source, key.notice()) ? source : null;
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

    private Operation operation(VoucherReversalOperation value) {
        if (value == null) return null;
        var observed = value.observation(); var posting = observed == null || observed.posting() == null ? null : observed.posting().reversal();
        String issue = value.failure() != null ? value.failure().name() : observed != null && observed.rejection() != null ? observed.rejection().name() : null;
        return new Operation(value.input().command().id(), value.version(), value.status(), value.attempts(), value.highestRevision(), value.updatedAt(), value.input().command().expiresAt(),
                observed == null ? null : observed.status(), issue, value.conflictingObservation() != null, posting == null ? null : posting.voucherReference(), posting == null ? null : posting.postedAt());
    }

    /**
     * 原来源只用于服务端通知接收关系，不作为公开财务响应。
     *
     * @author owlzhangfq@gmail.com
     */
    record Source(
            VoucherNotificationAccess.Source original,
            VoucherReversalPreparation preparation,
            VoucherReversalOperation operation,
            VoucherReversalRetirement retirement) {
        /** 安全结束的实际经办人只加入该结束事实，不能反向接收此前的准备或过账消息。 */
        List<String> recipients(ReversalNotice notice) {
            return Stream.concat(Stream.of(original.employee(), preparation.input().requestedBy()), notice == ReversalNotice.RETIRED && retirement != null
                    ? Stream.of(retirement.retiredBy()) : Stream.empty()).distinct().toList();
        }
    }

    /**
     * 持久消息的原业务身份，始终限定当前接收人。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) {}

    /**
     * 原冲销的只读摘要，不包含反向分录、账户、理由材料或办理动作。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(
            UUID messageId,
            UUID reversalId,
            UUID operationId,
            UUID applicationId,
            UUID businessId,
            int roundNo,
            VoucherCommand.Kind kind,
            VoucherOperation.Status originalStatus,
            boolean originalHeld,
            Preparation preparation,
            Operation operation,
            Retirement retirement) {}

    /**
     * 原准备的状态与日期，不返回待授权的完整命令。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(
            UUID id,
            long version,
            VoucherReversalPreparation.Status status,
            Instant requestedAt,
            Instant updatedAt,
            LocalDate accountingDate,
            String issue) {}

    /**
     * 原执行及此前已接受的过账事实，冲突不能隐藏原回执。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(
            UUID id,
            long version,
            VoucherReversalOperation.Status status,
            int attempts,
            long highestRevision,
            Instant updatedAt,
            Instant expiresAt,
            VoucherReversalObservation.Status observedStatus,
            String issue,
            boolean disputed,
            String voucherReference,
            Instant postedAt) {}

    /**
     * 安全结束与 ERP 执行状态并列，不能推断资金退款或资源已恢复。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(
            UUID id, Instant retiredAt, VoucherReversalOperation.RetirementBasis basis) {}
}
