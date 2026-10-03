package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcVoucherReversalCheckRepository;
import io.agentflow.finance.JdbcVoucherReversalRecordRepository;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.finance.VoucherAccess;
import io.agentflow.finance.VoucherCommand;
import io.agentflow.finance.VoucherOperation;
import io.agentflow.finance.VoucherReversalCheck;
import io.agentflow.finance.VoucherReversalPort;
import io.agentflow.finance.VoucherReversalRecord;
import io.agentflow.finance.VoucherReversalSources;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 旧核对消息只定位原核对和它实际消费的登记，当前字段权限仍由原财务用例判断。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ReversalCheckNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final VoucherNotificationAccess vouchers;
    private final VoucherAccess access;
    private final VoucherReversalSources sources;
    private final JdbcVoucherReversalCheckRepository checks;
    private final JdbcVoucherReversalRecordRepository records;
    private final OrganizationRepository organization;
    private final PaymentPersonnel personnel;
    /** 原身份复用原凭证来源，查询与登记保持各自已持久化的关联。 */
    public ReversalCheckNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, VoucherNotificationAccess vouchers, VoucherAccess access,
            VoucherReversalSources sources, JdbcVoucherReversalCheckRepository checks, JdbcVoucherReversalRecordRepository records,
            OrganizationRepository organization, PaymentPersonnel personnel) {
        this.actors = actors; this.jdbc = jdbc; this.vouchers = vouchers; this.access = access; this.sources = sources;
        this.checks = checks; this.records = records; this.organization = organization; this.personnel = personnel;
    }
    /** 读取只返回本次核对摘要，不查询 ERP、续期证据、登记或授予办理权限。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var source = allowed(actor.tenantId(), actor.userId(), row(actor.tenantId(), actor.userId(), messageId));
        if (source == null) throw new DomainException("NOT_FOUND", "Reversal check notification is unavailable in the current scope");
        var original = source.original(); access.read(original.application().id(), original.roundNo());
        var check = source.check(); var input = check.input(); var receipt = check.receipt(); var posting = receipt == null ? null : receipt.reversal();
        return new Target(messageId, input.id(), original.id(), original.application().id(), original.application().businessReference().id(), original.roundNo(),
                original.kind(), original.operation().status(), original.operation().reversalId() != null, check.version(), check.status(), input.requestedAt(), check.updatedAt(), check.issue(),
                receipt == null ? null : new Observation(receipt.status(), receipt.revision(), receipt.observedAt(), receipt.validUntil(), posting == null ? null : posting.voucherReference(),
                        posting == null ? null : posting.accountingDate(), posting == null ? null : posting.postedAt()),
                source.record() == null ? null : new Registration(source.record().id(), source.record().recordedAt()));
    }
    /** 外发前仍复核原消息与当前任职，后来的登记不会替换旧核对。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }
    Source original(String tenant, UUID id) {
        var check = checks.find(tenant, id).orElse(null); if (check == null) return null;
        var input = check.input(); var command = input.request().command(); var original = vouchers.original(tenant, command.id());
        if (original == null || original.operation() == null || !original.operation().input().command().equals(command)
                || !original.operation().input().targetDigest().equals(input.targetDigest())) return null;
        var accounting = sources.find(tenant, command.id());
        if (accounting.originalVersion() != input.originalVersion() || !accounting.request().equals(input.request())) return null;
        // 同一原凭证后来可能由另一核对登记，不能把那条记录挂到旧消息上。
        var record = records.forOperation(tenant, command.id()).filter(value -> value.checkId().equals(id)).orElse(null);
        if ((check.status() == VoucherReversalCheck.Status.RECORDED) != (record != null)
                || record != null && (!record.id().equals(check.recordId()) || !record.receipt().equals(check.receipt())
                    || !record.recordedBy().equals(input.requestedBy()) || !record.recordedAt().equals(check.updatedAt()))) return null;
        return new Source(original, check, record);
    }
    boolean eligible(String tenant, String recipient, Source source) {
        return source.recipients().contains(recipient) && organization.personBySubject(tenant, recipient).map(OrganizationPerson::active).orElse(false)
                && (recipient.equals(source.original().employee()) || personnel.eligible(tenant, recipient, source.original().entity()));
    }
    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = ReversalCheckNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.checkId());
        return source != null && source.original().application().id().equals(row.applicationId()) && source.original().roundNo() == row.roundNo()
                && ReversalCheckNotice.from(source.check()).filter(value -> value == key.notice()).isPresent() && eligible(tenant, recipient, source) ? source : null;
    }
    private Row row(String tenant, String recipient, UUID id) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('REVERSAL_CHECK_RESULT','REVERSAL_CHECK_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")), UUID.fromString(row.getString("application_id")), row.getInt("round_no")),
                tenant, recipient, id.toString()).stream().findFirst().orElse(null);
    }
    /**
     * 原参与关系不携带公开办理权限。
     * @author owlzhangfq@gmail.com
     */
    record Source(VoucherNotificationAccess.Source original, VoucherReversalCheck check, VoucherReversalRecord record) {
        List<String> recipients() { return List.of(original.employee(), check.input().requestedBy()).stream().distinct().toList(); }
    }
    /**
     * 只读取当前接收人的持久消息。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) { }
    /**
     * 原核对的最小摘要，财务资源状态应回到原业务核对。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(UUID messageId, UUID checkId, UUID operationId, UUID applicationId, UUID businessId, int roundNo,
                         VoucherCommand.Kind kind, VoucherOperation.Status originalStatus, boolean originalHeld, long version, VoucherReversalCheck.Status status,
                         Instant requestedAt, Instant updatedAt, VoucherReversalCheck.Issue issue, Observation observation, Registration record) { }
    /**
     * 原查询时的证据摘要，不返回分录或可登记动作。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Observation(VoucherReversalPort.Status status, long revision, Instant observedAt, Instant validUntil,
                              String voucherReference, LocalDate accountingDate, Instant postedAt) { }
    /**
     * 只有被本次核对实际消费的持久登记才会返回。
     * @author owlzhangfq@gmail.com
     */
    public record Registration(UUID id, Instant recordedAt) { }
}
