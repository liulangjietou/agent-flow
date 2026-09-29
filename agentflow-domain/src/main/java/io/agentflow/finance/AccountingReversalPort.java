package io.agentflow.finance;

/**
 * 实际创建独立冲销与查询原命令的外部端口，区别于只读核验已有反向凭证。
 * @author owlzhangfq@gmail.com
 */
public interface AccountingReversalPort {
    /** ERP 必须按原编号与摘要幂等，并原子检查原件版本、开放期间和完整反向分录。 */
    FinanceResult<VoucherReversalObservation> post(String targetDigest, VoucherReversalCommand command);
    /** 结果未知时只查询原命令，不用新编号重新发送。 */
    FinanceResult<VoucherReversalObservation> query(String targetDigest, VoucherReversalCommand command);
}
