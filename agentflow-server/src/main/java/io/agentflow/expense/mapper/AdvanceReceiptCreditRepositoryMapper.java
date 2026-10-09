package io.agentflow.expense.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * JdbcAdvanceReceiptCreditRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AdvanceReceiptCreditRepositoryMapper {
    /** 新增 insert 所需的持久化事实。 */
    int insert(
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") String legalEntityId,
            @Param("advanceId") String advanceId,
            @Param("channel") String channel,
            @Param("transactionReference") String transactionReference,
            @Param("voucherReference") String voucherReference,
            @Param("entryReference") String entryReference,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("repaymentId") String repaymentId,
            @Param("disbursementResolutionId") String disbursementResolutionId);
}
