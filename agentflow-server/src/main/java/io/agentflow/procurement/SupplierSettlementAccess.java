package io.agentflow.procurement;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 结算沿用原采购轮次原文权限，另限制当前结算财务不能是原申请人或出纳。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementAccess {
    private final CurrentActor actors;
    private final SupplierPaymentAccess procurement;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final JdbcSupplierPayableSettlementRepository settlements;

    /** 从真实原授权取得业务关联，客户端不能指定其他申请作为权限依据。 */
    public SupplierSettlementAccess(CurrentActor actors, SupplierPaymentAccess procurement, JdbcSupplierPaymentAuthorizationRepository authorizations,
            JdbcSupplierPaymentOperationRepository payments, JdbcSupplierPayableSettlementRepository settlements) {
        this.actors = actors; this.procurement = procurement; this.authorizations = authorizations; this.payments = payments; this.settlements = settlements;
    }

    /** 只读同样经过字段权限投影，管理员没有读取敏感原文的旁路。 */
    public Context read(UUID paymentId) {
        var actor = actors.actor(); var authorization = authorizations.find(actor.tenantId(), paymentId).orElseThrow(SupplierSettlementAccess::notFound);
        var source = authorization.source().reservation().source(); var scope = procurement.read(source.requestId(), source.round().roundNo());
        var payment = payments.find(actor.tenantId(), paymentId).orElse(null);
        return new Context(authorization, payment, scope.finance() && (payment == null || !payment.command().cashier().equals(actor.userId())));
    }

    /** 所有新决定及幂等回放都重新检查当前角色、法人任职和原轮次可读性。 */
    public Context requireFinance(UUID paymentId) {
        var context = read(paymentId); if (!context.finance()) throw new DomainException("FORBIDDEN", "Settlement requires independent finance access to the original procurement round"); return context;
    }

    /** 结算号始终按租户定位，再回到其真实原银行所属轮次验证权限。 */
    public SupplierPayableSettlementOperation requireSettlement(UUID id) {
        var value = settlements.find(actors.actor().tenantId(), id).orElseThrow(SupplierSettlementAccess::notFound);
        requireFinance(value.command().payment().id()); return value;
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original supplier payment or settlement is unavailable in the current scope"); }

    /**
     * 内部上下文不直接作为 HTTP 响应，原始资金命令必须留在服务端。
     * @author owlzhangfq@gmail.com
     */
    public record Context(SupplierPaymentAuthorization authorization, SupplierPaymentOperation payment, boolean finance) { }
}
