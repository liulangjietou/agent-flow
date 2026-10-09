package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseProjectApprovalRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseProjectApprovalRepositoryMapper {
    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("roundNo") Integer roundNo,
            @Param("ruleVersion") Integer ruleVersion,
            @Param("nodeId") String nodeId,
            @Param("catalogVersion") String catalogVersion,
            @Param("hasProjects") Boolean hasProjects,
            @Param("submittedAt") Timestamp submittedAt,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("financialVersion") Long financialVersion,
            @Param("applicationVersion") Long applicationVersion,
            @Param("currentRoundNo") Integer currentRoundNo,
            @Param("roundNo15") Integer roundNo15,
            @Param("definitionId") String definitionId,
            @Param("processKey") String processKey,
            @Param("definitionVersion") Long definitionVersion,
            @Param("id") String id,
            @Param("precheckVersion") Long precheckVersion,
            @Param("expectedApplicationVersion") Long expectedApplicationVersion,
            @Param("expectedFinancialVersion") Long expectedFinancialVersion);

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
}
