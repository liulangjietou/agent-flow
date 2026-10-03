package io.agentflow.finance;

/**
 * 凭证命令和权威原交易查询，实际调用在数据库事务之外执行。
 * @author owlzhangfq@gmail.com
 */
public interface AccountingVoucherPort {
    /** 仅使用持久化原编号推送原内容，调用方先核对已批准业务与结算权限。 */
    FinanceResult<VoucherObservation> post(String targetDigest, VoucherCommand command);
    /** 结果未知先查询原操作，不能换编号重新推送。 */
    FinanceResult<VoucherObservation> query(String targetDigest, VoucherCommand command);
}
