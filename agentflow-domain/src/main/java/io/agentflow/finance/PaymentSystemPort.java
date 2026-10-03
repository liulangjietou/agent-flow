package io.agentflow.finance;

/**
 * 借款及报销共用付款边界，应用层必须先持久化授权并核对真实审批、凭证和账户事实。
 * @author owlzhangfq@gmail.com
 */
public interface PaymentSystemPort {
    /** 固定授权号发送一次，不自动重试；超时或无有效回执不代表支付失败或成功。 */
    FinanceResult<PaymentObservation> execute(String targetDigest, PaymentCommand command);

    /** 查询原授权的权威事实，过期、崩溃或响应丢失后也可查询，不隐式重发付款。 */
    FinanceResult<PaymentObservation> query(String targetDigest, PaymentCommand command);
}
