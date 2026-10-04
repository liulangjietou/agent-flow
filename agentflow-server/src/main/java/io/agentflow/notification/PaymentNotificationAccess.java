package io.agentflow.notification;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcPaymentAuthorizationRepository;
import io.agentflow.finance.JdbcPaymentExecutionRequestRepository;
import io.agentflow.finance.JdbcPaymentOperationRepository;
import io.agentflow.finance.PaymentAccess;
import io.agentflow.finance.PaymentAuthorization;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.finance.PaymentView;
import io.agentflow.finance.VoucherAccess;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/**
 * 通知身份只定位原付款；当前人员、法人和财务字段权限仍由原业务读取入口决定。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final OrganizationRepository organization;
    private final PaymentPersonnel personnel;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final PaymentAccess payments;
    private final VoucherAccess financial;
    private final JdbcPaymentExecutionRequestRepository requests;
    private final JdbcPaymentOperationRepository operations;

    /** 通知不推定身份源角色，也不使用管理员身份代读原付款。 */
    public PaymentNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, OrganizationRepository organization, PaymentPersonnel personnel,
                                     JdbcPaymentAuthorizationRepository authorizations, PaymentAccess payments, VoucherAccess financial,
                                     JdbcPaymentExecutionRequestRepository requests, JdbcPaymentOperationRepository operations) {
        this.actors = actors; this.jdbc = jdbc; this.organization = organization; this.personnel = personnel;
        this.authorizations = authorizations; this.payments = payments; this.financial = financial;
        this.requests = requests; this.operations = operations;
    }

    /** 只为当前有效的原参与人建通知；财务和出纳还须保留原法人任职。 */
    public boolean eligible(String tenant, String recipient, String applicant, UUID legalEntityId) {
        return organization.personBySubject(tenant, recipient).map(OrganizationPerson::active).orElse(false)
                && (recipient.equals(applicant) || personnel.eligible(tenant, recipient, legalEntityId));
    }

    /** 返回受当前权限保护的入口；历史消息不赋予当前付款或完整表单权限。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var row = row(actor.tenantId(), actor.userId(), messageId);
        var payment = payment(actor.tenantId(), actor.userId(), row);
        if (payment == null) throw notFound();
        var terms = payment.terms(); View view;
        if (actor.userId().equals(terms.payee().employeeId())) {
            financial.read(terms.binding().applicationId(), terms.binding().roundNo()); view = View.APPLICATION_ROUND;
        } else if (actor.userId().equals(payment.decision().authorizedBy())) {
            payments.requireFinanceAuthorization(terms.id()); view = View.APPLICATION_ROUND;
        } else {
            payments.requireCashier(terms.id()); view = View.CASHIER_PAYMENT;
        }
        var request = requests.forAuthorization(terms.tenantId(), terms.id()).orElse(null);
        var operation = operations.find(terms.tenantId(), terms.id()).orElse(null);
        return new Target(messageId, terms.id(), view, terms.binding().applicationId(), terms.binding().roundNo(), PaymentView.of(payment, request, operation));
    }

    /** 外发前再查原参与关系和当前任职，已失效消息不能借通用投递重试恢复发送。 */
    public boolean deliveryAllowed(NotificationDelivery value) {
        var row = row(value.tenantId(), value.recipient(), value.inboxId());
        return row == null || payment(value.tenantId(), value.recipient(), row) != null;
    }

    private Row row(String tenant, String recipient, UUID messageId) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('PAYMENT_RESULT','PAYMENT_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")),
                UUID.fromString(row.getString("application_id")), row.getInt("round_no")), tenant, recipient, messageId.toString()).stream().findFirst().orElse(null);
    }

    private PaymentAuthorization payment(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var source = PaymentNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null);
        if (source == null) return null;
        var payment = authorizations.find(tenant, source.paymentId()).orElse(null);
        if (payment == null) return null;
        var terms = payment.terms();
        // 尚未登记付款命令时，原出纳只来自持久请求，不能从当前角色或其他授权推定。
        String cashier = payment.execution() == null
                ? requests.forAuthorization(tenant, terms.id()).map(value -> value.input().cashier()).orElse(null)
                : payment.execution().command().authorization().executedBy();
        if (cashier == null) return null;
        if (!terms.binding().applicationId().equals(row.applicationId()) || terms.binding().roundNo() != row.roundNo()
                || !List.of(terms.payee().employeeId(), payment.decision().authorizedBy(), cashier).contains(recipient)
                || !eligible(tenant, recipient, terms.payee().employeeId(), terms.payee().legalEntityId())) return null;
        return payment;
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Payment notification is unavailable in the current scope"); }

    /**
     * 存储事实只在服务内使用，不把事件键公开给页面。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) { }
    /**
     * 申请轮次与出纳最小视图使用各自既有读取入口。
     * @author owlzhangfq@gmail.com
     */
    public enum View { APPLICATION_ROUND, CASHIER_PAYMENT }
    /**
     * 只有明确打开消息且通过当前权限后才读取原付款；不以本轮最新授权替换历史原付款。
     * @author owlzhangfq@gmail.com
     */
    public record Target(UUID messageId, UUID paymentId, View view, UUID applicationId, int roundNo, PaymentView payment) { }
}
