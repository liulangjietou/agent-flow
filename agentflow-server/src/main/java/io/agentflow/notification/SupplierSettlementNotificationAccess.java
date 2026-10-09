package io.agentflow.notification;


import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.SupplierSettlementNotificationAccessMapper;
import io.agentflow.procurement.JdbcProcurementPayableReservationRepository;
import io.agentflow.procurement.JdbcSupplierPayableSettlementRepository;
import io.agentflow.procurement.JdbcSupplierSettlementPreparationRepository;
import io.agentflow.procurement.ProcurementPayableReservation;
import io.agentflow.procurement.SupplierPayableSettlementOperation;
import io.agentflow.procurement.SupplierSettlementAccess;
import io.agentflow.procurement.SupplierSettlementPreparation;
import io.agentflow.procurement.SupplierSettlementRetirement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 消息固定原准备编号，ERP 状态和经持久凭据核验的本地完成分别读取。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementNotificationAccess {
    private final CurrentActor actors;
    private final SupplierSettlementNotificationAccessMapper sqlMapper;
    private final JdbcSupplierSettlementPreparationRepository preparations;
    private final JdbcSupplierPayableSettlementRepository settlements;
    private final JdbcProcurementPayableReservationRepository reservations;
    private final ApplicationRepository applications;
    private final SupplierSettlementAccess access;
    private final SupplierPaymentNotificationAccess personnel;

    /** 复用原轮次敏感字段和当前人员、法人约束，不借用最新结算或管理员身份。 */
    public SupplierSettlementNotificationAccess(
            CurrentActor actors,
            SupplierSettlementNotificationAccessMapper sqlMapper,
            JdbcSupplierSettlementPreparationRepository preparations,
            JdbcSupplierPayableSettlementRepository settlements,
            JdbcProcurementPayableReservationRepository reservations,
            ApplicationRepository applications,
            SupplierSettlementAccess access,
            SupplierPaymentNotificationAccess personnel) {
        this.actors = actors;
        this.sqlMapper = sqlMapper;
        this.preparations = preparations;
        this.settlements = settlements;
        this.reservations = reservations;
        this.applications = applications;
        this.access = access;
        this.personnel = personnel;
    }

    /** 本人原消息只读查询；不触发 ERP 查询、核销、本地完成或安全结束。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor();
        var row = row(actor.tenantId(), actor.userId(), messageId);
        var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null)
            throw new DomainException(
                    "NOT_FOUND",
                    "Original supplier settlement notification is unavailable in the current"
                        + " scope");
        var input = source.preparation().input();
        var payment = input.payment().command();
        var binding = payment.holdCommand().authorization().source().reservation().source();
        access.read(payment.id());
        var operation = source.operation();
        var retirement = source.retirement();
        return new Target(
                messageId,
                input.id(),
                payment.id(),
                binding.requestId(),
                binding.applicationId(),
                binding.round().roundNo(),
                input.accountingDate(),
                SupplierSettlementNotice.source(row.eventKey()).orElseThrow().notice(),
                Preparation.of(source.preparation()),
                operation == null ? null : Operation.of(operation),
                retirement == null
                        ? null
                        : new Retirement(retirement.basis(), retirement.retiredAt()),
                source.completion());
    }

    /** 发送最小消息前重新确认原参与人和当前法人资格。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }

    Source original(String tenant, UUID id, SupplierSettlementNotice fact) {
        var preparation = preparations.find(tenant, id).orElse(null); if (preparation == null) return null;
        var input = preparation.input(); var payment = input.payment().command(); var original = payment.holdCommand().authorization().source().reservation(); var binding = original.source();
        var app = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (app == null || !app.createdBy().equals(binding.employeeId()) || app.businessReference() == null
                || app.businessReference().type() != BusinessReference.Type.PROCUREMENT_PAYMENT || !app.businessReference().id().equals(binding.requestId())) return null;
        var operation = preparation.status() == SupplierSettlementPreparation.Status.READY ? settlements.find(tenant, id).orElse(null) : null;
        if (preparation.status() == SupplierSettlementPreparation.Status.READY && operation == null) return null;
        var retirement = operation == null ? null : settlements.retirement(tenant, id).orElse(null);
        var reservation = reservations.find(tenant, original.id()).orElse(null);
        if (reservation == null || !reservation.source().equals(original.source()) || !reservation.heldAt().equals(original.heldAt())) return null;
        // 占用可能被后一次独立结算完成，旧消息不能借用另一编号的完成凭据。
        var completion = reservation.settlement();
        if (completion != null && !completion.operationId().equals(id)) completion = null;
        if (fact == SupplierSettlementNotice.COMPLETED && completion == null || fact == SupplierSettlementNotice.RETIRED && retirement == null) return null;
        var recipients = new ArrayList<String>(); recipients.add(binding.employeeId()); recipients.add(input.financeActor());
        if (fact == SupplierSettlementNotice.RETIRED && retirement != null) recipients.add(retirement.retiredBy());
        return new Source(app, preparation, operation, retirement, completion, binding.round().content().legalEntityId(), recipients.stream().distinct().toList());
    }

    boolean eligible(String tenant, String recipient, Source source) {
        var applicant = source.preparation().input().payment().command().holdCommand().authorization().source().reservation().source().employeeId();
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, applicant, source.entity());
    }

    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = SupplierSettlementNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.settlementId(), key.notice());
        return source != null && source.application().id().equals(row.applicationId())
                && source.preparation().input().payment().command().holdCommand().authorization().source().reservation().source().round().roundNo() == row.roundNo()
                && eligible(tenant, recipient, source) ? source : null;
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
     * 实际原关系和经仓储核验的完成凭据，不向 HTTP 暴露完整命令。
     *
     * @author owlzhangfq@gmail.com
     */
    record Source(
            Application application,
            SupplierSettlementPreparation preparation,
            SupplierPayableSettlementOperation operation,
            SupplierSettlementRetirement retirement,
            ProcurementPayableReservation.Settlement completion,
            UUID entity,
            List<String> recipients) {}

    /**
     * 只查询当前接收人的两类消息。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) {}

    /**
     * 当时通知事实与原编号的当前状态分开，不含金额、账户或办理许可。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(
            UUID messageId,
            UUID settlementId,
            UUID paymentId,
            UUID requestId,
            UUID applicationId,
            int roundNo,
            LocalDate accountingDate,
            SupplierSettlementNotice fact,
            Preparation preparation,
            Operation operation,
            Retirement retirement,
            ProcurementPayableReservation.Settlement completion) {}

    /**
     * 当前原准备的最小投影。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(
            long version,
            SupplierSettlementPreparation.Status status,
            SupplierSettlementPreparation.Issue issue,
            Instant updatedAt) {
        static Preparation of(SupplierSettlementPreparation value) { return new Preparation(value.version(), value.status(), value.issue(), value.updatedAt()); }
    }

    /**
     * ERP 当前状态不冒充本地占用完成。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(
            long version,
            SupplierPayableSettlementOperation.Status status,
            SupplierPayableSettlementOperation.Failure issue,
            Instant updatedAt) {
        static Operation of(SupplierPayableSettlementOperation value) { return new Operation(value.version(), value.status(), value.failure(), value.updatedAt()); }
    }

    /**
     * 仅在实际保存具名决定后展示安全结束。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(
            SupplierPayableSettlementOperation.RetirementBasis basis, Instant retiredAt) {}
}
