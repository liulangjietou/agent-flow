package io.agentflow.procurement;


import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.procurement.mapper.SupplierPayableReturnGuardMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同一原应付的回款疑点约束全部后续申请，历史银行行提供短事务内的共同锁。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class SupplierPayableReturnGuard {
    // r 是调用查询中的原占用；页面判定和后台核销扫描共用同一应付冻结规则。
    private final SupplierPayableReturnGuardMapper sqlMapper;

    /** 只关联持久的原应付身份，不依赖当前账户目录或外部网络。 */
    public SupplierPayableReturnGuard(SupplierPayableReturnGuardMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    /** 申请锁后按固定顺序锁定该应付的已有银行行，资金核对和新发送共享同一顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String tenant, ProcurementPaymentContent content) {
        sqlMapper.lock(
                tenant,
                tenant,
                content.legalEntityId().toString(),
                content.supplierReference(),
                content.payableReference());
    }

    /** 新占用、财务授权及外部发送必须先处理同一应付已知的退回疑点。 */
    public void requireClear(String tenant, ProcurementPaymentContent content) {
        if (blocked(tenant, content)) throw new DomainException("SUPPLIER_PAYMENT_RETURN_REVIEW_REQUIRED", "Original supplier payable has unresolved received-funds or bank evidence");
    }

    /** 只读投影与本地完成共用判定；原来已完成的凭据不会被回退。 */
    public boolean blocked(String tenant, ProcurementPaymentContent content) {
        var identity =
                new Object[] {
                    tenant,
                    content.legalEntityId().toString(),
                    content.supplierReference(),
                    content.payableReference()
                };
        return SqlRows.single(sqlMapper.blockedQuery(identity)) > 0;
    }
}
