package io.agentflow.procurement;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentPersonnel;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 供应商付款沿用原采购轮次字段权限，财务角色与当前法人任职不能替代原文授权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentAccess {
    private final CurrentActor actors;
    private final ProcurementPaymentService payments;
    private final ProcurementPaymentRepository requests;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final PaymentPersonnel personnel;

    /** 复用采购业务的历史读取边界，不为管理员增加敏感字段旁路。 */
    public SupplierPaymentAccess(CurrentActor actors, ProcurementPaymentService payments, ProcurementPaymentRepository requests,
                                 JdbcSupplierPaymentAuthorizationRepository authorizations, PaymentPersonnel personnel) {
        this.actors = actors; this.payments = payments; this.requests = requests; this.authorizations = authorizations; this.personnel = personnel;
    }

    /** 申请人和可读该轮原文的参与人能看办理状态，人工财务动作另受职责分离约束。 */
    public Context read(UUID requestId, Integer roundNo) {
        var view = payments.read(requestId, roundNo); var actor = actors.actor();
        var request = requests.find(actor.tenantId(), requestId).orElseThrow(SupplierPaymentAccess::notFound);
        boolean finance = actor.hasRole("FINANCE") && !actor.userId().equals(request.employeeId())
                && personnel.eligible(actor.tenantId(), actor.userId(), view.content().legalEntityId());
        return new Context(view, finance);
    }

    /** 每次新动作和幂等回放均核对当前权限，申请人即使兼任财务也不能自办。 */
    public Context requireFinance(UUID requestId, int roundNo) {
        var context = read(requestId, roundNo);
        if (!context.finance()) throw new DomainException("FORBIDDEN", "Independent finance role and current appointment in the original legal entity are required");
        return context;
    }

    /** 原授权只在原租户内查找，查询恢复仍须能读原批准轮次。 */
    public SupplierPaymentAuthorization requireAuthorization(UUID id) {
        var authorization = authorizations.find(actors.actor().tenantId(), id).orElseThrow(SupplierPaymentAccess::notFound);
        var source = authorization.source().reservation().source(); requireFinance(source.requestId(), source.round().roundNo()); return authorization;
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Supplier payment is unavailable in the current scope"); }

    /**
     * 内部读取上下文不直接暴露实体或完整 ERP 材料。
     * @author owlzhangfq@gmail.com
     */
    public record Context(ProcurementPaymentService.View view, boolean finance) { }
}
