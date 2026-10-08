package io.agentflow.notification;


import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.ExpenseSettlement;
import io.agentflow.expense.ExpenseSettlementAccess;
import io.agentflow.expense.ExpenseSettlementWorkspace;
import io.agentflow.expense.JdbcExpenseSettlementRepository;
import io.agentflow.finance.JdbcPaymentOperationRepository;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.mapper.ExpenseSettlementNotificationAccessMapper;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 原结算修订与当前状态分别读取，历史异常不会被后续重试或裁决替换。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementNotificationAccess {
    private final CurrentActor actors;
    private final ExpenseSettlementNotificationAccessMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseReportRepository reports;
    private final ApplicationRepository applications;
    private final ExpenseSettlementAccess access;
    private final VoucherNotificationAccess vouchers;
    private final JdbcPaymentOperationRepository payments;
    private final OrganizationRepository organization;
    private final PaymentPersonnel personnel;

    /** 原资金和凭证只用于核对身份，不能在消息读取中执行恢复或消费。 */
    public ExpenseSettlementNotificationAccess(
            CurrentActor actors,
            ExpenseSettlementNotificationAccessMapper sqlMapper,
            JsonUtil json,
            JdbcExpenseSettlementRepository settlements,
            ExpenseReportRepository reports,
            ApplicationRepository applications,
            ExpenseSettlementAccess access,
            VoucherNotificationAccess vouchers,
            JdbcPaymentOperationRepository payments,
            OrganizationRepository organization,
            PaymentPersonnel personnel) {
        this.actors = actors;
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.settlements = settlements;
        this.reports = reports;
        this.applications = applications;
        this.access = access;
        this.vouchers = vouchers;
        this.payments = payments;
        this.organization = organization;
        this.personnel = personnel;
    }

    /** 当前原轮次字段权限适用于全部接收人，管理员也不能凭消息越过。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var source = allowed(actor.tenantId(), actor.userId(), row(actor.tenantId(), actor.userId(), messageId));
        if (source == null) throw new DomainException("NOT_FOUND", "Expense settlement notification is unavailable in the current scope");
        var input = source.historical().input(); var binding = input.source(); access.read(binding.businessId(), binding.roundNo());
        var funding = input.payment() != null ? ExpenseSettlementWorkspace.Funding.PAYMENT
                : input.voucherOperationId() != null ? ExpenseSettlementWorkspace.Funding.FULL_OFFSET : ExpenseSettlementWorkspace.Funding.ZERO_AMOUNT;
        return new Target(messageId, binding.applicationId(), binding.businessId(), binding.roundNo(), binding.businessVersion(), funding,
                input.fundingConfirmedAt(), State.of(source.historical()), State.of(source.current()));
    }

    /** 外发领取和重试重新确认原修订、原参与关系与当前人员及法人任职。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }

    Source original(String tenant, UUID reportId, long version) {
        var historical = settlements.revision(tenant, reportId, version).orElse(null);
        var current = settlements.find(tenant, reportId).orElse(null);
        if (historical == null
                || current == null
                || current.version() < version
                || !historical.input().equals(current.input())
                || !historical.createdAt().equals(current.createdAt())) return null;
        var input = historical.input();
        var binding = input.source();
        var report = reports.find(tenant, reportId).orElse(null);
        var application = applications.findById(tenant, binding.applicationId()).orElse(null);
        if (report == null
                || !report.applicationId().equals(binding.applicationId())
                || !report.employeeId().equals(binding.employeeId())
                || application == null
                || !application.createdBy().equals(binding.employeeId())
                || application.businessReference() == null
                || application.businessReference().type() != BusinessReference.Type.EXPENSE
                || !application.businessReference().id().equals(reportId)) return null;
        var revision =
                SqlRows.map(
                                sqlMapper.original(
                                        tenant, reportId.toString(), binding.businessVersion()),
                                row ->
                                        new Revision(
                                                row.getString("actor_id"),
                                                ExpenseReport.restore(
                                                        json.read(
                                                                row.getString("state_json"),
                                                                ExpenseReport.State.class))))
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (revision == null) return null;
        try {
            historical.requireReport(revision.report());
        } catch (DomainException changed) {
            return null;
        }
        var entity = revision.report().currentRound().content().legalEntityId();
        var recipients = new ArrayList<String>();
        recipients.add(binding.employeeId());
        if (input.voucherOperationId() != null) {
            var voucher = vouchers.original(tenant, input.voucherOperationId());
            if (voucher == null
                    || voucher.operation() == null
                    || !voucher.operation().input().command().digest().equals(input.voucherDigest())
                    || !voucher.application().id().equals(application.id())
                    || voucher.roundNo() != binding.roundNo()
                    || !voucher.entity().equals(entity)) return null;
            recipients.addAll(voucher.recipients());
        } else {
            // 零核定没有付款或挂账命令，只纳入原财务修订可确证的实际核减人。
            revision.report().currentRound().adjustments().stream()
                    .filter(
                            value ->
                                    value.previousFinancialVersion()
                                                    == binding.businessVersion() - 1
                                            && value.adjustedBy().equals(revision.actor()))
                    .map(value -> value.adjustedBy())
                    .forEach(recipients::add);
        }
        if (input.payment() != null) {
            var payment = payments.find(tenant, input.payment().operationId()).orElse(null);
            if (payment == null) return null;
            var command = payment.input().command();
            var original = command.binding();
            if (command.purpose() != PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT
                    || !command.digest().equals(input.payment().commandDigest())
                    || !original.applicationId().equals(application.id())
                    || !original.businessId().equals(reportId)
                    || original.roundNo() != binding.roundNo()
                    || original.businessVersion() != binding.businessVersion()
                    || !command.payee().employeeId().equals(binding.employeeId())
                    || !command.payee().legalEntityId().equals(entity)) return null;
            recipients.add(command.authorization().authorizedBy());
        }
        return new Source(
                application, historical, current, entity, recipients.stream().distinct().toList());
    }

    boolean eligible(String tenant, String recipient, Source source) {
        return source.recipients().contains(recipient) && organization.personBySubject(tenant, recipient).map(OrganizationPerson::active).orElse(false)
                && (recipient.equals(source.historical().input().source().employeeId()) || personnel.eligible(tenant, recipient, source.entity()));
    }

    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = ExpenseSettlementNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null); if (key == null) return null;
        var source = original(tenant, key.reportId(), key.version());
        return source != null && source.application().id().equals(row.applicationId()) && source.historical().input().source().roundNo() == row.roundNo()
                && ExpenseSettlementNotice.from(source.historical()).filter(value -> value == key.notice()).isPresent() && eligible(tenant, recipient, source) ? source : null;
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
     * 原修订与当前状态均属于同一固定输入。
     *
     * @author owlzhangfq@gmail.com
     */
    record Source(
            Application application,
            ExpenseSettlement historical,
            ExpenseSettlement current,
            UUID entity,
            List<String> recipients) {}

    /**
     * 原财务修订不从最新核定结果反推。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Revision(String actor, ExpenseReport report) {}

    /**
     * 只查询当前接收人的两类结算消息。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) {}

    /**
     * 历史事实与当前状态分别展示，不包含金额、账户、单据明细或办理许可。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Target(
            UUID messageId,
            UUID applicationId,
            UUID reportId,
            int roundNo,
            long financialVersion,
            ExpenseSettlementWorkspace.Funding funding,
            Instant fundingConfirmedAt,
            State notice,
            State current) {}

    /**
     * 原预算编号只用于追溯，不把预算当前状态伪装成过去修订的事实。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record State(
            long version,
            ExpenseSettlement.Status status,
            boolean resourcesConsumed,
            UUID budgetOperationId,
            String issue,
            Instant updatedAt) {
        static State of(ExpenseSettlement value) { return new State(value.version(), value.status(), value.resourcesConsumed(), value.budgetOperationId(), value.issue(), value.updatedAt()); }
    }
}
