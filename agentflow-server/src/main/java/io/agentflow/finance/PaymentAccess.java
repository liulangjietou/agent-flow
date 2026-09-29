package io.agentflow.finance;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * 财务沿用当轮字段权限，出纳使用同法人付款专用最小视图；管理员不会自动取得出纳能力。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentAccess {
    public static final String CASHIER_ROLE = "CASHIER";
    private final CurrentActor actors;
    private final VoucherAccess financial;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcVoucherOperationRepository vouchers;
    private final PaymentPersonnel personnel;
    /** 认证角色、原业务可读性和当前法人任职分别核验，不能用某一个条件代替其他条件。 */
    public PaymentAccess(CurrentActor actors, VoucherAccess financial, JdbcPaymentAuthorizationRepository authorizations,
                         JdbcVoucherOperationRepository vouchers, PaymentPersonnel personnel) {
        this.actors = actors; this.financial = financial; this.authorizations = authorizations; this.vouchers = vouchers; this.personnel = personnel;
    }
    /** 财务新授权入口仍须能读取该轮完整财务字段，并在该法人具有当前有效任职。 */
    public VoucherAccess.Context requireFinance(UUID applicationId, int round) {
        var context = financial.requireFinance(applicationId, round);
        var voucher = vouchers.forRound(actors.actor().tenantId(), applicationId, round, context.kind()).orElseThrow(PaymentAccess::notFound);
        personnel.requireEligible(actors.actor().tenantId(), actors.actor().userId(), voucher.input().command().legalEntityId());
        return context;
    }
    /** 操作既有授权时也复核原轮次字段权限，不以角色或管理员身份跳过原文约束。 */
    public PaymentAuthorization requireFinanceAuthorization(UUID id) {
        var value = authorization(id); var terms = value.terms();
        financial.requireFinance(terms.binding().applicationId(), terms.binding().roundNo());
        personnel.requireEligible(terms.tenantId(), actors.actor().userId(), terms.payee().legalEntityId()); return value;
    }
    /** 出纳目录仅接受明确 CASHIER 角色，ADMIN 或 FINANCE 不能隐式兼任。 */
    public void requireCashierRole() {
        if (!actors.actor().hasRole(CASHIER_ROLE)) throw new DomainException("FORBIDDEN", "Cashier role is required");
    }
    /** 出纳只获得已授权付款的专用最小投影，不因此成为审批参与者或取得完整表单访问。 */
    public PaymentAuthorization requireCashier(UUID id) {
        requireCashierRole(); var value = authorization(id);
        if (!personnel.eligible(value.terms().tenantId(), actors.actor().userId(), value.terms().payee().legalEntityId())) throw notFound();
        return value;
    }
    /** 申请人和该笔财务授权人即使兼有出纳角色，也不能执行这笔付款。 */
    public PaymentAuthorization requireExecution(UUID id) {
        var value = requireCashier(id); var actor = actors.actor();
        if (actor.userId().equals(value.terms().payee().employeeId()) || actor.userId().equals(value.decision().authorizedBy())) {
            throw new DomainException("FORBIDDEN", "Applicant and payment authorizer cannot execute the payment");
        }
        return value;
    }
    private PaymentAuthorization authorization(UUID id) { return authorizations.find(actors.actor().tenantId(), id).orElseThrow(PaymentAccess::notFound); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Payment authorization is unavailable in the current scope"); }
}
