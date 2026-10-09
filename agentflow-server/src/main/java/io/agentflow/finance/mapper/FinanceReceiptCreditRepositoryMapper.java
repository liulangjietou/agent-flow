package io.agentflow.finance.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * JdbcFinanceReceiptCreditRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface FinanceReceiptCreditRepositoryMapper {
    /** 新增 record 所需的持久化事实。 */
    int record(
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") String legalEntityId,
            @Param("businessId") String businessId,
            @Param("channel") String channel,
            @Param("transactionReference") String transactionReference,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("supplierRegistrationId") String supplierRegistrationId);

    /** 更新 account 所需的持久化事实。 */
    int account(
            @Param("voucherReference") Object voucherReference,
            @Param("entryReference") Object entryReference,
            @Param("id") String id,
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") String legalEntityId,
            @Param("requestId") String requestId,
            @Param("transactionReference") String transactionReference,
            @Param("registrationId") String registrationId,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency);

    /** 新增 insert 所需的持久化事实。 */
    int insert(
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") String legalEntityId,
            @Param("businessId") String businessId,
            @Param("channel") String channel,
            @Param("transactionReference") String transactionReference,
            @Param("voucherReference") String voucherReference,
            @Param("entryReference") String entryReference,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("repaymentId") String repaymentId,
            @Param("disbursementResolutionId") String disbursementResolutionId,
            @Param("expenseRegistrationId") String expenseRegistrationId);
}
