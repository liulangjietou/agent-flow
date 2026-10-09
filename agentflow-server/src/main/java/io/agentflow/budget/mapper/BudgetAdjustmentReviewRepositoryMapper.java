package io.agentflow.budget.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcBudgetAdjustmentReviewRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface BudgetAdjustmentReviewRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("requestId") String requestId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("requestVersion") Long requestVersion,
            @Param("requestedBy") String requestedBy,
            @Param("attemptNo") Long attemptNo,
            @Param("requestedAt") Timestamp requestedAt,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("activeRequestId") String activeRequestId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("startedAt") Timestamp startedAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("checkedAt") Timestamp checkedAt,
            @Param("active") String active,
            @Param("consumedOperationId") String consumedOperationId,
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 latestAttempt 所需的持久化事实。 */
    List<Long> latestAttempt(
            @Param("tenant") String tenant,
            @Param("requestId") String requestId,
            @Param("actor") String actor);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(
            @Param("tenant") String tenant,
            @Param("requestId") String requestId,
            @Param("actor") String actor);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 findProcessInstance 所需的持久化事实。 */
    List<SqlRow> findProcessInstance(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);

    /** 读取 consumedCommand 所需的持久化事实。 */
    List<SqlRow> consumedCommand(
            @Param("tenantId") String tenantId,
            @Param("consumedOperationId") String consumedOperationId,
            @Param("id") String id);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reviewId") String reviewId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
