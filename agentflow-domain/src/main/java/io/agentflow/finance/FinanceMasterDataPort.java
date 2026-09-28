package io.agentflow.finance;

/**
 * 只读获取同租户、当前员工允许使用的财务主数据。
 * @author owlzhangfq@gmail.com
 */
public interface FinanceMasterDataPort {
    /** 不返回虚构的默认法人、币种或成本中心。 */
    FinanceResult<FinanceCatalog> catalog(String tenantId, String employeeId);
}
