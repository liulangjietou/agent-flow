package io.agentflow.procurement;

import io.agentflow.finance.FinanceResult;

/**
 * ERP 原应付结算独立于银行付款；按原银行付款防重，原子消费原预留并生成一次结算凭据。
 * @author owlzhangfq@gmail.com
 */
public interface SupplierPayableSettlementPort {
    /** 新发送先核验本次复查，外部按结算号及摘要去重，并限制同一银行付款只能成功核销一次并原子核验预留、银行回单和指定期间。 */
    FinanceResult<SupplierPayableSettlementObservation> settle(SupplierPayableSettlementCommand command, SupplierPayableSettlementEvidence evidence);
    /** 只查询原号和摘要，不重新发送付款或结算，原授权及期间到期也不能停止找回事实。 */
    FinanceResult<SupplierPayableSettlementObservation> query(SupplierPayableSettlementCommand command);
}
