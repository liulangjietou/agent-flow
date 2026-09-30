package io.agentflow.procurement;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 调整沿用原采购轮次原文权限及独立财务规则，调整编号不能替换权限关联。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentAccess {
    private final CurrentActor actors;
    private final SupplierSettlementAccess payments;
    private final JdbcSupplierPayableAdjustmentRepository adjustments;

    /** 复用已经核对角色、法人任职、申请人与出纳分离的采购权限。 */
    public SupplierAdjustmentAccess(CurrentActor actors, SupplierSettlementAccess payments, JdbcSupplierPayableAdjustmentRepository adjustments) {
        this.actors = actors; this.payments = payments; this.adjustments = adjustments;
    }

    /** 管理员读取也必须满足原轮次敏感字段权限。 */
    public SupplierSettlementAccess.Context read(UUID paymentId) { return payments.read(paymentId); }

    /** 缓存回放之前和写入加锁之后均核对当前独立财务权限。 */
    public SupplierSettlementAccess.Context requireFinance(UUID paymentId) { return payments.requireFinance(paymentId); }

    /** 按当前租户找到真实调整，再验证它所属的原付款与审批轮次。 */
    public SupplierPayableAdjustmentOperation requireAdjustment(UUID adjustmentId) {
        var value = adjustments.find(actors.actor().tenantId(), adjustmentId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Original supplier adjustment is unavailable in the current scope"));
        requireFinance(value.command().source().returns().request().command().id()); return value;
    }
}
