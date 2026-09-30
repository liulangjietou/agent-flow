package io.agentflow.finance;

/**
 * 已核销报销的独立挂账差额过账，原凭证保持有效且不能被此接口改写。
 * @author owlzhangfq@gmail.com
 */
public interface ExpenseAccrualReductionPort {
    /** ERP 原子核对原件、累计版本、当前余额和开放期间，再保存新凭证及幂等结果。 */
    FinanceResult<ExpenseAccrualReductionObservation> post(String targetDigest, ExpenseAccrualReductionCommand command);
    /** 未知结果只读原号，不能把一次差额重建为新的完整冲销。 */
    FinanceResult<ExpenseAccrualReductionObservation> query(String targetDigest, ExpenseAccrualReductionCommand command);
}
