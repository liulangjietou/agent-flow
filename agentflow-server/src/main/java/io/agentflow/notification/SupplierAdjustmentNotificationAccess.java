package io.agentflow.notification;


import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.SupplierAdjustmentNotificationAccessMapper;
import io.agentflow.procurement.JdbcSupplierAdjustmentCompletions;
import io.agentflow.procurement.JdbcSupplierAdjustmentPreparationRepository;
import io.agentflow.procurement.JdbcSupplierPayableAdjustmentRepository;
import io.agentflow.procurement.SupplierAdjustmentAccess;
import io.agentflow.procurement.SupplierAdjustmentCompletion;
import io.agentflow.procurement.SupplierAdjustmentPreparation;
import io.agentflow.procurement.SupplierAdjustmentRetirement;
import io.agentflow.procurement.SupplierPayableAdjustmentOperation;

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
public class SupplierAdjustmentNotificationAccess {
    private final CurrentActor actors;
    private final SupplierAdjustmentNotificationAccessMapper sqlMapper;
    private final JdbcSupplierAdjustmentPreparationRepository preparations;
    private final JdbcSupplierPayableAdjustmentRepository operations;
    private final JdbcSupplierAdjustmentCompletions completions;
    private final ApplicationRepository applications;
    private final SupplierAdjustmentAccess access;
    private final SupplierPaymentNotificationAccess personnel;

    /** 复用原轮次敏感字段和当前人员、法人约束，不借用最新应付调整或管理员身份。 */
    public SupplierAdjustmentNotificationAccess(
            CurrentActor actors,
            SupplierAdjustmentNotificationAccessMapper sqlMapper,
            JdbcSupplierAdjustmentPreparationRepository preparations,
            JdbcSupplierPayableAdjustmentRepository operations,
            JdbcSupplierAdjustmentCompletions completions,
            ApplicationRepository applications,
            SupplierAdjustmentAccess access,
            SupplierPaymentNotificationAccess personnel) {
        this.actors = actors;
        this.sqlMapper = sqlMapper;
        this.preparations = preparations;
        this.operations = operations;
        this.completions = completions;
        this.applications = applications;
        this.access = access;
        this.personnel = personnel;
    }

    /** 本人原消息只读查询；不触发 ERP 查询、调整、本地完成或安全结束。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor();
        var row = row(actor.tenantId(), actor.userId(), messageId);
        var source = allowed(actor.tenantId(), actor.userId(), row);
        if (source == null)
            throw new DomainException(
                    "NOT_FOUND",
                    "Original supplier adjustment notification is unavailable in the current"
                        + " scope");
        var input = source.preparation().input();
        var payment = input.source().returns().request().command();
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
                SupplierAdjustmentNotice.source(row.eventKey()).orElseThrow().notice(),
                Preparation.of(source.preparation()),
                operation == null ? null : Operation.of(operation),
                retirement == null
                        ? null
                        : new Retirement(retirement.basis(), retirement.retiredAt()),
                source.completion() == null ? null : Completion.of(source.completion()));
    }

    /** 发送最小消息前重新确认原参与人和当前法人资格。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }

    Source original(String tenant, UUID id, SupplierAdjustmentNotice fact) {
        var preparation = preparations.find(tenant, id).orElse(null); if (preparation == null) return null;
        var input = preparation.input(); var payment = input.source().returns().request().command(); var original = payment.holdCommand().authorization().source().reservation(); var binding = original.source();
        var app = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (app == null || !app.createdBy().equals(binding.employeeId()) || app.businessReference() == null
                || app.businessReference().type() != BusinessReference.Type.PROCUREMENT_PAYMENT || !app.businessReference().id().equals(binding.requestId())) return null;
        var operation = preparation.status() == SupplierAdjustmentPreparation.Status.READY ? operations.find(tenant, id).orElse(null) : null;
        if (preparation.status() == SupplierAdjustmentPreparation.Status.READY && operation == null) return null;
        var retirement = operation == null ? null : operations.retirement(tenant, id).orElse(null);
        // 只恢复本次实际完成的修订；后续追加资金和另一调整不能替换原凭据。
        var completion = operation == null ? null : completions.find(tenant, id).orElse(null);
        if (completion != null && !completion.operation().command().equals(operation.command())) return null;
        if (fact == SupplierAdjustmentNotice.COMPLETED && completion == null || fact == SupplierAdjustmentNotice.RETIRED && retirement == null) return null;
        var recipients = new ArrayList<String>(); recipients.add(binding.employeeId()); recipients.add(input.financeActor());
        if (fact == SupplierAdjustmentNotice.RETIRED && retirement != null) recipients.add(retirement.retiredBy());
        return new Source(app, preparation, operation, retirement, completion, binding.round().content().legalEntityId(), recipients.stream().distinct().toList());
    }

    boolean eligible(String tenant, String recipient, Source source) {
        var applicant = source.preparation().input().source().returns().request().command().holdCommand().authorization().source().reservation().source().employeeId();
        return source.recipients().contains(recipient) && personnel.eligible(tenant, recipient, applicant, source.entity());
    }

    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = SupplierAdjustmentNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.adjustmentId(), key.notice());
        return source != null && source.application().id().equals(row.applicationId())
                && source.preparation().input().source().returns().request().command().holdCommand().authorization().source().reservation().source().round().roundNo() == row.roundNo()
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
     * 实际原关系和经仓储回放核验的完成凭据，不向 HTTP 暴露完整命令。
     *
     * @author owlzhangfq@gmail.com
     */
    record Source(
            Application application,
            SupplierAdjustmentPreparation preparation,
            SupplierPayableAdjustmentOperation operation,
            SupplierAdjustmentRetirement retirement,
            SupplierAdjustmentCompletion completion,
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
            UUID adjustmentId,
            UUID paymentId,
            UUID requestId,
            UUID applicationId,
            int roundNo,
            LocalDate accountingDate,
            SupplierAdjustmentNotice fact,
            Preparation preparation,
            Operation operation,
            Retirement retirement,
            Completion completion) {}

    /**
     * 当前原准备的最小投影。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(
            long version,
            SupplierAdjustmentPreparation.Status status,
            SupplierAdjustmentPreparation.Issue issue,
            Instant updatedAt) {
        static Preparation of(SupplierAdjustmentPreparation value) { return new Preparation(value.version(), value.status(), value.issue(), value.updatedAt()); }
    }

    /**
     * ERP 当前状态不冒充本地账务完成。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(
            long version,
            SupplierPayableAdjustmentOperation.Status status,
            SupplierPayableAdjustmentOperation.Failure issue,
            Instant updatedAt) {
        static Operation of(SupplierPayableAdjustmentOperation value) { return new Operation(value.version(), value.status(), value.failure(), value.updatedAt()); }
    }

    /**
     * 仅在实际保存具名决定后展示安全结束。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(
            SupplierPayableAdjustmentOperation.RetirementBasis basis, Instant retiredAt) {}

    /**
     * 本次实际账务完成的精确修订，不替换成后续累计资金或最新 ERP 状态。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Completion(
            UUID adjustmentId,
            long adjustmentVersion,
            UUID paymentId,
            long paymentVersion,
            long returnVersion,
            Instant completedAt) {
        static Completion of(SupplierAdjustmentCompletion value) {
            return new Completion(value.operation().command().id(), value.operation().version(), value.bank().command().id(), value.bank().version(), value.after().version(), value.completedAt());
        }
    }
}
