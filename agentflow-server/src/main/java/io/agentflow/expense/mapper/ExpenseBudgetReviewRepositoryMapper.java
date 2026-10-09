package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseBudgetReviewRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseBudgetReviewRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("roundNo") Integer roundNo,
            @Param("submittedFinancialVersion") Long submittedFinancialVersion,
            @Param("precheckId") String precheckId,
            @Param("originalOperationId") String originalOperationId,
            @Param("targetDigest") String targetDigest,
            @Param("budgetNodeId") String budgetNodeId,
            @Param("policy") String policy,
            @Param("stateJson") String stateJson,
            @Param("stateJson2") String stateJson2,
            @Param("submittedAt") Timestamp submittedAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("updatedAt2") Timestamp updatedAt2,
            @Param("expectedTenantId") String expectedTenantId,
            @Param("id") String id,
            @Param("expectedRoundNo") Integer expectedRoundNo);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("authorizedOperationId") String authorizedOperationId,
            @Param("decision") String decision,
            @Param("automatic") String automatic,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextCheckAt") Timestamp nextCheckAt,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("report") String report,
            @Param("round") Integer round);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextCheckAt") Timestamp nextCheckAt);

    /** 更新 reschedule 所需的持久化事实。 */
    int reschedule(
            @Param("nextCheckAt") Timestamp nextCheckAt,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo);

    /** 读取 requireSources 所需的持久化事实。 */
    List<SqlRow> requireSources(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("submittedFinancialVersion") Long submittedFinancialVersion);

    /** 读取 requireSources 所需的持久化事实。 */
    List<String> requireSources2(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);

    /** 读取 audit 所需的持久化事实。 */
    List<SqlRow> audit(
            @Param("tenantId") String tenantId,
            @Param("auditId") String auditId,
            @Param("taskId") String taskId,
            @Param("applicationId") String applicationId,
            @Param("actor") String actor,
            @Param("action") String action);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
