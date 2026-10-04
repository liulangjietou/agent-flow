package io.agentflow.notification;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import io.agentflow.procurement.JdbcSupplierPayableHoldRepository;
import io.agentflow.procurement.JdbcSupplierPaymentAuthorizationRepository;
import io.agentflow.procurement.JdbcSupplierPaymentExecutionRepository;
import io.agentflow.procurement.JdbcSupplierPaymentOperationRepository;
import io.agentflow.procurement.SupplierCashierAccess;
import io.agentflow.procurement.SupplierCashierWorkspace;
import io.agentflow.procurement.SupplierPaymentAccess;
import io.agentflow.procurement.SupplierPaymentAuthorization;
import io.agentflow.procurement.SupplierPaymentExecutionRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商消息始终读取原出纳选择，当前人员、法人与采购字段权限由原业务入口复核。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentNotificationAccess {
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final OrganizationRepository organization;
    private final PaymentPersonnel personnel;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final JdbcSupplierPaymentExecutionRepository requests;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final JdbcSupplierPayableHoldRepository holds;
    private final SupplierPaymentAccess procurement;
    private final SupplierCashierAccess cashier;

    /** 历史消息不借最新授权、最近请求或管理员身份读取敏感业务。 */
    public SupplierPaymentNotificationAccess(CurrentActor actors, JdbcTemplate jdbc, OrganizationRepository organization, PaymentPersonnel personnel,
            JdbcSupplierPaymentAuthorizationRepository authorizations, JdbcSupplierPaymentExecutionRepository requests,
            JdbcSupplierPaymentOperationRepository payments, JdbcSupplierPayableHoldRepository holds, SupplierPaymentAccess procurement, SupplierCashierAccess cashier) {
        this.actors = actors; this.jdbc = jdbc; this.organization = organization; this.personnel = personnel; this.authorizations = authorizations;
        this.requests = requests; this.payments = payments; this.holds = holds; this.procurement = procurement; this.cashier = cashier;
    }

    /** 接收人仅来自原内部参与人，供应商名称和收款账户不能充当组织身份。 */
    public boolean eligible(String tenant, String recipient, String applicant, UUID entity) {
        return organization.personBySubject(tenant, recipient).map(OrganizationPerson::active).orElse(false)
                && (recipient.equals(applicant) || personnel.eligible(tenant, recipient, entity));
    }

    /** 当前权限与原登记请求共同限定只读资金投影，不返回付款动作许可。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Target target(UUID messageId) {
        var actor = actors.actor(); var context = context(actor.tenantId(), actor.userId(), row(actor.tenantId(), actor.userId(), messageId));
        if (context == null) throw notFound();
        var authorization = context.authorization(); var source = authorization.source().reservation().source(); View view;
        if (actor.userId().equals(source.employeeId())) { procurement.read(source.requestId(), source.round().roundNo()); view = View.APPLICATION_ROUND; }
        else if (actor.userId().equals(authorization.authorizedBy())) { procurement.requireAuthorization(authorization.id()); view = View.APPLICATION_ROUND; }
        else { cashier.requireCashier(authorization.id()); view = View.CASHIER_PAYMENT; }
        var request = context.request(); var tenant = actor.tenantId();
        // 已停止的原选择没有银行命令，即使同一授权后来另选账户也不能混入那次付款。
        var payment = request.status() == SupplierPaymentExecutionRequest.Status.READY ? payments.find(tenant, authorization.id()).orElseThrow(SupplierPaymentNotificationAccess::notFound) : null;
        var hold = holds.find(tenant, authorization.id()).orElseThrow(SupplierPaymentNotificationAccess::notFound);
        var retirement = authorizations.retirement(tenant, authorization.id()).orElse(null);
        var facts = SupplierCashierWorkspace.View.of(authorization, hold, request, payment, retirement == null ? null : retirement.retiredAt(),
                new SupplierCashierWorkspace.Actions(false, false, false));
        return new Target(messageId, authorization.id(), request.input().id(), view, source.applicationId(), source.round().roundNo(),
                view == View.CASHIER_PAYMENT && request.ownsAuthorization(), facts);
    }

    /** 外发仍只含最小消息，领取和重试重新核对当前人员、法人及原请求参与关系。 */
    public boolean deliveryAllowed(NotificationDelivery value) {
        var row = row(value.tenantId(), value.recipient(), value.inboxId());
        return row == null || context(value.tenantId(), value.recipient(), row) != null;
    }
    private Row row(String tenant, String recipient, UUID id) {
        return jdbc.query("""
                SELECT event_key,kind,application_id,round_no FROM notification_inbox
                WHERE tenant_id=? AND recipient_id=? AND id=? AND kind IN ('SUPPLIER_PAYMENT_RESULT','SUPPLIER_PAYMENT_ATTENTION')
                """, (row, index) -> new Row(row.getString("event_key"), InboxMessage.Kind.valueOf(row.getString("kind")),
                UUID.fromString(row.getString("application_id")), row.getInt("round_no")), tenant, recipient, id.toString()).stream().findFirst().orElse(null);
    }
    private Context context(String tenant, String recipient, Row row) {
        if (row == null) return null;
        var identity = SupplierPaymentNotice.source(row.eventKey()).filter(value -> value.notice().kind() == row.kind()).orElse(null);
        if (identity == null) return null;
        var authorization = authorizations.find(tenant, identity.paymentId()).orElse(null);
        var request = requests.find(tenant, identity.executionRequestId()).orElse(null);
        if (authorization == null || request == null || !request.input().authorizationId().equals(authorization.id())) return null;
        var source = authorization.source().reservation().source();
        if (!source.applicationId().equals(row.applicationId()) || source.round().roundNo() != row.roundNo()
                || !List.of(source.employeeId(), authorization.authorizedBy(), request.input().cashier()).contains(recipient)
                || !eligible(tenant, recipient, source.employeeId(), source.round().content().legalEntityId())) return null;
        return new Context(authorization, request);
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Supplier payment notification is unavailable in the current scope"); }
    /**
     * 原事件定位只在服务内使用，完整采购、账户和指令留在受控存储。
     * @author owlzhangfq@gmail.com
     */
    private record Row(String eventKey, InboxMessage.Kind kind, UUID applicationId, int roundNo) { }
    /**
     * 原授权与原选择不能被工作区的最近请求替代。
     * @author owlzhangfq@gmail.com
     */
    private record Context(SupplierPaymentAuthorization authorization, SupplierPaymentExecutionRequest request) { }
    /**
     * 采购原轮次与出纳工作区各自重新校验权限。
     * @author owlzhangfq@gmail.com
     */
    public enum View { APPLICATION_ROUND, CASHIER_PAYMENT }
    /**
     * 原选择已停止时只展示原事实，不导航到后来另一人的出纳选择。
     * @author owlzhangfq@gmail.com
     */
    public record Target(UUID messageId, UUID paymentId, UUID executionRequestId, View view, UUID applicationId, int roundNo,
                         boolean canOpenCashier, SupplierCashierWorkspace.View payment) { }
}
