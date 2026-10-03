package io.agentflow.procurement;

import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentObservation;

/**
 * 独立供应商银行付款协议；不得通过员工报销命令重新挂账或消耗采购预算。
 * @author owlzhangfq@gmail.com
 */
public interface SupplierPaymentPort {
    /** 按固定原授权编号提交不可变指令，外部原子核对原预留、两端账户及精确金额。 */
    FinanceResult<PaymentObservation> execute(SupplierPaymentCommand command);

    /** 只查原授权编号及命令摘要，授权到期后也不得换编号或重发未知交易。 */
    FinanceResult<PaymentObservation> query(SupplierPaymentCommand command);
}
