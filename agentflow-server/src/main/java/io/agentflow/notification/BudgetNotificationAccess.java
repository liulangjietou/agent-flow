package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseDraftService;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetObservation;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原命令固定财务版本及接收关系；详情使用当前费用读取权限，不能由历史通知补授权限。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ApplicationRepository applications;
    private final ExpenseReportRepository reports;
    private final JdbcBudgetOperationRepository operations;
    private final OrganizationRepository organization;
    private final ExpenseDraftService expenses;

    /** 财务历史按原版本读取；当前轮次或最新预算操作不能替换消息来源。 */
    public BudgetNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, JsonUtil json, ApplicationRepository applications,
            ExpenseReportRepository reports, JdbcBudgetOperationRepository operations, OrganizationRepository organization, ExpenseDraftService expenses) {
        this.actors = actors; this.jdbc = jdbc; this.json = json; this.applications = applications; this.reports = reports;
        this.operations = operations; this.organization = organization; this.expenses = expenses;
    }

    /** 本人消息仍须原申请轮次可读，敏感字段约束同样适用于管理员。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var source = allowed(actor.tenantId(), actor.userId(), row(actor.tenantId(), actor.userId(), messageId));
        if (source == null) throw new DomainException("NOT_FOUND", "Budget notification is unavailable in the current scope");
        var operation = source.operation(); var position = operation.input().command().position();
        expenses.read(position.reportId(), position.roundNo());
        var observation = operation.observation();
        String issue = operation.failure() != null ? operation.failure().name()
                : observation != null && observation.rejection() != null ? observation.rejection().name() : null;
        return new Target(messageId, operation.input().command().id(), source.application().id(), position.reportId(), position.roundNo(), position.financialVersion(),
                operation.input().command().action(), operation.status(), operation.version(), operation.attempts(), operation.updatedAt(),
                observation == null ? null : observation.status(), issue, observation == null ? null : observation.ledgerRevision(),
                observation == null ? null : observation.reference(), observation == null ? null : observation.appliedAt());
    }

    /** 最小外发提示也复核原接收关系和当前人员有效性。 */
    public boolean deliveryAllowed(NotificationDelivery delivery) {
        var row = row(delivery.tenantId(), delivery.recipient(), delivery.inboxId());
        return row == null || allowed(delivery.tenantId(), delivery.recipient(), row) != null;
    }

    Source original(String tenant, UUID id) {
        var operation = operations.find(tenant, id).orElse(null);
        if (operation == null) return null;
        var position = operation.input().command().position();
        var report = reports.find(tenant, position.reportId()).orElse(null);
        if (report == null || !report.employeeId().equals(position.employeeId())) return null;
        var application = applications.findById(tenant, report.applicationId()).orElse(null);
        if (application == null || !application.createdBy().equals(position.employeeId()) || application.businessReference() == null
                || application.businessReference().type() != BusinessReference.Type.EXPENSE || !application.businessReference().id().equals(position.reportId())) return null;
        var revision = jdbc.query("SELECT actor_id,state_json FROM expense_report_revision WHERE tenant_id=? AND report_id=? AND financial_version=?",
                (row, index) -> new Revision(row.getString("actor_id"), ExpenseReport.restore(json.read(row.getString("state_json"), ExpenseReport.State.class))),
                tenant, position.reportId().toString(), position.financialVersion()).stream().findFirst().orElse(null);
        if (revision == null) return null;
        var original = revision.report();
        if (!original.tenantId().equals(tenant) || !original.applicationId().equals(application.id()) || original.version() != position.financialVersion()
                || !BudgetPrecheckPort.Request.fromCurrent(original, position.accountingDate()).equals(position)) return null;
        // 精确版本的核减证据和仓储审计必须指向同一人；不猜测当前财务角色或其他版本的经办人。
        var recipients = Stream.concat(Stream.of(position.employeeId()), original.currentRound().adjustments().stream()
                .filter(value -> value.previousFinancialVersion() == position.financialVersion() - 1 && value.adjustedBy().equals(revision.actor()))
                .map(value -> value.adjustedBy())).distinct().toList();
        return new Source(application, operation, recipients);
    }

    boolean eligible(String tenant, String recipient, Source source) {
        return source.recipients().contains(recipient) && organization.personBySubject(tenant, recipient).map(OrganizationPerson::active).orElse(false);
    }

    private Source allowed(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var key = BudgetNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null);
        if (key == null) return null;
        var source = original(tenant, key.operationId());
        return source != null && source.application().id().equals(row.applicationId()) && source.operation().input().command().position().roundNo() == row.roundNo()
                && eligible(tenant, recipient, source) ? source : null;
    }

    private Row row(String tenant, String recipient, UUID messageId) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('BUDGET_RESULT','BUDGET_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")),
                UUID.fromString(row.getString("application_id")), row.getInt("round_no")), tenant, recipient, messageId.toString()).stream().findFirst().orElse(null);
    }

    /**
     * 内部原业务及接收人来源，不返回给客户端。
     * @author owlzhangfq@gmail.com
     */
    record Source(Application application, BudgetOperation operation, List<String> recipients) { }
    /**
     * 不可变财务版本的实际操作者与完整事实。
     * @author owlzhangfq@gmail.com
     */
    private record Revision(String actor, ExpenseReport report) { }
    /**
     * 当前消息接收人的持久身份。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) { }
    /**
     * 原操作摘要不包含金额、账户、分摊、外部请求正文或写入动作。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(UUID messageId, UUID operationId, UUID applicationId, UUID reportId, int roundNo, long financialVersion,
                         BudgetCommand.Action action, BudgetOperation.Status status, long version, int attempts, Instant updatedAt,
                         BudgetObservation.Status observedStatus, String issue, Long ledgerRevision, String reference, Instant appliedAt) { }
}
