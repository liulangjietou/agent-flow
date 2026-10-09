package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSupplierPaymentOperationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierPaymentOperationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("executionRequestId") String executionRequestId,
            @Param("executionRequestVersion") Long executionRequestVersion,
            @Param("holdVersion") Long holdVersion,
            @Param("commandJson") String commandJson,
            @Param("commandDigest") String commandDigest,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt);

    /** 读取 resolve 所需的持久化事实。 */
    List<String> resolve(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId);

    /** 新增 resolve 所需的持久化事实。 */
    int resolve2(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("paymentId") String paymentId,
            @Param("disputedVersion") Long disputedVersion,
            @Param("resolvedVersion") Long resolvedVersion,
            @Param("outcome") String outcome,
            @Param("resolvedBy") String resolvedBy,
            @Param("observedAt") Timestamp observedAt,
            @Param("resolvedAt") Timestamp resolvedAt,
            @Param("stateJson") String stateJson);

    /** 读取 latestResolution 所需的持久化事实。 */
    List<SqlRow> latestResolution(@Param("tenant") String tenant, @Param("id") String id);

    /** 更新 persist 所需的持久化事实。 */
    int persist(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("dispatches") Integer dispatches,
            @Param("highestRevision") Long highestRevision,
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

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 firstSuccessfulRevision 所需的持久化事实。 */
    List<SqlRow> firstSuccessfulRevision(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("leaseUntil") Timestamp leaseUntil);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 读取 resolutionHistory 的完整历史修订。 */
    List<SqlRow> resolutionHistoryRows(@Param("parameters") Object[] parameters);
}
