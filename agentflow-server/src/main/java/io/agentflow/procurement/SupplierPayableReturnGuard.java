package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同一原应付的回款疑点约束全部后续申请，历史银行行提供短事务内的共同锁。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class SupplierPayableReturnGuard {
    private final JdbcTemplate jdbc;

    /** 只关联持久的原应付身份，不依赖当前账户目录或外部网络。 */
    public SupplierPayableReturnGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 申请锁后按固定顺序锁定该应付的已有银行行，资金核对和新发送共享同一顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String tenant, ProcurementPaymentContent content) {
        jdbc.queryForList("""
                SELECT b.id FROM supplier_payment_operation b WHERE b.tenant_id=? AND b.id IN (
                    SELECT a.id FROM supplier_payment_authorization a
                    JOIN procurement_payable_reservation r ON r.tenant_id=a.tenant_id AND r.id=a.reservation_id
                    WHERE a.tenant_id=? AND r.legal_entity_id=? AND r.supplier_reference=? AND r.payable_reference=?)
                ORDER BY b.id FOR UPDATE
                """, String.class, tenant, tenant, content.legalEntityId().toString(), content.supplierReference(), content.payableReference());
    }

    /** 新占用、财务授权及外部发送必须先处理同一应付已知的退回疑点。 */
    public void requireClear(String tenant, ProcurementPaymentContent content) {
        if (blocked(tenant, content)) throw new DomainException("SUPPLIER_PAYMENT_RETURN_REVIEW_REQUIRED", "Original supplier payable has unresolved received-funds or bank evidence");
    }

    /** 只读投影与本地完成共用判定；原来已完成的凭据不会被回退。 */
    public boolean blocked(String tenant, ProcurementPaymentContent content) {
        var identity = new Object[] {tenant, content.legalEntityId().toString(), content.supplierReference(), content.payableReference()};
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_payment_returns WHERE tenant_id=? AND legal_entity_id=?
                AND supplier_reference=? AND payable_reference=? AND review_required=TRUE
                """, Integer.class, identity) > 0) return true;
        // 已完成后又出现银行未知、争议或退回，同样不能让另一张申请沿用旧完成事实继续付款。
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_payment_operation b
                JOIN supplier_payment_authorization a ON a.tenant_id=b.tenant_id AND a.id=b.id
                JOIN procurement_payable_reservation r ON r.tenant_id=a.tenant_id AND r.id=a.reservation_id
                WHERE b.tenant_id=? AND r.legal_entity_id=? AND r.supplier_reference=? AND r.payable_reference=?
                AND r.settled_at IS NOT NULL AND b.status<>'SUCCEEDED'
                """, Integer.class, identity) > 0;
    }
}
