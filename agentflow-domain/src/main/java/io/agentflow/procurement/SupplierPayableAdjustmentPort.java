package io.agentflow.procurement;

import io.agentflow.finance.FinanceResult;

/**
 * 对同一原付款及各笔回款实施原子账务调整，ERP 须独立约束每笔入款不可重复记账。
 * @author owlzhangfq@gmail.com
 */
public interface SupplierPayableAdjustmentPort {
    /** 新发送重新核对原件；未核销时原子消费原预留、确认原付款并冲回实际退回部分，不重付、不重记采购预算。 */
    FinanceResult<SupplierPayableAdjustmentObservation> adjust(SupplierPayableAdjustmentCommand command, SupplierPayableAdjustmentEvidence evidence);
    /** 结果未知时只向原目标查询原号及摘要，不因窗口过期改期或再发命令。 */
    FinanceResult<SupplierPayableAdjustmentObservation> query(SupplierPayableAdjustmentCommand command);
}
