package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseSplitRoutingRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseSplitRoutingRepositoryMapper {
    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("roundNo") Integer roundNo,
            @Param("ruleVersion") Integer ruleVersion,
            @Param("mode") String mode,
            @Param("submittedAt") Timestamp submittedAt,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("financialVersion") Long financialVersion,
            @Param("applicationVersion") Long applicationVersion,
            @Param("currentRoundNo") Integer currentRoundNo,
            @Param("status") String status,
            @Param("roundNo13") Integer roundNo13,
            @Param("definitionId") String definitionId,
            @Param("processKey") String processKey,
            @Param("definitionVersion") Long definitionVersion);

    /** 新增 save 所需的持久化事实。 */
    int save2(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("ordinal") Integer ordinal,
            @Param("sourceReportId") String sourceReportId,
            @Param("sourceApplicationId") String sourceApplicationId,
            @Param("sourceApplicationVersion") Long sourceApplicationVersion,
            @Param("sourceFinancialVersion") Long sourceFinancialVersion,
            @Param("sourceRoundNo") Integer sourceRoundNo,
            @Param("documentJson") String documentJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo);

    /** 读取 findByApplication 所需的持久化事实。 */
    List<SqlRow> findByApplication(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);

    /** 读取 restore 所需的持久化事实。 */
    List<SqlRow> restore(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo);

    /** 读取 candidateRows 所需的数据库事实。 */
    void candidateRows(
            @Param("parameters") Object[] parameters,
            org.apache.ibatis.session.ResultHandler<SqlRow> handler);
}
