package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseRiskRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseRiskRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("id") String id,
            @Param("requestedBy") String requestedBy,
            @Param("taskId") String taskId,
            @Param("kind") String kind,
            @Param("value5") String value5,
            @Param("stateJson") String stateJson,
            @Param("stateJson2") String stateJson2,
            @Param("createdAt") Timestamp createdAt,
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("applicationVersion") Object applicationVersion,
            @Param("financialVersion") Object financialVersion,
            @Param("roundNo") Integer roundNo);

    /** 新增 create 所需的持久化事实。 */
    int create2(
            @Param("id") String id,
            @Param("ordinal") Object ordinal,
            @Param("roundNo") Integer roundNo,
            @Param("snapshotDigest") Object snapshotDigest,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("applicationVersion") Object applicationVersion,
            @Param("financialVersion") Object financialVersion);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("status") String status,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("reportId") String reportId,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("previous") String previous,
            @Param("contextJson") String contextJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 lock 所需的持久化事实。 */
    List<String> lock(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil, @Param("BATCH_SIZE") Integer BATCH_SIZE);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page(
            @Param("tenant") String tenant,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("size") Integer size,
            @Param("offset") Long offset);

    /** 读取 page 所需的持久化事实。 */
    List<Long> page2(
            @Param("tenant") String tenant,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo);

    /** 读取 requireDocuments 所需的持久化事实。 */
    List<SqlRow> requireDocuments(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("runId") String runId,
            @Param("runVersion") Long runVersion,
            @Param("status") String status,
            @Param("stateJson") String stateJson);
}
