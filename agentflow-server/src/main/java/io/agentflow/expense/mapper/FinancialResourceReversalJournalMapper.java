package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * FinancialResourceReversalJournal 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface FinancialResourceReversalJournalMapper {
    /** 读取 prepare 所需的持久化事实。 */
    List<SqlRow> prepare(
            @Param("tenantId") String tenantId, @Param("adjustmentId") String adjustmentId);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("sourceLine") Integer sourceLine,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("reportLine") Integer reportLine,
            @Param("adjustmentId") String adjustmentId,
            @Param("beforeVersion") Long beforeVersion,
            @Param("afterVersion") Long afterVersion,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("reversedAt") Timestamp reversedAt);

    /** 读取 requireOriginalUse 所需的持久化事实。 */
    List<Integer> requireOriginalUse(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("canonical") String canonical,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("lineNo") Integer lineNo);

    /** 读取 requireOriginalUse 所需的持久化事实。 */
    List<Integer> requireOriginalUse2(
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("id") String id,
            @Param("sourceLine") Integer sourceLine,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("lineNo") Integer lineNo,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency);
}
