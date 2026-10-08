package io.agentflow.expense.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * FinancialResourceReductionJournal 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface FinancialResourceReductionJournalMapper {
    /** 读取 requireEffect 所需的持久化事实。 */
    List<Integer> requireEffect(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("canonical") String canonical,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("lineNo") Object lineNo);

    /** 读取 requireEffect 所需的持久化事实。 */
    List<Integer> requireEffect2(
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("id") String id,
            @Param("sourceLine") Object sourceLine,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("lineNo") Object lineNo,
            @Param("amount") Object amount,
            @Param("currency") Object currency);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("adjustmentId") String adjustmentId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("sourceLine") Integer sourceLine,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("reportLine") Integer reportLine,
            @Param("beforeVersion") Long beforeVersion,
            @Param("afterVersion") Long afterVersion,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("adjustedAt") Timestamp adjustedAt);
}
