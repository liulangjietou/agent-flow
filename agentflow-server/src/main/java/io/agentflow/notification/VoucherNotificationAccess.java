package io.agentflow.notification;


import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.expense.AdvanceRequestRepository;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.finance.JdbcVoucherOperationRepository;
import io.agentflow.finance.JdbcVoucherPreparationRepository;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.finance.VoucherAccess;
import io.agentflow.finance.VoucherCommand;
import io.agentflow.finance.VoucherOperation;
import io.agentflow.finance.VoucherPreparation;
import io.agentflow.finance.VoucherWorkspace;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.VoucherNotificationAccessMapper;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 原消息只定位原会计准备或操作；当前业务字段权限决定能否读取详情。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherNotificationAccess {
    private final CurrentActor actors;
    private final VoucherNotificationAccessMapper sqlMapper;
    private final ApplicationRepository applications;
    private final JdbcVoucherPreparationRepository preparations;
    private final JdbcVoucherOperationRepository operations;
    private final AdvanceRequestRepository advances;
    private final ExpenseReportRepository expenses;
    private final OrganizationRepository organization;
    private final PaymentPersonnel personnel;
    private final VoucherAccess access;

    /** 使用原仓储身份与既有财务读取入口，不通过通知补授管理员或财务角色。 */
    public VoucherNotificationAccess(
            CurrentActor actors,
            VoucherNotificationAccessMapper sqlMapper,
            ApplicationRepository applications,
            JdbcVoucherPreparationRepository preparations,
            JdbcVoucherOperationRepository operations,
            AdvanceRequestRepository advances,
            ExpenseReportRepository expenses,
            OrganizationRepository organization,
            PaymentPersonnel personnel,
            VoucherAccess access) {
        this.actors = actors;
        this.sqlMapper = sqlMapper;
        this.applications = applications;
        this.preparations = preparations;
        this.operations = operations;
        this.advances = advances;
        this.expenses = expenses;
        this.organization = organization;
        this.personnel = personnel;
        this.access = access;
    }

    /** 原准备失败后，即使同轮已重新准备成功，也不会把新编号的过账混入旧消息。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var source = allowed(actor.tenantId(), actor.userId(), row(actor.tenantId(), actor.userId(), messageId));
        if (source == null) throw notFound();
        access.read(source.application().id(), source.roundNo());
        return new Target(messageId, source.id(), source.application().id(), source.application().businessReference().id(), source.roundNo(), source.kind(),
                VoucherWorkspace.Preparation.of(source.preparation()), VoucherWorkspace.Operation.of(source.operation()),
                source.operation() != null && source.operation().reversalId() != null);
    }

    /** 外发仍是最小提示，发送前复核原参与关系；付款凭证另保留原法人任职限制。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }

    Source original(String tenant, UUID id) {
        var preparation = preparations.find(tenant, id).orElse(null); var operation = operations.find(tenant, id).orElse(null);
        if (preparation == null && operation == null) return null;
        var input = preparation == null ? null : preparation.input().source(); var command = operation == null ? null : operation.input().command();
        UUID applicationId = input == null ? command.binding().applicationId() : input.applicationId();
        UUID businessId = input == null ? command.binding().businessId() : input.businessId();
        int round = input == null ? command.binding().roundNo() : input.roundNo();
        String employee = input == null ? command.employeeId() : input.employeeId();
        var kind = input == null ? command.kind() : input.kind();
        var application = applications.findById(tenant, applicationId).orElse(null);
        if (application == null || application.businessReference() == null || !application.createdBy().equals(employee)
                || !application.businessReference().id().equals(businessId)) return null;
        var type = application.businessReference().type();
        if (type != BusinessReference.Type.EXPENSE && type != BusinessReference.Type.ADVANCE_REQUEST
                || input != null && input.businessType() != type
                || kind != VoucherCommand.Kind.PAYMENT && kind != (type == BusinessReference.Type.EXPENSE ? VoucherCommand.Kind.EXPENSE_ACCRUAL : VoucherCommand.Kind.EMPLOYEE_ADVANCE)) return null;
        // 只按原轮次取法人，不调用要求当前仍已批准的凭证派生逻辑。
        UUID entity = type == BusinessReference.Type.EXPENSE
                ? expenses.find(tenant, businessId).filter(value -> value.applicationId().equals(applicationId) && value.employeeId().equals(employee))
                    .flatMap(value -> value.rounds().stream().filter(valueRound -> valueRound.roundNo() == round).findFirst()).map(value -> value.content().legalEntityId()).orElse(null)
                : advances.find(tenant, businessId).filter(value -> value.applicationId().equals(applicationId) && value.employeeId().equals(employee))
                    .flatMap(value -> value.rounds().stream().filter(valueRound -> valueRound.roundNo() == round).findFirst()).map(value -> value.content().legalEntityId()).orElse(null);
        if (entity == null || command != null && (!command.binding().applicationId().equals(applicationId) || !command.binding().businessId().equals(businessId)
                || command.binding().roundNo() != round || command.kind() != kind || !command.employeeId().equals(employee) || !command.legalEntityId().equals(entity)
                || input != null && (command.binding().applicationVersion() != input.applicationVersion() || command.binding().businessVersion() != input.businessVersion()
                    || preparation.status() != VoucherPreparation.Status.READY))) return null;
        // 升级前直接登记的命令没有原准备发起人，只通知可确证的申请人，不从当前角色猜人。
        var recipients = preparation == null ? List.of(employee) : List.of(employee, preparation.input().requestedBy());
        return new Source(id, application, round, kind, employee, entity, recipients, preparation, operation);
    }

    boolean eligible(String tenant, String recipient, Source source) {
        return source.recipients().contains(recipient) && organization.personBySubject(tenant, recipient).map(OrganizationPerson::active).orElse(false)
                && (recipient.equals(source.employee()) || source.kind() != VoucherCommand.Kind.PAYMENT || personnel.eligible(tenant, recipient, source.entity()));
    }

    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = VoucherNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null);
        if (key == null) return null;
        var source = original(tenant, key.voucherId());
        return source != null && source.application().id().equals(row.applicationId()) && source.roundNo() == row.roundNo() && eligible(tenant, recipient, source) ? source : null;
    }

    private Row row(String tenant, String recipient, UUID messageId) {
        return SqlRows.map(
                        sqlMapper.row(tenant, recipient, messageId.toString()),
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

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Voucher notification is unavailable in the current scope"); }

    /**
     * 内部来源含接收关系，不能作为 HTTP 响应公开。
     *
     * @author owlzhangfq@gmail.com
     */
    record Source(
            UUID id,
            Application application,
            int roundNo,
            VoucherCommand.Kind kind,
            String employee,
            UUID entity,
            List<String> recipients,
            VoucherPreparation preparation,
            VoucherOperation operation) {}

    /**
     * 消息身份只取当前接收人的持久行。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) {}

    /**
     * 原准备与过账摘要没有会计命令、账户或写入动作。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(
            UUID messageId,
            UUID voucherId,
            UUID applicationId,
            UUID businessId,
            int roundNo,
            VoucherCommand.Kind kind,
            VoucherWorkspace.Preparation preparation,
            VoucherWorkspace.Operation operation,
            boolean reversalBound) {}
}
