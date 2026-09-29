package io.agentflow.procurement;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentAccess;
import io.agentflow.finance.PaymentPersonnel;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 供应商出纳仅取得同法人最小资金视图，角色不能授予完整采购审批和敏感字段读取。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierCashierAccess {
    private final CurrentActor actors;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final PaymentPersonnel personnel;

    /** 显式角色、当前法人任职与该笔三方分离分别核验。 */
    public SupplierCashierAccess(CurrentActor actors, JdbcSupplierPaymentAuthorizationRepository authorizations,
            JdbcSupplierPaymentOperationRepository payments, PaymentPersonnel personnel) {
        this.actors = actors; this.authorizations = authorizations; this.payments = payments; this.personnel = personnel;
    }
    /** 管理员或财务角色不会自动兼有资金执行能力。 */
    public void requireRole() {
        if (!actors.actor().hasRole(PaymentAccess.CASHIER_ROLE)) throw forbidden();
    }
    /** 不同租户、无本法人任职与不存在的授权统一返回不可见。 */
    public SupplierPaymentAuthorization requireCashier(UUID id) {
        requireRole(); var actor = actors.actor(); var value = authorizations.find(actor.tenantId(), id).orElseThrow(SupplierCashierAccess::notFound);
        if (!personnel.eligible(actor.tenantId(), actor.userId(), value.payable().request().legalEntityId())) throw notFound(); return value;
    }
    /** 原申请人和财务授权人即使兼任出纳，也不能选择或重发自己的付款。 */
    public SupplierPaymentAuthorization requireExecution(UUID id) {
        var value = requireCashier(id); var user = actors.actor().userId();
        if (user.equals(value.authorizedBy()) || user.equals(value.source().reservation().source().employeeId())) throw forbidden(); return value;
    }
    /** 查询可由当前同法人出纳接续，新的发送必须仍由原指令出纳明确办理。 */
    public SupplierPaymentAuthorization requireResend(UUID id) {
        var value = requireExecution(id); var payment = payments.find(actors.actor().tenantId(), id).orElseThrow(SupplierCashierAccess::notFound);
        if (!payment.command().cashier().equals(actors.actor().userId())) throw forbidden(); return value;
    }
    private static DomainException forbidden() { return new DomainException("FORBIDDEN", "An independent authorized cashier is required for this supplier payment"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Supplier payment authorization is unavailable in the current scope"); }
}
