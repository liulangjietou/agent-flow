package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseBudgetRetentionRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseBudgetRetentionRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("retentionDays") Integer retentionDays,
            @Param("expiresAt") Timestamp expiresAt,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("stoppedStatus") String stoppedStatus,
            @Param("retainedAt") Timestamp retainedAt);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("status") String status,
            @Param("releaseOperationId") String releaseOperationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("applicationId") String applicationId,
            @Param("stoppedStatus") String stoppedStatus,
            @Param("retainedAt") Timestamp retainedAt,
            @Param("retentionDays") Integer retentionDays,
            @Param("expiresAt") Timestamp expiresAt,
            @Param("expectedVersion") Long expectedVersion);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 执行 candidates 的条件查询。 */
    List<SqlRow> candidatesQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
