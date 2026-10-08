package io.agentflow.budget.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcBudgetAdjustmentOperationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface BudgetAdjustmentOperationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("requestId") String requestId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("requestVersion") Long requestVersion,
            @Param("authorizedBy") String authorizedBy,
            @Param("reviewId") String reviewId,
            @Param("reviewVersion") Long reviewVersion,
            @Param("commandJson") String commandJson,
            @Param("commandDigest") String commandDigest,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("activeRequestId") String activeRequestId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("commandJson") String commandJson,
            @Param("digest") String digest);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 activeForRequest 所需的持久化事实。 */
    List<SqlRow> activeForRequest(
            @Param("tenant") String tenant, @Param("requestId") String requestId);

    /** 读取 latestForRequest 所需的持久化事实。 */
    List<SqlRow> latestForRequest(
            @Param("tenant") String tenant, @Param("requestId") String requestId);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 findProcessInstance 所需的持久化事实。 */
    List<SqlRow> findProcessInstance(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);

    /** 新增 retire 所需的持久化事实。 */
    int retire(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("operationVersion") Long operationVersion,
            @Param("stateJson") String stateJson);

    /** 更新 retire 所需的持久化事实。 */
    int retire2(
            @Param("operationVersion") Long operationVersion,
            @Param("tenant") String tenant,
            @Param("operationId") String operationId,
            @Param("version") Long version);

    /** 读取 retirement 所需的持久化事实。 */
    List<SqlRow> retirement(@Param("tenant") String tenant, @Param("id") String id);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 执行 list 的条件查询。 */
    List<SqlRow> listQuery(
            @Param("firstPage") boolean firstPage, @Param("parameters") Object[] parameters);

    /** 执行 list 的条件查询。 */
    List<SqlRow> listQuery2(
            @Param("firstPage") boolean firstPage, @Param("parameters") Object[] parameters);
}
