package io.agentflow.procurement;

import io.agentflow.finance.FinanceResult;

/**
 * 原 ERP 应付预留独立于本地申请占用，按原版本原子检查未付可用额并持久去重。
 * @author owlzhangfq@gmail.com
 */
public interface SupplierPayableHoldPort {
    /** 同一授权号和完整摘要至多产生一个预留，拒绝版本冲突，不扩大金额或重新挂账。 */
    FinanceResult<SupplierPayableHoldObservation> reserve(SupplierPayableHoldCommand command);

    /** 在原目标只读查询同一命令；NOT_FOUND 必须是权威查询，不得在查询中创建或释放预留。 */
    FinanceResult<SupplierPayableHoldObservation> query(SupplierPayableHoldCommand command);
}
