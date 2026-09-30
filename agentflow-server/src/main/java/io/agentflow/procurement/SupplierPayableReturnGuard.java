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
    // r 是调用查询中的原占用；页面判定和后台核销扫描共用同一应付冻结规则。
    static final String BLOCKED_SQL = """
            EXISTS (SELECT 1 FROM supplier_payment_returns f WHERE f.tenant_id=r.tenant_id AND f.legal_entity_id=r.legal_entity_id
                AND f.supplier_reference=r.supplier_reference AND f.payable_reference=r.payable_reference AND f.review_required=TRUE)
            OR EXISTS (SELECT 1 FROM supplier_payment_operation b
                JOIN supplier_payment_authorization a ON a.tenant_id=b.tenant_id AND a.id=b.id
                JOIN procurement_payable_reservation prior ON prior.tenant_id=a.tenant_id AND prior.id=a.reservation_id
                WHERE b.tenant_id=r.tenant_id AND prior.legal_entity_id=r.legal_entity_id
                    AND prior.supplier_reference=r.supplier_reference AND prior.payable_reference=r.payable_reference
                    AND (prior.settled_at IS NOT NULL OR prior.adjusted_at IS NOT NULL)
                    AND (b.status NOT IN ('SUCCEEDED','REVERSED') OR b.status='REVERSED' AND NOT EXISTS (
                        SELECT 1 FROM supplier_payment_returns f JOIN supplier_adjustment_completion c
                            ON c.tenant_id=f.tenant_id AND c.operation_id=f.accounting_id AND c.operation_version=f.accounting_version
                        WHERE f.tenant_id=b.tenant_id AND f.payment_id=b.id AND c.bank_status='REVERSED')))
            OR EXISTS (SELECT 1 FROM supplier_payable_adjustment_operation adjustment
                JOIN procurement_payable_reservation prior ON prior.tenant_id=adjustment.tenant_id AND prior.id=adjustment.reservation_id
                WHERE adjustment.tenant_id=r.tenant_id AND prior.legal_entity_id=r.legal_entity_id
                    AND prior.supplier_reference=r.supplier_reference AND prior.payable_reference=r.payable_reference
                    AND adjustment.completed_version IS NOT NULL AND adjustment.status<>'ADJUSTED')
            OR EXISTS (SELECT 1 FROM supplier_payable_settlement_operation settlement
                JOIN procurement_payable_reservation prior ON prior.tenant_id=settlement.tenant_id AND prior.id=settlement.reservation_id
                WHERE settlement.tenant_id=r.tenant_id AND prior.legal_entity_id=r.legal_entity_id
                    AND prior.supplier_reference=r.supplier_reference AND prior.payable_reference=r.payable_reference
                    AND (prior.settled_at IS NOT NULL OR prior.adjusted_at IS NOT NULL)
                    AND settlement.active_payment_id=settlement.payment_id AND settlement.status<>'SETTLED')
            """;
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
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM procurement_payable_reservation r
                WHERE r.tenant_id=? AND r.legal_entity_id=? AND r.supplier_reference=? AND r.payable_reference=? AND (%s)
                """.formatted(BLOCKED_SQL), Integer.class, identity) > 0;
    }
}
